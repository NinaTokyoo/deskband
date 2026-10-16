package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡时间轴的纯函数测试。
 *
 * 出现/消失的**顺序与时长**是用户明确规定的
 * （0.15s 小→大依次出现、大气泡停留 1.5s、0.15s 大→小依次消失），
 * 而整段只有 1.8 秒、出现段只有 150ms —— 靠连拍截图去验太不可靠，
 * 所以把时间轴抽成纯函数 [BubbleView.popOf] 在这里钉死。
 */
class BubbleViewTest {

    private val small = 0
    private val mid = 1
    private val big = 2

    @Test
    fun total_is_150_plus_1500_plus_150() {
        assertEquals(1800L, BubbleView.TOTAL_MS)
    }

    @Test
    fun parts_appear_one_by_one_small_first() {
        // 时间轴刚起步（t=0 那一刻按设计还不画，第一帧从 t>0 开始）
        assertNotNull("小圈应当已经开始出现", BubbleView.popOf(small, 1f))
        assertNull("中圈还没轮到", BubbleView.popOf(mid, 1f))
        assertNull("大气泡还没轮到", BubbleView.popOf(big, 1f))

        // 中圈在 50ms 开始（边界那一刻按设计还不画，t>出现时刻才画）
        assertNull(BubbleView.popOf(mid, 49f))
        assertNotNull(BubbleView.popOf(mid, 51f))

        // 大气泡在 100ms 开始
        assertNull(BubbleView.popOf(big, 99f))
        assertNotNull(BubbleView.popOf(big, 101f))
    }

    @Test
    fun all_fully_appeared_at_150ms() {
        for (i in 0 until BubbleView.PART_COUNT) {
            assertEquals("第 $i 块在 0.15s 应当长到 100%", 1f, BubbleView.popOf(i, 150f)!!, 1e-4f)
        }
    }

    @Test
    fun big_bubble_holds_for_1500ms() {
        // 大气泡从 150ms 到 1650ms 恒为满尺寸 —— 正好 1.5s
        var t = 150f
        while (t <= 1650f) {
            assertEquals("t=$t 时大气泡应当满尺寸", 1f, BubbleView.popOf(big, t)!!, 1e-4f)
            t += 25f
        }
    }

    @Test
    fun parts_vanish_one_by_one_big_first() {
        // 1650ms：大气泡开始缩，中圈与小圈还满着
        assertTrue("大气泡应当开始缩", BubbleView.popOf(big, 1675f)!! < 1f)
        assertEquals(1f, BubbleView.popOf(mid, 1675f)!!, 1e-4f)
        assertEquals(1f, BubbleView.popOf(small, 1675f)!!, 1e-4f)

        // 1700ms：轮到中圈
        assertTrue(BubbleView.popOf(mid, 1725f)!! < 1f)
        assertEquals(1f, BubbleView.popOf(small, 1725f)!!, 1e-4f)

        // 1750ms：最后是小圈
        assertTrue(BubbleView.popOf(small, 1775f)!! < 1f)
    }

    @Test
    fun drag_vanish_starts_from_the_natural_vanish_frame() {
        // 用户 2026-10-16 新增："拖动人物时，气泡立刻播放消失动画并消失"。
        // 实现是把时间轴拨到 APPEAR + HOLD（= 自然消失的起点）：
        // 该帧三块都还是满的，随后按"由大到小"依次缩没，0.15s 内走完。
        val dragFrame = 150f + 1500f            // APPEAR_MS + HOLD_MS
        for (i in 0 until BubbleView.PART_COUNT) {
            assertEquals("拨到消失起点时第 $i 块应仍是满的", 1f, BubbleView.popOf(i, dragFrame)!!, 1e-4f)
        }
        // 往后 1/3 段：大气泡开始缩，另两块还满
        assertTrue(BubbleView.popOf(big, dragFrame + 25f)!! < 1f)
        assertEquals(1f, BubbleView.popOf(small, dragFrame + 25f)!!, 1e-4f)
        // 再走完这一段就全没了
        for (i in 0 until BubbleView.PART_COUNT) {
            assertNull(BubbleView.popOf(i, dragFrame + 150f))
        }
    }

    @Test
    fun pop_uses_easing_not_linear() {
        // 出现：ease-out（快出慢定）—— 半程时已经明显大于线性中值（0.25 + 0.75*0.5 = 0.625）
        // 小圈 i=0：appear = 0，STEP = 50ms，所以 t = 25ms 正好是半程
        val halfAppear = BubbleView.popOf(small, 25f)!!
        assertTrue("出现半程应已接近满尺寸（ease-out）", halfAppear > 0.85f)

        // 消失：ease-in（慢起快收）—— 半程时明显小于线性中值
        // 大气泡 i=big：vanish = 1650ms，STEP = 50ms，所以 t = 1675ms 正好半程
        val halfVanish = BubbleView.popOf(big, 1675f)!!
        assertTrue("消失半程应还比较小（ease-in）", halfVanish < 0.35f)
    }

    @Test
    fun everything_gone_at_1800ms() {
        for (i in 0 until BubbleView.PART_COUNT) {
            assertNull("第 $i 块在 1.8s 应当已经消失", BubbleView.popOf(i, 1800f))
        }
    }

