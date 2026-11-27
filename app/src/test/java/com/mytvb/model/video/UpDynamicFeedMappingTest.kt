package com.mytvb.model.video

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单UP动态流（x/polymer/web-dynamic/desktop/v1/feed/space）条目映射测试（issue#151）。
 * JSON 形态取自 2026-11-27 真实响应样本：aid 为字符串数字、stat/duration_text 为
 * 格式化文本、badge.text 区分「投稿视频/动态视频」。
 */
class UpDynamicFeedMappingTest {

    private val gson = Gson()

    @Test
    fun `AV 动态映射成 VideoModel 且字符串aid正常解析`() {
        val json = """
            {
              "type": "DYNAMIC_TYPE_AV",
              "modules": [
                {"module_type": "MODULE_TYPE_AUTHOR", "module_author": {
                  "pub_ts": 1795748239,
                  "user": {"mid": 3707054489340601, "name": "Tim潘子", "face": "http://face/1.jpg"}
                }},
                {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {
                  "dyn_archive": {
                    "aid": "117686147222313",
                    "bvid": "BV1o61HzWE79",
                    "title": "我是Tim，我想拍外星人...",
                    "cover": "http://i1.hdslb.com/bfs/archive/1.jpg",
                    "duration_text": "00:46",
                    "badge": {"text": "投稿视频"},
                    "stat": {"danmaku": "369", "like": "1.5万", "play": "13.3万"}
                  }
                }}
              ]
            }
        """.trimIndent()
        val item = gson.fromJson(json, UpDynamicFeedItem::class.java)
        val video = item.toVideoModel()

        assertTrue(video != null)
        video!!
        assertEquals(117686147222313L, video.aid)
        assertEquals("BV1o61HzWE79", video.bvid)
        assertEquals("我是Tim，我想拍外星人...", video.title)
        assertEquals("http://i1.hdslb.com/bfs/archive/1.jpg", video.pic)
        assertEquals(46L, video.duration)
        assertEquals(1795748239L, video.pubDate)
        assertEquals(3707054489340601L, video.owner?.mid)
        assertEquals("Tim潘子", video.owner?.name)
        // 「13.3万」还原 133000，展示层 NumberUtils 再缩写回「13.3万」，视觉无损
        assertEquals(133000L, video.stat?.view)
        assertEquals(369L, video.stat?.danmaku)
    }

    @Test
    fun `普通投稿视频动态不亮角标`() {
        val item = avItem(badgeText = "投稿视频")
        assertEquals("", item.toVideoModel()!!.dynamicBadgeText)
    }

    @Test
    fun `动态视频亮角标且显示官方原文`() {
        val item = avItem(badgeText = "动态视频")
        assertEquals("动态视频", item.toVideoModel()!!.dynamicBadgeText)
    }

    @Test
    fun `角标判据只排除投稿视频_官方文案变更也能命中`() {
        // 动态视频文案随官方版本可能变（判据为「有 badge 且非投稿视频」），
        // 避免把过滤钉死在一个没见过的字面量上导致页签永远为空
        val item = avItem(badgeText = "动态投稿")
        assertEquals("动态投稿", item.toVideoModel()!!.dynamicBadgeText)
    }

    @Test
    fun `无badge的AV条目不亮角标`() {
        val item = avItem(badgeText = null)
        assertEquals("", item.toVideoModel()!!.dynamicBadgeText)
    }

    @Test
    fun `非AV类型过滤为null`() {
        // 图文/转发动态没有稿件信息（module_dynamic 为空或只有 draw），映射为 null
        val draw = """
            {"type": "DYNAMIC_TYPE_DRAW", "modules": [
              {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {"major": {"draw": {}}}}
            ]}
        """.trimIndent()
        assertNull(gson.fromJson(draw, UpDynamicFeedItem::class.java).toVideoModel())

        val forward = """
            {"type": "DYNAMIC_TYPE_FORWARD", "modules": []}
        """.trimIndent()
        assertNull(gson.fromJson(forward, UpDynamicFeedItem::class.java).toVideoModel())
    }

    @Test
    fun `新版竖屏视频动态类型也能识别`() {
        // 判据不绑定 type 字面量：只要能取到稿件信息即视为视频动态，
        // 防止官方把动态视频改成 DYNAMIC_TYPE_COMMON_VERTICAL 等类型后页签整体变空
        val json = """
            {
              "type": "DYNAMIC_TYPE_COMMON_VERTICAL",
              "modules": [
                {"module_type": "MODULE_TYPE_AUTHOR", "module_author": {"pub_ts": 1795748239}},
                {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {
                  "dyn_archive": {
                    "aid": "117686147222313",
                    "bvid": "BV1o61HzWE79",
                    "title": "竖屏动态",
                    "cover": "http://c.jpg",
                    "duration_text": "00:30",
                    "badge": {"text": "动态视频"},
                    "stat": {"danmaku": "1", "play": "100"}
                  }
                }}
              ]
            }
        """.trimIndent()
        val video = gson.fromJson(json, UpDynamicFeedItem::class.java).toVideoModel()
        assertEquals("BV1o61HzWE79", video?.bvid)
        assertEquals("动态视频", video?.dynamicBadgeText)
    }

    @Test
    fun `legacy major archive 形态也能识别`() {
        val json = """
            {
              "type": "DYNAMIC_TYPE_AV",
              "modules": [
                {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {
                  "major": {"archive": {
                    "aid": "117686147222313",
                    "bvid": "BV1o61HzWE79",
                    "title": "legacy",
                    "cover": "http://c.jpg",
                    "duration_text": "00:10",
                    "badge": {"text": "动态视频"}
                  }}
                }}
              ]
            }
        """.trimIndent()
        val video = gson.fromJson(json, UpDynamicFeedItem::class.java).toVideoModel()
        assertEquals("BV1o61HzWE79", video?.bvid)
        assertEquals("动态视频", video?.dynamicBadgeText)
    }

    @Test
    fun `无dynArchive的AV条目过滤为null`() {
        val json = """
            {"type": "DYNAMIC_TYPE_AV", "modules": [
              {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {}}
            ]}
        """.trimIndent()
        assertNull(gson.fromJson(json, UpDynamicFeedItem::class.java).toVideoModel())
    }

    @Test
    fun `时长文本解析含小时段`() {
        val item = avItem(durationText = "01:02:03")
        assertEquals(3723L, item.toVideoModel()!!.duration)
    }

    private fun avItem(
        badgeText: String? = null,
        durationText: String = "00:46"
    ): UpDynamicFeedItem {
        val badge = badgeText?.let { """, "badge": {"text": "$it"}""" } ?: ""
        val json = """
            {
              "type": "DYNAMIC_TYPE_AV",
              "modules": [
                {"module_type": "MODULE_TYPE_AUTHOR", "module_author": {"pub_ts": 1795748239}},
                {"module_type": "MODULE_TYPE_DYNAMIC", "module_dynamic": {
                  "dyn_archive": {
                    "aid": "117686147222313",
                    "bvid": "BV1o61HzWE79",
                    "title": "t",
                    "cover": "http://c.jpg",
                    "duration_text": "$durationText"$badge,
                    "stat": {"danmaku": "369", "play": "13.3万"}
                  }
                }}
              ]
            }
        """.trimIndent()
        return gson.fromJson(json, UpDynamicFeedItem::class.java)
    }
}
