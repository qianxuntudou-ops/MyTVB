package com.mytvb.feature.hongguo.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * 红果业务客户端（P1：匿名分类 feed）。
 *
 * 由自研 Go 版 hongguo_client.go 的 Category/postSignedJSON 链路翻译：
 * - 匿名接口仍要求同一套 Passport 签名信封，但 device_id/iid 可用一次性随机值，
 *   边缘节点对身份拒绝时返回 HTTP 200 + 空体——轮换随机身份重试（最多 3 次）；
 * - 请求体为 JSON POST，签名走 HongguoCrypto.signRequest 全量（Medusa/Helios/Gorgon）。
 *
 * 隔离约束：自建 OkHttpClient，禁复用 B 站 NetworkManager。
 */
internal class HongguoClient(
    private val apiBase: String = "https://api5-normal-sinfonlineb.fqnovel.com"
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            // 字节系风控对 h2 指纹敏感，对齐 Go http.Client 行为走 HTTP/1.1
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    data class Drama(
        val seriesId: String,
        val title: String,
        val cover: String,
        val intro: String,
        val score: String,
        val playCount: Long,
        val episodeCount: Int,
        val vid: String,
        val tags: List<String>,
        val heatText: String,
        val statusTags: List<String>,
        val seasonIndex: Int,
        val multiSeason: Boolean,
        val contentType: String,
        val seriesStatus: String
    )

    data class CategoryPage(
        val items: List<Drama>,
        val hasMore: Boolean,
        val nextOffset: Int,
        val sessionId: String
    )

    /** 分类路径 → (req_scene, genre)：全部/漫剧/AI剧 三形态，对照 categorySpecFor。 */
    private fun specFor(path: String): Pair<String, String> = when (path) {
        "/category/comic-drama", "/category/comic" -> "comic_series" to "comic_series"
        "/category/ai-drama" -> "ai_series" to "ai_series"
        else -> "default" to "short_play"
    }

    /**
     * 拉取分类/推荐流。
     * @param path /category/short-drama（推荐·真人）等
     * @param sort hot_score / online_time（/new/ 前缀路径默认 online_time）
     */
    fun category(
        path: String,
        offset: Int,
        sessionId: String,
        filters: Map<String, String> = emptyMap()
    ): CategoryPage {
        val (scene, genre) = specFor(path)
        val sort = if (path.startsWith("/new/")) "online_time" else "hot_score"
        val selectItems = JSONObject()
        for (kind in FILTER_KINDS) {
            val arr = JSONArray()
            when {
                kind == "genre" -> arr.put(genre)
                kind == "sort" -> arr.put(sort)
                filters[kind]?.isNotEmpty() == true -> arr.put(filters[kind])
            }
            selectItems.put(kind, arr)
        }
        val body = JSONObject()
            .put("filter_ids", "")
            .put("req_scene", scene)
            .put("offset", maxOf(offset, 0))
            .put("need_selector_panel", false)
            .put("limit", 15)
            .put("select_items", selectItems)
            .put("session_id", sessionId)
            .put("req_type", "only_content")
            .put("client_req_type", 3)
        val payload = postSignedJson("/reading/distribution/category/landpage/v/", body)
        val data = payload.optJSONObject("data")
            ?: throw IllegalStateException("分类响应缺少 data")
        val rows = data.optJSONArray("video_data") ?: JSONArray()
        val items = ArrayList<Drama>(rows.length())
        for (i in 0 until rows.length()) {
            normalizeDrama(rows.opt(i))?.let { items.add(it) }
        }
        val hasMore = data.optBoolean("has_more", false)
        var nextOffset = data.optInt("next_offset", 0)
        if (nextOffset == 0 && hasMore) {
            nextOffset = maxOf(offset, 0) + items.size
        }
        return CategoryPage(items, hasMore, nextOffset, data.optString("session_id"))
    }

    // ---- 匿名签名 POST：一次性随机身份 + 空响应轮换重试（对照 postSignedJSON） ----
    private fun postSignedJson(path: String, body: JSONObject): JSONObject {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return postOnce(path, raw, randomId(), randomId())
            } catch (e: EmptyResponseException) {
                lastError = e
                if (attempt == 2) break
                Thread.sleep(100)
            }
        }
        throw lastError ?: IllegalStateException("红果接口无响应")
    }

    private class EmptyResponseException : IllegalStateException("empty body")

    private fun randomId(): String {
        val buf = ByteArray(8)
        SecureRandom().nextBytes(buf)
        var v = 0L
        for (b in buf) v = (v shl 8) or (b.toLong() and 0xFF)
        v %= 8_000_000_000_000_000_000L
        v += 1_000_000_000_000_000_000L
        return v.toString()
    }

    private fun postOnce(path: String, raw: ByteArray, deviceId: String, installId: String): JSONObject {
        val nowSec = System.currentTimeMillis() / 1000
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_id"] = deviceId
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["iid"] = installId
        q["language"] = "zh"; q["manifest_version_code"] = "73532"
        q["os"] = "android"; q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"
        q["pv_player"] = "73532"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
        q["is_android_pad_screen"] = "0"
        q["ts"] = nowSec.toString()
        q["update_version_code"] = "73532"; q["version_code"] = "73532"
        q["version_name"] = "7.3.5.32"

        val signed = HongguoCrypto.signRequest(q, raw, System.currentTimeMillis(), deviceId)
        fun be32(n: Long): ByteArray = byteArrayOf(
            ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
        )

        fun le32(n: Long): ByteArray = byteArrayOf(
            (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte()
        )

        val req = Request.Builder()
            .url("$apiBase$path?${signed.query}")
            .post(raw.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .apply { signed.headers.forEach { (k, v) -> header(k, v) } }
            .header("X-XS-From-Web", "0")
            .header("Sdk-Version", "2")
            .header("X-SS-DP", "8662")
            .header("X-Tt-Store-Region", "cn")
            .header("X-Tt-Store-Region-Src", "did")
            .header("X-Ladon", HongguoCrypto.base64(be32(nowSec)))
            .header("X-Argus", HongguoCrypto.base64(le32(nowSec)))
            .header("X-Tt-Dt", "")
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (HongguoClientDebug.enabled) {
                println("HG-HTTP code=${resp.code} len=${bytes.size} logid=${resp.header("x-tt-logid")}")
                if (bytes.isNotEmpty()) {
                    println("HG-HTTP body=${String(bytes.copyOfRange(0, minOf(300, bytes.size)), Charsets.UTF_8)}")
                }
            }
            if (!resp.isSuccessful) throw IllegalStateException("红果接口 HTTP ${resp.code}")
            if (bytes.isEmpty()) throw EmptyResponseException()
            val text = String(bytes, Charsets.UTF_8)
            if (text.isBlank()) throw EmptyResponseException()
            val payload = JSONObject(text)
            if (payload.optString("code", "0") != "0") {
                throw IllegalStateException("红果接口 code=${payload.optString("code")}")
            }
            return payload
        }
    }

    // ---- Drama 归一化，对照 normalizeDrama（列表/详情两种形态字段归并） ----
    private fun normalizeDrama(value: Any?): Drama? {
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
        val title = str("series_title", "series_name", "title").ifEmpty { "未命名短剧" }
        val episodeInfo = obj.optJSONObject("series_episode_info")
        val seriesStatus = str("series_status", "status").ifEmpty {
            episodeInfo?.let {
                listOf("series_status", "status").firstNotNullOfOrNull { k ->
                    it.optString(k).ifEmpty { null }
                }
            } ?: ""
        }
        return Drama(
            seriesId = id,
            title = title,
            cover = str("series_cover", "cover", "cover_url"),
            intro = str("series_intro", "video_desc", "description"),
            score = str("score", "rating", "scoreText"),
            playCount = obj.optLong("play_cnt", 0L),
            episodeCount = firstPositive(obj.optInt("episode_cnt", 0), obj.optInt("episode_total_cnt", 0)),
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

    private fun firstPositive(a: Int, b: Int): Int = when {
        a > 0 -> a
        b > 0 -> b
        else -> 0
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

    companion object {
        private val FILTER_KINDS = listOf(
            "genre", "category_dim_theme", "category_dim_role", "category_dim_epoch",
            "sort", "gender", "online_time", "creation_status"
        )
    }

    /** 排障开关：JVM 单测置 true 打印响应详情。 */
    object HongguoClientDebug {
        var enabled = false
    }
}
