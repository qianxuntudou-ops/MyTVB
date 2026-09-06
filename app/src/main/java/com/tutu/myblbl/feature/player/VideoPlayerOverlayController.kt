package com.tutu.myblbl.feature.player

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.TypedValue
import com.tutu.myblbl.R
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.model.video.detail.VideoDetailModel
import com.tutu.myblbl.ui.adapter.VideoAdapter
import com.tutu.myblbl.ui.dialog.OwnerDetailDialog
import com.tutu.myblbl.ui.dialog.PlayerActionDialog
import com.tutu.myblbl.ui.dialog.VideoInfoDialog
import com.tutu.myblbl.feature.detail.UserSpaceFragment
import com.tutu.myblbl.core.ui.base.VideoRecyclerViewTuning
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.feature.player.view.MyPlayerView
import androidx.appcompat.widget.AppCompatTextView

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

    private companion object {
        const val SEASON_GROUP_SIZE = 50
    }

    fun showChooseEpisodeDialog() {
        val episodes = sessionCoordinator.getEpisodes()
        val selectedEpisodeIndex = sessionCoordinator.getSelectedEpisodeIndex()
        if (episodes.isEmpty()) {
            Toast.makeText(activity, "当前暂无可选分集", Toast.LENGTH_SHORT).show()
            return
        }
        if (episodes.firstOrNull()?.source == VideoPlayerViewModel.EpisodeCatalogSource.UGC_SEASON) {
            showSeasonEpisodeDialog(episodes, selectedEpisodeIndex)
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
     * 合集专属弹窗：贴屏幕右侧的竖版列表（B 站风格），
     * 全集按 [SEASON_GROUP_SIZE] 个一组分 tab 展示；打开时焦点落在
     * 正在播放视频所在分组的 tab 上，列表自动定位到正在播放的视频。
     */
    private fun showSeasonEpisodeDialog(
        episodes: List<VideoPlayerViewModel.PlayableEpisode>,
        selectedEpisodeIndex: Int
    ) {
        overlayCoordinator.rememberFocusRestoreTarget(PlayerOverlayCoordinator.FocusTarget.EPISODE_BUTTON)
        keepControllerVisibleForOverlay()
        uiCoordinator.transition(UiEvent.PanelOpened(PanelType.EPISODE))

        // Dialog 层兜底：焦点在视频列表内时按返回不关弹窗，改回分组 tab。
        // 列表引用与回 tab 动作在下方初始化后才有值，先以可空引用承接。
        var seasonDialogRef: SeasonEpisodeDialog? = null
        var recyclerViewRef: RecyclerView? = null
        var backToTabAction: (() -> Unit)? = null
        val dialog = SeasonEpisodeDialog(
            activity,
            isFocusInsideList = {
                isFocusInsideRecyclerView(seasonDialogRef?.window?.currentFocus, recyclerViewRef)
            },
            onBackInsideList = { backToTabAction?.invoke() }
        ).also { seasonDialogRef = it }
        dialog.setCanceledOnTouchOutside(true)
        // 贴屏幕右侧、垂直居中；宽高由布局根节点固定（px600×px935），
        // floating dialog 上 MATCH_PARENT 窗口高会退化为 wrap 内容，不能用
        dialog.window?.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        dialog.setContentView(R.layout.dialog_choose_episode_season)

        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        val tabBar = dialog.findViewById<LinearLayout>(R.id.tab_bar)
        if (recyclerView == null || tabBar == null) {
            dialog.dismiss()
            return
        }
        recyclerViewRef = recyclerView

        val seasonTitle = latestVideoInfoProvider()?.view?.ugcSeason?.title.orEmpty()
        titleView?.text =
            "合集${if (seasonTitle.isNotBlank()) "·$seasonTitle" else ""}(${selectedEpisodeIndex + 1}/${episodes.size})"

        val groups = episodes.chunked(SEASON_GROUP_SIZE)
        var activeGroup = (selectedEpisodeIndex / SEASON_GROUP_SIZE).coerceIn(0, groups.lastIndex)
        val tabViews = mutableListOf<TextView>()

        fun backToSeasonTab() {
            tabViews.getOrNull(activeGroup)?.requestFocus()
        }
        backToTabAction = { backToSeasonTab() }

        val panelAdapter = PlayerSeasonEpisodePanelAdapter(
            onClick = { globalIndex ->
                dialog.dismiss()
                onHideNextPreview()
                onPlayEpisode(globalIndex)
            },
            onBackToTab = { backToSeasonTab() }
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = panelAdapter

        fun selectSeasonGroup(index: Int) {
            activeGroup = index
            tabViews.forEachIndexed { i, tab -> tab.isSelected = i == index }
            val offset = index * SEASON_GROUP_SIZE
            panelAdapter.submitGroup(groups[index], offset, selectedEpisodeIndex)
            // 默认定位到正在播放的视频；该组没有播放项则回到组首
            val localIndex = selectedEpisodeIndex - offset
            recyclerView.scrollToPosition(
                if (localIndex in groups[index].indices) localIndex else 0
            )
        }

        fun enterSeasonListTarget(index: Int): Int {
            val localIndex = selectedEpisodeIndex - index * SEASON_GROUP_SIZE
            return if (localIndex in groups[index].indices) localIndex else 0
        }

        groups.forEachIndexed { index, group ->
            val start = index * SEASON_GROUP_SIZE + 1
            val end = index * SEASON_GROUP_SIZE + group.size
            val tab = buildSeasonTab("$start-$end")
            tab.setOnFocusChangeListener { _, hasFocus ->
                tab.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (hasFocus) R.color.colorAccent else R.color.textColor
                    )
                )
                // 焦点在 tab 间移动即联动切换对应分组列表
                if (hasFocus) selectSeasonGroup(index)
            }
            tab.setOnClickListener { tab.requestFocus() }
            // 边缘处的左右键直接消费，焦点停在边界 tab 上
            tab.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (index > 0) tabViews[index - 1].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (index < tabViews.lastIndex) tabViews[index + 1].requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusTargetEpisode(recyclerView, enterSeasonListTarget(index))
                        true
                    }
                    else -> false
                }
            }
            tabBar.addView(tab)
            tabViews.add(tab)
        }

        selectSeasonGroup(activeGroup)

        // 初始焦点：优先落在正在播放的视频上；拿不到播放信息时才落分组 tab
        fun requestInitialSeasonFocus() {
            val localIndex = selectedEpisodeIndex - activeGroup * SEASON_GROUP_SIZE
            if (localIndex in groups[activeGroup].indices) {
                focusTargetEpisode(recyclerView, localIndex)
            } else {
                tabViews.getOrNull(activeGroup)?.requestFocus()
            }
        }

        dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        dialog.window?.decorView?.viewTreeObserver
                            ?.removeOnWindowFocusChangeListener(this)
                        requestInitialSeasonFocus()
                    }
                }
            }
        )

        dialog.setOnDismissListener {
            com.tutu.myblbl.core.common.log.AppLog.d("SeasonPanel", "dialog dismissed (用户按返回关闭)")
            uiCoordinator.transition(UiEvent.PanelClosed)
            if (isViewActive()) {
                restoreControllerAfterOverlay()
            }
        }
        dialog.show()
        // 布局完成后兜底设初始焦点；窗口焦点监听在部分路径（触摸唤起）下时机不稳
        recyclerView.post {
            if (dialog.isShowing) {
                requestInitialSeasonFocus()
            }
        }
        com.tutu.myblbl.core.common.log.AppLog.d(
            "SeasonPanel",
            "dialog shown groups=${groups.size} activeGroup=$activeGroup tabs=${tabViews.size}"
        )
    }

    private fun buildSeasonTab(label: String): AppCompatTextView {
        val res = activity.resources
        return AppCompatTextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.px24))
            setTextColor(ContextCompat.getColor(activity, R.color.textColor))
            setBackgroundResource(R.drawable.cell_background)
            setPadding(
                res.getDimensionPixelSize(R.dimen.px25),
                res.getDimensionPixelSize(R.dimen.px15),
                res.getDimensionPixelSize(R.dimen.px25),
                res.getDimensionPixelSize(R.dimen.px15)
            )
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = res.getDimensionPixelSize(R.dimen.px15)
            }
        }
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

