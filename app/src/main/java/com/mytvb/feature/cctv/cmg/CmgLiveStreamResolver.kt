package com.mytvb.feature.cctv.cmg

import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * 央视频（yangshipin）直播流解析器（三步签名链协调器）。
 *
 * get_live_info 端点按 TLS 指纹风控：OkHttp 携带与浏览器完全相同的签名/参数仍被拒
 * 20401，curl 与 WebView 放行（2026-10-31 MuMu + 桌面 curl 双向实证）——因此三步链
 * 的 HTTP 请求全部经 [CmgWebSigner] 的离屏 WebView 发出（浏览器栈），本类只负责：
 * 1. 签名材料计算（authBody 的 md5+AUTH_SALT、liveBody+cKey AES、sdkInput 定序 md5，
 *    对齐 cjs sites/yangshipin.cn 模板与 nTv YangshipinApiPayload）；
 * 2. auth 响应后按服务器秒补算 liveBody（cKey/requestId 依赖服务器时间）并渲染
 *    live-request 注入脚本；
 * 3. 结果组装（playurl + 自造 playerTag + 时钟偏移，native-light 模式）。
 */
object CmgLiveStreamResolver {

    private const val TAG = "CmgLiveStreamResolver"

    private const val APP_ID = "519748109"
    private const val APP_VERSION = "V1.0.0"
    private const val PLATFORM = "5910204"
    private const val AUTH_SALT = "n@7QKk%YeSjfw%22"
    private const val LIVE_SALT = "0f\$IVHi9Qno?G"
    private val CKEY_KEY = hexBytes("48e5918a74ae21c972b90cce8af6c8be")
    private val CKEY_IV = hexBytes("9a7e7d23610266b1d9fbf98581384d92")

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    /** 分片/清单请求的 Referer（MarmotLiveActivity 组 HTTP 工厂用）。 */
    const val REFERER = "https://www.yangshipin.cn/"

    private const val TIMEOUT_MS = 25_000L
    private const val CACHE_MS = 10L * 60L * 1000L

    private val RANDOM_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
    private val RANDOM = SecureRandom()

    /** 解析失败统一异常。 */
    class CmgResolveException(message: String) : Exception(message)

    /**
     * 解析结果。
     * @param hlsUrl ysp.cctv.cn 的媒体清单 URL（已拼 extended_param）
     * @param playerTag CMG 解密用自造毫秒时间戳（服务器校准时间）
     * @param clockOffsetMs 服务器时间 - 本地时间（解密时钟推进用）
     */
    data class CmgLiveStream(
        val hlsUrl: String,
        val playerTag: String,
        val clockOffsetMs: Long
    )

    /** 服务器时钟偏移（auth 的 ts 校准），进程内缓存。 */
    @Volatile
    private var clockOffsetMs: Long? = null

    /** 解析结果内存缓存（pid+defn → 结果，10 分钟），快速切台复用。 */
    private val resultCache = LinkedHashMap<String, CmgLiveStream>()
    private var resultCacheAt = 0L

