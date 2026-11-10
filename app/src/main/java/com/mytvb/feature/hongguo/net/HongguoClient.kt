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
class HongguoClient(
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

    data class SelectorItem(val id: String, val name: String)
    data class SelectorRow(val type: String, val name: String, val items: List<SelectorItem>)

    data class CategoryPage(
        val items: List<Drama>,
        val hasMore: Boolean,
        val nextOffset: Int,
        val sessionId: String,
        /** 首页（withPanel=true）时返回的筛选面板行；翻页为空。 */
        val selectorRows: List<SelectorRow> = emptyList()
    )

    /** 榜单页（bookmall cell/change，对照 Node hongguo-app-catalog browseList rank 分支）。 */
    data class RankPage(
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
     * @param withPanel 首页 true：need_selector_panel=true + req_type=default，返回 selectorRows
     */
    fun category(
        path: String,
        offset: Int,
        sessionId: String,
        filters: Map<String, String> = emptyMap(),
        withPanel: Boolean = false
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
            .put("need_selector_panel", withPanel)
            .put("limit", 15)
            .put("select_items", selectItems)
            .put("session_id", sessionId)
            .put("req_type", if (withPanel) "default" else "only_content")
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
        return CategoryPage(
            items, hasMore, nextOffset, data.optString("session_id"),
            if (withPanel) parseSelectorRows(data.optJSONArray("selector_rows")) else emptyList()
        )
    }

    /** 解析筛选面板行（对照 Go normalizeCategoryFilters；creation_status 行部分 scene 缺失时补齐）。 */
    private fun parseSelectorRows(rows: JSONArray?): List<SelectorRow> {
        rows ?: return emptyList()
        val out = ArrayList<SelectorRow>()
        val seenTypes = HashSet<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val type = row.optString("type")
            if (type !in FILTER_KINDS || !seenTypes.add(type)) continue
            val rawItems = row.optJSONArray("items") ?: continue
            val items = ArrayList<SelectorItem>()
            val seenIds = HashSet<String>()
            for (j in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(j) ?: continue
                val id = item.optString("selector_item_id")
                val name = item.optString("show_name")
                if (id.isEmpty() || name.isEmpty() || !seenIds.add(id)) continue
                items.add(SelectorItem(id, name))
            }
            if (items.isNotEmpty()) {
                out.add(SelectorRow(type, row.optString("row_name").ifEmpty { kindLabel(type) }, items))
            }
        }
        // 完结状态行部分 scene 不返回，硬编码补齐（对照 Go hongguo_client.go categorySelection）
        if ("creation_status" !in seenTypes) {
            out.add(
                SelectorRow("creation_status", "状态", listOf(
                    SelectorItem("creation_status_0", "已完结"),
                    SelectorItem("creation_status_1", "连载中")
                ))
            )
        }
        return out
    }

    private fun kindLabel(type: String): String = when (type) {
        "genre" -> "体裁"; "category_dim_theme" -> "题材"; "category_dim_role" -> "设定"
        "category_dim_epoch" -> "背景"; "sort" -> "排序"; "gender" -> "受众"
        "online_time" -> "时间"; "creation_status" -> "状态"; else -> type
    }

    // ---- 四板官方榜单（bookmall cell/change GET，同一签名链/匿名身份） ----

    /** 板块 ID 表（对照 Node hongguo-app-catalog RANKS）。 */
    val rankBoards: List<Pair<String, String>> = listOf(
        "all" to "ranklist_hot_play_sc",            // 红果热播榜（全站）
        "human" to "human_hot_play",                // 热门真人
        "comic_series_rank" to "comic_series_hot_play", // 热门漫剧
        "ai_playlet" to "ai_playlet_hot_play"       // 热门AI剧
    )

    fun rank(
        boardIndex: Int,
        offset: Int,
        sessionId: String
    ): RankPage {
        val (selected, subSelected) = rankBoards[boardIndex.coerceIn(0, rankBoards.size - 1)]
        val values = linkedMapOf(
            "tab_type" to if (offset == 0) "26" else "18",
            "client_req_type" to "2",
            "client_template" to "2",
            "background_selected_items" to "",
            "category_id" to "0",
            "limit" to "20",
            "cell_sub_id" to "0",
            "gender" to "2",
            "cell_id" to "7470092475068071998",
            "selected_items" to selected,
            "sub_selected_items" to subSelected,
            "panel_selected_items" to "",
            "offset" to maxOf(offset, 0).toString(),
            "session_id" to sessionId,
            "filter_ids" to "",
            "unlimited_selector_change_type" to "0"
        )
        val payload = getSignedJson("/reading/bookapi/bookmall/cell/change/v", values)
        val data = payload.optJSONObject("data")
            ?: throw IllegalStateException("榜单响应缺少 data")
        val view = data.optJSONObject("cell_view")
        val cellData = view?.optJSONArray("cell_data") ?: JSONArray()
        val items = ArrayList<Drama>()
        for (i in 0 until cellData.length()) {
            val cell = cellData.optJSONObject(i) ?: continue
            val videos = cell.optJSONArray("video_data") ?: continue
            for (j in 0 until videos.length()) {
                normalizeDrama(videos.opt(j))?.let { items.add(it) }
            }
        }
        val hasMore = data.optBoolean("has_more", view?.optBoolean("has_more", false) ?: false)
        var nextOffset = data.optInt("next_offset", view?.optInt("next_offset", 0) ?: 0)
        if (nextOffset <= maxOf(offset, 0)) {
            // 游标停滞保护（对照 Go stopStalledPage）
            return RankPage(items, false, nextOffset, data.optString("session_id"))
        }
        return RankPage(items, hasMore, nextOffset, data.optString("session_id"))
    }

    /** 匿名签名 GET（空响应轮换重试；GET 无 body，无 STUB/Content-Type）。号内存复用+空响应轮换。 */
    @Volatile
    private var signedGetIdentity: HongguoDeviceRegister.Identity? = null

    private fun getSignedJson(path: String, values: LinkedHashMap<String, String>): JSONObject {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                // 号复用+空响应轮换（feedIdentity 同款；随机假身份在模拟器出口全空——实证）
                val identity = signedGetIdentity?.takeIf { attempt == 0 }
                    ?: HongguoDeviceRegister.registerFresh().also { signedGetIdentity = it }
                return getOnce(path, values, identity.deviceId, identity.installId)
            } catch (e: EmptyResponseException) {
                lastError = e
                signedGetIdentity = null
                if (attempt == 2) break
                Thread.sleep(100)
            }
        }
        throw lastError ?: IllegalStateException("红果接口无响应")
    }

    /** GET 签名（bookmall 等）：F0.x1 形态（73532 档案+HongguoEnv+真版签名头集）。 */
    private fun getOnce(
        path: String, values: LinkedHashMap<String, String>,
        deviceId: String, installId: String
    ): JSONObject {
        val q = java.util.LinkedHashMap<String, String>()
        q["iid"] = installId
        q["device_id"] = deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "73532"; q["version_name"] = "7.3.5.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi
        q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "73532"
        q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = "73532"; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = "73532"
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
        q["ts"] = (System.currentTimeMillis() / 1000).toString()
        q.putAll(values)

        val versionName = "7.3.5.32"
        val signed = HongguoCrypto.signRequest(q, null, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(
            signed.query, null, nowSec, versionName, deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val ua = HongguoEnv.ua("73532")
        val traceId = java.util.UUID.randomUUID().toString().replace("-", "")
            .let { "00-$it-${it.substring(0, 16)}-01" }
        val req = Request.Builder()
            .url("$apiBase$path?${signed.query}")
            .get()
            .header("User-Agent", ua)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("X-SS-DP", "8662")
            .header("X-Tt-Store-Region", "cn")
            .header("X-Tt-Store-Region-Src", "did")
            .header("X-XS-From-Web", "0")
            .header("Sdk-Version", "2")
            .header("User-Agent", ua)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("x-ss-req-ticket", ticket)
            .header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
            .header("x-xs-from-web", "0")
            .header("x-khronos", (signed.headers["X-Khronos"] ?: ""))
            .header("x-ladon", realLadonArgus.ladon)
            .header("x-argus", realLadonArgus.argus)
            .header("x-gorgon", (signed.headers["X-Gorgon"] ?: ""))
            .header("x-helios", HongguoArgus.helios(nowSec))
            .header("lc", "101")
            .header("x-ss-dp", "8662")
            .header("x-tt-trace-id", traceId)
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            android.util.Log.d("Hongguo", "rank $path http=${resp.code} len=${bytes.size}")
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

    // ---- 签名 POST：注册身份 + 空响应轮换换号重试 ----
    @Volatile private var feedIdentity: HongguoDeviceRegister.Identity? = null

    private fun postSignedJson(path: String, body: JSONObject): JSONObject {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                // 注册号复用，空响应才换新（feed 翻页高频，不能每页注册）
                val identity = feedIdentity?.takeIf { attempt == 0 }
                    ?: HongguoDeviceRegister.registerFresh().also { feedIdentity = it }
                return postOnce(path, raw, identity.deviceId, identity.installId)
            } catch (e: EmptyResponseException) {
                feedIdentity = null
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
        // Kotlin 签名链（2026-11-10 与 F0.x1 逐字段对拍后，L1 字节码通道已拆除）：
        // （73532+HongguoEnv 动态环境+插入序）+ 真版 Ladon/Argus/Helios/Gorgon +
        // HGTV 同款头集（E0 外层+G0 改写后形态）。播放接口三通道实测与 L1 字节码等价。
        val q = java.util.LinkedHashMap<String, String>()
        q["iid"] = installId
        q["device_id"] = deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "73532"; q["version_name"] = "7.3.5.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi
        q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "73532"
        q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = "73532"; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = "73532"
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
        q["ts"] = (System.currentTimeMillis() / 1000).toString()

        val versionName = "7.3.5.32"
        val signed = HongguoCrypto.signRequest(q, raw, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(
            signed.query, raw, nowSec, versionName, deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val ua = HongguoEnv.ua("73532")
        val traceId = java.util.UUID.randomUUID().toString().replace("-", "")
            .let { "00-$it-${it.substring(0, 16)}-01" }
        val headers: List<Pair<String, String>> = listOf(
            // E0 外层
            "User-Agent" to ua,
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "X-SS-DP" to "8662",
            "X-Tt-Store-Region" to "cn",
            "X-Tt-Store-Region-Src" to "did",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            // F0.x1.d 签名头集（G0 改写后）
            "User-Agent" to ua,
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to ticket,
            "sdk-version" to "2",
            "passport-sdk-version" to "5051452",
            "x-xs-from-web" to "0",
            "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
            "x-ladon" to realLadonArgus.ladon,
            "x-argus" to realLadonArgus.argus,
            "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
            "x-helios" to HongguoArgus.helios(nowSec),
            "Content-Type" to "application/json; charset=UTF-8",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-reading-request" to "$ticket-${java.security.SecureRandom().nextLong() and 0xffffffffL}",
            "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
            // G0 追加（feed 明文 body，无 Content-Encoding）
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-tt-trace-id" to traceId
        )

        val req = Request.Builder()
            .url("$apiBase$path?${signed.query}")
            .post(raw.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            android.util.Log.d(
                "Hongguo", "feed $path http=${resp.code} len=${bytes.size} " +
                    "tls=${resp.handshake?.tlsVersion} logid=${resp.header("x-tt-logid")}"
            )
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
