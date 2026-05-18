package com.tutu.myblbl.feature.player.settings

import android.content.Context
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import org.koin.mp.KoinPlatform

enum class AfterPlayMode {
    NOTHING,
    RECOMMEND,
    PLAY_QUEUE,
    NEXT_EPISODE
}

data class PlayerSettings(
    val defaultVideoQualityId: Int? = VideoQualityDefaults.DEFAULT_VIDEO_QUALITY_ID,
    val defaultAudioQualityId: Int? = VideoQualityDefaults.DEFAULT_AUDIO_QUALITY_ID,
    val defaultPlaybackSpeed: Float = 1.0f,
    val defaultVideoCodec: VideoCodecEnum? = VideoCodecEnum.HEVC,
    val afterPlayMode: AfterPlayMode = AfterPlayMode.NEXT_EPISODE,
    val exitPlayerWhenPlaybackFinished: Boolean = true,
    val showSubtitleByDefault: Boolean = false,
    val subtitleTextSizePx: Int = 45,
    val showBottomProgressBar: Boolean = false,
    val showDebugInfo: Boolean = false,
    val simpleKeyPress: Boolean = false,
    val showRewindFastForward: Boolean = false,
    val showNextPrevious: Boolean = false,
    val showDanmakuSwitch: Boolean = false,
    val fastSeekSeconds: Int = 10,
    val resumePlayback: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val sponsorBlockAutoSkip: Boolean = true
)

private object VideoQualityDefaults {
    const val DEFAULT_VIDEO_QUALITY_ID = 80
    const val DEFAULT_AUDIO_QUALITY_ID = 30280
}

object PlayerSettingsStore {

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var cachedSettings: PlayerSettings? = null
    private var lastSettingsSnapshot: String? = null

    private const val KEY_DEFAULT_VIDEO_QUALITY = "default_video_quality"
    private const val KEY_DEFAULT_AUDIO_TRACK = "default_audio_track"
    private const val KEY_DEFAULT_PLAY_SPEED = "default_play_speed"
    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_AFTER_PLAY = "after_play"
    private const val KEY_PLAY_FINISH_EXIT_PLAYER = "play_finish_exit_player"
    private const val KEY_SHOW_SUBTITLE_DEFAULT = "show_subtitle_default"
    private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
    private const val KEY_SHOW_RE_FF = "show_re_ff"
    private const val KEY_SHOW_DEBUG = "show_debug"
    private const val KEY_SIMPLE_KEY_PRESS = "simple_key_press"
    private const val KEY_SHOW_BOTTOM_PROGRESS_BAR = "show_bottom_progress_bar"
    private const val KEY_SHOW_NEXT_PREVIOUS = "show_next_previous"
    private const val KEY_SHOW_DM_SWITCH = "show_dm_switch"
    private const val KEY_FF_SEEK_SECOND = "ff_seek_second"
    private const val KEY_RESUME_PLAYBACK = "resume_playback"
    private const val KEY_SPONSOR_BLOCK_ENABLED = "sponsor_block_enabled"
    private const val KEY_SPONSOR_BLOCK_AUTO_SKIP = "sponsor_block_auto_skip"

