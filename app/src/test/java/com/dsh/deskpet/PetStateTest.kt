package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 吸附状态机测试。
 * 这些规则都是用户口述的硬性要求，用测试钉死，避免以后改代码改坏。
 */
class PetStateTest {

    private val SW = 1440f
    private val SH = 3168f

    // ---------------------------------------------------------- 分区（最新规则）

    @Test
    fun `zone A is the 356 by 356 corner square`() {
        assertEquals(PetState.ZONE_A, PetState.zoneOf(0f, 0f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(356f, 356f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(10f, 300f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 10f))
        // 只要有一边超过 356 就不是 A
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(357f, 10f))
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(10f, 357f))
    }

    @Test
    fun `zone C is the 200px bottom strip outside A`() {
        // A 之外的底部 200px -> C
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 500f))
        assertEquals(PetState.ZONE_C, PetState.zoneOf(0f, 500f))
        // 关键回归：A 区里 200..356 的环带**不能**被判成 C（否则 C 会变 356 宽）
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 300f))
        // 超过 200 就不再是 C
        assertEquals(PetState.ZONE_D, PetState.zoneOf(201f, 500f))
    }

    @Test
    fun `zone B is the 200px right strip outside A`() {
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 200f))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 0f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 201f))
    }

    @Test
    fun `A swallows the whole 200px band near the corner, so C and B never overlap`() {
        // 几何结论：A 是 356×356，而 C/B 只有 200 宽。
        // 角附近"底边<=200"的点，右边也必然<=356，早已落进 A。
        // 所以 C 实际上只在 dr>356 时才可能出现，C 与 B 永远不冲突，
        // 「重合以底边为准」这条规则不会触发 —— 代码里先判 C 只是照规则写、更稳妥。
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 150f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 200f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 300f))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 356f))
        // C 真正生效要 dr>356
        assertEquals(PetState.ZONE_C, PetState.zoneOf(150f, 357f))
        // B 同理
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 150f))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(357f, 150f))
    }

    @Test
    fun `zone D is the L shaped band out to 444`() {
        assertEquals(PetState.ZONE_D, PetState.zoneOf(444f, 500f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 444f))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(400f, 400f))
        // 超过 444 两边都不沾 -> E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(445f, 445f))
    }

    @Test
    fun `zone E is everything else`() {
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(3084f, 1440f))
    }

    @Test
    fun `only zone D has blur`() {
        assertFalse(PetState.hasBlur(0f, 0f))        // A
        assertFalse(PetState.hasBlur(100f, 500f))    // C
        assertFalse(PetState.hasBlur(500f, 100f))    // B
        assertTrue(PetState.hasBlur(400f, 500f))     // D
        assertFalse(PetState.hasBlur(1000f, 1000f))  // E
    }

    // ---------------------------------------------------------- 吸附目标

    @Test
    fun `releasing in A snaps to the corner`() {
        assertEquals(PetState.CORNER_BR, PetState.snapTarget(10f, 10f, false))
        assertEquals(PetState.CORNER_BL, PetState.snapTarget(10f, 10f, true))
    }

    @Test
    fun `releasing in C snaps vertically to the bottom`() {
        assertEquals(PetState.CORNER_BR, PetState.snapTarget(100f, 600f, false))
        assertEquals(PetState.CORNER_BL, PetState.snapTarget(100f, 600f, true))
    }

    @Test
    fun `releasing in B snaps horizontally to the side`() {
        assertEquals(PetState.BORDER_R, PetState.snapTarget(600f, 100f, false))
        assertEquals(PetState.BORDER_L, PetState.snapTarget(600f, 100f, true))
    }

    @Test
    fun `releasing in D snaps to whichever edge is nearer`() {
        // 离底更近 -> 按 C 规则吸底
        assertEquals(PetState.CORNER_BR, PetState.snapTarget(400f, 430f, false))
        // 离右更近 -> 按 B 规则吸右边
        assertEquals(PetState.BORDER_R, PetState.snapTarget(430f, 400f, false))
        // 镜像
        assertEquals(PetState.CORNER_BL, PetState.snapTarget(400f, 430f, true))
        assertEquals(PetState.BORDER_L, PetState.snapTarget(430f, 400f, true))
    }

    @Test
    fun `releasing in E also snaps to the nearer edge`() {
        assertEquals(PetState.CORNER_BR, PetState.snapTarget(500f, 900f, false))
        assertEquals(PetState.BORDER_R, PetState.snapTarget(900f, 500f, false))
    }

    // ---------------------------------------------------------- 旋转角（用户规定）

    @Test
    fun `C uses the raw border asset with no rotation`() {
        // C 区吸附到底边 -> 边框原始素材，不转
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_BR), 0.001f)
        // 拖到 C 区时也不该转
        assertEquals(0f, PetState.dragRotationFor(100f, 600f, false), 0.001f)
    }

    @Test
    fun `B uses the border asset rotated 90 degrees`() {
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        // 拖到 B 区时应转
        assertEquals(-90f, PetState.dragRotationFor(600f, 100f, false), 0.001f)
        assertEquals(90f, PetState.dragRotationFor(600f, 100f, true), 0.001f)
    }

    @Test
    fun `A never rotates`() {
        assertEquals(0f, PetState.dragRotationFor(10f, 10f, false), 0.001f)
        assertEquals(0f, PetState.dragRotationFor(300f, 300f, true), 0.001f)
    }

    // ---------------------------------------------------------- 素材选择

    @Test
    fun `A uses corner art, everything else uses border art`() {
        assertEquals(PetState.CORNER_BR, PetState.artStateFor(10f, 10f, false))
        assertEquals(PetState.CORNER_BL, PetState.artStateFor(10f, 10f, true))
        // C 区虽然吸到角落，但用的是边框素材
        assertEquals(PetState.BORDER_R, PetState.artStateFor(100f, 600f, false))
        assertEquals(PetState.BORDER_R, PetState.artStateFor(600f, 100f, false))
        assertEquals(PetState.BORDER_R, PetState.artStateFor(1000f, 1000f, false))
    }

    // ---------------------------------------------------------- 只模糊角落过渡

    @Test
    fun `blur only exists inside the corner transition window`() {
        // 区间内才有模糊
        assertTrue(PetState.blurFor(400f, 400f) > 0f)
        // A 区完全不模糊
        assertEquals(0f, PetState.blurFor(10f, 10f), 0.001f)
        assertEquals(0f, PetState.blurFor(300f, 300f), 0.001f)
    }

    // ---------------------------------------------------------- 比例不变（用户硬要求）

    @Test
    fun `scaling preserves the aspect ratio of each asset`() {
        val corner = PetState.scaledSize(1440, 1352, 3084f * 0.15f)
        val border = PetState.scaledSize(1024, 875, 3084f * 0.15f)
        assertEquals(1440.0 / 1352.0, corner[0].toDouble() / corner[1], 0.01)
        assertEquals(1024.0 / 875.0, border[0].toDouble() / border[1], 0.01)
    }

    @Test
    fun `rotating never swaps width and height of the draw box`() {
        val a = PetState.scaledSize(1024, 875, 463f)
        val ratio = a[0].toDouble() / a[1]
        assertEquals(1024.0 / 875.0, ratio, 0.01)
        assertTrue("不能是倒数", abs(ratio - 875.0 / 1024.0) > 0.01)
    }
}
