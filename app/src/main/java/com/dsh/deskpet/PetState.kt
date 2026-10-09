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

    fun isCorner(state: Int) = state == CORNER_BR || state == CORNER_BL

    fun isBorder(state: Int) = state == BORDER_R || state == BORDER_L

    fun isLeft(state: Int) = state == CORNER_BL || state == BORDER_L

    /** 该状态用哪套素材：角落素材 or 边框素材 */
    fun usesCornerArt(state: Int) = isCorner(state)

    /** 贴左边时素材需要水平镜像 */
    fun needsMirror(state: Int) = isLeft(state)

    /**
     * 边框状态下人物是否要旋转 90°（吸附时头朝内侧）。
     * 角落状态不旋转（头朝上）。
     */
    fun rotationDeg(state: Int): Float = if (isBorder(state)) -90f else 0f

    // ------------------------------------------------------------------ 拖拽过渡

    /** 模糊程度 0f..1f（仅角落拖出时有意义） */
    fun blurFor(distanceFromBottomPx: Float): Float {
        if (distanceFromBottomPx <= CORNER_BLUR_START_PX) return 0f
        val t = (distanceFromBottomPx - CORNER_BLUR_START_PX) /
            (CORNER_TO_BORDER_PX - CORNER_BLUR_START_PX)
        return t.coerceIn(0f, 1f)
    }

    /**
     * 过渡进度 0f..1f：
     * 0 = 完全是角落素材（正立），1 = 完全是边框素材（已旋转）。
     * 用来做「角落素材 与 未旋转边框素材 的叠加」，避免替换生硬。
     */
    fun morphFor(distanceFromBottomPx: Float): Float {
        if (distanceFromBottomPx <= CORNER_BLUR_START_PX) return 0f
        val t = (distanceFromBottomPx - CORNER_BLUR_START_PX) /
            (CORNER_TO_BORDER_PX - CORNER_BLUR_START_PX)
        return t.coerceIn(0f, 1f)
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
