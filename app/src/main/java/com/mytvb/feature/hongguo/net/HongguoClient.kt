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
        val seriesStatus: String,
        /** 榜单推荐位状态标签（爆剧/新剧…），来自 recommend_reason_list.tag_info。 */
        val statusTag: String = ""
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

    /**
     * 搜索发现条目（猜你想搜/热搜榜共用）：seriesId 非空=可直接弹详情的剧；
     * 空=纯推荐词（点击执行搜索）。tip 为角标文案（card_tips/rec_text，如「96.4%完播」）；
     * contentType 仅 mixed 榜分类用（1/7=短剧、1004=漫剧）。
     */
    data class DiscoveryItem(
        val title: String,
        val seriesId: String = "",
        val cover: String = "",
        val episodeCount: Int = 0,
        val score: String = "",
        val playCount: Long = 0L,
        val tip: String = "",
        val contentType: String = ""
    )

    data class DiscoveryBoard(val id: String, val items: List<DiscoveryItem>)

    data class DiscoveryData(
        val guesses: List<DiscoveryItem>,
        val boards: List<DiscoveryBoard>
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

    // ---- 搜索发现（猜你想搜 + 短剧/漫剧热搜榜） ----
    //
    // 上游 GET /reading/bookapi/plan/v1/（from=search_input_page&bookstore_tab=16），
    // 匿名元数据读取（本通道本就不带账号票据，语义同 Go 版清 token/uid 后请求）。
    // 解析对照 hongguo-tv parseSearchDiscovery：递归 lynx_data/cell_data 找
    // cell_name=猜你想搜（≤4 条）/短剧热搜榜/漫剧热搜榜（各 ≤6 条）；「热搜榜」
    // 混合榜按 content_type 分类（1/7=短剧、1004=漫剧）。仅接受已知 cell——
    // 话题/图书/广告块即使带 ID 也不冒充可播放剧集。失败抛错由调用方隐藏该区。

    /** 搜索输入页发现接口（照抄 Go searchDiscoveryQuery 参数集）。 */
    fun discovery(): DiscoveryData {
        val values = linkedMapOf(
            "user_is_login" to "0",
            "bookstore_tab" to "16",
            "bookstore_tab_type" to "16",
            "scene" to "10",
            "limit" to "0",
            "tab_name" to "feed",
            "from" to "search_input_page",
            "query_history_removed" to "false",
            "offset" to "0",
            "search_middle_page_version" to "4",
            "search_source" to "1",
            "new_search_middle_page" to "true",
            "hot_word_exchange" to "false"
        )
        val payload = getSignedJson("/reading/bookapi/plan/v1/", values)
        return parseDiscovery(payload)
    }

    // ---- 相关作品 / 创作者（详情页长按菜单两个入口的数据通道） ----
    //
    // 相关作品（三级数据源，口径取自 openhongguo SDK 对官方客户端的观察 2026-10-24）：
    // 1) 系列剧：GET /reading/bookapi/plan/v1/ from=single_col_same_ip_series&filter_ids=
    //    <id>（同 IP 全部季，cell show_type=547；无其它季服务端回 code=101116）；
    // 2) 相关推荐：from=select_panel_recommend（详情页相关 tab；匿名拿不到 160 cell，
    //    取任意有内容 cell 当通用推荐）；
    // 3) Repository 层再兜底标题主干搜索。签名链与 searchTab 同基座（72232+cdid+
    //    sinfonlinea）——detail_page_more_related 旧口径匿名只回 lynx 模板（已弃用）。
    fun related(bookId: String): List<Drama> =
        planSeriesSeasons(bookId).ifEmpty { planRecommend(bookId) }

    /** 系列剧（同 IP 全部季，含自己在内——由调用方排除）。无其它季返回空。 */
    fun planSeriesSeasons(bookId: String): List<Drama> {
        val list = planFeed(bookId, "single_col_same_ip_series", extraFilterIds = true)
        // 547 cell 的 video_data 是季列表；若无 547（如 code=101116）返回空
        return list
    }

    /** 相关推荐（select_panel_recommend；匿名回通用「更多好剧」也接受）。 */
    fun planRecommend(bookId: String): List<Drama> =
        planFeed(bookId, "select_panel_recommend", extraFilterIds = false)

    private fun planFeed(bookId: String, from: String, extraFilterIds: Boolean): List<Drama> {
        require(bookId.matches(Regex("\\d{1,30}"))) { "series_id 无效" }
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val identity = if (attempt == 0) obtainApiIdentity() else rotateApiIdentity()
                return planOnce(bookId, from, extraFilterIds, identity)
            } catch (e: EmptyResponseException) {
                lastError = e
                apiIdentity = null
                if (attempt == 2) break
                Thread.sleep(100)
            } catch (e: PlanNoSeasonException) {
                return emptyList()   // 101116：单季剧，无系列 cell，非错误
            }
        }
        throw lastError ?: IllegalStateException("红果接口无响应")
    }

    private class PlanNoSeasonException : IllegalStateException("plan no season")

    /** plan feed 单次请求（searchTab 同款 72232 搜索中段页签名全家桶）。 */
    private fun planOnce(
        bookId: String, from: String, extraFilterIds: Boolean,
        identity: HongguoDeviceRegister.Identity
    ): List<Drama> {
        val q = java.util.LinkedHashMap<String, String>()
        q["iid"] = identity.installId
        q["device_id"] = identity.deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "72232"; q["version_name"] = "7.2.2.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi
        q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "72232"
        q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = "72232"; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = "72232"
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
        q["cdid"] = identity.cdid
        // 业务参数（openhongguo SignedPlanPairsFrom，官方客户端观察值）
        q["book_id"] = bookId
        q["from"] = from
        q["scene"] = "10"
        q["session_uuid"] = java.util.UUID.randomUUID().toString()
        q["user_is_login"] = "0"
        if (extraFilterIds) q["filter_ids"] = bookId

        val nowMs = System.currentTimeMillis()
        val versionName = "7.2.2.32"
        val signed = HongguoCrypto.signRequest(
            q, null, nowMs, identity.deviceId,
            targetBranch = 0, version = versionName
        )
        val ts = signed.headers["X-Khronos"]?.toIntOrNull() ?: (nowMs / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(
            signed.query, null, ts, versionName, identity.deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val traceHex = java.util.UUID.randomUUID().toString().replace("-", "")
        val ua = HongguoEnv.ua("72232")
        val req = Request.Builder()
            .url("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/plan/v1/?${signed.query}")
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
            .header("x-khronos", signed.headers["X-Khronos"] ?: "")
            .header("x-ladon", realLadonArgus.ladon)
            .header("x-argus", realLadonArgus.argus)
            .header("x-gorgon", signed.headers["X-Gorgon"] ?: "")
            .header("x-helios", HongguoArgus.helios(ts))
            .header("lc", "101")
            .header("x-ss-dp", "8662")
            .header("x-tt-trace-id", "00-$traceHex-${traceHex.substring(0, 16)}-01")
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            android.util.Log.d("Hongguo", "plan from=$from http=${resp.code} len=${bytes.size}")
            if (!resp.isSuccessful) throw IllegalStateException("相关作品 HTTP ${resp.code}")
            if (bytes.isEmpty()) throw EmptyResponseException()
            val payload = JSONObject(String(bytes, Charsets.UTF_8))
            val code = payload.optString("code", "0")
            if (code == "101116") throw PlanNoSeasonException()
            if (code != "0") throw EmptyResponseException()
            // 系列剧只认 547 cell；相关推荐接受任意带剧数据的 cell（匿名回 401 通用推荐）
            return parseRelatedCells(payload, bookId, exactOnly = from == "single_col_same_ip_series")
        }
    }

    /**
     * 解析 plan cell（openhongguo ParsePlanCell 口径）：条目取 video_data，空则
     * cell_data。exactOnly=true 只认 547/相关作品 cell（系列剧语义）；false 时
     * 兜底任意带剧数据的 cell（相关推荐语义——匿名拿不到 160，401 通用推荐也收）。
     */
    internal fun parseRelatedCells(
        payload: JSONObject, bookId: String, exactOnly: Boolean = false
    ): List<Drama> {
        val cells = payload.optJSONArray("data") ?: return emptyList()
        val out = LinkedHashMap<String, Drama>()
        collectRelatedCells(cells, exactOnly = true, bookId, out)
        if (out.isEmpty() && !exactOnly) {
            collectRelatedCells(cells, exactOnly = false, bookId, out)
        }
        if (out.isEmpty()) {
            // 诊断：cell 概要定位结构差异（exactOnly 两遍皆空才会到这里）
            val digest = StringBuilder()
            for (i in 0 until cells.length()) {
                val cell = cells.optJSONObject(i) ?: continue
                val keyItr = cell.keys()
                val keysStr = StringBuilder()
                var count = 0
                while (keyItr.hasNext() && count < 12) {
                    if (count > 0) keysStr.append(',')
                    keysStr.append(keyItr.next())
                    count++
                }
                digest.append("[st=").append(cell.optInt("show_type", -1))
                    .append(" name=").append(cell.optString("cell_name"))
                    .append(" vd=").append(cell.optJSONArray("video_data")?.length() ?: -1)
                    .append(" cd=").append(
                        when (val cd = cell.opt("cell_data")) {
                            is org.json.JSONArray ->
                                "arr${cd.length()} first=${cd.opt(0)?.toString()?.take(400) ?: "null"}"
                            is org.json.JSONObject -> cd.toString().take(240)
                            is String -> cd.take(120)
                            else -> "null"
                        }
                    )
                    .append(" keys=").append(keysStr)
                    .append("] ")
            }
            android.util.Log.w("Hongguo", "related empty book_id=$bookId cells=$digest")
        }
        return out.values.toList()
    }

    /** 收集相关作品 cell 的 video_data（exactOnly=true 只认 547/相关作品，否则任意 cell）。 */
    private fun collectRelatedCells(
        cells: org.json.JSONArray, exactOnly: Boolean, bookId: String,
        out: LinkedHashMap<String, Drama>
    ) {
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            if (exactOnly &&
                cell.optInt("show_type", -1) != RELATED_SHOW_TYPE &&
                cell.optString("cell_name") != RELATED_CELL_NAME
            ) continue
            // 列表字段两代并存：73532 响应为 video_data，72232（HGTV 通道）为
            // cell_data（MuMu 实证 2026-11-13「猜你喜欢」st=506 keys 含 cell_data）
            val videos = cell.optJSONArray("video_data")
                ?: cell.optJSONArray("cell_data")
                ?: continue
            for (j in 0 until videos.length()) {
                val drama = normalizeDrama(videos.opt(j)) ?: continue
                if (drama.seriesId != bookId && !out.containsKey(drama.seriesId)) {
                    out[drama.seriesId] = drama
                }
            }
        }
    }

    /** 创作者（带 userId——PersonWorks 拉作品要用它，不是剧 id）。 */
    data class Creator(val userId: String, val name: String)

    /**
     * 批量查短剧创作者：POST /reading/distribution/book_pack_fields/inner_feed_detail/v1
     * （req_ids 批量）。两路互补（2026-11-13 MuMu 实测不同剧各给一路）：
     * ① author_user_info.base_info = 出品方（如「橙子漫剧」）优先；
     * ② main_creator_user_groups[].ugc_user_infos[0].base_info = 主演（领衔）。
     * userId 一并带回（Person/PersonWorks 用）；multi-detail 的 author 是书城小说
     * 作者口径且无 userId，仅作 Repository 层名字兜底。单批失败返回空映射。
     */
    fun innerFeedDetailCreators(seriesIds: List<String>): Map<String, Creator> {
        val ids = seriesIds.filter { it.matches(Regex("\\d{1,30}")) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Creator>()
        for (batch in ids.chunked(MULTI_DETAIL_BATCH)) {
            val reqIds = JSONArray()
            for (id in batch) {
                // 官方请求体 req_ids 是数字数组（非字符串）
                id.toLongOrNull()?.let { reqIds.put(it) }
            }
            if (reqIds.length() == 0) continue
            val body = JSONObject().apply {
                put("book_pack_channel", "inner_feed_detail")
                put("req_fields", JSONArray(INNER_FEED_REQ_FIELDS))
                put("req_ids", reqIds)
            }
            try {
                val payload = postSignedJson(
                    "/reading/distribution/book_pack_fields/inner_feed_detail/v1",
                    body
                )
                val parsed = parseInnerFeedCreators(payload)
                if (parsed.isEmpty()) {
                    android.util.Log.w(
                        "Hongguo",
                        "inner_feed_detail miss ids=$ids resp=${payload.toString().take(400)}"
                    )
                }
                out.putAll(parsed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("Hongguo", "inner_feed_detail fail: ${e.message}")
            }
        }
        return out
    }

    /**
     * 演员主页（GET /reading/user/profile/get/v1/，openhongguo 同款）：名字+简介+
     * 头像+粉丝/获赞。查不到返回 null（名字为空即无此人）。
     */
    fun person(userId: String): Person? {
        if (!userId.matches(Regex("\\d{1,30}"))) return null
        return try {
            val payload = getSignedJson(
                "/reading/user/profile/get/v1/",
                linkedMapOf("user_id" to userId, "user_is_login" to "0")
            )
            parsePerson(payload)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("Hongguo", "person fail: ${e.message}")
            null
        }
    }

    /** 演员主页（openhongguo ParseProfile：data.ugc_user）。 */
    data class Person(
        val userId: String, val name: String, val description: String,
        val avatar: String, val fans: Long, val likes: Long
    )

    /**
     * 创作者作品（POST /novel/commentapi/user/profile/{uid}/v1）：主页「作品›剧作」
     * tab（tab_type=23/sub_tab_type=22，按时间倒序每页 18 条）。body 照抄官方 App
     * 抓包（2026-11-13）；**域名必须 sinfonlinec**——sinfonlineb 同参响应恒空
     * （用户 hook 官方 App 实测对照定位）。
     */
    fun personWorks(userId: String): List<Drama> {
        if (!userId.matches(Regex("\\d{1,30}"))) return emptyList()
        return try {
            val body = JSONObject().apply {
                put("comment_source", 1506)
                put("count", 18)
                put("cursor", "")
                put("extra_param", JSONObject().put("is_video_tab_comment", "1"))
                put("locate_post_id", "")
                put("profile_user_type", 0)
                put("sort", "time_desc")
                put("sub_tab_type", 22)
                put("tab_type", 23)
                put("target_user_id", userId)
            }
            val payload = postSignedJson(
                "/novel/commentapi/user/profile/$userId/v1", body,
                baseUrl = "https://api5-normal-sinfonlinec.fqnovel.com",
                version = HongguoApiVersion.V74532
            )
            parsePersonWorks(payload)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("Hongguo", "personWorks fail: ${e.message}")
            emptyList()
        }
    }

    /**
     * 批量查作者名：GET /reading/bookapi/multi-detail/v（book_id=逗号分隔&book_type=2），
     * 对照 HGTV b.x()：每项取 author，空则回落 source；批大小 50 同款。
     * 书城口径（小说作者/来源方），仅作 inner_feed_detail 缺数据时的兜底。
     * 单批失败返回空映射（作者名缺失按「暂无创作者」处理，不阻断菜单）。
     */
    fun authorsOf(seriesIds: List<String>): Map<String, String> {
        val ids = seriesIds.filter { it.matches(Regex("\\d{1,30}")) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (batch in ids.chunked(MULTI_DETAIL_BATCH)) {
            try {
                val payload = getSignedJson(
                    "/reading/bookapi/multi-detail/v",
                    linkedMapOf(
                        "book_id" to batch.joinToString(","),
                        "book_type" to "2"
                    )
                )
                val rows = payload.optJSONArray("data") ?: continue
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("book_id").ifEmpty { row.optString("series_id") }
                    if (id.isEmpty()) continue
                    val author = row.optString("author").ifEmpty { row.optString("source") }.trim()
                    if (author.isNotEmpty()) out[id] = author
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("Hongguo", "multi-detail fail: ${e.message}")
            }
        }
        return out
    }

    private fun parseDiscovery(payload: JSONObject): DiscoveryData {
        val guesses = ArrayList<DiscoveryItem>()
        val boards = LinkedHashMap<String, MutableList<DiscoveryItem>>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 8 || value == null) return
            when (value) {
                is String -> {
                    // lynx_data 可能是 JSON 字符串（Go 版 decode 后递归）
                    if (value.length <= 2 * 1024 * 1024) {
                        runCatching {
                            visit(org.json.JSONTokener(value).nextValue(), depth + 1)
                        }
                    }
                }
                is JSONArray -> {
                    val n = minOf(value.length(), 128)
                    for (i in 0 until n) visit(value.opt(i), depth + 1)
                }
                is JSONObject -> {
                    val name = value.optString("cell_name")
                    if (name == "猜你想搜" && guesses.isEmpty()) {
                        guesses += discoveryItems(value.opt("video_data"), "", 4)
                        if (guesses.isEmpty()) {
                            // 推荐词形态：search_tag_data[].tag_title（tag ID 不冒充剧集 ID）
                            val tags = value.optJSONArray("search_tag_data")
                            if (tags != null) {
                                val rows = JSONArray()
                                for (i in 0 until tags.length()) {
                                    val tag = tags.optJSONObject(i) ?: continue
                                    rows.put(JSONObject().put("title", tag.optString("tag_title")))
                                }
                                guesses += discoveryItems(rows, "", 4)
                            }
                        }
                    }
                    when (name) {
                        "短剧热搜榜" -> if (boards["short"].isNullOrEmpty()) {
                            boards["short"] = discoveryItems(value.opt("video_data"), "short", 6).toMutableList()
                        }
                        "漫剧热搜榜" -> if (boards["comic"].isNullOrEmpty()) {
                            boards["comic"] = discoveryItems(value.opt("video_data"), "comic", 6).toMutableList()
                        }
                        "热搜榜" -> {
                            // 现网部分计划只叫「热搜榜」：按明确 content_type 分类，不猜
                            for (item in discoveryItems(value.opt("video_data"), "mixed", 6)) {
                                val id = if (item.contentType == "1004") "comic" else "short"
                                val bucket = boards.getOrPut(id) { mutableListOf() }
                                if (bucket.none { it.seriesId == item.seriesId } && bucket.size < 6) {
                                    bucket.add(item)
                                }
                            }
                        }
                    }
                    visit(value.opt("lynx_data"), depth + 1)
                    visit(value.opt("cell_data"), depth + 1)
                }
            }
        }
        visit(payload.opt("data"), 0)
        val boardList = listOf("short", "comic").mapNotNull { id ->
            boards[id]?.takeIf { it.isNotEmpty() }?.let { DiscoveryBoard(id, it) }
        }
        return DiscoveryData(guesses, boardList)
    }

    /** content_type 透传位（mixed 榜分类用）。 */
    private fun discoveryItems(value: Any?, board: String, limit: Int): List<DiscoveryItem> {
        if (value == null) return emptyList()
        val rows = when (value) {
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> return emptyList()
        }
        val out = ArrayList<DiscoveryItem>()
        val seen = HashSet<String>()
        for (index in 0 until rows.length()) {
            if (out.size >= limit) break
            val row = rows.optJSONObject(index) ?: continue
            val title = normalizeSearchQuery(row.optString("title")) ?: continue
            val id = row.optString("series_id_str").ifEmpty { row.optString("series_id") }
            val kind = row.optString("content_type")
            if (kind.isNotEmpty() && kind != "1" && kind != "7" && kind != "1004") continue
            if (board == "mixed" && kind.isEmpty()) continue
            val key: String
            val item: DiscoveryItem
            if (id.matches(Regex("\\d{1,30}")) && id != "0") {
                val drama = normalizeDrama(row)
                key = id
                item = DiscoveryItem(
                    title = title,
                    seriesId = id,
                    cover = HongguoCoverUrl.rewrite(
                        drama?.cover ?: row.optString("cover").ifEmpty { row.optString("cover_url") }
                            .takeIf { it.startsWith("http") } ?: ""
                    ),
                    episodeCount = drama?.episodeCount ?: 0,
                    score = drama?.score ?: "",
                    playCount = drama?.playCount ?: 0L,
                    tip = row.optString("card_tips").ifEmpty { row.optString("rec_text") },
                    contentType = kind.ifEmpty {
                        when (board) {
                            "comic" -> "1004"
                            "mixed" -> ""
                            else -> "1"
                        }
                    }
                )
            } else {
                if (board.isNotEmpty()) continue
                item = DiscoveryItem(title = title)
                key = "query:$title"
            }
            if (!seen.add(key)) continue
            out.add(item)
        }
        return out
    }

    /** 搜索词规整（对照 Go normalizeSearchQuery：空白折叠、空串拒绝）。 */
    private fun normalizeSearchQuery(value: String): String? {
        val collapsed = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ")
        return collapsed.takeIf { it.isNotEmpty() }
    }

    /** 匿名签名 GET（空响应轮换重试；GET 无 body，无 STUB/Content-Type）。与 feed 共用一号。 */
    private fun getSignedJson(
        path: String, values: LinkedHashMap<String, String>,
        version: HongguoApiVersion = HongguoApiVersion.V73532
    ): JSONObject {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                // 号复用+空响应轮换（apiIdentity 共享；随机假身份在模拟器出口全空——实证）
                val identity = if (attempt == 0) obtainApiIdentity() else rotateApiIdentity()
                return getOnce(path, values, identity.deviceId, identity.installId, version)
            } catch (e: EmptyResponseException) {
                lastError = e
                apiIdentity = null
                if (attempt == 2) break
                Thread.sleep(100)
            }
        }
        throw lastError ?: IllegalStateException("红果接口无响应")
    }

    /** GET 签名（bookmall 等）：F0.x1 形态（HongguoEnv+真版签名头集）。 */
    private fun getOnce(
        path: String, values: LinkedHashMap<String, String>,
        deviceId: String, installId: String,
        version: HongguoApiVersion = HongguoApiVersion.V73532
    ): JSONObject {
        val q = java.util.LinkedHashMap<String, String>()
        q["iid"] = installId
        q["device_id"] = deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = version.code; q["version_name"] = version.versionName
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi
        q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = version.code
        q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = version.code; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = version.code
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
        q["ts"] = (System.currentTimeMillis() / 1000).toString()
        q.putAll(values)

        val versionName = version.versionName
        val signed = HongguoCrypto.signRequest(q, null, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(
            signed.query, null, nowSec, versionName, deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val ua = HongguoEnv.ua(version.code)
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
            android.util.Log.d(
                "Hongguo",
                "rank $path vc=${version.code} http=${resp.code} len=${bytes.size}"
            )
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

    // ---- 签名请求身份：feed(POST)/rank(GET) 共用一号，注册互斥 + 空响应轮换 ----
    @Volatile private var apiIdentity: HongguoDeviceRegister.Identity? = null

    /** 注册互斥（多页并发首启时防竞态各注册一号——2026-11-10 实测一次启动烧 3 个号）。 */
    private val registerLock = Any()

    private fun obtainApiIdentity(): HongguoDeviceRegister.Identity =
        apiIdentity ?: synchronized(registerLock) {
            apiIdentity ?: HongguoDeviceRegister.registerFresh().also { apiIdentity = it }
        }

    private fun rotateApiIdentity(): HongguoDeviceRegister.Identity =
        synchronized(registerLock) {
            HongguoDeviceRegister.registerFresh().also { apiIdentity = it }
        }

    private fun postSignedJson(
        path: String, body: JSONObject,
        baseUrl: String = apiBase,
        version: HongguoApiVersion = HongguoApiVersion.V73532
    ): JSONObject {
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                // 注册号复用，空响应才换新（feed 翻页高频，不能每页注册）
                val identity = if (attempt == 0) obtainApiIdentity() else rotateApiIdentity()
                return postOnce(path, raw, identity.deviceId, identity.installId, baseUrl, version)
            } catch (e: EmptyResponseException) {
                apiIdentity = null
                lastError = e
                if (attempt == 2) break
                Thread.sleep(100)
            }
        }
        throw lastError ?: IllegalStateException("红果接口无响应")
    }

    private class EmptyResponseException : IllegalStateException("empty body")


    private fun postOnce(
        path: String, raw: ByteArray, deviceId: String, installId: String,
        baseUrl: String = apiBase,
        version: HongguoApiVersion = HongguoApiVersion.V73532
    ): JSONObject {
        // Kotlin 签名链（2026-11-10 与 F0.x1 逐字段对拍后，L1 字节码通道已拆除）：
        // （73532+HongguoEnv 动态环境+插入序）+ 真版 Ladon/Argus/Helios/Gorgon +
        // HGTV 同款头集（E0 外层+G0 改写后形态）。播放接口三通道实测与 L1 字节码等价。
        val q = java.util.LinkedHashMap<String, String>()
        q["iid"] = installId
        q["device_id"] = deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = version.code; q["version_name"] = version.versionName
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi
        q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = version.code
        q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = version.code; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = version.code
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "1"
        q["ts"] = (System.currentTimeMillis() / 1000).toString()

        val versionName = version.versionName
        val signed = HongguoCrypto.signRequest(q, raw, System.currentTimeMillis(), deviceId, targetBranch = 0, version = versionName)
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val realLadonArgus = HongguoArgus.generate(
            signed.query, raw, nowSec, versionName, deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val ua = HongguoEnv.ua(version.code)
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
            .url("$baseUrl$path?${signed.query}")
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
        // 榜单形态（bookmall）字段降级：无 tags 数组时从 category_schema 提类型名（name 字段，
        // "家庭 · 家庭温情 · 治愈…"）；热度在 secondary_info_list 按「热度」后缀筛（data_type 恒 1
        // 不区分，同列表混有"39.2万次点赞/评分9.6"）；状态标签在 recommend_reason_list.tag_info
        val tags = stringList(obj.optJSONArray("tags")).ifEmpty {
            val schema = runCatching { JSONArray(obj.optString("category_schema")) }.getOrNull()
            if (schema != null) {
                val names = ArrayList<String>(schema.length())
                for (i in 0 until schema.length()) {
                    val name = schema.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotEmpty()) names.add(name)
                }
                names
            } else {
                emptyList()
            }
        }
        val heatTextResolved = obj.optString("heatText").ifEmpty {
            val infos = obj.optJSONArray("secondary_info_list")
            var resolved = ""
            if (infos != null) {
                for (i in 0 until infos.length()) {
                    val content = infos.optJSONObject(i)?.optString("content").orEmpty()
                    if (content.endsWith("热度")) {
                        resolved = content.removeSuffix("热度")
                        break
                    }
                }
            }
            resolved
        }
        val statusTag = runCatching {
            obj.optJSONObject("recommend_reason_list")
                ?.optJSONObject("tag_info")
                ?.optString("text")
                .orEmpty()
        }.getOrDefault("")

        return Drama(
            seriesId = id,
            title = title,
            cover = HongguoCoverUrl.rewrite(str("series_cover", "cover", "cover_url")),
            intro = str("series_intro", "video_desc", "description"),
            score = str("score", "rating", "scoreText"),
            playCount = obj.optLong("play_cnt", 0L),
            episodeCount = firstPositive(obj.optInt("episode_cnt", 0), obj.optInt("episode_total_cnt", 0)),
            vid = str("vid", "video_id"),
            tags = tags,
            heatText = heatTextResolved,
            statusTag = statusTag,
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
        /**
         * 接口版本档案：不同接口须按 HGTV 各自的 App 版本口径请求——服务端按
         * version_code 下发不同 cell 结构（实证 2026-11-13：plan/v1 相关作品
         * 73532 只回「猜你喜欢」st=506，72232 才回「相关作品」st=547）。
         */
        enum class HongguoApiVersion(val code: String, val versionName: String) {
            /** feed/landpage/bookmall 通道（线上验证）。 */
            V73532("73532", "7.3.5.32"),

            /** plan/v1 相关作品通道（HGTV t0 同款）+ 搜索（searchTab 已同款）。 */
            V72232("72232", "7.2.2.32"),

            /** commentapi 用户主页通道（官方 7.4.5 实测口径）。 */
            V74532("74532", "7.4.5.32")
        }

        private val FILTER_KINDS = listOf(
            "genre", "category_dim_theme", "category_dim_role", "category_dim_epoch",
            "sort", "gender", "online_time", "creation_status"
        )

        /** 相关作品 cell 过滤值（对照 HGTV b 构造器：show_type=547 / cell_name="相关作品"）。 */
        private const val RELATED_SHOW_TYPE = 547
        private const val RELATED_CELL_NAME = "相关作品"

        /** multi-detail 批大小（HGTV f7216q=50 同款）。 */
        private const val MULTI_DETAIL_BATCH = 50

        /** inner_feed_detail 请求字段（照抄官方请求体全集，服务端按需回填）。 */
        private val INNER_FEED_REQ_FIELDS = listOf(
            "author_user_info", "album_id", "album_serial_count", "series_in_album_order",
            "real_album_serial_count", "rec_tags", "video_tag_info", "main_creator_users_group",
            "op_tag", "show_urge_update", "has_urge_update", "urge_update_count"
        )

        /**
         * inner_feed_detail 响应解析：data 是 { id -> { base_info } } 映射。
         * 创作者两路（2026-11-13 MuMu 实测+用户反馈定案）：
         * ① author_user_info.base_info（出品方/上传者，带 user_id）优先；
         * ② main_creator_user_groups 里 group_type=="出品方"（ugc_creator_type=2）
         *    的组首位——部分剧只有这一路；**「主演」组不是创作者**（演员剧作
         *    tab 空、按名搜索匹配不上，曾致「创作者只有一个作品」误报，跳过）。
         * 都没有时由 Repository 回落 multi-detail author（HGTV 同款上传者口径）。
         */
        internal fun parseInnerFeedCreators(payload: JSONObject): Map<String, Creator> {
            val data = payload.optJSONObject("data") ?: return emptyMap()
            val out = LinkedHashMap<String, Creator>()
            for (key in data.keys()) {
                val base = data.optJSONObject(key)?.optJSONObject("base_info") ?: continue
                val creator = base.optJSONObject("author_user_info")
                    ?.optJSONObject("base_info")
                    ?.let { creatorOf(it) }
                    ?: studioGroupFirst(base)
                if (creator != null) out[key] = creator
            }
            return out
        }

        /** base_info 节点 → Creator（user_id + user_name）。 */
        private fun creatorOf(base: JSONObject): Creator? {
            val name = base.optString("user_name").trim()
            if (name.isEmpty()) return null
            return Creator(userId = base.optString("user_id"), name = name)
        }

        /** main_creator_user_groups 的「出品方」组（group_type/ugc_creator_type 双判）首位。 */
        private fun studioGroupFirst(base: JSONObject): Creator? {
            val groups = base.optJSONArray("main_creator_user_groups") ?: return null
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val isStudio = group.optString("group_type") == "出品方" ||
                    group.optInt("ugc_creator_type", -1) == 2
                if (!isStudio) continue
                val infos = group.optJSONArray("ugc_user_infos") ?: continue
                for (j in 0 until infos.length()) {
                    val c = infos.optJSONObject(j)?.optJSONObject("base_info")?.let { creatorOf(it) }
                    if (c != null) return c
                }
            }
            return null
        }

        /** person 响应解析（openhongguo ParseProfile：data.ugc_user）。名字空=null。 */
        internal fun parsePerson(payload: JSONObject): Person? {
            val user = payload.optJSONObject("data")?.optJSONObject("ugc_user") ?: return null
            val base = user.optJSONObject("base_info") ?: return null
            val name = base.optString("user_name").trim()
            if (name.isEmpty()) return null
            val relation = user.optJSONObject("user_relation")
            return Person(
                userId = user.optString("user_id"),
                name = name,
                description = base.optString("description"),
                avatar = base.optString("user_avatar"),
                fans = relation?.optLong("fans_num", 0L) ?: 0L,
                likes = relation?.optLong("recv_digged_count", 0L) ?: 0L
            )
        }

        /** personWorks 响应解析（openhongguo ParseUserWorks：data.data_list[].video）。 */
        internal fun parsePersonWorks(payload: JSONObject): List<Drama> {
            val rows = payload.optJSONObject("data")?.optJSONArray("data_list")
                ?: return emptyList()
            val out = ArrayList<Drama>()
            for (i in 0 until rows.length()) {
                val video = rows.optJSONObject(i)?.optJSONObject("video") ?: continue
                val id = video.optString("video_id")
                val common = video.optJSONObject("common")
                val title = common?.optString("title")?.trim().orEmpty()
                if (id.isEmpty() || !id.matches(Regex("\\d{1,30}")) || title.isEmpty()) continue
                val stat = video.optJSONObject("stat")
                val expand = video.optJSONObject("expand")
                out.add(
                    Drama(
                        seriesId = id,
                        title = title,
                        cover = HongguoCoverUrl.rewrite(
                            common.optString("cover").takeIf { it.startsWith("http") } ?: ""
                        ),
                        intro = common.optString("intro"),
                        score = "",
                        playCount = stat?.optLong("play_cnt", 0L) ?: 0L,
                        episodeCount = expand?.optInt("episode_cnt", 0) ?: 0,
                        vid = "",
                        tags = emptyList(),
                        heatText = "",
                        statusTags = emptyList(),
                        seasonIndex = 0,
                        multiSeason = false,
                        contentType = "",
                        seriesStatus = ""
                    )
                )
            }
            return out
        }
    }

    /** 排障开关：JVM 单测置 true 打印响应详情。 */
    object HongguoClientDebug {
        var enabled = false
    }
}
