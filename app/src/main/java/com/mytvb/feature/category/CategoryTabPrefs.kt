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

    /**
     * 老 UGC 分区 tid → 官网频道 tid 一次性迁移表（2026-11 官网 /v/ 分区页下线换 /c/ 频道页，
     * 分类页数据源随之切换 region/feed/rcmd 推荐流，分区表 id 换成频道新体系 tid 1001~1031）。
     * PGC 三分区（177 纪录片/23 电影/11 电视剧）不在频道体系，已从分区表移除，老勾选直接丢弃。
     */
    private val LEGACY_TID_TO_CHANNEL_TID = mapOf(
        0 to 0,       // 全站，id 不变
        1 to 1005,    // 动画
        3 to 1003,    // 音乐
        129 to 1004,  // 舞蹈
        4 to 1008,    // 游戏
        36 to 1010,   // 知识
        188 to 1012,  // 科技数码
        234 to 1018,  // 体育运动
        223 to 1013,  // 汽车
        160 to 1030,  // 生活 → 生活兴趣（官网把老生活区拆散，映射最近的频道）
        211 to 1020,  // 美食
        217 to 1024,  // 动物
        119 to 1007,  // 鬼畜
        155 to 1014,  // 时尚美妆
        5 to 1002,    // 娱乐
        181 to 1001   // 影视
    )

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var legacyMigrationDone = false

    /**
     * 分区全量表（单一事实源）：过滤、设置页摘要与勾选弹窗共用。
     * id = 官网频道新体系 tid（region/feed/rcmd 的 from_region）；全站 id=0 走热门流。
     * 顺序：原 19 分区中保留的 16 个在前（老用户 tab 布局基本不变），
     * 官网新增频道追加在后，靠 issue#117 按需显示机制管理。
     */
    fun allCategories(context: Context): List<CategoryModel> {
        val res = context.resources
        return listOf(
            CategoryModel(0, res.getString(R.string.allWeb)),
            CategoryModel(1005, res.getString(R.string.cartoon)),
            CategoryModel(1003, res.getString(R.string.music)),
            CategoryModel(1004, res.getString(R.string.dance)),
            CategoryModel(1008, res.getString(R.string.game)),
            CategoryModel(1010, res.getString(R.string.knowledge)),
            CategoryModel(1012, res.getString(R.string.science)),
            CategoryModel(1018, res.getString(R.string.sport)),
            CategoryModel(1013, res.getString(R.string.auto)),
            CategoryModel(1030, res.getString(R.string.lifestyle)),
            CategoryModel(1020, res.getString(R.string.food)),
            CategoryModel(1024, res.getString(R.string.pet)),
            CategoryModel(1007, res.getString(R.string.kichiku)),
            CategoryModel(1014, res.getString(R.string.fashion)),
            CategoryModel(1002, res.getString(R.string.entainment)),
            CategoryModel(1001, res.getString(R.string.film_and_television)),
            CategoryModel(1009, res.getString(R.string.information)),
            CategoryModel(1011, res.getString(R.string.ai)),
            CategoryModel(1006, res.getString(R.string.painting)),
            CategoryModel(1019, res.getString(R.string.handmade)),
            CategoryModel(1025, res.getString(R.string.parenting)),
            CategoryModel(1023, res.getString(R.string.rural)),
            CategoryModel(1026, res.getString(R.string.health)),
            CategoryModel(1015, res.getString(R.string.home_decor)),
            CategoryModel(1022, res.getString(R.string.travel)),
            CategoryModel(1016, res.getString(R.string.outdoor)),
            CategoryModel(1017, res.getString(R.string.gym)),
            CategoryModel(1031, res.getString(R.string.life_experience)),
            CategoryModel(1029, res.getString(R.string.vlog)),
            CategoryModel(1027, res.getString(R.string.emotion)),
            CategoryModel(1028, res.getString(R.string.mysticism)),
            CategoryModel(1021, res.getString(R.string.shortplay))
        )
    }

    /**
     * 用户勾选的分区 id 集（字符串化）；空 = 从未设置 = 全部显示。
     * 首次读取时把「老分区 tid」翻译成频道 tid，其余值（已经是频道新 tid，或历史无效值）
     * 原样保留——不能按「映射表内才保留」过滤：用户在新表上保存过勾选后，重装重启会把
     * 整份新 id 集当老 id 集误迁丢弃，只剩全站（2026-11-20 实测翻车）。
     * 只勾过 PGC 三分区的老用户映射后变空，落回「全部显示」兜底；迁移有变化才落盘，只做一次。
     */
    fun selectedIds(): Set<String> {
        val stored = appSettings.getCachedStringSet(KEY)
        if (legacyMigrationDone || stored.isEmpty()) return stored
        val migrated = stored.map { raw ->
            val id = raw.toIntOrNull() ?: return@map raw
            LEGACY_TID_TO_CHANNEL_TID[id]?.toString() ?: raw
        }.toSet()
        legacyMigrationDone = true
        if (migrated != stored) {
            appSettings.putStringSetAsync(KEY, migrated)
            return migrated
        }
        return stored
    }

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
