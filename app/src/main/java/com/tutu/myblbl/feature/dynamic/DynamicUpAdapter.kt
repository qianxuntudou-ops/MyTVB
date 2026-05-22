package com.tutu.myblbl.feature.dynamic

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.CellFollowingBinding
import com.tutu.myblbl.model.user.FollowingModel
import com.tutu.myblbl.core.ui.image.ImageLoader

class DynamicUpAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onItemFocused: (() -> Unit)? = null,
    private val onLeftEdge: () -> Boolean = { false },
    private val onRightEdge: () -> Boolean = { false },
    private val debugTag: String? = null
) : RecyclerView.Adapter<DynamicUpAdapter.ViewHolder>() {

    private val items = ArrayList<FollowingModel>()
    private var selectedPosition = 0
    private var avatarLoadsEnabled = false

    init {
        setHasStableIds(true)
    }

    fun setData(list: List<FollowingModel>) {
        items.clear()
        items.addAll(list)
        selectedPosition = selectedPosition.coerceIn(0, (list.lastIndex).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun getData(): List<FollowingModel> = items.toList()

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        if (oldPosition in 0 until itemCount) {
            notifyItemChanged(oldPosition)
        }
        if (position in 0 until itemCount) {
            notifyItemChanged(position)
        }
    }

    fun getSelectedPosition(): Int = selectedPosition

    fun setAvatarLoadsEnabled(enabled: Boolean) {
        if (avatarLoadsEnabled == enabled) return
        avatarLoadsEnabled = enabled
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val item = items.getOrNull(position) ?: return RecyclerView.NO_ID
        return when {
            item.mid > 0L -> item.mid
            item.uname.isNotBlank() -> item.uname.hashCode().toLong()
            else -> position.toLong()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellFollowingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    inner class ViewHolder(
        private val binding: CellFollowingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    items.getOrNull(position)?.let(onItemClick)
                }
            }
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.textName.isSelected = hasFocus
                if (hasFocus) {
                    onItemFocused?.invoke()
                }
            }
            binding.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> onLeftEdge()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> onRightEdge()
                    else -> false
                }
            }
        }

        fun bind(item: FollowingModel, isSelected: Boolean) {
            binding.root.isSelected = isSelected
            binding.indicator.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.textName.text = if (item.mid == 0L) {
                binding.root.context.getString(R.string.all_dynamic)
            } else {
                item.uname
            }

            if (item.mid == 0L) {
                ImageLoader.clear(binding.imageAvatar)
                binding.imageAvatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.imageAvatar.setImageResource(R.drawable.ic_dynamic)
                binding.imageAvatar.setBadge(officialVerifyType = -1)
            } else {
                if (avatarLoadsEnabled || isSelected) {
                    ImageLoader.loadCircle(
                        imageView = binding.imageAvatar,
                        url = item.face,
                        placeholder = R.drawable.default_avatar,
                        error = R.drawable.default_avatar
                    )
                } else {
                    ImageLoader.clear(binding.imageAvatar)
                    binding.imageAvatar.setImageResource(R.drawable.default_avatar)
                }
                binding.imageAvatar.setBadge(
                    officialVerifyType = item.officialVerify?.type ?: -1
                )
            }
        }


    }
}
