package com.dsh.deskpet

/**
 * 桌宠的分区、吸附与朝向规则（纯逻辑，不依赖 Android，可单元测试）。
 *
 * ============================ 分区（A / B / C / E） ============================
 *
 * 判定基准是桌宠的**底边**和**本侧边缘**：
 *   db = 底边离屏幕底部的距离
 *   dr = 离本侧吸附边的距离（左半屏量左边，右半屏量右边）
 *
 *   A 区：db <= aH 且 dr <= aW   —— 角落。用「右下角 / 左下角」素材，不旋转
 *   C 区：排除 A 后 db <= aH      —— 下侧带。用「正立素材」，不旋转
 *   B 区：排除 A 后 dr <= aW      —— 侧边带。用「正立素材」，不旋转
 *   E 区：其余                    —— 用「正立素材」；**右侧越界时旋转 90°**
 *
 * **A 区尺寸用户可调**（滑块 / 数字输入），B 与 C 跟着一起变：
 *   B 的横向起点 = A 的横向尺寸（B 左侧对齐 A 左侧）
 *   C 的纵向起点 = A 的纵向尺寸（C 上侧对齐 A 上侧）
 *
 * 没有 D 区，**没有模糊**。稳定存在的朝向只有「正立」与「旋转 90°」两种。
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
     * 吸附规则与下方角落一致（贴侧 + 贴垂直边），区别只在：
     *   · 垂直方向贴上沿（见 [snapPosition] 的 nearTop）
     *   · 素材**上下翻转**（见 [isTop]）
     *   · 收起时向**上**滑出（见 PetView.exitVector）
     */
    const val CORNER_TR = 7
    const val CORNER_TL = 8

    /** 是否属于"上边组"（上方角落）：决定素材上下翻转与收起方向 */
    fun isTop(state: Int): Boolean = state == CORNER_TR || state == CORNER_TL

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

    fun isCorner(state: Int) = state == CORNER_BR || state == CORNER_BL || state == CORNER_TR || state == CORNER_TL

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
            CORNER_BR, CORNER_BL, CORNER_TR, CORNER_TL -> true
        else -> false
    }

    fun isLeft(state: Int): Boolean = when (state) {
        CORNER_TL,
        CORNER_BL, BORDER_L, BORDER_L_UPRIGHT -> true
        else -> false
    }

    /** 是否用「角落」那套素材 */
    fun usesCornerArt(state: Int) = state == CORNER_BR || state == CORNER_BL || state == CORNER_TR || state == CORNER_TL

    /** 是否用「正立素材」那套图 */
    fun usesBorderArt(state: Int) = !usesCornerArt(state)

    fun needsMirror(state: Int) = isLeft(state)

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
     * 左右半屏用同一套逻辑，只是 dr 的基准边不同。
     */
    fun zoneOf(db: Float, dr: Float, leftHalf: Boolean): Int {
        if (db <= currentAH && dr <= currentAW) return ZONE_A
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
     * @param prev 上一次的区号，用于决定用哪个门槛
     */
    /**
     * 分区判定（带滞回）。
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
     * @param bandPx 中心点到侧边的门槛，调用方传 currentAW + trackW/2
     */
    fun zoneWithHysteresis(
        db: Float, dr: Float, leftHalf: Boolean, prev: Int,
        centerDist: Float = dr,
        bandPx: Float = currentAW
    ): Int {
        // A（角落）不涉及这个抖动，直接算；C 已并入 E，不再单判
        if (db <= currentAH && dr <= currentAW) return ZONE_A

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
    fun hasBlur(db: Float, dr: Float, leftHalf: Boolean): Boolean = false

    fun nearerEdgeIsBottom(db: Float, dr: Float): Boolean = db <= dr

    /**
     * 松手时贴哪条边：[0] = 贴底边，[1] = 贴侧边。
     *
     * ⚠️ 必须传入**已经判定好的 zone**，不要在这里重新 zoneOf。
     * 因为分区判定用的是"中心点是否越过 B/E 分界线"，而 zoneOf 用的是
     * 边缘距离，两者在素材较大时会得出不同结论 —— 结果就是判成了 B（该贴侧边）
     * 却按 E 去贴最近边，贴到底边上了（用户反馈的"侧边不吸附"）。
     */
    fun snapEdgesForZone(zone: Int): IntArray = when (zone) {
        ZONE_A -> intArrayOf(1, 1)
        ZONE_C -> intArrayOf(1, 0)
        ZONE_B -> intArrayOf(0, 1)
        else -> intArrayOf(0, 0)   // E：由调用方按"更近的一边"决定
    }

    /**
     * 该用哪个状态（同时决定素材与旋转）。
     *
     *   A 区 -> 角落素材，正立
     *   B 区 -> 正立素材，**贴边时旋转 90°**（恢复左右边框旋转吸附）
     *   C 区 -> 正立素材，正立（贴底，头朝上）
     *   E 区 -> 正立素材；是否旋转由 [rotationForE] 看右侧有没有越界
     */
    fun stateForZone(zone: Int, leftHalf: Boolean, nearTop: Boolean = false): Int = when (zone) {
        ZONE_A -> if (nearTop) { if (leftHalf) CORNER_TL else CORNER_TR }
            else { if (leftHalf) CORNER_BL else CORNER_BR }
        ZONE_B -> if (leftHalf) BORDER_L else BORDER_R
        ZONE_C -> if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
        else -> if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
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

    /** 离底边的距离（两侧相同） */
    fun bottomDist(y: Float, h: Float, screenH: Float): Float =
        (screenH - (y + h)).coerceAtLeast(0f)

    /** 离**上沿**的距离（与 [bottomDist] 完全对称） */
    fun topDist(y: Float, h: Float, screenH: Float): Float = y.coerceAtLeast(0f)

    /**
     * 到**较近的那条水平边**的距离（上沿或下沿取小）。
     *
     * 分区判定统一用这个当"垂直轴距离"，于是上边组与下边组
     * 共用同一套 A/B/E 判定，天然对称。
     */
    fun vertDist(y: Float, h: Float, screenH: Float): Float =
        minOf(topDist(y, h, screenH), bottomDist(y, h, screenH))

    /** 是否离上沿更近（决定贴上还是贴下、素材是否上下翻转、收起方向） */
    fun nearerEdgeIsTop(y: Float, h: Float, screenH: Float): Boolean =
        topDist(y, h, screenH) <= bottomDist(y, h, screenH)

    /**
     * 离**本侧吸附边**的距离 —— 左半屏量左边，右半屏量右边。
     *
     * 这个镜像很关键：早先在左半屏仍量"离右边"，左下角的宠物会被算成
     * 离侧边很远，判进 C 区，永远吸不到左下角。
     */
    fun sideDist(x: Float, w: Float, screenW: Float, leftHalf: Boolean): Float =
        if (leftHalf) x.coerceAtLeast(0f)
        else (screenW - (x + w)).coerceAtLeast(0f)

    // ---------------------------------------------------------------- 吸附

    /**
     * 计算吸附后的格子位置（贴边）。
     *
     * @param trackW/trackH 吸附后素材在屏幕上的**足迹**（旋转后宽高可能互换）
     * @return [x, y]
     */
    fun snapPosition(
        x: Float, y: Float,
        trackW: Float, trackH: Float,
        db: Float, dr: Float,
        screenW: Float, screenH: Float,
        leftHalf: Boolean,
        zone: Int = -1,
        /** 垂直方向该贴上沿(true)还是下沿(false)。与底边完全对称。 */
        nearTop: Boolean = false
    ): FloatArray {
        // 分区优先用调用方判定好的 zone（与状态判定保持同一套规则）；
        // 没传时退化为按边缘距离现算（仅旧调用方会走到这里）。
        val z = if (zone >= 0) zone else zoneOf(db, dr, leftHalf)
        val edges = if (z == ZONE_E) {
            // E 区：贴更近的那条边
            if (nearerEdgeIsBottom(db, dr)) intArrayOf(1, 0) else intArrayOf(0, 1)
        } else {
            snapEdgesForZone(z)
        }
        var nx = x
        var ny = y
        if (edges[0] == 1) ny = if (nearTop) 0f else screenH - trackH
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
