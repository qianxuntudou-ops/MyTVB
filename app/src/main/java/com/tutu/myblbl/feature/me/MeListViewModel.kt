package com.tutu.myblbl.feature.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tutu.myblbl.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeListViewModel(
    private val userRepository: UserRepository
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

    init {
        // 订阅「稍后观看」番剧弹幕数补全完成事件：
        // 用 sourceList 引用判断当前 UI 显示的还是不是这次 enrich 对应的数据。
        // 一致才用 enrichedList 替换，否则丢弃（避免回写过期数据）。
        viewModelScope.launch {
            userRepository.laterWatchEnriched.collectLatest { enrichment ->
                if (_uiState.value.laterVideos === enrichment.sourceList) {
                    _uiState.value = _uiState.value.copy(
                        laterVideos = enrichment.enrichedList
                    )
                }
            }
        }
    }

    fun isLoggedIn(): Boolean = userRepository.isLoggedIn()

    fun loadHistory(page: Int, pageSize: Int) {
        viewModelScope.launch {
            if (_loading.value) {
                return@launch
            }
            _loading.value = true
            _error.value = null

            if (!userRepository.isLoggedIn()) {
                _uiState.value = _uiState.value.copy(historyVideos = emptyList())
                _hasMore.value = false
                _error.value = "该功能需要登录后才可以使用"
                _loading.value = false
                return@launch
            }

            if (page == 1) {
                historyCursorViewAt = 0
            }

            userRepository.getHistory(historyCursorViewAt, pageSize)
                .onSuccess { response ->
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
                    _error.value = exception.message
                }

            _loading.value = false
        }
    }

    fun loadLaterWatch() {
        viewModelScope.launch {
            if (_loading.value) return@launch
            _loading.value = true
            _error.value = null

            if (!userRepository.isLoggedIn()) {
                _uiState.value = _uiState.value.copy(laterVideos = emptyList())
                _hasMore.value = false
                _error.value = "该功能需要登录后才可以使用"
                _loading.value = false
                return@launch
            }

            userRepository.getLaterWatch()
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        _uiState.value = _uiState.value.copy(laterVideos = response.data.list)
                        _hasMore.value = false
                        lastLoadedAt = System.currentTimeMillis()
                    } else {
                        _error.value = response.errorMessage
                    }
                }
                .onFailure { exception ->
                    _error.value = exception.message
                }

            _loading.value = false
        }
    }

    fun removeLaterVideo(aid: Long, bvid: String) {
        val current = _uiState.value.laterVideos
        _uiState.value = _uiState.value.copy(
            laterVideos = current.filter {
                it.aid != aid || (bvid.isNotBlank() && it.bvid != bvid)
            }
        )
    }

    fun removeHistoryVideo(item: com.tutu.myblbl.model.video.HistoryVideoModel) {
        val key = historyItemKey(item)
        _uiState.value = _uiState.value.copy(
            historyVideos = _uiState.value.historyVideos.filter { historyItemKey(it) != key }
        )
    }

    fun removeHistoryVideosByUp(upName: String) {
        _uiState.value = _uiState.value.copy(
            historyVideos = _uiState.value.historyVideos.filter {
                !it.displayAuthorName.equals(upName, ignoreCase = true)
            }
        )
    }

    fun shouldRefresh(ttlMs: Long): Boolean {
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }

    private fun historyItemKey(item: com.tutu.myblbl.model.video.HistoryVideoModel): String {
        return when {
            item.bvid.isNotBlank() -> "bvid:${item.bvid}"
            (item.history?.oid ?: 0L) > 0L -> "aid:${item.history?.oid}"
            else -> "title:${item.title}|cover:${item.cover}"
        }
    }
}
