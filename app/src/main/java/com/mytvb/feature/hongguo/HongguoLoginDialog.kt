package com.mytvb.feature.hongguo

import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
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
 *
 * 2026-11-20 重设计（统一 App 既有设计语言，不引入红果专属色）：
 * 通道切换由三个满宽分段大块改成与主界面顶层 tab 同款的下划线页签（选中白字加粗+
 * 强调色短条，聚焦未选中=白字+白条）；三个通道统一收进比弹窗壳更深的内容卡
 * （弹窗壳 thirdBackgroundColor 与按钮默认底几乎同色，按钮会被吃掉）；
 * 扫码=px300 二维码白卡+右侧四级信息列；短信=px560 居中窄列（内嵌发送钮、
 * MFA 提示卡、全宽登录钮）；导入=虚线粘贴框；发送验证码 60s 倒计时置灰保留。
 */
class HongguoLoginDialog(
    context: android.content.Context,
    private val repository: HongguoRepository,
    private val onLoggedIn: () -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    private lateinit var binding: DialogHongguoLoginBinding
    private var pollJob: Job? = null
    private var resendJob: Job? = null
    private var channel = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogHongguoLoginBinding.inflate(LayoutInflater.from(context))
        // 弹窗加宽 px900（池内值）：二维码 px300 保证 2-3 米可扫；高度按内容自适应
        DialogWindowFit.overlay(
            this, binding.root,
            context.resources.getDimensionPixelSize(R.dimen.px900),
            tag = "hongguoLogin"
        )
        binding.buttonLoginClose.setOnClickListener { dismiss() }
        setupChannels()
        setupQr()
        setupSms()
        setupImport()
        startQr()
    }

    override fun show() {
        super.show()
        // 提示浮层默认挂 Activity decor，会被 z 序更高的弹窗窗口压住；
        // 把本窗口注册成 Toast 宿主，浮层就落在弹窗之上（AppToast 自己挑最上层）
        (window?.decorView as? ViewGroup)?.let { AppToast.attachHost(it) }
        // 窗口 attach 后再聚焦（onCreate 时 view 未 attach，requestFocus 会被根容器吃掉）
        binding.tabQr.post { binding.tabQr.requestFocus() }
    }

    override fun dismiss() {
        (window?.decorView as? ViewGroup)?.let { AppToast.detachHost(it) }
        super.dismiss()
    }

    /** 三通道页签 = (页签文字钮, 选中指示条)。焦点落在文字钮上，焦点块由
     *  tab_item_round_background 直接渲染（与首页顶层 tab 同款）。 */
    private fun channelTabs() = listOf(
        binding.tabQr to binding.indQr,
        binding.tabSms to binding.indSms,
        binding.tabImport to binding.indImport
    )

    private fun setupChannels() {
        // 下划线式页签（与主界面顶层 tab 同款）；页签 DOWN 落当前面板首控件，面板 UP 回选中页签
        channelTabs().forEachIndexed { index, (tab, _) ->
            tab.setOnClickListener { selectChannel(index) }
            tab.setOnFocusChangeListener { _, _ -> refreshChannelTabs() }
        }
        selectChannel(0)
    }

    /**
     * 页签态（学习首页顶层 tab）：焦点/选中的文字提亮为 textColorPrimary（白）；
     * 焦点块的"提亮 + accent 描边"由 tab_item_round_background 走 duplicateParentState
     * 自动渲染；指示条只在选中项显示。
     */
    private fun refreshChannelTabs() {
        val grey = ContextCompat.getColor(context, R.color.tab_normal)
        val bright = MaterialColors.getColor(binding.tabQr, android.R.attr.textColorPrimary)
        channelTabs().forEachIndexed { index, (tab, indicator) ->
            val selected = index == channel
            tab.setTextColor(if (selected || tab.hasFocus()) bright else grey)
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    /** 页签切换 + 面板显隐 + 页签↔面板双向焦点链。 */
    private fun selectChannel(index: Int) {
        channel = index
        binding.panelQr.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelSms.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelImport.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 0 && binding.imageQr.drawable == null) startQr()
        refreshChannelTabs()
        // 页签 DOWN 落当前面板首个可聚焦控件
        val firstFocus = when (index) {
            0 -> R.id.button_qr_refresh
            1 -> R.id.edit_phone
            else -> R.id.edit_import
        }
        val tabViews = channelTabs().map { it.first }
        // 页签 UP → 右上关闭钮；关闭钮 DOWN → 回选中页签（遥控可直达关闭）
        tabViews.forEach {
            it.nextFocusDownId = firstFocus
            it.nextFocusUpId = binding.buttonLoginClose.id
        }
        val tabId = tabViews[index].id
        binding.buttonLoginClose.nextFocusDownId = tabId
        when (index) {
            0 -> binding.buttonQrRefresh.nextFocusUpId = tabId
            1 -> {
                // 短信面板上下落点全部接死：输入框自己会吃上下键、输入法也会先吞方向键，
                // 没有明确 nextFocus 就会"进得去出不来"
                binding.editPhone.nextFocusUpId = tabId
                binding.editPhone.nextFocusDownId = binding.editSmsCode.id
                binding.editSmsCode.nextFocusUpId = binding.editPhone.id
                binding.editSmsCode.nextFocusDownId = smsCodeDownTarget()
                binding.buttonSendCode.nextFocusUpId = tabId
                binding.buttonSendCode.nextFocusDownId = binding.buttonSmsLogin.id
                binding.buttonMfaSent.nextFocusUpId = tabId
                binding.buttonMfaSent.nextFocusDownId = binding.buttonSmsLogin.id
                binding.buttonSmsLogin.nextFocusUpId = binding.editSmsCode.id
            }
            else -> {
                binding.editImport.nextFocusUpId = tabId
                // 导入面板只有「粘贴框 → 导入钮」两级
                binding.editImport.nextFocusDownId = binding.buttonImport.id
                binding.buttonImport.nextFocusUpId = binding.editImport.id
            }
        }
    }

    /** 验证码输入框的下一个落点：MFA 提示卡出现时先落「我已发送」，否则落登录钮。 */
    private fun smsCodeDownTarget(): Int =
        if (binding.cardMfa.visibility == View.VISIBLE) binding.buttonMfaSent.id
        else binding.buttonSmsLogin.id

    /**
     * TV 焦点逃生：输入框的上下键一律改成焦点跳转。
     * 两条路都会把焦点困在输入框——多行 EditText 把上下键当光标行移动自己吃掉，
     * 输入法弹起时方向键又先被 IME 消费。左右键仍留给光标移动。
     */
    private fun bindVerticalFocusEscape(edit: View) {
        edit.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            ) {
                val direction =
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                val next = v.focusSearch(direction)
                if (next != null && next !== v) {
                    next.requestFocus()
                    true
                } else false
            } else false
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
                binding.textQrStatus.text = e.message ?: context.getString(R.string.hongguo_qr_failed)
            }
        }
    }

    private fun pollQr(ticket: HongguoAccount.QrTicket) {
        pollJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + ticket.expireSec * 1000L
            while (isActive && System.currentTimeMillis() < deadline && isShowing) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.pollQrStatus(ticket) }
                } catch (e: Exception) {
                    // 网络抖动继续轮询（对照 Go 行为）
                    android.util.Log.w("Hongguo", "qr poll error: ${e.javaClass.simpleName}: ${e.message}")
                }
                when (ticket.status) {
                    2 -> binding.textQrStatus.setText(R.string.hongguo_qr_scanned)
                    3 -> {
                        binding.progressLogin.visibility = View.VISIBLE
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.finishQrLogin(ticket) }
                            AppToast.show(context, context.getString(R.string.hongguo_login_success))
                            onLoggedIn()
                            dismiss()
                            return@launch
                        } catch (e: Exception) {
                            binding.progressLogin.visibility = View.GONE
                            binding.textQrStatus.text = e.message ?: context.getString(R.string.hongguo_login_failed)
                            return@launch
                        }
                    }
                    4 -> { binding.textQrStatus.setText(R.string.hongguo_login_canceled); return@launch }
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
        // showSoftInput 必须 post：焦点回调时 view 尚未完成 focus attach，同步调会被拒
        // （CctvVodSearchDialog 同款）；MuMu 键盘不画另因默认 IME 被重置为搜狗 stub
        binding.editPhone.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.editPhone.post { imm?.showSoftInput(binding.editPhone, 0) }
        }
        binding.editSmsCode.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.editSmsCode.post { imm?.showSoftInput(binding.editSmsCode, 0) }
            else imm?.hideSoftInputFromWindow(binding.editSmsCode.windowToken, 0)
        }
        binding.editSmsCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doSmsLogin()
                true
            } else false
        }
        // 手机号「完成」→ 收键盘并落到验证码框，不用靠方向键硬顶
        binding.editPhone.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                imm?.hideSoftInputFromWindow(binding.editPhone.windowToken, 0)
                binding.editSmsCode.requestFocus()
                true
            } else false
        }
        // 输入框上下键一律改焦点跳转（输入法会先吞方向键）
        bindVerticalFocusEscape(binding.editPhone)
        bindVerticalFocusEscape(binding.editSmsCode)
        binding.buttonSendCode.setOnClickListener {
            val phone = binding.editPhone.text?.toString().orEmpty()
            binding.progressLogin.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.smsSendCode(phone) }
                    AppToast.show(context, context.getString(R.string.hongguo_code_sent))
                    startResendCountdown()
                    binding.editSmsCode.requestFocus()
                } catch (e: Exception) {
                    AppToast.show(context, e.message ?: context.getString(R.string.hongguo_send_failed))
                } finally {
                    binding.progressLogin.visibility = View.GONE
                }
            }
        }
        binding.buttonSmsLogin.setOnClickListener { doSmsLogin() }
        binding.buttonMfaSent.setOnClickListener { doMfaConfirm() }
    }

    /** 发送验证码 60s 倒计时置灰（重设计定案：禁用态防连点+防轰炸）。 */
    private fun startResendCountdown() {
        resendJob?.cancel()
        resendJob = lifecycleScope.launch {
            binding.buttonSendCode.isEnabled = false
            try {
                for (left in RESEND_SECONDS downTo 1) {
                    binding.buttonSendCode.text =
                        context.getString(R.string.hongguo_resend_code, left)
                    delay(1000)
                }
            } finally {
                if (isActive) {
                    binding.buttonSendCode.isEnabled = true
                    binding.buttonSendCode.setText(R.string.hongguo_send_code)
                }
            }
        }
    }

    /** 当前 MFA 短信上行挑战（遇二次验证时缓存，点「我已发送」用）。 */
    private var mfaChallenge: HongguoAccount.MfaChallenge? = null

    private fun doSmsLogin() {
        val phone = binding.editPhone.text?.toString().orEmpty()
        val code = binding.editSmsCode.text?.toString().orEmpty()
        if (phone.isBlank() || code.isBlank()) {
            AppToast.show(context, context.getString(R.string.hongguo_input_phone_code))
            return
        }
        binding.progressLogin.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.account.smsLogin(phone, code) }
                AppToast.show(context, context.getString(R.string.hongguo_login_success))
                onLoggedIn()
                dismiss()
            } catch (e: com.mytvb.feature.hongguo.net.HongguoAccount.MfaRequiredException) {
                showMfa(e.challenge)
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: context.getString(R.string.hongguo_login_failed))
            } finally {
                binding.progressLogin.visibility = View.GONE
            }
        }
    }

    /** MFA 短信上行指引（服务端下发号码+内容，用户拿自己手机发完后点「我已发送」）。 */
    private fun showMfa(challenge: HongguoAccount.MfaChallenge) {
        mfaChallenge = challenge
        binding.textMfaTip.text =
            context.getString(R.string.hongguo_mfa_tip, challenge.smsContent, challenge.channelMobile)
        binding.cardMfa.visibility = View.VISIBLE
        binding.editSmsCode.nextFocusDownId = smsCodeDownTarget()
        binding.buttonMfaSent.requestFocus()
        AppToast.show(context, context.getString(R.string.hongguo_mfa_required))
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
                AppToast.show(context, context.getString(R.string.hongguo_login_success))
                onLoggedIn()
                dismiss()
            } catch (e: com.mytvb.feature.hongguo.net.HongguoAccount.MfaRequiredException) {
                // 服务端更新了短信内容（新挑战），刷新指引继续引导
                showMfa(e.challenge)
            } catch (e: Exception) {
                AppToast.show(context, e.message ?: context.getString(R.string.hongguo_verify_failed))
            } finally {
                binding.progressLogin.visibility = View.GONE
            }
        }
    }

    // ---- 导入 ----

    private fun setupImport() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        binding.editImport.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.editImport.post { imm?.showSoftInput(binding.editImport, 0) }
            else imm?.hideSoftInputFromWindow(binding.editImport.windowToken, 0)
        }
        // 多行粘贴框会把上下键当光标移动自己吃掉 → 统一走焦点逃生；输入法「完成」收键盘回页签
        bindVerticalFocusEscape(binding.editImport)
        binding.editImport.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                imm?.hideSoftInputFromWindow(binding.editImport.windowToken, 0)
                channelTabs().getOrNull(channel)?.first?.requestFocus()
                true
            } else false
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
                    AppToast.show(context, context.getString(R.string.hongguo_import_success))
                    onLoggedIn()
                    dismiss()
                } catch (e: Exception) {
                    AppToast.show(context, e.message ?: context.getString(R.string.hongguo_import_failed))
                } finally {
                    binding.progressLogin.visibility = View.GONE
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
        resendJob?.cancel()
    }

    companion object {
        private const val RESEND_SECONDS = 60

        fun show(
            context: android.content.Context,
            repository: HongguoRepository,
            onLoggedIn: () -> Unit
        ) {
            HongguoLoginDialog(context, repository, onLoggedIn).show()
        }
    }
}
