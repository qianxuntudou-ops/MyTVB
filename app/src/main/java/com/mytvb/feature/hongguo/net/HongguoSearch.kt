package com.mytvb.feature.hongguo.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 红果短剧搜索：/reading/bookapi/search/tab/v（GET 签名）。
 * 2026-11-14 起传输层收编 HongguoClient 统一签名通道（74532 官方抓包口径，
 * 空/100103 通道内轮换身份重试），本类只保留搜索专属参数与解析。
 * 联想词走 hongguoduanju.com 无签名接口（Go 版同款）。
 */
class HongguoSearch(private val client: HongguoClient) {

    /** TLS 定制共享 client（HongguoTls；搜索接口同样校验 TLS 形态）。 */
    private val http: OkHttpClient by lazy { HongguoHttp.ok }

    data class SearchResult(
        val seriesId: String,
        val title: String,
        val cover: String,
        val episodeCount: Int,
        val intro: String,
        val playCount: Long,
        /** 发布者名（官方口径：video_data 内嵌 user_info.user_name；空=该结果未带发布者）。 */
        val author: String = ""
    )

    data class SearchPage(
        val items: List<SearchResult>,
        val hasMore: Boolean,
        val cursor: Cursor?
    ) {
        data class Cursor(val searchId: String, val passback: String, val nextOffset: Int?)
    }

    /**
     * 短剧 tab 搜索（tab_type=11，其他 tab 的 cell 无 video_data 自然被过滤）。
     * 通道内空响应/100103 已轮换身份重试 3 次，全败抛 [EmptyResponseException]
     * 由调用方回退官网（HGTV「已自动改用官网搜索」同款）。
     * @param relevanceFilter 分词相关性过滤（默认开；作者名搜索时必须关——作者名
     *   不在 title/intro/tags 元数据里，开着会把结果全过滤光，对照 HGTV 作者作品
     *   走原始搜索后按 multi-detail 补的 author 精确匹配过滤）
     */
    fun searchTab(
        keyword: String, offset: Int,
        cursor: SearchPage.Cursor?,
        relevanceFilter: Boolean = true
    ): SearchPage {
        val kw = keyword.trim().take(80)
        require(kw.isNotEmpty()) { "搜索词为空" }
        val values = linkedMapOf(
            "query" to kw,
            "count" to "20",
            "tab_type" to "11",
            "bookshelf_search_plan" to "4",
            "use_correct" to "true",
            "user_is_login" to "0",
            "offset" to offset.toString()
        )
        cursor?.searchId?.takeIf { it.isNotEmpty() }?.let { values["search_id"] = it }
        cursor?.passback?.takeIf { it.isNotEmpty() }?.let { values["passback"] = it }
        val payload = try {
            client.searchTabRequest(values)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HongguoClient.EmptyResponseException) {
            throw EmptyResponseException()
        } catch (e: Exception) {
            // HTTP/code 级失败同样走官网回退口径（与空响应一致）
            throw EmptyResponseException()
        }
        return parsePage(payload, kw, offset, relevanceFilter)
    }

