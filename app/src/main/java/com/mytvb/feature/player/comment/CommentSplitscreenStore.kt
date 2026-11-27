package com.mytvb.feature.player.comment

import com.mytvb.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform

/**
 * 评论面板分屏显示（issue#150 建议）：开启后播放页打开评论面板时视频缩小为左侧
 * 垂直居中的 16:9 小窗，评论面板（独立 Dialog Window）从右缘划入，两侧拼成分屏，
 * 边看完整画面边读评论；关闭保持面板全屏覆盖的原行为。存储为 appSettings 单键
 * "开/关" 文本（与设置页 toggle 开关形态一致），默认关。消费点在
 * VideoPlayerOverlayController 打开/关闭评论面板处实时读缓存值——设置改动即时生效。
 */
object CommentSplitscreenStore {
    const val STORE_KEY = "comment_splitscreen"
    const val VALUE_ON = "开"
    const val VALUE_OFF = "关"

    private fun appSettings(): AppSettingsDataStore = KoinPlatform.getKoin().get()

    /** 存储文本 → 是否分屏；非「开」一律按关处理（与设置页 toggle 存储形态一致）。 */
    fun isStoredEnabled(raw: String?): Boolean = raw == VALUE_ON

    /** 当前是否启用分屏（实时读缓存）。 */
    fun isEnabled(): Boolean = isStoredEnabled(appSettings().getCachedString(STORE_KEY))
}
