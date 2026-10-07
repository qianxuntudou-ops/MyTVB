package com.mytvb.ui.dialog

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import com.mytvb.core.ui.user.UserBadgeText
import com.mytvb.core.ui.user.UserBadgesStore
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.databinding.DialogOwnerDetailBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.BaseResponse
import com.mytvb.model.user.CheckRelationModel
import com.mytvb.model.video.Owner
import com.mytvb.model.video.UserDynamicResponse
import com.mytvb.model.video.VideoModel
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.UserRepository
import com.mytvb.ui.activity.PlayerActivity
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.ui.focus.tv.GridTvFocusStrategy
import com.mytvb.core.ui.focus.tv.TvDataChangeReason
import com.mytvb.core.ui.focus.tv.TvListFocusController
import com.mytvb.core.ui.image.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class OwnerDetailDialog(
    context: Context,
    private val owner: Owner,
    private val onPlayVideo: (VideoModel, List<VideoModel>) -> Unit,
    private val currentAid: Long = 0L,
    private val currentVideoId: String = ""
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogOwnerDetailBinding.inflate(LayoutInflater.from(context))
    private val userRepository: UserRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val videoAdapter = VideoAdapter(
        onItemFocusedWithView = { view, position ->
            tvFocusController?.onItemFocused(view, position)
        },
        onItemDpad = { view, keyCode, event ->
            tvFocusController?.handleKey(view, keyCode, event) == true
        }
    ).also {
        it.currentPlayingAid = currentAid
    }

    private var relationAttribute = 0
    private var currentPage = 1
    private var hasMore = true
    private var isLoading = false
    private var tvFocusController: TvListFocusController? = null
    private var searchingCurrentVideo = false

    // 预取：一页数据回来后立即在后台发起下一页请求，把 1.5s 的网络往返
    // 藏进用户浏览过程。滚动/焦点触底时若已有预取在飞则直接 await，免去等待。
    private var prefetchJob: Deferred<BaseResponse<UserDynamicResponse>>? = null
    private var prefetchPage: Int = 0

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCanceledOnTouchOutside(true)
        // 设计尺寸 px1400×px935，但在低分屏/大缩放系数下换算后的像素可能超过屏幕物理尺寸，
        // 浮层 window 会被系统钳到屏幕大小而内容仍按原尺寸绘制，左右卡片内容被裁；
        // 这里取设计尺寸与屏幕 92% 的较小值，布局内部用约束自适应 window 实际大小。
        val dm = context.resources.displayMetrics
        val designWidth = context.resources.getDimensionPixelSize(R.dimen.px1400)
        val designHeight = context.resources.getDimensionPixelSize(R.dimen.px935)
        window?.setLayout(
            minOf(designWidth, (dm.widthPixels * 0.92f).toInt()),
            minOf(designHeight, (dm.heightPixels * 0.92f).toInt())
        )
        binding.root.setOnClickListener { dismiss() }
        initView()
        bindOwnerHeader()
        loadData()
    }

    private fun initView() {
        val spacing = context.resources.getDimensionPixelSize(R.dimen.px10)
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(context, 3)
        binding.recyclerView.adapter = videoAdapter
        VideoRecyclerViewTuning.apply(binding.recyclerView, videoAdapter)
        binding.recyclerView.setPadding(0, -spacing, 0, binding.recyclerView.paddingBottom)
        if (binding.recyclerView.itemDecorationCount == 0) {
            binding.recyclerView.addItemDecoration(
                GridSpacingItemDecoration(3, spacing, true)
            )
        }
        videoAdapter.setOnItemClickListener { _, item ->
            dismiss()
            onPlayVideo(
                item,
                PlayerActivity.buildPlayQueue(videoAdapter.getItemsSnapshot(), item)
            )
        }
        binding.buttonFollow.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            toggleFollow()
        }
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                // 不限制 dy>0：定位当前视频的 jump 滚动 dy=0，若拦掉会让列表停在
                // 尾部却不翻页，footer“正在加载..”没有任何请求在飞、永远空转。
                maybeTriggerLoadMore()
            }
        })
        installTvListFocusController()
    }

    private fun bindOwnerHeader() {
        UserBadgeText.bind(binding.textName, owner.name, isVip = false)
        ImageLoader.loadCircle(
            imageView = binding.imageAvatar,
            url = owner.face,
            placeholder = R.drawable.default_avatar,
            error = R.drawable.default_avatar
        )
        binding.imageAvatar.setBadge(
            officialVerifyType = owner.officialVerify?.type ?: -1
        )
        binding.imageAvatar.setPendant(null)
        // owner 接口不带会员/头像框，按 mid 补齐（dialog 单实例无复用错位）
        UserBadgesStore.enqueue(owner.mid) { badges ->
            binding.imageAvatar.setPendant(badges.pendantUrl)
            if (badges.isVip) {
                binding.imageAvatar.setBadge(
                    officialVerifyType = owner.officialVerify?.type ?: -1,
                    vipStatus = 1,
                    vipType = 2,
                )
            }
            UserBadgeText.bind(binding.textName, owner.name, badges.isVip)
        }

        val isSelf = sessionGateway.getUserInfo()?.mid == owner.mid
        binding.buttonFollow.isVisible = !isSelf
        updateFollowUi(
            labelRes = R.string.follow,
            textColorRes = R.color.colorAccent,
            iconRes = R.drawable.ic_plus,
            iconTintRes = R.color.colorAccent
        )
    }

    private fun loadData() {
        loadRelationState()
        loadOwnerVideos()
    }

    private fun loadRelationState() {
        if (!sessionGateway.isLoggedIn() || sessionGateway.getUserInfo()?.mid == owner.mid) {
            return
        }
        scope.launch {
            userRepository.checkUserRelation(owner.mid)
                .onSuccess { response ->
                    if (response.isSuccess && response.data != null) {
                        updateRelationState(response.data)
                    }
                }
        }
    }

    private fun updateRelationState(relation: CheckRelationModel) {
        relationAttribute = relation.attribute
        when {
            relation.isMutualFollow -> updateFollowUi(
                labelRes = R.string.follow_as_friend,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            relationAttribute == 2 -> updateFollowUi(
                labelRes = R.string.followed,
                textColorRes = R.color.grey,
                iconRes = R.drawable.ic_check,
                iconTintRes = R.color.grey
            )
            else -> updateFollowUi(
                labelRes = R.string.follow,
                textColorRes = R.color.colorAccent,
                iconRes = R.drawable.ic_plus,
                iconTintRes = R.color.colorAccent
            )
        }
    }

    private fun loadOwnerVideos() {
        if (isLoading || !hasMore) return
        val pageToLoad = currentPage
        // 若后台已有这一页的预取在飞，直接消费它，省掉一整次网络往返。
        val prefetchHit = prefetchJob != null && prefetchPage == pageToLoad
        isLoading = true
        if (pageToLoad == 1) {
            binding.progressBar.isVisible = true
        }
        scope.launch {
            val startMs = SystemClock.elapsedRealtime()
            val existingPrefetch = prefetchJob
            if (!prefetchHit) {
                // 页码不符或没有预取：丢弃陈旧预取，本次直接发请求。
                prefetchJob = null
                existingPrefetch?.cancel()
            }
            val result = if (prefetchHit && existingPrefetch != null) {
                prefetchJob = null
                runCatching { existingPrefetch.await() }
            } else {
                userRepository.getUserDynamic(owner.mid, page = pageToLoad, pageSize = 20)
            }
            result.onSuccess { response ->
                AppLog.i(
                    "OwnerDetailDialog",
                    "owner page=$pageToLoad end elapsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                        "items=${response.data?.archives?.size ?: 0} hasMore=${response.data?.hasMore} prefetchHit=$prefetchHit"
                )
                binding.progressBar.isVisible = false
                isLoading = false
                if (response.isSuccess) {
                    val videos = ContentFilter.filterVideos(binding.root.context, response.data?.archives.orEmpty())
                    hasMore = response.data?.hasMore ?: false
                    if (pageToLoad == 1) {
                        val found = scrollToCurrentVideo(videos)
                        videoAdapter.setData(videos) {
                            tvFocusController?.onDataChanged(TvDataChangeReason.REPLACE_PRESERVE_ANCHOR)
                        }
                        if (!found) {
                            searchingCurrentVideo = true
                        }
                    } else {
                        videoAdapter.addData(videos)
                        tvFocusController?.onDataChanged(TvDataChangeReason.APPEND)
                        if (searchingCurrentVideo) {
                            val allVideos = videoAdapter.getItemsSnapshot()
                            val found = scrollToCurrentVideo(allVideos)
                            if (found) {
                                searchingCurrentVideo = false
                            } else if (!hasMore) {
                                searchingCurrentVideo = false
                                focusDefault()
                            }
                        }
                    }
                    if (!hasMore) {
                        videoAdapter.setShowLoadMore(false)
                    }
                    // 当前页渲染成功后，立即在后台预取下一页，把网络往返藏进用户浏览过程。
                    // 递归搜索路径也会消费它，让"从旧视频打开"的串行循环每步都更快。
                    schedulePrefetch(currentPage + 1)
                    if (searchingCurrentVideo && hasMore && currentPage < 10) {
                        currentPage++
                        loadOwnerVideos()
                    }
                } else {
                    toast(response.message)
                }
            }.onFailure {
                AppLog.w(
                    "OwnerDetailDialog",
                    "owner page=$pageToLoad failed elapsed=${SystemClock.elapsedRealtime() - startMs}ms " +
                        "prefetchHit=$prefetchHit err=${it.message}"
                )
                binding.progressBar.isVisible = false
                isLoading = false
                currentPage--
                toast(it.message ?: context.getString(R.string.dialog_load_failed))
            }
        }
    }

    /**
     * 后台预取下一页，结果暂存在 [prefetchJob]。下一次 [loadOwnerVideos] 触底时优先消费它，
     * 命中即可省掉一整次网络往返。预取失败对当前列表无影响（消费时才暴露为失败）。
     */
    private fun schedulePrefetch(nextPage: Int) {
        if (!hasMore) return
        if (nextPage == prefetchPage && prefetchJob != null) return
        prefetchJob?.cancel()
        prefetchPage = nextPage
        prefetchJob = scope.async {
            userRepository.getUserDynamic(owner.mid, page = nextPage, pageSize = 20).getOrThrow()
        }
    }

    /**
     * 提前约一屏触发下一页：3 列网格一屏约 6 卡，提前消费已在飞的下一页预取。
     * 必须 post 到下一帧：预取命中时数据几乎同步返回，若在 onScrolled 里直接
     * addData 会撞 RecyclerView 的 assertNotInLayoutOrScroll 检查。
     */
    private fun maybeTriggerLoadMore() {
        val layoutManager = binding.recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
            ?: return
        val totalItems = binding.recyclerView.adapter?.itemCount ?: return
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        if (!isLoading && hasMore && lastVisibleItem >= totalItems - 6) {
            binding.recyclerView.post {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadOwnerVideos()
                }
            }
        }
    }

    private fun scrollToCurrentVideo(videos: List<VideoModel>): Boolean {
        val targetIndex = videos.indexOfFirst { video ->
            (currentAid > 0L && video.aid == currentAid) ||
                (currentVideoId.isNotBlank() && video.bvid == currentVideoId)
        }
        AppLog.d("OwnerDetailDialog", "scrollToCurrentVideo: targetIndex=$targetIndex, currentAid=$currentAid, currentVideoId=$currentVideoId, videos=${videos.size}")
        if (targetIndex < 0) return false

        if (videoAdapter.currentPlayingAid <= 0L) {
            val targetAid = videos[targetIndex].aid
            if (targetAid > 0L) {
                videoAdapter.currentPlayingAid = targetAid
            }
        }
        binding.recyclerView.post {
            binding.recyclerView.scrollToPosition(targetIndex)
            focusVideoAt(targetIndex)
            binding.recyclerView.post {
                val layoutManager =
                    binding.recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val vh = binding.recyclerView.findViewHolderForAdapterPosition(targetIndex)
                AppLog.d("OwnerDetailDialog", "centerScroll: layoutManager=$layoutManager, vh=$vh, targetIndex=$targetIndex")
                if (layoutManager != null && vh != null) {
                    val rvHeight = binding.recyclerView.height
                    val itemHeight = vh.itemView.height
                    val centerOffset = (rvHeight - itemHeight) / 2
                    AppLog.d("OwnerDetailDialog", "centerScroll: rvHeight=$rvHeight, itemHeight=$itemHeight, centerOffset=$centerOffset")
                    layoutManager.scrollToPositionWithOffset(
                        targetIndex, centerOffset.coerceAtLeast(0)
                    )
                    // jump 滚动不保证回调 onScrolled，停在尾部时显式补一次触底检查，
                    // 否则 footer 转圈会一直空转（见 maybeTriggerLoadMore 注释）。
                    binding.recyclerView.post { maybeTriggerLoadMore() }
                }
            }
        }
        return true
    }

    private fun focusDefault() {
        binding.recyclerView.post {
            if (binding.buttonFollow.isVisible) {
                binding.buttonFollow.requestFocus()
            } else {
                tvFocusController?.focusPrimary()
            }
        }
    }

    private fun focusVideoAt(targetIndex: Int, retries: Int = 6) {
        // allowOutsideFocus=true：对话框刚打开时焦点还在外层容器上，
        // 不放开这个开关 focusPosition 会被“焦点在 RV 外”直接拦截。
        if (tvFocusController?.requestFocusPosition(targetIndex, allowOutsideFocus = true) == true) {
            return
        }
        if (retries > 0) {
            binding.recyclerView.post { focusVideoAt(targetIndex, retries - 1) }
        } else {
            // 重试耗尽仍未聚焦成功：回退到第一个可见卡片，保证打开时焦点一定落在列表上。
            tvFocusController?.focusPrimary()
        }
    }

    private fun updateFollowUi(
        labelRes: Int,
        textColorRes: Int,
        iconRes: Int,
        iconTintRes: Int
    ) {
        binding.textFollow.text = context.getString(labelRes)
        binding.textFollow.setTextColor(ContextCompat.getColor(context, textColorRes))
        binding.iconFollow.setImageResource(iconRes)
        binding.iconFollow.imageTintList =
            ContextCompat.getColorStateList(context, iconTintRes)
    }

    private fun toggleFollow() {
        if (sessionGateway.requireCsrfToken() == null) {
            toast(context.getString(R.string.dialog_credential_error_retry_later))
            return
        }
        val action = if (isFollowing()) 2 else 1
        scope.launch {
            userRepository.modifyRelation(owner.mid, action)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        relationAttribute = if (action == 1) 2 else 0
                        updateRelationState(
                            CheckRelationModel(
                                attribute = relationAttribute
                            )
                        )
                        toast(
                            if (action == 1) context.getString(R.string.dialog_follow_success)
                            else context.getString(R.string.dialog_unfollowed)
                        )
                    } else {
                        toast(response.errorMessage)
                    }
                }
                .onFailure {
                    AppLog.e("OwnerDetailDialog", "toggleFollow failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
        }
    }

    private fun isFollowing(): Boolean {
        return relationAttribute == 2 || relationAttribute == 6
    }

    private fun checkLogin(): Boolean = sessionGateway.isLoggedIn()

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun installTvListFocusController() {
        tvFocusController?.release()
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = videoAdapter,
            strategy = GridTvFocusStrategy { 3 },
            canLoadMore = { hasMore },
            loadMore = {
                if (!isLoading && hasMore) {
                    currentPage++
                    loadOwnerVideos()
                }
            }
        )
    }

    override fun dismiss() {
        tvFocusController?.release()
        tvFocusController = null
        prefetchJob = null
        scope.cancel()
        super.dismiss()
    }
}
