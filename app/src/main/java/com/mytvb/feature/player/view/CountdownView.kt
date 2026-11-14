package com.mytvb.feature.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

class CountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt() // 黑色 80% 透明度
        style = Paint.Style.FILL
    }

    private var cornerRadius = 0f
    private val overlayRect = RectF()

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF // 白色 30% 透明度
        style = Paint.Style.FILL
    }

    private val playButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // 白色
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt() // 黑色 80% 透明度
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // 白色
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.MITER
    }

    private val arcRect = RectF()
    private var strokeWidth = 0f
    private var progress = 0f

    // 不用 ValueAnimator：设备「动画时长缩放=0」时（MuMu/部分电视 ROM 实测）它不产生
    // 中间帧，进度环直接定格空圈——同类坑已把加载圈统一换成 gif（见 2026-11-14 记录）。
    // 本控件是进度环套不了 gif，改为墙钟自驱动：postOnAnimation 挂 vsync 不受动画缩放影响。
    private var countdownStartElapsedMs = 0L
    private var countdownTotalMs = 0L

    private val countdownTick = object : Runnable {
        override fun run() {
            val elapsedMs = SystemClock.elapsedRealtime() - countdownStartElapsedMs
            progress = if (countdownTotalMs > 0L) {
                (elapsedMs.toFloat() / countdownTotalMs).coerceIn(0f, 1f)
            } else {
                1f
            }
            invalidate()
            if (progress < 1f) {
                postOnAnimation(this)
            }
        }
    }

    init {
        val density = resources.displayMetrics.density
        strokeWidth = 4.5f * density
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        cornerRadius = 15f // px15，与弹窗背景 transparent_black_bg 一致（px 不是 dp）
    }

    fun startCountdown(durationMs: Long) {
        stopCountdown()
        if (durationMs <= 0L) {
            return
        }
        countdownStartElapsedMs = SystemClock.elapsedRealtime()
        countdownTotalMs = durationMs
        progress = 0f
        invalidate()
        postOnAnimation(countdownTick)
    }

    fun stopCountdown() {
        removeCallbacks(countdownTick)
        countdownTotalMs = 0L
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(countdownTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val radius = size / 2f * 0.55f

        // 黑色半透明蒙版覆盖整个封面（圆角）
        overlayRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(overlayRect, cornerRadius, cornerRadius, overlayPaint)

        // 半透明圆盘背景
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // 播放按钮三角形
        drawPlayButton(canvas, cx, cy, radius)

        // 进度轨道（黑色圆环）— 与圆盘同大小居中
        val halfStroke = strokeWidth / 2f
        val left = cx - radius + halfStroke
        val top = cy - radius + halfStroke
        val right = cx + radius - halfStroke
        val bottom = cy + radius - halfStroke
        arcRect.set(left, top, right, bottom)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        // 进度弧线（白色）
        if (progress > 0f) {
            val sweepAngle = 360f * progress
            canvas.drawArc(arcRect, -90f, sweepAngle, false, progressPaint)
        }
    }

    private fun drawPlayButton(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val scale = radius / 117f
        val cornerR = 10f * scale // 三角形圆角半径

        val offsetX = cx - 143f * scale
        val offsetY = cy - 148f * scale

        val x1 = 110.18f * scale + offsetX
        val y1 = 93.57f * scale + offsetY
        val x2 = 210.8f * scale + offsetX
        val y2 = 148.97f * scale + offsetY
        val x3 = 110.18f * scale + offsetX
        val y3 = 211.95f * scale + offsetY

        // 计算各角偏移后的点，沿边方向内缩 cornerR
        val p1 = floatArrayOf(x1, y1)
        val p2 = floatArrayOf(x2, y2)
        val p3 = floatArrayOf(x3, y3)

        val path = android.graphics.Path()
        // 从 p1->p2 边上，离 p1 角 cornerR 处开始
        val start = pointOnLine(p1, p2, cornerR)
        path.moveTo(start[0], start[1])
        // p2 角圆弧过渡
        val before2 = pointOnLine(p2, p1, cornerR)
        val after2 = pointOnLine(p2, p3, cornerR)
        path.lineTo(before2[0], before2[1])
        path.quadTo(p2[0], p2[1], after2[0], after2[1])
        // p3 角圆弧过渡
        val before3 = pointOnLine(p3, p2, cornerR)
        val after3 = pointOnLine(p3, p1, cornerR)
        path.lineTo(before3[0], before3[1])
        path.quadTo(p3[0], p3[1], after3[0], after3[1])
        // p1 角圆弧过渡
        val before1 = pointOnLine(p1, p3, cornerR)
        val after1 = pointOnLine(p1, p2, cornerR)
        path.lineTo(before1[0], before1[1])
        path.quadTo(p1[0], p1[1], after1[0], after1[1])
        path.close()
        canvas.drawPath(path, playButtonPaint)
    }

    /** 在 from→to 的线段上，距 from 点 distance 处的点 */
    private fun pointOnLine(from: FloatArray, to: FloatArray, distance: Float): FloatArray {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val ratio = distance / len
        return floatArrayOf(from[0] + dx * ratio, from[1] + dy * ratio)
    }
}
