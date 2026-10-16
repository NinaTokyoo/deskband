package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「气泡内容」的纯逻辑：字数上限、截断、以及存档串的转义/解析。
 *
 * ⚠️ 这里**不碰** Android 的 Context / SharedPreferences / org.json ——
 *    单测跑在 JVM 上，那些要么没有、要么只有空壳实现（一调就抛）。
 *    所以 [BubbleTextStore] 把纯函数与落盘分开，这里只测纯函数。
 */
class BubbleTextStoreTest {

    @Test
    fun limits_match_the_spec() {
        // 用户给定：最多 20 条，每条最多 20 个字
        assertEquals(20, BubbleTextStore.MAX_ITEMS)
        assertEquals(20, BubbleTextStore.MAX_CHARS)
    }

    @Test
    fun char_count_counts_code_points_not_chars() {
        assertEquals(5, BubbleTextStore.charCount("这是一段测"))
        assertEquals(0, BubbleTextStore.charCount(""))
        // emoji 是一个"字"（用户看到的），String.length 会算成 2
        assertEquals(1, BubbleTextStore.charCount("🐟"))
        assertEquals(2, "🐟".length)
        assertEquals(3, BubbleTextStore.charCount("a🐟中"))
    }

    @Test
    fun fit_truncates_to_limit_without_splitting_surrogate_pairs() {
        val twenty = "字".repeat(20)
        assertEquals(twenty, BubbleTextStore.fit(twenty))
        assertEquals(20, BubbleTextStore.charCount(BubbleTextStore.fit("字".repeat(25))))

        // 正好 20 个字、最后一个是 emoji：一个字都不能少、也不能被截成半个代理对
        val exact = "字".repeat(19) + "🐟"
        assertEquals(20, BubbleTextStore.charCount(exact))
        assertEquals(exact, BubbleTextStore.fit(exact))

        // 21 个字、第 20 个是 emoji：应当只砍掉最后那个"字"，emoji 完整保留
        val over = exact + "字"
        val cut = BubbleTextStore.fit(over)
        assertEquals(20, BubbleTextStore.charCount(cut))
        assertEquals(exact, cut)
        assertTrue("末尾不能留下半个 emoji", cut.endsWith("🐟"))
    }

    @Test
    fun punctuation_does_not_count_toward_the_limit() {
        // 用户 2026-10-16："20 汉字不包括标点符号"
        assertEquals(4, BubbleTextStore.charCount("你好，世界！"))
        assertEquals(1, BubbleTextStore.charCount("好。"))
        assertEquals(1, BubbleTextStore.charCount("……好"))
        assertEquals(0, BubbleTextStore.charCount("，。！？；：、"))
        assertEquals(0, BubbleTextStore.charCount(",.!?;:'\"()[]{}"))
        // 拉丁字母 / 数字 / emoji 照常占额度
        assertEquals(3, BubbleTextStore.charCount("a1中"))
        assertEquals(1, BubbleTextStore.charCount("🐟"))

        // 20 个汉字 + 一堆标点：一个标点都不该被截掉
        val s = "字".repeat(20) + "，。！？"
        assertEquals(20, BubbleTextStore.charCount(s))
        assertEquals(s, BubbleTextStore.fit(s))

        // 21 个汉字 + 标点：只砍掉第 21 个汉字，后面的标点留着
        val over = "字".repeat(21) + "，。"
        assertEquals("字".repeat(20) + "，。", BubbleTextStore.fit(over))
    }

    @Test
    fun enabled_flag_round_trip_and_legacy_compat() {
        // 带启用标志的往返（用户 2026-10-16：标签右侧「已启用 / 启用」按钮）
        val items = listOf(
            BubbleTextStore.Item("开着的", enabled = true),
            BubbleTextStore.Item("关着的", enabled = false),
            BubbleTextStore.Item("带\n换行\\的", enabled = false)
        )
        assertEquals(items, BubbleTextStore.decodeItems(BubbleTextStore.encodeItems(items)))

        // 老存档（没有标志前缀）一律按"启用"读，且**首字符不能被吃掉**
        val legacy = BubbleTextStore.encode(listOf("1号方案", "0号方案"))
        assertEquals(
            listOf(
                BubbleTextStore.Item("1号方案", enabled = true),
                BubbleTextStore.Item("0号方案", enabled = true)
            ),
            BubbleTextStore.decodeItems(legacy)
        )
    }

    @Test
    fun encode_decode_round_trip() {
        val items = listOf("第一句", "带\\反斜杠", "带\n换行", "emoji 🐟", "")
        assertEquals(items, BubbleTextStore.decode(BubbleTextStore.encode(items)))
        // 空存档 = 空列表（首次使用的状态）
        assertTrue(BubbleTextStore.decode("").isEmpty())
        assertTrue(BubbleTextStore.decode(null).isEmpty())
    }

    @Test
    fun decode_keeps_every_item_including_empty_ones() {
        // 两条内容之间的分隔必须准确还原，不能因为空串就少一条
        assertEquals(listOf("a", "b", "c"), BubbleTextStore.decode("a\nb\nc"))
        assertEquals(listOf("a", "", "c"), BubbleTextStore.decode("a\n\nc"))
    }
}
