package com.mytvb.feature.cctv.h5e

import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * CCTV 直播流地址解析器（issue#106 阶段二：vdn 接口直调，摆脱 WebView）。
 *
 * 官方 tv.cctv.com 播放器 internally 走 vdn.live.cntv.cn 拿加密 HLS（hls_cdrm）清单：
 * `GET /api/v3/vdn/live?channel=cctv1&vn=1&pdrm=1&uid=<md5随机>&hbss=<毫秒时间戳>`
 * Header `auth-key: <t>-<nonce>-<md5(channel + t + nonce + 盐)>`，盐为播放器内置常量。
 * 返回 `ack=yes` + `manifest.hls_cdrm`（备用 `backup.hls_cdrm`），清单与分片请求需带
 * `Referer: https://tv.cctv.com/`。
 * 两个端点（vdnx/vdnxbk）容错轮询。签名/端点/盐对齐 TvWasm/cjs 站点插件 main.js。
 */
object CctvVdnResolver {

    private const val TAG = "CctvVdnResolver"

    private val ENDPOINTS = arrayOf(
        "https://vdnx.live.cntv.cn/api/v3/vdn/live",
        "https://vdnxbk.live.cntv.cn/api/v3/vdn/live"
    )
    private const val SALT = "a4220a71b31746908fa3e7fdd7a6852a"
    const val REFERER = "https://tv.cctv.com/"

    /** 解析失败统一异常（消息面向日志）。 */
    class VdnResolveException(message: String) : Exception(message)

    data class CctvLiveStream(
        /** hls_cdrm master 清单 URL（已按 [bRange] 改写 b= 码率窗口，未传则保留原窗口）。 */
        val hlsUrl: String,
        val referer: String
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解析频道直播流。IO 线程执行；两个端点轮询，全部失败抛 [VdnResolveException]。
     *
     * @param channelId 频道 id（tv.cctv.com/live/<id>，如 cctv1/cctvchild）
     * @param bRange 可选码率窗口（[com.mytvb.feature.cctv.CctvLiveQuality.bRange]），
     *        替换 master URL 的 `b=低-高` 参数锁档（CDN 按窗口过滤变体，与网页 hook 同语义）
     */
    suspend fun resolveLive(channelId: String, bRange: String?): CctvLiveStream =
        withContext(Dispatchers.IO) {
            val uid = md5(System.currentTimeMillis().toString() + Math.random())
            var lastError = ""
            for (endpoint in ENDPOINTS) {
                val resolved = try {
                    resolveEndpoint(endpoint, channelId, uid, bRange)
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
                if (resolved != null) {
                    AppLog.i(TAG, "vdn 解析成功 channel=$channelId endpoint=${endpoint.substringAfter("//").take(20)} bRange=$bRange")
                    return@withContext resolved
                } else if (lastError.isBlank()) {
                    lastError = "endpoint $endpoint no hls_cdrm"
                }
            }
            throw VdnResolveException("vdn 直播解析失败 channel=$channelId last=$lastError")
        }

    /** 单端点解析：失败/无 hls_cdrm 返回 null（不抛，由调用方轮询下一端点）。 */
    private fun resolveEndpoint(
        endpoint: String,
        channelId: String,
        uid: String,
        bRange: String?
    ): CctvLiveStream? {
        val t = System.currentTimeMillis()
        val nonce = (100..1000).random()
        val auth = "$t-$nonce-${md5(channelId + t + nonce + SALT)}"
        val url = "$endpoint?channel=$channelId&vn=1&pdrm=1&uid=$uid&hbss=$t"
        val request = Request.Builder()
            .url(url)
            .header("Referer", REFERER)
            .header("auth-key", auth)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return null
            val json = JSONObject(body)
            if (json.optString("ack") != "yes") return null
            val manifest = json.optJSONObject("manifest")
            val backup = json.optJSONObject("backup")
            var hls = manifest?.optString("hls_cdrm").orEmpty()
            if (hls.isBlank()) hls = backup?.optString("hls_cdrm").orEmpty()
            if (hls.isBlank()) return null
            if (!bRange.isNullOrBlank()) {
                hls = replaceBRange(hls, bRange)
            }
            return CctvLiveStream(hlsUrl = hls, referer = REFERER)
        }
    }

    /** 替换 URL 的 `b=低-高` 码率窗口；URL 原本无 b= 参数则追加。 */
    private fun replaceBRange(url: String, bRange: String): String {
        return if (Regex("[?&]b=\\d+-\\d+").containsMatchIn(url)) {
            url.replace(Regex("([?&])b=\\d+-\\d+"), "$1b=$bRange")
        } else {
            url + (if (url.contains('?')) "&" else "?") + "b=$bRange"
        }
    }
}
