package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 红果播放 host/画像热更（HongguoNetProfiles）验证：
 * host 必须 https + *.fqnovel.com（防投毒）；画像四元组格式校验，任一非法整档
 * 丢弃（画像三处同源自洽前提，禁止半新半旧）；无覆写回落内置档。
 * applyFrom 落盘与 restore 依赖 Context，由 MuMu 冒烟覆盖，不进纯 JVM 测试。
 */
class HongguoNetProfilesTest {

    @org.junit.After
    fun tearDown() {
        HongguoNetProfiles.resetForTest()
    }

    private fun net(
        host: String = "https://api5-normal-sinfonlinea.fqnovel.com",
        type: String = "25053RT47C",
        brand: String = "Redmi",
        os: String = "16",
        channel: String = "update_64"
    ): JSONObject = JSONObject()
        .put("play_host", host)
        .put("device_type", type)
        .put("device_brand", brand)
        .put("os_version", os)
        .put("channel", channel)

    @Test
    fun parseValidNet() {
        val n = HongguoNetProfiles.parseNet(net())!!
        assertEquals("https://api5-normal-sinfonlinea.fqnovel.com", n.playHost)
        // 远程档只能改 host/channel/routes；画像永远是本机真实设备
        assertEquals(HongguoEnv.deviceType, n.deviceType)
        assertEquals("update_64", n.channel)
    }

    @Test
    fun parseRejectsPoisonedHost() {
        // http 明文 / 非 fqnovel 域 / 带路径 / IP 直连——全部拒绝（防投毒）
        assertNull(HongguoNetProfiles.parseNet(net(host = "http://api5-normal-sinfonlineb.fqnovel.com")))
        assertNull(HongguoNetProfiles.parseNet(net(host = "https://evil.example.com")))
        assertNull(HongguoNetProfiles.parseNet(net(host = "https://api5-normal-sinfonlineb.fqnovel.com/x")))
        assertNull(HongguoNetProfiles.parseNet(net(host = "https://123.45.67.89")))
    }

    @Test
    fun parseRejectsBadProfileFields() {
        // 画像字段不再接受远程覆写（必须与本机注册一致）：写了非法值也不影响，
        // 一律用运行设备真实值；channel 仍有格式校验
        listOf(
            HongguoNetProfiles.parseNet(net(type = "25053RT47C;drop")),
            HongguoNetProfiles.parseNet(net(brand = "Redmi2")),
            HongguoNetProfiles.parseNet(net(os = "16.1")),
        ).forEach {
            assertEquals(HongguoEnv.deviceType, it!!.deviceType)
            assertEquals(HongguoEnv.deviceBrand, it.deviceBrand)
            assertEquals(HongguoEnv.osVersion, it.osVersion)
        }
        assertNull(HongguoNetProfiles.parseNet(net(channel = "UPDATE_64")))
    }

    @Test
    fun parseRejectsMissingFields() {
        assertNull(HongguoNetProfiles.parseNet(null))
        // 空对象 = 全是缺省 → 合法（等价内置档）；显式非法值才整档丢弃
        assertEquals(HongguoNetProfiles.playHost(), HongguoNetProfiles.parseNet(JSONObject())!!.playHost)
        // 单字段缺失（channel）不再丢弃：只覆写写了的字段，机型仍用运行设备真实值
        val partial = HongguoNetProfiles.parseNet(JSONObject().put("play_host", "https://api5-normal-sinfonlinea.fqnovel.com"))!!
        assertEquals("https://api5-normal-sinfonlinea.fqnovel.com", partial.playHost)
        assertEquals(HongguoEnv.deviceType, partial.deviceType)
    }

    @Test
    fun builtinFallbackWithoutOverride() {
        HongguoNetProfiles.resetForTest()
        assertEquals("https://api5-normal-sinfonlineb.fqnovel.com", HongguoNetProfiles.playHost())
        // 2026-11-24 起画像按运行设备如实生成（与注册同源），不再是固定的 Redmi 模板
        assertEquals(HongguoEnv.deviceType, HongguoNetProfiles.deviceType())
        assertEquals(HongguoEnv.deviceBrand, HongguoNetProfiles.deviceBrand())
        assertEquals(HongguoEnv.osVersion, HongguoNetProfiles.osVersion())
        assertEquals("update_64", HongguoNetProfiles.channel())
        // 内置档：只有 preload 一条（2026-11-24 起不做自动回退）；列表其余值只作记录
        assertEquals(listOf("preload"), HongguoNetProfiles.playRoutes())
    }

