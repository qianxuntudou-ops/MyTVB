package com.mytvb.feature.player.view

import com.mytvb.core.common.format.NumberUtils
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.mytvb.R
import com.mytvb.feature.player.sponsor.SponsorSegment
import java.util.Formatter
import java.util.Locale
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class DefaultTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), TimeBar {

    companion object {
    }

    private val listeners = CopyOnWriteArraySet<TimeBar.OnScrubListener>()
    private val tempPoint = Point()
    private val density: Float = context.resources.displayMetrics.density

    private val barHeight: Int
    private val touchTargetHeight: Int
    private val barGravity: Int
    private val scrubberEnabledSize: Int
    private val scrubberDisabledSize: Int
    private val scrubberDraggedSize: Int
    private val scrubberFocusPeakSize: Int

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrubberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val adMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playedAdMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sponsorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sponsorSegments: List<SponsorSegment> = emptyList()
    private var sponsorDurationMs: Long = 0L

    private val scrubberDrawable: Drawable?
    private val scrubberPadding: Int

    private val formatBuilder = StringBuilder()

    private var duration: Long = C.TIME_UNSET
    private var position: Long = 0
    private var bufferedPosition: Long = 0

    private var scrubbing: Boolean = false
    private var scrubberPosition: Long = 0

    private val drawnPosition = Rect()
    private val drawnScrubber = Rect()
    private val drawnBuffered = Rect()
    private val drawnUnplayed = Rect()
    private val touchTarget = Rect()
    private var gestureExclusionRect: Rect? = null

    private val scrubberAnimator = ValueAnimator()
    private var scrubberScale: Float = 1f

    private var keyCountIncrement: Int = 20
    private var keyTimeIncrement: Long = C.TIME_UNSET

    private val stopScrubbingRunnable = Runnable { stopScrubbing(false) }
    private val scrubAutoCommitTimeoutMs = 1000L

    private val fineScrubYThreshold: Int
    private var lastScrubX: Float = 0f

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (direction == View.FOCUS_UP) {
            super.addFocusables(views, direction, focusableMode)
        }
    }

    init {
        val defaultBarHeight = dpToPx(4)
        val defaultTouchTargetHeight = dpToPx(26)
        val defaultScrubberEnabledSize = dpToPx(12)
        val defaultScrubberDisabledSize = dpToPx(0)
        val defaultScrubberDraggedSize = dpToPx(16)

        scrubberPaint.isAntiAlias = true

        if (attrs != null) {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.DefaultTimeBar, 0, 0)
            try {
                scrubberDrawable = a.getDrawable(R.styleable.DefaultTimeBar_scrubber_drawable)
                barHeight = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_bar_height, defaultBarHeight)
                touchTargetHeight = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_touch_target_height, defaultTouchTargetHeight)
                barGravity = a.getInt(R.styleable.DefaultTimeBar_bar_gravity, 0)
                scrubberEnabledSize = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_scrubber_enabled_size, defaultScrubberEnabledSize)
                scrubberDisabledSize = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_scrubber_disabled_size, defaultScrubberDisabledSize)
                scrubberDraggedSize = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_scrubber_dragged_size, defaultScrubberDraggedSize)
                scrubberFocusPeakSize = a.getDimensionPixelSize(R.styleable.DefaultTimeBar_scrubber_focus_peak_size, scrubberDraggedSize)

                val playedColor = a.getInt(R.styleable.DefaultTimeBar_played_color, -0x1)
                val scrubberColor = a.getInt(R.styleable.DefaultTimeBar_scrubber_color, -0x1)
                val bufferedColor = a.getInt(R.styleable.DefaultTimeBar_buffered_color, -0x33333331)
                val unplayedColor = a.getInt(R.styleable.DefaultTimeBar_unplayed_color, 0x33FFFFFF)
                val adMarkerColor = a.getInt(R.styleable.DefaultTimeBar_ad_marker_color, -0x4ccccccd)
                val playedAdMarkerColor = a.getInt(R.styleable.DefaultTimeBar_played_ad_marker_color, 0x33FFFFFF)

                playedPaint.color = playedColor
                scrubberPaint.color = scrubberColor
                bufferedPaint.color = bufferedColor
                unplayedPaint.color = unplayedColor
                adMarkerPaint.color = adMarkerColor
                playedAdMarkerPaint.color = playedAdMarkerColor
            } finally {
                a.recycle()
            }
        } else {
            scrubberDrawable = null
            barHeight = defaultBarHeight
            touchTargetHeight = defaultTouchTargetHeight
            barGravity = 0
            scrubberEnabledSize = defaultScrubberEnabledSize
            scrubberDisabledSize = defaultScrubberDisabledSize
            scrubberDraggedSize = defaultScrubberDraggedSize
            scrubberFocusPeakSize = defaultScrubberDraggedSize

            playedPaint.color = -0x1
            scrubberPaint.color = -0x1
            bufferedPaint.color = -0x33333331
            unplayedPaint.color = 0x33FFFFFF
            adMarkerPaint.color = -0x4ccccccd
            playedAdMarkerPaint.color = 0x33FFFFFF
        }

        scrubberPadding = max(
            if (scrubberDrawable != null) (scrubberDrawable.intrinsicWidth + 1) / 2 else 0,
            (max(scrubberDisabledSize, max(scrubberEnabledSize, max(scrubberDraggedSize, scrubberFocusPeakSize))) + 1) / 2
        )

        isFocusable = true
        if (importantForAccessibility == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }

        fineScrubYThreshold = dpToPx(-50)

        scrubberAnimator.addUpdateListener { animation ->
            scrubberScale = animation.animatedValue as Float
            invalidate()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * density + 0.5f).toInt()
    }

    override fun addListener(listener: TimeBar.OnScrubListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TimeBar.OnScrubListener) {
        listeners.remove(listener)
    }

    override fun setKeyCountIncrement(count: Int) {
        require(count > 0) { "keyCountIncrement must be positive" }
        keyCountIncrement = count
        keyTimeIncrement = C.TIME_UNSET
    }

    override fun setKeyTimeIncrement(time: Long) {
        require(time > 0) { "keyTimeIncrement must be positive" }
        keyTimeIncrement = time
        keyCountIncrement = -1
    }

    override fun setPosition(position: Long) {
        if (this.position == position) {
            return
        }
        this.position = position
        updateContentDescription()
        invalidate()
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (this.bufferedPosition == bufferedPosition) {
            return
        }
        this.bufferedPosition = bufferedPosition
        invalidate()
    }

    override fun setDuration(duration: Long) {
        if (this.duration == duration) {
            return
        }
        this.duration = duration
        if (scrubbing && duration == C.TIME_UNSET) {
            stopScrubbing(true)
        }
        invalidate()
    }

    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) {
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && scrubbing) {
            stopScrubbing(true)
        }
    }

    private fun setScrubbing(scrubbing: Boolean) {
        if (this.scrubbing == scrubbing) {
            return
        }
        this.scrubbing = scrubbing
        if (scrubbing) {
            isPressed = true
            parent?.requestDisallowInterceptTouchEvent(true)
            listeners.forEach { it.onScrubStart(this, scrubberPosition) }
        } else {
            isPressed = false
            parent?.requestDisallowInterceptTouchEvent(false)
            invalidate()
        }
    }

    private fun updateScrubberPosition(position: Long) {
        if (scrubberPosition == position) {
            return
        }
        scrubberPosition = position
        listeners.forEach { it.onScrubMove(this, position) }
    }

    private fun getScrubberSize(): Int {
        return when {
            scrubbing || isFocused -> scrubberDraggedSize
            isEnabled -> scrubberEnabledSize
            else -> scrubberDisabledSize
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()

        val barLeft = paddingLeft + scrubberPadding
        val barRight = width - paddingRight - scrubberPadding
        val barWidth = barRight - barLeft
        if (barWidth <= 0) {
            canvas.restore()
            return
        }

        val barTop: Int
        val barBottom: Int
        when (barGravity) {
            1 -> {
                barBottom = height - paddingBottom
                barTop = barBottom - barHeight
            }
            else -> {
                val barCenterY = height / 2
                barTop = barCenterY - barHeight / 2
                barBottom = barTop + barHeight
            }
        }

        // 焦点态轨道上下各加宽（TV 焦点提示；对称扩展，播放头中心不变）。
        // 1dp 观感不足，用户要求再加粗 → 2dp
        val focusBarExpand = if (isFocused) dpToPx(2) else 0
        val drawnBarTop = barTop - focusBarExpand
        val drawnBarBottom = barBottom + focusBarExpand

        drawnUnplayed.set(barLeft, drawnBarTop, barRight, drawnBarBottom)
        drawnPosition.set(barLeft, drawnBarTop, barLeft, drawnBarBottom)
        drawnBuffered.set(barLeft, drawnBarTop, barLeft, drawnBarBottom)
        drawnScrubber.set(barLeft, drawnBarTop, barLeft, drawnBarBottom)

        if (duration > 0) {
            val playedProgress = position.toFloat() / duration
            drawnPosition.right = barLeft + (barWidth * playedProgress).toInt()

            val bufferedProgress = bufferedPosition.toFloat() / duration
            drawnBuffered.right = barLeft + (barWidth * bufferedProgress).toInt()

            val scrubberProgress = if (scrubbing) scrubberPosition.toFloat() / duration else playedProgress
            drawnScrubber.right = barLeft + (barWidth * scrubberProgress).toInt()
        }

        canvas.drawRect(drawnUnplayed, unplayedPaint)

        if (drawnBuffered.width() > 0) {
            val bufferedLeft = max(drawnPosition.right, drawnUnplayed.left)
            if (drawnBuffered.right > bufferedLeft) {
                canvas.drawRect(
                    bufferedLeft.toFloat(), drawnBarTop.toFloat(),
                    drawnBuffered.right.toFloat(), drawnBarBottom.toFloat(),
                    bufferedPaint
                )
            }
        }

        if (drawnPosition.width() > 0) {
            canvas.drawRect(drawnPosition, playedPaint)
        }

        // 空降助手标记：在进度之上、进度条头之下绘制
        if (sponsorSegments.isNotEmpty() && sponsorDurationMs > 0L) {
            for (segment in sponsorSegments) {
                val startRatio = segment.startTimeMs.toFloat() / sponsorDurationMs
                val endRatio = segment.endTimeMs.toFloat() / sponsorDurationMs
                val left = barLeft + (barWidth * startRatio)
                val right = barLeft + (barWidth * endRatio)
                sponsorPaint.color = (segment.categoryColor() and 0x00FFFFFFL).toInt() or 0x99000000.toInt()
                canvas.drawRect(left, drawnBarTop.toFloat(), right, drawnBarBottom.toFloat(), sponsorPaint)
            }
        }

        if (duration > 0) {
            val scrubberCenterX = drawnScrubber.right.coerceIn(barLeft, barRight)
            val scrubberCenterY = (barTop + barBottom) / 2
            val scrubberSize = (getScrubberSize() * scrubberScale).toInt()
            val scrubberRadius = scrubberSize / 2f

            if (scrubberDrawable != null) {
                val intrinsicMax = max(scrubberDrawable.intrinsicWidth, scrubberDrawable.intrinsicHeight)
                val drawableScale = if (intrinsicMax > 0) scrubberSize.toFloat() / intrinsicMax else scrubberScale
                val drawableWidth = (scrubberDrawable.intrinsicWidth * drawableScale).toInt()
                val drawableHeight = (scrubberDrawable.intrinsicHeight * drawableScale).toInt()
                scrubberDrawable.setBounds(
                    scrubberCenterX - drawableWidth / 2,
                    scrubberCenterY - drawableHeight / 2,
                    scrubberCenterX + drawableWidth / 2,
                    scrubberCenterY + drawableHeight / 2
                )
                if (scrubberDrawable.isStateful) {
                    scrubberDrawable.state = drawableState
                }
                scrubberDrawable.draw(canvas)
            } else {
                canvas.drawCircle(scrubberCenterX.toFloat(), scrubberCenterY.toFloat(), scrubberRadius, scrubberPaint)
            }
        }

        canvas.restore()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (scrubbing && !gainFocus) {
            stopScrubbing(false)
        }
        if (gainFocus && !scrubbing && scrubberFocusPeakSize > scrubberDraggedSize) {
            val peakScale = scrubberFocusPeakSize.toFloat() / scrubberDraggedSize
            scrubberAnimator.cancel()
            scrubberScale = peakScale
            invalidate()
            scrubberAnimator.interpolator = DecelerateInterpolator(1.5f)
            scrubberAnimator.setFloatValues(peakScale, 1f)
            scrubberAnimator.duration = 300
            scrubberAnimator.start()
        } else {
            scrubberAnimator.cancel()
            scrubberScale = 1f
            invalidate()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) {
            return super.onKeyDown(keyCode, event)
        }

        val increment = getPositionIncrement()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (increment > 0 && moveScrubber(-increment)) {
                    removeCallbacks(stopScrubbingRunnable)
                    postDelayed(stopScrubbingRunnable, scrubAutoCommitTimeoutMs)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (increment > 0 && moveScrubber(increment)) {
                    removeCallbacks(stopScrubbingRunnable)
                    postDelayed(stopScrubbingRunnable, scrubAutoCommitTimeoutMs)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (scrubbing) {
                    stopScrubbing(false)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getPositionIncrement(): Long {
        if (keyTimeIncrement != C.TIME_UNSET) {
            return keyTimeIncrement
        }
        if (duration == C.TIME_UNSET || duration <= 0) {
            return 0
        }
        return duration / keyCountIncrement
    }

    private fun moveScrubber(delta: Long): Boolean {
        if (duration <= 0) {
            return false
        }
        val currentPos = if (scrubbing) scrubberPosition else position
        val clampedPosition = (currentPos + delta).coerceIn(0, duration)
        if (clampedPosition == currentPos) {
            return false
        }
        if (!scrubbing) {
            scrubberPosition = clampedPosition
            setScrubbing(true)
        }
        updateScrubberPosition(clampedPosition)
        invalidate()
        return true
    }

    private fun stopScrubbing(canceled: Boolean) {
        removeCallbacks(stopScrubbingRunnable)
        if (scrubbing) {
            listeners.forEach { it.onScrubStop(this, scrubberPosition, canceled) }
            setScrubbing(false)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                tempPoint.set(event.x.toInt(), event.y.toInt())
                if (isInTouchTarget(tempPoint.x, tempPoint.y)) {
                    isPressed = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastScrubX = event.x
                    val pos = getPositionForX(tempPoint.x)
                    setScrubbing(true)
                    updateScrubberPosition(pos)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    val x = event.x
                    val y = event.y
                    val resolvedX = if (y < fineScrubYThreshold) {
                        // Fine-scrub: 1/3 sensitivity when finger is far above the bar
                        lastScrubX + (x - lastScrubX) / 3f
                    } else {
                        lastScrubX = x
                        x
                    }
                    val pos = getPositionForX(resolvedX.toInt())
                    updateScrubberPosition(pos)
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    stopScrubbing(event.action == MotionEvent.ACTION_CANCEL)
                    return true
                }
                return false
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun isInTouchTarget(x: Int, y: Int): Boolean {
        updateTouchTarget()
        return touchTarget.contains(x, y)
    }

    private fun updateTouchTarget() {
        val barLeft = paddingLeft
        val barRight = width - paddingRight
        when (barGravity) {
            1 -> {
                val touchBottom = height - paddingBottom
                val touchTop = touchBottom - touchTargetHeight
                touchTarget.set(barLeft, touchTop, barRight, touchBottom)
            }
            else -> {
                val touchCenterY = height / 2
                val touchTop = touchCenterY - touchTargetHeight / 2
                val touchBottom = touchTop + touchTargetHeight
                touchTarget.set(barLeft, touchTop, barRight, touchBottom)
            }
        }
    }

    private fun getPositionForX(x: Int): Long {
        val barLeft = paddingLeft + scrubberPadding
        val barRight = width - paddingRight - scrubberPadding
        val barWidth = barRight - barLeft
        if (barWidth <= 0) {
            return 0
        }
        val clampedX = x.coerceIn(barLeft, barRight)
        val progress = (clampedX - barLeft).toFloat() / barWidth
        return (progress * duration).toLong()
    }

    private fun updateContentDescription() {
        contentDescription = getProgressText()
    }

    private fun getProgressText(): String {
        return NumberUtils.formatTimeMs(position)
    }


    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            event.text.add(getProgressText())
        }
        event.className = "android.widget.SeekBar"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.contentDescription = getProgressText()
        if (duration > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, arguments)) {
            return true
        }
        if (duration <= 0) {
            return false
        }
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                moveScrubber(-getPositionIncrement())
                stopScrubbing(false)
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                moveScrubber(getPositionIncrement())
                stopScrubbing(false)
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
                return true
            }
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val desiredHeight = touchTargetHeight
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            heightSize = desiredHeight
        } else if (heightMode != MeasureSpec.EXACTLY) {
            heightSize = min(desiredHeight, heightSize)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightSize)
        if (scrubberDrawable != null && scrubberDrawable.isStateful) {
            if (scrubberDrawable.setState(drawableState)) {
                invalidate()
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val width = right - left
            val height = bottom - top
            val currentRect = gestureExclusionRect
            if (currentRect == null || currentRect.width() != width || currentRect.height() != height) {
                val rect = Rect(0, 0, width, height)
                gestureExclusionRect = rect
                systemGestureExclusionRects = Collections.singletonList(rect)
            }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (scrubberDrawable != null && scrubberDrawable.isStateful) {
            if (scrubberDrawable.setState(drawableState)) {
                invalidate()
            }
        }
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        scrubberDrawable?.jumpToCurrentState()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        val drawable = scrubberDrawable ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (drawable.setLayoutDirection(layoutDirection)) {
                invalidate()
            }
        }
    }

    fun showScrubber() {
        scrubberAnimator.cancel()
        scrubberAnimator.interpolator = null
        scrubberScale = 1f
        invalidate()
    }

    fun showScrubber(animationDurationMs: Long) {
        scrubberAnimator.cancel()
        if (animationDurationMs <= 0) {
            showScrubber()
            return
        }
        scrubberAnimator.interpolator = null
        scrubberAnimator.setFloatValues(scrubberScale, 1f)
        scrubberAnimator.duration = animationDurationMs
        scrubberAnimator.start()
    }

    fun hideScrubber() {
        scrubberAnimator.cancel()
        scrubberAnimator.interpolator = null
        scrubberScale = 0f
        invalidate()
    }

    fun hideScrubber(animationDurationMs: Long) {
        scrubberAnimator.cancel()
        if (animationDurationMs <= 0) {
            hideScrubber()
            return
        }
        scrubberAnimator.interpolator = null
        scrubberAnimator.setFloatValues(scrubberScale, 0f)
        scrubberAnimator.duration = animationDurationMs
        scrubberAnimator.start()
    }

    override fun getPreferredUpdateDelay(): Long {
        val barLeft = paddingLeft + scrubberPadding
        val barRight = width - paddingRight - scrubberPadding
        val barWidthDp = (barRight - barLeft).toFloat() / density
        return if (barWidthDp > 0 && duration > 0 && duration != C.TIME_UNSET) {
            (duration / barWidthDp).toLong()
        } else {
            Long.MAX_VALUE
        }
    }

    fun setSponsorSegments(segments: List<SponsorSegment>) {
        sponsorSegments = segments
        invalidate()
    }

    fun setSponsorDuration(durationMs: Long) {
        sponsorDurationMs = durationMs
        invalidate()
    }
}
