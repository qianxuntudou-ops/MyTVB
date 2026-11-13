package com.mytvb.feature.hongguo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.mytvb.feature.hongguo.net.HongguoPlay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 官方雪碧图进度预览（2026-11-13 替代旧 MediaMetadataRetriever 本地取帧器）：
 * player/v1 `big_thumbs` 下发 HEIC 网格整图（横屏剧 10×10 格 240×136，竖屏剧 136×240，
 * 按字段取勿假设方向），bind 时下载+解码一次保留整图（约 13MB Bitmap，裁格是 µs 级
 * 轻操作主线程直接做），request 按官方公式 `idx = 秒/interval → row=idx/xLen,col=idx%xLen`
 * 裁格并缩放入 LRU。
 *
 * 无回退层：本集未下发 big_thumbs / 下载失败 / HEIC 解码失败（API<28 无系统解码器）
 * 一律不启用预览图，浮窗保持纯时间提示——官方数据是唯一源（2026-11-13 定案）。
 */
internal class HongguoThumbProvider(
    private val thumbWidthPx: Int,
    private val onThumbReady: (quantizedMs: Long, bitmap: Bitmap?) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            isDaemon = true
            name = "hg-thumb"
        }
    }
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val lru = LruCache<Long, Bitmap>(LRU_CELLS)
    private val generation = AtomicLong(0)

    @Volatile private var thumbs: HongguoPlay.BigThumbs? = null
    @Volatile private var sheet: Bitmap? = null
    @Volatile private var available = false

    /** 绑定本集雪碧图（null = 本集未下发，清空旧集状态）。异步下载+解码，失败不再重试。 */
    fun bind(source: HongguoPlay.BigThumbs?) {
        val gen = generation.incrementAndGet()
        thumbs = source
        available = false
        lru.evictAll()
        val old = sheet
        sheet = null
        old?.recycle()
        if (source == null) return
        try {
            executor.execute {
                if (gen != generation.get()) return@execute
                val loaded = runCatching { downloadAndDecode(source.imageUrl) }
                    .onFailure { Log.w(TAG, "thumb sheet load failed: ${it.message}") }
                    .getOrNull()
                if (gen != generation.get()) {
                    loaded?.recycle()
                    return@execute
                }
                if (loaded == null) {
                    Log.w(TAG, "thumb sheet unavailable (no big_thumbs data or HEIC undecodable)")
                    return@execute
                }
                sheet = loaded
                available = true
                Log.d(TAG, "thumb sheet bound ${loaded.width}x${loaded.height} cell=${source.cellWidth}x${source.cellHeight}")
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** 请求目标位置预览格；未就绪（下载中/失败/未下发）静默无操作，浮窗保持纯时间形态。 */
    fun request(positionMs: Long) {
        val t = thumbs ?: return
        if (!available) return
        val intervalMs = t.intervalSec * 1000L
        val quantized = (positionMs.coerceAtLeast(0L) / intervalMs) * intervalMs
        lru.get(quantized)?.let { deliver(quantized, it); return }
        val sheet = sheet ?: return
        val index = t.cellIndexFor(positionMs)
        val row = index / t.xLen
        val col = index % t.xLen
        val left = col * t.cellWidth
        val top = row * t.cellHeight
        if (left + t.cellWidth > sheet.width || top + t.cellHeight > sheet.height) return
        val cell = Bitmap.createBitmap(sheet, left, top, t.cellWidth, t.cellHeight)
        val scaled = scaleDown(cell)
        if (scaled !== cell) cell.recycle()
        lru.put(quantized, scaled)
        deliver(quantized, scaled)
    }

    fun release() {
        generation.incrementAndGet()
        available = false
        thumbs = null
        lru.evictAll()
        try {
            executor.execute {
                sheet?.recycle()
                sheet = null
            }
            executor.shutdown()
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun downloadAndDecode(url: String): Bitmap? {
        val bytes = http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 15; zh_CN; 2211133C; Build/V417IR)")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("thumb HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IllegalStateException("thumb empty body")
        }
        // HEIF 静态图：API 28+ 系统解码器支持；低版本/无解码器返回 null（不启用预览图）
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun deliver(quantizedMs: Long, bitmap: Bitmap) {
        main.post { onThumbReady(quantizedMs, bitmap) }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= thumbWidthPx) return src
        val height = (src.height.toLong() * thumbWidthPx / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, thumbWidthPx, height, true)
    }

    companion object {
        private const val TAG = "Hongguo"

        /** 预览格（~px225 宽 ≈ 0.13MB）16 张覆盖长按快进来回活动集（对齐旧取帧器容量）。 */
        private const val LRU_CELLS = 16
    }
}