    @Test
    fun pop_grows_from_small_to_full() {
        // 每一块在自己的出现段里都是"由小到大"（从 25% 长到 100%）
        val a = BubbleView.popOf(small, 1f)!!
        val b = BubbleView.popOf(small, 25f)!!
        val c = BubbleView.popOf(small, 50f)!!
        assertTrue("应当由小到大：$a -> $b -> $c", a < b && b < c)
        assertEquals(100f, c * 100f, 0.001f)
    }

    @Test
    fun window_size_is_baseline_times_1_2() {
        // 基线整组 = 0.99219W × 0.98730W；用户要求整体放大 1.2 倍（以小气泡最左点为基准）
        // -> 1.190628W × 1.184760W。W = 1024 时应为 1219 × 1213。
        val size = BubbleView.windowSize(1024f, 0f)
        assertEquals(1219, size[0])
        assertEquals(1213, size[1])
    }

    @Test
    fun window_swaps_when_group_rotates_with_pet() {
        // 人物躺倒（-90°）时整组跟着转，窗口宽高互换
        val upright = BubbleView.windowSize(1024f, 0f)
        val lying = BubbleView.windowSize(1024f, -90f)
        assertEquals("转过 90° 后窗口宽 = 原来的高", upright[1], lying[0])
        assertEquals("转过 90° 后窗口高 = 原来的宽", upright[0], lying[1])
    }

    @Test
    fun screen_offset_follows_same_transform_as_sprite() {
        val w = 1000f
        // 素材右侧、不镜像不翻转不旋转：向右 ART_RIGHT、向上 ART_UP（屏幕 y 向下，故为负）
        val right = BubbleView.screenOffset(w, PetState.BUBBLE_SIDE_RIGHT, false, false, 0f)
        assertEquals(1099.2f, right[0], 0.5f)
        assertEquals(-426.2f, right[1], 0.5f)
        // 素材左侧就是它的左右镜像
        val left = BubbleView.screenOffset(w, PetState.BUBBLE_SIDE_LEFT, false, false, 0f)
        assertEquals(-1099.2f, left[0], 0.5f)
        assertEquals(-426.2f, left[1], 0.5f)
        // 吸附右边框（素材旋转 -90°）：素材"右"落到屏幕上、"上"落到屏幕左
        //   -> 屏幕偏移 = (左 426.2, 上 1099.2)（屏幕 y 向下，故 y 为负）
        val lying = BubbleView.screenOffset(w, PetState.BUBBLE_SIDE_RIGHT, false, false, -90f)
        assertEquals("素材上方 -> 屏幕左", -426.2f, lying[0], 0.5f)
        assertEquals("素材右侧 -> 屏幕上", -1099.2f, lying[1], 0.5f)
        // 贴左边框（镜像 + 旋转 +90°）：成为右边框的左右镜像
        val leftBorder = BubbleView.screenOffset(w, PetState.BUBBLE_SIDE_RIGHT, true, false, 90f)
        assertEquals("镜像后落在屏幕右侧", 426.2f, leftBorder[0], 0.5f)
        assertEquals("仍在屏幕上方", -1099.2f, leftBorder[1], 0.5f)
    }

    @Test
    fun part_boxes_are_inside_group_and_do_not_overlap() {
        for (box in BubbleView.PART_BOX) {
            assertTrue(box[0] >= 0f && box[1] >= 0f)
            assertTrue(box[0] + box[2] <= 1.0001f)
            assertTrue(box[1] + box[3] <= 1.0001f)
        }
        // 大气泡在最右（躺倒合成图里它离人物最远的一侧）
        assertTrue(BubbleView.PART_BOX[big][0] > BubbleView.PART_BOX[mid][0])
        assertTrue(BubbleView.PART_BOX[mid][0] > BubbleView.PART_BOX[small][0])
    }

    @Test
    fun dark_part_boxes_keep_the_same_invariants() {
        // 深色模式那套素材（用户 2026-10-16 提供）是另一张画：大气泡横在上方、
        // 两个小圈拖在左下。两条不变量必须与浅色那套一致 ——
        // ① 三块都在组外框内；② **小圈在左**（尾巴朝向人物那一侧的约定，镜像逻辑依赖它）。
        val boxes = BubbleView.partBoxes(dark = true)
        assertEquals(BubbleView.PART_COUNT, boxes.size)
        for (box in boxes) {
            assertTrue("左/上不能为负", box[0] >= -0.01f && box[1] >= -0.01f)
            // 容差放到 1%：深色素材抠图时每边留了 3px 抗锯齿余量，
            // 小圈那块因此会略微越出组外框（约 0.15%），视觉上无影响。
            assertTrue("右不能越界", box[0] + box[2] <= 1.01f)
            assertTrue("下不能越界", box[1] + box[3] <= 1.01f)
        }
        assertTrue("小圈应在最左（尾巴在左）", boxes[small][0] < boxes[mid][0])
        // 深色这张画的大气泡**横在上方**（整宽、只占上 73%），两个小圈拖在它下面 ——
        // 与浅色那套"大气泡在右、小圈在左侧中部"是不同的构图，但组外框都归一化到 (0,0)-(1,1)。
        assertEquals(0f, boxes[big][0], 0.01f)
        assertTrue(boxes[big][3] < 0.85f)
        assertTrue("中圈应在大气泡下方", boxes[mid][1] > boxes[big][1] + boxes[big][3] - 0.01f)
        assertTrue("小圈应在中圈下方", boxes[small][1] > boxes[mid][1])
        // 浅色套不受影响
        assertTrue(BubbleView.partBoxes(dark = false)[big][3] > 0.99f)
    }
}
