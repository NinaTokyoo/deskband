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
    private lateinit var seekSize: ThumbSeekBar
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

    /**
     * 二级菜单的**视口**（一页 5 行，超出在里面拖动滚动，右侧带指示条）。
     *
     * 它是自绘容器而不是 ScrollView —— 嵌套 ScrollView 会被外层页面抢走手势，
     * 见 [SkinMenuViewport] 的类注释。描边背景挂在外层 [skinMenuWrap] 上、
     * 高度上限设在这里（见 [limitSkinMenuHeight]）。
     */
    private lateinit var skinMenuScroll: SkinMenuViewport

    /** 二级菜单的外层（负责 2/3 宽度的留白，见布局注释） */
    private lateinit var skinMenuWrap: android.widget.LinearLayout
    private lateinit var btnReset: Button
    private lateinit var btnResetKeep: Button
    private lateinit var btnLock: Button
    private lateinit var btnLockInteract: Button

    // 松手回弹调节
    private lateinit var seekBounceAmp: ThumbSeekBar
    private lateinit var seekBounceMs: ThumbSeekBar
    private lateinit var txtBounceAmp: TextView
    private lateinit var txtBounceMs: TextView
    private lateinit var editBounceAmp: EditText
    private lateinit var editBounceMs: EditText
    private lateinit var btnSaveBounce: Button
    private lateinit var btnResetBounce: Button
    private lateinit var btnBounceToggle: Button

    // ---- 呼出回弹卡 ----
    private lateinit var seekSummonTime: ThumbSeekBar
    private lateinit var seekSummonPop: ThumbSeekBar
    private lateinit var seekSummonSpring: ThumbSeekBar
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
    private lateinit var seekBreathSpeed: ThumbSeekBar
    private lateinit var txtBreathSpeed: TextView
    private lateinit var editBreathSpeed: EditText
    private lateinit var seekBreathAmp: ThumbSeekBar
    private lateinit var txtBreathAmp: TextView
    private lateinit var editBreathAmp: EditText
    private lateinit var btnSaveBreath: Button
    private lateinit var btnResetBreath: Button
    private lateinit var btnBreathToggle: Button

    // 通知栏自定义
    private lateinit var editNotifyTitle: EditText
    private lateinit var txtNotifyTitleCount: TextView
    private lateinit var editNotify: EditText
    private lateinit var txtNotifyCount: TextView
    private lateinit var btnSaveNotify: Button
    private lateinit var btnResetNotify: Button

    /** 切换形象卡片右下角的素材预览（不可交互） */
    private lateinit var imgCornerPreview: android.widget.ImageView

    // ---- 「一横条 + 点管理展开」那几张卡的按钮与展开区 ----
    private lateinit var btnManageSize: Button
    private lateinit var btnManageSummon: Button
    private lateinit var btnManageBounce: Button
    private lateinit var btnManageBreath: Button
    private lateinit var btnManageNotify: Button
    private lateinit var panelSize: View
    private lateinit var panelSummon: View
    private lateinit var panelBounce: View
    private lateinit var panelBreath: View
    private lateinit var panelNotify: View

    // ---- 自定义形象卡 ----
    /** 卡片形态：收起（一横条）/ 新增表单 / 管理列表 */
    private var customMode = CUSTOM_NONE
    private lateinit var btnCustomAdd: Button
    private lateinit var btnCustomManage: Button
    private lateinit var panelCustomAdd: View
    private lateinit var panelCustomManage: View
    private lateinit var rowsCustomManage: android.widget.LinearLayout
    private lateinit var txtCustomEmpty: TextView
    private lateinit var editCustomName: EditText
    private lateinit var boxCustomUpright: View
    private lateinit var boxCustomCorner: View
    private lateinit var imgCustomUpright: android.widget.ImageView
    private lateinit var imgCustomCorner: android.widget.ImageView
    private lateinit var txtPlusCustomUpright: TextView
    private lateinit var txtPlusCustomCorner: TextView

    /**
     * 新增表单里已选中的两张图。
     *
     * ⚠️ 只存 Uri、**不存解码后的位图**：位图会随 Activity 重建丢失（旋转屏幕、
     * 被系统回收），而 Uri 一直有效；保存时才真正解码落盘（见 CustomSkinStore。
     * 顺带也就不会因为"选了两张 4000px 的图"把内存顶上去。
     */
    private var pickedUpright: Uri? = null
    private var pickedCorner: Uri? = null

    // ---- 气泡内容卡（用户 2026-10-16 新增，位置在「通知栏内容」之后） ----
    /** 卡片形态：收起（一横条）/ 新增表单 / 管理列表 —— 与自定义形象卡同一套 */
    private var bubbleTextMode = BUBBLE_NONE

    /**
     * 「管理」列表里**正处于"待确认删除"**的那一条下标（null = 没有）。
     *
     * 与自定义形象同一套：同一时刻只允许一个标签处于待确认，
     * 否则连点几个错号会攒出一排「确认删除」。
     * 用**下标**而不是内容：内容允许重复，下标才是唯一的。
     */
    private var pendingBubbleDelete: Int? = null

    /**
     * 标签的列宽要抄标题行按钮的宽度，而首次渲染发生在 onCreate 里、那时标题行还没量过。
     * 这个标志保证"量到 0 就延后一帧重排"，且只重排一次（不会自己套自己）。
     */
    private var bubbleTagsAwaitLayout = false

    /** 管理列表里每一行的视图（下标 = 条目下标）—— 点按钮时**原地更新**它们，不重建 */
    private val bubbleTagRows = ArrayList<View>()
    private lateinit var btnBubbleTextAdd: Button
    private lateinit var btnBubbleTextManage: Button
    private lateinit var panelBubbleTextAdd: View
    private lateinit var panelBubbleTextManage: View
    private lateinit var panelBubbleTextKind: View
    private lateinit var btnBubbleKindText: Button
    private lateinit var btnBubbleKindImage: Button
    private lateinit var rowsBubbleText: android.widget.LinearLayout
    private lateinit var txtBubbleTextEmpty: TextView
    private lateinit var editBubbleText: EditText
    private lateinit var txtBubbleTextCount: TextView
    private lateinit var btnBubbleTextSave: Button
    private lateinit var btnBubbleTextCancel: Button

    /** 保存/解码在后台线程跑，这个标志挡住连点（否则会存出两个一样的形象） */
    private var customSaving = false

    /**
     * 「管理」列表里**正处于"待确认删除"**的那个形象 id（null = 没有）。
     *
     * 删除是就地两步：点红错号 -> 该标签变成「确认删除」-> 再点才删。
     * 只留一个 id 而不是每个标签各带一个布尔：否则连点几个错号会攒出一排
     * "确认删除"，用户根本记不住哪个是哪个。
     */
    private var pendingDeleteId: String? = null

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

    /**
     * 刚刚从**相册 / 文件选择器**返回的时刻（[onActivityResult] 记）。
     *
     * 这种"离开"不算真的退出应用 —— [onResume] 会据此豁免一次"全部收起"，
     * 否则新增表单里刚选好的图会被收起时的 `clearCustomForm()` 清掉
     * （用户会遇到"选完图回来，表单没了"）。
     * 记时刻而不是布尔：万一 onActivityResult 之后没走 onResume，也不会一直
     * 把后续的收起动作吞掉。
     */
    private var returningFromPickerAt = 0L

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
        skinMenuScroll = findViewById(R.id.skinMenuScroll)
        skinMenu = findViewById(R.id.skinMenu)
        btnReset = findViewById(R.id.btnReset)
        btnResetKeep = findViewById(R.id.btnResetKeep)
        btnLock = findViewById(R.id.btnLock)
        btnLockInteract = findViewById(R.id.btnLockInteract)
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
        seekBreathSpeed = findViewById(R.id.seekBreathSpeed)
        txtBreathSpeed = findViewById(R.id.txtBreathSpeed)
        editBreathSpeed = findViewById(R.id.editBreathSpeed)
        seekBreathAmp = findViewById(R.id.seekBreathAmp)
        txtBreathAmp = findViewById(R.id.txtBreathAmp)
        editBreathAmp = findViewById(R.id.editBreathAmp)
        btnSaveBreath = findViewById(R.id.btnSaveBreath)
        btnResetBreath = findViewById(R.id.btnResetBreath)
        btnBreathToggle = findViewById(R.id.btnBreathToggle)
        editNotifyTitle = findViewById(R.id.editNotifyTitle)
        txtNotifyTitleCount = findViewById(R.id.txtNotifyTitleCount)
        editNotify = findViewById(R.id.editNotify)
        txtNotifyCount = findViewById(R.id.txtNotifyCount)
        btnSaveNotify = findViewById(R.id.btnSaveNotify)
        btnResetNotify = findViewById(R.id.btnResetNotify)
        imgCornerPreview = findViewById(R.id.imgCornerPreview)
        btnManageSize = findViewById(R.id.btnManageSize)
        btnManageSummon = findViewById(R.id.btnManageSummon)
        btnManageBounce = findViewById(R.id.btnManageBounce)
        btnManageBreath = findViewById(R.id.btnManageBreath)
        btnManageNotify = findViewById(R.id.btnManageNotify)
        panelSize = findViewById(R.id.panelSize)
        panelSummon = findViewById(R.id.panelSummon)
        panelBounce = findViewById(R.id.panelBounce)
        panelBreath = findViewById(R.id.panelBreath)
        panelNotify = findViewById(R.id.panelNotify)
        btnCustomAdd = findViewById(R.id.btnCustomAdd)
        btnCustomManage = findViewById(R.id.btnCustomManage)
        panelCustomAdd = findViewById(R.id.panelCustomAdd)
        panelCustomManage = findViewById(R.id.panelCustomManage)
        rowsCustomManage = findViewById(R.id.rowsCustomManage)
        txtCustomEmpty = findViewById(R.id.txtCustomEmpty)
        editCustomName = findViewById(R.id.editCustomName)
        boxCustomUpright = findViewById(R.id.boxCustomUpright)
        boxCustomCorner = findViewById(R.id.boxCustomCorner)
        imgCustomUpright = findViewById(R.id.imgCustomUpright)
        imgCustomCorner = findViewById(R.id.imgCustomCorner)
        txtPlusCustomUpright = findViewById(R.id.txtPlusCustomUpright)
        txtPlusCustomCorner = findViewById(R.id.txtPlusCustomCorner)
        btnThemeSystem = findViewById(R.id.btnThemeSystem)
        btnThemeLight = findViewById(R.id.btnThemeLight)
        btnThemeDark = findViewById(R.id.btnThemeDark)
        btnBubbleTextAdd = findViewById(R.id.btnBubbleTextAdd)
        btnBubbleTextManage = findViewById(R.id.btnBubbleTextManage)
        panelBubbleTextAdd = findViewById(R.id.panelBubbleTextAdd)
        panelBubbleTextManage = findViewById(R.id.panelBubbleTextManage)
        panelBubbleTextKind = findViewById(R.id.panelBubbleTextKind)
        btnBubbleKindText = findViewById(R.id.btnBubbleKindText)
        btnBubbleKindImage = findViewById(R.id.btnBubbleKindImage)
        rowsBubbleText = findViewById(R.id.rowsBubbleText)
        txtBubbleTextEmpty = findViewById(R.id.txtBubbleTextEmpty)
        editBubbleText = findViewById(R.id.editBubbleText)
        txtBubbleTextCount = findViewById(R.id.txtBubbleTextCount)
        btnBubbleTextSave = findViewById(R.id.btnBubbleTextSave)
        btnBubbleTextCancel = findViewById(R.id.btnBubbleTextCancel)

        applyEdgeToEdge()
        // 五张"一横条 + 点管理展开"的卡片（外观尺寸…通知栏内容）
        // 每张卡展开时抄一份参数，点「取消」原样写回 ——
        // 用户要求：改了数值/文案但没点保存，点取消时改动**不保留**。
        // 快照在三个时刻更新：展开时、保存后（新基线 = 刚存下的值）、恢复默认值后。
        val sizeSnap = FloatArray(1)
        registerExpandCard(
            panelSize, btnManageSize,
            capture = { sizeSnap[0] = prefs.sizeScale },
            restore = {
                prefs.sizeScale = sizeSnap[0]
                bindSizeControls()
                // 取消回退：只是把倍率改回去并通知服务（与 2.1.3 的尺寸路径同一条），
                // 不做任何额外的"吸附/落位" —— 那套在手机上会抖、会甩到另一侧
                PetService.notifyChanged(this)
            }
        )
        val summonSnap = FloatArray(3)
        registerExpandCard(
            panelSummon, btnManageSummon,
            capture = {
                summonSnap[0] = prefs.summonTimeScale
                summonSnap[1] = prefs.summonPopScale
                summonSnap[2] = prefs.summonSpringScale
            },
            restore = {
                prefs.summonTimeScale = summonSnap[0]
                prefs.summonPopScale = summonSnap[1]
                prefs.summonSpringScale = summonSnap[2]
                bindSummonControls()
            }
        )
        val bounceSnap = FloatArray(2)
        registerExpandCard(
            panelBounce, btnManageBounce,
            capture = {
                bounceSnap[0] = prefs.bounceAmpScale
                bounceSnap[1] = prefs.bounceTimeScale
            },
            restore = {
                prefs.bounceAmpScale = bounceSnap[0]
                prefs.bounceTimeScale = bounceSnap[1]
                bindBounceControls()
            }
        )
        val breathSnap = FloatArray(2)
        registerExpandCard(
            panelBreath, btnManageBreath,
            capture = {
                breathSnap[0] = prefs.breathScale
                breathSnap[1] = prefs.breathSpeed
            },
            restore = {
                prefs.breathScale = breathSnap[0]
                prefs.breathSpeed = breathSnap[1]
                bindBreathControls()
            }
        )
        // 通知栏这条是两段**文案**（按当前形象分开存），快照也存原始模板
        val notifySnap = arrayOf("", "")
        registerExpandCard(
            panelNotify, btnManageNotify,
            capture = {
                notifySnap[0] = prefs.notifyTitle
                notifySnap[1] = prefs.notifyText
            },
            restore = {
                prefs.notifyTitle = notifySnap[0]
                prefs.notifyText = notifySnap[1]
                refreshNotifyFields()
                PetService.refreshNotification(this)
            },
            // 通知栏内容 = 文案，不是锁定要拦的"数值" —— 锁定时也能展开改（用户要求）
            lockExempt = true
        )
        setupSummonControls()
        setupBounceControls()
        // 滑条只允许按住滑块拖动 —— 必须在各 setup* 之前装好
        installThumbOnlySeekBars()
        setupBreathControls()
        setupNotifyControls()
        setupCustomSkinControls()
        setupBubbleTextControls()
        // ⚠️ loadSkins() 必须在 applyTheme() **之前**。
        //
        // applyTheme() 会顺带刷"呼出 <角色名>"按钮与通知栏那两个输入框，而角色名
        // 来自 loadSkins() 建起来的名字表；顺序反了，首次进界面时 currentSkinName()
        // 只能退回**目录名** —— 内置形象的目录名恰好就是角色名（所以一直没露馅），
        // 自定义形象的目录名是 `u<时间戳>`，会明晃晃地显示成
        // "u1750000000000 正在陪伴你"。而这两处此后没有任何地方会再刷一次。
        //
        // warmUpSkins() 读的是 `skins`，同样必须在 loadSkins() 之后 ——
        // 原来它排在前面，冷启动时预热的是一个空列表（等于没预热）。
        loadSkins()
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
        // 锁定 / 锁定互动（用户要求）。两者都要**立刻下发**给正在跑的桌宠：
        // 锁定决定它还能不能被拖动，锁定互动决定点击/长按还灵不灵。
        btnLock.setOnClickListener {
            prefs.locked = !prefs.locked
            // 一旦锁定，立刻把所有已打开的卡片收回（用户要求）：锁定的语义就是
            // "别再去动设置"，展开的卡片留在那里既碍事、又正好是锁定要拦的东西。
            // 解锁时不收（用户是来改设置的，收了反而要多点一次）。
            if (prefs.locked) collapseAllCards()
            bindLock()
            PetService.notifyChanged(this)
        }
        btnLockInteract.setOnClickListener {
            // 三态顺序循环（用户要求）：开 -> 关 -> 淡出 -> 开
            prefs.lockMode = (prefs.lockMode + 1) % 3
            bindLock()
            PetService.notifyChanged(this)
        }
        // 锁定期间参数 setter 会拒绝写入并回调到这里：提示一次，再把控件刷回存档值
        //（否则滑条/输入框会停在用户拖到的位置，看着像"改了但没生效"）。
        prefs.onBlockedAdjust = {
            Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()
            bindSizeControls()
            bindSummonControls()
            bindBounceControls()
            bindBreathControls()
        }
        bindLock()
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.sizeScale = sizeScaleOf(progress)
                bindSizeControls()
                // 大小变了要重新计算绘制尺寸与窗口尺寸，必须通知服务。
                //
                // ⚠️ 这条路**照搬 2.1.3**（= 基线）：每格发一次 notifyChanged，
                //    不在尺寸变化里做任何"额外的吸附/落位"。
                //    2.1.6~2.1.9 试过在尺寸变化时强制吸回边缘，结果是在手机上抖动、
                //    甚至把人物甩到另一侧（用户反馈"抖而且飞"）。
                //    基线的 setSizeFraction 本来就是"以附着边为基准缩放"——
                //    宠物贴边时它自己就一直是贴着的，不需要再补一刀。
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
            // 操作完成 -> 右上角按钮从「取消」变「收起」
            markCardSaved(panelSize)
        }

        // 恢复默认值：大小会改变窗口尺寸，必须通知服务
        btnResetSize.setOnClickListener {
            prefs.sizeScale = 1f
            bindSizeControls()
            clearInputFocus()
            PetService.notifyChanged(this)
            markCardSaved(panelSize)
        }

        bindSizeControls()
    }

    /**
     * 把自定义形象卡收回一横条（手风琴展开别的卡时调）。
     *
     * 与 [setCustomMode] 是同一个入口 —— 那边现在已经不刷主题了，
     * 所以不会连带把通知栏里正在编辑的文字刷掉。
     */
    private fun collapseCustomCard() {
        if (!::btnCustomManage.isInitialized) return
        if (customMode == CUSTOM_NONE) return
        setCustomMode(CUSTOM_NONE)
    }

    // ---------------------------------------------------------------- 可展开卡片

    /** 一张"一横条 + 点管理展开"的卡片：展开区 + 右上角那个「管理 / 取消 / 收起」按钮 */
    private class ExpandCard(
        val body: View,
        val manage: Button,
        /** 展开时把这张卡的参数抄一份 */
        val capture: () -> Unit,
        /** 把抄下来的那份原样写回（点「取消」时用） */
        val restore: () -> Unit
    ) {
        /**
         * 展开之后是否已经"操作完成"（点过「保存」或「恢复默认值」）。
         *
         * 决定按钮文案（用户要求）：展开后是**取消**；点过保存就变**收起**；
         * 之后再碰一下这张卡，又回到**取消**。
         */
        var saved = false
    }

    private val expandCards = ArrayList<ExpandCard>()

    /**
     * 登记一张可展开卡片（用户要求：外观尺寸 / 呼出回弹 / 边框回弹 / 呼吸 /
     * 通知栏内容 都做成自定义形象那种"平常收起、点管理展开"）。
     *
     * ⚠️ 这里**不调 applyTheme()**：展开区里的控件都是 XML 里的静态视图，
     *    上一次主题刷色已经把它们刷好了；而 applyTheme() 会顺带
     *    `refreshNotifyFields()`，那会把通知栏两个输入框里**正在编辑**的内容
     *    覆盖成存档值（展开/收起任何一张卡都会误伤）。
     */
    private fun registerExpandCard(
        body: View,
        manage: Button,
        capture: () -> Unit = {},
        restore: () -> Unit = {},
        /**
         * 锁定时**照样可以展开**（用户要求）。
         * 目前只有「通知栏内容」用它 —— 那两项是文案，不属于锁定要拦的"数值"。
         */
        lockExempt: Boolean = false
    ) {
        val card = ExpandCard(body, manage, capture, restore)
        expandCards.add(card)
        // 「用户碰过这张卡」由 CardPanel 上报（它是 dispatchTouchEvent 那一层才收得到，
        //  挂在容器上的 OnTouchListener 收不到 —— 触摸目标是子控件）
        (body as? CardPanel)?.onUserTouch = { markCardDirty(card) }
        manage.setOnClickListener {
            // 锁定期间**卡片不能被展开**（用户要求）：点「管理」只提示「请解除锁定以操作」。
            // ⚠️ 三个例外都要留着：
            //   · 已经展开的卡片必须还能「收起 / 取消」，否则锁定时界面卡在展开态出不来；
            //   · 「当前形象」那个二级菜单（切换形象）锁定时照常可用；
            //   · 标了 [lockExempt] 的卡片（「通知栏内容」）锁定时也能展开 ——
            //     里面调的是通知文案，不是锁定要拦的"数值"（用户要求）。
            if (prefs.locked && !lockExempt && body.visibility != View.VISIBLE) {
                Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (body.visibility == View.VISIBLE) {
                // 收起前：这次展开后没点过保存 -> 把改动丢掉（用户要求）
                if (!card.saved) card.restore()
                setCardExpanded(body, false)
            } else {
                setCardExpanded(body, true)
            }
        }
        setCardExpanded(body, false)
    }

    /**
     * 展/收一张卡片。
     *
     * **手风琴**（用户要求）：展开某一张时，其余打开的卡片（含自定义形象卡的展开区、
     * 以及「当前形象」的二级菜单）立刻关闭 —— 一次只留一块展开。
     *
     * 展开时把"操作完成"标志清掉 -> 按钮显示**取消**（用户要求：没点过保存就是取消）。
     */
    private fun setCardExpanded(body: View, expanded: Boolean) {
        if (expanded) {
            for (c in expandCards) if (c.body !== body) setCardExpanded(c.body, false)
            collapseCustomCard()
            collapseBubbleTextCard()
            // 二级菜单也算"展开的卡片"（用户要求）
            closeSkinMenuIfOpen()
        }
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        expandCards.firstOrNull { it.body === body }?.let { card ->
            if (expanded) {
                card.saved = false
                // 记下展开这一刻的取值：这次展开里如果没点保存，点「取消」就回到这里
                card.capture()
            }
            refreshCardButton(card)
        }
        // 收起时收掉焦点与键盘，免得输入框在看不见的地方亮着
        if (!expanded) clearInputFocus()
    }

    /** 用户又碰了这张卡：未保存状态，按钮回到**取消** */
    private fun markCardDirty(card: ExpandCard) {
        if (!card.saved) return
        card.saved = false
        refreshCardButton(card)
    }

    /** 操作完成（保存 / 恢复默认值）：按钮变成**收起** */
    private fun markCardSaved(body: View) {
        val card = expandCards.firstOrNull { it.body === body } ?: return
        card.saved = true
        // 刚存下的值成为新基线：之后再改再点「取消」，回到的是这里而不是展开前的值
        card.capture()
        refreshCardButton(card)
    }

    /** 右上角按钮的文案：收起态=管理，展开且已保存=收起，展开未保存=取消 */
    private fun refreshCardButton(card: ExpandCard) {
        val expanded = card.body.visibility == View.VISIBLE
        card.manage.text = getString(
            when {
                !expanded -> R.string.card_manage
                card.saved -> R.string.card_collapse
                else -> R.string.card_cancel
            }
        )
    }

    /**
     * 把所有卡片收回一横条（不含自定义形象卡的展开区，那由 [collapseCustomCard] 管）。
     *
     * 两个地方调：
     *   · 进入应用时（见 [onResume]）：冷启动、或离开超过 [CARD_RESET_AFTER_MS] 再回来，
     *     一律回到"全收起"的初始状态（用户要求）；
     *   · 点「锁定」的那一刻（用户要求：一旦锁定立刻关闭所有已打开的卡片）。
     */
    private fun collapseAllCards() {
        for (c in expandCards) setCardExpanded(c.body, false)
        collapseCustomCard()
        // 「气泡内容」卡也算一张（用户 2026-10-16：锁定时它也要跟着收起）——
        // ⚠️ 只是**收起**：这张卡不受锁定限制，收起后照样能再点开、照样能增删改
        //    （与「自定义形象」同性质）。
        collapseBubbleTextCard()
        // 二级菜单同样要收（用户要求：它也算一张展开的卡片）
        closeSkinMenuIfOpen()
    }

    // ---------------------------------------------------------------- 复位

    /**
     * 复位：大小与回弹恢复默认倍率，桌宠回到右边框中点。
     *
     * 服务没在跑时只提示 —— 用户点它是为了"出问题时找回桌宠"，
     * 桌宠本来就不在，直接告诉他先开启。
     */
    private fun doReset() {
        // 复位会把各项倍率清零 —— 属于"改数值"，锁定期间一并拦下（用户要求）
        if (prefs.locked) {
            Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()
            return
        }
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
        // 退出应用再回来（冷启动、热启动都算）一律把卡片恢复成"全收起"，
        // 二级菜单也收（用户要求：不再等 1 分钟）。
        // 唯一豁免：刚从相册 / 文件选择器返回 —— 那不是"退出应用"，
        // 而且收起会把新增表单里刚选的图清掉（见 returningFromPickerAt）。
        val fromPicker = android.os.SystemClock.elapsedRealtime() - returningFromPickerAt < 5_000
        returningFromPickerAt = 0L
        if (!fromPicker) collapseAllCards()
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

        // 锁定按钮：通知栏那个「锁定 / 解锁」也能翻这个状态，
        // 回到 App 时得把两个按钮的文案跟 prefs 对齐（用户要求：收起的右边那个动作）
        bindLock()

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
        val found = PetSprite.listSkins(this)
        skins = if (found.isEmpty()) listOf(Prefs.DEFAULT_SKIN) else found
        // 只读 pet.json 拿显示名，**不再 load**（那会解码每个形象的两张 PNG）
        val labels = skins.map { dir -> PetSprite.readName(this, dir) ?: dir }
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
        // 每次重建都回到顶部：换角色 / 切明暗都会重建，带着上次的滚动位置会很怪
        skinMenuScroll.resetScroll()
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
        // 行数超了就把视口压到"一页 5 行"（用户要求），多出来的在里面拖
        limitSkinMenuHeight()
        // 布局完成后再用**实测行高**校准一次（见 refineSkinMenuHeight 的注释）
        skinMenu.post { refineSkinMenuHeight() }
    }

    /**
     * 二级菜单的视口高度：**最多显示 [SKIN_MENU_VISIBLE_ROWS] 行**，多出来的滚动。
     *
     * 高度**现算、不写死 dp**：行高取决于系统字号（行是 12dp 内边距 + 15sp 文字），
     * 写死会在放大字号时把第 5 行切掉一半。这里量的是"前 5 行 + 它们之间的 4 条
     * 分隔线"的总高，正好一页。
     *
     * ⚠️ 用 `measure()` 而不是读 `bottom`：菜单是 GONE 时子视图没有布局过，
     * bottom/height 全是 0（读 bottom 会在收起状态下把视口设成 0 高，
     * 下次展开就空了）；measure 不依赖 attach 状态。
     */
    private fun limitSkinMenuHeight() {
        if (!::skinMenuScroll.isInitialized) return
        val lp = skinMenuScroll.layoutParams ?: return
        if (skinMenuRows().size <= SKIN_MENU_VISIBLE_ROWS) {
            setMenuViewportHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        // 宽度给菜单的实测宽（没布局过就退回屏宽）；行是单行不换行，宽度不影响行高
        val w = if (skinMenu.width > 0) skinMenu.width else resources.displayMetrics.widthPixels
        val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var h = 0
        var rows = 0
        for (i in 0 until skinMenu.childCount) {
            val c = skinMenu.getChildAt(i)
            c.measure(wSpec, hSpec)
            h += c.measuredHeight
            if (c is TextView) {
                rows++
                if (rows == SKIN_MENU_VISIBLE_ROWS) break
            }
        }
        if (h > 0) setMenuViewportHeight(h)
        // 布局完成后再用**实测行高**校准一次：measure() 出来的行高比真正布局的小几个
        // 像素（实测 158 vs 162），只按估算值会把第 5 行切掉一截。
        skinMenu.post { refineSkinMenuHeight() }
    }

    /**
     * 用**已经布局好的**行高重算视口高度（[limitSkinMenuHeight] 的估算值只作兜底）。
     *
     * 菜单收起时子视图没有布局（height 全是 0），这里直接返回、保留估算值。
     */
    private fun refineSkinMenuHeight() {
        val rows = skinMenuRows()
        if (rows.size <= SKIN_MENU_VISIBLE_ROWS) {
            setMenuViewportHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        val rowH = rows[0].height
        if (rowH <= 0) return
        val dividerH = (0 until skinMenu.childCount)
            .map { skinMenu.getChildAt(it) }
            .firstOrNull { it !is TextView }?.height ?: 0
        setMenuViewportHeight(rowH * SKIN_MENU_VISIBLE_ROWS + dividerH * (SKIN_MENU_VISIBLE_ROWS - 1))
    }

    private fun setMenuViewportHeight(h: Int) {
        val lp = skinMenuScroll.layoutParams ?: return
        if (lp.height == h) return
        lp.height = h
        skinMenuScroll.layoutParams = lp
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
            // 二级菜单也算"一张展开的卡片"（用户要求）：开它就收起其余所有展开区
            // （五张参数卡 + 自定义形象卡的展开区）
            for (c in expandCards) setCardExpanded(c.body, false)
            collapseCustomCard()
            buildSkinMenu()
            skinMenuWrap.visibility = View.VISIBLE
            // 展开 -> 三角正立（旋转 180°）。直接设值，没有过渡动画。
            icoSkinArrow.rotation = 180f
            // 展开这一刻的行高才是真的（上面那次 buildSkinMenu 时还是 GONE），
            // 再校一次视口高度，保证第 5 行完整可见
            skinMenu.post { refineSkinMenuHeight() }
        } else {
            closeSkinMenuNow()
            return
        }
        // ⚠️ 必须重刷一次主题，而且要在 visibility 与菜单项都就绪之后：
        //   · 菜单项是**刚新造**的视图，不在上一次 AccentTheme.apply 的遍历里，
        //     不补这次的话文字色/分隔线不对；
        //   · 名称框的圆角要跟着开合变（展开 -> 下方两角直角，与菜单连成一整块）。
        // ⚠️ 但**不要**顺带刷新通知栏两个输入框（refreshNotify = false）：
        //    角色没变、模板不需要重新解析，刷了只会把正在编辑的文字覆盖成存档值。
        applyTheme(refreshNotify = false)
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
        // 名称框的下方两角要恢复圆角（菜单收起了，不再与它相连）。
        //
        // ⚠️ 这里**只改这一处背景**，不调 applyTheme()：手风琴每次展开别的卡都会
        //    收一次菜单，而 applyTheme() 会顺带 refreshNotifyFields()，
        //    把通知栏里正在编辑的文字刷掉。（选中角色那条路会自己再调一次
        //    applyTheme()，该刷的照样刷得到。）
        boxSkin.background = AccentTheme.skinBoxBackground(this, accent, lightMode, connected = false)
    }

    /** 二级菜单开着就收起来（手风琴与"进应用全收起"都要用） */
    private fun closeSkinMenuIfOpen() {
        if (::skinMenuWrap.isInitialized && skinMenuWrap.visibility == View.VISIBLE) {
            closeSkinMenuNow()
        }
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

    // ---------------------------------------------------------------- 自定义形象

    /**
     * 卡片三种形态的**唯一**切换点：收起（一横条）/ 新增表单 / 管理列表。
     *
     * 用户要求：
     *   · 默认只有一横条 —— 标题 + 右侧「新增」「管理」；
     *   · 点「新增」卡片向下展开表单，**这个按钮变成「保存」、右边那个变成「取消」**；
     *   · 点「管理」向下展开标签列表，**右边那个也变成「取消」**；
     *   · 「取消」= **退一级**：管理态下有标签正等"确认删除"就先退回管理列表，
     *     否则收回一横条；「取消」在新增态等于放弃本次新增（图与名字一起清掉）；
     *   · 保存成功也收回一横条。
     *
     * 所以右侧按钮的文案只看"卡片是不是展开着"（收起=管理、展开=取消），
     * 与当前是新增还是管理**无关**；左侧按钮才是"收起=新增、新增态=保存"。
     * 而"退一级"的具体分支在 [setupCustomSkinControls] 的点击回调里。
     *
     * ⚠️ 两个展开区**互斥**，只在这里改可见性：同时可见会出现
     * "新增表单下面挂着删除标签"的错位画面。
     * 也正因为互斥，两个按钮的文案必须在这里一起定，不能各写各的。
     */
    /**
     * 自定义形象卡右上角按钮的**文案与颜色**（用户要求）：
     *
     * | 状态 | 文案 | 颜色 |
     * |---|---|---|
     * | 收起（CUSTOM_NONE） | 管理 | 中性文字 + 发色描边 |
     * | 管理列表、没有待确认的删除 | **收起** | 同上 |
     * | 管理列表、有标签等着确认删除 | 取消 | **文字红**、描边仍是发色 |
     * | 新增表单 | 取消 | 同上 |
     *
     * 也就是说：只有"按下去会**丢掉**东西"的时候才叫「取消」（丢掉表单输入、
     * 或退掉那个待确认的删除），红的是**这两个字**，框仍然跟人物发色、与其它卡片一致。
     *
     * 描边**不在这里设**：交给 AccentTheme.apply 统一发（它是发色镂空样式），
     * 这里只覆盖文字色。
     */
    private fun refreshCustomManageButton() {
        val cancel = customMode == CUSTOM_ADD ||
            (customMode == CUSTOM_MANAGE && pendingDeleteId != null)
        btnCustomManage.text = getString(
            when {
                customMode == CUSTOM_NONE -> R.string.card_manage
                cancel -> R.string.card_cancel
                else -> R.string.card_collapse
            }
        )
        btnCustomManage.setTextColor(
            if (cancel) TAG_DELETE_RED else AccentTheme.skinBoxText(lightMode)
        )
    }

    private fun setCustomMode(mode: Int) {
        customMode = mode
        // 手风琴：**自定义形象卡展开时，其余卡片也要一并收起**（用户要求）。
        // 反方向（别的卡展开时收起这张）在 setCardExpanded 里 —— 两个方向都要写，
        // 少一个方向就会出现"两张卡同时展开"（上一版就漏了这个方向）。
        // 二级菜单也算"展开的卡片"，同样要收。
        if (mode != CUSTOM_NONE) {
            for (c in expandCards) setCardExpanded(c.body, false)
            closeSkinMenuIfOpen()
        }
        panelCustomAdd.visibility = if (mode == CUSTOM_ADD) View.VISIBLE else View.GONE
        panelCustomManage.visibility = if (mode == CUSTOM_MANAGE) View.VISIBLE else View.GONE
        btnCustomAdd.text = getString(
            if (mode == CUSTOM_ADD) R.string.custom_save else R.string.custom_add
        )
        // 文案与颜色由状态机统一算（管理/收起/取消、取消时文字红）
        refreshCustomManageButton()
        if (mode == CUSTOM_ADD) {
            // 名称框的**浅色占位**就是"留空时会用的默认名"（用户要求）：
            // 每次进来按当前数量重算（已有 2 个就是「自定义人物3」）。
            // 一输入它就消失（hint 的固有行为），而留空保存时用的正是这个名字 ——
            // 所以 hint 与 saveCustomSkin 里的兜底名必须是同一个算法。
            editCustomName.hint = getString(
                R.string.custom_default_name, CustomSkinStore.list(this).size + 1
            )
        }
        if (mode == CUSTOM_MANAGE) {
            renderCustomTags()
        } else {
            // 离开管理态（含点「取消」退回一横条）：待确认删除也一并作废，
            // 否则下次进来那个标签会直接顶着「确认删除」，一点就没了
            pendingDeleteId = null
        }
        if (mode != CUSTOM_ADD) {
            clearCustomForm()
            clearInputFocus()
        }
        // ⚠️ 这里**不调 applyTheme()**：展开区里的方块靠 tag=accentBox、标签由
        //    renderCustomTags() 自己上色，上一次刷主题时就都刷好了；
        //    而 applyTheme() 会顺带 refreshNotifyFields()，把通知栏两个输入框里
        //    **正在编辑**的内容覆盖成存档值 —— 手风琴收起别的卡时会被误伤。
    }

    // ---------------------------------------------------------------- 气泡内容卡

    /**
     * 「气泡内容」卡：形态切换（收起 / 新增 / 管理）。
     *
     * 与自定义形象卡同一套约定，只有两点不同（都是用户要求）：
     *   · **锁定时照常可用**（内容是文案，与「通知栏内容」同性质）；
     *   · 管理列表**一行一个**标签（自定义形象是一行两个）。
     */
    private fun setBubbleTextMode(mode: Int) {
        bubbleTextMode = mode
        // 手风琴：这张卡展开时其余卡片一并收起（反方向在 setCardExpanded 里）
        if (mode != BUBBLE_NONE) {
            for (c in expandCards) setCardExpanded(c.body, false)
            collapseCustomCard()
            closeSkinMenuIfOpen()
        }
        panelBubbleTextKind.visibility = if (mode == BUBBLE_KIND) View.VISIBLE else View.GONE
        panelBubbleTextAdd.visibility = if (mode == BUBBLE_ADD) View.VISIBLE else View.GONE
        panelBubbleTextManage.visibility = if (mode == BUBBLE_MANAGE) View.VISIBLE else View.GONE
        btnBubbleTextAdd.text = getString(
            if (mode == BUBBLE_ADD) R.string.custom_save else R.string.custom_add
        )
        refreshBubbleTextButton()
        if (mode == BUBBLE_ADD) {
            // 进表单就把字数提示刷成当前值（输入框在 addTextChangedListener 里实时更新）
            refreshBubbleTextCount()
        } else {
            // 离开新增态：待确认删除一并作废（否则下次进来那个标签直接顶着「确认删除」）
            if (mode != BUBBLE_MANAGE) pendingBubbleDelete = null
            editBubbleText.setText("")
            clearInputFocus()
        }
        pendingBubbleDelete = if (mode == BUBBLE_MANAGE) pendingBubbleDelete else null
        if (mode == BUBBLE_MANAGE) renderBubbleTextTags()
        // ⚠️ 这里不调 applyTheme()：理由与 setCustomMode 相同（会误刷通知栏输入框）
    }

    /** 把这张卡收回一横条（手风琴展开别的卡时调） */
    private fun collapseBubbleTextCard() {
        if (!::btnBubbleTextManage.isInitialized) return
        if (bubbleTextMode == BUBBLE_NONE) return
        setBubbleTextMode(BUBBLE_NONE)
    }

    /**
     * 右上角按钮的文案与颜色。
     *
     * 收起 -> 「管理」；管理列表里**有待确认删除** -> 「取消」（红字）；
     * 其余（选类型 / 输入表单 / 管理列表）-> 「收起」。
     *
     * ⚠️ 用户 2026-10-16 明确："这两个界面的取消改成收起" —— 也就是**选类型**与**输入表单**
     *    这两步不再显示「取消」（它们并没有会丢东西的中间态，只是把展开区收回去），
     *    只有"管理列表里点了删除、等你确认"那一步才叫「取消」（那才是真的会退掉一个操作）。
     */
    private fun refreshBubbleTextButton() {
        val cancel = bubbleTextMode == BUBBLE_MANAGE && pendingBubbleDelete != null
        btnBubbleTextManage.text = getString(
            when {
                bubbleTextMode == BUBBLE_NONE -> R.string.card_manage
                cancel -> R.string.card_cancel
                else -> R.string.card_collapse
            }
        )
        btnBubbleTextManage.setTextColor(
            if (cancel) TAG_DELETE_RED else AccentTheme.skinBoxText(lightMode)
        )
    }

    /** 输入框下方的「n / 20 字」（按码点数，emoji 算 1 个字） */
    private fun refreshBubbleTextCount() {
        txtBubbleTextCount.text = getString(
            R.string.bubble_text_count,
            BubbleTextStore.charCount(editBubbleText.text?.toString() ?: ""),
            BubbleTextStore.MAX_CHARS
        )
    }

    /**
     * 管理列表：**一行一个整条横条**（用户 2026-10-16 重做）——
     * 横条 = 内容 + 右侧「删除 / 确认」+ 右侧「启用 / 已启用」。
     *
     * ⚠️ 两个按钮的**列位置是抄标题行**的（删除对齐「新增」、启用对齐「管理/收起」），
     *    而标题行要布局完才有宽度 —— 首次渲染发生在 onCreate 里、那时量到 0，
     *    所以量到 0 时延后一帧重排一次（有标志位，不会循环）。
     *
     * ⚠️ 只在"列表本身变了"（进管理、增删、切主题）时重建。
     *    点「删除 / 启用」**不重建**，只原地改按钮文字与颜色（[refreshBubbleTagButtons]）——
     *    重建会让所有标签的按钮一起重新出现，用户看到的就是"所有按钮闪一下"。
     */
    private fun renderBubbleTextTags() {
        rowsBubbleText.removeAllViews()
        bubbleTagRows.clear()
        val items = BubbleTextStore.items(this)
        txtBubbleTextEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val gap = (resources.displayMetrics.density * 8f).toInt()
        for (i in items.indices) {
            val row = makeBubbleTextTag(i, items[i], topMargin = if (i == 0) 0 else gap)
            bubbleTagRows.add(row)
            rowsBubbleText.addView(row)
        }
        refreshBubbleTextButton()

        if (btnBubbleTextAdd.width == 0 || btnBubbleTextManage.width == 0) {
            if (!bubbleTagsAwaitLayout) {
                bubbleTagsAwaitLayout = true
                rowsBubbleText.post {
                    bubbleTagsAwaitLayout = false
                    if (bubbleTextMode == BUBBLE_MANAGE) renderBubbleTextTags()
                }
            }
        }
    }

    /**
     * **原地**刷新每一行按钮的文字与颜色（不重建视图）。
     *
     * 点「删除」-> 本行变「确认」、其余行保持「删除」、顶部按钮变「取消」；
     * 点「启用 / 已启用」-> 只改本行按钮。都不重建，所以不会闪。
     */
    private fun refreshBubbleTagButtons() {
        val items = BubbleTextStore.items(this)
        for ((i, row) in bubbleTagRows.withIndex()) {
            val item = items.getOrNull(i) ?: continue
            val pending = i == pendingBubbleDelete
            row.findViewById<TextView>(R.id.txtBubbleTagDelete)?.text = getString(
                if (pending) R.string.bubble_text_confirm else R.string.bubble_text_delete
            )
            row.findViewById<TextView>(R.id.btnBubbleTagToggle)?.apply {
                text = getString(
                    if (item.enabled) R.string.bubble_text_enabled else R.string.bubble_text_disabled
                )
                setTextColor(if (item.enabled) accent else AccentTheme.skinBoxText(lightMode))
            }
        }
        // 顶部按钮（管理/收起/取消）的文案也要跟着待确认状态走
        refreshBubbleTextButton()
    }

    /** 标题行里两个按钮之间的间距（启用按钮要靠它对齐到与「管理 / 收起」同一列） */
    private fun titleButtonGap(): Int {
        val lp = btnBubbleTextManage.layoutParams as? android.widget.LinearLayout.LayoutParams
        return lp?.marginStart ?: dp(resources.displayMetrics, 8f)
    }

    /**
     * 一条气泡内容的**整条横条**：内容 + 「删除 / 确认」+「启用 / 已启用」。
     *
     * 列位置（用户要求）：
     *   · 删除按钮宽度 = 标题行「新增」按钮宽度，间距 = 标题行两按钮间距
     *     -> 它的右边界与「新增」右边界对齐（内容占剩余宽度，于是"删除"正好落在新增那列）；
     *   · 启用按钮宽度 = 标题行「管理 / 收起 / 取消」按钮宽度
     *     -> 与它同列同宽。
     * 高度：两个按钮都 `match_parent`，于是与横条等高（用户要求）。
     *
     * 删除是**就地两步**：点「删除」-> 本按钮变「确认」+ 顶部按钮变「取消」（见
     * [refreshBubbleTextButton]）-> 点确认才删、点取消退回上一步。
     */
    private fun makeBubbleTextTag(index: Int, item: BubbleTextStore.Item, topMargin: Int): View {
        // 根布局是 LinearLayout（内容框 + 两个按钮并排）
        val tag = layoutInflater.inflate(R.layout.item_bubble_text_tag, rowsBubbleText, false)
            as android.widget.LinearLayout
        tag.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }
        // ⚠️ 描边只给**左边的内容框**（用户 2026-10-17：内容单独框起来，按钮放到框外）
        tag.findViewById<android.widget.LinearLayout>(R.id.boxBubbleTagContent).background =
            AccentTheme.accentBoxBackground(this, accent, lightMode)

        val dm = resources.displayMetrics
        val gap = titleButtonGap()
        val delW = btnBubbleTextAdd.width.coerceAtLeast(dp(dm, 56f))
        val togW = btnBubbleTextManage.width.coerceAtLeast(dp(dm, 64f))

        tag.findViewById<BubbleTagTextView>(R.id.txtBubbleTagText).apply {
            // ⚠️ 用 setFullText：截断与"补三个点"由这个 View 自己在**布局阶段**做，
            //    不是先塞全文再 `post{}` 改 —— 后者会让省略号闪一下、文字瞬移
            //    （用户 2026-10-17 实测反馈）。
            setFullText(item.text)
            setTextColor(AccentTheme.skinBoxText(lightMode))
        }

        // 图片条目：把缩略图放在标签**最前面（第一个字的位置）**。
        // 用户 2026-10-16 先要求"跟文字相同的大小"，随后改为**字号的 4 倍**。
        // 用 inSampleSize 直接解到接近缩略图尺寸，20 条一起建也不会慢。
        if (item.isImage) {
            val side = (editBubbleText.textSize * BUBBLE_TAG_IMAGE_SCALE)
                .toInt().coerceAtLeast(dp(dm, 16f))
            val iv = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(side, side)
                    .apply { marginEnd = gap / 2 }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            BubbleTextStore.imageFile(this, item.image)?.let { f ->
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(f, side)
                }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.let {
                    iv.setImageBitmap(it)
                }
            }
            tag.findViewById<android.widget.LinearLayout>(R.id.boxBubbleTagContent).addView(iv, 0)
        }

        val pending = index == pendingBubbleDelete
        tag.findViewById<TextView>(R.id.txtBubbleTagDelete).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(delW, ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginStart = gap }
            text = getString(
                if (pending) R.string.bubble_text_confirm else R.string.bubble_text_delete
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(TAG_DELETE_RED)                       // 红字固定，不跟强调色
            // 背景**立刻**给（圆角先用上限值），等这一帧量到高度后再按高度精修 ——
            // 若留到 post 里才设，按钮会"先没背景、后出背景"，点一下所有按钮闪一下。
            background = AccentTheme.accentBoxBackground(
                this@MainActivity, TAG_DELETE_RED, lightMode, radiusDp = 8f
            )
            post {
                background = AccentTheme.accentBoxBackground(
                    this@MainActivity, TAG_DELETE_RED, lightMode,
                    radiusDp = AccentTheme.radiusForHeight(height, resources.displayMetrics, 8f)
                )
            }
            setOnClickListener {
                // ⚠️ **现读**当前是否处于待确认，不能用创建时捕获的 `pending`：
                //    点「删除」只原地更新按钮文字、不重建视图，捕获值永远停在"第一次点击之前"
                //    -> 再点「确认」时它以为还是第一次，只是又把状态设成待确认，标签永远删不掉
                //    （用户实测："点击确认之后并没有删除标签"）。
                if (pendingBubbleDelete == index) {
                    BubbleTextStore.removeAtItem(this@MainActivity, index)
                    pendingBubbleDelete = null
                    renderBubbleTextTags()          // 列表少了一条、下标会前移 -> 只能重建
                    // ⚠️ 这里**不弹**"已删除 xxx"的提示（用户 2026-10-16：
                    //    "删除的时候，不要出现…已删除…"）—— 标签当场消失本身就是反馈。
                } else {
                    // 进入待确认：本按钮变「确认」、顶部变「取消」——
                    // 只原地刷按钮文字，不重建列表（重建会让所有按钮闪一下）
                    pendingBubbleDelete = index
                    refreshBubbleTagButtons()
                }
            }
        }

        tag.findViewById<TextView>(R.id.btnBubbleTagToggle).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(togW, ViewGroup.LayoutParams.MATCH_PARENT)
                .apply { marginStart = gap }
            text = getString(
                if (item.enabled) R.string.bubble_text_enabled else R.string.bubble_text_disabled
            )
            // ⚠️ 用户要求「已启用」三个字**排在一行**：列宽受"与收起对齐"约束不能加宽，
            //    所以固定单行 + 允许系统自动缩字号去适配（最小 8sp），并压掉左右内边距。
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            setPadding(dp(dm, 2f), 0, dp(dm, 2f), 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(
                    8, 13, 1, TypedValue.COMPLEX_UNIT_SP
                )
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            background = AccentTheme.ghostSelectorFor(this@MainActivity, accent, lightMode)
            setTextColor(
                if (item.enabled) accent else AccentTheme.skinBoxText(lightMode)
            )
            // 同删除按钮：立刻给背景，量到高度后再精修圆角（避免"没背景 -> 有背景"的闪烁）
            post {
                background = AccentTheme.ghostSelectorFor(
                    this@MainActivity, accent, lightMode,
                    radiusDp = AccentTheme.radiusForHeight(height, resources.displayMetrics, 10f)
                )
            }
            setOnClickListener {
                // ⚠️ 同上：**现读**当前启用状态。用创建时捕获的 `item.enabled` 的话，
                //    第一次切换后捕获值就过期了，再点算出的目标值还是同一个 ->
                //    "切换一次之后就切不动了"（用户实测）。
                val cur = BubbleTextStore.items(this@MainActivity).getOrNull(index)
                    ?: return@setOnClickListener
                BubbleTextStore.setEnabled(this@MainActivity, index, !cur.enabled)
                // 只原地刷按钮（不重建列表：重建会让所有按钮闪一下）
                refreshBubbleTagButtons()
            }
        }
        return tag
    }

    /** 保存按钮：把输入框里的内容存成一条（达到 20 条 / 内容为空时给提示，不关表单） */
    private fun saveBubbleText() {
        val raw = editBubbleText.text?.toString() ?: ""
        if (raw.isBlank()) {
            Toast.makeText(this, R.string.bubble_text_need_text, Toast.LENGTH_SHORT).show()
            return
        }
        if (BubbleTextStore.list(this).size >= BubbleTextStore.MAX_ITEMS) {
            Toast.makeText(this, R.string.bubble_text_full, Toast.LENGTH_SHORT).show()
            return
        }
        BubbleTextStore.add(this, raw)
        Toast.makeText(this, R.string.bubble_text_saved, Toast.LENGTH_SHORT).show()
        // 存完回到一横条（与自定义形象的「保存」一致）
        setBubbleTextMode(BUBBLE_NONE)
    }

    /**
     * 缩略图解码用的 inSampleSize：把长边降到接近 [target] 像素。
     * 图片条目最多 20 条，不采样的话进一次管理要解 20 张大图。
     */
    private fun sampleSizeFor(f: java.io.File, target: Int): Int {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        var s = 1
        var long = maxOf(opts.outWidth, opts.outHeight)
        while (long / 2 >= target.coerceAtLeast(1) * 2) {
            s *= 2
            long /= 2
        }
        return s
    }

    /**
     * 把 [text] 截到"在 [width] 宽、[maxLines] 行以内放得下"的最长前缀。
     *
     * 用系统的 [android.text.StaticLayout] 试排一次（与 TextView 自己同一套折行规则），
     * 行数没超就原样返回；超了取前 maxLines 行能放下的部分。
     */
    private fun fitLines(
        text: String, paint: android.text.TextPaint, width: Int, extraSpacing: Float, maxLines: Int
    ): String {
        if (width <= 0) return text
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setLineSpacing(extraSpacing, 1f)
            .build()
        if (layout.lineCount <= maxLines) return text
        // ⚠️ 少留一个字：后面还要补"..."，正好一个汉字的宽度
        val end = (layout.getLineEnd(maxLines - 1) - 1).coerceIn(1, text.length)
        return text.substring(0, end).trimEnd()
    }

    /**
     * 「新增」先给两个选项（用户 2026-10-16）：**文本** 或 **图片**。
     *
     * ⚠️ 选项**长在卡片里**（卡片向下延伸出 [panelBubbleTextKind] 那一小块），
     *    不弹对话框 —— 用户明确要求"应该是卡片向下延伸，在卡片里选择"。
     *
     * · 文本 -> 再展开原来的输入表单（[BUBBLE_ADD]）；
     * · 图片 -> 直接开系统文件选择器（`ACTION_OPEN_DOCUMENT`，类型选所有图片）——
     *   常见格式与 GIF 都在里面（GIF 取第一帧转存成 PNG，见 [BubbleTextStore.importImage]）。
     *
     * ⚠️ 注释里**不要**出现 "image/" 后面紧跟星号那种写法：Kotlin 的块注释可以嵌套，
     *    那个斜杠星号会再开一层注释，后面的结束符只关掉内层 —— 整个文件从此"注释未闭合"。
     */
    private fun chooseBubbleKind() {
        if (BubbleTextStore.items(this).size >= BubbleTextStore.MAX_ITEMS) {
            Toast.makeText(this, R.string.bubble_text_full, Toast.LENGTH_SHORT).show()
            return
        }
        setBubbleTextMode(BUBBLE_KIND)
    }

    /** 选图片：与自定义形象用同一套（系统文件选择器，无需任何存储权限） */
    private fun pickBubbleImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_BUBBLE_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.custom_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBubbleTextControls() {
        btnBubbleTextAdd.setOnClickListener {
            // 不受锁定限制（用户要求）：增删照常可用
            // ⚠️ 新增时**先给两个选项**（用户 2026-10-16）：文本 / 图片。
            //    在新增态里这个按钮是「保存」，所以只有非新增态才弹选择。
            if (bubbleTextMode == BUBBLE_ADD) saveBubbleText() else chooseBubbleKind()
        }
        btnBubbleTextManage.setOnClickListener {
            when {
                bubbleTextMode == BUBBLE_MANAGE && pendingBubbleDelete != null -> {
                    pendingBubbleDelete = null
                    // ⚠️ 只原地刷按钮文字，**不重建列表**：重建会让每个标签重新布局，
                    //    省略号那一帧的变化看起来就是"其他标签闪一下、文字瞬移"
                    //    （用户 2026-10-17 实测反馈）。
                    refreshBubbleTagButtons()
                }
                else -> setBubbleTextMode(
                    if (bubbleTextMode == BUBBLE_NONE) BUBBLE_MANAGE else BUBBLE_NONE
                )
            }
        }
        btnBubbleKindText.setOnClickListener { setBubbleTextMode(BUBBLE_ADD) }
        btnBubbleKindImage.setOnClickListener { pickBubbleImage() }
        btnBubbleTextSave.setOnClickListener { saveBubbleText() }
        btnBubbleTextCancel.setOnClickListener { setBubbleTextMode(BUBBLE_NONE) }
        editBubbleText.filters = arrayOf(BubbleTextStore.inputFilter())
        editBubbleText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                refreshBubbleTextCount()
                // 输入被 20 字上限吃掉时明确提示一次（否则用户会以为"标签把内容截断了"）
                if (BubbleTextStore.consumeTruncated()) {
                    Toast.makeText(
                        this@MainActivity, R.string.bubble_text_reach_limit, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        editBubbleText.setOnEditorActionListener { _, _, _ -> clearInputFocus(); true }
        renderBubbleTextTags()
    }

    private fun setupCustomSkinControls() {        // 不在新增态时是「新增」，在新增态时是「保存」（文案在 setCustomMode 里定）
        btnCustomAdd.setOnClickListener {
            // 自定义形象**不受锁定限制**（用户要求）：新增/管理照常可用
            if (customMode == CUSTOM_ADD) saveCustomSkin() else setCustomMode(CUSTOM_ADD)
        }
        // 「取消 / 收起 / 管理」= 三个不同意思（用户要求）：
        //   · 管理列表里有点错的标签 -> 「取消」先把那个待确认的删除退掉（退回列表）
        //   · 管理列表本身 -> 「收起」，列表里没有"未保存的东西"
        //   · 新增表单 -> 「取消」= 放弃本次新增（名字与素材一起清掉）
        //   · 横条态 -> 「管理」= 展开列表
        btnCustomManage.setOnClickListener {
            // 自定义形象**不受锁定限制**（用户要求）：管理列表、删除、取消都照常可用
            when {
                customMode == CUSTOM_MANAGE && pendingDeleteId != null -> {
                    pendingDeleteId = null
                    renderCustomTags()      // 里面会把按钮文案/颜色刷回去
                }
                else -> setCustomMode(
                    if (customMode == CUSTOM_NONE) CUSTOM_MANAGE else CUSTOM_NONE
                )
            }
        }
        boxCustomUpright.setOnClickListener { pickCustomImage(REQ_PICK_UPRIGHT) }
        boxCustomCorner.setOnClickListener { pickCustomImage(REQ_PICK_CORNER) }
        editCustomName.setOnEditorActionListener { _, _, _ -> clearInputFocus(); true }
        renderCustomTags()
    }

    /**
     * 选素材图：走系统文件选择器（`ACTION_OPEN_DOCUMENT`）。
     *
     * 选它而不是 `ACTION_GET_CONTENT` / 相册权限的原因：**不需要任何存储权限**，
     * 返回的是可以直接读的 Uri，用户也能选到"文件"里的图（不只是相册）。
     */
    private fun pickCustomImage(req: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, req)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.custom_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 先记下"这是从选择器回来的"（取消也算），让紧随其后的 onResume 别收起卡片
        returningFromPickerAt = android.os.SystemClock.elapsedRealtime()
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // 有些来源只给"本次会话有效"的临时授权，能持久就持久 ——
        // 否则进程一重建、回来点保存就变成读不到图
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        when (requestCode) {
            REQ_PICK_UPRIGHT -> {
                pickedUpright = uri
                showPickedImage(imgCustomUpright, txtPlusCustomUpright, uri)
            }
            REQ_PICK_CORNER -> {
                pickedCorner = uri
                showPickedImage(imgCustomCorner, txtPlusCustomCorner, uri)
            }
            REQ_PICK_BUBBLE_IMAGE -> {
                // 气泡的图片条目：转存成 PNG（限长边）后加入列表，默认启用
                val ok = BubbleTextStore.addImage(this, uri)
                if (ok == null) {
                    Toast.makeText(this, R.string.bubble_text_bad_image, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.bubble_text_image_added, Toast.LENGTH_SHORT).show()
                    renderBubbleTextTags()
                }
            }
        }
    }

    /** 选完图立刻在方框里显示缩略图，并把那个「+」收起来 */
    private fun showPickedImage(img: android.widget.ImageView, plus: TextView, uri: Uri) {
        val bmp = CustomSkinStore.preview(this, uri)
        if (bmp == null) {
            Toast.makeText(this, R.string.custom_bad_image, Toast.LENGTH_SHORT).show()
            return
        }
        img.setImageBitmap(bmp)
        plus.visibility = View.GONE
    }

    /** 清空新增表单（离开新增态时调用，避免下次展开还留着上次的半成品） */
    private fun clearCustomForm() {
        editCustomName.setText("")
        pickedUpright = null
        pickedCorner = null
        imgCustomUpright.setImageDrawable(null)
        imgCustomCorner.setImageDrawable(null)
        txtPlusCustomUpright.visibility = View.VISIBLE
        txtPlusCustomCorner.visibility = View.VISIBLE
        customSaving = false
    }

    /**
     * 「保存」：把表单里的人物存成一个自定义形象。
     *
     * 用户要求：
     *   · 主素材**必填** —— 没加就点保存 = 不生效 + 提示「须添加主素材」；
     *   · 角落素材可选，**不加不提示**（角落状态直接用主素材）；
     *   · 存完**不立刻使用**：只是出现在「当前形象」的二级菜单里，要用得去那儿选；
     *   · 存完卡片收回一横条（图一）。
     *
     * 解码 + 缩放 + PNG 编码都放后台线程：手机里的图常常是 4000px 起步，
     * 这一串在主线程上会让界面卡住好几百毫秒。
     */
    private fun saveCustomSkin() {
        if (customSaving) return
        val upright = pickedUpright
        if (upright == null) {
            Toast.makeText(this, R.string.custom_need_main, Toast.LENGTH_SHORT).show()
            return
        }
        val corner = pickedCorner
        val typed = editCustomName.text.toString().trim()
        val name = typed.ifEmpty {
            getString(R.string.custom_default_name, CustomSkinStore.list(this).size + 1)
        }
        clearInputFocus()
        customSaving = true
        btnCustomAdd.isEnabled = false

        val app = applicationContext
        Thread {
            val id = CustomSkinStore.save(app, name, upright, corner)
            runOnUiThread {
                btnCustomAdd.isEnabled = true
                customSaving = false
                if (id == null) {
                    Toast.makeText(this, R.string.custom_save_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // 重读形象列表：内置 + 自定义 —— 新人物立刻出现在二级菜单里
                loadSkins()
                setCustomMode(CUSTOM_NONE)
                Toast.makeText(this, R.string.custom_saved, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * 「管理」列表：自定义形象每行两个，**奇数个时最后一个与左列对齐**（用户要求）。
     *
     * 每次进「管理」、每次删除、每次切明暗都要重建 ——
     * 标签的描边与文字色都跟着当前强调色走（与二级菜单同一个道理）。
     */
    private fun renderCustomTags() {
        rowsCustomManage.removeAllViews()
        val ids = CustomSkinStore.list(this)
        txtCustomEmpty.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE

        val gap = (resources.displayMetrics.density * 10f).toInt()
        var i = 0
        while (i < ids.size) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowsCustomManage.childCount > 0) topMargin = gap }
            }
            row.addView(makeCustomTag(ids[i], startMargin = 0))
            // 奇数个：右列补一个占位，最后一个标签才与左列对齐。
            //
            // ⚠️ 占位的 layoutParams 必须跟真标签**一模一样**（0 宽 + 同权重 + 同样
            //    的左边距）。曾写成 `LayoutParams(1, 1, 1f)`，看着"也是个空格"，
            //    但权重分配对"宽 0"和"宽 1px"走的是两条分支：成对的行里每格拿到
            //    (W − 间距)/2，而单独一行的标签拿到 ≈ W/2 —— 宽出半个间距，
            //    表现就是"名字短的标签比成对的窄"（实测 621 vs 638px，用户反馈）。
            row.addView(
                if (i + 1 < ids.size) makeCustomTag(ids[i + 1], startMargin = gap)
                else android.widget.Space(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, 1, 1f
                    ).apply { marginStart = gap }
                }
            )
            rowsCustomManage.addView(row)
            i += 2
        }
        // 待确认删除的有无会改变右上角按钮的文案与颜色（取消 / 收起），一起刷
        refreshCustomManageButton()
    }

    /**
     * 一个形象标签：名字 + 右侧的删除控件。
     *
     * 删除是**就地两步**（用户要求，不要弹窗）：
     *   红错号「✕」点一下 -> 变成红描边的「确认删除」按钮 -> 再点才真删。
     * 同一时刻只允许一个标签处于"待确认"（[pendingDeleteId]），
     * 去点另一个标签的错号时，前一个会自动退回「✕」—— 不会攒出一排确认按钮。
     * 离开管理态（含「取消」）会把待确认状态一并清掉。
     */
    private fun makeCustomTag(id: String, startMargin: Int): View {
        val tag = layoutInflater.inflate(R.layout.item_custom_skin_tag, rowsCustomManage, false)
        tag.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = startMargin }
        tag.background = AccentTheme.accentBoxBackground(this, accent, lightMode)

        val name = CustomSkinStore.readName(this, id) ?: id
        tag.findViewById<TextView>(R.id.txtCustomTagName).apply {
            text = name
            setTextColor(AccentTheme.skinBoxText(lightMode))
        }

        val pending = id == pendingDeleteId
        val dm = resources.displayMetrics
        tag.findViewById<TextView>(R.id.txtCustomTagDelete).apply {
            // ⚠️ 高度**两种形态写死成同一个值**。
            //
            // 标签的高度只由这个控件撑起来（名字那行比它矮），而控件的行高是随
            // 字号走的：✕ 是 17sp、确认删除是 13sp —— 一换形态标签就上下各收一点，
            // 实测矮 2px（density 3.5；用户手机上字更大，收得更多，看起来就是
            // "点错号之后标签变窄了"）。写死之后标签高度恒定。
            //
            // 高度乘以 fontScale：系统字号调大时 sp 会变大，写死 dp 会把 ✕ 裁掉
            // 上下两头；按同一比例放大就仍然容得下，而两态依然等高。
            val slotDp = DELETE_SLOT_DP * resources.configuration.fontScale
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(dm, slotDp)
            )
            // 关掉字体自带的上下 padding，字形才会真正落在框的中间
            // （否则墨迹会偏下：实测到上边框 59px、到下边框 51px）
            includeFontPadding = false
            gravity = android.view.Gravity.CENTER
            // 用户 2026-10-16：错号改成文字按钮 —— ✕ -> 「删除」、确认删除 -> 「确定」
            text = getString(
                if (pending) R.string.custom_confirm_action else R.string.custom_delete_action
            )
            // 两态都是两个字，字号统一（原来是 ✕ 17sp / 确认删除 13sp，现在都用 13sp）
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            // 删除是**固定的红**，不跟强调色（强调色本身可能就是红的，就分不出"删除"了）
            setTextColor(TAG_DELETE_RED)
            // 上下内边距给 0（靠 gravity 居中），描边背景正好铺满这个固定高度的框 ——
            // 于是"删除 / 确定"按钮到标签描边的上 / 下 / 右三边距离 = 标签自己的内边距，
            // 三边天然相等（用户要求）。左右内边距只影响按钮的横向留白。
            setPadding(dp(dm, 8f), 0, dp(dm, 8f), 0)
            // ⚠️ **两态都要描边**：原先常态是纯文字（✕ 时代留下的写法），
            //    改成「删除」之后就成了"一行红字"，看着不像按钮（用户 2026-10-17：
            //    "删除加上边框呀"）。现在常态与确定态都是红描边圆角框。
            background = AccentTheme.accentBoxBackground(
                this@MainActivity, TAG_DELETE_RED, lightMode, radiusDp = 8f
            )
            setOnClickListener {
                if (pending) {
                    deleteCustomSkin(id, name)
                } else {
                    pendingDeleteId = id
                    renderCustomTags()
                }
            }
        }
        return tag
    }

    private fun dp(dm: android.util.DisplayMetrics, v: Float): Int =
        (v * dm.density).toInt()

    /**
     * 真删：素材目录、缓存、以及"删的正好是当前形象"这一种情况。
     *
     * 删的若是当前正在用的那个，[loadSkins] 会把 `prefs.skin` 退回列表第一个
     * （它本来就带这条兜底），这里再把界面与桌宠一起换过去 ——
     * 否则桌宠那边还拿着一个已经不存在的目录名，只能靠 FALLBACK 兜。
     */
    private fun deleteCustomSkin(id: String, name: String) {
        val wasCurrent = prefs.skin == id
        pendingDeleteId = null
        CustomSkinStore.delete(this, id)
        // 这个 id 的发色 / 包围盒 / 预览位图都是旧的了，留着会串味
        skinAssets.remove(id)
        loadSkins()
        if (wasCurrent) {
            applyTheme()
            refreshCornerPreview()
            PetService.notifyChanged(this)
        } else {
            renderCustomTags()
        }
        // ⚠️ 不弹"已删除 xxx"提示（用户 2026-10-16）：标签当场消失本身就是反馈
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
            markCardSaved(panelBounce)
        }

        // 恢复默认值：回弹参数是吸附时现读的，不需要通知服务
        btnResetBounce.setOnClickListener {
            prefs.bounceAmpScale = 1f
            prefs.bounceTimeScale = 1f
            bindBounceControls()
            clearInputFocus()
            markCardSaved(panelBounce)
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
            markCardSaved(panelSummon)
        }

        btnResetSummon.setOnClickListener {
            prefs.summonTimeScale = 1f
            prefs.summonPopScale = 1f
            prefs.summonSpringScale = 1f
            bindSummonControls()
            clearInputFocus()
            markCardSaved(panelSummon)
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
        // ---- 速度：与幅度完全独立 ----
        //
        // 用**线性**映射：区间 0.1~10 跨度不大，线性下 1.0 落在偏左但可接受；
        // 用对数的话 1.0 正好在正中，但 0.1~10 的两端手感差别（0.1 慢到几乎
        // 看不出、10 快到发颤）更适合线性 —— 与幅度一致，用户已在幅度上习惯。
        seekBreathSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.breathSpeed =
                    scaleOfLinear(progress, Prefs.BREATH_SPEED_MIN, Prefs.BREATH_SPEED_MAX)
                bindBreathControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        val applySpeed = {
            parseScale(
                editBreathSpeed.text.toString(),
                Prefs.BREATH_SPEED_MIN, Prefs.BREATH_SPEED_MAX
            )?.let { prefs.breathSpeed = it }
            bindBreathControls()
        }
        editBreathSpeed.setOnEditorActionListener { _, _, _ ->
            applySpeed(); clearInputFocus(); true
        }
        editBreathSpeed.setOnFocusChangeListener { _, has -> if (!has) applySpeed() }
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
            // 两个输入框一起应用（用户点的是这张卡的"保存并应用"）
            parseScale(
                editBreathSpeed.text.toString(),
                Prefs.BREATH_SPEED_MIN, Prefs.BREATH_SPEED_MAX
            )?.let { prefs.breathSpeed = it }
            parseScale(editBreathAmp.text.toString(), Prefs.BREATH_MIN, Prefs.BREATH_MAX)
                ?.let { prefs.breathScale = it }
            bindBreathControls()
            clearInputFocus()
            markCardSaved(panelBreath)
        }

        btnResetBreath.setOnClickListener {
            // ⚠️ 两个参数都要归位 —— 这张卡现在有幅度和速度两项，
            //    只复位其中一个会让"恢复默认值"变得不可预期。
            prefs.breathScale = 1f
            prefs.breathSpeed = 1f
            bindBreathControls()
            clearInputFocus()
            markCardSaved(panelBreath)
        }

        btnBreathToggle.setOnClickListener {
            prefs.breathEnabled = !prefs.breathEnabled
            bindBreathControls()
            clearInputFocus()
            // ⚠️ 这里**不通知服务**（notifyChanged 会触发 ACTION_RELOAD，让桌宠重新落位、
            //    白跳一下；边框回弹卡也踩过这个坑），但**必须叫醒动画时钟**：
            //    呼吸开着时确实是"每帧现读"的，可 ticker 在"呼吸关掉 + 完全静止"时
            //    已经彻底不排帧了 —— 不叫它，用户打开呼吸后得碰一下人物才开始呼吸
            //    （用户反馈的 bug）。wakePetAnimation 是同进程直调，零延迟、不落位。
            PetService.wakePetAnimation()
        }

        bindBreathControls()
    }

    /** 把呼吸卡的控件状态同步到 Prefs（含总开关的启用/停用外观） */
    private fun bindBreathControls() {
        val on = prefs.breathEnabled

        // 速度
        seekBreathSpeed.progress = progressOfScaleLinear(
            prefs.breathSpeed, Prefs.BREATH_SPEED_MIN, Prefs.BREATH_SPEED_MAX
        )
        editBreathSpeed.setText(fmtScale(prefs.breathSpeed))
        txtBreathSpeed.text = getString(
            R.string.breath_speed_label_value, fmtScale(prefs.breathSpeed),
            prefs.breathPeriodMs.roundToInt()
        )

        // 幅度
        seekBreathAmp.progress =
            progressOfScaleLinear(prefs.breathScale, Prefs.BREATH_MIN, Prefs.BREATH_MAX)
        editBreathAmp.setText(fmtScale(prefs.breathScale))
        txtBreathAmp.text = getString(
            R.string.breath_amp_label_value, fmtScale(prefs.breathScale),
            (prefs.breathSq * 100f).roundToInt()
        )

        btnBreathToggle.text = getString(if (on) R.string.breath_off else R.string.breath_on)
        seekBreathSpeed.isEnabled = on
        editBreathSpeed.isEnabled = on
        seekBreathAmp.isEnabled = on
        editBreathAmp.isEnabled = on
        btnResetBreath.isEnabled = on
        txtBreathSpeed.alpha = if (on) 1f else 0.45f
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
        refreshNotifyFields()

        editNotifyTitle.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNotifyTitleCount()
        })
        editNotify.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateNotifyCount()
        })

        /**
         * 保存通知文案。
         *
         * 两条**留空回填**规则（用户要求）：
         *   · 标题留空 -> 填入默认模板（`{name} 正在陪伴你`）。
         *     回填模板而不是解析后的文字，是为了让占位符留着 —— 换角色时
         *     标题还能跟着变；回填实际文字等于把角色名写死了。
         *   · 正文留空 -> 填入**当前标题**（解析后的实际文字，
         *     例如「后藤一里 正在陪伴你」）。这样通知里正文不会是一片空白。
         */
        val applyNotify = {
            val name = currentSkinName()
            val filled = PetState.fillNotifyTexts(
                editNotifyTitle.text.toString(),
                editNotify.text.toString(),
                name,
                Prefs.NOTIFY_TITLE_DEFAULT
            )
            // ⚠️ 存储用**模板**：把输入框里的当前角色名反向换成 {name}。
            //    不同步这一步的话，存下来的就是写死的名字，换角色后标题不会跟着变
            //    （这正是方案 2 原本的 bug）。
            prefs.notifyTitle = PetState.titleForStore(filled.title, name)
            // 正文也存模板（同样反向换一次），这样通知里能跟着角色一起变；
            // 留空时 PetState.fillNotifyTexts 已经把它填成当前标题了。
            prefs.notifyText = PetState.titleForStore(filled.body, name)
            // 回写一次：setter 会按上限截断；显示时统一解析 {name}
            refreshNotifyFields()
        }
        editNotifyTitle.setOnEditorActionListener { _, _, _ ->
            applyNotify(); clearInputFocus(); true
        }
        editNotify.setOnEditorActionListener { _, _, _ ->
            applyNotify(); clearInputFocus(); true
        }
        btnSaveNotify.setOnClickListener {
            applyNotify()
            clearInputFocus()
            PetService.refreshNotification(this)
            markCardSaved(panelNotify)
        }

        /**
         * 恢复默认值：把**当前形象**这两条自定义文案清掉。
         *
         * 「留空 = 用默认」本来就是这张卡的语义（提示语写着"留空使用默认文案"），
         * 所以"恢复默认"就是清空 —— 清的是当前形象那一份（文案按形象分开存），
         * 别的角色不受影响。清完立刻刷通知，不用再点一次保存。
         */
        btnResetNotify.setOnClickListener {
            prefs.notifyTitle = ""
            prefs.notifyText = ""
            refreshNotifyFields()
            clearInputFocus()
            PetService.refreshNotification(this)
            Toast.makeText(this, R.string.notify_restored, Toast.LENGTH_SHORT).show()
            markCardSaved(panelNotify)
        }
    }

    private fun updateNotifyCount() {
        val n = editNotify.text?.length ?: 0
        txtNotifyCount.text = getString(R.string.notify_count, n, Prefs.NOTIFY_TEXT_MAX)
    }

    /**
     * 用**当前角色名**把通知标题与正文的模板解析出来，显示到两个输入框里。
     *
     * ⚠️ 三个调用时机缺一不可：打开界面、保存后回写、**换角色**。
     *    存储里存的是模板（可能含 {name}），只有显示时才解析 ——
     *    少调一次就会在框里露出 {name}，或者留着上一个角色的名字。
     *
     * ⚠️ 正文也要解析：它的内容默认就是标题（也可能带 {name}），
     *    只解析标题的话正文框里会明晃晃地显示 {name}（踩过）。
     */
    private fun refreshNotifyFields() {
        if (!::editNotifyTitle.isInitialized) return
        val name = currentSkinName()

        // 一次性迁移：把上一版存下的**字面名字**转成 {name} 模板，
        // 否则换角色时它不会跟着变（就是用户要修的那个 bug）。
        PetState.migrateTitleToTemplate(prefs.notifyTitle, name)?.let { prefs.notifyTitle = it }
        PetState.migrateTitleToTemplate(prefs.notifyText, name)?.let { prefs.notifyText = it }

        editNotifyTitle.setText(
            PetState.titleForDisplay(
                prefs.notifyTitle, name, Prefs.NOTIFY_TITLE_DEFAULT
            )
        )
        editNotify.setText(
            PetState.titleForDisplay(
                prefs.notifyText, name, Prefs.NOTIFY_TITLE_DEFAULT
            )
        )
        updateNotifyTitleCount()
        updateNotifyCount()
    }

    private fun updateNotifyTitleCount() {
        val n = editNotifyTitle.text?.length ?: 0
        txtNotifyTitleCount.text =
            getString(R.string.notify_title_count, n, Prefs.NOTIFY_TITLE_MAX)
    }

    private companion object {

        /** 量文字宽度用的基准字号（px）：取得够大，量完再按比例缩 */
        const val PROBE_TEXT_PX = 400f

        /**
         * 呼出"弹出高度"标签里显示 px 时用的**参考行程**（贴边呼出大约 320px）。
         * 真实过冲量是按行程算的（见 [Prefs.summonPopPx]），所以标签只是参考值。
         */
        const val SUMMON_REF_TRAVEL_PX = 320f

        /**
         * 二级菜单一页显示几行（用户要求 5）。
         *
         * 超过就在菜单内部滚动（见 [limitSkinMenuHeight]），页面本身不再被拉长。
         */
        const val SKIN_MENU_VISIBLE_ROWS = 5

        // ---- 自定义形象卡 ----
        /** 收起：只有标题那一横条 */
        const val CUSTOM_NONE = 0

        /** 展开新增表单（此时「新增」按钮显示为「保存」） */
        const val CUSTOM_ADD = 1

        /** 展开管理列表 */
        const val CUSTOM_MANAGE = 2

        // ---- 气泡内容卡（与上面三个值一一对应） ----
        const val BUBBLE_NONE = 0
        const val BUBBLE_ADD = 1
        const val BUBBLE_MANAGE = 2

        /** 先选类型（文本 / 图片）那一小块 —— 用户要求"卡片向下延伸，在卡片里选择" */
        const val BUBBLE_KIND = 3

        /**
         * 管理列表里**图片条目缩略图**的边长 = 内容字号的这个倍数（用户 2026-10-16：
         * "若此标签为图片，则图片大小为字号的 4 倍"；最初是 1 倍 = 一行文字那么高）。
         */
        const val BUBBLE_TAG_IMAGE_SCALE = 4f

        /**
         * 气泡内容标签里"删除控件"那格的**固定宽度**（dp）。
         *
         * ⚠️ 用户要求："标签显示的内容应留出足够空间防止确认删除按钮与标签内容冲突"。
         *    删除控件是**两态**（窄的「✕」/ 宽的「确认删除」），宽度若跟着文字走，
         *    一点错号整格就变宽 —— 内容会被挤得重新折行、甚至被省略号多截掉几个字。
         *    所以两态**共用这一格的宽度**（=「确认删除」在最坏字号下的宽度 + 余量），
         *    ✕ 在格子里居中，切换时内容纹丝不动。
         */
        const val BUBBLE_DELETE_SLOT_DP = 68f

        /** 选图请求码：主素材 / 角落素材 / 气泡图片各一个 */
        const val REQ_PICK_UPRIGHT = 9001
        const val REQ_PICK_CORNER = 9002
        const val REQ_PICK_BUBBLE_IMAGE = 9003

        /**
         * 管理标签里"删除控件"的**固定高度**（dp）。
         *
         * ⚠️ 两态（✕ / 确认删除）必须共用这一个值，且**不随字号变**：
         * 标签的高度就是它撑起来的，字号一变标签就上下收，看起来像"标签变窄了"。
         * 28dp 约等于改动前的实测高度（96px / density 3.5 ≈ 27.4dp），观感不变。
         */
        const val DELETE_SLOT_DP = 28f

        /**
         * 管理标签上错号的红色。
         *
         * 这里是**唯一**不跟强调色、也不跟明暗模式的颜色（用户明确要"红色错号"）——
         * 强调色本身可能就是红的或橙的，跟着走就认不出哪个是删除了。
         */
        val TAG_DELETE_RED = 0xFFE53935.toInt()
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
        // 长按标题 = 弹出作者声明（用户要求：署名要能被用户自己看到/核验）。
        // 那句话同时写进了 BuildConfig.AUTHORSHIP、R.string.authorship、
        // assets/AUTHORSHIP.txt 和源码 Authorship.kt —— 反编译或问 AI 都能读到。
        t.setOnLongClickListener {
            Toast.makeText(this, Authorship.line, Toast.LENGTH_LONG).show()
            true
        }
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
        val sprite = PetSprite.load(this, skin)
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
     *
     * @param refreshNotify 是否顺带把通知栏两个输入框按当前角色重新解析一遍。
     *        **只有"角色变了 / 明暗变了 / 界面刚建好"才需要**；纯粹开合菜单、
     *        展开收起卡片这类 UI 状态变化要传 `false` —— 否则会把用户正在编辑的
     *        文案覆盖成存档值（踩过：开一下二级菜单，正在敲的通知文案就没了）。
     */
    private fun applyTheme(refreshNotify: Boolean = true) {
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
        // 二级菜单：描边挂在外层 skinMenuWrap（视口）上，不是 skinMenu（内容）上 ——
        // 列表一长，内容会在视口里滚动，边框画在内容上会跟着滚走。
        skinMenuWrap.background = AccentTheme.skinMenuBackground(this, accent, lightMode)
        // 视口右侧的滚动指示条：颜色跟随当前强调色（内容装得下时视口自己不画）
        skinMenuScroll.indicatorColor = (accent and 0x00FFFFFF) or (0xB3 shl 24)
        icoSkinArrow.imageTintList =
            android.content.res.ColorStateList.valueOf(AccentTheme.skinBoxText(lightMode))
        // 菜单项的横线与文字色都依赖强调色 / 明暗模式，重建一次
        buildSkinMenu()
        refreshSkinBox()

        // 「自定义形象」管理列表里的标签同理：描边与名字色都跟着强调色
        renderCustomTags()

        // 「气泡内容」管理列表里的标签同理（内容色跟强调色、错号固定红）
        if (::rowsBubbleText.isInitialized) renderBubbleTextTags()

        // 按钮上的文字里有形象名，名字要用强调色，所以每次都重建一次
        refreshSummonLabel()

        // 通知标题与正文里都可能带角色名（存的都是 {name} 模板），
        // 换角色后要重新解析，否则框里留着上一个角色的名字。
        // ⚠️ 只在需要时刷：纯 UI 开合传 refreshNotify = false（见函数注释）
        if (refreshNotify) refreshNotifyFields()

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

    /** 锁定 / 锁定互动两个按钮的文案（锁定态左按钮变「解除」） */
    private fun bindLock() {
        btnLock.text = getString(if (prefs.locked) R.string.lock_on else R.string.lock_off)
        btnLockInteract.text = getString(
            when (prefs.lockMode) {
                Prefs.MODE_OFF -> R.string.lock_interact_off
                Prefs.MODE_FADE -> R.string.lock_interact_fade
                else -> R.string.lock_interact_on
            }
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


    // ---------------------------------------------------------------- 滑条

    /**
     * 滑条统一设上限。
     *
     * ⚠️ "只能按住滑块拖动"**不在这里做** —— 那是 [ThumbSeekBar] 的职责
     * （见该类的说明：用 setOnTouchListener 拦不住）。这里只把上限收成一处，
     * 省得 8 个 setup* 里各写一遍 `bar.max = 1000`。
     */
    private fun installThumbOnlySeekBars() {
        val bars = arrayOf(
            seekSize,
            seekSummonTime, seekSummonPop, seekSummonSpring,
            seekBounceAmp, seekBounceMs,
            seekBreathSpeed, seekBreathAmp
        )
        for (bar in bars) bar.max = 1000
    }

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
