package com.tutu.myblbl.ui.adapter

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
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellVideoBinding
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.common.time.TimeUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class FavoriteHistoryAdapter(
    private val onItemClick: (HistoryVideoModel) -> Unit,
    private val onItemFocused: ((Int) -> Unit)? = null,
    private val onItemFavoriteRemoved: ((HistoryVideoModel) -> Unit)? = null,
    private val onItemFocusedWithView: ((View, Int) -> Unit)? = null,
    private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null,
    private val onItemsChanged: (() -> Unit)? = null
) : ListAdapter<HistoryVideoModel, FavoriteHistoryAdapter.ViewHolder>(DiffCallback), TvFocusableAdapter {

    private var focusedPosition = RecyclerView.NO_POSITION

    init {
        setHasStableIds(true)
    }

    fun setData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems.distinctBy(::favoriteHistoryItemKey)
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < deduplicated.size }
            ?: RecyclerView.NO_POSITION
        submitList(deduplicated)
    }

    fun addData(newItems: List<HistoryVideoModel>) {
        val deduplicated = newItems
            .distinctBy(::favoriteHistoryItemKey)
            .filter { incoming ->
                currentList.none { existing ->
                    favoriteHistoryItemKey(existing) == favoriteHistoryItemKey(incoming)
                }
            }
        if (deduplicated.isEmpty()) return
        submitList(currentList + deduplicated)
    }

    fun getItemsSnapshot(): List<HistoryVideoModel> = currentList.toList()

    fun getFocusedPosition(): Int = focusedPosition

    override fun focusableItemCount(): Int = itemCount

    override fun stableKeyAt(position: Int): String? {
        return currentList.getOrNull(position)?.let(::favoriteHistoryItemKey)
    }

    override fun findPositionByStableKey(key: String): Int {
        return currentList.indexOfFirst { favoriteHistoryItemKey(it) == key }
            .takeIf { it >= 0 }
            ?: RecyclerView.NO_POSITION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = VideoCardPerfLogger.measureInflate("FavoriteHistoryAdapter") {
            CellVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        }
        return ViewHolder(
            binding = binding,
            onItemClick = onItemClick,
            onItemFocused = onItemFocused,
            updateFocusedPosition = { view, position ->
                val previous = focusedPosition
                focusedPosition = position
                if (previous != RecyclerView.NO_POSITION && previous != position) {
                    notifyItemChanged(previous)
                }
                setFocusedState(view, true)
                onItemFocusedWithView?.invoke(view, position)
            },
            clearFocusedPosition = { view ->
                setFocusedState(view, false)
            },
            onItemDisliked = { item -> removeDislikedItem(item) },
            onUpDisliked = { upName -> removeBlockedItems(upName) },
            onItemFavoriteRemoved = { item -> removeDislikedItem(item) },
            onItemDpad = onItemDpad,
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == focusedPosition)
    }

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_FAVORITE_HISTORY

    override fun getItemId(position: Int): Long = favoriteHistoryItemKey(getItem(position)).hashCode().toLong()

    private fun removeBlockedItems(blockedName: String) {
        val filtered = currentList.filter { !it.displayAuthorName.equals(blockedName, ignoreCase = true) }
        if (filtered.size == currentList.size) return
        submitList(filtered) {
            onItemsChanged?.invoke()
        }
    }

    private fun removeDislikedItem(item: HistoryVideoModel) {
        val key = favoriteHistoryItemKey(item)
        val filtered = currentList.filter { favoriteHistoryItemKey(it) != key }
        if (filtered.size == currentList.size) return
        submitList(filtered) {
            onItemsChanged?.invoke()
        }
    }

    class ViewHolder(
        private val binding: CellVideoBinding,
        private val onItemClick: (HistoryVideoModel) -> Unit,
        private val onItemFocused: ((Int) -> Unit)?,
        private val updateFocusedPosition: (View, Int) -> Unit,
        private val clearFocusedPosition: (View) -> Unit,
        private val onItemDisliked: ((HistoryVideoModel) -> Unit)? = null,
        private val onUpDisliked: ((String) -> Unit)? = null,
        private val onItemFavoriteRemoved: ((HistoryVideoModel) -> Unit)? = null,
        private val onItemDpad: ((View, Int, KeyEvent) -> Boolean)? = null
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: HistoryVideoModel? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var longPressTriggered = false

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

        private fun showCardMenu() {
            cancelLongPress()
            val item = currentItem ?: return
            val video = item.toVideoModel()
            VideoCardMenuDialog(
                context = itemView.context,
                video = video,
                onDislikeVideo = { onItemDisliked?.invoke(item) },
                onDislikeUp = { upName -> onUpDisliked?.invoke(upName) },
                onFavoriteRemoved = { onItemFavoriteRemoved?.invoke(item) }
            ).show()
        }

        private fun startLongPress() {
            cancelLongPress()
            longPressTriggered = false
            longPressRunnable = Runnable {
                longPressTriggered = true
                showCardMenu()
            }
            handler.postDelayed(longPressRunnable!!, ViewConfiguration.getLongPressTimeout().toLong())
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
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
                if (position != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(position)
                    updateFocusedPosition(binding.root, position)
                }
                currentItem?.let(onItemClick)
            }
            @SuppressLint("ClickableViewAccessibility")
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPress()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
                }
                false
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    onItemFocused?.invoke(position)
                    updateFocusedPosition(binding.root, position)
                } else {
                    clearFocusedPosition(binding.root)
                }
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                handleListDpadDown = false,
                chainedListener = keyListener
            )
            binding.textView.maxLines = 1
            binding.textView.minLines = 1
            binding.imageAvatar.visibility = View.GONE
            binding.textBadge.visibility = View.GONE
            binding.textDuration.visibility = View.GONE
        }

        fun bind(item: HistoryVideoModel, isFocused: Boolean) {
            currentItem = item
            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            binding.textView.text = item.title.ifBlank { item.showTitle }
            binding.progressBar.visibility = View.GONE
            val authorName = item.displayAuthorName
            val hasAuthorName = authorName.isNotBlank()
            binding.imageAvatar.visibility = if (hasAuthorName) View.VISIBLE else View.GONE
            if (!hasAuthorName && item.isPortrait) {
                binding.textBadge.text = "竖屏"
                binding.textBadge.visibility = View.VISIBLE
            } else {
                binding.textBadge.visibility = View.GONE
            }
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
            binding.textDuration.visibility = View.GONE

            binding.textViewOwner.text = formatFavoriteOwnerLine(item)

            val stat = item.cntInfo
            if (stat != null) {
                binding.iconPlayCount.visibility = View.VISIBLE
                binding.textPlayCount.visibility = View.VISIBLE
                binding.iconDanmaku.visibility = View.VISIBLE
                binding.textDanmakuCount.visibility = View.VISIBLE
                binding.textPlayCount.text = NumberUtils.formatCount(stat.play)
                binding.textDanmakuCount.text = NumberUtils.formatCount(stat.danmaku)
            } else {
                binding.iconPlayCount.visibility = View.GONE
                binding.textPlayCount.visibility = View.GONE
                binding.iconDanmaku.visibility = View.GONE
                binding.textDanmakuCount.visibility = View.GONE
            }
            binding.textChargeBadge.visibility = if (item.isChargingExclusive) View.VISIBLE else View.GONE
            binding.textInteractionBadge.visibility = if (item.isSteinsGate) View.VISIBLE else View.GONE

            val durationValue = item.duration.coerceAtLeast(0L)
            if (durationValue > 0L) {
                binding.textDuration.text = NumberUtils.formatDuration(durationValue)
                binding.textDuration.visibility = View.VISIBLE
            } else {
                binding.textDuration.text = ""
                binding.textDuration.visibility = View.GONE
            }

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover
            )
        }

        private fun formatFavoriteOwnerLine(item: HistoryVideoModel): String {
            val favoriteTime = TimeUtils.formatRelativeTime(item.favTime)
                .takeIf { it.isNotBlank() }
                ?.let { "收藏于$it" }
                .orEmpty()
            return listOf(item.displayAuthorName, favoriteTime)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        setFocusedState(holder.itemView, false)
        super.onViewRecycled(holder)
    }

    private fun setFocusedState(view: View?, focused: Boolean) {
        view ?: return
        view.isSelected = focused
        view.findViewById<AppCompatTextView>(com.tutu.myblbl.R.id.textView)?.isSelected = focused
    }

    companion object {
        private const val VIEW_TYPE_FAVORITE_HISTORY = 0x464801

        private val DiffCallback = object : DiffUtil.ItemCallback<HistoryVideoModel>() {
            override fun areItemsTheSame(oldItem: HistoryVideoModel, newItem: HistoryVideoModel): Boolean {
                return favoriteHistoryItemKey(oldItem) == favoriteHistoryItemKey(newItem)
            }

            override fun areContentsTheSame(oldItem: HistoryVideoModel, newItem: HistoryVideoModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}

private fun favoriteHistoryItemKey(item: HistoryVideoModel): String {
    return when {
        item.bvid.isNotBlank() -> "bvid:${item.bvid}"
        (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
        else -> "title:${item.title}|cover:${item.cover}"
    }
}
