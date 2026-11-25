package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 红果签名链版本档案（2026-11-25 起内置常量，取消云端热更）：
 * default=74532/7.4.5.32（全通道）、plan=72232/7.2.2.32（相关作品通道）。
 */
class HongguoVersionProfilesTest {

    @Test
    fun builtinDefaultProfile() {
        assertEquals("74532", HongguoVersionProfiles.default.code)
        assertEquals("7.4.5.32", HongguoVersionProfiles.default.versionName)
    }

    @Test
    fun builtinPlanProfile() {
        assertEquals("72232", HongguoVersionProfiles.plan.code)
        assertEquals("7.2.2.32", HongguoVersionProfiles.plan.versionName)
    }
}
