package com.mytvb.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1L)
        if (roomId <= 0) return finish()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 退出二次确认（设置默认开）：关闭后单次 BACK 直接退出
                if (!ExitDoubleConfirm.enabled(appSettings) ||
                    System.currentTimeMillis() - exitTime <= exitInterval) {
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
