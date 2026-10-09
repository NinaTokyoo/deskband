package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 吸附状态机测试。
 * 这些规则都是用户口述的硬性要求，用测试钉死，避免以后改代码改坏。
 */
class PetStateTest {

    private val SW = 1440f
    private val SH = 3168f

    // ---------------------------------------------------------- 素材朝向

    @Test
    fun `corner states always keep the head up - never rotated`() {
        assertEquals(0f, PetState.rotationDeg(PetState.CORNER_BR, 0f), 0.001f)
        assertEquals(0f, PetState.rotationDeg(PetState.CORNER_BL, 0f), 0.001f)
        // 拖出很远也不转
        assertEquals(0f, PetState.rotationDeg(PetState.CORNER_BR, 900f), 0.001f)
        assertTrue(PetState.usesCornerArt(PetState.CORNER_BR))
        assertFalse(PetState.usesCornerArt(PetState.BORDER_R))
    }

    @Test
    fun `border states are only rotated while attached to the edge`() {
        // 贴边（距离 0）-> 旋转
        assertEquals(PetState.RIGHT_BORDER_ROTATION_DEG,
            PetState.rotationDeg(PetState.BORDER_R, 0f), 0.001f)
        assertEquals(PetState.LEFT_BORDER_ROTATION_DEG,
            PetState.rotationDeg(PetState.BORDER_L, 0f), 0.001f)
        // 刚好 80px 仍算贴边
        assertEquals(PetState.RIGHT_BORDER_ROTATION_DEG,
            PetState.rotationDeg(PetState.BORDER_R, 80f), 0.001f)
        // 超过 80px -> 恢复正立（用户规则）
        assertEquals(0f, PetState.rotationDeg(PetState.BORDER_R, 81f), 0.001f)
        assertEquals(0f, PetState.rotationDeg(PetState.BORDER_R, 400f), 0.001f)
        assertEquals(0f, PetState.rotationDeg(PetState.BORDER_L, 200f), 0.001f)
    }

    @Test
    fun `right border rotation is the empirically verified value`() {
        // 这个符号是真机截图实测定的：-90 时脸朝屏幕外侧（错），+90 才朝内侧。
        // 见 PetState.RIGHT_BORDER_ROTATION_DEG 的注释。
        assertEquals(90f, PetState.RIGHT_BORDER_ROTATION_DEG, 0.001f)
        assertEquals(-90f, PetState.LEFT_BORDER_ROTATION_DEG, 0.001f)
    }

    @Test
    fun `isAttached boundary is exactly 80px`() {
        assertTrue(PetState.isAttached(0f))
        assertTrue(PetState.isAttached(80f))
        assertFalse(PetState.isAttached(80.1f))
    }

    @Test
    fun `only left states are mirrored`() {
        assertTrue(PetState.needsMirror(PetState.CORNER_BL))
        assertTrue(PetState.needsMirror(PetState.BORDER_L))
        assertFalse(PetState.needsMirror(PetState.CORNER_BR))
        assertFalse(PetState.needsMirror(PetState.BORDER_R))
    }

    // ---------------------------------------------------------- 模糊阈值

    @Test
    fun `no blur until 120px off the bottom`() {
        assertEquals(0f, PetState.blurFor(0f), 0.001f)
        assertEquals(0f, PetState.blurFor(119f), 0.001f)
        assertEquals(0f, PetState.blurFor(120f), 0.001f)
    }

    @Test
    fun `blur ramps between 120 and 200 px and bites early`() {
        assertTrue(PetState.blurFor(150f) > 0f)
        assertTrue(PetState.blurFor(150f) < 1f)
        // 用了 t^0.6，中点(160px)要明显超过线性的一半 —— 即"模糊更狠"
        assertTrue(
            "160px 处应比线性更强",
            PetState.blurFor(160f) > 0.5f
        )
        assertEquals(1f, PetState.blurFor(200f), 0.001f)
        assertEquals(1f, PetState.blurFor(500f), 0.001f)
    }

    @Test
    fun `morph stays linear even though blur is boosted`() {
        assertEquals(0.5f, PetState.morphFor(160f), 0.001f)
        assertEquals(1f, PetState.morphFor(200f), 0.001f)
        assertEquals(0f, PetState.morphFor(100f), 0.001f)
    }

    // ---------------------------------------------------------- 吸附判定

    @Test
    fun `dropping bottom right snaps to the corner`() {
        val s = PetState.snapTarget(
            centerX = SW * 0.75f,
            centerY = SH - 50f,
            screenW = SW, screenH = SH
        )
        assertEquals(PetState.CORNER_BR, s)
    }

    @Test
    fun `dropping bottom left snaps to the left corner`() {
        val s = PetState.snapTarget(
            centerX = SW * 0.25f,
            centerY = SH - 50f,
            screenW = SW, screenH = SH
        )
        assertEquals(PetState.CORNER_BL, s)
    }

