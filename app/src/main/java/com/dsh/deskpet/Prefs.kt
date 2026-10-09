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

    /**
     * 宠物大小的**倍率**（用户可调，0.5~5）。
     *
     * 1.0 = 标准值 [SIZE_BASE]（占可用高度的 8%）。
     * 用户要求"以现在的大小为 1，幅度 0.5 到 5"，所以界面调的是倍率。
     */
    var sizeScale: Float
        get() = sp.getFloat(KEY_SIZE_SCALE, 1f).coerceIn(SIZE_SCALE_MIN, SIZE_SCALE_MAX)
        set(v) {
            sp.edit()
                .putFloat(KEY_SIZE_SCALE, v.coerceIn(SIZE_SCALE_MIN, SIZE_SCALE_MAX))
                .putBoolean(KEY_SIZE_TUNED, true)
                .apply()
        }

    /**
     * 由倍率折算出的实际比例（宠物高度 / 可用高度）。
     *
     * 用户**没手动调过**时直接用标准值，而不是读回旧存档 ——
     * 否则改默认值对老装机无效（SharedPreferences 里还留着上一次的数）。
     */
    val sizeFraction: Float
        get() = if (sp.getBoolean(KEY_SIZE_TUNED, false)) {
            (SIZE_BASE * sizeScale).coerceIn(MIN_SIZE, MAX_SIZE)
        } else {
            SIZE_BASE
        }

    /** 用户是否手动调过大小 */
    var sizeTuned: Boolean
        get() = sp.getBoolean(KEY_SIZE_TUNED, false)
        set(v) { sp.edit().putBoolean(KEY_SIZE_TUNED, v).apply() }

    /** 当前吸附状态（PetState.CORNER_* / BORDER_*） */
    var snapState: Int
        get() = sp.getInt(KEY_SNAP, PetState.CORNER_BR)
        set(v) { sp.edit().putInt(KEY_SNAP, v).apply() }

    /**
     * 宠物**足迹盒**（旋转后的外接矩形）左上角相对屏幕的像素位置。
     *
     * 注意不是素材格子的左上角 —— 旋转形态下足迹盒与素材格子宽高互换。
     * PetView.restore() 就是按这个语义还原位置的。
     */
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

    /**
     * 回弹幅度**倍率**（用户可调，0.1~10）。
     *
     * 1.0 = 标准值 [BOUNCE_PX_BASE]（36px）。
     * 用户要求"以现在的幅度为标准来调"，所以界面调的是倍率而不是绝对像素。
     */
    var bounceAmpScale: Float
        get() = sp.getFloat(KEY_BOUNCE_AMP, 1f).coerceIn(SCALE_MIN, SCALE_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_BOUNCE_AMP, v.coerceIn(SCALE_MIN, SCALE_MAX)
            ).apply()
        }

    /** 回弹回落时长**倍率**（1.0 = [BOUNCE_MS_BASE] = 360ms） */
    var bounceTimeScale: Float
        get() = sp.getFloat(KEY_BOUNCE_TIME, 1f).coerceIn(SCALE_MIN, SCALE_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_BOUNCE_TIME, v.coerceIn(SCALE_MIN, SCALE_MAX)
            ).apply()
        }

    /** 实际过冲幅度（px） */
    val bouncePx: Float get() = BOUNCE_PX_BASE * bounceAmpScale

    /** 实际回落时长（ms） */
    val bounceBackMs: Long get() = (BOUNCE_MS_BASE * bounceTimeScale).toLong()

    companion object {
        private const val NAME = "deskpet"
        private const val KEY_RUNNING = "running"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_SKIN = "skin"
        private const val KEY_SIZE = "size_fraction"
        private const val KEY_SIZE_TUNED = "size_tuned"
        private const val KEY_SIZE_SCALE = "size_scale"
        private const val KEY_SNAP = "snap_state"
        private const val KEY_LEFT = "pos_left"
        private const val KEY_TOP = "pos_top"
        private const val KEY_HAS_POS = "has_pos"
        private const val KEY_BOUNCE_AMP = "bounce_amp_scale"
        private const val KEY_BOUNCE_TIME = "bounce_time_scale"

        // ---- 松手回弹：标准值与可调倍率范围 ----
        /** 标准过冲幅度（px），倍率 1.0 对应它 */
        const val BOUNCE_PX_BASE = 36f

        /** 标准回落时长（ms），倍率 1.0 对应它 */
        const val BOUNCE_MS_BASE = 360f

        /** 倍率范围：0.1 ~ 10 */
        const val SCALE_MIN = 0.1f
        const val SCALE_MAX = 10f

        /** 默认角色：素材目录名 */
        val DEFAULT_SKIN = PetSprite.FALLBACK

        // ---- 大小：标准值与可调倍率范围 ----
        /** 标准大小：占可用高度的 8%，倍率 1.0 对应它 */
        const val SIZE_BASE = 0.08f

        /** 大小倍率范围：0.5 ~ 5（用户指定） */
        const val SIZE_SCALE_MIN = 0.5f
        const val SIZE_SCALE_MAX = 5f

        /** 由倍率范围推出的实际比例上下限：4% ~ 40% */
        const val MIN_SIZE = SIZE_BASE * SIZE_SCALE_MIN
        const val MAX_SIZE = SIZE_BASE * SIZE_SCALE_MAX
    }
}
