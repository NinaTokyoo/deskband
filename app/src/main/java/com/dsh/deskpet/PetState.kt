package com.dsh.deskpet

/**
 * 桌宠的分区、吸附与朝向规则（纯逻辑，不依赖 Android，可单元测试）。
 *
 * ============================ 分区（A / B / E） ============================
 *
 * 判定基准是桌宠的**足迹边缘**与**垂直基准边**（上沿或下沿，见 [vertOf]）：
 *
 *   dv = 足迹到**基准水平边**的距离（贴上沿时量上沿，贴下沿时量下沿）
 *   dr = 足迹到**本侧吸附边**的距离（左半屏量左边，右半屏量右边）
 *
 *   A 区：dv <= aH 且 dr <= aW   —— 角落（上角或下角，由基准边决定）。用「角落」素材
 *   B 区：排除 A 后 dr <= aW      —— 侧边带。用「正立素材」，**旋转 90° 躺倒**
 *   E 区：其余                    —— 用「正立素材」；松手时贴**更近的那条边**
 *
 * **A 区尺寸用户可调**（滑块 / 数字输入），B 跟着一起变：
 *   B 的横向起点 = A 的横向尺寸（B 左侧对齐 A 左侧）
 *
 * **上边组只在离上沿 [TOP_BAND_PX] 的带子里成立**（用户规定）：
 *   带内 -> 上角 / 贴上沿，素材**上下翻转**（[flipVFor]），头朝下；
 *   带外 -> 一律按下边组算，**跟贴下沿完全一致**（不看"上半屏"）。
 *   左右两侧的**旋转区（B 区）**永远是躺倒形态，不上翻也不下翻。
 *
 * 没有 D 区，**没有模糊**。稳定存在的朝向只有「正立」与「旋转 90°」两种。
 * ============================================================================
 *
 * ==================== 为什么要有 VERT_* 这个显式枚举 ====================
 *
 * 上边吸附与下边吸附**完全对称**，唯一的差别是"以哪条水平边为基准"。
 * 曾经的做法是直接把 `bottomDist` 换成 `min(topDist, bottomDist)` 就完事 ——
 * 结果下游全部错位：zoneOf / snapEdgesForZone / snapPosition 的语义都建立在
 * "到基准边的距离"上，悄悄换掉距离的含义却不同步改判定规则，于是
 * "上边/上角吸不上、初始位置变了、侧边头部朝向错了"一起爆发。
 *
 * 所以基准在这里**显式建模**：[vertOf] 只负责"选哪条边"，
 * 距离函数 [vertDistFor] 只负责"量到那条边的距离"，
 * 判定与吸附函数全部**显式接受**这个枚举。任何一处漏传，编译器立刻报错。
 * ============================================================================
 */
object PetState {

    // ---------------------------------------------------------------- 吸附状态

    const val CORNER_BR = 0
    const val CORNER_BL = 1
    const val BORDER_R = 2
    const val BORDER_L = 3

    /** 贴底 / 侧边带时用的"正立素材"变体（素材同边框图，但不旋转） */
    const val BORDER_R_UPRIGHT = 5
    const val BORDER_L_UPRIGHT = 6

    /**
     * **上方角落**（与下方的 CORNER_BR/CORNER_BL 完全对称）。
     *
     * 吸附规则与下方角落一致（贴侧 + 贴水平边），区别只在：
     *   · 水平方向贴上沿（见 [vertForState] 返回 VERT_TOP）
     *   · 素材**上下翻转**（见 [flipVFor]）
     *   · 收起时向**上**滑出（见 PetView.exitVector）
     */
    const val CORNER_TR = 7
    const val CORNER_TL = 8

    /**
     * **贴上沿**（正立素材上下翻转），与贴下沿的 [BORDER_R_UPRIGHT] /
     * [BORDER_L_UPRIGHT] 完全对称 —— 上边带内的"正立"就是下边倒过来挂。
     */
    const val EDGE_TOP_R = 9
    const val EDGE_TOP_L = 10

    // ---------------------------------------------------------------- 垂直基准

    /**
     * **无纵向基准**：只贴侧边，纵向自由（旋转躺倒的侧边形态）。
     *
     * 也用作 [vertOf] 的 `prev` 默认值，表示"没有上一次的基准"。
     */
    const val VERT_NONE = 0

    /** 下边组：以屏幕**下沿**为垂直基准 */
    const val VERT_BOTTOM = 1

    /** 上边组：以屏幕**上沿**为垂直基准（素材上下翻转、向上收、从上方呼入） */
    const val VERT_TOP = 2

    /**
     * **上边带的纵向范围**（px，从屏幕上沿量起）。
     *
     * 用户规定：**离上沿 240px 以内人物才是头朝下的**（上角 / 贴上沿），
     * 上方其余区域一律**跟下边组保持一致** —— 也就是说不看"上半屏"，
     * 只看这 240px 的带子。
     *
     * 曾经按"屏幕中线"分组，结果整个上半屏都倒挂：拖到屏幕中间偏上就翻过来，
     * 而那个位置离上沿还很远，观感上根本不是"贴在顶上"。
     *
     * ⚠️ 左右两侧的**旋转区**（B 区，躺倒形态）不在这条带子的规则里：
     * 躺倒形态从来不上翻下翻（见 [flipVFor]）。而带内贴着侧边时
     * 判的是 A 区（上角），那也不是旋转形态。
     */
    const val TOP_BAND_PX = 240f

    /**
     * 换组滞回带（px）：在 [TOP_BAND_PX] 边界上**多走这么远**才换组。
     *
     * 不设它的话，手指停在 240px 边界附近抖动会让素材在"正立 / 倒挂"之间高频翻转。
     * 换组是**整个形态翻过来**的视觉变化，比 B/E 分界那种切换更醒目，
     * 所以死区比 [HYSTERESIS_PX] 稍大。
     */
    const val VERT_HYSTERESIS_PX = 24f

    // ---------------------------------------------------------------- A 区尺寸（可调）

    const val A_W_DEFAULT = 200f
    const val A_H_DEFAULT = 365f
    const val A_W_MIN = 80f
    const val A_W_MAX = 600f
    const val A_H_MIN = 80f
    const val A_H_MAX = 900f

    @Volatile
    var currentAW: Float = A_W_DEFAULT
        private set

    @Volatile
    var currentAH: Float = A_H_DEFAULT
        private set

