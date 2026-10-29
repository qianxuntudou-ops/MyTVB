package com.mytvb.feature.marmot.quality

import com.mytvb.feature.cctv.CctvLiveQuality
import com.mytvb.feature.marmot.domain.HzItem

/**
 * CCTV 直播画质 Provider（方案 C 第一个实现）。
 *
 * 对标 v1.5.8 `CctvPlayerActivity`：CCTV HLS 直播是单条 master 清单，**运行时页面无可切换的
 * 画质 DOM**（实测 `#player_resolution_bar_player` 不存在，`[itemvalue]` 匹配到的是音效按钮）。
 * 因此画质写死 4 档（见 [CctvLiveQuality]），切换 = 按新档位重建播放器 HTML
 * （[CctvLiveQualityHook] 改写 master 清单的 `b=` 码率窗口真正切流，br 参数官方已忽略）。
 *
 * 当前画质状态保存在本对象（[current]），切台/重建时由 Activity 读取 [current] 生成 HTML。
 *
 * @param onSwitch 切画质时的回调（Activity 用当前 [current] 重建播放页）
 */
class CctvQualityProvider(
    private val onSwitch: () -> Unit
) : LiveQualityProvider {

    /** 当前画质（重建 HTML 的码率窗口来源）。默认最高档。 */
    var current: CctvLiveQuality = CctvLiveQuality.P1080
        private set

    override fun availableQualities(): List<HzItem> =
        CctvLiveQuality.entries.map {
            HzItem(name = it.label, level = it.level, isCurrent = it == current)
        }

    override fun currentQualityIndex(items: List<HzItem>): Int =
        items.indexOfFirst { it.name == current.label }.coerceAtLeast(0)

    override fun switchTo(item: HzItem, onSwitched: () -> Unit): Boolean {
        val next = CctvLiveQuality.entries.firstOrNull { it.label == item.name } ?: return false
        if (next == current) return false
        current = next
        onSwitch()       // Activity 用 current 重建播放页（注入新档位的 b= 窗口 hook）
        onSwitched()
        return true
    }
}
