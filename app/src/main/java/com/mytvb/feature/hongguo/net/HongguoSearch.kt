package com.mytvb.feature.hongguo.net

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * 红果短剧搜索：/reading/bookapi/search/tab/v（GET 签名，72232 profile）。
 * 对照 Node signedSearchRequest / Go lists.go searchDiscrete——线上验证链路。
 * 联想词走 hongguoduanju.com 无签名接口（Go 版同款）。
 */
class HongguoSearch {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class SearchResult(
        val seriesId: String,
        val title: String,
        val cover: String,
        val episodeCount: Int,
        val intro: String,
        val playCount: Long
    )

    data class SearchPage(
        val items: List<SearchResult>,
        val hasMore: Boolean,
        val cursor: Cursor?
    ) {
        data class Cursor(val searchId: String, val passback: String, val nextOffset: Int?)
    }

    /**
     * @param identity 真注册设备身份（搜索校验 device_id/iid 真实性 + cdid UUID 格式）。
     * 空响应或 100103 时抛 [EmptyResponseException]，调用方轮换身份重试。
     */
    fun searchTab(
        keyword: String, offset: Int,
        cursor: SearchPage.Cursor?,
        identity: HongguoDeviceRegister.Identity
    ): SearchPage {
        val kw = keyword.trim().take(80)
        require(kw.isNotEmpty()) { "搜索词为空" }
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["channel"] = "update_64"; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "72232"; q["version_name"] = "7.2.2.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = "25053RT47C"; q["device_brand"] = "Redmi"; q["language"] = "zh"
        q["os_api"] = "36"; q["os_version"] = "16"
        q["manifest_version_code"] = "72232"; q["resolution"] = "1280*2772"; q["dpi"] = "520"
        q["update_version_code"] = "72232"; q["host_abi"] = "arm64-v8a"
        q["dragon_device_type"] = "phone"; q["pv_player"] = "72232"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
        q["iid"] = identity.installId
        q["device_id"] = identity.deviceId
        q["cdid"] = identity.cdid
        q["query"] = kw
        q["count"] = "20"
        q["tab_type"] = "11"          // 短剧 tab（其他 tab 的 cell 无 video_data 自然被过滤）
        q["bookshelf_search_plan"] = "4"
        q["use_correct"] = "true"
        q["user_is_login"] = "0"
        cursor?.searchId?.takeIf { it.isNotEmpty() }?.let { q["search_id"] = it }
        cursor?.passback?.takeIf { it.isNotEmpty() }?.let { q["passback"] = it }
        q["offset"] = offset.toString()

        val nowMs = System.currentTimeMillis()
        val signed = HongguoCrypto.signRequest(
            q, null, nowMs, identity.deviceId,
            targetBranch = 0, version = "7.2.2.32"
        )
        val ts = signed.headers["X-Khronos"]?.toIntOrNull() ?: (nowMs / 1000).toInt()
        fun be32(n: Int): ByteArray = byteArrayOf(
            ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
        )

        fun le32(n: Int): ByteArray = byteArrayOf(
            (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte()
        )

        val req = Request.Builder()
            .url("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v?${signed.query}")
            .get()
            .header("User-Agent", SEARCH_UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("x-ss-req-ticket", signed.headers["X-SS-Req-Ticket"] ?: "")
            .header("x-tt-request-tag", "t=0;n=0")
            .header("sdk-version", "2")
            .header("passport-sdk-version", "50561")
            .header("x-xs-from-web", "0")
            .header("x-khronos", signed.headers["X-Khronos"] ?: "")
            .header("x-ladon", HongguoCrypto.base64(be32(ts)))
            .header("x-argus", HongguoCrypto.base64(le32(ts)))
            .header("x-gorgon", signed.headers["X-Gorgon"] ?: "")
            .header("x-helios", signed.headers["X-Helios"] ?: "")
            .header("x-medusa", signed.headers["X-Medusa"] ?: "")
            .header("x-tt-dt", "")
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            android.util.Log.d("Hongguo", "search http=${resp.code} len=${bytes.size} logid=${resp.header("x-tt-logid")}")
            if (!resp.isSuccessful) throw IllegalStateException("搜索接口 HTTP ${resp.code}")
            if (bytes.isEmpty()) throw EmptyResponseException()
            val payload = JSONObject(String(bytes, Charsets.UTF_8))
            if (payload.optInt("code", 0) != 0) {
                // 100103=游标会话失效：按 APK 行为可原签名重试，这里简化为身份轮换
                throw EmptyResponseException()
            }
            return parsePage(payload, kw, offset)
        }
    }

    class EmptyResponseException : IllegalStateException("search empty body")

    /** 解析 + 去重 + 相关性过滤（对照 hongguo-app-search.js parseSearch + search-relevance.js）。 */
    private fun parsePage(payload: JSONObject, keyword: String, offset: Int): SearchPage {
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
            if (terms.isNotEmpty() && terms.any { !hay.contains(it) }) continue
            items.add(
                SearchResult(
                    seriesId = id,
                    title = title,
                    cover = video.optString("cover").ifEmpty { video.optString("cover_url") }
                        .ifEmpty { video.optString("series_cover") }
                        .takeIf { it.startsWith("http") } ?: "",
                    episodeCount = firstPositive(
                        video.optInt("episode_cnt", 0),
                        video.optInt("episode_count", 0),
                        video.optInt("episode_total_cnt", 0)
                    ),
                    intro = stripTags(
                        video.optString("series_intro").ifEmpty { video.optString("video_desc") }
                            .ifEmpty { cell.optString("series_intro") }
                    ),
                    playCount = video.optLong("play_cnt", 0L)
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

    private companion object {
        /** 搜索专用 UA（72232 无 Cronet 后缀，对照 Node SEARCH_UA）。 */
        const val SEARCH_UA =
            "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
    }
}
