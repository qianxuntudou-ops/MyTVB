package com.mytvb.core.ui.video

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
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.ui.base.UiTextScale
import pl.droidsonroids.gif.GifDrawable
import kotlin.math.ceil

/**
 * 封面比例。全 App 视频卡默认 16:9 横版；影视/番剧这类竖版海报素材（央视片库
 * 178×226）用 [POSTER_4_5]，按竖版裁切才不会被 centerCrop 切掉上下主体。
 */
enum class VideoCardCoverRatio(val aspectWidth: Int, val aspectHeight: Int) {
    LANDSCAPE_16_9(16, 9),
    POSTER_4_5(4, 5)
}

object VideoLightCardFactory {

    fun create(
        parent: ViewGroup,
        source: String = "VideoLightCard.flat",
        coverRatio: VideoCardCoverRatio = VideoCardCoverRatio.LANDSCAPE_16_9
    ): VideoCardViews {
        val context = parent.context
        val metrics = LightCardMetrics.get(context)

        val root = FlatVideoLightCardLayout(context, metrics, source, coverRatio).apply {
            id = R.id.click_view
            isClickable = true
            isFocusable = true
            // 卡片刻意不设 focusableInTouchMode：触摸/鼠标按下会把焦点抢到被按的卡片上，
            // 列表滑动时焦点随每次按下的位置乱跳（滚动加载后 pos 一路后跑）。
            // touch mode 下的程序化聚焦由 RecyclerViewFocusOperator 在入口统一挡掉（touch mode 让路）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
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

    private var metrics = LightCardMetrics.get(context)
    private var assets = OverlayAssets.get(context, metrics.metaIconSize)
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
    private val iconDstRect = RectF()
    private val iconScalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var playCountText: CharSequence = ""
    private var danmakuText: CharSequence = ""
    private var durationText: CharSequence = ""
    private var showPlayCount = false
    private var showDanmakuCount = false
    private var showInteractionBadge = false
    private var showChargeBadge = false
    private var typeBadgeText: String = ""
    private var typeBadgeColor: Int = TYPE_BADGE_COLOR_DEFAULT
    private var progressMax = 100
    private var progressValue = 0
    private var showProgress = false

    /**
     * 角标行整体等比系数：界面缩放大档下列数粒度有限（卡宽×1.25 撑不起文字×1.5），
     * 需求宽超卡宽时整行（字号/图标/间距）等比缩到恰好放下，信息完整不叠压；
     * 100% 时恒为 1，走原始路径与历史视觉逐像素一致。
     */
    private var badgeFitScale = 1f
    private var badgeFitDirty = true

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        badgeFitDirty = true
        rebuildCoverGradient(w, h)
    }

    private fun rebuildCoverGradient(w: Int, h: Int) {
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

    /**
     * 密度/档位漂移自愈（issue#102）：字号在 View 构造时固化进 Paint，ViewHolder
     * 复用永不重读 metrics——运行中系统重置 displayMetrics（电视分辨率/系统栏变化
     * 等）窗口期构造的卡片会永久偏离，回收池复用即同屏大小不一且不自愈。父布局在
     * 测到新 metrics 时调用这里整体换档。
     */
    internal fun applyMetrics(next: LightCardMetrics) {
        if (next === metrics) return
        metrics = next
        assets = OverlayAssets.get(context, next.metaIconSize)
        metaPaint.textSize = next.metaTextSize
        badgeTextPaint.textSize = next.badgeTextSize
        badgeFitDirty = true
        if (width > 0 && height > 0) rebuildCoverGradient(width, height)
        invalidate()
    }

    fun bind(
        playCountText: CharSequence? = this.playCountText,
        showPlayCount: Boolean = this.showPlayCount,
        danmakuText: CharSequence? = this.danmakuText,
        showDanmakuCount: Boolean = this.showDanmakuCount,
        durationText: CharSequence? = this.durationText,
        showInteractionBadge: Boolean = this.showInteractionBadge,
        showChargeBadge: Boolean = this.showChargeBadge,
        typeBadgeText: CharSequence? = this.typeBadgeText,
        typeBadgeColor: Int = this.typeBadgeColor
    ) {
        val nextPlayCount = playCountText?.toString().orEmpty()
        val nextDanmaku = danmakuText?.toString().orEmpty()
        val nextDuration = durationText?.toString().orEmpty()
        val nextTypeBadge = typeBadgeText?.toString().orEmpty()
        if (TextUtils.equals(this.playCountText, nextPlayCount) &&
            TextUtils.equals(this.danmakuText, nextDanmaku) &&
            TextUtils.equals(this.durationText, nextDuration) &&
            TextUtils.equals(this.typeBadgeText, nextTypeBadge) &&
            this.typeBadgeColor == typeBadgeColor &&
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
        this.typeBadgeText = nextTypeBadge
        this.typeBadgeColor = typeBadgeColor
        this.showPlayCount = showPlayCount
        this.showDanmakuCount = showDanmakuCount
        this.showInteractionBadge = showInteractionBadge
        this.showChargeBadge = showChargeBadge
        badgeFitDirty = true
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

    /** 按当前文本内容与卡宽计算整行等比系数；内容或尺寸变化时重算。 */
    private fun ensureBadgeFitScale() {
        if (!badgeFitDirty || width <= 0) return
        var contentWidth = 0f
        if (showPlayCount && playCountText.isNotBlank()) {
            contentWidth += metrics.metaIconSize + metrics.metaTextStart +
                metaPaint.measureText(playCountText.toString()) + metrics.metaGroupGap
        }
        if (showDanmakuCount && danmakuText.isNotBlank()) {
            contentWidth += metrics.metaIconSize + metrics.metaTextStart +
                metaPaint.measureText(danmakuText.toString())
        }
        if (durationText.isNotBlank()) {
            contentWidth += metrics.px10 + metaPaint.measureText(durationText.toString()) + metrics.px8 * 2
        }
        val need = metrics.coverMetaStart + contentWidth + metrics.durationEnd
        // 边距与组间安全距不参与缩放（缩了会显得挤），只缩内容（字号/图标/组内距）
        val fixed = metrics.coverMetaStart + metrics.durationEnd + metrics.px10
        val contentOnly = (contentWidth - metrics.px10).coerceAtLeast(1f)
        badgeFitScale = if (need > width && need > 0f) {
            ((width - fixed) / contentOnly).coerceIn(0.5f, 1f)
        } else {
            1f
        }
        badgeFitDirty = false
    }

    private fun drawIconScaled(canvas: Canvas, icon: Bitmap, left: Float, top: Float, size: Float) {
        if (badgeFitScale == 1f) {
            canvas.drawBitmap(icon, left, top, null)
        } else {
            iconDstRect.set(left, top, left + size, top + size)
            canvas.drawBitmap(icon, null, iconDstRect, iconScalePaint)
        }
    }

    private fun drawMetaLine(canvas: Canvas) {
        ensureBadgeFitScale()
        val savedTextSize = metaPaint.textSize
        if (badgeFitScale != 1f) {
            metaPaint.textSize = savedTextSize * badgeFitScale
        }
        val iconSize = metrics.metaIconSize * badgeFitScale
        // 行中心线：底部边距上方半个行高（行高以图标计），图标/文字/时长统一居中到这条线
        val centerY = height - metrics.coverMetaBottom - iconSize / 2f
        val iconTop = centerY - iconSize / 2f
        var x = metrics.coverMetaStart.toFloat()
        if (showPlayCount && playCountText.isNotBlank()) {
            drawIconScaled(canvas, assets.playCountIcon, x, iconTop, iconSize)
            x += iconSize + metrics.metaTextStart * badgeFitScale
            x = drawMetaText(canvas, playCountText, x, centerY) + metrics.metaGroupGap * badgeFitScale
        }
        if (showDanmakuCount && danmakuText.isNotBlank()) {
            drawIconScaled(canvas, assets.danmakuIcon, x, iconTop, iconSize)
            x += iconSize + metrics.metaTextStart * badgeFitScale
            drawMetaText(canvas, danmakuText, x, centerY)
        }
        if (badgeFitScale != 1f) {
            metaPaint.textSize = savedTextSize
        }
    }

    private fun drawMetaText(canvas: Canvas, text: CharSequence, x: Float, centerY: Float): Float {
        val fm = metaPaint.fontMetrics
        val baseline = centerY - (fm.top + fm.bottom) / 2f
        canvas.drawText(text.toString(), x, baseline, metaPaint)
        return x + metaPaint.measureText(text.toString())
    }

    private fun drawDuration(canvas: Canvas) {
        if (durationText.isBlank()) return
        ensureBadgeFitScale()
        val savedTextSize = metaPaint.textSize
        if (badgeFitScale != 1f) {
            metaPaint.textSize = savedTextSize * badgeFitScale
        }
        val text = durationText.toString()
        val fm = metaPaint.fontMetrics
        val textWidth = metaPaint.measureText(text)
        val boxWidth = textWidth + metrics.px8 * 2 * badgeFitScale
        val left = width - metrics.durationEnd - boxWidth
        // 与左侧角标组共用同一行中心线（按图标半高定位），保证三组上下对齐
        val iconSize = metrics.metaIconSize * badgeFitScale
        val centerY = height - metrics.coverMetaBottom - iconSize / 2f
        val baseline = centerY - (fm.top + fm.bottom) / 2f
        canvas.drawText(text, left + metrics.px8 * badgeFitScale, baseline, metaPaint)
        if (badgeFitScale != 1f) {
            metaPaint.textSize = savedTextSize
        }
    }

    private fun drawBadges(canvas: Canvas) {
        var right = width - metrics.badgeEnd.toFloat()
        val top = metrics.badgeTop.toFloat()
        if (showChargeBadge) {
            right = drawBadge(canvas, context.getString(R.string.core_badge_charge_exclusive), right, top, 0xFFF6A11B.toInt()) - metrics.badgeGap
        }
        if (showInteractionBadge) {
            right = drawBadge(canvas, context.getString(R.string.core_badge_interaction), right, top, 0xFFFB7299.toInt()) - metrics.badgeGap
        }
        // 类型角标（历史记录「国创/电视剧/番剧…」与直播「直播中/未开播」），
        // 对齐官方：粉底白字（未开播灰底），排在功能角标左侧
        if (typeBadgeText.isNotBlank()) {
            drawBadge(canvas, typeBadgeText, right, top, typeBadgeColor)
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
        // 官方历史记录观看进度条同款 B 站粉
        private const val PLAYER_PROGRESS_COLOR = 0xFFFB7299.toInt()
        private val TYPE_BADGE_COLOR_DEFAULT = 0xFFFB7299.toInt()
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

    private var metrics = LightCardMetrics.get(context)
    private val defaultTitleColor = ContextCompat.getColor(context, R.color.textColor)
    private val ownerTextColor = ContextCompat.getColor(context, R.color.subTextColor)
    private var assets = TextLayerAssets.get(context, metrics, ownerTextColor)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultTitleColor
        textSize = metrics.titleTextSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val ownerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ownerTextColor
        textSize = metrics.ownerTextSize
    }
    // 官方推荐理由标签配色（对齐官方 App：暖橙深底 + 橙红字，无描边）
    private val badgeAccentColor = 0xFFFF8024.toInt()
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FF8024
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badgeAccentColor
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

    // --- 焦点卡长标题垂直滚动（两行装不下时滚动露出完整标题，失焦即停并复位）---
    private var cachedFullSource = ""
    private var cachedFullWidth = -1
    private var cachedFullPlaying = false
    private var cachedFullDrawLines: List<String> = emptyList()
    private var titleScrollRunning = false
    private var titleScrollOffset = 0f
    private var titleScrollCycleStartElapsedMs = 0L

    private val cardFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        setTitleScrollEnabled(isFocusOnThisCard(newFocus))
    }

    private val titleScrollRunnable = Runnable { tickTitleScroll() }

    // “正在播放” GIF 动画。GIF 在自定义 Canvas 绘制模式下默认只画首帧，
    // 这里持有 GifDrawable 并通过它的帧回调驱动重绘，让动图真正动起来。
    private var playingDrawable: GifDrawable? = null
    private val playingAnimCallback by lazy {
        object : Drawable.Callback {
            override fun invalidateDrawable(d: Drawable) {
                // GIF 每推进一帧会回调这里，触发 View 重绘以画出下一帧。
                invalidate()
            }

            override fun scheduleDrawable(d: Drawable, what: Runnable, t: Long) {}
            override fun unscheduleDrawable(d: Drawable, what: Runnable) {}
        }
    }

    private fun ensurePlayingDrawable(): GifDrawable? {
        playingDrawable?.let { return it }
        val d = runCatching { GifDrawable(resources, R.drawable.playing) }.getOrNull() ?: return null
        d.callback = playingAnimCallback
        playingDrawable = d
        return d
    }

    private fun updatePlayingAnimation() {
        val d = ensurePlayingDrawable()
        if (playing && isAttachedToWindow) {
            d?.start()
        } else {
            d?.stop()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePlayingAnimation()
        viewTreeObserver.addOnGlobalFocusChangeListener(cardFocusListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playingDrawable?.stop()
        setTitleScrollEnabled(false)
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalFocusChangeListener(cardFocusListener)
        }
    }

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
        // 换标题/播放态变化：复位滚动（新标题若仍超长且焦点在本卡，下一帧焦点
        // 监听不会再来，这里按当前焦点态重新评估）
        setTitleScrollEnabled(isFocusOnThisCard(isFocusedCardView()))
        clearTitleCache()
        if (layoutMayChange) {
            requestLayout()
        } else {
            invalidate()
        }
        updatePlayingAnimation()
    }

    fun setTitleColor(color: Int) {
        if (titleColor == color) return
        titleColor = color
        titlePaint.color = color
        invalidate()
    }

    /** 密度/档位漂移自愈（issue#102），同 [VideoCoverMetaOverlayView.applyMetrics]。 */
    internal fun applyMetrics(next: LightCardMetrics) {
        if (next === metrics) return
        metrics = next
        titlePaint.textSize = next.titleTextSize
        ownerPaint.textSize = next.ownerTextSize
        badgeTextPaint.textSize = next.badgeTextSize
        assets = TextLayerAssets.get(context, next, ownerTextColor)
        setTitleScrollEnabled(false)
        clearTitleCache()
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
        if (titleScrollRunning) {
            // 滚动态：裁剪到标题可视区（owner 行不参与），完整分行随偏移上移
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), (lineHeight * titleLines).toFloat())
            canvas.translate(0f, -titleScrollOffset)
        }
        var baseline = -fontMetrics.ascent
        val lines = if (titleScrollRunning) fullTitleLines(width) else titleLines(width)
        var iconSpace = 0f
        if (playing) {
            val iconSize = lineHeight.coerceAtMost(metrics.titleIconFallbackSize)
            val iconTop = ((lineHeight - iconSize) / 2f).coerceAtLeast(0f)
            val d = playingDrawable
            if (d != null) {
                d.setBounds(0, iconTop.toInt(), iconSize, (iconTop + iconSize).toInt())
                d.draw(canvas)
            } else {
                // GIF 解码失败时退化到静态首帧 Bitmap
                canvas.drawBitmap(assets.playingIcon(), null, RectF(0f, iconTop, iconSize.toFloat(), iconTop + iconSize), null)
            }
            iconSpace = (iconSize + metrics.playingIconMarginEnd).toFloat()
        }
        lines.forEachIndexed { index, line ->
            val x = if (index == 0) iconSpace else 0f
            canvas.drawText(line, x, baseline, titlePaint)
            baseline += lineHeight
        }
        if (titleScrollRunning) {
            canvas.restore()
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

    /** 官方「已关注」形态：圆形对勾图标 + 橙红文字，无底板。 */
    private fun drawFollowedBadge(canvas: Canvas, left: Float, centerY: Float): Float {
        val iconSize = badgeTextPaint.textSize
        val iconTop = centerY - iconSize / 2f
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeAccentColor }
        canvas.drawCircle(left + iconSize / 2f, centerY, iconSize / 2f, circlePaint)
        // 白色对勾（含笔画宽度的外接框与圆同心：x±0.64s / y±0.50s，到圆周留白均匀）
        val check = Path().apply {
            val s = iconSize / 2f
            moveTo(left + iconSize / 2f - s * 0.48f, centerY + s * 0.02f)
            lineTo(left + iconSize / 2f - s * 0.10f, centerY + s * 0.34f)
            lineTo(left + iconSize / 2f + s * 0.48f, centerY - s * 0.34f)
        }
        canvas.drawPath(
            check,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = iconSize * 0.16f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = 0xFFFFFFFF.toInt()
            }
        )
        val textX = left + iconSize + metrics.px4
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(FOLLOWED_LABEL, textX, centerY - (fm.top + fm.bottom) / 2f, badgeTextPaint)
        return textX + badgeTextPaint.measureText(FOLLOWED_LABEL)
    }

    /** 官方推荐理由形态：深色半透明底 + 橙红描边 + 橙红字。 */
    private fun drawOutlinedBadge(canvas: Canvas, text: String, left: Float, centerY: Float): Float {
        val width = badgeTextPaint.measureText(text) + metrics.px8 * 2
        val height = badgeHeight()
        val top = centerY - height / 2f
        badgeRect.set(left, top, left + width, top + height)
        canvas.drawRoundRect(badgeRect, metrics.badgeRadius.toFloat(), metrics.badgeRadius.toFloat(), badgeFillPaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(text, left + metrics.px8, top + metrics.px3 - fm.top, badgeTextPaint)
        return left + width
    }

    private fun drawLeadingBadge(canvas: Canvas, left: Float, centerY: Float): Float {
        // badgeText 按 "|" 分段依次排布；「已关注」走图标+字无底板，其余走描边胶囊
        var x = left
        ownerBadgeText.split("|").forEach { segment ->
            if (segment.isBlank()) return@forEach
            if (x > left) x += metrics.px4
            x = if (segment == FOLLOWED_LABEL) {
                drawFollowedBadge(canvas, x, centerY)
            } else {
                drawOutlinedBadge(canvas, segment, x, centerY)
            }
        }
        return x
    }

    private fun titleLines(width: Int): List<String> {        if (width <= 0 || titleText.isBlank()) return emptyList()
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

    /**
     * 完整分行（不限行数、末行不截断），供焦点滚动绘制；与 [titleLines] 的前
     * titleLines 行使用同一 breakText 与行宽，滚动起点与静止态视觉无跳变。
     */
    private fun fullTitleLines(width: Int): List<String> {
        if (width <= 0 || titleText.isBlank()) return emptyList()
        if (cachedFullSource == titleText && cachedFullWidth == width && cachedFullPlaying == playing) {
            return cachedFullDrawLines
        }
        val result = ArrayList<String>(titleLines + 1)
        var start = 0
        while (start < titleText.length) {
            val lineWidth = titleWidthForLine(result.size).coerceAtLeast(1)
            val count = titlePaint.breakText(titleText, start, titleText.length, true, lineWidth.toFloat(), null)
            val end = (start + count).coerceIn(start + 1, titleText.length)
            result += titleText.substring(start, end).trimEnd()
            start = end
            while (start < titleText.length && titleText[start].isWhitespace()) start++
        }
        cachedFullSource = titleText
        cachedFullWidth = width
        cachedFullPlaying = playing
        cachedFullDrawLines = result
        return result
    }

    /** 焦点是否落在本卡片（文字层的父链上任意一环持有焦点）。 */
    private fun isFocusOnThisCard(focused: View?): Boolean {
        val cardRoot = parent as? View ?: return false
        var v: View? = focused
        while (v != null) {
            if (v === cardRoot) return true
            v = v.parent as? View
        }
        return false
    }

    private fun isFocusedCardView(): View? =
        if (isAttachedToWindow) rootView.findFocus() else null

    /**
     * 焦点态长标题垂直滚动：先静止读首屏 [TITLE_SCROLL_START_HOLD_MS]，
     * 线性滚到末尾，停留 [TITLE_SCROLL_END_HOLD_MS] 后回顶循环；失焦/换标题/
     * 离屏即停并复位偏移。仅焦点卡片一张在滚，帧驱动重绘本 View 自身。
     */
    private fun setTitleScrollEnabled(enabled: Boolean) {
        if (enabled) {
            if (titleScrollRunning || !isAttachedToWindow) return
            if (fullTitleLines(width).size <= titleLines) return
            titleScrollRunning = true
            titleScrollOffset = 0f
            titleScrollCycleStartElapsedMs = SystemClock.elapsedRealtime()
            postOnAnimation(titleScrollRunnable)
        } else if (titleScrollRunning) {
            titleScrollRunning = false
            removeCallbacks(titleScrollRunnable)
            titleScrollOffset = 0f
            invalidate()
        }
    }

    private fun tickTitleScroll() {
        if (!titleScrollRunning || !isAttachedToWindow) {
            titleScrollRunning = false
            return
        }
        val lineHeight = titleLineHeight()
        val fullLines = fullTitleLines(width)
        val maxScrollPx = (fullLines.size - titleLines).coerceAtLeast(0) * lineHeight
        if (maxScrollPx <= 0) {
            setTitleScrollEnabled(false)
            return
        }
        val scrollDurationMs = (maxScrollPx / TITLE_SCROLL_SPEED_PX_PER_MS).toLong()
            .coerceAtLeast(TITLE_SCROLL_MIN_DURATION_MS)
        val cycleMs = TITLE_SCROLL_START_HOLD_MS + scrollDurationMs + TITLE_SCROLL_END_HOLD_MS
        var elapsed = SystemClock.elapsedRealtime() - titleScrollCycleStartElapsedMs
        while (elapsed >= cycleMs) {
            elapsed -= cycleMs
            titleScrollCycleStartElapsedMs += cycleMs
        }
        titleScrollOffset = when {
            elapsed < TITLE_SCROLL_START_HOLD_MS -> 0f
            elapsed < TITLE_SCROLL_START_HOLD_MS + scrollDurationMs ->
                (elapsed - TITLE_SCROLL_START_HOLD_MS).toFloat() / scrollDurationMs.toFloat() * maxScrollPx
            else -> maxScrollPx.toFloat()
        }
        postInvalidateOnAnimation()
        postOnAnimation(titleScrollRunnable)
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
        cachedFullSource = ""
        cachedFullWidth = -1
        cachedFullDrawLines = emptyList()
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

        /** 与 VideoAdapter.badgeTextFor 的已关注文案保持一致（分段绘制的形态判定键）。 */
        const val FOLLOWED_LABEL = "已关注"

        /** 焦点标题滚动：起点/终点静止时长与速度（px/ms，约 0.4~0.5s 滚一行） */
        private const val TITLE_SCROLL_START_HOLD_MS = 800L
        private const val TITLE_SCROLL_END_HOLD_MS = 1600L
        private const val TITLE_SCROLL_SPEED_PX_PER_MS = 1f / 14f
        private const val TITLE_SCROLL_MIN_DURATION_MS = 400L
    }
}

private class FlatVideoLightCardLayout @JvmOverloads constructor(
    context: Context,
    private var metrics: LightCardMetrics = LightCardMetrics.get(context),
    private val perfSource: String = "VideoLightCard.flat",
    private val coverRatio: VideoCardCoverRatio = VideoCardCoverRatio.LANDSCAPE_16_9,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    lateinit var imageView: View
    lateinit var progressBar: View
    lateinit var coverMetaOverlay: VideoCoverMetaOverlayView
    lateinit var textLayer: VideoCardTextLayerView

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
        // 密度/档位漂移自愈（issue#102）：字号固化于构造，复用路径不会重读 metrics；
        // 系统重置 displayMetrics 的窗口期构造的卡片会永久偏离。卡片是纯代码构造，
        // XML inflate 的密度守卫（UiTextScaleFactory）罩不到，只能在 measure 总入口
        // 校验快照（LightCardMetrics.get 带缓存与快照校验，未漂移时纳秒级返回）。
        val fresh = LightCardMetrics.get(context)
        // 必须按快照值判漂移而非实例同一性：get 缓存是 WeakHashMap，滚动中的 GC
        // 会清掉缓存条目并重建一个值相同的新实例，实例比较会把这种重建误判为漂移，
        // 每次都走无意义的 assets 重建并刷屏日志，劣化滚动性能。全部派生值由
        // scale+density 两输入决定，二者相等即整卡绘制参数相等。
        if (fresh.scaleSnapshot != metrics.scaleSnapshot || fresh.densitySnapshot != metrics.densitySnapshot) {
            AppLog.w(
                TAG,
                "metrics drift healed[$perfSource]: density ${metrics.densitySnapshot}->${fresh.densitySnapshot} " +
                    "textScale ${metrics.scaleSnapshot}->${fresh.scaleSnapshot} " +
                    "titlePx ${metrics.titleTextSize}->${fresh.titleTextSize}"
            )
            metrics = fresh
            coverMetaOverlay.applyMetrics(fresh)
            textLayer.applyMetrics(fresh)
            lastContentWidth = -1
            lastDesiredHeight = -1
        }
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
        coverHeight = contentWidth * coverRatio.aspectHeight / coverRatio.aspectWidth

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

    private companion object {
        private const val TAG = "VideoCard"
    }
}

internal class LightCardMetrics private constructor(context: Context) {
    val cardRadius = dimen(context, R.dimen.px15)
    val paddingTop = dimen(context, R.dimen.px20)
    val paddingBottom = dimen(context, R.dimen.px15)
    val horizontalMargin = dimen(context, R.dimen.px15)
    val titleTopMargin = dimen(context, R.dimen.px10)
    val ownerTopMargin = dimen(context, R.dimen.px5)
    val avatarStart = dimen(context, R.dimen.px2)
    val ownerTextMarginEnd = dimen(context, R.dimen.px8)
    val coverGradientHeight = dimen(context, R.dimen.px70)
    val progressHeight = dimen(context, R.dimen.px4)
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
    // 设备图标与左上 UP 角标视觉等高（原 30dip 比 UP 图标大 35%，右组显得"大一圈"）
    val historyDeviceSize = dimen(context, R.dimen.px22)
    val historyDeviceMarginEnd = dimen(context, R.dimen.px5)
    val playingIconMarginEnd = dp(context, 4)
    val titleIconFallbackSize = dimen(context, R.dimen.px31)
    // 卡片是自绘文字，跟随全局 UI 文字缩放（onMeasure 由字号推导高度，自动跟随）
    val scaleSnapshot = UiTextScale.scale()
    val densitySnapshot = context.resources.displayMetrics.density
    val titleTextSize = dimenF(context, R.dimen.px31) * scaleSnapshot
    val ownerTextSize = dimenF(context, R.dimen.px22) * UiTextScale.scale()
    val metaTextSize = dimenF(context, R.dimen.px22) * UiTextScale.scale()
    val badgeTextSize = dimenF(context, R.dimen.px20) * UiTextScale.scale()

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
            val scale = UiTextScale.scale()
            val density = resources.displayMetrics.density
            synchronized(cache) {
                // Resources 实例在 Activity recreate 后可能复用，档位/密度变化时按快照失效重建
                cache[resources]?.takeIf { it.scaleSnapshot == scale && it.densitySnapshot == density }
                    ?: cache.remove(resources)
                return cache.getOrPut(resources) { LightCardMetrics(context.applicationContext ?: context) }
            }
        }
    }
}
