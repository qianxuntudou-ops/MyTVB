package com.tutu.myblbl.feature.search

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.graphics.Outline
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellLiveRoomBinding
import com.tutu.myblbl.databinding.CellMovieBinding
import com.tutu.myblbl.databinding.CellUserBinding
import com.tutu.myblbl.databinding.CellVideoLightBinding
import com.tutu.myblbl.model.search.SearchItemModel
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

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

    fun setItems(list: List<SearchItemModel>) {
        if (items.isEmpty() && list.isNotEmpty()) {
            items.addAll(list)
            notifyItemRangeInserted(0, list.size)
            return
        }
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
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
        SearchType.Video -> VIEW_TYPE_VIDEO
        SearchType.LiveRoom -> VIEW_TYPE_LIVE
        SearchType.Animation,
        SearchType.FilmAndTv -> VIEW_TYPE_SERIES
        SearchType.User -> VIEW_TYPE_USER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                VideoCardPerfLogger.measureInflate("SearchItemAdapter.light") {
                    CellVideoLightBinding.inflate(inflater, parent, false)
                }
            )

            VIEW_TYPE_LIVE -> LiveViewHolder(
                CellLiveRoomBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_USER -> UserViewHolder(
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
        private val binding: CellVideoLightBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SearchItemModel? = null

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
            currentItem = item
            binding.textView.text = item.decodedTitle
            val ownerName = item.author.ifBlank { item.uname }
            val publishText = item.pubDate.takeIf { it > 0L }?.let(TimeUtils::formatRelativeTime).orEmpty()
            binding.textViewOwner.text = buildString {
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
            if (playCount > 0L) {
                binding.iconPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.text = NumberUtils.formatCount(playCount)
            } else {
                binding.iconPlayCount.visibility = View.GONE
                binding.textPlayCount.visibility = View.GONE
            }

            if (item.danmaku > 0L) {
                binding.iconDanmaku.visibility = View.VISIBLE
                binding.textDanmakuCount.visibility = View.VISIBLE
                binding.textDanmakuCount.text = NumberUtils.formatCount(item.danmaku)
            } else {
                binding.iconDanmaku.visibility = View.GONE
                binding.textDanmakuCount.visibility = View.GONE
            }

            val coverUrl = item.pic.ifBlank { item.cover }
            val cachedPortrait = coverUrl in portraitDetectedUrls
            val needPortraitDetect = item.dimension?.isPortrait != true && !cachedPortrait
            if (item.dimension?.isPortrait == true) {
                binding.imageAvatar.visibility = View.GONE
                binding.textBadge.text = "竖屏"
                binding.textBadge.visibility = View.VISIBLE
            } else {
                binding.imageAvatar.visibility = if (ownerName.isNotBlank()) View.VISIBLE else View.GONE
                binding.textBadge.visibility = View.GONE
            }
            binding.textDuration.text = item.duration
            binding.progressBar.visibility = View.GONE
            binding.textChargeBadge.visibility = View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = coverUrl,
                onPortraitDetected = if (needPortraitDetect) { isPortrait ->
                    if (bindingAdapterPosition != RecyclerView.NO_POSITION
                        && currentItem === item && isPortrait
                    ) {
                        portraitDetectedUrls.add(coverUrl)
                        binding.imageAvatar.visibility = View.GONE
                        binding.textBadge.text = "竖屏"
                        binding.textBadge.visibility = View.VISIBLE
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
            binding.textSub.text = item.usign

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
        const val VIEW_TYPE_VIDEO = 0x530100
        const val VIEW_TYPE_LIVE = 0x530101
        const val VIEW_TYPE_SERIES = 0x530102
        const val VIEW_TYPE_USER = 0x530103
    }
}
