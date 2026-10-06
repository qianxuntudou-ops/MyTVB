package com.mytvb.feature.player.view

import android.content.Context
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.feature.player.danmaku.DanmakuTrackSpacing
import com.mytvb.model.dm.DmScreenArea
import org.koin.mp.KoinPlatform

internal class MyPlayerSettingPreferenceStore(
    context: Context
) {

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /**
     * 读取全局弹幕行间距设置（prefValue，如 "standard"）。仅在 APP设置-弹幕设置中可调，
     * 不在播放器内面板暴露，故独立于 [loadDanmakuState] 的 PanelState 通道。
     */
    fun getTrackSpacingPref(): String =
        appSettings.getCachedString(MyPlayerSettingView.KEY_DM_TRACK_SPACING) ?: DanmakuTrackSpacing.DEFAULT.prefValue

    fun loadDanmakuState(
        state: MyPlayerSettingMenuBuilder.PanelState
    ): MyPlayerSettingMenuBuilder.PanelState {
        return state.copy(
            dmEnabled = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ENABLE)?.let { it == "开" } ?: true,
            dmAlpha = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALPHA)?.toFloatOrNull() ?: 1.0f,
            dmTextSize = MyPlayerSettingView.coerceDmTextSize(
                appSettings.getCachedString(MyPlayerSettingView.KEY_DM_TEXT_SIZE)?.let {
                    when (it) {
                        "小号" -> 35
                        "大号" -> 45
                        else -> it.toIntOrNull() ?: 40
                    }
                } ?: 40
            ),
            dmSpeed = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_SPEED)?.toIntOrNull() ?: 4,
            dmArea = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_AREA)?.let {
                when (it) {
                    "1/8" -> DmScreenArea.OneEighth.area
                    "1/6" -> DmScreenArea.OneSixth.area
                    "1/4" -> DmScreenArea.Quarter.area
                    "1/2" -> DmScreenArea.Half.area
                    "3/4" -> DmScreenArea.ThreeQuarter.area
                    "全屏" -> DmScreenArea.Full.area
                    else -> it.toIntOrNull() ?: DmScreenArea.Half.area
                }
            } ?: DmScreenArea.Half.area,
            dmAllowTop = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALLOW_TOP)?.let { it == "开" } ?: false,
            dmAllowBottom = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_ALLOW_BOTTOM)?.let { it == "开" } ?: false,
            dmMergeDuplicate = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_MERGE_DUPLICATE)?.let { it == "开" } ?: true,
            dmSmartShield = appSettings.getCachedString(MyPlayerSettingView.KEY_DM_SMART_SHIELD)?.let { it == "开" } ?: false
        )
    }
}
