package com.dsh.deskpet

import kotlin.math.abs

/**
 * 吸附状态机（纯逻辑，不依赖 Android，可单元测试）。
 *
 * 四种吸附状态，**没有悬浮状态**：
 *   CORNER_BR 右下角 / CORNER_BL 左下角 / BORDER_R 右边框 / BORDER_L 左边框
 *
 * ============================ 分区规则（以此为准） ============================
 *
 * 右半屏按桌宠的**底边**和**右边**分成 5 个区（左半屏完全镜像对称）：
 *
 *   A 区：距底 <=356 且 距右 <=356   -> 用「右下角」素材吸附到右下角
 *   C 区：排除 A 后，距底 <=200      -> 垂直吸附到屏幕底部，用「右边框」**原始素材**
 *   B 区：排除 A 后，距右 <=200      -> 水平吸附到屏幕右侧，用「右边框」**逆时针 90°**
 *   D 区：排除 A/B/C 后，距底 <=444 或 距右 <=444（L 形带）-> 有模糊；
 *                                    松手时离哪边近吸哪边，再按 C / B 的规则
 *   E 区：其余                       -> 同 D 的吸附方式，无模糊
 *
 * 两处容易写错的地方：
 *   1. 判定基准是**底边和右边**，不是中心点；
 *   2. 200 那条边必须**排除 A**。因为 200 < 356，若先判 200，
 *      A 区里 200..356 的环带会被误判进 C，C 就变成 356 宽了。
 *
 * 跨左右半屏：越过屏幕中轴线时左右翻转（简易动画）。
 * ===========================================================================
 */
object PetState {

    const val CORNER_BR = 0
    const val CORNER_BL = 1
    const val BORDER_R = 2
    const val BORDER_L = 3

    /**
     * 贴底边时用的"边框素材但正立"变体。
     *
     * 用户明确要求：**C 区（贴底）用「右边框」的原素材、人物头部向上**。
     * 所以它和 [BORDER_R] 的区别只在旋转 —— 素材是同一张边框图。
     */
    const val BORDER_R_UPRIGHT = 5
    const val BORDER_L_UPRIGHT = 6

    /** 是否用「边框」那套素材 */
    fun usesBorderArt(state: Int) = state == BORDER_R || state == BORDER_L ||
        state == BORDER_R_UPRIGHT || state == BORDER_L_UPRIGHT

    fun isUprightVariant(state: Int) =
        state == BORDER_R_UPRIGHT || state == BORDER_L_UPRIGHT

    // ---------------------------------------------------------------- 分区（最新规则）

    /**
     * A 区在**横向**（离吸附侧边的距离）上的尺寸。
     * 右侧 356，左侧 **200**（用户要求把左侧 A 从 356 收到 200）。
     */
    const val CORNER_ZONE_X_RIGHT = 356f
    const val CORNER_ZONE_X_LEFT = 200f

    /**
     * A 区在**纵向**（离底边的距离）上的尺寸。
     * D 区跟着 A 一起缩：D 内沿 = A 的纵向尺寸。
     *   右侧：A=356，D 到 444
     *   左侧：A_y=200 -> D 外沿同步缩到 288（= 444 - (356-200)）
     *
     * 结果就是左侧 A 变成 200×200 的小角，而 C 带（离底<=200）
     * 在横向上不再被 A 截断 —— 也就是用户说的"C 区同步向右延长"。
     */
    const val CORNER_ZONE_Y_RIGHT = 356f
    const val CORNER_ZONE_Y_LEFT = 200f

    /** D 区外沿：右侧 444；左侧同步缩到 288 */
    const val BLUR_ZONE_PX_RIGHT = 444f
    const val BLUR_ZONE_PX_LEFT = 288f

    /** B / C 区的边宽 200px */
    const val EDGE_ZONE_PX = 200f

    /** 兼容旧引用：右侧的 A / D 尺寸 */
    const val CORNER_ZONE_PX = CORNER_ZONE_X_RIGHT
    const val BLUR_ZONE_PX = BLUR_ZONE_PX_RIGHT

    /**
     * 一个半屏的分区几何参数。
     *
     * @param cornerX  A 区横向尺寸（离吸附侧边）
     * @param cornerY  A 区纵向尺寸（离底边），同时也是 D 的内沿
     * @param blurOut  D 区外沿
     */
    data class ZoneGeom(val cornerX: Float, val cornerY: Float, val blurOut: Float)

