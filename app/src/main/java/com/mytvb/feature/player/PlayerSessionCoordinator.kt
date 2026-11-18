package com.mytvb.feature.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.settings.AfterPlayMode
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.detail.VideoDetailModel
import java.util.ArrayDeque

@OptIn(UnstableApi::class)
class PlayerSessionCoordinator {

    private companion object {
        const val TAG = "PlayerSession"
    }

    sealed interface ContinuationPlan {
        data class PlayIntent(val intent: ContinuationPlaybackIntent) : ContinuationPlan

        object ExitPlayer : ContinuationPlan

        object ShowController : ContinuationPlan
    }

    private var launchContext: PlayerLaunchContext? = null
    private var launchVideo: VideoModel? = null
    private var currentVideo: VideoModel? = null
    private var launchStartEpisodeIndex: Int = -1
    private val launchQueue = ArrayDeque<VideoModel>()

    private var episodes: List<VideoPlayerViewModel.PlayableEpisode> = emptyList()
    private var selectedEpisodeIndex: Int = 0
    private var relatedVideos: List<VideoModel> = emptyList()
    private var isVideoAllowed: (VideoModel) -> Boolean = { true }
    private var isEpisodeAllowed: (VideoPlayerViewModel.PlayableEpisode) -> Boolean = { true }

    fun setContentGate(
        isVideoAllowed: (VideoModel) -> Boolean,
        isEpisodeAllowed: (VideoPlayerViewModel.PlayableEpisode) -> Boolean
    ) {
        this.isVideoAllowed = isVideoAllowed
        this.isEpisodeAllowed = isEpisodeAllowed
        launchVideo = launchVideo?.takeIf(::isAllowedVideo)
        currentVideo = currentVideo?.takeIf(::isAllowedVideo)
        relatedVideos = relatedVideos.filter(::isAllowedVideo)
        replacePlayQueue(launchQueue.toList())
    }

    fun consumeLaunchContext(arguments: Bundle?): PlayerLaunchContext? {
        val resolved = resolveLaunchContext(arguments) ?: return null
        launchContext = resolved
        launchStartEpisodeIndex = resolved.startEpisodeIndex
        if (launchStartEpisodeIndex >= 0 && selectedEpisodeIndex == 0) {
            selectedEpisodeIndex = launchStartEpisodeIndex
        }
        return resolved
    }

    fun resolveLaunchContext(arguments: Bundle?): PlayerLaunchContext? {
        val args = arguments ?: return null
        return PlayerLaunchContext.create(
            aid = args.getLong(VideoPlayerFragment.ARG_AID, 0L),
            bvid = args.getString(VideoPlayerFragment.ARG_BVID).orEmpty(),
            cid = args.getLong(VideoPlayerFragment.ARG_CID, 0L),
            epId = args.getLong(VideoPlayerFragment.ARG_EP_ID, 0L),
            seasonId = args.getLong(VideoPlayerFragment.ARG_SEASON_ID, 0L),
            seekPositionMs = args.getLong(VideoPlayerFragment.ARG_SEEK_POSITION_MS, 0L),
            startEpisodeIndex = args.getInt(VideoPlayerFragment.ARG_START_EPISODE, -1)
        )
    }

    fun updateVideoInfo(info: VideoDetailModel?) {
        val detailView = info?.view
        trimQueueAgainstCurrent(detailView?.toLaunchVideoModel())
        if (detailView != null) {
            currentVideo = mergeCurrentVideo(currentVideo ?: launchVideo, detailView)
        }
    }

    fun updateCurrentVideo(video: VideoModel?) {
        currentVideo = video?.takeIf(::isAllowedVideo)
    }

    fun updateEpisodes(value: List<VideoPlayerViewModel.PlayableEpisode>) {
        episodes = value
    }

    fun updateSelectedEpisodeIndex(index: Int) {
        selectedEpisodeIndex = index
    }

    fun updateRelatedVideos(value: List<VideoModel>) {
        relatedVideos = value.filter(::isAllowedVideo)
    }

    fun replacePlayQueue(playQueue: List<VideoModel>) {
        launchQueue.clear()
        launchQueue.addAll(playQueue.filter(::isAllowedVideo))
    }

    fun getLaunchVideo(): VideoModel? = launchVideo

    fun getCurrentVideo(): VideoModel? = currentVideo ?: launchVideo

    fun getEpisodes(): List<VideoPlayerViewModel.PlayableEpisode> = episodes

    fun getSelectedEpisodeIndex(): Int = selectedEpisodeIndex

    fun getSelectedEpisode(): VideoPlayerViewModel.PlayableEpisode? = episodes.getOrNull(selectedEpisodeIndex)

    fun canPlayEpisode(index: Int): Boolean {
        val episode = episodes.getOrNull(index) ?: return false
        return isAllowedEpisode(episode)
    }

