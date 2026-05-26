package com.tutu.myblbl.feature.player

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
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.feature.player.view.CountdownView

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

    private val autoNextRunnable = Runnable {
        if (!canExecutePendingAction()) {
            return@Runnable
        }
        val sessionId = pendingSessionId ?: return@Runnable
        hideNextPreview(clearPendingAction = false)
        pendingSessionId = null
        onExecutePendingSession(sessionId)
    }

    fun queueNextSession(title: String, coverUrl: String, sessionId: String, delayMs: Long = 5000L) {
        pendingSessionId = sessionId
        showNextPreview(title, coverUrl, delayMs)
        handler.removeCallbacks(autoNextRunnable)
        handler.postDelayed(autoNextRunnable, delayMs)
    }

    fun cancelPendingAction() {
        handler.removeCallbacks(autoNextRunnable)
        val hadPendingAction = pendingSessionId != null
        pendingSessionId = null
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
