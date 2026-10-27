@file:Suppress("SpellCheckingInspection")

package com.mytvb.feature.dynamic

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.mytvb.R
import com.mytvb.model.video.VideoModel
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.base.BaseVideoViewHolder
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils
import com.mytvb.core.common.log.VideoCardPerfLogger
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.ui.focus.VideoCardFocusHelper
import com.mytvb.core.ui.video.VideoCardViews
import com.mytvb.core.ui.video.VideoLightCardFactory
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.ui.dialog.VideoCardMenuDialog
import java.util.concurrent.atomic.AtomicInteger

class DynamicVideoAdapter(
    private val onItemClick: (VideoModel) -> Unit,
    onItemFocused: (Int) -> Unit,
    onLeftEdge: (() -> Boolean)? = null,
    onRightEdge: (() -> Boolean)? = null,
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null
) : BaseVideoAdapter<VideoModel, DynamicVideoAdapter.ViewHolder>() {

    private val portraitDetectedBvids = mutableSetOf<String>()
    private val contentViewType = nextViewType.getAndIncrement()

    init {
        setShowLoadMore(false)
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onLeftEdge = onLeftEdge
        this.onRightEdge = onRightEdge
        this.onItemDpad = onItemDpad
        this.onItemsChanged = onItemsChanged
    }

    override fun itemKey(item: VideoModel): String {
        val bvid = item.bvid
        if (bvid.isNotBlank()) return "bvid:$bvid"
        if (item.aid > 0) return "aid:${item.aid}"
        if (item.cid > 0) return "cid:${item.cid}"
        return "title:${item.title}|cover:${item.coverUrl}"
    }

    override fun areContentsSame(old: VideoModel, new: VideoModel): Boolean = old == new

    override fun setData(list: List<VideoModel>, onCommitted: (() -> Unit)?) {
        setDataDeduplicated(list, onCommitted)
    }


    private fun removeBlockedItems(blockedName: String) {
        removeItems { it.authorName.equals(blockedName, ignoreCase = true) }
    }

    private fun removeDislikedItem(video: VideoModel) {
        removeItems { itemKey(it) == itemKey(video) }
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val views = VideoCardPerfLogger.measureInflate("DynamicVideoAdapter.light") {
            VideoLightCardFactory.create(parent, source = "DynamicVideoAdapter.light")
        }
        return ViewHolder(views)
    }

    override fun getContentItemViewType(position: Int): Int = contentViewType

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        VideoCardPerfLogger.measureBind("DynamicVideoAdapter.light") {
            holder.bind(item)
        }
    }

    inner class ViewHolder(
        private val views: VideoCardViews
    ) : BaseVideoViewHolder(views.root) {

        private var currentItem: VideoModel? = null

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

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider =
                VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            views.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != NO_POSITION) {
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(views.root, position)
                    val item = getItem(position) ?: return@setOnClickListener
                    onItemClick(item)
                }
            }
            views.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != NO_POSITION) {
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(view, position)
                }
            }
            @Suppress("ClickableViewAccessibility")
            views.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = views.root,
                onLeftEdge = onLeftEdge,
                onRightEdge = onRightEdge,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
        }

        override fun showCardMenu() {
            cancelLongPress()
            val position = bindingAdapterPosition
            if (position == NO_POSITION || position !in items.indices) return
            val video = getItem(position) ?: return
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { this@DynamicVideoAdapter.removeDislikedItem(video) },
                onDislikeUp = { upName -> this@DynamicVideoAdapter.removeBlockedItems(upName) }
            ).show()
        }

        fun bind(item: VideoModel) {
            currentItem = item
            val ownerName = item.authorName
            val publishText = TimeUtils.formatRelativeTime(views.root.context, item.pubDate)

            val coverUrl: String
            if (item.bangumi != null) {
                views.textLayer.setTitle(item.bangumi.longTitle, lines = 2)
                coverUrl = item.bangumi.cover
            } else {
                views.textLayer.setTitle(item.title, lines = 2)
                coverUrl = item.coverUrl
            }
            views.textLayer.clearHistoryTrailing()
            val ownerLine = if (ownerName.isNotBlank()) {
                if (publishText.isNotBlank()) {
                    "$ownerName · $publishText"
                } else {
                    ownerName
                }
            } else {
                publishText
            }
            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = coverUrl,
                deferUntilPreDraw = true,
                onPortraitDetected = if (!item.isPortrait && ownerName.isNotBlank() && item.bvid !in portraitDetectedBvids) { isPortrait ->
                    if (bindingAdapterPosition != NO_POSITION
                        && currentItem === item && isPortrait
                    ) {
                        if (item.bvid.isNotBlank()) portraitDetectedBvids.add(item.bvid)
                        views.textLayer.setOwner(
                            ownerText = ownerLine,
                            showAvatar = false,
                            badgeText = views.root.context.getString(R.string.badge_portrait)
                        )
                    }
                } else null
            )

            if (ownerName.isNotBlank()) {
                views.textLayer.setOwner(
                    ownerText = ownerLine,
                    showAvatar = !item.isPortrait,
                    badgeText = if (item.isPortrait) views.root.context.getString(R.string.badge_portrait) else ""
                )
            } else {
                views.textLayer.setOwner(
                    ownerText = ownerLine,
                    showAvatar = false
                )
            }

            views.coverMetaOverlay.bind(
                playCountText = NumberUtils.formatCount(views.root.context, item.viewCount),
                showPlayCount = true,
                danmakuText = NumberUtils.formatCount(views.root.context, item.danmakuCount),
                showDanmakuCount = true,
                durationText = NumberUtils.formatDuration(item.durationValue.coerceAtLeast(0L)),
                showChargeBadge = item.isChargingExclusive,
                showInteractionBadge = false
            )
        }
    }

    private companion object {
        private val nextViewType = AtomicInteger(0x5A0100)
    }
}
