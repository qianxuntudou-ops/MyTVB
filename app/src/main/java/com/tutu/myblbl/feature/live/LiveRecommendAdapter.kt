package com.tutu.myblbl.feature.live

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.databinding.CellLaneScrollableBinding
import com.tutu.myblbl.model.live.LiveRecommendSection
import com.tutu.myblbl.model.live.LiveRoomItem
import com.tutu.myblbl.core.ui.base.RecyclerViewPoolPrewarmer
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager

class LiveRecommendAdapter(
    private val onRoomClick: (LiveRoomItem) -> Unit,
    private val onTopEdgeUp: () -> Boolean = { false },
    private val onLeftEdge: () -> Boolean = { false }
) : RecyclerView.Adapter<LiveRecommendAdapter.ViewHolder>() {

    private val items = ArrayList<LiveRecommendSection>()
    private val sharedRoomViewPool = RecyclerView.RecycledViewPool()

    val currentList: List<LiveRecommendSection>
        get() = items

    init {
        setHasStableIds(true)
    }

    fun setData(list: List<LiveRecommendSection>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun prewarm(parent: RecyclerView) {
        RecyclerViewPoolPrewarmer.prewarm(
            recyclerView = parent,
            adapter = this,
            count = 2,
            source = "LiveRecommend.sections"
        )
        RecyclerViewPoolPrewarmer.prewarm(
            recyclerView = parent,
            adapter = LiveRoomAdapter(onRoomClick),
            count = 4,
            source = "LiveRecommend.rooms",
            pool = sharedRoomViewPool,
            maxPoolSize = 24
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellLaneScrollableBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, onRoomClick, onTopEdgeUp, onLeftEdge, sharedRoomViewPool)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.title?.hashCode()?.toLong() ?: RecyclerView.NO_ID

    class ViewHolder(
        private val binding: CellLaneScrollableBinding,
        onRoomClick: (LiveRoomItem) -> Unit,
        private val onTopEdgeUp: () -> Boolean,
        private val onLeftEdge: () -> Boolean,
        sharedViewPool: RecyclerView.RecycledViewPool
    ) : RecyclerView.ViewHolder(binding.root) {

        private val roomAdapter = LiveRoomAdapter(onRoomClick)

        init {
            binding.recyclerView.layoutManager = object : WrapContentGridLayoutManager(binding.root.context, 4) {
                override fun canScrollVertically(): Boolean = false
            }
            binding.recyclerView.adapter = roomAdapter
            binding.recyclerView.setRecycledViewPool(sharedViewPool)
            binding.recyclerView.isNestedScrollingEnabled = false
            binding.recyclerView.setHasFixedSize(true)
            binding.recyclerView.itemAnimator = null
            binding.topTitle.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> bindingAdapterPosition == 0 && onTopEdgeUp()
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge()
                    else -> false
                }
            }
        }

        fun bind(item: LiveRecommendSection) {
            binding.topTitle.text = item.title
            roomAdapter.setData(item.rooms)
        }

        fun requestPrimaryFocus(): Boolean {
            return binding.topTitle.requestFocus()
        }

        fun focusRoomAt(roomIndex: Int): Boolean {
            val innerRv = binding.recyclerView
            val holder = innerRv.findViewHolderForAdapterPosition(roomIndex)
            if (holder != null && holder.itemView.isAttachedToWindow) {
                return holder.itemView.requestFocus()
            }
            innerRv.scrollToPosition(roomIndex)
            innerRv.post {
                innerRv.findViewHolderForAdapterPosition(roomIndex)?.itemView?.requestFocus()
            }
            return true
        }

        fun findRoomPositionByRoomId(roomId: Long): Int {
            return roomAdapter.currentList.indexOfFirst { it.roomId == roomId }
                .takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        }
    }
}
