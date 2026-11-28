package com.mytvb.model.video

import com.google.gson.annotations.SerializedName

data class UserDynamicResponse(
    @SerializedName("archives")
    private val archivesData: List<VideoModel>? = null,
    @SerializedName("list")
    private val listData: UserDynamicList? = null,
    @SerializedName("page")
    private val page: UserDynamicPage? = null,
    @SerializedName("has_more")
    private val hasMoreData: Boolean? = null
){
    val archives: List<VideoModel>
        get() = archivesData ?: listData?.vlist.orEmpty()

    val hasMore: Boolean
        get() = hasMoreData ?: page?.let { it.pageSize > 0 && it.pageNumber * it.pageSize < it.totalCount } ?: false

    val totalCount: Int
        get() = page?.totalCount ?: archives.size
}

data class UserDynamicList(
    @SerializedName("vlist")
    val vlist: List<VideoModel> = emptyList()
)

// 兼容两个同构接口的分页字段：x/space/arc/list 用 count/pn/ps，x/series/archives 用 total/num/size
data class UserDynamicPage(
    @SerializedName(value = "count", alternate = ["total"])
    val totalCount: Int = 0,
    @SerializedName(value = "pn", alternate = ["num"])
    val pageNumber: Int = 1,
    @SerializedName(value = "ps", alternate = ["size"])
    val pageSize: Int = 0
)

data class AllDynamicResponse(
    @SerializedName("items")
    val items: List<VideoModel>? = null,
    @SerializedName("has_more")
    val hasMore: Boolean = false,
    @SerializedName("offset")
    val offset: Long = 0
)

// ---------- 单 UP 动态流（x/polymer/web-dynamic/desktop/v1/feed/space，issue#151） ----------
// polymer 嵌套结构：视频类动态取 modules[].module_dynamic.dyn_archive。动态视频与
// 投稿视频在动态体系中同为 DYNAMIC_TYPE_AV、都带可播放 bvid（投稿列表接口 arc/list
// 不含动态视频，是报告人「动态视频不显示」的根因）；两者靠 badge.text 文案区分。

data class UpDynamicFeedResponse(
    @SerializedName("items")
    val items: List<UpDynamicFeedItem> = emptyList(),
    @SerializedName("has_more")
    val hasMore: Boolean = false,
    /** 翻页游标（数字字符串形态），与 feed/video 的 offset 同构；0=无更多或未下发 */
    @SerializedName("offset")
    val offset: Long = 0
)

data class UpDynamicFeedItem(
    /** 动态类型（DYNAMIC_TYPE_AV=视频动态；DRAW/WORD/FORWARD 等不渲染），仅日志用 */
    val type: String = "",
    val modules: List<UpDynamicFeedModule> = emptyList()
)

data class UpDynamicFeedModule(
    @SerializedName("module_type")
    val moduleType: String = "",
    @SerializedName("module_dynamic")
    val moduleDynamic: UpDynamicModuleDynamic? = null,
    @SerializedName("module_author")
    val moduleAuthor: UpDynamicModuleAuthor? = null
)

data class UpDynamicModuleDynamic(
    /** desktop 端点的稿件信息键名（旧版 polymer 是 major.archive） */
    @SerializedName("dyn_archive")
    val dynArchive: UpDynamicArchive? = null,
    /** 旧版 polymer / 其它动态类型（如 DYNAMIC_TYPE_COMMON_VERTICAL）的稿件挂载点 */
    @SerializedName("major")
    val major: UpDynamicMajor? = null
)

data class UpDynamicMajor(
    @SerializedName("archive")
    val archive: UpDynamicArchive? = null
)

data class UpDynamicModuleAuthor(
    /** 发布时间戳（秒） */
    @SerializedName("pub_ts")
    val pubTs: Long = 0,
    val user: UpDynamicFeedUser? = null
)

data class UpDynamicFeedUser(
    val mid: Long = 0,
    val name: String = "",
    val face: String = ""
)

