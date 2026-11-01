@file:Suppress("SpellCheckingInspection")

package com.mytvb.feature.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.mytvb.core.ui.base.AppToast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.mytvb.R
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.common.time.TimeUtils
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.databinding.FragmentSeriesDetailBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.VideoPvModel
import com.mytvb.model.video.detail.Tag
import com.mytvb.model.video.detail.UgcEpisode
import com.mytvb.model.video.detail.UgcSeason
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.model.video.detail.VideoView
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.FavoriteRepository
import com.mytvb.repository.UserRepository
import com.mytvb.repository.VideoRepository
import com.mytvb.ui.activity.MainActivity
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.ui.adapter.VideoDetailContentAdapter
import com.mytvb.ui.dialog.OwnerDetailDialog
import com.mytvb.ui.dialog.PlayerActionDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.feature.player.comment.CommentImageViewerDialog
import com.mytvb.feature.player.comment.CommentNoteImageRepository
import com.mytvb.feature.player.comment.CommentPanelDialog
import com.mytvb.feature.player.comment.VideoCommentItem
import com.mytvb.feature.player.comment.VideoCommentPicture
import com.mytvb.feature.player.comment.VideoCommentRootPagingState
import com.mytvb.feature.player.comment.VideoCommentDataSource
import com.mytvb.feature.player.comment.VIDEO_COMMENT_SORT_HOT
import com.mytvb.feature.player.comment.VIDEO_COMMENT_SORT_NEW
import com.mytvb.network.api.ApiService

class VideoDetailFragment : androidx.fragment.app.Fragment() {

    private val TAG = "VideoDetail"

