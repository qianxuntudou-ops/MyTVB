package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.DialogHongguoLoginBinding
import com.mytvb.feature.hongguo.net.HongguoAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 红果登录弹窗：扫码 / 短信 / 导入 sessionid 三通道（与 B 站账号完全隔离）。
 * 扫码轮询 1.8s（对照 Go account_scan）；成功回调刷新登录态。
 */
class HongguoLoginDialog(
    context: android.content.Context,
    private val repository: HongguoRepository,
    private val onLoggedIn: () -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogHongguoLoginBinding
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogHongguoLoginBinding.inflate(LayoutInflater.from(context))
        DialogWindowFit.overlay(
            this, binding.root,
            context.resources.getDimensionPixelSize(R.dimen.px1000),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px740),
            tag = "hongguoLogin"
        )
        setupChannels()
        setupQr()
        setupSms()
        setupImport()
        startQr()
    }

    override fun show() {
        super.show()
        // 窗口 attach 后再聚焦（onCreate 时 view 未 attach，requestFocus 会被根容器吃掉）
        binding.radioLoginQr.post { binding.radioLoginQr.requestFocus() }
    }

    private fun setupChannels() {
        // TV 页签：点击切换 + 选中态；页签 DOWN 落当前面板首控件，面板 UP 回选中页签
        val tabs = listOf(binding.radioLoginQr, binding.radioLoginSms, binding.radioLoginImport)
        tabs.forEach { tab ->
            tab.setOnClickListener { selectChannel(tab) }
        }
        selectChannel(binding.radioLoginQr)
    }

    /** 页签切换（选中紫实底白字）+ 面板显隐 + 页签↔面板双向焦点链。 */
    private fun selectChannel(selected: android.widget.TextView) {
        val tabs = listOf(binding.radioLoginQr, binding.radioLoginSms, binding.radioLoginImport)
        val grey = context.getColor(R.color.grey)
        tabs.forEach { tab ->
            tab.isSelected = tab === selected
            tab.setTextColor(if (tab.isSelected) android.graphics.Color.WHITE else grey)
        }
        binding.panelQr.visibility =
            if (selected === binding.radioLoginQr) View.VISIBLE else View.GONE
        binding.panelSms.visibility =
            if (selected === binding.radioLoginSms) View.VISIBLE else View.GONE
        binding.panelImport.visibility =
            if (selected === binding.radioLoginImport) View.VISIBLE else View.GONE
        if (selected === binding.radioLoginQr && binding.imageQr.drawable == null) startQr()
        // 页签 DOWN 落当前面板首个可聚焦控件
        val firstFocus = when (selected.id) {
            R.id.radio_login_qr -> R.id.button_qr_refresh
            R.id.radio_login_sms -> R.id.edit_phone
            else -> R.id.edit_import
        }
        tabs.forEach { it.nextFocusDownId = firstFocus }
        // 面板首控件 UP 回选中页签（MFA 指引按钮一并回）
        when (selected.id) {
            R.id.radio_login_qr -> binding.buttonQrRefresh.nextFocusUpId = selected.id
            R.id.radio_login_sms -> {
                binding.editPhone.nextFocusUpId = selected.id
                binding.buttonMfaSent.nextFocusUpId = selected.id
            }
            else -> binding.editImport.nextFocusUpId = selected.id
        }
    }

    // ---- 扫码 ----

    private fun startQr() {
        pollJob?.cancel()
        binding.progressLogin.visibility = View.VISIBLE
        binding.textQrStatus.setText(R.string.hongguo_qr_wait_scan)
        lifecycleScope.launch {
            try {
                val ticket = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.startQrLogin() }
                binding.progressLogin.visibility = View.GONE
                binding.imageQr.setImageBitmap(ticket.bitmap)
                pollQr(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.progressLogin.visibility = View.GONE
                android.util.Log.w("Hongguo", "qr start failed: ${e.javaClass.simpleName}: ${e.message}")
                binding.textQrStatus.text = e.message ?: "二维码获取失败"
            }
        }
    }

    private fun pollQr(ticket: HongguoAccount.QrTicket) {
        pollJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + ticket.expireSec * 1000L
            while (isActive && System.currentTimeMillis() < deadline && isShowing) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.pollQrStatus(ticket) }
                } catch (_: Exception) {
                    // 网络抖动继续轮询（对照 Go 行为）
                }
                when (ticket.status) {
                    2 -> binding.textQrStatus.setText(R.string.hongguo_qr_scanned)
                    3 -> {
                        binding.progressLogin.visibility = View.VISIBLE
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.finishQrLogin(ticket) }
                            AppToast.show(context, "登录成功")
                            onLoggedIn()
                            dismiss()
                            return@launch
                        } catch (e: Exception) {
                            binding.progressLogin.visibility = View.GONE
                            binding.textQrStatus.text = e.message ?: "登录失败"
                            return@launch
                        }
                    }
                    4 -> { binding.textQrStatus.text = "已取消"; return@launch }
                    5 -> { binding.textQrStatus.setText(R.string.hongguo_qr_expired); return@launch }
                }
                delay(1800)
            }
            if (isActive) binding.textQrStatus.setText(R.string.hongguo_qr_expired)
        }
    }

    private fun setupQr() {
        binding.buttonQrRefresh.setOnClickListener { startQr() }
    }

    // ---- 短信 ----

    private fun setupSms() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        binding.editPhone.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) imm?.showSoftInput(binding.editPhone, 0)
        }
        binding.editSmsCode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) imm?.showSoftInput(binding.editSmsCode, 0)
            else imm?.hideSoftInputFromWindow(binding.editSmsCode.windowToken, 0)
        }
        binding.editSmsCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doSmsLogin()
                true
            } else false
        }
        binding.buttonSendCode.setOnClickListener {
            val phone = binding.editPhone.text?.toString().orEmpty()
            binding.progressLogin.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.smsSendCode(phone) }
                    AppToast.show(context, context.getString(R.string.hongguo_code_sent))
                    binding.editSmsCode.requestFocus()
                } catch (e: Exception) {
                    AppToast.show(context, e.message ?: "发送失败")
                } finally {
                    binding.progressLogin.visibility = View.GONE
                }
            }
        }
        binding.buttonSmsLogin.setOnClickListener { doSmsLogin() }
        binding.buttonMfaSent.setOnClickListener { doMfaConfirm() }
    }

    /** 当前 MFA 短信上行挑战（遇二次验证时缓存，点「我已发送」用）。 */
    private var mfaChallenge: HongguoAccount.MfaChallenge? = null

    private fun doSmsLogin() {
        val phone = binding.editPhone.text?.toString().orEmpty()
        val code = binding.editSmsCode.text?.toString().orEmpty()
        if (phone.isBlank() || code.isBlank()) {
            AppToast.show(context, "请输入手机号与验证码")
            return
        }
        binding.progressLogin.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.smsLogin(phone, code) }
                AppToast.show(context, "登录成功")
                onLoggedIn()
                dismiss()
            } catch (e: com.mytvb.feature.hongguo.net.HongguoAccount.MfaRequiredException) {
                showMfa(e.challenge)
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: "登录失败")
            } finally {
                binding.progressLogin.visibility = View.GONE
            }
        }
    }

    /** MFA 短信上行指引（服务端下发号码+内容，用户拿自己手机发完后点「我已发送」）。 */
    private fun showMfa(challenge: HongguoAccount.MfaChallenge) {
        mfaChallenge = challenge
        binding.textMfaTip.text =
            "该账号需要短信上行验证：请用本机手机号编辑短信\n「${challenge.smsContent}」\n发送到 ${challenge.channelMobile}，发送后点击下方按钮"
        binding.textMfaTip.visibility = View.VISIBLE
        binding.buttonMfaSent.visibility = View.VISIBLE
        binding.buttonMfaSent.requestFocus()
        AppToast.show(context, "需要短信上行二次验证")
    }

    private fun doMfaConfirm() {
        val challenge = mfaChallenge ?: return
        val phone = binding.editPhone.text?.toString().orEmpty()
        val code = binding.editSmsCode.text?.toString().orEmpty()
        binding.progressLogin.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repository.account.smsLoginMfa(phone, code, challenge)
                }
                AppToast.show(context, "登录成功")
                onLoggedIn()
                dismiss()
            } catch (e: com.mytvb.feature.hongguo.net.HongguoAccount.MfaRequiredException) {
                // 服务端更新了短信内容（新挑战），刷新指引继续引导
                showMfa(e.challenge)
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: "验证失败")
            } finally {
                binding.progressLogin.visibility = View.GONE
            }
        }
    }

    // ---- 导入 ----

    private fun setupImport() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        binding.editImport.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) imm?.showSoftInput(binding.editImport, 0)
            else imm?.hideSoftInputFromWindow(binding.editImport.windowToken, 0)
        }
        binding.buttonImport.setOnClickListener {
            val raw = binding.editImport.text?.toString().orEmpty()
            if (raw.isBlank()) {
                AppToast.show(context, context.getString(R.string.hongguo_login_import_hint))
                return@setOnClickListener
            }
            binding.progressLogin.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.importSession(raw) }
                    AppToast.show(context, "导入成功")
                    onLoggedIn()
                    dismiss()
                } catch (e: Exception) {
                    AppToast.show(context, e.message ?: "导入失败")
                } finally {
                    binding.progressLogin.visibility = View.GONE
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
    }

    companion object {
        fun show(
            context: android.content.Context,
            repository: HongguoRepository,
            onLoggedIn: () -> Unit
        ) {
            HongguoLoginDialog(context, repository, onLoggedIn).show()
        }
    }
}
