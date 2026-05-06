package com.tutu.myblbl.feature.settings

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentSignInBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.core.common.cache.FileCacheManager
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.NetworkManager
import com.tutu.myblbl.network.cookie.CookieManager
import okhttp3.Cookie
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.repository.remote.TvAuthRepository
import com.tutu.myblbl.core.ui.base.BaseFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class SignInFragment : BaseFragment<FragmentSignInBinding>() {

    companion object {
        fun newInstance() = SignInFragment()
    }

    private val tvAuthRepository: TvAuthRepository by inject()
    private val appEventHub: AppEventHub by inject()
    private val cookieManager: CookieManager by inject()
    private val userRepository: UserRepository by inject()
    private var authCode = ""
    private var pollingJob: Job? = null
    private val pollingInterval = 1500L

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSignInBinding {
        return FragmentSignInBinding.inflate(inflater, container, false)
    }

    override fun initView() = Unit

    override fun initData() {
        loadQrCode()
    }

    private fun loadQrCode() {
        binding.progressBar.visibility = android.view.View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { NetworkManager.ensureWebFingerprintCookies() }
            try {
                val response = tvAuthRepository.generateTvQrCode()
                binding.progressBar.visibility = android.view.View.GONE

                if (response.isSuccess && response.data != null) {
                    authCode = response.data.authCode
                    val qrUrl = response.data.url
                    displayQrCode(qrUrl)
                    startPolling()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.sign_in_qr_failed_format, response.message.orEmpty()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sign_in_qr_failed_format, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun displayQrCode(url: String) {
        try {
            val bitmap = generateQrCode(url)
            bitmap?.let {
                binding.imageView.setImageBitmap(it)
            }
        } catch (e: Exception) {
            AppLog.e("SignInFragment", "displayQrCode failed", e)
            Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            AppLog.e("SignInFragment", "generateQrCode failed", e)
            null
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && authCode.isNotEmpty()) {
                delay(pollingInterval)
                checkTvPollResult()
            }
        }
    }

    private suspend fun checkTvPollResult() {
        if (authCode.isEmpty()) return

        try {
            val response = tvAuthRepository.pollTvQrCode(authCode)
            when {
                response.code == 0 && response.data != null -> {
                    pollingJob?.cancel()
                    val pollData = response.data
                    // 注入 cookie（直接构建 OkHttp Cookie 对象，保留 httpOnly/secure）
                    pollData.cookieInfo?.cookies?.let { cookies ->
                        val nowMs = System.currentTimeMillis()
                        val cookieObjects = cookies.mapNotNull { c ->
                            val name = c.name.trim()
                            val value = c.value.trim()
                            if (name.isBlank() || value.isBlank()) return@mapNotNull null
                            val expiresSec = c.expires
                            val expiresAt = if (expiresSec > 0L) expiresSec * 1000L else (nowMs + 180L * 24 * 60 * 60 * 1000)
                            Cookie.Builder()
                                .name(name).value(value)
                                .domain("bilibili.com").path("/")
                                .expiresAt(expiresAt)
                                .apply { if (c.secure == 1) secure() }
                                .apply { if (c.httpOnly == 1) httpOnly() }
                                .build()
                        }
                        if (cookieObjects.isNotEmpty()) {
                            cookieManager.saveCookieObjects(cookieObjects)
                        }
                    }
                    // TV 登录不产生 web refresh_token，清空防止后续刷新用无效 token
                    NetworkManager.clearWebRefreshToken()
                    Toast.makeText(requireContext(), "登录成功", Toast.LENGTH_SHORT).show()
                    onLoginSuccess()
                }
                response.code == 86038 -> {
                    // 二维码过期
                    pollingJob?.cancel()
                    loadQrCode()
                }
                response.code == 86101 -> {
                    // 未扫码，继续轮询
                }
                response.code == 86090 -> {
                    // 已扫码待确认，继续轮询
                }
            }
        } catch (e: Exception) {
            AppLog.w("SignInFragment", "TV poll error: ${e.message}")
        }
    }

    private fun onLoginSuccess() {
        FileCacheManager.clearUserCaches()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { NetworkManager.activateAfterLogin() }
            userRepository.refreshCurrentUserInfo()
            parentFragmentManager.popBackStackImmediate()
            appEventHub.dispatch(AppEventHub.Event.UserSessionChanged)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
    }
}
