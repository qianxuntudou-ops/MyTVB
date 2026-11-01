package com.mytvb.feature.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView

import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.core.common.log.AppLog
import com.mytvb.R
import com.mytvb.model.video.Owner
import com.mytvb.model.video.VideoModel
import com.mytvb.model.video.detail.VideoDetailModel
import com.mytvb.ui.adapter.VideoAdapter
import com.mytvb.ui.dialog.OwnerDetailDialog
import com.mytvb.ui.dialog.PlayerActionDialog
import com.mytvb.ui.dialog.VideoInfoDialog
import com.mytvb.feature.detail.UserSpaceFragment
import com.mytvb.core.ui.base.VideoRecyclerViewTuning
import com.mytvb.core.ui.layout.WrapContentGridLayoutManager
import com.mytvb.feature.player.comment.CommentPanelDialog
import com.mytvb.feature.player.view.MyPlayerView
import com.mytvb.model.player.ViewPoint
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.base.AppToast

@UnstableApi
class VideoPlayerOverlayController(
    private val activity: AppCompatActivity,
    private val playerView: MyPlayerView,
    private val overlayCoordinator: PlayerOverlayCoordinator,
    private val uiCoordinator: PlaybackUiCoordinator,
    private val sessionCoordinator: PlayerSessionCoordinator,
    private val latestVideoInfoProvider: () -> VideoDetailModel?,
    private val relatedAdapter: VideoAdapter,
    private val viewRelated: View,
    private val dimBackground: View,
    private val recyclerViewRelated: RecyclerView,
    private val textMoreTitle: TextView,
    private val onPlayEpisode: (Int) -> Unit,
    private val onPlayRelatedVideo: (VideoModel, List<VideoModel>) -> Unit,
    private val onOpenFragmentFromHost: (Fragment, String) -> Unit,
    private val onHideNextPreview: () -> Unit,
    private val isViewActive: () -> Boolean
) {

    /** 当前面板打开时控制栏处于隐藏态且未强制拉起（隐藏态 UP 直达），关闭后应保持隐藏。 */
    private var controllerWasHiddenForOverlay: Boolean = false

    fun showChooseEpisodeDialog(fromHiddenDpad: Boolean = false) {
        val episodes = sessionCoordinator.getEpisodes()
        val selectedEpisodeIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isEmpty()) {
            AppToast.show(activity, activity.getString(R.string.player_no_episodes_toast))
            return
        }
        // 合集（UGC）与连续剧/番剧（PGC）统一走贴右侧的竖版合集面板；
        // 居中网格弹窗只留给多P视频
        when (episodes.firstOrNull()?.source) {
            VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON,
            VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES -> {
                showSeasonEpisodeDialog(episodes, selectedEpisodeIndex, fromHiddenDpad)
                return
            }
            else -> Unit
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.EPISODE_BUTTON)
        keepControllerVisibleForOverlay(forceShowController = !fromHiddenDpad)
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.EPISODE))

        val dialog = AppCompatDialog(activity, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        // 固定尺寸卡片（布局根 match_parent 依赖窗口定尺寸），fill 载体下显式传高
        DialogWindowFit.overlay(
            dialog,
            LayoutInflater.from(activity).inflate(R.layout.dialog_choose_episode, null),
            activity.resources.getDimensionPixelSize(R.dimen.px1000),
            heightPx = activity.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "episode"
        )

        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        val moreInfoButton = dialog.findViewById<TextView>(R.id.button_more_info)
        val targetIndex = selectedEpisodeIndex

        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        recyclerView?.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                        focusTargetEpisode(recyclerView, targetIndex)
                    }
                }
            }
        )
        val currentVideoInfo = resolveCurrentVideoInfo()

        val currentPos = selectedEpisodeIndex + 1
        val totalCount = episodes.size
        titleView?.text = "${activity.getString(R.string.choose_episode)}($currentPos/$totalCount)"
        val showMoreInfo = currentVideoInfo != null
        moreInfoButton?.isVisible = showMoreInfo
        moreInfoButton?.setOnClickListener(
            if (showMoreInfo) {
                View.OnClickListener {
                    showVideoInfoDialog(
                        restorePlayerFocus = false,
                        onDismiss = {
                            moreInfoButton.post { moreInfoButton.requestFocus() }
                        }
                    )
                }
            } else {
                null
            }
        )

        val episodeDialogAdapter = PlayerEpisodePanelAdapter { index ->
            dialog.dismiss()
            onHideNextPreview()
            onPlayEpisode(index)
        }.apply {
            submitList(episodes)
            setSelectedIndex(selectedEpisodeIndex)
        }

        recyclerView?.apply {
            layoutManager = WrapContentGridLayoutManager(activity, 2)
            adapter = episodeDialogAdapter
        }

        dialog.setOnDismissListener {
            uiCoordinator.transition(UiEvent.PanelClosed)
            if (isViewActive()) {
                restoreControllerAfterOverlay()
            }
        }
        dialog.show()
    }

    /**
     * 章节面板（view_points）：缩略图+标题+时间范围单列列表，点击直接 seek 到章节
     * 起点；打开时定位并聚焦当前章。弹出/关闭协议与选集面板一致。
     */
    fun showChapterDialog(
        viewPoints: List<ViewPoint>,
        currentPositionMs: Long,
        onSeek: (Long) -> Unit
    ) {
        if (viewPoints.isEmpty()) return
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.CHAPTER_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.CHAPTER))

        val currentIndex = viewPoints.indexOfLast { currentPositionMs >= it.fromMs }.coerceAtLeast(0)
        val dialog = AppCompatDialog(activity, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        DialogWindowFit.overlay(
            dialog,
            LayoutInflater.from(activity).inflate(R.layout.dialog_chapter_list, null),
            activity.resources.getDimensionPixelSize(R.dimen.px1100),
            heightPx = activity.resources.getDimensionPixelSize(R.dimen.px700),
            tag = "chapter"
        )

        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        titleView?.text = "${activity.getString(R.string.video_chapter)}(${currentIndex + 1}/${viewPoints.size})"
        dialog.findViewById<View>(R.id.button_close)?.setOnClickListener { dialog.dismiss() }

        val chapterAdapter = ChapterPanelAdapter { point ->
            dialog.dismiss()
            onSeek(point.fromMs)
        }.apply {
            submitList(viewPoints, currentIndex)
        }
        recyclerView?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = chapterAdapter
        }

        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        recyclerView?.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                        focusTargetChapter(recyclerView, currentIndex)
                    }
                }
            }
        )

        dialog.setOnDismissListener {
            uiCoordinator.transition(UiEvent.PanelClosed)
            if (isViewActive()) {
                restoreControllerAfterOverlay()
            }
        }
        dialog.show()
    }

    /** 章节面板窗口获焦后的初始定位：优先同步聚焦当前章，未布局时 scrollToPosition+双 post 兜底。 */
    private fun focusTargetChapter(recyclerView: RecyclerView?, targetIndex: Int) {
        val rv = recyclerView ?: return
        val holder = rv.findViewHolderForAdapterPosition(targetIndex)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            return
        }
        if (targetIndex in 0 until (rv.adapter?.itemCount ?: 0)) {
            rv.scrollToPosition(targetIndex)
        }
        rv.post {
            rv.post {
                val h = rv.findViewHolderForAdapterPosition(targetIndex)
                h?.itemView?.requestFocus()
            }
        }
    }

    /**
     * 合集/连续剧选集弹窗：贴屏幕右侧的竖版列表（B 站风格），面板本体在
     * [showSeasonEpisodePanelDialog]，这里只挂播放器自己的状态机
     * （面板开合、控制条恢复、切集前置动作）与标题组装。
     */
    private fun showSeasonEpisodeDialog(
        episodes: List<VideoPlayerViewModel.PlayableEpisode>,
        selectedEpisodeIndex: Int,
        fromHiddenDpad: Boolean = false
    ) {
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.EPISODE_BUTTON)
        keepControllerVisibleForOverlay(forceShowController = !fromHiddenDpad)
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.EPISODE))

        val posText = "(${selectedEpisodeIndex + 1}/${episodes.size})"
        val title = when (episodes.firstOrNull()?.source) {
            // 连续剧/番剧：标题栏直接用剧名（与原居中弹窗的标题规则一致）
            VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES -> {
                val pgcTitle = latestVideoInfoProvider()?.view?.title.orEmpty()
                "${if (pgcTitle.isNotBlank()) pgcTitle else activity.getString(R.string.choose_episode)}$posText"
            }
            else -> {
                val seasonTitle = latestVideoInfoProvider()?.view?.ugcSeason?.title.orEmpty()
                "${activity.getString(R.string.player_collection)}${if (seasonTitle.isNotBlank()) "·$seasonTitle" else ""}$posText"
            }
        }

        showSeasonEpisodePanelDialog(
            activity = activity,
            title = title,
            episodes = episodes,
            selectedEpisodeIndex = selectedEpisodeIndex,
            onPlayEpisode = { index ->
                onHideNextPreview()
                onPlayEpisode(index)
            },
            onDismiss = {
                uiCoordinator.transition(UiEvent.PanelClosed)
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
        )
    }

    /**
     * Called when the episode dialog window actually gains focus.
     * Tries to focus the target item synchronously (layout is done by this point).
     * Falls back to a double-post if the ViewHolder isn't available yet.
     */
    private fun focusTargetEpisode(recyclerView: RecyclerView?, targetIndex: Int) {
        val rv = recyclerView ?: return
        // Fast path: items should already be laid out when the window gets focus
        val holder = rv.findViewHolderForAdapterPosition(targetIndex)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            // Center in next frame (doesn't affect focus)
            rv.post {
                centerItemInView(rv, targetIndex)
            }
            return
        }
        // Slow path: target item is off-screen, scroll to it first then focus after layout
        if (targetIndex in 0 until (rv.adapter?.itemCount ?: 0)) {
            rv.scrollToPosition(targetIndex)
        }
        rv.post {
            // First post: layout pass after scrollToPosition
            rv.post {
                // Second post: ViewHolder should now be available
                val h = rv.findViewHolderForAdapterPosition(targetIndex)
                if (h?.itemView != null) {
                    h.itemView.requestFocus()
                    centerItemInView(rv, targetIndex)
                }
            }
        }
    }

    private fun centerItemInView(rv: RecyclerView, position: Int) {
        val lm = rv.layoutManager as? WrapContentGridLayoutManager ?: return
        val holder = rv.findViewHolderForAdapterPosition(position) ?: return
        if (rv.height > 0 && holder.itemView.height > 0) {
            val centerOffset = (rv.height - holder.itemView.height) / 2
            lm.scrollToPositionWithOffset(position, centerOffset)
        }
    }

    private var relatedPanelFocusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    fun showRelatedPanel() {
        playerView.rememberCurrentFocusTarget()
        overlayCoordinator.onRelatedPanelShown()
        uiCoordinator.onRelatedPanelShown()
        keepControllerVisibleForOverlay()
        textMoreTitle.text = activity.getString(R.string.related_video)
        recyclerViewRelated.layoutManager =
            GridLayoutManager(activity, 1, RecyclerView.HORIZONTAL, false)
        recyclerViewRelated.adapter = relatedAdapter
        VideoRecyclerViewTuning.apply(recyclerViewRelated, relatedAdapter)
        if (viewRelated.isVisible) {
            focusRelatedItem()
            return
        }
        dimBackground.visibility = View.VISIBLE
        dimBackground.setOnClickListener { hideContentPanel() }
        viewRelated.clearAnimation()
        viewRelated.visibility = View.VISIBLE
        AnimationUtils.loadAnimation(activity, R.anim.slide_up).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    recyclerViewRelated.post { focusRelatedItem() }
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewRelated.startAnimation(this)
        }
        setupRelatedPanelFocusTrap()
    }

    fun hideContentPanel(restoreFocus: Boolean = true) {
        overlayCoordinator.onRelatedPanelHidden()
        removeRelatedPanelFocusTrap()
        if (uiCoordinator.panelState == PlaybackUiCoordinator.PanelState.Related) {
            uiCoordinator.transition(UiEvent.PanelClosed)
        }
        if (!viewRelated.isVisible) {
            dimBackground.visibility = View.GONE
            dimBackground.setOnClickListener(null)
            if (restoreFocus && isViewActive()) {
                restoreControllerAfterRelatedPanel()
            }
            return
        }
        dimBackground.visibility = View.GONE
        dimBackground.setOnClickListener(null)
        viewRelated.clearAnimation()
        AnimationUtils.loadAnimation(activity, R.anim.slide_down).apply {
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) = Unit

                override fun onAnimationEnd(animation: Animation?) {
                    viewRelated.visibility = View.GONE
                    if (restoreFocus && isViewActive()) {
                        restoreControllerAfterRelatedPanel()
                    }
                }

                override fun onAnimationRepeat(animation: Animation?) = Unit
            })
            viewRelated.startAnimation(this)
        }
    }

    fun showVideoInfoDialog(
        restorePlayerFocus: Boolean = true,
        onDismiss: (() -> Unit)? = null
    ) {
        val video = resolveCurrentVideoInfo()
        if (video == null) {
            AppToast.show(activity, activity.getString(R.string.player_video_info_not_loaded))
            return
        }
        if (restorePlayerFocus) {
            keepControllerVisibleForOverlay()
            playerView.rememberCurrentFocusTarget()
            uiCoordinator.transition(UiEvent.PanelOpened(PanelType.ACTION))
        }
        VideoInfoDialog(
            context = activity,
            coverUrl = video.coverUrl,
            title = video.title,
            description = video.desc
        ).apply {
            setOnDismissListener {
                if (restorePlayerFocus) {
                    uiCoordinator.transition(UiEvent.PanelClosed)
                }
                if (restorePlayerFocus && isViewActive()) {
                    playerView.showController()
                    playerView.restoreRememberedFocus()
                    playerView.resetControllerHideCallbacks()
                }
                onDismiss?.invoke()
            }
            show()
        }
    }

    private fun resolveCurrentVideoInfo(): VideoModel? {
        val detailView = latestVideoInfoProvider()?.view
        val selectedEpisode = sessionCoordinator.getSelectedEpisode()
        val currentVideo = sessionCoordinator.getCurrentVideo()
        if (detailView == null && currentVideo == null) {
            return null
        }
        return VideoModel(
            aid = currentVideo?.aid ?: detailView?.aid ?: selectedEpisode?.aid ?: 0L,
            bvid = currentVideo?.bvid
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.bvid
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.bvid.orEmpty(),
            cid = detailView?.cid ?: selectedEpisode?.cid ?: currentVideo?.cid ?: 0L,
            title = detailView?.title
                ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.title
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.title.orEmpty(),
            pic = currentVideo?.coverUrl
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.pic
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.cover
                    ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.pic.orEmpty(),
            cover = currentVideo?.cover
                ?.takeIf { it.isNotBlank() }
                ?: detailView?.pic
                    ?.takeIf { it.isNotBlank() }
                ?: selectedEpisode?.cover.orEmpty(),
            desc = detailView?.desc
                ?.takeIf { it.isNotBlank() }
                ?: currentVideo?.desc
                    ?.takeIf { it.isNotBlank() }
                ?: "",
            pubDate = currentVideo?.pubDate ?: detailView?.pubDate ?: 0L,
            createTime = currentVideo?.createTime ?: detailView?.createTime ?: 0L,
            owner = currentVideo?.owner ?: detailView?.owner,
            stat = currentVideo?.stat ?: detailView?.stat,
            isUpowerExclusive = detailView?.isUpowerExclusive ?: currentVideo?.isUpowerExclusive ?: false,
            isChargingArc = detailView?.isChargingArc ?: currentVideo?.isChargingArc ?: false,
            elecArcType = detailView?.elecArcType ?: currentVideo?.elecArcType ?: 0,
            elecArcBadge = detailView?.elecArcBadge ?: currentVideo?.elecArcBadge.orEmpty(),
            privilegeType = detailView?.privilegeType ?: currentVideo?.privilegeType ?: 0
        )
    }

    fun showPlayerActionDialog() {
        val view = latestVideoInfoProvider()?.view
        val aid = view?.aid ?: 0L
        val bvid = view?.bvid.orEmpty()
        if (aid <= 0L && bvid.isBlank()) {
            AppToast.show(activity, activity.getString(R.string.player_video_info_not_loaded))
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.MORE_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.ACTION))
        PlayerActionDialog(
            context = activity,
            aid = aid,
            bvid = bvid,
            ownerMid = view?.owner?.mid ?: 0L
        ).apply {
            setOnDismissListener {
                uiCoordinator.transition(UiEvent.PanelClosed)
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
            show()
        }
    }

    fun showOwnerDetailDialog() {
        val view = latestVideoInfoProvider()?.view
        val owner = view?.owner
        if (owner == null || owner.mid <= 0L) {
            AppToast.show(activity, activity.getString(R.string.player_up_info_not_loaded))
            return
        }
        showOwnerDetailDialog(owner)
    }

    /**
     * 打开用户主页弹窗（评论面板菜单键传入评论用户的 Owner 复用同一路径）。
     * currentAid/currentVideoId/currentSeasonId 仍取当前视频：仅用于合集/投稿 tab
     * 定位「正在播放」项，他人主页无此视频时无副作用。
     */
    fun showOwnerDetailDialog(owner: Owner) {
        val view = latestVideoInfoProvider()?.view
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.OWNER_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.OWNER))
        OwnerDetailDialog(
            context = activity,
            owner = owner,
            onPlayVideo = { video, playQueue ->
                hideContentPanel(restoreFocus = false)
                onHideNextPreview()
                onPlayRelatedVideo(video, playQueue)
            },
            currentAid = view?.aid ?: 0L,
            currentVideoId = view?.bvid.orEmpty(),
            currentSeasonId = view?.ugcSeason?.id ?: 0L
        ).apply {
            setOnDismissListener {
                uiCoordinator.transition(UiEvent.PanelClosed)
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
            show()
        }
    }

    fun showCommentPanel() {
        val video = resolveCurrentVideoInfo()
        if (video == null || video.aid <= 0L) {
            AppToast.show(activity, activity.getString(R.string.player_video_info_not_loaded))
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.COMMENT_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.COMMENT))
        CommentPanelDialog(
            activity = activity,
            aid = video.aid,
            upMid = video.owner?.mid ?: 0L,
            commentCount = video.stat?.reply ?: 0L,
            // 面板已在回调前自行 dismiss（PanelClosed/焦点恢复正常走完），这里直接开主页
            onOpenOwnerSpace = { item ->
                showOwnerDetailDialog(
                    Owner(mid = item.mid, name = item.userName, face = item.avatarUrl.orEmpty())
                )
            },
            onDismissed = {
                uiCoordinator.transition(UiEvent.PanelClosed)
                if (isViewActive()) {
                    restoreControllerAfterOverlay()
                }
            }
        ).showPanel()
    }

    /**
     * 面板打开期间保持控制栏可见：挂起自动隐藏调度（show 动画结束回调的
     * 重挂也被拦截，否则面板开着约 3 秒后控制栏会自动缩回）。
     *
     * @param forceShowController false=控制栏隐藏态直达面板（控制栏隐藏时按 UP
     * 直达选集/合集）：不强制拉起控制栏，面板关闭后也保持隐藏（v2.1.14 反馈）。
     */
    private fun keepControllerVisibleForOverlay(forceShowController: Boolean = true) {
        controllerWasHiddenForOverlay =
            !forceShowController && !playerView.isControllerFullyVisible()
        if (forceShowController && !playerView.isControllerFullyVisible()) {
            playerView.showController()
        }
        playerView.setControllerHideCallbacksSuspended(true)
    }

    private fun restoreControllerAfterOverlay() {
        val keepHidden = controllerWasHiddenForOverlay
        controllerWasHiddenForOverlay = false
        if (!keepHidden && !playerView.isControllerFullyVisible()) {
            playerView.showController()
        }
        // 延迟请求焦点，等控制器完全显示后再恢复，避免控制器还在隐藏/动画状态导致焦点请求失败
        playerView.post {
            overlayCoordinator.restoreFocus(playerView)
        }
        // 解除挂起并立即按当前可见状态恢复一次自动隐藏调度
        playerView.setControllerHideCallbacksSuspended(false)
    }

    private fun restoreControllerAfterRelatedPanel() {
        // Controller was kept visible via keepControllerVisibleForOverlay() while the panel was open.
        // Only restore focus and reset auto-hide timers — avoid calling showController() which
        // posts requestPlayPauseFocus() and causes a visible focus flicker.
        playerView.restoreRememberedFocus()
        playerView.setControllerHideCallbacksSuspended(false)
    }

    private fun setupRelatedPanelFocusTrap() {
        removeRelatedPanelFocusTrap()
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (!viewRelated.isVisible || newFocus == null) return@OnGlobalFocusChangeListener
            var v: View? = newFocus
            while (v != null) {
                if (v === viewRelated) return@OnGlobalFocusChangeListener
                v = v.parent as? View
            }
            recyclerViewRelated.post { focusRelatedItem() }
        }
        viewRelated.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        relatedPanelFocusListener = listener
    }

    private fun focusRelatedItem(): Boolean {
        val itemCount = relatedAdapter.contentCount()
        val closeButton = viewRelated.findViewById<View?>(R.id.button_close_related)
        if (itemCount <= 0) {
            return closeButton?.requestFocus() == true || viewRelated.requestFocus()
        }
        recyclerViewRelated.findViewHolderForAdapterPosition(0)?.itemView?.let { itemView ->
            if (itemView.requestFocus()) {
                return true
            }
        }
        recyclerViewRelated.scrollToPosition(0)
        recyclerViewRelated.post {
            recyclerViewRelated.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: closeButton?.requestFocus()
                ?: viewRelated.requestFocus()
        }
        return true
    }

    private fun removeRelatedPanelFocusTrap() {
        relatedPanelFocusListener?.let { listener ->
            if (viewRelated.viewTreeObserver.isAlive) {
                viewRelated.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
        }
        relatedPanelFocusListener = null
    }
}
