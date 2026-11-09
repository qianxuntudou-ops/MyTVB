package com.mytvb.feature.hongguo.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 红果网页端详情：hongguoduanju.com/detail?series_id=（免签名端点）。
 * 从 HTML 的 _ROUTER_DATA 抠出 seriesDetail（对照 Go 版 SeriesDetail/extractRouterData）。
 */
internal class HongguoWeb(
    private val webBase: String = "https://hongguoduanju.com"
) {

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

    fun seriesDetail(seriesId: String): SeriesDetail {
        require(seriesId.matches(Regex("\\d{1,30}"))) { "series_id 无效" }
        val req = Request.Builder()
            .url("$webBase/detail?series_id=$seriesId")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .header("Accept", "text/html")
            .build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("详情页 HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("详情页响应为空")
        }
        val router = extractRouterData(html)
        val detailObj = findObjectByKey(router, "seriesDetail")
            ?: throw IllegalStateException("详情响应缺少 seriesDetail")
        val drama = normalizeDrama(detailObj)
            ?: throw IllegalStateException("详情响应缺少剧集信息")
        require(drama.seriesId == seriesId) { "详情返回的剧集与请求不一致" }

        val vidList = stringList(detailObj.optJSONArray("vid_list"))
        var accessible = detailObj.optInt("accessible_episode_cnt", 0)
        if (accessible <= 0 || accessible > vidList.size) accessible = vidList.size
        val episodes = ArrayList<Episode>(vidList.size)
        for ((i, vid) in vidList.withIndex()) {
            episodes.add(Episode(i + 1, vid, "第${i + 1}集", i >= accessible))
        }

        val detailPage = findObjectByKey(router, "detail_page")
        val related = ArrayList<HongguoClient.Drama>()
        for (source in listOfNotNull(detailPage)) {
            collectRelated(source, seriesId, related)
        }
        return SeriesDetail(drama, episodes, related)
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

    private fun findObjectByKey(root: JSONObject, key: String): JSONObject? {
        if (root.has(key)) {
            val v = root.optJSONObject(key)
            if (v != null) return v
        }
        // BFS 一层常用容器：loaderData / routerData / 任意子对象
        for (k in root.keys()) {
            val child = root.optJSONObject(k) ?: continue
            if (child === root) continue
            val hit = child.optJSONObject(key)
            if (hit != null) return hit
            for (k2 in child.keys()) {
                val grand = child.optJSONObject(k2) ?: continue
                val hit2 = grand.optJSONObject(key)
                if (hit2 != null) return hit2
            }
        }
        return null
    }

    private fun collectRelated(obj: JSONObject, excludeId: String, out: MutableList<HongguoClient.Drama>) {
        for (key in listOf("related_works", "recommend", "recommendations", "more_like_this", "hot_drama")) {
            val arr = obj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val d = normalizeDrama(arr.opt(i)) ?: continue
                if (d.seriesId != excludeId && out.none { it.seriesId == d.seriesId }) {
                    out.add(d)
                }
            }
        }
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
