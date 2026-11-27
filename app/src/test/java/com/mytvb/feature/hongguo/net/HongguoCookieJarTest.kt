package com.mytvb.feature.hongguo.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号 cookie 容器单测（issue#139 回归）：
 * sms_login 的会话靠 Set-Cookie 下发，jar 必须能收、能取；跨域同名 sessionid 必须
 * 以最近一次登录域为准，否则「先扫码再短信登录」会读到旧票据。
 */
class HongguoCookieJarTest {

    private fun cookie(host: String, name: String, value: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(host).path("/").build()

    @Test
    fun `saves set-cookie and exposes sessionid`() {
        val jar = HongguoCookieJar()
        jar.saveFromResponse(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl(),
            listOf(
                cookie("api5-normal-lq.fqnovel.com", "sessionid", "sid-phone"),
                cookie("api5-normal-lq.fqnovel.com", "sid_guard", "guard")
            )
        )
        assertEquals("sid-phone", jar.value("sessionid"))
        assertEquals("guard", jar.value("sid_guard"))
        assertEquals("api5-normal-lq.fqnovel.com", jar.loginHost)
        assertEquals("sid-phone", jar.snapshot()["sessionid"])
    }

    @Test
    fun `newer login host wins over stale cross-domain sessionid`() {
        val jar = HongguoCookieJar()
        jar.saveFromResponse(
            "https://hongguoduanju.com/passport/web/check_qrconnect/".toHttpUrl(),
            listOf(cookie("hongguoduanju.com", "sessionid", "sid-qr"))
        )
        jar.saveFromResponse(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl(),
            listOf(cookie("api5-normal-lq.fqnovel.com", "sessionid", "sid-phone"))
        )
        assertEquals("api5-normal-lq.fqnovel.com", jar.loginHost)
        assertEquals("sid-phone", jar.value("sessionid"))
        assertEquals("sid-phone", jar.snapshot()["sessionid"])
    }

    @Test
    fun `later login on the other channel also wins`() {
        val jar = HongguoCookieJar()
        jar.saveFromResponse(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl(),
            listOf(cookie("api5-normal-lq.fqnovel.com", "sessionid", "sid-phone"))
        )
        jar.saveFromResponse(
            "https://hongguoduanju.com/passport/web/check_qrconnect/".toHttpUrl(),
            listOf(cookie("hongguoduanju.com", "sessionid", "sid-qr"))
        )
        assertEquals("hongguoduanju.com", jar.loginHost)
        assertEquals("sid-qr", jar.value("sessionid"))
    }

    @Test
    fun `put from import channel sets login host`() {
        val jar = HongguoCookieJar()
        jar.put("hongguoduanju.com", "sessionid", "sid-import")
        assertEquals("hongguoduanju.com", jar.loginHost)
        assertEquals("sid-import", jar.value("sessionid"))
    }

    @Test
    fun `expired cookie is removed`() {
        val jar = HongguoCookieJar()
        jar.saveFromResponse(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl(),
            listOf(cookie("api5-normal-lq.fqnovel.com", "sessionid", "sid-phone"))
        )
        // 已过期形态：OkHttp 的 Builder 把 <=0 归一成 Long.MAX_VALUE（session cookie），
        // 用 1L 表达「1970 年就过期」的删除指令
        jar.saveFromResponse(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("sessionid").value("gone")
                    .domain("api5-normal-lq.fqnovel.com").path("/").expiresAt(1L).build()
            )
        )
        assertTrue(jar.value("sessionid").isEmpty())
    }

    @Test
    fun `clear drops everything`() {
        val jar = HongguoCookieJar()
        jar.put("hongguoduanju.com", "sessionid", "sid")
        jar.clear()
        assertEquals("", jar.loginHost)
        assertTrue(jar.snapshot().isEmpty())
        assertTrue(jar.value("sessionid").isEmpty())
    }

    @Test
    fun `domain cookie from register host is sent to passport host`() {
        val jar = HongguoCookieJar()
        // 注册响应（log5-applog）下发的设备级 cookie 带 Domain=.fqnovel.com
        jar.put("log5-applog.fqnovel.com", "odin_tt", "odin", "fqnovel.com")
        val loaded = jar.loadForRequest(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl()
        )
        assertTrue("跨子域设备 cookie 必须随 sms_login 回发（1202 根因）",
            loaded.any { it.name == "odin_tt" && it.value == "odin" })
    }

    @Test
    fun `host-only cookie does not cross subdomains`() {
        val jar = HongguoCookieJar()
        // 无 Domain 属性 = host-only（put 缺省形态），只在同 host 请求回发
        jar.put("log5-applog.fqnovel.com", "ttreq", "req-only")
        val toPassport = jar.loadForRequest(
            "https://api5-normal-lq.fqnovel.com/passport/mobile/sms_login/".toHttpUrl()
        )
        assertTrue("host-only cookie 不得跨子域回发",
            toPassport.none { it.name == "ttreq" })
        val toSelf = jar.loadForRequest(
            "https://log5-applog.fqnovel.com/service/2/device_register/".toHttpUrl()
        )
        assertTrue(toSelf.any { it.name == "ttreq" })
    }
}
