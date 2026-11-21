package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * applog 埋点上报（HongguoApplog）组包验证：
 * body=gzip(明文 JSON{magic_tag:ss_app_log, header, events, seq})，事件仅
 * __activate+app_launch 两个通用事件（克制原则）；header/query 身份与注册
 * 同源。真网接收由 HongguoExpandProbeTest.probeApplog 覆盖（四发
 * message:success 实证），此处只验组包形态。
 */
class HongguoApplogTest {

    private val id = HongguoDeviceRegister.Identity(
        deviceId = "955954222091312", installId = "955954222095408",
        cdid = "35aaa33b-121b-48e6-bd17-12f0d6b95673",
        openUdid = "0123456789abcdef"
    )

    private fun gunzip(b: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(b)).readBytes().toString(Charsets.UTF_8)

    @Test
    fun buildReportShape() {
        val r = HongguoApplog.buildReport(id, 1795249007000L)
        val root = JSONObject(gunzip(r.gzBody))
        assertEquals("ss_app_log", root.getString("magic_tag"))
        assertEquals(1795249007000L, root.getLong("_gen_time"))
        val header = root.getJSONObject("header")
        assertEquals(id.deviceId, header.getString("device_id"))
        assertEquals(id.installId, header.getString("iid"))
        assertEquals(id.openUdid, header.getString("openudid"))
        assertEquals(8662, header.getInt("aid"))
        assertEquals("android", header.getString("os"))
        // 版本走档案（与播放通道同源自洽）
        assertEquals(HongguoVersionProfiles.default.code, header.getString("version_code"))
        val events = root.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("__activate", events.getJSONObject(0).getString("event"))
        assertEquals("app_launch", events.getJSONObject(1).getString("event"))
        assertTrue(root.getLong("seq") >= 1)
        // query 身份四件套齐全（ts/_rticket 与组包时间同源）
        assertTrue(r.rawQuery.contains("device_id=${id.deviceId}"))
        assertTrue(r.rawQuery.contains("iid=${id.installId}"))
        assertTrue(r.rawQuery.contains("_rticket=1795249007000"))
        assertTrue(r.rawQuery.contains("version_code=${HongguoVersionProfiles.default.code}"))
    }

    @Test
    fun seqIncrements() {
        val a = JSONObject(gunzip(HongguoApplog.buildReport(id, 1000L).gzBody)).getLong("seq")
        val b = JSONObject(gunzip(HongguoApplog.buildReport(id, 2000L).gzBody)).getLong("seq")
        assertTrue("seq 应单调递增: $a -> $b", b > a)
    }

    @Test
    fun invalidIdentityIgnored() {
        // 空 deviceId / "0"（注册失败占位）不炸不发
        HongguoApplog.onIdentity(id.copy(deviceId = ""))
        HongguoApplog.onIdentity(id.copy(deviceId = "0"))
        HongguoApplog.resetForTest()
    }

    @Test
    fun videoPlayEventShape() {
        // 官方行为足迹：video_play 事件组包（官方 TtsFeedTabView PLAYING 首发，
        // params=group_id/item_id/enter_from/position）
        val events = org.json.JSONArray().put(
            JSONObject().put("time", 1795249007000L).put("event", "video_play")
                .put(
                    "params", JSONObject()
                        .put("group_id", "7705824595416190014")
                        .put("item_id", "7705826165692304409")
                        .put("enter_from", "cold")
                        .put("position", 1)
                )
        )
        val r = HongguoApplog.buildReport(id, 1795249007000L, events)
        val root = JSONObject(gunzip(r.gzBody))
        val ev = root.getJSONArray("events")
        assertEquals(1, ev.length())
        assertEquals("video_play", ev.getJSONObject(0).getString("event"))
        val params = ev.getJSONObject(0).getJSONObject("params")
        assertEquals("7705824595416190014", params.getString("group_id"))
        assertEquals("7705826165692304409", params.getString("item_id"))
        // header 与默认轮同源（身份四件套+版本档案）
        assertEquals(id.deviceId, root.getJSONObject("header").getString("device_id"))
    }

    @Test
    fun buildRecoStatFiltersAndKeepsShape() {
        // 官方 recommend_stat/report 回传条目提取：带 recommend_info+group_id 的
        // 保留（字段原样），缺任一者静默滤除（不自造字段防露馅）
        val rows = org.json.JSONArray()
            .put(
                JSONObject()
                    .put("series_id_str", "7705826348597529625")
                    .put("content_type", 1004)
                    .put("recommend_info", "{\"channel_id\":\"94349618414\"}")
            )
            .put(JSONObject().put("series_id_str", "111").put("content_type", 1)) // 无 reco：滤
            .put(JSONObject().put("recommend_info", "{\"x\":1}")) // 无 gid：滤
            .put(
                JSONObject() // series_id 数字态兜底
                    .put("series_id", 7706004220599618584L)
                    .put("content_type", 1)
                    .put("recommend_info", "{\"rank\":\"1\"}")
            )
        val stat = HongguoApplog.buildRecoStat(rows, 1795249007000L)
        assertEquals(2, stat.length())
        val first = stat.getJSONObject(0)
        assertEquals("7705826348597529625", first.getString("group_id"))
        assertEquals(1004, first.getInt("content_type"))
        assertEquals("{\"channel_id\":\"94349618414\"}", first.getString("recommend_info"))
        assertEquals(1, first.getInt("stat_item_type"))
        assertEquals(1795249007000L, first.getLong("client_time"))
        assertEquals("7706004220599618584", stat.getJSONObject(1).getString("group_id"))
        // 空入参零批（landpage 无 reco 字段流静默）
        assertEquals(0, HongguoApplog.buildRecoStat(null, 1L).length())
        assertEquals(0, HongguoApplog.buildRecoStat(org.json.JSONArray(), 1L).length())
    }
}
