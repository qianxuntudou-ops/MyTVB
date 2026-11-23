package com.mytvb.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.model.common.CursorModel
import com.mytvb.model.video.HistoryVideoModel
import com.mytvb.model.video.VideoModel
import com.mytvb.repository.UserRepository
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeListViewModel(
    private val userRepository: UserRepository,
    private val feedPrewarmer: com.mytvb.repository.PersonalFeedPrewarmer
) : ViewModel() {
    private var lastLoadedAt = 0L

    // 与 MeListFragment 的 pageSize 保持一致（首页拉取条数）
    private val historyPageSize = 20

    private val _uiState = MutableStateFlow(MeListUiState())
    val uiState: StateFlow<MeListUiState> = _uiState.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 历史页「继续观看」推荐；null=不可用（接口无数据/结构未收录解析失败），UI 隐藏横条。 */
    private val _continuationVideos = MutableStateFlow<List<HistoryVideoModel>?>(null)
    val continuationVideos: StateFlow<List<HistoryVideoModel>?> = _continuationVideos.asStateFlow()

    // 历史游标三元组（官方文档：max+business+view_at 类似链表 next 指针）。
    // 只带 view_at 时同一秒的相邻条目定位不了边界，跨页会丢/重条目（79/80 现象的根源）。
    private var historyCursorViewAt: Long = 0
    private var historyCursorMax: Long = 0
    private var historyCursorBusiness: String = ""
    private var lastHistoryPayloadSignature = ""
    // 翻页连续零新增页数：单次整页重复可能是服务端游标偶发未推进（issue#128 报告翻页
    // 仍停在 79），推进游标重试一页往往就恢复；连续 2 次零新增才判真的到底。
    private var consecutiveEmptyHistoryPages = 0
    private var lastLaterPayloadSignature = ""
    private var loadJob: Job? = null
    private var silentRefreshJob: Job? = null
    private var requestSerial = 0
    private var activeRequestId = 0

    init {
        // 订阅「稍后观看」番剧弹幕数补全完成事件：
        // 用 sourceList 引用判断当前 UI 显示的还是不是这次 enrich 对应的数据。
        // 一致才用 enrichedList 替换，否则丢弃（避免回写过期数据）。
        viewModelScope.launch {
            userRepository.laterWatchEnriched.collectLatest { enrichment ->
                if (_uiState.value.laterVideos === enrichment.sourceList) {
                    val signature = videoListSignature(enrichment.enrichedList)
                    if (signature == lastLaterPayloadSignature) {
                        AppLog.d("MePerf", "laterWatchEnriched skipped duplicate payload")
                        return@collectLatest
                    }
                    lastLaterPayloadSignature = signature
                    _uiState.value = _uiState.value.copy(
                        laterVideos = enrichment.enrichedList
                    )
                }
            }
        }
    }

    fun isLoggedIn(): Boolean = userRepository.isLoggedIn()

    /** 历史页「继续观看」；失败静默（无推荐是常态，不占错误通道）。 */
    fun loadHistoryContinuation() {
        if (!userRepository.isLoggedIn()) return
        viewModelScope.launch {
            runCatching { userRepository.getHistoryContinuation() }
                .onSuccess { _continuationVideos.value = it }
                .onFailure { AppLog.d("MePerf", "historyContinuation failed: ${it.message}") }
        }
    }

    fun loadHistory(page: Int, pageSize: Int) {
        val restartFirstPage = page <= 1
        if (_loading.value && !restartFirstPage) {
            AppLog.d("MePerf", "loadHistory: skip load-more while loading, page=$page")
            return
        }
        val requestId = nextRequestId(restartFirstPage)
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null

            if (!userRepository.isLoggedIn()) {
                if (!isActiveRequest(requestId)) return@launch
                lastHistoryPayloadSignature = ""
                _uiState.value = _uiState.value.copy(historyVideos = emptyList())
                _hasMore.value = false
                _error.value = MyBLBLApplication.instance.getString(R.string.need_sign_in)
                finishRequest(requestId)
                return@launch
            }

            if (page == 1) {
                historyCursorViewAt = 0
                historyCursorMax = 0
                historyCursorBusiness = ""
                consecutiveEmptyHistoryPages = 0
            }

            val t0 = System.currentTimeMillis()
            AppLog.d("MePerf", "loadHistory: 开始请求, page=$page, viewAt=$historyCursorViewAt")

            try {
                // 预取命中时直接用内存结果；注意先求值成完整的 Result 再链 onSuccess，
                // 否则 ?: 的优先级会让命中分支跳过整个成功处理链。
                val response = (if (page == 1) feedPrewarmer.takeHistory() else null)
                    ?.let { Result.success(it) }
                    ?: userRepository.getHistory(
                        viewAt = historyCursorViewAt,
                        pageSize = pageSize,
                        max = historyCursorMax,
                        business = historyCursorBusiness
                    )
                response
                    .onSuccess { response ->
                    if (!isActiveRequest(requestId)) {
                        AppLog.d("MePerf", "loadHistory: drop stale result, request=$requestId page=$page")
                        return@onSuccess
                    }
                    AppLog.d("MePerf", "loadHistory: 请求返回, 耗时=${System.currentTimeMillis() - t0}ms, code=${response.code}")
                    if (response.isSuccess && response.data != null) {
                        val rawList = response.data.list
                        val pageItems = rawList
                            .filter { it.covers == null }
                            .distinctBy(::historyItemKey)
                        val merged = if (page == 1) {
                            pageItems
                        } else {
                            (_uiState.value.historyVideos + pageItems).distinctBy(::historyItemKey)
                        }
                        val signature = historyListSignature(merged)
                        if (signature == lastHistoryPayloadSignature) {
                            AppLog.d("MePerf", "loadHistory: 跳过重复payload, page=$page, items=${merged.size}")
                            // 即使跳过渲染也必须推进游标：不推进的话下一页仍带旧游标请求，
                            // 服务端原样重发同页 → 永远跳过 → 列表卡死不再增长。
                            applyHistoryCursor(response.data.cursor, rawList)
                            if (page > 1) {
                                // 翻页却零新增：可能是服务端游标偶发未推进重发同页（重试一页
                                // 往往就恢复），连续 2 次零新增才判到底（issue#128 报告仍停 79）
                                consecutiveEmptyHistoryPages += 1
                                AppLog.d(
                                    "MePerf",
                                    "loadHistory: empty-page $consecutiveEmptyHistoryPages/2, page=$page, raw=${rawList.size}"
                                )
                                _hasMore.value = consecutiveEmptyHistoryPages < 2
                            } else {
                                _hasMore.value = rawList.isNotEmpty()
                            }
                            lastLoadedAt = System.currentTimeMillis()
                            return@onSuccess
                        }
                        consecutiveEmptyHistoryPages = 0
                        lastHistoryPayloadSignature = signature
                        _uiState.value = _uiState.value.copy(historyVideos = merged)
                        applyHistoryCursor(response.data.cursor, rawList)
                        // 不满页不再直接判底（issue#131 仍停 79/99）：服务端遇失效视频/
                        // 同秒边界会返回不满页但后面还有数据。放宽为「有数据就继续」，
                        // 真正的到底交给上面的连续 2 页零新增兜底（空页自然推进计数）。
                        _hasMore.value = rawList.isNotEmpty()
                        lastLoadedAt = System.currentTimeMillis()
                    } else {
                        _error.value = response.errorMessage
                    }
                }
                .onFailure { exception ->
                    if (exception is CancellationException) {
                        throw exception
                    }
                    if (!isActiveRequest(requestId)) {
                        AppLog.d("MePerf", "loadHistory: drop stale failure, request=$requestId page=$page")
                        return@onFailure
                    }
                    AppLog.e("MePerf", "loadHistory: 失败, 耗时=${System.currentTimeMillis() - t0}ms, ${exception.message}")
                    _error.value = exception.message
                }
            } finally {
                finishRequest(requestId)
            }
        }
    }

    /**
     * 静默刷新历史进度（从播放器返回时调用）：
     * 重拉首页后按 itemKey 保序合并进现有列表——只覆盖匹配项的进度等字段，
     * 顺序不变、不增不删，列表/焦点零扰动。不占用 loading/error/cursor/请求串号体系，
     * 失败静默吞（下次手动刷新自愈）。
     */
    fun silentRefreshHistoryProgress() {
        if (!userRepository.isLoggedIn()) {
            AppLog.d("MePerf", "silentRefreshHistory: skip, not logged in")
            return
        }
        if (_uiState.value.historyVideos.isEmpty()) {
            AppLog.d("MePerf", "silentRefreshHistory: skip, empty list")
            return
        }
        if (_loading.value) {
            AppLog.d("MePerf", "silentRefreshHistory: skip, loading in flight")
            return
        }
        silentRefreshJob?.cancel()
        silentRefreshJob = viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                AppLog.d("MePerf", "silentRefreshHistory: request first page")
                // 不吃 feedPrewarmer 预热缓存：那是冷启动抓的旧数据，这里要服务端最新进度
                val response = userRepository.getHistory(0L, historyPageSize)
                response
                    .onSuccess { response ->
                        // 期间正常加载已启动则丢弃，正常加载的结果更权威
                        if (_loading.value) {
                            AppLog.d("MePerf", "silentRefreshHistory: drop, normal load took over")
                            return@onSuccess
                        }
                        if (!response.isSuccess || response.data == null) {
                            AppLog.e("MePerf", "silentRefreshHistory: code=${response.code}")
                            return@onSuccess
                        }
                        // 应用时才读当前列表做基底，避免请求期间删除项被旧快照写回
                        val current = _uiState.value.historyVideos
                        if (current.isEmpty()) {
                            AppLog.d("MePerf", "silentRefreshHistory: drop, list cleared during request")
                            return@onSuccess
                        }
                        val freshByKey = response.data.list
                            .filter { it.covers == null }
                            .distinctBy(::historyItemKey)
                            .associateBy(::historyItemKey)
                        val merged = current.map { freshByKey[historyItemKey(it)] ?: it }
                        if (merged == current) {
                            AppLog.d("MePerf", "silentRefreshHistory: no diff, items=${merged.size}, cost=${System.currentTimeMillis() - t0}ms")
                            return@onSuccess
                        }
                        val changed = merged.zip(current)
                            .count { (new, old) -> new != old }
                        AppLog.d("MePerf", "silentRefreshHistory: applied, items=${merged.size}, changed=$changed, cost=${System.currentTimeMillis() - t0}ms")
                        lastHistoryPayloadSignature = historyListSignature(merged)
                        _uiState.value = _uiState.value.copy(historyVideos = merged)
                    }
                    .onFailure { exception ->
                        if (exception is CancellationException) {
                            throw exception
                        }
                        AppLog.e("MePerf", "silentRefreshHistory: failed, ${exception.message}")
                    }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MePerf", "silentRefreshHistory: failed, ${t.message}")
            }
        }
    }

    /**
     * 静默刷新「稍后观看」进度（从播放器返回时调用）：
     * 与 [silentRefreshHistoryProgress] 同语义——重拉后按 itemKey 保序合并进现有列表，
     * 只覆盖匹配项的进度等字段，顺序不变、不增不删，列表/焦点零扰动。失败静默吞。
     */
    fun silentRefreshLaterWatchProgress() {
        if (!userRepository.isLoggedIn()) {
            AppLog.d("MePerf", "silentRefreshLater: skip, not logged in")
            return
        }
        if (_uiState.value.laterVideos.isEmpty()) {
            AppLog.d("MePerf", "silentRefreshLater: skip, empty list")
            return
        }
        if (_loading.value) {
            AppLog.d("MePerf", "silentRefreshLater: skip, loading in flight")
            return
        }
        silentRefreshJob?.cancel()
        silentRefreshJob = viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                AppLog.d("MePerf", "silentRefreshLater: request")
                val response = userRepository.getLaterWatch()
                response
                    .onSuccess { response ->
                        // 期间正常加载已启动则丢弃，正常加载的结果更权威
                        if (_loading.value) {
                            AppLog.d("MePerf", "silentRefreshLater: drop, normal load took over")
                            return@onSuccess
                        }
                        if (!response.isSuccess || response.data == null) {
                            AppLog.e("MePerf", "silentRefreshLater: code=${response.code}")
                            return@onSuccess
                        }
                        val current = _uiState.value.laterVideos
                        if (current.isEmpty()) {
                            AppLog.d("MePerf", "silentRefreshLater: drop, list cleared during request")
                            return@onSuccess
                        }
                        val freshByKey = response.data.list.associateBy(::videoItemKey)
                        val merged = current.map { freshByKey[videoItemKey(it)] ?: it }
                        if (merged == current) {
                            AppLog.d("MePerf", "silentRefreshLater: no diff, items=${merged.size}, cost=${System.currentTimeMillis() - t0}ms")
                            return@onSuccess
                        }
                        val changed = merged.zip(current)
                            .count { (new, old) -> new != old }
                        AppLog.d("MePerf", "silentRefreshLater: applied, items=${merged.size}, changed=$changed, cost=${System.currentTimeMillis() - t0}ms")
                        lastLaterPayloadSignature = videoListSignature(merged)
                        _uiState.value = _uiState.value.copy(laterVideos = merged)
                    }
                    .onFailure { exception ->
                        if (exception is CancellationException) {
                            throw exception
                        }
                        AppLog.e("MePerf", "silentRefreshLater: failed, ${exception.message}")
                    }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MePerf", "silentRefreshLater: failed, ${t.message}")
            }
        }
    }

    fun loadLaterWatch() {
        val requestId = nextRequestId(restart = true)
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null

            if (!userRepository.isLoggedIn()) {
                if (!isActiveRequest(requestId)) return@launch
                lastLaterPayloadSignature = ""
                _uiState.value = _uiState.value.copy(laterVideos = emptyList())
                _hasMore.value = false
                _error.value = MyBLBLApplication.instance.getString(R.string.need_sign_in)
                finishRequest(requestId)
                return@launch
            }

            try {
                val response = feedPrewarmer.takeLaterWatch()
                    ?.let { Result.success(it) }
                    ?: userRepository.getLaterWatch()
                response
                    .onSuccess { response ->
                    if (!isActiveRequest(requestId)) {
                        AppLog.d("MePerf", "loadLaterWatch: drop stale result, request=$requestId")
                        return@onSuccess
                    }
                    if (response.isSuccess && response.data != null) {
                        val videos = response.data.list
                        val signature = videoListSignature(videos)
                        if (signature == lastLaterPayloadSignature) {
                            AppLog.d("MePerf", "loadLaterWatch: 跳过重复payload, items=${videos.size}")
                            _hasMore.value = false
                            lastLoadedAt = System.currentTimeMillis()
                            return@onSuccess
                        }
                        lastLaterPayloadSignature = signature
                        _uiState.value = _uiState.value.copy(laterVideos = videos)
                        _hasMore.value = false
                        lastLoadedAt = System.currentTimeMillis()
                    } else {
                        _error.value = response.errorMessage
                    }
                }
                .onFailure { exception ->
                    if (exception is CancellationException) {
                        throw exception
                    }
                    if (!isActiveRequest(requestId)) {
                        AppLog.d("MePerf", "loadLaterWatch: drop stale failure, request=$requestId")
                        return@onFailure
                    }
                    _error.value = exception.message
                }
            } finally {
                finishRequest(requestId)
            }
        }
    }

    private fun nextRequestId(restart: Boolean): Int {
        if (restart) {
            loadJob?.cancel()
        }
        val requestId = ++requestSerial
        activeRequestId = requestId
        return requestId
    }

    private fun isActiveRequest(requestId: Int): Boolean {
        return requestId == activeRequestId
    }

    private fun finishRequest(requestId: Int) {
        if (isActiveRequest(requestId)) {
            _loading.value = false
        }
    }

    fun removeLaterVideo(aid: Long, bvid: String) {
        val current = _uiState.value.laterVideos
        val filtered = current.filter {
            it.aid != aid || (bvid.isNotBlank() && it.bvid != bvid)
        }
        lastLaterPayloadSignature = videoListSignature(filtered)
        _uiState.value = _uiState.value.copy(laterVideos = filtered)
    }

    fun removeHistoryVideo(item: HistoryVideoModel) {
        val key = historyItemKey(item)
        val filtered = _uiState.value.historyVideos.filter { historyItemKey(it) != key }
        lastHistoryPayloadSignature = historyListSignature(filtered)
        _uiState.value = _uiState.value.copy(historyVideos = filtered)
    }

    fun removeHistoryVideosByUp(upName: String) {
        val filtered = _uiState.value.historyVideos.filter {
            !it.displayAuthorName.equals(upName, ignoreCase = true)
        }
        lastHistoryPayloadSignature = historyListSignature(filtered)
        _uiState.value = _uiState.value.copy(historyVideos = filtered)
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }

    /** 用服务端返回的 cursor（或页尾兜底）推进历史游标三元组。 */
    private fun applyHistoryCursor(cursor: CursorModel?, rawList: List<HistoryVideoModel>) {
        historyCursorViewAt = cursor?.viewAt
            ?: rawList.lastOrNull()?.viewAt
            ?: historyCursorViewAt // 空页且无 cursor：保持旧游标，归零会从头重拉一整页
        historyCursorMax = cursor?.max ?: 0L
        historyCursorBusiness = cursor?.business.orEmpty()
    }

    private fun historyItemKey(item: HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }

    private fun historyListSignature(items: List<HistoryVideoModel>): String {
        if (items.isEmpty()) return "empty"
        return items.joinToString(separator = "|") { item ->
            "${historyItemKey(item)}:${item.title.hashCode()}:${item.progress}:${item.viewAt}:${item.cover.hashCode()}"
        }
    }

    private fun videoItemKey(item: VideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            item.aid > 0L -> "aid:${item.aid}"
            else -> "title:${item.title}|cover:${item.coverUrl}"
        }
    }

    private fun videoListSignature(items: List<VideoModel>): String {
        if (items.isEmpty()) return "empty"
        return items.joinToString(separator = "|") { item ->
            "${videoItemKey(item)}:${item.title.hashCode()}:${item.viewCount}:${item.danmakuCount}:${item.historyProgress}:${item.coverUrl.hashCode()}"
        }
    }
}
