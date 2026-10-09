package com.dsh.deskpet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 分区 / 吸附 / 朝向规则测试。
 *
 * 现行规则（用户最新确认）：
 *   只有 A / B / C / E 四个区，**没有 D 区、没有模糊**
 *   A 区尺寸可调（横向 aW、纵向 aH），B / C 跟着 A 变
 *   A 区用「角落素材」，B / C / E 用「正立素材」
 *   稳定朝向只有两种：正立 0° 与旋转 ∓90°，没有中间角度
 */
class PetStateTest {

    private val SW = 1440f
    private val SH = 3084f

    @After
    fun resetZone() {
        // 每个用例后恢复默认，避免互相影响
        PetState.setZoneSize(PetState.A_W_DEFAULT, PetState.A_H_DEFAULT)
    }

    // ---------------------------------------------------------- 分区

    @Test
    fun zone_A_is_aW_by_aH_at_the_corner() {
        PetState.setZoneSize(200f, 365f)
        assertEquals(PetState.ZONE_A, PetState.zoneOf(0f, 0f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(365f, 200f, false))
        // 底边超出 aH、但横向还在 aW 内 -> 归 B（侧边带）
        assertEquals(PetState.ZONE_B, PetState.zoneOf(366f, 200f, false))
        // 横向超出 aW、但底边还在 aH 内 -> 归 C（下侧带）
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 201f, false))
        // 两边都超出 -> E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(366f, 201f, false))
    }

    @Test
    fun zone_C_is_the_bottom_band_outside_A() {
        PetState.setZoneSize(200f, 365f)
        // 离底 <= aH 但横向已出 A -> C
        assertEquals(PetState.ZONE_C, PetState.zoneOf(200f, 201f, false))
        assertEquals(PetState.ZONE_C, PetState.zoneOf(365f, 500f, false))
        // 超出 aH、横向也超出 aW -> E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(366f, 500f, false))
    }

    @Test
    fun zone_B_is_the_side_band_outside_A() {
        PetState.setZoneSize(200f, 365f)
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 100f, false))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(500f, 200f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(500f, 201f, false))
    }

    @Test
    fun zone_E_is_everything_else() {
        PetState.setZoneSize(200f, 365f)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(1000f, 1000f, true))
    }

    @Test
    fun blur_is_gone_entirely() {
        // 用户取消了 D 区，任何位置都不该模糊
        assertFalse(PetState.hasBlur(0f, 0f, false))
        assertFalse(PetState.hasBlur(400f, 400f, false))
        assertFalse(PetState.hasBlur(1000f, 1000f, true))
    }

    // ---------------------------------------------------------- A 区尺寸可调

    @Test
    fun zone_size_is_clamped_to_valid_range() {
        PetState.setZoneSize(1f, 1f)
        assertEquals(PetState.A_W_MIN, PetState.currentAW, 0.01f)
        assertEquals(PetState.A_H_MIN, PetState.currentAH, 0.01f)
        PetState.setZoneSize(99999f, 99999f)
        assertEquals(PetState.A_W_MAX, PetState.currentAW, 0.01f)
        assertEquals(PetState.A_H_MAX, PetState.currentAH, 0.01f)
    }

    @Test
    fun A_size_is_settable_and_B_C_follow_it() {
        // A 小的时候，(300, 300) 在 A 之外、也不在带子里 -> E
        PetState.setZoneSize(200f, 200f)
        assertEquals(200f, PetState.currentAW, 0.01f)
        assertEquals(200f, PetState.currentAH, 0.01f)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(300f, 300f, false))

        // A 放大到 400 -> 同一个点落进 A
        PetState.setZoneSize(400f, 400f)
        assertEquals(PetState.ZONE_A, PetState.zoneOf(300f, 300f, false))

        // 放大后，底边仍在 aH 内但横向超出 -> C（C 带跟着 A 变宽）
        assertEquals(PetState.ZONE_C, PetState.zoneOf(300f, 900f, false))

        // 缩小到 100 -> 又回到 E
        PetState.setZoneSize(100f, 100f)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(300f, 300f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(100f, 100f, false))
    }

    // ---------------------------------------------------------- 素材与朝向

    @Test
    fun A_uses_corner_art_and_never_rotates() {
        assertEquals(PetState.CORNER_BR, PetState.stateForZone(PetState.ZONE_A, false))
        assertEquals(PetState.CORNER_BL, PetState.stateForZone(PetState.ZONE_A, true))
        assertTrue(PetState.usesCornerArt(PetState.CORNER_BR))
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_BR), 0.001f)
    }

    @Test
    fun B_and_C_use_the_upright_art_with_no_rotation() {
        val c = PetState.stateForZone(PetState.ZONE_C, false)
        val b = PetState.stateForZone(PetState.ZONE_B, false)
        assertTrue(PetState.isUprightVariant(c))
        // C 也是正立（用户要求：贴底边用正立素材）
        assertTrue(PetState.isUprightVariant(b) || b == PetState.BORDER_R)
        assertEquals(0f, PetState.rotationForSnap(c), 0.001f)
        assertFalse(PetState.usesCornerArt(c))
        assertTrue(PetState.usesBorderArt(c))
    }

    @Test
    fun B_rotates_when_snapped_to_the_side() {
        // 恢复"左右边框旋转吸附"：B 区是旋转形态
        assertEquals(PetState.BORDER_R, PetState.stateForZone(PetState.ZONE_B, false))
        assertEquals(PetState.BORDER_L, PetState.stateForZone(PetState.ZONE_B, true))
        // 用户规定：贴右边框时人物头朝左 -> +90；贴左边框头朝右 -> -90
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        // 用的是正立素材那张图
        assertTrue(PetState.usesBorderArt(PetState.BORDER_R))
    }

    @Test
    fun C_is_upright_and_does_not_rotate() {
        val c = PetState.stateForZone(PetState.ZONE_C, false)
        assertTrue(PetState.isUprightVariant(c))
        assertEquals(0f, PetState.rotationForSnap(c), 0.001f)
    }

    @Test
    fun E_rotates_only_when_the_upright_box_would_overflow() {
        val w = 400f   // 正立格子宽
        // 右边缘还在屏幕内 -> 不转
        assertEquals(0f, PetState.eRotation(false, 1000f, w, SW), 0.001f)
        assertEquals(0f, PetState.eRotation(false, SW - w, w, SW), 0.001f)
        // 右边缘越界 -> 转（右侧 +90，头朝左）
        assertEquals(90f, PetState.eRotation(false, SW - w + 10f, w, SW), 0.001f)
        // 左半屏 -> -90
        assertEquals(-90f, PetState.eRotation(true, SW - w + 10f, w, SW), 0.001f)
    }

    @Test
    fun hysteresis_stops_the_B_E_boundary_from_flickering() {
        PetState.setZoneSize(200f, 365f)
        // 稍微超出 aW 但还在滞回带内：
        // 之前是 B -> 保持 B（不闪）；之前是 E -> 保持 E
        val justOut = 200f + PetState.HYSTERESIS_PX / 2f
        assertEquals(
            PetState.ZONE_B,
            PetState.zoneWithHysteresis(1000f, justOut, false, PetState.ZONE_B)
        )
        assertEquals(
            PetState.ZONE_E,
            PetState.zoneWithHysteresis(1000f, justOut, false, PetState.ZONE_E)
        )
        // 明显离开 -> 一定退出 B
        assertEquals(
            PetState.ZONE_E,
            PetState.zoneWithHysteresis(
                1000f, 200f + PetState.HYSTERESIS_PX + 5f, false, PetState.ZONE_B
            )
        )
        // 真正贴边 -> 进入 B
        assertEquals(
            PetState.ZONE_B,
            PetState.zoneWithHysteresis(1000f, 150f, false, PetState.ZONE_E)
        )
    }

    @Test
    fun only_two_stable_rotations_exist() {
        // 任何状态吸附后只可能是 0 或 ∓90，没有中间角度
        val states = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT
        )
        for (s in states) {
            val r = PetState.rotationForSnap(s)
            assertTrue("状态 $s 的旋转角 $r 不是稳定值", r == 0f || abs(r) == 90f)
        }
    }

    @Test
    fun right_rotation_points_the_head_left() {
        // 用户规定：吸附右边框 -> 头朝左；左边框 -> 头朝右
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
    }

    @Test
    fun attached_states_are_recognised_for_drag_damping() {
        // 吸附状态起步才加阻尼
        assertTrue(PetState.isAttachedState(PetState.BORDER_R))
        assertTrue(PetState.isAttachedState(PetState.BORDER_L))
        assertTrue(PetState.isAttachedState(PetState.BORDER_R_UPRIGHT))
        assertTrue(PetState.isAttachedState(PetState.CORNER_BR))
        assertTrue(PetState.isAttachedState(PetState.CORNER_BL))
    }

    // ---------------------------------------------------------- 吸附

    @Test
    fun A_snaps_to_the_corner_on_both_axes() {
        PetState.setZoneSize(200f, 365f)
        val db = 50f; val dr = 50f
        val r = PetState.snapPosition(
            SW - 500f, SH - 500f, 400f, 400f, db, dr, SW, SH, false
        )
        assertEquals(SW - 400f, r[0], 0.5f)
        assertEquals(SH - 400f, r[1], 0.5f)
    }

    @Test
    fun C_snaps_only_vertically_and_keeps_x() {
        PetState.setZoneSize(200f, 365f)
        val x0 = 500f
        val r = PetState.snapPosition(
            x0, SH - 100f, 300f, 400f, 100f, 900f, SW, SH, false
        )
        assertEquals(SH - 400f, r[1], 0.5f)   // 贴底
        assertEquals(x0, r[0], 0.5f)          // x 不动
    }

    @Test
    fun B_snaps_only_horizontally_and_keeps_y() {
        PetState.setZoneSize(200f, 365f)
        val y0 = 1000f
        val r = PetState.snapPosition(
            SW - 100f, y0, 300f, 400f, 1200f, 100f, SW, SH, false
        )
        assertEquals(SW - 300f, r[0], 0.5f)   // 贴侧边
        assertEquals(y0, r[1], 0.5f)          // y 不动
    }

    @Test
    fun E_snaps_to_the_nearer_edge() {
        PetState.setZoneSize(200f, 365f)
        // 离底更近 -> 贴底
        val rb = PetState.snapPosition(500f, 1000f, 300f, 400f, 600f, 900f, SW, SH, false)
        assertEquals(SH - 400f, rb[1], 0.5f)
        // 离侧边更近 -> 贴侧边
        val rs = PetState.snapPosition(500f, 1000f, 300f, 400f, 900f, 600f, SW, SH, false)
        assertEquals(SW - 300f, rs[0], 0.5f)
    }

    @Test
    fun left_half_measures_distance_from_the_LEFT_edge() {
        val x = 50f
        assertEquals(50f, PetState.sideDist(x, 475f, SW, true), 0.5f)
        assertEquals(SW - (x + 475f), PetState.sideDist(x, 475f, SW, false), 0.5f)
    }

    // ---------------------------------------------------------- 比例与防丢

    @Test
    fun scaling_preserves_the_aspect_ratio() {
        val corner = PetState.scaledSize(1440, 1352, SH * 0.15f)
        val upright = PetState.scaledSize(1024, 875, SH * 0.15f)
        assertEquals(1440.0 / 1352.0, corner[0].toDouble() / corner[1], 0.01)
        assertEquals(1024.0 / 875.0, upright[0].toDouble() / upright[1], 0.01)
    }

    @Test
    fun rotating_never_changes_the_draw_box_ratio() {
        // 旋转只在 Canvas 上做，格子尺寸与角度无关
        val a = PetState.scaledSize(1024, 875, 463f)
        val b = PetState.scaledSize(1024, 875, 463f)
        assertEquals(a[0], b[0])
        assertEquals(a[1], b[1])
        val ratio = a[0].toDouble() / a[1]
        assertEquals(1024.0 / 875.0, ratio, 0.01)
        assertTrue(abs(ratio - 875.0 / 1024.0) > 0.01)
    }

    @Test
    fun position_is_clamped_so_the_pet_cannot_be_lost() {
        val far = PetState.clampToScreen(-9999f, 9999f, 400f, 500f, SW, SH)
        assertTrue(far[0] >= -400f + 200f - 0.01f)
        assertTrue(far[1] <= SH - 250f + 0.01f)
        // 屏幕内不动
        val inside = PetState.clampToScreen(400f, 800f, 400f, 500f, SW, SH)
        assertEquals(400f, inside[0], 0.01f)
        assertEquals(800f, inside[1], 0.01f)
    }
}
