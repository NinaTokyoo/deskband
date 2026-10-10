package com.dsh.deskpet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.log10
import kotlin.math.roundToInt

/** 控制台：授权、开关桌宠、调大小、换角色。 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var rowOverlay: View
    private lateinit var rowNotify: View
    private lateinit var markOverlay: TextView
    private lateinit var markNotify: TextView
    private lateinit var btnToggle: Button
    private lateinit var seekSize: SeekBar
    private lateinit var txtSize: TextView
    private lateinit var editSize: EditText
    private lateinit var btnSaveSize: Button
    private lateinit var btnResetSize: Button
    /**
     * 「当前形象」选择框（用户要求的新交互）。
     *
     * 原来是一个 Spinner（下拉箭头在框**外**、弹出的菜单浮在卡片上方）。
     * 现在改为：名称与倒三角同一个描边框、点**整个框**展开二级菜单，
     * 菜单向下撑开卡片、行与行之间用横线隔开，展开时三角翻成正立。
     */
    private lateinit var boxSkin: android.widget.LinearLayout
    private lateinit var txtSkinName: TextView
    private lateinit var icoSkinArrow: android.widget.ImageView

    /** 二级菜单容器（默认 gone，点框展开） */
    private lateinit var skinMenu: android.widget.LinearLayout

    /** 二级菜单的外层（负责 2/3 宽度的留白，见布局注释） */
    private lateinit var skinMenuWrap: android.widget.LinearLayout
    private lateinit var btnReset: Button
    private lateinit var btnResetKeep: Button

    // 松手回弹调节
    private lateinit var seekBounceAmp: SeekBar
    private lateinit var seekBounceMs: SeekBar
    private lateinit var txtBounceAmp: TextView
    private lateinit var txtBounceMs: TextView
    private lateinit var editBounceAmp: EditText
    private lateinit var editBounceMs: EditText
    private lateinit var btnSaveBounce: Button
    private lateinit var btnResetBounce: Button
    private lateinit var btnBounceToggle: Button

    // ---- 呼出回弹卡 ----
    private lateinit var seekSummonTime: SeekBar
    private lateinit var seekSummonPop: SeekBar
    private lateinit var seekSummonSpring: SeekBar
    private lateinit var txtSummonTime: TextView
    private lateinit var txtSummonPop: TextView
    private lateinit var txtSummonSpring: TextView
    private lateinit var editSummonTime: EditText
    private lateinit var editSummonPop: EditText
    private lateinit var editSummonSpring: EditText
    private lateinit var btnSaveSummon: Button
    private lateinit var btnResetSummon: Button
    private lateinit var btnSummonToggle: Button

    // ---- 呼吸卡 ----
    private lateinit var seekBreathAmp: SeekBar
    private lateinit var txtBreathAmp: TextView
    private lateinit var editBreathAmp: EditText
    private lateinit var btnSaveBreath: Button
    private lateinit var btnResetBreath: Button
    private lateinit var btnBreathToggle: Button

    // 通知栏自定义
    private lateinit var editNotify: EditText
    private lateinit var txtNotifyCount: TextView
    private lateinit var btnSaveNotify: Button

    /** 切换形象卡片右下角的素材预览（不可交互） */
    private lateinit var imgCornerPreview: android.widget.ImageView

    /**
     * 明暗模式：三个按钮（跟随系统 / 浅色 / 深色），选中的那个高亮。
     *
     * 原来是 Spinner，用户要求改成一行三个按钮。
     */
    private lateinit var btnThemeSystem: Button
    private lateinit var btnThemeLight: Button
    private lateinit var btnThemeDark: Button

    private var skins: List<String> = emptyList()

    /** 形象目录 -> 显示名，[loadSkins] 时一次算好 */
    private var skinNames: Map<String, String> = emptyMap()

    // ⚠️ 原来这里还有 skinReady / bindingSkin 两个标志，用来挡掉 Spinner 的
    // 首次 onItemSelected(0) 回调（不挡的话每次启动都会把 prefs.skin 覆盖成
    // 列表第一项）。改成自绘的「框 + 二级菜单」之后不再有 AdapterView，
    // 这类"初始回调"问题从根上消失了，所以两个标志一并删掉。

    /** 通知权限是否已经弹过系统对话框（弹过之后再点就直接去设置页） */
    private var notifyAsked = false

    /** 当前强调色（由角色发色推出） */
    private var accent = AccentTheme.FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        // 冷启动纠正一次残留标志：进程被从最近任务划掉时服务已死，
        // 但持久化的 running 还是 true，会让按钮显示"收起"却没有桌宠。
        // 只在这里做，绝不放进 refreshUi()（那里会和异步启停产生竞态）。
        if (!PetService.alive) prefs.running = false
        // 分区几何用 PetState 里的固定默认值（A 区尺寸调节功能已取消）

        rowOverlay = findViewById(R.id.rowOverlay)
        rowNotify = findViewById(R.id.rowNotify)
        markOverlay = findViewById(R.id.markOverlay)
        markNotify = findViewById(R.id.markNotify)

        // 下划线只能在代码里加（android:paintFlags 不是合法的 XML 属性）
        for (tv in arrayOf(
            findViewById<TextView>(R.id.txtPermOverlay),
            findViewById<TextView>(R.id.txtPermNotify)
        )) {
            tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        btnToggle = findViewById(R.id.btnToggle)
        seekSize = findViewById(R.id.seekSize)
        txtSize = findViewById(R.id.txtSize)
        editSize = findViewById(R.id.editSize)
        btnSaveSize = findViewById(R.id.btnSaveSize)
        btnResetSize = findViewById(R.id.btnResetSize)
        boxSkin = findViewById(R.id.boxSkin)
        txtSkinName = findViewById(R.id.txtSkinName)
        icoSkinArrow = findViewById(R.id.icoSkinArrow)
        skinMenuWrap = findViewById(R.id.skinMenuWrap)
        skinMenu = findViewById(R.id.skinMenu)
        btnReset = findViewById(R.id.btnReset)
        btnResetKeep = findViewById(R.id.btnResetKeep)
        seekBounceAmp = findViewById(R.id.seekBounceAmp)
        seekBounceMs = findViewById(R.id.seekBounceMs)
        txtBounceAmp = findViewById(R.id.txtBounceAmp)
        txtBounceMs = findViewById(R.id.txtBounceMs)
        editBounceAmp = findViewById(R.id.editBounceAmp)
        editBounceMs = findViewById(R.id.editBounceMs)
        btnSaveBounce = findViewById(R.id.btnSaveBounce)
        btnResetBounce = findViewById(R.id.btnResetBounce)
        btnBounceToggle = findViewById(R.id.btnBounceToggle)
        seekSummonTime = findViewById(R.id.seekSummonTime)
        seekSummonPop = findViewById(R.id.seekSummonPop)
        seekSummonSpring = findViewById(R.id.seekSummonSpring)
        txtSummonTime = findViewById(R.id.txtSummonTime)
        txtSummonPop = findViewById(R.id.txtSummonPop)
        txtSummonSpring = findViewById(R.id.txtSummonSpring)
        editSummonTime = findViewById(R.id.editSummonTime)
        editSummonPop = findViewById(R.id.editSummonPop)
        editSummonSpring = findViewById(R.id.editSummonSpring)
        btnSaveSummon = findViewById(R.id.btnSaveSummon)
        btnResetSummon = findViewById(R.id.btnResetSummon)
        btnSummonToggle = findViewById(R.id.btnSummonToggle)
        seekBreathAmp = findViewById(R.id.seekBreathAmp)
        txtBreathAmp = findViewById(R.id.txtBreathAmp)
        editBreathAmp = findViewById(R.id.editBreathAmp)
        btnSaveBreath = findViewById(R.id.btnSaveBreath)
        btnResetBreath = findViewById(R.id.btnResetBreath)
        btnBreathToggle = findViewById(R.id.btnBreathToggle)
        editNotify = findViewById(R.id.editNotify)
        txtNotifyCount = findViewById(R.id.txtNotifyCount)
        btnSaveNotify = findViewById(R.id.btnSaveNotify)
        imgCornerPreview = findViewById(R.id.imgCornerPreview)
        btnThemeSystem = findViewById(R.id.btnThemeSystem)
        btnThemeLight = findViewById(R.id.btnThemeLight)
        btnThemeDark = findViewById(R.id.btnThemeDark)

        applyEdgeToEdge()
        setupSummonControls()
        setupBounceControls()
        setupBreathControls()
        setupNotifyControls()
        applyTheme()
        refreshCornerPreview()
        setupThemeButtons()
        fitTitleToWidth()
        warmUpSkins()

        rowOverlay.setOnClickListener { openOverlaySettings() }
        rowNotify.setOnClickListener { openNotificationSettings() }
        btnToggle.setOnClickListener { togglePet() }
        btnReset.setOnClickListener { doReset() }
        btnResetKeep.setOnClickListener {
            prefs.resetKeepSettings = !prefs.resetKeepSettings
            bindResetKeep()
        }

        seekSize.max = 1000
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.sizeScale = sizeScaleOf(progress)
                bindSizeControls()
                // 大小变了要重新计算绘制尺寸与窗口尺寸，必须通知服务
                PetService.notifyChanged(this@MainActivity)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 数字输入：回车或失焦时生效（支持小数，如 1.5）
        val applySize = {
            parseScale(editSize.text.toString(), Prefs.SIZE_SCALE_MIN, Prefs.SIZE_SCALE_MAX)
                ?.let {
                    prefs.sizeScale = it
                    PetService.notifyChanged(this)
                }
            bindSizeControls()
        }
        editSize.setOnEditorActionListener { _, _, _ -> applySize(); clearInputFocus(); true }
        editSize.setOnFocusChangeListener { _, has -> if (!has) applySize() }

        btnSaveSize.setOnClickListener {
            parseScale(editSize.text.toString(), Prefs.SIZE_SCALE_MIN, Prefs.SIZE_SCALE_MAX)
                ?.let {
                    prefs.sizeScale = it
                    PetService.notifyChanged(this)
                }
            bindSizeControls()
            clearInputFocus()
        }

        // 恢复默认值：大小会改变窗口尺寸，必须通知服务
        btnResetSize.setOnClickListener {
            prefs.sizeScale = 1f
            bindSizeControls()
            clearInputFocus()
            PetService.notifyChanged(this)
        }

        bindSizeControls()
        loadSkins()
    }

    // ---------------------------------------------------------------- 复位

    /**
     * 复位：大小与回弹恢复默认倍率，桌宠回到右边框中点。
     *
     * 服务没在跑时只提示 —— 用户点它是为了"出问题时找回桌宠"，
     * 桌宠本来就不在，直接告诉他先开启。
     */
    private fun doReset() {
        if (!prefs.running) {
            Toast.makeText(this, R.string.reset_need_running, Toast.LENGTH_SHORT).show()
            return
        }
        // ⚠️ 只有**关闭「复位保留设置」**时才在这里重置 Prefs。
        //
        // 原来是无条件重置，于是开了"保留"也照样被清零 ——
        // 表现是：宠物当前画面没变（服务端 setSizeFraction 被跳过），
        // 但外观卡片滑块/数字变回 1.0、边框回弹也失效（用户反馈）。
        if (!prefs.resetKeepSettings) {
            prefs.sizeScale = 1f
            prefs.bounceAmpScale = 1f
            prefs.bounceTimeScale = 1f
            prefs.summonTimeScale = 1f
            prefs.summonPopScale = 1f
            prefs.summonSpringScale = 1f
            prefs.breathScale = 1f
        }
        bindSizeControls()
        bindBounceControls()
        bindSummonControls()
        bindBreathControls()
        clearInputFocus()
        PetService.reset(this)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }

    // ---------------------------------------------------------------- 边到边

    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val root = findViewById<View>(R.id.root)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    // ---------------------------------------------------------------- 状态刷新

    private fun refreshUi() {
        // 以**服务的真实存活状态**为准，并顺手修正持久化标志。
        //
        // prefs.running 是持久化的：进程被从最近任务里划掉时服务已经死了，
        // 但那个布尔值还留着 true —— 冷启动后界面显示"收起"、实际没有桌宠。
        // ⚠️ 这里**不能**再纠正 prefs.running！
        //
        // 点「呼出」时 PetService.start() 是异步的：refreshUi() 跑的这一刻
        // 服务 onCreate 还没执行、PetService.alive 仍是 false，
        // 于是 "!alive && running" 成立，会把刚设好的 running 打回 false ——
        // 表现就是"按钮一直显示呼出"、"收起动画不触发"（第二次点击被当成启动）。
        //
        // 残留标志的纠正只在 onCreate() 里做一次（那才是"冷启动"的语义）。

        // 权限行：字形区分（已开启 ✓ / 未开启 ✗），颜色一律黑白（见 setPermMark）
        setPermMark(markOverlay, Settings.canDrawOverlays(this))
        setPermMark(markNotify, hasNotificationPermission())

        // 开关按钮：呼出/收起 + **当前形象名**（名字用强调色，见 refreshSummonLabel）。
        // 名字跟着下拉框走，与桌宠是否在运行无关。
        refreshSummonLabel()

        // 形象选择框：显示当前角色名；点整框展开/收起二级菜单
        refreshSkinBox()
        boxSkin.setOnClickListener { toggleSkinMenu() }
        buildSkinMenu()

        bindSizeControls()
        bindResetKeep()
    }

    /**
     * 小标记：已开启 ✓，未开启 ✗。
     *
     * ⚠️ **颜色不在这里定**：两个标记都是**黑白**（浅色模式黑、深色模式白），
     * 由 [AccentTheme.apply] 按明暗模式统一上色，是否已开启只靠**字形**区分。
     *
     * 这里若写死颜色，切主题 / 切形象重绘时会被 AccentTheme 覆盖，
     * 反而变成"有时彩色有时黑白"。
     */
    private fun setPermMark(tv: TextView, ok: Boolean) {
        tv.text = getString(if (ok) R.string.perm_granted_mark else R.string.perm_missing_mark)
    }

    /**
     * 当前形象的中文显示名（pet.json 里的 name）。
     *
     * 直接查 [skinNames]，**不再每次 PetSprite.load** ——
     * 那个会解码两张 PNG，放在切换形象的回调里就是那一卡的来源。
     */
    private fun currentSkinName(): String =
        skinNames[prefs.skin] ?: prefs.skin

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- 动作

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /**
     * 跳到系统的**通知设置页**（用户要求"点击直接跳转到设置中对应的权限界面"）。
     *
     * 33 以上优先走运行时申请对话框（体验更顺），用户拒绝过之后再点才去设置页。
     */
    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission() && !notifyAsked) {
            notifyAsked = true
            requestNotificationPermission()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e2: Exception) {
                // 忽略
            }
        }
    }

    private fun togglePet() {
        if (prefs.running) {
            PetService.stop(this)
            prefs.running = false
            refreshUi()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        try {
            PetService.start(this)
            prefs.running = true
            refreshUi()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSkins() {
        val found = PetSprite.listSkins(assets)
        skins = if (found.isEmpty()) listOf(Prefs.DEFAULT_SKIN) else found
        val labels = skins.map { dir -> PetSprite.load(assets, dir)?.displayName ?: dir }
        // 名字表一次算好：之后按钮文案、通知标题都查它，不再重复解码 PNG
        skinNames = skins.indices.associate { skins[it] to labels[it] }

        // 保存的形象若已不在素材里（比如换了包），退回第一个，避免框里空着
        if (prefs.skin !in skins) prefs.skin = skins.first()
        refreshSkinBox()
        buildSkinMenu()
    }

    // ---------------------------------------------------------------- 当前形象选择框

    /** 框里显示当前角色名（名称与倒三角同在一个描边框内） */
    private fun refreshSkinBox() {
        txtSkinName.text = currentSkinName()
    }

    /**
     * 重建二级菜单。
     *
     * 用户要求：菜单里列出**其余**人物（当前选中的那个不再列一遍），
     * 行与行之间用横线隔开；选中后菜单消失、三角恢复倒立。
     *
     * ⚠️ 每次换角色 / 换明暗模式都要重建：横线的颜色与文字色都依赖当前强调色。
     */
    private fun buildSkinMenu() {
        skinMenu.removeAllViews()
        val others = skins.filter { it != prefs.skin }
        android.util.Log.i("SkMenu", "buildSkinMenu: skins=${skins.size} cur=${prefs.skin} others=${others.size}")
        if (others.isEmpty()) return
        for ((i, dir) in others.withIndex()) {
            if (i > 0) {
                val d = makeSkinMenuDivider()
                skinMenu.addView(d)
                android.util.Log.i("SkMenu", "  divider #$i tag=${d.tag} lp=${d.layoutParams?.height}")
            }
            val row = layoutInflater.inflate(R.layout.item_skin_menu_row, skinMenu, false)
                as TextView
            row.text = skinNames[dir] ?: dir
            row.setOnClickListener { selectSkin(dir) }
            skinMenu.addView(row)
        }
        android.util.Log.i("SkMenu", "  menu children=${skinMenu.childCount}")
    }

    /**
     * 菜单项之间的横线。
     *
     * ⚠️ 颜色**在这里直接给**，不要只打 tag 等 AccentTheme.apply 来刷：
     * 菜单项是展开瞬间新造的视图，主题那趟遍历与它的创建时序很容易错开，
     * 实测就会出现"视图树里有这个 View、但没画出来"。
     * `accent` 是当前强调色，换角色 / 切明暗时 buildSkinMenu() 会重建，
     * 所以直接给色也始终是新的。
     */
    private fun makeSkinMenuDivider(): View = View(this).apply {
        setBackgroundColor(accent)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 点整框：展开 / 收起二级菜单，三角同步翻转。
     *
     * 这里**刻意不做动画**（用户试用后要求取消）：展开/收起都是即时切换。
     * 顺带记一笔踩过的坑，免得以后又想加：
     *   · 每帧改 layoutParams.height + requestLayout() 会让外层 ScrollView
     *     把整页卡片重新测量一遍，dumpsys gfxinfo 实测掉帧 32.9%（50 分位 26ms、
     *     95 分位 77ms，预算 16.7ms）；
     *   · 改成动画 translationY 能做到"零布局"，但要额外维护一个固定高度的
     *     裁剪容器，收起时还必须取对距离（取容器高度会拿到 0，表现为
     *     "先瞬移再动画"）。
     *   这套复杂度换来的收益不明显，所以最终选择不做。
     */
    private fun toggleSkinMenu() {
        val open = skinMenuWrap.visibility != View.VISIBLE
        if (open) {
            buildSkinMenu()
            skinMenuWrap.visibility = View.VISIBLE
            // 展开 -> 三角正立（旋转 180°）。直接设值，没有过渡动画。
            icoSkinArrow.rotation = 180f
        } else {
            closeSkinMenuNow()
            return
        }
        // ⚠️ 必须重刷一次主题，而且要在 visibility 与菜单项都就绪之后：
        //   · 菜单项是**刚新造**的视图，不在上一次 AccentTheme.apply 的遍历里，
        //     不补这次的话分隔线是透明的（表现："线看不见"）；
        //   · 名称框的圆角要跟着开合变（展开 -> 下方两角直角，与菜单连成一整块）。
        applyTheme()
    }

    /**
     * 收起菜单并复位状态（也用于"选中角色后立即关闭"）。
     *
     * ⚠️ 每行的 alpha / translationY 是普通 View 属性，会留到下一次展开，
     *    必须复位，否则下次展开时那些行会带着旧值（闪一下或不显示）。
     */
    private fun closeSkinMenuNow() {
        skinMenuWrap.visibility = View.GONE
        skinMenu.translationY = 0f
        skinMenuRows().forEach { r ->
            r.animate().cancel()
            r.alpha = 1f
        }
        icoSkinArrow.rotation = 0f
        // 名称框的下方两角要恢复圆角（菜单收起了，不再与它相连）
        applyTheme()
    }

    /** 菜单里那些"内容行"（跳过 1.5dp 的分隔线），淡入淡出只作用在它们身上 */
    private fun skinMenuRows(): List<View> =
        (0 until skinMenu.childCount).map { skinMenu.getChildAt(it) }
            .filter { it is TextView }

    /** 选中某个角色：换肤 + 收起菜单 + 三角复位 */
    private fun selectSkin(dir: String) {
        closeSkinMenuNow()
        if (dir == prefs.skin) return
        prefs.skin = dir
        // 换角色 -> 重新提取发色、立刻换肤；按钮名字与右下角预览同步更新
        refreshSkinBox()
        applyTheme()
        refreshCornerPreview()
        PetService.notifyChanged(this)
    }

    // ---------------------------------------------------------------- 回弹调节

    /**
     * 松手回弹调节：滑块 + 数字输入，两者双向同步。
     *
     * 界面调的是**倍率**（0.1 ~ 10，1.0 = 标准值 36px / 360ms）——
     * 用户要求"以现在的幅度和回弹时间为标准"来调，所以调倍率而非绝对值。
     *
     * 两个值都在**每次吸附时现读**（见 PetView.snapWithBounce），
     * 改完立刻生效，不需要重启桌宠、也不会挪动它的位置。
     */
    private fun setupBounceControls() {
        seekBounceAmp.max = 1000
        seekBounceMs.max = 1000

        seekBounceAmp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.bounceAmpScale = scaleOf(progress, Prefs.AMP_SCALE_MIN, Prefs.AMP_SCALE_MAX)
                bindBounceControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        seekBounceMs.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.bounceTimeScale = scaleOfLinear(progress, Prefs.TIME_SCALE_MIN, Prefs.TIME_SCALE_MAX)
                bindBounceControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 数字输入：回车或失焦时生效（支持小数，如 2.5）
        val applyAmp = {
            parseScale(editBounceAmp.text.toString(), Prefs.AMP_SCALE_MIN, Prefs.AMP_SCALE_MAX)?.let { prefs.bounceAmpScale = it }
            bindBounceControls()
        }
        val applyMs = {
            parseScale(editBounceMs.text.toString(), Prefs.TIME_SCALE_MIN, Prefs.TIME_SCALE_MAX)?.let { prefs.bounceTimeScale = it }
            bindBounceControls()
        }
        editBounceAmp.setOnEditorActionListener { _, _, _ -> applyAmp(); clearInputFocus(); true }
        editBounceAmp.setOnFocusChangeListener { _, has -> if (!has) applyAmp() }
        editBounceMs.setOnEditorActionListener { _, _, _ -> applyMs(); clearInputFocus(); true }
        editBounceMs.setOnFocusChangeListener { _, has -> if (!has) applyMs() }

        // 保存并应用：把输入框当前值落盘。
        //
        // 不需要通知服务 —— PetView.snapWithBounce 是**每次吸附时现读**
        // Prefs 的，所以下一次松手就会用上新值。
        // （故意不调 PetService.notifyChanged：那会触发 ACTION_RELOAD，
        //   让桌宠重新吸附定位、白跳一下。）
        // 这个按钮真正的价值是：确保输入框里刚敲进去的值一定被提交
        // （有时键盘还没收起、焦点没变化，失焦回调还没跑）。
        btnSaveBounce.setOnClickListener {
            parseScale(editBounceAmp.text.toString(), Prefs.AMP_SCALE_MIN, Prefs.AMP_SCALE_MAX)?.let { prefs.bounceAmpScale = it }
            parseScale(editBounceMs.text.toString(), Prefs.TIME_SCALE_MIN, Prefs.TIME_SCALE_MAX)?.let { prefs.bounceTimeScale = it }
            bindBounceControls()
            // 保存后收掉焦点与键盘，否则输入框会一直亮着下划线、光标闪个不停
            clearInputFocus()
        }

        // 恢复默认值：回弹参数是吸附时现读的，不需要通知服务
        btnResetBounce.setOnClickListener {
            prefs.bounceAmpScale = 1f
            prefs.bounceTimeScale = 1f
            bindBounceControls()
            clearInputFocus()
        }

        // 边框回弹总开关：关掉后吸附只做位置补间，不再往外弹。
        // 同时把该卡片里的滑块/输入框置灰禁用，避免"调了没反应"的困惑。
        btnBounceToggle.setOnClickListener {
            prefs.bounceEnabled = !prefs.bounceEnabled
            bindBounceControls()
            clearInputFocus()
        }

        bindBounceControls()
    }

    /**
     * 「呼出回弹」卡片：总时长 / 弹出高度 / 回弹幅度 + 总开关 + 恢复默认 + 保存。
     *
     * ⚠️ 三个滑块按用户要求走**线性**映射（[scaleOfLinear]），不是对数。
     * 这三个参数都是**呼出动画现读**的（`PetView.startSlidingTo`），
     * 所以不需要通知服务：下一次呼出就会用上新值。
     */
    private fun setupSummonControls() {
        seekSummonTime.max = 1000
        seekSummonPop.max = 1000
        seekSummonSpring.max = 1000

        seekSummonTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.summonTimeScale =
                    scaleOfLinear(progress, Prefs.SUMMON_TIME_MIN, Prefs.SUMMON_TIME_MAX)
                bindSummonControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        seekSummonPop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.summonPopScale =
                    scaleOfLinear(progress, Prefs.SUMMON_POP_MIN, Prefs.SUMMON_POP_MAX)
                bindSummonControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        seekSummonSpring.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.summonSpringScale =
                    scaleOfLinear(progress, Prefs.SUMMON_SPRING_MIN, Prefs.SUMMON_SPRING_MAX)
                bindSummonControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val applyTime = {
            parseScale(
                editSummonTime.text.toString(), Prefs.SUMMON_TIME_MIN, Prefs.SUMMON_TIME_MAX
            )?.let { prefs.summonTimeScale = it }
            bindSummonControls()
        }
        val applyPop = {
            parseScale(
                editSummonPop.text.toString(), Prefs.SUMMON_POP_MIN, Prefs.SUMMON_POP_MAX
            )?.let { prefs.summonPopScale = it }
            bindSummonControls()
        }
        val applySpring = {
            parseScale(
                editSummonSpring.text.toString(),
                Prefs.SUMMON_SPRING_MIN, Prefs.SUMMON_SPRING_MAX
            )?.let { prefs.summonSpringScale = it }
            bindSummonControls()
        }
        editSummonTime.setOnEditorActionListener { _, _, _ -> applyTime(); clearInputFocus(); true }
        editSummonTime.setOnFocusChangeListener { _, has -> if (!has) applyTime() }
        editSummonPop.setOnEditorActionListener { _, _, _ -> applyPop(); clearInputFocus(); true }
        editSummonPop.setOnFocusChangeListener { _, has -> if (!has) applyPop() }
        editSummonSpring.setOnEditorActionListener { _, _, _ -> applySpring(); clearInputFocus(); true }
        editSummonSpring.setOnFocusChangeListener { _, has -> if (!has) applySpring() }

        btnSaveSummon.setOnClickListener {
            parseScale(
                editSummonTime.text.toString(), Prefs.SUMMON_TIME_MIN, Prefs.SUMMON_TIME_MAX
            )?.let { prefs.summonTimeScale = it }
            parseScale(
                editSummonPop.text.toString(), Prefs.SUMMON_POP_MIN, Prefs.SUMMON_POP_MAX
            )?.let { prefs.summonPopScale = it }
            parseScale(
                editSummonSpring.text.toString(),
                Prefs.SUMMON_SPRING_MIN, Prefs.SUMMON_SPRING_MAX
            )?.let { prefs.summonSpringScale = it }
            bindSummonControls()
            clearInputFocus()
        }

        btnResetSummon.setOnClickListener {
            prefs.summonTimeScale = 1f
            prefs.summonPopScale = 1f
            prefs.summonSpringScale = 1f
            bindSummonControls()
            clearInputFocus()
        }

        btnSummonToggle.setOnClickListener {
            prefs.summonBounceEnabled = !prefs.summonBounceEnabled
            bindSummonControls()
            clearInputFocus()
        }

        bindSummonControls()
    }

    /** 把呼出回弹卡的控件状态同步到 Prefs（含总开关的启用/停用外观） */
    private fun bindSummonControls() {
        val on = prefs.summonBounceEnabled
        seekSummonTime.progress =
            progressOfScaleLinear(prefs.summonTimeScale, Prefs.SUMMON_TIME_MIN, Prefs.SUMMON_TIME_MAX)
        seekSummonPop.progress =
            progressOfScaleLinear(prefs.summonPopScale, Prefs.SUMMON_POP_MIN, Prefs.SUMMON_POP_MAX)
        seekSummonSpring.progress = progressOfScaleLinear(
            prefs.summonSpringScale, Prefs.SUMMON_SPRING_MIN, Prefs.SUMMON_SPRING_MAX
        )
        editSummonTime.setText(fmtScale(prefs.summonTimeScale))
        editSummonPop.setText(fmtScale(prefs.summonPopScale))
        editSummonSpring.setText(fmtScale(prefs.summonSpringScale))

        // 标签里的绝对值按**典型行程**（贴边呼出约 320px）估算，纯参考
        val popPx = prefs.summonPopPx(SUMMON_REF_TRAVEL_PX)
        txtSummonTime.text = getString(
            R.string.summon_time_label_value, fmtScale(prefs.summonTimeScale),
            prefs.summonTotalMs.roundToInt()
        )
        txtSummonPop.text = getString(
            R.string.summon_pop_label_value, fmtScale(prefs.summonPopScale), popPx.roundToInt()
        )
        txtSummonSpring.text = getString(
            R.string.summon_spring_label_value, fmtScale(prefs.summonSpringScale),
            (prefs.summonSpringGain * 100f).roundToInt()
        )

        btnSummonToggle.text = getString(if (on) R.string.summon_off else R.string.summon_on)
        seekSummonTime.isEnabled = on
        seekSummonPop.isEnabled = on
        seekSummonSpring.isEnabled = on
        editSummonTime.isEnabled = on
        editSummonPop.isEnabled = on
        editSummonSpring.isEnabled = on
        btnResetSummon.isEnabled = on
        val a = if (on) 1f else 0.45f
        txtSummonTime.alpha = a
        txtSummonPop.alpha = a
        txtSummonSpring.alpha = a
    }

    /**
     * 「呼吸」卡片：呼吸幅度 + 总开关 + 恢复默认 + 保存。
     *
     * 幅度是 `PetView` 每帧现读的，所以不需要通知服务；
     * 关掉总开关时 ticker 会立刻停下（见 PetView 的呼吸分支）。
     */
    private fun setupBreathControls() {
        seekBreathAmp.max = 1000
        seekBreathAmp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.breathScale = scaleOfLinear(progress, Prefs.BREATH_MIN, Prefs.BREATH_MAX)
                bindBreathControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val applyAmp = {
            parseScale(editBreathAmp.text.toString(), Prefs.BREATH_MIN, Prefs.BREATH_MAX)
                ?.let { prefs.breathScale = it }
            bindBreathControls()
        }
        editBreathAmp.setOnEditorActionListener { _, _, _ -> applyAmp(); clearInputFocus(); true }
        editBreathAmp.setOnFocusChangeListener { _, has -> if (!has) applyAmp() }

        btnSaveBreath.setOnClickListener {
            parseScale(editBreathAmp.text.toString(), Prefs.BREATH_MIN, Prefs.BREATH_MAX)
                ?.let { prefs.breathScale = it }
            bindBreathControls()
            clearInputFocus()
        }

        btnResetBreath.setOnClickListener {
            prefs.breathScale = 1f
            bindBreathControls()
            clearInputFocus()
        }

        btnBreathToggle.setOnClickListener {
            prefs.breathEnabled = !prefs.breathEnabled
            bindBreathControls()
            clearInputFocus()
            // ⚠️ 这里**不通知服务**：呼吸开关是 PetView 每帧现读的，
            // 关掉后 ticker 立刻退出呼吸分支；而 notifyChanged 会触发
            // ACTION_RELOAD，让桌宠重新落位、白跳一下（边框回弹卡也踩过）。
        }

        bindBreathControls()
    }

    /** 把呼吸卡的控件状态同步到 Prefs（含总开关的启用/停用外观） */
    private fun bindBreathControls() {
        val on = prefs.breathEnabled
        seekBreathAmp.progress =
            progressOfScaleLinear(prefs.breathScale, Prefs.BREATH_MIN, Prefs.BREATH_MAX)
        editBreathAmp.setText(fmtScale(prefs.breathScale))
        txtBreathAmp.text = getString(
            R.string.breath_amp_label_value, fmtScale(prefs.breathScale),
            (prefs.breathSq * 100f).roundToInt()
        )
        btnBreathToggle.text = getString(if (on) R.string.breath_off else R.string.breath_on)
        seekBreathAmp.isEnabled = on
        editBreathAmp.isEnabled = on
        btnResetBreath.isEnabled = on
        txtBreathAmp.alpha = if (on) 1f else 0.45f
    }

    /** 把回弹区的控件状态同步到 Prefs（含总开关的启用/停用外观） */
    private fun bindBounceEnabledState() {
        val on = prefs.bounceEnabled
        btnBounceToggle.text = getString(if (on) R.string.bounce_off else R.string.bounce_on)
        seekBounceAmp.isEnabled = on
        seekBounceMs.isEnabled = on
        editBounceAmp.isEnabled = on
        editBounceMs.isEnabled = on
        btnResetBounce.isEnabled = on
        // 数值文字同步变淡，和灰掉的控件保持一致（颜色仍由主题统一刷新）
        txtBounceAmp.alpha = if (on) 1f else 0.45f
        txtBounceMs.alpha = if (on) 1f else 0.45f
    }

    /**
     * 滑块进度(0..1000) <-> 倍率。
     *
     * 用**对数刻度**：线性的话低倍率段只占很小一段行程，很难调；
     * 取对数后 1.0 正好落在滑条中间，两侧手感一致。
     */
    /**
     * 滑块进度(0..1000) <-> 倍率：**对数**映射。
     *
     * 试过线性（用户要求"按上下界取比例"），但幅度区间是 0.5 ~ 100（200 倍跨度），
     * 线性下 1.0 的进度只有 (1-0.5)/(100-0.5) ≈ 0.5%，滑块贴死最左端，
     * 而且整个"可用区间"（0.5~12，再大就被防飞出钳制）只占行程的 12%，
     * 一拖就冲进饱和区 —— 这正是"调了没效果"的观感来源之一。
     * 对数映射让 0.5 / 1 / 3 / 10 / 30 / 100 在行程上均匀铺开，宽区间就该用对数。
     */
    private fun scaleOf(progress: Int, min: Float, max: Float): Float {
        val lo = log10(min.toDouble())
        val hi = log10(max.toDouble())
        val t = progress.coerceIn(0, 1000) / 1000.0
        return Math.pow(10.0, lo + (hi - lo) * t).toFloat()
    }

    private fun progressOfScale(v: Float, min: Float, max: Float): Int {
        val lo = log10(min.toDouble())
        val hi = log10(max.toDouble())
        val x = log10(v.coerceIn(min, max).toDouble())
        return (((x - lo) / (hi - lo)) * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    /**
     * **线性**映射版（时长滑块用）。
     *
     * 时长区间是 0.1 ~ 10，对数下 1.0 正好落在行程**正中间**，
     * 但用户要求 1.0 应该在**靠左**的位置（和幅度滑块的手感一致）。
     * 线性下 1.0 的进度 = (1-0.1)/(10-0.1) ≈ 9%，就在最左边附近 ✓
     */
    private fun scaleOfLinear(progress: Int, min: Float, max: Float): Float =
        min + (max - min) * (progress.coerceIn(0, 1000) / 1000f)

    private fun progressOfScaleLinear(v: Float, min: Float, max: Float): Int {
        if (max <= min) return 0
        return (((v.coerceIn(min, max) - min) / (max - min)) * 1000f)
            .roundToInt().coerceIn(0, 1000)
    }

    private fun scaleOf(progress: Int): Float =
        scaleOf(progress, Prefs.SCALE_MIN, Prefs.SCALE_MAX)

    private fun progressOfScale(v: Float): Int =
        progressOfScale(v, Prefs.SCALE_MIN, Prefs.SCALE_MAX)

    private fun parseScale(s: String, min: Float, max: Float): Float? =
        s.trim().removePrefix("×").removePrefix("x").toFloatOrNull()?.coerceIn(min, max)

    private fun parseScale(s: String): Float? =
        parseScale(s, Prefs.SCALE_MIN, Prefs.SCALE_MAX)

    /**
     * 倍率的显示格式：**固定一位小数**（用户要求，例如 1 显示成 "1.0"）。
     *
     * 不用 String.format 是为了避开 locale 差异（某些地区小数点是逗号）。
     */
    private fun fmtScale(v: Float): String {
        val tenths = (v * 10f).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    // ---------------------------------------------------------------- 通知栏内容

    /**
     * 通知栏自定义内容：上限 40 字符，实时显示字数。
     *
     * 保存后通知服务重建常驻通知（ACTION_RELOAD 不够 —— 它不会重建通知，
     * 所以这里显式调用 PetService.notifyChanged，它在服务侧走的是整条刷新路径；
     * 通知正文在 startForegroundCompat 里读取，因此需要一次通知刷新）。
     */
    private fun setupNotifyControls() {
        editNotify.setText(prefs.notifyText)
        updateNotifyCount()

        editNotify.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNotifyCount()
        })

        val applyNotify = {
            prefs.notifyText = editNotify.text.toString()
            editNotify.setText(prefs.notifyText)
            updateNotifyCount()
        }
        editNotify.setOnEditorActionListener { _, _, _ ->
            applyNotify(); clearInputFocus(); true
        }
        btnSaveNotify.setOnClickListener {
            applyNotify()
            clearInputFocus()
            PetService.refreshNotification(this)
        }
    }

    private fun updateNotifyCount() {
        val n = editNotify.text?.length ?: 0
        txtNotifyCount.text = getString(R.string.notify_count, n, Prefs.NOTIFY_TEXT_MAX)
    }

    private companion object {
        /** 量文字宽度用的基准字号（px）：取得够大，量完再按比例缩 */
        const val PROBE_TEXT_PX = 400f

        /**
         * 呼出"弹出高度"标签里显示 px 时用的**参考行程**（贴边呼出大约 320px）。
         * 真实过冲量是按行程算的（见 [Prefs.summonPopPx]），所以标签只是参考值。
         */
        const val SUMMON_REF_TRAVEL_PX = 320f
    }

    // ---------------------------------------------------------------- 标题铺满

    /**
     * 把标题字号调到**正好填满可用宽度**。
     *
     * 不用 autoSizeTextType：它在 maxLines + ellipsize + wrap_content 的组合下
     * 收敛不准，实测只填到屏幕四分之三左右。
     * 这里直接量：先给一个很大的基准字号量出文字宽度，再按比例缩放到可用宽度。
     * bold 字形与 typeface 都从 TextView 自己的 paint 里复制，所以量的是真实宽度。
     */
    private fun fitTitleToWidth() {
        val t = findViewById<TextView>(R.id.txtTitle)
        t.post {
            val avail = t.width - t.paddingStart - t.paddingEnd
            val text = t.text?.toString()
            if (avail <= 0 || text.isNullOrEmpty()) return@post
            val probe = Paint(t.paint)
            probe.textSize = PROBE_TEXT_PX
            val measured = probe.measureText(text)
            if (measured > 0f) {
                // 左右各留 1px 余量，避免因四舍五入把最后一个字挤出边界
                val size = PROBE_TEXT_PX * (avail - 2f) / measured
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
        }
    }

    /**
     * 后台预热所有形象的重资产（大图解码 + 发色提取 + alpha 包围盒）。
     *
     * 这些在 UI 线程上做就是"点一下卡一下"，所以放到最低优先级线程上先算好；
     * 用户真正切换时直接命中 [skinAssets] 缓存，几乎是瞬时的。
     * 低优先级 + 逐张让出，不会和界面抢 CPU。
     */
    private fun warmUpSkins() {
        val dirs = skins.toList()
        Thread {
            for (d in dirs) {
                runCatching { assetsFor(d) }
                Thread.yield()
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    // ---------------------------------------------------------------- 换肤

    /** 当前是否浅色模式 */
    private var lightMode = false

    /**
     * 每个形象的**重资产**缓存。
     *
     * 之前切换一次人物会在 UI 线程上跑：
     *   PetSprite.load × 2（每次解码 corner.png + upright.png，都是大图）
     *   + 提取发色的全图扫描 × 1
     *   + 算 alpha 包围盒的全图扫描 × 1
     * 于是"点击有延迟、一卡一卡"。现在按形象目录缓存，
     * 同一个形象只在第一次付这个代价，之后切换（以及切明暗）都是查表。
     */
    private class SkinAssets(
        val bmp: android.graphics.Bitmap?,
        val visible: android.graphics.Rect,
        val accentLight: Int,
        val accentDark: Int
    )

    private val skinAssets =
        java.util.Collections.synchronizedMap(HashMap<String, SkinAssets>())

    private fun assetsFor(skin: String): SkinAssets = skinAssets.getOrPut(skin) {
        val sprite = PetSprite.load(assets, skin)
        val bmp = sprite?.bitmap(PetState.CORNER_BR, mirror = false, flipV = false)
        if (bmp == null) {
            SkinAssets(null, android.graphics.Rect(0, 0, 1, 1),
                AccentTheme.FALLBACK, AccentTheme.FALLBACK)
        } else {
            // 只扫一次发色，明暗两套强调色都从它派生
            val hair = AccentTheme.extractHairColor(bmp)
            SkinAssets(
                bmp,
                visibleBounds(bmp),
                hair?.let { AccentTheme.accentFrom(it, true) } ?: AccentTheme.FALLBACK,
                hair?.let { AccentTheme.accentFrom(it, false) } ?: AccentTheme.FALLBACK
            )
        }
    }

    /**
     * 重新计算强调色 + 明暗模式，并把整套配色刷到界面上。
     *
     * 强调色取自**角落素材**（头最大、发色面积最多，提取最稳）。
     * 明暗切换是"就地重绘"，不重建 Activity，所以立刻生效。
     */
    private fun applyTheme() {
        lightMode = AccentTheme.isLight(this, prefs.themeMode)
        val sa = assetsFor(prefs.skin)
        accent = if (lightMode) sa.accentLight else sa.accentDark
        prefs.accentColor = accent

        AccentTheme.apply(this, findViewById(R.id.root), accent, lightMode)

        // 形象选择框：描边跟随强调色，三角填充色也一起换。
        // connected = 菜单是否展开：展开时框的下方两角变直角，与菜单连成一整块。
        val menuOpen = skinMenuWrap.visibility == View.VISIBLE
        boxSkin.background =
            AccentTheme.skinBoxBackground(this, accent, lightMode, connected = menuOpen)
        // 二级菜单**整个区域**也要被描边包住（用户明确要求：展开的部分不能没有框）
        skinMenu.background = AccentTheme.skinMenuBackground(this, accent, lightMode)
        icoSkinArrow.imageTintList =
            android.content.res.ColorStateList.valueOf(AccentTheme.skinBoxText(lightMode))
        // 菜单项的横线与文字色都依赖强调色 / 明暗模式，重建一次
        buildSkinMenu()
        refreshSkinBox()

        // 按钮上的文字里有形象名，名字要用强调色，所以每次都重建一次
        refreshSummonLabel()

        // ⚠️ 必须放在 AccentTheme.apply 之后：三个按钮的镂空样式是那一趟发的，
        // 选中态要覆盖它才看得出来。
        refreshThemeButtons()
    }

    /**
     * 呼出/收起按钮的文字：**形象名部分用强调色**，其余保持中性。
     *
     * 用 SpannableString 给名字那一段单独上色 ——
     * AccentTheme 只设视图的基础文字色，span 会盖在它上面。
     */
    private fun refreshSummonLabel() {
        val name = currentSkinName()
        val text = getString(
            if (prefs.running) R.string.btn_dismiss else R.string.btn_summon, name
        )
        val sp = android.text.SpannableString(text)
        val i = text.indexOf(name)
        if (i >= 0) {
            sp.setSpan(
                android.text.style.ForegroundColorSpan(accent),
                i, i + name.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        btnToggle.text = sp
    }


    /**
     * 切换形象卡片右下角的预览图：当前角色的「右下角」素材。
     *
     * 关键点：素材 PNG 通常带**透明边距**，如果只靠 fitEnd 对齐的是整张位图，
     * 可见画面就会离卡片边框还差几个像素。所以这里按**可见区域（alpha 包围盒）**
     * 算矩阵，把可见画面的右下角精确压到卡片的右边框与底边框上，
     * 于是素材的右下角会被卡片圆角切掉（用户确认这是允许且期望的）。
     *
     * 不对素材做任何裁切 —— 只调整它在框内的缩放与位移。
     */
    /** 复位选项按钮的文案：显示当前是"保留"还是"恢复默认" */
    private fun bindResetKeep() {
        btnResetKeep.text = getString(
            if (prefs.resetKeepSettings) R.string.reset_keep_on else R.string.reset_keep_off
        )
    }

    private fun refreshCornerPreview() {
        val sa = assetsFor(prefs.skin)
        val bmp = sa.bmp
        imgCornerPreview.setImageBitmap(bmp)
        if (bmp == null) return
        val vis = sa.visible
        imgCornerPreview.post {
            val vw = imgCornerPreview.width.toFloat()
            val vh = imgCornerPreview.height.toFloat()
            if (vw <= 0f || vh <= 0f) return@post
            val scale = minOf(vw / vis.width(), vh / vis.height())
            val m = android.graphics.Matrix()
            m.postScale(scale, scale)
            // 可见区域的右下角 -> 视图的右下角
            m.postTranslate(vw - vis.right * scale, vh - vis.bottom * scale)
            imgCornerPreview.imageMatrix = m
        }
    }

    /**
     * 位图里**非透明**像素的包围盒（可见画面）；全透明就退回整张。
     *
     * 用 step 跳采样：只为定位边界，不需要逐像素精确，跳采样能把
     * 百万像素级的全图扫描从"肉眼可感的卡顿"降到可忽略。
     * （角落素材常在 1000x1400 上下，逐像素在 Kotlin 里是几百万次循环。）
     */
    private fun visibleBounds(bmp: android.graphics.Bitmap): android.graphics.Rect {
        val w = bmp.width
        val h = bmp.height
        val step = (maxOf(w, h) / 512).coerceAtLeast(1)
        var l = w
        var t = h
        var r = -1
        var b = -1
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            if (!runCatching { bmp.getPixels(row, 0, w, 0, y, w, 1) }.isSuccess) break
            var x = 0
            while (x < w) {
                if (((row[x] ushr 24) and 0xFF) >= 16) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
                x += step
            }
            y += step
        }
        return if (r < 0) android.graphics.Rect(0, 0, w, h)
        else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    // ---------------------------------------------------------------- 明暗模式

    /**
     * 明暗模式：一行三个按钮，点哪个立刻生效。
     *
     * 选中后立刻 applyTheme()，是"就地重绘"，不重建 Activity。
     * ⚠️ 按钮的选中态**不能**在这里自己设背景就完事：applyTheme() 里
     *    AccentTheme.apply 会把所有按钮刷成镂空样式，所以统一由
     *    refreshThemeButtons() 在 apply 之后覆盖。
     */
    private fun setupThemeButtons() {
        btnThemeSystem.setOnClickListener { pickThemeMode(AccentTheme.MODE_SYSTEM) }
        btnThemeLight.setOnClickListener { pickThemeMode(AccentTheme.MODE_LIGHT) }
        btnThemeDark.setOnClickListener { pickThemeMode(AccentTheme.MODE_DARK) }
        refreshThemeButtons()
    }

    /** 选中某个明暗模式；已经选中的再点一次不做无谓重绘 */
    private fun pickThemeMode(mode: Int) {
        if (prefs.themeMode == mode) return
        prefs.themeMode = mode
        applyTheme()
        refreshCornerPreview()
    }

    /**
     * 刷新三个按钮的选中态。
     *
     * 用户确认的方案：**选中 = 主题色文字 + 镂空**，未选中 = 常规前景色文字 +
     * 镂空。两者背景完全一样（都由 [AccentTheme.ghostSelectorFor] 下发），
     * 只靠文字色区分 —— 所以这里只改文字色，不碰背景形状。
     *
     * ⚠️ 但背景仍然要显式下发一次：applyTheme() 里 AccentTheme.apply 会把按钮
     *    统一刷成镂空样式，而本函数在它之后跑，必须保证不会留下上一次的实心底
     *    （改版前选中态是实心强调色，若不清就会残留）。
     */
    private fun refreshThemeButtons() {
        if (!::btnThemeSystem.isInitialized) return
        val cur = prefs.themeMode
        for ((mode, b) in listOf(
            AccentTheme.MODE_SYSTEM to btnThemeSystem,
            AccentTheme.MODE_LIGHT to btnThemeLight,
            AccentTheme.MODE_DARK to btnThemeDark
        )) {
            b.background = AccentTheme.ghostSelectorFor(this, accent, lightMode)
            b.setTextColor(
                if (mode == cur) accent else AccentTheme.skinBoxText(lightMode)
            )
        }
    }

    /**
     * 跟随系统时，系统切换深色/浅色要立刻跟上。
     *
     * 需要 AndroidManifest 里声明 android:configChanges="uiMode"，
     * 否则 Activity 会被重建（虽然结果也对，但会丢掉滚动位置）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (prefs.themeMode == AccentTheme.MODE_SYSTEM) {
            applyTheme()
            refreshCornerPreview()
        }
    }

    // ---------------------------------------------------------------- 大小调节

    /** 滑块进度(0..1000) <-> 大小倍率(0.5..5)，同样用对数刻度让 1.0 落在中间 */
    private fun sizeScaleOf(progress: Int): Float =
        scaleOf(progress, Prefs.SIZE_SCALE_MIN, Prefs.SIZE_SCALE_MAX)

    private fun progressOfSizeScale(v: Float): Int =
        progressOfScale(v, Prefs.SIZE_SCALE_MIN, Prefs.SIZE_SCALE_MAX)

    /** 把当前值刷到滑块 / 文本框 / 摘要文字 */
    private fun bindSizeControls() {
        val sc = prefs.sizeScale
        seekSize.progress = progressOfSizeScale(sc)
        editSize.setText(fmtScale(sc))
        // 只显示倍率，不再显示"占屏高 xx%"（用户要求，且太长会被截断）
        txtSize.text = getString(R.string.size_label_value, fmtScale(sc))
    }

    /** 把当前值刷到滑块 / 文本框 / 摘要文字 */
    private fun bindBounceControls() {
        val amp = prefs.bounceAmpScale
        val tms = prefs.bounceTimeScale
        seekBounceAmp.progress = progressOfScale(amp, Prefs.AMP_SCALE_MIN, Prefs.AMP_SCALE_MAX)
        seekBounceMs.progress = progressOfScaleLinear(tms, Prefs.TIME_SCALE_MIN, Prefs.TIME_SCALE_MAX)
        editBounceAmp.setText(fmtScale(amp))
        editBounceMs.setText(fmtScale(tms))
        txtBounceAmp.text = getString(
            R.string.bounce_amp_label_value, fmtScale(amp), prefs.bouncePx.roundToInt()
        )
        txtBounceMs.text = getString(
            R.string.bounce_ms_label_value, fmtScale(tms), prefs.bounceBackMs
        )
        bindBounceEnabledState()
    }

    /**
     * 收掉输入焦点：清焦点 + 隐藏软键盘。
     *
     * **遍历整棵视图树找出所有 EditText**，而不是逐个写死名字 ——
     * 用户要求"所有支持输入并保存的输入框都要在保存后去焦点"，
     * 逐个写的话以后新增输入框很容易漏（已经因此踩过两次：
     * 漏 editSize、漏 editNotify），遍历就永远不会漏。
     *
     * ⚠️ 必须先取 windowToken 再 clearFocus —— clearFocus() 之后
     * currentFocus 会变成 null，那时再取就拿到 null，
     * hideSoftInputFromWindow(null, 0) 是空操作，键盘根本不会关。
     */
    private fun clearInputFocus() {
        val edits = ArrayList<EditText>()
        collectEditTexts(findViewById(R.id.root), edits)
        val token = (currentFocus ?: edits.firstOrNull())?.windowToken
        for (e in edits) e.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun collectEditTexts(v: View?, out: MutableList<EditText>) {
        when (v) {
            is EditText -> out.add(v)
            is android.view.ViewGroup -> {
                for (i in 0 until v.childCount) collectEditTexts(v.getChildAt(i), out)
            }
        }
    }
}
