package com.mytvb.ui.widget

import com.mytvb.core.ui.video.resolveThemedTextColor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mytvb.R

class TripleActionProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.getDimension(R.dimen.px6)
        color = resolveThemedTextColor(context, androidx.appcompat.R.attr.colorAccent, 0xFFBB86FC.toInt())
        strokeCap = Paint.Cap.ROUND
    }

    private val buttonSize = context.resources.getDimension(R.dimen.px80)
    private val gap = context.resources.getDimension(R.dimen.px20)
    private val oval = RectF()
    private var sweepAngle = 0f

    private var forwardAnimator: ValueAnimator? = null
    private var reverseAnimator: ValueAnimator? = null

    var onComplete: (() -> Unit)? = null

    fun start() {
        cancel()
        visibility = VISIBLE
        sweepAngle = 0f
        invalidate()

        forwardAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (sweepAngle >= 359f) {
                        onComplete?.invoke()
                        visibility = GONE
                        sweepAngle = 0f
                    }
                }
            })
            start()
        }
    }

    fun cancel() {
        val current = sweepAngle
        forwardAnimator?.removeAllListeners()
        forwardAnimator?.cancel()
        forwardAnimator = null
        reverseAnimator?.cancel()
        reverseAnimator = null

        if (current > 0f && visibility == VISIBLE) {
            reverseAnimator = ValueAnimator.ofFloat(current, 0f).apply {
                duration = 200L
                addUpdateListener {
                    sweepAngle = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        visibility = GONE
                        sweepAngle = 0f
                    }
                })
                start()
            }
        } else {
            visibility = GONE
            sweepAngle = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sweepAngle <= 0f) return

        val cy = height / 2f
        val radius = (buttonSize / 2f) - paint.strokeWidth

        for (i in 0..2) {
            val cx = i * (buttonSize + gap) + buttonSize / 2f
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, -90f, sweepAngle, false, paint)
        }
    }
}
