package com.mytvb.feature.home

import android.content.Context
import androidx.fragment.app.Fragment
import com.mytvb.R
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.feature.favorite.FavoriteFragment
import com.mytvb.feature.me.MeListFragment
import org.koin.mp.KoinPlatform

/**
 * 首页二级页签配置（显示/隐藏 + 排序），设置「显示 · 首页页签」弹窗的数据源。
 *
 * 存储值 `home_tabs_config`：`id:visible,id:visible,...`，逗号分隔的顺序即页签顺序
 * （如 `0:1,1:1,4:1,5:1,2:1,3:1,6:1`）。无存储 = 默认顺序 + 全部显示。
 * id 全局稳定（与位置无关），ViewPager 靠它做 diff，改顺序/隐藏时其余页保留原数据与滚动位置。
 *
 * 历史背景：旧版「首页个人页签」开关（home_personal_tabs）只有开/关两套静态表，
 * 本配置取代它——历史/稍后观看/收藏固定进入首页候选页签，不再依赖开关。
 */
object HomeTabPrefs {

    const val KEY = "home_tabs_config"

    /**
     * 个人内容页签 id 集（历史/稍后观看/收藏，id 沿用旧静态表）。
     * 「侧栏『我的』入口」开关关闭且本集页签全部隐藏时三者无任何入口，用于设置侧的提示。
     */
    val PERSONAL_ENTRY_IDS = setOf(4L, 5L, 6L)

    /** 一个首页页签项。id 与 HomeFragmentStateAdapter 的 itemId 一致。 */
    data class HomeTabEntry(
        val id: Long,
        val titleRes: Int,
        val factory: () -> Fragment,
        val visible: Boolean = true
    )

    private val appSettings: AppSettingsDataStore
        get() = KoinPlatform.getKoin().get<AppSettingsDataStore>()

    /**
     * 全部页签（单一事实源）：默认顺序 推荐/热门/历史/稍后观看/番剧/影视/收藏。
     * id 沿用旧静态表（0/1/2/3/4/5/6），升级后已创建的页签 fragment 不会重建。
     */
    fun allEntries(): List<HomeTabEntry> = listOf(
        HomeTabEntry(0, R.string.recommend, factory = { RecommendListFragment.newInstance() }),
        HomeTabEntry(1, R.string.hot, factory = { HotListFragment.newInstance() }),
        HomeTabEntry(4, R.string.history, factory = { MeListFragment.newInstance(MeListFragment.TYPE_HISTORY) }),
        HomeTabEntry(5, R.string.later_watch, factory = { MeListFragment.newInstance(MeListFragment.TYPE_LATER) }),
        HomeTabEntry(2, R.string.animation, factory = { HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_ANIMATION) }),
        HomeTabEntry(3, R.string.film_and_television, factory = { HomeLaneFragment.newInstance(HomeLaneFragment.TYPE_CINEMA) }),
        HomeTabEntry(6, R.string.collection, factory = { FavoriteFragment.newEmbeddedInstance() })
    )

    /**
     * 读当前配置：按存储顺序返回全部项（含隐藏项，供设置弹窗编辑）。
     * 未知 id 丢弃、重复 id 只认首个、缺失 id 按默认顺序补到末尾（版本升级/数据损坏兜底）；
     * 兜底保证至少一个可见项（全隐藏的手改值强制首项可见，首页不留空）。
     */
    fun loadEntries(): List<HomeTabEntry> {
        val all = allEntries()
        val raw = appSettings.getCachedString(KEY).orEmpty().trim()
        if (raw.isEmpty()) return all

        val parsed = mutableListOf<HomeTabEntry>()
        val seen = mutableSetOf<Long>()
        raw.split(',').forEach { token ->
            val parts = token.split(':')
            val id = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@forEach
            val entry = all.firstOrNull { it.id == id } ?: return@forEach
            if (!seen.add(id)) return@forEach
            parsed += entry.copy(visible = parts.getOrNull(1)?.trim() != "0")
        }
        all.forEach { entry ->
            if (entry.id !in seen) parsed += entry
        }
        if (parsed.none { it.visible }) {
            parsed[0] = parsed[0].copy(visible = true)
        }
        return parsed
    }

    /** 当前配置对应的 ViewPager 页签（仅可见项，按配置顺序）。 */
    fun loadSpecs(): List<HomeTabSpec> = loadEntries()
        .filter { it.visible }
        .map { HomeTabSpec(it.id, it.titleRes, it.factory) }

    fun saveEntries(entries: List<HomeTabEntry>) {
        // 同步落盘：确认后立即被 loadSpecs() 读回应用到首页，强杀也不丢配置
        appSettings.putStringBlocking(
            KEY,
            entries.joinToString(",") { "${it.id}:${if (it.visible) 1 else 0}" }
        )
    }

    /** 设置页条目 info：「显示 7/7」。 */
    fun summaryText(context: Context): String {
        val entries = loadEntries()
        return context.getString(
            R.string.home_tabs_summary,
            entries.count { it.visible },
            entries.size
        )
    }
}
