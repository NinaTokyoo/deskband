package com.dsh.deskpet

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
    private lateinit var spinnerSkin: Spinner
    private lateinit var btnReset: Button

    // 松手回弹调节
    private lateinit var seekBounceAmp: SeekBar
    private lateinit var seekBounceMs: SeekBar
    private lateinit var txtBounceAmp: TextView
    private lateinit var txtBounceMs: TextView
    private lateinit var editBounceAmp: EditText
    private lateinit var editBounceMs: EditText
    private lateinit var btnSaveBounce: Button

    // 通知栏自定义
    private lateinit var editNotify: EditText
    private lateinit var txtNotifyCount: TextView
    private lateinit var btnSaveNotify: Button

    private var skins: List<String> = emptyList()
    private var bindingSkin = false

    /** 通知权限是否已经弹过系统对话框（弹过之后再点就直接去设置页） */
    private var notifyAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
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
        spinnerSkin = findViewById(R.id.spinnerSkin)
        btnReset = findViewById(R.id.btnReset)
        seekBounceAmp = findViewById(R.id.seekBounceAmp)
        seekBounceMs = findViewById(R.id.seekBounceMs)
        txtBounceAmp = findViewById(R.id.txtBounceAmp)
        txtBounceMs = findViewById(R.id.txtBounceMs)
        editBounceAmp = findViewById(R.id.editBounceAmp)
        editBounceMs = findViewById(R.id.editBounceMs)
        btnSaveBounce = findViewById(R.id.btnSaveBounce)
        editNotify = findViewById(R.id.editNotify)
        txtNotifyCount = findViewById(R.id.txtNotifyCount)
        btnSaveNotify = findViewById(R.id.btnSaveNotify)

        applyEdgeToEdge()
        setupBounceControls()
        setupNotifyControls()

        rowOverlay.setOnClickListener { openOverlaySettings() }
        rowNotify.setOnClickListener { openNotificationSettings() }
        btnToggle.setOnClickListener { togglePet() }
        btnReset.setOnClickListener { doReset() }

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
            Toast.makeText(this, R.string.size_saved, Toast.LENGTH_SHORT).show()
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
        // 本地也先把 UI 同步过来（服务端会写同一份 Prefs）
        prefs.sizeScale = 1f
        prefs.bounceAmpScale = 1f
        prefs.bounceTimeScale = 1f
        bindSizeControls()
        bindBounceControls()
        clearInputFocus()
        PetService.reset(this)
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
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
        // 权限行：已开启 -> 绿勾，未开启 -> 红叉
        setPermMark(markOverlay, Settings.canDrawOverlays(this))
        setPermMark(markNotify, hasNotificationPermission())

        // 开关按钮：呼出/收起 + **当前形象名**。
        // 名字跟着下拉框走，与桌宠是否在运行无关。
        btnToggle.text = getString(
            if (prefs.running) R.string.btn_dismiss else R.string.btn_summon,
            currentSkinName()
        )

        bindingSkin = true
        val idx = skins.indexOf(prefs.skin).coerceAtLeast(0)
        spinnerSkin.setSelection(idx, false)
        bindingSkin = false

        bindSizeControls()
    }

    /** 小标记：已开启绿勾，未开启红叉 */
    private fun setPermMark(tv: TextView, ok: Boolean) {
        tv.text = getString(if (ok) R.string.perm_granted_mark else R.string.perm_missing_mark)
        tv.setTextColor(if (ok) COLOR_OK else COLOR_BAD)
    }

    /** 当前形象的中文显示名（pet.json 里的 name），取不到就退回目录名 */
    private fun currentSkinName(): String =
        PetSprite.load(assets, prefs.skin)?.displayName ?: prefs.skin

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

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSkin.adapter = adapter
        spinnerSkin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (bindingSkin) return
                val dir = skins.getOrNull(position) ?: return
                if (dir == prefs.skin) return
                prefs.skin = dir
                // 按钮上的名字要立刻跟着换（与是否运行无关）
                btnToggle.text = getString(
                    if (prefs.running) R.string.btn_dismiss else R.string.btn_summon,
                    labels.getOrNull(position) ?: dir
                )
                PetService.notifyChanged(this@MainActivity)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
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
                prefs.bounceAmpScale = scaleOf(progress)
                bindBounceControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        seekBounceMs.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.bounceTimeScale = scaleOf(progress)
                bindBounceControls()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 数字输入：回车或失焦时生效（支持小数，如 2.5）
        val applyAmp = {
            parseScale(editBounceAmp.text.toString())?.let { prefs.bounceAmpScale = it }
            bindBounceControls()
        }
        val applyMs = {
            parseScale(editBounceMs.text.toString())?.let { prefs.bounceTimeScale = it }
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
            parseScale(editBounceAmp.text.toString())?.let { prefs.bounceAmpScale = it }
            parseScale(editBounceMs.text.toString())?.let { prefs.bounceTimeScale = it }
            bindBounceControls()
            // 保存后收掉焦点与键盘，否则输入框会一直亮着下划线、光标闪个不停
            clearInputFocus()
            Toast.makeText(this, R.string.bounce_saved, Toast.LENGTH_SHORT).show()
        }

        bindBounceControls()
    }

    /**
     * 滑块进度(0..1000) <-> 倍率。
     *
     * 用**对数刻度**：线性的话低倍率段只占很小一段行程，很难调；
     * 取对数后 1.0 正好落在滑条中间，两侧手感一致。
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
            Toast.makeText(this, R.string.notify_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNotifyCount() {
        val n = editNotify.text?.length ?: 0
        txtNotifyCount.text = getString(R.string.notify_count, n, Prefs.NOTIFY_TEXT_MAX)
    }

    private companion object {
        /** 权限已开启的绿勾颜色 */
        const val COLOR_OK = 0xFF4CD964.toInt()

        /** 权限未开启的红叉颜色 */
        const val COLOR_BAD = 0xFFFF453A.toInt()
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
        val frac = prefs.sizeFraction
        seekSize.progress = progressOfSizeScale(sc)
        editSize.setText(fmtScale(sc))
        txtSize.text = getString(
            R.string.size_value, fmtScale(sc), (frac * 100f).roundToInt()
        )
    }

    /** 把当前值刷到滑块 / 文本框 / 摘要文字 */
    private fun bindBounceControls() {
        val amp = prefs.bounceAmpScale
        val tms = prefs.bounceTimeScale
        seekBounceAmp.progress = progressOfScale(amp)
        seekBounceMs.progress = progressOfScale(tms)
        editBounceAmp.setText(fmtScale(amp))
        editBounceMs.setText(fmtScale(tms))
        txtBounceAmp.text = getString(
            R.string.bounce_amp_value, fmtScale(amp), prefs.bouncePx.roundToInt()
        )
        txtBounceMs.text = getString(
            R.string.bounce_ms_value, fmtScale(tms), prefs.bounceBackMs
        )
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
