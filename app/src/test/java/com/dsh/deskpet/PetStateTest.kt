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
 *   A / B / E 三个区，**没有 D 区、没有模糊**；C 已并入 E（常量保留）
 *   A 区尺寸可调（横向 aW、纵向 aH），B 跟着 A 变
 *   A 区用「角落素材」，B / E 用「正立素材」
 *   稳定朝向只有两种：正立 0° 与旋转 ∓90°，没有中间角度
 *   **上边吸附与下边吸附完全对称**：左上角 / 右上角 / 上边，
 *   素材上下翻转（flipV），收起向上滑出、呼出从上方滑入
 *
 * 本文件里有一组**回归测试**（[bottom_group_still_matches_the_old_rules] 等），
 * 用"照抄改动前实现"的参考函数把**下边组**的行为逐项钉死 ——
 * 新增上边吸附时只要碰坏了任何一处下边组语义，它们会立刻失败。
 */
class PetStateTest {

    private val SW = 1440f
    private val SH = 3084f

    /** 正立素材缩放后的足迹（与 README 的实机数值一致） */
    private val TRACK_W = 541f
    private val TRACK_H = 463f

    @After
    fun resetZone() {
        // 每个用例后恢复默认，避免互相影响
        PetState.setZoneSize(PetState.A_W_DEFAULT, PetState.A_H_DEFAULT)
    }

    // ---------------------------------------------------------- 分区

