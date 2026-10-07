package com.mytvb.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.CellUserSpaceHeaderBinding
import com.mytvb.model.user.UserSpaceInfo
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils

class UserSpaceHeaderAdapter(
    private val onFollowClick: () -> Unit,
    private val onFollowingClick: () -> Unit,
    private val onFollowerClick: () -> Unit,
    private val onHeaderFocusChanged: (FocusTarget) -> Unit,
    private val onMoveToContent: () -> Boolean
) : RecyclerView.Adapter<UserSpaceHeaderAdapter.HeaderViewHolder>() {

    enum class FocusTarget {
        FOLLOW,
        FOLLOWING,
        FOLLOWER
    }

    data class HeaderState(
        val userInfo: UserSpaceInfo? = null,
        val followingCount: Int? = null,
        val followerCount: Int? = null,
        val videoCount: Int = 0,
        val showFollow: Boolean = false,
        @StringRes val followLabelRes: Int = R.string.follow,
        @DrawableRes val followBackgroundRes: Int = R.drawable.bg_follow_space_btn,
        @DrawableRes val followIconRes: Int = R.drawable.ic_plus,
        val followDimmed: Boolean = false
    )

    companion object {
        const val VIEW_TYPE_HEADER = 1001
        val FOLLOW_DIM_COLOR = android.graphics.Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
    }

    private var state = HeaderState()
    private var attachedHolder: HeaderViewHolder? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = 1

    override fun getItemId(position: Int): Long = Long.MIN_VALUE

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_HEADER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = CellUserSpaceHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(state)
        attachedHolder = holder
    }

    override fun onViewDetachedFromWindow(holder: HeaderViewHolder) {
        if (attachedHolder === holder) {
            attachedHolder = null
        }
        super.onViewDetachedFromWindow(holder)
    }

    fun submitState(newState: HeaderState) {
        state = newState
        notifyItemChanged(0)
    }

    fun updateUserInfo(info: UserSpaceInfo?, showFollow: Boolean) {
        submitState(
            state.copy(
                userInfo = info,
                showFollow = showFollow
            )
        )
    }

    fun updateStat(following: Int?, follower: Int?) {
        submitState(
            state.copy(
                followingCount = following,
                followerCount = follower
            )
        )
    }

    fun updateVideoCount(count: Int) {
        submitState(state.copy(videoCount = count))
    }

    fun updateFollowState(
        @StringRes labelRes: Int,
        @DrawableRes backgroundRes: Int,
        @DrawableRes iconRes: Int = R.drawable.ic_plus,
        dimmed: Boolean = false
    ) {
        submitState(
            state.copy(
                followLabelRes = labelRes,
                followBackgroundRes = backgroundRes,
                followIconRes = iconRes,
                followDimmed = dimmed
            )
        )
    }

    fun requestFocus(target: FocusTarget): Boolean {
        val holder = attachedHolder ?: return false
        return holder.requestFocus(target)
    }

    fun hasVisibleFollowAction(): Boolean {
        return state.showFollow
    }

    inner class HeaderViewHolder(
        private val binding: CellUserSpaceHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.userSpaceTop.buttonFollow.setOnClickListener {
                onFollowClick()
            }
            binding.tvFollowing.setOnClickListener {
                onFollowingClick()
            }
            binding.tvFollower.setOnClickListener {
                onFollowerClick()
            }
            binding.userSpaceTop.buttonFollow.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOW)
                }
            }
            binding.tvFollowing.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOWING)
                }
            }
            binding.tvFollower.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOWER)
                }
            }
            val contentExitListener = View.OnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return@OnKeyListener onMoveToContent()
                }
                false
            }
            binding.userSpaceTop.buttonFollow.setOnKeyListener(contentExitListener)
            binding.tvFollowing.setOnKeyListener(contentExitListener)
            binding.tvFollower.setOnKeyListener(contentExitListener)
        }

        fun bind(state: HeaderState) {
            binding.userSpaceTop.textTitle.text = state.userInfo?.name.orEmpty()
            binding.userSpaceTop.textSubtitle.text =
                state.userInfo?.sign?.takeIf { it.isNotBlank() }
                    ?: binding.root.context.getString(R.string.adapter_default_sign)
            binding.userSpaceTop.buttonFollow.visibility = if (state.showFollow) {
                View.VISIBLE
            } else {
                View.GONE
            }
            binding.userSpaceTop.buttonFollow.setText(state.followLabelRes)
            binding.userSpaceTop.buttonFollow.setBackgroundResource(state.followBackgroundRes)
            bindFollowIcon(state.followIconRes, state.followDimmed)
            binding.layoutStatActions.visibility =
                if (state.followingCount != null && state.followerCount != null) View.VISIBLE else View.GONE
            binding.tvFollowing.text = buildString {
                append(NumberUtils.formatCount(binding.root.context, (state.followingCount ?: 0).toLong()))
                append("\n")
                append(binding.root.context.getString(R.string.user_following))
            }
            binding.tvFollower.text = buildString {
                append(NumberUtils.formatCount(binding.root.context, (state.followerCount ?: 0).toLong()))
                append("\n")
                append(binding.root.context.getString(R.string.user_follower))
            }
            binding.tvVideoCount.text = binding.root.context.getString(
                R.string.user_space_video_count,
                state.videoCount
            )

            val faceUrl = state.userInfo?.face
            if (faceUrl.isNullOrBlank()) {
                ImageLoader.loadDrawableRes(
                    imageView = binding.userSpaceTop.imageAvatar,
                    resId = R.drawable.default_avatar,
                    circleCrop = true
                )
            } else {
                ImageLoader.loadCircle(
                    imageView = binding.userSpaceTop.imageAvatar,
                    url = faceUrl,
                    placeholder = R.drawable.default_avatar,
                    error = R.drawable.default_avatar
                )
            }
            binding.userSpaceTop.imageAvatar.setBadge(
                officialVerifyType = if (state.userInfo?.official?.role != null && state.userInfo.official.role > 0) state.userInfo.official.type else -1,
                vipStatus = state.userInfo?.vip?.vipStatus ?: 0,
                vipType = state.userInfo?.vip?.vipType ?: 0,
                vipAvatarSubscript = state.userInfo?.vip?.avatarSubscript ?: 0
            )

            ImageLoader.loadWideBanner(
                imageView = binding.userSpaceTop.imageTop,
                url = state.userInfo?.topPhoto,
                placeholder = R.drawable.background_image,
                error = R.drawable.background_image
            )
        }

        fun requestFocus(target: FocusTarget): Boolean {
            val view = when (target) {
                FocusTarget.FOLLOW -> binding.userSpaceTop.buttonFollow.takeIf { it.isShown }
                FocusTarget.FOLLOWING -> binding.tvFollowing.takeIf { it.isShown }
                FocusTarget.FOLLOWER -> binding.tvFollower.takeIf { it.isShown }
            } ?: return false
            return view.requestFocus()
        }

        /** 官方描边式按钮的左侧图标（+ / ✓），随已关注态弱化。 */
        private fun bindFollowIcon(iconRes: Int, dimmed: Boolean) {
            val context = binding.root.context
            val size = context.resources.getDimensionPixelSize(R.dimen.px30)
            val icon = androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(if (dimmed) FOLLOW_DIM_COLOR else android.graphics.Color.WHITE)
                setBounds(0, 0, size, size)
            }
            binding.userSpaceTop.buttonFollow.setCompoundDrawables(icon, null, null, null)
            binding.userSpaceTop.buttonFollow.setTextColor(
                if (dimmed) FOLLOW_DIM_COLOR else android.graphics.Color.WHITE
            )
        }
    }
}
