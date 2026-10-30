package com.mytvb.feature.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList
import com.mytvb.core.common.log.AppLog
import java.util.concurrent.atomic.AtomicLong

/**
 * 无缝清晰度切换的全局目标状态。
 *
 * PlayerInstancePool 的播放器实例带自定义 TrackSelector（见 [SeamlessQualityTrackSelectionFactory]），
 * 视频轨选择由这里的 target(qn, codecid) 决定；换源时由 VideoPlayerViewModel 重置。
 * 只有当前 MediaSource 是多清晰度 DASH MPD（见 SeamlessDashMediaSourceFactory）时
 * target 才会生效，其余源（Progressive/Merging/直播 HLS）的 format.id 无法解析，
 * TrackSelection 自动退回系统默认行为。
 */
@UnstableApi
object SeamlessQualitySelector {
    private val generation = AtomicLong()
    @Volatile
    private var targetQn = 0
    @Volatile
    private var targetCodecid = 0

    @Synchronized
    fun setTarget(qn: Int, codecid: Int) {
        if (targetQn == qn && targetCodecid == codecid) return
        targetQn = qn
        targetCodecid = codecid
        generation.incrementAndGet()
    }

    fun clearTarget() = setTarget(0, 0)

    fun targetQn(): Int = targetQn

    fun targetCodecid(): Int = targetCodecid

    fun generation(): Long = generation.get()
}

/**
 * 接管多清晰度 DASH MPD 的视频轨选择：目标 (qn, codecid) 命中时锁定该 Representation，
 * 并在目标变化后丢弃队列里旧清晰度的已缓冲 chunk（保留约 1.5s 平滑过渡），
 * 使下一个 chunk 直接按新清晰度下载——全程不重建 MediaSource、不黑屏。
 * 非无缝源（format.id 解析不出 qn/codec）一律走 super 的默认自适应逻辑。
 */
@UnstableApi
class SeamlessQualityTrackSelectionFactory : AdaptiveTrackSelection.Factory() {

    override fun createAdaptiveTrackSelection(
        group: TrackGroup,
        tracks: IntArray,
        type: Int,
        bandwidthMeter: BandwidthMeter,
        adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>
    ): AdaptiveTrackSelection {
        val codecid = commonCodecid(group, tracks)
        return if (codecid > 0) {
            UserControlledQualityTrackSelection(group, tracks, bandwidthMeter, codecid)
        } else {
            super.createAdaptiveTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints)
        }
    }

    private fun commonCodecid(group: TrackGroup, tracks: IntArray): Int {
        var codecid = 0
        for (track in tracks) {
            val parsed = parseVideoRepresentationId(group.getFormat(track).id) ?: return 0
            if (codecid == 0) {
                codecid = parsed.second
            } else if (codecid != parsed.second) {
                return 0
            }
        }
        return codecid
    }
}

@UnstableApi
private class UserControlledQualityTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    bandwidthMeter: BandwidthMeter,
    private val codecid: Int
) : AdaptiveTrackSelection(group, tracks, bandwidthMeter) {

    private var selectedIndex = findTargetIndex()
    private var selectionReason = C.SELECTION_REASON_INITIAL
    private var appliedGeneration = SeamlessQualitySelector.generation()

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: MutableList<out MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>
    ) {
        val targetIndex = findTargetIndex()
        if (targetIndex != selectedIndex) {
            AppLog.i(
                TAG,
                "seamless track switch oldQn=${qnOf(getFormat(selectedIndex))} " +
                    "newQn=${qnOf(getFormat(targetIndex))} codecid=$codecid bufferedUs=$bufferedDurationUs"
            )
            selectedIndex = targetIndex
            selectionReason = C.SELECTION_REASON_ADAPTIVE
        }
        appliedGeneration = SeamlessQualitySelector.generation()
    }

    override fun evaluateQueueSize(playbackPositionUs: Long, queue: MutableList<out MediaChunk>): Int {
        val generation = SeamlessQualitySelector.generation()
        if (queue.isEmpty() || (generation == appliedGeneration && queueMatchesTarget(queue))) {
            return queue.size
        }
        val targetQn = SeamlessQualitySelector.targetQn()
        if (SeamlessQualitySelector.targetCodecid() != codecid || targetQn <= 0) return queue.size
        if (findExactTargetIndex() < 0) return queue.size

        for (i in 0 until queue.size) {
            val chunk = queue[i]
            val durationBeforeChunkUs = chunk.startTimeUs - playbackPositionUs
            if (durationBeforeChunkUs < MIN_BUFFER_TO_RETAIN_US) continue
            if (qnOf(chunk.trackFormat) != targetQn) {
                AppLog.i(
                    TAG,
                    "seamless discard old-quality queue from=$i size=${queue.size} " +
                        "retainUs=$durationBeforeChunkUs targetQn=$targetQn"
                )
                return i
            }
        }
        return queue.size
    }

    override fun getSelectedIndex(): Int = selectedIndex

    override fun getSelectionReason(): Int = selectionReason

    override fun getSelectionData(): Any? = null

    private fun findTargetIndex(): Int {
        val exact = findExactTargetIndex()
        return if (exact >= 0) exact else selectedIndexInBounds()
    }

    private fun findExactTargetIndex(): Int {
        val targetQn = SeamlessQualitySelector.targetQn()
        if (targetQn > 0 && SeamlessQualitySelector.targetCodecid() == codecid) {
            for (index in 0 until length()) {
                val parsed = parseVideoRepresentationId(getFormat(index).id)
                if (parsed != null && parsed.first == targetQn && parsed.second == codecid) return index
            }
        }
        return -1
    }

    private fun selectedIndexInBounds(): Int =
        if (selectedIndex in 0 until length()) selectedIndex else 0

    private fun queueMatchesTarget(queue: MutableList<out MediaChunk>): Boolean {
        val targetQn = SeamlessQualitySelector.targetQn()
        if (targetQn <= 0 || SeamlessQualitySelector.targetCodecid() != codecid) return true
        val last = queue[queue.size - 1]
        return qnOf(last.trackFormat) == targetQn
    }

    private fun qnOf(format: Format): Int =
        parseVideoRepresentationId(format.id)?.first ?: -1

    companion object {
        private const val TAG = "SeamlessQuality"
        private const val MIN_BUFFER_TO_RETAIN_US = 1_500_000L
    }
}