    fun geomFor(leftHalf: Boolean): ZoneGeom =
        if (leftHalf) {
            ZoneGeom(CORNER_ZONE_X_LEFT, CORNER_ZONE_Y_LEFT, BLUR_ZONE_PX_LEFT)
        } else {
            ZoneGeom(CORNER_ZONE_X_RIGHT, CORNER_ZONE_Y_RIGHT, BLUR_ZONE_PX_RIGHT)
        }

    /** 模糊渐强的起点 */
    const val FADE_INNER_PX = 240f

    /**
     * 模糊渐弱的终点。
     *
     * 实测调参（扫描 0..700 看相邻 1px 的最大跳变）：
     *   460 -> 跳变 0.093（太陡，看得出硬边）
     *   480 -> 跳变 0.042（够柔，且 E 区残留很快消失）
     *   500 -> 跳变 0.027，但 E 区残留偏多
     * 用户要求"极致的顺滑"，所以再放宽到 560：跳变更小。
     * 代价是 E 区靠内一段还有淡淡余雾 —— 这正是渐变该有的样子。
     */
    const val FADE_OUTER_PX = 560f

    /** 旋转的过渡带宽：dr 在这个范围内从满角度平滑衰减到 0 */
    const val EDGE_FADE_DEG = 200f

    /** 素材混合完成的距离：到这儿已经完全用边框素材 */
    const val ART_FADE_OUTER_PX = 400f

    const val ZONE_A = 0
    const val ZONE_B = 1
    const val ZONE_C = 2
    const val ZONE_D = 3
    const val ZONE_E = 4

    fun isCorner(state: Int) = state == CORNER_BR || state == CORNER_BL

    fun isBorder(state: Int) = state == BORDER_R || state == BORDER_L

    fun isLeft(state: Int): Boolean = when (state) {
        CORNER_BL, BORDER_L, BORDER_L_UPRIGHT -> true
        else -> false
    }

    /** 该状态用哪套素材：角落素材 or 边框素材 */
    fun usesCornerArt(state: Int) = state == CORNER_BR || state == CORNER_BL

    /** 贴左边时素材需要水平镜像 */
    fun needsMirror(state: Int) = isLeft(state)

    /**
     * 吸附到某个状态时素材该转多少度（传给 Canvas.rotate，正数 = 屏幕视觉顺时针）。
     *
     *   - 角落状态：0，正立（头朝上）
     *   - BORDER_R / BORDER_L：贴侧边，逆时针 90° 躺在边上
     *   - UPRIGHT 变体（贴底边）：**0**，用边框原素材、头朝上
     *
     * 符号是实测定的，不要靠推理改。
     */
    fun rotationForSnap(state: Int): Float = when (state) {
        BORDER_R -> -90f
        BORDER_L -> 90f
        else -> 0f
    }

    /**
     * 判定桌宠落在哪个区（右半屏；左半屏完全镜像，用同样的距离）。
     *
     * 判定基准是桌宠的**底边**和**右边**：db = 底边离屏幕底部，dr = 右边离屏幕右侧。
     * 两者重合时**以底边为准** —— 所以先判 A 再判 C。
     *
     *   A = db<=356 且 dr<=356
     *   C = 排除 A、D 后 db<=200          （垂直吸底边，边框素材原样）
     *   B = 排除 A、D 后 dr<=200          （水平吸右边，边框素材逆时针90°）
     *   D = 排除 A/B/C 后 db<=444 或 dr<=444（L 形带，有模糊）
     *   E = 其余
     *
     * ⚠️ 200 那条边必须**排除 A**：因为 200 < 356，若先判 200，
     * A 区里 200..356 的环带会被误判进 C，C 就变成 356 宽了。
     */
    fun zoneOf(db: Float, dr: Float, leftHalf: Boolean): Int {
        val g = geomFor(leftHalf)
        if (db <= g.cornerY && dr <= g.cornerX) return ZONE_A
        if (db <= EDGE_ZONE_PX) return ZONE_C
        if (dr <= EDGE_ZONE_PX) return ZONE_B
        if (db <= g.blurOut || dr <= g.blurOut) return ZONE_D
        return ZONE_E
    }

    /** D 区（那条 L 形带）内才有模糊 */
    fun hasBlur(db: Float, dr: Float, leftHalf: Boolean): Boolean =
        zoneOf(db, dr, leftHalf) == ZONE_D

