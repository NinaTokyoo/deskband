package com.dsh.deskpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets

/**
 * 桌宠前台服务。
 *
 * 窗口是**整屏大小**的透明悬浮窗，宠物画在窗口内部。
 * 这样做是因为边框状态下人物要旋转 90°，紧贴窗口的窗口尺寸装不下旋转后的图。
 * 触摸由 [PetView] 自己判定是否落在宠物身上，点在外面会返回 false 让触摸透下去。
 *
 * 按用户要求：悬浮时**没有任何菜单**，只有单击和拖动。
 * 所有设置改动只能在桌宠 App 页面里做。
 */
class PetService : Service(), PetView.Host {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sprite: PetSprite? = null
    private var petView: PetView? = null

    /**
     * 呼出/收起的世代号。
     *
     * 收起动画是异步的；动画期间用户又点呼出时，必须让那次收起的
     * 完成回调作废，否则它会把刚呼出的人物删掉。
     */
    private var epoch = 0
    private var petParams: WindowManager.LayoutParams? = null

    private var screenOn = true
    private var saveScheduled = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    petView?.setPaused(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    petView?.setPaused(false)
                }
            }
        }
    }

    private val saveRunnable = Runnable {
        saveScheduled = false
        savePosition()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        startForegroundCompat()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 先滑出屏幕、滑完再停服务。
                // 不能先 stopSelf()：服务一销毁视图就被移除，动画根本没机会播。
                slideOutThenStop()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadPet()
                return START_STICKY
            }
            ACTION_RESET -> {
                resetPet()
                return START_STICKY
            }
            ACTION_NOTIFY -> {
                refreshNotificationOnly()
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            prefs.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (petView == null) {
            showPet(animateIn = true)
        } else {
            // 宠物已经在（可能正在播收起动画）。这是**一次新的呼出请求**：
            //  1) 作废在途的收起回调 —— 否则它随后会 removePet()+stopSelf()，
            //     把人物删掉（用户反馈"连续点击呼出收起人物会消失"）。
            //  2) 如果它正滑出去，原路滑回来；否则**兜底重新吸附回屏幕内**。
            //
            // 第 2 条的 else 分支很关键：收起动画已经播完、但视图因为时序原因
            // 还没被移除时，锚点停在**屏幕外**，此时 reverseSlide() 会因为
            // sliding==false 直接返回，人物就永远看不见了
            // （用户反馈"呼出→收起→再呼出，人物消失"）。
            // applySnap() 会按当前状态重新算一个合法的贴边位置，直接拉回来。
            epoch++
            prefs.running = true
            val v = petView
            if (v != null) {
                if (v.isSliding()) {
                    v.reverseSlide()
                } else {
                    v.applySnap(v.state)
                    v.syncWindow()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(saveRunnable)
        removePet()
        sprite?.evict()
        sprite = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // 忽略
        }
        prefs.running = false
        alive = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // 忽略
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    /**
     * 通知标题：「<当前形象名> 正在陪伴你」。
     *
     * 形象名取 sprite 的 displayName（pet.json 里的 name），
     * 还没加载出 sprite 时退回通用文案。
     *
     * 形象名那一段单独上强调色（与界面里的"收起 山田凉"保持一致）。
     * 通知标题支持 Spannable，颜色通常会被系统渲染出来；
     * 万一某个 ROM 不认，也只是退化成普通文字，不影响可读性。
     */
    private fun notificationTitle(): CharSequence {
        val name = sprite?.displayName
        if (name.isNullOrBlank()) return getString(R.string.notif_title_fallback)
        val text = getString(R.string.notif_title_fmt, name)
        val sp = android.text.SpannableString(text)
        val i = text.indexOf(name)
        if (i >= 0) {
            sp.setSpan(
                android.text.style.ForegroundColorSpan(prefs.accentColor),
                i, i + name.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    /**
     * 通知正文：优先用用户自定义内容，为空则回落到默认文案。
     *
     * 再 take 一次上限是防御性的 —— 万一历史存档里有超长串。
     */
    private fun notificationText(): String {
        // 已收起（服务仍在跑，只是没有显示宠物）时给一句明确的提示，
        // 否则常驻通知会一直说"正在陪着你"，与实际状态不符。
        if (!prefs.running) return getString(R.string.notif_text_collapsed)
        val custom = prefs.notifyText.trim()
        return if (custom.isEmpty()) getString(R.string.notif_text)
        else custom.take(Prefs.NOTIFY_TEXT_MAX)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet_notify)
            .setContentTitle(notificationTitle())
            .setContentText(notificationText())
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 23) {
            builder.setColor(0xFF2AA8E0.toInt())
        }
        try {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pet_notify),
                    getString(R.string.action_stop),
                    stopIntent
                ).build()
            )
        } catch (e: Exception) {
            // 忽略
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun showPet(animateIn: Boolean = false) {
        // 作废所有在途的收起动画回调（见 slideOutThenStop 的说明）
        epoch++
        val s = PetSprite.load(assets, prefs.skin)
            ?: PetSprite.load(assets, PetSprite.FALLBACK)
            ?: PetSprite.listSkins(assets).firstOrNull()?.let { PetSprite.load(assets, it) }
            ?: run {
                stopSelf()
                return
            }
        sprite = s
        // 通知标题里要带形象名，而 onCreate 里第一次 startForeground 时
        // sprite 还是 null（会退回默认文案）。这里加载完立刻重发一次通知。
        runCatching { startForegroundCompat() }

        val b = screenBounds()
        val view = PetView(this)
        view.host = this
        view.sprite = s
        view.setSizeFraction(prefs.sizeFraction)

        // 窗口**只有宠物那么大**（不是整屏）。
        //
        // ⚠️ 不要改成整屏窗口！曾经用整屏 + FLAG_NOT_TOUCH_MODAL，
        // 而那个 flag 的语义是"本窗口之外的触摸由我自己处理"，
        // 结果开启桌宠后整个屏幕都点不动。
        //
        // 现在窗口贴合宠物外接矩形，窗口之外的触摸由 Android 直接派发给桌面/应用，
        // 天然不遮挡；窗口内部再由 PetView 做逐像素 alpha 判定。
        val zero = PetView.windowRectFor(s, prefs.snapState, prefs.sizeFraction, b)
        val p = WindowManager.LayoutParams(
            zero[2], zero[3],
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = zero[0]
        p.y = zero[1]

        try {
            wm.addView(view, p)
        } catch (e: Exception) {
            stopSelf()
            return
        }

        petView = view
        petParams = p

        // 恢复上次的吸附状态与位置。
        //
        // 首次使用（还没存过位置）要求出现在**右边框中点**，所以直接复用
        // resetToRightMid()：它会把状态设成 BORDER_R 并垂直居中 —— 这和
        // 界面上的「复位」是同一套语义，不会出现两处逻辑不一致。
        val st = prefs.snapState
        if (prefs.hasPosition) {
            view.restore(st, prefs.posLeft, prefs.posTop)
        } else {
            view.resetToRightMid()
        }

        // 呼出：从屏幕外滑进来。目标点就是上面刚定好的位置，
        // 所以要先抓住它，再让 slideIn 把锚点瞬移到屏幕外开始滑。
        if (animateIn) {
            val tx = view.anchorXNow()
            val ty = view.anchorYNow()
            view.slideIn(tx, ty)
            // 看门狗：动画总时长 420+150ms，留足余量后强制落位。
            // 万一帧回调没跑起来（被暂停、或窗口在屏幕外时系统不给帧），
            // 宠物就会永远停在屏幕外 —— 绝不能出现"看不见但按钮显示已呼出"。
            mainHandler.postDelayed({
                if (petView === view) view.forceSettleAt(tx, ty)
            }, 900L)
        }

        prefs.running = true
        if (!screenOn) view.setPaused(true)
    }

    /**
     * 收起：先播滑动动画，滑完再移除视图并停服务。
     *
     * 期间服务保持存活，所以动画可以正常跑完；`prefs.running` 立刻置 false，
     * 界面按钮即时变成"呼出"，不用等动画结束。
     */
    private fun slideOutThenStop() {
        prefs.running = false
        val v = petView
        if (v == null) {
            removePet()
            stopSelf()
            return
        }
        // 世代号：收起动画是异步的（420ms）。如果动画期间用户又点了呼出，
        // 新宠物已经建好，而**这个过期的回调**随后仍会 removePet()+stopSelf()，
        // 把刚呼出的人物删掉 —— 用户反馈的"连续点击呼出收起人物会消失"。
        // 用世代号让过期回调直接作废。
        // ⚠️ 必须在**开始滑出之前**保存位置。
        // 滑出动画会把锚点移到屏幕外，若那时才保存（见 removePet），
        // 存下来的就是"屏幕外坐标"；下次呼出 restore() 到这个坐标
        // 人物就看不见了 —— 而复位不受影响（它按右边框中点重算），
        // 于是表现为"完全收起后再呼出看不见人，但复位能救回来"。
        savePositionNow()
        val my = ++epoch
        v.slideOut {
            if (my != epoch) return@slideOut
            // ⚠️ onStartCommand 是**异步**的：动画只有 420ms，收起回调可能
            // 抢在 START intent 送达之前执行。所以这里不能只靠 epoch 判断，
            // 必须看 prefs.running —— 它是 Activity 在点击那一刻**同步**写好的，
            // 不受 intent 送达时机影响。
            // 若已变回 true，说明用户在这 420ms 内又点了呼出：
            // 这次收起作废，把宠物原路滑回来（不能删，否则人物消失）。
            if (prefs.running) {
                v.slideIn(v.restX(), v.restY())
                return@slideOut
            }
            // ⚠️ 只移除视图，**不再 stopSelf()**。
            //
            // 原来这里销毁服务，于是"收起 → 再呼出"要走一次
            // stopService/stopSelf 与 startForegroundService 的抢跑：
            // 真机上这两者会互相踩，服务被停掉后没起来，
            // 表现就是"先呼出、再收起、再呼出，就再也呼不出来了"。
            // 服务保活后，呼出只是"把视图加回来"，
            // 不存在任何启动竞态，也从根上避免了 Android 12+
            // 后台启动前台服务的限制。
            removePet()
            // 通知改成"已收起"状态（服务还在，仍在后台常驻）
            runCatching { startForegroundCompat() }
        }
    }

    private fun removePet() {
        val v = petView ?: return
        // ⚠️ 正在滑出时**不能**保存位置：那一刻锚点在屏幕外，
        // 存下去就成了"上次位置在屏幕外"，下次呼出 restore() 过去就看不见人。
        // 正常情况下的位置已经在 slideOutThenStop() 里提前存过了。
        if (!v.isSliding()) savePositionNow()
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // 忽略
        }
        petView = null
        petParams = null
    }

    private fun reloadPet() {
        if (petView == null) {
            showPet()
            return
        }
        if (sprite?.dir != prefs.skin) {
            removePet()
            showPet()
            return
        }
        val v = petView ?: return
        v.sprite = sprite
        v.setSizeFraction(prefs.sizeFraction)
        // 尺寸/分区都变了：重新吸附定位，并把窗口尺寸同步过去。
        // syncWindow 不能省 —— 否则宠物的新尺寸超出窗口，超出部分点不到
        // （用户实测踩过："调整大小之后明明点到并拖动但是没有反应"）。
        v.applySnap(v.state)
        if (prefs.hasPosition) {
            v.restore(v.state, prefs.posLeft, prefs.posTop)
        }
        v.syncWindow()
    }

    /**
     * 复位：把一切恢复成"出厂状态"并出现在**右边框中点**。
     *
     *   - 大小回到默认倍率（1.0）
     *   - 回弹幅度与时长回到默认倍率（1.0）
     *   - 位置：右边框，垂直居中，旋转形态
     *
     * 用户点它是为了"出问题时一键回到能看见的状态"，
     * 所以这里同时把尺寸和回弹参数也归位，而不只是挪位置。
     */
    private fun resetPet() {
        // 「保留当前设置」开启时只把人物挪回右边框中点，大小与回弹都不动；
        // 关闭时是原来的语义：一切恢复默认。
        if (!prefs.resetKeepSettings) {
            prefs.sizeScale = 1f
            prefs.bounceAmpScale = 1f
            prefs.bounceTimeScale = 1f
        }
        prefs.forgetPosition()

        if (petView == null) {
            showPet(animateIn = true)
            return
        }
        val v = petView ?: return
        // 角色不变则不用重建；变了就走重建流程
        if (sprite?.dir != prefs.skin) {
            removePet()
            showPet()
            return
        }
        v.sprite = sprite
        // 保留设置时不要重设尺寸 —— 那会把用户调好的大小冲掉
        if (!prefs.resetKeepSettings) v.setSizeFraction(prefs.sizeFraction)
        v.resetToRightMid()
    }

    /**
     * 只刷新常驻通知（用户改了通知栏自定义内容时调用）。
     *
     * 不能用 ACTION_RELOAD：那条路径会重建宠物视图、重新吸附定位，
     * 为了改一行通知文字而让桌宠跳一下是不合适的。
     */
    private fun refreshNotificationOnly() {
        if (!prefs.running) return
        runCatching { startForegroundCompat() }
    }

    private fun screenBounds(): Rect {
        val r = Rect()
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            r.set(0, 0, m.bounds.width(), m.bounds.height())
            val insets = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
            )
            r.bottom -= insets.bottom
        } else {
            val dm = resources.displayMetrics
            r.set(0, 0, dm.widthPixels, dm.heightPixels)
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) r.bottom -= resources.getDimensionPixelSize(id)
        }
        if (r.bottom <= 0) r.bottom = resources.displayMetrics.heightPixels
        return r
    }

    // ---------------------------------------------------------------- PetView.Host

    override fun bounds(): Rect = screenBounds()

    /**
     * 移动并调整悬浮窗 —— 窗口**紧贴宠物的外接矩形**。
     *
     * 这正是"不遮挡屏幕"的保证：窗口之外的触摸由系统直接派发给下层，
     * 我们连碰都碰不到，不需要任何 flag 技巧。
     */
    override fun placeWindow(x: Int, y: Int, w: Int, h: Int) {
        val v = petView ?: return
        val p = petParams ?: return
        if (p.x == x && p.y == y && p.width == w && p.height == h) return
        p.x = x
        p.y = y
        p.width = w
        p.height = h
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
            // 窗口可能已被系统移除
        }
    }

    override fun onSettled(state: Int, left: Int, top: Int) {
        prefs.snapState = state
        prefs.savePosition(left, top)
        scheduleSave()
    }

    override fun onTapped() {
        // 悬浮时点击只做视觉反馈，不打开任何界面（用户要求）
    }

    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        mainHandler.postDelayed(saveRunnable, 400)
    }

    private fun savePosition() {
        val v = petView ?: return
        prefs.snapState = v.state
        // 用**锚点反算**的足迹位置，而不是 currentLeft/currentTop。
        // 后者由当前窗口推出，动画中/后可能拿到屏幕外或中间态坐标，
        // 存下去会让下次 restore() 落到错误位置（表现为位置漂移、首次不在右边框中点）。
        prefs.savePosition(v.footLeft(), v.footTop())
    }

    private fun savePositionNow() {
        mainHandler.removeCallbacks(saveRunnable)
        saveScheduled = false
        savePosition()
    }

    companion object {
        /**
         * 服务是否真的活着。
         *
         * `prefs.running` 是**持久化**的，进程被划掉（从最近任务里清掉）时
         * 服务已经死了但那个布尔值还留着 true —— 冷启动后界面就会显示
         * "收起"、实际却没有桌宠（用户反馈的现象）。
         *
         * Activity 与 Service 在**同一个进程**里，所以用这个静态标志
         * 判断真实存活状态最可靠：onCreate 置 true、onDestroy 置 false。
         */
        @Volatile
        var alive = false
            private set

        private const val CHANNEL_ID = "deskpet"
        private const val NOTIF_ID = 1001

        const val ACTION_STOP = "com.dsh.deskpet.action.STOP"
        const val ACTION_RELOAD = "com.dsh.deskpet.action.RELOAD"
        const val ACTION_RESET = "com.dsh.deskpet.action.RESET"
        const val ACTION_NOTIFY = "com.dsh.deskpet.action.NOTIFY"

        /** App 里改了通知栏自定义内容：只刷新通知，不动桌宠位置 */
        fun refreshNotification(context: Context) {
            if (!Prefs(context).running) return
            val i = Intent(context, PetService::class.java).setAction(ACTION_NOTIFY)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        /** App 里点"复位"：恢复默认大小与回弹，并出现在右边框中点 */
        fun reset(context: Context) {
            if (!Prefs(context).running) return
            val i = Intent(context, PetService::class.java).setAction(ACTION_RESET)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        fun start(context: Context) {
            val i = Intent(context, PetService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            try {
                // ⚠️ 必须发 ACTION_STOP intent，**不能**用 context.stopService()。
                //
                // stopService() 会立刻销毁服务、直接走 onDestroy，
                // 根本不经过 onStartCommand 的 ACTION_STOP 分支 ——
                // 于是 slideOutThenStop() 永远不会被调用，收起动画也就不存在
                // （用户反馈"收回还是没有动画"）。ACTION_STOP 分支里会先播
                // 滑出动画、滑完再 stopSelf()。
                val i = Intent(context, PetService::class.java).setAction(ACTION_STOP)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        /** 大小或角色变了，通知正在跑的服务刷新 */
        fun notifyChanged(context: Context) {
            if (!Prefs(context).running) return
            val i = Intent(context, PetService::class.java).setAction(ACTION_RELOAD)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
