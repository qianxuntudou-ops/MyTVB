package com.mytvb.feature.player.sponsor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SponsorProgressMarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** 章节起点竖线：白 80%、2px 宽（细条高度小，不按密度缩放以免吞掉整条）。 */
        private const val CHAPTER_MARKER_COLOR = 0xCCEEEEEE.toInt()
        private const val CHAPTER_MARKER_STROKE_PX = 2f
    }

    private var segments: List<SponsorSegment> = emptyList()
    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var sponsorDurationMs: Long = 0L
    private var chapterStartsMs: List<Long> = emptyList()
    private var chapterDurationMs: Long = 0L

    // 已播进度统一 B 站粉 90%：与收起态章节条 ChapterSegmentBarView、展开态
    // DefaultTimeBar(played_color #E6FB7299) 三处同色；曾用 #0088BB 蓝造成
    // 无章节视频收起态与章节视频观感割裂
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FB7299.toInt() }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chapterMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHAPTER_MARKER_COLOR
    }

    fun setSegments(segments: List<SponsorSegment>) {
        this.segments = segments
        invalidate()
    }

    fun setSponsorDuration(durationMs: Long) {
        this.sponsorDurationMs = durationMs
        invalidate()
    }

    /** 章节起点标记（view_points.fromMs，忽略 0 起点），在细条上画白竖线。 */
    fun setChapterStarts(startsMs: List<Long>, durationMs: Long) {
        this.chapterStartsMs = startsMs.filter { it > 0L }
        this.chapterDurationMs = durationMs
        invalidate()
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        this.positionMs = positionMs
        this.durationMs = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val progressEnd = SponsorProgressMarkerGeometry.playedEndPx(w, positionMs, durationMs)
        if (progressEnd > 0f) {
            canvas.drawRect(0f, 0f, progressEnd, h, playedPaint)
        }

        // 空降分段色块：左界裁到已播放末端之后——半透明绿叠在已播放色上会混合
        // 变脏（issue#140），高亮只落在未播放区间（与 DefaultTimeBar 同款修法）
        if (segments.isNotEmpty() && sponsorDurationMs > 0L) {
            for (segment in segments) {
                val left = ((segment.startTimeMs.toFloat() / sponsorDurationMs) * w)
                    .coerceAtLeast(progressEnd)
                val right = (segment.endTimeMs.toFloat() / sponsorDurationMs) * w
                if (right <= left) continue
                segmentPaint.color = (segment.categoryColor() and 0x00FFFFFFL).toInt() or 0x99000000.toInt()
                canvas.drawRect(left, 0f, right, h, segmentPaint)
            }
        }

        // 章节起点白竖线（最上层，细条全高）
        if (chapterStartsMs.isNotEmpty() && chapterDurationMs > 0L) {
            val halfStroke = CHAPTER_MARKER_STROKE_PX / 2f
            for (startMs in chapterStartsMs) {
                val x = (startMs.toFloat() / chapterDurationMs) * w
                canvas.drawRect(x - halfStroke, 0f, x + halfStroke, h, chapterMarkerPaint)
            }
        }
    }
}

internal object SponsorProgressMarkerGeometry {
    fun playedEndPx(widthPx: Float, positionMs: Long, durationMs: Long): Float {
        if (widthPx <= 0f || durationMs <= 0L) {
            return 0f
        }
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * widthPx
    }
}