    /** 离底边近就吸底边，否则吸吸附侧边（D / E 区松手用） */
    fun nearerEdgeIsBottom(db: Float, dr: Float): Boolean = db <= dr

    /**
     * 松手时**贴哪条边**（不看素材，素材由 [snapCategory] 按吸附后的位置决定）。
     *
     * 单独抽出来是因为"贴到哪条边"和"用哪套素材"是两件事：
     * 关键的一条是 —— C 区贴底边后如果水平位置靠近角落，落点其实在 A 区，
     * 那时该用角落素材。所以顺序必须是：先算落点，再判素材。
     */
    fun snapEdges(db: Float, dr: Float, leftHalf: Boolean): IntArray {
        return when (zoneOf(db, dr, leftHalf)) {
            // A：贴角（底边 + 侧边）
            ZONE_A -> intArrayOf(1, 1)
            // C：只垂直贴底边
            ZONE_C -> intArrayOf(1, 0)
            // B：只水平贴侧边
            ZONE_B -> intArrayOf(0, 1)
            // D / E：贴更近的那一边
            else -> if (nearerEdgeIsBottom(db, dr)) intArrayOf(1, 0) else intArrayOf(0, 1)
        }
    }

    // ------------------------------------------------------------------ 拖拽过渡

    /**
     * 模糊强度 0f..1f —— **整张图一起糊**，高强度，用来做过渡。
     *
     * 用户说明：
     *   - "模糊是给整张图片加上高度模糊" -> 整图糊，且是**高强度**
     *   - "能在 D 与其他区域之间实行渐变的模糊吗" -> 交界处要渐变，不能有生硬分界
     *
     * 推进量取 **min(db, dr)**，即"离最近那条吸附边有多远"：
     * 贴着右边不放（dr 很小）时，不管离底部多远都还是 D 的状态，强度就该是满的。
     * （早先用 max(db,dr) 会算错：贴着右边但离底 500px 时只得 0.5。）
     *
     * 三个分量取最小值，保证每一段的行为都精确：
     *   1. 从 FADE_INNER 起平滑升到满 —— 贴边处清晰，靠近 D 时渐渐起雾
     *   2. D 区（356..444）内恒为 1 —— 满足"高强度"
     *   3. 从 D 外沿起平滑衰减回 0 —— 离开 D 后恢复清晰
     *
     * 最小值组合天然连续：356 处两个分量都等于 1，444 处也都等于 1，没有跳变。
     */
    fun blurFor(db: Float, dr: Float, leftHalf: Boolean): Float {
        val g = geomFor(leftHalf)
        val d = minOf(db, dr)

        // D 区本体：恒定满强度
        if (d > g.cornerY && d <= g.blurOut) return 1f

        // 从贴边处平滑升到 D 内沿。内沿用的是纵向尺寸（= A 的纵向高度）。
        if (d <= g.cornerY) {
            val inner = minOf(FADE_INNER_PX, g.cornerY - 1f)
            if (d <= inner) return 0f
            return smooth((d - inner) / (g.cornerY - inner))
        }

        // 从 D 外沿平滑衰减回清晰
        val outer = maxOf(FADE_OUTER_PX, g.blurOut + 1f)
        if (d >= outer) return 0f
        return smooth(1f - (d - g.blurOut) / (outer - g.blurOut))
    }

    /** 平滑插值（smoothstep），两端不会出现折线感 */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 是否落在 D 区（L 形带） */
    fun isInD(db: Float, dr: Float, leftHalf: Boolean): Boolean =
        zoneOf(db, dr, leftHalf) == ZONE_D

    /**
     * 吸附时**保留松手位置**。
     *
     * 用户说明："松手吸附是吸附到松手位置对应的像素位置"。
     * 所以吸附只决定**用哪套素材、转不转**，不再把宠物挪到屏幕边缘。
     */
    fun snapKeepsPosition(): Boolean = true

    /**
     * 素材替换进度 0f..1f：0 = 用角落素材，1 = 用边框素材。
     *
     *   A 区 -> 角落素材（0）
     *   其余 -> 边框素材（1）；其中只有 B 区会旋转
     */
    fun morphFor(db: Float, dr: Float, leftHalf: Boolean): Float =
        if (zoneOf(db, dr, leftHalf) == ZONE_A) 0f else 1f

    /** 离底边的距离（两侧相同） */
    fun bottomDist(y: Float, h: Float, screenH: Float): Float =
        (screenH - (y + h)).coerceAtLeast(0f)

