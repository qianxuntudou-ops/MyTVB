package com.mytvb.core.ui.focus

import com.mytvb.core.common.settings.AppSettingsDataStore

/**
 * 「视频卡片左右移到列表边缘自动切换顶部 tab」开关（设置页通用·浏览与入口组）。
 * 默认开；关闭时三个 switchAdjacentTabFromContentEdge（首页/分区/我的，收藏 tab
 * 转调我的）统一吞键停原地——边缘按键不切 tab 也不去侧栏，去侧栏仍可从顶部
 * tab 栏按 LEFT。存储值为中文"开/关"，与设置页其他开关同口径。
 */
object EdgeTabSwitch {
    const val KEY = "edge_tab_switch"

    fun enabled(appSettings: AppSettingsDataStore): Boolean {
        return appSettings.getCachedString(KEY, "开") != "关"
    }
}
