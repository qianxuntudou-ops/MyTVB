package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 红果签名版本档案热更（HongguoVersionProfiles）验证：
 * 解析校验（code 5~6 位数字 / versionName 四段点分）/ 非法整档丢弃 / 内置回落。
 * applyFrom 的落盘与 restore 依赖 Context，由 MuMu 冒烟覆盖，不进纯 JVM 测试。
 */
class HongguoVersionProfilesTest {

    @org.junit.After
    fun tearDown() {
        HongguoVersionProfiles.resetForTest()
    }

    @Test
    fun parseValidProfile() {
        val json = JSONObject().put("code", "74532").put("name", "7.4.5.32")
        val profile = HongguoVersionProfiles.parseProfile(json)!!
        assertEquals("74532", profile.code)
        assertEquals("7.4.5.32", profile.versionName)
    }

    @Test
    fun parseRejectsBadCode() {
        // 4 位太短 / 7 位太长 / 带字母——全部整档丢弃
        assertNull(HongguoVersionProfiles.parseProfile(profile("7453", "7.4.5.32")))
        assertNull(HongguoVersionProfiles.parseProfile(profile("7453213", "7.4.5.32")))
        assertNull(HongguoVersionProfiles.parseProfile(profile("74a32", "7.4.5.32")))
    }

    @Test
    fun parseRejectsBadVersionName() {
        // 三段（7.4.5）/ 带字母（7.4.5.a）不合法
        assertNull(HongguoVersionProfiles.parseProfile(profile("74532", "7.4.5")))
        assertNull(HongguoVersionProfiles.parseProfile(profile("74532", "7.4.5.a")))
    }

    @Test
    fun parseRejectsMissingOrNull() {
        assertNull(HongguoVersionProfiles.parseProfile(null))
        assertNull(HongguoVersionProfiles.parseProfile(JSONObject()))
        // name 缺失时 optString 回空串，过不了校验
        assertNull(HongguoVersionProfiles.parseProfile(JSONObject().put("code", "74532")))
    }

    @Test
    fun builtinFallbackWithoutOverride() {
        HongguoVersionProfiles.resetForTest()
        assertEquals("74532", HongguoVersionProfiles.default.code)
        assertEquals("7.4.5.32", HongguoVersionProfiles.default.versionName)
        assertEquals("72232", HongguoVersionProfiles.plan.code)
        assertEquals("7.2.2.32", HongguoVersionProfiles.plan.versionName)
    }

    @Test
    fun versionJsonFieldShape() {
        // 仓库 version.json 的 hg_versions 形状必须可被解析（schema 与实现互相锁定）
        val raw = """
            {"version":"2.1.29","apk":"x.apk","notes":"n",
             "hg_versions":{"default":{"code":"74532","name":"7.4.5.32"},
                            "plan":{"code":"72232","name":"7.2.2.32"}}}
        """.trimIndent()
        val hg = JSONObject(raw).optJSONObject("hg_versions")
        val def = HongguoVersionProfiles.parseProfile(hg!!.optJSONObject("default"))
        val plan = HongguoVersionProfiles.parseProfile(hg.optJSONObject("plan"))
        assertEquals("74532", def!!.code)
        assertEquals("72232", plan!!.code)
    }

    private fun profile(code: String, name: String): JSONObject =
        JSONObject().put("code", code).put("name", name)
}
