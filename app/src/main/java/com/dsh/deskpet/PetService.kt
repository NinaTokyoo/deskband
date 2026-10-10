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
                stopSelf()
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
        if (petView == null) showPet()
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
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // 忽略
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    /**
     * 通知标题：「<当前形象名>正在陪伴你」。
     *
     * 形象名取 sprite 的 displayName（pet.json 里的 name），
     * 还没加载出 sprite 时退回通用文案。
     */
    private fun notificationTitle(): String {
        val name = sprite?.displayName
        return if (name.isNullOrBlank()) getString(R.string.notif_title_fallback)
        else getString(R.string.notif_title_fmt, name)
    }

    /**
     * 通知正文：优先用用户自定义内容，为空则回落到默认文案。
     *
     * 再 take 一次上限是防御性的 —— 万一历史存档里有超长串。
     */
    private fun notificationText(): String {
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

    private fun showPet() {
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

        // 恢复上次的吸附状态与位置
        val st = prefs.snapState
        if (prefs.hasPosition) {
            view.restore(st, prefs.posLeft, prefs.posTop)
        } else {
            view.applySnap(st)
        }

        prefs.running = true
        if (!screenOn) view.setPaused(true)
    }

    private fun removePet() {
        val v = petView ?: return
        savePositionNow()
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
        prefs.sizeScale = 1f
        prefs.bounceAmpScale = 1f
        prefs.bounceTimeScale = 1f
        prefs.forgetPosition()

        if (petView == null) {
            showPet()
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
        v.setSizeFraction(prefs.sizeFraction)
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
        prefs.savePosition(v.currentLeft(), v.currentTop())
    }

    private fun savePositionNow() {
        mainHandler.removeCallbacks(saveRunnable)
        saveScheduled = false
        savePosition()
    }

    companion object {
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
                context.stopService(Intent(context, PetService::class.java))
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
