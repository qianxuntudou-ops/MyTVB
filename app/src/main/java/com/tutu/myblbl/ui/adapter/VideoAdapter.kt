package com.tutu.myblbl.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Outline
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.databinding.CellVideoLightBinding
import com.tutu.myblbl.model.video.Dimension
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.base.BaseVideoAdapter
import com.tutu.myblbl.core.ui.base.BaseVideoViewHolder
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.core.ui.video.VideoCardViews
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class VideoAdapter(
    private val displayStyle: DisplayStyle = DisplayStyle.DEFAULT,
    private val itemWidthPx: Int? = null,
    private val onItemClick: (VideoModel) -> Unit = {},
    onTopEdgeUp: (() -> Boolean)? = null,
    onBottomEdgeDown: (() -> Boolean)? = null,
    onItemFocused: ((Int) -> Unit)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null,
    private val detectPortraitFromCover: Boolean = true
) : BaseVideoAdapter<VideoModel, VideoAdapter.VideoViewHolder>() {

    var currentPlayingAid: Long = 0

    companion object {
        private const val VIEW_TYPE_DEFAULT = 0x560100
        private const val VIEW_TYPE_HISTORY = 0x560101
        private val portraitDetectedBvids = mutableSetOf<String>()
    }

    init {
        this.onTopEdgeUp = onTopEdgeUp
        this.onBottomEdgeDown = onBottomEdgeDown
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
        return when (displayStyle) {
            DisplayStyle.DEFAULT -> VIEW_TYPE_DEFAULT
            DisplayStyle.HISTORY -> VIEW_TYPE_HISTORY
        }
    }

    fun setOnItemClickListener(listener: (View, VideoModel) -> Unit) {
        onItemClickListener = listener
    }


    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val views = if (displayStyle == DisplayStyle.HISTORY) {
            VideoCardPerfLogger.measureInflate("VideoAdapter.history") {
                VideoCardViews.from(CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        } else {
            VideoCardPerfLogger.measureInflate("VideoAdapter.light") {
                VideoCardViews.from(CellVideoLightBinding.inflate(LayoutInflater.from(parent.context), parent, false))
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
            detectPortraitFromCover
        )
    }

    override fun onBindContentViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position) ?: return
        holder.bind(video, video.aid == currentPlayingAid)
    }

    class VideoViewHolder(
        private val views: VideoCardViews,
        private val displayStyle: DisplayStyle,
        private val onItemClick: (View, VideoModel) -> Unit,
        onTopEdgeUp: (() -> Boolean)?,
        onBottomEdgeDown: (() -> Boolean)?,
        onFocusChange: ((View, Int, Boolean) -> Unit)? = null,
        private val onItemInteracted: ((View, Int) -> Unit)? = null,
        private val onItemDisliked: ((VideoModel) -> Unit)? = null,
        private val onUpDisliked: ((String) -> Unit)? = null,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
        private val detectPortraitFromCover: Boolean = true
    ) : BaseVideoViewHolder(views.root) {

        private var currentVideo: VideoModel? = null
        private val defaultTextColor: Int by lazy {
            val ta = views.root.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val color = ta.getColor(0, 0)
            ta.recycle()
            color
        }

        companion object {
            // 进度条永远是圆角胶囊，所有 holder 共享一个 ViewOutlineProvider 实例，
            // 避免 RecyclerView 创建 12 个 holder 时实例化 12 个匿名类 + 12 个 ViewOutline。
            private val PROGRESS_OUTLINE_PROVIDER = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }

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
            views.progressBar.clipToOutline = true
            views.progressBar.outlineProvider = PROGRESS_OUTLINE_PROVIDER
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
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            if (onFocusChange != null) {
                views.root.setOnFocusChangeListener { view, hasFocus ->
                    onFocusChange.invoke(view, bindingAdapterPosition, hasFocus)
                }
            }
        }

        private var splitRunnable: Runnable? = null

        fun bind(video: VideoModel, isCurrentlyPlaying: Boolean = false) {
            currentVideo = video
            splitRunnable?.let { views.textView.removeCallbacks(it) }
            splitRunnable = null

            val title = resolveDisplayTitle(video)
            if (isCurrentlyPlaying) {
                views.iconPlaying.visibility = View.VISIBLE
                val iconSize = views.textView.textSize.toInt()
                views.iconPlaying.layoutParams = views.iconPlaying.layoutParams.apply {
                    width = iconSize
                    height = iconSize
                }
                ImageLoader.loadDrawableRes(views.iconPlaying, R.drawable.playing)
                val accentColor = ContextCompat.getColor(views.root.context, R.color.colorAccent)
                views.textView.setTextColor(accentColor)
                views.textOverflow.setTextColor(accentColor)
                views.textView.text = title
                views.textView.ellipsize = TextUtils.TruncateAt.END
                views.textView.minLines = 1
                views.textView.maxLines = 1

                val split = Runnable {
                    val layout = views.textView.layout ?: return@Runnable
                    if (layout.lineCount > 0) {
                        val ellipsisCount = layout.getEllipsisCount(0)
                        if (ellipsisCount > 0) {
                            val visibleEnd = layout.getEllipsisStart(0)
                            views.textView.text = title.substring(0, visibleEnd)
                            views.textOverflow.text = title.substring(visibleEnd)
                            views.textOverflow.visibility = View.VISIBLE
                        } else {
                            views.textOverflow.visibility = View.GONE
                        }
                    }
                }
                splitRunnable = split
                views.textView.post(split)
            } else {
                views.iconPlaying.visibility = View.GONE
                ImageLoader.clear(views.iconPlaying)
                views.textView.setTextColor(defaultTextColor)
                views.textView.text = title
                views.textView.minLines = 2
                views.textView.maxLines = 2
                views.textOverflow.visibility = View.GONE
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
            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = coverUrl,
                onPortraitDetected = if (needPortraitDetect) { isPortrait ->
                    if (bindingAdapterPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                        && currentVideo === video && isPortrait
                    ) {
                        if (video.bvid.isNotBlank()) portraitDetectedBvids.add(video.bvid)
                        views.imageAvatar.visibility = View.GONE
                        applyBadge(video.copy(dimension = Dimension(width = 1, height = 2)))
                    }
                } else null
            )
        }

        private fun bindDefault(video: VideoModel) {
            views.iconPlayCount.visibility = View.VISIBLE
            views.textPlayCount.visibility = View.VISIBLE
            views.iconDanmaku.visibility = View.VISIBLE
            views.textDanmakuCount.visibility = View.VISIBLE
            views.textHistoryViewTime?.visibility = View.GONE
            views.iconHistoryDevice?.visibility = View.GONE

            val ownerName = video.authorName
            val publishLabel = formatPublishTime(video)
            views.textViewOwner.text = buildString {
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
            if (video.isPortrait) {
                views.imageAvatar.visibility = View.GONE
            } else {
                views.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
            }
            applyBadge(video)

            val duration = video.durationValue
            val progress = video.historyProgress.coerceAtLeast(0L)
            if (duration > 0 && progress > 0) {
                views.progressBar.visibility = View.VISIBLE
                views.progressBar.max = duration.toInt()
                views.progressBar.progress = progress.coerceAtMost(duration).toInt()
                if (duration > 3 && progress >= duration - 3) {
                    views.textDuration.text = "已看完"
                } else {
                    views.textDuration.text = views.root.context.getString(
                        R.string.video_watch_progress_format,
                        NumberUtils.formatDuration(progress),
                        NumberUtils.formatDuration(duration)
                    )
                }
            } else {
                views.progressBar.visibility = View.GONE
                views.textDuration.text = NumberUtils.formatDuration(duration.coerceAtLeast(0L))
            }

            views.textPlayCount.text = NumberUtils.formatCount(video.viewCount)
            views.textDanmakuCount.text = NumberUtils.formatCount(video.danmakuCount)
            views.textDuration.visibility = if (views.textDuration.text.isNullOrBlank()) View.GONE else View.VISIBLE
            views.textChargeBadge.visibility = if (video.isChargingExclusive) View.VISIBLE else View.GONE
            views.textInteractionBadge.visibility = if (video.isSteinsGate) View.VISIBLE else View.GONE
        }

        private fun bindHistory(video: VideoModel) {
            views.iconPlayCount.visibility = View.GONE
            views.textPlayCount.visibility = View.GONE
            views.iconDanmaku.visibility = View.GONE
            views.textDanmakuCount.visibility = View.GONE
            val ownerName = video.authorName
            views.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
            views.textBadge.visibility = View.GONE

            val duration = video.durationValue
            val progress = video.historyProgress.coerceAtLeast(0)
            if (duration > 0) {
                views.progressBar.visibility = View.VISIBLE
                views.progressBar.max = duration.toInt()
                views.progressBar.progress = progress.coerceAtMost(duration).toInt()
            } else {
                views.progressBar.visibility = View.GONE
            }

            val durationText = if (video.historyBusiness == "live" && video.historyBadge.isNotBlank()) {
                video.historyBadge
            } else if (duration > 0) {
                "${NumberUtils.formatDuration(progress)}/${NumberUtils.formatDuration(duration)}"
            } else {
                video.historyBadge
            }
            views.textDuration.text = durationText
            views.textDuration.visibility = if (durationText.isNotBlank()) View.VISIBLE else View.GONE
            views.textViewOwner.text = ownerName
            views.textHistoryViewTime?.text = formatHistoryTime(video.historyViewAt)
            views.textHistoryViewTime?.visibility = View.VISIBLE
            applyHistoryDeviceIcon(video.historyDevice)
            views.textChargeBadge.visibility = if (video.isChargingExclusive) View.VISIBLE else View.GONE
            views.textInteractionBadge.visibility = if (video.isSteinsGate) View.VISIBLE else View.GONE
        }

        private fun applyHistoryDeviceIcon(dt: Int) {
            val deviceIcon = HistoryDeviceIcon.resolve(dt)
            val iconHistoryDevice = views.iconHistoryDevice ?: return
            if (deviceIcon == null) {
                iconHistoryDevice.visibility = View.GONE
                iconHistoryDevice.contentDescription = null
            } else {
                iconHistoryDevice.setImageResource(deviceIcon.drawableRes)
                iconHistoryDevice.contentDescription = deviceIcon.contentDescription
                iconHistoryDevice.visibility = View.VISIBLE
            }
        }

        private fun applyBadge(video: VideoModel) {
            val parts = mutableListOf<String>()
            if (video.isFollowed) parts.add("已关注")
            if (video.isPortrait) parts.add("竖屏")
            if (parts.isEmpty()) {
                views.textBadge.visibility = View.GONE
            } else {
                views.textBadge.text = parts.joinToString("|")
                views.textBadge.visibility = View.VISIBLE
            }
        }

        private fun resolveDisplayTitle(video: VideoModel): String {
            return video.bangumi?.longTitle?.takeIf { it.isNotBlank() } ?: video.title
        }

        private fun resolveCoverUrl(video: VideoModel): String {
            return video.bangumi?.cover?.takeIf { it.isNotBlank() } ?: video.coverUrl
        }

        private fun formatPublishTime(video: VideoModel): String {
            val publishedAt = when {
                video.pubDate > 0 -> video.pubDate
                video.createTime > 0 -> video.createTime
                else -> 0L
            }
            return if (publishedAt > 0) {
                TimeUtils.formatRelativeTime(publishedAt)
            } else {
                ""
            }
        }

        private fun formatHistoryTime(viewAtSeconds: Long): String {
            return TimeUtils.formatHistoryViewTime(viewAtSeconds)
        }
    }
}
