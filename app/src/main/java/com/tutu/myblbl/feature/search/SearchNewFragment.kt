package com.tutu.myblbl.feature.search

import android.content.Context
import android.text.Editable
import android.text.TextWatcher

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.tutu.myblbl.R
import com.tutu.myblbl.databinding.FragmentSearchNewBinding
import com.tutu.myblbl.model.search.HotWordModel
import com.tutu.myblbl.model.search.SearchCategoryItem
import com.tutu.myblbl.model.search.SearchType
import com.tutu.myblbl.model.search.SearchVideoOrder
import com.tutu.myblbl.ui.activity.LivePlayerActivity
import com.tutu.myblbl.core.common.content.ContentFilter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import com.tutu.myblbl.feature.detail.UserSpaceFragment
import com.tutu.myblbl.feature.series.SeriesDetailFragment
import com.tutu.myblbl.feature.search.view.KeyboardView
import com.tutu.myblbl.core.ui.focus.SpatialFocusNavigator
import com.tutu.myblbl.core.ui.system.ViewUtils
import com.tutu.myblbl.core.ui.tab.enableTouchNavigation
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.core.common.log.PagePerfLogger
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.tab.focusNearestTabTo
import com.tutu.myblbl.core.ui.tab.focusSelectedTab
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

@OptIn(FlowPreview::class)
class SearchNewFragment :
    BaseFragment<FragmentSearchNewBinding>(),
    MainTabFocusTarget,
    KeyboardView.KeySelectListener {

    private val viewModel: SearchViewModel by viewModel()
    private lateinit var hotSearchAdapter: HotSearchAdapter
    private lateinit var centerAdapter: SearchSuggestAdapter
    private var resultPagerAdapter: SearchResultPagerAdapter? = null
    private lateinit var historyStore: SearchHistoryStore
    private lateinit var focusCoordinator: SearchFocusCoordinator
    private var tabMediator: TabLayoutMediator? = null
    private var recentSearches: List<String> = emptyList()
    private var currentKeyword = ""
    private var latestSuggests: List<HotWordModel> = emptyList()
    private var pendingHistoryKeyword: String? = null
    private var suppressTextWatcher = false
    private var suggestDebounceJob: Job? = null
    private val pageSize = 20
    private var currentOrder = SearchVideoOrder.TotalRank
    private var isResultPanelVisible = false
    private var pendingResultFocus = false
    private var pendingOpenKeyword: String? = null
    private var searchOpenStartMs = 0L
    private var searchResultStartMs = 0L
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateOrderButtonVisibility(position)
            maybeLoadPage(position, forceRefresh = false)
        }
    }

    companion object {
        private const val ARG_KEYWORD = "keyword"

        fun newInstance(): SearchNewFragment = SearchNewFragment()

        fun newInstance(keyword: String): SearchNewFragment {
            return SearchNewFragment().apply {
                arguments = bundleOf(ARG_KEYWORD to keyword)
            }
        }
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentSearchNewBinding {
        return FragmentSearchNewBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        searchOpenStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("Search", "initView_start")
        historyStore = SearchHistoryStore(requireContext())
        focusCoordinator = SearchFocusCoordinator(
            searchPanelRoots = {
                listOf(
                    binding.viewKeyboard,
                    binding.recyclerViewCenter,
                    binding.recyclerViewEnd
                )
            },
            resultPanelRoot = { binding.viewSearchResult }
        )
        setupInput()
        setupKeyboard()
        setupKeywordColumns()
        setupResultHeader()
        setupOrderButton()
        registerFocusTracking()
        updateOrderText()
        showSearchPanel()
        binding.root.post {
            if (isAdded && view != null) {
                PagePerfLogger.mark("Search", "input_panel_ready", searchOpenStartMs)
            }
        }
    }

    override fun initData() {
        loadRecentSearches()
        viewModel.loadHotSearch()
        pendingOpenKeyword = arguments?.getString(ARG_KEYWORD)?.trim()?.takeIf { it.isNotEmpty() }
        submitPendingOpenKeyword()
    }

    override fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hotSearchWords.collectLatest { words ->
                    hotSearchAdapter.setData(words)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.suggestWords.collectLatest { suggests ->
                    latestSuggests = suggests
                    syncCenterColumn()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchOverview.collectLatest { overview ->
                    val keyword = pendingHistoryKeyword
                    if (overview != null && !keyword.isNullOrBlank() && keyword == currentKeyword) {
                        saveRecentSearch(keyword)
                        pendingHistoryKeyword = null
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchCategories.collectLatest { categories ->
                    applySearchCategories(categories)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchPageStates.collectLatest { states ->
                    val adapter = resultPagerAdapter ?: return@collectLatest
                    states.forEach { (type, state) ->
                        adapter.submitState(type, state.items, state.loading, state.hasMore)
                    }
                    maybeResolvePendingResultFocus()
                }
            }
        }
    }

    private fun setupInput() {
        binding.editText.showSoftInputOnFocus = false

        binding.editText.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> mainActivity?.focusLeftFunctionArea(view) == true
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isResultPanelVisible) {
                        focusResultHeader(view) || focusCurrentResultContent(view)
                    } else {
                        focusCenterColumn(view) ||
                            focusHotColumn(view) ||
                            binding.viewKeyboard.requestPrimaryFocus()
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isResultPanelVisible) {
                        focusResultHeader(view)
                    } else {
                        focusCenterColumn(view) ||
                            focusHotColumn(view) ||
                            binding.viewKeyboard.requestPrimaryFocus()
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }

        binding.editText.setOnEditorActionListener { _: TextView, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.editText.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressTextWatcher) {
                    return
                }
                val text = s?.toString().orEmpty()
                val requestSuggest = !isResultPanelVisible
                suggestDebounceJob?.cancel()
                suggestDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    handleInputChanged(text = text, requestSuggest = requestSuggest)
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupKeyboard() {
        binding.viewKeyboard.setKeySelectListener(this)
        binding.viewKeyboard.setDispatchKeyDel(true)
        binding.viewKeyboard.post {
            if (isAdded) {
                binding.viewKeyboard.requestPrimaryFocus()
            }
        }
    }

    private fun setupKeywordColumns() {
        centerAdapter = SearchSuggestAdapter(
            onItemClick = { keyword -> performSearch(keyword) },
            onClearHistory = ::clearSearchHistory,
            onLeftEdge = ::focusKeyboardFromSearchColumn,
            onRightEdge = ::focusHotSearchColumn
        )
        hotSearchAdapter = HotSearchAdapter(
            onItemClick = { keyword -> performSearch(keyword) },
            onLeftEdge = ::focusLeftColumnFromHotSearch
        )

        binding.recyclerViewCenter.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewCenter.adapter = centerAdapter

        binding.recyclerViewEnd.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewEnd.adapter = hotSearchAdapter
    }

    private fun ensureSearchResultSetup(): SearchResultPagerAdapter {
        resultPagerAdapter?.let { return it }

        val setupStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("Search", "result_setup_start")
        val adapter = SearchResultPagerAdapter(
            onItemClick = ::onResultItemClick,
            onLoadMore = ::onResultPageLoadMore,
            onTopEdgeUp = ::focusResultHeader
        )
        resultPagerAdapter = adapter
        adapter.setPages(emptyList())
        binding.viewPagerResult.adapter = adapter
        binding.viewPagerResult.offscreenPageLimit = 2
        binding.viewPagerResult.registerOnPageChangeCallback(pageChangeCallback)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabSearchResult, binding.viewPagerResult) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }
        tabMediator?.attach()
        binding.tabSearchResult.enableTouchNavigation(
            binding.viewPagerResult,
            tabFocusable = true,
            onNavigateDown = { focusCurrentResultContent() },
            onNavigateRight = ::focusOrderButton
        )
        PagePerfLogger.mark("Search", "result_setup_end", setupStartMs)
        return adapter
    }

    private fun setupResultHeader() {
        binding.buttonResultBack.apply {
            val goBack = {
                if (arguments?.containsKey(ARG_KEYWORD) == true) {
                    navigateBackFromUi()
                } else {
                    showSearchPanel()
                }
            }
            setOnClickListener { goBack() }
            setOnKeyListener { view, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> mainActivity?.focusLeftFunctionArea(view) == true
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusResultPrimaryActions(view) || focusCurrentResultContent(view)
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        goBack()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun setupOrderButton() {
        binding.buttonOrder.setOnClickListener { showOrderDialog() }
        binding.buttonOrder.setOnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> focusCurrentResultContent(view)
                KeyEvent.KEYCODE_DPAD_LEFT -> binding.tabSearchResult.focusNearestTabTo(view)
                else -> false
            }
        }
    }

    private fun showOrderDialog() {
        val orders = SearchVideoOrder.values()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.video_order)
            .setItems(orders.map { it.showName }.toTypedArray()) { _, which ->
                currentOrder = orders[which]
                updateOrderText()
                if (currentKeyword.isNotBlank()) {
                    performSearch(currentKeyword)
                }
            }
            .show()
        activity?.let { ViewUtils.hideSystemBars(it) }
    }

    private fun updateOrderText() {
        binding.textOrder.text = currentOrder.showName
    }

    private fun updateOrderButtonVisibility(position: Int = binding.viewPagerResult.currentItem) {
        val adapter = resultPagerAdapter ?: run {
            binding.buttonOrder.visibility = View.GONE
            return
        }
        val isVideoTab = adapter.getPageType(position) == SearchType.Video
        binding.buttonOrder.visibility = if (isVideoTab) View.VISIBLE else View.GONE
        if (!isVideoTab && binding.buttonOrder.hasFocus()) {
            binding.tabSearchResult.focusSelectedTab()
        }
    }

    private fun performSearch(keyword: String) {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) {
            Toast.makeText(requireContext(), R.string.empty_keyword, Toast.LENGTH_SHORT).show()
            return
        }

        currentKeyword = normalized
        searchResultStartMs = PagePerfLogger.now()
        PagePerfLogger.markNow("Search", "perform_search", "keywordLen=${normalized.length}")
        pendingHistoryKeyword = null
        pendingResultFocus = true
        updateEditText(normalized, triggerSuggest = false)
        binding.textResultKeyword.text = normalized
        val adapter = ensureSearchResultSetup()
        showResultPanel()
        PagePerfLogger.mark("Search", "result_panel_visible", searchResultStartMs)
        adapter.clearResults()

        if (ContentFilter.isSearchKeywordBlocked(requireContext(), normalized)) {
            adapter.setPages(emptyList())
            binding.tabSearchResult.isVisible = false
            binding.viewPagerResult.isVisible = false
            binding.textSearchEmpty.isVisible = true
            return
        }

        pendingHistoryKeyword = normalized
        viewModel.searchAll(normalized)
    }

    private fun onResultItemClick(entry: SearchResultEntry) {
        val item = entry.item
        when (entry.pageType) {
            SearchType.Video -> {
                if (item.bvid.isNotBlank() || item.aid > 0) {
                    VideoRouteNavigator.openVideo(
                        context = requireContext(),
                        video = com.tutu.myblbl.model.video.VideoModel(
                            aid = item.aid,
                            bvid = item.bvid,
                            title = item.title,
                            pic = item.pic.ifBlank { item.cover },
                            cover = item.cover,
                            pubDate = item.pubDate
                        )
                    )
                }
            }

            SearchType.Animation,
            SearchType.FilmAndTv -> {
                val seasonId = item.pgcSeasonId.takeIf { it > 0 } ?: item.mediaId
                if (seasonId > 0) {
                    openInHostContainer(SeriesDetailFragment.newInstance(seasonId))
                }
            }

            SearchType.LiveRoom -> {
                if (item.roomId > 0) {
                    LivePlayerActivity.start(requireContext(), item.roomId)
                }
            }

            SearchType.User -> {
                val mid = item.mid.toLongOrNull() ?: 0L
                if (mid > 0) {
                    openInHostContainer(UserSpaceFragment.newInstance(mid))
                }
            }
        }
    }

    private fun onResultPageLoadMore(type: SearchType) {
        if (currentKeyword.isBlank()) {
            return
        }
        viewModel.loadSearchPage(type, currentKeyword, pageSize, currentOrder, forceRefresh = false)
    }

    private fun showResultPanel() {
        isResultPanelVisible = true
        pendingResultFocus = true
        binding.viewSearchResult.isVisible = true
        binding.layoutSearchInput.isVisible = false
        binding.viewKeyboard.isVisible = false
        binding.recyclerViewCenter.isVisible = false
        binding.recyclerViewEnd.isVisible = false
        binding.textSearchEmpty.isVisible = false
    }

    private fun showSearchPanel() {
        isResultPanelVisible = false
        binding.viewSearchResult.isVisible = false
        binding.layoutSearchInput.isVisible = true
        binding.viewKeyboard.isVisible = true
        binding.recyclerViewEnd.isVisible = true
        binding.textSearchEmpty.isVisible = false
        handleInputChanged(binding.editText.text?.toString().orEmpty(), requestSuggest = true)
        syncCenterColumn()
        binding.viewKeyboard.post {
            if (isAdded && binding.viewKeyboard.isVisible) {
                restoreSearchPanelFocus()
            }
        }
    }

    private fun syncCenterColumn() {
        if (isResultPanelVisible) {
            return
        }

        val input = binding.editText.text?.toString().orEmpty().trim()
        if (input.isNotEmpty() && latestSuggests.isNotEmpty()) {
            centerAdapter.setData(latestSuggests)
            binding.recyclerViewCenter.isVisible = true
        } else {
            val historyModels = buildList {
                if (recentSearches.isNotEmpty()) {
                    add(HotWordModel.createClearHistory())
                }
                recentSearches.mapIndexedTo(this) { index, keyword ->
                    HotWordModel.createHistory(keyword, index)
                }
            }
            centerAdapter.setData(historyModels)
            binding.recyclerViewCenter.isVisible = historyModels.isNotEmpty()
        }
    }

    override fun onInsertText(text: String) {
        val current = binding.editText.text?.toString().orEmpty().trim()
        var base = current
        if (base == getString(R.string.search)) {
            base = ""
        }
        val next = base + text
        updateEditText(next, triggerSuggest = true)
    }

    override fun onDelete() {
        val current = binding.editText.text?.toString().orEmpty().trim()
        if (current.isEmpty()) {
            return
        }
        val next = current.dropLast(1)
        updateEditText(next, triggerSuggest = true)
        if (next.isEmpty()) {
            showSearchPanel()
        }
    }

    override fun onClear() {
        updateEditText("", triggerSuggest = false)
        currentKeyword = ""
        showSearchPanel()
    }

    override fun onSearch() {
        performSearch(binding.editText.text?.toString().orEmpty())
    }

    fun openKeyword(keyword: String) {
        pendingOpenKeyword = keyword.trim().takeIf { it.isNotEmpty() }
        if (view != null) {
            submitPendingOpenKeyword()
        }
    }

    fun onBackPressed(): Boolean {
        if (isResultPanelVisible) {
            if (arguments?.containsKey(ARG_KEYWORD) == true) {
                return false
            }
            showSearchPanel()
            return true
        }
        return false
    }

    private fun loadRecentSearches() {
        recentSearches = historyStore.load()
        syncCenterColumn()
    }

    private fun saveRecentSearch(keyword: String) {
        recentSearches = historyStore.save(keyword, recentSearches)
        syncCenterColumn()
    }

    private fun clearSearchHistory() {
        historyStore.clear()
        recentSearches = emptyList()
        syncCenterColumn()
    }

    override fun onDestroyView() {
        focusCoordinator.unregister(view)
        tabMediator?.detach()
        tabMediator = null
        if (resultPagerAdapter != null) {
            binding.viewPagerResult.unregisterOnPageChangeCallback(pageChangeCallback)
            binding.viewPagerResult.adapter = null
            resultPagerAdapter = null
        }
        super.onDestroyView()
    }

    private fun applySearchCategories(categories: List<SearchCategoryItem>) {
        if (currentKeyword.isBlank() && resultPagerAdapter == null) {
            return
        }
        val adapter = ensureSearchResultSetup()
        val pageStates = viewModel.searchPageStates.value
        val initialItems = pageStates.mapValues { (_, state) -> state.items }
        val initialLoading = pageStates.mapValues { (_, state) -> state.loading }
        val initialHasMore = pageStates.mapValues { (_, state) -> state.hasMore }
        adapter.setPages(categories, initialItems, initialLoading, initialHasMore)
        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabSearchResult, binding.viewPagerResult) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }
        tabMediator?.attach()
        binding.tabSearchResult.enableTouchNavigation(
            binding.viewPagerResult,
            tabFocusable = true,
            onNavigateDown = { focusCurrentResultContent() },
            onNavigateRight = ::focusOrderButton
        )
        viewModel.searchPageStates.value.forEach { (type, state) ->
            adapter.submitState(type, state.items, state.loading, state.hasMore)
        }
        val hasCategories = categories.isNotEmpty()
        binding.tabSearchResult.isVisible = hasCategories
        binding.viewPagerResult.isVisible = hasCategories
        val searchCompleted = viewModel.searchOverview.value != null
        binding.textSearchEmpty.isVisible = !hasCategories && searchCompleted
        PagePerfLogger.mark(
            "Search",
            "categories_applied",
            searchResultStartMs,
            "categories=${categories.size} completed=$searchCompleted"
        )

        if (hasCategories) {
            binding.viewPagerResult.setCurrentItem(0, false)
            updateOrderButtonVisibility(0)
            val firstType = categories.first().type
            val state = viewModel.searchPageStates.value[firstType]
            if (state == null || state.items.isEmpty()) {
                viewModel.loadSearchPage(firstType, currentKeyword, pageSize, currentOrder, forceRefresh = false)
            }
        } else {
            binding.buttonOrder.visibility = View.GONE
        }
        maybeResolvePendingResultFocus()
    }

    private fun maybeLoadPage(position: Int, forceRefresh: Boolean) {
        if (currentKeyword.isBlank()) {
            return
        }
        val adapter = resultPagerAdapter ?: return
        val type = adapter.getPageType(position) ?: return
        val state = viewModel.searchPageStates.value[type]
        if (forceRefresh || state == null || state.items.isEmpty()) {
            viewModel.loadSearchPage(type, currentKeyword, pageSize, currentOrder, forceRefresh)
        }
    }

    private fun openInHostContainer(fragment: Fragment) {
        mainActivity?.openInHostContainer(fragment)
    }

    private fun submitPendingOpenKeyword() {
        val keyword = pendingOpenKeyword ?: return
        pendingOpenKeyword = null
        performSearch(keyword)
    }

    private fun updateEditText(text: String, triggerSuggest: Boolean) {
        suppressTextWatcher = true
        binding.editText.setText(text)
        binding.editText.setSelection(text.length)
        suppressTextWatcher = false
        handleInputChanged(text = text, requestSuggest = triggerSuggest && !isResultPanelVisible)
    }

    private fun handleInputChanged(text: String, requestSuggest: Boolean) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            latestSuggests = emptyList()
            syncCenterColumn()
            return
        }

        if (!requestSuggest) {
            syncCenterColumn()
            return
        }

        viewModel.searchSuggest(normalized)
    }

    private fun registerFocusTracking() {
        focusCoordinator.register(binding.root)
    }

    private fun restoreSearchPanelFocus(anchorView: View? = null): Boolean {
        return focusCoordinator.restoreSearchPanelFocus(
            anchorView = anchorView,
            focusCenterColumn = ::focusCenterColumn,
            focusHotColumn = ::focusHotColumn,
            focusKeyboard = binding.viewKeyboard::requestPrimaryFocus
        )
    }

    private fun maybeResolvePendingResultFocus() {
        if (
            focusCoordinator.resolvePendingResultFocus(
                isResultPanelVisible = isResultPanelVisible,
                pendingResultFocus = pendingResultFocus,
                focusCurrentResultContent = ::focusCurrentResultContent,
                focusResultHeader = ::focusResultHeader,
                currentResultPageHasItems = ::currentResultPageHasItems
            )
        ) {
            pendingResultFocus = false
        }
    }

    private fun restoreResultPanelFocus(anchorView: View? = null): Boolean {
        return focusCoordinator.restoreResultPanelFocus(
            anchorView = anchorView,
            focusCurrentResultContent = ::focusCurrentResultContent,
            focusResultHeader = ::focusResultHeader
        )
    }

    override fun focusEntryFromMainTab(): Boolean {
        return focusEntryFromMainTab(anchorView = null, preferSpatialEntry = false)
    }

    override fun focusEntryFromMainTab(anchorView: View?, preferSpatialEntry: Boolean): Boolean {
        return if (isResultPanelVisible) {
            restoreResultPanelFocus(anchorView)
        } else {
            restoreSearchPanelFocus(anchorView)
        }
    }

    private fun focusCurrentResultContent(anchorView: View? = view?.findFocus()): Boolean {
        val adapter = resultPagerAdapter ?: return false
        return adapter.focusPrimaryContent(
            type = adapter.getPageType(binding.viewPagerResult.currentItem),
            anchorView = anchorView
        )
    }

    private fun focusResultHeader(anchorView: View? = view?.findFocus()): Boolean {
        val candidates = buildList {
            binding.buttonResultBack.takeIf { it.isVisible && it.isFocusable }?.let {
                add(it)
            }
            addAll(resultTabViews())
            if (binding.buttonOrder.isVisible && binding.buttonOrder.isFocusable) {
                add(binding.buttonOrder)
            }
        }
        return SpatialFocusNavigator.requestBestCandidate(
            anchorView = anchorView,
            candidates = candidates,
            direction = View.FOCUS_UP,
            fallback = {
                (binding.buttonResultBack.requestFocus()) ||
                    focusOrderButton() ||
                    binding.tabSearchResult.focusSelectedTab()
            }
        )
    }

    private fun focusResultPrimaryActions(anchorView: View? = view?.findFocus()): Boolean {
        val candidates = buildList {
            addAll(resultTabViews())
            if (binding.buttonOrder.isVisible && binding.buttonOrder.isFocusable) {
                add(binding.buttonOrder)
            }
        }
        return SpatialFocusNavigator.requestBestCandidate(
            anchorView = anchorView,
            candidates = candidates,
            direction = View.FOCUS_RIGHT,
            fallback = {
                focusOrderButton() || binding.tabSearchResult.focusSelectedTab()
            }
        )
    }

    private fun focusOrderButton(): Boolean {
        return if (binding.buttonOrder.isVisible && binding.buttonOrder.isFocusable) {
            binding.buttonOrder.requestFocus()
        } else {
            false
        }
    }

    private fun focusKeyboardFromSearchColumn(anchorView: View): Boolean {
        return SpatialFocusNavigator.requestBestDescendant(
            anchorView = anchorView,
            root = binding.viewKeyboard,
            direction = View.FOCUS_LEFT,
            fallback = { binding.viewKeyboard.requestPrimaryFocus() }
        )
    }

    private fun focusHotSearchColumn(anchorView: View): Boolean {
        return focusHotColumn(anchorView)
    }

    private fun focusLeftColumnFromHotSearch(anchorView: View): Boolean {
        return focusCenterColumn(anchorView) || focusKeyboardFromSearchColumn(anchorView)
    }

    private fun focusCenterColumn(anchorView: View?): Boolean {
        if (!binding.recyclerViewCenter.isVisible) {
            return false
        }
        return focusRecycler(binding.recyclerViewCenter, anchorView, View.FOCUS_LEFT)
    }

    private fun focusHotColumn(anchorView: View?): Boolean {
        if (!binding.recyclerViewEnd.isVisible) {
            return false
        }
        return focusRecycler(binding.recyclerViewEnd, anchorView, View.FOCUS_RIGHT)
    }

    private fun focusRecycler(
        recyclerView: RecyclerView,
        anchorView: View?,
        direction: Int
    ): Boolean {
        return SpatialFocusNavigator.requestBestDescendant(
            anchorView = anchorView,
            root = recyclerView,
            direction = direction,
            fallback = {
                recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true ||
                    recyclerView.getChildAt(0)?.requestFocus() == true
            }
        )
    }

    private fun resultTabViews(): List<View> {
        val tabStrip = binding.tabSearchResult.getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until tabStrip.childCount)
            .mapNotNull(tabStrip::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.isFocusable }
    }

    private fun currentResultPageHasItems(): Boolean {
        val adapter = resultPagerAdapter ?: return false
        val type = adapter.getPageType(binding.viewPagerResult.currentItem) ?: return false
        return viewModel.searchPageStates.value[type]?.items?.isNotEmpty() == true
    }
}
