package com.mytvb.feature.series

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.databinding.FragmentSeriesDetailBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.episode.EpisodeModel
import com.mytvb.model.series.EpisodeProgressModel
import com.mytvb.model.series.EpisodesDetailModel
import com.mytvb.model.series.SeriesModel
import com.mytvb.model.series.SeriesUserState
import com.mytvb.model.video.VideoModel
import com.mytvb.ui.adapter.SeriesDetailContentAdapter
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.navigation.navigateBackFromUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.mytvb.core.ui.base.AppToast

class SeriesDetailFragment : Fragment() {

    private enum class FocusArea {
        BACK,
        HEADER_CONTINUE,
        HEADER_FOLLOW,
        CONTENT
    }

    private var _binding: FragmentSeriesDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SeriesDetailViewModel by viewModel()

    private lateinit var contentAdapter: SeriesDetailContentAdapter

    private var currentDetail: EpisodesDetailModel? = null
    private var currentFollowed: Boolean = false
    private var pendingPrimaryFocus = true
    private var lastFocusedArea = FocusArea.HEADER_CONTINUE
    private var lastContentStructureKey: String? = null
    private var lastRecommendKey: String? = null
    private var pendingHeaderFocusAnchorX: Int? = null
    private var pendingHeaderFocusRetries: Int = 0
    private var pendingContentFocusRow: Int? = null
    private var pendingContentFocusAnchorX: Int = 0
    private var pendingContentFocusRetries: Int = 0
    private val appEventHub: AppEventHub by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeriesDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        setupObservers()

