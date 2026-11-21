package com.mytvb.feature.player.douyin

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.PlayerSessionCoordinator
import com.mytvb.feature.player.PlaybackPreloadTarget
import com.mytvb.feature.player.VideoPlayerViewModel
import com.mytvb.feature.player.view.DouyinModePreview
import com.mytvb.feature.player.view.MyPlayerView
import com.mytvb.model.video.VideoModel
import kotlinx.coroutines.launch

private const val TAG = "PlayerActivity"

/**
 * 抖音模式（竖屏连播）的播放编排协调器。
 *
 * 从 PlayerActivity 拆分而来，负责连播手势响应、上下切换、转场动画、
 * 队列预热与预加载、Header 渲染。队列数据管理由 [DouyinModeManager] 负责，
 * 本类只做编排：取下一个 → 触发播放 → 预加载下一条 → 转场动画。
 */
internal class DouyinPlaybackCoordinator(
    private val douyinModeManager: DouyinModeManager,
    private val sessionCoordinator: PlayerSessionCoordinator,
    private val playerView: MyPlayerView,
    private val viewModel: VideoPlayerViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val context: Context,
    private val host: Host
) {

    /** 由 PlayerActivity 实现，提供 Coordinator 无法直接访问的宿主能力。 */
    interface Host {
        fun toast(message: String)
        fun isVideoBlockedByMinorProtection(video: VideoModel): Boolean
    }

    private var sourceAid: Long = 0L
    private var sourceKey: String = ""
    private var initializing: Boolean = false
    private var transitionRunning: Boolean = false
    private var internalNavigationKey: String = ""
    private var pendingNextAfterInit: Boolean = false

    fun isModeActive(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        return douyinModeManager.isApplicable(currentVideo)
    }

    fun handleNext(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        if (!douyinModeManager.hasList() && !initializing) {
            pendingNextAfterInit = true
            startQueueInitialization(currentVideo)
            return true
        }
        if (initializing) {
            pendingNextAfterInit = true
            return true
        }

        val next = douyinModeManager.next()
        if (next == null) {
            // 边界静默：画面飞出后归位即可（对齐红果/CCTV 滑动切换的到头反馈）
            playerView.cancelDouyinPageTransition()
            return true
        }
        playVideo(next, direction = 1)
        return true
    }

    fun handlePrevious(): Boolean {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return false
        if (!douyinModeManager.isApplicable(currentVideo)) return false

        if (!douyinModeManager.hasList()) {
            playerView.cancelDouyinPageTransition()
            return true
        }
        val prev = douyinModeManager.previous()
        if (prev != null) {
            playVideo(prev, direction = -1)
        } else {
            playerView.cancelDouyinPageTransition()
        }
        return true
    }

    fun peekNextPreview(): DouyinModePreview? {
        return douyinModeManager.peekNext()?.toDouyinPreview()
            ?: if (!douyinModeManager.hasList()) {
                DouyinModePreview(title = context.getString(R.string.player_loading_recommendations), coverUrl = "")
            } else {
                null
            }
    }

    fun peekPreviousPreview(): DouyinModePreview? =
        douyinModeManager.peekPrevious()?.toDouyinPreview()

    private fun playVideo(video: VideoModel?, direction: Int = 0) {
        if (video == null) return
        if (host.isVideoBlockedByMinorProtection(video)) {
            host.toast(context.getString(R.string.player_minor_protection_blocked))
            return
        }
        playerView.hideController()
        internalNavigationKey = video.douyinIdentityKey()
        sessionCoordinator.updateCurrentVideo(video)
        renderTargetHeader(video)

        val playTarget = {
            viewModel.playRelatedVideo(video, preferLastPlayTime = false)
        }

        if (direction != 0 && playerView.consumeDouyinGestureTransition()) {
            playTarget()
        } else if (direction != 0 && !transitionRunning) {
            transitionRunning = true
            val transitionStarted = playerView.startDouyinPageTransition(
                direction
            ) {
                playTarget()
                transitionRunning = false
            }
            if (!transitionStarted) {
                playTarget()
                transitionRunning = false
            }
        } else {
            playTarget()
        }

        lifecycleScope.launch {
            douyinModeManager.appendMore()
        }
    }

    fun schedulePreloadAfterPlaybackRequest(playbackRequest: VideoPlayerViewModel.PlaybackRequest) {
        if (!douyinModeManager.hasList()) return
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) return
        val requestMatchesCurrent = when {
            playbackRequest.cid > 0L && currentVideo.cid > 0L -> playbackRequest.cid == currentVideo.cid
            playbackRequest.aid != null && currentVideo.aid > 0L -> playbackRequest.aid == currentVideo.aid
            !playbackRequest.bvid.isNullOrBlank() && currentVideo.bvid.isNotBlank() -> playbackRequest.bvid == currentVideo.bvid
            else -> false
        }
        if (!requestMatchesCurrent) return
        lifecycleScope.launch {
            douyinModeManager.appendMore()
            preloadNext()
        }
    }

    private fun preloadNext() {
        val nextVideo = douyinModeManager.peekNext() ?: return
        val aid = nextVideo.aid.takeIf { it > 0 }
        val bvid = nextVideo.bvid.takeIf { it.isNotBlank() }
        val cid = nextVideo.cid
        if (cid <= 0L) return
        viewModel.preloadPlayback(
            PlaybackPreloadTarget(
                aid = aid,
                bvid = bvid,
                cid = cid,
                source = PlaybackPreloadTarget.Source.DOUYIN_MODE
            )
        )
    }

    private fun renderTargetHeader(video: VideoModel) {
        playerView.setTitle(video.title)
        playerView.setSubTitle(video.authorName.takeIf { it.isNotBlank() }.orEmpty())
    }

    fun resetIfNeeded() {
        val currentVideo = sessionCoordinator.getCurrentVideo()
        val currentAid = currentVideo?.aid ?: 0L
        val currentKey = currentVideo?.douyinIdentityKey().orEmpty()
        if (currentKey.isNotBlank() && currentKey == internalNavigationKey) {
            sourceAid = currentAid
            sourceKey = currentKey
            internalNavigationKey = ""
            return
        }
        // APP 端推荐流不返回 bvid，播放后详情接口会把真实 bvid 回填到当前视频，
        // 若仍按身份串精确比对，每次上下滑都会被误判成「外部换片」而重建队列。
        // 队列内的跳转以 aid 为准（aid 在两条流里都稳定存在）。
        if (isInternalNavigation(currentVideo, currentAid, currentKey)) {
            sourceAid = currentAid
            sourceKey = currentKey
            internalNavigationKey = ""
            return
        }
        if (currentKey.isNotBlank() && currentKey != sourceKey) {
            AppLog.i(TAG, "DouyinQueue reset_external current=${currentVideo?.douyinDebugId().orEmpty()} sourceKey=$sourceKey internalKey=$internalNavigationKey")
            sourceAid = currentAid
            sourceKey = currentKey
            douyinModeManager.reset()
            initializing = false
            internalNavigationKey = ""
            pendingNextAfterInit = false
        }
    }

    /** 当前播放的是否就是队列里刚跳过去的那一条（容忍 bvid 回填导致的身份串变化）。 */
    private fun isInternalNavigation(
        currentVideo: VideoModel?,
        currentAid: Long,
        currentKey: String
    ): Boolean {
        if (currentVideo == null || currentKey.isBlank()) return false
        if (currentAid <= 0L) return false
        return douyinModeManager.currentAid() == currentAid
    }

    fun ensureQueueStarted() {
        val currentVideo = sessionCoordinator.getCurrentVideo() ?: return
        if (!douyinModeManager.isApplicable(currentVideo)) {
            if (douyinModeManager.hasList()) {
                AppLog.i(TAG, "DouyinQueue ensure_reset_unplayable current=${currentVideo.douyinDebugId()}")
                douyinModeManager.reset()
            }
            initializing = false
            return
        }
        // 用户改了「首页推荐来源」：旧队列来自另一条流，直接作废并按新来源重建
        if (douyinModeManager.hasList()) {
            douyinModeManager.resetIfSourceChanged()
        }
        if (douyinModeManager.hasList() || initializing) return

        startQueueInitialization(currentVideo)
    }

    private fun startQueueInitialization(currentVideo: VideoModel) {
        if (initializing) return
        initializing = true
        lifecycleScope.launch {
            douyinModeManager.initialize(currentVideo)
            initializing = false
            val latestVideo = sessionCoordinator.getCurrentVideo()
            if (!currentVideo.isSameDouyinVideo(latestVideo)) {
                AppLog.i(TAG, "DouyinQueue ensure_stale initialized=${currentVideo.douyinDebugId()} latest=${latestVideo?.douyinDebugId().orEmpty()}")
                pendingNextAfterInit = false
                return@launch
            }
            if (!douyinModeManager.hasList()) {
                if (pendingNextAfterInit) {
                    // 边界静默：画面归位即可，无弹跳无 Toast
                    playerView.cancelDouyinPageTransition()
                }
                pendingNextAfterInit = false
                return@launch
            }

            if (pendingNextAfterInit) {
                pendingNextAfterInit = false
                val next = douyinModeManager.next()
                if (next == null) {
                    playerView.cancelDouyinPageTransition()
                } else {
                    playVideo(next, direction = 1)
                }
            } else {
                preloadNext()
            }
        }
    }
}

private fun VideoModel.toDouyinPreview(): DouyinModePreview {
    return DouyinModePreview(
        title = title,
        coverUrl = effectiveCoverUrl
    )
}

private fun VideoModel.douyinDebugId(): String {
    return "aid=$aid,bvid=$bvid,title=${title.take(18)}"
}

private fun VideoModel.douyinIdentityKey(): String {
    val epKey = playbackEpId.takeIf { it > 0L }?.let { "ep:$it" }
    val bvidKey = bvid.takeIf { it.isNotBlank() }?.let { "bvid:$it" }
    val aidKey = aid.takeIf { it > 0L }?.let { "aid:$it" }
    val cidKey = cid.takeIf { it > 0L }?.let { "cid:$it" }
    return listOfNotNull(epKey, bvidKey, aidKey, cidKey).joinToString("|")
}

private fun VideoModel.isSameDouyinVideo(other: VideoModel?): Boolean {
    if (other == null) return false
    return when {
        aid > 0L && other.aid > 0L -> aid == other.aid
        bvid.isNotBlank() && other.bvid.isNotBlank() -> bvid == other.bvid
        else -> false
    }
}
