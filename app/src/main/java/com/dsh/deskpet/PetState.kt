package com.dsh.deskpet

import kotlin.math.abs

/**
 * 吸附状态机（纯逻辑，不依赖 Android，可单元测试）。
 *
 * 四种吸附状态，**没有悬浮状态**：
 *   CORNER_BR 右下角 / CORNER_BL 左下角 / BORDER_R 右边框 / BORDER_L 左边框
 *
 * 素材朝向规则（用户定义）：
 *   - 非吸附（角落状态）：人物头部朝上，素材原样
 *   - 吸附（边框状态）：人物头部指向屏幕内部
 *     · 贴右边 -> 头朝左（内侧）
 *     · 贴左边 -> 头朝右（内侧）
 *
 * 拖动过渡（阈值是**屏幕绝对像素**，用户明确指定）：
 *   - 角落 -> 边框：素材底边离屏幕底部 > 120px 开始模糊，
 *                   >= 200px 模糊结束、换成边框素材
 *   - 边框 -> 角落：素材右边离屏幕右侧 > 80px 触发旋转摆正（逆过程）
 *   - 左右半屏：跨过屏幕中轴线时左右翻转（简易动画）
 */
object PetState {

    const val CORNER_BR = 0
    const val CORNER_BL = 1
    const val BORDER_R = 2
    const val BORDER_L = 3

    /** 从角落拖出：开始模糊的距离（素材底边离屏幕底部） */
    const val CORNER_BLUR_START_PX = 120f

    /** 从角落拖出：完成变身边框素材的距离 */
    const val CORNER_TO_BORDER_PX = 200f

    /** 从边框拖出：触发旋转摆正的距离（素材右边离屏幕右侧） */
    const val BORDER_DETACH_PX = 80f

    /**
     * 吸附在右侧时，右边框素材要旋转的角度。
     *
     * 用户要求：右边框素材**逆时针旋转 90°**。
     * 约定：本值是传给 [android.graphics.Canvas.rotate] 的角度，
     * **正数 = 屏幕视觉上的顺时针**。
     *
     * 这个符号是**实测定下来的**，不要再靠推理改：
     * 用 -90 时真机截图里脸落在格子 x=0.75（朝屏幕外侧），是错的；
     * 改成 +90 后脸落在 x=0.25（朝屏幕内侧），符合"头指向屏幕内部"。
     */
    const val RIGHT_BORDER_ROTATION_DEG = 90f

    /** 左侧是镜像出来的，旋转方向相反 */
    const val LEFT_BORDER_ROTATION_DEG = -90f

    fun isCorner(state: Int) = state == CORNER_BR || state == CORNER_BL

    fun isBorder(state: Int) = state == BORDER_R || state == BORDER_L

    fun isLeft(state: Int) = state == CORNER_BL || state == BORDER_L

    /** 该状态用哪套素材：角落素材 or 边框素材 */
    fun usesCornerArt(state: Int) = isCorner(state)

    /** 贴左边时素材需要水平镜像 */
    fun needsMirror(state: Int) = isLeft(state)

    /**
     * 当前是否应该把人物转成"躺在边上"的形态。
     *
     * 用户规则：**只有吸附贴边时才转**；离目标边超过 80px 就恢复正立（用原素材方向）。
     *
     * @param distanceFromEdgePx 离目标边的距离；贴边时为 0
     */
    fun isAttached(distanceFromEdgePx: Float): Boolean =
        distanceFromEdgePx <= BORDER_DETACH_PX

    /**
     * 当前该用多少度旋转。
     * 角落状态永远 0（人物正立）；边框状态只有贴边时才转。
     */
    fun rotationDeg(state: Int, distanceFromEdgePx: Float = 0f): Float {
        if (isCorner(state)) return 0f
        if (!isAttached(distanceFromEdgePx)) return 0f
        return if (isLeft(state)) LEFT_BORDER_ROTATION_DEG else RIGHT_BORDER_ROTATION_DEG
    }

    /** 该状态贴边时的目标旋转角（不看距离），用于单测与缓存键 */
    fun attachedRotationDeg(state: Int): Float =
        if (isCorner(state)) 0f
        else if (isLeft(state)) LEFT_BORDER_ROTATION_DEG
        else RIGHT_BORDER_ROTATION_DEG

    // ------------------------------------------------------------------ 拖拽过渡

    /**
     * 模糊程度 0f..1f（仅角落拖出时有意义）。
     * 用户要求"模糊再狠一点"，所以用平方曲线让前段就明显糊起来。
     */
    fun blurFor(distanceFromBottomPx: Float): Float {
        if (distanceFromBottomPx <= CORNER_BLUR_START_PX) return 0f
        val t = ((distanceFromBottomPx - CORNER_BLUR_START_PX) /
            (CORNER_TO_BORDER_PX - CORNER_BLUR_START_PX)).coerceIn(0f, 1f)
        // t^0.6：一开始就压上明显的模糊，而不是线性慢慢加
        return Math.pow(t.toDouble(), 0.6).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 过渡进度 0f..1f：0 = 完全是角落素材，1 = 完全是边框素材。
     * 这是**替换进度**，保持线性；模糊强度另由 [blurFor] 控制（用户要求更狠）。
     */
    fun morphFor(distanceFromBottomPx: Float): Float {
        if (distanceFromBottomPx <= CORNER_BLUR_START_PX) return 0f
        val t = (distanceFromBottomPx - CORNER_BLUR_START_PX) /
            (CORNER_TO_BORDER_PX - CORNER_BLUR_START_PX)
        return t.coerceIn(0f, 1f)
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
     * 松手时决定吸附到哪个状态。
     *
     * @param centerX 宠物中心 x（屏幕坐标）
     * @param centerY 宠物中心 y
     * @param screenW 屏幕宽
     * @param screenH 屏幕高
     * @param cornerBandPx 判定「吸到角落」的底部区间高度（默认用 200px 阈值）
     *
     * 规则：先按左/右半屏定左右（跨中轴线），
     *       再看离屏幕底部是否够近 —— 够近吸角落，否则吸边框。
     */
    fun snapTarget(
        centerX: Float,
        centerY: Float,
        screenW: Float,
        screenH: Float,
        cornerBandPx: Float = CORNER_TO_BORDER_PX
    ): Int {
        val left = centerX < screenW / 2f
        val nearBottom = (screenH - centerY) <= cornerBandPx
        return when {
            left && nearBottom -> CORNER_BL
            left -> BORDER_L
            nearBottom -> CORNER_BR
            else -> BORDER_R
        }
    }

    /**
     * 拖动过程中根据当前位置推算出「显示用的临时状态」。
     * 用于：拖到左半屏时立刻用左边框素材（配合翻转动画）。
     */
    fun transientState(
        draggedCenterX: Float,
        screenW: Float,
        anchorState: Int
    ): Int {
        val left = draggedCenterX < screenW / 2f
        return if (isCorner(anchorState)) {
            if (left) CORNER_BL else CORNER_BR
        } else {
            if (left) BORDER_L else BORDER_R
        }
    }
}
