package com.tutu.myblbl.network.api

import android.os.SystemClock
import com.tutu.myblbl.model.live.LiveListWrapper
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.BiliClient
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.WbiGenerator
import org.json.JSONObject

object BiliApi {

    private const val TAG = "BiliApi"

    data class RecommendPageResult(
        val items: List<VideoModel>,
        val rawCount: Int,
        val source: String
    )

    suspend fun recommendV8(freshIdx: Int, ps: Int, fetchRow: Int): RecommendPageResult {
        val t0 = SystemClock.elapsedRealtime()
        if (NetworkManager.areWbiKeysStale()) {
            val ensureStart = SystemClock.elapsedRealtime()
            NetworkManager.ensureWbiKeys()
            AppLog.i(TAG, "recommendV8 ensureWbiKeys elapsed=${SystemClock.elapsedRealtime() - ensureStart}ms")
        }
        val keys = NetworkManager.getWbiKeys()
        val params = mapOf(
            "ps" to ps.toString(),
            "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
            "fresh_idx_1h" to freshIdx.coerceAtLeast(1).toString(),
            "fetch_row" to fetchRow.coerceAtLeast(1).toString(),
            "feed_version" to "V8"
        )
        val signedParams = WbiGenerator.generateWbiParams(
            params = params,
            imgKey = keys.first,
            subKey = keys.second,
            includeDmParams = false
        )
        val url = BiliClient.buildUrl(
            path = "x/web-interface/wbi/index/top/feed/rcmd",
            params = signedParams
        )
        AppLog.i(
            TAG,
            "recommendV8 request freshIdx=${freshIdx.coerceAtLeast(1)} fetchRow=${fetchRow.coerceAtLeast(1)} ps=$ps keys=${keys.first.isNotBlank()}/${keys.second.isNotBlank()} url=$url"
        )
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommendV8 response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "recommendV8")
        val data = json.optJSONObject("data")
        if (data == null) {
            AppLog.w(TAG, "recommendV8 data is null, raw=${json.toString().take(200)}")
            return RecommendPageResult(emptyList(), rawCount = 0, source = "V8")
        }
        val items = data.optJSONArray("item") ?: data.optJSONArray("items")
        if (items == null) {
            AppLog.w(TAG, "recommendV8 item is null, data keys=${data.keys().asSequence().toList()}")
            return RecommendPageResult(emptyList(), rawCount = 0, source = "V8")
        }
        AppLog.i(TAG, "recommendV8 items count=${items.length()}")
        val result = parseRecommendItems(items.length()) { idx -> items.getJSONObject(idx) }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommendV8 total=${t2 - t0}ms parse=${t2 - t1}ms raw=${items.length()} result=${result.size}")
        return RecommendPageResult(result, rawCount = items.length(), source = "V8")
    }

    suspend fun recommend(freshIdx: Int, ps: Int): List<VideoModel> {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "x/web-interface/index/top/feed/rcmd",
            params = mapOf(
                "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
                "ps" to ps.toString(),
                "feed_version" to "V1",
                "fresh_type" to "3",
                "plat" to "1"
            )
        )
        AppLog.i(TAG, "recommend request url=$url")
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend response code=${json.optInt("code", -1)} msg=${json.optString("message", "")} net=${t1 - t0}ms")
        BiliClient.checkResponse(json, "recommend")
        val data = json.optJSONObject("data")
        if (data == null) {
            AppLog.w(TAG, "recommend data is null, raw=${json.toString().take(200)}")
            return emptyList()
        }
        val items = data.optJSONArray("items") ?: data.optJSONArray("item")
        if (items == null) {
            AppLog.w(TAG, "recommend items is null, data keys=${data.keys().asSequence().toList()}")
            return emptyList()
        }
        AppLog.i(TAG, "recommend items count=${items.length()}")
        val result = parseRecommendItems(items.length()) { idx -> items.getJSONObject(idx) }
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "recommend total=${t2 - t0}ms parse=${t2 - t1}ms result=${result.size}")
        return result
    }

    private inline fun parseRecommendItems(
        count: Int,
        crossinline itemAt: (Int) -> JSONObject
    ): List<VideoModel> {
        return (0 until count).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(itemAt(idx)) }
                .onFailure { AppLog.w(TAG, "recommend parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun hotList(pn: Int, ps: Int): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/popular",
            params = mapOf("pn" to pn.toString(), "ps" to ps.toString())
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "hotList")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "hotList parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun ranking(rid: Int, type: String = "all"): List<VideoModel> {
        val url = BiliClient.buildUrl(
            path = "x/web-interface/ranking/v2",
            params = mapOf("rid" to rid.toString(), "type" to type)
        )
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "ranking")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(list.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "ranking parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun related(aid: Long?, bvid: String?): List<VideoModel> {
        val params = mutableMapOf<String, String>()
        aid?.let { params["avid"] = it.toString() }
        bvid?.let { params["bvid"] = it }
        val url = BiliClient.buildUrl("x/web-interface/archive/related", params)
        val json = BiliClient.getJson(url)
        BiliClient.checkResponse(json, "related")
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { idx ->
            runCatching { VideoModel.fromJson(data.getJSONObject(idx)) }
                .onFailure { AppLog.w(TAG, "related parse item failed: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun liveHomeList(): LiveListWrapper {
        val t0 = SystemClock.elapsedRealtime()
        val url = BiliClient.buildUrl(
            path = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList",
            params = mapOf("platform" to "web")
        )
        val json = BiliClient.getJson(url)
        val t1 = SystemClock.elapsedRealtime()
        BiliClient.checkResponse(json, "liveHomeList")
        val data = json.optJSONObject("data")
        val result = if (data != null) LiveListWrapper.fromJson(data) else LiveListWrapper()
        val t2 = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "liveHomeList net=${t1 - t0}ms parse=${t2 - t1}ms total=${t2 - t0}ms")
        return result
    }
}
