package com.mytvb.feature.player

import androidx.lifecycle.SavedStateHandle
import com.mytvb.core.common.json.GsonHolder
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.settings.SubtitleDefaultMode
import com.mytvb.model.subtitle.SubtitleData
import com.mytvb.model.subtitle.SubtitleInfoModel
import com.mytvb.model.subtitle.SubtitleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 字幕子系统控制器：轨道列表/选中态/正文缓存/时间轴渲染，从 VideoPlayerViewModel 原样搬移。
 * 视频身份（aid/bvid/cid）与播放位置通过 provider 回调读取宿主最新值。
 */
internal class SubtitlePlaybackController(
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient,
    private val playInfoGateway: VideoPlayerPlayInfoGateway,
    private val savedStateHandle: SavedStateHandle,
    private val currentAid: () -> Long?,
    private val currentBvid: () -> String?,
    private val currentCid: () -> Long,
    private val currentPositionMs: () -> Long
) {

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        internal const val SAVED_SUBTITLE_INDEX = "saved_player_subtitle_index"
    }

    internal data class SubtitleLoadResult(
        val data: SubtitleData?,
        val httpCode: Int? = null
    )

    private val subtitleCache = object : LinkedHashMap<String, SubtitleData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubtitleData>): Boolean {
            return size > 8
        }
    }

    private val _subtitles = MutableStateFlow<List<SubtitleInfoModel>>(emptyList())
    val subtitles: StateFlow<List<SubtitleInfoModel>> = _subtitles.asStateFlow()

    private val _selectedSubtitleIndex = MutableStateFlow(-1)
    val selectedSubtitleIndex: StateFlow<Int> = _selectedSubtitleIndex.asStateFlow()

    private val _currentSubtitleText = MutableStateFlow<String?>(null)
    val currentSubtitleText: StateFlow<String?> = _currentSubtitleText.asStateFlow()

    private var currentSubtitleData: SubtitleData? = null
    private var currentSubtitleCueIndex: Int = 0
    private var subtitleLoadToken: Long = 0L
    private var subtitleOwnerBvid: String? = null
    private var subtitleOwnerCid: Long = 0L
    private var shouldAutoSelectSubtitle = true
    private var subtitleDefaultMode = SubtitleDefaultMode.AUTO
    private var preferredSubtitleLan: String = ""

    // 当前选中的字幕是否来自自动策略（而非用户手动）。detail 阶段可能因 ai_status 缺失误判加载，
    // playerInfo 轨道（信息更全）到达后据此纠偏关闭，用户手动选过则永不回收。
    private var autoSelectedByPolicy = false

    /** loadVideoInfo / 换视频时的整体复位，恢复"按设置自动选字幕"。 */
    fun resetForNewVideo(mode: SubtitleDefaultMode, preferredLan: String = "") {
        subtitleLoadToken++
        currentSubtitleData = null
        subtitleOwnerBvid = null
        subtitleOwnerCid = 0L
        subtitleDefaultMode = mode
        shouldAutoSelectSubtitle = mode != SubtitleDefaultMode.OFF
        preferredSubtitleLan = preferredLan
        autoSelectedByPolicy = false
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
        currentSubtitleCueIndex = 0
    }

    /** 同视频内切集/切清晰度等会话级复位。上次是自动选择的则恢复自动资格，让新分P 重新按策略判定；用户手动选过（开或关）则尊重手动结果。 */
    fun resetSession() {
        subtitleLoadToken++
        currentSubtitleData = null
        subtitleOwnerBvid = null
        subtitleOwnerCid = 0L
        currentSubtitleCueIndex = 0
        if (autoSelectedByPolicy) {
            shouldAutoSelectSubtitle = subtitleDefaultMode != SubtitleDefaultMode.OFF
            autoSelectedByPolicy = false
        }
        _selectedSubtitleIndex.value = -1
        _currentSubtitleText.value = null
    }

    fun setSubtitles(tracks: List<SubtitleInfoModel>) {
        // 统一在此收口展示排序（设备语言→AI→其它）：detail/playerInfo 两条装载
        // 链路的多个直塞入口无需各自排序，UI 下标全程一致
        _subtitles.value = orderSubtitleTracksByPreference(tracks)
    }

    fun subtitlesValue(): List<SubtitleInfoModel> = _subtitles.value

    fun persistSelection() {
        savedStateHandle[SAVED_SUBTITLE_INDEX] = _selectedSubtitleIndex.value
    }

    fun consumePersistedSelection(): Int {
        return savedStateHandle.remove<Int>(SAVED_SUBTITLE_INDEX) ?: -1
    }

    fun selectSubtitle(index: Int) {
        // 显式选择（含手动关闭）后本视频内不再自动选字幕，避免迟到的轨道更新覆盖用户操作。
        shouldAutoSelectSubtitle = false
        autoSelectedByPolicy = false
        val requestToken = ++subtitleLoadToken
        _selectedSubtitleIndex.value = index
        savedStateHandle[SAVED_SUBTITLE_INDEX] = index
        if (index < 0) {
            currentSubtitleData = null
            subtitleOwnerBvid = null
            subtitleOwnerCid = 0L
            currentSubtitleCueIndex = 0
            _currentSubtitleText.value = null
            AppLog.i(TAG, "subtitle_trace select_off cid=${currentCid()} bvid=${currentBvid()}")
            return
        }
        val subtitle = _subtitles.value.getOrNull(index) ?: run {
            AppLog.w(
                TAG,
                "subtitle_trace select_no_track cid=${currentCid()} bvid=${currentBvid()} " +
                    "index=$index size=${_subtitles.value.size}"
            )
            return
        }
        // [诊断] loadSubtitleData 需要网络请求。请求期间切换视频时，旧结果可能写入当前字幕状态。
        // 记录请求归属和轨道摘要，便于确认轨道来自 detail 还是 playerInfo。
        val reqCid = currentCid()
        val reqBvid = currentBvid()
        AppLog.i(
            TAG,
            "subtitle_trace select_enter cid=$reqCid bvid=$reqBvid index=$index " +
                "lan=${subtitle.lan} url=${subtitle.subtitleUrl} " +
                "allTracks=${subtitleTracksSummary(_subtitles.value)}"
        )
        scope.launch {
            var loadedResult = loadSubtitleData(subtitle, reqBvid, reqCid)
            if (shouldRefreshSubtitleTrack(subtitle, loadedResult.httpCode)) {
                val refreshedTracks = playInfoGateway.requestPlayerInfoData(
                    aid = currentAid(),
                    bvid = reqBvid,
                    cid = reqCid,
                    cacheBustTimestamp = System.currentTimeMillis()
                )?.subtitle?.subtitles.orEmpty()
                val refreshedTrack = refreshedSubtitleTrackFor(subtitle, refreshedTracks)
                if (refreshedTrack != null) {
                    AppLog.i(
                        TAG,
                        "subtitle_trace load_retry_fresh_track cid=$reqCid bvid=$reqBvid " +
                            "code=${loadedResult.httpCode} lan=${refreshedTrack.lan}"
                    )
                    loadedResult = loadSubtitleData(refreshedTrack, reqBvid, reqCid)
                }
            }
            val loaded = loadedResult.data
            if (!shouldApplySubtitleLoadResult(
                    activeToken = subtitleLoadToken,
                    resultToken = requestToken,
                    requestCid = reqCid,
                    requestBvid = reqBvid,
                    currentCid = currentCid(),
                    currentBvid = currentBvid()
                )
            ) {
                AppLog.w(
                    TAG,
                    "subtitle_trace data_drop_stale reqCid=$reqCid reqBvid=$reqBvid " +
                        "curCid=${currentCid()} curBvid=${currentBvid()} index=$index token=$requestToken " +
                        "activeToken=$subtitleLoadToken " +
                        "cues=${loaded?.body?.size ?: 0}"
                )
                return@launch
            }
            currentSubtitleData = loaded
            subtitleOwnerBvid = reqBvid
            subtitleOwnerCid = reqCid
            currentSubtitleCueIndex = 0
            AppLog.i(
                TAG,
                "subtitle_trace data_set cid=${currentCid()} bvid=${currentBvid()} " +
                    "index=$index cues=${loaded?.body?.size ?: 0}"
            )
            updateSubtitleText(currentPositionMs())
        }
    }

    private suspend fun loadSubtitleData(
        track: SubtitleInfoModel,
        bvid: String?,
        cid: Long
    ): SubtitleLoadResult =
        withContext(Dispatchers.IO) {
            if (!isTrustedBilibiliSubtitleUrl(track.subtitleUrl)) {
                AppLog.w(TAG, "subtitle_trace load_drop_untrusted_url lan=${track.lan} cid=$cid bvid=$bvid")
                return@withContext SubtitleLoadResult(data = null)
            }
            val normalizedUrl = normalizeBilibiliSubtitleUrl(track.subtitleUrl)
            val cacheKey = buildSubtitleCueCacheKey(bvid, cid, track, normalizedUrl)
            subtitleCache[cacheKey]?.let {
                AppLog.i(TAG, "subtitle_trace load_cache_hit key=$cacheKey cues=${it.body?.size ?: 0}")
                return@withContext SubtitleLoadResult(data = it)
            }
            AppLog.i(TAG, "subtitle_trace load_net_start url=$normalizedUrl lan=${track.lan}")

            runCatching {
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("Referer", "https://www.bilibili.com")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                    )
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use SubtitleLoadResult(data = null, httpCode = response.code)
                    }
                    SubtitleLoadResult(
                        data = response.body?.charStream()?.use { reader ->
                            GsonHolder.DEFAULT.fromJson(reader, SubtitleData::class.java)
                        }
                    )
                }
            }.getOrElse { SubtitleLoadResult(data = null) }.also { result ->
                result.data?.let { subtitleData ->
                    subtitleCache[cacheKey] = subtitleData
                // [诊断] 打印首条 cue 的内容，用于判断接口返回的字幕文本是否属于当前视频。
                // 如果这里的内容就已经是别的视频的台词，说明是服务端返回错（URL/cid 都对但内容错），
                // 客户端无法修复；如果内容对但用户仍觉错，则疑点在时间轴/显示环节。
                    val firstCue = subtitleData.body?.firstOrNull()
                    AppLog.i(
                        TAG,
                        "subtitle_trace load_net_ok url=$normalizedUrl cues=${subtitleData.body?.size ?: 0} " +
                            "firstFrom=${firstCue?.from} firstTo=${firstCue?.to} " +
                            "firstText=${firstCue?.content?.replace('\n', ' ')?.take(40)}"
                    )
                }
                if (result.data == null) {
                    AppLog.w(
                        TAG,
                        "subtitle_trace load_net_empty url=$normalizedUrl lan=${track.lan} code=${result.httpCode}"
                    )
                }
            }
        }

    /** 快捷键/设置切换字幕三态后即时应用：OFF 直接关；ON/AUTO 恢复自动资格并立即按策略重判。 */
    fun applyModeNow(mode: SubtitleDefaultMode) {
        subtitleDefaultMode = mode
        if (mode == SubtitleDefaultMode.OFF) {
            selectSubtitle(-1)
        } else {
            shouldAutoSelectSubtitle = true
            autoSelectedByPolicy = false
            maybeAutoSelectSubtitle()
        }
    }

    fun maybeAutoSelectSubtitle() {
        val subtitles = _subtitles.value
        // 纠偏：此前按策略自动加载过，而最新轨道（playerInfo 的 ai_status 更全）判定视频已有
        // 内嵌字幕不该挂外挂 → 回收关闭。用户手动选过（autoSelectedByPolicy=false）则永不回收。
        if (autoSelectedByPolicy && subtitleDefaultMode == SubtitleDefaultMode.AUTO &&
            subtitles.isNotEmpty() && !shouldAutoLoadExternalSubtitle(subtitles)
        ) {
            AppLog.i(
                TAG,
                "subtitle_trace auto_revert cid=${currentCid()} bvid=${currentBvid()} " +
                    "tracks=${subtitleTracksSummary(subtitles)}"
            )
            selectSubtitle(-1)
            // 纠偏不是终态：后续轨道更新/切集仍按策略正常自动判定。
            shouldAutoSelectSubtitle = subtitleDefaultMode != SubtitleDefaultMode.OFF
            return
        }
        if (!shouldAutoSelectSubtitle) {
            AppLog.i(TAG, "subtitle_trace auto_select_skip reason=disabled cid=${currentCid()} bvid=${currentBvid()}")
            return
        }
        if (subtitles.isEmpty()) {
            AppLog.i(TAG, "subtitle_trace auto_select_skip reason=empty cid=${currentCid()} bvid=${currentBvid()}")
            return
        }
        // 自动模式：视频已有内嵌字幕（全部为 AI 辅助备选轨）时不加载外挂字幕，避免双层重叠。
        // 此处不消费 shouldAutoSelectSubtitle，等 playerInfo 轨道（带更全的 ai_status）到达后再判定。
        if (subtitleDefaultMode == SubtitleDefaultMode.AUTO && !shouldAutoLoadExternalSubtitle(subtitles)) {
            AppLog.i(
                TAG,
                "subtitle_trace auto_select_skip reason=embedded_hint mode=auto " +
                    "cid=${currentCid()} bvid=${currentBvid()} " +
                    "tracks=${subtitleTracksSummary(subtitles)}"
            )
            return
        }
        shouldAutoSelectSubtitle = false
        val targetIndex = selectPreferredSubtitleIndex(subtitles, preferredSubtitleLan)
        val targetLan = subtitles.getOrNull(targetIndex)?.lan.orEmpty()
        AppLog.i(
            TAG,
            "subtitle_trace auto_select cid=${currentCid()} bvid=${currentBvid()} " +
                "size=${subtitles.size} index=$targetIndex lan=$targetLan " +
                "preferredLan=$preferredSubtitleLan"
        )
        selectSubtitle(targetIndex)
        autoSelectedByPolicy = true
    }

    fun updateSubtitleText(positionMs: Long) {
        if (subtitleOwnerBvid != currentBvid() || subtitleOwnerCid != currentCid()) {
            currentSubtitleCueIndex = 0
            _currentSubtitleText.value = null
            return
        }
        val data = currentSubtitleData?.body.orEmpty()
        if (_selectedSubtitleIndex.value < 0 || data.isEmpty()) {
            currentSubtitleCueIndex = 0
            if (_currentSubtitleText.value != null) {
                _currentSubtitleText.value = null
            }
            return
        }
        val positionSeconds = positionMs / 1000f
        val cue = data.findCueAt(positionSeconds)
        val subtitleText = cue?.content
        // [诊断] 命中切换时打印位置 + cue 区间 + 内容,确认时间轴是否对齐。
        // 若 from/to 与 position 相差很远,说明时间轴错位(疑点在 findCueAt 或服务端时间轴);
        // 若区间对齐但内容不符当前画面,疑点在服务端返回的字幕文本本身。
        if (cue != null && _currentSubtitleText.value != subtitleText) {
            AppLog.i(
                TAG,
                "subtitle_trace show posMs=$positionMs cueFrom=${cue.from} cueTo=${cue.to} " +
                    "deltaSec=${(positionSeconds - cue.from)} cid=${currentCid()} " +
                    "text=${cue.content.replace('\n', ' ').take(40)}"
            )
        }
        if (_currentSubtitleText.value != subtitleText) {
            _currentSubtitleText.value = subtitleText
        }
    }

    private fun List<SubtitleItem>.findCueAt(positionSeconds: Float): SubtitleItem? {
        if (isEmpty()) {
            currentSubtitleCueIndex = 0
            return null
        }
        var index = currentSubtitleCueIndex.coerceIn(0, lastIndex)
        if (positionSeconds < this[index].from) {
            while (index > 0 && positionSeconds < this[index].from) {
                index--
            }
        } else {
            while (index < lastIndex && positionSeconds > this[index].to) {
                index++
            }
        }
        currentSubtitleCueIndex = index
        val cue = this[index]
        return cue.takeIf { positionSeconds >= it.from && positionSeconds <= it.to }
    }
}

// [诊断] 把字幕轨道列表压缩成 "lan=url尾段" 的短摘要，便于在日志里对照
// detail 与 playerInfo 两个接口返回的轨道是否一致、是否串台。
// url 只取最后一个 '/' 之后的部分并截断，避免日志被超长 url 淹没。
internal fun subtitleTracksSummary(tracks: List<SubtitleInfoModel>?): String {
    if (tracks.isNullOrEmpty()) return "[]"
    return tracks.joinToString(prefix = "[", postfix = "]", separator = ",") { t ->
        subtitleTrackBindingKey(t)
    }
}
