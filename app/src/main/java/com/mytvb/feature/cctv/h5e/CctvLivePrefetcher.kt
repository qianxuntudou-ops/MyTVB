package com.mytvb.feature.cctv.h5e

import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * CCTV 直播起播预取器（issue#106 换台提速，借鉴 nTv 的 startup gate 机制）。
 *
 * vdn 解析完成后立刻（与 ExoPlayer 构建/prepare 并行）拉取 master 清单 → 媒体清单 →
 * 尾部 2 个分片并完成 H5e 解密，全部存入一次性交接缓存；ExoPlayer 首个清单/分片
 * 请求经 [CctvH5eDataSource] 命中缓存直接回放，把「清单两次往返 + 分片下载解密」
 * 从播放器起播关键路径中移除。
 *
 * 交接缓存为 take 即删语义（同 nTv HlsStartupPlaylistCache）：媒体清单只在播放器
 * **首次**请求时命中，后续 reload 走网络拿到最新窗口，不缓存滚动清单。
 * 预取在任何阶段失败都静默放弃——缓存不命中即回退纯网络路径，行为与无预取一致。
 */
object CctvLivePrefetcher {

    private const val TAG = "CctvLivePrefetcher"

    /** 交接缓存条目寿命：超过视为过期不交付（起播窗口短，防陈旧清单）。 */
    private const val ENTRY_MAX_AGE_MS = 15_000L

    /** 预取的分片数（CCTV 分片 4s/段：尾部 2 段 ≈ 起播门槛 1.5s + 追边余量）。 */
    private const val PREFETCH_SEGMENTS = 2

    private data class Entry(val body: ByteArray, val at: Long)

    private val entries = HashMap<String, Entry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var prefetchJob: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 启动一次预取（换台/重试入口调用）。会取消上一次未完成的预取。 */
    fun prefetchAsync(hlsUrl: String, referer: String) {
        cancel()
        entries.clear()
        prefetchJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                val master = fetch(hlsUrl, referer)
                    ?: return@launch AppLog.w(TAG, "预取 master 失败，放弃")
                put(hlsUrl, master)
                val masterText = master.decodeToString()
                val variantUrl = pickVariantUrl(masterText, hlsUrl)
                val mediaUrl: String
                val mediaBody: ByteArray
                if (variantUrl != null) {
                    val media = fetch(variantUrl, referer) ?: return@launch AppLog.w(TAG, "预取媒体清单失败，放弃")
                    put(variantUrl, media)
                    mediaUrl = variantUrl
                    mediaBody = media
                } else {
                    // 清单本身就是媒体清单（vdn 单档直发）
                    mediaUrl = hlsUrl
                    mediaBody = master
                }
                val segments = pickTailSegments(mediaBody.decodeToString(), mediaUrl, PREFETCH_SEGMENTS)
                if (segments.isEmpty()) return@launch AppLog.w(TAG, "媒体清单无分片，放弃")
                // 尾段并发下载 + 解密（native 每线程独立 wasm 上下文，与播放线程互不干扰）
                val decrypted = segments.map { seg ->
                    async {
                        val body = fetch(seg, referer) ?: return@async null
                        if (!looksLikeTransportStream(body)) return@async null
                        try {
                            CctvH5eDecryptor.decryptTransportStream(body)
                        } catch (t: Throwable) {
                            AppLog.w(TAG, "预取分片解密异常: ${t.message}")
                            null
                        }
                    }
                }.map { it.await() }
                var ok = 0
                segments.forEachIndexed { i, seg ->
                    decrypted[i]?.let { put(seg, it); ok++ }
                }
                AppLog.i(TAG, "预取完成 ${ok}/${segments.size} 段 清单2张 耗时${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                AppLog.w(TAG, "预取异常（忽略，走网络路径）: ${t.message}")
            }
        }
    }

    /** 一次性取走缓存（命中即删）。返回 null 表示未命中/已过期/已取走。 */
    fun take(url: String): ByteArray? {
        synchronized(entries) {
            val entry = entries.remove(url) ?: return null
            val age = android.os.SystemClock.elapsedRealtime() - entry.at
            if (age > ENTRY_MAX_AGE_MS) return null
            return entry.body
        }
    }

    /** 换台/退出清理：取消在途预取并清空缓存（防旧频道数据被新频道误命中、省内存）。 */
    fun cancel() {
        prefetchJob?.cancel()
        prefetchJob = null
        synchronized(entries) { entries.clear() }
    }

    private fun put(url: String, body: ByteArray) {
        synchronized(entries) { entries[url] = Entry(body, android.os.SystemClock.elapsedRealtime()) }
    }

    private fun fetch(url: String, referer: String): ByteArray? = try {
        val request = Request.Builder().url(url).header("Referer", referer).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.bytes()
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "预取请求失败 url=${url.take(60)}: ${t.message}")
        null
    }

    /** master 清单取最高档变体 URL（b= 窗口锁档后通常恰一条）；非 master 返回 null。 */
    private fun pickVariantUrl(masterText: String, masterUrl: String): String? {
        if (!masterText.contains("#EXT-X-STREAM-INF")) return null
        val lines = masterText.lines()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                val uri = lines[i + 1].trim()
                if (uri.isNotEmpty() && !uri.startsWith("#")) {
                    return java.net.URI(masterUrl).resolve(uri).toString()
                }
            }
        }
        return null
    }

    /** 媒体清单取尾部 [count] 个分片 URL（绝对化）。 */
    private fun pickTailSegments(mediaText: String, mediaUrl: String, count: Int): List<String> {
        val uris = ArrayList<String>()
        val base = java.net.URI(mediaUrl)
        for (line in mediaText.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            runCatching { uris.add(base.resolve(t).toString()) }
        }
        return if (uris.size <= count) uris else uris.subList(uris.size - count, uris.size)
    }

    private fun looksLikeTransportStream(bytes: ByteArray): Boolean {
        if (bytes.size < 188 || bytes.size % 188 != 0) return false
        if (bytes[0] != 0x47.toByte()) return false
        if (bytes.size >= 376 && bytes[188] != 0x47.toByte()) return false
        return true
    }
}
