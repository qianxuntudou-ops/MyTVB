package com.mytvb.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent

import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.fragment.app.commit
import androidx.media3.common.util.UnstableApi
import com.mytvb.R
import com.mytvb.databinding.ActivityPlayerBinding
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.common.ext.toast
import com.mytvb.core.common.settings.ExitDoubleConfirm
import com.mytvb.feature.player.LivePlayerFragment
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.feature.player.shortcut.PlayerShortcutAction
import com.mytvb.feature.player.shortcut.PlayerShortcutKeyDispatcher
import com.mytvb.feature.player.shortcut.ShortcutSettingKeys
import com.mytvb.feature.player.shortcut.hasTextInputFocus
import com.mytvb.feature.player.view.MyPlayerView
import com.mytvb.core.ui.base.AppToast

@OptIn(UnstableApi::class)
class LivePlayerActivity : BaseActivity<ActivityPlayerBinding>() {

    private var exitTime: Long = 0
    private val exitInterval = 2000L

    /** 青少年模式：直播期间每 15 秒结算观看时长，达上限触发休息退出。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val teenModeTicker = object : Runnable {
        override fun run() {
            com.mytvb.core.common.content.TeenModeTimer.tick()
            if (com.mytvb.core.common.content.TeenModeTimer.isResting()) {
                val restMin = com.mytvb.core.common.content.TeenModeTimer.getRestLimitMin().coerceAtLeast(1)
                toast(getString(R.string.activity_teen_rest_needed_format, restMin))
                finish()
                return
            }
            // 公益广告触发：启动锁死播放，原直播进入后台自动暂停，回来 onResume 恢复
            if (com.mytvb.core.common.content.TeenModeTimer.checkAndConsumePsasTrigger()) {
                com.mytvb.core.common.content.PsasRepository.launchRandomPsas(this@LivePlayerActivity)
            }
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    override fun getViewBinding(): ActivityPlayerBinding =
        ActivityPlayerBinding.inflate(layoutInflater)

    // ================= 自定义快捷键（issue#116）：直播宿主 =================

    private val shortcutKeyDispatcher = PlayerShortcutKeyDispatcher(
        isTextInputActive = { hasTextInputFocus() },
    ) { performShortcutAction(it) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shortcutKeyDispatcher.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun performShortcutAction(action: PlayerShortcutAction) {
        val playerView: MyPlayerView? = findViewById(R.id.playerView)
        when (action) {
            PlayerShortcutAction.DANMAKU -> {
                val enabled = !(playerView?.isDanmakuEnabled() ?: true)
                playerView?.setDanmakuEnabled(enabled)
                AppToast.show(this, "${getString(R.string.dm_switch)}：${getString(if (enabled) R.string.on else R.string.off)}")
            }
            PlayerShortcutAction.SMART_SHIELD -> playerView?.toggleDmSmartShield()
            PlayerShortcutAction.MERGE_DUPLICATE -> playerView?.toggleDmMergeDuplicate()
            PlayerShortcutAction.SUPER_CHAT -> toggleShortcutSwitch(
                ShortcutSettingKeys.SHOW_LIVE_SUPER_CHAT,
                getString(R.string.show_live_super_chat),
                current = PlayerSettingsStore.load(this).showLiveSuperChat,
            ) { enabled -> playerView?.setLiveSuperChatEnabled(enabled) }
            PlayerShortcutAction.ALWAYS_CLOCK -> toggleShortcutSwitch(
                ShortcutSettingKeys.SHOW_ALWAYS_ON_CLOCK,
                getString(R.string.show_always_on_clock),
                current = PlayerSettingsStore.load(this).showAlwaysOnClock,
            ) { enabled -> playerView?.showAlwaysOnClock(enabled) }
            PlayerShortcutAction.DOUBLE_BACK_EXIT -> toggleShortcutSwitch(
                ExitDoubleConfirm.KEY,
                getString(R.string.exit_double_confirm),
                current = ExitDoubleConfirm.enabled(appSettings),
            ) { }
            else -> AppToast.show(this, R.string.shortcut_not_supported_here)
        }
    }

    /** 开关型动作通用：落盘（中文"开/关"口径）+ 即时生效回调 + Toast 反馈。 */
    private fun toggleShortcutSwitch(key: String, title: String, current: Boolean, apply: (Boolean) -> Unit) {
        val newValue = !current
        appSettings.putStringAsync(key, if (newValue) "开" else "关")
        apply(newValue)
        AppToast.show(this, "$title：${getString(if (newValue) R.string.on else R.string.off)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1L)
        if (roomId <= 0) return finish()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 退出二次确认（设置默认开）：关闭后单次 BACK 直接退出
                if (!ExitDoubleConfirm.enabled(appSettings) ||
                    System.currentTimeMillis() - exitTime <= exitInterval
                ) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    AppToast.show(applicationContext, R.string.activity_exit_player_hint)
                }
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.player_container, LivePlayerFragment.newInstance(roomId))
            }
        }
        // 青少年模式：启动观看时长定时器
        mainHandler.postDelayed(teenModeTicker, 15_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(teenModeTicker)
    }

    companion object {
        private const val EXTRA_ROOM_ID = "room_id"

        fun start(context: Context, roomId: Long) {
            // 青少年模式：休息期间拦截直播入口
            com.mytvb.core.common.content.TeenModeTimer.consumeBlockReason(context)?.let {
                context.toast(it)
                return
            }
            context.startActivity(Intent(context, LivePlayerActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
            })
        }
    }
}
