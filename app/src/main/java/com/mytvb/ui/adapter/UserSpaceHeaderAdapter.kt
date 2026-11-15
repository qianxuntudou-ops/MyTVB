package com.mytvb.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.mytvb.core.ui.tab.enableDirectTabNavigation
import com.mytvb.core.ui.tab.focusSelectedTab
import com.mytvb.core.ui.user.UserBadgeText
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
    private val onMoveToContent: () -> Boolean,
    private val onOrderClick: () -> Unit,
    private val onTabSelected: (index: Int) -> Unit
) : RecyclerView.Adapter<UserSpaceHeaderAdapter.HeaderViewHolder>() {

    enum class FocusTarget {
        FOLLOW,
        FOLLOWING,
        FOLLOWER,
        FILTER
    }

    data class HeaderState(
        val userInfo: UserSpaceInfo? = null,
        val followingCount: Int? = null,
        val followerCount: Int? = null,
        val videoCount: Int = 0,
        val showFollow: Boolean = false,
        @StringRes val followLabelRes: Int = R.string.follow,
        @DrawableRes val followBackgroundRes: Int = R.drawable.bg_hongguo_btn_detail,
        @DrawableRes val followIconRes: Int = R.drawable.ic_plus,
        val followDimmed: Boolean = false,
        // 投稿排序按钮（头部关注按钮左边），列表首屏加载后显示
        @StringRes val orderLabelRes: Int = R.string.model_search_order_pub_date,
        val showOrderButton: Boolean = false,
        // 列表 tab 标题（视频/直播回放/系列/合集，弹窗同款）
        val tabTitles: List<String> = emptyList(),
        val selectedTabIndex: Int = 0
    )

    companion object {
        const val VIEW_TYPE_HEADER = 1001
        // payload 局部刷新标记：DefaultItemAnimator 对带 payload 的 change 原位复用 holder，
        // 不做 cross-fade 新建 itemView——否则切 tab 时 tab 焦点随旧 itemView detach 丢失
        const val STATE_PAYLOAD = "header_state"
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

    override fun onViewAttachedToWindow(holder: HeaderViewHolder) {
        // 列表滚回顶时 header 常从一级缓存复用直接 attach（不重跑 onBindViewHolder），
        // 不在这里恢复 attachedHolder 的话，滚出屏后程序的头部聚焦请求会永远落空
        attachedHolder = holder
        super.onViewAttachedToWindow(holder)
    }

    override fun onViewDetachedFromWindow(holder: HeaderViewHolder) {
        if (attachedHolder === holder) {
            attachedHolder = null
        }
        super.onViewDetachedFromWindow(holder)
    }

    fun submitState(newState: HeaderState) {
        state = newState
        notifyItemChanged(0, STATE_PAYLOAD)
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

    /** 更新排序显示名；列表首屏加载后调用一次以显示按钮。 */
    fun updateOrderLabel(@StringRes labelRes: Int) {
        submitState(state.copy(orderLabelRes = labelRes, showOrderButton = true))
    }

    /** 建列表 tab（视频/直播回放/系列/合集，弹窗同款结构）。 */
    fun submitTabs(titles: List<String>) {
        submitState(state.copy(tabTitles = titles))
    }

    /** 同步 tab 选中态（用户点 tab 时 UI 已切换，这里只刷 state 供重绑恢复）。 */
    fun selectTab(index: Int) {
        submitState(state.copy(selectedTabIndex = index))
    }

    fun isOrderButtonFocused(): Boolean {
        return attachedHolder?.isOrderButtonFocused() ?: false
    }

    /** 焦点是否在列表 tab 上（含 tab 子 view）。 */
    fun isTabsFocused(): Boolean {
        return attachedHolder?.isTabsFocused() ?: false
    }

    /** 聚焦当前选中的列表 tab；header 未挂载或 tab 未显示时返回 false。 */
    fun focusSelectedTab(): Boolean {
        return attachedHolder?.focusSelectedTab() ?: false
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

        // tab 重建/程序 select 期间屏蔽 OnTabSelectedListener 的业务回调，防止 select 循环
        private var isBindingTabs = false
        private var boundTabTitles: List<String>? = null
        private var boundSelectedTabIndex = 0

        init {
            binding.userSpaceTop.buttonFollow.setOnClickListener {
                onFollowClick()
            }
            binding.userSpaceTop.tvFollowing.setOnClickListener {
                onFollowingClick()
            }
            binding.userSpaceTop.tvFollower.setOnClickListener {
                onFollowerClick()
            }
            binding.userSpaceTop.buttonFollow.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOW)
                }
            }
            binding.userSpaceTop.tvFollowing.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOWING)
                }
            }
            binding.userSpaceTop.tvFollower.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FOLLOWER)
                }
            }
            val contentExitListener = View.OnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 头部行 DOWN：先落投稿行排序按钮，按钮未显示才直进列表
                    if (state.showOrderButton && binding.buttonOrder.isShown) {
                        return@OnKeyListener binding.buttonOrder.requestFocus()
                    }
                    return@OnKeyListener onMoveToContent()
                }
                false
            }
            binding.userSpaceTop.buttonFollow.setOnKeyListener(contentExitListener)
            binding.userSpaceTop.tvFollowing.setOnKeyListener(contentExitListener)
            binding.userSpaceTop.tvFollower.setOnKeyListener(contentExitListener)

            binding.buttonOrder.setOnClickListener {
                onOrderClick()
            }
            binding.buttonOrder.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onHeaderFocusChanged(FocusTarget.FILTER)
                }
            }
            binding.buttonOrder.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    // 投稿行（tab 栏上方）：下进 tab 栏、上到关注/粉丝行
                    KeyEvent.KEYCODE_DPAD_DOWN -> binding.tabFilter.focusSelectedTab()
                    KeyEvent.KEYCODE_DPAD_UP -> focusHeaderStatRow()
                    else -> false
                }
            }
            // 注册一次（bind 重建 tab 时不重复注册）
            binding.tabFilter.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (isBindingTabs) return
                    onTabSelected(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }

        fun bind(state: HeaderState) {
            val user = state.userInfo
            val isVip = user?.vip?.vipStatus == 1 && (user.vip?.vipType ?: 0) > 0
            UserBadgeText.bind(binding.userSpaceTop.textTitle, user?.name.orEmpty(), isVip)
            // 官方等级徽章（空间接口直接下发 level）
            binding.userSpaceTop.levelBadge.bind(user?.level, isSeniorMember = false)
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
            binding.userSpaceTop.layoutStatActions.visibility =
                if (state.followingCount != null && state.followerCount != null) View.VISIBLE else View.GONE
            binding.userSpaceTop.tvFollowing.text = buildString {
                append(NumberUtils.formatCount(binding.root.context, (state.followingCount ?: 0).toLong()))
                append("\n")
                append(binding.root.context.getString(R.string.user_following))
            }
            binding.userSpaceTop.tvFollower.text = buildString {
                append(NumberUtils.formatCount(binding.root.context, (state.followerCount ?: 0).toLong()))
                append("\n")
                append(binding.root.context.getString(R.string.user_follower))
            }
            binding.tvVideoCount.text = binding.root.context.getString(
                R.string.user_space_video_count,
                state.videoCount
            )

            binding.buttonOrder.visibility = if (state.showOrderButton) View.VISIBLE else View.GONE
            binding.textOrder.setText(state.orderLabelRes)

            bindListTabs(state)

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
            binding.userSpaceTop.imageAvatar.setPendant(
                state.userInfo?.pendant?.imageEnhance?.takeIf { it.startsWith("http") }
            )

            ImageLoader.loadWideBanner(
                imageView = binding.userSpaceTop.imageTop,
                url = state.userInfo?.topPhoto,
                placeholder = R.drawable.background_image,
                error = R.drawable.background_image
            )
        }

        fun requestFocus(target: FocusTarget): Boolean {
            if (target == FocusTarget.FILTER) {
                if (!state.showOrderButton) return false
                return binding.buttonOrder.requestFocus()
            }
            val view = when (target) {
                FocusTarget.FOLLOW -> binding.userSpaceTop.buttonFollow.takeIf { it.isShown }
                FocusTarget.FOLLOWING -> binding.userSpaceTop.tvFollowing.takeIf { it.isShown }
                FocusTarget.FOLLOWER -> binding.userSpaceTop.tvFollower.takeIf { it.isShown }
                else -> null
            } ?: return false
            return view.requestFocus()
        }

        fun isTabsFocused(): Boolean {
            return binding.tabFilter.hasFocus()
        }

        fun focusSelectedTab(): Boolean {
            if (binding.tabFilter.visibility != View.VISIBLE) return false
            return binding.tabFilter.focusSelectedTab()
        }

        fun isOrderButtonFocused(): Boolean {
            return binding.buttonOrder.hasFocus()
        }

        /**
         * 列表 tab 绑定（视频/直播回放/系列/合集，弹窗同款）：
         * 标题列表变化才重建（重建会清 tab 焦点），仅选中变化时切 select。
         */
        private fun bindListTabs(state: HeaderState) {
            val tabLayout = binding.tabFilter
            tabLayout.visibility = if (state.tabTitles.isEmpty()) View.GONE else View.VISIBLE
            if (state.tabTitles.isEmpty()) return
            if (state.tabTitles != boundTabTitles) {
                isBindingTabs = true
                tabLayout.removeAllTabs()
                state.tabTitles.forEach { title ->
                    tabLayout.addTab(tabLayout.newTab().setText(title))
                }
                tabLayout.enableDirectTabNavigation(
                    onNavigateDown = { onMoveToContent() },
                    // tab 上行到投稿行排序按钮（未显示时退头部行）。
                    // 注意读 adapter 实时 state（this@...），不能用本方法的 state 参数——
                    // tab 只在标题变化时重建重装本监听，捕获当次 bind 的参数快照会拿到
                    // 首屏加载前的旧值（showOrderButton 竞态）
                    onNavigateUp = {
                        if (this@UserSpaceHeaderAdapter.state.showOrderButton &&
                            binding.buttonOrder.isShown
                        ) {
                            binding.buttonOrder.requestFocus()
                        } else {
                            focusHeaderStatRow()
                        }
                    }
                )
                boundTabTitles = state.tabTitles
                isBindingTabs = false
                applyListTabSelection(state)
            } else if (state.selectedTabIndex != boundSelectedTabIndex) {
                applyListTabSelection(state)
            }
        }

        private fun applyListTabSelection(state: HeaderState) {
            boundSelectedTabIndex = state.selectedTabIndex
            val tabLayout = binding.tabFilter
            if (tabLayout.selectedTabPosition != state.selectedTabIndex) {
                isBindingTabs = true
                tabLayout.getTabAt(state.selectedTabIndex)?.select()
                isBindingTabs = false
            }
        }

        /** 筛选栏上方向落点：关注按钮 → 关注/粉丝文本（与 Fragment 顶行 UP 兜底同序）。 */
        private fun focusHeaderStatRow(): Boolean {
            return requestFocus(FocusTarget.FOLLOW) ||
                requestFocus(FocusTarget.FOLLOWING) ||
                requestFocus(FocusTarget.FOLLOWER)
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
