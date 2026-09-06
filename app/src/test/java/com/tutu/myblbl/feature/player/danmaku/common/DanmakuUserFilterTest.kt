package com.tutu.myblbl.feature.player.danmaku.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端用户屏蔽规则归一化回归（对齐 blbl VideoApiDmFilterUserNormalizeTest）：
 * - type=2（拉黑用户）：8 位 CRC32 hex 补前导零；>8 位数字视为 MID 转 CRC32；非法输入丢弃
 * - type=1（正则）：/pattern/flags 字面量解析（i/m/s），非法正则返回 null
 */
class DanmakuUserFilterTest {

    @Test
    fun `mid hash normalized to lowercase and padded`() {
        assertEquals("0abc0def", DanmakuUserFilter.normalizeMidHashRule("0abc0def"))
        assertEquals("00dc0589", DanmakuUserFilter.normalizeMidHashRule("dc0589"))
        assertEquals("00dc0589", DanmakuUserFilter.normalizeMidHashRule("DC0589"))
    }

    @Test
    fun `long numeric value treated as mid and converted to crc32 hash`() {
        val mid = 123456789L
        val expected = DanmakuUserFilter.midHashOfMid(mid)
        assertEquals(8, expected.length)
        assertEquals(expected, DanmakuUserFilter.normalizeMidHashRule(mid.toString()))
    }

    @Test
    fun `invalid mid hash rule returns null`() {
        assertNull(DanmakuUserFilter.normalizeMidHashRule(""))
        assertNull(DanmakuUserFilter.normalizeMidHashRule("   "))
        assertNull(DanmakuUserFilter.normalizeMidHashRule("zzzzzzzz"))
        assertNull(DanmakuUserFilter.normalizeMidHashRule("123456789012345678999"))
        assertNull(DanmakuUserFilter.normalizeMidHashRule("0x1234"))
    }

    @Test
    fun `regex literal parsed with flags`() {
        val plain = DanmakuUserFilter.normalizeRegexRule("/abc/")
        assertNotNull(plain)
        assertEquals("abc", plain!!.pattern)

        val withFlags = DanmakuUserFilter.normalizeRegexRule("/abc/im")
        assertNotNull(withFlags)
        assertEquals("abc", withFlags!!.pattern)
        assertTrue(withFlags.options.contains(RegexOption.IGNORE_CASE))
        assertTrue(withFlags.options.contains(RegexOption.MULTILINE))

        // 非字面量按裸正则处理。
        val bare = DanmakuUserFilter.normalizeRegexRule("^\\d+$")
        assertNotNull(bare)
        assertTrue(bare!!.pattern.contains("\\d"))
    }

    @Test
    fun `invalid regex returns null`() {
        assertNull(DanmakuUserFilter.normalizeRegexRule(""))
        assertNull(DanmakuUserFilter.normalizeRegexRule("[[[["))
    }

    @Test
    fun `escaped slash inside regex literal`() {
        // /a\/b/ 的 pattern 是 a\/b（正则里 \/ 即 /）。
        val regex = DanmakuUserFilter.normalizeRegexRule("/a\\/b/")
        assertNotNull(regex)
        assertTrue(regex!!.pattern.contains("a"))
    }

    @Test
    fun `empty filter reports empty`() {
        assertTrue(DanmakuUserFilter.EMPTY.isEmpty())
        assertTrue(DanmakuUserFilter(keywords = listOf("a")).isNotEmpty())
    }
}
