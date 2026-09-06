package com.tutu.myblbl.feature.player.danmaku.emote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.BiliClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 弹幕表情位图加载器：LruCache（进程堆 1/32，至少 2MB）+ 在途请求去重 + waiter 扇出。
 *
 * 使用方约定：
 * - 渲染路径只调 [getCached]（内存命中或 null，绝不触发网络）；
 * - [prefetch] 由建图调度在表情未就绪时触发；加载完成后引擎下一帧轮询会发现就绪并建图，
 *   因此无需完成回调。
 * - B 站 CDN 的 http 表情 URL 归一化为 https（hdslb/bilibili/bilivideo 域）。
 *
 * 对齐 blbl.cat3399.core.emote.EmoteBitmapLoader。
 */
internal object EmoteBitmapLoader {
    private const val TAG = "DanmakuEmoteLoader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private val cache =
        object : LruCache<String, Bitmap>(maxCacheBytes()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    private val inFlight: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val waiters: ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>> = ConcurrentHashMap()

    fun getCached(url: String): Bitmap? {
        val normalized = normalizeImageUrl(url) ?: return null
        if (normalized.isBlank()) return null
        synchronized(lock) {
            return cache.get(normalized)
        }
    }

    fun prefetch(url: String?) {
        val normalized = normalizeImageUrl(url) ?: return
        load(normalized) { /* no-op */ }
    }

    fun load(url: String, onResult: (Bitmap?) -> Unit) {
        val normalized = normalizeImageUrl(url)
        if (normalized == null || normalized.isBlank()) {
            onResult(null)
            return
        }

        synchronized(lock) {
            val cached = cache.get(normalized)
            if (cached != null) {
                onResult(cached)
                return
            }
        }

        // 在途请求去重：同一 URL 只发一次网络，结果扇出给所有 waiter。
        synchronized(lock) {
            val list = waiters[normalized]
            if (list != null) {
                list.add(onResult)
            } else {
                waiters[normalized] = mutableListOf(onResult)
            }

            if (inFlight[normalized]?.isActive == true) return

            val job =
                scope.launch {
                    val bmp =
                        runCatching {
                            val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        }.onFailure { t ->
                            AppLog.w(TAG, "load failed url=$normalized", t)
                        }.getOrNull()

                    if (bmp != null) {
                        synchronized(lock) {
                            cache.put(normalized, bmp)
                        }
                    }

                    val callbacks =
                        synchronized(lock) {
                            val out = waiters.remove(normalized).orEmpty().toList()
                            inFlight.remove(normalized)
                            out
                        }
                    callbacks.forEach { cb ->
                        runCatching { cb(bmp) }
                    }
                }

            inFlight[normalized] = job
        }
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return (maxMemory / 32).coerceAtLeast(2 * 1024 * 1024)
    }
}
