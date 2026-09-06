package com.tutu.myblbl.feature.player

import com.tutu.myblbl.model.video.quality.VideoCodecEnum

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
                        representation = option.representation,
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
                representation = audio,
                representationId = "audio_${audio.id}"
            )
            append("    </AdaptationSet>\n")
            append("  </Period>\n")
            append("</MPD>\n")
        }
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
