package com.mytvb.feature.player

import com.mytvb.feature.player.settings.AfterPlayMode
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.model.video.detail.VideoView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSessionCoordinatorPlayQueueTest {

    @Test
    fun playQueueBuildsContinuationForListItemWithoutCid() {
        val coordinator = PlayerSessionCoordinator()
        val next = VideoModel(aid = 2L, bvid = "BV2", title = "next", cid = 0L)
        coordinator.updateCurrentVideo(VideoModel(aid = 1L, bvid = "BV1", title = "current", cid = 101L))
        coordinator.replacePlayQueue(listOf(next))

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.PLAY_QUEUE,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = false,
            nextEpisode = null
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.PlayIntent)
        val intent = (plan as PlayerSessionCoordinator.ContinuationPlan.PlayIntent).intent
        assertEquals(ContinuationPlaybackIntent.Kind.PLAY_QUEUE, intent.kind)
        assertEquals(2L, intent.target.aid)
        assertEquals("BV2", intent.target.bvid)
        assertEquals(0L, intent.target.cid)
    }

    @Test
    fun playQueueSkipsBlockedVideosBeforeBuildingContinuation() {
        val coordinator = PlayerSessionCoordinator()
        val blocked = VideoModel(aid = 2L, bvid = "BV2", title = "blocked")
        val allowed = VideoModel(aid = 3L, bvid = "BV3", title = "allowed")
        coordinator.setContentGate(
            isVideoAllowed = { it.aid != blocked.aid },
            isEpisodeAllowed = { true }
        )
        coordinator.updateCurrentVideo(VideoModel(aid = 1L, bvid = "BV1", title = "current"))
        coordinator.replacePlayQueue(listOf(blocked, allowed))

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.PLAY_QUEUE,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = false,
            nextEpisode = null
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.PlayIntent)
        val intent = (plan as PlayerSessionCoordinator.ContinuationPlan.PlayIntent).intent
        assertEquals(ContinuationPlaybackIntent.Kind.PLAY_QUEUE, intent.kind)
        assertEquals(3L, intent.target.aid)
    }

    @Test
    fun recommendContinuationIgnoresBlockedRelatedVideo() {
        val coordinator = PlayerSessionCoordinator()
        val blocked = VideoModel(aid = 2L, bvid = "BV2", title = "blocked")
        val allowed = VideoModel(aid = 3L, bvid = "BV3", title = "allowed")
        coordinator.setContentGate(
            isVideoAllowed = { it.aid != blocked.aid },
            isEpisodeAllowed = { true }
        )
        coordinator.updateCurrentVideo(VideoModel(aid = 1L, bvid = "BV1", title = "current"))
        coordinator.updateRelatedVideos(listOf(blocked, allowed))

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.RECOMMEND,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = false,
            nextEpisode = null
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.PlayIntent)
        val intent = (plan as PlayerSessionCoordinator.ContinuationPlan.PlayIntent).intent
        assertEquals(ContinuationPlaybackIntent.Kind.RECOMMEND, intent.kind)
        assertEquals(3L, intent.target.aid)
    }

    @Test
    fun blockedNextEpisodeDoesNotBuildContinuation() {
        val coordinator = PlayerSessionCoordinator()
        val nextEpisode = VideoPlayerViewModel.PlayableEpisode(
            cid = 20L,
            title = "blocked",
            aid = 2L,
            bvid = "BV2"
        )
        coordinator.setContentGate(
            isVideoAllowed = { true },
            isEpisodeAllowed = { it.aid != nextEpisode.aid }
        )

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.NEXT_EPISODE,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = true,
            nextEpisode = nextEpisode
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.ShowController)
    }

    @Test
    fun canPlayEpisodeUsesContentGateWithoutChangingEpisodeIndexes() {
        val coordinator = PlayerSessionCoordinator()
        val allowed = VideoPlayerViewModel.PlayableEpisode(cid = 10L, title = "allowed", aid = 1L)
        val blocked = VideoPlayerViewModel.PlayableEpisode(cid = 20L, title = "blocked", aid = 2L)
        coordinator.setContentGate(
            isVideoAllowed = { true },
            isEpisodeAllowed = { it.aid != blocked.aid }
        )

        coordinator.updateEpisodes(listOf(allowed, blocked))

        assertEquals(listOf(allowed, blocked), coordinator.getEpisodes())
        assertTrue(coordinator.canPlayEpisode(0))
        assertTrue(!coordinator.canPlayEpisode(1))
    }

    @Test
    fun nextEpisodeContinuationCarriesVideoModelForCurrentVideoRegistration() {
        // 连播切合集下一集（跨稿件 aid 不同于当前集）：intent 必须携带 video 供宿主在
        // 真正切换时登记 currentVideo，否则 detail 回调以旧视频为 base 合并，评论区等
        // 仍指向上一个视频
        val coordinator = PlayerSessionCoordinator()
        val nextEpisode = VideoPlayerViewModel.PlayableEpisode(
            cid = 20L,
            title = "next episode",
            cover = "http://pic/next.jpg",
            aid = 2L,
            bvid = "BV2",
            duration = 100L
        )

        val plan = coordinator.buildContinuationPlan(
            afterPlayMode = AfterPlayMode.NEXT_EPISODE,
            exitPlayerWhenPlaybackFinished = false,
            hasNextEpisode = true,
            nextEpisode = nextEpisode
        )

        assertTrue(plan is PlayerSessionCoordinator.ContinuationPlan.PlayIntent)
        val intent = (plan as PlayerSessionCoordinator.ContinuationPlan.PlayIntent).intent
        val video = intent.video
        assertTrue("NEXT_EPISODE intent should carry video for currentVideo registration", video != null)
        assertEquals(2L, video?.aid)
        assertEquals("BV2", video?.bvid)
        assertEquals(20L, video?.cid)
    }

    @Test
    fun continuationSwitchThenDetailMergeKeepsNewAid() {
        // 连播切换的完整时序：宿主登记新视频（updateCurrentVideo）→ 新视频 detail
        // 回调合并（updateVideoInfo）→ currentVideo 的 aid 必须是新视频的。
        // 未登记时 merge 会以旧视频为 base 把 aid 钉死，评论区串到上一个视频。
        val coordinator = PlayerSessionCoordinator()
        coordinator.updateCurrentVideo(VideoModel(aid = 1L, bvid = "BV1", title = "old", cid = 101L))
        coordinator.updateCurrentVideo(VideoModel(aid = 2L, bvid = "BV2", title = "new", cid = 202L))
        coordinator.updateVideoInfo(
            VideoDetailModel(view = VideoView(aid = 2L, bvid = "BV2", cid = 202L, title = "new"))
        )
        assertEquals(2L, coordinator.getCurrentVideo()?.aid)
    }
}
