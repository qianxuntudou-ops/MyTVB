package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.FragmentHongguoMineBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 红果「我的」页：登录态直达（未登录点开三通道登录弹窗；已登录显示昵称+退出+我的预约）。
 * 红果账号与 B 站完全隔离（票据在 hongguo_session，登出只清红果票据保留设备身份）。
 */
class HongguoMinePage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoMineBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonLogin.setOnClickListener {
            HongguoLoginDialog.show(requireContext(), repository) { refreshLoginState() }
        }
        binding.buttonLogout.setOnClickListener {
            repository.account.logout()
            refreshLoginState()
            AppToast.show(requireContext(), "已退出红果账号")
        }
        binding.buttonSubscribe.setOnClickListener { showSubscribeList() }
        binding.buttonWatchLaterList.setOnClickListener { showWatchLaterList() }
        binding.buttonClear.setOnClickListener {
            repository.clearLocal()
            refreshLoginState()
            AppToast.show(requireContext(), "已清除本机红果数据")
        }
        refreshLoginState()
    }

    /** 侧栏 RIGHT / 顶部 tab DOWN 的落点：未登录→登录按钮，已登录→稍后看。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        val target = if (binding.buttonLogin.isVisible) binding.buttonLogin else binding.buttonWatchLaterList
        return target.requestFocus()
    }

    /** 云端预约列表（subscribe/list，登录态才有数据）：点选条目=取消该预约。 */
    private fun showSubscribeList() {
        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { repository.subscribeList(onlyOnline = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppToast.show(requireContext(), "预约列表拉取失败：${e.message}")
                return@launch
            }
            if (items.isEmpty()) {
                AppToast.show(requireContext(), "暂无云端预约")
                return@launch
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            ChoiceDialog.show(
                requireContext(), "我的预约（选择取消）",
                items.map { item ->
                    val time = if (item.onlineTimeSec > 0) fmt.format(Date(item.onlineTimeSec * 1000)) else "待定"
                    (if (item.isOnline) "已上线 " else "$time 上线 ") + item.title
                }
            ) { index ->
                val target = items.getOrNull(index) ?: return@show
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { repository.cancelSubscribe(target.seriesId) } }
                        .onSuccess { AppToast.show(requireContext(), "已取消预约：${target.title}") }
                        .onFailure { AppToast.show(requireContext(), "取消失败：${it.message}") }
                }
            }
        }
    }

    /** 本地稍后看列表：点选条目弹详情（详情里可继续/移除）。 */
    private fun showWatchLaterList() {
        val items = repository.watchLater()
        if (items.isEmpty()) {
            AppToast.show(requireContext(), getString(R.string.hongguo_watch_later_empty))
            return
        }
        ChoiceDialog.show(
            requireContext(), getString(R.string.hongguo_watch_later),
            items.map { item ->
                val progress = if (item.episodeCount > 0) " · 看到${item.episodeIndex + 1}/${item.episodeCount}集" else ""
                item.title + progress
            }
        ) { index ->
            items.getOrNull(index)?.let { item ->
                HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
            }
        }
    }

    private fun refreshLoginState() {
        val profile = repository.account.storedProfile()
        val stats = "本机收藏 ${repository.favorites().size} 部 · 历史 ${repository.history().size} 条"
        if (profile != null) {
            binding.textNickname.text = profile.name.ifEmpty { "红果用户" }
            binding.textStats.text = stats
            binding.buttonLogin.visibility = View.GONE
            binding.buttonLogout.visibility = View.VISIBLE
            binding.buttonSubscribe.visibility = View.VISIBLE
            // 启动登录态校验+token 滚动（HGTV H0/g.c() scene=boot 同款；失败静默）
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.sync.bootVerify() } }
            }
            if (profile.avatar.startsWith("http")) {
                ImageLoader.load(
                    imageView = binding.imageAvatar,
                    url = profile.avatar,
                    placeholder = R.drawable.ic_hongguo,
                    error = R.drawable.ic_hongguo
                )
            }
        } else {
            binding.textNickname.setText(R.string.hongguo_not_logged_in)
            binding.textStats.text = "游客模式 · $stats"
            binding.buttonLogin.visibility = View.VISIBLE
            binding.buttonLogout.visibility = View.GONE
            binding.buttonSubscribe.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): HongguoMinePage = HongguoMinePage()
    }
}