    fun buildContinuationPlan(
        afterPlayMode: AfterPlayMode,
        exitPlayerWhenPlaybackFinished: Boolean,
        hasNextEpisode: Boolean,
        nextEpisode: VideoPlayerViewModel.PlayableEpisode?
    ): ContinuationPlan {
        fun finishPlan(): ContinuationPlan {
            return if (exitPlayerWhenPlaybackFinished) {
                ContinuationPlan.ExitPlayer
            } else {
                ContinuationPlan.ShowController
            }
        }

        return when (afterPlayMode) {
            AfterPlayMode.NOTHING -> finishPlan()
            AfterPlayMode.RECOMMEND -> {
                val related = nextRelatedVideo()
                AppLog.i(TAG, "continuation recommend pool=${relatedVideos.size} next=${related?.bvid ?: "none"}")
                val target = related?.toPreloadTarget(PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN)
                if (related != null && target != null) {
                    ContinuationPlan.PlayIntent(
                        ContinuationPlaybackIntent(
                            mode = afterPlayMode,
                            kind = ContinuationPlaybackIntent.Kind.RECOMMEND,
                            title = related.title,
                            coverUrl = related.coverUrl,
                            target = target,
                            video = related
                        )
                    )
                } else {
                    finishPlan()
                }
            }
            AfterPlayMode.PLAY_QUEUE -> {
                trimQueueAgainstCurrent(getCurrentVideo())
                val queuedVideo = pollNextAllowedQueuedVideo()
                val target = queuedVideo?.toPreloadTarget(PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN)
                if (queuedVideo != null && target != null) {
                    ContinuationPlan.PlayIntent(
                        ContinuationPlaybackIntent(
                            mode = afterPlayMode,
                            kind = ContinuationPlaybackIntent.Kind.PLAY_QUEUE,
                            title = queuedVideo.title,
                            coverUrl = queuedVideo.coverUrl,
                            target = target,
                            video = queuedVideo
                        )
                    )
                } else {
                    finishPlan()
                }
            }
            AfterPlayMode.NEXT_EPISODE -> {
                // 严格按“播放合集中的下一个”执行；没有下一集时不再兜底播队列或推荐。
                if (hasNextEpisode && nextEpisode != null && isAllowedEpisode(nextEpisode)) {
                    ContinuationPlan.PlayIntent(
                        ContinuationPlaybackIntent(
                            mode = afterPlayMode,
                            kind = ContinuationPlaybackIntent.Kind.NEXT_EPISODE,
                            title = nextEpisode.title,
                            coverUrl = nextEpisode.cover,
                            target = PlaybackPreloadTarget(
                                aid = nextEpisode.aid.takeIf { it > 0L },
                                bvid = nextEpisode.bvid.takeIf { it.isNotBlank() },
                                cid = nextEpisode.cid,
                                epId = nextEpisode.epId.takeIf { it > 0L },
                                seasonId = nextEpisode.seasonId.takeIf { it > 0L },
                                source = PlaybackPreloadTarget.Source.AUTOPLAY_COUNTDOWN
                            ),
                            episodeIndex = selectedEpisodeIndex + 1,
                            // 连播执行时由宿主登记 currentVideo（合集分集可能跨稿件，
                            // aid 与上一集不同，缺了会让评论区等仍指向上一个视频）
                            video = VideoModel(
                                aid = nextEpisode.aid,
                                bvid = nextEpisode.bvid,
                                cid = nextEpisode.cid,
                                title = nextEpisode.title,
                                pic = nextEpisode.cover,
                                duration = nextEpisode.duration
                            )
                        )
                    )
                } else {
                    finishPlan()
                }
            }
        }
    }

    private fun nextRelatedVideo(): VideoModel? {
        val current = getCurrentVideo()
        return relatedVideos.firstOrNull { candidate ->
            isAllowedVideo(candidate) && (current == null || !isSameVideo(candidate, current))
        }
    }

    private fun pollNextAllowedQueuedVideo(): VideoModel? {
        while (launchQueue.isNotEmpty()) {
            val video = launchQueue.pollFirst() ?: continue
            if (isAllowedVideo(video)) return video
        }
        return null
    }

    private fun trimQueueAgainstCurrent(current: VideoModel?) {
        val currentVideo = current ?: return
        while (launchQueue.peekFirst()?.let { isSameVideo(it, currentVideo) } == true) {
            launchQueue.removeFirst()
        }
    }

    private fun isPlayableVideo(video: VideoModel): Boolean {
        return video.hasPlaybackIdentity
    }

    private fun isAllowedVideo(video: VideoModel): Boolean {
        return isPlayableVideo(video) && isVideoAllowed(video)
    }

    private fun isAllowedEpisode(episode: VideoPlayerViewModel.PlayableEpisode): Boolean {
        return isEpisodeAllowed(episode)
    }

