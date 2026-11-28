package com.mytvb.model.video

/**
 * 动态视频识别（issue#151）：动态视频按 B 站规则不进 UP 主空间投稿栏，因此
 * 「出现在动态流、但不在该 UP 投稿列表里」的视频即动态视频。
 *
 * 为什么不用别的判据（2026-11-27 真样本实测结论，样本 UP「亿点点不一样」）：
 * - badge.text：动态视频条目同样下发「投稿视频」，与普通投稿无差别；
 * - module_author.pub_text：同样是「投稿了视频」；
 * - 条目结构与 basic.rtype / dyn_archive.type / open_player_params：完全一致；
 * - view 接口 is_story=true 可区分但需逐条请求，代价过高（仅作离线核对用）。
 */
object DynamicVideoClassifier {

    /** 角标文案（与 B 站 官方「动态视频」叫法一致）。 */
    const val BADGE_TEXT_DYNAMIC_VIDEO = "动态视频"

    /**
     * 标记动态视频：把 [videos] 中 bvid 不在 [archiveBvids]（该 UP 投稿列表）的条目
     * 打上「动态视频」角标文案，其余置空。
     *
     * [archiveBvids] 为空表示投稿列表没取到（接口失败/无投稿），此时**一律不判定**
     * 动态视频——宁可少标也不能把投稿视频误标成动态视频。
     */
    fun markDynamicVideos(videos: List<VideoModel>, archiveBvids: Set<String>): List<VideoModel> {
        if (archiveBvids.isEmpty()) return videos.map { it.withDynamicBadge("") }
        return videos.map { video ->
            val isDynamicVideo = video.bvid.isNotBlank() && video.bvid !in archiveBvids
            video.withDynamicBadge(if (isDynamicVideo) BADGE_TEXT_DYNAMIC_VIDEO else "")
        }
    }

    /** 只保留动态视频（页签数据源）。[archiveBvids] 为空时返回空列表（不猜）。 */
    fun filterDynamicVideos(videos: List<VideoModel>, archiveBvids: Set<String>): List<VideoModel> {
        if (archiveBvids.isEmpty()) return emptyList()
        return videos
            .filter { it.bvid.isNotBlank() && it.bvid !in archiveBvids }
            .map { it.withDynamicBadge(BADGE_TEXT_DYNAMIC_VIDEO) }
    }

    private fun VideoModel.withDynamicBadge(text: String): VideoModel =
        if (dynamicBadgeText == text) this else copy(dynamicBadgeText = text)
}
