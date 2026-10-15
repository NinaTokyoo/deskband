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
    fun window_size_follows_recorded_ratio() {
        // 记录：气泡整组宽 = 0.99219 × 人物正立素材宽；整组纵横比 1.00448
        val size = BubbleView.windowSize(1024f, 0f)
        assertEquals(1016, size[0])
        assertEquals(1011, size[1])
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
}
