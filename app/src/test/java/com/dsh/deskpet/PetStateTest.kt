package com.dsh.deskpet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun hysteresis_stops_the_B_E_boundary_from_flickering() {        PetState.setZoneSize(200f, 365f)
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

    /**
     * **角落（A 区）边界也要滞回**：
     * A 与 B/E 的分界就是 `dr == aW`，贴着角落拖时手指抖一两像素，
     * 就会在"角落素材 <-> 正立素材"之间来回切（两套图宽度都不同，约 263 vs 289），
     * 看起来就是闪（用户反馈："切换角落素材与非角落素材时会有闪烁现象"）。
     */
    @Test
    fun the_corner_boundary_has_hysteresis_so_the_art_cannot_flicker() {
        PetState.setZoneSize(200f, 365f)
        val v = PetState.VERT_BOTTOM
        val aW = PetState.currentAW
        val aH = PetState.currentAH
        val hys = PetState.HYSTERESIS_PX

        // 横向边界：刚出界一点点
        val justOut = aW + hys / 2f
        assertEquals(
            "已在角落：出界一点点必须仍是 A（否则素材会来回闪）",
            PetState.ZONE_A,
            PetState.zoneWithHysteresis(v, 0f, justOut, false, PetState.ZONE_A, justOut, 400f)
        )
        // 原本不在角落：同一个位置不该被判成 A（深入才进）
        assertFalse(
            "不在角落：这个位置不该突然进 A",
            PetState.zoneWithHysteresis(v, 0f, justOut, false, PetState.ZONE_E, justOut, 400f)
                == PetState.ZONE_A
        )
        // 纵向边界同理
        val justOutV = aH + hys / 2f
        assertEquals(
            PetState.ZONE_A,
            PetState.zoneWithHysteresis(v, justOutV, 100f, false, PetState.ZONE_A)
        )
        assertFalse(
            PetState.zoneWithHysteresis(v, justOutV, 100f, false, PetState.ZONE_E) == PetState.ZONE_A
        )

        // 明确离开 -> 一定退出 A（否则会"粘"在角落）
        assertFalse(
            PetState.zoneWithHysteresis(
                v, 0f, aW + hys + 2f, false, PetState.ZONE_A, aW + hys + 2f, 400f
            ) == PetState.ZONE_A
        )
        // 明确深入 -> 一定进 A
        assertEquals(
            PetState.ZONE_A,
            PetState.zoneWithHysteresis(v, 0f, aW - 10f, false, PetState.ZONE_E)
        )

        // 在边界上来回抖（±3px）不允许换区：这是"闪"的直接检验
        var zone = PetState.ZONE_A
        for (jitter in intArrayOf(-3, 3, -3, 3, -2, 2, -3, 3)) {
            val dr = aW + jitter
            zone = PetState.zoneWithHysteresis(v, 0f, dr, false, zone, dr, 400f)
            assertEquals("抖动到 dr=$dr 时不该换出 A（否则素材闪）", PetState.ZONE_A, zone)
        }
    }

    /**
     * 呼出回弹要"弹一下"：中途必须荡到落点**另一侧**，最后精确落在落点上。
     *
     * 用户反馈："呼出的回弹动画是线性的吗？感觉很生硬。能不能做出弹了一下的感觉？"
     */
    @Test
    fun the_summon_bounce_actually_swings_past_the_target() {
        // 起点 = 过冲量（正好在过冲点上），终点 = 0（精确落位，不留缝）
        assertEquals(1f, PetState.springBack(0f), 0.0001f)
        assertEquals(0f, PetState.springBack(1f), 0.0001f)
        // 超出范围也要夹住（t 可能因掉帧略大于 1）
        assertEquals(1f, PetState.springBack(-0.5f), 0.0001f)
        assertEquals(0f, PetState.springBack(1.5f), 0.0001f)

        var deepest = 0f
        var crossings = 0
        var prev = PetState.springBack(0f)
        // 只扫到 u<1：u=1 按定义就是 0（落点），不该算成"穿过"
        for (i in 1..400) {
            val u = i / 401f
            val v = PetState.springBack(u)
            // 摆动幅度绝不允许超过过冲量本身（否则会甩出去）
            assertTrue("u=$u 时 |springBack|=$v 超过 1", abs(v) <= 1.0001f)
            if (v < deepest) deepest = v
            if (prev > 0f && v <= 0f) crossings++
            prev = v
        }
        // 只穿一次零：一次干脆的"弹"，不是抖
        assertEquals("应该只穿过落点一次", 1, crossings)
        // 荡到另一侧要有可见幅度（约 -0.39），太小就看不出弹
        assertTrue("另一侧幅度太小：$deepest", deepest < -0.3f)
        assertTrue("另一侧幅度过大：$deepest", deepest > -0.55f)
        // 摆回来那一段必须一路贴着 0 收（不能再翻正，否则尾巴会抖）
        for (i in 330..399) {
            assertTrue(
                "尾巴上又摆回正侧了：u=${i / 400f}",
                PetState.springBack(i / 400f) <= 0.0001f
            )
        }

        // 曲线要连续：相邻采样不允许跳变（跳变会表现为画面一顿）
        var last = PetState.springBack(0f)
        for (i in 1..400) {
            val v = PetState.springBack(i / 400f)
            assertTrue("曲线跳变", abs(v - last) < 0.02f)
            last = v
        }
    }

    /**
     * 进场缓动：起步最快、之后一路减速（"冲进来然后刹住"），
     * 且两端精确（0 -> 1），否则要么飞进来时有偏差、要么最后落不到位。
     */
    @Test
    fun the_arrival_easing_starts_fast_and_lands_exactly() {
        assertEquals(0f, PetState.easeInArrive(0f), 0.0001f)
        assertEquals(1f, PetState.easeInArrive(1f), 0.0001f)
        // 单调不减
        var last = -1f
        for (i in 0..200) {
            val v = PetState.easeInArrive(i / 200f)
            assertTrue("进场缓动不单调", v >= last - 0.0001f)
            last = v
        }
        // 前 10% 就该走掉近三成行程（smoothstep 只有 2.8%），这才叫"冲进来"
        assertTrue(
            "起步太慢：${PetState.easeInArrive(0.1f)}",
            PetState.easeInArrive(0.1f) > 0.25f
        )
        // 中段速度必须明显低于 smoothstep（否则又是匀速平移的观感）
        val midArrive = PetState.easeInArrive(0.7f) - PetState.easeInArrive(0.3f)
        val midSmooth = PetState.smoothstep(0.7f) - PetState.smoothstep(0.3f)
        assertTrue("进场中段仍然接近匀速：$midArrive vs $midSmooth", midArrive < midSmooth * 0.7f)
        // 收起那条保持原样（慢慢平移）
        assertEquals(0f, PetState.smoothstep(0f), 0.0001f)
        assertEquals(1f, PetState.smoothstep(1f), 0.0001f)
        assertEquals(0.5f, PetState.smoothstep(0.5f), 0.0001f)
    }

    /**
     * **四个角落的可互动面积不缩**（保持整框），其余状态缩到 75%。
     *
     * 用户要求："吸附至四个角落时的面积不要改动，只改动没有吸附到四个角落情况的"。
     */
    @Test
    fun only_non_corner_states_get_the_smaller_hit_box() {
        val corners = intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.CORNER_TR, PetState.CORNER_TL
        )
        for (c in corners) {
            assertTrue("状态 $c 应该被认成角落", PetState.isCorner(c))
            assertEquals("角落 $c 不许缩", 1f, PetState.hitBoxScaleFor(c), 0.0001f)
            // 角落用的框必须与原框逐值一致
            val b = intArrayOf(10, 20, 210, 220)
            val box = PetState.hitBox(b, PetState.hitBoxScaleFor(c))
            assertEquals(10f, box[0], 0.001f)
            assertEquals(20f, box[1], 0.001f)
            assertEquals(210f, box[2], 0.001f)
            assertEquals(220f, box[3], 0.001f)
        }

        // 非角落：贴边、正立变体、上边，全部缩到 75%
        val others = intArrayOf(
            PetState.BORDER_R, PetState.BORDER_L,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )
        for (s in others) {
            assertFalse("状态 $s 不该被认成角落", PetState.isCorner(s))
            assertEquals("$s 应该缩到 75%", 0.75f, PetState.hitBoxScaleFor(s), 0.0001f)
            val box = PetState.hitBox(intArrayOf(10, 20, 210, 220), PetState.hitBoxScaleFor(s))
            assertEquals(150f, box[2] - box[0], 0.001f)
        }
    }

    /**
     * 可互动面积 = 素材 alpha 外接框的 75%（中心不变、等比缩小）。
     *
     * 用户要求："感觉可互动面积还是有点大，互动面积等比缩小到现在的 75%。"
     */
    @Test
    fun the_hit_box_shrinks_to_75_percent_around_its_centre() {
        // 典型角落素材的外接框（缩放后的取样位图：288x270 -> 216x202 之类）
        val b = intArrayOf(10, 20, 210, 220) // 200 x 200
        val box = PetState.hitBox(b, 0.75f)
        // 中心不变
        assertEquals(110f, (box[0] + box[2]) / 2f, 0.001f)
        assertEquals(120f, (box[1] + box[3]) / 2f, 0.001f)
        // 宽高各是原来的 75%
        assertEquals(150f, box[2] - box[0], 0.001f)
        assertEquals(150f, box[3] - box[1], 0.001f)
        // 原框的角落在新框之外（这正是"面积变小"的部分）
        assertTrue(10f < box[0] && 20f < box[1])
        assertTrue(210f > box[2] && 220f > box[3])
        // 中心仍在框内
        assertTrue(110f >= box[0] && 110f < box[2])
        assertTrue(120f >= box[1] && 120f < box[3])
        // 新框边缘内侧一点点：命中；外侧一点点：不命中
        assertTrue(box[0] + 1f >= box[0] && box[0] + 1f < box[2])
        assertFalse(box[0] - 1f >= box[0])
        // scale = 1 时与原框一致
        val same = PetState.hitBox(b, 1f)
        assertEquals(10f, same[0], 0.001f)
        assertEquals(20f, same[1], 0.001f)
        assertEquals(210f, same[2], 0.001f)
        assertEquals(220f, same[3], 0.001f)
        // 非正方框同样等比（宽高比不变）
        val r = PetState.hitBox(intArrayOf(0, 0, 400, 100), 0.75f)
        assertEquals(300f, r[2] - r[0], 0.001f)
        assertEquals(75f, r[3] - r[1], 0.001f)
        // scale 夹在 [0.05, 1]：不会缩成 0，也不会放大
        val clamped = PetState.hitBox(b, 5f)
        assertEquals(200f, clamped[2] - clamped[0], 0.001f)
        assertTrue(PetState.hitBox(b, 0f)[2] - PetState.hitBox(b, 0f)[0] > 0f)
    }

    /**
     * 呼出的过冲量要**按行程放大**，否则长行程下那点过冲完全看不见。
     *
     * 靠边吸附的行程短（几十像素），36px 过冲占 24%~90%，所以很"Q弹"；
     * 呼出从屏幕外滑回来（300~900px），同样的 36px 只占 4%~11%，看着就不弹。
     * 用户要求："把呼出动画做成靠边吸附动画那种 Q 弹的"。
     */
    @Test
    fun summon_overshoot_scales_with_the_travel_distance() {
        val base = 36f
        // 短行程（靠边吸附 / 连点滑回）：原样，不做任何放大 —— 手感与吸附一致
        assertEquals(base, PetState.bounceAmp(40f, base), 0.001f)
        assertEquals(base, PetState.bounceAmp(80f, base), 0.001f)
        assertEquals(base, PetState.bounceAmp(163f, base), 0.001f) // 163*0.22 = 36
        // 长行程（呼出）：放大，最多 2 倍
        assertEquals(59.4f, PetState.bounceAmp(270f, base), 0.01f)
        assertEquals(2f * base, PetState.bounceAmp(330f, base), 0.001f)
        assertEquals(2f * base, PetState.bounceAmp(900f, base), 0.001f)
        // 单调不减、且有上界
        var last = 0f
        for (i in 0..400) {
            val v = PetState.bounceAmp(i * 10f, base)
            assertTrue("不该回落", v >= last - 0.001f)
            assertTrue("超过 2 倍上界", v <= 2f * base + 0.001f)
            last = v
        }
        // 关掉回弹（base = 0）或没有行程时必须是 0，不能凭空放大
        assertEquals(0f, PetState.bounceAmp(500f, 0f), 0.001f)
        assertEquals(0f, PetState.bounceAmp(0f, base), 0.001f)
        // 用户把幅度调大时，上界跟着一起放大（滑块依然有效）
        assertEquals(180f, PetState.bounceAmp(900f, 90f), 0.001f)
    }

    /**
     * 收起方向 = **最近的那条边**，而且任何时候都必须给出正确方向。
     *
     * 回归用例：旋转冻结期间 `trackW/trackH` 还是旧形态的值，足迹宽度会差 42px、
     * 右边缘因此离墙 21px —— 原来的实现要求"误差 2px 以内"，四条边全不匹配，
     * 就掉进写死的兜底 `(0,1)`，贴右边框的人物于是**竖着往下滑出屏幕**
     * （用户反馈："跑到右下角跑出屏幕了"）。
     */
    @Test
    fun exit_direction_is_always_the_nearest_edge() {
        val w = 1440f
        val h = 3084f

        // 贴右边框（上 1/3，足迹 247x289）：往右
        var v = PetState.exitDir(1193f, 885f, 1440f, 1174f, w, h)
        assertEquals(1f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)

        // ★ 离墙 21px（冻结期宽度算错导致的错配）：**仍然往右**，绝不能往下
        v = PetState.exitDir(1193f, 885f, 1440f - 21f, 1174f, w, h)
        assertEquals("离墙 21px 也必须从右边出去", 1f, v[0], 0.001f)
        assertEquals("绝不能往下滑", 0f, v[1], 0.001f)

        // 更进一步：差得更多也一样（45px）
        v = PetState.exitDir(1150f, 885f, 1440f - 45f, 1174f, w, h)
        assertEquals(1f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)

        // 贴左边框：往左
        v = PetState.exitDir(0f, 885f, 247f, 1174f, w, h)
        assertEquals(-1f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)

        // 贴底（正立/倒挂都一样）：往下
        v = PetState.exitDir(575f, 2837f, 864f, 3084f, w, h)
        assertEquals(0f, v[0], 0.001f)
        assertEquals(1f, v[1], 0.001f)

        // 贴上沿：往上
        v = PetState.exitDir(575f, 0f, 864f, 247f, w, h)
        assertEquals(0f, v[0], 0.001f)
        assertEquals(-1f, v[1], 0.001f)

        // 右下角（两条边都贴）平手 -> 往下，与旧行为一致
        v = PetState.exitDir(1177f, 2837f, 1440f, 3084f, w, h)
        assertEquals(0f, v[0], 0.001f)
        assertEquals(1f, v[1], 0.001f)

        // 压出屏幕（负距离）也取"压得最多"的那条边
        v = PetState.exitDir(1400f, 885f, 1647f, 1174f, w, h)
        assertEquals(1f, v[0], 0.001f)
        assertEquals(0f, v[1], 0.001f)

        // 悬空（四条边都是正距离）：取最近的那条，绝不会是"往下"的兜底
        v = PetState.exitDir(600f, 100f, 889f, 347f, w, h)
        assertEquals("悬空时取最近边（上沿）", 0f, v[0], 0.001f)
        assertEquals(-1f, v[1], 0.001f)
    }

    /**
     * 压缩/弹起的**吸附边**判定：四条边各自对应哪些状态。
     *
     * 用户先要求"只针对底边和左右下角，底边不动仅上边向下压缩"，
     * 随后要求"把底边的单击和长按动画复制到其他三边，记得旋转对应角度"——
     * 于是判据从"是不是底边组"泛化成"贴的是哪条屏幕边"。
     */
    @Test
    fun every_state_knows_which_screen_edge_it_is_attached_to() {
        // 下边：两个下角 + 贴下沿的两个正立形态
        for (s in intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT
        )) {
            assertEquals("状态 $s 应贴下边", PetState.ATTACH_BOTTOM, PetState.attachEdgeOf(s))
        }
        // 上边：两个上角 + 贴上沿的两个正立形态
        for (s in intArrayOf(
            PetState.CORNER_TR, PetState.CORNER_TL, PetState.EDGE_TOP_R, PetState.EDGE_TOP_L
        )) {
            assertEquals("状态 $s 应贴上边", PetState.ATTACH_TOP, PetState.attachEdgeOf(s))
        }
        // 左右两条竖边
        assertEquals(PetState.ATTACH_RIGHT, PetState.attachEdgeOf(PetState.BORDER_R))
        assertEquals(PetState.ATTACH_LEFT, PetState.attachEdgeOf(PetState.BORDER_L))

        // 支点：只有贴顶取格子上缘，其余三种取下缘
        //（贴左右边框时画布 y 轴指向屏幕左右，支点在下缘 == 贴边那条竖边不动）
        assertFalse(PetState.squashPivotAtCellBottom(PetState.CORNER_TR))
        assertFalse(PetState.squashPivotAtCellBottom(PetState.CORNER_TL))
        assertFalse(PetState.squashPivotAtCellBottom(PetState.EDGE_TOP_R))
        assertFalse(PetState.squashPivotAtCellBottom(PetState.EDGE_TOP_L))
        for (s in intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.BORDER_R, PetState.BORDER_L
        )) {
            assertTrue("状态 $s 的支点应在格子下缘", PetState.squashPivotAtCellBottom(s))
        }

        // 弹起方向：贴顶往下（+1），其余三种往屏幕内侧（-1）
        assertEquals(1f, PetState.jumpSignY(PetState.CORNER_TR), 0.0001f)
        assertEquals(1f, PetState.jumpSignY(PetState.EDGE_TOP_L), 0.0001f)
        for (s in intArrayOf(
            PetState.CORNER_BR, PetState.CORNER_BL,
            PetState.BORDER_R_UPRIGHT, PetState.BORDER_L_UPRIGHT,
            PetState.BORDER_R, PetState.BORDER_L
        )) {
            assertEquals("状态 $s 应朝屏幕内侧弹", -1f, PetState.jumpSignY(s), 0.0001f)
        }

        // 十个状态一个都不能漏：每种都必须有明确的吸附边
        for (s in 0..10) {
            if (s == 4) continue // 4 是空缺编号
            val e = PetState.attachEdgeOf(s)
            assertTrue("状态 $s 的吸附边非法: $e", e in 0..3)
        }
    }

    /**
     * 长按松手的弹起高度**与压缩程度成正比**。
     *
     * 用户要求："长按之后松手要向上弹起，弹起高度与压缩程度成正比。"
     */
    @Test
    fun the_press_release_jump_is_proportional_to_the_squash_depth() {
        val full = 0.5f
        val maxFrac = 0.28f
        // 没压 -> 不弹
        assertEquals(0f, PetState.pressJumpFraction(0f, full, maxFrac), 0.0001f)
        // 压满 -> 最大弹起
        assertEquals(maxFrac, PetState.pressJumpFraction(full, full, maxFrac), 0.0001f)
        // 一半 -> 一半（正比）
        assertEquals(maxFrac / 2f, PetState.pressJumpFraction(full / 2f, full, maxFrac), 0.0001f)
        // 阈值处（22% = 点一下与长按的分界）也要有可见弹起
        val atThreshold = PetState.pressJumpFraction(0.22f, full, maxFrac)
        assertEquals(0.22f / full * maxFrac, atThreshold, 0.0001f)
        assertTrue("阈值处弹起太小：$atThreshold", atThreshold > 0.1f)
        // 严格单调：压得越深弹得越高
        var last = -1f
        for (i in 0..100) {
            val v = PetState.pressJumpFraction(i / 100f * full, full, maxFrac)
            assertTrue("不是单调递增", v > last - 0.0001f)
            // 永不超过上限
            assertTrue("超过上限", v <= maxFrac + 0.0001f)
            last = v
        }
        // 越界要夹住（压缩量不可能超过压满值；除零要安全）
        assertEquals(maxFrac, PetState.pressJumpFraction(full * 3f, full, maxFrac), 0.0001f)
        assertEquals(0f, PetState.pressJumpFraction(0.3f, 0f, maxFrac), 0.0001f)

        // 与实际尺寸挂钩：默认 247px 高的素材，压满时弹起约 0.28*247 ≈ 69px，
        // 必须明显高于"点一下"的轻跳（0.08*247 ≈ 20px），否则看不出是长按弹起
        val cellH = 247f
        val longJump = cellH * PetState.pressJumpFraction(full, full, maxFrac)
        val tapJump = cellH * 0.08f
        assertTrue("长按弹起($longJump)应该明显大于轻跳($tapJump)", longJump > tapJump * 2.5f)
    }

    /**
     * 落地回弹曲线：`0 -> 压扁 -> 轻微拉伸 -> 0`，两端精确。
     *
     * 用户要求："长按弹起落地之后加一个回弹效果"，"还有单击也是"。
     */
    @Test
    fun the_landing_rebound_squashes_then_springs_back_to_zero() {
        // 两端必须精确：起点 0、终点 0（否则落地后会残留变形）
        assertEquals(0f, PetState.landSquash(0f), 0.0001f)
        assertEquals(0f, PetState.landSquash(1f), 0.0001f)
        // 越界夹住
        assertEquals(0f, PetState.landSquash(-1f), 0.0001f)
        assertEquals(0f, PetState.landSquash(2f), 0.0001f)

        // 前 25% 压到最深（= 1），随后回落
        assertEquals(1f, PetState.landSquash(0.25f), 0.0001f)
        // 两段在 0.25 处连续（左右极限一致）
        assertTrue(abs(PetState.landSquash(0.249f) - PetState.landSquash(0.251f)) < 0.01f)

        var peak = -1f
        var deepest = 0f
        var prev = PetState.landSquash(0f)
        for (i in 1..400) {
            val v = PetState.landSquash(i / 400f)
            if (v > peak) peak = v
            if (v < deepest) deepest = v
            // 曲线要连续（相邻采样不跳变）
            assertTrue("曲线跳变", abs(v - prev) < 0.03f)
            prev = v
        }
        // 峰值就是 1（压到设定的深度），不会超出
        assertTrue("峰值 $peak 应为 1", peak <= 1.0001f && peak > 0.99f)
        // 必须真的"弹回来"：中途要过冲到轻微拉伸（负值），否则只是慢慢鼓回来
        assertTrue("没有回弹过冲：$deepest", deepest < -0.05f)
        assertTrue("过冲过大：$deepest", deepest > -0.35f)
    }

    /**
     * 落地回弹深度：单击最轻，长按**与按压时间成正比**。
     *
     * 用户要求："单击时的回弹效果轻一点；长按落地时的回弹效果与按压时间成正比。"
     */
    @Test
    fun the_landing_depth_grows_linearly_with_the_press_time() {
        val full = 1100f
        val tap = 0.07f
        val max = 0.14f
        // 没按住（单击）-> 最轻的那一档
        assertEquals(tap, PetState.landSquashDepth(0f, full, tap, max), 0.0001f)
        // 按满 -> 最深
        assertEquals(max, PetState.landSquashDepth(full, full, tap, max), 0.0001f)
        // 按一半 -> 正好在中间（线性）
        assertEquals((tap + max) / 2f, PetState.landSquashDepth(full / 2f, full, tap, max), 0.0001f)
        // 严格线性：等长时间间隔的增量相等
        val d1 = PetState.landSquashDepth(200f, full, tap, max)
        val d2 = PetState.landSquashDepth(400f, full, tap, max)
        val d3 = PetState.landSquashDepth(600f, full, tap, max)
        assertEquals("不是线性", d2 - d1, d3 - d2, 0.0001f)
        // 单调递增、且有上界；超时夹住
        var last = -1f
        for (i in 0..200) {
            val v = PetState.landSquashDepth(i * 10f, full, tap, max)
            assertTrue("不是单调递增", v >= last - 0.0001f)
            assertTrue("超过上限", v <= max + 0.0001f)
            assertTrue("低于单击档", v >= tap - 0.0001f)
            last = v
        }
        assertEquals(max, PetState.landSquashDepth(full * 5f, full, tap, max), 0.0001f)
        // 单击档必须**明显轻于**按满档（用户要求"轻一点"）
        assertTrue("单击档不够轻", tap < max * 0.6f)
        // fullMs = 0 不能除零
        assertEquals(tap, PetState.landSquashDepth(500f, 0f, tap, max), 0.0001f)
    }

    /**
     * 呼吸晃动波形：静止满 0.5s 才开始，起点严格为 0（不跳变），且永远不发散。
     *
     * 用户要求："当人物处于吸附 0.5s 后，给人物增加微微的呼吸晃动效果，
     * 原则是素材吸附边不得离开手机被吸附边。"
     * （"不离开吸附边"由绘制支点保证：绕吸附边缩放、沿吸附边平移。）
     */
    @Test
    fun the_breathing_wave_starts_flat_and_never_drifts() {
        val delay = 500f
        val period = 2600f
        // 没到 0.5s：一动不动
        assertEquals(0f, PetState.breathWave(0f, delay, period), 0.0001f)
        assertEquals(0f, PetState.breathWave(delay - 1f, delay, period), 0.0001f)
        // 刚到 0.5s：仍在 0 上 —— 开始的那一刻不跳变
        assertEquals(0f, PetState.breathWave(delay, delay, period), 0.0001f)
        // 四分之一周期后到达峰值 +1，四分之三周期到 -1
        assertEquals(1f, PetState.breathWave(delay + period / 4f, delay, period), 0.001f)
        assertEquals(-1f, PetState.breathWave(delay + period * 3f / 4f, delay, period), 0.001f)
        // 一个周期后回到起点（周期正确）
        assertEquals(0f, PetState.breathWave(delay + period, delay, period), 0.001f)
        // 长时间运行也不发散：始终在 [-1, 1]
        for (i in 0..2000) {
            val v = PetState.breathWave(i * 37f, delay, period)
            assertTrue("呼吸波形越界: $v", v >= -1.0001f && v <= 1.0001f)
        }
        // 参数非法（周期为 0）时安全返回 0，不产生 NaN
        assertEquals(0f, PetState.breathWave(9999f, delay, 0f), 0.0001f)
    }

    /**
     * 「呼出回弹」卡片里的**回弹幅度**：只缩放回摆的负半周，两端必须仍然精确。
     *
     * 用户要求"回弹幅度以现在为 1.0 倍，可调范围 0.1~10"，
     * 于是把"弹出高度"（过冲距离）与"回弹幅度"（反向冲过的比例）解耦。
     */
    @Test
    fun the_spring_return_gain_scales_only_the_back_swing() {
        // gain = 1 时与原来逐值一致（默认不能改变现有手感）
        for (i in 0..200) {
            val u = i / 200f
            assertEquals(
                "gain=1 时应与 springBack 完全一致",
                PetState.springBack(u), PetState.springBackGained(u, 1f), 0.0001f
            )
        }
        // 两端仍然精确：起点 1（接着过冲点）、终点 0（不留缝）
        assertEquals(1f, PetState.springBackGained(0f, 3f), 0.0001f)
        assertEquals(0f, PetState.springBackGained(1f, 3f), 0.0001f)
        // 负半周随 gain 线性放大
        val u = 0.5f
        val base = PetState.springBack(u)
        assertTrue("u=0.5 应该在负半周", base < 0f)
        assertEquals(base * 3f, PetState.springBackGained(u, 3f), 0.0001f)
        assertEquals(base * 0.1f, PetState.springBackGained(u, 0.1f), 0.0001f)
        // gain = 0：负半周被抹平（只减速停住，不回摆）
        assertEquals(0f, PetState.springBackGained(u, 0f), 0.0001f)
        // 正半周不受 gain 影响
        assertEquals(
            PetState.springBack(0.1f), PetState.springBackGained(0.1f, 9f), 0.0001f
        )
        // 越界要夹住，不能失控
        assertTrue(PetState.springBackGained(u, 1e9f) >= base * 12f - 0.001f)
        assertTrue(PetState.springBackGained(u, -5f) >= 0f)
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

    // ---------------------------------------------------------------- 通知标题

    private val TITLE_DEFAULT = "{name} 正在陪伴你"

    /** 留空 -> 用默认模板 */
    @Test
    fun title_blank_uses_default() {
        for (raw in listOf(null, "", "   ", "\n")) {
            val t = PetState.buildTitle(raw, "后藤一里", TITLE_DEFAULT)
            assertEquals("后藤一里 正在陪伴你", t.text)
            assertEquals(0, t.hlFrom)
            assertEquals("后藤一里".length, t.hlTo)
        }
    }

    /** 自定义模板：{name} 被替换成角色名 */
    @Test
    fun title_replaces_name_token() {
        val t = PetState.buildTitle("{name} 陪你哦", "山田凉", TITLE_DEFAULT)
        assertEquals("山田凉 陪你哦", t.text)
        assertEquals(0, t.hlFrom)
        assertEquals("山田凉".length, t.hlTo)
    }

    /** 不写 {name} -> 标题固定不变，且不高亮 */
    @Test
    fun title_without_token_is_fixed() {
        val t = PetState.buildTitle("桌宠在线", "山田凉", TITLE_DEFAULT)
        assertEquals("桌宠在线", t.text)
        assertEquals(-1, t.hlFrom)
        assertEquals(-1, t.hlTo)
    }

    /** {name} 写在中间：高亮区间要跟着挪 */
    @Test
    fun title_token_in_middle_shifts_highlight() {
        val t = PetState.buildTitle("你的 {name} 已上线", "喜多郁代", TITLE_DEFAULT)
        assertEquals("你的 喜多郁代 已上线", t.text)
        assertEquals("你的 ".length, t.hlFrom)
        assertEquals("你的 ".length + "喜多郁代".length, t.hlTo)
    }

    /**
     * {name} 写两次 -> 两处都替换，但只高亮**第一处**。
     *
     * 这条正是不能用 indexOf 反推的原因：两处都是角色名，indexOf 只能命中一处，
     * 虽然本例恰好也是第一处，但下面的"名字是模板子串"那条会暴露 indexOf 的错。
     */
    @Test
    fun title_token_twice_replaces_both_but_highlights_first() {
        val t = PetState.buildTitle("{name} 来了，{name} 陪你", "广井菊里", TITLE_DEFAULT)
        assertEquals("广井菊里 来了，广井菊里 陪你", t.text)
        assertEquals(0, t.hlFrom)
        assertEquals("广井菊里".length, t.hlTo)
    }

    /**
     * 角色名恰好是模板里已有的子串 —— indexOf 反推会高亮错位置。
     *
     * 模板「一里 陪着一里」、名字「一里」：正确的高亮区间是占位符那一处（开头），
     * 而不是 indexOf 找到的第一个"一里"（其实本例开头就是占位符，所以再构造
     * 一个更刁的：模板里角色名先出现、占位符在后）。
     */
    @Test
    fun title_highlight_follows_token_not_first_name_occurrence() {
        // 模板里先出现字面的"一里"，占位符在后
        val t = PetState.buildTitle("一里 通知：{name} 正在陪你", "一里", TITLE_DEFAULT)
        assertEquals("一里 通知：一里 正在陪你", t.text)
        // 高亮的是**占位符**那一处，不是开头那个字面"一里"
        val expectedAt = "一里 通知：".length
        assertEquals(expectedAt, t.hlFrom)
        assertEquals(expectedAt + "一里".length, t.hlTo)
    }

    /** 角色名为空：{name} 被去掉，不高亮、也不崩 */
    @Test
    fun title_blank_name_drops_token_without_highlight() {
        for (n in listOf(null, "")) {
            val t = PetState.buildTitle("{name} 正在陪你", n, TITLE_DEFAULT)
            assertEquals(" 正在陪你", t.text)
            assertEquals(-1, t.hlFrom)
        }
    }

    /** 首尾空白要 trim 掉（用户常在输入框里手滑） */
    @Test
    fun title_trims_whitespace() {
        val t = PetState.buildTitle("  {name} 陪你  ", "山田凉", TITLE_DEFAULT)
        assertEquals("山田凉 陪你", t.text)
    }

    /** 默认模板自身也走同一条替换路径（不能写死"角色名一定在开头"） */
    @Test
    fun title_default_template_goes_through_same_path() {
        val t = PetState.buildTitle("", "后藤一里", "{name} 正在陪伴你")
        assertEquals("后藤一里 正在陪伴你", t.text)
        assertEquals(0, t.hlFrom)
        assertEquals("后藤一里".length, t.hlTo)
    }

    // ------------------------------------------------ 通知文案的留空回填

    /** 标题留空 -> 填入默认模板（保留占位符，不解析） */
    @Test
    fun fill_blank_title_uses_template_with_token_kept() {
        val r = PetState.fillNotifyTexts("", "正文", "后藤一里", TITLE_DEFAULT)
        assertEquals("{name} 正在陪伴你", r.title)
        assertEquals("正文", r.body)   // 正文有值就不动
    }

    /** 只敲空格也算留空 */
    @Test
    fun fill_whitespace_only_counts_as_blank() {
        val r = PetState.fillNotifyTexts("   ", "  \n ", "后藤一里", TITLE_DEFAULT)
        assertEquals("{name} 正在陪伴你", r.title)
        // 正文也留空 -> 回填标题解析后的文字
        assertEquals("后藤一里 正在陪伴你", r.body)
    }

    /** 正文留空 -> 填入当前标题解析后的文字 */
    @Test
    fun fill_blank_body_uses_resolved_title() {
        val r = PetState.fillNotifyTexts("{name} 陪你哦", "", "山田凉", TITLE_DEFAULT)
        assertEquals("{name} 陪你哦", r.title)
        assertEquals("山田凉 陪你哦", r.body)
    }

    /** 两个都留空 -> 标题用模板、正文用模板解析后的文字（两者一致） */
    @Test
    fun fill_both_blank() {
        val r = PetState.fillNotifyTexts(null, null, "喜多郁代", TITLE_DEFAULT)
        assertEquals("{name} 正在陪伴你", r.title)
        assertEquals("喜多郁代 正在陪伴你", r.body)
    }

    /** 两个都有值 -> 原样保留，不做任何替换 */
    @Test
    fun fill_both_present_keeps_them() {
        val r = PetState.fillNotifyTexts("我的标题", "我的正文", "后藤一里", TITLE_DEFAULT)
        assertEquals("我的标题", r.title)
        assertEquals("我的正文", r.body)
    }

    /** 标题不含 {name} 时，正文回填的就是标题原文 */
    @Test
    fun fill_body_follows_title_without_token() {
        val r = PetState.fillNotifyTexts("桌宠在线", "", "后藤一里", TITLE_DEFAULT)
        assertEquals("桌宠在线", r.title)
        assertEquals("桌宠在线", r.body)
    }

    // ------------------------------------------- 标题的显示 / 存储互转

    /** 存储模板 -> 显示时解析成实际名字 */
    @Test
    fun title_display_resolves_token() {
        assertEquals(
            "后藤一里 正在陪伴你",
            PetState.titleForDisplay("{name} 正在陪伴你", "后藤一里", TITLE_DEFAULT)
        )
    }

    /** 存储为空 -> 显示默认模板解析后的文字 */
    @Test
    fun title_display_blank_uses_default() {
        assertEquals(
            "山田凉 正在陪伴你",
            PetState.titleForDisplay("", "山田凉", TITLE_DEFAULT)
        )
    }

    /** 显示文字 -> 存储时把当前角色名换成 {name} */
    @Test
    fun title_store_replaces_name_with_token() {
        assertEquals(
            "{name} 正在陪伴你",
            PetState.titleForStore("后藤一里 正在陪伴你", "后藤一里")
        )
    }

    /**
     * 往返一致：解析出来的显示文字，反向换回去必须还是同一个模板。
     * 这条就是"换角色后标题还对不对"的核心保证。
     */
    @Test
    fun title_round_trip_is_stable() {
        for (tpl in listOf(
            "{name} 正在陪伴你",
            "你的 {name} 已上线",
            "{name} 来了，{name} 陪你"
        )) {
            val shown = PetState.titleForDisplay(tpl, "喜多郁代", TITLE_DEFAULT)
            val back = PetState.titleForStore(shown, "喜多郁代")
            assertEquals("模板 $tpl 往返后应保持不变", tpl, back)
        }
    }

    /** 换了角色后，同一个模板要解析出新名字（不会残留旧名字） */
    @Test
    fun title_display_follows_new_name() {
        val tpl = PetState.titleForStore("后藤一里 正在陪伴你", "后藤一里")
        assertEquals("{name} 正在陪伴你", tpl)
        assertEquals(
            "山田凉 正在陪伴你",
            PetState.titleForDisplay(tpl, "山田凉", TITLE_DEFAULT)
        )
    }

    /** 标题里没有角色名 -> 存储时原样保留 */
    @Test
    fun title_store_without_name_is_unchanged() {
        assertEquals("桌宠在线", PetState.titleForStore("桌宠在线", "后藤一里"))
    }

    /** 角色名为空时不能乱替换（否则会在每个字符之间插入 {name}） */
    @Test
    fun title_store_with_blank_name_is_safe() {
        assertEquals("后藤一里 正在陪伴你",
            PetState.titleForStore("后藤一里 正在陪伴你", ""))
        assertEquals("", PetState.titleForStore("", ""))
        assertEquals("abc", PetState.titleForStore("abc", null))
    }

    // ------------------------------------------- 历史值的迁移

    /** 字面名字 -> 模板 */
    @Test
    fun migrate_literal_name_becomes_token() {
        assertEquals(
            "{name} 正在陪伴你",
            PetState.migrateTitleToTemplate("喜多郁代 正在陪伴你", "喜多郁代")
        )
    }

    /** 已经是模板 -> 不动（返回 null 表示"无需迁移"） */
    @Test
    fun migrate_existing_template_is_skipped() {
        assertNull(PetState.migrateTitleToTemplate("{name} 正在陪伴你", "喜多郁代"))
    }

    /** 与角色无关的自定义标题 -> 不碰 */
    @Test
    fun migrate_unrelated_title_is_untouched() {
        assertNull(PetState.migrateTitleToTemplate("桌宠在线", "喜多郁代"))
    }

    /** 空值 / 空名字 -> 不迁移 */
    @Test
    fun migrate_blank_inputs_are_safe() {
        assertNull(PetState.migrateTitleToTemplate("", "喜多郁代"))
        assertNull(PetState.migrateTitleToTemplate(null, "喜多郁代"))
        assertNull(PetState.migrateTitleToTemplate("喜多郁代 正在陪伴你", ""))
        assertNull(PetState.migrateTitleToTemplate("喜多郁代 正在陪伴你", null))
    }

    /** 幂等：迁移一次之后再调就不会再动 */
    @Test
    fun migrate_is_idempotent() {
        val once = PetState.migrateTitleToTemplate("喜多郁代 正在陪伴你", "喜多郁代")
        assertEquals("{name} 正在陪伴你", once)
        assertNull(PetState.migrateTitleToTemplate(once, "喜多郁代"))
    }

    /** 迁移后再换角色，显示要跟着变 */
    @Test
    fun migrate_then_switch_name_follows() {
        val tpl = PetState.migrateTitleToTemplate("喜多郁代 正在陪伴你", "喜多郁代")!!
        assertEquals("山田凉 正在陪伴你",
            PetState.titleForDisplay(tpl, "山田凉", TITLE_DEFAULT))
    }

    /**
     * 正文留空时的取值：直接用当前标题的**模板**，解析后应与标题一致。
     *
     * 这条对应实机抓到的 bug：通知里标题解析了、正文没解析，
     * 结果正文原样显示了 {name}。
     */
    @Test
    fun body_blank_copies_title_template_and_resolves_the_same() {
        val name = "后藤一里"
        // 标题留空 -> 模板；正文留空 -> 取标题模板
        val filled = PetState.fillNotifyTexts("", "", name, TITLE_DEFAULT)
        assertEquals("{name} 正在陪伴你", filled.title)
        assertEquals("后藤一里 正在陪伴你", filled.body)

        // 服务端解析正文时用的也是 titleForDisplay，两边必须一致
        val storedBody = PetState.titleForStore(filled.body, name)
        assertEquals("{name} 正在陪伴你", storedBody)
        assertEquals(
            PetState.titleForDisplay(filled.title, name, TITLE_DEFAULT),
            PetState.titleForDisplay(storedBody, name, TITLE_DEFAULT)
        )
    }

    // ------------------------------------------------------ 呼吸速度 / 幅度

    /** 速度倍率 -> 周期：1.0 是基准，2 倍速度周期减半，0.5 倍周期翻倍 */
    @Test
    fun breath_period_is_inverse_to_speed() {
        val base = 2600f
        assertEquals("1.0 倍 = 基准周期", base, PetState.breathPeriod(1f, base), 0.01f)
        assertEquals("2 倍速度 = 半个周期", base / 2f, PetState.breathPeriod(2f, base), 0.01f)
        assertEquals("10 倍速度", base / 10f, PetState.breathPeriod(10f, base), 0.01f)
        assertEquals("0.5 倍 = 两倍周期", base * 2f, PetState.breathPeriod(0.5f, base), 0.01f)
        assertEquals("0.1 倍（下限）", base * 10f, PetState.breathPeriod(0.1f, base), 0.01f)
    }

    /**
     * **速度调大周期必须变短** —— 这条单独钉死，防止有人把除法改成乘法。
     *
     * 用户调的是"速度"：滑条往右拉应该呼吸更快。写成乘法的话往右拉会变慢，
     * 是个很容易犯、又很难在代码审查里看出来的方向性错误。
     */
    @Test
    fun faster_speed_means_shorter_period() {
        val base = 2600f
        val slow = PetState.breathPeriod(0.5f, base)
        val mid = PetState.breathPeriod(1f, base)
        val fast = PetState.breathPeriod(4f, base)
        assertTrue("0.5 倍应该比 1 倍慢（周期更长）", slow > mid)
        assertTrue("4 倍应该比 1 倍快（周期更短）", fast < mid)
    }

    /** 防御：speed <= 0 时不能除零，退回基准周期 */
    @Test
    fun breath_period_guards_against_non_positive_speed() {
        val base = 2600f
        assertEquals(base, PetState.breathPeriod(0f, base), 0.01f)
        assertEquals(base, PetState.breathPeriod(-3f, base), 0.01f)
    }

    /**
     * 波形本身只认周期：同一个 elapsed、周期越短 -> 相位越靠后。
     *
     * 也就是说"速度只影响播放快慢"，与幅度（由调用方另乘）互不干涉。
     */
    @Test
    fun breath_wave_phase_advances_faster_with_shorter_period() {
        // ⚠️ 容差不能取太小：sin(2*pi*n) 在浮点下不是精确的 0，
        //    实测 sin(pi) = -8.7e-8。用 1e-4 的容差足够表达"回到 0"。
        val eps = 1e-4f
        val elapsed = 2600f
        // 三个周期都能整除 elapsed，都应回到 0 —— 说明确实是"相位推进更快"
        // 而不是幅度变了
        assertEquals(0f, PetState.breathWave(elapsed, 0f, 2600f), eps)
        assertEquals(0f, PetState.breathWave(elapsed, 0f, 1300f), eps)
        assertEquals(0f, PetState.breathWave(elapsed, 0f, 650f), eps)
        // ⚠️ 峰值在 **u = 0.25**（四分之一周期），不是 0.5。
        //    breathWave 是 sin(2*pi*u)：u=0.25 -> 1，u=0.5 -> 0，u=1 -> 0。
        //    我第一版把 0.5 当成峰，结果拿到 -8.7e-8（就是那个 0），
        //    还误以为是浮点精度问题去放宽容差 —— 实际是参数给错了。
        assertEquals(1f, PetState.breathWave(650f, 0f, 2600f), eps)
        assertEquals(1f, PetState.breathWave(325f, 0f, 1300f), eps)
        // 四分之三周期处是谷
        assertEquals(-1f, PetState.breathWave(1950f, 0f, 2600f), eps)
    }

    // ---------------------------------------------------------------- 思考气泡摆哪一侧

    /**
     * 气泡侧别规则（用户 2026-10-16 定的推广规则，位置一律按"人物素材自己的视角"）。
     *
     * 屏幕 1440 x 3084：中线 x = 720，上 1/3 = 1028，上 1/5 = 616.8。
     */
    private fun sideOf(
        state: Int, x: Float, y: Float, defaultTop: Float = 1e5f
    ) = PetState.bubbleSide(state, x, y, SW, SH, defaultTop)

    @Test
    fun bubble_on_right_border_defaults_to_character_right() {
        // 下半屏、气泡也不会顶出上 1/5 线 -> 默认（素材右侧 = 人物右上）
        assertEquals(
            PetState.BUBBLE_SIDE_RIGHT,
            sideOf(PetState.BORDER_R, SW - 100f, SH * 0.75f, defaultTop = 2000f)
        )
    }

    @Test
    fun bubble_flips_when_pet_is_in_top_third() {
        // 条件1：吸附边中点在上 1/3 点及以上（含正好压线）
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R, SW - 100f, SH / 3f))
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R, SW - 100f, SH / 3f - 1f))
        // 差一点就还在默认侧
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.BORDER_R, SW - 100f, SH / 3f + 1f, 2000f))
    }

    @Test
    fun bubble_flips_when_default_placement_would_cross_top_fifth() {
        // 条件2：默认摆法的最上沿在上 1/5 点及以上（含正好压线）
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R, SW - 100f, SH * 0.6f, SH / 5f))
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R, SW - 100f, SH * 0.6f, SH / 5f - 1f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.BORDER_R, SW - 100f, SH * 0.6f, SH / 5f + 1f))
    }

    @Test
    fun bubble_on_left_border_mirrors_right_border() {
        // 规则5：贴左边框 = 贴右边框的左右镜像 -> 判据完全相同
        assertEquals(
            PetState.BUBBLE_SIDE_RIGHT,
            sideOf(PetState.BORDER_L, 100f, SH * 0.75f, defaultTop = 2000f)
        )
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_L, 100f, SH / 3f))
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_L, 100f, SH * 0.6f, SH / 5f))
    }

    @Test
    fun bubble_at_corners_follows_horizontal_side_only() {
        // 规则2：右下角 -> 左上（素材左侧）；左下角 = 左右镜像 -> 右上
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.CORNER_BR, SW - 120f, SH - 120f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.CORNER_BL, 120f, SH - 120f))
        // 上方的两个角 = 下方的上下镜像（左右不变）-> 右上角仍是左上、左上角仍是右上
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.CORNER_TR, SW - 120f, 120f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.CORNER_TL, 120f, 120f))
    }

    @Test
    fun bubble_on_bottom_and_top_edges_uses_screen_middle() {
        // 规则3：吸附边中点在屏幕中线及右侧 -> 左上；否则右上（含正好压线）
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R_UPRIGHT, SW / 2f, SH - 100f))
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.BORDER_R_UPRIGHT, SW / 2f + 1f, SH - 100f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.BORDER_R_UPRIGHT, SW / 2f - 1f, SH - 100f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.BORDER_L_UPRIGHT, SW * 0.2f, SH - 100f))
        // 规则4：贴顶边同判据（素材上下翻转，屏幕上的表现上下镜像）
        assertEquals(PetState.BUBBLE_SIDE_LEFT, sideOf(PetState.EDGE_TOP_R, SW / 2f, 100f))
        assertEquals(PetState.BUBBLE_SIDE_RIGHT, sideOf(PetState.EDGE_TOP_L, SW / 2f - 1f, 100f))
    }
}
