package com.mytvb.feature.player

import com.mytvb.model.episode.EpisodeModel
import com.mytvb.model.series.EpisodesDetailModel
import com.mytvb.model.series.SectionModel
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.VideoPvModel
import com.mytvb.model.video.detail.UgcEpisode
import com.mytvb.model.video.detail.UgcSeason
import com.mytvb.model.video.detail.UgcSection
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.model.video.detail.VideoView
import com.mytvb.network.api.ApiService
import android.content.Context
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerEpisodeCatalogBuilderTest {

    private val builder = VideoPlayerEpisodeCatalogBuilder(unusedApiService(), unusedContext())

    @Test
    fun ugcSeasonMergesAndSortsSplitPagesFromSameArchive() = runBlocking {
        val detail = VideoDetailModel(
            view = VideoView(
                aid = 1L,
                bvid = "BVcurrent",
                pic = "cover",
                ugcSeason = UgcSeason(
                    sections = listOf(
                        UgcSection(
                            episodes = listOf(
                                ugcEpisode(
                                    aid = 100L,
                                    bvid = "BV100",
                                    cid = 1002L,
                                    page = 2,
                                    part = "P2 title",
                                    archiveTitle = "Archive A"
                                ),
                                ugcEpisode(
                                    aid = 100L,
                                    bvid = "BV100",
                                    cid = 1001L,
                                    page = 1,
                                    part = "P1 title",
                                    archiveTitle = "Archive A"
                                ),
                                ugcEpisode(
                                    aid = 200L,
                                    bvid = "BV200",
                                    cid = 2001L,
                                    page = 1,
                                    part = "Only page",
                                    archiveTitle = "Archive B"
                                )
                            )
                        )
                    )
                )
            )
        )

        val episodes = builder.buildUgcEpisodes(detail)

        assertEquals(3, episodes.size)
        assertEquals(1001L, episodes[0].cid)
        assertEquals("Archive A · P1", episodes[0].title)
        assertEquals("Archive A · P1", episodes[0].panelTitle)
        assertEquals("共 2 P", episodes[0].subtitle)
        assertEquals(1002L, episodes[1].cid)
        assertEquals("Archive A · P2", episodes[1].title)
        assertEquals(2001L, episodes[2].cid)
        assertEquals("Archive B", episodes[2].title)
    }

    @Test
    fun pgcCatalogAppendsSectionEntriesAfterMainEpisodes() = runBlocking {
        val detail = EpisodesDetailModel(
            seasonId = 28747L,
            episodes = listOf(
                EpisodeModel(id = 733316L, aid = 1L, bvid = "BV1", cid = 1001L, title = "1", longTitle = "第一集"),
                EpisodeModel(id = 733317L, aid = 2L, bvid = "BV2", cid = 1002L, title = "2", longTitle = "第二集")
            ),
            section = listOf(
                SectionModel(
                    title = "特别花絮",
                    type = 2,
                    episodes = listOf(
                        // 真实形态：section 接口条目没有 ep_id/bvid，只有 cid+aid+share_url
                        VideoModel(
                            aid = 11L,
                            cid = 43705764573L,
                            title = "2026年发布会PV",
                            redirectUrl = "https://www.bilibili.com/bangumi/play/ep8012711"
                        ),
                        // 「UP论道」类纯引用条目：cid=0，进目录也无法播放，应被过滤
                        VideoModel(aid = 12L, epid = 215048L, title = "UP论道条目")
                    )
                ),
                SectionModel(
                    title = "预告",
                    type = 1,
                    episodes = listOf(
                        // 与正片重复的 cid 应去重（正片行末已补下一集预告）
                        VideoModel(aid = 13L, cid = 1001L, epid = 999L, title = "重复预告")
                    )
                )
            )
        )

        val episodes = builder.buildPgcEpisodes(detail)

        assertEquals(3, episodes.size)
        assertEquals(8012711L, episodes[2].epId)
        assertEquals(43705764573L, episodes[2].cid)
        assertEquals("2026年发布会PV", episodes[2].title)
        assertEquals("特别花絮", episodes[2].subtitle)
        assertEquals(28747L, episodes[2].seasonId)

        // 点击花絮入口的 epId 必须能命中目录，否则被回退成正片第 1 集（issue#135）
        val selectedIndex = builder.resolvePgcEpisodeIndex(
            episodes = episodes,
            targetEpId = 8012711L,
            targetCid = 0L,
            targetBvid = null,
            fallbackIndex = -1
        )
        assertEquals(2, selectedIndex)
    }

    @Test
    fun pgcCatalogSectionOnlyWhenMainEmpty() = runBlocking {
        val detail = EpisodesDetailModel(
            seasonId = 9L,
            episodes = emptyList(),
            section = listOf(
                SectionModel(
                    title = "花絮",
                    episodes = listOf(VideoModel(cid = 901L, epid = 9011L, title = "pv"))
                )
            )
        )

        val episodes = builder.buildPgcEpisodes(detail)

        assertEquals(1, episodes.size)
        assertEquals(9011L, episodes[0].epId)
        assertEquals("花絮", episodes[0].subtitle)
    }

    private fun ugcEpisode(
        aid: Long,
        bvid: String,
        cid: Long,
        page: Int,
        part: String,
        archiveTitle: String
    ): UgcEpisode {
        return UgcEpisode(
            aid = aid,
            bvid = bvid,
            cid = cid,
            title = part,
            arc = VideoModel(
                aid = aid,
                bvid = bvid,
                cid = cid,
                title = archiveTitle,
                pic = "cover-$aid"
            ),
            pageInfo = VideoPvModel(
                cid = cid,
                page = page,
                part = part
            )
        )
    }

    private fun unusedApiService(): ApiService {
        return Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)
        ) { _, method, _ ->
            error("Unexpected ApiService call: ${method.name}")
        } as ApiService
    }

    /** Builder 用 context.getString 组装分 P 副标题；getString 为 final，纯 JVM 下只能 Mockito 拦截，
     *  按 player_total_p_format（"共 %1$d P"）的格式化语义回放。 */
    private fun unusedContext(): Context {
        val context = org.mockito.Mockito.mock(Context::class.java)
        org.mockito.Mockito.`when`(
            context.getString(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any<Int>()
            )
        ).thenAnswer { invocation ->
            val count = invocation.getArgument<Int>(1)
            "共 $count P"
        }
        return context
    }
}
