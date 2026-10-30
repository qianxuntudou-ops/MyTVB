package com.mytvb.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.mytvb.core.common.media.VideoCodecSupport
import com.mytvb.model.player.DashAudio
import com.mytvb.model.player.DashVideo
import com.mytvb.model.player.PlayInfoModel
import com.mytvb.model.player.SegmentBase
import com.mytvb.model.player.SupportFormat
import com.mytvb.model.video.quality.AudioQuality
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality
import com.mytvb.core.common.log.AppLog
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
internal class VideoPlayerStreamResolver(
    private val dataSourceFactory: DataSource.Factory,
    private val urlNormalizer: (String) -> String,
    /**
     * 起播能力预检（与播放选择器同语义的 supportsFormat 判定）：
     * 判定"本机声明不可解码"的编码会在候选列表中被稳定后置（不剔除），
     * 避免"选流判可解、播放却开不起来"。默认放行（不影响未接线场景）。
     */
    private val isCodecDecodable: (DashVideo) -> Boolean = { true }
) {

    companion object {
        private const val TAG = "StreamResolver"
    }

    // Centralizes stream fallback rules so the ViewModel only coordinates state and side effects.
    data class SelectionSnapshot(
        val qualities: List<VideoQuality>,
        val selectedQualityId: Int?,
        val audios: List<AudioQuality>,
        val selectedAudioId: Int?,
        val codecs: List<VideoCodecEnum>,
        val selectedCodec: VideoCodecEnum?
    )

    data class MediaSourceSelection(
        val mediaSource: MediaSource,
        val availableCodecs: List<VideoCodecEnum>,
        val selectedCodec: VideoCodecEnum?,
        val cdnFailoverStates: List<VideoPlayerCdnFailoverState> = emptyList()
    )

    data class RepresentationDescriptor(
        val mimeType: String,
        val codecs: String,
        val bandwidth: Long,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: String = "",
        val startWithSap: Int = 1,
        val segmentBase: SegmentBase? = null
    )

    data class CodecRoute(
        val codec: VideoCodecEnum,
        val videoUrls: List<String>,
        val audioUrls: List<String>,
        val videoDescriptor: RepresentationDescriptor,
        val audioDescriptor: RepresentationDescriptor?
    )

    data class StreamFallbackPlan(
        val qualityId: Int,
        val selectedAudioId: Int?,
        val durationMs: Long,
        val minBufferTimeMs: Long,
        val routes: List<CodecRoute>
    )

    fun resolveDashRoutePlan(
        playInfo: PlayInfoModel,
        lockedQualityId: Int,
        selectedAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): DashRoutePlan? {
        val dash = playInfo.dash ?: return null
        val videosAtQuality = dash.video.orEmpty()
            .filter { it.id == lockedQualityId }
        if (videosAtQuality.isEmpty()) return null

        val selectedAudio = selectAudioTrack(playInfo, selectedAudioId)
        AppLog.i(TAG, "audio track selected: id=${selectedAudio?.id} name=${AudioQuality.fromId(selectedAudio?.id ?: 0).name} " +
            "mimeType=${selectedAudio?.realMimeType} codecs=${selectedAudio?.codecs} bandwidth=${selectedAudio?.bandwidth} " +
            "requestedId=$selectedAudioId fallback=${selectedAudio?.id != selectedAudioId}")
        val audioUrls = selectedAudio
            ?.let { CdnLatencyProfile.sortUrlsByLatency(buildDistinctUrls(it.realBaseUrl, it.realBackupUrl)) }
            .orEmpty()
        val audioRepresentation = selectedAudio?.let { audio ->
            DashRepresentation(
                id = audio.id,
                mimeType = audio.realMimeType.ifBlank { "audio/mp4" },
                codecs = audio.codecs,
                bandwidth = audio.bandwidth,
                baseUrl = audio.realBaseUrl,
                backupUrls = audio.realBackupUrl.orEmpty(),
                segmentBase = audio.realSegmentBase?.let { sb ->
                    DashSegmentBase(
                        initialization = sb.initialization.ifBlank { sb.range },
                        indexRange = sb.realIndexRange
                    )
                }
            )
        }

        val videosByCodec = videosAtQuality.groupBy { VideoCodecEnum.fromId(it.codecId) }
        val codecOrder = reorderUndecodableLast(
            codecs = orderCodecs(
                available = videosByCodec.keys,
                preferredCodec = preferredCodec,
                hardwareSupportedCodecs = hardwareSupportedCodecs
            ),
            videos = videosAtQuality
        )
        val routes = codecOrder.mapNotNull { codec ->
            val selectedVideo = videosByCodec[codec]
                .orEmpty()
                .maxByOrNull { it.bandwidth }
                ?: return@mapNotNull null
            val videoUrls = CdnLatencyProfile.sortUrlsByLatency(
                buildDistinctUrls(selectedVideo.realBaseUrl, selectedVideo.realBackupUrl)
            )
            if (videoUrls.isEmpty()) return@mapNotNull null
            DashRoute(
                codec = codec,
                videoRepresentation = DashRepresentation(
                    id = selectedVideo.id,
                    mimeType = selectedVideo.realMimeType.ifBlank { "video/mp4" },
                    codecs = selectedVideo.codecs,
                    bandwidth = selectedVideo.bandwidth,
                    width = selectedVideo.width,
                    height = selectedVideo.height,
                    frameRate = selectedVideo.realFrameRate,
                    baseUrl = videoUrls.first(),
                    backupUrls = videoUrls.drop(1),
                    segmentBase = selectedVideo.realSegmentBase?.let { sb ->
                        DashSegmentBase(
                            initialization = sb.initialization.ifBlank { sb.range },
                            indexRange = sb.realIndexRange
                        )
                    }
                ),
                audioRepresentation = audioRepresentation?.let { audioRep ->
                    audioRep.copy(
                        baseUrl = audioUrls.firstOrNull() ?: audioRep.baseUrl,
                        backupUrls = audioUrls.drop(1).ifEmpty { audioRep.backupUrls }
                    )
                },
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                durationMs = resolveDurationMs(playInfo),
                minBufferTimeMs = resolveMinBufferTimeMs(playInfo)
            )
        }
        if (routes.isEmpty()) return null
        AppLog.i(
            TAG,
            "dash route plan: quality=$lockedQualityId preferred=$preferredCodec " +
                "routes=${routes.joinToString(" > ") {
                    "${it.codec}(${it.videoRepresentation.width}x${it.videoRepresentation.height} " +
                        "${it.videoRepresentation.bandwidth / 1000000}Mbps)"
                }}"
        )
        return DashRoutePlan(
            qualityId = lockedQualityId,
            selectedAudioId = selectedAudio?.id ?: selectedAudioId,
            routes = routes,
            durationMs = resolveDurationMs(playInfo),
            minBufferTimeMs = resolveMinBufferTimeMs(playInfo)
        )
    }

    fun buildFnval(@Suppress("UNUSED_PARAMETER") qualityId: Int): Int = 4048

    /**
     * 从 playInfo 的 dash 响应收集全部可无缝切换的档位，构建多清晰度目录。
     * 返回 null 表示不满足无缝条件（可用档位不足两档 / 初始档缺失 / 音轨缺 SegmentBase），
     * 调用方回退现有 Progressive+Merging 链路。
     */
    fun buildSeamlessCatalog(
        playInfo: PlayInfoModel,
        initialQualityId: Int,
        selectedAudioId: Int?,
        initialCodec: VideoCodecEnum?
    ): SeamlessQualityCatalog? {
        val dash = playInfo.dash ?: return null
        // 同一 (qn, codec) 可能有多条流（不同带宽），取最高带宽；SegmentBase 缺失的流进不了静态 MPD。
        val options = dash.video.orEmpty()
            .filter { it.realSegmentBase != null && it.realBaseUrl.isNotBlank() }
            .groupBy { it.id to it.codecId }
            .mapNotNull { (_, streams) ->
                val video = streams.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                val urls = CdnLatencyProfile.sortUrlsByLatency(
                    buildDistinctUrls(video.realBaseUrl, video.realBackupUrl)
                )
                if (urls.isEmpty()) return@mapNotNull null
                SeamlessVideoOption(
                    qn = video.id,
                    codec = VideoCodecEnum.fromId(video.codecId),
                    representation = DashRepresentation(
                        id = video.id,
                        mimeType = video.realMimeType.ifBlank { "video/mp4" },
                        codecs = video.codecs,
                        bandwidth = video.bandwidth,
                        width = video.width,
                        height = video.height,
                        frameRate = video.realFrameRate,
                        baseUrl = urls.first(),
                        backupUrls = urls.drop(1),
                        segmentBase = video.realSegmentBase?.let { sb ->
                            DashSegmentBase(
                                initialization = sb.initialization.ifBlank { sb.range },
                                indexRange = sb.realIndexRange
                            )
                        }
                    )
                )
            }
        if (options.map { it.qn }.distinct().size < 2) return null
        val resolvedInitialCodec = initialCodec ?: options.first().codec
        if (options.none { it.qn == initialQualityId && it.codec == resolvedInitialCodec }) return null

        // 音轨选择与 resolveDashRoutePlan 保持一致：请求 id 优先，缺省取最高带宽。
        val selectedAudio = selectAudioTrack(playInfo, selectedAudioId) ?: return null
        val audioUrls = CdnLatencyProfile.sortUrlsByLatency(
            buildDistinctUrls(selectedAudio.realBaseUrl, selectedAudio.realBackupUrl)
        )
        if (audioUrls.isEmpty()) return null
        val audioRepresentation = DashRepresentation(
            id = selectedAudio.id,
            mimeType = selectedAudio.realMimeType.ifBlank { "audio/mp4" },
            codecs = selectedAudio.codecs,
            bandwidth = selectedAudio.bandwidth,
            baseUrl = audioUrls.first(),
            backupUrls = audioUrls.drop(1),
            segmentBase = selectedAudio.realSegmentBase?.let { sb ->
                DashSegmentBase(
                    initialization = sb.initialization.ifBlank { sb.range },
                    indexRange = sb.realIndexRange
                )
            }
        )
        return SeamlessQualityCatalog(
            options = options,
            audioRepresentation = audioRepresentation,
            durationMs = resolveDurationMs(playInfo),
            minBufferTimeMs = resolveMinBufferTimeMs(playInfo),
            initialQualityId = initialQualityId,
            initialCodec = resolvedInitialCodec
        )
    }

    fun buildFourk(qualityId: Int): Int {
        return if (qualityId >= 120) 1 else 0
    }

    fun resolveSelections(
        playInfo: PlayInfoModel,
        preferredQualityId: Int?,
        preferredAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum> = emptySet()
    ): SelectionSnapshot {
        val qualities = buildQualityList(playInfo)
        val resolvedQualityId = resolveQualityId(playInfo, qualities, preferredQualityId)
        val audios = buildAudioList(playInfo)
        val resolvedAudioId = resolveAudioId(audios, preferredAudioId)
        val codecs = buildCodecList(
            playInfo = playInfo,
            qualityId = resolvedQualityId,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
        val resolvedCodec = codecs.firstOrNull()
        // 诊断锚点：设置默认编码"不起作用"类问题的判定入口——三值同看：
        // 入参 preferredCodec（意图）、codecs（排序结果）、resolvedCodec（实际生效）。
        // preferredCodec=HEVC 而 resolvedCodec=AV1 即排序层翻转（对照 hardwareSupportedCodecs）。
        AppLog.i(
            TAG,
            "resolveSelections: preferredCodec=$preferredCodec quality=$resolvedQualityId " +
                "hw=$hardwareSupportedCodecs codecs=$codecs resolved=$resolvedCodec"
        )
        return SelectionSnapshot(
            qualities = qualities,
            selectedQualityId = resolvedQualityId,
            audios = audios,
            selectedAudioId = resolvedAudioId,
            codecs = codecs,
            selectedCodec = resolvedCodec
        )
    }

    fun buildQualityList(playInfo: PlayInfoModel): List<VideoQuality> {
        val formatNames = playInfo.supportFormats.orEmpty().associateBy { it.quality }
        val videos = playInfo.dash?.video.orEmpty()
        val qualityOrder = playInfo.acceptQuality.orEmpty()

        val streamQualities = if (videos.isNotEmpty()) {
            videos
                .groupBy { it.id }
                .values
                .mapNotNull { streams ->
                    val sample = streams.maxByOrNull { it.bandwidth } ?: return@mapNotNull null
                    buildQualityModel(
                        qualityId = sample.id,
                        support = formatNames[sample.id],
                        resolution = "${sample.width}x${sample.height}",
                        codecId = sample.codecId,
                        bandwidth = sample.bandwidth,
                        baseUrl = sample.realBaseUrl,
                        backupUrls = sample.realBackupUrl
                    )
                }
        } else {
            playInfo.durl
                ?.firstOrNull()
                ?.takeIf { playInfo.quality > 0 && it.url.isNotBlank() }
                ?.let { currentStream ->
                    listOf(
                        buildQualityModel(
                            qualityId = playInfo.quality,
                            support = formatNames[playInfo.quality],
                            resolution = formatNames[playInfo.quality]?.displayDesc
                                ?.takeIf { it.isNotBlank() }
                                ?: VideoQuality.fromId(playInfo.quality).resolution,
                            bandwidth = playInfo.durl.orEmpty().sumOf { it.size },
                            baseUrl = currentStream.url,
                            backupUrls = currentStream.backupUrl
                        )
                    )
                }
                .orEmpty()
        }

        // 官方对齐的设备能力过滤：无杜比视界解码器的设备不展示 126 档（官方 app 同款
        // 行为——本机解不了 DV 元数据，该档只能按普通 HEVC 播且永不点亮，展示只会带来
        // 「为什么选了杜比视界没效果」的困惑）。电视等有 DV 解码器的设备不受影响。
        val deviceFiltered = streamQualities.filter { quality ->
            quality.id != VideoQuality.QUALITY_DOLBY_VISION.id ||
                VideoCodecSupport.isDolbyVisionSupported()
        }
        if (deviceFiltered.size != streamQualities.size) {
            AppLog.i(
                TAG,
                "quality list filtered: dolby vision not decodable on device, " +
                    "before=${streamQualities.map { it.id }} after=${deviceFiltered.map { it.id }}"
            )
        }
        return deviceFiltered.sortedWith(
            compareBy<VideoQuality> {
                qualityOrder.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }.thenByDescending { it.id }
        )
    }

    fun buildAudioList(playInfo: PlayInfoModel): List<AudioQuality> {
        return buildAudioTracks(playInfo)
            .map { audio ->
                AudioQuality(
                    id = audio.id,
                    name = AudioQuality.fromId(audio.id).name,
                    bandwidth = audio.bandwidth,
                    codecId = audio.codecId,
                    baseUrl = audio.realBaseUrl,
                    backupUrls = audio.realBackupUrl
                )
            }
            .sortedByDescending { it.bandwidth }
            .also { AppLog.i(TAG, "available audio tracks: ${it.map { a -> "${a.name}(id=${a.id},codec=${a.codecId},bw=${a.bandwidth})" }}") }
    }

    fun buildCodecList(
        playInfo: PlayInfoModel,
        qualityId: Int?,
        preferredCodec: VideoCodecEnum? = null,
        hardwareSupportedCodecs: Collection<VideoCodecEnum> = emptySet()
    ): List<VideoCodecEnum> {
        val videos = playInfo.dash?.video.orEmpty()
        val filteredVideos = videos
            .filter { qualityId == null || it.id == qualityId }
            .ifEmpty { videos }
        val availableCodecs = filteredVideos
            .map { VideoCodecEnum.fromId(it.codecId) }
            .distinct()
        val ordered = VideoCodecSupport.orderCandidates(
            availableCodecs = availableCodecs,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
        return reorderUndecodableLast(ordered, filteredVideos)
    }

    /**
     * 起播能力预检（保守）：判定本机不可解码的编码稳定后置（不剔除——预检查询异常时
     * 视为可解码，运行时渲染器仍有最终判定与错误回退）。同档多流时按最高带宽取代表流。
     */
    private fun reorderUndecodableLast(
        codecs: List<VideoCodecEnum>,
        videos: List<DashVideo>
    ): List<VideoCodecEnum> {
        if (codecs.size <= 1) return codecs
        val representativeByCodec = videos
            .groupBy { VideoCodecEnum.fromId(it.codecId) }
            .mapValues { (_, streams) -> streams.maxByOrNull { it.bandwidth } }
        val (decodable, undecodable) = codecs.partition { codec ->
            val track = representativeByCodec[codec] ?: return@partition true
            isCodecDecodable(track)
        }
        if (undecodable.isEmpty()) return codecs
        AppLog.i(
            TAG,
            "codec capability precheck: undecodable-last applied, decodable=$decodable undecodable=$undecodable"
        )
        return decodable + undecodable
    }

    fun buildMediaSource(
        playInfo: PlayInfoModel,
        selectedQualityId: Int?,
        selectedAudioId: Int?,
        selectedCodec: VideoCodecEnum?
    ): MediaSourceSelection? {
        val dash = playInfo.dash
        if (dash != null && !dash.video.isNullOrEmpty()) {
            val filteredByQuality = dash.video
                .filter { it.id == selectedQualityId }
                .ifEmpty { dash.video }
            val availableCodecs = filteredByQuality
                .map { VideoCodecEnum.fromId(it.codecId) }
                .distinct()
            val resolvedCodec = selectedCodec.takeIf { it in availableCodecs } ?: availableCodecs.firstOrNull()
            val selectedVideo = filteredByQuality
                .filter { resolvedCodec == null || it.codecId == resolvedCodec.id }
                .maxByOrNull { it.bandwidth }
                ?: filteredByQuality.maxByOrNull { it.bandwidth }
                ?: return null
            val selectedAudio = selectAudioTrack(playInfo, selectedAudioId)
            AppLog.i(TAG, "buildMediaSource audio: id=${selectedAudio?.id} mimeType=${selectedAudio?.realMimeType} " +
                "codecs=${selectedAudio?.codecs} bandwidth=${selectedAudio?.bandwidth}")
            val videoUrls = CdnLatencyProfile.sortUrlsByLatency(
                buildDistinctUrls(
                    primaryUrl = selectedVideo.realBaseUrl,
                    backupUrls = selectedVideo.realBackupUrl
                )
            )
            val audioUrls = selectedAudio?.let {
                CdnLatencyProfile.sortUrlsByLatency(
                    buildDistinctUrls(
                        primaryUrl = it.realBaseUrl,
                        backupUrls = it.realBackupUrl
                    )
                )
            }.orEmpty()
            val sourceWithState = createMediaSource(
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                videoMimeType = selectedVideo.realMimeType,
                audioMimeType = selectedAudio?.realMimeType.orEmpty()
            )
            return MediaSourceSelection(
                mediaSource = sourceWithState.mediaSource,
                availableCodecs = availableCodecs,
                selectedCodec = resolvedCodec,
                cdnFailoverStates = sourceWithState.cdnFailoverStates
            )
        }

        // durl 分段流：无 DASH 转码的视频服务端返回多段 durl（单段约 20 分钟），
        // 必须把全部段拼成单一时间轴，否则长视频只播第一段（50 分钟视频 20:00 即自动结束）。
        val durlSegments = playInfo.durl.orEmpty().filter { it.url.isNotBlank() }
        if (durlSegments.isEmpty()) return null
        val durlMimeType = if (
            playInfo.from.contains("flv", ignoreCase = true) ||
            playInfo.format.contains("flv", ignoreCase = true)
        ) "video/x-flv" else "video/mp4"
        AppLog.i(
            TAG,
            "durl playback: segments=${durlSegments.size} " +
                "segmentLengthMs=[${durlSegments.joinToString(",") { it.length.toString() }}] " +
                "sumLengthMs=${durlSegments.sumOf { it.length }} timeLengthMs=${playInfo.timeLength} " +
                "from=${playInfo.from} format=${playInfo.format}"
        )
        val segmentSources = durlSegments.map { segment ->
            createProgressiveSource(
                urls = buildDistinctUrls(primaryUrl = segment.url, backupUrls = segment.backupUrl),
                mimeType = durlMimeType
            )
        }
        val durlMediaSource = if (segmentSources.size == 1) {
            segmentSources.first().mediaSource
        } else {
            segmentSources.fold(ConcatenatingMediaSource2.Builder()) { builder, source ->
                builder.add(source.mediaSource)
            }.build()
        }
        return MediaSourceSelection(
            mediaSource = durlMediaSource,
            availableCodecs = emptyList(),
            selectedCodec = null,
            cdnFailoverStates = segmentSources.flatMap { it.cdnFailoverStates }
        )
    }

    fun buildMediaSourceForRoute(
        route: CodecRoute,
        videoUrl: String,
        audioUrl: String?,
        availableCodecs: List<VideoCodecEnum>,
        selectedCodec: VideoCodecEnum?
    ): MediaSourceSelection {
        val sourceWithState = createMediaSource(
            videoUrls = CdnLatencyProfile.sortUrlsByLatency(prioritizeUrl(route.videoUrls, videoUrl)),
            audioUrls = CdnLatencyProfile.sortUrlsByLatency(prioritizeUrl(route.audioUrls, audioUrl)),
            videoMimeType = route.videoDescriptor.mimeType,
            audioMimeType = route.audioDescriptor?.mimeType.orEmpty()
        )
        return MediaSourceSelection(
            mediaSource = sourceWithState.mediaSource,
            availableCodecs = availableCodecs,
            selectedCodec = selectedCodec,
            cdnFailoverStates = sourceWithState.cdnFailoverStates
        )
    }

    fun buildStreamFallbackPlan(
        playInfo: PlayInfoModel,
        lockedQualityId: Int,
        selectedAudioId: Int?,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): StreamFallbackPlan? {
        val dash = playInfo.dash ?: return null
        val videosAtQuality = dash.video.orEmpty()
            .filter { it.id == lockedQualityId }
        if (videosAtQuality.isEmpty()) {
            return null
        }

        val selectedAudio = selectAudioTrack(playInfo, selectedAudioId)
        val audioUrls = selectedAudio
            ?.let { buildDistinctUrls(it.realBaseUrl, it.realBackupUrl) }
            .orEmpty()

        val videosByCodec = videosAtQuality.groupBy { VideoCodecEnum.fromId(it.codecId) }
        val codecOrder = reorderUndecodableLast(
            codecs = orderCodecs(
                available = videosByCodec.keys,
                preferredCodec = preferredCodec,
                hardwareSupportedCodecs = hardwareSupportedCodecs
            ),
            videos = videosAtQuality
        )
        val routes = codecOrder.mapNotNull { codec ->
            val selectedVideo = videosByCodec[codec]
                .orEmpty()
                .maxByOrNull { it.bandwidth }
                ?: return@mapNotNull null
            val videoUrls = buildDistinctUrls(selectedVideo.realBaseUrl, selectedVideo.realBackupUrl)
            if (videoUrls.isEmpty()) {
                return@mapNotNull null
            }
            CodecRoute(
                codec = codec,
                videoUrls = videoUrls,
                audioUrls = audioUrls,
                videoDescriptor = selectedVideo.toRepresentationDescriptor(),
                audioDescriptor = selectedAudio?.toRepresentationDescriptor()
            )
        }
        if (routes.isEmpty()) {
            return null
        }
        return StreamFallbackPlan(
            qualityId = lockedQualityId,
            selectedAudioId = selectedAudio?.id ?: selectedAudioId,
            durationMs = resolveDurationMs(playInfo),
            minBufferTimeMs = resolveMinBufferTimeMs(playInfo),
            routes = routes
        )
    }

    private fun resolveQualityId(
        playInfo: PlayInfoModel,
        qualities: List<VideoQuality>,
        preferredQualityId: Int?
    ): Int? {
        val availableQualityIds = qualities.map { it.id }
        if (preferredQualityId in availableQualityIds) {
            return preferredQualityId
        }
        return qualities.maxByOrNull { it.id }?.id
            ?: qualities.firstOrNull()?.id
            ?: playInfo.quality
    }

    private fun resolveAudioId(
        audios: List<AudioQuality>,
        preferredAudioId: Int?
    ): Int? {
        val availableAudioIds = audios.map { it.id }
        if (preferredAudioId in availableAudioIds) {
            return preferredAudioId
        }
        return audios.maxByOrNull { it.bandwidth }?.id
            ?: audios.firstOrNull()?.id
    }

    /**
     * 音轨选择（DASH/seamless/progressive 各路径共用）：请求 id 优先，缺省取最高带宽。
     *
     * 无声档过滤：本机无 AC-3 解码器时杜比全景声轨（30250）解不了，media3 对解不了
     * 的音频轨跳轨不报错，选了就是「视频正常但无声」（华为真机实测切档后 audio
     * decoder init 一次都不出现）。这里统一排除，偏好该档的用户回落到剩余最高带宽轨
     * （Hi-Res/192K AAC）。
     */
    private fun selectAudioTrack(playInfo: PlayInfoModel, selectedAudioId: Int?): DashAudio? {
        val tracks = buildAudioTracks(playInfo)
        val playableTracks = tracks.filter { track ->
            track.id != AudioQuality.AUDIO_DOLBY.id || VideoCodecSupport.isDolbyAtmosTrackPlayable()
        }
        if (playableTracks.size != tracks.size) {
            AppLog.i(
                TAG,
                "audio track filtered: dolby atmos not decodable on device, " +
                    "tracks=${tracks.map { it.id }} playable=${playableTracks.map { it.id }}"
            )
        }
        return playableTracks.firstOrNull { it.id == selectedAudioId }
            ?: playableTracks.maxByOrNull { it.bandwidth }
    }

    private fun buildAudioTracks(playInfo: PlayInfoModel): List<DashAudio> {
        val dash = playInfo.dash ?: return emptyList()
        return buildList {
            addAll(dash.audio.orEmpty())
            dash.dolby?.audio?.let(::addAll)
            dash.flac?.audio?.let(::add)
        }.distinctBy { it.id }
    }

    private fun orderCodecs(
        available: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        return VideoCodecSupport.orderCandidates(
            availableCodecs = available,
            preferredCodec = preferredCodec,
            hardwareSupportedCodecs = hardwareSupportedCodecs
        )
    }

    private fun buildDistinctUrls(primaryUrl: String, backupUrls: List<String>?): List<String> {
        return buildList {
            if (primaryUrl.isNotBlank()) {
                add(primaryUrl)
            }
            backupUrls
                .orEmpty()
                .filter { it.isNotBlank() }
                .let(::addAll)
        }.distinct().map(urlNormalizer)
    }

    private fun buildQualityModel(
        qualityId: Int,
        support: SupportFormat?,
        resolution: String,
        codecId: Int = 0,
        bandwidth: Long = 0,
        baseUrl: String = "",
        backupUrls: List<String>? = null
    ): VideoQuality {
        val static = VideoQuality.fromId(qualityId)
        val displayName = support?.newDescription?.takeIf(String::isNotBlank)
            ?: support?.displayDesc?.takeIf(String::isNotBlank)
            ?: static.name
        val displayResolution = resolution.ifBlank {
            support?.displayDesc?.takeIf(String::isNotBlank)
                ?: static.resolution
        }
        return VideoQuality(
            id = qualityId,
            name = displayName,
            resolution = displayResolution,
            codecId = codecId,
            bandwidth = bandwidth,
            baseUrl = baseUrl,
            backupUrls = backupUrls
        )
    }

    private fun DashVideo.toRepresentationDescriptor(): RepresentationDescriptor {
        return RepresentationDescriptor(
            mimeType = realMimeType.ifBlank { "video/mp4" },
            codecs = codecs,
            bandwidth = bandwidth,
            width = width,
            height = height,
            frameRate = realFrameRate,
            startWithSap = realStartWithSap.takeIf { it > 0 } ?: 1,
            segmentBase = realSegmentBase
        )
    }

    private fun DashAudio.toRepresentationDescriptor(): RepresentationDescriptor {
        return RepresentationDescriptor(
            mimeType = realMimeType.ifBlank { "audio/mp4" },
            codecs = codecs,
            bandwidth = bandwidth,
            startWithSap = startWithSap.takeIf { it > 0 } ?: startWithSapAlt.takeIf { it > 0 } ?: 1,
            segmentBase = realSegmentBase
        )
    }

    private fun resolveDurationMs(playInfo: PlayInfoModel): Long {
        return playInfo.timeLength.takeIf { it > 0L }
            ?: playInfo.dash?.duration?.takeIf { it > 0L }?.times(1000L)
            ?: 1L
    }

    private fun resolveMinBufferTimeMs(playInfo: PlayInfoModel): Long {
        val dash = playInfo.dash
        val seconds = when {
            dash == null -> 1.5
            dash.minBufferTime > 0.0 -> dash.minBufferTime
            dash.minBufferTimeAlt > 0.0 -> dash.minBufferTimeAlt
            else -> 1.5
        }
        return (seconds * 1000.0).roundToLong().coerceAtLeast(500L)
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

    private fun createMediaSource(
        videoUrls: List<String>,
        audioUrls: List<String>,
        videoMimeType: String,
        audioMimeType: String
    ): MediaSourceWithCdnState {
        val normalizedVideoUrls = videoUrls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedAudioUrls = audioUrls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        return createProgressivePairSource(
            videoUrls = normalizedVideoUrls,
            audioUrls = normalizedAudioUrls,
            videoMimeType = videoMimeType.ifBlank { "video/mp4" },
            audioMimeType = audioMimeType.ifBlank { "audio/mp4" }
        )
    }

    private fun createProgressivePairSource(
        videoUrls: List<String>,
        audioUrls: List<String>,
        videoMimeType: String,
        audioMimeType: String
    ): MediaSourceWithCdnState {
        val videoSource = createProgressiveSource(videoUrls, videoMimeType)
        val audioSource = audioUrls
            .takeIf { it.isNotEmpty() }
            ?.let { createProgressiveSource(it, audioMimeType) }
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

    private fun createProgressiveSource(urls: List<String>, mimeType: String): MediaSourceWithCdnState {
        val primaryUrl = urls.firstOrNull()
            ?: error("No media url candidates available")
        val (sourceFactory, state) = createCandidateAwareFactory(urls)
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

    private fun createCandidateAwareFactory(urls: List<String>): Pair<DataSource.Factory, VideoPlayerCdnFailoverState?> {
        val candidates = urls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.size <= 1) {
            return dataSourceFactory to null
        }
        val state = VideoPlayerCdnFailoverState(candidates = candidates.map(Uri::parse))
        return VideoPlayerCdnFailoverDataSourceFactory(
            upstreamFactory = dataSourceFactory,
            state = state
        ) to state
    }

    /**
     * createMediaSource 的带 CDN state 返回版本：把本次创建的 failover state 一并吐出，
     * 供外层在卡顿时调用 penalizeCurrentHost 降权。
     */
    data class MediaSourceWithCdnState(
        val mediaSource: MediaSource,
        val cdnFailoverStates: List<VideoPlayerCdnFailoverState>
    )

    private fun prioritizeUrl(urls: List<String>, preferredUrl: String?): List<String> {
        val normalizedUrls = urls
            .map(urlNormalizer)
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedPreferred = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(urlNormalizer)
            ?: return normalizedUrls
        val preferredFirst = normalizedUrls.firstOrNull { it == normalizedPreferred } ?: return normalizedUrls
        return buildList {
            add(preferredFirst)
            normalizedUrls
                .filterNot { it == normalizedPreferred }
                .forEach(::add)
        }
    }
}
