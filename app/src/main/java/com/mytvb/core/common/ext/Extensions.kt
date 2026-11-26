package com.mytvb.core.common.ext

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.ui.base.AppToast
import org.koin.mp.KoinPlatform

private const val VALUE_ON = "开"
private const val VALUE_OFF = "关"
private const val VALUE_FILTER_LEVEL_MIN = 1
private const val VALUE_FILTER_LEVEL_MAX = 10
private val appSettings: AppSettingsDataStore
    get() = KoinPlatform.getKoin().get()

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    AppToast.show(applicationContext, message, duration)
}

fun Context.toast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    AppToast.show(applicationContext, resId, duration)
}

fun isOpenDetailFirstEnabled(): Boolean {
    return getToggleSetting("show_video_detail", false)
}

fun getDanmakuSmartFilterLevel(): Int {
    val raw = appSettings.getCachedString("dm_filter_weight")?.trim() ?: return 0
    val parsed = raw.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return parsed
    }
    return when (raw) {
        "低" -> 1
        "中" -> 5
        "高" -> 10
        VALUE_ON -> 1
        else -> 0
    }
}

fun normalizeDanmakuSmartFilterValue(value: String?): String {
    val trimmed = value?.trim()
    val parsed = trimmed?.toIntOrNull()
    if (parsed != null && parsed in VALUE_FILTER_LEVEL_MIN..VALUE_FILTER_LEVEL_MAX) {
        return trimmed
    }
    return when (trimmed) {
        "低" -> "1"
        "中" -> "5"
        "高" -> "10"
        VALUE_ON -> "1"
        else -> VALUE_OFF
    }
}

fun isVipColorfulDanmakuAllowed(): Boolean {
    return getToggleSetting("dm_allow_vip_colorful_dm", true)
}

/**
 * 「默认首页」设置 → 首页 ViewPager 起始索引。
 *
 * 存储值仍是中文标签（"推荐"/"热门"/"历史"/"稍后观看"/"番剧"/"影视"/"收藏"，与设置项
 * 落盘格式一致），实际位置由 [positionOfLabel] 按当前「首页页签」配置解析——
 * 页签顺序可由用户调整、页签也可能被隐藏，不能再按固定索引映射。
 * 标签无法解析（历史遗留值/页签已隐藏）时回退第一个可见页签（索引 0）。
 */
fun getHomeDefaultStartPageIndex(
    maxIndex: Int,
    defaultIndex: Int = 0,
    positionOfLabel: (String) -> Int = { -1 }
): Int {
    val safeMaxIndex = maxIndex.coerceAtLeast(0)
    val fallback = defaultIndex.coerceIn(0, safeMaxIndex)
    val label = appSettings.getCachedString("default_start_page")?.trim()
    if (label.isNullOrEmpty()) return fallback
    val position = positionOfLabel(label)
    return if (position >= 0) position.coerceIn(0, safeMaxIndex) else fallback
}

private fun getToggleSetting(key: String, defaultValue: Boolean): Boolean {
    val fallback = if (defaultValue) VALUE_ON else VALUE_OFF
    return (appSettings.getCachedString(key) ?: fallback) == VALUE_ON
}
