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
        assertEquals("25053RT47C", n.deviceType)
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
        // 画像任一非法整档丢弃：型号带特殊字符 / 品牌 brand 带数字 / os 带点 / channel 大写
        assertNull(HongguoNetProfiles.parseNet(net(type = "25053RT47C;drop")))
        assertNull(HongguoNetProfiles.parseNet(net(brand = "Redmi2")))
        assertNull(HongguoNetProfiles.parseNet(net(os = "16.1")))
        assertNull(HongguoNetProfiles.parseNet(net(channel = "UPDATE_64")))
    }

    @Test
    fun parseRejectsMissingFields() {
        assertNull(HongguoNetProfiles.parseNet(null))
        assertNull(HongguoNetProfiles.parseNet(JSONObject()))
        // 单字段缺失（channel）整档丢弃
        assertNull(HongguoNetProfiles.parseNet(JSONObject().put("play_host", "https://api5-normal-sinfonlinea.fqnovel.com")))
    }

    @Test
    fun builtinFallbackWithoutOverride() {
        HongguoNetProfiles.resetForTest()
        assertEquals("https://api5-normal-sinfonlineb.fqnovel.com", HongguoNetProfiles.playHost())
        assertEquals("25053RT47C", HongguoNetProfiles.deviceType())
        assertEquals("Redmi", HongguoNetProfiles.deviceBrand())
        assertEquals("16", HongguoNetProfiles.osVersion())
        assertEquals("update_64", HongguoNetProfiles.channel())
    }
}
