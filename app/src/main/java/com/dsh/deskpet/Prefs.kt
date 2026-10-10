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
        get() = sp.getFloat(KEY_BOUNCE_AMP, 1f).coerceIn(AMP_SCALE_MIN, AMP_SCALE_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_BOUNCE_AMP, v.coerceIn(AMP_SCALE_MIN, AMP_SCALE_MAX)
            ).apply()
        }

    /** 回弹回落时长**倍率**（1.0 = [BOUNCE_MS_BASE] = 360ms） */
    var bounceTimeScale: Float
        get() = sp.getFloat(KEY_BOUNCE_TIME, 1f).coerceIn(TIME_SCALE_MIN, TIME_SCALE_MAX)
        set(v) {
            sp.edit().putFloat(
                KEY_BOUNCE_TIME, v.coerceIn(TIME_SCALE_MIN, TIME_SCALE_MAX)
            ).apply()
        }

    /** 实际过冲幅度（px） */
    val bouncePx: Float get() = BOUNCE_PX_BASE * bounceAmpScale

    /** 实际回落时长（ms） */
    val bounceBackMs: Long get() = (BOUNCE_MS_BASE * bounceTimeScale).toLong()

    /**
     * 常驻通知的自定义内容（空串 = 用默认文案）。
     *
     * 上限 [NOTIFY_TEXT_MAX] 个字符，允许任意语言、数字、符号与 emoji。
     * 按 UTF-16 code unit 计数（emoji 通常占 2 个）——简单、可控、不会切坏代理对
     * 到一半（take 不会把代理对拆开，因为上限远大于常见 emoji 的长度）。
     */
    var notifyText: String
        get() = sp.getString(KEY_NOTIFY_TEXT, "") ?: ""
        set(v) { sp.edit().putString(KEY_NOTIFY_TEXT, v.take(NOTIFY_TEXT_MAX)).apply() }

    /**
     * 通知标题的自定义文案，用 `{name}` 表示角色名。
     *
     * 空串表示"用默认"（[Prefs.NOTIFY_TITLE_DEFAULT]）—— 与正文一致，
     * 「留空即默认」比强制填更省事。真正的 `{name}` 替换在
     * [PetService.notificationTitle] 里做，因为只有那边知道当前角色名。
     */
    var notifyTitle: String
        get() = sp.getString(KEY_NOTIFY_TITLE, "") ?: ""
        set(v) { sp.edit().putString(KEY_NOTIFY_TITLE, v.take(NOTIFY_TITLE_MAX)).apply() }

    /**
     * 由当前角色发色推出的强调色（缓存，供下次启动先上色避免闪一下默认色）。
     */
    var accentColor: Int
        get() = sp.getInt(KEY_ACCENT, 0xFF5FD3F3.toInt())
        set(v) { sp.edit().putInt(KEY_ACCENT, v).apply() }

    /**
     * 边框回弹总开关。关掉后吸附只做位置补间，不再往外弹一下。
     */
    var bounceEnabled: Boolean
        get() = sp.getBoolean(KEY_BOUNCE_ON, true)
        set(v) { sp.edit().putBoolean(KEY_BOUNCE_ON, v).apply() }

    // ---------------------------------------------------------------- 呼出回弹

    /**
     * 呼出回弹总开关。关掉后呼出只做位置补间，不再过冲、也不回摆。
     *
     * ⚠️ 只管"呼出滑入 + 回摆"这一段；单击轻跳、长按弹起、落地回弹都不受影响。
     */
    var summonBounceEnabled: Boolean
        get() = sp.getBoolean(KEY_SUMMON_ON, true)
        set(v) { sp.edit().putBoolean(KEY_SUMMON_ON, v).apply() }

    /** 呼出回弹**总时长**倍率：1.0 = [SUMMON_MS_BASE]（560ms），范围 0.2~5 */
    var summonTimeScale: Float
        get() = sp.getFloat(KEY_SUMMON_TIME, 1f).coerceIn(SUMMON_TIME_MIN, SUMMON_TIME_MAX)
        set(v) {
            sp.edit()
                .putFloat(KEY_SUMMON_TIME, v.coerceIn(SUMMON_TIME_MIN, SUMMON_TIME_MAX))
                .apply()
        }

    /** 呼出**弹出高度**倍率：1.0 = 现在过冲量的一半，范围 0.1~10 */
    var summonPopScale: Float
        get() = sp.getFloat(KEY_SUMMON_POP, 1f).coerceIn(SUMMON_POP_MIN, SUMMON_POP_MAX)
        set(v) {
            sp.edit()
                .putFloat(KEY_SUMMON_POP, v.coerceIn(SUMMON_POP_MIN, SUMMON_POP_MAX))
                .apply()
        }

    /** 呼出**回弹幅度**倍率：1.0 = 现在回摆反向冲过的比例（0.39），范围 0.1~10 */
    var summonSpringScale: Float
        get() = sp.getFloat(KEY_SUMMON_SPRING, 1f).coerceIn(SUMMON_SPRING_MIN, SUMMON_SPRING_MAX)
        set(v) {
            sp.edit()
                .putFloat(KEY_SUMMON_SPRING, v.coerceIn(SUMMON_SPRING_MIN, SUMMON_SPRING_MAX))
                .apply()
        }

    /** 呼出滑入 + 回摆的**总时长**（ms） */
    val summonTotalMs: Float get() = SUMMON_MS_BASE * summonTimeScale

    /** 呼出滑入段时长（ms）：总时长的 [SUMMON_IN_RATIO] */
    val summonInMs: Float get() = summonTotalMs * SUMMON_IN_RATIO

    /** 呼出回摆段时长（ms） */
    val summonBackMs: Float get() = summonTotalMs * (1f - SUMMON_IN_RATIO)

    /**
     * 呼出**弹出高度**（过冲距离，px）。
     *
     * 仍然按行程给（见 [PetState.bounceAmp]，短行程时就是"靠边吸附那种 Q 弹"），
     * 再乘"现在的 0.5 倍"这个基准与用户倍率 —— 用户要求
     * "弹出高度以现在的 0.5 倍为 1.0 倍，可调范围 0.1~10"。
     */
    fun summonPopPx(travel: Float): Float =
        PetState.bounceAmp(travel, BOUNCE_PX_BASE) * SUMMON_POP_BASE_RATIO * summonPopScale

    /** 呼出回摆的反向冲过比例（相对弹出高度）：现在 = [SUMMON_SPRING_BASE] */
    val summonSpringGain: Float get() = SUMMON_SPRING_BASE * summonSpringScale

    // ---------------------------------------------------------------- 呼吸晃动

    /** 呼吸晃动总开关。关掉后吸附静止时完全不动 */
    var breathEnabled: Boolean
        get() = sp.getBoolean(KEY_BREATH_ON, true)
        set(v) { sp.edit().putBoolean(KEY_BREATH_ON, v).apply() }

    /** 呼吸幅度倍率：1.0 = 现在幅度的 1.3 倍，范围 0.5~10 */
    var breathScale: Float
        get() = sp.getFloat(KEY_BREATH_SCALE, 1f).coerceIn(BREATH_MIN, BREATH_MAX)
        set(v) {
            sp.edit().putFloat(KEY_BREATH_SCALE, v.coerceIn(BREATH_MIN, BREATH_MAX)).apply()
        }

    /** 呼吸在**法线**方向的幅度（占素材高度的比例） */
    val breathSq: Float get() = BREATH_SQ_BASE * breathScale

    /**
     * 复位时是否**保留当前设置**。
     *
     * 开启：只把人物挪回右边框中点，大小/回弹等设置全不动。
     * 关闭（默认）：一切恢复默认值。
     */
    var resetKeepSettings: Boolean
        get() = sp.getBoolean(KEY_RESET_KEEP, false)
        set(v) { sp.edit().putBoolean(KEY_RESET_KEEP, v).apply() }

    /**
     * 明暗模式：[AccentTheme.MODE_SYSTEM] / [MODE_LIGHT][AccentTheme.MODE_LIGHT]
     * / [AccentTheme.MODE_DARK]。
     */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, AccentTheme.MODE_SYSTEM)
        set(v) { sp.edit().putInt(KEY_THEME_MODE, v).apply() }

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
        private const val KEY_NOTIFY_TEXT = "notify_text"
    private const val KEY_NOTIFY_TITLE = "notify_title"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BOUNCE_ON = "bounce_on"
        private const val KEY_RESET_KEEP = "reset_keep"
        private const val KEY_SUMMON_ON = "summon_bounce_on"
        private const val KEY_SUMMON_TIME = "summon_time_scale"
        private const val KEY_SUMMON_POP = "summon_pop_scale"
        private const val KEY_SUMMON_SPRING = "summon_spring_scale"
        private const val KEY_BREATH_ON = "breath_on"
        private const val KEY_BREATH_SCALE = "breath_scale"

        /** 通知栏自定义内容的字符上限（用户指定 40） */
        const val NOTIFY_TEXT_MAX = 40

    /** 通知标题长度上限（比正文宽松些，标题允许写长一点） */
    const val NOTIFY_TITLE_MAX = 40

    /**
     * 标题的默认文案。`{name}` 是占位符，会被替换成当前角色名。
     * 与 strings.xml 里的 notif_title_default 保持一致。
     */
    const val NOTIFY_TITLE_DEFAULT = "{name} 正在陪你"

    /** 标题里的角色名占位符 */
    const val NOTIFY_NAME_TOKEN = "{name}"

        // ---- 松手回弹：标准值与可调倍率范围 ----
        /** 标准过冲幅度（px），倍率 1.0 对应它 */
        const val BOUNCE_PX_BASE = 36f

        /** 标准回落时长（ms），倍率 1.0 对应它 */
        const val BOUNCE_MS_BASE = 360f

        /**
         * 回弹**幅度**倍率区间：0.5 ~ 100（用户要求，上限拉得很高是为了做夸张效果；
         * 真弹出去时会被 PetView 的 BOUNCE_MIN_VISIBLE 夹住，不会真的飞出屏幕）。
         */
        const val AMP_SCALE_MIN = 0.5f
        const val AMP_SCALE_MAX = 100f

        /** 回弹**时长**倍率区间：0.1 ~ 10 */
        const val TIME_SCALE_MIN = 0.1f
        const val TIME_SCALE_MAX = 10f

        /** 倍率范围（历史别名，测试与旧代码用） */
        const val SCALE_MIN = TIME_SCALE_MIN
        const val SCALE_MAX = TIME_SCALE_MAX

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

        // ---- 呼出回弹：标准值与可调倍率范围（用户指定） ----
        /**
         * 呼出回弹**总时长**标准值（ms），倍率 1.0 对应它。
         *
         * 用户要求"总时长以现在的 0.8 倍为 1.0 倍"：现在滑入 320ms + 回摆 380ms = 700ms，
         * 0.8 倍就是 **560ms**。
         */
        const val SUMMON_MS_BASE = 560f

        /** 滑入段占总时长的比例（沿用改动前的 320 / 700） */
        const val SUMMON_IN_RATIO = 320f / 700f

        /**
         * "弹出高度"的基准比例：用户要求"以现在的 0.5 倍为 1.0 倍"，
         * 即默认过冲量 = 现在的一半（现在按行程算，见 [PetState.bounceAmp]）。
         */
        const val SUMMON_POP_BASE_RATIO = 0.5f

        /** "回弹幅度"标准值 = 回摆时反向冲过弹出高度的比例（现在 springBack 的 0.39） */
        const val SUMMON_SPRING_BASE = 0.39f

        const val SUMMON_TIME_MIN = 0.2f
        const val SUMMON_TIME_MAX = 5f
        const val SUMMON_POP_MIN = 0.1f
        const val SUMMON_POP_MAX = 10f
        const val SUMMON_SPRING_MIN = 0.1f
        const val SUMMON_SPRING_MAX = 10f

        // ---- 呼吸晃动 ----
        /**
         * 呼吸幅度标准值（法线方向占素材高度的比例）。
         * 用户要求"以现在的 1.3 倍为 1.0 倍"，所以是 0.015 × 1.3。
         */
        const val BREATH_SQ_BASE = 0.015f * 1.3f

        const val BREATH_MIN = 0.5f
        const val BREATH_MAX = 10f
    }
}
