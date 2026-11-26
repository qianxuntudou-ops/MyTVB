package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [findDecisionConfDeeply]（对照 HGTV C0163h2.e 的兜底挑战递归扫描）语义。 */
class HongguoSmsMfaConfTest {

    @Test
    fun `risk_info 顶层命中`() {
        val data = JSONObject("""{"error_code":"0","risk_info":{"subtype":"x"}}""")
        assertEquals("x", findDecisionConfDeeply(data)?.optString("subtype"))
    }

    @Test
    fun `任意含 decision_conf 的键名命中`() {
        val data = JSONObject("""{"sms_secondary_decision_conf":{"subtype":"m"}}""")
        assertEquals("m", findDecisionConfDeeply(data)?.optString("subtype"))
    }

    @Test
    fun `字符串形态解析为对象`() {
        val data = JSONObject("""{"decision_conf":"{\"subtype\":\"ok\"}"}""")
        assertEquals("ok", findDecisionConfDeeply(data)?.optString("subtype"))
    }

    @Test
    fun `深度3以内的嵌套命中`() {
        val data = JSONObject("""{"a":{"b":{"c":{"decision_conf":{"subtype":"deep"}}}}}""")
        assertEquals("deep", findDecisionConfDeeply(data)?.optString("subtype"))
    }

    @Test
    fun `超过深度3不命中`() {
        val data = JSONObject("""{"a":{"b":{"c":{"d":{"decision_conf":{"subtype":"too-deep"}}}}}}""")
        assertNull(findDecisionConfDeeply(data))
    }

    @Test
    fun `非法JSON字符串跳过继续扫`() {
        val data = JSONObject("""{"decision_conf":"not-json","ok":{"decision_conf":{"subtype":"fallback"}}}""")
        assertEquals("fallback", findDecisionConfDeeply(data)?.optString("subtype"))
    }

    @Test
    fun `无相关键返回null`() {
        assertNull(findDecisionConfDeeply(JSONObject("""{"message":"error","data":123}""")))
    }
}
