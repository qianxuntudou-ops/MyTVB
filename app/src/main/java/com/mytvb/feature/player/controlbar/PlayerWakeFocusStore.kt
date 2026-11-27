package com.mytvb.feature.player.controlbar

import com.mytvb.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

/**
 * 控制栏唤醒焦点落点（issue#150 建议）：控制栏隐藏时按方向键/OK 唤醒（以及控制栏
 * 可见但焦点丢失的兜底），初始焦点默认固定落「播放/暂停」；本存储让用户改落常用
 * 按钮（如三连菜单=「更多」）。存储为 appSettings 单键中文名，选项文本=
 * [ControlBarButton] 显示名（与设置页 stored 形态一致）；未存储/文本无法识别时按
 * 默认「播放/暂停」处理。应用点在 PlayerControlFocusCoordinator 唤醒落焦处经
 * MyPlayerControlView 注入的 provider 实时读缓存值——设置改动即时生效。
 */
object PlayerWakeFocusStore {
    const val STORE_KEY = "player_wake_focus_target"

    private fun appSettings(): AppSettingsDataStore = KoinPlatform.getKoin().get()

    /**
     * 存储文本 → 目标按钮；[nameOf] 注入枚举显示名解析（单测免 Android 资源）。
     * 空白/未识别返回 null=落默认「播放/暂停」。
     */
    fun resolve(raw: String?, nameOf: (ControlBarButton) -> String): ControlBarButton? {
        val label = raw?.trim().orEmpty()
        if (label.isEmpty()) return null
        return ControlBarButton.entries.firstOrNull { nameOf(it) == label }
    }

    /** 当前配置的落点按钮；未配置返回 null（调用方回落播放/暂停）。 */
    fun currentTarget(nameOf: (ControlBarButton) -> String): ControlBarButton? =
        resolve(appSettings().getCachedString(STORE_KEY), nameOf)
}
