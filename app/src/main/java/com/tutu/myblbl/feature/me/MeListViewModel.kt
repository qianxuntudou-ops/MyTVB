package com.tutu.myblbl.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.model.video.HistoryVideoModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.repository.UserRepository
import com.tutu.myblbl.core.common.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeListViewModel(
    private val userRepository: UserRepository,
    private val feedPrewarmer: com.tutu.myblbl.repository.PersonalFeedPrewarmer
) : ViewModel() {
    private var lastLoadedAt = 0L

    private val _uiState = MutableStateFlow(MeListUiState())
    val uiState: StateFlow<MeListUiState> = _uiState.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private var historyCursorViewAt: Long = 0
    private var lastHistoryPayloadSignature = ""
    private var lastLaterPayloadSignature = ""
    private var loadJob: Job? = null
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
                _error.value = "该功能需要登录后才可以使用"
                finishRequest(requestId)
                return@launch
            }

            if (page == 1) {
                historyCursorViewAt = 0
            }

            val t0 = System.currentTimeMillis()
            AppLog.d("MePerf", "loadHistory: 开始请求, page=$page, viewAt=$historyCursorViewAt")

            try {
                // 预取命中时直接用内存结果；注意先求值成完整的 Result 再链 onSuccess，
                // 否则 ?: 的优先级会让命中分支跳过整个成功处理链。
                val response = (if (page == 1) feedPrewarmer.takeHistory() else null)
                    ?.let { Result.success(it) }
                    ?: userRepository.getHistory(historyCursorViewAt, pageSize)
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
                            _hasMore.value = rawList.size >= pageSize
                            lastLoadedAt = System.currentTimeMillis()
                            return@onSuccess
                        }
                        lastHistoryPayloadSignature = signature
                        _uiState.value = _uiState.value.copy(historyVideos = merged)
                        historyCursorViewAt = response.data.cursor?.viewAt
                            ?: rawList.lastOrNull()?.viewAt
                            ?: 0L
                        _hasMore.value = rawList.size >= pageSize
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
                _error.value = "该功能需要登录后才可以使用"
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

    private fun videoListSignature(items: List<VideoModel>): String {
        if (items.isEmpty()) return "empty"
        return items.joinToString(separator = "|") { item ->
            val key = when {
                item.bvid.isNotBlank() -> "bvid:${item.bvid}"
                item.aid > 0L -> "aid:${item.aid}"
                else -> "title:${item.title}|cover:${item.coverUrl}"
            }
            "$key:${item.title.hashCode()}:${item.viewCount}:${item.danmakuCount}:${item.historyProgress}:${item.coverUrl.hashCode()}"
        }
    }
}
