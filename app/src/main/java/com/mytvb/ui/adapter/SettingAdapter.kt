package com.mytvb.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.CellSettingBinding
import com.mytvb.databinding.CellSettingHeadBinding
import com.mytvb.model.SettingModel

/** 列表行：组头 或 组内设置条目（slot 标记其在组框中的拼接位置）。 */
sealed interface SettingRow {
    data class Header(val title: String) : SettingRow
    data class Item(val model: SettingModel, val slot: Int) : SettingRow
}

class SettingAdapter(
    private val onItemClick: ((position: Int, item: SettingModel) -> Unit)? = null
) : ListAdapter<SettingRow, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val SLOT_FIRST = 0
        const val SLOT_MIDDLE = 1
        const val SLOT_LAST = 2
        const val SLOT_SINGLE = 3

        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val PAYLOAD_FOCUS = "payload_focus"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SettingRow>() {
            override fun areItemsTheSame(oldItem: SettingRow, newItem: SettingRow): Boolean {
                return when {
                    oldItem is SettingRow.Header && newItem is SettingRow.Header ->
                        oldItem.title == newItem.title
                    oldItem is SettingRow.Item && newItem is SettingRow.Item ->
                        oldItem.model.key == newItem.model.key
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: SettingRow, newItem: SettingRow): Boolean {
                return when {
                    oldItem is SettingRow.Header && newItem is SettingRow.Header ->
                        oldItem.title == newItem.title
                    oldItem is SettingRow.Item && newItem is SettingRow.Item ->
                        oldItem.model.key == newItem.model.key &&
                            oldItem.model.title == newItem.model.title &&
                            oldItem.model.info == newItem.model.info &&
                            oldItem.slot == newItem.slot
                    else -> false
                }
            }
        }
    }

    private var focusedPosition = RecyclerView.NO_POSITION

    fun setData(newRows: List<SettingRow>) {
        focusedPosition = RecyclerView.NO_POSITION
        submitList(newRows)
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position) is SettingRow.Header) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(CellSettingHeadBinding.inflate(inflater, parent, false))
        } else {
            SettingViewHolder(CellSettingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SettingRow.Header -> (holder as HeaderViewHolder).bind(row)
            is SettingRow.Item -> (holder as SettingViewHolder).bind(row)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val row = getItem(position)
        if (row is SettingRow.Item && holder is SettingViewHolder) {
            holder.bindContent(row)
            holder.bindFocusState(position == focusedPosition, row.slot)
        }
    }

    class HeaderViewHolder(
        private val binding: CellSettingHeadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: SettingRow.Header) {
            binding.tvGroupTitle.text = row.title
        }
    }

    inner class SettingViewHolder(
        private val binding: CellSettingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.clickView.setOnClickListener {
                val position = bindingAdapterPosition
                val row = currentList.getOrNull(position)
                if (position != RecyclerView.NO_POSITION && row is SettingRow.Item) {
                    onItemClick?.invoke(position, row.model)
                }
            }
            binding.clickView.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                val row = currentList.getOrNull(position)
                if (hasFocus) {
                    val oldFocused = focusedPosition
                    focusedPosition = position
                    // 同步应用焦点样式：只走 notifyItemChanged 会晚一帧重绑，
                    // 这一帧里会先画出复用 view 残留的按下态（亮紫方块）
                    if (row is SettingRow.Item) {
                        bindFocusState(true, row.slot)
                    }
                    if (oldFocused != RecyclerView.NO_POSITION && oldFocused != position) {
                        notifyFocusChanged(oldFocused)
                    }
                    notifyFocusChanged(position)
                    return@setOnFocusChangeListener
                }
                if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                    if (row is SettingRow.Item) {
                        bindFocusState(false, row.slot)
                    }
                    notifyFocusChanged(position)
                }
            }
        }

        /**
         * 布局/滚动计算中禁止 notifyItemChanged（RecyclerView assertNotInLayoutOrScroll 直接抛
         * IllegalStateException 闪退）：失焦可能被布局过程中的 scrap 回收 clearFocus 链条间接触发
         * （dispatchLayoutStep3 → removeAndRecycleScrapInt → removeDetachedView → clearFocus），
         * 此时上面的 bindFocusState 已同步把样式改到 view 上，notify 只是冗余的重绑保险，跳过即可。
         */
        private fun notifyFocusChanged(position: Int) {
            val recyclerView = itemView.parent as? RecyclerView ?: return
            if (recyclerView.isComputingLayout) {
                return
            }
            notifyItemChanged(position, PAYLOAD_FOCUS)
        }

        fun bind(row: SettingRow.Item) {
            bindContent(row)
            bindFocusState(bindingAdapterPosition == focusedPosition, row.slot)
        }

        /**
         * 清掉 view 复用/焦点转移时残留的暂态：item 被触摸滑动或按下后，
         * pressed 等状态会跟着被回收的 view 带到新位置，压过焦点背景的 focused 样式。
         */
        private fun clearTransientStates() {
            binding.clickView.isPressed = false
            binding.clickView.isActivated = false
            binding.clickView.isSelected = false
            binding.clickView.jumpDrawablesToCurrentState()
        }

        fun bindContent(row: SettingRow.Item) {
            binding.tvTitle.text = row.model.title
            binding.tvInfo.text = row.model.info
        }

        fun bindFocusState(isFocused: Boolean, slot: Int) {
            clearTransientStates()
            binding.iconArrow.alpha = if (isFocused) 1f else 0.6f
            binding.tvInfo.alpha = if (isFocused) 1f else 0.8f
            if (isFocused) {
                binding.clickView.setBackgroundResource(R.drawable.cell_background)
            } else {
                binding.clickView.setBackgroundResource(slotBackground(slot))
            }
            binding.clickView.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .setDuration(120L)
                .start()
        }

        /** 非聚焦态按组内位置取拼接背景：单条组保持原独立卡片样式。 */
        private fun slotBackground(slot: Int): Int = when (slot) {
            SLOT_FIRST -> R.drawable.cell_setting_group_first
            SLOT_MIDDLE -> R.drawable.cell_setting_group_middle
            SLOT_LAST -> R.drawable.cell_setting_group_last
            else -> R.drawable.cell_setting_background
        }
    }
}
