package com.mytvb.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.core.ui.user.UserBadgeText
import com.mytvb.core.ui.user.UserBadgesStore
import com.mytvb.R
import com.mytvb.databinding.CellFollowUserBinding
import com.mytvb.model.user.FollowingModel
import com.mytvb.core.ui.image.ImageLoader

class FollowUserAdapter(
    private val onItemClick: (FollowingModel) -> Unit,
    private val onItemFocused: ((Int) -> Unit)? = null
) : ListAdapter<FollowingModel, FollowUserAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var focusedPosition = RecyclerView.NO_POSITION

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FollowingModel>() {
            override fun areItemsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return oldItem.mid == newItem.mid
            }

            override fun areContentsTheSame(oldItem: FollowingModel, newItem: FollowingModel): Boolean {
                return oldItem == newItem
            }
        }
    }

    fun setData(newItems: List<FollowingModel>) {
        focusedPosition = focusedPosition
            .takeIf { it != RecyclerView.NO_POSITION && it < newItems.size }
            ?: if (newItems.isEmpty()) RecyclerView.NO_POSITION else 0
        submitList(newItems)
    }

    fun addData(newItems: List<FollowingModel>) {
        submitList(currentList + newItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = CellFollowUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == focusedPosition)
    }

    fun getFocusedPosition(): Int = focusedPosition

    inner class ViewHolder(
        private val binding: CellFollowUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(currentList[position])
                }
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnFocusChangeListener
                }
                if (hasFocus) {
                    val oldPosition = focusedPosition
                    focusedPosition = position
                    onItemFocused?.invoke(position)
                    itemView.post {
                        if (oldPosition != RecyclerView.NO_POSITION && oldPosition != position) {
                            notifyItemChanged(oldPosition)
                        }
                        notifyItemChanged(position)
                    }
                } else if (focusedPosition == position) {
                    itemView.post { notifyItemChanged(position) }
                }
            }
        }

        fun bind(item: FollowingModel, isFocused: Boolean) {
            binding.root.isSelected = isFocused
            binding.textView.isSelected = isFocused
            // 关注接口本身下发 vip（大会员），头像框 pendant 仍只有空间接口才有
            val isVip = item.vip != null && item.vip.vipStatus == 1 && item.vip.vipType > 0
            UserBadgeText.bind(binding.textView, item.uname, isVip)
            binding.textSub.text = item.sign
            binding.textSub.isVisible = item.sign.isNotBlank()

            ImageLoader.loadFastAvatar(
                imageView = binding.imageView,
                url = item.face,
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar,
                source = "FollowUserAdapter.fastAvatar",
                slot = bindingAdapterPosition,
                targetCount = 16
            )
            binding.imageView.setBadge(
                officialVerifyType = item.officialVerify?.type ?: -1,
                vipStatus = item.vip?.vipStatus ?: 0,
                vipType = item.vip?.vipType ?: 0,
                vipAvatarSubscript = item.vip?.avatarSubscript ?: 0
            )
            binding.imageView.tag = item.mid
            binding.imageView.setPendant(null)
            // 头像框仅空间接口下发；只读缓存升级，防列表请求风暴
            UserBadgesStore.enqueue(item.mid, allowFetch = false) { badges ->
                if (binding.imageView.tag != badges.mid) return@enqueue
                binding.imageView.setPendant(badges.pendantUrl)
            }
        }
    }
}
