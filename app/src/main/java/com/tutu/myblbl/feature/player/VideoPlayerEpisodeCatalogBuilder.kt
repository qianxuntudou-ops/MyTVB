package com.tutu.myblbl.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.tutu.myblbl.model.episode.EpisodeModel
import com.tutu.myblbl.model.episode.EpisodeStatModel
import com.tutu.myblbl.model.series.EpisodesDetailModel
import com.tutu.myblbl.model.video.VideoPvModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.model.video.detail.UgcEpisode
import com.tutu.myblbl.network.api.ApiService

/**
 * Converts UGC / PGC detail payloads into the episode catalog shape consumed by the player UI.
 */
@OptIn(UnstableApi::class)
class VideoPlayerEpisodeCatalogBuilder(
    private val apiService: ApiService
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
            .expandArchivePages()

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
        if (mainEpisodes.isNotEmpty()) {
            return mainEpisodes
        }
        return detail.section.orEmpty()
            .flatMap { section -> section.episodes }
            .mapIndexed { index, episode ->
                VideoPlayerViewModel.PlayableEpisode(
                    cid = episode.cid,
                    title = episode.title.ifBlank { "EP${index + 1}" },
                    panelTitle = episode.title.ifBlank { "EP${index + 1}" },
                    subtitle = episode.desc.ifBlank { episode.typeName },
                    cover = episode.coverUrl.ifBlank { detail.cover },
                    aid = episode.aid,
                    bvid = episode.bvid,
                    epId = episode.epid,
                    seasonId = episode.sid.takeIf { it > 0L } ?: seasonId,
                    source = VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES
                )
            }
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
            view = com.tutu.myblbl.model.video.detail.VideoView(
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
        view: com.tutu.myblbl.model.video.detail.VideoView
    ): VideoPlayerViewModel.PlayableEpisode {
        return VideoPlayerViewModel.PlayableEpisode(
            cid = cid,
            title = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            panelTitle = part.takeIf { it.isNotBlank() } ?: "P${page.takeIf { it > 0 } ?: index + 1}",
            subtitle = "第 ${page.takeIf { it > 0 } ?: index + 1} P",
            cover = view.pic,
            aid = view.aid,
            bvid = view.bvid,
            source = VideoPlayerViewModel.EpisodeCatalogSource.PAGES
        )
    }

    private fun List<UgcEpisode>.expandArchivePages(): List<VideoPlayerViewModel.PlayableEpisode> {
        if (isEmpty()) return emptyList()
        val archiveGroups = LinkedHashMap<String, MutableList<UgcEpisode>>()
        forEachIndexed { index, episode ->
            archiveGroups.getOrPut(episode.archiveKey(index)) { ArrayList() }.add(episode)
        }
        return archiveGroups.values.flatMap { it.toPlayablePages() }
    }

    private fun UgcEpisode.archiveKey(index: Int): String =
        when {
            displayBvid.isNotBlank() -> "bvid:$displayBvid"
            displayAid > 0L -> "aid:$displayAid"
            else -> "episode:$index"
        }

    private fun List<UgcEpisode>.toPlayablePages(): List<VideoPlayerViewModel.PlayableEpisode> {
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
        val archiveTitle = asSequence()
            .mapNotNull { it.arc?.title?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: asSequence().mapNotNull { it.title.takeIf(String::isNotBlank) }.firstOrNull()
            ?: candidatePages.firstOrNull()?.part?.takeIf { it.isNotBlank() }
        val archiveAid = firstOrNull { it.displayAid > 0L }?.displayAid ?: 0L
        val archiveBvid = firstOrNull { it.displayBvid.isNotBlank() }?.displayBvid.orEmpty()
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
                subtitle = "共 ${candidatePages.size} P",
                cover = archiveCover,
                aid = archiveAid,
                bvid = archiveBvid,
                pubDate = archivePubDate,
                playCount = archivePlayCount,
                danmakuCount = archiveDanmakuCount,
                duration = archiveDuration,
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

    private fun EpisodeStatModel?.toVideoStat(): com.tutu.myblbl.model.video.Stat? {
        val value = this ?: return null
        return com.tutu.myblbl.model.video.Stat(
            view = value.view.takeIf { it > 0L } ?: value.play,
            danmaku = value.danmaku,
            reply = value.reply,
            share = value.share,
            coin = value.coin
        )
    }
}
