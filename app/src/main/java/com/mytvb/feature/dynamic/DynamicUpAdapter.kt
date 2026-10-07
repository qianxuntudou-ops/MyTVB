package com.mytvb.feature.dynamic

import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.CellFollowingBinding
import com.mytvb.model.user.FollowingModel
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.user.UserBadgeText
import com.mytvb.core.ui.user.UserBadgesStore

class DynamicUpAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onItemFocused: (() -> Unit)? = null,
    private val onLeftEdge: () -> Boolean = { false },
    private val onRightEdge: () -> Boolean = { false },
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
            // 关注接口本身下发 vip（大会员），头像框 pendant 仍只有空间接口才有
            val isVip = item.vip != null && item.vip.vipStatus == 1 && item.vip.vipType > 0
            val displayName = if (item.mid == 0L) {
                binding.root.context.getString(R.string.all_dynamic)
            } else {
                item.uname
            }
            UserBadgeText.bind(binding.textName, displayName, isVip && item.mid != 0L)

            if (item.mid == 0L) {
                ImageLoader.clear(binding.imageAvatar)
                binding.imageAvatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.imageAvatar.setImageResource(R.drawable.ic_dynamic)
                binding.imageAvatar.setBadge(officialVerifyType = -1)
                binding.imageAvatar.tag = 0L
                binding.imageAvatar.setPendant(null)
            } else {
                if (avatarLoadsEnabled || isSelected) {
                    ImageLoader.loadFastAvatar(
                        imageView = binding.imageAvatar,
                        url = item.face,
                        placeholder = R.drawable.default_avatar,
                        error = R.drawable.default_avatar,
                        source = "DynamicUpAdapter.fastAvatar",
                        slot = bindingAdapterPosition,
                        targetCount = 8
                    )
                } else {
                    ImageLoader.clear(binding.imageAvatar)
                    binding.imageAvatar.setImageResource(R.drawable.default_avatar)
                }
                binding.imageAvatar.setBadge(
                    officialVerifyType = item.officialVerify?.type ?: -1,
                    vipStatus = item.vip?.vipStatus ?: 0,
                    vipType = item.vip?.vipType ?: 0,
                    vipAvatarSubscript = item.vip?.avatarSubscript ?: 0
                )
                binding.imageAvatar.tag = item.mid
                binding.imageAvatar.setPendant(null)
                // 头像框仅空间接口下发；只读缓存升级，防列表请求风暴
                UserBadgesStore.enqueue(item.mid, allowFetch = false) { badges ->
                    if (binding.imageAvatar.tag != badges.mid) return@enqueue
                    binding.imageAvatar.setPendant(badges.pendantUrl)
                }
            }
        }


    }
}
