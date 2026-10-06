package com.mytvb.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Outline
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.mytvb.R
import com.mytvb.model.video.Dimension
import com.mytvb.model.video.VideoModel
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.base.BaseVideoViewHolder
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.video.VideoLightCardFactory
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.ui.dialog.VideoCardMenuDialog
import java.util.concurrent.atomic.AtomicInteger

class VideoAdapter(
    private val displayStyle: DisplayStyle = DisplayStyle.DEFAULT,
    private val itemWidthPx: Int? = null,
    private val onItemClick: (VideoModel) -> Unit = {},
    onTopEdgeUp: (() -> Boolean)? = null,
    onBottomEdgeDown: (() -> Boolean)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onRightEdge: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null,
    private val detectPortraitFromCover: Boolean = true,
    private val fastFirstScreenCovers: Boolean = false,
    private val fastFirstScreenCoverCount: Int = 8
) : BaseVideoAdapter<VideoModel, VideoAdapter.VideoViewHolder>() {

    var currentPlayingAid: Long = 0

    companion object {
        private const val VIEW_TYPE_DEFAULT_START = 0x560100
        private const val VIEW_TYPE_HISTORY_START = 0x570100
        private val nextDefaultViewType = AtomicInteger(VIEW_TYPE_DEFAULT_START)
        private val nextHistoryViewType = AtomicInteger(VIEW_TYPE_HISTORY_START)
        private val portraitDetectedBvids = mutableSetOf<String>()
    }

    private val contentViewType = when (displayStyle) {
        DisplayStyle.DEFAULT -> nextDefaultViewType.getAndIncrement()
        DisplayStyle.HISTORY -> nextHistoryViewType.getAndIncrement()
    }

    init {
        this.onTopEdgeUp = onTopEdgeUp
        this.onBottomEdgeDown = onBottomEdgeDown
        this.onLeftEdge = onLeftEdge
        this.onRightEdge = onRightEdge
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
        setHasStableIds(true)
    }

    override fun itemKey(item: VideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.aid > 0 -> "aid:${item.aid}"
            item.cid > 0 -> "cid:${item.cid}"
            else -> "title:${item.title}|cover:${item.coverUrl}"
        }
    }

    override fun coverUrlOf(item: VideoModel): String? {
        val raw = item.bangumi?.cover?.takeIf { it.isNotBlank() } ?: item.coverUrl
        return raw.takeIf { it.isNotBlank() }
    }

    override fun areContentsSame(old: VideoModel, new: VideoModel): Boolean =
        old.title == new.title &&
        old.coverUrl == new.coverUrl &&
        old.viewCount == new.viewCount &&
        old.danmakuCount == new.danmakuCount &&
        old.isFollowed == new.isFollowed &&
        old.isPortrait == new.isPortrait &&
        old.isChargingExclusive == new.isChargingExclusive &&
        old.historyProgress == new.historyProgress &&
        old.durationValue == new.durationValue &&
        old.authorName == new.authorName &&
        old.pubDate == new.pubDate &&
        old.createTime == new.createTime &&
        old.bangumi == new.bangumi &&
        old.historyBadge == new.historyBadge &&
        old.historyBusiness == new.historyBusiness &&
        old.historyDevice == new.historyDevice &&
        old.historyRecordKid == new.historyRecordKid &&
        old.historyViewAt == new.historyViewAt &&
        old.isSteinsGate == new.isSteinsGate

    enum class DisplayStyle {
        DEFAULT,
        HISTORY
    }

    private var onItemClickListener: ((View, VideoModel) -> Unit)? = null

    override fun getContentItemViewType(position: Int): Int {
        return contentViewType
    }

    fun setOnItemClickListener(listener: (View, VideoModel) -> Unit) {
        onItemClickListener = listener
    }


    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val views = if (displayStyle == DisplayStyle.HISTORY) {
            VideoCardPerfLogger.measureInflate("VideoAdapter.history") {
                VideoLightCardFactory.create(parent, source = "VideoAdapter.history")
            }
        } else {
            VideoCardPerfLogger.measureInflate("VideoAdapter.light") {
                VideoLightCardFactory.create(parent, source = "VideoAdapter.light")
            }
        }
        itemWidthPx?.let { width ->
            val layoutParams = views.root.layoutParams
                ?: androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            layoutParams.width = width
            views.root.layoutParams = layoutParams
        }
        val clickLambda: (View, VideoModel) -> Unit = { v, item ->
            onItemClickListener?.invoke(v, item) ?: onItemClick(item)
        }
        return VideoViewHolder(
            views,
            displayStyle,
            clickLambda,
            onTopEdgeUp,
            onBottomEdgeDown,
            onLeftEdge,
            onRightEdge,
            { view, position, hasFocus ->
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(view, position)
                }
            },
            { view, position ->
                onItemFocused?.invoke(position)
                onItemFocusedWithView?.invoke(view, position)
            },
            { video -> removeItems { itemKey(it) == itemKey(video) } },
            { upName -> removeItems { it.authorName.equals(upName, ignoreCase = true) } },
            onItemDpad,
            detectPortraitFromCover,
            fastFirstScreenCovers,
            fastFirstScreenCoverCount
        )
    }

    override fun onBindContentViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position) ?: return
        val source = if (displayStyle == DisplayStyle.HISTORY) {
            "VideoAdapter.history"
        } else {
            "VideoAdapter.light"
        }
        VideoCardPerfLogger.measureBind(source) {
            holder.bind(video, video.aid == currentPlayingAid, position)
        }
    }

    class VideoViewHolder(
        private val views: VideoCardViews,
        private val displayStyle: DisplayStyle,
        private val onItemClick: (View, VideoModel) -> Unit,
        onTopEdgeUp: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?,
        onLeftEdge: (() -> Boolean)? = null,
        onRightEdge: (() -> Boolean)? = null,
        onFocusChange: ((View, Int, Boolean) -> Unit)? = null,
        private val onItemInteracted: ((View, Int) -> Unit)? = null,
        private val onItemDisliked: ((VideoModel) -> Unit)? = null,
        private val onUpDisliked: ((String) -> Unit)? = null,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
        private val detectPortraitFromCover: Boolean = true,
        private val fastFirstScreenCovers: Boolean = false,
        private val fastFirstScreenCoverCount: Int = 8
    ) : BaseVideoViewHolder(views.root) {

        private var currentVideo: VideoModel? = null
        private var titleInPlayingMode = false
        private var titleColor = 0
        private val defaultTitleColor: Int = ContextCompat.getColor(views.root.context, R.color.textColor)

        companion object {
            // 封面的圆角半径来自 dimen，但每个 holder resources 一致，半径也一致；
            // 用单例缓存避免每个 holder 创建匿名类。第一次访问时通过 resources 把 px 值算出来锁住。
            @Volatile
            private var coverOutlineProvider: ViewOutlineProvider? = null

            fun coverOutlineProviderFor(resources: android.content.res.Resources): ViewOutlineProvider {
                coverOutlineProvider?.let { return it }
                synchronized(this) {
                    coverOutlineProvider?.let { return it }
                    val radius = resources.getDimension(R.dimen.px15)
                    return object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                    }.also { coverOutlineProvider = it }
                }
            }
        }

        private val keyListener = View.OnKeyListener { view, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPress()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPress()
                    }
                }
                false
            } else {
                onItemDpad?.invoke(view, keyCode, event) == true
            }
        }

        override fun showCardMenu() {
            cancelLongPress()
            val video = currentVideo ?: return
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = {
                    onItemDisliked?.invoke(video)
                },
                onDislikeUp = { upName ->
                    onUpDisliked?.invoke(upName)
                }
            ).show()
        }

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    AppLog.w("VideoAdapter", "click blocked by longPressTriggered")
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onItemInteracted?.invoke(views.root, position)
                }
                if (currentVideo == null) {
                    AppLog.e("VideoAdapter", "click but currentVideo is null, pos=$position")
                } else {
                    AppLog.d("VideoAdapter", "click: pos=$position, bvid=${currentVideo?.bvid}, title=${currentVideo?.title}")
                }
                currentVideo?.let { onItemClick(views.root, it) }
            }
            @SuppressLint("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onTopEdgeUp = onTopEdgeUp,
                onBottomEdgeDown = onBottomEdgeDown,
                onLeftEdge = onLeftEdge,
                onRightEdge = onRightEdge,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            if (onFocusChange != null) {
                views.root.setOnFocusChangeListener { view, hasFocus ->
                    onFocusChange.invoke(view, bindingAdapterPosition, hasFocus)
                }
            }
        }

        fun bind(video: VideoModel, isCurrentlyPlaying: Boolean = false, adapterPosition: Int = bindingAdapterPosition) {
            currentVideo = video

            val title = resolveDisplayTitle(video)
            if (isCurrentlyPlaying) {
                titleInPlayingMode = true
                val accentColor = ContextCompat.getColor(views.root.context, R.color.colorAccent)
                views.textLayer.setTitle(title, lines = 2, isPlaying = true, color = accentColor)
            } else {
                titleInPlayingMode = false
                views.textLayer.setTitle(title, lines = 2, isPlaying = false, color = defaultTitleColor)
            }
            when (displayStyle) {
                DisplayStyle.DEFAULT -> bindDefault(video)
                DisplayStyle.HISTORY -> bindHistory(video)
            }

            val coverUrl = resolveCoverUrl(video)
            val cachedPortrait = video.bvid.isNotBlank() && video.bvid in portraitDetectedBvids
            val needPortraitDetect = detectPortraitFromCover &&
                displayStyle == DisplayStyle.DEFAULT &&
                !video.isPortrait && !cachedPortrait
            val portraitCallback = if (needPortraitDetect) { isPortrait: Boolean ->
                if (bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                    && currentVideo === video && isPortrait
                ) {
                    if (video.bvid.isNotBlank()) portraitDetectedBvids.add(video.bvid)
                    val portraitVideo = video.copy(dimension = Dimension(width = 1, height = 2))
                    views.textLayer.setOwner(
                        ownerText = buildOwnerLine(portraitVideo),
                        showAvatar = false,
                        badgeText = badgeTextFor(portraitVideo)
                    )
                }
            } else null

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = coverUrl,
                priority = if (fastFirstScreenCovers && adapterPosition >= fastFirstScreenCoverCount) {
                    ImageLoader.Priority.NORMAL
                } else {
                    ImageLoader.Priority.VISIBLE_HIGH
                },
                deferUntilPreDraw = true,
                onPortraitDetected = portraitCallback
            )
        }

        private fun bindDefault(video: VideoModel) {
            views.textLayer.clearHistoryTrailing()

            val ownerName = video.authorName
            val publishLabel = TimeUtils.formatRelativeTime(views.root.context, if (video.pubDate > 0) video.pubDate else video.createTime)
            val ownerLine = buildOwnerLine(ownerName, publishLabel)
            views.textLayer.setOwner(
                ownerText = ownerLine,
                showAvatar = ownerName.isNotBlank() && !video.isPortrait,
                badgeText = badgeTextFor(video)
            )

            val duration = video.durationValue
            val progress = watchedProgress(video.historyProgress, duration)
            val durationText: String
            if (duration > 0 && progress > 0) {
                views.progressBar.visibility = View.VISIBLE
                views.progressBar.max = duration.toInt()
                views.progressBar.progress = progress.coerceAtMost(duration).toInt()
                durationText = if (duration > 3 && progress >= duration - 3) {
                    views.root.context.getString(R.string.adapter_watched_complete)
                } else {
                    views.root.context.getString(
                        R.string.video_watch_progress_format,
                        NumberUtils.formatDuration(progress),
                        NumberUtils.formatDuration(duration)
                    )
                }
            } else {
                views.progressBar.visibility = View.GONE
                durationText = NumberUtils.formatDuration(duration.coerceAtLeast(0L))
            }

            views.coverMetaOverlay.bind(
                playCountText = views.root.context.let { NumberUtils.formatCount(it, video.viewCount) },
                showPlayCount = true,
                danmakuText = views.root.context.let { NumberUtils.formatCount(it, video.danmakuCount) },
                showDanmakuCount = true,
                durationText = durationText,
                showChargeBadge = video.isChargingExclusive,
                showInteractionBadge = video.isSteinsGate
            )
        }

        private fun bindHistory(video: VideoModel) {
            val ownerName = video.authorName
            views.textLayer.setOwner(
                ownerText = ownerName,
                showAvatar = ownerName.isNotBlank()
            )

            val duration = video.durationValue
            val progress = watchedProgress(video.historyProgress, duration)
            if (duration > 0) {
                views.progressBar.visibility = View.VISIBLE
                views.progressBar.max = duration.toInt()
                views.progressBar.progress = progress.coerceAtMost(duration).toInt()
            } else {
                views.progressBar.visibility = View.GONE
            }

            val durationText = if (video.historyBusiness == "live" && video.historyBadge.isNotBlank()) {
                video.historyBadge
            } else if (isWatchedComplete(progress, duration)) {
                views.root.context.getString(R.string.adapter_watched_complete)
            } else if (duration > 0) {
                "${NumberUtils.formatDuration(progress)}/${NumberUtils.formatDuration(duration)}"
            } else {
                video.historyBadge
            }
            views.textLayer.setHistoryTrailing(
                timeText = TimeUtils.formatHistoryViewTime(views.root.context, video.historyViewAt),
                deviceDrawableRes = HistoryDeviceIcon.resolve(video.historyDevice)?.drawableRes ?: 0
            )
            views.coverMetaOverlay.bind(
                showPlayCount = false,
                showDanmakuCount = false,
                durationText = durationText,
                showChargeBadge = video.isChargingExclusive,
                showInteractionBadge = video.isSteinsGate
            )
        }

        private fun buildOwnerLine(video: VideoModel): String =
            buildOwnerLine(video.authorName, TimeUtils.formatRelativeTime(views.root.context, if (video.pubDate > 0) video.pubDate else video.createTime))

        private fun buildOwnerLine(ownerName: String, publishLabel: String): String = buildString {
            if (ownerName.isNotBlank()) {
                append(ownerName)
            }
            if (publishLabel.isNotBlank()) {
                if (isNotEmpty()) {
                    append(" · ")
                }
                append(publishLabel)
            }
        }

        private fun badgeTextFor(video: VideoModel): String {
            val parts = mutableListOf<String>()
            if (video.isFollowed) parts.add(views.root.context.getString(R.string.followed))
            if (video.isPortrait) parts.add(views.root.context.getString(R.string.adapter_portrait))
            return parts.joinToString("|")
        }

        private fun resolveDisplayTitle(video: VideoModel): String {
            return video.bangumi?.longTitle?.takeIf { it.isNotBlank() } ?: video.title
        }

        private fun resolveCoverUrl(video: VideoModel): String {
            return video.bangumi?.cover?.takeIf { it.isNotBlank() } ?: video.coverUrl
        }

        private fun watchedProgress(rawProgress: Long, duration: Long): Long {
            if (duration <= 0L) return 0L
            return if (rawProgress < 0L) {
                duration
            } else {
                rawProgress.coerceAtMost(duration)
            }
        }

        private fun isWatchedComplete(progress: Long, duration: Long): Boolean {
            return duration > 3L && progress >= duration - 3L
        }

        private fun setTitleColor(color: Int) {
            titleColor = color
            views.textLayer.setTitleColor(color)
        }
    }
}
