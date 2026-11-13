package com.mytvb.feature.hongguo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.mytvb.R

/**
 * 红果快进预览：跟随进度条滑块的帧预览浮窗。
 *
 * 数据源 = player/v1 响应内联的官方雪碧图 `big_thumbs`（HEIC 网格，2026-11-13 官方
 * 抓包实锤；旧「红果没有这个数据源」判断错误，本地取帧器已删），取格器见
 * [HongguoThumbProvider]。本集未下发 / 解码失败 = 不启用预览图，浮窗降级纯时间提示。
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
