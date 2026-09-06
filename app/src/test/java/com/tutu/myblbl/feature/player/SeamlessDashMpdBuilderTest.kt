package com.tutu.myblbl.feature.player

import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无缝清晰度切换的静态正确性验证：
 * 1. parseVideoRepresentationId 对非无缝源的 format.id（null / CDN URL / 音频轨 id）必须返回 null，
 *    保证注入的 TrackSelectionFactory 对 Progressive/durl 等旧链路零影响；
 * 2. 生成的 MPD 必须包含约定的 Representation id、SegmentBase 索引与元数据
 *    （MPD 可解析性已由真机播放实证，JVM 单测里 android.net.Uri 为 stub 无法跑 DashManifestParser）。
 */
class SeamlessDashMpdBuilderTest {

    @Test
    fun `parseVideoRepresentationId rejects non seamless ids`() {
        assertNull(parseVideoRepresentationId(null))
        assertNull(parseVideoRepresentationId(""))
        assertNull(parseVideoRepresentationId(" "))
        // durl/Progressive 源的 format.id（null 或 URL）不得被误识别
        assertNull(parseVideoRepresentationId("https://upos-sz-mirror08c.bilivideo.com/upgcxcode/xx/xx.mp4"))
        // 音频轨 id 不含 qn 信息
        assertNull(parseVideoRepresentationId("audio_30280"))
        // qn/codecid 必须为正数
        assertNull(parseVideoRepresentationId("video_qn_0_codec_12_index_0"))
        assertNull(parseVideoRepresentationId("video_qn_80_codec_0_index_0"))

        assertEquals(80 to 12, parseVideoRepresentationId("video_qn_80_codec_12_index_0"))
        assertEquals(16 to 7, parseVideoRepresentationId("video_qn_16_codec_7_index_3"))
    }

    @Test
    fun `generated mpd contains expected representations and segment bases`() {
        val catalog = buildCatalog()
        val mpd = SeamlessDashMpdBuilder.buildAdaptiveOnDemandMpd(catalog)

        // 静态点播 MPD + 时长
        assertTrue(mpd.contains("type=\"static\""))
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT300.000S\""))
        assertTrue(mpd.contains("minBufferTime=\"PT1.500S\""))

        // 每个编码一个 AdaptationSet（HEVC=12 / AVC=7），id 格式可被渲染层解析
        assertTrue(mpd.contains("id=\"12\">"))
        assertTrue(mpd.contains("id=\"7\">"))
        // index 为编码分组内的下标：HEVC 组内 [80, 64]，AVC 组内 [80]
        assertTrue(mpd.contains("id=\"video_qn_80_codec_12_index_0\""))
        assertTrue(mpd.contains("id=\"video_qn_64_codec_12_index_1\""))
        assertTrue(mpd.contains("id=\"video_qn_80_codec_7_index_0\""))

        // 档位元数据完整
        assertTrue(mpd.contains("width=\"1920\" height=\"1080\""))
        assertTrue(mpd.contains("codecs=\"hev1.1.6.L120.90\""))
        assertTrue(mpd.contains("bandwidth=\"2000000\""))

        // SegmentBase 索引与初始化区间（无此则静态 MPD 无法定位分片）
        assertTrue(mpd.contains("<SegmentBase indexRange=\"960-1335\">"))
        assertTrue(mpd.contains("<Initialization range=\"0-959\" />"))

        // CDN 基址写入 BaseURL，供路由 DataSource 匹配
        assertTrue(mpd.contains("https://cdn.example.com/hevc80.m4s"))

        // 音频轨独立 AdaptationSet
        assertTrue(mpd.contains("contentType=\"audio\""))
        assertTrue(mpd.contains("id=\"audio_30280\""))
    }

    private fun buildCatalog(): SeamlessQualityCatalog {
        val hevc80 = videoOption(80, VideoCodecEnum.HEVC, baseUrl = "https://cdn.example.com/hevc80.m4s")
        val hevc64 = videoOption(64, VideoCodecEnum.HEVC, baseUrl = "https://cdn.example.com/hevc64.m4s")
        val avc80 = videoOption(80, VideoCodecEnum.AVC, baseUrl = "https://cdn.example.com/avc80.m4s")
        return SeamlessQualityCatalog(
            options = listOf(hevc80, hevc64, avc80),
            audioRepresentation = DashRepresentation(
                id = 30280,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bandwidth = 302_641,
                baseUrl = "https://cdn.example.com/audio.m4s",
                backupUrls = emptyList(),
                segmentBase = DashSegmentBase(
                    initialization = "0-959",
                    indexRange = "960-1335"
                )
            ),
            durationMs = 300_000,
            minBufferTimeMs = 1_500,
            initialQualityId = 80,
            initialCodec = VideoCodecEnum.HEVC
        )
    }

    private fun videoOption(qn: Int, codec: VideoCodecEnum, baseUrl: String): SeamlessVideoOption =
        SeamlessVideoOption(
            qn = qn,
            codec = codec,
            representation = DashRepresentation(
                id = qn,
                mimeType = "video/mp4",
                codecs = if (codec == VideoCodecEnum.HEVC) "hev1.1.6.L120.90" else "avc1.640028",
                bandwidth = 2_000_000L,
                width = 1920,
                height = 1080,
                frameRate = "30",
                baseUrl = baseUrl,
                backupUrls = listOf("https://cdn-backup.example.com/same.m4s"),
                segmentBase = DashSegmentBase(
                    initialization = "0-959",
                    indexRange = "960-1335"
                )
            )
        )
}