/**
 * 无缝清晰度的组间钦点选择器：锁定目标 (qn, codecid) 时直接选对应的编码组，
 * 绕过 DefaultTrackSelector 的组间自适应比较。
 *
 * 背景（issue#101 三方实测，华为手机+MTK 电视双向复现）：seamless MPD 把全部档位放进
 * 一个源，UserControlledQualityTrackSelection 的 qn 锁定只作用于"被选中的组"内部，
 * 选哪个组由 DefaultTrackSelector 的 compareSelections 决定——实测存在与
 * preferredVideoMimeType 注入方向相反的组间胜出（hevc 偏好下 AV1 组赢 / av01 偏好下
 * HEVC 组赢，注入确认先于选轨执行仍复现），锁定 125-HEVC 时实际 init AV1 解码器，
 * HDR/DV 档被 SDR 轨架空。这里把组选择权收回来：目标命中的组直接钦点（组内仍交给
 * UserControlledQualityTrackSelection 锁轨），目标缺失（非 seamless 源 / 目标轨被
 * 能力过滤）回落 super 的默认行为。
 */
@UnstableApi
class SeamlessQualityTrackSelector(
    context: android.content.Context,
    trackSelectionFactory: SeamlessQualityTrackSelectionFactory
) : DefaultTrackSelector(context, trackSelectionFactory) {

    private val pinTag = "SeamlessQuality"

    override fun selectVideoTrack(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        mixedMimeTypeSupports: IntArray,
        params: Parameters,
        selectedAudioLanguage: String?
    ): android.util.Pair<ExoTrackSelection.Definition, Int>? {
        val targetQn = SeamlessQualitySelector.targetQn()
        val targetCodecid = SeamlessQualitySelector.targetCodecid()
        if (targetQn > 0 && targetCodecid > 0) {
            for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_VIDEO) continue
                val groups = mappedTrackInfo.getTrackGroups(rendererIndex)
                for (groupIndex in 0 until groups.length) {
                    val group = groups.get(groupIndex)
                    // 只考虑"组内全部轨都带 seamless Representation id"的编码组，
                    // 避免把混合源（progressive 兜底轨）误当无缝组钦点。
                    if (!isSeamlessCodecGroup(group)) continue
                    val support = rendererFormatSupports[rendererIndex][groupIndex]
                    // 组内可解轨（剔除 FORMAT_UNSUPPORTED；EXCEEDS 保留作兜底）。
                    // 全组不可解时不钦点（如 DV 档被能力过滤），交回默认链路。
                    val playableIndices = (0 until group.length).filter {
                        RendererCapabilities.getFormatSupport(support[it]) != C.FORMAT_UNSUPPORTED_TYPE
                    }
                    if (playableIndices.isEmpty()) continue
                    val hasTarget = playableIndices.any {
                        parseVideoRepresentationId(group.getFormat(it).id)
                            ?.let { parsed -> parsed.first == targetQn && parsed.second == targetCodecid } == true
                    }
                    if (!hasTarget) continue
                    AppLog.i(
                        pinTag,
                        "seamless group pinned: qn=$targetQn codecid=$targetCodecid " +
                            "tracks=${playableIndices.size} renderer=$rendererIndex"
                    )
                    return android.util.Pair(
                        ExoTrackSelection.Definition(group, *playableIndices.toIntArray()),
                        rendererIndex
                    )
                }
            }
            AppLog.w(
                pinTag,
                "seamless group pin missed: qn=$targetQn codecid=$targetCodecid, fall back to default selection"
            )
        }
        return super.selectVideoTrack(
            mappedTrackInfo, rendererFormatSupports, mixedMimeTypeSupports, params, selectedAudioLanguage
        )
    }

    private fun isSeamlessCodecGroup(group: TrackGroup): Boolean {
        if (group.length == 0) return false
        var codecid = 0
        for (i in 0 until group.length) {
            val parsed = parseVideoRepresentationId(group.getFormat(i).id) ?: return false
            if (codecid == 0) {
                codecid = parsed.second
            } else if (codecid != parsed.second) {
                return false
            }
        }
        return true
    }
}
