package com.mytvb.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.BaseVideoAdapter
import com.mytvb.core.ui.base.BaseVideoViewHolder
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.video.resolveThemedTextColor
import com.mytvb.databinding.CellStaffMemberBinding
import com.mytvb.model.video.detail.Staff
import java.util.concurrent.atomic.AtomicInteger

/**
 * 创作团队成员卡 adapter（issue#151 联合投稿）：头像+名字+角色胶囊。
 * 「UP主」角色用 B 站粉，其余角色（参演/配音/后期…）用次级文字色。
 */
class StaffMemberAdapter(
    private val onMemberClick: (Staff) -> Unit
) : BaseVideoAdapter<Staff, StaffMemberAdapter.MemberViewHolder>() {

    private val contentViewType = nextViewType.getAndIncrement()

    override fun itemKey(item: Staff): String = "staff:${item.mid}:${item.title}"

    override fun onCreateContentViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = CellStaffMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MemberViewHolder(binding, onMemberClick)
    }

    override fun getContentItemViewType(position: Int): Int = contentViewType

    override fun onBindContentViewHolder(holder: MemberViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.bind(item)
    }

    private companion object {
        private val nextViewType = AtomicInteger(0x5A0200)
    }

    class MemberViewHolder(
        private val binding: CellStaffMemberBinding,
        onMemberClick: (Staff) -> Unit
    ) : BaseVideoViewHolder(binding.root) {

        private var currentItem: Staff? = null

        init {
            // 长按无菜单（BaseVideoViewHolder.showCardMenu 空实现），UP 事件后不触发点击
            binding.root.setOnClickListener {
                if (longPressTriggered) {
                    longPressTriggered = false
                    return@setOnClickListener
                }
                currentItem?.let(onMemberClick)
            }
        }

        fun bind(staff: Staff) {
            currentItem = staff
            binding.textMemberName.text = staff.name
            // 角色必有（UP主/参演/配音…），空串兜底隐藏胶囊
            if (staff.title.isBlank()) {
                binding.textMemberRole.visibility = android.view.View.GONE
            } else {
                binding.textMemberRole.visibility = android.view.View.VISIBLE
                binding.textMemberRole.text = staff.title
                // 「UP主」= B 站粉胶囊白字（官方 App 同款）；其余角色灰字胶囊弱化
                if (staff.title == UP_OWNER_TITLE) {
                    binding.textMemberRole.setTextColor(android.graphics.Color.WHITE)
                    binding.textMemberRole.setBackgroundResource(R.drawable.bg_comment_up_badge)
                } else {
                    binding.textMemberRole.setTextColor(
                        resolveThemedTextColor(
                            binding.root.context,
                            android.R.attr.textColorSecondary,
                            0xFFCCCCCC.toInt()
                        )
                    )
                }
            }
            ImageLoader.loadCircle(
                imageView = binding.imageAvatar,
                url = staff.face,
                placeholder = R.drawable.default_avatar,
                error = R.drawable.default_avatar
            )
            binding.imageAvatar.setBadge(officialVerifyType = staff.official?.role ?: -1)
            binding.imageAvatar.tag = staff.mid
            binding.imageAvatar.setPendant(null)
        }

        private companion object {
            const val UP_OWNER_TITLE = "UP主"
        }
    }
}
