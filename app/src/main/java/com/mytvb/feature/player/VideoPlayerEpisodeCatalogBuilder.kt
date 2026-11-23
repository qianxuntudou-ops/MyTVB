package com.mytvb.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.mytvb.R
import com.mytvb.model.episode.EpisodeModel
import com.mytvb.model.episode.EpisodeStatModel
import com.mytvb.model.series.EpisodesDetailModel
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.VideoPvModel
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.model.video.detail.UgcEpisode
import com.mytvb.network.api.ApiService

/**
 * Converts UGC / PGC detail payloads into the episode catalog shape consumed by the player UI.
 */
@OptIn(UnstableApi::class)
class VideoPlayerEpisodeCatalogBuilder(
    private val apiService: ApiService,
    private val context: Context
) {

    suspend fun buildUgcEpisodes(
        detail: VideoDetailModel
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        val view = detail.view ?: return emptyList()

        // Check ugcSeason (合集) FIRST: if the video belongs to a collection,
        // show all videos in the collection rather than just the current video's pages.
        // 展开到「分P」粒度：一个稿件若含多P，则每个P各自成为列表中的一项，
        // 这样合集列表能看到 P1/P2/P3，自动播放也能按 P 顺序连播而不是跳到下一个子合集。
        val ugcEpisodes = view.ugcSeason?.sections
            .orEmpty()
            .flatMap { it.episodes.orEmpty() }
            .expandArchivePages(
                currentArchiveTitle = view.title.takeIf { it.isNotBlank() },
                currentBvid = view.bvid
            )

        if (ugcEpisodes.isNotEmpty()) {
            return ugcEpisodes
        }

        // Fallback: show individual pages (分P) of the current video
        val pages = view.pages.orEmpty().ifEmpty {
            runCatching { apiService.getVideoPv(view.aid, view.bvid) }
                .getOrNull()
                ?.data
                .orEmpty()
        }

        if (pages.size <= 1) {
            return emptyList()
        }

        return pages.mapIndexed { index, page ->
            page.toPlayableEpisode(index, view)
        }
    }

    fun buildPgcEpisodes(
        detail: EpisodesDetailModel
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        val seasonId = detail.seasonId
        val mainEpisodes = detail.episodes.orEmpty().mapIndexed { index, episode ->
            episode.toPlayableEpisode(index, detail.cover, seasonId)
        }
        if (mainEpisodes.isEmpty()) {
            return detail.section.orEmpty()
                .flatMap { section ->
                    section.episodes.mapIndexed { index, episode ->
                        episode.toSectionPlayableEpisode(
                            index = index,
                            sectionTitle = section.title,
                            fallbackCover = detail.cover,
                            fallbackSeasonId = seasonId
                        )
                    }
                }
        }
        // 花絮/PV/预告等 section 条目并入选集目录：入口点击的 epId 才能在
        // resolvePgcEpisodeIndex 里命中，否则被回退成正片第 1 集（issue#135
        // 「番剧的花絮看不了」——点花絮实际播正片）。连载中正片行末已由
        // EpisodeListMerge 补「下一集预告」，按 cid 去重避免重复条目。
        val mainCids = mainEpisodes.mapTo(mutableSetOf()) { it.cid }
        return mainEpisodes + detail.section.orEmpty()
            .flatMap { section ->
                section.episodes.mapIndexed { index, episode ->
                    episode.toSectionPlayableEpisode(
                        index = index,
                        sectionTitle = section.title,
                        fallbackCover = detail.cover,
                        fallbackSeasonId = seasonId
                    )
                }
            }
            .filter { it.cid > 0L && mainCids.add(it.cid) }
    }

    private fun VideoModel.toSectionPlayableEpisode(
        index: Int,
        sectionTitle: String,
        fallbackCover: String,
        fallbackSeasonId: Long
    ): VideoPlayerViewModel.PlayableEpisode {
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = title.ifBlank { "EP${index + 1}" },
            panelTitle = title.ifBlank { "EP${index + 1}" },
            subtitle = sectionTitle.ifBlank { desc.ifBlank { typeName } },
            cover = coverUrl.ifBlank { fallbackCover },
            aid = aid,
            bvid = bvid,
            // section 接口条目没有 ep_id 字段，epid 恒 0；share_url(/bangumi/play/epXX)
            // 兜底解析出的 playbackEpId 才是真实集 id（心跳/目录命中都依赖它）。
            epId = epid.takeIf { it > 0L } ?: playbackEpId,
            seasonId = sid.takeIf { it > 0L } ?: fallbackSeasonId,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES
        )
    }

    fun resolvePgcEpisodeIndex(
        episodes: List<VideoPlayerViewModel.PlayableEpisode>,
        targetEpId: Long?,
        targetCid: Long,
        targetBvid: String?,
        fallbackIndex: Int
    ): Int {
        if (episodes.isEmpty()) {
            return 0
        }
        return episodes.indexOfFirst { episode ->
            (targetEpId != null && targetEpId > 0L && episode.epId == targetEpId) ||
                (targetCid > 0L && episode.cid == targetCid) ||
                (!targetBvid.isNullOrBlank() && episode.bvid == targetBvid)
        }.takeIf { it >= 0 }
            ?: fallbackIndex.takeIf { it in episodes.indices }
            ?: 0
    }

    fun buildPgcVideoDetail(
        detail: EpisodesDetailModel,
        selectedEpisode: VideoPlayerViewModel.PlayableEpisode?,
        fallbackAid: Long,
        fallbackBvid: String,
        fallbackCid: Long
    ): VideoDetailModel {
        return VideoDetailModel(
            view = com.mytvb.model.video.detail.VideoView(
                aid = selectedEpisode?.aid ?: fallbackAid,
                bvid = selectedEpisode?.bvid ?: fallbackBvid,
                cid = selectedEpisode?.cid ?: fallbackCid,
                pic = detail.cover.ifBlank { selectedEpisode?.cover.orEmpty() },
                title = detail.title.ifBlank {
                    detail.seasonTitle.ifBlank { selectedEpisode?.title.orEmpty() }
                },
                desc = detail.evaluate.ifBlank { detail.subtitle },
                pubDate = detail.episodes.orEmpty()
                    .firstOrNull { it.id == selectedEpisode?.epId }
                    ?.pubTime
                    ?: 0L,
                stat = detail.stat.toVideoStat()
            )
        )
    }

    private fun VideoPvModel.toPlayableEpisode(
        index: Int,
        view: com.mytvb.model.video.detail.VideoView
    ): VideoPlayerViewModel.PlayableEpisode {
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            panelTitle = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            subtitle = context.getString(R.string.player_page_p_format, page.takeIf { it > 0 } ?: index + 1),
            cover = view.pic,
            aid = view.aid,
            bvid = view.bvid,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PAGES
        )
    }

    private fun List<UgcEpisode>.expandArchivePages(
        currentArchiveTitle: String?,
        currentBvid: String
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        if (isEmpty()) return emptyList()
        val archiveGroups = LinkedHashMap<String, MutableList<UgcEpisode>>()
        forEachIndexed { index, episode ->
            archiveGroups.getOrPut(episode.archiveKey(index)) { ArrayList() }.add(episode)
        }
        return archiveGroups.values.flatMap { it.toPlayablePages(currentArchiveTitle, currentBvid) }
    }

    private fun UgcEpisode.archiveKey(index: Int): String =
        when {
            displayBvid.isNotBlank() -> "bvid:$displayBvid"
            displayAid > 0L -> "aid:$displayAid"
            else -> "episode:$index"
        }

    // 合集接口里的标题可能是投稿工具未替换的模板串（含 "{{title}}"、"{{yyyy}}" 等占位符），视为不可用。
    private fun cleanArchiveTitle(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() && !it.contains("{{") }

    private fun List<UgcEpisode>.toPlayablePages(
        currentArchiveTitle: String?,
        currentBvid: String
    ): List<VideoPlayerViewModel.PlayableEpisode> {
        val representative = first()
        val pagesByKey = LinkedHashMap<String, VideoPvModel>()
        var fallbackPageIndex = 0
        for (episode in this) {
            val pages = episode.pages.orEmpty().ifEmpty {
                episode.pageInfo?.let(::listOf).orEmpty()
            }
            for (page in pages) {
                val key = when {
                    page.page > 0 -> "page:${page.page}"
                    page.cid > 0L -> "cid:${page.cid}"
                    !page.part.isNullOrBlank() -> "part:${page.part.trim()}"
                    else -> "fallback:${fallbackPageIndex++}"
                }
                val existing = pagesByKey[key]
                if (existing == null || existing.cid <= 0L && page.cid > 0L) {
                    pagesByKey[key] = page
                }
            }
        }
        val candidatePages = pagesByKey.values.sortedWith(
            compareBy<VideoPvModel> { it.page.takeIf { page -> page > 0 } ?: Int.MAX_VALUE }
                .thenBy { it.cid.takeIf { cid -> cid > 0L } ?: Long.MAX_VALUE }
        )
        val archiveAid = firstOrNull { it.displayAid > 0L }?.displayAid ?: 0L
        val archiveBvid = firstOrNull { it.displayBvid.isNotBlank() }?.displayBvid.orEmpty()
        // 当前稿件优先用详情页 View.title 校正（恒为渲染后的成品标题），其余候选过滤占位符模板。
        val archiveTitle = currentArchiveTitle
            ?.takeIf { currentBvid.isNotBlank() && archiveBvid == currentBvid }
            ?: asSequence().mapNotNull { it.arc?.title?.let(::cleanArchiveTitle) }.firstOrNull()
            ?: asSequence().mapNotNull { cleanArchiveTitle(it.title) }.firstOrNull()
            ?: candidatePages.firstOrNull()?.part?.let(::cleanArchiveTitle)
        val archiveCover = firstOrNull { it.displayCover.isNotBlank() }?.displayCover.orEmpty()
        val archiveCid = firstOrNull { it.displayCid > 0L }?.displayCid ?: representative.displayCid
        val archivePubDate = asSequence()
            .mapNotNull { it.arc?.pubDate?.takeIf { date -> date > 0L } }
            .firstOrNull()
            ?: 0L
        val archivePlayCount = asSequence()
            .mapNotNull { it.arc?.viewCount?.takeIf { count -> count > 0L } }
            .firstOrNull()
            ?: 0L
        val archiveDanmakuCount = asSequence()
            .mapNotNull { it.arc?.danmakuCount?.takeIf { count -> count > 0L } }
            .firstOrNull()
            ?: 0L
        val archiveDuration = firstOrNull { it.displayDuration > 0L }?.displayDuration ?: 0L

        // 单P（或拿不到分P信息）：维持"一个稿件一项"的形态，标题用稿件名。
        if (candidatePages.size <= 1) {
            val page = candidatePages.firstOrNull()
            val cid = page?.cid?.takeIf { it > 0L } ?: archiveCid
            val fallbackTitle = archiveTitle ?: "P1"
            return listOf(
                VideoPlayerViewModel.PlayableEpisode(
                    cid = cid,
                    title = fallbackTitle,
                    panelTitle = fallbackTitle,
                    subtitle = "",
                    cover = archiveCover,
                    aid = archiveAid,
                    bvid = archiveBvid,
                    pubDate = archivePubDate,
                    playCount = archivePlayCount,
                    danmakuCount = archiveDanmakuCount,
                    duration = archiveDuration,
                    source = VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON
                )
            )
        }

        // 多P：每个分P一项，标题带上 P 编号，方便在合集列表里辨认。
        return candidatePages.mapIndexed { index, page ->
            val pageNo = page.page.takeIf { it > 0 } ?: (index + 1)
            val partTitle = page.part?.takeIf { it.isNotBlank() }
            val baseName = archiveTitle ?: partTitle ?: "P$pageNo"
            val displayTitle = when {
                // 分P标题已自带"P"前缀（如"P2 xxx"）就不重复加
                partTitle != null && baseName == partTitle -> "$baseName"
                else -> "$baseName · P$pageNo"
            }
            VideoPlayerViewModel.PlayableEpisode(
                cid = page.cid.takeIf { it > 0L } ?: archiveCid,
                title = displayTitle,
                panelTitle = displayTitle,
                subtitle = context.getString(R.string.player_total_p_format, candidatePages.size),
                cover = archiveCover,
                aid = archiveAid,
                bvid = archiveBvid,
                pubDate = archivePubDate,
                playCount = archivePlayCount,
                danmakuCount = archiveDanmakuCount,
                duration = page.duration.takeIf { it > 0L } ?: archiveDuration,
                source = VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON
            )
        }
    }

    private fun EpisodeModel.toPlayableEpisode(
        index: Int,
        seasonCover: String,
        seasonId: Long
    ): VideoPlayerViewModel.PlayableEpisode {
        val displayTitle = longTitle.takeIf { it.isNotBlank() }
            ?: title.takeIf { it.isNotBlank() }
            ?: "EP${index + 1}"
        val subTitle = buildList {
            title.takeIf { it.isNotBlank() && it != displayTitle }?.let(::add)
            subtitle.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = displayTitle,
            panelTitle = buildString {
                append(index + 1)
                append('、')
                val badgeText = badgeInfo?.text?.takeIf { it.isNotBlank() } ?: badge
                if (badgeText.isNotBlank()) {
                    append('(')
                    append(badgeText)
                    append(')')
                }
                append(displayTitle)
            },
            subtitle = subTitle,
            cover = cover.ifBlank { seasonCover },
            aid = aid,
            bvid = bvid,
            epId = id,
            seasonId = seasonId,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES
        )
    }

    private fun EpisodeStatModel?.toVideoStat(): com.mytvb.model.video.Stat? {
        val value = this ?: return null
        return com.mytvb.model.video.Stat(
            view = value.view.takeIf { it > 0L } ?: value.play,
            danmaku = value.danmaku,
            reply = value.reply,
            share = value.share,
            coin = value.coin
        )
    }
}
