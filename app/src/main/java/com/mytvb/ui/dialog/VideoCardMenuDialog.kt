@file:Suppress("SpellCheckingInspection")

package com.mytvb.ui.dialog

import com.mytvb.core.common.log.AppLog
import com.mytvb.core.ui.video.resolveThemedTextColor

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window

import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.DialogVideoCardMenuBinding
import com.mytvb.event.AppEventHub
import com.mytvb.model.favorite.FavoriteFolderModel
import com.mytvb.model.favorite.planFavoriteDeal
import com.mytvb.model.video.VideoModel
import com.mytvb.network.session.ActionError
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.FavoriteRepository
import com.mytvb.repository.VideoRepository
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.ui.adapter.FavoriteFolderDialogAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.base.AppToast

class VideoCardMenuDialog(
    context: Context,
    private val video: VideoModel,
    private val onDislikeVideo: (() -> Unit)? = null,
    private val onDislikeUp: ((String) -> Unit)? = null,
    private val onFavoriteRemoved: (() -> Unit)? = null,
    private val onHistoryRecordDeleted: (() -> Unit)? = null,
    // 直播推荐页传 true 才显示「不想看该分区」；分区详情页用户主动进入，屏蔽会清空列表
    private val allowAreaDislike: Boolean = false,
    private val onDislikeArea: ((String) -> Unit)? = null
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    companion object {
        private const val TAG = "VideoCardMenuDialog"
        private const val REASON_ID_NOT_INTERESTED = 1
        private const val REASON_ID_DISLIKE_UP = 4
        private const val MAX_TITLE_CHARS = 30
        private const val TITLE_ELLIPSIS = "···"
    }

    private val binding = DialogVideoCardMenuBinding.inflate(LayoutInflater.from(context))
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val appEventHub: AppEventHub by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isInWatchLater = false
    private var isFavorited = false
    private var isActionInProgress = false
    private val isLiveFeedbackCard = video.goto.equals("live", ignoreCase = true) ||
        video.roomId > 0L ||
        video.isLive ||
        video.historyBusiness == "live"
    private val supportsWatchLater = !isLiveFeedbackCard &&
        (video.aid > 0L || video.bvid.isNotBlank())
    // 视频详情页仅对普通视频开放，直播卡片无详情可看。
    private val supportsVideoDetail = !isLiveFeedbackCard &&
        (video.aid > 0L || video.bvid.isNotBlank())
    private val supportsHistoryRecordDelete = onHistoryRecordDeleted != null &&
        video.historyRecordKid.isNotBlank()
    // UP 主信息是否就绪：owner 缺失或 mid<=0 时（如关注/动态页扁平结构未带 owner），
    // “UP主主页”不可跳转、“不感兴趣UP”也无法生效，统一由此字段兜底显隐与可用性。
    // 例外：app 端卡片（gRPC 热门 / feed/index 推荐，特征为 aid-only 无 bvid）服务端
    // 不下发 UP mid，但 aid 可查——放开显示，点击时经 view?aid= 懒解析补齐再跳转。
    private val supportsUpSpace = video.owner?.let { it.mid > 0L } == true ||
        (video.aid > 0L && video.bvid.isBlank())
    // 直播卡片无 aid，收藏接口必失败，直接隐藏收藏入口
    private val supportsFavorite = !isLiveFeedbackCard
    // 「不感兴趣当前直播」对直播价值很小（主播和分区两个维度已覆盖），直播卡片隐藏
    private val supportsDislikeVideo = !isLiveFeedbackCard
    // 「不想看该分区」：仅直播推荐流开放；typeName 由 LiveRoomAdapter 用二级分区名填充
    private val supportsAreaDislike = allowAreaDislike && isLiveFeedbackCard && video.typeName.isNotBlank()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        // fill 全屏窗口 + 居中卡片（华为电视对独立小窗有延迟裁高，fill 载体不受影响）
        DialogWindowFit.overlay(
            this, binding.root,
            (context.resources.displayMetrics.widthPixels * 0.45).toInt(),
            tag = "menu"
        )
        // 菜单项较多（历史删除/稍后再看/详情/UP主页/收藏/两个不感兴趣）时整体会高过屏幕，
        // 底部选项被裁；限制菜单区最高 80% 屏高，超出转为滚动
        binding.menuScroll.maxHeight =
            (context.resources.displayMetrics.heightPixels * 0.8f).toInt()
        setCanceledOnTouchOutside(true)
        configureContent()
        initListeners()
        refreshWatchLaterState()
        refreshFavoriteState()
        setOnShowListener {
            binding.root.post {
                val target = when {
                    supportsHistoryRecordDelete -> R.id.button_delete_history_record
                    supportsWatchLater -> R.id.button_watch_later
                    supportsUpSpace -> R.id.button_up_space
                    else -> 0
                }
                if (target != 0) {
                    findViewById<View>(target)?.requestFocus()
                }
            }
        }
    }

    private fun initListeners() {
        binding.root.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
                && event.action == KeyEvent.ACTION_UP
            ) {
                dismiss()
                true
            } else {
                false
            }
        }

        binding.buttonWatchLater.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            if (isInWatchLater) {
                removeWatchLater()
            } else {
                addWatchLater()
            }
        }

        binding.buttonVideoDetail.setOnClickListener {
            dismiss()
            if (!VideoRouteNavigator.openVideoDetail(context, video)) {
                VideoRouteNavigator.openVideo(context, video)
            }
        }

        binding.buttonDeleteHistoryRecord.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            if (!supportsHistoryRecordDelete) return@setOnClickListener
            setActionInProgress(true)
            deleteHistoryRecord()
        }

        binding.buttonUpSpace.setOnClickListener {
            scope.launch {
                val owner = resolveOwner()
                if (owner == null) {
                    toast(context.getString(R.string.dialog_up_info_not_loaded))
                } else {
                    dismiss()
                    OwnerDetailDialog(
                        context = context,
                        owner = owner,
                        onPlayVideo = { v, playQueue ->
                            VideoRouteNavigator.openVideo(
                                context = context,
                                video = v,
                                playQueue = playQueue
                            )
                        },
                        currentAid = video.aid,
                        currentVideoId = video.bvid
                    ).show()
                }
            }
        }

        binding.buttonFavorite.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            toggleFavorite()
        }

        binding.buttonFavorite.setOnLongClickListener {
            if (!checkCsrfAndLogin()) return@setOnLongClickListener true
            showFavoriteFolderDialog()
            true
        }

        binding.buttonDislikeVideo.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            dislikeVideo(REASON_ID_NOT_INTERESTED)
        }

        binding.buttonDislikeUp.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            if (!canDislikeUp()) return@setOnClickListener
            setActionInProgress(true)
            dislikeVideo(REASON_ID_DISLIKE_UP)
        }

        binding.buttonDislikeArea.setOnClickListener {
            if (!supportsAreaDislike) return@setOnClickListener
            // 纯本地屏蔽，无需登录态
            val areaName = video.typeName.trim()
            if (areaName.isBlank()) return@setOnClickListener
            ContentFilter.addBlockedLiveArea(context, areaName)
            toast(context.getString(R.string.toast_dislike_area_success, areaName))
            onDislikeArea?.invoke(areaName)
            dismiss()
        }
    }

    /** 懒解析缓存：app 端 aid-only 卡片的 UP mid 同一次菜单会话只查一次。 */
    @Volatile
    private var lazyResolvedOwner: com.mytvb.model.video.Owner? = null

    /**
     * 获取卡片 UP 信息：owner.mid 已有直接用；缺失（app 端 gRPC 热门/feed 推荐卡片
     * 服务端不下发）时经 view?aid= 详情懒解析，一次 ~100ms 的按需请求。
     */
    private suspend fun resolveOwner(): com.mytvb.model.video.Owner? {
        video.owner?.takeIf { it.mid > 0L }?.let { return it }
        if (video.aid <= 0L) return null
        if (lazyResolvedOwner != null) return lazyResolvedOwner
        lazyResolvedOwner = runCatching {
            videoRepository.getVideoDetail(video.aid, video.bvid.takeIf { it.isNotBlank() })
        }.getOrNull()?.data?.view?.owner?.takeIf { it.mid > 0L }
        return lazyResolvedOwner
    }

    private fun dislikeVideo(reasonId: Int) {
        scope.launch {
            runCatching {
                videoRepository.dislikeFeed(video, reasonId)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    if (reasonId == REASON_ID_DISLIKE_UP) {
                        toast(context.getString(R.string.toast_dislike_up_success))
                        val upName = video.authorName.trim()
                        if (upName.isNotBlank()) {
                            // 直播卡片写直播专属主播名单，刷新后不再出现；
                            // 不写视频侧 blocked_up_names——那会被收藏页过滤，误伤用户已收藏的投稿
                            if (isLiveFeedbackCard) {
                                ContentFilter.addBlockedLiveAnchor(context, upName)
                            }
                            onDislikeUp?.invoke(upName)
                        }
                    } else {
                        toast(context.getString(R.string.toast_dislike_video_success))
                        onDislikeVideo?.invoke()
                    }
                    dismiss()
                } else {
                    toast(context.getString(R.string.toast_dislike_video_failed))
                    setActionInProgress(false)
                }
            }.onFailure {
                AppLog.e(
                    TAG,
                    "dislikeVideo failure: reasonId=$reasonId, aid=${video.aid}, bvid=${video.bvid}, ownerMid=${video.owner?.mid ?: 0L}, message=${it.message}",
                    it
                )
                toast(context.getString(R.string.toast_dislike_video_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun deleteHistoryRecord() {
        val kid = video.historyRecordKid
        if (kid.isBlank()) {
            toast(context.getString(R.string.toast_delete_history_record_failed))
            setActionInProgress(false)
            return
        }
        scope.launch {
            runCatching {
                videoRepository.deleteHistoryRecord(kid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    toast(context.getString(R.string.toast_delete_history_record_success))
                    onHistoryRecordDeleted?.invoke()
                    dismiss()
                } else {
                    handleActionError(response.code, response.errorMessage)
                    setActionInProgress(false)
                }
            }.onFailure {
                AppLog.e(
                    TAG,
                    "deleteHistoryRecord failure: kid=$kid, aid=${video.aid}, bvid=${video.bvid}, message=${it.message}",
                    it
                )
                toast(context.getString(R.string.toast_delete_history_record_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun addWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.addWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = true
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_add_watch_later_success))
                    dismiss()
                } else {
                    val msg = response.errorMessage
                    when (val error = sessionGateway.classifyActionError(response.code, msg)) {
                        is ActionError.SessionExpired -> handleAuthExpired()
                        is ActionError.CsrfMismatch -> toast(context.getString(R.string.toast_add_watch_later_failed))
                        is ActionError.RiskControl -> toast(context.getString(R.string.toast_add_watch_later_failed))
                        is ActionError.FrequencyLimit -> toast(error.message)
                        is ActionError.Other -> {
                            if (msg.contains("90001") || msg.contains("上限") || msg.contains("已满")) {
                                toast(context.getString(R.string.toast_watch_later_full))
                            } else {
                                toast(context.getString(R.string.toast_add_watch_later_failed))
                            }
                        }
                        is ActionError.CsrfMissing -> handleAuthExpired()
                    }
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_add_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun removeWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.removeWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = false
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_remove_watch_later_success))
                    appEventHub.dispatch(
                        AppEventHub.Event.WatchLaterVideoRemoved(
                            aid = video.aid,
                            bvid = video.bvid
                        )
                    )
                    dismiss()
                } else {
                    toast(context.getString(R.string.toast_remove_watch_later_failed))
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_remove_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun refreshWatchLaterState() {
        if (!supportsWatchLater) return
        if (!sessionGateway.isLoggedIn()) return
        scope.launch {
            runCatching {
                videoRepository.checkWatchLater(video.aid, video.bvid)
            }.onSuccess { inList ->
                isInWatchLater = inList
                renderWatchLaterState()
            }
        }
    }

    private fun toggleFavorite() {
        scope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
                ?: video.owner?.mid?.takeIf { it > 0L }
                ?: 0L
            if (currentUserMid <= 0L) {
                toast(context.getString(R.string.dialog_favorite_folder_not_loaded))
                setActionInProgress(false)
                return@launch
            }
            val folderResult = favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
            val folders = folderResult.getOrNull()?.data?.list.orEmpty()
            if (folders.isEmpty()) {
                toast(context.getString(R.string.dialog_no_available_favorite_folder))
                setActionInProgress(false)
                return@launch
            }
            // 按服务端返回的 fav_state 计算真实加/减夹：取消收藏必须删视频实际所在的夹，
            // 不能拿「默认夹」或「用户点的夹」冒充（issue#156：删不存在的夹会幂等成功，
            // 客户端误判为操作成功、用户反复重试，短时间内连续 deal 触发账号级风控 HTTP 412）
            val plan = planFavoriteDeal(folders)
            if (plan.isEmpty) {
                toast(context.getString(R.string.dialog_no_available_favorite_folder))
                setActionInProgress(false)
                return@launch
            }
            val result = favoriteRepository.updateFavorite(
                video.aid,
                addMediaIds = plan.addMediaIds,
                delMediaIds = plan.delMediaIds
            )
            result.onSuccess { response ->
                if (response.isSuccess) {
                    isFavorited = plan.favoritedAfter
                    renderFavoriteState()
                    toast(
                        if (isFavorited) context.getString(R.string.collection_)
                        else context.getString(R.string.collection)
                    )
                    if (!isFavorited) {
                        onFavoriteRemoved?.invoke()
                    }
                    dismiss()
                } else {
                    handleActionError(response.code, response.errorMessage)
                }
                setActionInProgress(false)
            }.onFailure {
                toast(it.message ?: context.getString(R.string.dialog_action_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun showFavoriteFolderDialog() {
        val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
            ?: video.owner?.mid?.takeIf { it > 0L }
            ?: 0L
        if (currentUserMid <= 0L) {
            toast(context.getString(R.string.dialog_favorite_folder_not_loaded))
            return
        }
        scope.launch {
            favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
                .onSuccess { response ->
                    if (!response.isSuccess) {
                        toast(response.errorMessage)
                        return@onSuccess
                    }
                    val folders = response.data?.list.orEmpty()
                    if (folders.isEmpty()) {
                        toast(context.getString(R.string.dialog_no_available_favorite_folder))
                        return@onSuccess
                    }
                    val actualIsFavorited = folders.any { it.favState == 1 }
                    if (actualIsFavorited != isFavorited) {
                        isFavorited = actualIsFavorited
                        renderFavoriteState()
                    }
                    displayFavoriteFolderChooser(folders)
                }
                .onFailure { toast(it.message ?: context.getString(R.string.dialog_load_favorite_folder_failed)) }
        }
    }

    private fun displayFavoriteFolderChooser(folders: List<FavoriteFolderModel>) {
        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        // 固定尺寸卡片（布局根 match_parent 依赖窗口定尺寸），fill 载体下显式传高
        DialogWindowFit.overlay(
            dialog,
            LayoutInflater.from(context).inflate(R.layout.dialog_favorite_folder, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "favFolder"
        )
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = if (isFavorited) context.getString(R.string.collection_)
        else context.getString(R.string.collection)

        val adapter = FavoriteFolderDialogAdapter(folders) { position ->
            val folder = folders.getOrNull(position) ?: return@FavoriteFolderDialogAdapter
            dialog.dismiss()
            scope.launch {
                // 点的是未收藏的夹 → 加入它并从其它夹移除（一次 deal 完成换夹）；
                // 点的是已收藏的夹 → 只从该夹移除。绝不能拿「目标夹」当删除目标。
                val plan = planFavoriteDeal(folders, folder.id)
                if (plan.isEmpty) {
                    toast(context.getString(R.string.dialog_no_available_favorite_folder))
                    return@launch
                }
                val result = favoriteRepository.updateFavorite(
                    video.aid,
                    addMediaIds = plan.addMediaIds,
                    delMediaIds = plan.delMediaIds
                )
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = plan.favoritedAfter
                        renderFavoriteState()
                        toast(
                            if (plan.addMediaIds != null) {
                                context.getString(R.string.dialog_favorited_to_format, folder.title)
                            } else {
                                context.getString(R.string.dialog_unfavorited_from_format, folder.title)
                            }
                        )
                        // 只要有「移出夹」的动作，当前收藏夹列表里的这张卡就必须移除：
                        // 换夹时 favoritedAfter 仍为 true，不能再用「是否已收藏」判断
                        if (plan.delMediaIds != null) {
                            onFavoriteRemoved?.invoke()
                        }
                    } else {
                        handleActionError(response.code, response.errorMessage)
                    }
                }.onFailure { toast(it.message ?: context.getString(R.string.dialog_action_failed)) }
            }
        }

        recyclerView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                adapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
    }

    private fun refreshFavoriteState() {
        if (!supportsFavorite) return
        if (!sessionGateway.isLoggedIn()) return
        scope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: return@launch
            favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = response.data?.list.orEmpty().any { it.favState == 1 }
                        renderFavoriteState()
                    }
                }
        }
    }

    private fun renderFavoriteState() {
        if (isFavorited) {
            binding.textFavoriteTitle.text = context.getString(R.string.menu_already_favorited)
            binding.textFavoriteTitle.setTextColor(
                ContextCompat.getColor(context, R.color.pink)
            )
        } else {
            binding.textFavoriteTitle.text = context.getString(R.string.menu_favorite)
            binding.textFavoriteTitle.setTextColor(
                resolveThemedTextColor(binding.root.context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())
            )
        }
    }

    private fun renderWatchLaterState() {
        if (isInWatchLater) {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_already_in_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                ContextCompat.getColor(context, R.color.pink)
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_added_summary)
        } else {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_add_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                resolveThemedTextColor(binding.root.context, android.R.attr.textColorPrimary, 0xFFEEEEEE.toInt())
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_summary)
        }
    }

    private fun configureContent() {
        binding.textTitle.text = formatDialogTitle(
            video.title.ifBlank { context.getString(R.string.video) }
        )
        binding.buttonDeleteHistoryRecord.visibility =
            if (supportsHistoryRecordDelete) View.VISIBLE else View.GONE
        binding.buttonWatchLater.visibility = if (supportsWatchLater) View.VISIBLE else View.GONE
        binding.buttonVideoDetail.visibility = if (supportsVideoDetail) View.VISIBLE else View.GONE
        binding.buttonUpSpace.visibility = if (supportsUpSpace) View.VISIBLE else View.GONE
        binding.buttonFavorite.visibility = if (supportsFavorite) View.VISIBLE else View.GONE
        binding.buttonDislikeVideo.visibility = if (supportsDislikeVideo) View.VISIBLE else View.GONE
        binding.buttonDislikeArea.visibility = if (supportsAreaDislike) View.VISIBLE else View.GONE
        recomputeFocusChain()
        binding.textFavoriteSummary.text = context.getString(R.string.menu_favorite_summary)
        binding.textSubtitle.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_live_card_subtitle
            } else {
                R.string.menu_video_card_subtitle
            }
        )
        binding.textDislikeVideoTitle.text = context.getString(R.string.menu_dislike_video)
        binding.textDislikeVideoSummary.text = context.getString(R.string.menu_dislike_video_summary)
        renderWatchLaterState()
        renderFavoriteState()
        renderDislikeUpState()
        renderDislikeAreaState()
    }

    /**
     * 根据各菜单项的实际显隐，重连纵向 nextFocus 关系，
     * 避免某个按钮被 GONE 后方向键焦点悬空或落到隐藏项上。
     */
    private fun recomputeFocusChain() {
        val focusableButtons = buildList {
            if (supportsHistoryRecordDelete) add(R.id.button_delete_history_record)
            if (supportsWatchLater) add(R.id.button_watch_later)
            if (supportsVideoDetail) add(R.id.button_video_detail)
            if (supportsUpSpace) add(R.id.button_up_space)
            if (supportsFavorite) add(R.id.button_favorite)
            if (supportsDislikeVideo) add(R.id.button_dislike_video)
            // dislike_up 恒显示（信息不全时仅置灰禁用）
            add(R.id.button_dislike_up)
            if (supportsAreaDislike) add(R.id.button_dislike_area)
        }
        val indexById = focusableButtons.withIndex().associate { (i, id) -> id to i }
        for (id in focusableButtons) {
            val view = findViewById<View>(id) ?: continue
            val idx = indexById.getValue(id)
            view.nextFocusDownId =
                focusableButtons.getOrNull(idx + 1) ?: View.NO_ID
            view.nextFocusUpId =
                focusableButtons.getOrNull(idx - 1) ?: View.NO_ID
        }
    }

    private fun renderDislikeUpState() {
        val upName = video.authorName.trim()
        val hasUpName = upName.isNotBlank()
        binding.textDislikeUpTitle.text = if (hasUpName) {
            context.getString(R.string.menu_dislike_up_title_with_name, upName)
        } else {
            context.getString(R.string.menu_dislike_up)
        }
        binding.textDislikeUpSummary.text = if (hasUpName) {
            context.getString(
                if (isLiveFeedbackCard) {
                    R.string.menu_dislike_live_up_summary
                } else {
                    R.string.menu_dislike_up_summary
                }
            )
        } else {
            context.getString(R.string.menu_dislike_up_unknown_summary)
        }
        val enabled = canDislikeUp() && !isActionInProgress
        binding.buttonDislikeUp.isEnabled = enabled
        binding.buttonDislikeUp.isClickable = enabled
        binding.buttonDislikeUp.isFocusable = enabled
        binding.buttonDislikeUp.alpha = if (canDislikeUp()) 1f else 0.45f
    }

    private fun renderDislikeAreaState() {
        if (!supportsAreaDislike) return
        binding.textDislikeAreaTitle.text =
            context.getString(R.string.menu_dislike_area_title_with_name, video.typeName.trim())
        binding.textDislikeAreaSummary.text =
            context.getString(R.string.menu_dislike_area_summary)
    }

    private fun setActionInProgress(inProgress: Boolean) {
        isActionInProgress = inProgress
        val watchLaterEnabled = supportsWatchLater && !inProgress
        val favoriteEnabled = supportsFavorite && !inProgress
        val dislikeVideoEnabled = supportsDislikeVideo && !inProgress
        val deleteHistoryEnabled = supportsHistoryRecordDelete && !inProgress
        binding.buttonDeleteHistoryRecord.isEnabled = deleteHistoryEnabled
        binding.buttonDeleteHistoryRecord.isClickable = deleteHistoryEnabled
        binding.buttonWatchLater.isEnabled = watchLaterEnabled
        binding.buttonWatchLater.isClickable = watchLaterEnabled
        binding.buttonFavorite.isEnabled = favoriteEnabled
        binding.buttonFavorite.isClickable = favoriteEnabled
        binding.buttonDislikeVideo.isEnabled = dislikeVideoEnabled
        binding.buttonDislikeVideo.isClickable = dislikeVideoEnabled
        binding.buttonWatchLater.alpha = if (watchLaterEnabled) 1f else 0.6f
        binding.buttonDeleteHistoryRecord.alpha = if (deleteHistoryEnabled) 1f else 0.6f
        binding.buttonFavorite.alpha = if (favoriteEnabled) 1f else 0.6f
        binding.buttonDislikeVideo.alpha = if (dislikeVideoEnabled) 1f else 0.6f
        renderDislikeUpState()
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            toast(context.getString(R.string.toast_need_login))
            return false
        }
        return true
    }

    private fun checkCsrfAndLogin(): Boolean {
        if (!checkLogin()) return false
        if (sessionGateway.requireCsrfToken() == null) {
            toast(context.getString(R.string.dialog_credential_error_retry_later))
            return false
        }
        return true
    }

    private fun handleActionError(code: Int, message: String?) {
        when (val error = sessionGateway.classifyActionError(code, message)) {
            is ActionError.SessionExpired -> handleAuthExpired()
            is ActionError.CsrfMismatch -> toast(context.getString(R.string.dialog_action_failed_retry_later))
            is ActionError.RiskControl -> toast(context.getString(R.string.dialog_risk_control_verify_hint))
            is ActionError.FrequencyLimit -> toast(error.message)
            is ActionError.Other -> toast(error.message)
            is ActionError.CsrfMissing -> toast(context.getString(R.string.dialog_credential_error_retry_later))
        }
    }

    private fun handleAuthExpired() {
        toast(context.getString(R.string.login_expired))
        dismiss()
    }

    private fun canDislikeUp(): Boolean = supportsUpSpace

    private fun formatDialogTitle(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.length <= MAX_TITLE_CHARS) {
            trimmed
        } else {
            trimmed.take(MAX_TITLE_CHARS - TITLE_ELLIPSIS.length) + TITLE_ELLIPSIS
        }
    }

    private fun toast(message: String) {
        AppToast.show(context, message)
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

}