    @Test
    fun `dropping high on the right snaps to the right border`() {
        val s = PetState.snapTarget(
            centerX = SW * 0.75f,
            centerY = SH * 0.4f,
            screenW = SW, screenH = SH
        )
        assertEquals(PetState.BORDER_R, s)
    }

    @Test
    fun `dropping high on the left snaps to the left border`() {
        val s = PetState.snapTarget(
            centerX = SW * 0.25f,
            centerY = SH * 0.4f,
            screenW = SW, screenH = SH
        )
        assertEquals(PetState.BORDER_L, s)
    }

    @Test
    fun `crossing the vertical centerline switches sides`() {
        val justRight = PetState.snapTarget(SW / 2f + 1f, SH * 0.4f, SW, SH)
        val justLeft = PetState.snapTarget(SW / 2f - 1f, SH * 0.4f, SW, SH)
        assertEquals(PetState.BORDER_R, justRight)
        assertEquals(PetState.BORDER_L, justLeft)
    }

    @Test
    fun `corner band boundary is exactly 200px from the bottom`() {
        // 距底 200px -> 仍然算角落；201px -> 变成边框
        assertEquals(PetState.CORNER_BR,
            PetState.snapTarget(SW * 0.75f, SH - 200f, SW, SH))
        assertEquals(PetState.BORDER_R,
            PetState.snapTarget(SW * 0.75f, SH - 201f, SW, SH))
    }

    // ---------------------------------------------------------- 拖动中的临时状态

    @Test
    fun `dragging a corner across midline shows the other corner art`() {
        assertEquals(PetState.CORNER_BL,
            PetState.transientState(SW * 0.2f, SW, PetState.CORNER_BR))
        assertEquals(PetState.CORNER_BR,
            PetState.transientState(SW * 0.8f, SW, PetState.CORNER_BR))
    }

    @Test
    fun `dragging a border across midline shows the other border art`() {
        assertEquals(PetState.BORDER_L,
            PetState.transientState(SW * 0.2f, SW, PetState.BORDER_R))
        assertEquals(PetState.BORDER_R,
            PetState.transientState(SW * 0.8f, SW, PetState.BORDER_L))
    }

    @Test
    fun `corner and border classification helpers agree`() {
        assertTrue(PetState.isCorner(PetState.CORNER_BR))
        assertTrue(PetState.isCorner(PetState.CORNER_BL))
        assertFalse(PetState.isCorner(PetState.BORDER_R))
        assertTrue(PetState.isBorder(PetState.BORDER_R))
        assertTrue(PetState.isBorder(PetState.BORDER_L))
        assertFalse(PetState.isBorder(PetState.CORNER_BL))
    }

    // ---------------------------------------------------------- 比例不变（用户硬要求）

    @Test
    fun `scaling preserves the aspect ratio of each asset`() {
        // 真实素材尺寸
        val corner = PetState.scaledSize(1440, 1352, 3084f * 0.15f)
        val border = PetState.scaledSize(1024, 875, 3084f * 0.15f)
        val rCorner = corner[0].toDouble() / corner[1]
        val rBorder = border[0].toDouble() / border[1]
        assertEquals(1440.0 / 1352.0, rCorner, 0.01)
        assertEquals(1024.0 / 875.0, rBorder, 0.01)
    }

    @Test
    fun `rotating never swaps width and height of the draw box`() {
        // 旋转是绘制时做的，格子尺寸与状态无关 —— 所以同一素材在任何状态下
        // 算出来的格子必须一模一样，否则就会出现"旋转把图拉长"的 bug。
        val a = PetState.scaledSize(1024, 875, 463f)
        val b = PetState.scaledSize(1024, 875, 463f)
        assertEquals(a[0], b[0])
        assertEquals(a[1], b[1])
        // 而且宽/高比必须等于素材原始比，绝不是它的倒数
        val ratio = a[0].toDouble() / a[1]
        assertEquals(1024.0 / 875.0, ratio, 0.01)
        assertTrue("不能是倒数", Math.abs(ratio - 875.0 / 1024.0) > 0.01)
    }

    // ---------------------------------------------------------- 只模糊角落过渡

    @Test
    fun `blur only exists inside the corner transition window`() {
        // 区间内才有模糊
        assertTrue(PetState.blurFor(121f) > 0f)
        assertTrue(PetState.blurFor(160f) > 0f)
        assertTrue(PetState.blurFor(199f) > 0f)
        // 区间外为 0（UI 层用 inCornerTransition 卡住，200 以上已经全边框素材）
        assertEquals(0f, PetState.blurFor(120f), 0.001f)
        assertEquals(1f, PetState.blurFor(200f), 0.001f) // 端点是满值，但 UI 排除
    }
}