    // ---- 取源路由（hg_net.play_routes，2026-11-23） ----

    @Test
    fun parsePlayRoutesInOrder() {
        val n = HongguoNetProfiles.parseNet(
            net().put("play_routes", org.json.JSONArray(listOf("preload", "v5tab", "player")))
        )!!
        // 顺序即回退顺序，原样保留
        assertEquals(listOf("preload", "v5tab", "player"), n.playRoutes)
    }

    @Test
    fun parsePlayRoutesFiltersUnknownAndDuplicates() {
        val n = HongguoNetProfiles.parseNet(
            net().put("play_routes", org.json.JSONArray(listOf("v5tab", "bogus", "v5tab", "")))
        )!!
        // 未知路由名与重复项剔除，不影响 host/画像整档采用
        assertEquals(listOf("v5tab"), n.playRoutes)
        assertEquals("https://api5-normal-sinfonlinea.fqnovel.com", n.playHost)
    }

    @Test
    fun parsePlayRoutesFallsBackToBuiltin() {
        // 缺失 / 非数组 / 全非法 → 保持内置默认（preload 单条）
        assertEquals(listOf("preload"), HongguoNetProfiles.parseNet(net())!!.playRoutes)
        assertEquals(listOf("preload"), HongguoNetProfiles.parseNet(net().put("play_routes", "v5tab"))!!.playRoutes)
        assertEquals(
            listOf("preload"),
            HongguoNetProfiles.parseNet(net().put("play_routes", org.json.JSONArray(listOf("bogus"))))!!.playRoutes
        )
    }

    // ---- TNC httpDns 直连表（hg_dns，2026-11-22 豁免入口段） ----

    @Test
    fun parseValidDnsTable() {
        val t = HongguoNetProfiles.parseDnsTable(
            JSONObject().put("api5-normal-sinfonlineb.fqnovel.com", org.json.JSONArray(listOf("101.126.1.161", "101.126.1.156")))
        )!!
        assertEquals(listOf("101.126.1.161", "101.126.1.156"), t["api5-normal-sinfonlineb.fqnovel.com"])
    }

    @Test
    fun parseDnsTableRejectsPoisonedHost() {
        // 非红果域不许注入 IP（防投毒：劫持 B 站/任意站点的解析）
        assertNull(
            HongguoNetProfiles.parseDnsTable(
                JSONObject().put("evil.example.com", org.json.JSONArray(listOf("1.2.3.4")))
            )
        )
        assertNull(
            HongguoNetProfiles.parseDnsTable(
                JSONObject().put("fqnovel.com.evil.example.com", org.json.JSONArray(listOf("1.2.3.4")))
            )
        )
    }

    @Test
    fun parseDnsTableRejectsBadIps() {
        // 空 IP 列表 / 非 IPv4 字面量（域名值/端口/IPv6）——整表丢弃
        assertNull(
            HongguoNetProfiles.parseDnsTable(
                JSONObject().put("api5-normal-lf.fqnovel.com", org.json.JSONArray(emptyList<String>()))
            )
        )
        assertNull(
            HongguoNetProfiles.parseDnsTable(
                JSONObject().put("api5-normal-lf.fqnovel.com", org.json.JSONArray(listOf("api.fqnovel.com")))
            )
        )
        assertNull(
            HongguoNetProfiles.parseDnsTable(
                JSONObject().put("api5-normal-lf.fqnovel.com", org.json.JSONArray(listOf("101.126.1.161:443")))
            )
        )
    }

    @Test
    fun builtinDnsTableHasPlaybackHosts() {
        HongguoNetProfiles.resetForTest()
        val t = HongguoNetProfiles.dnsTable()
        // 播放 host 必须在表内（豁免入口段直连是本表的存在意义）
        org.junit.Assert.assertTrue(t.containsKey(HongguoNetProfiles.playHost().removePrefix("https://")))
        // 内置档 IP 全为 IPv4 字面量
        t.values.flatten().forEach { ip ->
            org.junit.Assert.assertTrue("bad builtin ip $ip", ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
        }
    }
}
