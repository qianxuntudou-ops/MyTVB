package com.mytvb.feature.hongguo.net

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 红果账号云同步 + 登录态保活（对照 HGTV 1.3.6 账号模块逐接口对齐）：
 * - 业务写接口（core/b.java h() 通道，73932 档案+明文 JSON body+登录头）：
 *   read_progress/upload（播放进度→跨设备续播）、bookshelf/video/update（收藏云同步）、
 *   read_history/update|delete（观看历史）、user/subscribe/list|delete（预约）；
 * - 保活（H0/g.java）：passport/token/beat/v2 心跳（30 分钟阈值）+ account/info/v2 boot 校验 +
 *   sid_guard 临期 3 天预警 + x-tt-token 滚动续期（响应头 X-Tt-Token / H0/b.java）。
 * 登录态请求头（b.java b1()）：Cookie 白名单串 + x-tt-token（有才带）。
 */
class HongguoSync(
    private val session: HongguoSession,
    private val identity: () -> HongguoDeviceRegister.Identity
) {

    class SyncException(message: String, val code: String = "SYNC_ERROR") : IllegalStateException(message)

    /** 预约条目（subscribe/list/v1 的 subscribe_items，对照 HGTV 1551 解析）。 */
    data class SubscribeItem(
        val seriesId: String, val title: String, val cover: String,
        val onlineTimeSec: Long, val isOnline: Boolean
    )

    private val http get() = HongguoHttp.ok

    // ---- 通用登录态 POST（core/b.java h()：73932 档案+明文 body+x-tt-token/Cookie） ----

    private fun accountPost(path: String, body: JSONObject): JSONObject {
        val id = identity()
        val q = LinkedHashMap<String, String>()
        if (id.klinkEgdi.isNotEmpty()) q["klink_egdi"] = id.klinkEgdi
        if (id.deviceToken.isNotEmpty()) q["device_token"] = id.deviceToken
        q["iid"] = id.installId
        q["device_id"] = id.deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "73932"; q["version_name"] = "7.3.9.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType
        q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"
        q["os_api"] = HongguoEnv.osApi; q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "73932"
        q["resolution"] = HongguoEnv.resolution; q["dpi"] = HongguoEnv.dpi
        q["update_version_code"] = "73932"; q["host_abi"] = HongguoEnv.HOST_ABI
        q["dragon_device_type"] = "phone"; q["pv_player"] = "73932"
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
        q["cdid"] = id.cdid
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
        val signed = HongguoCrypto.signRequest(
            q, bodyBytes, System.currentTimeMillis(), id.deviceId,
            targetBranch = 0, version = "7.3.9.32"
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val argus = HongguoArgus.generate(
            signed.query, bodyBytes, nowSec, "7.3.9.32", id.deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val cookieHeader = HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
            session.accountCookies[name]?.takeIf { it.isNotEmpty() }?.let { "$name=$it" }
        }.joinToString("; ")
        val traceHex = java.util.UUID.randomUUID().toString().replace("-", "")
        val req = Request.Builder()
            .url("https://api5-normal-sinfonlinea.fqnovel.com$path?${signed.query}")
            .post(bodyBytes.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .apply {
                for ((k, v) in listOf(
                    "User-Agent" to HongguoEnv.ua("73932"),
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "X-SS-DP" to "8662", "X-Tt-Store-Region" to "cn",
                    "X-Tt-Store-Region-Src" to "did", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
                    "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                    "x-ss-req-ticket" to ticket, "sdk-version" to "2",
                    "passport-sdk-version" to "5051452",
                    "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
                    "x-ladon" to argus.ladon, "x-argus" to argus.argus,
                    "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
                    "x-helios" to HongguoArgus.helios(nowSec),
                    "Content-Type" to "application/json; charset=UTF-8",
                    "x-vc-bdturing-sdk-version" to "4.0.3.cn",
                    "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
                    "lc" to "101", "x-ss-dp" to "8662",
                    "x-tt-trace-id" to "00-$traceHex-${traceHex.substring(0, 16)}-01"
                )) addHeader(k, v)
                if (cookieHeader.isNotEmpty()) addHeader("Cookie", cookieHeader)
                // 登录第二票据（b.java b1()：有才带）
                if (session.accountToken.isNotEmpty()) addHeader("x-tt-token", session.accountToken)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            rollToken(resp.headers)
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("HongguoSync", "$path http=${resp.code} len=${text.length}")
            if (text.isEmpty()) throw SyncException("账号接口空响应")
            val payload = JSONObject(text)
            if (payload.optInt("code", -1) != 0) {
                if (payload.optString("error_name") == "session_expired" ||
                    payload.optJSONObject("data")?.optString("error_name") == "session_expired"
                ) throw SyncException("登录态已过期", "SESSION_EXPIRED")
                throw SyncException(
                    payload.optString("message").ifEmpty { "code=${payload.optInt("code", -1)}" },
                    "API_${payload.optInt("code", -1)}"
                )
            }
            return payload
        }
    }

    /** x-tt-token 滚动续期（C0051b:68：X-Tt-Token 响应头 / Set-Cookie，>=99 字符直接收）。 */
    private fun rollToken(headers: Headers) {
        headers.values("X-Tt-Token").firstOrNull()?.takeIf { it.length >= 99 }?.let {
            session.accountToken = it
            return
        }
        headers.values("Set-Cookie").forEach { raw ->
            if (raw.startsWith("x-tt-token=", ignoreCase = true)) {
                raw.substringAfter('=').substringBefore(';').takeIf { it.length >= 99 }
                    ?.let { session.accountToken = it }
            }
        }
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
        val positionMs: Long, val durationMs: Long, val lastPlayedAtMs: Long
    )

    /**
     * 云端观看历史（对照 HGTV cloud.go）：
     * GET read_history/list/v，limit=0（官方同款，服务端每批最多 500 条）；
     * 按 series_id 去重保留最近播放，按时间倒序。
     */
    fun cloudHistory(maxItems: Int = 100): List<CloudHistoryItem> {
        val id = identity()
        val q = LinkedHashMap<String, String>()
        q["iid"] = id.installId
        q["device_id"] = id.deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "72232"; q["version_name"] = "7.2.2.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType; q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"; q["os_api"] = HongguoEnv.osApi; q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "72232"; q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi; q["update_version_code"] = "72232"
        q["host_abi"] = HongguoEnv.HOST_ABI; q["cdid"] = id.cdid
        q["limit"] = "0"; q["offset"] = "0"; q["book_type"] = "2"
        q["full_field"] = "false"; q["query_soft_deleted"] = "false"
        q["last_min_read_timestamp_ms"] = "0"; q["is_first_load"] = "true"
        val bodyBytes = ByteArray(0)
        val signed = HongguoCrypto.signRequest(
            q, null, System.currentTimeMillis(), id.deviceId,
            targetBranch = 0, version = "7.2.2.32"
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val argus = HongguoArgus.generate(
            signed.query, null, nowSec, "7.2.2.32", id.deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val cookieHeader = HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
            session.accountCookies[name]?.takeIf { it.isNotEmpty() }?.let { "$name=$it" }
        }.joinToString("; ")
        val req = Request.Builder()
            .url("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/read_history/list/v/?${signed.query}")
            .get()
            .apply {
                for ((k, v) in listOf(
                    "User-Agent" to HongguoEnv.ua("72232"),
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "X-SS-DP" to "8662", "X-Tt-Store-Region" to "cn",
                    "X-Tt-Store-Region-Src" to "did", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
                    "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                    "x-ss-req-ticket" to (signed.headers["X-SS-Req-Ticket"] ?: ""),
                    "sdk-version" to "2", "passport-sdk-version" to "5051452",
                    "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
                    "x-ladon" to argus.ladon, "x-argus" to argus.argus,
                    "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
                    "x-helios" to HongguoArgus.helios(nowSec),
                    "lc" to "101", "x-ss-dp" to "8662"
                )) addHeader(k, v)
                if (cookieHeader.isNotEmpty()) addHeader("Cookie", cookieHeader)
                if (session.accountToken.isNotEmpty()) addHeader("x-tt-token", session.accountToken)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            rollToken(resp.headers)
            val text = resp.body?.string().orEmpty()
            if (text.isEmpty()) throw SyncException("云端历史空响应")
            val payload = JSONObject(text)
            if (payload.optInt("code", -1) != 0) throw SyncException(payload.optString("message"))
            val data = payload.optJSONObject("data") ?: JSONArray()
            val rows = (data as? JSONObject)?.optJSONArray("data_list") ?: JSONArray()
            val byId = LinkedHashMap<String, CloudHistoryItem>()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                if (r.optBoolean("is_delete", false)) continue
                val sid = r.optString("book_id_str").ifEmpty { r.optLong("book_id", 0L).takeIf { it > 0 }?.toString().orEmpty() }
                if (sid.isEmpty()) continue
                val idx1 = r.optInt("vid_index", 0)
                val atMs = r.optLong("read_timestamp_ms", 0L)
                if (idx1 < 1 || atMs <= 0) continue
                val item = CloudHistoryItem(
                    seriesId = sid,
                    title = r.optString("book_name"),
                    cover = r.optString("thumb_url").takeIf { it.startsWith("http") } ?: "",
                    episodeIndex1 = idx1,
                    episodeCount = r.optInt("episode_cnt", 0),
                    positionMs = r.optLong("current_play_position", 0L).coerceAtLeast(0L),
                    durationMs = r.optLong("duration", 0L).coerceAtLeast(0L),
                    lastPlayedAtMs = atMs
                )
                val old = byId[item.seriesId]
                if (old == null || item.lastPlayedAtMs > old.lastPlayedAtMs) byId[item.seriesId] = item
            }
            val out = byId.values.sortedByDescending { it.lastPlayedAtMs }
            android.util.Log.d("HongguoSync", "cloud history rows=${rows.length()} merged=${out.size}")
            return out.take(maxItems)
        }
    }


    // ---- 4. 预约列表（b.java 1551：subscribe/list GET；tab_type=13） ----

    fun subscribeList(onlyOnline: Boolean): List<SubscribeItem> {
        val id = identity()
        val q = LinkedHashMap<String, String>()
        q["iid"] = id.installId
        q["device_id"] = id.deviceId
        q["ac"] = "wifi"; q["channel"] = HongguoEnv.CHANNEL; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "72232"; q["version_name"] = "7.2.2.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = HongguoEnv.deviceType; q["device_brand"] = HongguoEnv.deviceBrand
        q["language"] = "zh"; q["os_api"] = HongguoEnv.osApi; q["os_version"] = HongguoEnv.osVersion
        q["manifest_version_code"] = "72232"; q["resolution"] = HongguoEnv.resolution
        q["dpi"] = HongguoEnv.dpi; q["update_version_code"] = "72232"
        q["host_abi"] = HongguoEnv.HOST_ABI; q["cdid"] = id.cdid
        q["offset"] = "0"; q["limit"] = "20"
        q["is_online"] = if (onlyOnline) "true" else "false"
        q["session_id"] = ""; q["tab_type"] = "13"
        q["subscribe_order_type"] = "0"; q["swipe_type"] = "0"
        q["need_calendar_schema"] = "false"; q["subscribe_offset"] = "0"
        val bodyBytes = ByteArray(0)
        val signed = HongguoCrypto.signRequest(
            q, null, System.currentTimeMillis(), id.deviceId,
            targetBranch = 0, version = "7.2.2.32"
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        val argus = HongguoArgus.generate(
            signed.query, null, nowSec, "7.2.2.32", id.deviceId,
            deviceProfile = HongguoEnv.argusProfile
        )
        val cookieHeader = HongguoCookieStoreParser.COOKIE_ORDER.mapNotNull { name ->
            session.accountCookies[name]?.takeIf { it.isNotEmpty() }?.let { "$name=$it" }
        }.joinToString("; ")
        val req = Request.Builder()
            .url("https://api5-normal-sinfonlinea.fqnovel.com/reading/user/subscribe/list/v1/?${signed.query}")
            .get()
            .apply {
                for ((k, v) in listOf(
                    "User-Agent" to HongguoEnv.ua("72232"),
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "X-SS-DP" to "8662", "X-Tt-Store-Region" to "cn",
                    "X-Tt-Store-Region-Src" to "did", "X-XS-From-Web" to "0", "Sdk-Version" to "2",
                    "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                    "x-ss-req-ticket" to (signed.headers["X-SS-Req-Ticket"] ?: ""),
                    "sdk-version" to "2", "passport-sdk-version" to "5051452",
                    "x-khronos" to (signed.headers["X-Khronos"] ?: ""),
                    "x-ladon" to argus.ladon, "x-argus" to argus.argus,
                    "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
                    "x-helios" to HongguoArgus.helios(nowSec),
                    "lc" to "101", "x-ss-dp" to "8662"
                )) addHeader(k, v)
                if (cookieHeader.isNotEmpty()) addHeader("Cookie", cookieHeader)
                if (session.accountToken.isNotEmpty()) addHeader("x-tt-token", session.accountToken)
            }
            .build()
        http.newCall(req).execute().use { resp ->
            rollToken(resp.headers)
            val text = resp.body?.string().orEmpty()
            if (text.isEmpty()) throw SyncException("预约列表空响应")
            val payload = JSONObject(text)
            if (payload.optInt("code", -1) != 0) throw SyncException(payload.optString("message"))
            val out = ArrayList<SubscribeItem>()
            val items = payload.optJSONObject("data")
                ?.optJSONObject("subscribe_data")?.optJSONArray("subscribe_items")
                ?: payload.optJSONObject("data")?.optJSONArray("subscribe_items")
                ?: JSONArray()
            for (i in 0 until items.length()) {
                val cell = items.optJSONObject(i) ?: continue
                // 条目形态：video_data(剧卡) + sub_info(预约信息+schedule_publish_time)
                val video = cell.optJSONObject("video_data") ?: cell
                val subInfo = cell.optJSONObject("sub_info")
                val sid = video.optString("series_id_str").ifEmpty { video.optString("series_id") }
                if (sid.isEmpty()) continue
                out.add(
                    SubscribeItem(
                        seriesId = sid,
                        title = video.optString("title").ifEmpty { video.optString("series_title") },
                        cover = video.optString("cover").ifEmpty { video.optString("cover_url") }
                            .takeIf { it.startsWith("http") } ?: "",
                        onlineTimeSec = (subInfo?.optLong("schedule_publish_time", 0L)
                            ?: video.optLong("schedule_publish_time", 0L)),
                        isOnline = subInfo?.optInt("is_online", 0) == 1
                    )
                )
            }
            return out
        }
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
            rollToken(resp.headers)
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
            rollToken(resp.headers)
            val text = resp.body?.string().orEmpty()
            android.util.Log.d("HongguoSync", "boot verify http=${resp.code} len=${text.length}")
            if (text.contains("session_expired")) throw SyncException("登录态已过期", "SESSION_EXPIRED")
            if (resp.code in 200..299) session.lastBeatAt = System.currentTimeMillis()
        }
    }
}
