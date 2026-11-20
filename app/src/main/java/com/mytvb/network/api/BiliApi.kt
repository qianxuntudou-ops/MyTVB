package com.mytvb.network.api

import android.os.SystemClock
import com.mytvb.model.live.LiveListWrapper
import com.mytvb.model.video.Owner
import com.mytvb.model.video.Stat
import com.mytvb.model.video.VideoModel
import com.mytvb.network.BiliClient
import com.mytvb.core.common.log.AppLog
import com.mytvb.network.NetworkManager
import com.mytvb.network.WbiGenerator
import com.mytvb.network.security.AppSignUtils
import org.json.JSONObject

object BiliApi {

    private const val TAG = "BiliApi"
    private const val SOURCE_APP_FEED = "APP_FEED"

    data class RecommendPageResult(
        val items: List<VideoModel>,
        val rawCount: Int,
        val source: String
    )

    suspend fun recommendV8(freshIdx: Int, ps: Int, fetchRow: Int): RecommendPageResult {
        val t0 = SystemClock.elapsedRealtime()
        if (NetworkManager.areWbiKeysStale()) {
            val ensureStart = SystemClock.elapsedRealtime()
            NetworkManager.ensureWbiKeys()
            AppLog.i(TAG, "recommendV8 ensureWbiKeys elapsed=${SystemClock.elapsedRealtime() - ensureStart}ms")
        }
        val keys = NetworkManager.getWbiKeys()
        val params = mapOf(
            "ps" to ps.toString(),
            "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
            "fresh_idx_1h" to freshIdx.coerceAtLeast(1).toString(),
            "fetch_row" to fetchRow.coerceAtLeast(1).toString(),
            "feed_version" to "V8"
        )
        val signedParams = WbiGenerator.generateWbiParams(
            params = params,
            imgKey = keys.first,
            subKey = keys.second,
            includeDmParams = false
        )
        val url = BiliClient.buildUrl(
            path = "x/web-interface/wbi/index/top/feed/rcmd",
            params = signedParams
        )
        AppLog.i(
            TAG,
            "recommendV8 request freshIdx=${freshIdx.coerceAtLeast(1)} fetchRow=${fetchRow.coerceAtLeast(1)} ps=$ps keys=${keys.first.isNotBlank()}/${keys.second.isNotBlank()} url=$url"
        )
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommendV8 response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "recommendV8")
        val data = json.optJSONObject("data")
        if (data == null) {
            AppLog.w(TAG, "recommendV8 data is null, raw=${json.toString().take(200)}")
            return RecommendPageResult(emptyList(), rawCount = 0, source = "V8")
        }
        val items = data.optJSONArray("item") ?: data.optJSONArray("items")
        if (items == null) {
            AppLog.w(TAG, "recommendV8 item is null, data keys=${data.keys().asSequence().toList()}")
            return RecommendPageResult(emptyList(), rawCount = 0, source = "V8")
        }
        AppLog.i(TAG, "recommendV8 items count=${items.length()}")
        val result = parseRecommendItems(items.length()) { idx -> items.getJSONObject(idx) }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommendV8 total=${t2 - t0}ms parse=${t2 - t1}ms raw=${items.length()} result=${result.size}")
        return RecommendPageResult(result, rawCount = items.length(), source = "V8")
    }

    /**
     * app 端推荐流（app.bilibili.com/x/v2/feed/index，android_hd appkey 签名 + access_key）。
     * 与网页推荐池不同，内容按 app 账号个性化；接口不返回 bvid（param/uri 均为数字 aid），
     * 用 AvToBv 本地换算补齐。idx=0 为刷新（pull=true），之后递增加载更多。
     */
    suspend fun appRecommend(idx: Int, accessKey: String, ps: Int): RecommendPageResult {
        val t0 = SystemClock.elapsedRealtime()
        val params = mapOf(
            "access_key" to accessKey,
            "appkey" to AppSignUtils.TV_APP_KEY,
            "build" to "2001100",
            "column" to "4",
            "device" to "pad",
            "flush" to "5",
            "idx" to idx.coerceAtLeast(0).toString(),
            "mobi_app" to "android_hd",
            "platform" to "android",
            "ps" to ps.toString(),
            "pull" to if (idx <= 0) "true" else "false",
            "ts" to AppSignUtils.getTimestamp().toString()
        )
        val signedParams = AppSignUtils.signForTvLogin(params)
        val url = BiliClient.buildUrl(
            path = "https://app.bilibili.com/x/v2/feed/index",
            params = signedParams
        )
        AppLog.i(TAG, "appRecommend request idx=$idx ps=$ps")
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "appRecommend response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "appRecommend")
        val items = json.optJSONObject("data")?.optJSONArray("items")
        if (items == null) {
            AppLog.w(TAG, "appRecommend items is null, raw=${json.toString().take(200)}")
            return RecommendPageResult(emptyList(), rawCount = 0, source = SOURCE_APP_FEED)
        }
        val result = (0 until items.length()).mapNotNull { i ->
            runCatching { parseAppFeedItem(items.getJSONObject(i)) }
                .onFailure { AppLog.w(TAG, "appRecommend parse item failed: ${it.message}") }
                .getOrNull()
        }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "appRecommend total=${t2 - t0}ms parse=${t2 - t1}ms raw=${items.length()} result=${result.size}")
        return RecommendPageResult(result, rawCount = items.length(), source = SOURCE_APP_FEED)
    }

    /** app 推荐流条目 → VideoModel。只认 goto=av 的视频卡片，aid 缺失的条目丢弃。 */
    private fun parseAppFeedItem(obj: JSONObject): VideoModel? {
        if (obj.optString("goto") != "av") return null
        // 广告卡片官方也以 goto=av 下发，靠顶层 ad_info（creative_content 为推广素材）区分，
        // 卡面与正常视频无异；不滤会混进推荐流（2026-10-16 实测 20 条中 3 条）
        if (obj.has("ad_info")) return null
        val playerArgs = obj.optJSONObject("player_args")
        val args = obj.optJSONObject("args")
        val aid = (playerArgs?.optLong("aid", 0L) ?: 0L).takeIf { it > 0 }
            ?: obj.optString("param").toLongOrNull()
            ?: return null
        val cover = obj.optString("cover", "")
        return VideoModel(
            aid = aid,
            // 不做 AvToBv 本地换算：查表算法只覆盖 58^6≈3.8e10 以内的旧号段，
            // 新视频 aid 已到 15 位，换算结果错误；bvid 留空走播放链的
            // view?aid= 回退（超大 aid + cid 直连 playUrl）并由详情响应修正回真实 bvid
            bvid = "",
            title = obj.optString("title", ""),
            pic = cover,
            cover = cover,
            duration = playerArgs?.optLong("duration", 0L) ?: obj.optLong("duration", 0L),
            cid = playerArgs?.optLong("cid", 0L) ?: 0L,
            owner = args?.let {
                Owner(
                    mid = it.optLong("up_id", 0L),
                    name = it.optString("up_name", ""),
                    face = it.optString("up_face", "")
                )
            },
            stat = Stat(
                view = parseAppCoverCountText(obj.optString("cover_left_text_1", "")),
                danmaku = parseAppCoverCountText(obj.optString("cover_left_text_2", ""))
            ),
            typeName = args?.optString("tname", "")?.ifBlank { args.optString("rname", "") }.orEmpty(),
            typeId = args?.optInt("tid", 0)?.takeIf { it > 0 } ?: args?.optInt("rid", 0) ?: 0,
            // 不感兴趣等反馈接口需要（服务端据此定位推荐上下文）
            trackId = obj.optString("track_id", ""),
            // 官方以推荐理由文案表达关注关系（"已关注"/"53万点赞"），卡片据此显示已关注标记
            isFollowed = obj.optString("rcmd_reason") == "已关注",
            rcmdReason = obj.optString("rcmd_reason"),
            goto = "av"
        )
    }

    /** "12.3万"/"1.2亿"/"83" 形态的封面角标文本 → 数值。 */
    private fun parseAppCoverCountText(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        return when {
            t.endsWith("万") -> ((t.removeSuffix("万").toDoubleOrNull() ?: 0.0) * 10_000).toLong()
            t.endsWith("亿") -> ((t.removeSuffix("亿").toDoubleOrNull() ?: 0.0) * 100_000_000).toLong()
            else -> t.toLongOrNull() ?: 0L
        }
    }

    suspend fun recommend(freshIdx: Int, ps: Int): List<VideoModel> {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "x/web-interface/index/top/feed/rcmd",
            params = mapOf(
                "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
                "ps" to ps.toString(),
                "feed_version" to "V1",
                "fresh_type" to "3",
                "plat" to "1"
            )
        )
        AppLog.i(TAG, "recommend request url=$url")
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "recommend")
        val data = json.optJSONObject("data")
        if (data == null) {
            AppLog.w(TAG, "recommend data is null, raw=${json.toString().take(200)}")
            return emptyList()
        }
        val items = data.optJSONArray("items") ?: data.optJSONArray("item")
        if (items == null) {
            AppLog.w(TAG, "recommend items is null, data keys=${data.keys().asSequence().toList()}")
            return emptyList()
        }
        AppLog.i(TAG, "recommend items count=${items.length()}")
        val result = parseRecommendItems(items.length()) { idx -> items.getJSONObject(idx) }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend total=${t2 - t0}ms parse=${t2 - t1}ms result=${result.size}")
        return result
    }

    private inline fun parseRecommendItems(
        count: Int,
        crossinline itemAt: (Int) -> JSONObject
    ): List<VideoModel> {
        return (0 until count).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(itemAt(idx)) }
                .onFailure { AppLog.w(TAG, "recommend parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun hotList(pn: Int, ps: Int): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/popular",
            params = mapOf("pn" to pn.toString(), "ps" to ps.toString())
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "hotList")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "hotList parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun ranking(rid: Int, type: String = "all"): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/ranking/v2",
            params = mapOf("rid" to rid.toString(), "type" to type)
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "ranking")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "ranking parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * 官网频道页推荐流（x/web-interface/region/feed/rcmd，WBI 签名）——与官网 /c/{route}/
     * 分区页同源。from_region 传频道新体系 tid（1001~1031，如动画=1005，见 CategoryTabPrefs），
     * display_id 是刷新序号而非页码（从 2 起，传 1 返回 62013「暂时没有更多内容了」），
     * 每批 request_cnt 条（官网同款 15）。返回 data.archives 精简结构：author 而非 owner、
     * stat 只含 view/like/danmaku，与 VideoModel.fromJson 的完整 archive 结构不兼容，走专属解析。
     */
    suspend fun regionFeedRcmd(fromRegion: Int, displayId: Int, requestCnt: Int = 15): List<VideoModel> {
        val t0 = SystemClock.elapsedRealtime()
        if (NetworkManager.areWbiKeysStale()) {
            NetworkManager.ensureWbiKeys()
        }
        val url = BiliClient.signedWbiUrl(
            path = "x/web-interface/region/feed/rcmd",
            params = mapOf(
                "from_region" to fromRegion.coerceAtLeast(1).toString(),
                "request_cnt" to requestCnt.coerceIn(1, 50).toString(),
                // display_id 从 2 起：传 1 返回 62013「暂时没有更多内容了」（官网首屏=2，MuMu 实测）
                "display_id" to displayId.coerceAtLeast(2).toString(),
                "device" to "web",
                "plat" to "30"
            )
        )
        AppLog.i(
            TAG,
            "regionFeedRcmd request from_region=$fromRegion display_id=$displayId cnt=$requestCnt url=$url"
        )
        val json = BiliClient.getJson(url, mapOf("Referer" to "https://www.bilibili.com/"))
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(
            TAG,
            "regionFeedRcmd response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms"
        )
        BiliClient.checkResponse(json, "regionFeedRcmd")
        val archives = json.optJSONObject("data")?.optJSONArray("archives")
        if (archives == null) {
            AppLog.w(TAG, "regionFeedRcmd archives is null, raw=${json.toString().take(200)}")
            return emptyList()
        }
        val result = (0 until archives.length()).mapNotNull { idx ->
            runCatching { parseRegionFeedItem(archives.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "regionFeedRcmd parse item failed: ${it.message}") }
                .getOrNull()
        }
        AppLog.i(TAG, "regionFeedRcmd total=${SystemClock.elapsedRealtime() - t0}ms raw=${archives.length()} result=${result.size}")
        return result
    }

    /** 频道推荐流条目 → VideoModel。只认 goto=av 的视频卡片，aid 缺失丢弃。 */
    private fun parseRegionFeedItem(obj: JSONObject): VideoModel? {
        if (obj.optString("goto", "av") != "av") return null
        val aid = obj.optLong("aid", 0)
        if (aid <= 0) return null
        val cover = obj.optString("cover", "")
        return VideoModel(
            aid = aid,
            bvid = obj.optString("bvid", ""),
            title = obj.optString("title", ""),
            pic = cover,
            cover = cover,
            duration = obj.optLong("duration", 0),
            cid = obj.optLong("cid", 0),
            pubDate = obj.optLong("pubdate", 0),
            owner = obj.optJSONObject("author")?.let {
                Owner(mid = it.optLong("mid", 0), name = it.optString("name", ""))
            },
            stat = obj.optJSONObject("stat")?.let {
                Stat(
                    view = it.optLong("view", 0),
                    danmaku = it.optLong("danmaku", 0),
                    like = it.optLong("like", 0)
                )
            },
            trackId = obj.optString("trackid", ""),
            goto = "av"
        )
    }

    suspend fun related(aid: Long?, bvid: String?): List<VideoModel> {
        val params = mutableMapOf<String, String>()
        aid?.let { params["avid"] = it.toString() }
        bvid?.let { params["bvid"] = it }
        val url = BiliClient.buildUrl("x/web-interface/archive/related", params)
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "related")
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(data.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "related parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * 视频评论根列表（网页版同款 x/v2/reply/wbi/main，WBI 签名 + 游标分页）。
     * 首页传空游标，翻页传上一页返回的 cursor.pagination_reply.next_offset。
     * mode：3=热门（默认）、2=最新（时间）。
     */
    suspend fun commentMainPage(
        oid: Long,
        type: Int,
        mode: Int,
        cursorOffset: String,
    ): JSONObject {
        val paginationStr = JSONObject().put("offset", cursorOffset).toString()
        val url = BiliClient.signedWbiUrl(
            path = "x/v2/reply/wbi/main",
            params = mapOf(
                "oid" to oid.coerceAtLeast(1).toString(),
                "type" to type.coerceAtLeast(1).toString(),
                "mode" to mode.toString(),
                "pagination_str" to paginationStr,
                "plat" to "1",
                "web_location" to "1315875",
            )
        )
        val json = BiliClient.getJson(url, mapOf("Referer" to "https://www.bilibili.com/"))
        BiliClient.checkResponse(json, "commentMainPage")
        return json.optJSONObject("data") ?: JSONObject()
    }

    /** 用户空间信息（x/space/wbi/acc/info，WBI 签名）——用户标识补齐（大会员/头像框/等级）。 */
    suspend fun spaceAccInfo(mid: Long): com.mytvb.core.ui.user.UserBadges? {
        val url = BiliClient.signedWbiUrl(
            path = "x/space/wbi/acc/info",
            params = mapOf("mid" to mid.toString())
        )
        val json = BiliClient.getJson(url, mapOf("Referer" to "https://space.bilibili.com/"))
        BiliClient.checkResponse(json, "spaceAccInfo")
        val data = json.optJSONObject("data") ?: return null
        val vip = data.optJSONObject("vip")
        return com.mytvb.core.ui.user.UserBadges(
            mid = data.optLong("mid", mid),
            name = data.optString("name", ""),
            avatar = data.optString("face", "").trim().takeIf { it.isNotBlank() },
            level = data.optInt("level", 0).takeIf { it > 0 },
            isVip = vip != null && vip.optInt("status", 0) == 1 && vip.optInt("type", 0) > 0,
            pendantUrl = data.optJSONObject("pendant")
                ?.optString("image_enhance", "")
                ?.trim()
                ?.takeIf { it.startsWith("http") },
        )
    }

    /** 评论楼中楼回复列表（x/v2/reply/reply，pn/ps 分页，无需 WBI 签名）。 */
    suspend fun commentRepliesPage(
        type: Int,
        oid: Long,
        rootRpid: Long,
        pn: Int = 1,
        ps: Int = 20
    ): JSONObject {
        val url = BiliClient.buildUrl(
            path = "x/v2/reply/reply",
            params = mapOf(
                "type" to type.coerceAtLeast(1).toString(),
                "oid" to oid.coerceAtLeast(1).toString(),
                "root" to rootRpid.coerceAtLeast(1).toString(),
                "pn" to pn.coerceAtLeast(1).toString(),
                "ps" to ps.coerceIn(1, 49).toString()
            )
        )
        val json = BiliClient.getJson(url, mapOf("Referer" to "https://www.bilibili.com/"))
        BiliClient.checkResponse(json, "commentRepliesPage")
        return json.optJSONObject("data") ?: JSONObject()
    }

    suspend fun liveHomeList(): LiveListWrapper {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList",
            params = mapOf("platform" to "web")
        )
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        BiliClient.checkResponse(json, "liveHomeList")
        val data = json.optJSONObject("data")
        val result = if (data != null) LiveListWrapper.fromJson(data) else LiveListWrapper()
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "liveHomeList net=${t1 - t0}ms parse=${t2 - t1}ms total=${t2 - t0}ms")
        return result
    }

    /**
     * 关注主播的正在直播全量列表（xlive/app-interface/v1/relation/liveAnchor，需登录）。
     *
     * 官方「我的关注」H5（live-app-my-focus）同款接口。首页 index/getList 的关注模块
     * 服务端只下发约 6 条，此接口按页返回全量：data.total_count=正在直播的关注总数，
     * data.rooms=直播间列表（字段与首页模块相近，room_id/roomid 两种命名都兼容，
     * 见 LiveRoomItem.fromJson）。
     *
     * @return total_count 到当前页条目的 Pair，接口异常时抛错由调用方降级。
     */
    suspend fun attentionLiveList(page: Int, pageSize: Int = 30): Pair<Int, List<com.mytvb.model.live.LiveRoomItem>> {
        val url = BiliClient.buildUrl(
            path = "https://api.live.bilibili.com/xlive/app-interface/v1/relation/liveAnchor",
            params = mapOf(
                "page" to page.coerceAtLeast(1).toString(),
                "pagesize" to pageSize.coerceIn(1, 30).toString()
            )
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "attentionLiveList")
        val data = json.optJSONObject("data") ?: JSONObject()
        val totalCount = data.optInt("total_count", 0)
        val rooms = data.optJSONArray("rooms")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                runCatching { com.mytvb.model.live.LiveRoomItem.fromJson(arr.getJSONObject(idx)) }.getOrNull()
            }
        } ?: emptyList()
        return totalCount to rooms
    }

    /**
     * 查询当前登录用户对目标用户的关注关系（x/relation，需登录 cookie）。
     *
     * 返回 attribute：0=未关注、1=悄悄关注、2=已关注、6=互相关注；
     * 未登录（-101）或网络失败返回 null，由调用方决定缓存策略。
     */
    suspend fun relationAttribute(fid: Long): Int? {
        if (fid <= 0L) return null
        val url = BiliClient.buildUrl(
            path = "x/relation",
            params = mapOf("fid" to fid.toString())
        )
        return try {
            val json = BiliClient.getJson(url, mapOf("Referer" to "https://www.bilibili.com/"))
            val code = json.optInt("code", -1)
            if (code != 0) {
                AppLog.i(TAG, "relationAttribute fid=$fid code=$code msg=${json.optString("message", "")}")
                null
            } else {
                val attribute = json.optJSONObject("data")?.optInt("attribute", 0)
                AppLog.i(TAG, "relationAttribute fid=$fid ok attribute=$attribute raw=${json.toString().take(160)}")
                attribute
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "relationAttribute fid=$fid failed: ${e.message}")
            null
        }
    }
}
