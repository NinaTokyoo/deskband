package com.dsh.deskpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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

    // ---------------------------------------------------------------- 思考气泡
    //
    // 气泡是**独立悬浮窗**：宠物窗口静止时收紧到"刚好包住人物"，气泡画在里面
    // 一定被裁掉；临时把宠物窗口撑大又会让每次冒泡都 resize（WM 重新布局、
    // 可能重建 Surface），观感是"闪一下"。

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleHide: Runnable? = null

    /**
     * 屏幕是否点亮。
     *
     * ⚠️ 初值必须**真读一次** `PowerManager.isInteractive`，不能写死 true：
     *    服务完全可能在息屏状态下被拉起（开机自启、sticky 重启），
     *    写死 true 会让"息屏时不该跑动画"的判断反过来，
     *    桌宠在熄屏状态下继续按帧重绘。
     */
    private var screenOn = true

    /**
     * 屏幕旋转 / 显示尺寸变化。
     *
     * ⚠️ 这个回调**必须有**，否则贴边吸附的桌宠会整只消失：
     *    窗口位置与尺寸是按当时的屏幕算的，而 `FLAG_LAYOUT_NO_LIMITS`
     *    允许窗口停在屏幕外。竖屏贴在右边框 -> 横屏之后原来的 x 已经在屏幕外，
     *    桌宠就看不见了，只有"复位"能救回来；更糟的是随后任何一次落位
     *    都会把**夹取后的新坐标**写进存档，原位置被吃掉。
     *
     * ⚠️ 这里只能 `syncWindow()` + `stayVisible()`，**绝不能用 `applySnap()`**：
     *    applySnap 会按状态重排到"标准位置"，而贴右边框的标准位置是
     *    右边框**底部** —— 那正是用户反馈过的
     *    "吸附在右边框多次快速点击呼入收起会瞬移到右下角"。
     */
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val v = petView ?: return
            if (v.parent == null || !v.isAttachedToWindow) return
            // 先按新的屏幕尺寸重算窗口，再把它拉回可见范围
            v.syncWindow()
            v.stayVisible()
        }

        // ⚠️ 用匿名 object 实现 ComponentCallbacks2 时，**两个方法都要写出来** ——
        //    Kotlin 的 object 表达式不继承接口方法的默认实现（写了才知道）。
        //    onLowMemory 在接口里已弃用，覆盖它会报 OVERRIDE_DEPRECATION
        //    （注意不是 DEPRECATION，用那个消不掉）。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }

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
        // 息屏状态下被拉起时不能当成亮屏（写死 true 会让动画在息屏时继续跑）
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
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
        // 旋转 / 折叠屏展开 / 分辨率变化：必须重算窗口并拉回可见范围，
        // 否则贴边的桌宠会跑到屏幕外整只消失（见 componentCallbacks 的说明）。
        registerComponentCallbacks(componentCallbacks)
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
            ACTION_SUMMON -> {
                // 落到下面那条"把桌宠叫出来"的公共路径（与 App 内部呼出完全同一套：
                // 窗口 attach 判据、reverseSlide/restoreRest 兜底都在那里）。
                // 这里**不 return**，让它继续往下走。
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            prefs.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        // ⚠️ 判据必须是「窗口**真的还在**」，不能只是 `petView != null`。
        //
        // petView 非空只说明我们**创建过**一个 PetView，不代表它的窗口还挂在
        // WindowManager 上。以下情况会让引用非空而窗口已消失：
        //   · 悬浮窗权限被撤销 / 被 ROM（MIUI、EMUI 等）在后台回收
        //   · 窗口在首帧之前就被系统拆掉
        //   · removeView 抛异常被 catch 吞掉，petView 置了 null 但窗口其实还在
        //     （反向不一致）
        // 一旦引用非空而窗口没了，点"呼出"会走下面的"已在场"分支：
        // reverseSlide / restoreRest / stayVisible 全都在改一个 **已 detach 的
        // View 的内部字段**，对屏幕毫无影响 —— 界面显示"收起"、
        // 屏幕上什么都没有，只有"复位"才可能救回来。
        // 这正是历史症状"点呼出没反应 / 显示已呼出但看不到桌宠"的复现通路。
        val attached = petView?.let { it.parent != null && it.isAttachedToWindow } == true
        if (!attached) {
            // 引用还在但窗口没了 —— 先彻底清干净再重建，否则残留的 PetView
            // 会一直挡着"窗口还在"的判断。
            if (petView != null) removePet()
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
                } else if (!v.restoreRest()) {
                    // 极端兜底：就停在原地（夹进屏幕保证可见）。
                    //
                    // ⚠️ 这里**绝对不能**用 applySnap()：它按状态重排到"标准位置"，
                    // 而贴右边框的标准位置是右边框**底部** —— 那正是用户反馈的
                    // "吸附在右边框多次快速点击呼入收起会瞬移到右下角"。
                    v.stayVisible()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(saveRunnable)
        try {
            unregisterComponentCallbacks(componentCallbacks)
        } catch (e: Exception) {
            // 忽略
        }
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

        // ⚠️ **已收起时标题是固定的**：「<角色名> 待呼出」。
        //
        // 用户要求："标题固定修改为 xxx 待呼出，也就是说 app 里面自定义
        // 只能自定义呼出状态下的标题。"
        // 所以自定义模板（prefs.notifyTitle）只在**呼出状态**下生效 ——
        // 否则收起后标题还写着"正在陪你"，与屏幕上一个桌宠都没有的状态自相矛盾。
        if (!prefs.running) {
            return getString(R.string.notif_title_collapsed, name)
        }

        // 标题可在 App 内自定义，用 {name} 表示角色名；拼接规则在 PetState.buildTitle
        // 里（纯函数、有单测），这里只负责把结果套上强调色。
        val t = PetState.buildTitle(
            prefs.notifyTitle, name, Prefs.NOTIFY_TITLE_DEFAULT
        )
        val sp = android.text.SpannableString(t.text)
        if (t.hlFrom >= 0) {
            sp.setSpan(
                android.text.style.ForegroundColorSpan(prefs.accentColor),
                t.hlFrom, t.hlTo,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    /**
     * 通知正文。
     *
     * 规则（用户要求）：
     *   · 已收起（服务仍在跑但没显示宠物）-> 固定提示，说明当前状态
     *   · 正文留空 -> **直接复制当前标题**（解析后的文字），与标题保持一致
     *   · 否则用自定义正文
     *
     * ⚠️ 存的可能是**模板**（含 {name}），所以这里必须解析一遍再显示。
     *    漏掉这一步的表现就是通知正文原样显示「{name} 正在陪伴你」——
     *    界面那边解析了、服务这边没解析，实测踩过（用户截图抓到的）。
     *
     * take 一次上限是防御性的 —— 万一历史存档里有超长串。
     */
    private fun notificationText(): String {
        // 已收起 -> 固定文案「点击回到应用」（用户要求）。
        // 同样地，App 内自定义的正文只作用于**呼出状态**。
        if (!prefs.running) return getString(R.string.notif_text_collapsed)

        val name = sprite?.displayName
        val custom = prefs.notifyText.trim()
        val raw = if (custom.isEmpty()) {
            // 正文留空 -> 用当前标题（模板），下一行统一解析
            prefs.notifyTitle
        } else {
            custom
        }
        return PetState
            .titleForDisplay(raw, name, Prefs.NOTIFY_TITLE_DEFAULT)
            .take(Prefs.NOTIFY_TEXT_MAX)
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
        // 按钮按状态切换：
        //   已呼出 -> 「收起」（发 ACTION_STOP，走滑出动画）
        //   已收起 -> 「呼出」（发 ACTION_SUMMON，与 App 内部呼出一致）
        //
        // ⚠️ 原来恒为「收起」+ ACTION_STOP：已收起时点它等于"再收起一次"，
        //    完全没法从通知栏把桌宠叫回来（用户截图反馈的现象）。
        //
        // requestCode 用 1 与 2 区分：两个 PendingIntent 的 action 不同，
        // 但显式给不同 requestCode 更稳妥（避免某些 ROM 上按 action 合并）。
        val running = prefs.running
        val actionIntent = PendingIntent.getService(
            this, if (running) 1 else 2,
            Intent(this, PetService::class.java)
                .setAction(if (running) ACTION_STOP else ACTION_SUMMON),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pet_notify)
            .setContentTitle(notificationTitle())
            .setContentText(notificationText())
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 23) {
            // 跟随当前角色的强调色，与界面保持一致。
            //
            // 原来硬编码 0xFF2AA8E0 —— 它既不跟 prefs.accentColor，也不是
            // AccentTheme.FALLBACK，属于"第三种真值"：换角色后通知栏的颜色
            // 与 App 里的主题色对不上。
            builder.setColor(
                if (prefs.accentColor != 0) prefs.accentColor else AccentTheme.FALLBACK
            )
        }
        try {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_pet_notify),
                    getString(
                        if (running) R.string.action_stop else R.string.notif_action_summon
                    ),
                    actionIntent
                ).build()
            )
        } catch (e: Exception) {
            // 忽略
        }

        // ⚠️ 通知栏**只留「收起 / 呼出」一个动作**。
        //
        // 曾经在它右边加过一个「锁定 / 解锁」（与 App 里那两个按钮共用 prefs.locked）：
        // AOSP 上能用，但用户实机上点了没反应 —— 那条动作用 PendingIntent 启动服务，
        // Android 12 起"后台启动服务"受限、系统会把这次启动丢掉（vivo 这类 ROM 尤其严；
        // 换成 getForegroundService 也没救回来）。用户拍板：**取消这个动作**。
        // 要锁就在 App 里点「锁定」—— 别再往通知栏加第二个动作了。
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- 悬浮窗

    /**
     * 取当前角色对应的 sprite，取不到返回 null（调用方负责收尾）。
     *
     * ⚠️ 角色没变就**复用已有的 sprite**，不要每次都重新解码。
     *
     * 收起走的只是 removePet()（只移除视图，sprite 字段与位图都还在），
     * 而呼出/收起是本 App 最高频的交互 —— 无条件重新 load 的话，每次呼出都要
     * 在主线程解码两张大图（约 11 MiB 分配 + 30~100ms）并丢弃旧位图，
     * 内存曲线呈锯齿、呼出还掉帧。
     *
     * 用 isEvicted 而不是只判空：evict() 之后派生位图已被 recycle，
     * 复用它是 "Canvas: trying to use a recycled bitmap" 崩溃。
     *
     * 抽成独立函数是为了让 [reloadPet] 在**收起状态**下也能拿到最新角色 ——
     * 通知标题里的角色名来自 sprite，收起时换角色若不同步加载，
     * 标题就会一直显示上一个角色的名字。
     */
    private fun acquireSprite(): PetSprite? {
        val s = sprite?.takeIf { !it.isEvicted && it.dir == prefs.skin }
            ?: PetSprite.load(this, prefs.skin)
            ?: PetSprite.load(this, PetSprite.FALLBACK)
            ?: PetSprite.listSkins(this).firstOrNull()?.let { PetSprite.load(this, it) }
        if (s != null) sprite = s
        return s
    }

    private fun showPet(animateIn: Boolean = false) {
        // 作废所有在途的收起动画回调（见 slideOutThenStop 的说明）
        epoch++
        // ⚠️ `prefs.running` 必须在**构建通知之前**就置为 true。
        //
        // 通知的标题与正文都按 `prefs.running` 分状态（收起 -> 「待呼出」/
        // 「点击回到应用」；呼出 -> 自定义文案），而下面那句 startForegroundCompat()
        // 在函数中部就执行了。原来置位留在函数末尾，于是通知按"已收起"构建 ——
        // 呼出之后通知栏一直显示「<角色名> 待呼出」，直到因为别的原因才刷新
        // （实测踩到：点通知栏「呼出」后 running 已回 true，通知却还是收起态文案）。
        prefs.running = true
        val s = acquireSprite()
        if (s == null) {
            stopSelf()
            return
        }
        // 通知标题里要带形象名，而 onCreate 里第一次 startForeground 时
        // sprite 还是 null（会退回默认文案）。这里加载完立刻重发一次通知。
        runCatching { startForegroundCompat() }

        val b = screenBounds()
        val view = PetView(this)
        view.host = this
        view.sprite = s
        // ⚠️ 不再往视图上下发「锁定 / 锁定互动」标志：PetView 自己按 prefs **现读**
        //    （它本来就有 Prefs 实例）。下发式写法踩过坑：标志在某条路径上没刷新，
        //    就会出现"明明关了锁定互动、点它还在跳"。
        view.setSizeFraction(prefs.sizeFraction)

        // 窗口**只有宠物那么大**（不是整屏）。
        //
        // ⚠️ 不要改成整屏窗口！曾经用整屏 + FLAG_NOT_TOUCH_MODAL，
        // 而那个 flag 的语义是"本窗口之外的触摸由我自己处理"，
        // 结果开启桌宠后整个屏幕都点不动。
        //
        // 现在窗口贴合宠物外接矩形，窗口之外的触摸由 Android 直接派发给桌面/应用，
        // 天然不遮挡；窗口内部再由 PetView 做逐像素 alpha 判定。
        // ⚠️ 有存档时按存档算初始窗口位置：窗口是 addView 那一下就定好位置的，
        // 随后 restore→placeWindow 才把它挪到正确位置 —— 中间那一帧宠物会画在
        // "状态的标准位置"上（躺侧边的标准位置是垂直居中，而贴右边框时若算成
        // 屏幕底部，就是用户反馈的"顺着右边框往下走"那一闪）。
        val saved = prefs.hasPosition
        val zero = PetView.windowRectFor(
            s, prefs.snapState, prefs.sizeFraction, b,
            if (saved) prefs.posLeft else null,
            if (saved) prefs.posTop else null
        )
        val p = WindowManager.LayoutParams(
            zero[2], zero[3],
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // 「锁定互动：淡出」（用户要求）= 半透明 + 触控穿透：
                // 挂 FLAG_NOT_TOUCHABLE，输入系统直接跳过这个窗口，点形象 =
                // 点它下面的屏幕。**flag 只能在 addView 这一刻定死**：运行中改 flag 要走
                // updateViewLayout，那会让 surface 重建、画面发白/发花（用户实机实测过）。
                // 所以状态变化时由 syncPassThrough() 重建窗口，别在这里做别的花样。
                or if (prefs.petPassesThrough) {
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    0
                },
            // 半透明**由系统给**：挂 FLAG_NOT_TOUCHABLE 的悬浮窗，实机 ROM 自己会淡化
            //（这正是 2.1.14~2.1.18 那场"关掉互动就变淡"的根源 —— 现在它成了想要的效果）。
            // ⚠️ 别自己再压一层 alpha：用户明确"不用刻意变淡，系统给的透明度就够了"。
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
        // 同进程的 App 侧要能直接叫醒这个视图的动画时钟（见 wakePetAnimation）
        liveView = view

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
            //
            // ⚠️ 必须带上**世代号**一起判断：这段超时时间里用户完全可能又点了收起/呼出。
            // 那时这次兜底会 forceSettleAt()，把**后来的收起动画**掐断
            // （forceSettleAt 会取消补间、丢弃 slideDone），
            // 于是"点了收起没反应、宠物留在屏幕上"——
            // 用户反馈："多次点击呼出收起的时候，有时候点击收起没有反应"。
            //
            // ⚠️⚠️ 超时值**必须按实际动画时长算**，不能用固定值。动画总时长是
            //     Prefs.SUMMON_MS_BASE(560ms) x summonTimeScale，而倍率上限为 5
            //     （Prefs.SUMMON_TIME_MAX）—— 最长 2800ms。原来写死 900L 时，
            //     只要"呼出回弹"的总时长调到约 1.7 倍以上，看门狗就会先触发
            //     forceSettleAt()：呼出动画被截断成"瞬间落位"，
            //     slideHasPhase2 被清掉、回弹段整个丢失（用户看到"猛地定住"）。
            val myEpoch = epoch
            val watchdogMs = (prefs.summonTotalMs * 1.2f + 150f).toLong()
            mainHandler.postDelayed({
                if (petView === view && epoch == myEpoch && view.isSliding()) {
                    view.forceSettleAt(tx, ty)
                }
            }, watchdogMs)
        }

        // 注意：`prefs.running = true` 已经在函数**开头**设过了 ——
        // 因为通知要在本函数中部就按最新状态构建（见开头那段注释），
        // 放这里就晚了。
        if (!screenOn) view.setPaused(true)
        // 叫醒动画时钟：不带入场动画地建窗口（例如「淡出」状态切换时的重建）
        // 之后，视图是静止的 —— 没有这一下，呼吸要等到用户碰它才开始。
        view.wakeAnimation()
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
        // 人物都收起来了，气泡不能自己留在屏幕上
        hideBubble()
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
        liveView = null
    }

    /**
     * 让窗口的可触性跟上「锁定互动：淡出」的状态。
     *
     * 淡出 = 形象半透明 **且** 触控上完全透明（点它等于点它下面的屏幕）：
     *   · 触控穿透只有 `FLAG_NOT_TOUCHABLE` 能做到（视图返回 false 不够，实测过）；
     *   · 而这个 flag **只能在 addView 那一刻定死** —— 运行中改 flag 要走
     *     `updateViewLayout`，那会让窗口 surface 重建、画面发白/发花（用户实测）。
     *     所以状态真的变了就**重建窗口**。
     *   · 半透明**不自己设**：挂了这个 flag 的悬浮窗由系统淡化，实机上就是用户
     *     想要的那种透明度（他自己点名"系统给的透明度就足够了，别刻意变淡"）。
     *
     * @return 是否**已经重建过窗口**（调用方要据此提前返回：新窗口已按新状态建好）
     */
    private fun syncPassThrough(): Boolean {
        petView ?: return false
        val has = petParams?.let {
            (it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        } ?: return false
        if (has == prefs.petPassesThrough) return false
        removePet()
        showPet()
        return true
    }

    private fun reloadPet() {
        // 收起状态下也要先拿到**当前角色**的 sprite，否则通知标题里的角色名
        // 还是上一个角色的（标题文案由 sprite.displayName 提供）。
        acquireSprite()

        // 通知的标题带角色名，通知本身也分呼出/收起两种文案 ——
        // 换角色时收起状态下也要刷新，所以这一步放在**收起判断之前**。
        runCatching { startForegroundCompat() }

        // ⚠️ 已收起时**什么都别做**，绝不能在这里把桌宠叫出来。
        //
        // 原来这里第一句是 `if (petView == null) { showPet(); return }`，
        // 而"收起"的收尾正是 removePet()（它把 petView 置 null）——
        // 于是"没有视图"被当成了"该把桌宠叫出来"：
        // 收起状态下拖一下「外观尺寸」滑条，桌宠就自己冒出来了，
        // 还顺带把 prefs.running 改回 true。
        // 更糟的是界面按钮不会跟着变（它只在 onResume 的 refreshUi 里更新），
        // 于是表现成"人物自己出现、但按钮还写着呼出"——
        // 用户反馈的正是这个现象。
        //
        // 收起状态下尺寸不需要作用到窗口上（压根没有窗口），
        // 下次呼出时 showPet() 会读最新的 prefs.sizeFraction，
        // 所以直接返回是安全的。
        if (!prefs.running) return

        if (petView == null) {
            // 走到这里说明 running=true 但视图不在（异常状态），补建一个
            showPet()
            return
        }
        if (sprite?.dir != prefs.skin) {
            removePet()
            showPet()
            return
        }
        // 「锁定互动：淡出」需要在窗口上挂/摘 FLAG_NOT_TOUCHABLE（半透明 + 触控穿透），
        // 而 flag 只能在 addView 那一刻定死 -> 状态变了就重建窗口，然后直接返回。
        if (syncPassThrough()) return
        val v = petView ?: return
        v.sprite = sprite
        // 叫醒动画时钟：呼吸开关之类的设置刚变过，而 ticker 在"呼吸关 + 静止"时
        // 已经彻底不排帧了 —— 不叫它，打开呼吸也要等到用户点一下才开始（用户报的 bug）。
        v.wakeAnimation()
        // 以**吸附边为基准**缩放（贴底/下角 -> 底边不动，贴侧边 -> 那一侧不动）。
        //
        // ⚠️ 就到这里为止 —— 2.1.6~2.1.9 曾在这里额外做"吸回某一侧"，结果是手机上
        //    抖动、甚至把人物甩到另一侧。用户拍板：尺寸这块照搬 2.1.3（= 基线）。
        //    基线这条"以附着边为基准缩放"本身就让贴边的宠物一直贴着，够用。
        v.setSizeFraction(prefs.sizeFraction)
        // 尺寸一变，存档里的 pos_left/pos_top **就过期了**（它们是"足迹左上角"的
        // 绝对坐标，跟尺寸绑定）。不更新的话，缩小以后下次呼出会用旧坐标摆位 ——
        // 表现就是"呼出后不在屏幕边缘、调大小也不贴边"（用户反馈的正是这个）。
        //（放大方向看不出来：旧坐标落到屏幕外，restore 的夹取会顺手把它推回边上。）
        //
        // ⚠️ 必须在**服务侧**、在视图已经落位之后写，不能在 PetView.setSizeFraction
        //    里写：showPet() 是先 setSizeFraction（锚点还是 0,0）再 restore/reset，
        //    在那里写会把存档写成 (0,0)，人物下次呼出就落在左上角（踩过）。
        scheduleSave()
        // ⚠️ 这里**不要**再调 v.restore(...)。
        //
        // 曾经是"先 setSizeFraction，再 restore(state, posLeft, posTop)"。
        // setSizeFraction 的锚定是对的（记下吸附边的屏幕位置，缩放后让那条边
        // 回到原位）；但紧接着的 restore 会用**存档里的 posLeft/posTop** 重新摆位，
        // 而那对坐标在这次改动里根本没变过 —— 等于按"足迹左上角固定"缩放，
        // 贴右边框时右缘会随着变小一路往左退。
        //
        // 实测（贴右边框，屏宽 1440）：
        //   scale 1.0   -> 窗口 right=1550
        //   scale 0.841 -> 窗口 right=1495，而存档仍是 pos=(1193,1392)
        //   scale 0.653 -> 窗口 right=1429
        // 右缘退到 1429 就是用户看到的"人物悬浮、不吸附"。
        //
        // 侧边形态（贴左右边框）纵向本来要**垂直居中**，按足迹左上角固定也保不住
        // 居中 —— 所以不恢复位置反而是更正确的行为。
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
        // ⚠️ 闸门用 `alive`（服务是否活着），**不是** `prefs.running`（桌宠是否可见）。
        //
        // 收起桌宠时服务按设计仍然常驻、通知栏也还挂着（只是内容改成"已收起"）。
        // 这时用户在 App 里换角色或改通知标题，如果这里被 `prefs.running` 挡住，
        // 通知栏的标题就一直是**旧角色名**，要等下次呼出才刷新 ——
        // 属于"改了没反应"。
        if (!alive) return
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
    override fun placeWindow(x: Int, y: Int, w: Int, h: Int): Boolean {
        val v = petView ?: return false
        val p = petParams ?: return false
        if (p.x == x && p.y == y && p.width == w && p.height == h) return true
        p.x = x
        p.y = y
        p.width = w
        p.height = h
        return try {
            wm.updateViewLayout(v, p)
            true
        } catch (e: Exception) {
            // 窗口可能已被系统移除（权限被撤、被 ROM 回收）。
            //
            // ⚠️ 这里返回 false 而不是静默忽略：PetView 据此让窗口缓存失效并重试。
            //    另外顺手做一次自愈 —— 窗口真的没了就把引用清掉，
            //    否则"petView 非空但窗口已消失"会一直骗过 onStartCommand 里的
            //    attach 判断（那正是"显示已呼出却看不到桌宠"的成因）。
            if (v.parent == null || !v.isAttachedToWindow) {
                petView = null
                petParams = null
                prefs.running = false
            }
            false
        }
    }

    override fun onSettled(state: Int, left: Int, top: Int) {
        // 动画期间视图报来的坐标不是静止位置（可能在屏幕外），直接忽略。
        // 静止位置由 slideOutThenStop / savePosition 用 restFoot* 处理。
        if (petView?.isSliding() == true) return
        prefs.snapState = state
        prefs.savePosition(left, top)
        scheduleSave()
    }

    override fun onTapped() {
        // 悬浮时点击只做视觉反馈，不打开任何界面（用户要求）
    }

    /**
     * 人物"压缩完、开始弹起"的那一刻（PetView 只在**吸附右边框 + 下半屏**时回调）。
     *
     * 气泡就在这一刻开始出现，与弹起同步 —— 用户要求："当人物压缩完开始弹起时，
     * 气泡按照上述相对位置和大小同步开始出现"。
     */
    override fun onJumpStart() = showBubble()

    /**
     * 让思考气泡出现。位置、大小、朝向**全部与人物素材同步**（用户 2026-10-16 的推广规则）：
     *
     *   · **大小**：整组 = 1.190628W × 1.184760W（记录基线的 0.99219/0.98730 各乘 1.2）；
     *   · **朝向**：与人物素材走同一条变换链 —— 镜像(x) → 上下翻转(y) → 旋转(0/∓90)；
     *   · **位置**：人物盒中心 + 素材坐标系偏移（向素材右 1.0992W 或左、素材上 0.4262W），
     *     再套同一条变换链。摆哪一侧由 [PetState.bubbleSide] 按用户规则算。
     */
    private fun showBubble() {
        val pet = petView ?: return
        val a = pet.bubbleAnchor()
        if (a.size < 11) return
        val w = a[2].toFloat()                                   // 人物正立素材宽 W = 2a
        val side = a[3]
        val mirror = a[4] == 1
        val flipV = a[5] == 1
        val rot = a[6].toFloat()

        val size = BubbleView.windowSize(w, rot)
        val bw = size[0]
        val bh = size[1]
        val off = BubbleView.screenOffset(w, side, mirror, flipV, rot)
        val b = bounds()
        var fx = Math.round(a[0] + off[0] - bw / 2f).toInt()
        var fy = Math.round(a[1] + off[1] - bh / 2f).toInt()

        // 原则（用户对右下角提的）：气泡**不能进入人物素材矩形**。
        // 相交时按"最小位移"推到矩形外侧（左右上下四个方向里位移最小的那个）。
        if (PetState.bubbleKeepOutOfPetRect(pet.state)) {
            val pl = a[7]
            val pt = a[8]
            val pr = pl + a[9]
            val pb = pt + a[10]
            if (fx < pr && fx + bw > pl && fy < pb && fy + bh > pt) {
                val cands = arrayOf(
                    Math.abs(fx - (pl - bw)) to intArrayOf(pl - bw, fy),
                    Math.abs(fx - pr) to intArrayOf(pr, fy),
                    Math.abs(fy - (pt - bh)) to intArrayOf(fx, pt - bh),
                    Math.abs(fy - pb) to intArrayOf(fx, pb)
                )
                val best = cands.minByOrNull { it.first }!!
                fx = best.second[0]
                fy = best.second[1]
            }
        }
        val x = fx.coerceIn(0, (b.width() - bw).coerceAtLeast(0))
        val y = fy.coerceIn(0, (b.height() - bh).coerceAtLeast(0))

        hideBubble()
        val view = BubbleView(this)
        // 与人物素材同一套变换；"摆素材左侧"时整组要在素材系里翻过来（尾巴才朝着人物）
        // —— 右下角例外（用户确认的 C 形态），由 PetState.bubbleArtMirror 统一决定
        view.rotationDeg = rot
        view.mirror = PetState.bubbleArtMirror(pet.state, mirror, side)
        view.flipV = flipV
        val p = WindowManager.LayoutParams(
            bw, bh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // ⚠️ 这里**不能**加 FLAG_NOT_TOUCHABLE：WMS 对"系统悬浮窗 + 不可触摸
            //    + LayoutParams.alpha > 0.80"会把整窗 alpha 强制压到 0.80
            //    （气泡描边与内部都会变半透明，深色壁纸会透上来）。
            //    代价是这个窗口会挡住它自己那一小块的触摸 —— 它只活 1.8 秒、
            //    又紧挨人物（不重叠），点人物不受影响。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.RGBA_8888
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        p.alpha = 1f
        try {
            wm.addView(view, p)
        } catch (e: Exception) {
            return
        }
        bubbleView = view
        bubbleParams = p
        view.start()

        // 布局自检：`adb logcat -s BubbleGeom` 可以量准"气泡窗口 / 人物足迹盒 / 宠物窗口"，
        // 靠截图反推误差太大（这个功能的位置关系是靠像素说话的）。
        runCatching {
            android.util.Log.i(
                "BubbleGeom",
                "bubble=($x,$y ${bw}x$bh rot=$rot side=$side mir=$mirror flipV=$flipV) " +
                    "petCenter=(${a[0]},${a[1]}) W=${a[2]} state=${pet.state} pet=" +
                    (petParams?.let { "(${it.x},${it.y} ${it.width}x${it.height})" } ?: "?")
            )
        }

        val hide = Runnable { hideBubble() }
        bubbleHide = hide
        // 时间轴走完再多留一点余量才收窗口，免得最后一帧被截掉
        mainHandler.postDelayed(hide, BubbleView.TOTAL_MS + BUBBLE_TAIL_MS)
    }

    /** 收掉气泡窗口（时间到、人物收起、服务销毁都会走这里） */
    private fun hideBubble() {
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        bubbleHide = null
        val v = bubbleView ?: return
        bubbleView = null
        bubbleParams = null
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // 窗口可能已经被系统收走，忽略
        }
    }

    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        mainHandler.postDelayed(saveRunnable, 400)
    }

    private fun savePosition() {
        val v = petView ?: return
        prefs.snapState = v.state
        // 用**静止**位置（动画中取记住的静止点），并**强制夹进屏幕**。
        //
        // 夹取是最后一道硬保证：上游已经堵了"起滑前先存"和"动画中忽略 onSettled"，
        // 但只要还有任何路径把屏幕外坐标写进去，下次 restore() 就会让人物
        // 落到屏幕外或贴到底部（用户反馈："连续快速点击几次之后就跳到右边框底部"）。
        // 保证存进 prefs 的坐标永远在屏幕内，这类问题就不可能再出现。
        val b = screenBounds()
        val l = v.restFootLeft().coerceIn(0, b.width().coerceAtLeast(0))
        val t = v.restFootTop().coerceIn(0, b.height().coerceAtLeast(0))
        prefs.savePosition(l, t)
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

        /** 气泡时间轴走完后再多留一点余量才收窗口（毫秒） */
        private const val BUBBLE_TAIL_MS = 80L

        /**
         * 当前正在显示的宠物视图（收起时为 null；[showPet]/[removePet] 维护）。
         *
         * ⚠️ 用途只有一个：让 App 侧**叫醒动画时钟**（见 [wakePetAnimation]）。
         *    为什么不走 Intent：没必要（Activity 与 Service 同进程），而且少一条
         *    "后台启动服务"的路 —— 通知栏那个锁定动作就是死在那上面的。
         */
        @Volatile
        private var liveView: PetView? = null

        /**
         * 叫醒形象的动画时钟。
         *
         * 给 App 侧那些"**每帧现读、所以故意不通知服务**"的设置用（呼吸总开关就是）：
         * 它们不需要 `notifyChanged`（那会触发 ACTION_RELOAD，让桌宠重新落位、白跳一下），
         * 但 ticker 在"呼吸关掉 + 完全静止"时已经**彻底不排帧**了 —— 不叫它，
         * 用户打开呼吸后得碰一下人物才开始呼吸（用户反馈的 bug）。同进程直接调，
         * 零延迟、不落位。
         */
        fun wakePetAnimation() {
            liveView?.wakeAnimation()
        }

        const val ACTION_STOP = "com.dsh.deskpet.action.STOP"
        const val ACTION_RELOAD = "com.dsh.deskpet.action.RELOAD"
        const val ACTION_RESET = "com.dsh.deskpet.action.RESET"
        const val ACTION_NOTIFY = "com.dsh.deskpet.action.NOTIFY"

        /** 通知栏的「呼出」按钮。效果与 [start]（App 内部的呼出）一致。 */
        const val ACTION_SUMMON = "com.dsh.deskpet.action.SUMMON"
        /** App 里改了通知栏自定义内容：只刷新通知，不动桌宠位置 */
        fun refreshNotification(context: Context) {
            // ⚠️ 闸门是 `alive`（服务是否活着），不是 `running`（桌宠是否可见）。
            //    收起后服务仍按设计常驻、通知栏也还挂着，这时改通知文案必须能生效，
            //    否则要等下次呼出才刷新（"改了没反应"）。
            if (!alive) return
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

        /**
         * 换角色 / 改大小后通知正在跑的服务刷新。
         *
         * ⚠️ 尺寸路径曾经分出一个 notifySizeChanged（额外要求"吸回某一侧"），
         *    2.1.10 按用户要求删除：尺寸这块照搬 2.1.3，走的就是这一条。
         */
        fun notifyChanged(context: Context) {
            // 换角色 / 改大小后通知标题里的角色名要跟着变。
            //
            // ⚠️ 闸门同样是 `alive` 而不是 `running`：收起状态下换角色，
            //    通知栏（此时显示"已收起"文案，但标题里带角色名）也要立刻更新。
            if (!alive) return
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