    /**
     * 离"本侧吸附边"的距离 —— **左半屏量左边，右半屏量右边**。
     *
     * 这个镜像很关键：早先在左半屏仍然量"离右边"，于是左下角的宠物
     * 会被算成 dr≈915，判成 C 区只做垂直吸附，永远吸不到左下角。
     */
    fun sideDist(x: Float, w: Float, screenW: Float, leftHalf: Boolean): Float =
        if (leftHalf) x.coerceAtLeast(0f)
        else (screenW - (x + w)).coerceAtLeast(0f)

    /**
     * 吸附后该用哪个状态（供测试与外部查询；实际吸附走 [categoryForZone]）。
     *
     *   落在 A 区 -> 角落素材（正立）
     *   落在 C 区 -> 边框素材、正立（头朝上）
     *   落在 B 区 -> 边框素材、旋转 90 度
     */
    fun snapCategory(
        snappedDb: Float,
        snappedDr: Float,
        leftHalf: Boolean
    ): Int = categoryForZone(zoneOf(snappedDb, snappedDr, leftHalf), leftHalf)

    /**
     * 计算吸附后的格子位置。
     *
     * 用户规则（左半屏完全镜像）：
     *   A   -> 贴角：底边和右边都贴
     *   C   -> **只垂直吸附到底边**，保留松手时的水平位置 x
     *   B   -> **只水平吸附到侧边**，保留松手时的垂直位置 y
     *   D/E -> 离哪边近就贴哪边，同样保留另一轴
     *
     * @param leftHalf 是否在左半屏（决定贴左边还是右边）
     * @return [x, y, category]，category 供上层决定素材与旋转
     */
    fun snapPosition(
        x: Float, y: Float,
        w: Float, h: Float,
        db: Float, dr: Float,
        screenW: Float, screenH: Float,
        leftHalf: Boolean
    ): FloatArray {
        // 先决定"贴哪条边"
        val edges = snapEdges(db, dr, leftHalf)
        val snapBottom = edges[0] == 1
        val snapSide = edges[1] == 1

        var nx = x
        var ny = y
        if (snapBottom) ny = screenH - h          // 贴底边
        if (snapSide) {
            nx = if (leftHalf) 0f else screenW - w // 贴左边 / 右边
        }

        // 状态按**松手时所在的区**决定（不是按落点）。
        // 用户明确："在 C 区向下吸附的时候素材是人物头部向上的整立素材" ——
        // 即使贴底后落点恰好进了 A 区，C 区松手也仍然用边框素材正立。
        val cat = categoryForZone(zoneOf(db, dr, leftHalf), leftHalf, db, dr)

        return floatArrayOf(nx, ny, cat.toFloat())
    }

    /** 区域 -> 该用哪个状态（同时决定素材与旋转） */
    fun categoryForZone(
        zone: Int,
        leftHalf: Boolean,
        db: Float = 0f,
        dr: Float = 0f
    ): Int = when (zone) {
        ZONE_A -> if (leftHalf) CORNER_BL else CORNER_BR
        ZONE_B -> if (leftHalf) BORDER_L else BORDER_R
        // C 区贴底边：边框素材但**正立**（用户要求头朝上）
        ZONE_C -> if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
        // D / E：贴哪条边就按那条边的形态
        else -> {
            if (nearerEdgeIsBottom(db, dr)) {
                if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
            } else {
                if (leftHalf) BORDER_L else BORDER_R
            }
        }
    }

    /**
     * 拖动过程中"当前该用哪个状态"。
     *
     * 状态 id 同时表达**素材**和**旋转**：
     *   A 区 -> 角落素材、正立
     *   B 区 -> 边框素材、旋转 90 度
     *   C 区 -> 边框素材、**正立（头朝上）** —— 用 UPRIGHT 变体表达
     *   D/E  -> 边框素材，按更近的边决定
     */
    fun stateForDrag(db: Float, dr: Float, leftHalf: Boolean): Int {
        return when (zoneOf(db, dr, leftHalf)) {
            ZONE_A -> if (leftHalf) CORNER_BL else CORNER_BR
            ZONE_B -> if (leftHalf) BORDER_L else BORDER_R
            ZONE_C -> if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
            else -> {
                if (nearerEdgeIsBottom(db, dr)) {
                    if (leftHalf) BORDER_L_UPRIGHT else BORDER_R_UPRIGHT
                } else {
                    if (leftHalf) BORDER_L else BORDER_R
                }
            }
        }
    }

