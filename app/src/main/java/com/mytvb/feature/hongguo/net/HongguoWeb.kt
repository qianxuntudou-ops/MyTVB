package com.mytvb.feature.hongguo.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 红果网页端通道（hongguoduanju.com，免签名端点）：排行榜网页路由 + SeriesDetail/Episode
 * 数据类宿主。剧集详情主链路走原生 video_detail/v1（HongguoClient.videoDetail），
 * 2026-11-28 起 SSR 网页详情回退已删（11-27 接口变更后 vid_list 渐进加载只回前 3 集
 * 且 vid 变 v1_xxx 播放接口不认，坏兜底静默返回坏数据不如明确报错）。
 */
class HongguoWeb(
    private val webBase: String = "https://hongguoduanju.com"
) {

    private companion object {
        /** SSR 页须爬虫 UA（浏览器 UA 返回 stream 渲染版，无 _ROUTER_DATA）。 */
        const val GOOGLEBOT_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        /** 四板榜单网页路由（与 App 板块一一对应）。 */
        val RANK_ROUTES = listOf(
            "hot-drama", "hot-real-drama", "hot-comic-drama", "hot-ai-drama"
        )
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    data class Episode(
        val index: Int,
        val videoId: String,
        val title: String,
        val locked: Boolean
    )

    data class SeriesDetail(
        val drama: HongguoClient.Drama,
        val episodes: List<Episode>,
        val related: List<HongguoClient.Drama>
    )

    /** 网页 SSR 榜单（免签名后备通道，fqnovel 域被风控时兜底；对照 Go Rank/extractRouterData）。 */
    fun rankFromWeb(routeIndex: Int, page: Int): HongguoClient.RankPage {
        val route = RANK_ROUTES[routeIndex.coerceIn(0, RANK_ROUTES.size - 1)]
        val html = http.newCall(
            Request.Builder()
                .url("$webBase/rank/$route?page=${maxOf(page, 1)}")
                .header("User-Agent", GOOGLEBOT_UA)
                .header("Accept", "text/html")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("榜单页 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val router = extractRouterData(html)
        val content = findObjectByKeyDeep(router, "content")
            ?: throw IllegalStateException("榜单页缺少 content")
        val rows = content.optJSONArray("rankList") ?: org.json.JSONArray()
        val items = ArrayList<HongguoClient.Drama>()
        for (i in 0 until rows.length()) {
            normalizeDrama(rows.opt(i))?.let { items.add(it) }
        }
        val pagination = content.optJSONObject("pagination")
        val pageNum = pagination?.optInt("pageNum", 1) ?: 1
        val totalPages = pagination?.optInt("totalPages", 1) ?: 1
        return HongguoClient.RankPage(
            items,
            hasMore = pageNum < totalPages && items.isNotEmpty(),
            nextOffset = pageNum + 1,
            sessionId = ""
        )
    }

    // ---- HTML 内嵌 _ROUTER_DATA 提取（对照 extractRouterData/balancedJSONEnd） ----
    private fun extractRouterData(html: String): JSONObject {
        val marker = "_ROUTER_DATA"
        val pos = html.indexOf(marker)
        if (pos < 0) throw IllegalStateException("页面缺少 _ROUTER_DATA")
        var start = pos + marker.length
        while (start < html.length && (html[start] == ' ' || html[start] == '\t' ||
                html[start] == '\r' || html[start] == '\n' || html[start] == '=')
        ) start++
        while (start < html.length && html[start] != '{' && html[start] != '[') start++
        if (start >= html.length) throw IllegalStateException("_ROUTER_DATA JSON 起点无效")
        val end = balancedJsonEnd(html, start)
        return JSONObject(html.substring(start, end + 1))
    }

    private fun balancedJsonEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && (c == '{' || c == '[') -> depth++
                !inString && (c == '}' || c == ']') -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        throw IllegalStateException("_ROUTER_DATA JSON 未闭合")
    }

    /** 深度优先找键（榜单 content 嵌在多层容器内，浅层 BFS 不够用）。 */
    private fun findObjectByKeyDeep(root: JSONObject, key: String, depth: Int = 0): JSONObject? {
        if (depth > 6) return null
        root.optJSONObject(key)?.let { return it }
        for (k in root.keys()) {
            when (val child = root.opt(k)) {
                is JSONObject -> findObjectByKeyDeep(child, key, depth + 1)?.let { return it }
                is org.json.JSONArray ->
                    for (i in 0 until child.length()) {
                        (child.opt(i) as? JSONObject)
                            ?.let { findObjectByKeyDeep(it, key, depth + 1) }
                            ?.let { return it }
                    }
            }
        }
        return null
    }

    private fun normalizeDrama(value: Any?): HongguoClient.Drama? {
        var obj = value as? JSONObject ?: return null
        val nested = obj.optJSONObject("video_data")
        if (nested != null) obj = nested
        fun str(vararg keys: String): String {
            for (k in keys) {
                val v = obj.optString(k)
                if (v.isNotEmpty()) return v
            }
            return ""
        }
        val id = str("series_id_str", "series_id", "seriesId", "book_id", "id")
        if (id.isEmpty()) return null
        val episodeInfo = obj.optJSONObject("series_episode_info")
        val seriesStatus = str("series_status", "status").ifEmpty {
            episodeInfo?.let { e ->
                listOf("series_status", "status").firstNotNullOfOrNull { k -> e.optString(k).ifEmpty { null } }
            } ?: ""
        }
        return HongguoClient.Drama(
            seriesId = id,
            title = str("series_title", "series_name", "title").ifEmpty { "未命名短剧" },
            cover = str("series_cover", "cover", "cover_url"),
            intro = str("series_intro", "video_desc", "description"),
            score = str("score", "rating", "scoreText"),
            playCount = obj.optLong("play_cnt", 0L),
            episodeCount = firstPositive(obj.optInt("episode_cnt", 0), obj.optInt("episode_total_cnt", 0), stringList(obj.optJSONArray("vid_list")).size),
            vid = str("vid", "video_id"),
            tags = stringList(obj.optJSONArray("tags")),
            heatText = obj.optString("heatText"),
            statusTags = stringList(obj.optJSONArray("statusTags")),
            seasonIndex = obj.optInt("season_index", 0),
            multiSeason = obj.optBoolean("is_multi_season", false),
            contentType = str("content_type", "contentType"),
            seriesStatus = seriesStatus
        )
    }

    private fun firstPositive(vararg values: Int): Int {
        for (v in values) if (v > 0) return v
        return 0
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
