package com.dsh.deskpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UpdateCheck] 的纯逻辑测试：解析 / 比较 / 从 JSON 取标签 */
class UpdateCheckTest {

    @Test
    fun parse_handles_prefix_and_parts() {
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("2.3.1"))
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("v2.3.1"))
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("V2.3.1"))
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("  v2.3.1  "))
        assertEquals(listOf(2, 3), UpdateCheck.parseVersion("2.3"))
        assertEquals(listOf(20200), UpdateCheck.parseVersion("20200"))
    }

    @Test
    fun parse_handles_suffixes() {
        // 预发布后缀不参与比较：2.3.1-beta.2 与 2.3.1 视为同版本
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("2.3.1-beta.2"))
        assertEquals(listOf(2, 3, 1), UpdateCheck.parseVersion("2.3.1+build7"))
        // 遇到第一段没有数字的就停下
        assertEquals(listOf(2, 3), UpdateCheck.parseVersion("2.3.beta"))
    }

    @Test
    fun parse_rejects_garbage() {
        assertNull(UpdateCheck.parseVersion(null))
        assertNull(UpdateCheck.parseVersion(""))
        assertNull(UpdateCheck.parseVersion("   "))
        assertNull(UpdateCheck.parseVersion("v"))
        assertNull(UpdateCheck.parseVersion("beta"))
    }

    @Test
    fun newer_detects_patch_and_minor_and_major() {
        assertTrue(UpdateCheck.isNewer("2.3.0", "2.3.1"))
        assertTrue(UpdateCheck.isNewer("2.3.1", "2.4.0"))
        assertTrue(UpdateCheck.isNewer("2.3.1", "3.0.0"))
        // tag 带 v 也要能比
        assertTrue(UpdateCheck.isNewer("2.3.1", "v2.3.2"))
    }

    @Test
    fun newer_is_false_for_same_or_older() {
        assertFalse(UpdateCheck.isNewer("2.3.1", "2.3.1"))
        assertFalse(UpdateCheck.isNewer("2.3.1", "v2.3.1"))
        assertFalse(UpdateCheck.isNewer("2.3.1", "2.3.0"))
        assertFalse(UpdateCheck.isNewer("2.4.0", "2.3.9"))
    }

    @Test
    fun numeric_compare_not_string_compare() {
        // 这是最容易写错的一处：按字符串比会认为 "2.3.9" > "2.3.10"
        assertTrue(UpdateCheck.isNewer("2.3.9", "2.3.10"))
        assertTrue(UpdateCheck.isNewer("2.3.9", "2.3.100"))
        assertFalse(UpdateCheck.isNewer("2.3.10", "2.3.9"))
    }

    @Test
    fun different_part_counts() {
        assertTrue(UpdateCheck.isNewer("2.3.9", "2.4"))
        assertFalse(UpdateCheck.isNewer("2.3", "2.3.0"))
        assertTrue(UpdateCheck.isNewer("2.3", "2.3.1"))
    }

    @Test
    fun unparsable_input_never_reports_newer() {
        assertFalse(UpdateCheck.isNewer("2.3.1", null))
        assertFalse(UpdateCheck.isNewer(null, "2.4.0"))
        assertFalse(UpdateCheck.isNewer("2.3.1", "beta"))
        assertFalse(UpdateCheck.isNewer("2.3.1", ""))
    }

    @Test
    fun tag_from_json() {
        val json = """{"url":"https://api.github.com/x","tag_name":"v2.3.2","name":"2.3.2"}"""
        assertEquals("v2.3.2", UpdateCheck.tagFromJson(json))
        // 带空格与换行的排版也要能取到
        assertEquals("v9.9.9", UpdateCheck.tagFromJson("{\n  \"tag_name\" : \"v9.9.9\"\n}"))
    }

    @Test
    fun tag_from_json_failure_cases() {
        assertNull(UpdateCheck.tagFromJson(null))
        assertNull(UpdateCheck.tagFromJson(""))
        assertNull(UpdateCheck.tagFromJson("""{"message":"Not Found"}"""))
        assertNull(UpdateCheck.tagFromJson("""{"tag_name":""}"""))
    }
}