    fun setZoneSize(w: Float, h: Float) {
        currentAW = w.coerceIn(A_W_MIN, A_W_MAX)
        currentAH = h.coerceIn(A_H_MIN, A_H_MAX)
    }

    // ---------------------------------------------------------------- 区号

    const val ZONE_A = 0
    const val ZONE_B = 1
    const val ZONE_C = 2
    const val ZONE_E = 4

    /** 兼容旧引用 */
    val CORNER_ZONE_PX: Float get() = currentAW
    val BLUR_ZONE_PX: Float get() = currentAH
    val EDGE_ZONE_PX: Float get() = currentAW

    /**
     * 滞回带宽度（px）：B/E 分界线上留出的"保持原状"死区，
     * 用来吸收手指抖动，避免状态在边界反复横跳。
     */
    const val HYSTERESIS_PX = 14f

    // ---------------------------------------------------------------- 状态判定

    /** 是否属于**上边组**：素材上下翻转、向上收起、从上方呼入 */
    fun isTop(state: Int): Boolean = vertForState(state) == VERT_TOP

    fun isCorner(state: Int) =
        state == CORNER_BR || state == CORNER_BL || state == CORNER_TR || state == CORNER_TL

    fun isBorder(state: Int) = state == BORDER_R || state == BORDER_L

    fun isUprightVariant(state: Int) =
        state == BORDER_R_UPRIGHT || state == BORDER_L_UPRIGHT

    /** 是不是"躺在侧边"的形态（垂直居中，不贴底） */
    fun isZoneless(state: Int) = isUprightVariant(state)

    /**
     * 这个状态算不算"吸附在边上"。
     * 用来决定从该状态起拖时要不要加阻尼 —— 贴边的宠物才需要"脱开"手感。
     */
    fun isAttachedState(state: Int): Boolean = when (state) {
        BORDER_R, BORDER_L, BORDER_R_UPRIGHT, BORDER_L_UPRIGHT,
            CORNER_BR, CORNER_BL, CORNER_TR, CORNER_TL,
            EDGE_TOP_R, EDGE_TOP_L -> true
        else -> false
    }

    fun isLeft(state: Int): Boolean = when (state) {
        CORNER_TL, EDGE_TOP_L,
        CORNER_BL, BORDER_L, BORDER_L_UPRIGHT -> true
        else -> false
    }

    /** 是否用「角落」那套素材 */
    fun usesCornerArt(state: Int) = isCorner(state)

    /** 是否用「正立素材」那套图 */
    fun usesBorderArt(state: Int) = !usesCornerArt(state)

    fun needsMirror(state: Int) = isLeft(state)

    /**
     * 是否处于某个**稳定吸附状态**（角落 / 贴边）—— 也就是"能冒思考气泡"的那些状态。
     *
     * 拖拽、滑动过程中 state 也是这些值，但那一刻并没有停稳，所以冒泡时机由
     * PetView 的点击动画控制（只有真的"点了一下"才会走到这里）。
     */
    fun isSnapped(state: Int): Boolean = when (state) {
        CORNER_BR, CORNER_BL, CORNER_TR, CORNER_TL,
        BORDER_R, BORDER_L, BORDER_R_UPRIGHT, BORDER_L_UPRIGHT,
        EDGE_TOP_R, EDGE_TOP_L -> true
        else -> false
    }

    // ---------------------------------------------------------------- 思考气泡摆哪一侧

    /** 气泡摆在人物**素材坐标系**的右侧（= 人物右上，默认） */
    const val BUBBLE_SIDE_RIGHT = 1

    /** 气泡摆在人物**素材坐标系**的左侧（= 人物左上） */
    const val BUBBLE_SIDE_LEFT = -1

    /**
     * 气泡摆在哪一侧 —— 纯函数，便于单测。
     *
     * ⚠️ 返回值是**人物素材自己坐标系**里的侧别（+1 = 素材右侧＝人物右上；
     *    -1 = 素材左侧＝人物左上），不是屏幕左右。用户 2026-10-16 明确：
     *    "后面我说的位置均为从人物自己的角度看"。
     *
     * 用户规则（对应 2.1.33 的推广）：
     *   1. 贴右边框：默认**右上**；命中任一条件则**左上** ——
     *        · 条件1：人物**吸附边中点**在右边框"上 1/3 点"及以上（含压线）
     *        · 条件2：按默认摆法时气泡的**最上沿**在右边框"上 1/5 点"及以上（含压线）
     *   2. 右下角 -> 左上；左下角是它的左右镜像 -> 右上；
     *      上方的两个角是下方的**上下镜像**（左右不变）-> 右上角左上、左上角右上
     *   3/4. 贴底边 / 贴顶边：吸附边中点在屏幕水平中线**及右侧** -> 左上，否则右上
     *        （贴顶边 = 底边的上下镜像，判据相同）
     *   5. 贴左边框 = 贴右边框的**左右镜像**：判据完全一样，只是屏幕上左右相反
     *
     * @param edgeMidX 吸附边中点的屏幕 x（贴底/顶边时 = 足迹盒中心 x）
     * @param edgeMidY 吸附边中点的屏幕 y（贴左/右边框时 = 足迹盒中心 y）
     * @param defaultTopY 按默认（右上）摆法时，气泡窗口的**最上沿** y（条件2 用）
     */
    fun bubbleSide(
        state: Int, edgeMidX: Float, edgeMidY: Float,
        screenW: Float, screenH: Float, defaultTopY: Float
    ): Int = when (state) {
        BORDER_R, BORDER_L ->
            if (edgeMidY <= screenH / 3f || defaultTopY <= screenH / 5f) BUBBLE_SIDE_LEFT
            else BUBBLE_SIDE_RIGHT

        CORNER_BR, CORNER_TR -> BUBBLE_SIDE_LEFT
        CORNER_BL, CORNER_TL -> BUBBLE_SIDE_RIGHT

        // 贴底边（BORDER_*_UPRIGHT）与贴顶边（EDGE_TOP_*）：看吸附边中点在中线哪一侧
        else -> if (edgeMidX >= screenW / 2f) BUBBLE_SIDE_LEFT else BUBBLE_SIDE_RIGHT
    }

