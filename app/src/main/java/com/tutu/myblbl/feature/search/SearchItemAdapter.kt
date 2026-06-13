package com.tutu.myblbl.feature.search

import android.annotation.SuppressLint
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellLiveRoomBinding
import com.tutu.myblbl.databinding.CellMovieBinding
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.core.ui.video.VideoCardViews
import com.tutu.myblbl.core.ui.video.VideoLightCardFactory
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog
import java.util.concurrent.atomic.AtomicInteger

data class SearchResultEntry(
    val pageType: SearchType,
    val item: SearchItemModel
)

class SearchItemAdapter(
    private val searchType: SearchType,
    private val onItemClick: (SearchResultEntry) -> Unit,
    private val onTopEdgeUp: ((View) -> Boolean)? = null,
    private val onItemFocused: ((View, Int) -> Unit)? = null,
    private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    private val onItemsChanged: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), TvFocusableAdapter {

    private val portraitDetectedUrls = mutableSetOf<String>()
    private val items = mutableListOf<SearchItemModel>()
    private val viewTypeBase = nextViewTypeBase.getAndAdd(VIEW_TYPE_STRIDE)

    fun setItems(list: List<SearchItemModel>): Boolean {
        val plan = SearchItemUpdatePlanner.plan(
            oldKeys = items.map(::searchItemKey),
            newKeys = list.map(::searchItemKey)
        )
        return when (plan) {
            SearchItemUpdatePlan.NoChange -> false
            is SearchItemUpdatePlan.Append -> {
                items.addAll(list.subList(plan.positionStart, plan.positionStart + plan.itemCount))
                notifyItemRangeInserted(plan.positionStart, plan.itemCount)
                true
            }
            SearchItemUpdatePlan.Replace -> {
                items.clear()
                items.addAll(list)
                notifyDataSetChanged()
                true
            }
        }
    }

    override fun focusableItemCount(): Int = itemCount

    override fun getItemCount(): Int = items.size

    override fun stableKeyAt(position: Int): String? {
        return items.getOrNull(position)?.let(::searchItemKey)
    }

    override fun findPositionByStableKey(key: String): Int {
        return items.indexOfFirst { searchItemKey(it) == key }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
    }

    private fun removeBlockedItems(blockedName: String) {
        val filtered = items.filter {
            val authorName = it.author.ifBlank { it.uname }
            !authorName.equals(blockedName, ignoreCase = true)
        }
        if (filtered.size == items.size) return
        setItems(filtered)
        onItemsChanged?.invoke()
    }

    private fun RecyclerView.ViewHolder.showCardMenu() {
        val position = bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val item = items[position]
        val video = VideoModel(
            aid = item.aid,
            bvid = item.bvid,
            title = item.decodedTitle,
            pic = item.pic.ifBlank { item.cover },
            goto = if (searchType == SearchType.LiveRoom || item.roomId > 0L) "live" else "av",
            roomId = item.roomId,
            isLive = searchType == SearchType.LiveRoom || item.roomId > 0L,
            owner = Owner(
                mid = item.mid.toLongOrNull() ?: 0L,
                name = item.author.ifBlank { item.uname }
            )
        )
        VideoCardMenuDialog(
            context = itemView.context,
            video = video,
            onDislikeVideo = {
                val key = searchItemKey(item)
                val filtered = items.filter { searchItemKey(it) != key }
                if (filtered.size != items.size) {
                    setItems(filtered)
                    onItemsChanged?.invoke()
                }
            },
            onDislikeUp = { upName -> removeBlockedItems(upName) }
        ).show()
    }

    override fun getItemViewType(position: Int): Int = when (searchType) {
        SearchType.Video -> viewTypeBase + VIEW_TYPE_VIDEO_OFFSET
        SearchType.LiveRoom -> viewTypeBase + VIEW_TYPE_LIVE_OFFSET
        SearchType.Animation,
        SearchType.FilmAndTv -> viewTypeBase + VIEW_TYPE_SERIES_OFFSET
        SearchType.User -> viewTypeBase + VIEW_TYPE_USER_OFFSET
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType - viewTypeBase) {
            VIEW_TYPE_VIDEO_OFFSET -> VideoViewHolder(
                VideoCardPerfLogger.measureInflate("SearchItemAdapter.light") {
                    VideoLightCardFactory.create(parent, source = "SearchItemAdapter.light")
                }
            )

            VIEW_TYPE_LIVE_OFFSET -> LiveViewHolder(
                CellLiveRoomBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_USER_OFFSET -> UserViewHolder(
                CellUserBinding.inflate(inflater, parent, false)
            )

            else -> SeriesViewHolder(
                CellMovieBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is VideoViewHolder -> holder.bind(item)
            is LiveViewHolder -> holder.bind(item)
            is SeriesViewHolder -> holder.bind(item)
            is UserViewHolder -> holder.bind(item)
        }
    }

    private inner class VideoViewHolder(
        private val views: VideoCardViews
    ) : RecyclerView.ViewHolder(views.root) {

        private var currentItem: SearchItemModel? = null

        init {
            views.imageView.clipToOutline = true
            views.imageView.outlineProvider = VideoAdapter.VideoViewHolder.coverOutlineProviderFor(views.imageView.resources)
            bindInteraction(views.root)
        }

        fun bind(item: SearchItemModel) {
            currentItem = item
            views.textLayer.setTitle(item.decodedTitle, lines = 2)
            val ownerName = item.author.ifBlank { item.uname }
            val publishText = item.pubDate.takeIf { it > 0L }?.let(TimeUtils::formatRelativeTime).orEmpty()
            val ownerLine = buildString {
                if (ownerName.isNotBlank()) {
                    append(ownerName)
                }
                if (publishText.isNotBlank()) {
                    if (isNotEmpty()) {
                        append(" · ")
                    }
                    append(publishText)
                }
            }

            val playCount = item.play.takeIf { it > 0L } ?: item.online

            val coverUrl = item.pic.ifBlank { item.cover }
            val cachedPortrait = coverUrl in portraitDetectedUrls
            val needPortraitDetect = item.dimension?.isPortrait != true && !cachedPortrait
            if (item.dimension?.isPortrait == true) {
                views.textLayer.setOwner(
                    ownerText = ownerLine,
                    showAvatar = false,
                    badgeText = "竖屏"
                )
            } else {
                views.textLayer.setOwner(
                    ownerText = ownerLine,
                    showAvatar = ownerName.isNotBlank()
                )
            }
            views.progressBar.visibility = View.GONE
            views.textLayer.clearHistoryTrailing()
            views.coverMetaOverlay.bind(
                playCountText = if (playCount > 0L) NumberUtils.formatCount(playCount) else "",
                showPlayCount = playCount > 0L,
                danmakuText = if (item.danmaku > 0L) NumberUtils.formatCount(item.danmaku) else "",
                showDanmakuCount = item.danmaku > 0L,
                durationText = item.duration,
                showChargeBadge = false,
                showInteractionBadge = false
            )

            ImageLoader.loadVideoCover(
                imageView = views.imageView,
                url = coverUrl,
                deferUntilPreDraw = true,
                onPortraitDetected = if (needPortraitDetect) { isPortrait ->
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION
                        && currentItem === item && isPortrait
                    ) {
                        portraitDetectedUrls.add(coverUrl)
                        views.textLayer.setOwner(
                            ownerText = ownerLine,
                            showAvatar = false,
                            badgeText = "竖屏"
                        )
                    }
                } else null
            )
        }
    }

    private inner class LiveViewHolder(
        private val binding: CellLiveRoomBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
            binding.imageView.clipToOutline = true
            binding.imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                }
            }
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.decodedTitle
            binding.textPlayCount.text = NumberUtils.formatCount(item.online)

            val ownerName = item.uname.ifBlank { item.author }
            val hasOwner = ownerName.isNotBlank()
            binding.imageAvatar.visibility = if (hasOwner) View.VISIBLE else View.GONE
            binding.textViewOwner.visibility = if (hasOwner) View.VISIBLE else View.GONE
            if (hasOwner) {
                binding.textViewOwner.text = ownerName
            }

            ImageLoader.load(
                imageView = binding.imageView,
                url = normalizeUrl(item.cover.ifBlank { item.pic }),
                placeholder = R.color.thirdBackgroundColor
            )
        }
    }

    private inner class SeriesViewHolder(
        private val binding: CellMovieBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.decodedTitle
            binding.textSub.text = item.indexShow.ifBlank { item.desc }
            binding.textBadge.visibility = if (item.type.contains("media")) View.VISIBLE else View.GONE
            binding.textBadge.text = item.type.removePrefix("media_").ifBlank { "PGC" }

            ImageLoader.load(
                imageView = binding.imageView,
                url = normalizeUrl(item.cover.ifBlank { item.pic }),
                placeholder = R.color.thirdBackgroundColor
            )
        }
    }

    private inner class UserViewHolder(
        private val binding: CellUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            bindInteraction(binding.root)
        }

        fun bind(item: SearchItemModel) {
            binding.textView.text = item.uname
            binding.textLevel.visibility = if (item.level > 0) View.VISIBLE else View.INVISIBLE
            binding.textLevel.text = "LV${item.level}"
            binding.textMeta.text = buildUserMetaText(item)
            binding.textSub.text = item.usign.ifBlank { item.desc }

            ImageLoader.loadCircle(
                imageView = binding.imageView,
                url = normalizeUrl(item.upic),
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
            binding.imageView.setBadge(
                officialVerifyType = item.officialVerify?.type ?: -1
            )
        }
    }

    private fun buildUserMetaText(item: SearchItemModel): String {
        val parts = mutableListOf<String>()
        if (item.fans > 0L) {
            parts.add("${NumberUtils.formatCount(item.fans)}粉丝")
        }
        if (item.videos > 0L) {
            parts.add("${NumberUtils.formatCount(item.videos)}个视频")
        }
        return parts.joinToString(" · ").ifBlank { "0粉丝 · 0个视频" }
    }

    private fun RecyclerView.ViewHolder.bindInteraction(
        view: View
    ) {
        val handler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        var longPressTriggered = false

        val keyListener = View.OnKeyListener { targetView, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            longPressTriggered = false
                            longPressRunnable = Runnable {
                                longPressTriggered = true
                                showCardMenu()
                            }
                            handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                }
                false
            } else {
                onItemDpad?.invoke(targetView, keyCode, event) == true
            }
        }

        view.setOnClickListener {
            if (longPressTriggered) {
                longPressTriggered = false
                return@setOnClickListener
            }
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onItemFocused?.invoke(view, position)
                onItemClick(SearchResultEntry(searchType, items[position]))
            }
        }
        view.setOnFocusChangeListener { targetView, hasFocus ->
            val position = bindingAdapterPosition
            if (hasFocus && position != RecyclerView.NO_POSITION) {
                onItemFocused?.invoke(targetView, position)
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressRunnable = Runnable {
                        longPressTriggered = true
                        showCardMenu()
                    }
                    handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }
            }
            false
        }
        VideoCardFocusHelper.bindSidebarExit(
            view,
            onTopEdgeUp = {
                onTopEdgeUp?.invoke(view) == true
            },
            handleListDpadDown = false,
            chainedListener = keyListener
        )
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> url
        }
    }

    private fun searchItemKey(item: SearchItemModel): String {
        return when {
            item.aid > 0L -> "aid:${item.aid}"
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.id > 0L -> "id:${item.id}"
            else -> "title:${item.title}"
        }
    }

    private companion object {
        const val VIEW_TYPE_VIDEO_OFFSET = 0
        const val VIEW_TYPE_LIVE_OFFSET = 1
        const val VIEW_TYPE_SERIES_OFFSET = 2
        const val VIEW_TYPE_USER_OFFSET = 3
        const val VIEW_TYPE_STRIDE = 8
        val nextViewTypeBase = AtomicInteger(0x5C0100)
    }
}

internal sealed class SearchItemUpdatePlan {
    data object NoChange : SearchItemUpdatePlan()
    data class Append(val positionStart: Int, val itemCount: Int) : SearchItemUpdatePlan()
    data object Replace : SearchItemUpdatePlan()
}

internal object SearchItemUpdatePlanner {
    fun plan(oldKeys: List<String>, newKeys: List<String>): SearchItemUpdatePlan {
        val sharedSize = minOf(oldKeys.size, newKeys.size)
        for (i in 0 until sharedSize) {
            if (oldKeys[i] != newKeys[i]) {
                return SearchItemUpdatePlan.Replace
            }
        }
        return when {
            oldKeys.size == newKeys.size -> SearchItemUpdatePlan.NoChange
            newKeys.size > oldKeys.size -> SearchItemUpdatePlan.Append(
                positionStart = oldKeys.size,
                itemCount = newKeys.size - oldKeys.size
            )
            else -> SearchItemUpdatePlan.Replace
        }
    }
}
