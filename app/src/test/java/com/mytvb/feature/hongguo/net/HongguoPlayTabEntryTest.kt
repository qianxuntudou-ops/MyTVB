package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * video_tab 路由响应解析（HongguoPlay.tabEntryOf）与空响应熔断（递增冷却）验证。
 * video_tab 行在 data.video_model_data[vid]（2026-11-23 真网响应形态，video_model 为
 * JSON-in-string），少数形态直接 data[vid]；业务码透传进 PlayException.code 供恢复链判定。
 * 纯函数，不打网络。
 */
class HongguoPlayTabEntryTest {

    private val vid = "7701937936731737112"

    /** 真网形态的 video_model 字符串（节选）。 */
    private val modelText = "{\"status\":10,\"message\":\"success\",\"video_duration\":173.315," +
        "\"video_list\":[{\"codec_type\":\"bytevc1\",\"definition\":\"1080p\"}]}"

    @After
    fun tearDown() {
        HongguoPlay.resetCooldownForTest()
    }

    private fun entryOf(model: String) = JSONObject().put("video_model", model)

    private fun payload(rows: JSONObject) = JSONObject()
        .put("code", 0).put("message", "SUCCESS")
        .put("data", JSONObject().put("video_model_data", rows))

    // ---- 响应解析 ----

    @Test
    fun parsesVideoModelDataStringForm() {
        val entry = HongguoPlay().tabEntryOf(vid, payload(JSONObject().put(vid, entryOf(modelText))).toString())
        // entry 保留 video_model 键（字符串形态由 parseEntry 二跳），不在此处展开
        val model = JSONObject(entry.getString("video_model"))
        assertEquals(10, model.getInt("status"))
        assertEquals(1, model.getJSONArray("video_list").length())
    }

    @Test
    fun parsesObjectFormAndDirectDataRows() {
        // video_model 为对象而非字符串
        val asObject = payload(JSONObject().put(vid, JSONObject().put("video_model", JSONObject(modelText))))
        assertTrue(HongguoPlay().tabEntryOf(vid, asObject.toString()).getJSONObject("video_model").has("video_list"))
        // 无 video_model_data 层，行直接挂在 data 下
        val direct = JSONObject().put("code", 0).put("data", JSONObject().put(vid, entryOf(modelText)))
        assertEquals(modelText, HongguoPlay().tabEntryOf(vid, direct.toString()).getString("video_model"))
    }

    @Test
    fun fallsBackToFirstRowWhenVidMissing() {
        // 行键与本集 vid 不符时取首行（服务端偶发改键），不直接判无片源
        val rows = JSONObject().put("7700000000000000000", entryOf(modelText))
        assertEquals(modelText, HongguoPlay().tabEntryOf(vid, payload(rows).toString()).getString("video_model"))
    }

    @Test
    fun businessCodeBecomesPlayExceptionCode() {
        for (key in listOf("code", "Code")) {
            val text = JSONObject().put(key, 101000).put("data", JSONObject()).toString()
            try {
                HongguoPlay().tabEntryOf(vid, text)
                fail("expected PlayException for $key=101000")
            } catch (e: HongguoPlay.PlayException) {
                // BIZ_101000 = 设备拒收，上层恢复链据此触发重注册
                assertEquals("BIZ_101000", e.code)
            }
        }
    }

    @Test
    fun missingRowIsMissingModel() {
        val text = JSONObject().put("code", 0).put("data", JSONObject().put("video_model_data", JSONObject())).toString()
        try {
            HongguoPlay().tabEntryOf(vid, text)
            fail("expected PlayException")
        } catch (e: HongguoPlay.PlayException) {
            assertEquals("MISSING_MODEL", e.code)
        }
    }

    // ---- 空响应熔断 ----

    @Test
    fun cooldownGrowsThenCaps() {
        // 3 → 6 → 12 → 24 → 30 分钟封顶
        assertEquals(3L * 60_000L, HongguoPlay.backoffMs(1))
        assertEquals(6L * 60_000L, HongguoPlay.backoffMs(2))
        assertEquals(12L * 60_000L, HongguoPlay.backoffMs(3))
        assertEquals(24L * 60_000L, HongguoPlay.backoffMs(4))
        assertEquals(30L * 60_000L, HongguoPlay.backoffMs(5))
        assertEquals(30L * 60_000L, HongguoPlay.backoffMs(9))
    }

    @Test
    fun emptyBodyStartsCooldownAndSuccessClearsIt() {
        assertEquals(0L, HongguoPlay.cooldownRemainingMs())
        // 连续空响应逐次加长冷却
        assertEquals(3L * 60_000L, HongguoPlay.noteEmptyBody())
        assertEquals(6L * 60_000L, HongguoPlay.noteEmptyBody())
        val now = System.currentTimeMillis()
        assertTrue(HongguoPlay.cooldownRemainingMs(now) in (6L * 60_000L - 5_000L)..(6L * 60_000L))
        // 冷却期随时间流逝递减
        assertTrue(HongguoPlay.cooldownRemainingMs(now + 60_000L) <= 5L * 60_000L)
        // 成功一次即清零，下一次空响应回到最短冷却
        HongguoPlay.noteSuccess()
        assertEquals(0L, HongguoPlay.cooldownRemainingMs())
        assertEquals(3L * 60_000L, HongguoPlay.noteEmptyBody())
    }

    @Test
    fun cooldownRejectsWithoutNetwork() {
        // 冷却期内取址直接抛 COOLDOWN：身份是任意假值，若发了网络请求会先在别处失败
        HongguoPlay.noteEmptyBody()
        try {
            HongguoPlay().playUrl(
                vid,
                HongguoDeviceRegister.Identity("1", "1", "c", "o")
            )
            fail("expected PlayException")
        } catch (e: HongguoPlay.PlayException) {
            assertEquals(HongguoPlay.COOLDOWN, e.code)
            assertTrue("message=${e.message}", e.message!!.contains("分钟后再试"))
        }
    }
}
