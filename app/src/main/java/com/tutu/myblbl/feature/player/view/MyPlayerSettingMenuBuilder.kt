package com.tutu.myblbl.feature.player.view

import android.content.Context
import com.tutu.myblbl.R
import com.tutu.myblbl.feature.player.settings.AfterPlayMode
import com.tutu.myblbl.feature.player.LiveQualityInfo
import com.tutu.myblbl.model.dm.DmScreenArea
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality
import java.util.Locale

/**
 * Builds immutable menu models so MyPlayerSettingView only coordinates navigation and callbacks.
 */
internal class MyPlayerSettingMenuBuilder(
    private val context: Context
) {

    data class PanelState(
        val videoQualities: List<VideoQuality> = emptyList(),
        val audioQualities: List<AudioQuality> = emptyList(),
        val videoCodecs: List<VideoCodecEnum> = emptyList(),
        val subtitles: List<SubtitleInfoModel> = emptyList(),
        val currentVideoQuality: VideoQuality? = null,
        val currentAudioQuality: AudioQuality? = AudioQuality.AUDIO_192K,
        val currentVideoCodec: VideoCodecEnum? = VideoCodecEnum.HEVC,
        val currentSubtitlePosition: Int = -1,
        val currentSpeed: Float = 1.0f,
        val currentScreenRatio: Int = 0,
        val dmEnabled: Boolean = true,
        val dmAlpha: Float = 1.0f,
        val dmTextSize: Int = 40,
        val dmSpeed: Int = 4,
        val dmArea: Int = DmScreenArea.Half.area,
        val dmAllowTop: Boolean = false,
        val dmAllowBottom: Boolean = false,
        val dmMergeDuplicate: Boolean = true,
        val dmSmartShield: Boolean = true,
        val screenMirrorEnabled: Boolean = false,
        val afterPlayMode: AfterPlayMode = AfterPlayMode.NEXT_EPISODE,
        val liveQualities: List<LiveQualityInfo> = emptyList(),
        val currentLiveQualityQn: Int? = null
    )

    data class DmChoiceMenu(
        val menuKey: Int,
        val title: String,
        val values: List<String>,
        val selectedIndex: Int
    )

    fun buildMainMenu(state: PanelState): List<PlayerSettingRow> {
        return listOf(
            PlayerSettingRow.Header(title = context.getString(R.string.setting)),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_VIDEO_QUALITY,
                title = context.getString(R.string.video_quality),
                value = state.currentVideoQuality?.name ?: "1080P",
                iconRes = R.drawable.ic_video_play_count
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_PLAYBACK_SPEED,
                title = context.getString(R.string.playSpeed),
                value = speedDisplayLabel(state.currentSpeed),
                iconRes = R.drawable.exo_ic_speed
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_AFTER_PLAY,
                title = "播放完成后",
                value = afterPlayModeLabel(state.afterPlayMode),
                iconRes = R.drawable.ic_after_play
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_SUBTITLE,
                title = context.getString(R.string.subtitle),
                value = subtitleDisplayValue(state.subtitles, state.currentSubtitlePosition),
                iconRes = R.drawable.ic_subtitles
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_VIDEO_CODEC,
                title = context.getString(R.string.video_codec),
                value = state.currentVideoCodec?.displayName ?: "HEVC",
                iconRes = R.drawable.ic_setting
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_ASPECT_RATIO,
                title = context.getString(R.string.screen_ratio),
                value = screenRatioLabels().getOrElse(state.currentScreenRatio) { screenRatioLabels().first() },
                iconRes = R.drawable.ic_screen_ratio
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_SCREEN_MIRROR,
                title = context.getString(R.string.screen_mirror),
                value = state.screenMirrorEnabled.toOpenCloseLabel(),
                iconRes = if (state.screenMirrorEnabled) R.drawable.ic_mirror_on else R.drawable.ic_mirror_off
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_DM_SETTING,
                title = context.getString(R.string.dm_setting),
                value = "",
                iconRes = if (state.dmEnabled) R.drawable.ic_dm_enable else R.drawable.ic_dm_disable
            ),
            PlayerSettingRow.Item(
                id = MyPlayerSettingView.ITEM_AUDIO_QUALITY,
                title = context.getString(R.string.audioTrack),
                value = state.currentAudioQuality?.name ?: AudioQuality.AUDIO_192K.name,
                iconRes = R.drawable.exo_ic_audiotrack
            )
        )
    }

    fun buildVideoQualityMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(
                title = context.getString(R.string.video_quality)
            )
        )
        rows += state.videoQualities.mapIndexed { index, quality ->
            PlayerSettingRow.Item(
                id = index,
                title = quality.name,
                value = quality.resolution,
                checked = quality.id == state.currentVideoQuality?.id,
                showArrow = false
            )
        }
        return rows
    }

    fun buildPlaybackSpeedMenu(state: PanelState): List<PlayerSettingRow> {
        val labels = resolvePlaybackSpeedLabels()
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.playSpeed))
        )
        rows += MyPlayerSettingView.PLAYBACK_SPEEDS.mapIndexed { index, speed ->
            PlayerSettingRow.Item(
                id = index,
                title = labels.getOrElse(index) { formatSpeed(speed) },
                checked = speed == state.currentSpeed,
                showArrow = false
            )
        }
        return rows
    }

    fun buildSubtitleMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.subtitle))
        )
        rows += PlayerSettingRow.Item(
            id = -1,
            title = context.getString(R.string.off),
            checked = state.currentSubtitlePosition !in state.subtitles.indices,
            showArrow = false
        )
        rows += state.subtitles.mapIndexed { index, subtitle ->
            PlayerSettingRow.Item(
                id = index,
                title = subtitle.lanDoc,
                checked = index == state.currentSubtitlePosition,
                showArrow = false
            )
        }
        return rows
    }

    fun buildVideoCodecMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.video_codec))
        )
        rows += state.videoCodecs.mapIndexed { index, codec ->
            PlayerSettingRow.Item(
                id = index,
                title = codec.displayName,
                checked = codec == state.currentVideoCodec,
                showArrow = false
            )
        }
        return rows
    }

    fun buildAudioQualityMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.audioTrack))
        )
        rows += state.audioQualities.mapIndexed { index, quality ->
            PlayerSettingRow.Item(
                id = index,
                title = quality.name,
                checked = quality.id == state.currentAudioQuality?.id,
                showArrow = false
            )
        }
        return rows
    }

    fun buildScreenRatioMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.screen_ratio))
        )
        rows += screenRatioLabels().mapIndexed { index, title ->
            PlayerSettingRow.Item(
                id = index,
                title = title,
                checked = index == state.currentScreenRatio,
                showArrow = false
            )
        }
        return rows
    }

    fun buildLiveQualityMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = context.getString(R.string.live_quality))
        )
        rows += state.liveQualities.mapIndexed { index, quality ->
            PlayerSettingRow.Item(
                id = index,
                title = quality.desc,
                checked = quality.qn == state.currentLiveQualityQn,
                showArrow = false
            )
        }
        return rows
    }

    fun buildDmSettingMenu(state: PanelState): List<PlayerSettingRow> {
        fun dmTextSizeToLabel(size: Int): String = size.toString()

        return listOf(
            PlayerSettingRow.Header(title = context.getString(R.string.dm_setting)),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_ENABLE, context.getString(R.string.dm_switch), state.dmEnabled.toOpenCloseLabel()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_ALPHA, context.getString(R.string.dm_alpha), state.dmAlpha.toDisplayString()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_TEXT_SIZE, context.getString(R.string.dm_text_size), dmTextSizeToLabel(state.dmTextSize)),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_AREA, context.getString(R.string.dm_screen_area), getAreaDisplay(state.dmArea)),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_SPEED, context.getString(R.string.dm_speed), state.dmSpeed.toString()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_ALLOW_TOP, context.getString(R.string.dm_allow_top), state.dmAllowTop.toOpenCloseLabel()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_ALLOW_BOTTOM, context.getString(R.string.dm_allow_bottom), state.dmAllowBottom.toOpenCloseLabel()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_MERGE_DUPLICATE, context.getString(R.string.dm_merge_duplicate), state.dmMergeDuplicate.toOpenCloseLabel()),
            PlayerSettingRow.Item(MyPlayerSettingView.ITEM_DM_SMART_SHIELD, context.getString(R.string.dm_smart_shield), state.dmSmartShield.toOpenCloseLabel())
        )
    }

    fun buildDmChoiceMenu(itemId: Int, state: PanelState): DmChoiceMenu? {
        return when (itemId) {
            MyPlayerSettingView.ITEM_DM_ENABLE -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_ENABLE,
                title = context.getString(R.string.dm_switch),
                currentValue = state.dmEnabled
            )

            MyPlayerSettingView.ITEM_DM_ALPHA -> buildChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_ALPHA,
                title = context.getString(R.string.dm_alpha),
                values = MyPlayerSettingView.DM_ALPHA_VALUES.map { it.toDisplayString() },
                selectedIndex = MyPlayerSettingView.DM_ALPHA_VALUES.indexOfFirst { it == state.dmAlpha }.coerceAtLeast(0)
            )

            MyPlayerSettingView.ITEM_DM_TEXT_SIZE -> buildChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_TEXT_SIZE,
                title = context.getString(R.string.dm_text_size),
                values = MyPlayerSettingView.DM_TEXT_SIZE_VALUES.map(Int::toString),
                selectedIndex = MyPlayerSettingView.DM_TEXT_SIZE_VALUES.indexOf(state.dmTextSize).coerceAtLeast(0)
            )

            MyPlayerSettingView.ITEM_DM_AREA -> buildChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_AREA,
                title = context.getString(R.string.dm_screen_area),
                values = MyPlayerSettingView.DM_AREA_VALUES.map { it.showName },
                selectedIndex = MyPlayerSettingView.DM_AREA_VALUES.indexOfFirst { it.area == state.dmArea }.coerceAtLeast(0)
            )

            MyPlayerSettingView.ITEM_DM_SPEED -> buildChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_SPEED,
                title = context.getString(R.string.dm_speed),
                values = MyPlayerSettingView.DM_SPEED_VALUES.map(Int::toString),
                selectedIndex = MyPlayerSettingView.DM_SPEED_VALUES.indexOf(state.dmSpeed).coerceAtLeast(0)
            )

            MyPlayerSettingView.ITEM_DM_ALLOW_TOP -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_ALLOW_TOP,
                title = context.getString(R.string.dm_allow_top),
                currentValue = state.dmAllowTop
            )

            MyPlayerSettingView.ITEM_DM_ALLOW_BOTTOM -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_ALLOW_BOTTOM,
                title = context.getString(R.string.dm_allow_bottom),
                currentValue = state.dmAllowBottom
            )

            MyPlayerSettingView.ITEM_DM_MERGE_DUPLICATE -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_MERGE_DUPLICATE,
                title = context.getString(R.string.dm_merge_duplicate),
                currentValue = state.dmMergeDuplicate
            )

            MyPlayerSettingView.ITEM_DM_SMART_SHIELD -> buildBooleanChoiceMenu(
                menuKey = MyPlayerSettingView.ITEM_DM_SMART_SHIELD,
                title = context.getString(R.string.dm_smart_shield),
                currentValue = state.dmSmartShield
            )

            else -> null
        }
    }

    fun screenRatioLabels(): Array<String> {
        return context.resources.getStringArray(R.array.screen_ratios)
    }

    private fun buildBooleanChoiceMenu(
        menuKey: Int,
        title: String,
        currentValue: Boolean
    ): DmChoiceMenu {
        return buildChoiceMenu(
            menuKey = menuKey,
            title = title,
            values = listOf(context.getString(R.string.on), context.getString(R.string.off)),
            selectedIndex = if (currentValue) 0 else 1
        )
    }

    private fun buildChoiceMenu(
        menuKey: Int,
        title: String,
        values: List<String>,
        selectedIndex: Int
    ): DmChoiceMenu {
        return DmChoiceMenu(
            menuKey = menuKey,
            title = title,
            values = values,
            selectedIndex = selectedIndex
        )
    }

    private fun speedDisplayLabel(currentSpeed: Float): String {
        val labels = resolvePlaybackSpeedLabels()
        val index = MyPlayerSettingView.PLAYBACK_SPEEDS.indexOfFirst { it == currentSpeed }
        return labels.getOrElse(index) { formatSpeed(currentSpeed) }
    }

    private fun resolvePlaybackSpeedLabels(): List<String> {
        val labels = context.resources.getStringArray(R.array.exo_controls_playback_speeds)
        return if (labels.size == MyPlayerSettingView.PLAYBACK_SPEEDS.size) {
            labels.toList()
        } else {
            MyPlayerSettingView.PLAYBACK_SPEEDS.map(::formatSpeed)
        }
    }

    private fun subtitleDisplayValue(
        subtitles: List<SubtitleInfoModel>,
        currentSubtitlePosition: Int
    ): String {
        return when {
            subtitles.isEmpty() -> "不可用"
            currentSubtitlePosition !in subtitles.indices -> context.getString(R.string.off)
            else -> subtitles.getOrNull(currentSubtitlePosition)?.lanDoc ?: "不可用"
        }
    }

    private fun getAreaDisplay(area: Int): String {
        return DmScreenArea.entries.firstOrNull { it.area == area }?.showName ?: DmScreenArea.Full.showName
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed % 1f == 0f) {
            "${speed.toInt()}.0x"
        } else {
            "${speed}x"
        }
    }

    private fun Float.toDisplayString(): String {
        return if (this % 1f == 0f) {
            String.format(Locale.getDefault(), "%.1f", this)
        } else {
            toString()
        }
    }

    private fun Boolean.toOpenCloseLabel(): String = if (this) "开" else "关"

    internal val AFTER_PLAY_OPTIONS = listOf(
        AfterPlayMode.NOTHING to "什么都不做",
        AfterPlayMode.RECOMMEND to "播推荐视频",
        AfterPlayMode.PLAY_QUEUE to "播列表中的下一个",
        AfterPlayMode.NEXT_EPISODE to "播放合集中的下一个"
    )

    fun buildAfterPlayMenu(state: PanelState): List<PlayerSettingRow> {
        val rows = mutableListOf<PlayerSettingRow>(
            PlayerSettingRow.Header(title = "播放完成后")
        )
        val selectedIndex = AFTER_PLAY_OPTIONS.indexOfFirst { it.first == state.afterPlayMode }.coerceAtLeast(0)
        rows += AFTER_PLAY_OPTIONS.mapIndexed { index, option ->
            PlayerSettingRow.Item(
                id = index,
                title = option.second,
                checked = index == selectedIndex,
                showArrow = false
            )
        }
        return rows
    }

    private fun afterPlayModeLabel(mode: AfterPlayMode): String {
        return AFTER_PLAY_OPTIONS.firstOrNull { it.first == mode }?.second ?: "什么都不做"
    }
}