data class UpDynamicArchive(
    /** B 站下发为字符串形态的数字，Gson lenient 解析可直转 Long */
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    /** "00:46"/"01:02:03" 文本时长，映射层解析成秒（VideoModel.duration 无 JSON 键可用） */
    @SerializedName("duration_text")
    val durationText: String = "",
    val badge: UpDynamicFeedBadge? = null,
    /** 播放/弹幕数为「13.3万」格式化文本，映射层还原成近似数值（展示层再缩写回同形态） */
    val stat: UpDynamicFeedStat? = null
)

data class UpDynamicFeedBadge(
    val text: String = ""
)

data class UpDynamicFeedStat(
    val play: String = "",
    val danmaku: String = ""
)

/**
 * 视频类动态 → 卡片模型；非视频动态（图文/转发/文字）返回 null 由调用方过滤。
 *
 * 判据刻意不绑定 type 字面量：动态体系里视频动态可能下发 DYNAMIC_TYPE_AV，也可能是
 * 新版竖屏/通用视频类型（DYNAMIC_TYPE_COMMON_VERTICAL 等），只要 module_dynamic 里
 * 能取到稿件信息（dyn_archive 或 legacy major.archive）且有 bvid 即视为视频动态。
 * 这样动态视频改版换类型时页签不会整体变空。
 *
 * 注意本函数**不判断是否为动态视频**：2026-11-27 用真样本（UP「亿点点不一样」
 * BV171e76NE2E/BV1aza76HERk/BV1ffpY6nEZR）实测，动态视频条目的 badge.text 同样
 * 是「投稿视频」、pub_text 同样「投稿了视频」、条目结构与普通投稿完全一致，入口
 * 字段无任何区分标记。唯一可靠判据是「不在该 UP 投稿列表里」或 view 接口
 * is_story=true，故动态视频判定由调用方（[com.mytvb.model.video.DynamicVideoClassifier]）
 * 结合投稿列表集合完成。
 */
fun UpDynamicFeedItem.toVideoModel(): VideoModel? {
    val dynamicModule = modules.firstOrNull { it.moduleDynamic != null }?.moduleDynamic ?: return null
    val archive = dynamicModule.dynArchive ?: dynamicModule.major?.archive ?: return null
    if (archive.bvid.isBlank()) return null
    val author = modules.firstOrNull { it.moduleAuthor != null }?.moduleAuthor
    val user = author?.user
    return VideoModel(
        aid = archive.aid,
        bvid = archive.bvid,
        title = archive.title,
        pic = archive.cover,
        duration = archive.durationText.parseDurationTextSeconds(),
        pubDate = author?.pubTs ?: 0L,
        owner = user?.let {
            Owner(mid = it.mid, name = it.name, face = it.face)
        },
        stat = archive.stat?.let {
            Stat(
                view = it.play.parseFormattedCount(),
                danmaku = it.danmaku.parseFormattedCount()
            )
        }
    )
}


/** "00:46"→46、"01:02:03"→3723；解析失败返回 0（时长显示层对 0 有兜底）。 */
private fun String.parseDurationTextSeconds(): Long {
    val parts = split(':').map { it.trim().toLongOrNull() ?: 0L }
    if (parts.any { it < 0L } || parts.size > 3) return 0L
    var seconds = 0L
    for (part in parts) {
        seconds = seconds * 60 + part
    }
    return seconds
}

/** "369"→369、"1.5万"→15000、"1.2亿"→120000000；解析失败返回 0（显示层对 0 有兜底）。 */
private fun String.parseFormattedCount(): Long {
    val text = trim()
    if (text.isEmpty()) return 0L
    val unitMultiplier = when (text.last()) {
        '万' -> 10_000L
        '亿' -> 100_000_000L
        else -> 1L
    }
    val numeric = if (unitMultiplier > 1L) text.dropLast(1) else text
    val value = numeric.toDoubleOrNull() ?: return 0L
    return (value * unitMultiplier).toLong()
}
