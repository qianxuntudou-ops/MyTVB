package com.mytvb.feature.player

import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.media.VideoCodecSupport
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality

/** 无缝目录里的一个可选视频轨：某个 (qn, codec) 组合对应的 DASH 流。 */
internal data class SeamlessVideoOption(
    val qn: Int,
    val codec: VideoCodecEnum,
    val representation: DashRepresentation
)

/**
 * 一次 playurl 响应里可无缝切换的全部档位目录。
 * 由此生成包含所有清晰度的 DASH MPD（见 [SeamlessDashMpdBuilder.buildAdaptiveOnDemandMpd]），
 * 切换档位时只改 [SeamlessQualitySelector] 的 target，不重建 MediaSource。
 */
internal data class SeamlessQualityCatalog(
    val options: List<SeamlessVideoOption>,
    val audioRepresentation: DashRepresentation?,
    val durationMs: Long,
    val minBufferTimeMs: Long,
    val initialQualityId: Int,
    val initialCodec: VideoCodecEnum
) {
    val qualityIds: List<Int> get() = options.map { it.qn }.distinct()

    fun hasTrack(qn: Int, codec: VideoCodecEnum): Boolean =
        options.any { it.qn == qn && it.codec == codec }
}

/**
 * 生成包含全部清晰度的 isoff-on-demand DASH MPD。
 * Representation id 采用 [videoRepresentationId] 的固定格式，
 * 供 [SeamlessQualitySelector] 在渲染器层解析出 (qn, codecid)。
 */
internal object SeamlessDashMpdBuilder {

    private const val TAG = "SeamlessQuality"

    fun videoRepresentationId(qn: Int, codecid: Int, index: Int): String =
        "video_qn_${qn}_codec_${codecid}_index_$index"

    private val VIDEO_REPRESENTATION_ID = Regex("video_qn_(\\d+)_codec_(\\d+)_index_\\d+")

