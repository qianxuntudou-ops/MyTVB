package com.mytvb.feature.category

import android.content.Context
import com.mytvb.R
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.model.CategoryModel
import org.koin.mp.KoinPlatform

/**
 * 分类页分区显示过滤（issue#117）：存用户勾选的分区 id 集合。
 *
 * 语义：无存储（空集）= 全部分区显示，即默认全选，老用户升级后行为不变；
 * 弹窗确认时强制至少勾选一个，因此落盘值永远是「用户主动选择的非空子集」。
 */
object CategoryTabPrefs {

    const val KEY = "category_visible_ids"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /**
     * 分区全量表（原 CategoryFragment.initCategories 硬编码表收编至此，单一事实源）：
     * 过滤、设置页摘要与勾选弹窗共用，保证三处看到的分区集合一致。
     */
    fun allCategories(context: Context): List<CategoryModel> {
        val res = context.resources
        return listOf(
            CategoryModel(0, res.getString(R.string.allWeb)),
            CategoryModel(1, res.getString(R.string.cartoon)),
            CategoryModel(3, res.getString(R.string.music)),
            CategoryModel(129, res.getString(R.string.dance)),
            CategoryModel(4, res.getString(R.string.game)),
            CategoryModel(36, res.getString(R.string.knowledge)),
            CategoryModel(188, res.getString(R.string.science)),
            CategoryModel(234, res.getString(R.string.sport)),
            CategoryModel(223, res.getString(R.string.auto)),
            CategoryModel(160, res.getString(R.string.lifestyle)),
            CategoryModel(211, res.getString(R.string.food)),
            CategoryModel(217, res.getString(R.string.pet)),
            CategoryModel(119, res.getString(R.string.kichiku)),
            CategoryModel(155, res.getString(R.string.fashion)),
            CategoryModel(5, res.getString(R.string.entainment)),
            CategoryModel(181, res.getString(R.string.film_and_television)),
            CategoryModel(177, res.getString(R.string.documentary)),
            CategoryModel(23, res.getString(R.string.movie)),
            CategoryModel(11, res.getString(R.string.series))
        )
    }

    /** 用户勾选的分区 id 集（字符串化）；空 = 从未设置 = 全部显示。 */
    fun selectedIds(): Set<String> = appSettings.getCachedStringSet(KEY)

    /** 按勾选过滤分区表；未设置时原样返回全量。 */
    fun visibleCategories(all: List<CategoryModel>): List<CategoryModel> {
        val selected = selectedIds()
        if (selected.isEmpty()) return all
        return all.filter { selected.contains(it.id.toString()) }
    }

    fun saveSelectedIds(ids: Set<String>) {
        appSettings.putStringSetAsync(KEY, ids)
    }

    /** 设置页条目 info：未设置=「全部」，否则「已选数/总数」。 */
    fun summaryText(context: Context, all: List<CategoryModel>): String {
        val selected = selectedIds()
        if (selected.isEmpty()) return context.getString(R.string.category_tabs_all)
        val count = all.count { selected.contains(it.id.toString()) }
        return context.getString(R.string.category_tabs_count_format, count, all.size)
    }
}
