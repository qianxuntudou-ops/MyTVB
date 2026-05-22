@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.feature.dynamic

import android.graphics.Outline
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.tutu.myblbl.R
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import com.tutu.myblbl.databinding.CellVideoLightBinding
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.core.ui.base.BaseVideoAdapter
import com.tutu.myblbl.core.ui.base.BaseVideoViewHolder
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class DynamicVideoAdapter(
    private val onItemClick: (VideoModel) -> Unit,
    onItemFocused: (Int) -> Unit,
    onLeftEdge: () -> Boolean = { false },
    onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    onItemsChanged: (() -> Unit)? = null
) : BaseVideoAdapter<VideoModel, DynamicVideoAdapter.ViewHolder>() {

    private val portraitDetectedBvids = mutableSetOf<String>()

    init {
        setShowLoadMore(false)
        this.onItemFocused = onItemFocused
        this.onItemFocusedWithView = onItemFocusedWithView
        this.onLeftEdge = onLeftEdge
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

    fun setData(list: List<VideoModel>) {
        setDataDeduplicated(list)
    }


    private fun removeBlockedItems(blockedName: String) {
        removeItems { it.authorName.equals(blockedName, ignoreCase = true) }
    }

    private fun removeDislikedItem(video: VideoModel) {
        removeItems { itemKey(it) == itemKey(video) }
    }

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = VideoCardPerfLogger.measureInflate("DynamicVideoAdapter.light") {
            CellVideoLightBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        }
        return ViewHolder(binding)
    }

    override fun onBindContentViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    inner class ViewHolder(
        private val binding: CellVideoLightBinding
    ) : BaseVideoViewHolder(binding.root) {

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
            val coverRadiusPx = binding.imageView.resources.getDimension(R.dimen.px15)
            binding.imageView.clipToOutline = true
            binding.imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, coverRadiusPx)
                }
            }
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                val position = bindingAdapterPosition
                if (position != NO_POSITION) {
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(binding.root, position)
                    val item = getItem(position) ?: return@setOnClickListener
                    onItemClick(item)
                }
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != NO_POSITION) {
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    onItemFocusedWithView?.invoke(view, position)
                }
            }
            @Suppress("ClickableViewAccessibility")
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onLeftEdge = onLeftEdge,
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
            val publishText = formatPublishTime(item)

            val coverUrl: String
            if (item.bangumi != null) {
                binding.textView.text = item.bangumi.longTitle
                coverUrl = item.bangumi.cover
            } else {
                binding.textView.text = item.title
                coverUrl = item.coverUrl
            }
            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = coverUrl,
                onPortraitDetected = if (!item.isPortrait && ownerName.isNotBlank() && item.bvid !in portraitDetectedBvids) { isPortrait ->
                    if (bindingAdapterPosition != NO_POSITION
                        && currentItem === item && isPortrait
                    ) {
                        if (item.bvid.isNotBlank()) portraitDetectedBvids.add(item.bvid)
                        binding.imageAvatar.visibility = View.GONE
                        binding.textBadge.text = "竖屏"
                        binding.textBadge.visibility = View.VISIBLE
                    }
                } else null
            )

            if (ownerName.isNotBlank()) {
                binding.textViewOwner.text = if (publishText.isNotBlank()) {
                    "$ownerName · $publishText"
                } else {
                    ownerName
                }
                if (item.isPortrait) {
                    binding.imageAvatar.visibility = View.GONE
                    binding.textBadge.text = "竖屏"
                    binding.textBadge.visibility = View.VISIBLE
                } else {
                    binding.imageAvatar.visibility = View.VISIBLE
                    binding.textBadge.visibility = View.GONE
                }
            } else {
                binding.textViewOwner.text = publishText
                binding.imageAvatar.visibility = View.GONE
                binding.textBadge.visibility = View.GONE
            }

            binding.textPlayCount.text = NumberUtils.formatCount(item.viewCount)
            binding.textDanmakuCount.text = NumberUtils.formatCount(item.danmakuCount)
            binding.textDuration.text = NumberUtils.formatDuration(item.durationValue.coerceAtLeast(0L))
            binding.textChargeBadge.visibility = if (item.isChargingExclusive) View.VISIBLE else View.GONE
        }

        private fun formatPublishTime(video: VideoModel): String {
            val publishedAt = video.pubDate
            return if (publishedAt > 0) {
                TimeUtils.formatRelativeTime(publishedAt)
            } else {
                ""
            }
        }
    }
}
