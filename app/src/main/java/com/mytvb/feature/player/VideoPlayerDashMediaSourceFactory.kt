package com.mytvb.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@OptIn(UnstableApi::class)
internal class VideoPlayerDashMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val urlNormalizer: (String) -> String
) {

    /**
     * createMediaSource 的带 CDN state 返回版本：把本次创建的 failover state 一并吐出，
     * 供外层（ViewModel/Controller）在卡顿时调用 penalizeCurrentHost 降权。
     */
    internal data class MediaSourceWithCdnState(
        val mediaSource: MediaSource,
        val cdnFailoverStates: List<VideoPlayerCdnFailoverState>
    )

    companion object {
    }

    private val loadErrorPolicy = object : LoadErrorHandlingPolicy {
        override fun getFallbackSelectionFor(
            options: LoadErrorHandlingPolicy.FallbackOptions,
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): LoadErrorHandlingPolicy.FallbackSelection? = null

        override fun getRetryDelayMsFor(
            errorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long = 500L

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
    }

    fun createMediaSource(route: DashRoute): MediaSource = createMediaSourceWithCdnState(route).mediaSource

    fun createMediaSourceWithCdnState(route: DashRoute): MediaSourceWithCdnState {
        val videoSource = createProgressiveSource(
            urls = route.videoUrls,
            mimeType = route.videoRepresentation.mimeType,
            bitrateBps = route.videoRepresentation.bandwidth,
            isVideoStream = true
        )

        val audioSource = route.audioRepresentation?.let {
            if (route.audioUrls.isNotEmpty()) {
                createProgressiveSource(
                    urls = route.audioUrls,
                    mimeType = it.mimeType,
                    bitrateBps = it.bandwidth,
                    isVideoStream = false
                )
            } else null
        }

        val mediaSource = if (audioSource != null) {
            MergingMediaSource(true, videoSource.mediaSource, audioSource.mediaSource)
        } else {
            videoSource.mediaSource
        }
        return MediaSourceWithCdnState(
            mediaSource = mediaSource,
            cdnFailoverStates = buildList {
                addAll(videoSource.cdnFailoverStates)
                addAll(audioSource?.cdnFailoverStates.orEmpty())
            }
        )
    }

    private fun createProgressiveSource(
        urls: List<String>,
        mimeType: String,
        bitrateBps: Long,
        isVideoStream: Boolean
    ): MediaSourceWithCdnState {
        val primaryUrl = urls.firstOrNull()
            ?: error("No media url candidates available")
        val (sourceFactory, state) = createCandidateAwareFactory(urls, bitrateBps, isVideoStream)
        val mediaItem = MediaItem.Builder()
            .setUri(primaryUrl)
            .setMimeType(mimeType.takeIf { it.isNotBlank() })
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(sourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(mediaItem)
        return MediaSourceWithCdnState(
            mediaSource = mediaSource,
            cdnFailoverStates = listOfNotNull(state)
        )
    }

    private fun createCandidateAwareFactory(
        urls: List<String>,
        bitrateBps: Long,
        isVideoStream: Boolean
    ): Pair<DataSource.Factory, VideoPlayerCdnFailoverState?> {
        // 构建时刻按最新画像+流码率排序：route.videoUrls 在 resolveDashRoutePlan
        // 时已排过一次，但 fallback 换线重建复用同一 route 对象，构建时重排才能吃到两次构建之间
        // 新记的卡顿/超时惩罚——否则重建后 candidates[0] 还是那个刚被降权的烂 host，换线换了个
        // 寂寞（S65 现场：hwo1 被记 30+ 次首帧超时后，fallback:dash 重建 attempt=1 仍是它）。
        // 初次构建时排序幂等（同一时刻分数不变）。
        val candidates = CdnProfile.rankCandidates(
            urls
                .map(urlNormalizer)
                .filter { it.isNotBlank() }
                .distinct(),
            bitrateBps
        )
        if (candidates.size <= 1) {
            return dataSourceFactory to null
        }
        val state = VideoPlayerCdnFailoverState(
            candidates = candidates.map(Uri::parse),
            streamBitrateBps = bitrateBps,
            isVideoStream = isVideoStream
        )
        return VideoPlayerCdnFailoverDataSourceFactory(
            upstreamFactory = dataSourceFactory,
            state = state
        ) to state
    }
}