    /**
     * 解析央视频频道直播流。失败抛 [CmgResolveException]。
     * @param context 主线程 WebView 需要的 context（Activity）
     * @param pid 频道 livepid（URL ?pid=）
     * @param cnlid 央视频频道号（[CmgChannelCatalog.cnlidOf]）
     * @param defn 画质档：fhd/shd/hd/sd，非法值归一为 fhd
     */
    suspend fun resolveLive(
        context: android.content.Context,
        pid: String,
        cnlid: String,
        defn: String = "fhd"
    ): CmgLiveStream = withContext(Dispatchers.IO) {
        val quality = if (defn == "shd" || defn == "hd" || defn == "sd") defn else "fhd"
        val cacheKey = "$pid $quality"
        synchronized(resultCache) {
            if (resultCache.containsKey(cacheKey) &&
                System.currentTimeMillis() - resultCacheAt in 0 until CACHE_MS
            ) {
                AppLog.i(TAG, "使用缓存 CMG 线路 pid=$pid defn=$quality")
                return@withContext resultCache.getValue(cacheKey)
            }
        }

        // ---- 签名材料（auth 前可知部分；guid 前缀=base36(服务器估计时间)后 8 位）----
        val serverGuessMs = clockOffsetMs?.let { System.currentTimeMillis() + it }
            ?: System.currentTimeMillis()
        val guid = createGuid(serverGuessMs)
        val authRandom = randomText(10)
        val ticketRandom = randomText(10)
        val liveRandom = randomText(10)
        val auth = TreeMap<String, String>()
        auth["appid"] = "ysp_pc"
        auth["guid"] = guid
        auth["pid"] = pid
        auth["rand_str"] = authRandom
        val authSignature = md5(joinForSignature(auth) + AUTH_SALT)
        val authBody = "pid=${urlEncode(pid)}&guid=${urlEncode(guid)}&appid=ysp_pc" +
            "&rand_str=${urlEncode(authRandom)}&signature=$authSignature"

        // ---- WebView 跑三步链（主线程）----
        val result = withTimeout(TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val signer = CmgWebSigner.get(context)
                    val request = CmgWebSigner.Request(
                        pid = pid, cnlid = cnlid, defn = quality, guid = guid,
                        ticketRandom = ticketRandom, authBody = authBody,
                        buildLiveScript = { serverSeconds, token ->
                            // auth 响应到达：按服务器秒补算全部时间敏感材料
                            clockOffsetMs = serverSeconds * 1000L - System.currentTimeMillis()
                            val requestId = "999999" + randomText(10) + serverSeconds * 1000L
                            val liveBody = createLiveBody(
                                pid, cnlid, guid, liveRandom, serverSeconds, quality
                            )
                            val sdkInput = createSdkInput(liveBody)
                            CmgWebSigner.renderLiveScript(
                                context, pid, guid, ticketRandom,
                                serverSeconds, token, liveBody, sdkInput, requestId
                            )
                        }
                    )
                    signer.request(request) { signed, reason ->
                        if (continuation.isActive) {
                            if (signed != null) {
                                continuation.resume(signed)
                            } else {
                                continuation.resumeWith(
                                    Result.failure(CmgResolveException(reason ?: "签名链失败"))
                                )
                            }
                        }
                    }
                }
            }
        }

        val stream = buildResult(result.playUrl)
        synchronized(resultCache) {
            resultCache.clear()
            resultCache[cacheKey] = stream
            resultCacheAt = System.currentTimeMillis()
        }
        AppLog.i(TAG, "CMG 解析成功 pid=$pid defn=$quality serverSeconds=${result.serverSeconds}" +
            " url=${stream.hlsUrl.take(90)}")
        stream
    }

    private fun buildResult(url: String): CmgLiveStream {
        val offset = clockOffsetMs ?: 0L
        return CmgLiveStream(
            hlsUrl = url,
            playerTag = (System.currentTimeMillis() + offset).toString(),
            clockOffsetMs = offset
        )
    }

    /** liveBody JSON（TreeMap 签名 + 字段全集，对齐 YangshipinApiPayload.createLiveBody）。 */
    private fun createLiveBody(
        pid: String, cnlid: String, guid: String, liveRandom: String,
        serverSeconds: Long, defn: String
    ): String {
        val live = TreeMap<String, String>()
        live["cnlid"] = cnlid
        live["livepid"] = pid
        live["stream"] = "2"
        live["guid"] = guid
        live["cKey"] = createCKey(cnlid, guid, serverSeconds)
        live["adjust"] = "1"
        live["sphttps"] = "1"
        live["platform"] = PLATFORM
        live["cmd"] = "2"
        live["encryptVer"] = "8.1"
        live["dtype"] = "1"
        live["devid"] = "devid"
        live["otype"] = "ojson"
        live["appVer"] = APP_VERSION
        live["app_version"] = APP_VERSION
        live["channel"] = "ysp_tx"
        live["defn"] = defn
        live["rand_str"] = liveRandom
        val signature = md5(joinForSignature(live) + LIVE_SALT)
        val json = JSONObject()
        for ((key, value) in live) json.put(key, value)
        json.put("adjust", 1)
        json.put("signature", signature)
        return json.toString()
    }

    /** yspsdkinput：17 键定序串接后 md5（对齐 YangshipinApiPayload.createSdkInput）。 */
    private fun createSdkInput(liveBody: String): String {
        val json = JSONObject(liveBody)
        val order = arrayOf(
            "adjust", "app_version", "appVer", "channel", "cKey", "cmd",
            "cnlid", "defn", "devid", "dtype", "encryptVer", "guid",
            "livepid", "otype", "platform", "sphttps", "stream"
        )
        val value = order.joinToString("&") { "$it=${json.optString(it, "")}" }
        return md5(value)
    }

    /** cKey：hash 串 AES/CBC 加密（前缀 --01 + 大写 hex，对齐 YangshipinApiPayload.createCKey）。 */
    private fun createCKey(channelId: String, guid: String, seconds: Long): String {
        val base = "|$channelId|$seconds|mg3c3b04ba|$APP_VERSION|$guid|$PLATFORM" +
            "|https://www.yangshipin.c" +
            "|mozilla/5.0 (windows nt ||Mozilla|Netscape|Win32|"
        var hash = 0
        for (ch in base) hash = hash * 31 + ch.code
        val plain = "|$hash$base".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(CKEY_KEY, "AES"), IvParameterSpec(CKEY_IV))
        return "--01" + toHex(cipher.doFinal(plain), upperCase = true)
    }

    private fun createGuid(serverTimeMs: Long): String {
        var prefix = java.lang.Long.toString(serverTimeMs, 36)
        if (prefix.length > 8) prefix = prefix.substring(prefix.length - 8)
        val value = StringBuilder(prefix).append('_')
        while (value.length < 20) {
            value.append(RANDOM_CHARS[RANDOM.nextInt(36)])
        }
        return value.substring(0, 20)
    }

    private fun randomText(length: Int): String {
        val value = StringBuilder(length)
        repeat(length) { value.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.size)]) }
        return value.toString()
    }

    private fun joinForSignature(values: TreeMap<String, String>): String =
        values.entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun hexBytes(text: String): ByteArray =
        ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private fun toHex(bytes: ByteArray, upperCase: Boolean): String {
        val digits = if (upperCase) "0123456789ABCDEF" else "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            out[index * 2] = digits[value ushr 4]
            out[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(out)
    }
}
