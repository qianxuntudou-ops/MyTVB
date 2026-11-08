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

fun getHomeDefaultStartPageIndex(
    maxIndex: Int,
    defaultIndex: Int = 1,
    animationIndex: Int = 2,
    cinemaIndex: Int = 3
): Int {
    val safeMaxIndex = maxIndex.coerceAtLeast(0)
    val clampedDefault = defaultIndex.coerceIn(0, safeMaxIndex)
    val mappedFromLabel = when (appSettings.getCachedString("default_start_page")?.trim()) {
        "推荐" -> 0
        "热门" -> 1
        // 番剧/影视的实际位置随「首页个人页签」开关变化（默认 2/3，开启时 4/5），
        // 由调用方按当前 tab 配置传入
        "番剧" -> animationIndex
        "影视" -> cinemaIndex
        "动态" -> clampedDefault
        else -> null
    }
    if (mappedFromLabel != null) {
        val normalized = mappedFromLabel.coerceIn(0, safeMaxIndex)
        if (appSettings.getCachedInt("defaultStartPage", Int.MIN_VALUE) != normalized) {
            appSettings.putIntAsync("defaultStartPage", normalized)
        }
        return normalized
    }
    return appSettings.getCachedInt("defaultStartPage", clampedDefault)
        .coerceIn(0, safeMaxIndex)
}

private fun getToggleSetting(key: String, defaultValue: Boolean): Boolean {
    val fallback = if (defaultValue) VALUE_ON else VALUE_OFF
    return (appSettings.getCachedString(key) ?: fallback) == VALUE_ON
}
