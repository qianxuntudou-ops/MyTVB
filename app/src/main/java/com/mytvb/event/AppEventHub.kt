package com.mytvb.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AppEventHub {

    sealed interface Event {
        data object UserSessionChanged : Event

        data object NetworkRecovered : Event

        /** 动态页 UP 主排序设置变更（issue#142）：设置页覆盖在动态页上方时底层 Fragment 收不到 onResume，靠事件即时刷新。 */
        data object DynamicUpSortChanged : Event

        data class PlaybackProgressUpdated(
            val aid: Long,
            val cid: Long,
            val progressMs: Long
        ) : Event

        data class EpisodePlaybackProgressUpdated(
            val episodeId: Long,
            val progressMs: Long,
            val episodeIndex: String
        ) : Event

        data class WatchLaterVideoRemoved(
            val aid: Long,
            val bvid: String
        ) : Event

        data class VideoBlockedByMinorProtection(
            val aid: Long,
            val bvid: String
        ) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun dispatch(event: Event) {
        _events.tryEmit(event)
    }
}
