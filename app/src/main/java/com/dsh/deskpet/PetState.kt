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

    // ---------------------------------------------------------------- 分区（最新规则）

    /**
     * A 区：右下角 356×356 的方块 —— 用「右下角」素材吸附到角落。
     */
    const val CORNER_ZONE_PX = 356f

    /**
     * D 区外沿 444px。D 是 A/B/C 之外的那条 L 形带：
     *   356 < db <= 444  或  356 < dr <= 444
     * D 区内有模糊。
     */
    const val BLUR_ZONE_PX = 444f

    /** B / C 区的边宽 200px */
    const val EDGE_ZONE_PX = 200f

    /** 模糊渐强的起点 */
    const val FADE_INNER_PX = 240f

    /**
     * 模糊渐弱的终点。
     *
     * 实测调参（扫描 0..700 看相邻 1px 的最大跳变）：
     *   460 -> 跳变 0.093（太陡，看得出硬边）
     *   480 -> 跳变 0.042（够柔，且 E 区残留很快消失）
     *   500 -> 跳变 0.027，但 E 区残留偏多
     * 取 480。D 区外沿 444 到 480 之间衰减，所以 E 区开头会有一点余雾，
     * 这正是"D 与其他区域之间渐变"想要的效果。
     */
    const val FADE_OUTER_PX = 480f

    const val ZONE_A = 0
    const val ZONE_B = 1
    const val ZONE_C = 2
    const val ZONE_D = 3
    const val ZONE_E = 4

    fun isCorner(state: Int) = state == CORNER_BR || state == CORNER_BL

    fun isBorder(state: Int) = state == BORDER_R || state == BORDER_L

    fun isLeft(state: Int) = state == CORNER_BL || state == BORDER_L

    /** 该状态用哪套素材：角落素材 or 边框素材 */
    fun usesCornerArt(state: Int) = isCorner(state)

    /** 贴左边时素材需要水平镜像 */
    fun needsMirror(state: Int) = isLeft(state)

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
    fun zoneOf(db: Float, dr: Float): Int {
        if (db <= CORNER_ZONE_PX && dr <= CORNER_ZONE_PX) return ZONE_A
        if (db <= EDGE_ZONE_PX) return ZONE_C
        if (dr <= EDGE_ZONE_PX) return ZONE_B
        if (db <= BLUR_ZONE_PX || dr <= BLUR_ZONE_PX) return ZONE_D
        return ZONE_E
    }

    /** D 区（那条 L 形带）内才有模糊 */
    fun hasBlur(db: Float, dr: Float): Boolean = zoneOf(db, dr) == ZONE_D

    /** 离底边近就吸底边，否则吸右边（D / E 区松手用） */
    fun nearerEdgeIsBottom(db: Float, dr: Float): Boolean = db <= dr

    /**
     * 吸附到某个状态时，素材该转多少度。
     * 传给 [android.graphics.Canvas.rotate]，**正数 = 屏幕视觉顺时针**。
     *
     *   - 角落状态（A 区，「右下角」素材） -> 0，人物正立
     *   - C 区垂直吸到底边 -> 用「右边框」**原始素材**，0
     *   - B 区水平吸到右边 -> 用「右边框」**逆时针 90°** -> -90
     *
     * 这个符号是实测定的，不要再靠推理改。
     */
    fun rotationForSnap(state: Int): Float = when (state) {
        BORDER_R -> -90f
        BORDER_L -> 90f
        else -> 0f
    }

    /**
     * 松手时决定吸附到哪个状态。
     *
     * @param db 桌宠底边离屏幕底部
     * @param dr 桌宠右边离屏幕右侧
     * @param leftHalf 是否在左半屏
     */
    fun snapTarget(db: Float, dr: Float, leftHalf: Boolean): Int {
        return when (zoneOf(db, dr)) {
            // A 区：吸附到角落
            ZONE_A -> if (leftHalf) CORNER_BL else CORNER_BR
            // C 区：垂直吸附到屏幕底部
            ZONE_C -> if (leftHalf) CORNER_BL else CORNER_BR
            // B 区：水平吸附到侧边
            ZONE_B -> if (leftHalf) BORDER_L else BORDER_R
            // D / E 区：离哪边近吸哪边
            else -> {
                if (nearerEdgeIsBottom(db, dr)) {
                    if (leftHalf) CORNER_BL else CORNER_BR
                } else {
                    if (leftHalf) BORDER_L else BORDER_R
                }
            }
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
    fun blurFor(db: Float, dr: Float): Float {
        val d = minOf(db, dr)

        // D 区本体：恒定满强度
        if (d > CORNER_ZONE_PX && d <= BLUR_ZONE_PX) return 1f

        // 从贴边处平滑升到 D 内沿
        if (d <= CORNER_ZONE_PX) {
            if (d <= FADE_INNER_PX) return 0f
            return smooth((d - FADE_INNER_PX) / (CORNER_ZONE_PX - FADE_INNER_PX))
        }

        // 从 D 外沿平滑衰减回清晰
        if (d >= FADE_OUTER_PX) return 0f
        return smooth(1f - (d - BLUR_ZONE_PX) / (FADE_OUTER_PX - BLUR_ZONE_PX))
    }

    /** 平滑插值（smoothstep），两端不会出现折线感 */
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 是否落在 D 区（L 形带） */
    fun isInD(db: Float, dr: Float): Boolean = zoneOf(db, dr) == ZONE_D

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
     *   A 区 -> 右下角素材（0）
     *   其余 -> 右边框素材（1）；其中只有 B 区会旋转
     */
    fun morphFor(db: Float, dr: Float): Float =
        if (zoneOf(db, dr) == ZONE_A) 0f else 1f

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
     * 拖动过程中用哪套素材（配合跨半屏的翻转动画）。
     * 角落区用角落素材，离开角落就用边框素材。
     */
    fun artStateFor(db: Float, dr: Float, leftHalf: Boolean): Int =
        if (zoneOf(db, dr) == ZONE_A) {
            if (leftHalf) CORNER_BL else CORNER_BR
        } else {
            if (leftHalf) BORDER_L else BORDER_R
        }

    /**
     * 拖动过程中素材的旋转角。
     *
     *   - A 区：不转（角落素材正立）
     *   - B 区：边框**逆时针90°**
     *   - C / D / E：边框**原始素材**，不转
     *
     * 用户补充明确："E区内以「右边框」原素材（不旋转）呈现"。
     */
    fun dragRotationFor(db: Float, dr: Float, leftHalf: Boolean): Float {
        if (zoneOf(db, dr) != ZONE_B) return 0f
        return if (leftHalf) rotationForSnap(BORDER_L) else rotationForSnap(BORDER_R)
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
