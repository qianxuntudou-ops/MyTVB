package com.tutu.myblbl.feature.player

import com.tutu.myblbl.feature.player.settings.AfterPlayMode
import com.tutu.myblbl.model.video.VideoModel
import java.util.UUID

// 连播倒计时期间唯一可信的播放意图：预热和真正起播都必须复用同一个 id/target。
data class ContinuationPlaybackIntent(
    val id: String = UUID.randomUUID().toString(),
    val mode: AfterPlayMode,
    val kind: Kind,
    val title: String,
    val coverUrl: String,
    val target: PlaybackPreloadTarget,
    val episodeIndex: Int? = null,
    val video: VideoModel? = null,
    val preferLastPlayTime: Boolean = false,
    val startPositionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val replaceInPlace: Boolean = false
) {
    enum class Kind {
        NEXT_EPISODE,
        PLAY_QUEUE,
        RECOMMEND
    }
}
