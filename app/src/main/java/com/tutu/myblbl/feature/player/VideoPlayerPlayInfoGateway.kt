package com.tutu.myblbl.feature.player

import com.tutu.myblbl.core.common.format.MediaFormatUtils
import com.tutu.myblbl.model.player.PgcV2Result
import com.tutu.myblbl.model.player.PlayInfoModel
import com.tutu.myblbl.model.proto.DmProtoParser
import com.tutu.myblbl.model.player.VideoSnapshotData
import com.tutu.myblbl.network.WbiGenerator
import com.tutu.myblbl.network.api.ApiService
import com.tutu.myblbl.network.NetworkManager

import com.tutu.myblbl.network.security.NetworkSecurityGateway
import com.tutu.myblbl.network.session.AuthContext
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.network.response.Base2Response
import com.tutu.myblbl.network.response.PlayerInfoDataWrapper
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.cookie.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request

/** 可疑段预热的 UA 强制刷新最小间隔。 */
private const val SUSPICIOUS_UA_REFRESH_MIN_INTERVAL_MS = 60_000L

class VideoPlayerPlayInfoGateway(
    private val apiService: ApiService,
    private val noCookieApiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val cookieManager: CookieManager,
    private val sessionGateway: NetworkSessionGateway,
    private val securityGateway: NetworkSecurityGateway,
    private val logTag: String
) {

    private data class DanmakuSegmentProbe(
        val bytes: ByteArray,
        val elemCount: Int,
        val state: Int
    )

    private data class DanmakuSegmentExpectation(
        val expectedCount: Int,
        val minimumUsefulCount: Int
    )

    /**
     * 可疑段触发的 forceUaRefresh 预热节流时间点。真空段（该 6 分钟本来就没弹幕，
     * 1..64 字节）会被误判可疑，若上层持续重试同一空段，这里会以调用频率高频刷新 UA——
     * 高频 UA 抖动正是 B 站风控的触发特征，会把局部空段放大成全会话弹幕断供。
     * 任何路径的可疑预热，UA 强制刷新至少间隔 [SUSPICIOUS_UA_REFRESH_MIN_INTERVAL_MS]。
     */
    private var lastSuspiciousUaRefreshAtMs: Long = 0L

    data class PlayInfoResult(
        val code: Int,
        val message: String,
        val data: PlayInfoModel?,
        val isTryLookBypass: Boolean = false,
        val vVoucher: String = ""
    ) {
        val isSuccess: Boolean
            get() = code == 0 && data != null

        val hasVVoucher: Boolean
            get() = vVoucher.isNotBlank()
    }

    suspend fun requestPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long?,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        allowWbi: Boolean = true,
        seasonId: Long = 0L
    ): PlayInfoResult? {
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        if (epId != null && epId > 0L) {
            AppLog.i(
                logTag,
                "playback_diag playinfo started type=pgc cid=$cid epId=$epId qn=$qualityId"
            )
            val result = requestPgcPlayInfo(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                seasonId = seasonId
            )
            AppLog.i(
                logTag,
                "playback_diag playinfo finished type=pgc cid=$cid totalMs=${SystemClock.elapsedRealtime() - requestStartedAtMs} " +
                    "code=${result?.code ?: "none"} playable=${hasPlayableMedia(result?.data)}"
            )
            return result
        }

        val resolvedBvid = bvid?.takeIf { it.isNotBlank() }
        if (resolvedBvid == null && (aid ?: 0L) <= 0L) return null

        AppLog.i(
            logTag,
            "playback_diag playinfo started cid=$cid qn=$qualityId hasBvid=${resolvedBvid != null}"
        )
        val securityStartedAtMs = SystemClock.elapsedRealtime()
        securityGateway.ensureHealthyForPlay()
        AppLog.i(
            logTag,
            "playback_diag playinfo security_ready cid=$cid durationMs=${SystemClock.elapsedRealtime() - securityStartedAtMs}"
        )

        val primaryResult = requestPrimaryPlayInfo(
            aid = aid,
            bvid = resolvedBvid,
            cid = cid,
            qualityId = qualityId,
            fnval = fnval,
            fourk = fourk,
            allowWbi = allowWbi
        )

        if (primaryResult != null && hasPlayableMedia(primaryResult.data)) {
            AppLog.i(
                logTag,
                "playback_diag playinfo ready cid=$cid source=primary totalMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}"
            )
            return primaryResult
        }

        val primaryCode = primaryResult?.code ?: 0
        val primaryMessage = primaryResult?.message.orEmpty()
        val extractedVVoucher = primaryResult?.data?.vVoucher?.trim().orEmpty()

        if (extractedVVoucher.isNotBlank()) {
            AppLog.w(logTag, "requestPlayInfo v_voucher detected: cid=$cid, qn=$qualityId, vVoucherLen=${extractedVVoucher.length}")
        }

        if (sessionGateway.isRiskControl(primaryCode, primaryMessage) ||
            (primaryCode == 0 && primaryResult?.data != null && !hasPlayableMedia(primaryResult.data))) {
            AppLog.w(logTag, "requestPlayInfo risk-control detected: code=$primaryCode, falling back to try_look, cid=$cid, qn=$qualityId")
            val tryLookResult = requestTryLookPlayInfo(
                aid = aid,
                bvid = resolvedBvid,
                cid = cid,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                allowWbi = allowWbi
            )
            if (tryLookResult != null && hasPlayableMedia(tryLookResult.data)) {
                return tryLookResult.copy(isTryLookBypass = true, vVoucher = extractedVVoucher)
            }
            return (tryLookResult ?: primaryResult)?.copy(vVoucher = extractedVVoucher)
        }

        // 移动端推荐视频: bvid 为空时 web API 无法工作，尝试用 avid 换 bvid
        if (resolvedBvid == null && (aid ?: 0L) > 0L) {
            AppLog.w(logTag, "requestPlayInfo: bvid is empty, avid=$aid cannot be used without access_key, returning primary result")
        }

        return primaryResult
    }

    private suspend fun requestPrimaryPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        allowWbi: Boolean
    ): PlayInfoResult? {
        if (!allowWbi) {
            return requestNormalPlayInfo(aid, bvid, cid, qualityId, fnval, fourk)
        }

        // 并行发起 WBI 和 Normal 请求，可播放结果先到先用，避免慢 WBI 拖住冷启动。
        return coroutineScope {
            val wbiDeferred = async<PlayInfoResult?> {
                val startedAtMs = SystemClock.elapsedRealtime()
                val result = runCatching {
                    requestWbiPlayInfo(aid, bvid, cid, qualityId, fnval, fourk)
                }.getOrNull()
                AppLog.i(
                    logTag,
                    "playback_diag playinfo_attempt source=wbi cid=$cid durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "code=${result?.code ?: "none"} playable=${hasPlayableMedia(result?.data)}"
                )
                result
            }
            val normalDeferred = async<PlayInfoResult?> {
                val startedAtMs = SystemClock.elapsedRealtime()
                val result = runCatching {
                    requestNormalPlayInfo(aid, bvid, cid, qualityId, fnval, fourk)
                }.getOrNull()
                AppLog.i(
                    logTag,
                    "playback_diag playinfo_attempt source=normal cid=$cid durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                        "code=${result?.code ?: "none"} playable=${hasPlayableMedia(result?.data)}"
                )
                result
            }

            var wbiResult: PlayInfoResult? = null
            var normalResult: PlayInfoResult? = null
            val pending = mutableListOf(
                "wbi" to wbiDeferred,
                "normal" to normalDeferred
            )

            while (pending.isNotEmpty()) {
                val completed = awaitNextPlayInfo(pending)
                val (source, result) = completed
                pending.removeAll { it.first == source }
                when (source) {
                    "wbi" -> wbiResult = result
                    "normal" -> normalResult = result
                }
                if (result != null && hasPlayableMedia(result.data)) {
                    AppLog.i(
                        logTag,
                        "playback_diag playinfo_attempt winner=$source cid=$cid code=${result.code}"
                    )
                    pending.forEach { it.second.cancel() }
                    return@coroutineScope result
                }
            }

            // 两边都没有可播放数据时，保留原有优先级：code==0 且有 data 的 > 非null 的。
            val codeOkWithData = listOf(wbiResult, normalResult).firstOrNull {
                it != null && it.code == 0 && it.data != null
            }
            if (codeOkWithData != null) return@coroutineScope codeOkWithData

            return@coroutineScope normalResult ?: wbiResult
        }
    }

    private suspend fun awaitNextPlayInfo(
        pending: List<Pair<String, Deferred<PlayInfoResult?>>>
    ): Pair<String, PlayInfoResult?> {
        return select {
            pending.forEach { (source, deferred) ->
                deferred.onAwait { result ->
                    source to result
                }
            }
        }
    }

    private suspend fun requestWbiPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        ensureWbiKeys()
        if (!hasWbiKeys()) return null

        val params = mutableMapOf(
            "cid" to cid.toString(),
            "qn" to qualityId.toString(),
            "fnver" to "0",
            "fnval" to fnval.toString(),
            "fourk" to fourk.toString(),
            "voice_balance" to "1",
            "gaia_source" to "pre-load",
            "isGaiaAvoided" to "true",
            "web_location" to "1315873"
        )
        if (!bvid.isNullOrBlank()) {
            params["bvid"] = bvid
        } else {
            aid?.takeIf { it > 0L }?.let { params["avid"] = it.toString() }
        }

        val gaiaVtoken = cookieManager.getCookieValue("x-bili-gaia-vtoken")?.trim()
        if (!gaiaVtoken.isNullOrBlank()) {
            params["gaia_vtoken"] = gaiaVtoken
        }

        if (!cookieManager.hasSessionCookie()) {
            params["try_look"] = "1"
        }

        val session = genPlayUrlSession()
        if (session != null) {
            params["session"] = session
        }

        val wbiResponse = runCatching {
            apiService.getVideoPlayInfoWbi(buildWbiParams(params))
        }.onFailure { throwable ->
            if (throwable !is CancellationException) {
                AppLog.e(logTag, "requestPlayInfo wbi exception: ${throwable.message}", throwable)
            }
        }.getOrNull()

        if (wbiResponse != null) {
            return PlayInfoResult(
                code = wbiResponse.code,
                message = wbiResponse.message,
                data = wbiResponse.data
            )
        }
        return null
    }

    private suspend fun requestNormalPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        val gaiaVtoken = cookieManager.getCookieValue("x-bili-gaia-vtoken")?.trim()
        val tryLook = if (!cookieManager.hasSessionCookie()) 1 else null
        val normalResponse = runCatching {
            apiService.getVideoPlayInfo(
                avid = aid,
                bvid = bvid,
                cid = cid,
                qn = qualityId,
                fnval = fnval,
                fourk = fourk,
                fnver = 0,
                gaiaVtoken = gaiaVtoken?.takeIf { it.isNotBlank() },
                tryLook = tryLook
            )
        }.onFailure { throwable ->
            if (throwable !is CancellationException) {
                AppLog.e(logTag, "requestPlayInfo normal exception: ${throwable.message}", throwable)
            }
        }.getOrNull()

        if (normalResponse != null) {
            return PlayInfoResult(
                code = normalResponse.code,
                message = normalResponse.message,
                data = normalResponse.data
            )
        }
        return null
    }

    private suspend fun requestTryLookPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        allowWbi: Boolean
    ): PlayInfoResult? {
        val wbiTryLook = if (allowWbi) {
            requestWbiTryLookPlayInfo(aid, bvid, cid, qualityId, fnval, fourk)
        } else null

        if (wbiTryLook != null && hasPlayableMedia(wbiTryLook.data)) {
            return wbiTryLook
        }

        val normalTryLook = requestNormalTryLookPlayInfo(aid, bvid, cid, qualityId, fnval, fourk)

        if (normalTryLook != null && hasPlayableMedia(normalTryLook.data)) {
            return normalTryLook
        }

        return wbiTryLook ?: normalTryLook
    }

    private suspend fun requestWbiTryLookPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        ensureWbiKeys()
        if (!hasWbiKeys()) return null

        val params = mutableMapOf(
            "cid" to cid.toString(),
            "qn" to qualityId.toString(),
            "fnver" to "0",
            "fnval" to fnval.toString(),
            "fourk" to fourk.toString(),
            "try_look" to "1",
            "voice_balance" to "1",
            "gaia_source" to "pre-load",
            "isGaiaAvoided" to "true",
            "web_location" to "1315873"
        )
        if (!bvid.isNullOrBlank()) {
            params["bvid"] = bvid
        } else {
            aid?.takeIf { it > 0L }?.let { params["avid"] = it.toString() }
        }

        val session = genPlayUrlSession()
        if (session != null) {
            params["session"] = session
        }

        val wbiResponse = runCatching {
            noCookieApiService.getVideoPlayInfoWbiTryLook(buildWbiParams(params))
        }.onFailure { throwable ->
            AppLog.e(logTag, "requestPlayInfo wbi try_look exception: ${throwable.message}", throwable)
        }.getOrNull()

        if (wbiResponse != null) {
            return PlayInfoResult(
                code = wbiResponse.code,
                message = wbiResponse.message,
                data = wbiResponse.data
            )
        }
        return null
    }

    private suspend fun requestNormalTryLookPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int
    ): PlayInfoResult? {
        val response = runCatching {
            noCookieApiService.getVideoPlayInfoTryLook(
                avid = aid,
                bvid = bvid,
                cid = cid,
                qn = qualityId,
                fnval = fnval,
                fourk = fourk,
                fnver = 0
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "requestPlayInfo normal try_look exception: ${throwable.message}", throwable)
        }.getOrNull()

        if (response != null) {
            return PlayInfoResult(
                code = response.code,
                message = response.message,
                data = response.data
            )
        }
        return null
    }

    suspend fun requestPlayerInfoData(
        aid: Long?,
        bvid: String?,
        cid: Long,
        cacheBustTimestamp: Long? = null
    ): PlayerInfoDataWrapper? {
        ensureWbiKeys()
        if (hasWbiKeys()) {
            val params = mutableMapOf("cid" to cid.toString())
            aid?.let { params["avid"] = it.toString() }
            bvid?.takeIf { it.isNotBlank() }?.let { params["bvid"] = it }
            val wbiResponse = runCatching {
                apiService.getPlayerInfoWbi(buildWbiParams(params))
            }.onFailure { throwable ->
                AppLog.e(logTag, "loadPlayerInfoData wbi exception: ${throwable.message}", throwable)
            }.getOrNull()
            wbiResponse?.data?.takeIf { wbiResponse.isSuccess }?.let { return it }
        }

        val normalResponse = runCatching {
            apiService.getPlayerInfo(
                aid = aid,
                bvid = bvid,
                cid = cid,
                cacheBustTimestamp = cacheBustTimestamp
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "loadPlayerInfoData normal exception: ${throwable.message}", throwable)
        }.getOrNull()
        normalResponse?.data?.takeIf { normalResponse.isSuccess }?.let { return it }
        return null
    }

    suspend fun requestVideoSnapshot(
        aid: Long?,
        bvid: String?,
        cid: Long
    ): VideoSnapshotData? {
        if ((aid == null || aid <= 0L) && bvid.isNullOrBlank()) {
            return null
        }
        if (cid <= 0L) {
            return null
        }

        val response = runCatching {
            apiService.getVideoSnapshot(
                aid = aid,
                bvid = bvid,
                cid = cid
            )
        }.onFailure { throwable ->
            AppLog.e(logTag, "requestVideoSnapshot exception: ${throwable.message}", throwable)
        }.getOrNull()

        if (response != null) {
        }

        return response?.data?.takeIf { response.isSuccess }
    }

    suspend fun requestDanmakuSegmentBytes(
        cid: Long,
        aid: Long,
        segmentIndex: Int,
        expectedSegmentCount: Int = 0,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null
    ): ByteArray? {
        if (rangeStartMs != null || rangeEndMs != null) {
            val rangedStartedAt = SystemClock.elapsedRealtime()
            var prewarmMs = 0L
            val needsSessionWarmup = !hasWbiKeys() || sessionGateway.areWbiKeysStale()
            if (needsSessionWarmup) {
                val prewarmStartedAt = SystemClock.elapsedRealtime()
                securityGateway.prewarmWebSession()
                prewarmMs = SystemClock.elapsedRealtime() - prewarmStartedAt
            }
            val keyStartedAt = SystemClock.elapsedRealtime()
            val refreshedKeys = ensureWbiKeys()
            val keyMs = SystemClock.elapsedRealtime() - keyStartedAt
            val requestStartedAt = SystemClock.elapsedRealtime()
            val rangedBytes = if (hasWbiKeys()) requestDanmakuSegmentBytesWbi(
                cid = cid,
                aid = aid,
                segmentIndex = segmentIndex,
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs
            ) else null
            val requestMs = SystemClock.elapsedRealtime() - requestStartedAt
            val bytesSize = rangedBytes?.size ?: 0
            AppLog.i(
                logTag,
                "requestDanmakuSegment range timing: cid=$cid aid=$aid segment=$segmentIndex " +
                    "range=${MediaFormatUtils.formatDanmakuRange(rangeStartMs, rangeEndMs)} prewarm=$prewarmMs ms " +
                    "key=$keyMs ms refreshedKey=$refreshedKeys request=$requestMs ms " +
                    "total=${SystemClock.elapsedRealtime() - rangedStartedAt}ms " +
                    "bytes=$bytesSize"
            )
            if (rangedBytes != null && rangedBytes.isNotEmpty()) {
                return rangedBytes
            }
        }
        val expectation = buildDanmakuSegmentExpectation(expectedSegmentCount)
        val normalBytes = runCatching {
            apiService.getVideoComment(
                oid = cid,
                pid = aid,
                segmentIndex = segmentIndex
            ).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
        val normalProbe = probeDanmakuSegment(normalBytes)
        logDanmakuSegmentProbe(
            source = "normal",
            cid = cid,
            aid = aid,
            segmentIndex = segmentIndex,
            probe = normalProbe
        )
        if (normalProbe != null && !isSuspiciousDanmakuSegment(normalProbe, expectation)) {
            return normalProbe.bytes
        }

        if (normalProbe != null) {
            AppLog.w(
                logTag,
                "requestDanmakuSegment suspicious normal payload: cid=$cid, aid=$aid, " +
                    "segment=$segmentIndex, bytes=${normalProbe.bytes.size}, elems=${normalProbe.elemCount}, " +
                    "state=${normalProbe.state}, expected=${expectation?.expectedCount ?: 0}, " +
                    "minUseful=${expectation?.minimumUsefulCount ?: 0}"
            )
        }

        // 可疑预热：UA 强制刷新按最小间隔节流（prewarm 本身仍执行，只是不重复换 UA）。
        val forceUaRefresh = normalProbe != null &&
            SystemClock.elapsedRealtime() - lastSuspiciousUaRefreshAtMs >= SUSPICIOUS_UA_REFRESH_MIN_INTERVAL_MS
        if (forceUaRefresh) {
            lastSuspiciousUaRefreshAtMs = SystemClock.elapsedRealtime()
        }
        securityGateway.prewarmWebSession(forceUaRefresh = forceUaRefresh)
        ensureWbiKeys()

        if (!hasWbiKeys()) {
            return normalProbe?.bytes
        }

        val wbiBytes = requestDanmakuSegmentBytesWbi(
            cid = cid,
            aid = aid,
            segmentIndex = segmentIndex
        )
        val wbiProbe = probeDanmakuSegment(wbiBytes)
        logDanmakuSegmentProbe(
            source = "wbi",
            cid = cid,
            aid = aid,
            segmentIndex = segmentIndex,
            probe = wbiProbe
        )
        if (wbiProbe != null) {
            if (!isSuspiciousDanmakuSegment(wbiProbe, expectation)) {
                AppLog.i(
                    logTag,
                    "requestDanmakuSegment use wbi fallback: cid=$cid, aid=$aid, " +
                        "segment=$segmentIndex, normalElems=${normalProbe?.elemCount ?: -1}, " +
                        "wbiElems=${wbiProbe.elemCount}, expected=${expectation?.expectedCount ?: 0}"
                )
                return wbiProbe.bytes
            }
            if (normalProbe == null || wbiProbe.elemCount > normalProbe.elemCount || wbiProbe.bytes.size > normalProbe.bytes.size) {
                AppLog.w(
                    logTag,
                    "requestDanmakuSegment use larger suspicious wbi payload: cid=$cid, aid=$aid, " +
                        "segment=$segmentIndex, normalElems=${normalProbe?.elemCount ?: -1}, " +
                        "wbiElems=${wbiProbe.elemCount}, expected=${expectation?.expectedCount ?: 0}"
                )
                return wbiProbe.bytes
            }
        }
        return normalProbe?.bytes
    }

    private suspend fun requestDanmakuSegmentBytesWbi(
        cid: Long,
        aid: Long,
        segmentIndex: Int,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null
    ): ByteArray? {
        val params = mutableMapOf(
            "type" to "1",
            "oid" to cid.toString(),
            "pid" to aid.toString(),
            "segment_index" to segmentIndex.toString()
        )
        if (rangeStartMs != null || rangeEndMs != null) {
            params["pull_mode"] = "1"
            rangeStartMs?.let { params["ps"] = it.coerceAtLeast(0L).toString() }
            rangeEndMs?.let { params["pe"] = it.coerceAtLeast(0L).toString() }
        }
        return runCatching {
            apiService.getVideoCommentWbi(buildWbiParams(params)).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
    }

    private fun probeDanmakuSegment(bytes: ByteArray?): DanmakuSegmentProbe? {
        if (bytes == null) {
            return null
        }
        val segment = runCatching { DmProtoParser.parseSegment(bytes) }.getOrNull()
        return DanmakuSegmentProbe(
            bytes = bytes,
            elemCount = segment?.elems?.size ?: -1,
            state = segment?.state ?: -1
        )
    }

    private fun buildDanmakuSegmentExpectation(expectedSegmentCount: Int): DanmakuSegmentExpectation? {
        val expectedCount = expectedSegmentCount.takeIf { it >= 200 } ?: return null
        return DanmakuSegmentExpectation(
            expectedCount = expectedCount,
            minimumUsefulCount = (expectedCount * 3 / 5).coerceAtLeast(120)
        )
    }

    private fun isSuspiciousDanmakuSegment(
        probe: DanmakuSegmentProbe,
        expectation: DanmakuSegmentExpectation?
    ): Boolean {
        if (probe.bytes.size in 1..64 && probe.elemCount == 0) return true
        return expectation != null &&
            probe.elemCount >= 0 &&
            probe.elemCount < expectation.minimumUsefulCount
    }

    private fun logDanmakuSegmentProbe(
        source: String,
        cid: Long,
        aid: Long,
        segmentIndex: Int,
        probe: DanmakuSegmentProbe?
    ) {
    }

    suspend fun requestDanmakuViewBytes(
        cid: Long,
        aid: Long
    ): ByteArray? {
        return runCatching {
            apiService.getVideoCommentView(
                oid = cid,
                pid = aid
            ).use { responseBody ->
                responseBody.bytes()
            }
        }.getOrNull()
    }

    suspend fun requestAbsoluteBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val normalizedUrl = when {
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            else -> url
        }
        return@withContext runCatching {
            val request = Request.Builder()
                .url(normalizedUrl)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                response.body?.bytes()
            }
        }.getOrNull()
    }

    fun hasWbiKeys(): Boolean {
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return imgKey.isNotBlank() && subKey.isNotBlank()
    }

    suspend fun warmupWbiKeys() {
        ensureWbiKeys()
    }

    private suspend fun requestPgcPlayInfo(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        seasonId: Long = 0L
    ): PlayInfoResult? {
        // PGC 前置调用耗时埋点：三者均为冷启动可能的真实网络请求源，
        // 各自记录 durationMs 以便定位 totalMs 中有多少花在前置而非 playurl 本身。
        // 与 UGC 的 security_ready 日志对齐，统一用 playback_diag 前缀方便筛查。
        val healthyStartedAtMs = SystemClock.elapsedRealtime()
        securityGateway.ensureHealthyForPlay()
        AppLog.i(
            logTag,
            "playback_diag pgc_prelude step=ensureHealthy cid=$cid epId=$epId durationMs=${SystemClock.elapsedRealtime() - healthyStartedAtMs}"
        )
        val prewarmStartedAtMs = SystemClock.elapsedRealtime()
        securityGateway.prewarmWebSession()
        AppLog.i(
            logTag,
            "playback_diag pgc_prelude step=prewarmWebSession cid=$cid epId=$epId durationMs=${SystemClock.elapsedRealtime() - prewarmStartedAtMs}"
        )
        val wbiStartedAtMs = SystemClock.elapsedRealtime()
        ensureWbiKeys()
        AppLog.i(
            logTag,
            "playback_diag pgc_prelude step=ensureWbiKeys cid=$cid epId=$epId durationMs=${SystemClock.elapsedRealtime() - wbiStartedAtMs} hasWbi=${hasWbiKeys()}"
        )
        val hasWbi = hasWbiKeys()

        val baseAttempts = listOf(
            "simple" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                seasonId = seasonId,
                includeCid = true,
                includeVideoIds = true
            ),
            "ep-only" to buildPgcPlayParams(
                aid = aid,
                bvid = bvid,
                cid = cid,
                epId = epId,
                qualityId = qualityId,
                fnval = fnval,
                fourk = fourk,
                seasonId = seasonId,
                includeCid = false,
                includeVideoIds = false
            )
        )

        val attempts = if (hasWbi) {
            baseAttempts.flatMap { (label, params) ->
                val wbiParams = buildWbiParams(params)
                listOf("$label-wbi" to wbiParams, label to params)
            }
        } else {
            baseAttempts
        }

        var lastResponse: Base2Response<PgcV2Result>? = null
        attempts.forEachIndexed { index, (label, params) ->
            if (index > 0) {
                // 串行降级链：前一个 attempt 失败后才强制刷新 UA，此处单独计时以暴露其开销。
                val forceUaStartedAtMs = SystemClock.elapsedRealtime()
                securityGateway.prewarmWebSession(forceUaRefresh = true)
                AppLog.i(
                    logTag,
                    "playback_diag pgc_prelude step=forceUaRefresh cid=$cid epId=$epId before=$label durationMs=${SystemClock.elapsedRealtime() - forceUaStartedAtMs}"
                )
            }
            val attemptStartedAtMs = SystemClock.elapsedRealtime()
            val response = runCatching {
                apiService.getVideoPlayPgcInfo(params)
            }.onFailure { throwable ->
                AppLog.e(
                    logTag,
                    "requestPgcPlayInfo[$label] exception: ${throwable.message}",
                    throwable
                )
            }.getOrNull()
            // PGC attempt 级埋点：对齐 UGC 的 playinfo_attempt，补齐 PGC 成功路径零日志的黑盒。
            // 记录每个 attempt 的 label/durationMs/code/playable，可据此判断 totalMs 是单次慢还是多次重试。
            AppLog.i(
                logTag,
                "playback_diag playinfo_attempt source=$label cid=$cid epId=$epId attempt=${index + 1}/${attempts.size} " +
                    "durationMs=${SystemClock.elapsedRealtime() - attemptStartedAtMs} " +
                    "code=${response?.code ?: "none"} playable=${response?.let { it.isSuccess && it.result?.videoInfo != null } ?: false}"
            )
            lastResponse = response
            if (response != null) {
                if (response.isSuccess && response.result?.videoInfo != null) {
                    AppLog.i(
                        logTag,
                        "playback_diag playinfo_attempt winner=$label cid=$cid epId=$epId code=${response.code} attemptsUsed=${index + 1}"
                    )
                    return response.toPlayInfoResult()
                }
                if (!shouldRetryPgcPlayInfo(response)) {
                    AppLog.i(
                        logTag,
                        "playback_diag playinfo_attempt terminal=$label cid=$cid epId=$epId code=${response.code} msg=${response.message} reason=not_retryable"
                    )
                    return response.toPlayInfoResult()
                }
            }
        }

        AppLog.w(
            logTag,
            "playback_diag playinfo_attempt exhausted cid=$cid epId=$epId attempts=${attempts.size} lastCode=${lastResponse?.code}"
        )

        return lastResponse?.toPlayInfoResult()
    }

    private fun buildPgcPlayParams(
        aid: Long?,
        bvid: String?,
        cid: Long,
        epId: Long,
        qualityId: Int,
        fnval: Int,
        fourk: Int,
        seasonId: Long,
        includeCid: Boolean,
        includeVideoIds: Boolean
    ): Map<String, String> {
        val params = linkedMapOf(
            "ep_id" to epId.toString(),
            "qn" to qualityId.toString(),
            "fnver" to "0",
            "fnval" to fnval.toString(),
            "fourk" to "1",
            "try_look" to "1",
            "voice_balance" to "1",
            "gaia_source" to "pre-load",
            "isGaiaAvoided" to "true",
            "web_location" to "1315873"
        )
        if (seasonId > 0L) {
            params["season_id"] = seasonId.toString()
        }
        if (includeCid && cid > 0L) {
            params["cid"] = cid.toString()
        }
        if (includeVideoIds) {
            aid?.takeIf { it > 0L }?.let { params["avid"] = it.toString() }
            bvid?.takeIf { it.isNotBlank() }?.let { params["bvid"] = it }
        }
        return params
    }

    private fun shouldRetryPgcPlayInfo(response: Base2Response<PgcV2Result>): Boolean {
        if (response.isSuccess && response.result?.videoInfo != null) {
            return false
        }
        val message = response.message.trim()
        return response.code != 0 ||
            response.result == null ||
            message.contains("啥都没有") ||
            message.contains("nothing", ignoreCase = true)
    }

    private suspend fun ensureWbiKeys(): Boolean {
        if (hasWbiKeys() && !sessionGateway.areWbiKeysStale()) {
            return false
        }
        // 使用无 cookie 的客户端，防止 -101 响应清除关键 cookie
        val response = runCatching {
            noCookieApiService.getUserDetailInfo()
        }.onFailure { throwable ->
            AppLog.e(logTag, "ensureWbiKeys exception: ${throwable.message}", throwable)
        }.getOrNull() ?: return false
        if (response.isSuccess && response.data != null) {
            // 只更新 userInfo 和 WBI keys，不触发 session 清除
            sessionGateway.syncUserSession(response, source = "ensureWbiKeys", context = AuthContext.BACKGROUND)
            return true
        } else if (response.code != -101) {
            AppLog.e(logTag, "ensureWbiKeys failed: code=${response.code}, message=${response.errorMessage}")
        }
        return false
    }

    private fun buildWbiParams(params: Map<String, String>): Map<String, String> {
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        return WbiGenerator.generateWbiParams(params, imgKey, subKey)
    }


    private fun Base2Response<PgcV2Result>.toPlayInfoResult(): PlayInfoResult {
        return PlayInfoResult(
            code = code,
            message = message,
            data = result?.videoInfo
        )
    }

    private fun hasPlayableMedia(playInfo: PlayInfoModel?): Boolean {
        if (playInfo == null) {
            return false
        }
        val hasDashVideo = playInfo.dash?.video.orEmpty().isNotEmpty()
        val hasDurl = playInfo.durl.orEmpty().any { it.url.isNotBlank() }
        return hasDashVideo || hasDurl
    }

    private fun genPlayUrlSession(): String? {
        val buvid3 = cookieManager.getCookieValue("buvid3")?.trim()
        if (buvid3.isNullOrBlank()) return null
        val nowMs = System.currentTimeMillis()
        val raw = "$buvid3$nowMs"
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