    /**
     * 素材要不要**上下翻转**（flipV）。
     *
     * 上边组的形态就是下边组沿水平轴镜像过来的：贴在底边是"站在地上"，
     * 贴在上沿就是"倒挂在顶上"。
     *
     * ⚠️ 这个判断必须**只是 state 的纯函数**（内部走 [vertForState]）。
     * PetView 的 artCenterCache / scaledCache 都以 state 为键，
     * 若翻转取决于运行时的临时标志（比如"当前离上沿近不近"），
     * 缓存就会串味 —— 素材中心点、命中判定与实际画面全对不上，
     * 表现就是"位置自己变了、点击没反应"。
     *
     * 旋转形态（BORDER_R/BORDER_L）**不翻**：它们本来就是靠镜像 + 旋转角
     * 让头朝屏幕内侧的，再上下翻转会把人物倒过来（曾经踩过）。
     */
    fun flipVFor(state: Int): Boolean = isTop(state)

    /**
     * 状态对应的旋转角（传给 Canvas.rotate，正数 = 屏幕视觉顺时针）。
     *
     * 用户规定：**吸附右边框时人物头朝左；吸附左边框时头朝右。**
     *
     * ⚠️ 符号以**用户实机观感**为准，不要再凭几何直觉改：
     * 右侧用 +90 时用户反馈"方向反了"，因此取 -90。
     */
    fun rotationForSnap(state: Int): Float = when (state) {
        BORDER_R -> -90f
        BORDER_L -> 90f
        else -> 0f
    }

    /**
     * 这两个状态是不是"**同一张图水平镜像**"的关系：贴右边框 <-> 贴左边框。
     *
     * 可以证明（也是实机观感）：
     *   rotate(+90) ∘ mirror  ≡  mirror ∘ rotate(-90)
     * 所以 [BORDER_L] 画出来的东西**正好是** [BORDER_R] 的水平镜像。
     *
     * 于是左右互切时**根本不需要转 180°** —— 换图本身就是镜像，
     * 硬转只会让人物先立正再倒下、白转一大圈。剩下的交给镜像翻转动画。
     */
    fun isSideMirrorPair(a: Int, b: Int): Boolean =
        (a == BORDER_R && b == BORDER_L) || (a == BORDER_L && b == BORDER_R)

