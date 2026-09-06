package com.tutu.myblbl.network

import android.os.SystemClock
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object BiliClient {

    private const val TAG = "BiliClient"
    private const val API_BASE = "https://api.bilibili.com/"

    @Volatile
    private var client: OkHttpClient? = null

    fun init(okHttpClient: OkHttpClient) {
        client = okHttpClient
    }

    private fun okHttpClient(): OkHttpClient {
        return client ?: NetworkManager.getOkHttpClient().also { resolvedClient ->
            client = resolvedClient
        }
    }

    suspend fun getJson(url: String, extraHeaders: Map<String, String>? = null): JSONObject {
        return withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val reqBuilder = Request.Builder().url(url)
            extraHeaders?.forEach { (k, v) -> reqBuilder.header(k, v) }
            val call = okHttpClient().newCall(reqBuilder.build())
            val t1 = SystemClock.elapsedRealtime()
            call.execute().use { resp ->
                val t2 = SystemClock.elapsedRealtime()
                val body = resp.body?.string().orEmpty()
                val t3 = SystemClock.elapsedRealtime()
                val json = JSONObject(body)
                val t4 = SystemClock.elapsedRealtime()
                AppLog.i(TAG, "getJson ${url.substringAfterLast("/")} net=${t2 - t1}ms read=${t3 - t2}ms parse=${t4 - t3}ms total=${t4 - t0}ms code=${resp.code}")
                json
            }
        }
    }

    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String>? = null
    ): JSONObject {
        return withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val formBody = FormBody.Builder().apply {
                form.forEach { (k, v) -> add(k, v) }
            }.build()
            val reqBuilder = Request.Builder()
                .url(url)
                .post(formBody)
            extraHeaders?.forEach { (k, v) -> reqBuilder.header(k, v) }
            okHttpClient().newCall(reqBuilder.build()).execute().use { resp ->
                val t1 = SystemClock.elapsedRealtime()
                val body = resp.body?.string().orEmpty()
                val t2 = SystemClock.elapsedRealtime()
                val json = JSONObject(body)
                val t3 = SystemClock.elapsedRealtime()
                AppLog.i(TAG, "postFormJson ${url.substringAfterLast("/")} net=${t1 - t0}ms read=${t2 - t1}ms parse=${t3 - t2}ms total=${t3 - t0}ms")
                json
            }
        }
    }

    fun buildUrl(path: String, params: Map<String, String>): String {
        val base = if (path.startsWith("http")) path else API_BASE + path
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val separator = if (base.contains("?")) "&" else "?"
        return base + separator + query
    }

    fun signedWbiUrl(path: String, params: Map<String, String>): String {
        val keys = NetworkManager.getWbiKeys()
        val signed = WbiGenerator.generateWbiParams(params, keys.first, keys.second)
        return buildUrl(path, signed)
    }

    /** 拉取原始字节（表情图等静态资源）。非 2xx 抛 IOException。 */
    suspend fun getBytes(url: String): ByteArray {
        return withContext(Dispatchers.IO) {
            okHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    fun requireCsrf(): String {
        return NetworkManager.getCsrfToken()
    }

    fun checkResponse(json: JSONObject, source: String): JSONObject {
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw ApiException(code, msg.ifEmpty { "请求失败" }, source)
        }
        return json
    }

    class ApiException(val code: Int, message: String, val source: String) : Exception("[$source] code=$code $message")
}
