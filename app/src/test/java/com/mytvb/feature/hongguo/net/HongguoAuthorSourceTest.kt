package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * 长按菜单「创作者/相关作品」数据源解析（2026-11-13 官方 App 抓包口径）：
 * 1) inner_feed_detail 出品方：data{ id -> base_info.author_user_info.base_info.user_name }；
 * 2) 搜索结果内嵌发布者：user_info.user_name 优先，book_record_info.author_name 兜底。
 * 样例取自 .trae/hongguo-api-capture 抓包原文（7697857990400953368=橙子漫剧）。
 */
class HongguoAuthorSourceTest {

    @Test
    fun innerFeedDetailParsesOfficialSample() {
        // 官方响应骨架（抓包原文节选：series 有作者，album 项无 base_info 自然跳过）
        val payload = JSONObject(
            """
            {"code":0,"data":{
              "7697857990400953368":{"base_info":{"author_user_info":{
                "base_info":{"user_id":"1061489014479444","user_name":"橙子漫剧"},
                "user_id":"1061489014479444"},"main_creator_user_groups":[]}},
              "7697792935663766590":{"album_info":{"album_id":0},"base_info":{}}
            }}
            """.trimIndent()
        )
        val out = HongguoClient.parseInnerFeedCreators(payload)
        assertEquals(
            mapOf("7697857990400953368" to HongguoClient.Creator("1061489014479444", "橙子漫剧")), out
        )
    }

    @Test
    fun innerFeedDetailSkipsBlankAndMissing() {
        val payload = JSONObject(
            """
            {"code":0,"data":{
              "1":{"base_info":{"author_user_info":{"base_info":{"user_name":"  "}}}},
              "2":{"base_info":{}},
              "3":{}
            }}
            """.trimIndent()
        )
        assertTrue(HongguoClient.parseInnerFeedCreators(payload).isEmpty())
    }

    @Test
    fun innerFeedDetailIgnoresCreatorGroups() {
        // 2026-11-13 用户实测反馈定案：main_creator_user_groups 是主演阵容，
        // 演员不是「创作者」（其 user_id 剧作 tab 空、按名搜索也匹配不上，
        // 曾致「创作者只有一个作品」误报）——只认 author_user_info
        val payload = JSONObject(
            """
            {"code":0,"data":{
              "7703408432757361688":{"base_info":{
                "main_creator_user_groups":[
                  {"group_type":"主演","total_count":3,"ugc_creator_type":5,"ugc_user_infos":[
                    {"base_info":{"user_id":"10001","user_name":"张艳"}},
                    {"base_info":{"user_name":"李四"}}]}
                ]}
              }}
            }}
            """.trimIndent()
        )
        assertTrue(HongguoClient.parseInnerFeedCreators(payload).isEmpty())
    }

    @Test
    fun innerFeedDetailAuthorInfoBeatsCreatorGroups() {
        // 两路都有时出品方优先（authorWorks 用它匹配搜索结果的发布者 user_name）
        val payload = JSONObject(
            """
            {"code":0,"data":{"1":{"base_info":{
              "author_user_info":{"base_info":{"user_id":"20002","user_name":"橙子漫剧"}},
              "main_creator_user_groups":[{"group_type":"主演","ugc_user_infos":[
                {"base_info":{"user_name":"张艳"}}]}]
            }}}}
            """.trimIndent()
        )
        assertEquals(
            mapOf("1" to HongguoClient.Creator("20002", "橙子漫剧")),
            HongguoClient.parseInnerFeedCreators(payload)
        )
    }

    @Test
    fun innerFeedDetailToleratesAbsentData() {
        assertTrue(HongguoClient.parseInnerFeedCreators(JSONObject("{\"code\":0}")).isEmpty())
        assertTrue(HongguoClient.parseInnerFeedCreators(JSONObject("{}")).isEmpty())
    }