/**
 * 合集选集弹窗。焦点落在视频列表内时拦截返回键：回调 [onBackInsideList]
 * 把焦点送回分组 tab，而不是关闭弹窗；焦点在 tab 或其它位置时正常关闭。
 */
private class SeasonEpisodeDialog(
    context: AppCompatActivity,
    private val isFocusInsideList: () -> Boolean,
    private val onBackInsideList: () -> Unit
) : AppCompatDialog(context, R.style.DialogTheme) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val inside = isFocusInsideList()
        com.tutu.myblbl.core.common.log.AppLog.d(
            "SeasonPanel",
            "dialog onKeyDown keyCode=$keyCode action=${event.action} insideList=$inside focus=${window?.currentFocus?.let { it::class.java.simpleName } ?: "null"}"
        )
        if (keyCode == KeyEvent.KEYCODE_BACK && inside) {
            // 消费 DOWN，避免系统进入返回键 tracking 流程
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val inside = isFocusInsideList()
        com.tutu.myblbl.core.common.log.AppLog.d(
            "SeasonPanel",
            "dialog onKeyUp keyCode=$keyCode action=${event.action} insideList=$inside"
        )
        if (keyCode == KeyEvent.KEYCODE_BACK && inside) {
            onBackInsideList()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

private fun isFocusInsideRecyclerView(focus: View?, recyclerView: RecyclerView?): Boolean {
    if (focus == null || recyclerView == null) return false
    var v: View? = focus
    while (v != null) {
        if (v === recyclerView) return true
        v = v.parent as? View
    }
    return false
}
