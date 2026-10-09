package com.dsh.deskpet

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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

    private lateinit var txtOverlayStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var txtNotifyStatus: TextView
    private lateinit var btnNotify: Button
    private lateinit var btnToggle: Button
    private lateinit var seekSize: SeekBar
    private lateinit var txtSize: TextView
    private lateinit var editSize: EditText
    private lateinit var btnSaveSize: Button
    private lateinit var spinnerSkin: Spinner
    private lateinit var chkAuto: CheckBox

    // 松手回弹调节
    private lateinit var seekBounceAmp: SeekBar
    private lateinit var seekBounceMs: SeekBar
    private lateinit var txtBounceAmp: TextView
    private lateinit var txtBounceMs: TextView
    private lateinit var editBounceAmp: EditText
    private lateinit var editBounceMs: EditText
    private lateinit var btnSaveBounce: Button

    private var skins: List<String> = emptyList()
    private var bindingSkin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        // 分区几何用 PetState 里的固定默认值（A 区尺寸调节功能已取消）

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        txtNotifyStatus = findViewById(R.id.txtNotifyStatus)
        btnNotify = findViewById(R.id.btnNotify)
        btnToggle = findViewById(R.id.btnToggle)
        seekSize = findViewById(R.id.seekSize)
        txtSize = findViewById(R.id.txtSize)
        editSize = findViewById(R.id.editSize)
        btnSaveSize = findViewById(R.id.btnSaveSize)
        spinnerSkin = findViewById(R.id.spinnerSkin)
        chkAuto = findViewById(R.id.chkAuto)
        seekBounceAmp = findViewById(R.id.seekBounceAmp)
        seekBounceMs = findViewById(R.id.seekBounceMs)
        txtBounceAmp = findViewById(R.id.txtBounceAmp)
        txtBounceMs = findViewById(R.id.txtBounceMs)
        editBounceAmp = findViewById(R.id.editBounceAmp)
        editBounceMs = findViewById(R.id.editBounceMs)
        btnSaveBounce = findViewById(R.id.btnSaveBounce)

        applyEdgeToEdge()
        setupBounceControls()

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnNotify.setOnClickListener { requestNotificationPermission() }
        btnToggle.setOnClickListener { togglePet() }

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

        chkAuto.setOnCheckedChangeListener { _, checked -> prefs.autoStart = checked }

        bindSizeControls()
        loadSkins()
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
        val overlayOk = Settings.canDrawOverlays(this)
        txtOverlayStatus.text = getString(
            if (overlayOk) R.string.perm_overlay_on else R.string.perm_overlay_off
        )
        btnOverlay.text = getString(
            if (overlayOk) R.string.perm_overlay_btn_on else R.string.perm_overlay_btn_off
        )
        btnOverlay.isEnabled = !overlayOk

        val notifyOk = hasNotificationPermission()
        txtNotifyStatus.text = getString(
            if (notifyOk) R.string.perm_notify_on else R.string.perm_notify_off
        )
        btnNotify.isEnabled = !notifyOk && Build.VERSION.SDK_INT >= 33

        btnToggle.text = getString(if (prefs.running) R.string.btn_stop else R.string.btn_start)

        bindingSkin = true
        val idx = skins.indexOf(prefs.skin).coerceAtLeast(0)
        spinnerSkin.setSelection(idx, false)
        bindingSkin = false

        chkAuto.isChecked = prefs.autoStart

        bindSizeControls()
    }

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

    private fun togglePet() {
        if (prefs.running) {
            PetService.stop(this)
            prefs.running = false
            btnToggle.text = getString(R.string.btn_start)
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
            btnToggle.text = getString(R.string.btn_stop)
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
     * 不清的话，点完"保存并应用"输入框仍处于聚焦态 ——
     * 表现为蓝色下划线和一直闪的光标（用户反馈的正是这个）。
     */
    private fun clearInputFocus() {
        editBounceAmp.clearFocus()
        editBounceMs.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
}
