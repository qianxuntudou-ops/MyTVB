package com.tutu.myblbl.feature.player

import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.dialog.OwnerDetailDialog
import com.tutu.myblbl.ui.dialog.PlayerActionDialog
import com.tutu.myblbl.ui.dialog.VideoInfoDialog
import com.tutu.myblbl.feature.detail.UserSpaceFragment
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.feature.player.view.MyPlayerView

@UnstableApi
class VideoPlayerOverlayController(
    private val activity: AppCompatActivity,
    private val playerView: MyPlayerView,
    private val overlayCoordinator: PlayerOverlayCoordinator,
    private val uiCoordinator: PlaybackUiCoordinator,
    private val sessionCoordinator: PlayerSessionCoordinator,
    private val playerProvider: () -> androidx.media3.common.Player?,
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

    fun showChooseEpisodeDialog() {
        val episodes = sessionCoordinator.getEpisodes()
        val selectedEpisodeIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isEmpty()) {
            Toast.makeText(activity, "当前暂无可选分集", Toast.LENGTH_SHORT).show()
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.EPISODE_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.EPISODE))

        val dialog = AppCompatDialog(activity, R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_choose_episode)
        dialog.setCanceledOnTouchOutside(true)

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
        val catalogSource = episodes.firstOrNull()?.source ?: VideoPlayerViewModel.EpisodeCatalogSource.PAGES
        val currentVideoInfo = resolveCurrentVideoInfo()

        val currentPos = selectedEpisodeIndex + 1
        val totalCount = episodes.size
        titleView?.text = when (catalogSource) {
            VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON -> {
                val seasonTitle = latestVideoInfoProvider()?.view?.ugcSeason?.title.orEmpty()
                "合集${if (seasonTitle.isNotBlank()) "·$seasonTitle" else ""}($currentPos/$totalCount)"
            }
            VideoPlayerViewModel.EpisodeCatalogSource.PGC_EPISODES -> {
                val pgcTitle = latestVideoInfoProvider()?.view?.title.orEmpty()
                "${if (pgcTitle.isNotBlank()) pgcTitle else activity.getString(R.string.choose_episode)}($currentPos/$totalCount)"
            }
            VideoPlayerViewModel.EpisodeCatalogSource.PAGES -> {
                "选集($currentPos/$totalCount)"
            }
        }
        val showMoreInfo = catalogSource == VideoPlayerViewModel.EpisodeCatalogSource.PAGES && currentVideoInfo != null
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
        recyclerViewRelated.itemAnimator = null
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
            Toast.makeText(activity, "当前视频信息未加载完成", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, "当前视频信息未加载完成", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(activity, "UP主信息未加载完成", Toast.LENGTH_SHORT).show()
            return
        }
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.OWNER_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.OWNER))
        OwnerDetailDialog(
            context = activity,
            owner = owner,
            onOpenSpace = { mid ->
                onOpenFragmentFromHost(UserSpaceFragment.newInstance(mid), "user_space")
            },
            onPlayVideo = { video, playQueue ->
                hideContentPanel(restoreFocus = false)
                onHideNextPreview()
                onPlayRelatedVideo(video, playQueue)
            },
            currentAid = view.aid,
            currentVideoId = view.bvid
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

    private fun keepControllerVisibleForOverlay() {
        if (!playerView.isControllerFullyVisible()) {
            playerView.showController()
        }
        playerView.removeControllerHideCallbacks()
    }

    private fun restoreControllerAfterOverlay() {
        if (!playerView.isControllerFullyVisible()) {
            playerView.showController()
        }
        // 延迟请求焦点，等控制器完全显示后再恢复，避免控制器还在隐藏/动画状态导致焦点请求失败
        playerView.post {
            overlayCoordinator.restoreFocus(playerView)
        }
        playerView.resetControllerHideCallbacks()
    }

    private fun restoreControllerAfterRelatedPanel() {
        // Controller was kept visible via keepControllerVisibleForOverlay() while the panel was open.
        // Only restore focus and reset auto-hide timers — avoid calling showController() which
        // posts requestPlayPauseFocus() and causes a visible focus flicker.
        playerView.restoreRememberedFocus()
        playerView.resetControllerHideCallbacks()
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
