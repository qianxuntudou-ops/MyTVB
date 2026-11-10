package com.mytvb.feature.hongguo

import android.content.Context
import com.mytvb.feature.hongguo.net.HongguoAccount
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoDeviceRegister
import com.mytvb.feature.hongguo.net.HongguoPlay
import com.mytvb.feature.hongguo.net.HongguoSearch
import com.mytvb.feature.hongguo.net.HongguoSession
import com.mytvb.feature.hongguo.net.HongguoSync
import com.mytvb.feature.hongguo.net.HongguoWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果数据门面：统一身份管理（真注册持久化）+ feed/详情/播放/搜索/榜单 + 账号 + 本地收藏/进度。
 * 全部 IO 走 Dispatchers.IO；红果会话独立于 B 站。
 */
class HongguoRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val session = HongguoSession(appContext)
    private val client = HongguoClient()
    private val web = HongguoWeb()
    // 设备自愈钩子（HGTV "device healed" 同款）：空响应=号被拒 → 换新号并持久化
    private val play = HongguoPlay(rotateIdentity = { registerAndSave() })
    private val search = HongguoSearch()
    val account = HongguoAccount(session)

    // ---- 身份（真注册，multi_video_model 需要） ----
    @Volatile
    private var cachedIdentity: HongguoDeviceRegister.Identity? = null

    /** 身份注册互斥（多接口并发首启防竞态烧号）。 */
    private val identityLock = Any()

    private fun identity(): HongguoDeviceRegister.Identity {
        cachedIdentity?.let { return it }
        session.takeIf { it.registered && it.cdid.isNotEmpty() }?.let { s ->
            HongguoDeviceRegister.Identity(
                s.deviceId, s.installId, s.cdid, s.openUdid, s.cookies,
                s.klinkEgdi, s.deviceToken, s.clientUdid
            ).also { cachedIdentity = it; return it }
        }
        return synchronized(identityLock) {
            // 双检：等锁期间其它线程可能已注册
            cachedIdentity ?: registerAndSave()
        }
    }

    /**
     * 注册新号并持久化到 session（HGTV O0/E.java registered-* 持久化复用模型）。
     * 有旧号时走身份继承重注册（registerFresh(carry)：声明旧三件套+设备指纹沿用，
     * 对照 HGTV b.java r0「device healed」的换号继承语义），首装/null 全新随机。
     */
    private fun registerAndSave(): HongguoDeviceRegister.Identity {
        val carry = cachedIdentity ?: session.takeIf { it.registered && it.cdid.isNotEmpty() }?.let { s ->
            HongguoDeviceRegister.Identity(
                s.deviceId, s.installId, s.cdid, s.openUdid, s.cookies,
                s.klinkEgdi, s.deviceToken, s.clientUdid
            )
        }
        val fresh = HongguoDeviceRegister.registerFresh(carry)
        saveIdentity(fresh)
        return fresh
    }

    private fun saveIdentity(id: HongguoDeviceRegister.Identity) {
        session.deviceId = id.deviceId
        session.installId = id.installId
        session.cdid = id.cdid
        session.openUdid = id.openUdid
        session.clientUdid = id.clientUdid
        session.cookies = id.cookies.toMutableMap()
        session.klinkEgdi = id.klinkEgdi
        session.deviceToken = id.deviceToken
        cachedIdentity = id
    }

    /** 空响应=身份被拒时轮换新注册身份重试一次（对照 Node getPlaybackDevice.reset）。 */
    private fun withIdentity(block: (HongguoDeviceRegister.Identity) -> Unit): Boolean {
        return try {
            block(identity()); true
        } catch (e: Exception) {
            android.util.Log.w("Hongguo", "withIdentity failed: ${e.javaClass.simpleName}: ${e.message}")
            cachedIdentity = null
            session.clearAll()
            false
        }
    }

    // ---- feed ----
    suspend fun feed(
        path: String, offset: Int, sessionId: String,
        filters: Map<String, String> = emptyMap(), withPanel: Boolean = false
    ): HongguoClient.CategoryPage =
        withContext(Dispatchers.IO) { client.category(path, offset, sessionId, filters, withPanel) }

    // ---- 榜单（四板；fqnovel 被风控时网页 SSR 兜底） ----
    suspend fun rank(boardIndex: Int, offset: Int, sessionId: String): HongguoClient.RankPage =
        withContext(Dispatchers.IO) {
            try {
                client.rank(boardIndex, offset, sessionId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // App 通道空响应（模拟器传输层风控）→ 网页榜单兜底（offset 即页码）
                web.rankFromWeb(boardIndex, if (offset <= 0) 1 else offset)
            }
        }

    // ---- 搜索 ----
    /** 空响应轮换新注册身份重试。 */
    suspend fun search(
        keyword: String, offset: Int,
        cursor: HongguoSearch.SearchPage.Cursor?
    ): HongguoSearch.SearchPage = withContext(Dispatchers.IO) {
        var current = identity()
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return@withContext search.searchTab(keyword, offset, cursor, current)
            } catch (e: HongguoSearch.EmptyResponseException) {
                lastError = e
                if (attempt == 2) break
                current = registerAndSave()
                Thread.sleep(120)
            }
        }
        throw lastError ?: IllegalStateException("搜索接口无响应")
    }

    suspend fun suggestions(keyword: String): List<String> =
        withContext(Dispatchers.IO) { search.suggestions(keyword) }

    fun accountMethod(): String = session.loginMethod

    // ---- 详情 ----
    suspend fun detail(seriesId: String): HongguoWeb.SeriesDetail =
        withContext(Dispatchers.IO) { web.seriesDetail(seriesId) }

    // ---- 播放 ----
    /**
     * @param transport 传输通道：真机/模拟器传 WebView 通道（Chromium TLS 指纹，
     * OkHttp 会被播放接口空响应拒）；JVM 单测传 null 走 OkHttp（预期空响应，验证签名用）。
     */
    suspend fun playUrl(
        vid: String,
        transport: ((url: String, headers: Map<String, String>, body: ByteArray) -> String)? = null
    ): HongguoPlay.PlayUrl = withContext(Dispatchers.IO) {
        var result: HongguoPlay.PlayUrl? = null
        for (attempt in 0 until 2) {
            if (withIdentity { id -> result = play.playUrl(vid, id, transport = transport) }) break
        }
        result ?: throw IllegalStateException("红果播放地址解析失败")
    }

    // ---- 本地收藏 / 观看进度（独立 SP，与 B 站零共享） ----
    data class WatchRecord(
        val seriesId: String, val title: String, val cover: String,
        val episodeIndex: Int, val episodeCount: Int,
        val positionMs: Long, val durationMs: Long, val updatedAt: Long
    )

    private val localPrefs by lazy { appContext.getSharedPreferences("hongguo_local", Context.MODE_PRIVATE) }

    // ---- 账号云同步（登录态才发；本地为准、云端尽力，失败静默不影响主流程） ----

    val sync: HongguoSync by lazy { HongguoSync(session) { identity() } }

    private val cloudScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private fun cloudAsync(block: suspend HongguoSync.() -> Unit) {
        if (!session.loggedIn) return
        cloudScope.launch {
            runCatching { sync.block() }
                .onFailure {
                    if (it is HongguoSync.SyncException && it.code == "SESSION_EXPIRED") {
                        android.util.Log.w("HongguoSync", "登录态过期，云同步停用（下次登录恢复）")
                    } else {
                        android.util.Log.d("HongguoSync", "cloud sync fail: ${it.message}")
                    }
                }
        }
    }

    /** 预约列表（数据层就位，UI 入口后续接）。 */
    suspend fun subscribeList(onlyOnline: Boolean): List<HongguoSync.SubscribeItem> =
        withContext(Dispatchers.IO) { sync.subscribeList(onlyOnline) }

    /** 取消预约（我的页点选）。 */
    suspend fun cancelSubscribe(seriesId: String): Boolean =
        withContext(Dispatchers.IO) { sync.cancelSubscribe(seriesId) }

    /** 云端历史删除（本地历史清空时同步）。 */
    fun deleteHistoryCloud(seriesIds: List<String>) =
        cloudAsync { deleteHistory(seriesIds) }

    /** 登录态保活（播放启动等低频场景调用；30 分钟阈值内部判定）。 */
    fun beatIfNeeded() {
        if (!session.loggedIn) return
        cloudScope.launch {
            runCatching { sync.beatIfNeeded(scene = "play") }
                .onFailure { android.util.Log.d("HongguoSync", "beat fail: ${it.message}") }
        }
    }

    fun favorites(): List<WatchRecord> = readRecords("favorites")

    fun isFavorite(seriesId: String): Boolean =
        localPrefs.getString("favorites", null)?.contains("\"$seriesId\"") == true

    fun toggleFavorite(record: WatchRecord): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.seriesId == record.seriesId }
        val added = if (existing >= 0) {
            list.removeAt(existing); writeRecords("favorites", list); false
        } else {
            list.add(0, record); writeRecords("favorites", list); true
        }
        // 云同步（bookshelf/video/update；登录态才发，失败不回滚本地——本地为准，云端尽力）
        cloudAsync { syncFavorite(record.seriesId, cancel = !added) }
        return added
    }

    fun history(): List<WatchRecord> = readRecords("history")

    fun saveProgress(record: WatchRecord, vid: String = record.seriesId) {
        val list = history().filter { it.seriesId != record.seriesId }.toMutableList()
        list.add(0, record)
        writeRecords("history", list.take(200))
        // 云同步（进度+历史双通道：read_progress/upload 支持跨设备续播；节流 20s 防连发）
        val now = System.currentTimeMillis()
        val key = "last_cloud_push_${record.seriesId}"
        if (now - localPrefs.getLong(key, 0L) > 20_000L) {
            localPrefs.edit().putLong(key, now).apply()
            cloudAsync {
                uploadProgress(
                    record.seriesId, vid, (record.episodeIndex + 1).coerceAtLeast(1),
                    record.positionMs, record.durationMs, record.episodeCount
                )
                uploadHistory(
                    record.seriesId, (record.episodeIndex + 1).coerceAtLeast(1),
                    record.positionMs, record.durationMs, record.episodeCount
                )
                Unit
            }
        }
    }

    fun progressOf(seriesId: String): WatchRecord? =
        history().firstOrNull { it.seriesId == seriesId }

    private fun readRecords(key: String): List<WatchRecord> {
        val raw = localPrefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                WatchRecord(
                    o.optString("sid"), o.optString("t"), o.optString("c"),
                    o.optInt("e", 0), o.optInt("n", 0),
                    o.optLong("p", 0L), o.optLong("d", 0L), o.optLong("u", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeRecords(key: String, list: List<WatchRecord>) {
        val arr = org.json.JSONArray()
        for (r in list) {
            arr.put(
                org.json.JSONObject()
                    .put("sid", r.seriesId).put("t", r.title).put("c", r.cover)
                    .put("e", r.episodeIndex).put("n", r.episodeCount)
                    .put("p", r.positionMs).put("d", r.durationMs).put("u", r.updatedAt)
            )
        }
        localPrefs.edit().putString(key, arr.toString()).apply()
    }

    fun clearLocal() {
        localPrefs.edit().clear().apply()
    }

    companion object {
        @Volatile
        private var instance: HongguoRepository? = null

        fun get(context: Context): HongguoRepository =
            instance ?: synchronized(this) {
                instance ?: HongguoRepository(context.applicationContext).also { instance = it }
            }
    }
}
