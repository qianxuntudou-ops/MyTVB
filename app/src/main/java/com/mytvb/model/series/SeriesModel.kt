package com.mytvb.model.series

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class SeriesModel(
    @SerializedName("season_id")
    val seasonId: Long = 0,
    @SerializedName("media_id")
    val mediaId: String = "",
    @SerializedName("title")
    val title: String = "",
    @SerializedName("cover")
    val cover: String = "",
    @SerializedName("badge")
    val badge: String = "",
    @SerializedName("badge_info")
    val badgeInfo: BadgeInfoModel? = null,
    @SerializedName("season_title")
    val seasonTitle: String = "",
    @SerializedName("evaluate")
    val evaluate: String = "",
    @SerializedName("summary")
    val summary: String = "",
    @SerializedName("url")
    val url: String = "",
    @SerializedName("progress")
    val progress: String = "",
    @SerializedName("horizontal_cover_16_9")
    val horizontalCover169: String = "",
    @SerializedName("first_ep")
    val firstEp: Long = 0,
    @SerializedName("first_ep_info")
    val firstEpInfo: AnimEpisodeInfoModel? = null,
    @SerializedName("new_ep")
    val newEp: AnimEpisodeInfoModel? = null,
    @SerializedName("season_attr")
    val seasonAttr: Long = 0,
    @SerializedName("media_attr")
    val mediaAttr: Long = 0
) : Serializable {

    /**
     * 追番/追剧卡进度对齐官方百分比形态（issue#145）：「看到第X集 12:34」末尾的
     * 当前时长换成百分比（卡上位置小，放不下「当前/总时长」）。总时长用 new_ep
     * 的 duration（接口自带字段，不额外发请求）；看到集非最新集时同番正片时长
     * 近似，整数百分比下偏差可忽略。「已看完第X集」及解析失败/无时长时返回
     * null（调用方保持服务端原文，或走各自兜底链）。
     */
    fun progressWithPercent(): String? {
        val raw = progress
        if (raw.isBlank() || raw.contains("已看完")) {
            return null
        }
        val totalMs = newEp?.duration ?: 0L
        if (totalMs <= 0L) return null
        val match = PROGRESS_PATTERN.find(raw) ?: return null
        val curSec = parseClockToSeconds(match.groupValues[2]) ?: return null
        val totalSec = totalMs / 1000L
        if (totalSec <= 0L) return null
        val percent = ((curSec * 100L + totalSec / 2L) / totalSec).toInt()
        return "${match.groupValues[1]} ${percent}%"
    }

    companion object {
        /** progress 原文（服务端下发）：「看到第201集 08:12」→ 集数前缀 + 当前时长 */
        private val PROGRESS_PATTERN = Regex("^(.*\\d[集话])\\s+(\\d{1,3}(:\\d{2}){1,2})$")

        private fun parseClockToSeconds(clock: String): Long? {
            val parts = clock.split(":").mapNotNull { it.toLongOrNull() }
            if (parts.isEmpty() || parts.any { it < 0L }) return null
            return when (parts.size) {
                3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
                2 -> parts[0] * 60L + parts[1]
                else -> null
            }
        }
    }
}
