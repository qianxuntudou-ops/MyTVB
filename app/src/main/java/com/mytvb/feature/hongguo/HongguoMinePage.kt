package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.FragmentHongguoMineBinding

/**
 * 红果「我的」页：登录态直达（未登录点开三通道登录弹窗；已登录显示昵称+退出）。
 * 红果账号与 B 站完全隔离（票据在 hongguo_session，登出只清红果票据保留设备身份）。
 */
class HongguoMinePage : Fragment() {

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
        binding.buttonClear.setOnClickListener {
            repository.clearLocal()
            refreshLoginState()
            AppToast.show(requireContext(), "已清除本机红果数据")
        }
        refreshLoginState()
    }

    private fun refreshLoginState() {
        val profile = repository.account.storedProfile()
        val stats = "本机收藏 ${repository.favorites().size} 部 · 历史 ${repository.history().size} 条"
        if (profile != null) {
            binding.textNickname.text = profile.name.ifEmpty { "红果用户" }
            binding.textStats.text = stats
            binding.buttonLogin.visibility = View.GONE
            binding.buttonLogout.visibility = View.VISIBLE
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
