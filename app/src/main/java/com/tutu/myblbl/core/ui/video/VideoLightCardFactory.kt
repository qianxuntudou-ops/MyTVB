package com.tutu.myblbl.core.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import kotlin.math.ceil

object VideoLightCardFactory {

    fun create(parent: ViewGroup, source: String = "VideoLightCard.flat"): VideoCardViews {
        val context = parent.context
        val metrics = LightCardMetrics.get(context)

        val root = FlatVideoLightCardLayout(context, metrics, source).apply {
            id = R.id.click_view
            isClickable = true
            isFocusable = true
            defaultFocusHighlightEnabled = false
            setBackgroundResource(R.drawable.cell_background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val imageView = ImageView(context).apply {
            id = R.id.imageView
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.default_video)
        }
        root.addView(imageView)
        root.imageView = imageView

        val coverMetaOverlay = VideoCoverMetaOverlayView(context)
        root.addView(coverMetaOverlay)
        root.coverMetaOverlay = coverMetaOverlay

        val progressBar = VideoCardProgressView(context, coverMetaOverlay).apply {
            id = R.id.progressBar
            visibility = View.GONE
        }
        root.progressBar = progressBar

        val textLayer = VideoCardTextLayerView(context).apply {
            id = R.id.textView
        }
        root.addView(textLayer)
        root.textLayer = textLayer

        return VideoCardViews(
            root = root,
            imageView = imageView,
            progressBar = progressBar,
            coverMetaOverlay = coverMetaOverlay,
            textLayer = textLayer
        )
    }

}

class VideoCardProgressView @JvmOverloads constructor(
    context: Context,
    private val coverOverlay: VideoCoverMetaOverlayView? = null,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var max: Int = 100
        set(value) {
            val next = value.coerceAtLeast(0)
            if (field == next) return
            field = next
            if (progress > next) {
                progress = next
            } else {
                publishProgress()
            }
        }

    var progress: Int = 0
        set(value) {
            val next = value.coerceIn(0, max.coerceAtLeast(0))
            if (field == next) return
            field = next
            publishProgress()
        }

    override fun setVisibility(visibility: Int) {
        if (this.visibility == visibility) return
        super.setVisibility(visibility)
        publishProgress()
    }

    private fun publishProgress() {
        coverOverlay?.bindProgress(
            max = max,
            progress = progress,
            visible = visibility == VISIBLE
        )
    }
}

class VideoCoverMetaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val metrics = LightCardMetrics.get(context)
    private val assets = OverlayAssets.get(context, metrics.metaIconSize)
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientClipPath = Path()
    private val gradientClipRect = RectF()
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.textColor)
        textSize = metrics.metaTextSize
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = metrics.badgeTextSize
    }
    private val badgeRect = RectF()

    private var playCountText: CharSequence = ""
    private var danmakuText: CharSequence = ""
    private var durationText: CharSequence = ""
    private var showPlayCount = false
    private var showDanmakuCount = false
    private var showInteractionBadge = false
    private var showChargeBadge = false
    private var progressMax = 100
    private var progressValue = 0
    private var showProgress = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = (h - metrics.coverGradientHeight).coerceAtLeast(0).toFloat()
        gradientPaint.shader = LinearGradient(
            0f,
            top,
            0f,
            h.toFloat(),
            Color.TRANSPARENT,
            0xAA000000.toInt(),
            Shader.TileMode.CLAMP
        )
        gradientClipRect.set(0f, 0f, w.toFloat(), h.toFloat())
        gradientClipPath.reset()
        gradientClipPath.addRoundRect(
            gradientClipRect,
            metrics.cardRadius.toFloat(),
            metrics.cardRadius.toFloat(),
            Path.Direction.CW
        )
    }

    fun bind(
        playCountText: CharSequence? = this.playCountText,
        showPlayCount: Boolean = this.showPlayCount,
        danmakuText: CharSequence? = this.danmakuText,
        showDanmakuCount: Boolean = this.showDanmakuCount,
        durationText: CharSequence? = this.durationText,
        showInteractionBadge: Boolean = this.showInteractionBadge,
        showChargeBadge: Boolean = this.showChargeBadge
    ) {
        val nextPlayCount = playCountText?.toString().orEmpty()
        val nextDanmaku = danmakuText?.toString().orEmpty()
        val nextDuration = durationText?.toString().orEmpty()
        if (TextUtils.equals(this.playCountText, nextPlayCount) &&
            TextUtils.equals(this.danmakuText, nextDanmaku) &&
            TextUtils.equals(this.durationText, nextDuration) &&
            this.showPlayCount == showPlayCount &&
            this.showDanmakuCount == showDanmakuCount &&
            this.showInteractionBadge == showInteractionBadge &&
            this.showChargeBadge == showChargeBadge
        ) {
            return
        }
        this.playCountText = nextPlayCount
        this.danmakuText = nextDanmaku
        this.durationText = nextDuration
        this.showPlayCount = showPlayCount
        this.showDanmakuCount = showDanmakuCount
        this.showInteractionBadge = showInteractionBadge
        this.showChargeBadge = showChargeBadge
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCoverGradient(canvas)
        drawMetaLine(canvas)
        drawDuration(canvas)
        drawBadges(canvas)
        drawProgress(canvas)
    }

    private fun drawCoverGradient(canvas: Canvas) {
        val top = (height - metrics.coverGradientHeight).coerceAtLeast(0).toFloat()
        val saveCount = canvas.save()
        canvas.clipPath(gradientClipPath)
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), gradientPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawMetaLine(canvas: Canvas) {
        var x = metrics.coverMetaStart.toFloat()
        val centerY = height - metrics.coverMetaBottom - metrics.metaIconSize / 2f
        val iconTop = centerY - metrics.metaIconSize / 2f
        if (showPlayCount && playCountText.isNotBlank()) {
            canvas.drawBitmap(assets.playCountIcon, x, iconTop, null)
            x += metrics.metaIconSize + metrics.metaTextStart
            x = drawMetaText(canvas, playCountText, x, centerY) + metrics.metaGroupGap
        }
        if (showDanmakuCount && danmakuText.isNotBlank()) {
            canvas.drawBitmap(assets.danmakuIcon, x, iconTop, null)
            x += metrics.metaIconSize + metrics.metaTextStart
            drawMetaText(canvas, danmakuText, x, centerY)
        }
    }

    private fun drawMetaText(canvas: Canvas, text: CharSequence, x: Float, centerY: Float): Float {
        val fm = metaPaint.fontMetrics
        val textHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3
        val baseline = centerY - textHeight / 2f + metrics.px3 - fm.top
        canvas.drawText(text.toString(), x, baseline, metaPaint)
        return x + metaPaint.measureText(text.toString())
    }

    private fun drawDuration(canvas: Canvas) {
        if (durationText.isBlank()) return
        val text = durationText.toString()
        val fm = metaPaint.fontMetrics
        val textHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px4 * 2
        val textWidth = metaPaint.measureText(text)
        val boxWidth = textWidth + metrics.px8 * 2
        val left = width - metrics.durationEnd - boxWidth
        val top = height - metrics.durationBottom - textHeight
        val baseline = top + metrics.px4 - fm.top
        canvas.drawText(text, left + metrics.px8, baseline, metaPaint)
    }

    private fun drawBadges(canvas: Canvas) {
        var right = width - metrics.badgeEnd.toFloat()
        val top = metrics.badgeTop.toFloat()
        if (showChargeBadge) {
            right = drawBadge(canvas, "充电专属", right, top, 0xFFF6A11B.toInt()) - metrics.badgeGap
        }
        if (showInteractionBadge) {
            drawBadge(canvas, "互动", right, top, 0xFFFB7299.toInt())
        }
    }

    private fun drawBadge(canvas: Canvas, text: String, right: Float, top: Float, color: Int): Float {
        val fm = badgeTextPaint.fontMetrics
        val textWidth = badgeTextPaint.measureText(text)
        val badgeWidth = textWidth + metrics.px8 * 2
        val badgeHeight = ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3 * 2
        val left = right - badgeWidth
        badgeRect.set(left, top, right, top + badgeHeight)
        badgePaint.color = color
        canvas.drawRoundRect(badgeRect, metrics.badgeRadius.toFloat(), metrics.badgeRadius.toFloat(), badgePaint)
        canvas.drawText(text, left + metrics.px8, top + metrics.px3 - fm.top, badgeTextPaint)
        return left
    }

    fun bindProgress(max: Int, progress: Int, visible: Boolean) {
        val nextMax = max.coerceAtLeast(0)
        val nextProgress = progress.coerceIn(0, nextMax)
        if (progressMax == nextMax && progressValue == nextProgress && showProgress == visible) {
            return
        }
        progressMax = nextMax
        progressValue = nextProgress
        showProgress = visible
        invalidate()
    }

    private fun drawProgress(canvas: Canvas) {
        if (!showProgress || progressMax <= 0 || progressValue <= 0 || width <= 0 || height <= 0) return
        val progressWidth = width * (progressValue / progressMax.toFloat())
        val saveCount = canvas.save()
        canvas.clipPath(gradientClipPath)
        badgePaint.color = PLAYER_PROGRESS_COLOR
        canvas.drawRect(
            0f,
            height - metrics.progressHeight.toFloat(),
            progressWidth,
            height.toFloat(),
            badgePaint
        )
        canvas.restoreToCount(saveCount)
    }

    private companion object {
        private const val PLAYER_PROGRESS_COLOR = 0xFF0088BB.toInt()
    }

    private class OverlayAssets(
        val playCountIcon: Bitmap,
        val danmakuIcon: Bitmap
    ) {
        companion object {
            private val cache = java.util.WeakHashMap<android.content.res.Resources, MutableMap<Int, OverlayAssets>>()

            fun get(context: Context, iconSize: Int): OverlayAssets {
                val resources = context.resources
                synchronized(cache) {
                    val bySize = cache.getOrPut(resources) { mutableMapOf() }
                    return bySize.getOrPut(iconSize) {
                        OverlayAssets(
                            playCountIcon = loadBitmap(context, R.drawable.ic_video_play_count, iconSize),
                            danmakuIcon = loadBitmap(context, R.drawable.ic_video_danmaku, iconSize)
                        )
                    }
                }
            }

            private fun loadBitmap(context: Context, resId: Int, size: Int): Bitmap {
                val drawable = ContextCompat.getDrawable(context, resId)
                    ?: return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                return drawable.toBitmap(size, size)
            }

            private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, width, height)
                draw(canvas)
                return bitmap
            }
        }
    }
}

class VideoCardTextLayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val metrics = LightCardMetrics.get(context)
    private val defaultTitleColor = ContextCompat.getColor(context, R.color.textColor)
    private val ownerTextColor = ContextCompat.getColor(context, R.color.subTextColor)
    private val assets = TextLayerAssets.get(context, metrics, ownerTextColor)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultTitleColor
        textSize = metrics.titleTextSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val ownerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ownerTextColor
        textSize = metrics.ownerTextSize
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF6A11B.toInt()
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = metrics.badgeTextSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val badgeRect = RectF()

    private var titleText: String = ""
    private var titleLines = 2
    private var playing = false
    private var titleColor = defaultTitleColor
    private var ownerText: String = ""
    private var ownerBadgeText: String = ""
    private var showOwnerAvatar = false
    private var showOwner = true
    private var historyTimeText: String = ""
    private var historyDeviceRes: Int = 0
    private var cachedTitleSource = ""
    private var cachedTitleWidth = -1
    private var cachedTitleLines = -1
    private var cachedPlaying = false
    private var cachedDrawLines: List<String> = emptyList()

    fun setTitle(
        text: CharSequence?,
        lines: Int,
        isPlaying: Boolean = false,
        color: Int = defaultTitleColor
    ) {
        val nextText = text?.toString().orEmpty()
        val nextLines = lines.coerceIn(1, 2)
        val layoutMayChange = titleLines != nextLines || playing != isPlaying
        if (titleText == nextText && titleLines == nextLines && playing == isPlaying && titleColor == color) {
            return
        }
        titleText = nextText
        titleLines = nextLines
        playing = isPlaying
        titleColor = color
        titlePaint.color = color
        clearTitleCache()
        if (layoutMayChange) {
            requestLayout()
        } else {
            invalidate()
        }
    }

    fun setTitleColor(color: Int) {
        if (titleColor == color) return
        titleColor = color
        titlePaint.color = color
        invalidate()
    }

    fun setOwner(
        ownerText: CharSequence?,
        showAvatar: Boolean,
        badgeText: CharSequence? = null,
        show: Boolean = true
    ) {
        val nextOwner = ownerText?.toString().orEmpty()
        val nextBadge = badgeText?.toString().orEmpty()
        val layoutMayChange = this.showOwnerAvatar != showAvatar ||
            this.ownerBadgeText.isBlank() != nextBadge.isBlank() ||
            this.showOwner != show
        if (this.ownerText == nextOwner &&
            this.ownerBadgeText == nextBadge &&
            this.showOwnerAvatar == showAvatar &&
            this.showOwner == show
        ) {
            return
        }
        this.ownerText = nextOwner
        this.ownerBadgeText = nextBadge
        this.showOwnerAvatar = showAvatar
        this.showOwner = show
        if (layoutMayChange) {
            requestLayout()
        } else {
            invalidate()
        }
    }

    fun setHistoryTrailing(timeText: CharSequence?, deviceDrawableRes: Int = 0) {
        val nextTime = timeText?.toString().orEmpty()
        val layoutMayChange = historyTimeText.isBlank() != nextTime.isBlank() ||
            historyDeviceRes != deviceDrawableRes
        if (historyTimeText == nextTime && historyDeviceRes == deviceDrawableRes) {
            return
        }
        historyTimeText = nextTime
        historyDeviceRes = deviceDrawableRes
        if (layoutMayChange) {
            requestLayout()
        } else {
            invalidate()
        }
    }

    fun clearHistoryTrailing() {
        setHistoryTrailing("", 0)
    }

    fun firstLineVisibleEnd(source: CharSequence = titleText): Int {
        val drawWidth = titleWidthForLine(0)
        if (drawWidth <= 0 || source.isEmpty()) return source.length
        val availableWidth = (drawWidth - titlePaint.measureText(ELLIPSIS)).coerceAtLeast(0f)
        val count = titlePaint.breakText(source, 0, source.length, true, availableWidth, null)
        return count.coerceIn(1, source.length)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val titleHeight = titleLineHeight() * titleLines
        val ownerHeight = if (hasOwnerContent()) {
            metrics.ownerTopMargin + maxOf(
                ownerTextHeight().toInt(),
                if (showOwnerAvatar && ownerBadgeText.isBlank()) metrics.avatarHeight else 0,
                if (ownerBadgeText.isNotBlank()) badgeHeight().toInt() else 0,
                if (historyDeviceRes != 0) metrics.historyDeviceSize else 0
            )
        } else {
            0
        }
        val desiredHeight = titleHeight + ownerHeight
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTitle(canvas)
        drawOwnerRow(canvas)
    }

    private fun drawTitle(canvas: Canvas) {
        if (titleText.isBlank()) return
        val lineHeight = titleLineHeight()
        val fontMetrics = titlePaint.fontMetrics
        var baseline = -fontMetrics.ascent
        val lines = titleLines(width)
        var iconSpace = 0f
        if (playing) {
            val iconSize = lineHeight.coerceAtMost(metrics.titleIconFallbackSize)
            val iconTop = ((lineHeight - iconSize) / 2f).coerceAtLeast(0f)
            canvas.drawBitmap(assets.playingIcon(), null, RectF(0f, iconTop, iconSize.toFloat(), iconTop + iconSize), null)
            iconSpace = (iconSize + metrics.playingIconMarginEnd).toFloat()
        }
        lines.forEachIndexed { index, line ->
            val x = if (index == 0) iconSpace else 0f
            canvas.drawText(line, x, baseline, titlePaint)
            baseline += lineHeight
        }
    }

    private fun drawOwnerRow(canvas: Canvas) {
        if (!hasOwnerContent()) return
        val top = titleLineHeight() * titleLines + metrics.ownerTopMargin
        val rowHeight = height - top
        val centerY = top + rowHeight / 2f
        var right = width.toFloat()
        if (historyTimeText.isNotBlank()) {
            val textWidth = ownerPaint.measureText(historyTimeText)
            val fm = ownerPaint.fontMetrics
            val baseline = centerY - ownerTextHeight() / 2f - fm.top
            canvas.drawText(historyTimeText, right - textWidth, baseline, ownerPaint)
            right -= textWidth
        }
        if (historyDeviceRes != 0) {
            right -= metrics.historyDeviceMarginEnd
            val iconSize = metrics.historyDeviceSize.toFloat()
            val left = right - iconSize
            val topIcon = centerY - iconSize / 2f
            assets.historyDevice(historyDeviceRes)?.let { bitmap ->
                canvas.drawBitmap(bitmap, null, RectF(left, topIcon, right, topIcon + iconSize), null)
            }
            right = left
        }

        var x = metrics.avatarStart.toFloat()
        if (ownerBadgeText.isNotBlank()) {
            x = drawLeadingBadge(canvas, x, centerY) + metrics.badgeOwnerGap
        } else if (showOwnerAvatar) {
            val iconTop = centerY - assets.ownerIcon.height / 2f
            canvas.drawBitmap(assets.ownerIcon, x, iconTop, null)
            x += metrics.avatarWidth
        }

        if (ownerText.isNotBlank()) {
            val availableWidth = (right - x - metrics.ownerTextMarginEnd).coerceAtLeast(0f)
            if (availableWidth > 0f) {
                val text = TextUtils.ellipsize(ownerText, ownerPaint, availableWidth, TextUtils.TruncateAt.END).toString()
                val fm = ownerPaint.fontMetrics
                val baseline = centerY - ownerTextHeight() / 2f - fm.top
                canvas.drawText(text, x, baseline, ownerPaint)
            }
        }
    }

    private fun drawLeadingBadge(canvas: Canvas, left: Float, centerY: Float): Float {
        val badgeWidth = badgeTextPaint.measureText(ownerBadgeText) + metrics.px8 * 2
        val height = badgeHeight()
        val top = centerY - height / 2f
        badgeRect.set(left, top, left + badgeWidth, top + height)
        canvas.drawRoundRect(badgeRect, metrics.badgeRadius.toFloat(), metrics.badgeRadius.toFloat(), badgePaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(ownerBadgeText, left + metrics.px8, top + metrics.px3 - fm.top, badgeTextPaint)
        return left + badgeWidth
    }

    private fun titleLines(width: Int): List<String> {
        if (width <= 0 || titleText.isBlank()) return emptyList()
        if (cachedTitleSource == titleText &&
            cachedTitleWidth == width &&
            cachedTitleLines == titleLines &&
            cachedPlaying == playing
        ) {
            return cachedDrawLines
        }
        val result = ArrayList<String>(titleLines)
        var start = 0
        while (start < titleText.length && result.size < titleLines) {
            val lastLine = result.size == titleLines - 1
            val lineWidth = titleWidthForLine(result.size).coerceAtLeast(1)
            if (lastLine) {
                result += TextUtils.ellipsize(
                    titleText.subSequence(start, titleText.length),
                    titlePaint,
                    lineWidth.toFloat(),
                    TextUtils.TruncateAt.END
                ).toString()
                break
            }
            val count = titlePaint.breakText(titleText, start, titleText.length, true, lineWidth.toFloat(), null)
            val end = (start + count).coerceIn(start + 1, titleText.length)
            result += titleText.substring(start, end).trimEnd()
            start = end
            while (start < titleText.length && titleText[start].isWhitespace()) start++
        }
        cachedTitleSource = titleText
        cachedTitleWidth = width
        cachedTitleLines = titleLines
        cachedPlaying = playing
        cachedDrawLines = result
        return result
    }

    private fun titleWidthForLine(index: Int): Int {
        val iconSpace = if (playing && index == 0) {
            titleLineHeight().coerceAtMost(metrics.titleIconFallbackSize) + metrics.playingIconMarginEnd
        } else {
            0
        }
        return (width - iconSpace).coerceAtLeast(0)
    }

    private fun hasOwnerContent(): Boolean {
        return showOwner && (ownerText.isNotBlank() || ownerBadgeText.isNotBlank() || showOwnerAvatar ||
            historyTimeText.isNotBlank() || historyDeviceRes != 0)
    }

    private fun titleLineHeight(): Int {
        val fm = titlePaint.fontMetrics
        return ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
    }

    private fun ownerTextHeight(): Float {
        val fm = ownerPaint.fontMetrics
        return ceil((fm.bottom - fm.top).toDouble()).toFloat()
    }

    private fun badgeHeight(): Float {
        val fm = badgeTextPaint.fontMetrics
        return ceil((fm.bottom - fm.top).toDouble()).toFloat() + metrics.px3 * 2
    }

    private fun clearTitleCache() {
        cachedTitleSource = ""
        cachedTitleWidth = -1
        cachedDrawLines = emptyList()
    }

    private class TextLayerAssets(
        private val context: Context,
        private val metrics: LightCardMetrics,
        private val tintColor: Int,
        val ownerIcon: Bitmap,
        private val historyIcons: MutableMap<Int, Bitmap> = mutableMapOf()
    ) {
        private var playingIconCache: Bitmap? = null

        fun playingIcon(): Bitmap {
            playingIconCache?.let { return it }
            return loadBitmap(
                context = context,
                resId = R.drawable.playing,
                width = metrics.titleIconFallbackSize,
                height = metrics.titleIconFallbackSize
            ).also { playingIconCache = it }
        }

        fun historyDevice(resId: Int): Bitmap? {
            if (resId == 0) return null
            historyIcons[resId]?.let { return it }
            return loadTintedBitmap(
                context = context,
                resId = resId,
                width = metrics.historyDeviceSize,
                height = metrics.historyDeviceSize,
                tintColor = tintColor
            ).also { historyIcons[resId] = it }
        }

        companion object {
            private val cache = java.util.WeakHashMap<android.content.res.Resources, MutableMap<String, TextLayerAssets>>()

            fun get(context: Context, metrics: LightCardMetrics, tintColor: Int): TextLayerAssets {
                val resources = context.resources
                val key = "${metrics.avatarWidth}:${metrics.avatarHeight}:${metrics.titleIconFallbackSize}:$tintColor"
                synchronized(cache) {
                    val byKey = cache.getOrPut(resources) { mutableMapOf() }
                    return byKey.getOrPut(key) {
                        TextLayerAssets(
                            context = context.applicationContext,
                            metrics = metrics,
                            tintColor = tintColor,
                            ownerIcon = loadTintedBitmap(
                                context = context,
                                resId = R.drawable.ic_video_up,
                                width = metrics.avatarWidth - metrics.px10,
                                height = metrics.avatarHeight,
                                tintColor = tintColor
                            )
                        )
                    }
                }
            }

            private fun loadTintedBitmap(
                context: Context,
                resId: Int,
                width: Int,
                height: Int,
                tintColor: Int
            ): Bitmap {
                val drawable = ContextCompat.getDrawable(context, resId)?.mutate()
                    ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                drawable.setTint(tintColor)
                return drawable.toBitmap(width, height)
            }

            private fun loadBitmap(context: Context, resId: Int, width: Int, height: Int): Bitmap {
                val drawable = ContextCompat.getDrawable(context, resId)
                    ?: return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                return drawable.toBitmap(width, height)
            }

            private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                setBounds(0, 0, width, height)
                draw(canvas)
                return bitmap
            }
        }
    }

    private companion object {
        private const val ELLIPSIS = "\u2026"
    }
}

