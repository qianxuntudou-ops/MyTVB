package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.fragment.app.Fragment
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.FragmentHongguoMineBinding

/** 红果「我的」页：P1 游客态账号中心（登录三通道 P2）。 */
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
        binding.textStats.text = "本机收藏 ${repository.favorites().size} 部 · 历史 ${repository.history().size} 条"
        binding.buttonLogin.setOnClickListener {
            // P1：登录方式弹窗占位（扫码/短信/导入参数三通道 P2 实现）
            AppToast.show(requireContext(), getString(R.string.hongguo_login_p2))
        }
        binding.buttonClear.setOnClickListener {
            repository.clearLocal()
            repository.let { }
            binding.textStats.text = "本机收藏 0 部 · 历史 0 条"
            AppToast.show(requireContext(), "本机红果数据已清除")
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
