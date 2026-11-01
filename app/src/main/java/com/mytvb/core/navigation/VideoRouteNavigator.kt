package com.mytvb.core.navigation

import android.content.Context
import android.content.ContextWrapper
import com.mytvb.model.video.HistoryVideoModel
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.activity.LivePlayerActivity
import com.mytvb.ui.activity.MainActivity
import com.mytvb.ui.activity.PlayerActivity
import com.mytvb.feature.detail.VideoDetailFragment
import com.mytvb.feature.series.SeriesDetailFragment
import com.mytvb.feature.player.PlaybackStartupTrace
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.core.common.ext.isOpenDetailFirstEnabled
import com.mytvb.core.common.ext.toast
import com.mytvb.core.common.log.AppLog

object VideoRouteNavigator {

    private const val TAG = "VideoRouteNav"

    fun openVideo(
        context: Context,
        video: VideoModel,
        playQueue: List<VideoModel> = emptyList(),
        seekPositionMs: Long = 0L,
        startEpisodeIndex: Int = -1,
        forcePlayer: Boolean = false
    ) {
        val traceId = PlaybackStartupTrace.newTraceId()
        val traceStartElapsedMs = PlaybackStartupTrace.nowMs()
        PlaybackStartupTrace.log(
            traceId = traceId,
            startElapsedMs = traceStartElapsedMs,
            step = "card_click",
            message = "aid=${video.aid} bvid=${video.bvid} cid=${video.cid} title=${video.title.take(32)}"
        )
        AppLog.d(TAG, "openVideo: bvid=${video.bvid}, aid=${video.aid}, title=${video.title}, forcePlayer=$forcePlayer")
        // 青少年模式：休息期间拦截视频入口
        com.mytvb.core.common.content.TeenModeTimer.consumeBlockReason()?.let {
            context.toast(it)
            return
        }
        if (!video.hasPlaybackIdentity) {
            AppLog.w(TAG, "openVideo blocked: missing playback identity, title=${video.title}, cid=${video.cid}")
            context.toast("当前卡片数据不完整，暂时无法播放")
            return
        }
        if (!forcePlayer && shouldOpenVideoDetailFirst(video)) {
            val hostActivity = findMainActivityHost(context)
            AppLog.d(TAG, "openVideo: detailFirst=true, host=$hostActivity")
            if (hostActivity != null) {
                // PGC（番剧/影视）条目必须走番剧详情页：UGC 详情页用 x/web-interface
                // 接口加载，番剧单集返回的数据没有 ugcSeason，选集/预告/花絮整个
                // 不显示（v2.1.14 反馈：历史页点进看过的番剧下方空白）。历史条目的
                // seasonId 由 uri(/bangumi/play/ssXXXX) 解析、epId=history.epid。
                if (video.isPgc && video.playbackSeasonId > 0L) {
                    AppLog.d(TAG, "openVideo: pgc route seasonId=${video.playbackSeasonId} epId=${video.playbackEpId}")
                    hostActivity.openInHostContainer(
                        SeriesDetailFragment.newInstance(
                            seasonId = video.playbackSeasonId,
                            epId = video.playbackEpId
                        )
                    )
                    return
                }
                hostActivity.openInHostContainer(VideoDetailFragment.newInstance(video, playQueue))
                return
            }
        }
        AppLog.d(TAG, "openVideo: starting PlayerActivity")
        val shouldUseHistoryProgress = seekPositionMs <= 0L &&
            PlayerSettingsStore.load(context).resumePlayback
        PlayerActivity.start(
            context = context,
            video = video,
            seekPositionMs = seekPositionMs.takeIf { it > 0L }
                ?: if (shouldUseHistoryProgress) video.historyProgress * 1000L else 0L,
            playQueue = playQueue,
            startEpisodeIndex = startEpisodeIndex,
            startupTraceId = traceId,
            startupTraceStartElapsedMs = traceStartElapsedMs
        )
    }

    /**
     * 显式打开视频详情页（标签/简介/互动数据），不受“显示视频详情页”设置开关限制，
     * 供长按菜单等用户明确表达意图的入口使用。
     */
    fun openVideoDetail(
        context: Context,
        video: VideoModel,
        playQueue: List<VideoModel> = emptyList()
    ): Boolean {
        val hostActivity = findMainActivityHost(context)
        if (hostActivity != null) {
            hostActivity.openInHostContainer(VideoDetailFragment.newInstance(video, playQueue))
            return true
        }
        // 播放器等独立 Activity 宿主拿不到主页容器：带参拉起主页直达详情
        // （CLEAR_TOP 复用栈底主页并替换其上的播放器）。此入口语义是「看详情」，
        // 失败也不能兜底成播放。
        return MainActivity.startWithVideoDetail(context, video)
    }

    fun openHistory(
        context: Context,
        historyVideo: HistoryVideoModel,
        playQueue: List<VideoModel> = emptyList(),
        forcePlayer: Boolean = false
    ) {
        resolveLiveRoomId(historyVideo)?.let { roomId ->
            LivePlayerActivity.start(context, roomId)
            return
        }
        openVideo(
            context = context,
            video = historyVideo.toVideoModel(),
            playQueue = playQueue,
            forcePlayer = forcePlayer
        )
    }

    private fun shouldOpenVideoDetailFirst(video: VideoModel): Boolean {
        if (!isOpenDetailFirstEnabled()) {
            return false
        }
        if (video.isLive || video.roomId > 0L || video.historyBusiness == "live") {
            return false
        }
        return video.aid > 0L || video.bvid.isNotBlank()
    }

    private fun resolveLiveRoomId(historyVideo: HistoryVideoModel): Long? {
        val history = historyVideo.history ?: return null
        if (history.business != "live") {
            return null
        }
        return history.oid.takeIf { it > 0L }
    }

    private fun findMainActivityHost(context: Context): MainActivity? {
        var current: Context? = context
        while (current != null) {
            when (current) {
                is MainActivity -> {
                    if (!current.isFinishing && !current.isDestroyed) {
                        return current
                    }
                    return null
                }
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}