private class FlatVideoLightCardLayout @JvmOverloads constructor(
    context: Context,
    private val metrics: LightCardMetrics = LightCardMetrics.get(context),
    private val perfSource: String = "VideoLightCard.flat",
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    lateinit var imageView: View
    lateinit var progressBar: View
    lateinit var coverMetaOverlay: View
    lateinit var textLayer: View

    private var coverLeft = 0
    private var coverTop = 0
    private var coverWidth = 0
    private var coverHeight = 0
    private var titleTop = 0
    private var titleRowHeight = 0
    private var lastContentWidth = -1
    private var lastDesiredHeight = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (widthSize - metrics.horizontalMargin * 2).coerceAtLeast(0)
        if (canReuseMeasuredChildren(widthSize, contentWidth)) {
            setMeasuredDimension(
                resolveSize(widthSize, widthMeasureSpec),
                resolveSize(lastDesiredHeight, heightMeasureSpec)
            )
            VideoCardPerfLogger.recordPhase(perfSource, "measure_reuse", SystemClock.elapsedRealtimeNanos() - startNs)
            return
        }
        coverWidth = contentWidth
        coverHeight = contentWidth * 9 / 16

        measureExact(imageView, coverWidth, coverHeight)
        measureExact(coverMetaOverlay, coverWidth, coverHeight)
        measureExactWidth(textLayer, contentWidth)
        titleRowHeight = textLayer.measuredHeight

        coverLeft = metrics.horizontalMargin
        coverTop = metrics.paddingTop
        titleTop = coverTop + coverHeight + metrics.titleTopMargin

        val desiredHeight = titleTop + titleRowHeight + metrics.paddingBottom
        lastContentWidth = contentWidth
        lastDesiredHeight = desiredHeight
        setMeasuredDimension(
            resolveSize(widthSize, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
        VideoCardPerfLogger.recordPhase(perfSource, "measure", SystemClock.elapsedRealtimeNanos() - startNs)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val coverRight = coverLeft + coverWidth
        val coverBottom = coverTop + coverHeight

        imageView.layout(coverLeft, coverTop, coverRight, coverBottom)
        coverMetaOverlay.layout(coverLeft, coverTop, coverRight, coverBottom)

        textLayer.layout(coverLeft, titleTop, coverRight, titleTop + textLayer.measuredHeight)
        VideoCardPerfLogger.recordPhase(perfSource, "layout", SystemClock.elapsedRealtimeNanos() - startNs)
    }

    private fun canReuseMeasuredChildren(widthSize: Int, contentWidth: Int): Boolean {
        if (widthSize <= 0 || lastContentWidth != contentWidth || lastDesiredHeight <= 0) {
            return false
        }
        return !coverMetaOverlay.isLayoutRequested &&
            !textLayer.isLayoutRequested
    }

    private fun measureExact(child: View, width: Int, height: Int) {
        child.measure(exact(width), exact(height))
    }

    private fun measureExactWidth(child: View, width: Int) {
        if (child.visibility == GONE) {
            measureExact(child, 0, 0)
            return
        }
        child.measure(exact(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams?): LayoutParams =
        LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams?): Boolean =
        params is LayoutParams

    private fun exact(size: Int): Int = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
}

private class LightCardMetrics private constructor(context: Context) {
    val cardRadius = dimen(context, R.dimen.px15)
    val paddingTop = dimen(context, R.dimen.px20)
    val paddingBottom = dimen(context, R.dimen.px15)
    val horizontalMargin = dimen(context, R.dimen.px15)
    val titleTopMargin = dimen(context, R.dimen.px10)
    val ownerTopMargin = dimen(context, R.dimen.px5)
    val avatarStart = dimen(context, R.dimen.px2)
    val ownerTextMarginEnd = dimen(context, R.dimen.px8)
    val coverGradientHeight = dimen(context, R.dimen.px70)
    val progressHeight = dimen(context, R.dimen.px3)
    val metaIconSize = dimen(context, R.dimen.px30)
    val coverMetaStart = dimen(context, R.dimen.px10)
    val coverMetaBottom = dimen(context, R.dimen.px10)
    val metaTextStart = dimen(context, R.dimen.px5)
    val metaGroupGap = dimen(context, R.dimen.px10)
    val durationEnd = dimen(context, R.dimen.px20)
    val durationBottom = dimen(context, R.dimen.px10)
    val badgeTop = dimen(context, R.dimen.px10)
    val badgeEnd = dimen(context, R.dimen.px10)
    val badgeGap = dimen(context, R.dimen.px10)
    val badgeRadius = dimen(context, R.dimen.px5)
    val px3 = dimen(context, R.dimen.px3)
    val px4 = dimen(context, R.dimen.px4)
    val px8 = dimen(context, R.dimen.px8)
    val px10 = dimen(context, R.dimen.px10)
    val avatarWidth = dimen(context, R.dimen.px40)
    val avatarHeight = dimen(context, R.dimen.px25)
    val badgeOwnerGap = dimen(context, R.dimen.px6)
    val historyDeviceSize = dimen(context, R.dimen.px30)
    val historyDeviceMarginEnd = dimen(context, R.dimen.px5)
    val playingIconMarginEnd = dp(context, 4)
    val titleIconFallbackSize = dimen(context, R.dimen.px31)
    val titleTextSize = dimenF(context, R.dimen.px31)
    val ownerTextSize = dimenF(context, R.dimen.px22)
    val metaTextSize = dimenF(context, R.dimen.px22)
    val badgeTextSize = dimenF(context, R.dimen.px20)

    private fun dimen(context: Context, resId: Int): Int = context.resources.getDimensionPixelSize(resId)

    private fun dimenF(context: Context, resId: Int): Float = context.resources.getDimension(resId)

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    companion object {
        private val cache = java.util.WeakHashMap<android.content.res.Resources, LightCardMetrics>()

        fun get(context: Context): LightCardMetrics {
            val resources = context.resources
            synchronized(cache) {
                return cache.getOrPut(resources) { LightCardMetrics(context.applicationContext ?: context) }
            }
        }
    }
}
