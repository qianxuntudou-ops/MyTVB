package com.mytvb.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.model.search.HotWordModel
import com.mytvb.model.search.SearchAllCountWrapper
import com.mytvb.model.search.SearchAllResponseData
import com.mytvb.model.search.SearchCategoryItem
import com.mytvb.model.search.SearchItemModel
import com.mytvb.model.search.SearchType
import com.mytvb.model.search.SearchVideoOrder
import com.mytvb.repository.SearchRepository
import com.mytvb.core.common.log.AppLog
import com.mytvb.network.api.BiliApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SearchViewModel(
    private val searchRepository: SearchRepository
) : ViewModel() {

    data class SearchPageState(
        val items: List<SearchItemModel> = emptyList(),
        val page: Int = 0,
        val hasMore: Boolean = true,
        val loading: Boolean = false
    )

    private val _hotSearchWords = MutableStateFlow<List<HotWordModel>>(emptyList())
    val hotSearchWords: StateFlow<List<HotWordModel>> = _hotSearchWords.asStateFlow()

    private val _suggestWords = MutableStateFlow<List<HotWordModel>>(emptyList())
    val suggestWords: StateFlow<List<HotWordModel>> = _suggestWords.asStateFlow()

    private val _searchOverview = MutableStateFlow<SearchAllResponseData?>(null)
    val searchOverview: StateFlow<SearchAllResponseData?> = _searchOverview.asStateFlow()

    private val _searchCategories = MutableStateFlow<List<SearchCategoryItem>>(emptyList())
    val searchCategories: StateFlow<List<SearchCategoryItem>> = _searchCategories.asStateFlow()

    private val _searchPageStates = MutableStateFlow<Map<SearchType, SearchPageState>>(emptyMap())
    val searchPageStates: StateFlow<Map<SearchType, SearchPageState>> = _searchPageStates.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var searchAllJob: Job? = null
    private val pageJobs = mutableMapOf<SearchType, Job>()
    private var suggestJob: Job? = null
    private var activeKeyword: String = ""

    /** 搜索用户条目「关注」按钮状态缓存（mid → 是否已关注），会话级。 */
    private val relationStates = ConcurrentHashMap<Long, Boolean>()
    private val relationInFlight = ConcurrentHashMap<Long, Boolean>()

    fun cachedRelation(mid: Long): Boolean? = relationStates[mid]

    /** 从用户空间等页面返回时失效缓存（用户可能在那里变更了关注状态）。 */
    fun clearRelationCache() {
        relationStates.clear()
    }

    /**
     * 查询关注状态并回调；未登录/失败按「未关注」缓存，避免列表滚动重复请求。
     */
    fun queryRelation(mid: Long, onLoaded: (Boolean) -> Unit) {
        relationStates[mid]?.let(onLoaded)
        if (relationInFlight.putIfAbsent(mid, true) != null) return
        viewModelScope.launch {
            val followed = try {
                (BiliApi.relationAttribute(mid) ?: 0) > 0
            } catch (e: Exception) {
                AppLog.w("SearchViewModel", "queryRelation mid=$mid failed: ${e.message}")
                false
            }
            relationStates[mid] = followed
            relationInFlight.remove(mid)
            onLoaded(followed)
        }
    }

    fun loadHotSearch() {
        viewModelScope.launch {
            _hotSearchWords.value = searchRepository.loadHotSearchWords()
                .getOrDefault(emptyList())
        }
    }

    fun searchAll(keyword: String) {
        searchAllJob?.cancel()
        pageJobs.values.forEach(Job::cancel)
        pageJobs.clear()
        activeKeyword = keyword
        searchAllJob = viewModelScope.launch {
            val requestKeyword = keyword
            _loading.value = true
            _searchOverview.value = null
            _searchCategories.value = emptyList()
            _searchPageStates.value = emptyMap()

            val result = searchRepository.searchAll(keyword)
            if (activeKeyword != requestKeyword) {
                return@launch
            }

            result.fold(
                onSuccess = { overview ->
                    val categories = buildCategories(overview)
                    val pageStates = buildInitialPageStates(overview, categories)
                    _searchOverview.value = overview
                    _searchPageStates.value = pageStates
                    _searchCategories.value = categories
                },
                onFailure = {
                    AppLog.e("SearchVM", "searchAll failure: keyword=$requestKeyword", it)
                    _searchOverview.value = null
                    _searchCategories.value = emptyList()
                    _searchPageStates.value = emptyMap()
                }
            )

            _loading.value = false
        }
    }

    fun loadSearchPage(
        searchType: SearchType,
        keyword: String,
        pageSize: Int,
        order: SearchVideoOrder = SearchVideoOrder.TotalRank,
        forceRefresh: Boolean = false
    ) {
        if (keyword != activeKeyword) {
            return
        }
        val currentState = _searchPageStates.value[searchType] ?: SearchPageState()
        if (currentState.loading) {
            return
        }
        if (!forceRefresh && !currentState.hasMore && currentState.page > 0) {
            return
        }

        val nextPage = if (forceRefresh) 1 else currentState.page + 1
        updatePageState(searchType) {
            it.copy(
                loading = true,
                items = if (forceRefresh) emptyList() else it.items
            )
        }

        pageJobs[searchType]?.cancel()
        pageJobs[searchType] = viewModelScope.launch {
            val result = searchRepository.searchByType(
                searchType = searchType,
                keyword = keyword,
                page = nextPage,
                pageSize = pageSize,
                order = order
            )
            if (activeKeyword != keyword) {
                return@launch
            }

            result.fold(
                onSuccess = { response ->
                    val newItems = response.result.orEmpty()
                    updatePageState(searchType) { old ->
                        old.copy(
                            items = if (nextPage == 1) newItems else old.items + newItems,
                            page = nextPage,
                            hasMore = response.numPages > nextPage || newItems.size >= pageSize,
                            loading = false
                        )
                    }
                },
                onFailure = {
                    updatePageState(searchType) { old ->
                        if (nextPage == 1) {
                            old.copy(
                                items = emptyList(),
                                page = 0,
                                hasMore = true,
                                loading = false
                            )
                        } else {
                            old.copy(
                                page = old.page,
                                hasMore = old.hasMore,
                                loading = false
                            )
                        }
                    }
                }
            )
            pageJobs.remove(searchType)
        }
    }

    fun searchSuggest(keyword: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            if (keyword.isEmpty()) {
                _suggestWords.value = emptyList()
                return@launch
            }

            _suggestWords.value = searchRepository.searchSuggest(keyword)
                .getOrDefault(emptyList())
        }
    }

    private fun buildCategories(overview: SearchAllResponseData): List<SearchCategoryItem> {
        val pageInfo = overview.pageinfo ?: return emptyList()
        val categories = mutableListOf<SearchCategoryItem>()

        fun addIfNeeded(type: SearchType, count: Int) {
            if (count > 0) {
                categories += SearchCategoryItem(type, resolveTitle(type))
            }
        }

        addIfNeeded(SearchType.Video, pageInfo.video?.numResults ?: 0)
        addIfNeeded(SearchType.Animation, pageInfo.mediaBangumi?.numResults ?: 0)
        addIfNeeded(SearchType.FilmAndTv, pageInfo.mediaFt?.numResults ?: 0)
        addIfNeeded(SearchType.LiveRoom, pageInfo.liveRoom?.numResults ?: 0)
        addIfNeeded(SearchType.User, pageInfo.biliUser?.numResults ?: 0)

        return categories
    }

    private fun buildInitialPageStates(
        overview: SearchAllResponseData,
        categories: List<SearchCategoryItem>
    ): Map<SearchType, SearchPageState> {
        val pageInfo = overview.pageinfo
        val groupedResults = overview.result
            .orEmpty()
            .mapNotNull { result ->
                SearchType.fromValue(result.resultType)?.let { type ->
                    type to result.data.orEmpty()
                }
            }
            .toMap()

        return categories.associate { category ->
            val items = groupedResults[category.type].orEmpty()
            category.type to SearchPageState(
                items = items,
                page = if (items.isNotEmpty()) 1 else 0,
                hasMore = hasMorePages(category.type, pageInfo, items.size),
                loading = false
            )
        }
    }

    private fun hasMorePages(
        type: SearchType,
        pageInfo: SearchAllCountWrapper?,
        itemCount: Int
    ): Boolean {
        val count = when (type) {
            SearchType.Video -> pageInfo?.video
            SearchType.Animation -> pageInfo?.mediaBangumi
            SearchType.FilmAndTv -> pageInfo?.mediaFt
            SearchType.LiveRoom -> pageInfo?.liveRoom
            SearchType.User -> pageInfo?.biliUser
        } ?: return itemCount > 0

        return count.pages > 1 || count.numResults > itemCount
    }

    private fun resolveTitle(type: SearchType): String {
        val resId = when (type) {
            SearchType.Video -> R.string.video
            SearchType.Animation -> R.string.animation
            SearchType.FilmAndTv -> R.string.film_and_television
            SearchType.LiveRoom -> R.string.live
            SearchType.User -> R.string.user
        }
        return MyBLBLApplication.instance.getString(resId)
    }

    private fun updatePageState(
        searchType: SearchType,
        updater: (SearchPageState) -> SearchPageState
    ) {
        val current = _searchPageStates.value[searchType] ?: SearchPageState()
        _searchPageStates.value = _searchPageStates.value.toMutableMap().apply {
            this[searchType] = updater(current)
        }
    }
}
