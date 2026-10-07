package com.mytvb.ui.dialog

import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.mytvb.core.ui.user.UserBadgeText
import com.mytvb.core.ui.user.UserBadgesStore
import com.mytvb.R
import com.mytvb.databinding.DialogUserInfoBinding
import com.mytvb.model.user.UserDetailInfoModel
import com.mytvb.model.user.UserStatModel
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.UserRepository
import com.mytvb.ui.activity.MainActivity
import com.mytvb.feature.detail.UserSpaceFragment
import com.mytvb.feature.user.FollowUserListFragment
import com.mytvb.core.common.cache.FileCacheManager
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.common.format.NumberUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.mytvb.core.ui.base.DialogWindowFit

class UserInfoDialog(context: Context) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogUserInfoBinding.inflate(LayoutInflater.from(context))
    private val userRepository: UserRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        DialogWindowFit.apply(
            window, context,
            context.resources.getDimensionPixelSize(R.dimen.px620)
        )
        binding.root.setOnClickListener { dismiss() }
        bindUserInfo(sessionGateway.getUserInfo())
        bindUserStat(null)
        initListeners()
        setOnShowListener {
            binding.buttonViewSpace.requestFocus()
        }
        scope.launch {
            refreshUserInfo()
            refreshUserStat()
        }
    }

    private fun initListeners() {
        binding.buttonViewSpace.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(UserSpaceFragment.newInstance(mid), "user_space")
            }
        }
        binding.viewStatFollowing.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(
                    FollowUserListFragment.newInstance(mid, FollowUserListFragment.TYPE_FOLLOWING),
                    "following"
                )
            }
        }
        binding.viewStatFollower.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(
                    FollowUserListFragment.newInstance(mid, FollowUserListFragment.TYPE_FOLLOWER),
                    "follower"
                )
            }
        }
        binding.viewStatDynamic.setOnClickListener {
            val mid = sessionGateway.getUserInfo()?.mid ?: 0L
            if (mid > 0L) {
                openOverlay(UserSpaceFragment.newInstance(mid), "user_space")
            }
        }
        binding.buttonSignOut.setOnClickListener {
            sessionGateway.clearUserSession(reason = "userInfoDialog.signOut")
            FileCacheManager.clearUserCaches()
            dismiss()
        }
    }

    private suspend fun refreshUserInfo() {
        val info = userRepository.refreshCurrentUserInfo().getOrNull() ?: return
        bindUserInfo(info)
    }

    private suspend fun refreshUserStat() {
        val response = runCatching { userRepository.getUserStat() }.getOrNull() ?: return
        if (response.isSuccess) {
            bindUserStat(response.data)
        }
    }

    private fun bindUserInfo(info: UserDetailInfoModel?) {
        ImageLoader.loadCircle(
            imageView = binding.imageAvatar,
            url = info?.face,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
        if (info != null) {
            val oType = info.officialVerify?.type ?: info.official?.let { if (it.role > 0) it.type else -1 } ?: -1
            val vStatus = info.vipStatus.coerceAtLeast(info.vip?.vipStatus ?: 0)
            val vType = info.vipType.coerceAtLeast(info.vip?.vipType ?: 0)
            binding.imageAvatar.setBadge(
                officialVerifyType = oType,
                vipStatus = vStatus,
                vipType = vType
            )
        } else {
            binding.imageAvatar.setBadge(officialVerifyType = -1)
        }
        val isVip = info != null &&
            info.vipStatus.coerceAtLeast(info.vip?.vipStatus ?: 0) > 0
        UserBadgeText.bind(binding.textName, info?.uname.orEmpty().ifBlank { "Nickname" }, isVip)
        // 头像框不在本地会话数据里，按 mid 补齐（单实例无复用错位）
        val selfMid = info?.mid ?: 0L
        binding.imageAvatar.setPendant(null)
        if (selfMid > 0L) {
            UserBadgesStore.enqueue(selfMid) { badges ->
                binding.imageAvatar.setPendant(badges.pendantUrl)
            }
        }
        binding.textVip.text = info?.vipLabel?.text
            .orEmpty()
            .ifBlank { info?.vip?.label?.text.orEmpty() }
            .ifBlank { context.getString(R.string.dialog_normal_member) }
        // 官方等级徽章（硬核会员闪电）
        binding.textLevel.bind(
            info?.levelInfo?.currentLevel,
            isSeniorMember = (info?.isSeniorMember ?: 0) > 0,
        )
        val coinValue = info?.wallet?.bcoinBalance?.takeIf { it > 0 }?.toDouble()
            ?: info?.money
            ?: 0.0
        binding.textCoin.text = context.getString(R.string.coin_number_, coinValue.toFloat())
    }

    private fun bindUserStat(stat: UserStatModel?) {
        binding.textFollowing.text = NumberUtils.formatCount(context, (stat?.following ?: 0).toLong())
        binding.textFollower.text = NumberUtils.formatCount(context, (stat?.follower ?: 0).toLong())
        binding.textDynamic.text = NumberUtils.formatCount(context, (stat?.dynamicCount ?: 0).toLong())
    }

    private fun openOverlay(fragment: Fragment, tag: String) {
        dismiss()
        (findHostActivity() as? MainActivity)?.openOverlayFragment(fragment, tag)
    }

    private fun findHostActivity(): FragmentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
