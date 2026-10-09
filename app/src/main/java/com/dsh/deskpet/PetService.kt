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
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
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

        val b = screenBounds()
        val view = PetView(this)
        view.host = this
        view.sprite = s
        view.setSizeFraction(prefs.sizeFraction)

        // 整屏透明窗口。
        //
        // ⚠️ 绝对不要加 FLAG_NOT_TOUCH_MODAL！
        // 那个 flag 的语义是"本窗口之外的触摸由我自己处理，不要传给下层"，
        // 配上整屏窗口就会把**整个屏幕的触摸全部吃掉**，用户没法操作手机。
        // （实测踩过这个坑：开启桌宠后整个屏幕点不动。）
        //
        // 正确做法是：不加该 flag，让窗口外的触摸自然落到下层；
        // 窗口内部的触摸由 PetView.onTouchEvent 自己判定是否点在宠物身上，
        // 点空了返回 false，同样会继续往下传。
        val p = WindowManager.LayoutParams(
            b.width(), b.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 0
        p.y = 0

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
        view.applySnap(st)
        if (prefs.hasPosition) {
            view.restore(st, prefs.posLeft, prefs.posTop)
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
        // 大小变了要重新吸附定位
        v.applySnap(v.state)
        if (prefs.hasPosition) {
            v.restore(v.state, prefs.posLeft, prefs.posTop)
        }
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

    /** 整屏窗口不移动，位置由 PetView 自己画；这里只用来触发保存 */
    override fun moveTo(x: Int, y: Int) {
        // 窗口固定整屏，无需 updateViewLayout
    }

    override fun onSettled(state: Int, leftInset: Int, topInset: Int) {
        prefs.snapState = state
        prefs.savePosition(leftInset, topInset)
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
        prefs.savePosition(v.leftInset(), v.topInset())
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
