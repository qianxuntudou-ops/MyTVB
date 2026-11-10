package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * cookieStore.xml 导入解析验证（对照 HGTV 1.3.6 F0.AbstractC0133c 语义）：
 * TLV hex 值解码 / snssdk.com 域过滤 / 白名单拼串兜底（sessionid 缺失必须报错）。
 */
class HongguoCookieStoreParserTest {

    /** 测试侧 TLV 编码器：每项 't' + 2 字节 BE 长度 + UTF-8 字节，整体 hex。 */
    private fun tlvHex(vararg parts: String): String {
        val out = StringBuilder()
        for (p in parts) {
            val bytes = p.toByteArray(Charsets.UTF_8)
            out.append("74").append("%04x".format(bytes.size)).append(
                bytes.joinToString("") { "%02x".format(it) }
            )
        }
        return out.toString()
    }

    private fun entry(name: String, valueHex: String): String =
        "<string name=\"https://snssdk.com/|$name\">$valueHex</string>"

    @Test
    fun decodeSinglePair() {
        val sid = "0123456789abcdef0123456789abcdef"
        assertEquals(sid, HongguoCookieStoreParser.decodeTlvCookieValue(tlvHex("sessionid", sid), "sessionid"))
    }

    @Test
    fun decodeMultiplePairsAndPickByName() {
        // 数组里多对键值，按名字取下一项（HGTV a() 的 indexOf 语义）
        val hex = tlvHex("odin_tt", "abc", "sessionid", "deadbeef", "sid_tt", "x")
        assertEquals("deadbeef", HongguoCookieStoreParser.decodeTlvCookieValue(hex, "sessionid"))
        assertEquals("abc", HongguoCookieStoreParser.decodeTlvCookieValue(hex, "odin_tt"))
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(HongguoCookieStoreParser.decodeTlvCookieValue("", "sessionid"))
        assertNull(HongguoCookieStoreParser.decodeTlvCookieValue("zz", "sessionid"))          // 非 hex
        assertNull(HongguoCookieStoreParser.decodeTlvCookieValue("740", "sessionid"))          // 奇数长度
        assertNull(HongguoCookieStoreParser.decodeTlvCookieValue(tlvHex("sid_tt", "v"), "sessionid")) // 名字不在数组
    }

    @Test
    fun decodeToleratesNonTlvPrefix() {
        // 前面有无关字节（非 't' 或校验不过）应滑动跳过，不整串作废
        val sid = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
        val prefix = "ff00ff"
        assertEquals(sid, HongguoCookieStoreParser.decodeTlvCookieValue(prefix + tlvHex("sessionid", sid), "sessionid"))
    }

    @Test
    fun parseXmlFiltersDomainAndDecodes() {
        val sid = "0123456789abcdef0123456789abcdef"
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="https://snssdk.com/|sessionid">${tlvHex("sessionid", sid)}</string>
                <string name="https://snssdk.com/|sid_guard">${tlvHex("sid_guard", "123%7C1762700000%7C15552000%7C--)")}</string>
                <string name="https://snssdk.com/|odin_tt">${tlvHex("odin_tt", "odin-value")}</string>
                <string name="https://fqnovel.com/|sessionid">${tlvHex("sessionid", "should-drop")}</string>
                <string name="https://ad.snssdk.com/|ttreq">${tlvHex("ttreq", "should-drop-too")}</string>
                <string name="https://snssdk.com/|broken">nothex</string>
            </map>
        """.trimIndent()
        val cookies = HongguoCookieStoreParser.parse(xml)
        assertEquals(sid, cookies["sessionid"])
        assertEquals("123%7C1762700000%7C15552000%7C--)", cookies["sid_guard"])
        assertEquals("odin-value", cookies["odin_tt"])
        // fqnovel 域/子域/解码失败条目全部过滤
        assertNull(cookies["ttreq"])
    }

    @Test
    fun parseRejectsMissingSessionid() {
        val xml = "<map>${entry("odin_tt", tlvHex("odin_tt", "v"))}</map>"
        assertThrows(HongguoAccount.AccountException::class.java) {
            HongguoCookieStoreParser.parse(xml)
        }
    }

    @Test
    fun parseAcceptsSessionidSsFallback() {
        val xml = "<map>${entry("sessionid_ss", tlvHex("sessionid_ss", "ff".repeat(16)))}</map>"
        assertEquals("ff".repeat(16), HongguoCookieStoreParser.parse(xml)["sessionid_ss"])
    }
}
