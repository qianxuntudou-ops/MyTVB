package com.mytvb.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.CellSettingChoiceBinding

class SettingSelectionDialogAdapter(
    private val options: List<String>,
    selectedIndex: Int,
    private val sortDirection: Int = -1,
    private val onFocused: ((Int) -> Unit)? = null,
    private val onItemSelected: (Int) -> Unit
) : RecyclerView.Adapter<SettingSelectionDialogAdapter.SettingChoiceViewHolder>() {

    private var selectedIndex = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
    private var focusedPosition = RecyclerView.NO_POSITION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingChoiceViewHolder {
        val binding = CellSettingChoiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SettingChoiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingChoiceViewHolder, position: Int) {
        holder.bind(option = options[position], isSelected = position == selectedIndex)
    }

    override fun getItemCount(): Int = options.size

    /**
     * 初始聚焦逐帧重试：弹窗复显/页面加载中时，post 一帧后目标行可能尚未绑定
     * （scrollToPosition 的布局 pass 未完成，findViewHolderForAdapterPosition 返回
     * null），焦点就会悬空，表现为"行无高亮、按确定无反应"。没落上就再等一帧，
     * 最多重试 20 帧（约 330ms，覆盖慢设备上弹窗首个布局+滚动完成）；仍未成功时
     * 由 ChoiceDialog 的上下键自愈兜底把焦点拉回列表。
     */
    fun requestInitialFocus(recyclerView: RecyclerView?) {
        val targetPosition = selectedIndex.coerceIn(0, options.lastIndex.coerceAtLeast(0))
        recyclerView?.scrollToPosition(targetPosition)
        focusAfterLayout(recyclerView, targetPosition, attemptsLeft = 20)
    }

    private fun focusAfterLayout(recyclerView: RecyclerView?, position: Int, attemptsLeft: Int) {
        if (recyclerView == null || attemptsLeft <= 0) return
        recyclerView.post {
            val target = recyclerView.findViewHolderForAdapterPosition(position)?.itemView
            if (target != null && target.requestFocus()) return@post
            focusAfterLayout(recyclerView, position, attemptsLeft - 1)
        }
    }

    inner class SettingChoiceViewHolder(
        private val binding: CellSettingChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                selectedIndex = position
                onItemSelected(position)
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    focusedPosition = position
                    onFocused?.invoke(position)
                } else if (focusedPosition == position) {
                    focusedPosition = RecyclerView.NO_POSITION
                }
                applyState(
                    isSelected = position == selectedIndex,
                    isFocused = hasFocus
                )
            }
        }

        fun bind(option: String, isSelected: Boolean) {
            binding.tvTitle.text = option
            applyState(
                isSelected = isSelected,
                isFocused = binding.root.hasFocus()
            )
        }

        private fun applyState(isSelected: Boolean, isFocused: Boolean) {
            if (isSelected && sortDirection >= 0) {
                binding.tvCurrent.text = binding.root.context.getString(
                    if (sortDirection == 1) R.string.adapter_sort_ascending else R.string.adapter_sort_descending
                )
            } else {
                binding.tvCurrent.text = binding.root.context.getString(R.string.adapter_current_selection)
            }
            binding.tvCurrent.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.iconCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            binding.root.isSelected = isSelected
            binding.root.animate()
                .scaleX(if (isFocused) 1.02f else 1f)
                .scaleY(if (isFocused) 1.02f else 1f)
                .setDuration(120L)
                .start()

            val titleColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.colorAccent)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.textColor)
            }
            binding.tvTitle.setTextColor(titleColor)
        }
    }
}
