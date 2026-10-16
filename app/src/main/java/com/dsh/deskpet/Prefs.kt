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
     * **锁定**（用户要求）：锁上以后
     *   · 形象在屏幕上不能被拖动；
     *   · App 里**任何数值都不许改** —— 所有参数 setter 都先过 [rejectWhenLocked]，
     *     被拦下时回调 [onBlockedAdjust]，由界面提示「请解除锁定以操作」并把控件弹回原值。
     * 「锁定互动」是另一个开关：[lockInteraction]，见它的说明。
     */
    var locked: Boolean
        get() = sp.getBoolean(KEY_LOCKED, false)
        set(v) { sp.edit().putBoolean(KEY_LOCKED, v).apply() }

    /**
     * **锁定互动**（用户要求）：锁定状态下还允不允许对屏幕上的形象做操作。
     * 顺序循环三种状态（[MODE_ON] -> [MODE_OFF] -> [MODE_FADE] -> 开）：
     *   · [MODE_ON]   开   -> 只保留点击与长按（拖动依旧被锁定挡掉）；
     *   · [MODE_OFF]  关   -> 点击 / 长按**完全没有反应**（连按压动画都不播），形象实心；
     *   · [MODE_FADE] 淡出 -> 形象半透明，并且**触控上完全透明**（点它等于点它下面的屏幕）。
     * **三种状态都只在锁定时生效**（未锁定时形象照常可点可拖，用户要求）。
     */
    var lockMode: Int
        get() {
            // 旧版本存的是布尔 lock_interact（true=开 / false=关），迁移一次：
            // 关 -> MODE_OFF（那个年代还没有"淡出"）。
            if (!sp.contains(KEY_LOCK_MODE) && sp.contains(KEY_LOCK_INTERACT)) {
                return if (sp.getBoolean(KEY_LOCK_INTERACT, true)) MODE_ON else MODE_OFF
            }
            return sp.getInt(KEY_LOCK_MODE, MODE_ON).coerceIn(MODE_ON, MODE_FADE)
        }
        set(v) { sp.edit().putInt(KEY_LOCK_MODE, v.coerceIn(MODE_ON, MODE_FADE)).apply() }

    /** 锁定时是否让形象**不接收触摸**（淡出状态）：点它等于点它下面的屏幕 */
    val petPassesThrough: Boolean
        get() = locked && lockMode == MODE_FADE

    /** 锁定期间有人试图改数值：界面挂上去做提示 + 把控件刷回存档值 */
    var onBlockedAdjust: (() -> Unit)? = null

    /**
     * 参数 setter 的统一闸门：锁定期间拒绝写入，并通知界面提示一次。
     *
     * ⚠️ 只在**真正"数值"**的 setter 里调（倍率、开关这些）；
     *    角色、明暗、通知文案、以及内部状态（位置、运行中…）不受锁定限制 ——
     *    用户要的是"没法调数值"，不是"整个 App 冻结"。
     */
    private fun rejectWhenLocked(): Boolean {
        if (!locked) return false
        onBlockedAdjust?.invoke()
        return true
    }

    /**
     * 宠物大小的**倍率**（用户可调，0.5~5）。
     *
     * 1.0 = 标准值 [SIZE_BASE]（占可用高度的 8%）。
     * 用户要求"以现在的大小为 1，幅度 0.5 到 5"，所以界面调的是倍率。
     */
    var sizeScale: Float
        get() = sp.getFloat(KEY_SIZE_SCALE, 1f).coerceIn(SIZE_SCALE_MIN, SIZE_SCALE_MAX)
        set(v) {
            if (rejectWhenLocked()) return
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
            if (rejectWhenLocked()) return
            sp.edit().putFloat(
                KEY_BOUNCE_AMP, v.coerceIn(AMP_SCALE_MIN, AMP_SCALE_MAX)
            ).apply()
        }

    /** 回弹回落时长**倍率**（1.0 = [BOUNCE_MS_BASE] = 360ms） */
    var bounceTimeScale: Float
        get() = sp.getFloat(KEY_BOUNCE_TIME, 1f).coerceIn(TIME_SCALE_MIN, TIME_SCALE_MAX)
        set(v) {
            if (rejectWhenLocked()) return
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
     * ⚠️ **按形象分开存**（用户要求："每个形象都有单独备份而不是共用一个"）：
     * 键是 `notify_text:<形象目录>`。换角色时 [skin] 变了，读写自然落到那个角色
     * 自己的槽位上 —— 界面与服务两边都不需要知道这件事（它们照旧读 [notifyText]）。
     *
     * 上限 [NOTIFY_TEXT_MAX] 个字符，允许任意语言、数字、符号与 emoji。
     * 按 UTF-16 code unit 计数（emoji 通常占 2 个）——简单、可控、不会切坏代理对
     * 到一半（take 不会把代理对拆开，因为上限远大于常见 emoji 的长度）。
     */
    var notifyText: String
        get() = skinValue(KEY_NOTIFY_TEXT)
        set(v) { putSkinValue(KEY_NOTIFY_TEXT, v.take(NOTIFY_TEXT_MAX)) }

    /**
     * 通知标题的自定义文案，用 `{name}` 表示角色名。**同样按形象分开存**。
     *
     * 空串表示"用默认"（[Prefs.NOTIFY_TITLE_DEFAULT]）—— 与正文一致，
     * 「留空即默认」比强制填更省事。真正的 `{name}` 替换在
     * [PetService.notificationTitle] 里做，因为只有那边知道当前角色名。
     */
    var notifyTitle: String
        get() = skinValue(KEY_NOTIFY_TITLE)
        set(v) { putSkinValue(KEY_NOTIFY_TITLE, v.take(NOTIFY_TITLE_MAX)) }

    /** 按形象分槽的键：`<旧键名>:<形象目录>` */
    private fun skinKey(base: String, skin: String): String = "$base:$skin"

    /**
     * 读**当前形象自己**的那份通知文案。
     *
     * 旧版只有一个全局共用值，所以第一次读某个形象时做一次性迁移：
     * 把那个旧值**归给升级那一刻正选着的形象**（就是这里读的这个），
     * 读完即删旧键 —— 不删的话每个形象首次读到都会拿到同一份旧值，
     * 那还是"共用一个"。其余形象留空 = 用默认文案（其中已含角色名）。
     */
    private fun skinValue(base: String): String {
        val key = skinKey(base, skin)
        sp.getString(key, null)?.let { return it }
        val legacy = sp.getString(base, null)
        if (legacy != null) {
            sp.edit().remove(base).putString(key, legacy).apply()
            return legacy
        }
        return ""
    }

    private fun putSkinValue(base: String, value: String) {
        sp.edit().putString(skinKey(base, skin), value).apply()
    }

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
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit().putBoolean(KEY_BOUNCE_ON, v).apply()
        }

    // ---------------------------------------------------------------- 呼出回弹

    /**
     * 呼出回弹总开关。关掉后呼出只做位置补间，不再过冲、也不回摆。
     *
     * ⚠️ 只管"呼出滑入 + 回摆"这一段；单击轻跳、长按弹起、落地回弹都不受影响。
     */
    var summonBounceEnabled: Boolean
        get() = sp.getBoolean(KEY_SUMMON_ON, true)
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit().putBoolean(KEY_SUMMON_ON, v).apply()
        }

    /** 呼出回弹**总时长**倍率：1.0 = [SUMMON_MS_BASE]（560ms），范围 0.2~5 */
    var summonTimeScale: Float
        get() = sp.getFloat(KEY_SUMMON_TIME, 1f).coerceIn(SUMMON_TIME_MIN, SUMMON_TIME_MAX)
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit()
                .putFloat(KEY_SUMMON_TIME, v.coerceIn(SUMMON_TIME_MIN, SUMMON_TIME_MAX))
                .apply()
        }

    /** 呼出**弹出高度**倍率：1.0 = 现在过冲量的一半，范围 0.1~10 */
    var summonPopScale: Float
        get() = sp.getFloat(KEY_SUMMON_POP, 1f).coerceIn(SUMMON_POP_MIN, SUMMON_POP_MAX)
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit()
                .putFloat(KEY_SUMMON_POP, v.coerceIn(SUMMON_POP_MIN, SUMMON_POP_MAX))
                .apply()
        }

    /** 呼出**回弹幅度**倍率：1.0 = 现在回摆反向冲过的比例（0.39），范围 0.1~10 */
    var summonSpringScale: Float
        get() = sp.getFloat(KEY_SUMMON_SPRING, 1f).coerceIn(SUMMON_SPRING_MIN, SUMMON_SPRING_MAX)
        set(v) {
            if (rejectWhenLocked()) return
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
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit().putBoolean(KEY_BREATH_ON, v).apply()
        }

    /**
     * 呼吸**幅度**倍率：1.0 = 基准幅度（[BREATH_SQ_BASE]），范围 [BREATH_MIN]~[BREATH_MAX]。
     *
     * ⚠️ 用户明确要求**幅度与速度分开**：
     * "呼吸幅度倍率则只决定呼吸动画的幅度" —— 所以这个值只影响幅度，
     * 不要再顺手拿它去缩周期（速度见 [breathSpeed]）。
     */
    var breathScale: Float
        get() = sp.getFloat(KEY_BREATH_SCALE, 1f).coerceIn(BREATH_MIN, BREATH_MAX)
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit().putFloat(KEY_BREATH_SCALE, v.coerceIn(BREATH_MIN, BREATH_MAX)).apply()
        }

    /**
     * 呼吸**速度**倍率：1.0 = 基准周期 [BREATH_PERIOD_BASE_MS]（2600ms），
     * 范围 [BREATH_SPEED_MIN]~[BREATH_SPEED_MAX]（0.1~10）。
     *
     * 值越大呼吸越快 —— 玩法是用它去**除**周期（见 [breathPeriodMs]）。
     * 用户要求："新增呼吸速度倍率，决定呼吸动画的播放速度，0.1~10 可调节"。
     */
    var breathSpeed: Float
        get() = sp.getFloat(KEY_BREATH_SPEED, 1f)
            .coerceIn(BREATH_SPEED_MIN, BREATH_SPEED_MAX)
        set(v) {
            if (rejectWhenLocked()) return
            sp.edit()
                .putFloat(KEY_BREATH_SPEED, v.coerceIn(BREATH_SPEED_MIN, BREATH_SPEED_MAX))
                .apply()
        }

    /** 呼吸在**法线**方向的幅度（占素材高度的比例） */
    val breathSq: Float get() = BREATH_SQ_BASE * breathScale

    /**
     * 呼吸周期（ms）= 基准周期 / 速度倍率。
     *
     * 换算本身在 [PetState.breathPeriod] 里（纯函数、有单测）—— 那段逻辑
     * 最容易把"速度"写成乘法，所以特意挪出去让它可测。
     */
    val breathPeriodMs: Float
        get() = PetState.breathPeriod(breathSpeed, BREATH_PERIOD_BASE_MS)

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

    /**
     * 气泡内容的原始存档串（转义后用 `\n` 分隔，每行开头带启用标志）。
     *
     * ⚠️ **按形象分开存**（用户 2026-10-16："气泡内容也是每个人物有备份，
     * 跟通知栏内容一样"）：键是 `bubble_texts:<形象目录>`，
     * 走与通知文案同一套 [skinValue] / [putSkinValue]（含旧全局值的一次性迁移 ——
     * 升级那一刻正选着的形象继承旧数据，其余形象从空开始）。
     *
     * ⚠️ 业务代码请走 [BubbleTextStore]（items / add / removeAt / setEnabled），
     *    这里只做最小的读写，格式与限制都在那边统一。
     */
    var bubbleTextsRaw: String
        get() = skinValue(KEY_BUBBLE_TEXTS)
        set(v) { putSkinValue(KEY_BUBBLE_TEXTS, v) }

    /**
     * 「气泡设置 -> 单击时出现」（用户 2026-10-17，默认开）。
     *
     * 关掉后：点人物**不再冒气泡**（长按等其它交互不受影响）。
     * 不按形象分开存 —— 这是使用习惯，不是每个角色的内容。
     */
    var bubbleOnTap: Boolean
        get() = sp.getBoolean(KEY_BUBBLE_ON_TAP, true)
        set(v) { sp.edit().putBoolean(KEY_BUBBLE_ON_TAP, v).apply() }

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
        private const val KEY_BUBBLE_TEXTS = "bubble_texts"
        private const val KEY_BUBBLE_ON_TAP = "bubble_on_tap"
        private const val KEY_BOUNCE_ON = "bounce_on"
        private const val KEY_RESET_KEEP = "reset_keep"
        private const val KEY_LOCKED = "locked"
        private const val KEY_LOCK_INTERACT = "lock_interact"   // 旧版布尔值，仅用于迁移
        private const val KEY_LOCK_MODE = "lock_mode"

        /** 锁定互动：开（点击/长按可用） */
        const val MODE_ON = 0

        /** 锁定互动：关（完全不响应，形象实心） */
        const val MODE_OFF = 1

        /** 锁定互动：淡出（半透明 + 触控穿透） */
        const val MODE_FADE = 2
        private const val KEY_SUMMON_ON = "summon_bounce_on"
        private const val KEY_SUMMON_TIME = "summon_time_scale"
        private const val KEY_SUMMON_POP = "summon_pop_scale"
        private const val KEY_SUMMON_SPRING = "summon_spring_scale"
        private const val KEY_BREATH_ON = "breath_on"
        private const val KEY_BREATH_SCALE = "breath_scale"
        private const val KEY_BREATH_SPEED = "breath_speed"

        /** 通知栏自定义内容的字符上限（用户指定 40） */
        const val NOTIFY_TEXT_MAX = 40

    /** 通知标题长度上限（比正文宽松些，标题允许写长一点） */
    const val NOTIFY_TITLE_MAX = 40

    /**
     * 标题的默认文案。`{name}` 是占位符，会被替换成当前角色名。
     * 与 strings.xml 里的 notif_title_default 保持一致。
     */
    const val NOTIFY_TITLE_DEFAULT = "{name} 正在陪伴你"

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

        /**
         * 呼吸**周期**基准值（ms）。倍率 1.0 对应它。
         *
         * 原来这个数写死在 `PetView.BREATH_PERIOD_MS`，现在挪到这里成为
         * 唯一真值，PetView 改为读 [breathPeriodMs]。
         */
        const val BREATH_PERIOD_BASE_MS = 2600f

        /**
         * 呼吸速度倍率范围：**0.1 ~ 10**（用户指定）。
         *
         * 与幅度范围（[BREATH_MIN]/[BREATH_MAX] = 0.5~10）**不同**：
         * 速度下限是 0.1（能慢到几乎看不出在动），幅度下限是 0.5。
         * 别把两者合并成一个常量。
         */
        const val BREATH_SPEED_MIN = 0.1f
        const val BREATH_SPEED_MAX = 10f
    }
}