    /**
     * 官网搜索（hongguoduanju.com/api/search/result，GET 免登录无签名；对照 HGTV F0.O 单页请求 + b.Y0 解析）。
     * 结果略少但翻页稳定，作为搜索源可选值与 App 接口失败时的回退通道。
     * @param page 物理页码 1..100（ HGTV 同款上限），[SearchPage.Cursor.nextOffset] 回传下一页码。
     */
    fun searchWeb(keyword: String, page: Int): SearchPage {
        val kw = keyword.trim().take(80)
        require(kw.isNotEmpty()) { "搜索词为空" }
        val safePage = page.coerceIn(1, 100)
        // 官网接口对空格/%7E 敏感：URLEncoder 的 + 与 ~ 的 %7E 编码都要还原（HGTV 同款改写）
        val encoded = java.net.URLEncoder.encode(kw, "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
        val url = "https://hongguoduanju.com/api/search/result?query=$encoded&page=$safePage"
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                .header("Accept", "application/json")
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("Hongguo", "searchWeb http=${resp.code} len=${text.length}")
            if (!resp.isSuccessful) throw IllegalStateException("官网搜索 HTTP ${resp.code}")
            if (text.isBlank()) throw EmptyResponseException()
            val payload = JSONObject(text)
            if (payload.has("code")) {
                val code = payload.optString("code")
                // invalid_query=词被拒（过短/纯符号）：按无结果处理，其余码视为接口异常
                if (code == "invalid_query") return SearchPage(emptyList(), false, null)
                throw IllegalStateException("官网搜索暂不可用（$code）")
            }
            val list = payload.optJSONArray("searchList")
                ?: return SearchPage(emptyList(), false, null)
            // 防爆量护栏（HGTV 以每页条数×3 为上限，异常大列表按空处理）
            if (list.length() > 60) return SearchPage(emptyList(), false, null)
            val seen = HashSet<String>()
            val items = ArrayList<SearchResult>(list.length())
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val video = entry.optJSONObject("video_data")?.takeIf { it.length() > 0 } ?: entry
                val id = video.optString("series_id").ifEmpty { entry.optString("keyword") }
                if (id.isEmpty() || !id.matches(Regex("\\d{1,30}")) || !seen.add(id)) continue
                val title = stripTags(video.optString("series_title").ifEmpty { entry.optString("name") })
                if (title.isEmpty()) continue
                items.add(
                    SearchResult(
                        seriesId = id,
                        title = title,
                        cover = HongguoCoverUrl.rewrite(
                            video.optString("series_cover").takeIf { it.startsWith("http") } ?: ""
                        ),
                        episodeCount = video.optInt("episode_cnt", 0),
                        intro = stripTags(video.optString("series_intro")),
                        playCount = 0L
                    )
                )
            }
            val totalPages = payload.optInt("totalPages", 1).coerceIn(1, 100)
            val hasMore = items.isNotEmpty() &&
                payload.optBoolean("hasMore", safePage < totalPages) &&
                safePage < totalPages
            // 官网游标只带页码（nextOffset=下一页码），与 App 源的条数偏移互不混用
            val cursor = if (hasMore) SearchPage.Cursor("", "", safePage + 1) else null
            return SearchPage(items, hasMore, cursor)
        }
    }

    class EmptyResponseException : IllegalStateException("search empty body")

    /** 解析 + 去重 + 相关性过滤（对照 hongguo-app-search.js parseSearch + search-relevance.js）。 */
    private fun parsePage(payload: JSONObject, keyword: String, offset: Int, relevanceFilter: Boolean): SearchPage {
        val tabs = payload.optJSONArray("search_tabs")
            ?: payload.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: JSONArray()
        var tab: JSONObject? = null
        for (i in 0 until tabs.length()) {
            val t = tabs.optJSONObject(i) ?: continue
            if (t.optInt("tab_type") == 11) { tab = t; break }
        }
        if (tab == null) return SearchPage(emptyList(), false, null)
        val cells = tab.optJSONArray("data") ?: JSONArray()
        val seen = HashSet<String>()
        val items = ArrayList<SearchResult>()
        val meta = StringBuilder()
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val videos = cell.optJSONArray("video_data") ?: continue
            val video = (0 until videos.length())
                .mapNotNull { videos.optJSONObject(it) }
                .firstOrNull { v ->
                    v.optString("series_id").matches(Regex("\\d{1,30}")) ||
                        v.optString("series_id_str").matches(Regex("\\d{1,30}"))
                } ?: continue
            val id = video.optString("series_id_str").ifEmpty { video.optString("series_id") }
            if (id.isEmpty() || !seen.add(id)) continue
            val highlighted = cell.optJSONObject("search_high_light")?.optJSONObject("title")?.optString("text").orEmpty()
            val title = stripTags(highlighted.ifEmpty { video.optString("title").ifEmpty { video.optString("series_title") } })
            if (title.isEmpty()) continue
            // 相关性过滤：分词全部命中元数据才保留（防「无结果塞热门」）
            meta.setLength(0)
            meta.append(title).append(' ')
                .append(video.optString("series_intro")).append(' ')
                .append(video.optString("video_desc")).append(' ')
                .append(cell.optString("series_intro")).append(' ')
                .append(tagsText(video.optJSONArray("tags")))
                .append(categoryText(video.optJSONArray("category_list")))
                .append(celebrityText(video.optJSONArray("celebrities")))
            val hay = normalize(meta.toString())
            val terms = normalize(keyword).split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (relevanceFilter && terms.isNotEmpty() && terms.any { !hay.contains(it) }) continue
            items.add(
                SearchResult(
                    seriesId = id,
                    title = title,
                    cover = HongguoCoverUrl.rewrite(
                        video.optString("cover").ifEmpty { video.optString("cover_url") }
                            .ifEmpty { video.optString("series_cover") }
                            .takeIf { it.startsWith("http") } ?: ""
                    ),
                    episodeCount = firstPositive(
                        video.optInt("episode_cnt", 0),
                        video.optInt("episode_count", 0),
                        video.optInt("episode_total_cnt", 0)
                    ),
                    intro = stripTags(
                        video.optString("series_intro").ifEmpty { video.optString("video_desc") }
                            .ifEmpty { cell.optString("series_intro") }
                    ),
                    playCount = video.optLong("play_cnt", 0L),
                    author = parseVideoAuthor(video)
                )
            )
        }
        val hasMore = items.isNotEmpty() && tab.optBoolean("has_more", false) && offset < 1000
        val cursor = if (hasMore) SearchPage.Cursor(
            searchId = tab.optString("search_id"),
            passback = tab.optString("passback"),
            nextOffset = tab.optString("next_offset").toIntOrNull()
                ?: tab.optInt("next_offset", offset + items.size)
        ) else null
        return SearchPage(items, hasMore, cursor)
    }

    /** 联想词：无签名 web 接口（对照 Go hongguo_client.go suggestion）。 */
    fun suggestions(keyword: String): List<String> {
        val kw = keyword.trim().take(100)
        if (kw.isEmpty()) return emptyList()
        val url = "https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&count=10&query=${
            java.net.URLEncoder.encode(kw, "UTF-8")
        }"
        return try {
            http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.isBlank()) return@use emptyList()
                val list = JSONObject(text).optJSONArray("suggest_list") ?: return@use emptyList()
                val out = ArrayList<String>(list.length())
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                        .ifEmpty { item.optJSONArray("display_words")?.optString(0).orEmpty() }
                    if (name.isNotBlank()) out.add(name)
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- 工具 ----

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("<[^>]*>"), "")

    private fun tagsText(arr: JSONArray?): String {
        arr ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            sb.append(t.optString("tag_name").ifEmpty { t.optString("name") }).append(' ')
        }
        return sb.toString()
    }

    private fun categoryText(arr: JSONArray?): String {
        arr ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONObject(i)?.optString("name") ?: "").append(' ')
        }
        return sb.toString()
    }

    private fun celebrityText(arr: JSONArray?): String {
        arr ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONObject(i)?.optString("nickname") ?: "").append(' ')
        }
        return sb.toString()
    }

    private fun firstPositive(vararg values: Int): Int {
        for (v in values) if (v > 0) return v
        return 0
    }

    companion object {

        /**
         * 发布者名（官方口径，2026-11-13 抓包实证 video_data 内嵌）：优先出品方
         * user_info.user_name，空则回落原著 book_record_info.author_name（改编类短剧
         * 无 UGC 发布者时的第二口径）；两者皆空返回空串。
         */
        fun parseVideoAuthor(video: JSONObject): String =
            video.optJSONObject("user_info")?.optString("user_name")?.trim().orEmpty()
                .ifEmpty {
                    video.optJSONObject("book_record_info")?.optString("author_name")?.trim().orEmpty()
                }
    }
}