        arguments?.let { args ->
            val seasonId = args.getLong(ARG_SEASON_ID, -1L)
            val epId = args.getLong(ARG_EP_ID, -1L)
            if (seasonId > 0 || epId > 0) {
                viewModel.loadSeriesDetail(seasonId, epId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.post {
            if (!isAdded || view == null || pendingPrimaryFocus) {
                return@post
            }
            restoreFocus()
        }
    }

    private fun setupViews() {
        contentAdapter = SeriesDetailContentAdapter(
            onToggleFollow = viewModel::toggleFollow,
            onContinueWatchClick = ::continueWatchEpisode,
            onEpisodeClick = ::playEpisode,
            onSeasonClick = ::openSeason,
            onSectionEpisodeClick = ::playSectionVideo,
            onContentFocused = {
                lastFocusedArea = FocusArea.CONTENT
            },
            onContentVerticalKey = ::handleContentVerticalKey
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = contentAdapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return
                }
                // 逐行上滚后的落焦优先：与 header pending 由同一按键产生，不会并存
                if (pendingContentFocusRow != null) {
                    if (isAdded && view != null) {
                        focusPendingContentRow()
                    }
                    return
                }
                val anchorX = pendingHeaderFocusAnchorX ?: return
                pendingHeaderFocusAnchorX = null
                val retries = pendingHeaderFocusRetries
                pendingHeaderFocusRetries = 0
                if (isAdded && view != null) {
                    focusNearestHeaderAction(anchorX, retries)
                }
            }
        })
        binding.buttonBack1.setOnClickListener {
            navigateBackFromUi()
        }
        binding.buttonBack1.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.BACK
            }
        }
        binding.buttonBack1.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> focusPrimaryAction()
                else -> false
            }
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesDetail.collect { detail ->
                    currentDetail = detail
                    renderContent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isFollowed.collect { followed ->
                    currentFollowed = followed
                    renderContent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendItems.collect { items ->
                    contentAdapter.setRecommendItems(items)
                    renderContent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { message ->
                    when (message) {
                        is SeriesDetailViewModel.UiMessage.Res -> {
                            AppToast.show(requireContext(), message.resId)
                        }

                        is SeriesDetailViewModel.UiMessage.Text -> {
                            AppToast.show(requireContext(), message.value)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (isHidden || !isVisible) {
                        return@collectLatest
                    }
                    if (event is AppEventHub.Event.EpisodePlaybackProgressUpdated) {
                        viewModel.updateEpisodeProgress(
                            epId = event.episodeId,
                            timeMs = event.progressMs / 1000,
                            epIndex = event.episodeIndex
                        )
                    }
                }
            }
        }
    }

    private fun renderContent() {
        val detail = currentDetail
        val contentStructureKey = detail?.contentStructureKey()
        val recommendKey = viewModel.recommendItems.value.joinToString(",") { it.seasonId.toString() }
        val structureChanged = contentStructureKey != lastContentStructureKey
            || recommendKey != lastRecommendKey
            || contentAdapter.itemCount == 0
        if (detail == null || structureChanged) {
            contentAdapter.submit(detail, currentFollowed)
            lastContentStructureKey = contentStructureKey
            lastRecommendKey = recommendKey
        } else {
            contentAdapter.updateHeader(detail, currentFollowed)
        }
        binding.recyclerView.post {
            bindHeaderFocusBridges()
            if (pendingPrimaryFocus && currentDetail != null) {
                focusPrimaryAction()
                pendingPrimaryFocus = false
            }
        }
    }

    private fun openSeason(series: SeriesModel) {
        val targetSeasonId = series.seasonId
        if (targetSeasonId <= 0) return
        if (targetSeasonId == currentDetail?.seasonId) {
            AppToast.show(requireContext(), R.string.series_already_open)
            return
        }
        pendingPrimaryFocus = true
        // 换季整页重建：取消未完成的滚动落焦，防止陈旧 pending 在下次滚动停止时误抢焦点
        pendingContentFocusRow = null
        viewModel.loadSeriesDetail(targetSeasonId, series.firstEp)
        binding.recyclerView.scrollToPosition(0)
    }

    private fun playEpisode(episode: EpisodeModel) {
        playEpisode(episode, 0L)
    }

    private fun continueWatchEpisode(episode: EpisodeModel, seekPositionMs: Long) {
        playEpisode(episode, seekPositionMs)
    }

    private fun playEpisode(episode: EpisodeModel, seekPositionMs: Long) {
        if ((episode.bvid.isNotBlank() || episode.id > 0L) && episode.cid > 0) {
            val playbackVideo = episode.toPlaybackVideoModel(currentDetail?.seasonId ?: 0L)
            val startEpisodeIndex = currentDetail?.episodes.orEmpty()
                .indexOfFirst { it.id == episode.id }
                .takeIf { it >= 0 }
                ?: -1
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = playbackVideo,
                seekPositionMs = seekPositionMs,
                startEpisodeIndex = startEpisodeIndex,
                forcePlayer = true
            )
        }
    }

    private fun playSectionVideo(video: VideoModel) {
        if ((video.bvid.isNotBlank() || video.epid > 0L) && video.cid > 0) {
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = video,
                playQueue = buildSectionPlayQueue(video),
                forcePlayer = true
            )
            return
        }
        // 「UP论道」等 section 只下发 aid（cid=0、bvid 空、epid 指向站外引用），
        // PGC 播放链路走不通：抹掉 PGC 身份按普通 UGC 稿件播放（issue#135）。
        if (video.aid > 0L) {
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = video.copy(epid = 0L, sid = 0L),
                playQueue = buildSectionPlayQueue(video),
                forcePlayer = true
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun bindHeaderFocusBridges() {
        val continueButton = binding.recyclerView.findViewById<View>(R.id.button_continue_watch) ?: return
        val followButton = binding.recyclerView.findViewById<View>(R.id.button_follow_series)

        continueButton.nextFocusUpId = View.NO_ID
        followButton?.nextFocusUpId = View.NO_ID
        continueButton.nextFocusLeftId = R.id.button_back_1
        continueButton.nextFocusRightId = followButton?.id ?: View.NO_ID
        followButton?.nextFocusLeftId = R.id.button_continue_watch
        val revealHeaderListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.HEADER_CONTINUE
            }
        }
        continueButton.onFocusChangeListener = revealHeaderListener
        followButton?.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedArea = FocusArea.HEADER_FOLLOW
            }
        }

        continueButton.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> binding.buttonBack1.requestFocus()

                KeyEvent.KEYCODE_DPAD_DOWN ->
                    focusNearestTargetInRow(rowPosition = 1, anchorX = continueButton.centerXOnScreen())

                else -> false
            }
        }
        followButton?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    focusNearestTargetInRow(rowPosition = 1, anchorX = followButton.centerXOnScreen())

                else -> false
            }
        }
    }

    private fun focusPrimaryAction(smoothReveal: Boolean = false, retries: Int = 4): Boolean {
        val continueButton = binding.recyclerView.findViewById<View>(R.id.button_continue_watch)
        if (continueButton?.isShown == true && continueButton.isEnabled && continueButton.requestFocus()) {
            lastFocusedArea = FocusArea.HEADER_CONTINUE
            if (smoothReveal) {
                revealHeaderSmooth()
            }
            return true
        }
        val followButton = binding.recyclerView.findViewById<View>(R.id.button_follow_series)
        if (followButton?.isShown == true && followButton.requestFocus()) {
            lastFocusedArea = FocusArea.HEADER_FOLLOW
            if (smoothReveal) {
                revealHeaderSmooth()
            }
            return true
        }
        if (retries > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
            binding.recyclerView.post {
                if (isAdded && view != null) {
                    focusPrimaryAction(smoothReveal, retries - 1)
                }
            }
            return true
        }
        lastFocusedArea = FocusArea.BACK
        return binding.buttonBack1.requestFocus()
    }

    private fun focusFirstContentItem(
        preferStoredFocus: Boolean = true,
        retries: Int = 6
    ): Boolean {
        if (preferStoredFocus && contentAdapter.requestLastFocusedView(binding.recyclerView)) {
            lastFocusedArea = FocusArea.CONTENT
            return true
        }
        val rowHolder = binding.recyclerView.findViewHolderForAdapterPosition(1)
        val rowView = rowHolder?.itemView
        if (rowView != null) {
            val nestedRecycler = rowView.findViewById<RecyclerView>(R.id.recyclerView)
            if (nestedRecycler != null && nestedRecycler.adapter?.itemCount.orZero() > 0) {
                nestedRecycler.scrollToPosition(0)
                val firstChild = nestedRecycler.findViewHolderForAdapterPosition(0)?.itemView
                if (firstChild != null) {
                    lastFocusedArea = FocusArea.CONTENT
                    return firstChild.requestFocus()
                }
            }
        }
        if (retries > 0) {
            binding.recyclerView.post {
                focusFirstContentItem(preferStoredFocus = preferStoredFocus, retries = retries - 1)
            }
            return true
        }
        return false
    }

    private fun handleContentVerticalKey(source: View, keyCode: Int): Boolean {
        // 新的导航意图到达：取消上一轮未完成的滚动落焦，防止旧滚动停下后把焦点拽回去
        pendingContentFocusRow = null
        val currentRowPosition = findContainingRowPosition(source) ?: return false
        val anchorX = source.centerXOnScreen()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentRowPosition <= 1) {
                    focusNearestPrimaryHeaderAction(anchorX)
                } else {
                    focusNearestContentRow(
                        startRow = currentRowPosition - 1,
                        step = -1,
                        anchorX = anchorX
                    ) || focusRowByScrolling(currentRowPosition - 1, anchorX)
                        || focusNearestPrimaryHeaderAction(anchorX)
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusNearestContentRow(
                    startRow = currentRowPosition + 1,
                    step = 1,
                    anchorX = anchorX
                )
            }

            else -> false
        }
    }

    private fun focusNearestContentRow(startRow: Int, step: Int, anchorX: Int): Boolean {
        var row = startRow
        while (row in 1 until contentAdapter.itemCount) {
            if (focusNearestTargetInRow(row, anchorX)) {
                return true
            }
            row += step
        }
        return false
    }

    /**
     * 上方行都未进屏（ViewHolder 已被回收）时不直接跳顶：滚动到紧邻上一行，
     * 滚动停止（SCROLL_STATE_IDLE）后再落焦，保持「一层一层往上」的导航手感。
     * 直接跳顶会整页长滚，焦点行被滚出屏回收导致焦点丢失（globalFocusChange old=null），
     * 系统把焦点还给布局里第一个可聚焦的返回按钮，IDLE 后 header 落焦又把焦点
     * 拉到「继续观看」——用户看到的就是焦点先跳返回按钮再自动跳继续观看。
     */
    private fun focusRowByScrolling(targetRow: Int, anchorX: Int): Boolean {
        if (targetRow < 1) {
            return false
        }
        pendingContentFocusRow = targetRow
        pendingContentFocusAnchorX = anchorX
        pendingContentFocusRetries = CONTENT_FOCUS_SCROLL_RETRIES
        binding.recyclerView.smoothScrollToPosition(targetRow)
        return true
    }

    private fun focusPendingContentRow() {
        val row = pendingContentFocusRow ?: return
        pendingContentFocusRow = null
        val anchorX = pendingContentFocusAnchorX
        val retries = pendingContentFocusRetries
        pendingContentFocusRetries = 0
        when {
            // 滚动被打断/未达目标行：再滚一次（重试次数递减，防死循环）
            binding.recyclerView.findViewHolderForAdapterPosition(row) == null -> {
                if (retries > 0 && focusRowByScrolling(row, anchorX)) {
                    pendingContentFocusRetries = retries - 1
                }
            }

            // 行已进屏：直接落焦
            focusNearestTargetInRow(row, anchorX) -> Unit

            // 行进了屏但嵌套子项尚未布局完（childCount=0）：post 一帧再试
            retries > 0 -> binding.recyclerView.post {
                if (!isAdded || view == null) {
                    return@post
                }
                if (focusNearestTargetInRow(row, anchorX)) {
                    return@post
                }
                // 该行确实无可聚焦项：继续向上（已进屏行优先，不行再继续滚动）
                focusNearestContentRow(
                    startRow = row - 1,
                    step = -1,
                    anchorX = anchorX
                ) || focusRowByScrolling(row - 1, anchorX)
                    || focusNearestPrimaryHeaderAction(anchorX)
            }
        }
    }

    private fun focusNearestTargetInRow(rowPosition: Int, anchorX: Int): Boolean {
        val rowView = binding.recyclerView.findViewHolderForAdapterPosition(rowPosition)?.itemView ?: return false
        val candidates = buildList {
            rowView.findViewById<View>(R.id.button_order)
                ?.takeIf { it.isShown && it.isFocusable }
                ?.let(::add)
            rowView.findViewById<RecyclerView>(R.id.recyclerView)
                ?.visibleFocusableChildren()
                ?.let(::addAll)
        }
        val targetView = candidates
            .minByOrNull { child -> kotlin.math.abs(child.centerXOnScreen() - anchorX) }
            ?: return false
        lastFocusedArea = FocusArea.CONTENT
        return targetView.requestFocus()
    }

    private fun focusNearestHeaderAction(anchorX: Int, retries: Int = 4): Boolean {
        val headerTop = headerTopOffset()
        if (headerTop == null || headerTop < 0) {
            if (retries <= 0) {
                return false
            }
            pendingHeaderFocusAnchorX = anchorX
            pendingHeaderFocusRetries = retries - 1
            binding.recyclerView.smoothScrollToPosition(0)
            return true
        }
        val actionCandidates = listOfNotNull(
            binding.recyclerView.findViewById<View>(R.id.button_continue_watch)?.takeIf { it.isShown && it.isEnabled },
            binding.recyclerView.findViewById<View>(R.id.button_follow_series)?.takeIf { it.isShown }
        )
        val target = if (actionCandidates.isNotEmpty()) {
            actionCandidates.minByOrNull { candidate ->
                kotlin.math.abs(candidate.centerXOnScreen() - anchorX)
            }
        } else {
            binding.buttonBack1.takeIf { it.isShown }
        }
        if (target == null) {
            if (retries <= 0) {
                return false
            }
            pendingHeaderFocusAnchorX = anchorX
            pendingHeaderFocusRetries = retries - 1
            binding.recyclerView.smoothScrollToPosition(0)
            return true
        }
        lastFocusedArea = when (target.id) {
            R.id.button_follow_series -> FocusArea.HEADER_FOLLOW
            R.id.button_back_1 -> FocusArea.BACK
            else -> FocusArea.HEADER_CONTINUE
        }
        return target.requestFocus()
    }

    private fun focusNearestPrimaryHeaderAction(anchorX: Int, retries: Int = 4): Boolean {
        val headerTop = headerTopOffset()
        if (headerTop == null || headerTop < 0) {
            if (retries <= 0) {
                return false
            }
            pendingHeaderFocusAnchorX = anchorX
            pendingHeaderFocusRetries = retries - 1
            binding.recyclerView.smoothScrollToPosition(0)
            return true
        }
        val candidates = listOfNotNull(
            binding.recyclerView.findViewById<View>(R.id.button_continue_watch)?.takeIf { it.isShown && it.isEnabled },
            binding.recyclerView.findViewById<View>(R.id.button_follow_series)?.takeIf { it.isShown }
        )
        val target = candidates.minByOrNull { candidate ->
            kotlin.math.abs(candidate.centerXOnScreen() - anchorX)
        } ?: return focusNearestHeaderAction(anchorX, retries)
        lastFocusedArea = when (target.id) {
            R.id.button_follow_series -> FocusArea.HEADER_FOLLOW
            else -> FocusArea.HEADER_CONTINUE
        }
        return target.requestFocus()
    }

    private fun findContainingRowPosition(source: View): Int? {
        var current: View? = source
        while (current != null) {
            val parent = current.parent
            if (parent === binding.recyclerView) {
                val position = binding.recyclerView.getChildAdapterPosition(current)
                return position.takeIf { it != RecyclerView.NO_POSITION }
            }
            current = current.parent as? View
        }
        return null
    }

    private fun restoreFocus(): Boolean {
        return when (lastFocusedArea) {
            FocusArea.CONTENT -> focusFirstContentItem(preferStoredFocus = true)
            FocusArea.HEADER_FOLLOW -> {
                val followButton = binding.recyclerView.findViewById<View>(R.id.button_follow_series)
                followButton?.requestFocus() == true || focusPrimaryAction(smoothReveal = true)
            }

            FocusArea.BACK -> binding.buttonBack1.requestFocus() || focusPrimaryAction(smoothReveal = true)
            FocusArea.HEADER_CONTINUE -> focusPrimaryAction(smoothReveal = true)
        }
    }

    private fun revealHeaderSmooth() {
        val headerTop = headerTopOffset()
        if (headerTop == null) {
            binding.recyclerView.smoothScrollToPosition(0)
            return
        }
        if (headerTop < 0) {
            binding.recyclerView.smoothScrollBy(0, headerTop)
        }
    }

    private fun headerTopOffset(): Int? {
        val headerHolder = binding.recyclerView.findViewHolderForAdapterPosition(0) ?: return null
        return headerHolder.itemView.top
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun View.centerXOnScreen(): Int {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location[0] + width / 2
    }

    private fun RecyclerView.visibleFocusableChildren(): List<View> {
        return buildList {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child != null && child.isFocusable && child.visibility == View.VISIBLE) {
                    add(child)
                }
            }
        }
    }

    private fun buildSectionPlayQueue(current: VideoModel): List<VideoModel> {
        val sections = currentDetail?.section.orEmpty()
        val ownerSection = sections.firstOrNull { section ->
            section.episodes.any { candidate ->
                when {
                    current.epid > 0L && candidate.epid > 0L -> current.epid == candidate.epid
                    current.bvid.isNotBlank() && candidate.bvid.isNotBlank() -> current.bvid == candidate.bvid
                    current.aid > 0L && candidate.aid > 0L -> current.aid == candidate.aid
                    else -> current.cid > 0L && current.cid == candidate.cid
                }
            }
        } ?: return emptyList()
        return com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(ownerSection.episodes, current)
    }

    private fun EpisodeModel.toPlaybackVideoModel(seasonId: Long): VideoModel {
        return VideoModel(
            aid = aid,
            bvid = bvid,
            cid = cid,
            epid = id,
            sid = seasonId,
            title = longTitle.ifBlank { title },
            cover = cover
        )
    }

    companion object {
        const val ARG_SEASON_ID = "season_id"
        const val ARG_EP_ID = "ep_id"
        private const val CONTENT_FOCUS_SCROLL_RETRIES = 3

        fun newInstance(seasonId: Long, epId: Long = 0): SeriesDetailFragment {
            return SeriesDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SEASON_ID, seasonId)
                    putLong(ARG_EP_ID, epId)
                }
            }
        }
    }
}

private fun EpisodesDetailModel.contentStructureKey(): String {
    val episodePart = episodes.orEmpty().joinToString(",") { it.id.toString() }
    val sectionPart = section.orEmpty().joinToString("|") { sectionModel ->
        buildString {
            append(sectionModel.id)
            append(':')
            append(sectionModel.title)
            append(':')
            append(sectionModel.episodes.joinToString(",") { video ->
                when {
                    video.epid > 0L -> "ep${video.epid}"
                    video.bvid.isNotBlank() -> video.bvid
                    else -> "aid${video.aid}"
                }
            })
        }
    }
    val seasonPart = seasons.orEmpty().joinToString(",") { it.seasonId.toString() }
    return buildString {
        append(seasonId)
        append('#')
        append(type)
        append('#')
        append(episodePart)
        append('#')
        append(sectionPart)
        append('#')
        append(seasonPart)
    }
}