    @Test
    fun zone_A_is_aW_by_aH_at_the_corner() {
        PetState.setZoneSize(200f, 365f)
        val b = PetState.VERT_BOTTOM
        assertEquals(PetState.ZONE_A, PetState.zoneOf(b, 0f, 0f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(b, 365f, 200f, false))
        // 纵向超出 aH、但横向还在 aW 内 -> 归 B（侧边带）
        assertEquals(PetState.ZONE_B, PetState.zoneOf(b, 366f, 200f, false))
        // 横向超出 aW、纵向还在 aH 内 -> C 已并入 E，所以是 E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 200f, 201f, false))
        // 两边都超出 -> E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 366f, 201f, false))
    }

    @Test
    fun zone_C_is_gone_it_was_merged_into_E() {
        PetState.setZoneSize(200f, 365f)
        val b = PetState.VERT_BOTTOM
        // 底部那条带（排除 A 之后）现在一律是 E，与 E 完全同一套逻辑
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 300f, 500f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 0f, 500f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 365f, 900f, false))
        // 任何位置都不应再返回 ZONE_C
        for (db in intArrayOf(0, 100, 300, 365, 500, 1500)) {
            for (dr in intArrayOf(0, 100, 200, 300, 900, 1400)) {
                for (left in booleanArrayOf(false, true)) {
                    assertFalse(
                        "db=$db dr=$dr left=$left 不应再判为 C 区",
                        PetState.zoneOf(b, db.toFloat(), dr.toFloat(), left) == PetState.ZONE_C
                    )
                    assertFalse(
                        "db=$db dr=$dr left=$left 不应再判为 C 区（带滞回）",
                        PetState.zoneWithHysteresis(
                            b, db.toFloat(), dr.toFloat(), left, PetState.ZONE_E,
                            dr.toFloat(), 470f
                        ) == PetState.ZONE_C
                    )
                }
            }
        }
    }

    @Test
    fun zone_B_is_the_side_band_outside_A() {
        PetState.setZoneSize(200f, 365f)
        val b = PetState.VERT_BOTTOM
        assertEquals(PetState.ZONE_B, PetState.zoneOf(b, 500f, 100f, false))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(b, 500f, 200f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 500f, 201f, false))
    }

    @Test
    fun zone_E_is_everything_else() {
        PetState.setZoneSize(200f, 365f)
        val b = PetState.VERT_BOTTOM
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 1000f, 1000f, false))
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 1000f, 1000f, true))
    }

    @Test
    fun blur_is_gone_entirely() {
        // 用户取消了 D 区，任何位置都不该模糊
        assertFalse(PetState.hasBlur(PetState.VERT_BOTTOM, 0f, 0f, false))
        assertFalse(PetState.hasBlur(PetState.VERT_BOTTOM, 400f, 400f, false))
        assertFalse(PetState.hasBlur(PetState.VERT_TOP, 1000f, 1000f, true))
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
    fun A_size_is_settable_and_B_follows_it() {
        val b = PetState.VERT_BOTTOM
        // A 小的时候，(300, 300) 在 A 之外、也不在带子里 -> E
        PetState.setZoneSize(200f, 200f)
        assertEquals(200f, PetState.currentAW, 0.01f)
        assertEquals(200f, PetState.currentAH, 0.01f)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 300f, 300f, false))

        // A 放大到 400 -> 同一个点落进 A
        PetState.setZoneSize(400f, 400f)
        assertEquals(PetState.ZONE_A, PetState.zoneOf(b, 300f, 300f, false))

        // 放大后，横向超出 -> C 已并入 E，所以是 E
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 300f, 900f, false))

        // 缩小到 100 -> 又回到 E
        PetState.setZoneSize(100f, 100f)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(b, 300f, 300f, false))
        assertEquals(PetState.ZONE_A, PetState.zoneOf(b, 100f, 100f, false))
    }

    // ---------------------------------------------------------- 素材与朝向

    @Test
    fun A_uses_corner_art_and_never_rotates() {
        assertEquals(PetState.CORNER_BR, PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_A, false))
        assertEquals(PetState.CORNER_BL, PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_A, true))
        assertTrue(PetState.usesCornerArt(PetState.CORNER_BR))
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_BR), 0.001f)
    }

    @Test
    fun B_and_C_use_the_upright_art_with_no_rotation() {
        val c = PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_C, false)
        val b = PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_B, false)
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
        assertEquals(PetState.BORDER_R, PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_B, false))
        assertEquals(PetState.BORDER_L, PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_B, true))
        // 用户规定：贴右边框时人物头朝左 -> -90；贴左边框头朝右 -> +90
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        // 用的是正立素材那张图
        assertTrue(PetState.usesBorderArt(PetState.BORDER_R))
    }

    @Test
    fun C_is_upright_and_does_not_rotate() {
        val c = PetState.stateForZone(PetState.VERT_BOTTOM, PetState.ZONE_C, false)
        assertTrue(PetState.isUprightVariant(c))
        assertEquals(0f, PetState.rotationForSnap(c), 0.001f)
    }

    @Test
    fun rotation_is_decided_by_whether_the_CENTER_crosses_the_BE_line() {
        PetState.setZoneSize(200f, 365f)
        val v = PetState.VERT_BOTTOM
        // 故意把 dr（边缘距离）给成很大的值，证明判定用的是**中心点**
        val farEdge = 5000f
        // 中心点离侧边 200（正好在分界线上）-> B，旋转
        assertEquals(PetState.ZONE_B,
            PetState.zoneWithHysteresis(v, 1000f, farEdge, false, PetState.ZONE_E, 200f))
        // 中心点离侧边 120 -> 深入 B，旋转
        assertEquals(PetState.ZONE_B,
            PetState.zoneWithHysteresis(v, 1000f, farEdge, false, PetState.ZONE_E, 120f))
        // 中心点离侧边 250 -> 还在 E，保持正立
        assertEquals(PetState.ZONE_E,
            PetState.zoneWithHysteresis(v, 1000f, farEdge, false, PetState.ZONE_E, 250f))
    }

    @Test
    fun centerSideDist_measures_from_the_pets_own_side() {
        assertEquals(300f, PetState.centerSideDist(1140f, 1440f, false), 0.01f)
        assertEquals(300f, PetState.centerSideDist(300f, 1440f, true), 0.01f)
    }

    @Test
    fun hysteresis_stops_the_B_E_boundary_from_flickering() {
        PetState.setZoneSize(200f, 365f)
        val v = PetState.VERT_BOTTOM
        // 稍微超出 aW 但还在滞回带内：
        // 之前是 B -> 保持 B（不闪）；之前是 E -> 保持 E
        val justOut = 200f + PetState.HYSTERESIS_PX / 2f
        assertEquals(
            PetState.ZONE_B,
            PetState.zoneWithHysteresis(v, 1000f, justOut, false, PetState.ZONE_B)
        )
        assertEquals(
            PetState.ZONE_E,
            PetState.zoneWithHysteresis(v, 1000f, justOut, false, PetState.ZONE_E)
        )
        // 明显离开 -> 一定退出 B
        assertEquals(
            PetState.ZONE_E,
            PetState.zoneWithHysteresis(
                v, 1000f, 200f + PetState.HYSTERESIS_PX + 5f, false, PetState.ZONE_B
            )
        )
        // 真正贴边 -> 进入 B
        assertEquals(
            PetState.ZONE_B,
            PetState.zoneWithHysteresis(v, 1000f, 150f, false, PetState.ZONE_E)
        )
    }

    @Test
    fun only_two_stable_rotations_exist() {
        // 任何状态吸附后只可能是 0 或 ∓90，没有中间角度
        val states = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.CORNER_TR, PetState.CORNER_TL,
            PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )
        for (s in states) {
            val r = PetState.rotationForSnap(s)
            assertTrue("状态 $s 的旋转角 $r 不是稳定值", r == 0f || abs(r) == 90f)
        }
    }

    @Test
    fun right_rotation_points_the_head_left() {
        // 用户规定：吸附右边框 -> 头朝左；左边框 -> 头朝右
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
    }

    /**
     * 用户要求：**需要旋转的时候无论方向都转最小角度**。
     *
     *   · 正立 <-> 贴侧边        -> 90°（最小）
     *   · 贴右边框 <-> 贴左边框  -> **0°**：这两个图案互为水平镜像
     *     （`rotate(+90) ∘ mirror ≡ mirror ∘ rotate(-90)`），换图本身就是镜像，
     *     硬转 180° 只会让人物先立正再倒下、白转一大圈
     *   · 旋转角相同的状态之间   -> 0°（只换素材/镜像，不转）
     */
    @Test
    fun rotation_always_takes_the_minimum_angle() {
        val states = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL, PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.CORNER_TR, PetState.CORNER_TL, PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )
        for (a in states) {
            for (b in states) {
                val d = abs(PetState.rotationDelta(a, b))
                // 任何一次切换都不该超过 90°（180° 那种被识别成镜像，取 0）
                assertTrue("状态 $a -> $b 要转 $d 度，超过 90°", d <= 90.001f)
                // 最短弧：反着转的角度必须一样
                assertEquals(
                    "状态 $a -> $b 的正反向角度不一致",
                    d, abs(PetState.rotationDelta(b, a)), 0.001f
                )
            }
        }
        // 具体数值（符号 = 屏幕视觉方向：正 = 顺时针）
        // 正立 -> 贴右边框：头从"上"扫向"左"，逆时针 90°
        assertEquals(-90f, PetState.rotationDelta(PetState.BORDER_R_UPRIGHT, PetState.BORDER_R), 0.001f)
        // 正立 -> 贴左边框：头扫向"右"，顺时针 90°
        assertEquals(90f, PetState.rotationDelta(PetState.BORDER_R_UPRIGHT, PetState.BORDER_L), 0.001f)
        // 反向回正立，角度取反
        assertEquals(90f, PetState.rotationDelta(PetState.BORDER_R, PetState.BORDER_R_UPRIGHT), 0.001f)
        assertEquals(-90f, PetState.rotationDelta(PetState.BORDER_L, PetState.BORDER_R_UPRIGHT), 0.001f)
        // 角落 -> 同侧贴边也是 90°
        assertEquals(-90f, PetState.rotationDelta(PetState.CORNER_BR, PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationDelta(PetState.CORNER_BL, PetState.BORDER_L), 0.001f)
        // 左右互切：互为水平镜像，不转
        assertEquals(0f, PetState.rotationDelta(PetState.BORDER_R, PetState.BORDER_L), 0.001f)
        assertEquals(0f, PetState.rotationDelta(PetState.BORDER_L, PetState.BORDER_R), 0.001f)
        assertTrue(PetState.isSideMirrorPair(PetState.BORDER_R, PetState.BORDER_L))
        assertTrue(PetState.isSideMirrorPair(PetState.BORDER_L, PetState.BORDER_R))
        assertFalse(PetState.isSideMirrorPair(PetState.BORDER_R, PetState.BORDER_R_UPRIGHT))
        assertFalse(PetState.isSideMirrorPair(PetState.CORNER_BR, PetState.CORNER_BL))
        // 同角度状态之间也不转（左右角、左右正立、左右上边都是镜像关系）
        assertEquals(0f, PetState.rotationDelta(PetState.CORNER_BR, PetState.CORNER_BL), 0.001f)
        assertEquals(
            0f,
            PetState.rotationDelta(PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT), 0.001f
        )
        assertEquals(0f, PetState.rotationDelta(PetState.CORNER_TR, PetState.EDGE_TOP_R), 0.001f)
    }

    @Test
    fun attached_states_are_recognised_for_drag_damping() {
        // 吸附状态起步才加阻尼
        assertTrue(PetState.isAttachedState(PetState.BORDER_R))
        assertTrue(PetState.isAttachedState(PetState.BORDER_L))
        assertTrue(PetState.isAttachedState(PetState.BORDER_R_UPRIGHT))
        assertTrue(PetState.isAttachedState(PetState.CORNER_BR))
        assertTrue(PetState.isAttachedState(PetState.CORNER_BL))
        // 上边组同样是"吸附状态"
        assertTrue(PetState.isAttachedState(PetState.CORNER_TR))
        assertTrue(PetState.isAttachedState(PetState.CORNER_TL))
        assertTrue(PetState.isAttachedState(PetState.EDGE_TOP_R))
        assertTrue(PetState.isAttachedState(PetState.EDGE_TOP_L))
    }

    // ---------------------------------------------------------- 吸附

    @Test
    fun A_snaps_to_the_corner_on_both_axes() {
        PetState.setZoneSize(200f, 365f)
        val r = PetState.snapPosition(
            PetState.VERT_BOTTOM,
            SW - 500f, SH - 500f, 400f, 400f, 50f, 50f, SW, SH, false
        )
        assertEquals(SW - 400f, r[0], 0.5f)
        assertEquals(SH - 400f, r[1], 0.5f)
    }

    @Test
    fun C_snaps_only_vertically_and_keeps_x() {
        PetState.setZoneSize(200f, 365f)
        val x0 = 500f
        val r = PetState.snapPosition(
            PetState.VERT_BOTTOM,
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
            PetState.VERT_BOTTOM,
            SW - 100f, y0, 300f, 400f, 1200f, 100f, SW, SH, false
        )
        assertEquals(SW - 300f, r[0], 0.5f)   // 贴侧边
        assertEquals(y0, r[1], 0.5f)          // y 不动
    }

    @Test
    fun E_snaps_to_the_nearer_edge() {
        PetState.setZoneSize(200f, 365f)
        val v = PetState.VERT_BOTTOM
        // 离底更近 -> 贴底
        val rb = PetState.snapPosition(v, 500f, 1000f, 300f, 400f, 600f, 900f, SW, SH, false)
        assertEquals(SH - 400f, rb[1], 0.5f)
        // 离侧边更近 -> 贴侧边
        val rs = PetState.snapPosition(v, 500f, 1000f, 300f, 400f, 900f, 600f, SW, SH, false)
        assertEquals(SW - 300f, rs[0], 0.5f)
    }

    @Test
    fun left_half_measures_distance_from_the_LEFT_edge() {
        val x = 50f
        assertEquals(50f, PetState.sideDist(x, 475f, SW, true), 0.5f)
        assertEquals(SW - (x + 475f), PetState.sideDist(x, 475f, SW, false), 0.5f)
    }

    // ============================================================ 上边吸附

    /**
     * 用户要求的三条上边吸附，逐条钉死：
     *   右上角 / 左上角 / 上边。
     */
    @Test
    fun the_three_top_attachments_the_user_asked_for() {
        PetState.setZoneSize(200f, 365f)
        val t = PetState.VERT_TOP

        // ---- 右上角：足迹贴 (SW - w, 0) ----
        val brRight = SW - TRACK_W
        assertEquals(
            "贴到右上角应是 A 区",
            PetState.ZONE_A, PetState.zoneOf(t, 0f, 0f, false)
        )
        assertEquals(PetState.CORNER_TR, PetState.stateForZone(t, PetState.ZONE_A, false))
        val snapTR = PetState.snapPosition(
            t, brRight - 5f, 5f, TRACK_W, TRACK_H, 0f, 0f, SW, SH, false
        )
        assertEquals(brRight, snapTR[0], 0.5f)
        assertEquals(0f, snapTR[1], 0.5f)

        // ---- 左上角：足迹贴 (0, 0)，且用镜像素材 ----
        assertEquals(PetState.CORNER_TL, PetState.stateForZone(t, PetState.ZONE_A, true))
        assertTrue("左上角必须是镜像的", PetState.isLeft(PetState.CORNER_TL))
        val snapTL = PetState.snapPosition(
            t, 5f, 5f, TRACK_W, TRACK_H, 0f, 0f, SW, SH, true
        )
        assertEquals(0f, snapTL[0], 0.5f)
        assertEquals(0f, snapTL[1], 0.5f)

        // ---- 上边：x 不贴侧边（dr 很大）-> E 区，贴上沿、保持 x ----
        val x0 = 500f
        val dr = PetState.sideDist(x0, TRACK_W, SW, false)
        assertEquals(PetState.ZONE_E, PetState.zoneOf(t, 0f, dr, false))
        assertEquals(PetState.EDGE_TOP_R, PetState.stateForZone(t, PetState.ZONE_E, false))
        val snapT = PetState.snapPosition(
            t, x0, 0f, TRACK_W, TRACK_H, 0f, dr, SW, SH, false
        )
        assertEquals(x0, snapT[0], 0.5f)   // x 完全不动
        assertEquals(0f, snapT[1], 0.5f)   // 贴上沿
    }

    /** 上边组的状态必须**上下翻转**，且**绝不旋转**；侧边形态绝不翻转 */
    @Test
    fun flipV_is_a_pure_function_of_state_and_never_touches_the_side_forms() {
        // 上角 / 上边 -> 翻转
        assertTrue(PetState.flipVFor(PetState.CORNER_TR))
        assertTrue(PetState.flipVFor(PetState.CORNER_TL))
        assertTrue(PetState.flipVFor(PetState.EDGE_TOP_R))
        assertTrue(PetState.flipVFor(PetState.EDGE_TOP_L))
        // 下角 / 下边 -> 不翻
        assertFalse(PetState.flipVFor(PetState.CORNER_BR))
        assertFalse(PetState.flipVFor(PetState.CORNER_BL))
        assertFalse(PetState.flipVFor(PetState.BORDER_R_UPRIGHT))
        assertFalse(PetState.flipVFor(PetState.BORDER_L_UPRIGHT))
        // 躺侧边的旋转形态**绝不**上下翻转（翻了头就朝错方向了）
        assertFalse(PetState.flipVFor(PetState.BORDER_R))
        assertFalse(PetState.flipVFor(PetState.BORDER_L))
        // 上边组一律不旋转
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_TR), 0.001f)
        assertEquals(0f, PetState.rotationForSnap(PetState.CORNER_TL), 0.001f)
        assertEquals(0f, PetState.rotationForSnap(PetState.EDGE_TOP_R), 0.001f)
        assertEquals(0f, PetState.rotationForSnap(PetState.EDGE_TOP_L), 0.001f)
        // 上边组用正立素材、上角用角落素材
        assertFalse(PetState.usesCornerArt(PetState.EDGE_TOP_R))
        assertFalse(PetState.usesCornerArt(PetState.EDGE_TOP_L))
        assertTrue(PetState.usesCornerArt(PetState.CORNER_TR))
        assertTrue(PetState.usesCornerArt(PetState.CORNER_TL))
    }

    /** 状态 -> 纵向归属：上角贴上沿、躺侧边无纵向基准、其余贴下沿 */
    @Test
    fun vertForState_says_which_horizontal_edge_a_state_hangs_on() {
        assertEquals(PetState.VERT_TOP, PetState.vertForState(PetState.CORNER_TR))
        assertEquals(PetState.VERT_TOP, PetState.vertForState(PetState.CORNER_TL))
        assertEquals(PetState.VERT_TOP, PetState.vertForState(PetState.EDGE_TOP_R))
        assertEquals(PetState.VERT_TOP, PetState.vertForState(PetState.EDGE_TOP_L))
        assertEquals(PetState.VERT_BOTTOM, PetState.vertForState(PetState.CORNER_BR))
        assertEquals(PetState.VERT_BOTTOM, PetState.vertForState(PetState.CORNER_BL))
        assertEquals(PetState.VERT_BOTTOM, PetState.vertForState(PetState.BORDER_R_UPRIGHT))
        assertEquals(PetState.VERT_BOTTOM, PetState.vertForState(PetState.BORDER_L_UPRIGHT))
        // 躺侧边：纵向自由，不挂任何水平边
        assertEquals(PetState.VERT_NONE, PetState.vertForState(PetState.BORDER_R))
        assertEquals(PetState.VERT_NONE, PetState.vertForState(PetState.BORDER_L))
    }

    /** 上边组 / 下边组的"是上边组吗"与翻转、基准三者必须一致 */
    @Test
    fun isTop_agrees_with_vertForState_and_flipVFor() {
        val all = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL, PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.CORNER_TR, PetState.CORNER_TL, PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )
        for (s in all) {
            val top = PetState.vertForState(s) == PetState.VERT_TOP
            assertEquals("状态 $s 的 isTop 与 vertForState 不一致", top, PetState.isTop(s))
            assertEquals("状态 $s 的 flipVFor 与 isTop 不一致", top, PetState.flipVFor(s))
        }
    }

    /**
     * **对称性的核心测试**：上半屏的判定结果，必须正好是下半屏结果沿水平轴的镜像。
     *
     * 对同一个"离自己那条水平边的距离 d"，下边组取 y = SH - h - d、上边组取 y = d，
     * 两者应当给出：同一个区号、同一组吸附边、互为镜像的状态、
     * 相同的旋转角，以及镜像的吸附位置。
     */
    @Test
    fun the_top_band_is_the_exact_vertical_mirror_of_the_bottom_edge() {
        PetState.setZoneSize(200f, 365f)
        val w = TRACK_W
        val h = TRACK_H
        var checked = 0
        // ⚠️ d 只能取到上边带以内（240）：带外不再是上边组了
        for (d in intArrayOf(
            0, 1, 30, 100, 150, 200, 239,
            (PetState.TOP_BAND_PX - 1f).toInt(), PetState.TOP_BAND_PX.toInt()
        )) {
            for (x in intArrayOf(0, 60, 200, 300, 500, 900, 1100, 899)) {
                for (leftHalf in booleanArrayOf(false, true)) {
                    val yB = SH - h - d          // 下边组：离下沿 d
                    val yT = d                   // 上边组：离上沿 d
                    val dr = PetState.sideDist(x.toFloat(), w, SW, leftHalf)

                    val dB = PetState.bottomDist(yB.toFloat(), h, SH)
                    val dT = PetState.topDist(yT.toFloat(), h, SH)
                    assertEquals("镜像距离必须相等", dB, dT, 0.001f)

                    val vB = PetState.vertOf(yB.toFloat(), h, SH)
                    val vT = PetState.vertOf(yT.toFloat(), h, SH)
                    assertEquals(PetState.VERT_BOTTOM, vB)
                    assertEquals("d=$d 在带内应判上边组", PetState.VERT_TOP, vT)

                    val dvB = PetState.vertDistFor(vB, yB.toFloat(), h, SH)
                    val dvT = PetState.vertDistFor(vT, yT.toFloat(), h, SH)
                    assertEquals(dvB, dvT, 0.001f)

                    val zB = PetState.zoneOf(vB, dvB, dr, leftHalf)
                    val zT = PetState.zoneOf(vT, dvT, dr, leftHalf)
                    assertEquals("d=$d x=$x left=$leftHalf 的分区不镜像", zB, zT)

                    val eB = PetState.snapEdgesForZone(vB, zB, dvB, dr)
                    val eT = PetState.snapEdgesForZone(vT, zT, dvT, dr)
                    assertEquals(
                        "d=$d x=$x left=$leftHalf 的吸附边不镜像",
                        eB.toList(), eT.toList()
                    )

                    val sB = PetState.stateForZone(vB, zB, leftHalf)
                    val sT = PetState.stateForZone(vT, zT, leftHalf)
                    assertEquals(
                        "d=$d x=$x left=$leftHalf 的状态不镜像",
                        mirrorState(sB), sT
                    )
                    assertEquals(
                        "flipV 必须与 isTop 一致",
                        PetState.isTop(sT), PetState.flipVFor(sT)
                    )
                    // 带内的上边组一定是"上角 / 贴上沿"，都要上下翻转
                    assertTrue("d=$d x=$x 上边组状态必须翻转素材", PetState.flipVFor(sT))
                    assertEquals(
                        "上下两组的旋转角必须一致",
                        PetState.rotationForSnap(sB), PetState.rotationForSnap(sT), 0.001f
                    )

                    val pB = PetState.snapPosition(
                        vB, x.toFloat(), yB.toFloat(), w, h, dvB, dr, SW, SH, leftHalf, zB
                    )
                    val pT = PetState.snapPosition(
                        vT, x.toFloat(), yT.toFloat(), w, h, dvT, dr, SW, SH, leftHalf, zT
                    )
                    assertEquals("吸附后 x 必须相同", pB[0], pT[0], 0.5f)
                    // 上边组吸附后的 y，等于下边组吸附后那一条边的镜像
                    assertEquals(
                        "d=$d x=$x left=$leftHalf 的吸附位置不镜像",
                        SH - (pB[1] + h), pT[1], 0.5f
                    )
                    checked++
                }
            }
        }
        assertTrue("用例数异常", checked > 100)
    }

    /**
     * **用户最新规则**：上边带以外（离上沿 > 240），一切都跟下边组一模一样 ——
     * 素材不上翻、状态不会用上角/贴上沿、吸附也不贴上沿。
     *
     * 这条把"上方其余区域跟下面保持一致"逐点钉死：整片上半屏（带外）都拿
     * 改动前的下边组参考实现来对照。
     */
    @Test
    fun outside_the_top_band_everything_matches_the_bottom_group() {
        PetState.setZoneSize(200f, 365f)
        val w = TRACK_W
        val h = TRACK_H
        var checked = 0
        // 上边带边界之外的整片上方区域，一直扫到屏幕底部
        for (d in intArrayOf(265, 300, 400, 600, 900, 1200, 1500, 2000, 2400, 2621)) {
            for (x in intArrayOf(0, 60, 200, 300, 500, 720, 900, 1100, 1180)) {
                for (leftHalf in booleanArrayOf(false, true)) {
                    val y = d.toFloat()          // 离上沿 d（带外）
                    val dr = PetState.sideDist(x.toFloat(), w, SW, leftHalf)

                    val v = PetState.vertOf(y, h, SH)
                    assertEquals("d=$d 带外必须按下边组算", PetState.VERT_BOTTOM, v)

                    // 距离语义必须完全等同于"离下沿的距离"
                    val db = PetState.bottomDist(y, h, SH)
                    assertEquals(
                        "d=$d 带外的垂直距离必须还是 bottomDist",
                        db, PetState.vertDistFor(v, y, h, SH), 0.001f
                    )

                    // 分区 / 吸附边 / 状态 / 吸附位置全部与改动前的下边组实现一致
                    val zone = oldZoneOf(db, dr)
                    assertEquals("d=$d x=$x 分区变了", zone, PetState.zoneOf(v, db, dr, leftHalf))
                    assertEquals(
                        "d=$d x=$x 吸附边变了",
                        oldSnapEdges(zone, db, dr),
                        PetState.snapEdgesForZone(v, zone, db, dr).toList()
                    )
                    val st = PetState.stateForZone(v, zone, leftHalf)
                    assertEquals("d=$d x=$x 状态变了", oldStateForZone(zone, leftHalf), st)
                    // 关键：带外**绝不**出现倒挂形态
                    assertFalse("d=$d x=$x 带外不该上下翻转", PetState.flipVFor(st))
                    assertFalse("d=$d x=$x 带外不该用上角/贴上沿", PetState.isTop(st))

                    val eOld = oldSnapEdges(zone, db, dr)
                    val px = PetState.snapPosition(
                        v, x.toFloat(), y, w, h, db, dr, SW, SH, leftHalf, zone
                    )
                    assertEquals(
                        "d=$d x=$x 吸附后 y 变了",
                        if (eOld[0] == 1) SH - h else y,
                        px[1], 0.5f
                    )
                    checked++
                }
            }
        }
        assertEquals("用例数应为 10×9×2", 10 * 9 * 2, checked)
    }

    /**
     * 上边带里贴着侧边时判的是**上角**（角落素材、头朝下），
     * 带外贴着侧边才轮到**侧边旋转区**（躺倒）；而躺倒形态永远不翻转。
     */
    @Test
    fun the_side_rotation_zone_never_flips_even_inside_the_top_band() {
        PetState.setZoneSize(200f, 365f)
        // 带内 + 贴着右边 -> 右上角（角落素材、翻转、不旋转）
        val inBandV = PetState.vertOf(100f, TRACK_H, SH)
        assertEquals(PetState.VERT_TOP, inBandV)
        val corner = PetState.stateForZone(inBandV, PetState.ZONE_A, false)
        assertEquals(PetState.CORNER_TR, corner)
        assertTrue(PetState.flipVFor(corner))
        assertEquals(0f, PetState.rotationForSnap(corner), 0.001f)

        // 带外 + 贴着右边 -> 侧边带（躺倒、旋转、不翻转）
        val outBandV = PetState.vertOf(400f, TRACK_H, SH)
        assertEquals(PetState.VERT_BOTTOM, outBandV)
        val side = PetState.stateForZone(outBandV, PetState.ZONE_B, false)
        assertEquals(PetState.BORDER_R, side)
        assertFalse("躺倒形态永远不翻转", PetState.flipVFor(side))
        assertEquals(-90f, PetState.rotationForSnap(side), 0.001f)
    }

    /**
     * 上边带边界必须**滞回**：在 240px 附近来回一两个像素不能把素材翻过来翻过去。
     *
     * 滞回是**单向**的：带内（<= 240）**永远**是上边组 —— 用户给的是几何规则
     * "离上沿 240px 以内就是头朝下"，所以进带立刻倒挂；而退出要越出
     * 240 + [PetState.VERT_HYSTERESIS_PX] 才生效，避免在边界上抖动。
     */
    @Test
    fun the_top_band_edge_has_hysteresis_so_the_art_cannot_flicker() {
        val band = PetState.TOP_BAND_PX
        val hys = PetState.VERT_HYSTERESIS_PX

        // 没有上一次时：正好在边界上算带内（<= 的约定），出去一点就不算
        assertEquals(PetState.VERT_TOP, PetState.vertOf(band, TRACK_H, SH, PetState.VERT_NONE))
        assertEquals(PetState.VERT_TOP, PetState.vertOf(band - 1f, TRACK_H, SH))
        assertEquals(PetState.VERT_BOTTOM, PetState.vertOf(band + 1f, TRACK_H, SH))

        // **带内一定是上边组**：与上一次是什么无关（规则是几何的）
        run {
            var d = 0f
            while (d <= band) {
                assertEquals(
                    "离上沿 $d 在带内，必须头朝下",
                    PetState.VERT_TOP,
                    PetState.vertOf(d, TRACK_H, SH, PetState.VERT_BOTTOM)
                )
                assertEquals(
                    "离上沿 $d 在带内，必须头朝下",
                    PetState.VERT_TOP,
                    PetState.vertOf(d, TRACK_H, SH, PetState.VERT_NONE)
                )
                d += 7f
            }
        }

        // 已经在上边组：刚出界还保持上边组（不到 边界+滞回 不换）
        assertEquals(
            PetState.VERT_TOP,
            PetState.vertOf(band + hys / 2f, TRACK_H, SH, PetState.VERT_TOP)
        )
        assertEquals(
            PetState.VERT_TOP,
            PetState.vertOf(band + hys, TRACK_H, SH, PetState.VERT_TOP)
        )
        // 明显越界 -> 换组
        assertEquals(
            PetState.VERT_BOTTOM,
            PetState.vertOf(band + hys + 2f, TRACK_H, SH, PetState.VERT_TOP)
        )
        // 带外（且上一次是下边组）始终是下边组
        assertEquals(
            PetState.VERT_BOTTOM,
            PetState.vertOf(band + 1f, TRACK_H, SH, PetState.VERT_BOTTOM)
        )
        assertEquals(
            PetState.VERT_BOTTOM,
            PetState.vertOf(band + 400f, TRACK_H, SH, PetState.VERT_BOTTOM)
        )
    }

    /** 没有纵向基准时没有"角"可贴：A 区退化成只贴侧边，不会莫名其妙吸到某条水平边 */
    @Test
    fun zone_A_degrades_gracefully_without_a_vertical_reference() {
        assertEquals(
            listOf(0, 1),
            PetState.snapEdgesForZone(PetState.VERT_NONE, PetState.ZONE_A, 0f, 0f).toList()
        )
        // 有基准时仍然是"两条边都贴"
        assertEquals(
            listOf(1, 1),
            PetState.snapEdgesForZone(PetState.VERT_TOP, PetState.ZONE_A, 0f, 0f).toList()
        )
        assertEquals(
            listOf(1, 1),
            PetState.snapEdgesForZone(PetState.VERT_BOTTOM, PetState.ZONE_A, 0f, 0f).toList()
        )
        // VERT_NONE 时 A 区判定本身也不会成立
        assertEquals(PetState.ZONE_E, PetState.zoneOf(PetState.VERT_NONE, 0f, 500f, false))
        assertEquals(PetState.ZONE_B, PetState.zoneOf(PetState.VERT_NONE, 0f, 100f, false))
    }

    // ============================================================ 下边组回归

    /**
     * **下边组行为与改动前逐项一致**。
     *
     * 下面这几个 `old*` 函数是**逐字照抄改动前实现**的参考版本，
     * 全部建立在"离下沿的距离 db"上。测试拿它们与新版（走 VERT_BOTTOM）
     * 在整片区域上逐点对比 —— 新增上边吸附若碰坏了任何一处下边组语义，
     * 这里会立刻失败（上次的失败正是"改了距离却不同步改判定规则"）。
     */
    private fun oldZoneOf(db: Float, dr: Float): Int {
        if (db <= PetState.currentAH && dr <= PetState.currentAW) return PetState.ZONE_A
        if (dr <= PetState.currentAW) return PetState.ZONE_B
        return PetState.ZONE_E
    }

    private fun oldZoneWithHysteresis(
        db: Float, dr: Float, prev: Int, centerDist: Float, bandPx: Float
    ): Int {
        if (db <= PetState.currentAH && dr <= PetState.currentAW) return PetState.ZONE_A
        val inB =
            if (prev == PetState.ZONE_B) centerDist <= bandPx + PetState.HYSTERESIS_PX
            else centerDist <= bandPx
        return if (inB) PetState.ZONE_B else PetState.ZONE_E
    }

    private fun oldSnapEdges(zone: Int, db: Float, dr: Float): List<Int> = when (zone) {
        PetState.ZONE_A -> listOf(1, 1)
        PetState.ZONE_C -> listOf(1, 0)
        PetState.ZONE_B -> listOf(0, 1)
        else -> if (db <= dr) listOf(1, 0) else listOf(0, 1)
    }

    private fun oldStateForZone(zone: Int, leftHalf: Boolean): Int = when (zone) {
        PetState.ZONE_A -> if (leftHalf) PetState.CORNER_BL else PetState.CORNER_BR
        PetState.ZONE_B -> if (leftHalf) PetState.BORDER_L else PetState.BORDER_R
        else -> if (leftHalf) PetState.BORDER_L_UPRIGHT else PetState.BORDER_R_UPRIGHT
    }

    /** 下边组的纵向归属恒为 VERT_BOTTOM，且量出来的距离就是 bottomDist */
    @Test
    fun bottom_half_always_uses_the_bottom_edge_as_its_reference() {
        for (db in intArrayOf(0, 1, 100, 1300)) {
            val y = SH - TRACK_H - db
            val v = PetState.vertOf(y, TRACK_H, SH, PetState.VERT_NONE)
            assertEquals("y=$y 应属下边组", PetState.VERT_BOTTOM, v)
            assertEquals(
                "下边组的垂直距离必须还是 bottomDist",
                PetState.bottomDist(y, TRACK_H, SH),
                PetState.vertDistFor(v, y, TRACK_H, SH),
                0.001f
            )
        }
    }

    /** 下边组：分区 / 吸附边 / 状态 / 吸附位置，逐点与改动前实现对比 */
    @Test
    fun bottom_group_still_matches_the_old_rules() {
        PetState.setZoneSize(200f, 365f)
        val v = PetState.VERT_BOTTOM
        val w = TRACK_W
        val h = TRACK_H
        var checked = 0
        // db 覆盖 A 区内/边界/带内、dr 覆盖 B 内外、左右半屏都过一遍
        for (db in intArrayOf(0, 1, 100, 364, 365, 366, 500, 900, 1500)) {
            for (dr in intArrayOf(0, 100, 199, 200, 201, 300, 900, 1400)) {
                for (leftHalf in booleanArrayOf(false, true)) {
                    val dbf = db.toFloat()
                    val drf = dr.toFloat()

                    assertEquals(
                        "db=$db dr=$dr 分区变了",
                        oldZoneOf(dbf, drf), PetState.zoneOf(v, dbf, drf, leftHalf)
                    )

                    val zone = oldZoneOf(dbf, drf)
                    assertEquals(
                        "db=$db dr=$dr 带滞回的分区变了",
                        oldZoneWithHysteresis(dbf, drf, PetState.ZONE_E, drf, 470f),
                        PetState.zoneWithHysteresis(
                            v, dbf, drf, leftHalf, PetState.ZONE_E, drf, 470f
                        )
                    )
                    assertEquals(
                        "db=$db dr=$dr 滞回（prev=B）变了",
                        oldZoneWithHysteresis(dbf, drf, PetState.ZONE_B, drf, 470f),
                        PetState.zoneWithHysteresis(
                            v, dbf, drf, leftHalf, PetState.ZONE_B, drf, 470f
                        )
                    )

                    assertEquals(
                        "db=$db dr=$dr 吸附边变了",
                        oldSnapEdges(zone, dbf, drf),
                        PetState.snapEdgesForZone(v, zone, dbf, drf).toList()
                    )
                    assertEquals(
                        "db=$db dr=$dr 状态变了",
                        oldStateForZone(zone, leftHalf),
                        PetState.stateForZone(v, zone, leftHalf)
                    )

                    // 吸附位置：足迹盒左上角（期望值由参考实现给出的"贴哪条边"推出）
                    val x = 300f
                    val y = SH - h - db
                    val eOld = oldSnapEdges(zone, dbf, drf)
                    val px = PetState.snapPosition(
                        v, x, y, w, h, dbf, drf, SW, SH, leftHalf, zone
                    )
                    assertEquals(
                        "db=$db dr=$dr 吸附后 x 变了",
                        if (eOld[1] == 1) (if (leftHalf) 0f else SW - w) else x,
                        px[0], 0.5f
                    )
                    assertEquals(
                        "db=$db dr=$dr 吸附后 y 变了",
                        if (eOld[0] == 1) SH - h else y,
                        px[1], 0.5f
                    )
                    checked++
                }
            }
        }
        assertEquals("用例数应为 9×8×2", 9 * 8 * 2, checked)
    }

    /** 下边组的四个稳定形态与素材选择，逐项钉死（改动前的期望值） */
    @Test
    fun bottom_group_states_are_pinned_to_their_old_values() {
        // 右下角 / 左下角：角落素材、不旋转、不翻转
        assertEquals(0, PetState.CORNER_BR)
        assertEquals(1, PetState.CORNER_BL)
        assertTrue(PetState.usesCornerArt(PetState.CORNER_BR))
        assertFalse(PetState.isLeft(PetState.CORNER_BR))
        assertTrue(PetState.isLeft(PetState.CORNER_BL))
        // 贴右边框 / 左边框：旋转形态
        assertEquals(2, PetState.BORDER_R)
        assertEquals(3, PetState.BORDER_L)
        assertEquals(-90f, PetState.rotationForSnap(PetState.BORDER_R), 0.001f)
        assertEquals(90f, PetState.rotationForSnap(PetState.BORDER_L), 0.001f)
        // 贴底正立
        assertEquals(5, PetState.BORDER_R_UPRIGHT)
        assertEquals(6, PetState.BORDER_L_UPRIGHT)
        assertTrue(PetState.isUprightVariant(PetState.BORDER_R_UPRIGHT))
        assertTrue(PetState.isZoneless(PetState.BORDER_L_UPRIGHT))
        assertFalse(PetState.isZoneless(PetState.CORNER_BR))
        // 下边组的状态编号一个都没动（改上边吸附只允许**新增**编号）
        assertEquals(7, PetState.CORNER_TR)
        assertEquals(8, PetState.CORNER_TL)
        // 下边组的镜像规则不变
        assertTrue(PetState.needsMirror(PetState.CORNER_BL))
        assertFalse(PetState.needsMirror(PetState.CORNER_BR))
        assertTrue(PetState.needsMirror(PetState.BORDER_L_UPRIGHT))
        assertFalse(PetState.needsMirror(PetState.BORDER_R_UPRIGHT))
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
        // 贴上沿（y=0）不会被夹走
        val top = PetState.clampToScreen(400f, 0f, 400f, 500f, SW, SH)
        assertEquals(0f, top[1], 0.01f)
    }

    /** 上边组的状态必须是**新增**编号，不能与下边组撞号（撞号会让持久化读回错误形态） */
    @Test
    fun top_states_do_not_collide_with_bottom_states() {
        val all = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL, PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.CORNER_TR, PetState.CORNER_TL, PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )
        assertEquals("状态编号不允许重复", all.size, all.toSet().size)
        assertEquals(PetState.EDGE_TOP_R, 9)
        assertEquals(PetState.EDGE_TOP_L, 10)
    }

    /** 上下两组的状态必须能互相镜像成对（渲染与吸附都依赖这个对应关系） */
    private fun mirrorState(s: Int): Int = when (s) {
        PetState.CORNER_BR -> PetState.CORNER_TR
        PetState.CORNER_BL -> PetState.CORNER_TL
        PetState.BORDER_R_UPRIGHT -> PetState.EDGE_TOP_R
        PetState.BORDER_L_UPRIGHT -> PetState.EDGE_TOP_L
        // 躺侧边的旋转形态上下一致（纵向自由，与贴哪条水平边无关）
        else -> s
    }
}
