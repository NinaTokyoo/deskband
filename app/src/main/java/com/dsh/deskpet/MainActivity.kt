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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    private var skins: List<String> = emptyList()
    private var bindingSkin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        txtOverlayStatus = findViewById(R.id.txtOverlayStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        txtNotifyStatus = findViewById(R.id.txtNotifyStatus)
        btnNotify = findViewById(R.id.btnNotify)
        btnToggle = findViewById(R.id.btnToggle)
        seekSize = findViewById(R.id.seekSize)
        txtSize = findViewById(R.id.txtSize)
        spinnerSkin = findViewById(R.id.spinnerSkin)
        chkAuto = findViewById(R.id.chkAuto)

        applyEdgeToEdge()

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

    // ---------------------------------------------------------------- 大小映射

    private fun progressOf(fraction: Float): Int =
        (((fraction - Prefs.MIN_SIZE) / (Prefs.MAX_SIZE - Prefs.MIN_SIZE)) * 1000f)
            .roundToInt().coerceIn(0, 1000)

    private fun fractionOf(progress: Int): Float =
        Prefs.MIN_SIZE + (Prefs.MAX_SIZE - Prefs.MIN_SIZE) * (progress / 1000f)
}
