package com.mytvb.feature.player

import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import com.mytvb.R
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.feature.player.view.CountdownView

class VideoPlayerAutoPlayController(
    private val activity: AppCompatActivity,
    private val viewNext: View,
    private val imageNext: AppCompatImageView,
    private val textNext: TextView,
    private val countdownView: CountdownView,
    private val canExecutePendingAction: () -> Boolean,
    private val onExecutePendingSession: (String) -> Unit,
    private val onPendingActionCleared: () -> Unit = {}
) {
    companion object {
        /** 连播倒计时总时长：片尾提前弹卡的提前量与它对齐（剩余=倒计时长时弹，数完正好衔接切集） */
        const val AUTO_PLAY_COUNTDOWN_MS = 5000L
    }

    init {
        imageNext.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 15f)
            }
        }
        imageNext.clipToOutline = true
    }

    private val handler = Handler(Looper.getMainLooper())
    // 倒计时控件只保存会话 id，不再持有会重新读取播放器状态的 lambda。
    private var pendingSessionId: String? = null

    // 片尾提前弹卡：倒计时数完但视频尚未 ENDED（暂停/缓冲拖慢媒体钟）时置位，
    // 转由 ENDED 事件经 consumeEndedPending() 补执行，两侧谁后到谁触发切集。
    private var countdownElapsed = false

    /** 连播倒计时是否进行中（BACK 链据此决定「取消连播+展示推荐页」分支） */
    val hasPendingAction: Boolean get() = pendingSessionId != null

    private val autoNextRunnable = Runnable {
        val sessionId = pendingSessionId ?: return@Runnable
        if (!canExecutePendingAction()) {
            countdownElapsed = true
            return@Runnable
        }
        executePendingSession(sessionId)
    }

    fun queueNextSession(
        title: String,
        coverUrl: String,
        sessionId: String,
        delayMs: Long = AUTO_PLAY_COUNTDOWN_MS
    ) {
        // 同一目标已在倒计时（片尾提前弹卡后 ENDED 重新分发）：不重排不重数，
        // 让原倒计时数完时衔接切集，否则提前量会被清零退化成播完再数 5 秒。
        if (pendingSessionId == sessionId) {
            return
        }
        pendingSessionId = sessionId
        countdownElapsed = false
        showNextPreview(title, coverUrl, delayMs)
        handler.removeCallbacks(autoNextRunnable)
        handler.postDelayed(autoNextRunnable, delayMs)
    }

    /**
     * STATE_ENDED 到达时消费片尾提前倒计时：已数完挂起则立即切集（数完即衔接），
     * 未数完返回 false（原倒计时继续，数完时 ENDED 已成立自然执行）。
     */
    fun consumeEndedPending(): Boolean {
        val sessionId = pendingSessionId ?: return false
        if (!countdownElapsed) {
            return false
        }
        executePendingSession(sessionId)
        return true
    }

    private fun executePendingSession(sessionId: String) {
        hideNextPreview(clearPendingAction = false)
        pendingSessionId = null
        countdownElapsed = false
        onExecutePendingSession(sessionId)
    }

    fun cancelPendingAction() {
        handler.removeCallbacks(autoNextRunnable)
        val hadPendingAction = pendingSessionId != null
        pendingSessionId = null
        countdownElapsed = false
        if (hadPendingAction) {
            onPendingActionCleared()
        }
    }

    fun hideNextPreview() {
        hideNextPreview(clearPendingAction = true)
    }

    private fun showNextPreview(title: String, coverUrl: String, delayMs: Long) {
        textNext.text = title
        ImageLoader.loadVideoCover(imageNext, coverUrl)
        countdownView.startCountdown(delayMs)
        if (viewNext.isVisible) {
            return
        }
        viewNext.clearAnimation()
        viewNext.visibility = View.VISIBLE
        AnimationUtils.loadAnimation(activity, R.anim.slide_in_to_right).apply {
            viewNext.startAnimation(this)
        }
    }

    private fun hideNextPreview(clearPendingAction: Boolean) {
        handler.removeCallbacks(autoNextRunnable)
        if (clearPendingAction) {
            val hadPendingAction = pendingSessionId != null
            pendingSessionId = null
            if (hadPendingAction) {
                onPendingActionCleared()
            }
        }
        countdownView.stopCountdown()
        if (!viewNext.isVisible) {
            return
        }
        viewNext.clearAnimation()
        AnimationUtils.loadAnimation(activity, R.anim.slide_out_to_right).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    viewNext.visibility = View.GONE
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewNext.startAnimation(this)
        }
    }
}