    /**
     * 把绘制格子夹在屏幕内，保证**至少有一部分身体可见**。
     *
     * 因为吸附不再自动对齐边缘，用户可以把宠物往外拖，
     * 不加这个兜底就会被拖丢（实测踩过：pos_left 变成 -121，半个身子在屏幕外）。
     *
     * @param minVisible 至少要露出多大比例的身体，默认一半
     */
    fun clampToScreen(
        x: Float, y: Float,
        w: Float, h: Float,
        screenW: Float, screenH: Float,
        minVisible: Float = 0.5f
    ): FloatArray {
        if (w <= 0f || h <= 0f) return floatArrayOf(x, y)
        val f = minVisible.coerceIn(0f, 1f)
        val keepX = w * f
        val keepY = h * f
        return floatArrayOf(
            x.coerceIn(-w + keepX, screenW - keepX),
            y.coerceIn(-h + keepY, screenH - keepY)
        )
    }

    /**
     * 计算素材画到屏幕上的格子尺寸。
     *
     * 关键：**永远按素材自身的正立比例等比缩放**，不因为旋转而互换宽高。
     * 用户明确要求"素材无论如何旋转比例不要变"。
     * 旋转由绘制时绕格子中心 [android.graphics.Canvas.rotate] 完成，只改朝向不改比例。
     *
     * @param targetH 希望画到多高（屏幕高 × 大小比例）
     */
    fun scaledSize(natW: Int, natH: Int, targetH: Float): IntArray {
        if (natW <= 0 || natH <= 0) return intArrayOf(1, 1)
        val scale = targetH / natH
        return intArrayOf(
            Math.round(natW * scale).coerceAtLeast(1),
            Math.round(natH * scale).coerceAtLeast(1)
        )
    }

    /**
     * 拖动过程中素材的旋转角 —— **连续场，不是分区硬切**。
     *
     * 用户要求："要极致的顺滑！无论是模糊还是旋转"。
     * 所以这里不用 `zoneOf()` 做离散判断，而是让角度随位置平滑变化：
     *
     *   - 只可能在**靠近右侧**时才转（dr 小），垂直位置不影响角度
     *   - dr 从 356 往外走时，在 [EDGE_FADE_DEG] 度内平滑地从 90° 转到 0°
     *   - db 越小（越靠底）角度越小 -> 底部那条带（C 区）自然保持不转
     *
     * 于是：
     *   B 区（贴右、离底远）-> 90°（逆时针90°，即 -90）
     *   C 区（贴底）        -> 0°（原素材）
     *   E 区                -> 0°
     *   区与区之间是渐变，没有跳变
     *
     * 返回值单位是度，**正数 = 屏幕视觉顺时针**，直接喂给 Canvas.rotate。
     */
    fun rotationForDrag(db: Float, dr: Float, leftHalf: Boolean): Float {
        // 用本侧几何：左侧 A 横向是 200，D 内沿也同步缩小
        val g = geomFor(leftHalf)
        val t = smooth((g.cornerX - dr) / EDGE_FADE_DEG) *
            smooth((db - g.cornerY) / EDGE_FADE_DEG)
        if (t <= 0.0001f) return 0f
        val full = if (leftHalf) rotationForSnap(BORDER_L) else rotationForSnap(BORDER_R)
        return full * t
    }

    /**
     * 素材混合：0 = 全用角落素材，1 = 全用边框素材。**连续**，不是 0/1 硬切。
     *
     * 从贴角处平滑过渡到 ART_FADE_OUTER_PX，
     * 并且这段正好落在模糊升起的区间里，所以换图被糊住、看不出来。
     */
    fun artBlendFor(db: Float, dr: Float, leftHalf: Boolean): Float {
        val g = geomFor(leftHalf)
        val d = minOf(db, dr)
        val inner = minOf(FADE_INNER_PX, g.cornerY - 1f)
        return smooth((d - inner) / (ART_FADE_OUTER_PX - inner))
    }

    /**
     * 拖动过程中根据当前位置推算出「显示用的临时状态」。
     * 跨过半屏时用来立刻切到另一边素材，配合翻转动画。
     */
    fun transientState(draggedCenterX: Float, screenW: Float, anchorState: Int): Int {
        val left = draggedCenterX < screenW / 2f
        return if (isCorner(anchorState)) {
            if (left) CORNER_BL else CORNER_BR
        } else {
            if (left) BORDER_L else BORDER_R
        }
    }
}
