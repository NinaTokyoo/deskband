package com.dsh.deskpet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 开机 / 应用更新后，如果用户开过「开机自启」就把桌宠叫起来。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = Prefs(context)
        if (!prefs.autoStart) return
        if (!Settings.canDrawOverlays(context)) return

        try {
            PetService.start(context)
        } catch (e: Exception) {
            // 安卓对「开机直接拉起前台服务」有限制，失败就放弃，用户手动开即可
        }
    }
}
