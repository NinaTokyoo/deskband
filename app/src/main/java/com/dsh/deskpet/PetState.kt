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
 * 没有 D 区，**没有模糊**。稳定存在的朝向只有「正立」与「旋转 90°」两种；
 * 上半屏的形态则是下半屏的**上下镜像**（素材上下翻转，见 [flipVFor]）。
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
     * [BORDER_L_UPRIGHT] 完全对称 —— 上半屏的"正立"就是下半屏倒过来挂。
     */
    const val EDGE_TOP_R = 9
    const val EDGE_TOP_L = 10

    // ---------------------------------------------------------------- 垂直基准

    /**
     * **无纵向基准**：只贴侧边，纵向自由（旋转躺倒的侧边形态）。
     *
     * 也用作 [vertOf] 的 `prev` 默认值，表示"没有上一次的基准，直接比大小"。
     */
    const val VERT_NONE = 0

    /** 下边组：以屏幕**下沿**为垂直基准 */
    const val VERT_BOTTOM = 1

    /** 上边组：以屏幕**上沿**为垂直基准（素材上下翻转、向上收、从上方呼入） */
    const val VERT_TOP = 2

    /**
     * 中线滞回带（px）：跨越屏幕中线后要**多走这么远**才换组。
     *
     * 不设它的话，手指停在中线附近抖动会让素材在"正立 / 倒挂"之间高频翻转。
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
     * **选垂直基准**：离上沿更近就是上边组，否则是下边组。
     *
     * 带**中线滞回**：`prev` 给出的上一次基准在中线附近 [VERT_HYSTERESIS_PX]
     * 之内保持不变，避免手指抖动时素材在正立/倒挂之间反复翻。
     *
     * @param prev 上一次的基准；[VERT_NONE] 表示没有上一次，直接比大小
     * @return [VERT_TOP] 或 [VERT_BOTTOM]（**从不返回 VERT_NONE**）
     */
    fun vertOf(y: Float, h: Float, screenH: Float, prev: Int = VERT_NONE): Int {
        val t = topDist(y, h, screenH)
        val b = bottomDist(y, h, screenH)
        return when (prev) {
            // 已经在上边组：要"明显变成下沿更近"才换组
            VERT_TOP -> if (t > b + VERT_HYSTERESIS_PX) VERT_BOTTOM else VERT_TOP
            // 已经在下边组：要"明显变成上沿更近"才换组
            VERT_BOTTOM -> if (b > t + VERT_HYSTERESIS_PX) VERT_TOP else VERT_BOTTOM
            else -> if (t <= b) VERT_TOP else VERT_BOTTOM
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
        // A（角落）不涉及这个抖动，直接算；C 已并入 E，不再单判
        if (v != VERT_NONE && dv <= currentAH && dr <= currentAW) return ZONE_A

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
}
