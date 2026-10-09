package com.dsh.deskpet

import android.content.Context

/**
 * 轻量偏好存储：运行状态、角色、大小、位置、开机自启。
 * 位置以「可活动范围的比例」保存，换分辨率或换大小都不会跑出屏幕。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 桌宠是否正在运行 */
    var running: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(v) { sp.edit().putBoolean(KEY_RUNNING, v).apply() }

    /** 开机自动启动 */
    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) { sp.edit().putBoolean(KEY_AUTOSTART, v).apply() }

    /** 角色目录名，对应 assets/pet/<skin> */
    var skin: String
        get() = sp.getString(KEY_SKIN, DEFAULT_SKIN) ?: DEFAULT_SKIN
        set(v) { sp.edit().putString(KEY_SKIN, v).apply() }

    /** 宠物高度占可活动区域高度的比例 */
    var sizeFraction: Float
        get() = sp.getFloat(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        set(v) { sp.edit().putFloat(KEY_SIZE, v.coerceIn(MIN_SIZE, MAX_SIZE)).apply() }

    /** 用户是否手动调过大小 */
    var sizeTuned: Boolean
        get() = sp.getBoolean(KEY_SIZE_TUNED, false)
        set(v) { sp.edit().putBoolean(KEY_SIZE_TUNED, v).apply() }

    /** 当前吸附状态（PetState.CORNER_* / BORDER_*） */
    var snapState: Int
        get() = sp.getInt(KEY_SNAP, PetState.CORNER_BR)
        set(v) { sp.edit().putInt(KEY_SNAP, v).apply() }

    /** 宠物左上角相对屏幕的像素位置 */
    var posLeft: Int
        get() = sp.getInt(KEY_LEFT, 0)
        set(v) { sp.edit().putInt(KEY_LEFT, v).apply() }

    var posTop: Int
        get() = sp.getInt(KEY_TOP, 0)
        set(v) { sp.edit().putInt(KEY_TOP, v).apply() }

    /** 是否已经保存过位置 */
    val hasPosition: Boolean
        get() = sp.getBoolean(KEY_HAS_POS, false)

    fun savePosition(left: Int, top: Int) {
        sp.edit()
            .putInt(KEY_LEFT, left)
            .putInt(KEY_TOP, top)
            .putBoolean(KEY_HAS_POS, true)
            .apply()
    }

    fun forgetPosition() {
        sp.edit()
            .remove(KEY_LEFT).remove(KEY_TOP).remove(KEY_HAS_POS)
            .apply()
    }

    /** A 区横向尺寸（离吸附侧边），可调 */
    var zoneW: Float
        get() = sp.getFloat(KEY_ZONE_W, PetState.A_W_DEFAULT)
            .coerceIn(PetState.A_W_MIN, PetState.A_W_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_ZONE_W, v.coerceIn(PetState.A_W_MIN, PetState.A_W_MAX)
            ).apply()
        }

    /** A 区纵向尺寸（离底边），可调 */
    var zoneH: Float
        get() = sp.getFloat(KEY_ZONE_H, PetState.A_H_DEFAULT)
            .coerceIn(PetState.A_H_MIN, PetState.A_H_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_ZONE_H, v.coerceIn(PetState.A_H_MIN, PetState.A_H_MAX)
            ).apply()
        }

    /** 把当前设置同步进状态机 */
    fun applyZoneSize() {
        PetState.setZoneSize(zoneW, zoneH)
    }

    companion object {
        private const val NAME = "deskpet"
        private const val KEY_RUNNING = "running"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_SKIN = "skin"
        private const val KEY_SIZE = "size_fraction"
        private const val KEY_SIZE_TUNED = "size_tuned"
        private const val KEY_SNAP = "snap_state"
        private const val KEY_LEFT = "pos_left"
        private const val KEY_TOP = "pos_top"
        private const val KEY_HAS_POS = "has_pos"
        private const val KEY_ZONE_W = "zone_w"
        private const val KEY_ZONE_H = "zone_h"

        /** 默认角色：素材目录名 */
        val DEFAULT_SKIN = PetSprite.FALLBACK

        const val MIN_SIZE = 0.06f
        const val MAX_SIZE = 0.40f

        /** 默认大小：占屏幕可用高度的 8% */
        const val DEFAULT_SIZE = 0.08f
    }
}
