package com.mytvb.feature.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.collection.LruCache
import com.mytvb.R
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.image.SimpleDisposable
import com.mytvb.model.player.VideoSnapshotData

/**
 * 跟随进度条滑块移动的缩略图预览浮窗（对齐 blbl videoShotPreview 形态）：
 * 水平方向贴着进度条预览位置、垂直方向悬在进度条上方，tick/点按推进时跟随滑块移动。
 * 小窗尺寸约占屏宽 12%，纯黑圆角底 + clipToOutline 裁剪内容图（blbl 同款）。
 *
 * 帧管线以「雪碧图」为缓存单位（blbl videoShotImageCache 同策略）：命中后主线程
 * 裁剪当前帧（~1ms）即可出图；未命中才发网络请求，且完成回调按"最新预览位置"
 * 裁剪——滑动中跨帧不再取消重发（取消风暴会让图永远停在同一帧），并顺手预取
 * 下一张雪碧图。帧级缓存不保留：裁剪太便宜，不值得多占一份内存。
 */
class TimelineThumbPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val previewImage: AppCompatImageView
    private var snapshot: VideoSnapshotData? = null
    private val spriteCache = object : LruCache<String, Bitmap>(SPRITE_CACHE_SHEETS) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    private var requestToken = 0
    private var currentDisposable: SimpleDisposable? = null
    private var inFlightUrl: String? = null
    private var lastQuantizedFrameMs = -1L
    private var currentFrameKey: String? = null
    private var latestPositionMs = -1L

    init {
        View.inflate(context, R.layout.timeline_thumb_preview, this)
        previewImage = findViewById(R.id.image_timeline_thumb)
        visibility = View.GONE
    }

    fun setSnapshot(newSnapshot: VideoSnapshotData?) {
        if (snapshot === newSnapshot) return
        snapshot = newSnapshot
        cancelRequest(resetFrameKey = true)
        spriteCache.evictAll()
        if (newSnapshot == null) {
            hideNow()
        }
    }

    /**
     * 显示 [positionMs] 对应帧并定位到进度条上方。
     * [trackBounds] 是进度条 track 在宿主 MyPlayerView 坐标系下的矩形。
     */
    fun show(positionMs: Long, durationMs: Long, trackBounds: Rect) {
        val data = snapshot
        if (data == null || durationMs <= 0L) {
            hideNow()
            return
        }
        latestPositionMs = positionMs
        positionToTrack(trackBounds, positionMs, durationMs)
        // 帧未就绪时先保持 INVISIBLE（黑底空窗不露脸），帧到达后再转 VISIBLE
        visibility = if (previewImage.drawable != null) View.VISIBLE else View.INVISIBLE
        requestFrame(data, positionMs)
    }

    fun hideNow() {
        removeHideCallback()
        cancelRequest(resetFrameKey = false)
        visibility = View.GONE
        previewImage.setImageDrawable(null)
        currentFrameKey = null
    }

    /** 提交 seek 后延迟收起（等渲染稳定，对齐 controller endSeekPreview 的淡出节奏）。 */
    fun hideAfter(delayMs: Long) {
        removeHideCallback()
        postDelayed(hideRunnable, delayMs)
    }

    private val hideRunnable = Runnable { hideNow() }

    private fun removeHideCallback() {
        removeCallbacks(hideRunnable)
    }

    private fun positionToTrack(trackBounds: Rect, positionMs: Long, durationMs: Long) {
        if (width <= 0 || trackBounds.width() <= 0) return
        val progressRatio = (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble())
        val hostWidth = (parent as? View)?.width ?: trackBounds.right
        val rawOffset = trackBounds.left + trackBounds.width() * progressRatio - width / 2.0
        val clamped = rawOffset.coerceIn(0.0, (hostWidth - width).coerceAtLeast(0).toDouble())
        translationX = clamped.toFloat()
        // 悬浮在进度条上方，留 px20 间距（blbl 实测 ~25px@1440p）
        val gap = resources.getDimension(R.dimen.px20)
        translationY = (trackBounds.top - height - gap).toFloat()
    }

    private fun requestFrame(data: VideoSnapshotData, positionMs: Long) {
        latestPositionMs = positionMs
        val quantizedMs = data.quantizeToFrameMs(positionMs)
        if (quantizedMs != null && quantizedMs == lastQuantizedFrameMs && previewImage.drawable != null) {
            return
        }
        if (quantizedMs != null) {
            lastQuantizedFrameMs = quantizedMs
        }

        if (applyLatestFrame(data)) return

        val frame = data.resolveFrame(positionMs) ?: return
        // 同一张雪碧图的请求已在路上：不取消不重发，等完成回调按最新位置裁剪
        if (inFlightUrl == frame.imageUrl) return
        fetchSprite(data, frame.imageUrl)
    }

    /** 用已缓存的雪碧图按最新预览位置即时出图；未命中返回 false。 */
    private fun applyLatestFrame(data: VideoSnapshotData): Boolean {
        val frame = data.resolveFrame(latestPositionMs) ?: return false
        if (frame.cacheKey == currentFrameKey && previewImage.drawable != null) return true
        val sprite = spriteCache.get(frame.imageUrl) ?: return false
        currentFrameKey = frame.cacheKey
        previewImage.setImageBitmap(cropFrameBitmap(sprite, frame))
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        return true
    }

    private fun fetchSprite(data: VideoSnapshotData, url: String) {
        val token = ++requestToken
        inFlightUrl = url
        currentDisposable?.dispose()
        currentDisposable = ImageLoader.loadBitmap(
            context = context,
            url = url,
            applyBilibiliHeaders = true,
            onSuccess = { sprite ->
                if (token != requestToken) return@loadBitmap
                spriteCache.put(url, sprite)
                if (inFlightUrl == url) inFlightUrl = null
                // 完成时按"当前想看的位置"裁剪——发起请求时的位置多半已过时
                applyLatestFrame(data)
                prefetchAdjacentSheets(data)
            },
            onFailed = {
                if (token == requestToken && inFlightUrl == url) inFlightUrl = null
            }
        )
    }

    /** 预取相邻雪碧图（快进/回滑大概率用到），不阻塞显示链。 */
    private fun prefetchAdjacentSheets(data: VideoSnapshotData) {
        val current = data.resolveFrame(latestPositionMs) ?: return
        for (delta in intArrayOf(1, -1)) {
            val nextUrl = data.images.getOrNull(current.imageIndex + delta) ?: continue
            val normalized = nextUrl.let {
                when {
                    it.startsWith("https://") -> it
                    it.startsWith("http://") -> "https://${it.removePrefix("http://")}"
                    it.startsWith("//") -> "https:$it"
                    else -> it
                }
            }
            if (spriteCache.get(normalized) != null) continue
            ImageLoader.loadBitmap(
                context = context,
                url = normalized,
                applyBilibiliHeaders = true,
                onSuccess = { sprite ->
                    if (snapshot === data) spriteCache.put(normalized, sprite)
                },
                onFailed = {}
            )
        }
    }

    private fun cancelRequest(resetFrameKey: Boolean) {
        requestToken += 1
        currentDisposable?.dispose()
        currentDisposable = null
        inFlightUrl = null
        if (resetFrameKey) {
            currentFrameKey = null
            lastQuantizedFrameMs = -1L
        }
    }

    private fun cropFrameBitmap(source: Bitmap, frame: VideoSnapshotData.Frame): Bitmap {
        val boundedX = frame.offsetX.coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val boundedY = frame.offsetY.coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val boundedWidth = frame.width.coerceAtMost(source.width - boundedX).coerceAtLeast(1)
        val boundedHeight = frame.height.coerceAtMost(source.height - boundedY).coerceAtLeast(1)
        return Bitmap.createBitmap(source, boundedX, boundedY, boundedWidth, boundedHeight)
    }

    companion object {
        /** 雪碧图缓存张数：单张约几 MB，4 张覆盖滑动来回的活动集合 */
        private const val SPRITE_CACHE_SHEETS = 4
    }
}
