package com.mytvb.feature.hongguo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.mytvb.R
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 红果快进预览：跟随进度条滑块的帧预览浮窗 + 本地取帧器。
 *
 * blbl 用 videoShot 雪碧图接口做预览图（TimelineThumbPreviewView），红果没有这个
 * 数据源——但加密集（CENC spade）本来就在本地解密落地完整 MP4（HongguoPlayerActivity
 * 的 resolvePlayable），用 MediaMetadataRetriever 直接取目标位置关键帧即可；
 * 未加密远程直链集无本地文件，不 bind 取帧器，浮窗降级为纯时间提示。
 */

/**
 * 跟随进度条滑块移动的预览浮窗（定位模型对齐 TimelineThumbPreviewView.positionToTrack）。
 * 红果全是竖屏剧：窗体竖版固定尺寸（px127×px225，约画面 9:16），由宿主 addView 时给定；
 * 帧未就绪时 imageView GONE、时间提示居中（降级形态），出图后时间提示贴底。
 */
internal class HongguoSeekPreviewView(context: Context) : FrameLayout(context) {

    private val imageView: AppCompatImageView
    private val textTime: AppCompatTextView
    private val hideRunnable = Runnable { hideNow() }

    init {
        background = context.getDrawable(R.drawable.timeline_thumb_preview_bg)
        clipToOutline = true
        imageView = AppCompatImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textTime = AppCompatTextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px22))
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(2f, 1f, 1f, 0xFF000000.toInt())
            val hpad = resources.getDimensionPixelSize(R.dimen.px12)
            val vpad = resources.getDimensionPixelSize(R.dimen.px4)
            setPadding(hpad, vpad, hpad, vpad)
        }
        addView(
            textTime,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
        visibility = GONE
    }

    /**
     * 显示 [positionMs] 对应预览并定位到进度条上方。
     * [trackBounds] 是进度条在宿主（根布局）坐标系下的矩形。
     */
    fun show(positionMs: Long, durationMs: Long, trackBounds: Rect) {
        removeCallbacks(hideRunnable)
        if (durationMs <= 0 || trackBounds.width() <= 0) {
            hideNow()
            return
        }
        textTime.text = formatMs(positionMs.coerceIn(0L, durationMs))
        positionToTrack(trackBounds, positionMs, durationMs)
        // 帧未就绪先不露黑底空窗：无图时只剩时间提示也保持可见（降级形态）
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    /** 取帧器回调出图；null = 该位置取帧失败，退回纯时间提示形态。 */
    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            imageView.visibility = GONE
            (textTime.layoutParams as LayoutParams).gravity = Gravity.CENTER
        } else {
            imageView.visibility = VISIBLE
            imageView.setImageBitmap(bitmap)
            (textTime.layoutParams as LayoutParams).gravity =
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        textTime.requestLayout()
    }

    fun hideNow() {
        removeCallbacks(hideRunnable)
        visibility = GONE
        imageView.setImageDrawable(null)
        imageView.visibility = GONE
        (textTime.layoutParams as LayoutParams).gravity = Gravity.CENTER
        textTime.requestLayout()
    }

    /** 提交 seek 后延迟收起（等渲染稳定，对齐 TimelineThumbPreviewView.hideAfter 节奏）。 */
    fun hideAfter(delayMs: Long) {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, delayMs)
    }

    private fun positionToTrack(trackBounds: Rect, positionMs: Long, durationMs: Long) {
        val host = parent as? View ?: return
        if (host.width <= 0 || trackBounds.width() <= 0) return
        val progressRatio =
            (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble())
        val rawOffset = trackBounds.left + trackBounds.width() * progressRatio - width / 2.0
        val clamped = rawOffset.coerceIn(0.0, (host.width - width).coerceAtLeast(0).toDouble())
        translationX = clamped.toFloat()
        // 悬浮在进度条上方，留 px20 间距（blbl 实测 ~25px@1440p）
        val gap = resources.getDimensionPixelSize(R.dimen.px20)
        translationY = (trackBounds.top - height - gap).toFloat()
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}

/**
 * 本地 MP4 关键帧取帧器（MediaMetadataRetriever）：
 * 单线程串行 + 「只追最新位置」（堆队任务读最新量化槽位，完成回调只出最新位置帧）
 * + 1s 槽位量化 + 缩略图 LRU。切集/销毁用代际号作废在途任务。
 */
internal class HongguoFrameExtractor(
    private val thumbWidthPx: Int,
    private val onFrameReady: (quantizedMs: Long, bitmap: Bitmap?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r).apply {
            isDaemon = true
            name = "hg-frame"
        }
    }
    private val lru = LruCache<Long, Bitmap>(LRU_FRAMES)
    @Volatile private var retriever: MediaMetadataRetriever? = null
    @Volatile private var generation = 0
    @Volatile private var available = false
    @Volatile private var latestQuantizedMs = -1L
    private val pending = AtomicBoolean(false)

    /** 绑定本地解密文件（异步：setDataSource 要解析文件头）。失败后保持降级形态。 */
    fun bindAsync(localFilePath: String) {
        val gen = ++generation
        latestQuantizedMs = -1L
        executor.execute {
            retriever?.release()
            retriever = null
            available = false
            lru.evictAll()
            if (gen != generation) return@execute
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(localFilePath)
                if (gen != generation) {
                    r.release()
                    return@execute
                }
                retriever = r
                available = true
                Log.d("Hongguo", "frame extractor bound")
            } catch (e: Exception) {
                Log.w("Hongguo", "frame extractor bind failed", e)
            }
        }
    }

    /** 请求目标位置帧；未绑定（远程直链集）为空操作，浮窗保持纯时间提示。 */
    fun request(positionMs: Long) {
        if (!available) return
        val quantized = (positionMs.coerceAtLeast(0L) / QUANTIZE_MS) * QUANTIZE_MS
        latestQuantizedMs = quantized
        lru.get(quantized)?.let { deliver(quantized, it) }
        if (pending.getAndSet(true)) return
        try {
            executor.execute {
                pending.set(false)
                val target = latestQuantizedMs
                val r = retriever
                if (target < 0 || r == null || !available) return@execute
                lru.get(target)?.let { deliver(target, it); return@execute }
                val bitmap = try {
                    r.getFrameAtTime(target * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { scaleDown(it) }
                } catch (e: Exception) {
                    Log.w("Hongguo", "getFrameAtTime failed at $target", e)
                    null
                }
                if (bitmap == null) {
                    Log.w("Hongguo", "getFrameAtTime null at $target")
                    return@execute
                }
                lru.put(target, bitmap)
                deliver(target, bitmap)
            }
        } catch (_: RejectedExecutionException) {
            pending.set(false)
        }
    }

    fun release() {
        generation++
        available = false
        try {
            executor.execute {
                retriever?.release()
                retriever = null
            }
            executor.shutdown()
        } catch (_: RejectedExecutionException) {
        }
    }

    /** 帧与槽位成对回调（任务执行期间最新槽位可能已变，恒以取帧时的 target 为准）。 */
    private fun deliver(target: Long, bitmap: Bitmap) {
        main.post { onFrameReady(target, bitmap) }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= thumbWidthPx) return src
        val height = (src.height.toLong() * thumbWidthPx / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, thumbWidthPx, height, true)
    }

    companion object {
        /** 预览帧按 1s 槽位量化，长按 tick 40ms 一次 request 只在跨秒时真正取帧。 */
        private const val QUANTIZE_MS = 1000L

        /** 缩略图（~px225 宽）单张约 0.2MB，16 张覆盖长按快进的来回活动集。 */
        private const val LRU_FRAMES = 16
    }
}
