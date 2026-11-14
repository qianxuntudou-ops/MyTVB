package com.mytvb.feature.hongguo.net

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 红果账号云同步 + 登录态保活（对照 HGTV 1.3.6 账号模块逐接口对齐）：
 * - 业务读写接口（read_progress、bookshelf/video、read_history、user/subscribe）——
 *   2026-11-14 起传输层收编 HongguoClient 统一签名通道（74532+Cookie/x-tt-token，
 *   原三套手写 73932/72232 样板删除），本类只保留 body 构造与解析；
 * - 保活（H0/g.java）：passport/token/beat/v2 心跳（30 分钟阈值）+ account/info/v2 boot 校验 +
 *   sid_guard 临期 3 天预警 + x-tt-token 滚动续期（HongguoSession.rollTokenFrom）。
 */
class HongguoSync(
    private val session: HongguoSession,
    private val client: HongguoClient
) {

    class SyncException(message: String, val code: String = "SYNC_ERROR") : IllegalStateException(message)

    /** 预约条目（subscribe/list/v1 的 subscribe_items，对照 HGTV 1551 解析）。 */
    data class SubscribeItem(
        val seriesId: String, val title: String, val cover: String,
        val onlineTimeSec: Long, val isOnline: Boolean,
        /** 总集数（封面右下「共 N 集」，video_data 多名兜底解析）。 */
        val episodeCount: Int = 0,
        /** 分类标签（category_schema[].name，HGTV 同族响应实证字段）。 */
        val tags: List<String> = emptyList(),
        /** 内容形态（content_type 防御解析，同 [CloudHistoryItem] 口径）。 */
        val contentType: String = "",
        /** 状态标（tag_info.text 如「预告」，官方金 #D7A461；2026-11-14 实机日志实证）。 */
        val statusTag: String = "",
        /** 官方上线文案（sub_title_list 首条 content 如「11月15日上线」，优于本地拼时间）。 */
        val onlineText: String = ""
    )

    private val http get() = HongguoHttp.ok

    // ---- 通用登录态通道（client.authPost/authGet：74532+登录头+token 滚动） ----

    /** 通道异常翻译：session_expired → SESSION_EXPIRED（调用方引导重登），其余归 SyncException。 */
    private fun accountPost(path: String, body: JSONObject): JSONObject =
        try {
            client.authPost(path, body)
        } catch (e: HongguoClient.SessionExpiredException) {
            throw SyncException("登录态已过期", "SESSION_EXPIRED")
        } catch (e: HongguoClient.EmptyResponseException) {
            throw SyncException("账号接口空响应")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(e.message ?: "账号接口失败")
        }

    private fun accountGet(path: String, values: Map<String, String>): JSONObject =
        try {
            client.authGet(path, values)
        } catch (e: HongguoClient.SessionExpiredException) {
            throw SyncException("登录态已过期", "SESSION_EXPIRED")
        } catch (e: HongguoClient.EmptyResponseException) {
            throw SyncException("账号接口空响应")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(e.message ?: "账号接口失败")
        }

    // ---- 1. 播放进度上传（b.java m()：跨设备断点续播） ----

    fun uploadProgress(
        seriesId: String, itemId: String, episodeIndex1: Int,
        playTimeMs: Long, totalMs: Long, episodeCount: Int
    ): Boolean {
        val rate = if (totalMs > 0) playTimeMs.toDouble() / totalMs else 0.0
        val book = JSONObject()
            .put("book_id", seriesId)
            .put("item_id", itemId)
            .put("vid_index", episodeIndex1)
            .put("current_play_time", playTimeMs.coerceAtLeast(0L))
            .put("current_total_time", totalMs.coerceAtLeast(0L))
            .put("episode_count", episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms", System.currentTimeMillis())
            .put("book_type", 2)
            .put("progress_rate", String.format(Locale.US, "%.4f", rate.coerceIn(0.0, 1.0)))
            .put("player_cumulative_total_duration", playTimeMs.coerceAtLeast(0L))
        val body = JSONObject()
            .put("books", JSONArray().put(book))
            .put("write_mode", 1)
        return accountPost("/reading/bookapi/read_progress/upload/v", body).optInt("code") == 0
    }

    // ---- 2. 收藏云同步（b.java V0：video_shelf_operate_type 0=加入 1=移除） ----

    fun syncFavorite(seriesId: String, cancel: Boolean): Boolean {
        val operate = if (cancel) 1 else 0
        val item = JSONObject()
            .put("book_id", seriesId)
            .put("book_type", 2)
            .put("modify_time", System.currentTimeMillis())
            .put("video_shelf_operate_type", operate)
        if (operate == 1) item.put("group_name", "")
        item.put("is_cancelled", false)
        if (operate == 0) {
            item.put(
                "shark_extra", JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("previous_page", "")
                    .put("page_list", "MainFragmentActivity")
                    .put("is_active_behavior", "true")
                    .put("inactive_type", "0")
            )
        }
        val body = JSONObject().put("update_bookshelf_video_list", JSONArray().put(item))
        return accountPost("/reading/bookapi/bookshelf/video/update/v", body).optInt("code") == 0
    }

    // ---- 3. 观看历史（b.java 5470：update_datas 批量条目） ----

    private fun historyItem(seriesId: String, episodeIndex1: Int, playMs: Long, totalMs: Long,
                            epCount: Int, delete: Boolean): JSONObject {
        val o = JSONObject()
            .put("book_id", seriesId.toLongOrNull() ?: 0L)
            .put("book_type", 2)
            .put("chapter_index", 0)
            .put("current_play_position", if (delete) 0L else playMs)
            .put("digged_count", 0)
            .put("duration", if (delete) 0L else totalMs)
            .put("episode_cnt", if (delete) 0 else epCount)
            .put("is_delete", delete)
            .put("is_interactive_game", false)
            .put("is_listen_mode", false)
            .put("is_multi_season", 0)
            .put("player_accumulate_total_time", if (delete) 0L else playMs)
            .put("read_timestamp_ms", if (delete) 0L else System.currentTimeMillis())
            .put("recent_reads", 0)
            .put("retain_video_play_time", 0)
            .put("season_index", 0)
            .put("series_play_cnt", 0)
            .put("tone_id", 0)
            .put("update_timestamp_ms", System.currentTimeMillis())
            .put("user_digg", false)
            .put("vid", 0)
            .put("vid_index", episodeIndex1)
        return o
    }

    fun uploadHistory(seriesId: String, episodeIndex1: Int, playMs: Long, totalMs: Long, epCount: Int): Boolean {
        val body = JSONObject().put(
            "update_datas", JSONArray().put(historyItem(seriesId, episodeIndex1, playMs, totalMs, epCount, false))
        )
        return accountPost("/reading/bookapi/read_history/update/v", body).optInt("code") == 0
    }

    fun deleteHistory(seriesIds: List<String>): Boolean {
        val arr = JSONArray()
        seriesIds.forEach { arr.put(historyItem(it, 1, 0, 0, 0, true)) }
        val body = JSONObject().put("update_datas", arr)
        return accountPost("/reading/bookapi/read_history/update/v", body).optInt("code") == 0
    }

    // ---- 3.5 云端历史读取（HGTV cloud.go：read_history/list GET） ----

    /** 云端历史条目（read_history/list/v 的 data_list，同剧多条取最近播放）。 */
    data class CloudHistoryItem(
        val seriesId: String, val title: String, val cover: String,
        val episodeIndex1: Int, val episodeCount: Int,
        val positionMs: Long, val durationMs: Long, val lastPlayedAtMs: Long,
        /** 内容形态（content_type：1004=漫剧，1/7/空=真人短剧；AI 剧值待日志实证）。 */
        val contentType: String = ""
    )

    /**
     * 云端观看历史（对照 HGTV cloud.go）：
     * GET read_history/list/v，limit=0（官方同款，服务端每批最多 500 条）；
     * 按 series_id 去重保留最近播放，按时间倒序。
     * 截断上限对齐服务端批大小 500（原 100：重度用户历史滑到底"莫名没了"，
     * 历史 tab 无分页加载，只能靠一次拉满）。
     */
    fun cloudHistory(maxItems: Int = 500): List<CloudHistoryItem> {
        val payload = accountGet(
            "/reading/bookapi/read_history/list/v/",
            linkedMapOf(
                "limit" to "0", "offset" to "0", "book_type" to "2",
                "full_field" to "false", "query_soft_deleted" to "false",
                "last_min_read_timestamp_ms" to "0", "is_first_load" to "true"
            )
        )
        val rows = payload.optJSONObject("data")?.optJSONArray("data_list") ?: JSONArray()
        val byId = LinkedHashMap<String, CloudHistoryItem>()
        for (i in 0 until rows.length()) {
            val item = parseCloudRow(rows.optJSONObject(i) ?: continue) ?: continue
            val old = byId[item.seriesId]
            if (old == null || item.lastPlayedAtMs > old.lastPlayedAtMs) byId[item.seriesId] = item
        }
        val out = byId.values.sortedByDescending { it.lastPlayedAtMs }
        android.util.Log.d(
            "HongguoSync",
            "cloud history rows=${rows.length()} merged=${out.size} " +
                "ct=${rows.optJSONObject(0)?.optString("content_type")} " +
                "keys=${rows.optJSONObject(0)?.keys()?.asSequence()?.take(24)?.toList()}"
        )
        return out.take(maxItems)
    }

    /**
     * 云端单剧进度（read_progress/get/v，官方进播放页必拉——2026-11-13 抓包 13 次）：
     * 无记录/未登录返回 null。行字段与 read_history/list 同源。
     */
    fun cloudProgress(seriesId: String): CloudHistoryItem? {
        if (!session.loggedIn) return null
        val sid = seriesId.toLongOrNull() ?: return null
        val body = JSONObject().put(
            "book_ids", JSONObject()
                .put("1", JSONArray())
                .put("0", JSONArray().put(sid))
        )
        val payload = accountPost("/reading/bookapi/read_progress/get/v", body)
        val rows = payload.optJSONArray("video_data")?.takeIf { it.length() > 0 }
            ?: payload.optJSONArray("data")?.takeIf { it.length() > 0 }
            ?: return null
        var best: CloudHistoryItem? = null
        for (i in 0 until rows.length()) {
            val item = parseCloudRow(rows.optJSONObject(i) ?: continue) ?: continue
            if (best == null || item.lastPlayedAtMs > best.lastPlayedAtMs) best = item
        }
        android.util.Log.d("HongguoSync", "cloud progress rows=${rows.length()} hit=${best != null}")
        return best
    }

    /** 云端进度行解析（read_history/read_progress 共用字段；缺集数或时间戳的行丢弃）。 */
    private fun parseCloudRow(r: JSONObject): CloudHistoryItem? {
        if (r.optBoolean("is_delete", false)) return null
        val sid = r.optString("book_id_str").ifEmpty { r.optLong("book_id", 0L).takeIf { it > 0 }?.toString().orEmpty() }
        if (sid.isEmpty()) return null
        val idx1 = r.optInt("vid_index", 0)
        val atMs = r.optLong("read_timestamp_ms", 0L)
        if (idx1 < 1 || atMs <= 0) return null
        return CloudHistoryItem(
            seriesId = sid,
            title = r.optString("book_name"),
            cover = r.optString("thumb_url").takeIf { it.startsWith("http") } ?: "",
            episodeIndex1 = idx1,
            episodeCount = r.optInt("episode_cnt", 0),
            positionMs = r.optLong("current_play_position", 0L).coerceAtLeast(0L),
            durationMs = r.optLong("duration", 0L).coerceAtLeast(0L),
            lastPlayedAtMs = atMs,
            // 同族响应的 content_type 防御两态解析（1004=漫剧；AI 剧值未知日志实证）
            contentType = r.optString("content_type").ifEmpty { r.optString("contentType") }
        )
    }

    // ---- 4. 预约列表（b.java 1551：subscribe/list GET；tab_type=13） ----

    fun subscribeList(onlyOnline: Boolean): List<SubscribeItem> {
        val payload = accountGet(
            "/reading/user/subscribe/list/v1/",
            linkedMapOf(
                "offset" to "0", "limit" to "20",
                "is_online" to if (onlyOnline) "true" else "false",
                "session_id" to "", "tab_type" to "13",
                "subscribe_order_type" to "0", "swipe_type" to "0",
                "need_calendar_schema" to "false", "subscribe_offset" to "0"
            )
        )
        val out = ArrayList<SubscribeItem>()
        val items = payload.optJSONObject("data")
            ?.optJSONObject("subscribe_data")?.optJSONArray("subscribe_items")
            ?: payload.optJSONObject("data")?.optJSONArray("subscribe_items")
            ?: JSONArray()
        for (i in 0 until items.length()) {
            val cell = items.optJSONObject(i) ?: continue
            // 条目形态（HGTV b.java 1553 三级回落）：subscribe_data ?? video_data ?? 条目本身；
            // schedule_publish_time/is_online 在条目层，sub_info 有则优先；id 数字/字符串两态
            val video = cell.optJSONObject("subscribe_data")
                ?: cell.optJSONObject("video_data")
                ?: cell
            val subInfo = cell.optJSONObject("sub_info")
            val sid = idOf(video, cell)
            if (sid.isEmpty()) continue
            // 集数/分类多名防御解析（HGTV b.java K()/I() 同族字段：episode_cnt/
            // episode_count/serial_count/drama_chapter_number、category_schema[].name）；
            // 命中不了就空值，首条打原始 key 供热度/评分回填的后续实证
            val episodeCount = listOf(video, cell).firstNotNullOfOrNull { o ->
                listOf("episode_cnt", "episode_count", "serial_count", "drama_chapter_number")
                    .firstNotNullOfOrNull { o.optInt(it, 0).takeIf { v -> v > 0 } }
            } ?: 0
            val tags = listOf(video, cell).firstNotNullOfOrNull { o ->
                o.optJSONArray("category_schema")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }
                    }.takeIf { it.isNotEmpty() }
                }
            } ?: emptyList()
            if (i == 0) {
                android.util.Log.d(
                    "HongguoSync",
                    "subscribe item keys=${video.keys().asSequence().toList()} " +
                        "cellKeys=${cell.keys().asSequence().toList()} ep=$episodeCount tags=${tags.size} " +
                        "category=${cell.optString("category")} categories=${cell.optJSONArray("categories")} " +
                        "item_type=${cell.opt("item_type")} sub_title_list=${cell.optJSONArray("sub_title_list")} " +
                        "tag_info=${cell.optJSONObject("tag_info")}"
                )
            }
            val contentType = video.optString("content_type").ifEmpty {
                cell.optString("content_type").ifEmpty { video.optString("contentType") }
            }
            // tag_info 状态标 + sub_title_list 官方上线文案（2026-11-14 实机日志实证字段）
            val statusTag = cell.optJSONObject("tag_info")?.optString("text")
                ?.takeIf { it.isNotEmpty() } ?: ""
            val onlineText = cell.optJSONArray("sub_title_list")?.optJSONObject(0)
                ?.optString("content")?.takeIf { it.isNotEmpty() } ?: ""
            out.add(
                SubscribeItem(
                    seriesId = sid,
                    title = titleOf(video, cell),
                    cover = video.optString("cover").ifEmpty { video.optString("cover_url") }
                        .takeIf { it.startsWith("http") } ?: "",
                    onlineTimeSec = subInfo?.optLong("schedule_publish_time", 0L)
                        ?.takeIf { it > 0 }
                        ?: cell.optLong("schedule_publish_time", 0L),
                    isOnline = subInfo?.optInt("is_online", -1)?.let { it == 1 }
                        ?: cell.optBoolean("is_online", false),
                    episodeCount = episodeCount,
                    tags = tags,
                    contentType = contentType,
                    statusTag = statusTag,
                    onlineText = onlineText
                )
            )
        }
        android.util.Log.d(
            "HongguoSync", "subscribe rows=${items.length()} parsed=${out.size} " +
                "online=$onlyOnline"
        )
        if (out.isEmpty() && items.length() > 0) {
            android.util.Log.w(
                "HongguoSync",
                "subscribe parse miss: ${payload.optJSONObject("data").toString().take(600)}"
            )
        }
        return out
    }

    /** 条目 id（两层×多字段兜底：*_str/item_id 字符串态 / series_id·book_id 数字态）。 */
    private fun idOf(vararg objs: JSONObject): String {
        for (o in objs) {
            o.optString("series_id_str").takeIf { it.isNotEmpty() }?.let { return it }
            o.optString("series_id").takeIf { it.isNotEmpty() }?.let { return it }
            o.optString("book_id_str").takeIf { it.isNotEmpty() }?.let { return it }
            // 预约条目（即将上线的未开播剧）是扁平 item_id/name/cover 形态（2026-11-14
            // MuMu 实机日志实证，无 video_data 剧卡）
            o.optString("item_id").takeIf { it.isNotEmpty() }?.let { return it }
            o.optLong("series_id", 0L).takeIf { it > 0 }?.let { return it.toString() }
            o.optLong("book_id", 0L).takeIf { it > 0 }?.let { return it.toString() }
        }
        return ""
    }

    /** 条目标题（title/series_title/name 三态，name=扁平预约/收藏条目）。 */
    private fun titleOf(vararg objs: JSONObject): String {
        for (o in objs) {
            o.optString("title").takeIf { it.isNotEmpty() }?.let { return it }
            o.optString("series_title").takeIf { it.isNotEmpty() }?.let { return it }
            o.optString("name").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    // ---- 4.5 云端收藏书架（bookshelf/video/list GET，2026-11-14 补：此前只有 update 上传） ----

    /** 云端收藏条目（video_shelf_info[]：video_data 剧卡 + 条目层 collect_time，秒）。 */
    data class CloudFavoriteItem(
        val seriesId: String, val title: String, val cover: String,
        val episodeCount: Int, val collectedAtMs: Long,
        /** 内容形态（条目层 content_type 防御解析，同 [CloudHistoryItem] 口径）。 */
        val contentType: String = ""
    )

    fun cloudFavorites(): List<CloudFavoriteItem> {
        // HGTV F0/K 同款 info/v。条目形态（2026-11-14 MuMu 实机日志实证）：剧卡字段在
        // **条目层**（series_title/series_cover_url/series_intro/category_schema），video_data
        // 里只是单 vid 信息（episode_cnt 有用）；collect_time 原生毫秒
        val payload = accountGet("/reading/bookapi/bookshelf/video/info/v", linkedMapOf())
        val rows = payload.optJSONObject("data")?.optJSONArray("video_shelf_info")
            ?: JSONArray()
        val out = ArrayList<CloudFavoriteItem>(rows.length())
        for (i in 0 until rows.length()) {
            val cell = rows.optJSONObject(i) ?: continue
            val sid = idOf(cell, cell.optJSONObject("video_data"))
            if (sid.isEmpty()) continue
            val eps = cell.optJSONObject("video_data")?.optInt("episode_cnt", 0) ?: 0
            val collectRaw = cell.optLong("collect_time", 0L)
            out.add(
                CloudFavoriteItem(
                    seriesId = sid,
                    title = titleOf(cell),
                    cover = cell.optString("series_cover_url")
                        .ifEmpty { cell.optString("cover").ifEmpty { cell.optString("cover_url") } }
                        .takeIf { it.startsWith("http") } ?: "",
                    episodeCount = eps,
                    // 防御两态：>1e12 已是毫秒，否则当秒转
                    collectedAtMs = if (collectRaw > 1_000_000_000_000L) collectRaw else collectRaw * 1000L,
                    contentType = cell.optString("content_type").ifEmpty { cell.optString("contentType") }
                )
            )
        }
        android.util.Log.d(
            "HongguoSync",
            "cloud favorites rows=${rows.length()} parsed=${out.size} " +
                "ct=${rows.optJSONObject(0)?.optString("content_type")} " +
                "keys=${rows.optJSONObject(0)?.keys()?.asSequence()?.take(24)?.toList()}"
        )
        if (out.size != rows.length() || out.any { it.title.isEmpty() || it.cover.isEmpty() }) {
            android.util.Log.w(
                "HongguoSync",
                "favorites parse miss: ${payload.optJSONObject("data").toString().take(1200)}"
            )
        }
        return out
    }

    // ---- 5. 取消预约（b.java 6399：subscribe/delete，tab_type=13 批量形态） ----

    fun cancelSubscribe(seriesId: String): Boolean {
        val body = JSONObject()
            .put("item_id", JSONArray().put(seriesId))
            .put("all_select", false)
            .put("not_del_item_id", JSONArray())
            .put("is_online", false)
            .put("tab_type", 13)
        return accountPost("/reading/user/subscribe/delete/v1/", body).optInt("code") == 0
    }

    // ---- 6. beat 心跳 + boot 校验 + 过期管理（H0/g.java） ----

    /**
     * 登录态心跳：距上次 beat 超 30 分钟（j() 的 1800000ms）或 sid_guard 临期 3 天时发
     * `GET security.snssdk.com/passport/token/beat/v2/?scene=<scene>&first_beat=<b>`。
     * 200 即记 lastBeatAt；session_expired 抛 [SyncException]（code=SESSION_EXPIRED）由调用方降级。
     */
    fun beatIfNeeded(scene: String = "boot") {
        if (!session.loggedIn) return
        val now = System.currentTimeMillis()
        val stale = now - session.lastBeatAt > 30 * 60_000L
        val expiring = session.accountExpires > 0 &&
            session.accountExpires * 1000 - now in 1..(3 * 24 * 3600_000L)
        if (!stale && !expiring) return
        val firstBeat = session.lastBeatAt <= 0L
        val url = "https://security.snssdk.com/passport/token/beat/v2/?scene=$scene&first_beat=$firstBeat"
        val cookieHeader = HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
            session.accountCookies[name]?.takeIf { it.isNotEmpty() }?.let { "$name=$it" }
        }.joinToString("; ")
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
            .header("Accept", "application/json")
            .apply {
                if (cookieHeader.isNotEmpty()) addHeader("Cookie", cookieHeader)
                if (session.accountToken.isNotEmpty()) addHeader("x-tt-token", session.accountToken)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            session.rollTokenFrom(resp.headers)
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("HongguoSync", "beat scene=$scene http=${resp.code} len=${text.length}")
            if (resp.code in 200..299) {
                session.lastBeatAt = now
                return
            }
            // session_expired 检测（H0/g.java o.w(error_name, "session_expired")）
            if (text.contains("session_expired")) throw SyncException("登录态已过期", "SESSION_EXPIRED")
            // 网络/服务端抖动不惩罚：仅 HTTP 成功才刷新 lastBeatAt，失败留待下次
        }
    }

    /** 启动校验（account/info/v2?scene=boot）：验证登录态有效性并滚动 token。 */
    fun bootVerify() {
        if (!session.loggedIn) return
        val cookieHeader = HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
            session.accountCookies[name]?.takeIf { it.isNotEmpty() }?.let { "$name=$it" }
        }.joinToString("; ")
        val req = Request.Builder()
            .url("https://security.snssdk.com/passport/account/info/v2/?scene=boot")
            .get()
            .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
            .header("Accept", "application/json")
            .apply {
                if (cookieHeader.isNotEmpty()) addHeader("Cookie", cookieHeader)
                if (session.accountToken.isNotEmpty()) addHeader("x-tt-token", session.accountToken)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            session.rollTokenFrom(resp.headers)
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("HongguoSync", "boot verify http=${resp.code} len=${text.length}")
            if (text.contains("session_expired")) throw SyncException("登录态已过期", "SESSION_EXPIRED")
            if (resp.code in 200..299) session.lastBeatAt = System.currentTimeMillis()
        }
    }
}
