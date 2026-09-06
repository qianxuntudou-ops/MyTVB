package com.tutu.myblbl.feature.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList
import com.tutu.myblbl.core.common.log.AppLog
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
