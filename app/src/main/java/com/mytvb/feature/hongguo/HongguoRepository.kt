package com.mytvb.feature.hongguo

import android.content.Context
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoDeviceRegister
import com.mytvb.feature.hongguo.net.HongguoPlay
import com.mytvb.feature.hongguo.net.HongguoSession
import com.mytvb.feature.hongguo.net.HongguoWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 红果数据门面：统一身份管理（真注册持久化）+ feed/详情/播放 + 本地收藏/进度。
 * 全部 IO 走 Dispatchers.IO；红果会话独立于 B 站。
 */
class HongguoRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val session = HongguoSession(appContext)
    private val client = HongguoClient()
    private val web = HongguoWeb()
    private val play = HongguoPlay()

    // ---- 身份（真注册，multi_video_model 需要） ----
    @Volatile
    private var cachedIdentity: HongguoDeviceRegister.Identity? = null

    private fun identity(): HongguoDeviceRegister.Identity {
        cachedIdentity?.let { return it }
        session.takeIf { it.registered && it.cdid.isNotEmpty() }?.let { s ->
            HongguoDeviceRegister.Identity(s.deviceId, s.installId, s.cdid, s.openUdid, s.cookies)
                .also { cachedIdentity = it; return it }
        }
        val fresh = HongguoDeviceRegister.registerFresh()
        session.deviceId = fresh.deviceId
        session.installId = fresh.installId
        session.cdid = fresh.cdid
        session.openUdid = fresh.openUdid
        session.clientUdid = ""
        session.cookies = fresh.cookies.toMutableMap()
        cachedIdentity = fresh
        return fresh
    }

    /** 空响应=身份被拒时轮换新注册身份重试一次（对照 Node getPlaybackDevice.reset）。 */
    private fun withIdentity(block: (HongguoDeviceRegister.Identity) -> Unit): Boolean {
        return try {
            block(identity()); true
        } catch (e: Exception) {
            cachedIdentity = null
            session.clearAll()
            false
        }
    }

    // ---- feed ----
    suspend fun feed(path: String, offset: Int, sessionId: String): HongguoClient.CategoryPage =
        withContext(Dispatchers.IO) { client.category(path, offset, sessionId) }

    // ---- 详情 ----
    suspend fun detail(seriesId: String): HongguoWeb.SeriesDetail =
        withContext(Dispatchers.IO) { web.seriesDetail(seriesId) }

    // ---- 播放 ----
    suspend fun playUrl(vid: String): HongguoPlay.PlayUrl = withContext(Dispatchers.IO) {
        var result: HongguoPlay.PlayUrl? = null
        for (attempt in 0 until 2) {
            if (withIdentity { id -> result = play.playUrl(vid, id) }) break
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

    fun favorites(): List<WatchRecord> = readRecords("favorites")

    fun isFavorite(seriesId: String): Boolean =
        localPrefs.getString("favorites", null)?.contains("\"$seriesId\"") == true

    fun toggleFavorite(record: WatchRecord): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.seriesId == record.seriesId }
        return if (existing >= 0) {
            list.removeAt(existing); writeRecords("favorites", list); false
        } else {
            list.add(0, record); writeRecords("favorites", list); true
        }
    }

    fun history(): List<WatchRecord> = readRecords("history")

    fun saveProgress(record: WatchRecord) {
        val list = history().filter { it.seriesId != record.seriesId }.toMutableList()
        list.add(0, record)
        writeRecords("history", list.take(200))
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
