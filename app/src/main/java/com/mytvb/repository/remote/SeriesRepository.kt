package com.mytvb.repository.remote

import com.mytvb.R
import com.mytvb.MyBLBLApplication
import com.mytvb.model.episode.EpisodeListMerge
import com.mytvb.model.series.CheckUserSeriesResult
import com.mytvb.model.series.EpisodesDetailModel
import com.mytvb.model.series.FollowSeriesResult
import com.mytvb.model.series.MyFollowingResponseWrapper
import com.mytvb.model.series.RelatedRecommendResult
import com.mytvb.model.series.timeline.TimeLineADayModel
import com.mytvb.network.api.ApiService
import com.mytvb.network.security.NetworkSecurityGateway
import com.mytvb.network.session.NetworkSessionGateway

class SeriesRepository(
    private val apiService: ApiService,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway
) {

    suspend fun getSeriesDetail(seasonId: Long, epId: Long = 0): Result<EpisodesDetailModel> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val response = sessionGateway.retryOnRiskControl(
                key = "series_detail_$seasonId",
                source = "series.getSeriesDetail",
                getCode = { it.code },
                getMessage = { it.message },
                getIsSuccess = { it.isSuccess }
            ) {
                apiService.getVideoEpisodes(
                    if (seasonId > 0) seasonId else null,
                    if (epId > 0) epId else null
                ).let {
                    sessionGateway.syncAuthState(it, source = "series.getSeriesDetail")
                }
            }
            val detail = response.result
            if (response.isSuccess && detail != null) {
                val resolvedSeasonId = detail.seasonId.takeIf { it > 0 } ?: seasonId
                val sectionResult = if (resolvedSeasonId > 0) {
                    apiService.getVideoEpisodeSections(resolvedSeasonId)
                        .let { sessionGateway.syncAuthState(it, source = "series.getVideoEpisodeSections") }
                        .result
                } else {
                    null
                }
                val mergedDetail = detail.copy(
                    // 分区接口的正片不含「下一集预告」。连载中官方会把这一集预告
                    // 接在最新正片后面（看剧进度也经常停在这集），episodes 只留它；
                    // 已完结的历史预告仍走下面的预告分区，不混进正片行。
                    episodes = EpisodeListMerge.mergeOngoingNextPreview(
                        mainEpisodes = sectionResult?.mainSection?.episodes.orEmpty(),
                        seasonEpisodes = detail.episodes.orEmpty(),
                        isFinished = detail.publish?.isFinish == 1
                    ),
                    section = mergeSectionEpIds(detail.section.orEmpty(), sectionResult?.section.orEmpty()),
                    mainSectionTitle = sectionResult?.mainSection?.title.orEmpty()
                )
                mergedDetail
            } else {
                throw IllegalStateException(response.errorMessage)
            }
        }
    }

    /**
     * 单独 section 接口（pgc/web/season/section）不下发 ep_id（issue#135 实证），
     * 一体接口（pgc/view/web/season）自带的 section 带 ep_id——按 aid/cid 交叉
     * 回填给 section 接口条目。没有 ep id 详情页就无法用 progress.last_ep_id
     * 匹配花絮/预告：定位高亮、「上次看到」文案、继续观看全失效（issue#140）。
     * 一体接口没有的条目（如预约栏目）保持 epid=0，走既有 UGC/站外引用分支。
     */
    private fun mergeSectionEpIds(
        seasonSections: List<com.mytvb.model.series.SectionModel>,
        apiSections: List<com.mytvb.model.series.SectionModel>
    ): List<com.mytvb.model.series.SectionModel> {
        if (apiSections.isEmpty()) return apiSections
        val epIdByKey = buildMap {
            seasonSections.forEach { sec ->
                sec.episodes.forEach { video ->
                    if (video.epid > 0L) {
                        if (video.aid > 0L) put("a${video.aid}", video.epid)
                        if (video.cid > 0L) put("c${video.cid}", video.epid)
                    }
                }
            }
        }
        if (epIdByKey.isEmpty()) return apiSections
        return apiSections.map { sec ->
            sec.copy(episodes = sec.episodes.map { video ->
                if (video.epid > 0L) return@map video
                val epId = epIdByKey["a${video.aid}"] ?: epIdByKey["c${video.cid}"] ?: 0L
                if (epId > 0L) video.copy(epid = epId) else video
            })
        }
    }

    suspend fun checkUserFollowStatus(seasonId: Long, epId: Long = 0): Result<CheckUserSeriesResult> {
        return runCatching {
            val response = sessionGateway.retryOnRiskControl(
                key = "series_status_$seasonId",
                source = "series.checkUserFollowStatus",
                getCode = { it.code },
                getMessage = { it.message },
                getIsSuccess = { it.isSuccess }
            ) {
                apiService.getSeriesUserStatus(
                    if (seasonId > 0) seasonId else null,
                    if (epId > 0) epId else null
                ).let {
                    sessionGateway.syncAuthState(it, source = "series.checkUserFollowStatus")
                }
            }
            val result = response.result
            if (response.isSuccess && result != null) {
                result
            } else {
                throw IllegalStateException(response.errorMessage.ifBlank { MyBLBLApplication.instance.getString(R.string.series_error_follow_status_failed) })
            }
        }
    }

    suspend fun followSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: throw IllegalStateException("csrf token is blank")
            var response = apiService.followSeries(seasonId, csrf)
            if (response.code == -101 && sessionGateway.tryRecoverExpiredSession()) {
                val newCsrf = sessionGateway.requireCsrfToken()
                if (!newCsrf.isNullOrBlank()) {
                    response = apiService.followSeries(seasonId, newCsrf)
                }
            }
            val finalResponse = sessionGateway.syncAuthState(
                response,
                source = "series.followSeries"
            )
            if (finalResponse.isSuccess) {
                FollowSeriesResult(
                    relation = true,
                    status = 1,
                    toast = ""
                )
            } else {
                throw IllegalStateException(finalResponse.errorMessage.ifBlank { MyBLBLApplication.instance.getString(R.string.series_error_follow_failed) })
            }
        }
    }

    suspend fun cancelFollowSeries(seasonId: Long): Result<FollowSeriesResult> {
        return runCatching {
            securityGateway.ensureHealthyForPlay()
            val csrf = sessionGateway.requireCsrfToken()
                ?: throw IllegalStateException("csrf token is blank")
            var response = apiService.cancelFollowSeries(seasonId, csrf)
            if (response.code == -101 && sessionGateway.tryRecoverExpiredSession()) {
                val newCsrf = sessionGateway.requireCsrfToken()
                if (!newCsrf.isNullOrBlank()) {
                    response = apiService.cancelFollowSeries(seasonId, newCsrf)
                }
            }
            val finalResponse = sessionGateway.syncAuthState(
                response,
                source = "series.cancelFollowSeries"
            )
            if (finalResponse.isSuccess) {
                FollowSeriesResult(
                    relation = false,
                    status = 0,
                    toast = ""
                )
            } else {
                throw IllegalStateException(finalResponse.errorMessage.ifBlank { MyBLBLApplication.instance.getString(R.string.series_error_unfollow_failed) })
            }
        }
    }

    suspend fun getMyFollowingSeries(
        type: Int,
        page: Int,
        pageSize: Int,
        vmid: Long
    ): Result<MyFollowingResponseWrapper> {
        return runCatching {
            val response = sessionGateway.executeWithRiskControlRetry(
                key = "series_following_${type}_$vmid",
                source = "series.getMyFollowingSeries"
            ) {
                apiService.getMyFollowingSeries(
                    type = type,
                    page = page,
                    pageSize = pageSize,
                    vmid = vmid,
                    ts = System.currentTimeMillis()
                ).let {
                    sessionGateway.syncAuthState(it, source = "series.getMyFollowingSeries")
                }
            }
            if (response.code == 0 && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.message.ifEmpty { response.msg })
            }
        }
    }

    suspend fun getSeriesTimeline(
        type: Int,
        before: Int = 6,
        after: Int = 6
    ): Result<List<TimeLineADayModel>> {
        return runCatching {
            val response = apiService.getSeriesTimeLine(
                type = type,
                before = before,
                after = after
            )
            if (response.code == 0) {
                response.result.map { day ->
                    day.copy(
                        episodes = day.episodes.map { episode ->
                            episode.copy(dayOfWeek = day.dayOfWeek)
                        }
                    )
                }
            } else {
                throw IllegalStateException(response.message.ifEmpty { MyBLBLApplication.instance.getString(R.string.series_error_timeline_failed) })
            }
        }
    }

    suspend fun getRelatedRecommend(seasonId: Long): Result<RelatedRecommendResult> {
        return runCatching {
            val response = apiService.getRelatedRecommend(seasonId)
            if (response.isSuccess && response.data != null) {
                response.data
            } else {
                throw IllegalStateException(response.errorMessage.ifEmpty { MyBLBLApplication.instance.getString(R.string.series_error_recommend_failed) })
            }
        }
    }
}