    companion object {
        private const val ARG_AID = "aid"
        private const val ARG_BVID = "bvid"
        private const val ARG_PLAY_QUEUE_TOKEN = "play_queue_token"
        /** 评论触底加载阈值：距最后一条评论 N 条时预取下一页。 */
        private const val COMMENT_LOAD_MORE_THRESHOLD = 4

        fun newInstance(video: VideoModel, playQueue: List<VideoModel> = emptyList()): VideoDetailFragment {
            val queueToken = VideoDetailPlayQueueStore.enqueue(playQueue)
            return VideoDetailFragment().apply {
                arguments = bundleOf(
                    ARG_AID to video.aid.takeIf { it > 0L },
                    ARG_BVID to video.bvid.takeIf { it.isNotBlank() },
                    ARG_PLAY_QUEUE_TOKEN to queueToken
                )
            }
        }

        fun newInstance(aid: Long): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(ARG_AID to aid)
            }
        }

        fun newInstance(bvid: String): VideoDetailFragment {
            return VideoDetailFragment().apply {
                arguments = bundleOf(ARG_BVID to bvid)
            }
        }
    }

    private var _binding: FragmentSeriesDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var contentAdapter: VideoDetailContentAdapter
    private var mainActivity: MainActivity? = null

    private var videoModel: VideoModel? = null
    private var videoView: VideoView? = null
    private var currentDetail: VideoDetailModel? = null
    private var aid: Long? = null
    private var bvid: String? = null
    private var launchPlayQueue: List<VideoModel> = emptyList()

    private val appEventHub: AppEventHub by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val userRepository: UserRepository by inject()
    private val appSettings: AppSettingsDataStore by inject()

    private var isLiked = false
    private var isCoined = false
    private var isFavorited = false

    private var ugcEpisodes: List<UgcEpisode> = emptyList()
    private var ugcReverseOrder = false

    private var pendingFocusAid: Long = 0

    private enum class PlaybackSource { NONE, PLAY_BUTTON, UGC_CARD }
    private var lastPlaybackSource = PlaybackSource.NONE

    private var actionDialog: PlayerActionDialog? = null
    private var ownerDetailDialog: OwnerDetailDialog? = null

    // ---------- 内容 Tab / 评论区（issue#108：详情页直接看评论） ----------
    private enum class DetailTab { CONTENT, COMMENT }

    private var selectedTab = DetailTab.CONTENT
    private var commentSortNew = false
    private val commentsState = VideoCommentRootPagingState()
    private val expandedCommentRpids = mutableSetOf<Long>()
    private var commentsFetchJob: kotlinx.coroutines.Job? = null
    private var commentsFetchToken = 0
    /** Tab 栏"评论 N"数字：初始取 stat.reply，评论接口落地后校准为 all_count。 */
    private var commentCountOverride = -1L
    private var commentImageViewer: CommentImageViewerDialog? = null
    private var commentPanelDialog: CommentPanelDialog? = null

    private val apiService: ApiService by inject()

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        if (context is MainActivity) {
            mainActivity = context
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            aid = if (args.containsKey(ARG_AID)) args.getLong(ARG_AID) else null
            bvid = if (args.containsKey(ARG_BVID)) args.getString(ARG_BVID) else null
            launchPlayQueue = VideoDetailPlayQueueStore.consume(args.getString(ARG_PLAY_QUEUE_TOKEN))
        }
    }

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
        initObserver()
        loadVideoDetail()
    }

    override fun onResume() {
        super.onResume()
        val source = lastPlaybackSource
        if (source == PlaybackSource.NONE) return
        lastPlaybackSource = PlaybackSource.NONE

        val recyclerView = _binding?.recyclerView ?: return
        recyclerView.postDelayed({
            if (!canTouchView()) return@postDelayed
            when (source) {
                PlaybackSource.PLAY_BUTTON -> focusPlayButton()
                PlaybackSource.UGC_CARD -> {
                    val currentAid = videoView?.aid ?: videoModel?.aid ?: return@postDelayed
                    focusUgcCardByAid(currentAid)
                }
                else -> {}
            }
        }, 150)
    }

    private fun focusPlayButton() {
        val binding = _binding ?: return
        val header = binding.recyclerView.findViewHolderForAdapterPosition(0)?.itemView ?: return
        header.findViewById<View>(R.id.button_play)?.requestFocus()
    }

    private fun focusUgcCardByAid(aid: Long) {
        val binding = _binding ?: return
        val ugcRow = contentAdapter.currentList.indexOfFirst {
            it is VideoDetailContentAdapter.Row.UgcSeason
        }
        if (ugcRow < 0) return
        val laneHolder = binding.recyclerView.findViewHolderForAdapterPosition(ugcRow)
            as? VideoDetailContentAdapter.UgcSeasonLaneViewHolder ?: return
        val innerRv = laneHolder.innerRecyclerView
        val adapter = innerRv.adapter as? VideoAdapter ?: return
        for (i in 0 until innerRv.childCount) {
            val child = innerRv.getChildAt(i)
            val vh = innerRv.getChildViewHolder(child)
            val item = adapter.getItem(vh.bindingAdapterPosition)
            if (item?.aid == aid) {
                child.requestFocus()
                return
            }
        }
    }

    private fun setupViews() {
        binding.buttonBack1.setOnClickListener {
            navigateBackFromUi()
        }
        binding.buttonBack1.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                AppLog.d(TAG, "buttonBack1 focused: ${describeFocusView(view)}")
            }
        }

        contentAdapter = VideoDetailContentAdapter(
            onPlayClick = {
                lastPlaybackSource = PlaybackSource.PLAY_BUTTON
                playVideo()
            },
            onUploaderClick = { showOwnerDetailDialog() },
            onLikeClick = { toggleLike() },
            onCoinClick = { giveCoin() },
            onFavoriteClick = { toggleFavorite() },
            onTagClick = { tag -> onTagClicked(tag) },
            onPageClick = { model -> onPageVideoClicked(model) },
            onUgcEpisodeClick = { v, model -> onUgcEpisodeClicked(v, model) },
            onUgcOrderToggle = { toggleUgcOrder() },
            onRelatedVideoClick = { model -> onRelatedVideoClicked(model) },
            onDescriptionClick = { desc -> showDescriptionDialog(desc) },
            onBackFocus = {
                val before = binding.root.findFocus()
                val result = binding.buttonBack1.requestFocus()
                AppLog.d(TAG, "onBackFocus request: before=${describeFocusView(before)} result=$result after=${describeFocusView(binding.root.findFocus())}")
                result
            },
            onFollowClick = { toggleFollow() },
            onTripleAction = { tripleAction() },
            onTabSelected = { commentTab -> switchTab(commentTab) },
            onCommentSortSelected = { sortNew -> applyCommentSort(sortNew) },
            expandedCommentRpids = expandedCommentRpids,
            onCommentToggleExpand = { rpid, position ->
                if (!expandedCommentRpids.remove(rpid)) expandedCommentRpids.add(rpid)
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    contentAdapter.notifyItemChanged(position)
                }
            },
            onCommentClick = { item -> onCommentClicked(item) },
            onCommentLongClick = { item -> onCommentLongClicked(item) },
            onCommentPictureClick = { item, index -> openCommentPictures(item, index) },
            onCommentItemFocused = { maybeLoadMoreCommentsFromLayout() }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = contentAdapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // 评论触底加载挂点之一：滚动（dy>0）触发（触摸模式/惯性滚动；焦点模式走 onCommentItemFocused）
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (selectedTab != DetailTab.COMMENT) return
                maybeLoadMoreCommentsFromLayout()
            }
        })

        // 分层返回：焦点在评论条/排序行时按返回，焦点先回当前选中的 tab（再按一次才退出页面）
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (focusInCommentArea()) {
                        val tabId = if (selectedTab == DetailTab.COMMENT) {
                            R.id.tab_comment
                        } else {
                            R.id.tab_content
                        }
                        _binding?.recyclerView?.findViewById<View>(tabId)?.requestFocus()
                    } else {
                        // tab 栏/Header/触摸无焦点：按默认退出详情页
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /** 焦点是否在评论区的评论条/排序行上（tab 栏与 Header 不算）。 */
    private fun focusInCommentArea(): Boolean {
        if (selectedTab != DetailTab.COMMENT) return false
        val binding = _binding ?: return false
        val focus = binding.recyclerView.findFocus() ?: return false
        return focus.id == R.id.click_view ||
            focus.id == R.id.sort_hot ||
            focus.id == R.id.sort_new
    }

    private fun initObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appEventHub.events.collectLatest { event ->
                    if (event !is AppEventHub.Event.PlaybackProgressUpdated) {
                        return@collectLatest
                    }
                    val currentVideo = videoModel
                    if (currentVideo == null || event.aid != currentVideo.aid) {
                        return@collectLatest
                    }
                    videoModel = currentVideo.copy(
                        cid = event.cid,
                        historyProgress = event.progressMs.coerceAtLeast(0L)
                    )
                    videoView = videoView?.copy(cid = event.cid)
                }
            }
        }
    }

    private fun loadVideoDetail() {
        val currentAid = aid
        val currentBvid = bvid
        AppLog.d(TAG, "loadVideoDetail: start, aid=$currentAid, bvid=$currentBvid")

        view?.post {
            dumpProgressBars(view, "AFTER_LOAD_START")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                videoRepository.getVideoDetail(currentAid, currentBvid)
            }.onSuccess { response ->
                AppLog.d(TAG, "loadVideoDetail: response received, code=${response.code}, isSuccess=${response.isSuccess}")
                if (response.isSuccess) {
                    AppLog.d(TAG, "loadVideoDetail: show content, data=${response.data != null}")
                    response.data?.let(::updateUI)
                    view?.post {
                        dumpProgressBars(view, "AFTER_UPDATE_UI")
                    }
                } else {
                    AppLog.w(TAG, "loadVideoDetail: API error code=${response.code}, msg=${response.message}")
                    showError(response.message)
                }
            }.onFailure { e ->
                AppLog.e(TAG, "loadVideoDetail: failed", e)
                showError(e.message ?: getString(R.string.detail_load_failed))
            }
        }
    }

    private fun updateUI(detail: VideoDetailModel) {
        val view = detail.view ?: return

        if (ContentFilter.isBlockedByTags(requireContext(), detail.tags)) {
            AppLog.i(TAG, "Video blocked by tags: aid=${view.aid}, bvid=${view.bvid}, tags=${detail.tags?.map { it.tagName }}")
            ContentFilter.addBlockedVideo(
                requireContext(),
                aid = view.aid,
                bvid = view.bvid,
                title = view.title,
                coverUrl = view.pic
            )
            appEventHub.dispatch(AppEventHub.Event.VideoBlockedByMinorProtection(
                aid = view.aid,
                bvid = view.bvid
            ))
            val ctx = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val video = VideoModel(aid = view.aid, bvid = view.bvid, title = view.title, pic = view.pic)
                    videoRepository.dislikeFeed(video, 1)
                }
                navigateBackFromUi()
            }
            return
        }

        AppLog.d(TAG, "updateUI: title=${view.title}, tags=${detail.tags?.size}, pages=${view.pages?.size}, related=${detail.related?.size}")
        videoView = view
        currentDetail = detail

        if (videoModel == null) {
            videoModel = VideoModel(
                aid = view.aid,
                bvid = view.bvid,
                title = view.title,
                pic = view.pic,
                owner = view.owner,
                stat = view.stat,
                cid = view.cid,
                duration = view.duration,
                isUpowerExclusive = view.isUpowerExclusive,
                isChargingArc = view.isChargingArc,
                elecArcType = view.elecArcType,
                elecArcBadge = view.elecArcBadge,
                privilegeType = view.privilegeType
            )
        }
        if (aid == null) aid = view.aid
        if (bvid.isNullOrBlank()) bvid = view.bvid

        ugcEpisodes = view.ugcSeason?.sections?.flatMap { it.episodes ?: emptyList() } ?: emptyList()

        if (pendingFocusAid > 0) {
            updateUIForEpisodeSwitch(detail)
        } else {
            val rows = buildRows(detail)
            contentAdapter.submitList(rows) {
                val recyclerView = _binding?.recyclerView ?: return@submitList
                recyclerView.postDelayed({
                    if (!canTouchView()) return@postDelayed
                    focusPlayButton()
                }, 150)
            }
        }

        refreshActionState()
        loadRelationState()
        loadOnlineWatching()
    }

    private fun updateUIForEpisodeSwitch(detail: VideoDetailModel) {
        val view = detail.view ?: return
        val focusAid = pendingFocusAid
        pendingFocusAid = 0

        val headerHolder = binding.recyclerView.findViewHolderForAdapterPosition(0)
            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder
        if (headerHolder != null) {
            headerHolder.bind(view, detail.tags ?: emptyList(), isLiked, isCoined, isFavorited)
        }

        val ugcRowIndex = contentAdapter.currentList.indexOfFirst {
            it is VideoDetailContentAdapter.Row.UgcSeason
        }
        if (ugcRowIndex >= 0) {
            val oldRow = contentAdapter.currentList[ugcRowIndex] as VideoDetailContentAdapter.Row.UgcSeason
            val rawTitle = view.ugcSeason?.title.orEmpty()
            val items = oldRow.items
            val currentIdx = items.indexOfFirst { it.aid == view.aid }.let { if (it >= 0) it + 1 else 0 }
            val seasonTitle = buildString {
                append(getString(R.string.detail_ugc_collection))
                if (rawTitle.isNotBlank()) append("·").append(rawTitle)
                append("（").append(currentIdx).append("/").append(items.size).append("）")
            }
            val ugcHolder = binding.recyclerView.findViewHolderForAdapterPosition(ugcRowIndex)
                as? VideoDetailContentAdapter.UgcSeasonLaneViewHolder
            ugcHolder?.bind(seasonTitle, items, oldRow.isReverse, view.aid)
        }

        val recyclerView = _binding?.recyclerView ?: return
        recyclerView.postDelayed({
            if (!canTouchView()) return@postDelayed
            focusUgcCardByAid(focusAid)
        }, 150)
    }

    private fun buildRows(detail: VideoDetailModel): List<VideoDetailContentAdapter.Row> {
        val view = videoView ?: return emptyList()
        val rows = mutableListOf<VideoDetailContentAdapter.Row>()

        rows.add(
            VideoDetailContentAdapter.Row.Header(
                view = view,
                tags = detail.tags ?: emptyList(),
                isLiked = isLiked,
                isCoined = isCoined,
                isFavorited = isFavorited
            )
        )

        val hasSeason = ugcEpisodes.isNotEmpty()
        rows.add(
            VideoDetailContentAdapter.Row.TabBar(
                contentTabText = getString(
                    if (hasSeason) R.string.detail_tab_collection_recommend else R.string.recommend
                ),
                commentTabSelected = selectedTab == DetailTab.COMMENT,
                commentCountText = formatCommentCount()
            )
        )

        if (selectedTab == DetailTab.COMMENT) {
            buildCommentRows(rows)
            return rows
        }

        if (hasSeason) {
            val videos = buildUgcSeasonVideos()
            val ordered = if (ugcReverseOrder) {
                videos.sortedByDescending { it.pubDate }
            } else {
                videos.sortedBy { it.pubDate }
            }
            if (ordered.isNotEmpty()) {
                val rawTitle = view.ugcSeason?.title.orEmpty()
                val currentIdx = ordered.indexOfFirst { it.aid == view.aid }.let { if (it >= 0) it + 1 else 0 }
                val seasonTitle = buildString {
                    append(getString(R.string.detail_ugc_collection))
                    if (rawTitle.isNotBlank()) {
                        append("·").append(rawTitle)
                    }
                    append("（").append(currentIdx).append("/").append(ordered.size).append("）")
                }
                rows.add(
                    VideoDetailContentAdapter.Row.UgcSeason(
                        title = seasonTitle,
                        items = ordered,
                        isReverse = ugcReverseOrder,
                        currentAid = view.aid
                    )
                )
            }
        }

        detail.related?.let { rawRelated ->
            val related = ContentFilter.filterVideos(requireContext(), rawRelated)
            if (related.isNotEmpty()) {
                rows.add(VideoDetailContentAdapter.Row.Related(items = related))
            }
        }

        return rows
    }

    /** 评论 tab 的 rows：排序行 + 评论条 + 状态提示（数据取 [commentsState] 缓存，切 tab 不重拉）。 */
    private fun buildCommentRows(rows: MutableList<VideoDetailContentAdapter.Row>) {
        rows.add(VideoDetailContentAdapter.Row.CommentSort(sortNewSelected = commentSortNew))
        rows.addAll(commentsState.items.map { VideoDetailContentAdapter.Row.CommentEntry(it) })
        when {
            commentsFetchJob?.isActive == true && commentsState.items.isEmpty() ->
                rows.add(VideoDetailContentAdapter.Row.CommentHint(getString(R.string.player_comment_loading)))
            commentsState.items.isEmpty() && commentsState.endReached ->
                rows.add(VideoDetailContentAdapter.Row.CommentHint(getString(R.string.player_comment_empty)))
        }
    }

    private fun formatCommentCount(): String {
        val count = if (commentCountOverride >= 0) {
            commentCountOverride
        } else {
            videoView?.stat?.reply?.toLong() ?: videoModel?.stat?.reply?.toLong() ?: 0L
        }
        return if (count > 0) {
            com.mytvb.core.common.format.NumberUtils.formatCount(requireContext(), count)
        } else {
            ""
        }
    }

    private fun refreshActionState() {
        if (!sessionGateway.isLoggedIn()) {
            updateActionStateAndRefresh()
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { videoRepository.getArchiveRelation(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        val data = response.data
                        if (data != null) {
                            isLiked = data.like
                            isCoined = data.coin > 0
                            isFavorited = data.favorite
                            updateActionStateAndRefresh()
                        }
                    }
                }
        }
    }

    private fun updateActionStateAndRefresh() {
        val headerIndex = contentAdapter.currentList.indexOfFirst { it is VideoDetailContentAdapter.Row.Header }
        if (headerIndex >= 0) {
            val newList = contentAdapter.currentList.toMutableList()
            val oldHeader = newList[headerIndex] as VideoDetailContentAdapter.Row.Header
            newList[headerIndex] = oldHeader.copy(isLiked = isLiked, isCoined = isCoined, isFavorited = isFavorited)
            contentAdapter.submitList(newList)
        }
    }

    private fun loadRelationState() {
        val ownerMid = videoView?.owner?.mid ?: return
        val isSelf = sessionGateway.getUserInfo()?.mid == ownerMid
        viewLifecycleOwner.lifecycleScope.launch {
            // 粉丝数是公开数据，不需要登录
            runCatching {
                val statResponse = userRepository.getRelationStat(ownerMid)
                if (statResponse.isSuccess && statResponse.data != null) {
                    val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
                    (holder as? VideoDetailContentAdapter.VideoDetailHeadViewHolder)
                        ?.updateFansCount(statResponse.data.follower.toLong())
                } else {
                    AppLog.w(TAG, "getRelationStat failed: code=${statResponse.code}, msg=${statResponse.errorMessage}")
                }
            }.onFailure {
                AppLog.w(TAG, "getRelationStat exception: ${it.message}")
            }
            // 关注关系需要登录
            if (!sessionGateway.isLoggedIn() || isSelf) return@launch
            userRepository.checkUserRelation(ownerMid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
                        (holder as? VideoDetailContentAdapter.VideoDetailHeadViewHolder)
                            ?.updateFollowState(response.data.attribute)
                    }
                }
        }
    }

    private fun toggleFollow() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val ownerMid = videoView?.owner?.mid ?: return
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder ?: return
        val currentAttr = holder.currentRelationAttribute()
        val isFollowing = currentAttr == 2 || currentAttr == 6
        val action = if (isFollowing) 2 else 1
        viewLifecycleOwner.lifecycleScope.launch {
            userRepository.modifyRelation(ownerMid, action)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        val newAttr = if (action == 1) 2 else 0
                        holder.updateFollowState(newAttr)
                        toast(
                            if (action == 1) getString(R.string.toast_follow_success)
                            else getString(R.string.toast_cancel_follow_success)
                        )
                    } else {
                        toast(response.errorMessage)
                    }
                }
                .onFailure { toast(it.message ?: getString(R.string.toast_operation_failed)) }
        }
    }

    private fun playVideo() {
        val targetVideo = videoView?.toPlaybackVideoModel() ?: videoModel ?: return
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = targetVideo,
            playQueue = launchPlayQueue,
            forcePlayer = true
        )
    }

    private fun onPageVideoClicked(model: VideoModel) {
        val pages = videoView?.pages ?: return
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = model,
            playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                pages.mapIndexed { index, pv ->
                    VideoModel(
                        aid = videoView?.aid ?: 0L,
                        bvid = videoView?.bvid ?: "",
                        pic = videoView?.pic ?: "",
                        title = pv.part.ifBlank { "P${index + 1}" },
                        cid = pv.cid,
                        duration = pv.duration
                    )
                },
                model
            ),
            forcePlayer = true
        )
    }

    private fun onUgcEpisodeClicked(view: View, model: VideoModel) {
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        if (model.aid == currentAid) {
            lastPlaybackSource = PlaybackSource.UGC_CARD
            pendingFocusAid = 0
            playVideo()
            return
        }
        launchPlayQueue = emptyList()
        pendingFocusAid = model.aid
        lastPlaybackSource = PlaybackSource.NONE
        aid = model.aid.takeIf { it > 0 }
        bvid = model.bvid.takeIf { it.isNotBlank() }
        videoModel = null
        videoView = null
        currentDetail = null
        isLiked = false
        isCoined = false
        isFavorited = false
        resetCommentState()
        selectedTab = DetailTab.CONTENT
        loadVideoDetail()
    }

    /** 切集/换视频时清空评论区缓存（新视频重新加载）。 */
    private fun resetCommentState() {
        commentsFetchJob?.cancel()
        commentsFetchJob = null
        commentsFetchToken++
        commentsState.reset()
        expandedCommentRpids.clear()
        commentCountOverride = -1L
        commentSortNew = false
    }

    private fun onRelatedVideoClicked(model: VideoModel) {
        if (model.aid != 0L || model.bvid.isNotBlank()) {
            val relatedRow = contentAdapter.currentList
                .find { it is VideoDetailContentAdapter.Row.Related } as? VideoDetailContentAdapter.Row.Related
            VideoRouteNavigator.openVideo(
                context = requireContext(),
                video = model,
                playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                    relatedRow?.items ?: emptyList(),
                    model
                )
            )
        }
    }

    private fun toggleUgcOrder() {
        ugcReverseOrder = !ugcReverseOrder
        val view = videoView ?: return
        val ugcSeasonIndex = contentAdapter.currentList.indexOfFirst {
            it is VideoDetailContentAdapter.Row.UgcSeason
        }
        if (ugcSeasonIndex >= 0) {
            val videos = buildUgcSeasonVideos()
            val ordered = if (ugcReverseOrder) {
                videos.sortedByDescending { it.pubDate }
            } else {
                videos.sortedBy { it.pubDate }
            }
            val rawTitle = view.ugcSeason?.title.orEmpty()
            val currentIdx = ordered.indexOfFirst { it.aid == view.aid }.let { if (it >= 0) it + 1 else 0 }
            val seasonTitle = buildString {
                append(getString(R.string.detail_ugc_collection))
                if (rawTitle.isNotBlank()) {
                    append("·").append(rawTitle)
                }
                append("（").append(currentIdx).append("/").append(ordered.size).append("）")
            }
            val newList = contentAdapter.currentList.toMutableList()
            newList[ugcSeasonIndex] = VideoDetailContentAdapter.Row.UgcSeason(
                title = seasonTitle,
                items = ordered,
                isReverse = ugcReverseOrder,
                currentAid = view.aid
            )
            contentAdapter.submitList(newList)
        }
    }

    // ---------- 内容 Tab / 评论区 ----------

    /** 重建 rows 并提交（tab 切换/评论数据变化共用）。 */
    private fun rebuildRows() {
        val detail = currentDetail ?: return
        contentAdapter.submitList(buildRows(detail))
    }

    private fun switchTab(commentTab: Boolean) {
        val target = if (commentTab) DetailTab.COMMENT else DetailTab.CONTENT
        if (selectedTab == target) return
        selectedTab = target
        if (commentTab && commentsState.items.isEmpty() && commentsFetchJob?.isActive != true) {
            // 首次进评论 tab：先出骨架（排序行+加载提示）再异步拉数据
            rebuildRows()
            loadComments()
        } else {
            rebuildRows()
        }
        // 不做滚动：Header/TabBar 两个 row 稳定，DiffUtil 局部替换后列表位置天然保持
    }

    private fun applyCommentSort(sortNew: Boolean) {
        if (commentSortNew == sortNew) return
        commentSortNew = sortNew
        loadComments()
    }

    /** 数据源：upMid 供评论条 UP 徽标判定。 */
    private val commentDataSource: VideoCommentDataSource by lazy {
        VideoCommentDataSource(upMidProvider = { videoView?.owner?.mid ?: 0L })
    }

    private fun loadComments() {
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        commentsFetchJob?.cancel()
        val token = ++commentsFetchToken
        commentsState.beginReload()
        rebuildRows()

        commentsFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pageData = commentDataSource.loadRootPage(
                    oid = currentAid,
                    sort = if (commentSortNew) VIDEO_COMMENT_SORT_NEW else VIDEO_COMMENT_SORT_HOT,
                    cursorOffset = null
                )
                if (token != commentsFetchToken) return@launch
                commentsState.replace(pageData)
                if (pageData.totalCount >= 0) {
                    commentCountOverride = pageData.totalCount.toLong()
                }
                rebuildRows()
                maybeLoadMoreCommentsFromLayout(force = true)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                AppLog.w(TAG, "loadComments failed aid=$currentAid sort=$commentSortNew", t)
                if (token != commentsFetchToken) return@launch
                if (commentsState.items.isEmpty()) {
                    rebuildRowsWithHint(getString(R.string.player_comment_load_failed))
                } else {
                    toast(t.message ?: getString(R.string.player_comment_load_failed))
                }
            } finally {
                if (token == commentsFetchToken) commentsFetchJob = null
            }
        }
    }

    private fun loadMoreComments() {
        if (commentsFetchJob?.isActive == true || commentsState.endReached) return
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val cursorOffset = commentsState.nextCursorOffset ?: return
        val token = ++commentsFetchToken

        commentsFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pageData = commentDataSource.loadRootPage(
                    oid = currentAid,
                    sort = if (commentSortNew) VIDEO_COMMENT_SORT_NEW else VIDEO_COMMENT_SORT_HOT,
                    cursorOffset = cursorOffset,
                    fallbackTotalCount = commentsState.totalCount
                )
                if (token != commentsFetchToken) return@launch
                if (!commentsState.append(pageData)) {
                    return@launch
                }
                rebuildRows()
                maybeLoadMoreCommentsFromLayout(force = true)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) return@launch
                AppLog.w(TAG, "loadMoreComments failed aid=$currentAid cursor=$cursorOffset", t)
            } finally {
                if (token == commentsFetchToken) commentsFetchJob = null
            }
        }
    }

    /** 评论加载失败且无缓存的提示行（items 已空，无评论行即不会误触底加载）。 */
    private fun rebuildRowsWithHint(text: String) {
        val detail = currentDetail ?: return
        contentAdapter.submitList(buildRows(detail).toMutableList().apply {
            add(VideoDetailContentAdapter.Row.CommentHint(text))
        })
    }

    private fun maybeLoadMoreCommentsFromLayout(force: Boolean = false) {
        if (selectedTab != DetailTab.COMMENT) return
        if (commentsFetchJob?.isActive == true || commentsState.endReached) return
        val recyclerView = _binding?.recyclerView ?: return
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible < 0) return
        val list = contentAdapter.currentList
        val lastCommentIndex = list.indexOfLast { it is VideoDetailContentAdapter.Row.CommentEntry }
        if (lastCommentIndex < 0) return
        if (force || lastVisible >= lastCommentIndex - COMMENT_LOAD_MORE_THRESHOLD) {
            loadMoreComments()
        }
    }

    // ---------- 评论条交互（分流对齐播放页评论面板） ----------

    private fun onCommentClicked(item: VideoCommentItem) {
        val hasPictures = item.pictures.isNotEmpty() || item.noteCvid > 0L
        if (hasPictures) {
            openCommentPictures(item, 0)
            return
        }
        if (item.replyCount <= 0) {
            toast(getString(R.string.player_comment_thread_empty))
            return
        }
        openCommentPanelWithThread(item.rpid)
    }

    private fun onCommentLongClicked(item: VideoCommentItem): Boolean {
        // 长按进楼中楼：与"长按查看全部 N 条回复"文案一致（触摸路径）
        if (item.replyCount <= 0) return false
        openCommentPanelWithThread(item.rpid)
        return true
    }

    private fun openCommentPictures(item: VideoCommentItem, startIndex: Int) {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        if (item.pictures.isNotEmpty()) {
            ensureCommentImageViewer(activity).open(pictures = item.pictures, startIndex = startIndex)
            return
        }
        AppToast.show(activity, getString(R.string.player_comment_note_image_loading))
        CommentNoteImageRepository.load(item.noteCvid) { images ->
            if (!isAdded) return@load
            if (images.isEmpty()) return@load
            ensureCommentImageViewer(activity).open(
                pictures = images.map { VideoCommentPicture(url = it.url, width = it.width, height = it.height) },
                startIndex = startIndex
            )
        }
    }

    private fun ensureCommentImageViewer(activity: androidx.appcompat.app.AppCompatActivity): CommentImageViewerDialog {
        val existing = commentImageViewer
        if (existing != null) return existing
        return CommentImageViewerDialog(activity).also { commentImageViewer = it }
    }

    /** 打开评论面板直达楼中楼（面板 BACK 回其根列表，再 BACK 关面板回详情页）。 */
    private fun openCommentPanelWithThread(rootRpid: Long) {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        commentPanelDialog?.dismiss()
        commentPanelDialog = CommentPanelDialog(
            activity = activity,
            aid = currentAid,
            upMid = videoView?.owner?.mid ?: 0L,
            commentCount = if (commentCountOverride >= 0) commentCountOverride else videoView?.stat?.reply?.toLong() ?: 0L,
            onOpenOwnerSpace = { },
            onDismissed = { commentPanelDialog = null },
            initialThreadRpid = rootRpid
        ).apply { showPanel() }
    }

    // ---------- 正在看人数 ----------

    private fun loadOnlineWatching() {
        val currentBvid = videoView?.bvid
        val currentCid = videoView?.cid ?: return
        if (currentBvid.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { apiService.getOnlineTotal(bvid = currentBvid, cid = currentCid) }
                .onSuccess { response ->
                    val text = if (response.isSuccess) response.data?.displayText() else null
                    if (text != null) {
                        val binding = _binding ?: return@onSuccess
                        val holder = binding.recyclerView
                            .findViewHolderForAdapterPosition(0)
                            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder
                        holder?.updateOnlineWatching(text)
                    }
                }
        }
    }

    private fun buildUgcSeasonVideos(): List<VideoModel> {
        if (ugcEpisodes.isEmpty()) return emptyList()
        val episodes = LinkedHashMap<String, UgcEpisode>()
        ugcEpisodes.forEachIndexed { index, episode ->
            val key = episode.archiveKey(index)
            val existing = episodes[key]
            if (existing == null || episode.preferredOver(existing)) {
                episodes[key] = episode
            }
        }
        return episodes.values.mapNotNull { it.toMergedVideoModel() }
    }

    private fun UgcEpisode.toMergedVideoModel(): VideoModel? {
        val source = arc ?: return null
        return source.copy(
            pic = displayCover.ifBlank { source.coverUrl },
            aid = displayAid,
            bvid = displayBvid,
            cid = displayCid
        )
    }

    private fun UgcEpisode.archiveKey(index: Int): String {
        return when {
            displayBvid.isNotBlank() -> "bvid:$displayBvid"
            displayAid > 0L -> "aid:$displayAid"
            else -> "episode:$index"
        }
    }

    private fun UgcEpisode.preferredOver(other: UgcEpisode): Boolean {
        val page = displayPage.takeIf { it > 0 } ?: Int.MAX_VALUE
        val otherPage = other.displayPage.takeIf { it > 0 } ?: Int.MAX_VALUE
        return page < otherPage
    }

    private fun onTagClicked(tag: Tag) {
        val effectiveTagId = tag.tagId.takeIf { it > 0 } ?: tag.id
        if (tag.tagType == "new_channel" && effectiveTagId > 0) {
            openInHostContainer(
                ChannelVideoFragment.newInstance(tag.tagName, effectiveTagId)
            )
        } else {
            openInHostContainer(
                com.mytvb.feature.search.SearchNewFragment.newInstance(tag.tagName)
            )
        }
    }

    private fun showOwnerDetailDialog() {
        val owner = videoView?.owner ?: videoModel?.owner ?: return
        val currentAid = videoView?.aid ?: videoModel?.aid ?: 0L
        val currentBvid = videoView?.bvid ?: videoModel?.bvid ?: ""

        ownerDetailDialog?.dismiss()
        ownerDetailDialog = OwnerDetailDialog(
            context = requireContext(),
            owner = owner,
            onPlayVideo = { video, playQueue ->
                VideoRouteNavigator.openVideo(
                    context = requireContext(),
                    video = video,
                    playQueue = playQueue
                )
            },
            currentAid = currentAid,
            currentVideoId = currentBvid,
            currentSeasonId = videoView?.ugcSeason?.id ?: 0L
        ).apply {
            show()
        }
    }

    private fun showDescriptionDialog(description: CharSequence) {
        val dialogBinding = com.mytvb.databinding.DialogDescriptionBinding
            .inflate(LayoutInflater.from(requireContext()))
        dialogBinding.textDescription.text = description
        val dialog = androidx.appcompat.app.AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { /* back key or touch outside */ }
        // 简介可能很长：固定高卡片自动钳 92% 屏高，由布局根 ScrollView 滚动展示
        DialogWindowFit.overlay(
            dialog, dialogBinding.root,
            resources.getDimensionPixelSize(R.dimen.px1200),
            heightPx = resources.getDimensionPixelSize(R.dimen.px800),
            tag = "description"
        )
        dialog.show()
    }


    private fun toggleLike() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                videoRepository.like(currentAid, currentBvid, if (isLiked) 2 else 1)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isLiked = !isLiked
                    updateActionButtonsDirectly()
                    toast(if (isLiked) getString(R.string.liked_) else getString(R.string.like))
                } else {
                    toast(response.message)
                }
            }.onFailure {
                toast(it.message ?: getString(R.string.toast_operation_failed))
            }
        }
    }

    private fun giveCoin() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        if (isCoined) {
            toast(getString(R.string.give_coin_))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        val coinCount = appSettings.getCachedString("give_coin_number")?.toIntOrNull()?.coerceIn(1, 2) ?: 2
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                videoRepository.giveCoin(currentAid, currentBvid, multiply = coinCount)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isCoined = true
                    updateActionButtonsDirectly()
                    toast(getString(R.string.toast_coin_success))
                } else {
                    toast(response.message)
                }
            }.onFailure {
                toast(it.message ?: getString(R.string.toast_operation_failed))
            }
        }
    }

    private fun toggleFavorite() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
                ?: videoView?.owner?.mid ?: videoModel?.owner?.mid ?: 0L
            if (currentUserMid <= 0L) {
                toast(getString(R.string.toast_favorite_folder_not_ready))
                return@launch
            }
            val folderResult = favoriteRepository.getFavoriteFolders(currentUserMid)
            val folders = folderResult.getOrNull()?.data?.list.orEmpty()
            val defaultFolder = folders.firstOrNull()
            if (defaultFolder == null) {
                toast(getString(R.string.toast_no_available_folder))
                return@launch
            }
            val folderId = defaultFolder.id.toString()
            val result = if (isFavorited) {
                favoriteRepository.removeFavorite(currentAid, folderId)
            } else {
                favoriteRepository.addFavorite(currentAid, folderId)
            }
            result.onSuccess { response ->
                if (response.isSuccess) {
                    isFavorited = !isFavorited
                    updateActionButtonsDirectly()
                    toast(
                        if (isFavorited) getString(R.string.collection_)
                        else getString(R.string.toast_cancel_favorite)
                    )
                } else {
                    toast(response.errorMessage)
                }
            }.onFailure { toast(it.message ?: getString(R.string.toast_operation_failed)) }
        }
    }

    private fun tripleAction() {
        if (!sessionGateway.isLoggedIn()) {
            toast(getString(R.string.need_sign_in))
            return
        }
        val currentAid = videoView?.aid ?: videoModel?.aid ?: return
        val currentBvid = videoView?.bvid ?: videoModel?.bvid
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { videoRepository.tripleAction(currentAid, currentBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = true
                        isCoined = true
                        isFavorited = true
                        updateActionButtonsDirectly()
                        if (response.data?.isRisk == true) {
                            AppToast.show(requireContext(),getString(R.string.toast_triple_success_risk_control),Toast.LENGTH_LONG)
                        } else {
                            toast(getString(R.string.triple_action))
                        }
                    } else {
                        toast(response.message)
                    }
                }.onFailure { toast(it.message ?: getString(R.string.toast_operation_failed)) }
        }
    }

    private fun updateActionButtonsDirectly() {
        val binding = _binding ?: return
        val holder = binding.recyclerView.findViewHolderForAdapterPosition(0)
            as? VideoDetailContentAdapter.VideoDetailHeadViewHolder ?: return
        holder.updateActionButtons(isLiked, isCoined, isFavorited)
    }

    private fun canTouchView(): Boolean {
        return isAdded && view != null && _binding != null
    }

    private fun toast(message: String) {
        if (isAdded) {
            AppToast.show(requireContext(), message)
        }
    }

    private fun showError(message: String?) {
        toast(message ?: getString(R.string.detail_load_failed))
    }

    private fun openInHostContainer(
        fragment: androidx.fragment.app.Fragment,
        addToBackStack: Boolean = true
    ) {
        mainActivity?.openInHostContainer(fragment, addToBackStack)
    }

    override fun onDestroyView() {
        actionDialog?.dismiss()
        actionDialog = null
        ownerDetailDialog?.dismiss()
        ownerDetailDialog = null
        commentsFetchJob?.cancel()
        commentsFetchJob = null
        commentPanelDialog?.dismiss()
        commentPanelDialog = null
        commentImageViewer?.dismiss()
        commentImageViewer = null
        super.onDestroyView()
        _binding = null
        mainActivity = null
    }

    private fun VideoView.toPlaybackVideoModel(): VideoModel {
        return VideoModel(
            aid = aid,
            bvid = bvid,
            title = title,
            pic = pic,
            cid = cid,
            duration = duration,
            pubDate = pubDate,
            owner = owner,
            stat = stat,
            isUpowerExclusive = isUpowerExclusive,
            isChargingArc = isChargingArc,
            elecArcType = elecArcType,
            elecArcBadge = elecArcBadge,
            privilegeType = privilegeType
        )
    }

    private fun dumpProgressBars(root: View?, label: String) {
        if (root == null) return
        val activityRoot = activity?.findViewById<View>(android.R.id.content) ?: root
        val stack = ArrayDeque<View>()
        stack.add(activityRoot)
        var found = 0
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v is ProgressBar) {
                found++
                AppLog.d(TAG, "$label: ProgressBar id=${v.id}, vis=${v.visibility}, w=${v.width}, h=${v.height}, parent=${v.parent?.javaClass?.simpleName}")
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    stack.add(v.getChildAt(i))
                }
            }
        }
        if (found == 0) {
            AppLog.d(TAG, "$label: NO ProgressBar found in view hierarchy")
        }
    }

    private fun describeFocusView(view: View?): String {
        if (view == null) return "null"
        val idName = if (view.id == View.NO_ID) {
            "no-id"
        } else {
            runCatching { resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString())
        }
        return "${view.javaClass.simpleName}(id=$idName,attached=${view.isAttachedToWindow},shown=${view.isShown},focusable=${view.isFocusable})"
    }
}
