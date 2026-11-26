package com.mytvb.feature.marmot.quality

import android.content.res.Resources
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
 * 当前档位保存在本对象（[current]），切台/重建时由 Activity 读取生成 HTML。
 *
 * 档位语义（issue#132）：
 * - [current] 是**当前实际生效**的档位，自动降档（[dropForRecovery]）只改它；
 * - [userChoice] 是**用户手选**的档位（持久化来源）。自动降档不回写用户选择，
 *   切台时用 [restoreUserChoiceOnChannelSwitch] 回到用户选择（未手选=最高档），
 *   避免一次卡顿降档后，后续所有频道都被拖在低档。
 *
 * @param resources 档位显示文案资源（本地化；分辨率文案见 [CctvLiveQuality.displayNameRes]）
 * @param onSwitch 切画质时的回调（Activity 用当前 [current] 重建播放页）
 */
class CctvQualityProvider(
    private val resources: Resources,
    private val onSwitch: () -> Unit
) : LiveQualityProvider {

    /** 当前实际生效档位（重建 HTML/解析流时读它；自动降档会改它）。 */
    var current: CctvLiveQuality = CctvLiveQuality.P1080
        private set

    /** 用户手选的档位（持久化来源）。自动降档只改 [current]，不改这里。 */
    private var userChoice: CctvLiveQuality = CctvLiveQuality.P1080

    /**
     * 恢复上次用户手选的画质（AppSettingsDataStore 持久化，跨会话/跨频道生效）。
     * label 不在档位表内（如老版本存储值）时保持默认最高档。
     * 注意与 [dropForRecovery] 区分：自动降档不回写 [userChoice]，只有用户手选才落盘。
     */
    fun restore(label: String?) {
        val saved = CctvLiveQuality.entries.firstOrNull { it.label == label } ?: return
        current = saved
        userChoice = saved
    }

    /**
     * 切台恢复（issue#132）：自动降档只作用于发生卡顿的那个频道。换台时回到用户手选档
     * （未手选=最高档），否则某个台卡顿降档后，后续切到的所有频道都会沿用低档。
     */
    fun restoreUserChoiceOnChannelSwitch() {
        current = userChoice
    }

    override fun availableQualities(): List<HzItem> =
        CctvLiveQuality.entries.map {
            HzItem(
                name = resources.getString(it.displayNameRes),
                level = it.level,
                isCurrent = it == current
            )
        }

    override fun currentQualityIndex(items: List<HzItem>): Int =
        items.indexOfFirst { it.level == current.level }.coerceAtLeast(0)

    /**
     * 持久化键：用稳定 label（旧版本存的就是"1080P"等），不受显示文案本地化影响。
     * 无效项返回 null。
     */
    fun storageKeyOf(item: HzItem): String? =
        CctvLiveQuality.entries.firstOrNull { it.level == item.level }?.label

    /**
     * 播放停滞自动恢复用：降一档（不触发 [onSwitch]，由调用方自行重建播放页）。
     * 注意 1080 档顶格锁的仍是 1800k 720p（`_pd`「1080P」变体经实测多数频道是 360P 假档），
     * 因此从 1080/720 都要降到 540 才是真实降码率；已在 360 则无可降，返回 false。
     */
    fun dropForRecovery(): Boolean {
        val next = when (current) {
            CctvLiveQuality.P1080, CctvLiveQuality.P720 -> CctvLiveQuality.P540
            CctvLiveQuality.P540 -> CctvLiveQuality.P360
            CctvLiveQuality.P360 -> null
        } ?: return false
        current = next
        return true
    }

    override fun switchTo(item: HzItem, onSwitched: () -> Unit): Boolean {
        val next = CctvLiveQuality.entries.firstOrNull { it.level == item.level }
            ?: CctvLiveQuality.entries.firstOrNull { it.label == item.name }
            ?: return false
        // 用户手选即记住（含自动降档后重新选回当前档），供切台恢复
        userChoice = next
        if (next == current) return false
        current = next
        onSwitch()       // Activity 用 current 重建播放页（注入新档位的 b= 窗口 hook）
        onSwitched()
        return true
    }
}