    fun load(context: Context): PlayerSettings {
        fun readSetting(key: String): String? = appSettings.getCachedString(key)
        val snapshot = buildString {
            append(readSetting(KEY_DEFAULT_VIDEO_QUALITY).orEmpty())
            append("|")
            append(readSetting(KEY_DEFAULT_AUDIO_TRACK).orEmpty())
            append("|")
            append(readSetting(KEY_DEFAULT_PLAY_SPEED).orEmpty())
            append("|")
            append(readSetting(KEY_VIDEO_CODEC).orEmpty())
            append("|")
            append(readSetting(KEY_AFTER_PLAY).orEmpty())
            append("|")
            append(readSetting(KEY_PLAY_FINISH_EXIT_PLAYER).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_SUBTITLE_DEFAULT).orEmpty())
            append("|")
            append(readSetting(KEY_SUBTITLE_TEXT_SIZE).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_RE_FF).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_DEBUG).orEmpty())
            append("|")
            append(readSetting(KEY_SIMPLE_KEY_PRESS).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_BOTTOM_PROGRESS_BAR).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_NEXT_PREVIOUS).orEmpty())
            append("|")
            append(readSetting(KEY_SHOW_DM_SWITCH).orEmpty())
            append("|")
            append(readSetting(KEY_FF_SEEK_SECOND).orEmpty())
            append("|")
            append(readSetting(KEY_RESUME_PLAYBACK).orEmpty())
            append("|")
            append(readSetting(KEY_SPONSOR_BLOCK_ENABLED).orEmpty())
            append("|")
            append(readSetting(KEY_SPONSOR_BLOCK_AUTO_SKIP).orEmpty())
        }
        if (snapshot == lastSettingsSnapshot) {
            return cachedSettings!!
        }
        val settings = PlayerSettings(
            defaultVideoQualityId = parseVideoQualityId(
                readSetting(KEY_DEFAULT_VIDEO_QUALITY)
            ),
            defaultAudioQualityId = parseAudioQualityId(
                readSetting(KEY_DEFAULT_AUDIO_TRACK)
            ),
            defaultPlaybackSpeed = readSetting(KEY_DEFAULT_PLAY_SPEED)
                ?.toFloatOrNull()
                ?.takeIf { it > 0f }
                ?: 1.0f,
            defaultVideoCodec = parseVideoCodec(
                readSetting(KEY_VIDEO_CODEC)
            ),
            afterPlayMode = when (readSetting(KEY_AFTER_PLAY)) {
                "什么都不做" -> AfterPlayMode.NOTHING
                "播推荐视频" -> AfterPlayMode.RECOMMEND
                "播列表中的下一个" -> AfterPlayMode.PLAY_QUEUE
                "播放合集中的下一个" -> AfterPlayMode.NEXT_EPISODE
                null, "" -> AfterPlayMode.NEXT_EPISODE
                else -> AfterPlayMode.NEXT_EPISODE
            },
            exitPlayerWhenPlaybackFinished = parseToggle(
                readSetting(KEY_PLAY_FINISH_EXIT_PLAYER),
                defaultValue = true
            ),
            showSubtitleByDefault = parseToggle(
                readSetting(KEY_SHOW_SUBTITLE_DEFAULT),
                defaultValue = false
            ),
            subtitleTextSizePx = readSetting(KEY_SUBTITLE_TEXT_SIZE)
                ?.toIntOrNull()
                ?.coerceIn(30, 60)
                ?: 45,
            showBottomProgressBar = parseToggle(
                readSetting(KEY_SHOW_BOTTOM_PROGRESS_BAR),
                defaultValue = false
            ),
            showDebugInfo = parseToggle(
                readSetting(KEY_SHOW_DEBUG),
                defaultValue = false
            ),
            simpleKeyPress = parseToggle(
                readSetting(KEY_SIMPLE_KEY_PRESS),
                defaultValue = false
            ),
            showRewindFastForward = parseToggle(
                readSetting(KEY_SHOW_RE_FF),
                defaultValue = false
            ),
            showNextPrevious = parseToggle(
                readSetting(KEY_SHOW_NEXT_PREVIOUS),
                defaultValue = false
            ),
            showDanmakuSwitch = parseToggle(
                readSetting(KEY_SHOW_DM_SWITCH),
                defaultValue = false
            ),
            fastSeekSeconds = readSetting(KEY_FF_SEEK_SECOND)
                ?.trim()
                ?.removeSuffix("s")
                ?.toIntOrNull()
                ?.coerceIn(5, 60)
                ?: 10,
            resumePlayback = parseToggle(
                readSetting(KEY_RESUME_PLAYBACK),
                defaultValue = true
            ),
            sponsorBlockEnabled = parseToggle(
                readSetting(KEY_SPONSOR_BLOCK_ENABLED),
                defaultValue = true
            ),
            sponsorBlockAutoSkip = parseToggle(
                readSetting(KEY_SPONSOR_BLOCK_AUTO_SKIP),
                defaultValue = true
            )
        )
        cachedSettings = settings
        lastSettingsSnapshot = snapshot
        return settings
    }

    private fun parseVideoQualityId(value: String?): Int? {
        return when (value?.trim()?.uppercase()) {
            null, "" -> VideoQualityDefaults.DEFAULT_VIDEO_QUALITY_ID
            "AUTO", "自动" -> null
            "8K" -> 127
            "杜比视界", "DOLBYVISION" -> 126
            "HDR VIVID", "HDRVIVID" -> 129
            "HDR" -> 125
            "4K" -> 120
            "1080P60" -> 116
            "1080P+" -> 112
            "智能修复", "SUPERRESOLUTION" -> 100
            "1080P" -> 80
            "720P60" -> 74
            "720P" -> 64
            "480P" -> 32
            "360P" -> 16
            "240P" -> 6
            else -> value.toIntOrNull()
        }
    }

    private fun parseAudioQualityId(value: String?): Int? {
        return when (value?.trim()?.uppercase()) {
            null, "" -> VideoQualityDefaults.DEFAULT_AUDIO_QUALITY_ID
            "192KBPS", "192K", AudioQuality.AUDIO_192K.name.uppercase() -> AudioQuality.AUDIO_192K.id
            "132KBPS", "132K", AudioQuality.AUDIO_132K.name.uppercase() -> AudioQuality.AUDIO_132K.id
            "64KBPS", "64K", AudioQuality.AUDIO_64K.name.uppercase() -> AudioQuality.AUDIO_64K.id
            "DOLBYATMOS", "杜比全景声" -> AudioQuality.AUDIO_DOLBY.id
            "HI-RES无损", "HI-RES", "HIRES" -> AudioQuality.AUDIO_HIRES.id
            else -> value.toIntOrNull()
        }
    }

    private fun parseVideoCodec(value: String?): VideoCodecEnum? {
        return when (value?.trim()?.uppercase()) {
            null, "" -> VideoCodecEnum.HEVC
            "AVC", "H.264", "H264" -> VideoCodecEnum.AVC
            "HEVC", "H.265", "H265" -> VideoCodecEnum.HEVC
            "AV1" -> VideoCodecEnum.AV1
            else -> VideoCodecEnum.HEVC
        }
    }

    private fun parseToggle(value: String?, defaultValue: Boolean): Boolean {
        return when (value?.trim()) {
            "开", "ON", "true", "TRUE", "1" -> true
            "关", "OFF", "false", "FALSE", "0" -> false
            else -> defaultValue
        }
    }
}