    /**
     * 从一个状态转到另一个状态，**最少**要转多少度（带符号，最短弧）。
     *
     * 用户要求："需要旋转的时候无论方向旋转最小角度"。
     *   · 0 <-> ∓90  -> ±90
     *   · 左右边框互切 -> **0**（互为水平镜像，见 [isSideMirrorPair]）
     *   · 其余（旋转角相同）-> 0
     */
    fun rotationDelta(fromState: Int, toState: Int): Float {
        if (isSideMirrorPair(fromState, toState)) return 0f
        var d = (rotationForSnap(toState) - rotationForSnap(fromState)) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    // ---------------------------------------------------------------- 垂直基准

    fun topDist(y: Float, h: Float, screenH: Float): Float = y.coerceAtLeast(0f)

    /** 离底边的距离（两侧相同） */
    fun bottomDist(y: Float, h: Float, screenH: Float): Float =
        (screenH - (y + h)).coerceAtLeast(0f)

    /**
     * 到**较近的那条水平边**的距离（上沿或下沿取小）。
     * 只用于"没有指定基准"的场合（[VERT_NONE]）。
     */
    fun vertDist(y: Float, h: Float, screenH: Float): Float =
        minOf(topDist(y, h, screenH), bottomDist(y, h, screenH))

    /**
     * **选垂直基准**：足迹进到离上沿 [TOP_BAND_PX] 的带子里就是上边组（倒挂），
     * 其余一律是下边组 —— **上方其余区域跟下边完全一致**（用户规定）。
     *
     * ⚠️ 判据是"到上沿的距离"，**不是"在上半屏"**：屏幕中线跟这件事无关。
     *
     * 带**边界滞回**：已经是上边组时要越出 [TOP_BAND_PX] + [VERT_HYSTERESIS_PX]
     * 才退回下边组，避免手指停在边界上时素材在正立/倒挂之间反复翻。
     *
     * @param prev 上一次的基准；[VERT_NONE] 表示没有上一次
     * @return [VERT_TOP] 或 [VERT_BOTTOM]（**从不返回 VERT_NONE**）
     */
    fun vertOf(y: Float, h: Float, screenH: Float, prev: Int = VERT_NONE): Int {
        val t = topDist(y, h, screenH)
        return when (prev) {
            // 已经在上边组：要明显离开这条带子才换组
            VERT_TOP -> if (t > TOP_BAND_PX + VERT_HYSTERESIS_PX) VERT_BOTTOM else VERT_TOP
            // 不在上边组：要真正进到带子里才换组
            else -> if (t <= TOP_BAND_PX) VERT_TOP else VERT_BOTTOM
        }
    }

    /**
     * 该基准下"到基准边的距离" —— 分区与吸附**统一用这个当垂直轴距离**。
     *
     * 于是上边组与下边组共用同一套 A/B/E 判定，天然对称。
     * [VERT_NONE]（没有基准）时退化成"到较近那条水平边的距离"。
     */
    fun vertDistFor(v: Int, y: Float, h: Float, screenH: Float): Float = when (v) {
        VERT_TOP -> topDist(y, h, screenH)
        VERT_BOTTOM -> bottomDist(y, h, screenH)
        else -> vertDist(y, h, screenH)
    }

    /**
     * 状态挂在**哪条水平边**上 —— 由状态反推基准（持久化恢复、复位、初始窗口都用它）。
     *
     *   上角 / 贴上沿 -> [VERT_TOP]
     *   躺侧边（旋转）-> [VERT_NONE]（纵向自由，垂直居中）
     *   其余（下角 / 贴下沿）-> [VERT_BOTTOM]
     */
    fun vertForState(state: Int): Int = when (state) {
        CORNER_TR, CORNER_TL, EDGE_TOP_R, EDGE_TOP_L -> VERT_TOP
        BORDER_R, BORDER_L -> VERT_NONE
        else -> VERT_BOTTOM
    }

    /**
     * 素材**中心点**离本侧吸附边的距离。
     *
     * 用户最新规则：**旋转与否，就看素材中心点有没有越过 B/E 分界线。**
     * 分界线在离侧边 [currentAW] 处，所以中心点距离 <= aW 就算"进了 B"，
     * 该旋转；否则算 E，保持正立。
     *
     * 这与 [sideDist]（按素材**边缘**量）是两个不同的基准，别混用。
     */
    fun centerSideDist(cx: Float, screenW: Float, leftHalf: Boolean): Float =
        if (leftHalf) cx.coerceAtLeast(0f) else (screenW - cx).coerceAtLeast(0f)

    // ---------------------------------------------------------------- 距离

    /**
     * 离**本侧吸附边**的距离 —— 左半屏量左边，右半屏量右边。
     *
     * 这个镜像很关键：早先在左半屏仍量"离右边"，左下角的宠物会被算成
     * 离侧边很远，判进 C 区，永远吸不到左下角。
     */
    fun sideDist(x: Float, w: Float, screenW: Float, leftHalf: Boolean): Float =
        if (leftHalf) x.coerceAtLeast(0f)
        else (screenW - (x + w)).coerceAtLeast(0f)

    // ---------------------------------------------------------------- 分区判定

    /**
     * 落在哪个区。
     *
     * 先判 A（角落），再判 B（贴侧边），其余都是 E。
     *
     * ⚠️ 原来的 **C 区（贴底边带）已按要求合并进 E 区**，与 E 完全同一套逻辑：
     * 松手时贴"更近的那条边" —— 在底部附近自然就是贴底边，所以观感不变，
     * 但靠近底部又靠近侧边时会正确地贴侧边并旋转，不再被 C 强行拉到底边。
     * [ZONE_C] 常量保留（历史值与测试引用），但**不会再被返回**。
     *
     * @param v  垂直基准（[vertOf] 的结果）
     * @param dv 到**基准边**的距离（[vertDistFor] 的结果）
     * @param dr 到本侧吸附边的距离（[sideDist] 的结果）
     */
    fun zoneOf(v: Int, dv: Float, dr: Float, leftHalf: Boolean): Int {
        // A 区（角落）必须有纵向基准才成立：没有基准就没有"角"
        if (v != VERT_NONE && dv <= currentAH && dr <= currentAW) return ZONE_A
        if (dr <= currentAW) return ZONE_B
        return ZONE_E
    }

    /**
     * 带**滞回**的分区判定，专治 B/E 分界线附近来回闪。
     *
     * 问题：单纯比较 dr 与 aW，宠物停在分界线上时手指的微小抖动
     * 就会让 dr 在 aW 两侧反复越界，状态于是来回切。
     *
     * 做法：进入 B 用较严的门槛（必须真正贴边），退出 B 用较松的门槛
     * （要明显离开才退出），两个门槛之间是"保持原状"的死区。
     *
     * ⚠️ B/E 用**中心点**判据（用户规则），但门槛必须把素材**半宽**算进去：
     *
     *   宠物完全贴紧侧边时，中心点离侧边的距离正好等于**半宽**。
     *   若拿 aW(默认200) 直接当门槛，而半宽有 231~270，
     *   那么"贴边静止"这个位置反而被判成 E —— 状态会在旋转/正立之间
     *   反复横跳，而且 leftHalf 依赖 trackW，trackW 一跳过中线判定也跟着抖，
     *   镜像翻转看起来就不对了。
     *
     * 所以传入的门槛是 `aW + 半宽`：等价于"素材**边缘**落在 aW 以内"，
     * 既保留了中心点判据，又让贴边静止位置稳定落在 B 内。
     *
     * ⚠️ **角落（A 区）边界同样要滞回**：A 与 B/E 的分界就是 `dr == aW`
     * （中心点判据那侧也是同一条线），所以贴着角落拖时手指抖一两像素，
     * 就会在"角落素材 <-> 正立素材"之间来回切 —— 两套图连宽度都不同
     * （约 263 vs 289），看起来就是**闪**（用户反馈：
     * "切换角落素材与非角落素材时会有闪烁现象"）。已经在 A 里时把门槛放宽
     * [HYSTERESIS_PX]，要明显离开才退出。
     *
     * @param v    垂直基准（[vertOf] 的结果）
     * @param dv   到**基准边**的距离
     * @param prev 上一次的区号，用于决定用哪个门槛
     * @param bandPx 中心点到侧边的门槛，调用方传 currentAW + trackW/2
     */
    fun zoneWithHysteresis(
        v: Int, dv: Float, dr: Float, leftHalf: Boolean, prev: Int,
        centerDist: Float = dr,
        bandPx: Float = currentAW
    ): Int {
        // C 已并入 E，不再单判。A 带滞回：已在角落则要"明显离开"才退出
        val aPad = if (prev == ZONE_A) HYSTERESIS_PX else 0f
        if (v != VERT_NONE && dv <= currentAH + aPad && dr <= currentAW + aPad) return ZONE_A

        val inB: Boolean
        if (prev == ZONE_B) {
            // 已经在 B：要明显离开才退出
            inB = centerDist <= bandPx + HYSTERESIS_PX
        } else {
            // 不在 B：中心点要真正越过分界线才进入
            inB = centerDist <= bandPx
        }
        return if (inB) ZONE_B else ZONE_E
    }

    /** 已取消模糊功能，永远返回 false（接口保留） */
    fun hasBlur(v: Int, dv: Float, dr: Float, leftHalf: Boolean): Boolean = false

    /**
     * 松手时贴哪条边：`[0]` = 贴**水平边**（上沿还是下沿由 [snapPosition] 里的基准决定），
     * `[1]` = 贴**侧边**。
     *
     * ⚠️ 必须传入**已经判定好的 zone**，不要在这里重新分区。
     * 因为分区判定用的是"中心点是否越过 B/E 分界线"，而 zoneOf 用的是
     * 边缘距离，两者在素材较大时会得出不同结论 —— 结果就是判成了 B（该贴侧边）
     * 却按 E 去贴最近边，贴到底边上了（用户反馈的"侧边不吸附"）。
     *
     * ⚠️ E 区"贴更近的那条边"的比较也**必须在这里**做完，调用方不要自己再比一遍：
     * 两处规则一旦分叉，就会出现"显示贴在侧边、实际吸到了底边"这类错位。
     *
     * @param v  垂直基准；[VERT_NONE] 时 A 区退化（没有角可贴，只贴侧边）
     * @param dv 到**基准边**的距离（E 区用它和 [dr] 比远近）
     */
    fun snapEdgesForZone(v: Int, zone: Int, dv: Float, dr: Float): IntArray = when (zone) {
        // 角落要两条边都贴；但"哪个角"依赖纵向基准，没有基准就只能贴侧边
        ZONE_A -> if (v == VERT_NONE) intArrayOf(0, 1) else intArrayOf(1, 1)
        ZONE_C -> intArrayOf(1, 0)
        ZONE_B -> intArrayOf(0, 1)
        // E：贴更近的那条边（dv 已经是"到基准水平边"的距离）
        else -> if (dv <= dr) intArrayOf(1, 0) else intArrayOf(0, 1)
    }

    /**
     * 该用哪个状态（同时决定素材、旋转与是否上下翻转）。
     *
     *   A 区 -> 角落素材（纵向基准决定是上角还是下角），正立/倒挂
     *   B 区 -> 正立素材，**贴边时旋转 90°**（头朝屏幕内侧）
     *   C 区 -> 正立素材，正立（历史区号，已并入 E）
     *   E 区 -> 正立素材：贴水平边（上/下由基准决定），旋转与否由调用方按 B 区判定
     *
     * @param v 垂直基准 —— 决定"上角 / 下角"和"贴上沿 / 贴下沿"
     */
    fun stateForZone(v: Int, zone: Int, leftHalf: Boolean): Int = when (zone) {
        ZONE_A -> if (v == VERT_TOP) {
            if (leftHalf) CORNER_TL else CORNER_TR
        } else {
            if (leftHalf) CORNER_BL else CORNER_BR
        }
        ZONE_B -> if (leftHalf) BORDER_L else BORDER_R
        ZONE_C -> uprightFor(v, leftHalf)
        else -> uprightFor(v, leftHalf)
    }

    /** 贴水平边（不旋转）时的状态：上沿倒挂，下沿正立 */
    private fun uprightFor(v: Int, leftHalf: Boolean): Int =
        if (v == VERT_TOP) {
            if (leftHalf) EDGE_TOP_L else EDGE_TOP_R
        } else {
            if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
        }

    // ---------------------------------------------------------------- 吸附

    /**
     * 计算吸附后的格子位置（贴边）。
     *
     * @param v 垂直基准（[VERT_TOP] -> 贴上沿，其余 -> 贴下沿）
     * @param trackW/trackH 吸附后素材在屏幕上的**足迹**（旋转后宽高可能互换）
     * @return [x, y]
     */
    fun snapPosition(
        v: Int,
        x: Float, y: Float,
        trackW: Float, trackH: Float,
        dv: Float, dr: Float,
        screenW: Float, screenH: Float,
        leftHalf: Boolean,
        zone: Int = -1
    ): FloatArray {
        // 分区优先用调用方判定好的 zone（与状态判定保持同一套规则）；
        // 没传时退化为按边缘距离现算（仅旧调用方会走到这里）。
        val z = if (zone >= 0) zone else zoneOf(v, dv, dr, leftHalf)
        val edges = snapEdgesForZone(v, z, dv, dr)
        var nx = x
        var ny = y
        if (edges[0] == 1) ny = if (v == VERT_TOP) 0f else screenH - trackH
        if (edges[1] == 1) nx = if (leftHalf) 0f else screenW - trackW
        return floatArrayOf(nx, ny)
    }

    /**
     * 把格子夹在屏幕内，保证至少有一部分可见（避免被拖丢）。
     */
    fun clampToScreen(
        x: Float, y: Float,
        w: Float, h: Float,
        screenW: Float, screenH: Float,
        minVisible: Float = 0.5f
    ): FloatArray {
        if (w <= 0f || h <= 0f) return floatArrayOf(x, y)
        val f = minVisible.coerceIn(0f, 1f)
        return floatArrayOf(
            x.coerceIn(-w + w * f, screenW - w * f),
            y.coerceIn(-h + h * f, screenH - h * f)
        )
    }

    // ---------------------------------------------------------------- 缩放

    /**
     * 按素材自身**正立比例**等比缩放，得到绘制格子。
     *
     * 用户硬要求："素材无论如何旋转比例不要变"。
     * 旋转只作用在 Canvas 上，从不修改格子的宽高。
     */
    fun scaledSize(natW: Int, natH: Int, targetH: Float): IntArray {
        if (natW <= 0 || natH <= 0) return intArrayOf(1, 1)
        val scale = targetH / natH
        return intArrayOf(
            Math.round(natW * scale).coerceAtLeast(1),
            Math.round(natH * scale).coerceAtLeast(1)
        )
    }

    // ---------------------------------------------------------------- 动画曲线

    /**
     * 呼出回弹的"弹一下"曲线（纯函数，便于单测）。
     *
     * 输入 u ∈ [0,1]（回弹段进度），输出**相对落点的偏移系数**：
     *   - `u = 0` 时为 **1** —— 正好停在过冲点上（偏移量 = 过冲量 amp）
     *   - 中途摆到**负值** —— 冲过落点、到另一侧一点点，这就是"弹一下"
     *   - `u = 1` 时严格为 **0** —— 必须精确落在落点上，一像素缝都不能留
     *
     * 用 `(1-u)·cos(π·swings·u)`：一次阻尼摆动，冲过去 → 荡回来 → 贴住。
     *
     * 原来第二段用的是 easeOutCubic（`1-(1-t)³`），那只是"减速停住"，
     * 中途**不会越过落点**，所以看起来是很生硬地刹住；
     * 第一段又用 smoothstep，中段近似匀速（0.3→0.7 就走了 57% 行程），
     * 整段观感因此偏线性平移。用户反馈：
     * "呼出的回弹动画是线性的吗？感觉很生硬。能不能做出弹了一下的感觉？"
     *
     * @param swings 摆动快慢系数：越大摆得越快越浅。
     *   **必须 ≤ 1.5**，否则 `cos` 在 u=1 之前又变回正数，尾巴上会多出一小段
     *   反向摆动（单测里有"只允许穿过落点一次"的断言，1.6 就是被它挡下来的）。
     *   取 1.5 时刚好在 u=1 处回到 0：一次性摆到最低约 **-0.39**，
     *   也就是"向落点另一侧冲过约 39% 的过冲量"，弹一下很明显。
     */
    fun springBack(u: Float, swings: Float = 1.5f): Float {
        val t = u.coerceIn(0f, 1f)
        return (1f - t) * kotlin.math.cos((Math.PI * swings * t).toFloat())
    }

    /**
     * 进场（呼入）用的缓动：**冲进来然后刹住**。
     *
     * 与 [smoothstep] 的区别：smoothstep 中段近似匀速（显得像线性平移），
     * 而这条起步就是全程最快、之后一路减速 —— 观感是"啪一下到位"。
     * 收起仍用 smoothstep，因为那边的要求是"慢慢平移出去"。
     */
    fun easeInArrive(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        val k = 1f - t
        return 1f - k * k * k
    }

    /** 收起（滑出）用的缓动：两头慢、中段快，观感是"慢慢平移出去" */
    fun smoothstep(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 呼出回弹的**过冲量**：按行程放大，但夹在 `[base, base×maxFactor]` 之间。
     *
     * 为什么需要它：靠边吸附的回弹看着很"Q弹"，是因为**行程短**（拖动后松手，
     * 往往只有几十像素），36px 的过冲占了行程的 24%~90%，一眼就能看到；
     * 而呼出的行程是"从屏幕外滑回落点"（300~900px），同样是 36px，
     * 只占 4%~11% —— 同一条曲线、同一个幅度，一个弹一个不弹，差别全在这里。
     *
     * 所以过冲量跟着行程走（默认 22%），但两头都夹住：
     *   - 不低于用户设定的 [base]：短行程（滑回、连点）时手感和靠边吸附完全一致；
     *   - 不超过 base 的 [maxFactor] 倍：行程再长也不会甩出夸张的一大截
     *     （素材只有两三百像素，过冲太大就成了"飞出去又飞回来"）。
     *
     * @param travel 这次的行程（像素）
     * @param base 用户设定的回弹幅度
     */
    fun bounceAmp(
        travel: Float,
        base: Float,
        travelFrac: Float = 0.22f,
        maxFactor: Float = 2f
    ): Float {
        if (base <= 0f || travel <= 0f) return 0f
        return maxOf(base, minOf(travel * travelFrac, base * maxFactor))
    }

    /**
     * 命中判定用的**有效外接框**：把素材 alpha 外接框以**中心为基准等比缩小** `scale` 倍。
     *
     * 为什么要缩：单像素 alpha 判定之外还有一条"落在 alpha 外接框里也算命中"的兜底
     * （用来兜住发丝之间的透明缝隙、抗锯齿边缘 —— 否则点上去会穿透到下层桌面）。
     * 但外接框是**矩形**，人物往往不占满它，于是"明显是空白的地方"也能点中。
     * 用户反馈："感觉可互动面积还是有点大，互动面积等比缩小到现在的 75%。"
     *
     * 缩小后：人物轮廓本身（不透明像素）照样可点，只是外接框外侧那一圈空白不再命中。
     *
     * @param bounds `[left, top, right, bottom]`（`alphaBounds` 的结果，单位像素）
     * @return `[left, top, right, bottom]`，中心不变、宽高各乘 `scale`
     */
    fun hitBox(bounds: IntArray, scale: Float): FloatArray {
        val l = bounds[0].toFloat()
        val t = bounds[1].toFloat()
        val r = bounds[2].toFloat()
        val b = bounds[3].toFloat()
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val hw = (r - l) * scale.coerceIn(0.05f, 1f) / 2f
        val hh = (b - t) * scale.coerceIn(0.05f, 1f) / 2f
        return floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /**
     * 某状态的可互动面积缩放系数。
     *
     * ⚠️ **四个角落保持 1.0（完全不缩）**。用户要求：
     * "吸附至四个角落时的面积不要改动，只改动没有吸附到四个角落情况的"。
     * 理由也站得住：角落素材（四角那套图）透明缝隙最多 —— 发丝、头与身体之间
     * 到处都是洞，缩小后很容易点不中、抓不住；而角落位置本来就贴死在屏幕角上，
     * 留一圈可点区域反而更好把它拖出来。
     *
     * @param normal 非角落状态用的系数（默认 0.75 = 缩到 75%）
     */
    fun hitBoxScaleFor(state: Int, normal: Float = 0.75f): Float =
        if (isCorner(state)) 1f else normal

    /**
     * 点击/长按压缩时，宠物**贴着哪条屏幕边**。
     *
     * 压缩规则是"吸附边不动，另一侧压过来"，弹起方向是它的反方向（朝屏幕内侧）——
     * 四条边共用同一套，只是要按状态**旋转到对应角度**：
     *
     * | 状态 | 吸附边 | 法线（画布坐标） | 支点（画布局部 y） |
     * |---|---|---|---|
     * | `CORNER_BR/BL`、`BORDER_*_UPRIGHT` | 下边 | y | 格子**下**缘 |
     * | `CORNER_TR/TL`、`EDGE_TOP_*` | 上边 | y | 格子**上**缘 |
     * | `BORDER_R` | 右边 | y（素材已躺倒，法线在画布上仍是 y） | 格子**下**缘 |
     * | `BORDER_L` | 左边 | 同上 | 格子**下**缘 |
     *
     * 贴左右边框时素材是旋转 90° 画的，画布的 y 轴正好指向屏幕的左右方向，
     * 所以法线仍然是 y —— 四种边在绘制代码里因此可以走同一套缩放/平移。
     */
    const val ATTACH_BOTTOM = 0
    const val ATTACH_TOP = 1
    const val ATTACH_LEFT = 2
    const val ATTACH_RIGHT = 3

    /** 该状态贴在哪条屏幕边上（见 [ATTACH_BOTTOM] 等常量的说明） */
    fun attachEdgeOf(state: Int): Int = when {
        state == BORDER_R -> ATTACH_RIGHT
        state == BORDER_L -> ATTACH_LEFT
        vertForState(state) == VERT_TOP -> ATTACH_TOP
        else -> ATTACH_BOTTOM
    }

    /**
     * 压缩支点是否取格子的**下**缘（否则取上缘）。
     *
     * "贴顶"是唯一需要翻面的一种 —— 它贴的是屏幕上方，压缩时屏幕上方那条边不动。
     * 贴左右边框虽然贴的是竖边，但素材躺倒后画布 y 轴指向那条竖边，支点仍在下缘。
     */
    fun squashPivotAtCellBottom(state: Int): Boolean =
        attachEdgeOf(state) != ATTACH_TOP

    /**
     * 弹起方向在画布 y 轴上的符号：+1 = 向局部下方，-1 = 向局部上方。
     *
     * 永远是"背离吸附边、朝屏幕内侧"：贴下边往上弹、贴上边往下弹、
     * 贴左右边框时画布 y 轴指向屏幕左右，于是也是**背离那条竖边**。
     */
    fun jumpSignY(state: Int): Float =
        if (attachEdgeOf(state) == ATTACH_TOP) 1f else -1f

    /**
     * 长按松手时的**弹起高度系数**：与压缩程度成正比。
     *
     * 用户要求："长按之后松手要向上弹起，弹起高度与压缩程度成正比。"
     * 也就是压得越深、弹得越高；[depth] 为 0（没压）时不弹。
     *
     * @param depth 松手那一刻的压缩量（0..[fullDepth]）
     * @param fullDepth 压满时的压缩量（= `PetView.PRESS_MAX_SQ`，0.5）
     * @param maxFraction 压满时弹起高度占素材高度的比例
     * @return 弹起高度占素材高度的比例（0..[maxFraction]）
     */
    fun pressJumpFraction(
        depth: Float,
        fullDepth: Float,
        maxFraction: Float
    ): Float {
        if (depth <= 0f || fullDepth <= 0f) return 0f
        return maxFraction * (depth / fullDepth).coerceIn(0f, 1f)
    }

    /**
     * 落地回弹曲线：`0 -> 压扁 -> 轻微拉伸 -> 0`（长按弹起落地那一下）。
     *
     * 用户要求："长按弹起落地之后加一个回弹效果。"
     *
     * 形状分两段，在 u=0.25 处连续（两段都等于 1）：
     *   · 前 25%：快速压下去（落地冲击）—— 用 [easeInArrive]，起步最快；
     *   · 后 75%：用 [springBack] 弹回来，中途会**轻微过冲到拉伸**再落定。
     * `u = 1` 时严格为 0，所以落地回弹结束后压缩量一定是 0（不会残留变形）。
     *
     * 返回 **0..1 的系数**，乘上落地压缩深度才是实际压缩量。
     * 最低点约 -0.16（轻微拉伸），保证"弹回来"而不是"慢慢鼓回来"。
     */
    fun landSquash(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return if (t < 0.25f) {
            easeInArrive(t / 0.25f)
        } else {
            springBack((t - 0.25f) / 0.75f, 1f)
        }
    }

    /**
     * 落地回弹的压缩**深度**（占素材高度的比例）。
     *
     * 用户要求："单击时的回弹效果轻一点；长按落地时的回弹效果与按压时间成正比。"
     *
     *   · 单击（几乎没按住）：用最轻的 [tapSq]；
     *   · 长按：从 [tapSq] 起，随**按住时长**线性增长，按满（[fullMs]）时到 [maxSq]。
     *
     * 起点取 [tapSq] 而不是 0，是为了让"刚好够长按"和"点一下"衔接得上 ——
     * 否则刚过长按阈值那一下的回弹会比单击还轻，手感是断的。
     *
     * @param heldMs 按住时长（毫秒）
     * @param fullMs 压满所需时长（= `PetView.PRESS_FULL_MS`）
     */
    fun landSquashDepth(heldMs: Float, fullMs: Float, tapSq: Float, maxSq: Float): Float {
        if (fullMs <= 0f || heldMs <= 0f) return tapSq
        val t = (heldMs / fullMs).coerceIn(0f, 1f)
        return tapSq + (maxSq - tapSq) * t
    }

    /**
     * 静止吸附一段时间后的**呼吸晃动**波形（纯函数，便于单测）。
     *
     * 用户要求："当人物处于吸附 0.5s 后，给人物增加微微的呼吸晃动效果，
     * 原则是素材吸附边不得离开手机被吸附边。"
     *
     * 约定：
     *   · `elapsed < delayMs` 时严格返回 **0** —— 静止不满 delay 不呼吸，
     *     而且开始的那一刻正好在 0 上，所以**不会跳变**；
     *   · 之后是标准正弦，取值 `[-1, 1]`；
     *   · "不离开吸附边"由**绘制时的支点**保证（绕吸附边缩放、沿吸附边平移，
     *     见 `PetView.squashPivotLocalY`）—— 波形本身只负责"微微"。
     *
     * @param elapsedMs 距上一次"有动画/被按住"结束的毫秒数
     * @param delayMs 静止多久之后开始呼吸（= `PetView.BREATH_DELAY_MS`，500ms）
     * @param periodMs 呼吸周期（用户可调，见 `Prefs.breathPeriodMs`）
     * @return -1..1；未到 delay 或参数非法时为 0
     */
    fun breathWave(elapsedMs: Float, delayMs: Float, periodMs: Float): Float {
        if (periodMs <= 0f) return 0f
        if (elapsedMs <= delayMs) return 0f
        val u = (elapsedMs - delayMs) / periodMs
        return kotlin.math.sin((2.0 * Math.PI * u).toFloat())
    }

    /**
     * 带回摆幅度调节的弹簧回摆曲线。
     *
     * 形状与 [springBack] 完全相同（起点严格 1、终点严格 0），只是把**负半周**
     * （反向冲过落点那一段）按 [gain] 缩放 —— 这样"弹出高度"（过冲距离）与
     * "回弹幅度"（反向冲过的比例）就能各自独立调。
     *
     * `gain = 1` 时与 [springBack] 逐值相同（用户要求"回弹幅度以现在为 1.0 倍"）；
     * `gain` 会被夹在 0..12，避免负半周被放到失控。
     *
     * 负半周与零点的衔接是连续的（两边都过 0），只是斜率会拐一下 ——
     * 那正是"弹回来"该有的顿挫感。
     */
    fun springBackGained(u: Float, gain: Float): Float {
        val k = springBack(u)
        if (k >= 0f) return k
        return k * gain.coerceIn(0f, 12f)
    }

    /**
     * 收起时往哪条边滑出去：**取最近的那条边**（纯函数，便于单测）。
     *
     * 关键点：这个函数**永远返回一个方向**，没有"都不贴边就返回某种兜底"的分支。
     * 因为兜底方向一旦写死（原来是"向下"），只要状态与位置有细微错配就会露馅 ——
     * 典型场景：旋转动画期间 `frozen=true`，`trackW/trackH` 还是**旧形态**的值，
     * 横竖互换会让足迹宽高差 42px、右边缘差 21px，四条边一条都对不上，
     * 于是贴右边框的人物会**竖着往下滑出屏幕**
     * （用户反馈："跑到右下角跑出屏幕了"）。
     *
     * 平手时按 上 -> 下 -> 左 -> 右 的顺序，所以贴右下角仍然往下收（与旧行为一致）。
     *
     * @return `[dirX, dirY]`，分量取 -1 / 0 / 1
     */
    fun exitDir(
        left: Float, top: Float, right: Float, bottom: Float,
        screenW: Float, screenH: Float
    ): FloatArray {
        val dTop = top
        val dBottom = screenH - bottom
        val dLeft = left
        val dRight = screenW - right
        val nearest = minOf(dTop, dBottom, dLeft, dRight)
        return when (nearest) {
            dTop -> floatArrayOf(0f, -1f)
            dBottom -> floatArrayOf(0f, 1f)
            dLeft -> floatArrayOf(-1f, 0f)
            else -> floatArrayOf(1f, 0f)
        }
    }

    // ---------------------------------------------------------------- 通知标题

    /**
     * 通知标题的解析结果。
     *
     * @param text  最终要显示的标题文字
     * @param hlFrom 角色名高亮的起始下标；-1 表示不高亮（模板里没写 {name}）
     * @param hlTo   高亮结束下标（不含）
     */
    data class Title(val text: String, val hlFrom: Int, val hlTo: Int)

    /**
     * 把用户自定义的标题模板解析成最终标题。
     *
     * 规则（都在 [com.dsh.deskpet.PetService.notificationTitle] 的调用处生效）：
     *   · 模板为空     -> 用 [default]（默认文案）
     *   · 模板含 {name} -> 全部替换成 [name]，并以**第一处**的位置作为高亮区间
     *   · 模板不含 {name} -> 标题固定不变，不高亮
     *   · [name] 为空   -> 只把 {name} 去掉，不高亮
     *
     * ⚠️ 高亮区间**不能**用 `text.indexOf(name)` 反推：角色名可能恰好是模板里
     *    已有的子串（例如名字叫"一里"、模板里还有别的"一里"），
     *    也可能 {name} 写了两次而只有第一处该高亮。所以统一按**模板里
     *    占位符的下标**换算。
     */
    fun buildTitle(raw: String?, name: String?, default: String): Title {
        val custom = raw?.trim().orEmpty()
        val template = if (custom.isEmpty()) default else custom
        val n = name.orEmpty()
        val at = template.indexOf(NAME_TOKEN)
        val text = template.replace(NAME_TOKEN, n)
        // 占位符之前的字符数不变，所以第一处占位符的下标就是高亮的起点
        val hl = if (at >= 0 && n.isNotEmpty()) at else -1
        return Title(text, hl, if (hl >= 0) hl + n.length else -1)
    }

    /** 标题模板里的角色名占位符 */
    const val NAME_TOKEN = "{name}"

    /**
     * 通知标题与正文的"留空回填"结果。
     *
     * @param title 回填后的标题（**保留 {name} 占位符**，这样换角色时还会跟着变）
     * @param body  回填后的正文（**已解析成实际文字**，例如「后藤一里 正在陪伴你」）
     */
    data class NotifyTexts(val title: String, val body: String)

    /**
     * 通知文案的留空回填规则（用户要求）：
     *
     *   · 标题留空 -> 用 [default]（默认模板）。
     *     注意回填的是**模板**而不是解析后的文字 —— 保留 {name} 换角色才会跟着变，
     *     回填实际文字等于把角色名写死了。
     *   · 正文留空 -> 用**当前标题解析后的文字**。这样通知里正文不会是一片空白，
     *     且用户改标题时正文会自动跟上。
     *
     * 判定用 isBlank：只敲了空格也算留空（用户视角那就是没填）。
     */
    fun fillNotifyTexts(
        rawTitle: String?,
        rawBody: String?,
        name: String?,
        default: String
    ): NotifyTexts {
        val title = if (rawTitle.isNullOrBlank()) default else rawTitle
        val body = if (rawBody.isNullOrBlank()) {
            buildTitle(title, name, default).text
        } else {
            rawBody
        }
        return NotifyTexts(title, body)
    }

    /**
     * 把存下来的标题模板解析成**要显示在输入框里的文字**。
     *
     * 留空 -> 用 [default]；然后一律把 {name} 换成 [name]。
     * 这样输入框里看到的永远是实际名字，用户不需要懂占位符语法。
     */
    fun titleForDisplay(raw: String?, name: String?, default: String): String =
        buildTitle(raw, name, default).text

    /**
     * 把输入框里的文字**反向**转成要存储的模板：当前角色名 -> {name}。
     *
     * 为什么要反向替换：显示的是实际名字，直接存下来就等于把角色名写死了，
     * 换角色后标题不会跟着变（这正是方案 2 原本的 bug）。
     * 转成模板后，无论用户是手敲名字还是（理论上）写了 {name}，存储都统一。
     *
     * ⚠️ 名字为空时原样返回 —— 否则 `replace("", token)` 会在每个字符之间插东西。
     */
    fun titleForStore(displayed: String?, name: String?): String {
        val t = displayed.orEmpty()
        val n = name.orEmpty()
        return if (n.isEmpty()) t else t.replace(n, NAME_TOKEN)
    }

    /**
     * 把**历史遗留的字面名字**转成 {name} 模板（一次性迁移，反复调用安全）。
     *
     * 背景：本功能的上一版把标题解析成名字后**存了下来**，于是存储里是
     * `喜多郁代 正在陪伴你` 这种字面值。它不含 {name}，换角色时不会跟着变 ——
     * 正是要修掉的那个 bug。
     *
     * 转换条件（够严，避免误伤用户自己写的内容）：
     *   · 非空、且不含 {name}（已经是模板就不用动）
     *   · **含当前角色名** —— 含才换。用户故意写「桌宠在线」这类与角色无关的
     *     标题时，绝不能把其中的字换成占位符。
     *
     * 幂等：转完含 {name}，下次调用直接短路返回。
     */
    fun migrateTitleToTemplate(stored: String?, name: String?): String? {
        val s = stored ?: return null
        val n = name.orEmpty()
        if (s.isEmpty() || n.isEmpty()) return null
        if (s.contains(NAME_TOKEN)) return null
        if (!s.contains(n)) return null
        return s.replace(n, NAME_TOKEN)
    }

    // ---------------------------------------------------------------- 呼吸

    /**
     * 「呼吸速度倍率」-> 呼吸周期（ms）。
     *
     * ⚠️ 用**除法**：用户调的是"速度"，2 倍速度就该"一个周期只用一半时间"。
     *    写成乘法的话，滑条往右拉会变慢 —— 与"速度"的直觉相反。
     *
     * 速度只作用于周期，**不影响幅度**（幅度另见 `Prefs.breathSq`）。
     * 用户明确："呼吸幅度倍率则只决定呼吸动画的幅度"。
     *
     * @param speed 速度倍率（Prefs 会把输入夹到 0.1~10）
     * @param basePeriodMs 基准周期（= `Prefs.BREATH_PERIOD_BASE_MS`，2600ms）
     * @return 周期（ms）；speed <= 0 时返回基准值（防御，避免除零）
     */
    fun breathPeriod(speed: Float, basePeriodMs: Float): Float =
        if (speed <= 0f) basePeriodMs else basePeriodMs / speed
}
