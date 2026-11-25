package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红果网络常量（2026-11-25 起全部内置，取消云端热更）：
 * 默认取源路由 = video_model、内置 host/channel、画像与运行设备同源、内置 DNS 表。
 */
class HongguoNetProfilesTest {

    @Test
    fun builtinRouteIsVideoModelOnly() {
        // 只取首位、不做自动回退；video_model 是 2026-11-25 真网验证放行的唯一形态
        assertEquals(listOf(HongguoNetProfiles.ROUTE_VIDEO_MODEL), HongguoNetProfiles.playRoutes())
        assertEquals(HongguoNetProfiles.ROUTE_VIDEO_MODEL, HongguoNetProfiles.activeRoute())
    }

    @Test
    fun builtinHostAndChannel() {
        assertEquals("https://api5-normal-sinfonlineb.fqnovel.com", HongguoNetProfiles.playHost())
        assertEquals("update_64", HongguoNetProfiles.channel())
    }

    @Test
    fun profileFollowsRuntimeDevice() {
        // player/preload/v5tab 与注册/详情同源：如实取运行设备
        assertEquals(HongguoEnv.deviceType, HongguoNetProfiles.deviceType())
        assertEquals(HongguoEnv.deviceBrand, HongguoNetProfiles.deviceBrand())
        assertEquals(HongguoEnv.osVersion, HongguoNetProfiles.osVersion())
    }

    @Test
    fun builtinDnsTableHasPlaybackHosts() {
        val t = HongguoNetProfiles.dnsTable()
        // 播放 host 必须在表内（豁免入口段直连是本表的存在意义）
        assertTrue(t.containsKey(HongguoNetProfiles.playHost().removePrefix("https://")))
        // 内置档 IP 全为 IPv4 字面量
        t.values.flatten().forEach { ip ->
            assertTrue("bad builtin ip $ip", ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
        }
    }
}
