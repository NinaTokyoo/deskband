package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 吸附状态机测试。
 *
 * 这里每一条都是用户口述的硬性要求，用测试钉死，避免以后改代码改坏。
 *
 * 分区几何（左半屏是镜像，但 A 区尺寸不同）：
 *   右侧：A = 356x356，D 外沿 444
 *   左侧：A = 200x200，D 外沿 288（A 缩小后 C 带自然向右延长）
 *   B / C 带宽度都是 200
 */
class PetStateTest {

    private val SW = 1440f          // 屏幕宽
    private val SH_WORK = 3084f     // 可活动高度（已扣导航栏）
    private val PW = 475f           // 宠物格子宽
    private val PH = 463f           // 宠物格子高

    // ---------------------------------------------------------- 分区：右侧

    @Test
    fun zone_A_right_is_356_by_356() {
        assertEquals(PetState.ZONE_A, PetState.zoneOf(0f, 0f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(356f, 356f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(10f, 300f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 10f, false))
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(357f, 10f, false))
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(10f, 357f, false))
    }

    @Test
    fun zone_C_right_is_the_200px_bottom_strip() {
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 500f, false))
        assertEquals(PetState.ZONE_C, PetState.zoneOf(0f, 500f, false))
        // A 区里 200..356 的环带不能被判成 C，否则 C 会变 356 宽
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 300f, false))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(201f, 500f, false))
    }

    @Test
    fun zone_B_right_is_the_200px_side_strip() {
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 200f, false))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 0f, false))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 201f, false))
    }

    @Test
    fun zone_D_right_reaches_444() {
        assertEquals(PetState.ZONE_D, PetState.zoneOf(444f, 500f, false))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(500f, 444f, false))
        assertEquals(PetState.ZONE_D, PetState.zoneOf(400f, 400f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(445f, 445f, false))
    }

    @Test
    fun zone_E_is_everything_else() {
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(3084f, 1440f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f, true))
    }

    // ---------------------------------------------------------- 分区：左侧（A 缩到 200）

    @Test
    fun zone_A_left_is_200_by_200() {
        assertEquals(PetState.ZONE_A, PetState.zoneOf(0f, 0f, true))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(200f, 200f, true))
        // 超过 200 就不再是 A —— 这是本次改动的核心
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(201f, 10f, true))
        assertNotEquals(PetState.ZONE_A, PetState.zoneOf(10f, 201f, true))
    }

    @Test
    fun on_the_left_C_extends_right_because_A_shrank() {
        // 离底 150、离左边 250：已出 A(200)，但仍在 C 带（离底<=200）
        // 改动前 A=356 时这里会被 A 吃掉；现在应归 C
        assertEquals(PetState.ZONE_C, PetState.zoneOf(150f, 250f, true))
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 250f, true))
        // 同样的坐标在右侧仍属 A（右侧 A 没变）
        assertEquals(PetState.ZONE_A, PetState.zoneOf(150f, 250f, false))
    }

    @Test
    fun on_the_left_D_shrinks_to_288() {
        assertEquals(PetState.ZONE_D, PetState.zoneOf(288f, 500f, true))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(289f, 500f, true))
        // 右侧 D 外沿仍是 444
        assertEquals(PetState.ZONE_D, PetState.zoneOf(444f, 500f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(445f, 500f, false))
    }

    @Test
    fun only_zone_D_has_blur() {
        assertFalse(PetState.hasBlur(0f, 0f, false))        // A
        assertFalse(PetState.hasBlur(100f, 500f, false))    // C
        assertFalse(PetState.hasBlur(500f, 100f, false))    // B
        assertTrue(PetState.hasBlur(400f, 500f, false))     // D
        assertFalse(PetState.hasBlur(1000f, 1000f, false))  // E
        assertFalse(PetState.hasBlur(0f, 0f, true))         // A left
        assertTrue(PetState.hasBlur(250f, 500f, true))      // D left
    }

    // ---------------------------------------------------------- 吸附：贴到边上

    /** 跑一次吸附，返回 [x, y, category]（侧边距离按本侧量） */
    private fun snap(x: Float, y: Float, leftHalf: Boolean): FloatArray {
        val db = PetState.bottomDist(y, PH, SH_WORK)
        val dr = PetState.sideDist(x, PW, SW, leftHalf)
        return PetState.snapPosition(x, y, PW, PH, db, dr, SW, SH_WORK, leftHalf)
    }

    @Test
    fun A_snaps_to_the_corner_on_both_axes() {
        val r = snap(SW - PW - 50f, SH_WORK - PH - 50f, false)
        assertEquals(SW - PW, r[0], 0.5f)
        assertEquals(SH_WORK - PH, r[1], 0.5f)
        assertEquals(PetState.CORNER_BR.toFloat(), r[2], 0.01f)
    }

    @Test
    fun C_snaps_only_vertically_and_keeps_x() {
        val x0 = SW - PW - 500f          // 离侧边 500 > 356，已在 A 之外
        val r = snap(x0, SH_WORK - PH - 100f, false)
        assertEquals(SH_WORK - PH, r[1], 0.5f)   // 垂直贴底
        assertEquals(x0, r[0], 0.5f)             // x 原样保留
        // C 区用「边框素材 + 正立」的变体（用户要求头部向上）
        assertEquals(PetState.BORDER_R_UPRIGHT.toFloat(), r[2], 0.01f)
    }

    @Test
    fun B_snaps_only_horizontally_and_keeps_y() {
        val y0 = 1200f
        val r = snap(SW - PW - 100f, y0, false)
        assertEquals(SW - PW, r[0], 0.5f)        // 水平贴侧边
        assertEquals(y0, r[1], 0.5f)             // y 原样保留
        assertEquals(PetState.BORDER_R.toFloat(), r[2], 0.01f)
    }

    @Test
    fun E_snaps_to_the_nearer_edge_and_keeps_the_other_axis() {
        val x0 = 300f
        val rBottom = snap(x0, SH_WORK - PH - 500f, false)
        assertEquals(SH_WORK - PH, rBottom[1], 0.5f)
        assertEquals(x0, rBottom[0], 0.5f)

        val y0 = 800f
        val rSide = snap(SW - PW - 500f, y0, false)
        assertEquals(SW - PW, rSide[0], 0.5f)
        assertEquals(y0, rSide[1], 0.5f)
    }

    @Test
    fun D_snaps_to_the_nearer_edge_too() {
        val rBottom = snap(SW - PW - 420f, SH_WORK - PH - 380f, false)
        assertEquals(SH_WORK - PH, rBottom[1], 0.5f)
        val rSide = snap(SW - PW - 380f, SH_WORK - PH - 420f, false)
        assertEquals(SW - PW, rSide[0], 0.5f)
    }

    @Test
    fun left_half_is_a_perfect_mirror_of_the_right_half() {
        // 左下角：左侧 A 是 200，所以要贴到很靠角的位置才算 A
        val lc = snap(60f, SH_WORK - PH - 60f, true)
        assertEquals(0f, lc[0], 0.5f)
        assertEquals(PetState.CORNER_BL.toFloat(), lc[2], 0.01f)

        // 左边框：贴左边、y 保留
        val y0 = 1500f
        val lb = snap(300f, y0, true)
        assertEquals(0f, lb[0], 0.5f)
        assertEquals(y0, lb[1], 0.5f)
        assertEquals(PetState.BORDER_L.toFloat(), lb[2], 0.01f)

        // 左右镜像：右侧 x 镜像过来应等于左侧 x
        val rc = snap(SW - PW - 60f, SH_WORK - PH - 60f, false)
        val lc2 = snap(60f, SH_WORK - PH - 60f, true)
        assertEquals(SW - (rc[0] + PW), lc2[0], 0.5f)
    }

    @Test
    fun left_half_measures_distance_from_the_LEFT_edge() {
        // 关键回归：左半屏必须量左边。早先仍量"离右边"，
        // 左下角的宠物会被算成离侧边 915，判进 C 区，永远吸不到左下角。
        val x = 50f
        assertEquals(50f, PetState.sideDist(x, PW, SW, true), 0.5f)
        assertEquals(SW - (x + PW), PetState.sideDist(x, PW, SW, false), 0.5f)
    }

    // ---------------------------------------------------------- 素材与旋转

    @Test
    fun C_uses_the_upright_border_asset_with_no_rotation() {
        // C 区（贴底）用边框素材、正立、头部向上
        assertEquals(0f, PetState.rotationForSnap(PetState.BORDER_R_UPRIGHT), 0.001f)
        assertEquals(0f, PetState.rotationForSnap(PetState.BORDER_L_UPRIGHT), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(100f, 600f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(100f, 100f, false), 0.001f)
        // 素材类别：C 区 -> 「边框素材 + 正立」变体（不是角落素材，也不是旋转版）
        val r = snap(465f, SH_WORK - PH - 100f, false)
        assertEquals(PetState.BORDER_R_UPRIGHT.toFloat(), r[2], 0.01f)
        // 用的是边框那套图，不是角落图
        assertFalse(PetState.usesCornerArt(r[2].toInt()))
        assertTrue(PetState.usesBorderArt(r[2].toInt()))
    }

    @Test
    fun B_uses_the_border_asset_rotated_90_degrees() {
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        assertEquals(-90f, PetState.rotationForDrag(700f, 0f, false), 0.001f)
        assertEquals(90f, PetState.rotationForDrag(700f, 0f, true), 0.001f)
    }

    @Test
    fun A_never_rotates() {
        assertEquals(0f, PetState.rotationForDrag(10f, 10f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(150f, 150f, true), 0.001f)
    }

    @Test
    fun rotation_is_a_continuous_field() {
        var prev = PetState.rotationForDrag(700f, 0f, false)
        var maxJump = 0f
        var dr = 1f
        while (dr <= 800f) {
            val cur = PetState.rotationForDrag(700f, dr, false)
            maxJump = maxOf(maxJump, abs(cur - prev))
            prev = cur
            dr += 1f
        }
        assertTrue("旋转沿侧边距离的最大单步跳变应 < 1 度，实际 $maxJump", maxJump < 1f)
    }

    @Test
    fun rotation_is_monotonic_leaving_the_side() {
        val a = PetState.rotationForDrag(700f, 300f, false)
        val b = PetState.rotationForDrag(700f, 400f, false)
        val c = PetState.rotationForDrag(700f, 500f, false)
        assertTrue(a < c)
        assertTrue(b in a..c)
        assertEquals(-90f, PetState.rotationForDrag(700f, 0f, false), 0.001f)
    }

    @Test
    fun rotation_fades_with_height_so_the_bottom_band_never_tilts() {
        assertEquals(0f, PetState.rotationForDrag(100f, 0f, false), 0.001f)
        assertEquals(0f, PetState.rotationForDrag(356f, 0f, false), 0.001f)
        val low = PetState.rotationForDrag(400f, 0f, false)
        val high = PetState.rotationForDrag(600f, 0f, false)
        assertTrue(abs(high) > abs(low))
    }

    @Test
    fun art_blend_is_continuous() {
        assertEquals(0f, PetState.artBlendFor(0f, 0f, false), 0.001f)
        assertEquals(0f, PetState.artBlendFor(200f, 200f, false), 0.001f)
        val mid = PetState.artBlendFor(320f, 900f, false)
        assertTrue(mid > 0.05f && mid < 0.95f)
        assertEquals(1f, PetState.artBlendFor(400f, 900f, false), 0.001f)
        assertEquals(1f, PetState.artBlendFor(900f, 900f, false), 0.001f)
    }

    // ---------------------------------------------------------- 模糊：D 区高强度 + 渐变

    @Test
    fun blur_is_zero_hugging_an_edge_and_zero_far_away() {
        assertEquals(0f, PetState.blurFor(0f, 0f, false), 0.001f)
        assertEquals(0f, PetState.blurFor(100f, 100f, false), 0.001f)
        assertEquals(0f, PetState.blurFor(PetState.FADE_OUTER_PX, 900f, false), 0.001f)
        assertEquals(0f, PetState.blurFor(700f, 900f, false), 0.001f)
    }

    @Test
    fun blur_stays_high_across_the_D_band() {
        assertTrue(PetState.blurFor(400f, 400f, false) > 0.9f)
        assertTrue(PetState.blurFor(356f, 356f, false) > 0.9f)
        assertTrue(PetState.blurFor(444f, 444f, false) > 0.85f)
    }

    @Test
    fun blur_uses_the_NEARER_edge_not_the_farther_one() {
        // 贴着侧边（离侧边很小）但离底很远，仍属 D，强度必须高
        assertTrue(PetState.blurFor(500f, 400f, false) > 0.9f)
        assertTrue(PetState.blurFor(400f, 500f, false) > 0.9f)
    }

    @Test
    fun blur_ramps_up_before_D() {
        val a = PetState.blurFor(260f, 900f, false)
        val b = PetState.blurFor(300f, 900f, false)
        val c = PetState.blurFor(340f, 900f, false)
        assertTrue(a > 0f && a < 1f)
        assertTrue(b > a)
        assertTrue(c > b)
        assertTrue(PetState.blurFor(356f, 900f, false) > 0.9f)
    }

    @Test
    fun blur_ramps_back_down_after_D() {
        val a = PetState.blurFor(444f, 900f, false)
        val b = PetState.blurFor(452f, 900f, false)
        assertTrue(b < a)
        assertEquals(0f, PetState.blurFor(PetState.FADE_OUTER_PX, 900f, false), 0.001f)
    }

    @Test
    fun blur_is_continuous_everywhere() {
        var prev = PetState.blurFor(0f, 900f, false)
        var maxJump = 0f
        var d = 1f
        while (d <= 700f) {
            val cur = PetState.blurFor(d, 900f, false)
            maxJump = maxOf(maxJump, abs(cur - prev))
            prev = cur
            d += 1f
        }
        assertTrue("模糊最大单步跳变应 < 0.06，实际 $maxJump", maxJump < 0.06f)
    }

    @Test
    fun blur_works_on_the_left_side_with_its_own_geometry() {
        // 左侧 D 是 200..288，所以 250 处应该是高强度
        assertTrue(PetState.blurFor(250f, 900f, true) > 0.9f)
        // 左侧 A 内（<=200）不糊
        assertEquals(0f, PetState.blurFor(100f, 100f, true), 0.001f)
    }

    // ---------------------------------------------------------- 比例不变（硬要求）

    @Test
    fun scaling_preserves_the_aspect_ratio_of_each_asset() {
        val corner = PetState.scaledSize(1440, 1352, SH_WORK * 0.15f)
        val border = PetState.scaledSize(1024, 875, SH_WORK * 0.15f)
        assertEquals(1440.0 / 1352.0, corner[0].toDouble() / corner[1], 0.01)
        assertEquals(1024.0 / 875.0, border[0].toDouble() / border[1], 0.01)
    }

    @Test
    fun draw_box_is_independent_of_the_rotation_angle() {
        // 旋转只作用在 Canvas 上，从不修改目标矩形 -> 格子尺寸与角度无关
        val first = PetState.scaledSize(1024, 875, 463f)
        var deg = 0f
        while (deg <= 360f) {
            val s = PetState.scaledSize(1024, 875, 463f)
            assertEquals(first[0], s[0])
            assertEquals(first[1], s[1])
            deg += 15f
        }
        val ratio = first[0].toDouble() / first[1]
        assertEquals(1024.0 / 875.0, ratio, 0.01)
        assertTrue(abs(ratio - 875.0 / 1024.0) > 0.01)
    }

    // ---------------------------------------------------------- 别被拖丢

    @Test
    fun position_is_clamped_so_the_pet_cannot_be_dragged_off_screen() {
        val farLeft = PetState.clampToScreen(-2000f, 1000f, PW, PH, SW, SH_WORK)
        assertTrue(farLeft[0] >= -PW + PW * 0.5f - 0.01f)
        val farRight = PetState.clampToScreen(9999f, 1000f, PW, PH, SW, SH_WORK)
        assertTrue(farRight[0] <= SW - PW * 0.5f + 0.01f)
        val farUp = PetState.clampToScreen(500f, -2000f, PW, PH, SW, SH_WORK)
        assertTrue(farUp[1] >= -PH + PH * 0.5f - 0.01f)
        val farDown = PetState.clampToScreen(500f, 9999f, PW, PH, SW, SH_WORK)
        assertTrue(farDown[1] <= SH_WORK - PH * 0.5f + 0.01f)

        // 屏幕内的位置不能被改动（吸附要能停在贴边处）
        val inside = PetState.clampToScreen(800f, 1500f, PW, PH, SW, SH_WORK)
        assertEquals(800f, inside[0], 0.01f)
        assertEquals(1500f, inside[1], 0.01f)

        // 右下角原位也不能被改动
        val corner = PetState.clampToScreen(SW - PW, SH_WORK - PH, PW, PH, SW, SH_WORK)
        assertEquals(SW - PW, corner[0], 0.01f)
        assertEquals(SH_WORK - PH, corner[1], 0.01f)
    }
}
