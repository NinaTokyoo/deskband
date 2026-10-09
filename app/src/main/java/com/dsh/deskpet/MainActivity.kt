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
    private lateinit var spinnerSkin: Spinner
    private lateinit var chkAuto: CheckBox

    // 松手回弹调节
    private lateinit var seekBounceAmp: SeekBar
    private lateinit var seekBounceMs: SeekBar
    private lateinit var txtBounceAmp: TextView
    private lateinit var txtBounceMs: TextView
    private lateinit var editBounceAmp: EditText
    private lateinit var editBounceMs: EditText

    private var skins: List<String> = emptyList()
    private var bindingSkin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        // 把保存的 A 区尺寸同步进状态机，后续分区判定都用它
        prefs.applyZoneSize()

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        txtNotifyStatus = findViewById(R.id.txtNotifyStatus)
        btnNotify = findViewById(R.id.btnNotify)
        btnToggle = findViewById(R.id.btnToggle)
        seekSize = findViewById(R.id.seekSize)
        txtSize = findViewById(R.id.txtSize)
        spinnerSkin = findViewById(R.id.spinnerSkin)
        chkAuto = findViewById(R.id.chkAuto)
        seekBounceAmp = findViewById(R.id.seekBounceAmp)
        seekBounceMs = findViewById(R.id.seekBounceMs)
        txtBounceAmp = findViewById(R.id.txtBounceAmp)
        txtBounceMs = findViewById(R.id.txtBounceMs)
        editBounceAmp = findViewById(R.id.editBounceAmp)
        editBounceMs = findViewById(R.id.editBounceMs)

        applyEdgeToEdge()
        setupBounceControls()

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnNotify.setOnClickListener { requestNotificationPermission() }
        btnToggle.setOnClickListener { togglePet() }

        seekSize.max = 1000
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val f = fractionOf(progress)
                txtSize.text = getString(R.string.size_value, (f * 100).roundToInt())
                if (fromUser) {
                    prefs.sizeFraction = f
                    prefs.sizeTuned = true
                    PetService.notifyChanged(this@MainActivity)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        chkAuto.setOnCheckedChangeListener { _, checked -> prefs.autoStart = checked }

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

        seekSize.progress = progressOf(prefs.sizeFraction)
        txtSize.text = getString(R.string.size_value, (prefs.sizeFraction * 100).roundToInt())
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

    // ---------------------------------------------------------------- A 区调节

    /**
     * A 区尺寸调节：滑块 + 数字输入框，两者双向同步。
     *
     * 调 A 的同时 B / C 会跟着变（B 左侧对齐 A 左侧，C 上侧对齐 A 上侧），
     * 所以这里只需要改 A 的宽高两个数。
     * 调整过程中会通知服务闪一下红色指示线，方便对照。
     */
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
        editBounceAmp.setOnEditorActionListener { _, _, _ -> applyAmp(); true }
        editBounceAmp.setOnFocusChangeListener { _, has -> if (!has) applyAmp() }
        editBounceMs.setOnEditorActionListener { _, _, _ -> applyMs(); true }
        editBounceMs.setOnFocusChangeListener { _, has -> if (!has) applyMs() }

        bindBounceControls()
    }

    /**
     * 滑块进度(0..1000) <-> 倍率(0.1..10)。
     *
     * 用**对数刻度**：线性的话 0.1~1 只占 9% 的行程，很难调；
     * 取对数后 1.0 正好落在滑条中间，两侧手感一致。
     */
    private fun scaleOf(progress: Int): Float {
        val lo = log10(Prefs.SCALE_MIN.toDouble())
        val hi = log10(Prefs.SCALE_MAX.toDouble())
        val t = progress.coerceIn(0, 1000) / 1000.0
        return Math.pow(10.0, lo + (hi - lo) * t).toFloat()
    }

    private fun progressOfScale(v: Float): Int {
        val lo = log10(Prefs.SCALE_MIN.toDouble())
        val hi = log10(Prefs.SCALE_MAX.toDouble())
        val x = log10(v.coerceIn(Prefs.SCALE_MIN, Prefs.SCALE_MAX).toDouble())
        return (((x - lo) / (hi - lo)) * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    private fun parseScale(s: String): Float? =
        s.trim().removePrefix("×").removePrefix("x").toFloatOrNull()
            ?.coerceIn(Prefs.SCALE_MIN, Prefs.SCALE_MAX)

    /** 倍率显示成 1 位小数（整数则不显示 .0） */
    private fun fmtScale(v: Float): String {
        val r = (v * 10f).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
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

    // ---------------------------------------------------------------- 大小映射

    private fun progressOf(fraction: Float): Int =
        (((fraction - Prefs.MIN_SIZE) / (Prefs.MAX_SIZE - Prefs.MIN_SIZE)) * 1000f)
            .roundToInt().coerceIn(0, 1000)

    private fun fractionOf(progress: Int): Float =
        Prefs.MIN_SIZE + (Prefs.MAX_SIZE - Prefs.MIN_SIZE) * (progress / 1000f)
}