    private fun isSameVideo(left: VideoModel, right: VideoModel): Boolean {
        return when {
            left.playbackEpId > 0L && right.playbackEpId > 0L -> left.playbackEpId == right.playbackEpId
            left.bvid.isNotBlank() && right.bvid.isNotBlank() -> left.bvid == right.bvid
            left.aid > 0L && right.aid > 0L -> left.aid == right.aid
            left.cid > 0L && right.cid > 0L -> left.cid == right.cid
            else -> left.title == right.title && left.coverUrl == right.coverUrl
        }
    }

    private fun mergeCurrentVideo(
        base: VideoModel?,
        detailView: com.mytvb.model.video.detail.VideoView
    ): VideoModel {
        val current = base
        val preferredCoverUrl = current?.coverUrl
            ?.takeIf { it.isNotBlank() }
            ?: detailView.pic.takeIf { it.isNotBlank() }
            ?: ""
        return VideoModel(
            aid = current?.aid?.takeIf { it > 0L } ?: detailView.aid,
            bvid = current?.bvid
                ?.takeIf { it.isNotBlank() }
                ?: detailView.bvid,
            cid = detailView.cid.takeIf { it > 0L } ?: current?.cid ?: 0L,
            title = detailView.title
                .takeIf { it.isNotBlank() }
                ?: current?.title.orEmpty(),
            pic = preferredCoverUrl,
            cover = preferredCoverUrl,
            desc = detailView.desc
                .takeIf { it.isNotBlank() }
                ?: current?.desc.orEmpty(),
            duration = detailView.duration.takeIf { it > 0L } ?: current?.duration ?: 0L,
            pubDate = detailView.pubDate.takeIf { it > 0L } ?: current?.pubDate ?: 0L,
            createTime = detailView.createTime.takeIf { it > 0L } ?: current?.createTime ?: 0L,
            owner = detailView.owner ?: current?.owner,
            stat = detailView.stat ?: current?.stat,
            bangumi = current?.bangumi,
            pages = current?.pages,
            ugcSeason = current?.ugcSeason,
            play = current?.play ?: 0L,
            videoReview = current?.videoReview ?: 0L,
            typeName = current?.typeName.orEmpty(),
            typeId = current?.typeId ?: 0,
            dynamicText = current?.dynamicText.orEmpty(),
            dimension = detailView.dimension ?: current?.dimension,
            isLive = current?.isLive ?: false,
            roomId = current?.roomId ?: 0L,
            isFollowed = current?.isFollowed ?: false,
            isLike = current?.isLike ?: false,
            isCoin = current?.isCoin ?: false,
            isFavorite = current?.isFavorite ?: false,
            epid = current?.epid ?: 0L,
            sid = current?.sid ?: 0L,
            seasonType = current?.seasonType ?: 0,
            isOgv = current?.isOgv ?: false,
            redirectUrl = detailView.redirectUrl.takeIf { it.isNotBlank() } ?: current?.redirectUrl.orEmpty(),
            teenageMode = current?.teenageMode ?: 0,
            historyProgress = current?.historyProgress ?: 0L,
            historyViewAt = current?.historyViewAt ?: 0L,
            historyBadge = current?.historyBadge.orEmpty(),
            historyBusiness = current?.historyBusiness.orEmpty(),
            isUpowerExclusive = detailView.isUpowerExclusive || current?.isUpowerExclusive ?: false,
            isChargingArc = detailView.isChargingArc || current?.isChargingArc ?: false,
            elecArcType = detailView.elecArcType.takeIf { it > 0 } ?: current?.elecArcType ?: 0,
            elecArcBadge = detailView.elecArcBadge.ifBlank { current?.elecArcBadge.orEmpty() },
            privilegeType = detailView.privilegeType.takeIf { it > 0 } ?: current?.privilegeType ?: 0
        )
    }

    private fun com.mytvb.model.video.detail.VideoView.toLaunchVideoModel(): VideoModel {
        return VideoModel(
            aid = aid,
            bvid = bvid,
            title = title,
            pic = pic,
            cid = cid,
            desc = desc,
            duration = duration,
            pubDate = pubDate,
            owner = owner,
            stat = stat,
            isUpowerExclusive = isUpowerExclusive,
            isChargingArc = isChargingArc,
            elecArcType = elecArcType,
            elecArcBadge = elecArcBadge,
            privilegeType = privilegeType
        )
    }

    private fun VideoModel.toPreloadTarget(source: PlaybackPreloadTarget.Source): PlaybackPreloadTarget? {
        val targetAid = aid.takeIf { it > 0L }
        val targetBvid = bvid.takeIf { it.isNotBlank() }
        val targetEpId = playbackEpId.takeIf { it > 0L }
        if (targetAid == null && targetBvid.isNullOrBlank() && targetEpId == null) {
            return null
        }
        return PlaybackPreloadTarget(
            aid = targetAid,
            bvid = targetBvid,
            cid = cid,
            epId = targetEpId,
            seasonId = playbackSeasonId.takeIf { it > 0L },
            source = source
        )
    }
}
