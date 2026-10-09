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
    fun `corner states keep the head up - no rotation`() {
        assertEquals(0f, PetState.rotationDeg(PetState.CORNER_BR), 0.001f)
        assertEquals(0f, PetState.rotationDeg(PetState.CORNER_BL), 0.001f)
        assertTrue(PetState.usesCornerArt(PetState.CORNER_BR))
        assertFalse(PetState.usesCornerArt(PetState.BORDER_R))
    }

    @Test
    fun `border states rotate 90 degrees so the head points inward`() {
        assertEquals(-90f, PetState.rotationDeg(PetState.BORDER_R), 0.001f)
        assertEquals(-90f, PetState.rotationDeg(PetState.BORDER_L), 0.001f)
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
    fun `blur ramps between 120 and 200 px`() {
        assertEquals(0.5f, PetState.blurFor(160f), 0.001f)
        assertTrue(PetState.blurFor(150f) > 0f)
        assertTrue(PetState.blurFor(150f) < 1f)
    }

    @Test
    fun `blur is full at 200px and beyond`() {
        assertEquals(1f, PetState.blurFor(200f), 0.001f)
        assertEquals(1f, PetState.blurFor(500f), 0.001f)
    }

    @Test
    fun `morph matches blur ramp so the crossfade lines up`() {
        assertEquals(PetState.blurFor(160f), PetState.morphFor(160f), 0.001f)
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
}
