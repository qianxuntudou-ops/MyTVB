@file:Suppress("SpellCheckingInspection")

package com.mytvb.feature.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
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

class VideoDetailFragment : androidx.fragment.app.Fragment() {

    private val TAG = "VideoDetail"

    companion object {
        private const val ARG_AID = "aid"
        private const val ARG_BVID = "bvid"
        private const val ARG_PLAY_QUEUE_TOKEN = "play_queue_token"

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
    private var ugcReverseOrder = true

    private var pendingFocusAid: Long = 0

    private enum class PlaybackSource { NONE, PLAY_BUTTON, UGC_CARD }
    private var lastPlaybackSource = PlaybackSource.NONE

    private var actionDialog: PlayerActionDialog? = null
    private var ownerDetailDialog: OwnerDetailDialog? = null

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
            onTripleAction = { tripleAction() }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = contentAdapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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

        if (ugcEpisodes.isNotEmpty()) {
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
        isLiked = false
        isCoined = false
        isFavorited = false
        loadVideoDetail()
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
        dialog.setContentView(dialogBinding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { /* back key or touch outside */ }
        dialog.show()
        // 简介可能很长：高度超屏时钳到 92% 屏高由 ScrollView 滚动展示
        DialogWindowFit.apply(
            dialog.window, requireContext(),
            resources.getDimensionPixelSize(R.dimen.px1200),
            resources.getDimensionPixelSize(R.dimen.px800)
        )
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
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.toast_triple_success_risk_control),
                                Toast.LENGTH_LONG
                            ).show()
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
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