    fun buildAdaptiveOnDemandMpd(catalog: SeamlessQualityCatalog): String {
        val videos = catalog.options
        require(videos.isNotEmpty()) { "Seamless DASH needs at least one video representation" }
        val audio = requireNotNull(catalog.audioRepresentation) { "Seamless DASH audio track is missing" }

        val durationAttr = formatMpdDuration(catalog.durationMs)
        val minBufferAttr = formatMpdDuration(catalog.minBufferTimeMs)
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            append("type=\"static\" minBufferTime=\"").append(minBufferAttr).append("\"")
            if (durationAttr != null) append(" mediaPresentationDuration=\"").append(durationAttr).append("\"")
            append(">\n")
            append("  <Period start=\"PT0S\"")
            if (durationAttr != null) append(" duration=\"").append(durationAttr).append("\"")
            append(">\n")

            videos.groupBy { it.codec.id }.forEach { (codecid, options) ->
                append("    <AdaptationSet contentType=\"video\" segmentAlignment=\"true\" startWithSAP=\"1\" id=\"")
                    .append(codecid)
                    .append("\">\n")
                options.forEachIndexed { index, option ->
                    appendRepresentation(
                        mimeType = option.representation.mimeType.ifBlank { "video/mp4" },
                        representation = withDolbyVisionCodecsRewritten(option),
                        representationId = videoRepresentationId(option.qn, option.codec.id, index)
                    )
                }
                append("    </AdaptationSet>\n")
            }

            append("    <AdaptationSet contentType=\"audio\" mimeType=\"")
                .append(xmlEscapeAttr(audio.mimeType.ifBlank { "audio/mp4" }))
                .append("\">\n")
            appendRepresentation(
                mimeType = audio.mimeType.ifBlank { "audio/mp4" },
                representation = withDolbyAtmosCodecsCorrected(audio),
                representationId = "audio_${audio.id}"
            )
            append("    </AdaptationSet>\n")
            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    /**
     * 杜比全景声音轨 codecs 纠偏：B 站 API 对 30250 档泛标 "ac-3"，但实测码率 1.67Mbps
     * 远超 AC-3 规范上限（5.1 最大 640kbps），码流实为 EAC3（官方 app 亦按 EAC3 解，
     * ijkplayer eac3-type 配置实证）。
     *
     * 纠正为 "ec-3" 后 media3 解析为 audio/eac3：有 EAC3 硬解的电视走系统解码器更精准；
     * 无系统解码器的手机由 FFmpeg 扩展的 eac3 解码器（AC-3/E-AC-3 超集实现，纯 AC-3
     * 码流同样能解）软解出声，杜比全景声不再静默跳轨。非 ac-3 的音轨不动。
     */
    private fun withDolbyAtmosCodecsCorrected(audio: DashRepresentation): DashRepresentation {
        if (audio.codecs.lowercase() != "ac-3") return audio
        AppLog.i(TAG, "dolby atmos mpd codecs rewrite: audio=${audio.id} \"ac-3\" -> \"ec-3\"")
        return audio.copy(codecs = "ec-3")
    }

    /**
     * 杜比视界档（qn=126）且设备具备 DV 解码器时，把服务端下发的 HEVC 形态 codecs
     * （实测 hvc1.2.4.H150.90，不带 dvhe 前缀）改写为 [VideoCodecSupport.DOLBY_VISION_REWRITE_CODECS]。
     *
     * B 站 126 档的真实内容是 DV Profile 5，但装在 hvc1 标记的普通 HEVC 容器里，
     * 不改写时 media3 全链路按 video/hevc 配给解码器，电视 DV 管线收不到 DV 标记
     * 永远不点亮（海信 MT9952 实测）。改写后 media3 按 video/dolby-vision 选解码器
     * （配合 VideoCodecSupport 选择器里的 dvhe 组件前插），流内容不变。
     * 无 DV 解码器的设备保持原样——按普通 HEVC 播放，行为与现状完全一致。
     */
    private fun withDolbyVisionCodecsRewritten(option: SeamlessVideoOption): DashRepresentation {
        if (option.qn != VideoQuality.QUALITY_DOLBY_VISION.id) return option.representation
        if (!VideoCodecSupport.isDolbyVisionSupported()) return option.representation
        val original = option.representation.codecs
        if (original.startsWith("dvhe") || original.startsWith("dvh1")) return option.representation
        AppLog.i(
            TAG,
            "dolby vision mpd codecs rewrite: qn=${option.qn} " +
                "\"$original\" -> \"${VideoCodecSupport.DOLBY_VISION_REWRITE_CODECS}\""
        )
        return option.representation.copy(codecs = VideoCodecSupport.DOLBY_VISION_REWRITE_CODECS)
    }

    private fun StringBuilder.appendRepresentation(
        mimeType: String,
        representation: DashRepresentation,
        representationId: String
    ) {
        append("      <Representation id=\"").append(xmlEscapeAttr(representationId)).append("\"")
        append(" mimeType=\"").append(xmlEscapeAttr(mimeType)).append("\"")
        if (representation.codecs.isNotBlank()) {
            append(" codecs=\"").append(xmlEscapeAttr(representation.codecs)).append("\"")
        }
        if (representation.bandwidth > 0L) {
            append(" bandwidth=\"").append(representation.bandwidth).append("\"")
        }
        if (representation.width > 0 && representation.height > 0) {
            append(" width=\"").append(representation.width).append("\"")
            append(" height=\"").append(representation.height).append("\"")
        }
        if (representation.frameRate.isNotBlank()) {
            append(" frameRate=\"").append(xmlEscapeAttr(representation.frameRate)).append("\"")
        }
        append(">\n")
        append("        <BaseURL>").append(xmlEscapeText(representation.baseUrl)).append("</BaseURL>\n")
        representation.segmentBase?.let { segmentBase ->
            append("        <SegmentBase indexRange=\"")
                .append(xmlEscapeAttr(segmentBase.indexRange))
                .append("\">\n")
            append("          <Initialization range=\"")
                .append(xmlEscapeAttr(segmentBase.initialization))
                .append("\" />\n")
            append("        </SegmentBase>\n")
        }
        append("      </Representation>\n")
    }

    private fun xmlEscapeAttr(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun xmlEscapeText(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    private fun formatMpdDuration(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val seconds = durationMs / 1000.0
        val fixed = String.format(java.util.Locale.US, "%.3f", seconds)
        return "PT${fixed}S"
    }
}

/** 解析多清晰度 MPD 的 Representation id，返回 (qn, codecid)；非无缝源的 id 返回 null。 */
internal fun parseVideoRepresentationId(id: String?): Pair<Int, Int>? {
    if (id.isNullOrBlank()) return null
    val match = VIDEO_REPRESENTATION_ID_PATTERN.find(id) ?: return null
    val qn = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val codecid = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return qn to codecid
}

private val VIDEO_REPRESENTATION_ID_PATTERN = Regex("video_qn_(\\d+)_codec_(\\d+)_index_\\d+")
