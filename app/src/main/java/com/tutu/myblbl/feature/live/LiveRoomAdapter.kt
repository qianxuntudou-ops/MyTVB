package com.tutu.myblbl.feature.live

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.graphics.Outline
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.VideoCardPerfLogger
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.common.format.NumberUtils
import com.tutu.myblbl.core.ui.focus.VideoCardFocusHelper
import com.tutu.myblbl.core.ui.focus.tv.TvFocusableAdapter
import com.tutu.myblbl.databinding.CellVideoLightBinding
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.model.video.Owner
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.ui.dialog.VideoCardMenuDialog

class LiveRoomAdapter(
    private val onItemClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: (() -> Boolean)? = null
) : RecyclerView.Adapter<LiveRoomAdapter.ViewHolder>(), TvFocusableAdapter {

    private val items = ArrayList<LiveRoomItem>()

    val currentList: List<LiveRoomItem>
        get() = items

    init {
        setHasStableIds(true)
    }

    override fun focusableItemCount(): Int = items.size

    override fun stableKeyAt(position: Int): String? = items.getOrNull(position)?.roomId?.toString()

    override fun findPositionByStableKey(key: String): Int =
        items.indexOfFirst { it.roomId.toString() == key }
            .takeIf { it >= 0 } ?: RecyclerView.NO_POSITION

    fun setData(list: List<LiveRoomItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun addData(list: List<LiveRoomItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.roomId ?: RecyclerView.NO_ID

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_LIVE_ROOM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = VideoCardPerfLogger.measureInflate("LiveRoomAdapter.light") {
            CellVideoLightBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    private fun removeItemsByAnchorName(blockedName: String) {
        val filtered = items.filter { !it.uname.equals(blockedName, ignoreCase = true) }
        if (filtered.size == items.size) return
        setData(filtered)
    }

    private fun removeRoom(roomId: Long) {
        val index = items.indexOfFirst { it.roomId == roomId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    inner class ViewHolder(
        private val binding: CellVideoLightBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: LiveRoomItem? = null
        private val handler = Handler(Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private val longPressThreshold = 5_000L
        private var longPressTriggered = false

        private val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            startLongPressTimer()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        cancelLongPressTimer()
                    }
                }
            }
            false
        }

        private fun startLongPressTimer() {
            cancelLongPressTimer()
            longPressTriggered = false
            longPressRunnable = Runnable {
                val item = currentItem ?: return@Runnable
                longPressTriggered = true
                VideoCardMenuDialog(
                    context = itemView.context,
                    video = item.toFeedbackVideoModel(),
                    onDislikeVideo = {
                        removeRoom(item.roomId)
                    },
                    onDislikeUp = { upName ->
                        removeItemsByAnchorName(upName)
                    }
                ).show()
            }
            handler.postDelayed(longPressRunnable!!, longPressThreshold)
        }

        private fun cancelLongPressTimer() {
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
                    items.getOrNull(position)?.let(onItemClick)
                }
            }
            @SuppressLint("ClickableViewAccessibility")
            binding.root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> startLongPressTimer()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressTimer()
                }
                false
            }
            VideoCardFocusHelper.bindSidebarExit(
                view = binding.root,
                onTopEdgeUp = onTopEdgeUp,
                chainedListener = keyListener
            )
        }

        fun bind(item: LiveRoomItem) {
            currentItem = item
            binding.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.root.resources.getDimension(R.dimen.px31))
            binding.textView.maxLines = 1
            binding.textView.minLines = 1
            binding.textView.text = item.title
            binding.textViewOwner.text = item.uname
            binding.imageAvatar.visibility = if (item.uname.isBlank()) View.GONE else View.VISIBLE
            binding.textBadge.visibility = View.GONE
            binding.textViewOwner.visibility = if (item.uname.isBlank()) View.GONE else View.VISIBLE
            binding.textPlayCount.text = NumberUtils.formatCount(item.online.toLong())
            binding.iconPlayCount.visibility = View.VISIBLE
            binding.textPlayCount.visibility = View.VISIBLE
            binding.iconDanmaku.visibility = View.GONE
            binding.textDanmakuCount.visibility = View.GONE
            binding.textDuration.visibility = View.GONE
            binding.progressBar.visibility = View.GONE

            ImageLoader.loadVideoCover(
                imageView = binding.imageView,
                url = item.cover
            )
        }
    }

    private fun LiveRoomItem.toFeedbackVideoModel(): VideoModel {
        return VideoModel(
            title = title,
            cover = cover,
            goto = "live",
            roomId = roomId,
            owner = Owner(
                mid = uid,
                name = uname,
                face = face
            )
        )
    }

    companion object {
        private const val VIEW_TYPE_LIVE_ROOM = 0x4C5200
    }
}