    @Test
    fun searchVideoAuthorPrefersUserInfo() {
        // 抓包原文形态：video_data 内嵌 user_info（user_type=12 UGC 发布者）
        val video = JSONObject(
            """{"series_id":"7682100011156376601","title":"苏临，被低估的异能逆袭主角",
               "user_info":{"user_id":"2857001738572368","user_type":12,"user_name":"℡伊伊琪琪"},
               "book_record_info":{"author_name":""}}""".trimIndent()
        )
        assertEquals("℡伊伊琪琪", HongguoSearch.parseVideoAuthor(video))
    }

    @Test
    fun searchVideoAuthorFallsBackToBookRecord() {
        // 无 UGC 发布者（非用户投稿）：回落改编原著作者名
        val video = JSONObject(
            """{"series_id":"1","user_info":{"user_name":""},
               "book_record_info":{"record_info":"改编自番茄小说《血量亿倍增幅》","author_name":"某人"}}""".trimIndent()
        )
        assertEquals("某人", HongguoSearch.parseVideoAuthor(video))
    }

    @Test
    fun searchVideoAuthorEmptyWhenBothAbsent() {
        assertEquals("", HongguoSearch.parseVideoAuthor(JSONObject("""{"series_id":"1"}""")))
    }

    @Test
    fun seriesStemExtractsSeasonAndSequelSuffix() {
        // 相关作品兜底的系列主干提取（Repository 静态逻辑镜像，同款正则验证）
        val stem = fun(title: String): String {
            val t = title.trim()
            val m = Regex("^(.{2,}?)(第[0-9一二三四五六七八九十百]+[季部篇曲]|之.+)").find(t)
            return m?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } ?: t
        }
        assertEquals("我家后山藏真龙", stem("我家后山藏真龙第四季"))
        assertEquals("小镇是非多", stem("小镇是非多之同床异梦"))
        assertEquals("我在八零年代当富婆", stem("我在八零年代当富婆 第2季"))
        assertEquals("普通剧名", stem("普通剧名"))
        assertEquals("第四季", stem("第四季")) // 首词即系列词：全名兜底前主干过短场景
    }

    @Test
    fun personParsesProfile() {
        // openhongguo ParseProfile 口径：data.ugc_user
        val payload = JSONObject(
            """
            {"code":0,"data":{"ugc_user":{
              "user_id":"2857001738572368",
              "base_info":{"user_name":"℡伊伊琪琪","description":"拍短剧的","user_avatar":"https://p3-novel.byteimg.com/a.jpg"},
              "user_relation":{"fans_num":12000,"recv_digged_count":99999}
            }}}
            """.trimIndent()
        )
        val p = HongguoClient.parsePerson(payload)!!
        assertEquals("℡伊伊琪琪", p.name)
        assertEquals("2857001738572368", p.userId)
        assertEquals(12000L, p.fans)
        assertEquals(99999L, p.likes)
    }

    @Test
    fun personEmptyNameIsNull() {
        val payload = JSONObject("""{"code":0,"data":{"ugc_user":{"base_info":{"user_name":""}}}}""")
        assertTrue(HongguoClient.parsePerson(payload) == null)
    }

    @Test
    fun personWorksParsesVideoList() {
        // openhongguo ParseUserWorks 口径：data.data_list[].video
        val payload = JSONObject(
            """
            {"code":0,"data":{
              "data_list":[
                {"video":{"video_id":"7682100011156376601","common":{"title":"苏临，被低估的异能逆袭主角","intro":"爽剧","cover":"https://p3-reading-sign.fqnovelpic.com/x.webp"},"stat":{"hot":180,"play_cnt":12345},"expand":{"episode_cnt":80,"sub_title_list":[{"text":"连载中"}]}}},
                {"video":null},
                {"other":1}
              ],
              "common_list_info":{"cursor":"next","has_more":true}
            }}
            """.trimIndent()
        )
        val works = HongguoClient.parsePersonWorks(payload)
        assertEquals(1, works.size)
        val w = works[0]
        assertEquals("7682100011156376601", w.seriesId)
        assertEquals("苏临，被低估的异能逆袭主角", w.title)
        assertEquals(80, w.episodeCount)
        assertEquals(12345L, w.playCount)
    }
}
