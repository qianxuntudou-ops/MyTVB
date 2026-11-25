package com.mytvb.core.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 双行标题 + 焦点滚动（issue#140）：常态两行截断，获焦/选中且内容超两行时
 * 逐行上滚循环（停 1.8s → 滚一行 → 到底停 1.8s → 回顶），对齐主页视频卡片
 * 「双行显示 + 标题滚动」的阅读体验。原生 marquee 只支持单行，故自管 scrollY。
 * 字号由布局决定（选集列表保持 px30 不缩，避免一屏条数变少）。
 */
class TwoLineScrollTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var scrollAnimator: ValueAnimator? = null
    private var autoScrollWanted = false

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelScroll()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        autoScrollWanted = selected
        applyScroll()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 布局完成才知道行数；复用条目重新 bind 后也要重判
        applyScroll()
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        applyScroll()
    }

    private fun applyScroll() {
        if (!autoScrollWanted || !isLaidOut || lineCount <= 0) {
            cancelScroll()
            return
        }
        if (lineCount <= maxLines) {
            cancelScroll()
            return
        }
        startScroll()
    }

    private fun startScroll() {
        if (scrollAnimator?.isRunning == true) return
        val lineH = lineHeight
        val maxScroll = (lineCount - maxLines) * lineH
        if (maxScroll <= 0) return
        val holdMs = HOLD_MS
        val stepMs = STEP_MS
        // 阶梯时间轴：停-滚-停-滚-…-到底停-复位，整体作为一个周期循环
        val steps = maxScroll / lineH
        val totalDuration = (steps + 2) * holdMs + steps * stepMs
        val animator = ValueAnimator.ofInt(0, maxScroll).apply {
            duration = totalDuration
            repeatCount = ValueAnimator.INFINITE
            // 阶梯时间轴在 evaluator 里自带节奏，插值必须线性，否则停留段被扭曲
            interpolator = android.view.animation.LinearInterpolator()
            setEvaluator(android.animation.TypeEvaluator { fraction: Float, start: Int, end: Int ->
                evaluateStaircase(fraction, start, end)
            })
            addUpdateListener { animation ->
                scrollTo(0, animation.animatedValue as Int)
            }
        }
        scrollAnimator = animator
        animator.start()
    }

    /** 分段插值：第 k 段为「停留 holdMs → 滚动 stepMs」交替，尾部停留后回顶 */
    private fun evaluateStaircase(fraction: Float, start: Int, end: Int): Int {
        val lineH = lineHeight.coerceAtLeast(1)
        val steps = (end - start + lineH - 1) / lineH
        if (steps <= 0) return start
        val holdMs = HOLD_MS
        val stepMs = STEP_MS
        val perStep = (holdMs + stepMs).toFloat()
        val cycle = (steps + 2) * holdMs + steps * stepMs
        var elapsed = fraction * cycle
        // 尾部到底停留
        val tailHoldStart = steps * perStep + holdMs
        if (elapsed >= tailHoldStart) return end
        var scrollLines = 0
        while (elapsed >= perStep) {
            elapsed -= perStep
            scrollLines++
        }
        return if (elapsed <= holdMs) {
            (scrollLines.coerceAtMost(steps)) * lineH
        } else {
            val lineFraction = (elapsed - holdMs) / stepMs
            ((scrollLines + lineFraction).coerceAtMost(steps.toFloat())).toInt() * lineH
        }
    }

    private fun cancelScroll() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollTo(0, 0)
    }

    companion object {
        private const val HOLD_MS = 1800L
        private const val STEP_MS = 400L
    }
}
