package com.mytvb.feature.player.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewGroup
import com.mytvb.R

class MyPlayerControlViewLayoutManager(
    private val playerControlView: MyPlayerControlView,
    private val uiCoordinator: com.mytvb.feature.player.PlaybackUiCoordinator? = null
) {

    companion object {
        private const val ANIMATION_DURATION_MS = 250L
        private const val OVERFLOW_ANIMATION_DURATION_MS = 220L
        private const val PROGRESS_ONLY_DURATION_MS = 1000L
        private const val UX_STATE_ALL_VISIBLE = 0
        private const val UX_STATE_ONLY_PROGRESS_VISIBLE = 1
        private const val UX_STATE_NONE_VISIBLE = 2
        private const val UX_STATE_ANIMATING_HIDE = 3
        private const val UX_STATE_ANIMATING_SHOW = 4
    }

    private val controlsBackground: View = playerControlView.findViewById(R.id.exo_controls_background)
    private val centerControls: ViewGroup = playerControlView.findViewById(R.id.exo_center_controls)
    private val bottomBar: ViewGroup = playerControlView.findViewById(R.id.exo_bottom_bar)
    private val timeView: ViewGroup = playerControlView.findViewById(R.id.exo_time)
    private val titleView: ViewGroup = playerControlView.findViewById(R.id.view_title)
    private val subtitleView: View = playerControlView.findViewById(R.id.text_sub_title)
    private val bottomBarController: ViewGroup = playerControlView.findViewById(R.id.view_bottom_controller)
    private val timeBar: DefaultTimeBar = playerControlView.findViewById(R.id.exo_progress)
    private val basicControls: ViewGroup = playerControlView.findViewById(R.id.exo_basic_controls)
    private val extraControls: ViewGroup = playerControlView.findViewById(R.id.exo_extra_controls)
    private val extraControlsScrollView: ViewGroup = playerControlView.findViewById(R.id.exo_extra_controls_scroll_view)
    private val overflowShowButton: View = playerControlView.findViewById(R.id.exo_overflow_show)
    private val overflowHideButton: View = playerControlView.findViewById(R.id.exo_overflow_hide)
    private val orderedButtons: List<View> = listOf(
        playerControlView.findViewById(R.id.button_play),
        playerControlView.findViewById(R.id.button_refresh),
        playerControlView.findViewById(R.id.button_previous),
        playerControlView.findViewById(R.id.button_next),
        playerControlView.findViewById(R.id.button_rewind),
        playerControlView.findViewById(R.id.button_fast_forward),
        playerControlView.findViewById(R.id.button_dm_switch),
        playerControlView.findViewById(R.id.button_mirror),
        playerControlView.findViewById(R.id.button_play_speed),
        playerControlView.findViewById(R.id.exo_settings),
        playerControlView.findViewById(R.id.button_choose_episode),
        playerControlView.findViewById(R.id.button_more),
        playerControlView.findViewById(R.id.button_up_info),
        playerControlView.findViewById(R.id.button_comment),
        playerControlView.findViewById(R.id.button_subtitle),
        playerControlView.findViewById(R.id.button_related),
        playerControlView.findViewById(R.id.button_repeat),
        playerControlView.findViewById(R.id.button_live_settings),
        playerControlView.findViewById(R.id.button_close)
    )
    private val overflowPriority = listOf(
        R.id.button_live_settings,
        R.id.button_repeat,
        R.id.button_related,
        R.id.button_subtitle,
        R.id.button_up_info,
        R.id.button_comment,
        R.id.button_more,
        R.id.button_choose_episode,
        R.id.button_refresh,
        R.id.button_dm_switch,
        R.id.button_mirror,
        R.id.button_play_speed,
        R.id.button_next,
        R.id.button_previous,
        R.id.button_fast_forward,
        R.id.button_rewind,
        R.id.exo_settings
    )
    private val hiddenTranslationY by lazy {
        playerControlView.resources.getDimension(R.dimen.px147)
    }
    private val originalTimeBarTopMargin by lazy {
        (timeBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
    }
    private val originalBottomBarPaddingBottom = bottomBar.paddingBottom
    private val compactBottomBarPaddingBottom = originalBottomBarPaddingBottom / 2
    private val hideMainBarRunnable = Runnable { hideMainBar() }
    private val hideProgressBarRunnable = Runnable { hideProgressBar() }
    private val hideControllerRunnable = Runnable { hideController() }
    private val onLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            scheduleAdaptiveLayout()
        }
    }

    private val shownButtons = LinkedHashSet<View>()
    private var uxState: Int = UX_STATE_ALL_VISIBLE
    private var isAnimationEnabled: Boolean = true
    private var needToShowBars: Boolean = false
    private var isOverflowVisible: Boolean = false
    private var isCompactMode: Boolean = false
    private var progressOnlyUiEnabled: Boolean = true
    private var timeViewVisible: Boolean = true
    private var adaptiveLayoutScheduled: Boolean = false

    init {
        overflowShowButton.setOnClickListener { showOverflowControls() }
        overflowHideButton.setOnClickListener { hideOverflowControls() }
        extraControlsScrollView.visibility = View.INVISIBLE
        overflowShowButton.visibility = View.GONE
    }

    fun show(focusPlayPause: Boolean = false) {
        val fromSeekProgress = uxState == UX_STATE_ONLY_PROGRESS_VISIBLE
        if (!playerControlView.isVisible()) {
            playerControlView.visibility = View.VISIBLE
            playerControlView.updateAll()
            playerControlView.startProgressUpdates()
        }
        updateAdaptiveLayout()
        showAllBars()
        playerControlView.post {
            if (fromSeekProgress && !focusPlayPause) {
                playerControlView.requestTimeBarFocus()
            } else if (!timeBar.hasFocus()) {
                // 焦点已在进度条上时不要抢给播放按钮（timebar 快进后恢复控制栏的场景）
                playerControlView.requestPlayPauseFocus()
            }
        }
    }

    fun hide() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        removeHideCallbacks()
        if (!isAnimationEnabled) {
            hideController()
        } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
            hideProgressBar()
        } else {
            hideMainBar()
        }
    }

    fun hideImmediately() {
        if (uxState == UX_STATE_ANIMATING_HIDE || (uxState == UX_STATE_NONE_VISIBLE && !playerControlView.isVisible())) {
            return
        }
        removeHideCallbacks()
        cancelAllAnimations()
        hideController()
    }

    fun isFullyVisible(): Boolean = uxState == UX_STATE_ALL_VISIBLE && playerControlView.isVisible()

    fun setAnimationEnabled(enabled: Boolean) {
        isAnimationEnabled = enabled
    }

    fun setProgressOnlyUiEnabled(enabled: Boolean) {
        progressOnlyUiEnabled = enabled
    }

    fun setTimeViewVisible(visible: Boolean) {
        timeViewVisible = visible
        timeView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun getShowButton(button: View?): Boolean = button != null && shownButtons.contains(button)

    fun setShowButton(button: View?, show: Boolean) {
        if (button == null) {
            return
        }
        if (!show) {
            shownButtons.remove(button)
            removeViewFromParent(button)
            button.visibility = View.GONE
        } else {
            shownButtons.add(button)
            button.visibility = View.VISIBLE
        }
        scheduleAdaptiveLayout()
    }

    fun removeHideCallbacks() {
        playerControlView.removeCallbacks(hideControllerRunnable)
        playerControlView.removeCallbacks(hideMainBarRunnable)
        playerControlView.removeCallbacks(hideProgressBarRunnable)
    }

    fun resetHideCallbacks() {
        if (uxState == UX_STATE_ANIMATING_HIDE) {
            return
        }
        removeHideCallbacks()
        if (playerControlView.getShowTimeoutMs() <= 0 || !playerControlView.isVisible()) {
            return
        }
        if (!isAnimationEnabled) {
            playerControlView.postDelayed(hideControllerRunnable, playerControlView.getShowTimeoutMs().toLong())
        } else if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE && progressOnlyUiEnabled) {
            playerControlView.postDelayed(hideProgressBarRunnable, PROGRESS_ONLY_DURATION_MS)
        } else {
            playerControlView.postDelayed(hideMainBarRunnable, playerControlView.getShowTimeoutMs().toLong())
        }
    }

    /**
     * 瞬时进度条模式的松手收尾（blbl 节奏）：保持只有进度条+时间，
     * PROGRESS_ONLY_DURATION_MS 后由 hideProgressBar 动画淡出。
     * 返回 false 表示当前状态不适用（如持久细条模式），调用方需自行立即收起。
     */
    fun scheduleProgressOnlyFadeOutIfApplicable(): Boolean {
        if (uxState != UX_STATE_ONLY_PROGRESS_VISIBLE || !progressOnlyUiEnabled) return false
        removeHideCallbacks()
        playerControlView.postDelayed(hideProgressBarRunnable, PROGRESS_ONLY_DURATION_MS)
        return true
    }

    fun hideInfoOnlyLeftTimeBar() {
        if (titleView.visibility != View.VISIBLE || bottomBarController.visibility != View.VISIBLE) {
            return
        }
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        titleView.animate()
            .alpha(0f)
            .setDuration(300L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    titleView.visibility = View.INVISIBLE
                }
            })
            .start()
        bottomBarController.animate()
            .alpha(0f)
            .setDuration(300L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomBarController.visibility = View.INVISIBLE
                }
            })
            .start()
    }

    fun showInfoAfterEndFF() {
        if (titleView.visibility == View.VISIBLE && bottomBarController.visibility == View.VISIBLE) {
            return
        }
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        titleView.visibility = View.VISIBLE
        bottomBarController.visibility = View.VISIBLE
        titleView.animate().alpha(1f).setDuration(300L).setListener(null).start()
        bottomBarController.animate().alpha(1f).setDuration(300L).setListener(null).start()
    }

    fun onAttachedToWindow() {
        playerControlView.addOnLayoutChangeListener(onLayoutChangeListener)
        playerControlView.post { updateAdaptiveLayout() }
    }

    fun onDetachedFromWindow() {
        playerControlView.removeOnLayoutChangeListener(onLayoutChangeListener)
        removeHideCallbacks()
        cancelAllAnimations()
    }

    /**
     * 进入 seek 模式：只显示进度条+时间，隐藏其他 UI。
     * 无论当前处于什么状态都可以调用。
     */
    fun enterSeekProgressOnly() {
        cancelAllAnimations()
        removeHideCallbacks()
        playerControlView.visibility = View.VISIBLE
        controlsBackground.visibility = View.INVISIBLE
        centerControls.visibility = View.INVISIBLE
        titleView.visibility = View.INVISIBLE
        bottomBarController.visibility = View.INVISIBLE
        bottomBar.visibility = View.VISIBLE
        bottomBar.translationY = 0f
        bottomBar.alpha = 1f
        timeBar.showScrubber()
        // 不启动 progressRunnable，避免与 beginSeekPreview() 冲突导致进度条头乱跳
        // 位置由 beginSeekPreview() 通过 seek tick 驱动
        setUxState(UX_STATE_ONLY_PROGRESS_VISIBLE)
    }

    /**
     * 退出 seek 模式：立刻隐藏进度条和整个控制器，不闪现其他 UI。
     */
    fun exitSeekProgressOnly() {
        if (uxState == UX_STATE_ONLY_PROGRESS_VISIBLE) {
            cancelAllAnimations()
            playerControlView.stopProgressUpdates()
            playerControlView.visibility = View.GONE
            setUxState(UX_STATE_NONE_VISIBLE)
        }
    }

    fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) {
            controlsBackground.layout(0, 0, right - left, bottom - top)
            updateAdaptiveLayout()
        }
    }

    private fun hideMainBar() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_ONLY_PROGRESS_VISIBLE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        cancelAllAnimations()
        needToShowBars = false
        setUxState(UX_STATE_ANIMATING_HIDE)
        timeBar.hideScrubber(ANIMATION_DURATION_MS)
        if (bottomBarController.hasFocus() || titleView.hasFocus() || playerControlView.isAnyPrimaryControlFocused()) {
            timeBar.requestFocus()
        }
        controlsBackground.visibility = View.INVISIBLE
        animateMainInfo(visible = false) {}
        bottomBar.animate()
            .translationY(hiddenTranslationY)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideController()
                    restoreMainBarImmediately()
                    if (needToShowBars) {
                        needToShowBars = false
                        playerControlView.post { showAllBars() }
                    }
                }
            })
            .start()
    }

    private fun hideProgressBar() {
        if (uxState == UX_STATE_ANIMATING_HIDE || uxState == UX_STATE_NONE_VISIBLE) {
            return
        }
        cancelAllAnimations()
        needToShowBars = false
        setUxState(UX_STATE_ANIMATING_HIDE)
        controlsBackground.visibility = View.INVISIBLE
        bottomBar.animate()
            .translationY(hiddenTranslationY)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    hideController()
                    restoreMainBarImmediately()
                    if (needToShowBars) {
                        needToShowBars = false
                        playerControlView.post { showAllBars() }
                    }
                }
            })
            .start()
    }

    private fun hideController() {
        playerControlView.stopProgressUpdates()
        setUxState(UX_STATE_NONE_VISIBLE)
    }

    private fun showAllBars() {
        if (!isAnimationEnabled) {
            playerControlView.visibility = View.VISIBLE
            restoreMainBarImmediately()
            timeBar.showScrubber()
            setUxState(UX_STATE_ALL_VISIBLE)
            playerControlView.startProgressUpdates()
            resetHideCallbacks()
            return
        }
        when (uxState) {
            UX_STATE_ONLY_PROGRESS_VISIBLE -> animateShowMainBar()
            UX_STATE_NONE_VISIBLE -> animateShowAllBars()
            UX_STATE_ANIMATING_HIDE -> needToShowBars = true
            UX_STATE_ANIMATING_SHOW -> return
        }
        resetHideCallbacks()
    }

    private fun animateShowMainBar() {
        cancelAllAnimations()
        setUxState(UX_STATE_ANIMATING_SHOW)
        playerControlView.visibility = View.VISIBLE
        controlsBackground.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        centerControls.translationY = -hiddenTranslationY
        if (timeViewVisible) timeView.visibility = View.VISIBLE
        bottomBar.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
        timeBar.showScrubber(ANIMATION_DURATION_MS)
        playerControlView.startProgressUpdates()
        animateMainInfo(visible = true) {
            setUxState(UX_STATE_ALL_VISIBLE)
            resetHideCallbacks()
        }
    }

    private fun animateShowAllBars() {
        cancelAllAnimations()
        playerControlView.visibility = View.VISIBLE
        restoreMainBarImmediately(initialAlpha = 0f)
        bottomBar.translationY = hiddenTranslationY
        setUxState(UX_STATE_ANIMATING_SHOW)
        timeBar.showScrubber(ANIMATION_DURATION_MS)
        playerControlView.startProgressUpdates()
        // 顶栏下落与底部栏升起同时启动、同时长收尾，避免一前一后错拍
        bottomBar.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
        animateMainInfo(visible = true) {
            setUxState(UX_STATE_ALL_VISIBLE)
            resetHideCallbacks()
        }
    }

    private fun animateMainInfo(visible: Boolean, endAction: () -> Unit) {
        val targetAlpha = if (visible) 1f else 0f
        if (visible) {
            controlsBackground.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            titleView.visibility = View.VISIBLE
            bottomBarController.visibility = View.VISIBLE
            applyCompactMode(force = true)
            applyOverflowState()
        }
        var completedCount = 0
        val onPartEnd = {
            completedCount += 1
            if (completedCount >= 4) {
                if (!visible) {
                    controlsBackground.visibility = View.INVISIBLE
                    centerControls.visibility = View.INVISIBLE
                    titleView.visibility = View.INVISIBLE
                    bottomBarController.visibility = View.INVISIBLE
                }
                endAction()
            }
        }
        controlsBackground.animate()
            .alpha(targetAlpha)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        centerControls.animate()
            .alpha(targetAlpha)
            .translationY(if (visible) 0f else -hiddenTranslationY)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        titleView.animate()
            .alpha(targetAlpha)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
        bottomBarController.animate()
            .alpha(targetAlpha)
            .setDuration(ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onPartEnd()
                }
            })
            .start()
    }

    private fun updateAdaptiveLayout() {
        if (playerControlView.width <= 0) {
            return
        }
        syncOverflowButtons()
        applyCompactMode(force = false)
        applyOverflowState()
    }

    private fun scheduleAdaptiveLayout() {
        if (adaptiveLayoutScheduled) {
            return
        }
        adaptiveLayoutScheduled = true
        playerControlView.post {
            adaptiveLayoutScheduled = false
            updateAdaptiveLayout()
        }
    }

    private fun syncOverflowButtons() {
        val visibleButtons = orderedButtons.filter { shownButtons.contains(it) }
        val movedIds = LinkedHashSet<Int>()
        while (requiredWidth(visibleButtons, movedIds) > availableWidth()) {
            val nextId = overflowPriority.firstOrNull { candidateId ->
                candidateId !in movedIds && visibleButtons.any { it.id == candidateId }
            } ?: break
            movedIds.add(nextId)
        }

        val basicButtons = visibleButtons.filterNot { it.id in movedIds }
        val extraButtons = visibleButtons.filter { it.id in movedIds }
        val focusedExtraHidden = !isOverflowVisible && extraButtons.any { it.isFocused }
        val previouslyFocusedButton = orderedButtons.firstOrNull { it.isFocused }

        basicControls.removeAllViews()
        extraControls.removeAllViews()

        basicButtons.forEach { button ->
            removeViewFromParent(button)
            basicControls.addView(button)
            button.visibility = View.VISIBLE
        }

        val hasOverflow = extraButtons.isNotEmpty()
        if (hasOverflow) {
            removeViewFromParent(overflowShowButton)
            basicControls.addView(overflowShowButton)
            overflowShowButton.visibility = if (isOverflowVisible) View.INVISIBLE else View.VISIBLE

            removeViewFromParent(overflowHideButton)
            extraControls.addView(overflowHideButton)
            extraButtons.forEach { button ->
                removeViewFromParent(button)
                extraControls.addView(button)
                button.visibility = View.VISIBLE
            }
        } else {
            overflowShowButton.visibility = View.GONE
            extraControlsScrollView.visibility = View.INVISIBLE
            isOverflowVisible = false
        }

        if (focusedExtraHidden) {
            playerControlView.post {
                if (overflowShowButton.visibility == View.VISIBLE) {
                    overflowShowButton.requestFocus()
                } else {
                    playerControlView.requestPlayPauseFocus()
                }
            }
        } else if (previouslyFocusedButton != null && previouslyFocusedButton.visibility == View.VISIBLE) {
            previouslyFocusedButton.requestFocus()
        }
    }

    private fun applyCompactMode(force: Boolean) {
        val compactMode = useCompactMode()
        if (!force && compactMode == isCompactMode) {
            return
        }
        isCompactMode = compactMode

        subtitleView.visibility = if (compactMode) View.GONE else View.VISIBLE

        val timeBarLayoutParams = timeBar.layoutParams as ViewGroup.MarginLayoutParams
        timeBarLayoutParams.topMargin = if (compactMode) 0 else originalTimeBarTopMargin
        timeBar.layoutParams = timeBarLayoutParams

        bottomBar.setPadding(
            bottomBar.paddingLeft,
            bottomBar.paddingTop,
            bottomBar.paddingRight,
            if (compactMode) compactBottomBarPaddingBottom else originalBottomBarPaddingBottom
        )
    }

    private fun useCompactMode(): Boolean {
        val availableHeight = playerControlView.height - playerControlView.paddingTop - playerControlView.paddingBottom
        if (availableHeight <= 0) {
            return false
        }
        return availableHeight <= titleView.measuredHeight + bottomBar.measuredHeight + timeBar.measuredHeight
    }

    private fun availableWidth(): Int {
        return playerControlView.width - playerControlView.paddingLeft - playerControlView.paddingRight
    }

    private fun requiredWidth(visibleButtons: List<View>, movedIds: Set<Int>): Int {
        val basicWidth = visibleButtons
            .filterNot { it.id in movedIds }
            .sumOf { getWidthWithMargins(it) }
        val overflowWidth = if (movedIds.isNotEmpty()) getWidthWithMargins(overflowShowButton) else 0
        return basicWidth + overflowWidth + getWidthWithMargins(timeView)
    }

    private fun applyOverflowState() {
        val hasOverflow = extraControls.childCount > 1
        if (!hasOverflow) {
            isOverflowVisible = false
            cancelOverflowAnimations()
            setOverflowProgress(0f)
            extraControlsScrollView.visibility = View.GONE
            overflowShowButton.visibility = View.GONE
            return
        }
        if (isOverflowVisible) {
            overflowShowButton.visibility = View.INVISIBLE
            cancelOverflowAnimations()
            extraControlsScrollView.visibility = View.VISIBLE
            setOverflowProgress(1f)
        } else {
            overflowShowButton.visibility = View.VISIBLE
            cancelOverflowAnimations()
            setOverflowProgress(0f)
            extraControlsScrollView.visibility = View.GONE
        }
    }

    private fun showOverflowControls() {
        if (extraControls.childCount <= 1 || isOverflowVisible) {
            return
        }
        resetHideCallbacks()
        isOverflowVisible = true
        animateOverflow(show = true)
    }

    private fun hideOverflowControls() {
        if (!isOverflowVisible) {
            return
        }
        resetHideCallbacks()
        isOverflowVisible = false
        val restoreFocus = extraControls.anyDescendantFocused()
        animateOverflow(show = false, restoreOverflowButtonFocus = restoreFocus)
    }

    private fun animateOverflow(show: Boolean, restoreOverflowButtonFocus: Boolean = false) {
        cancelOverflowAnimations()
        if (show) {
            extraControlsScrollView.visibility = View.VISIBLE
            overflowShowButton.visibility = View.INVISIBLE
            setOverflowProgress(0f)
        } else {
            setOverflowProgress(1f)
        }
        extraControlsScrollView.animate()
            .translationX(if (show) 0f else extraControlsWidth().toFloat())
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!show) {
                        extraControlsScrollView.visibility = View.GONE
                        overflowShowButton.visibility = View.VISIBLE
                        if (restoreOverflowButtonFocus) {
                            overflowShowButton.requestFocus()
                        }
                    } else if (overflowHideButton.visibility == View.VISIBLE) {
                        overflowHideButton.requestFocus()
                    }
                }
            })
            .start()
        basicControls.animate()
            .alpha(if (show) 0f else 1f)
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
        timeView.animate()
            .alpha(if (show) 0f else 1f)
            .setDuration(OVERFLOW_ANIMATION_DURATION_MS)
            .setListener(null)
            .start()
    }

    private fun setOverflowProgress(progress: Float) {
        basicControls.alpha = 1f - progress
        timeView.alpha = 1f - progress
        extraControlsScrollView.translationX = (1f - progress) * extraControlsWidth()
    }

    private fun extraControlsWidth(): Int {
        val width = extraControlsScrollView.width.takeIf { it > 0 }
            ?: extraControls.width.takeIf { it > 0 }
        return width ?: extraControls.childrenWidth()
    }

    private fun restoreMainBarImmediately(initialAlpha: Float = 1f) {
        controlsBackground.visibility = View.VISIBLE
        controlsBackground.alpha = initialAlpha
        centerControls.visibility = View.VISIBLE
        centerControls.alpha = initialAlpha
        centerControls.translationY = if (initialAlpha == 0f) -hiddenTranslationY else 0f
        bottomBar.visibility = View.VISIBLE
        bottomBar.alpha = 1f
        bottomBar.translationY = 0f
        titleView.visibility = View.VISIBLE
        bottomBarController.visibility = View.VISIBLE
        if (timeViewVisible) timeView.visibility = View.VISIBLE
        titleView.alpha = initialAlpha
        bottomBarController.alpha = initialAlpha
        applyCompactMode(force = true)
        applyOverflowState()
    }

    private fun cancelAllAnimations() {
        cancelViewAnimation(centerControls)
        cancelViewAnimation(bottomBar)
        cancelViewAnimation(titleView)
        cancelViewAnimation(bottomBarController)
        cancelViewAnimation(controlsBackground)
        cancelOverflowAnimations()
    }

    private fun cancelOverflowAnimations() {
        cancelViewAnimation(extraControlsScrollView)
        cancelViewAnimation(basicControls)
        cancelViewAnimation(timeView)
    }

    private fun cancelViewAnimation(view: View) {
        view.animate().setListener(null)
        view.animate().cancel()
    }

    private fun removeViewFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun getWidthWithMargins(view: View): Int {
        val layoutParams = view.layoutParams
        val baseWidth = when {
            view.width > 0 -> view.width
            view.measuredWidth > 0 -> view.measuredWidth
            layoutParams != null && layoutParams.width > 0 -> layoutParams.width
            else -> 0
        }
        val marginLayoutParams = layoutParams as? ViewGroup.MarginLayoutParams ?: return baseWidth
        return baseWidth + marginLayoutParams.leftMargin + marginLayoutParams.rightMargin
    }

    private fun ViewGroup.childrenWidth(): Int {
        var total = 0
        for (index in 0 until childCount) {
            total += getWidthWithMargins(getChildAt(index))
        }
        return total
    }

    private fun ViewGroup.anyDescendantFocused(): Boolean {
        if (hasFocus()) {
            return true
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isFocused) {
                return true
            }
            if (child is ViewGroup && child.anyDescendantFocused()) {
                return true
            }
        }
        return false
    }

    private fun setUxState(newState: Int) {
        val oldState = uxState
        uxState = newState
        if (newState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.GONE
            (playerControlView.parent as? View)?.requestFocus()
        } else if (oldState == UX_STATE_NONE_VISIBLE) {
            playerControlView.visibility = View.VISIBLE
        }
        if (oldState != newState) {
            val effective = when (newState) {
                UX_STATE_ANIMATING_SHOW, UX_STATE_ALL_VISIBLE -> View.VISIBLE
                UX_STATE_ANIMATING_HIDE, UX_STATE_ONLY_PROGRESS_VISIBLE -> View.INVISIBLE
                UX_STATE_NONE_VISIBLE -> View.GONE
                else -> playerControlView.visibility
            }
            playerControlView.notifyChromeState(effective)
        }
        uiCoordinator?.let { coordinator ->
            when (newState) {
                UX_STATE_ALL_VISIBLE -> {
                    coordinator.transition(com.mytvb.feature.player.UiEvent.ChromeShowAll)
                }
                UX_STATE_ONLY_PROGRESS_VISIBLE -> {
                    if (coordinator.chromeState != com.mytvb.feature.player.PlaybackUiCoordinator.ChromeState.ProgressOnly) {
                        coordinator.withState { state ->
                            state.chromeState = com.mytvb.feature.player.PlaybackUiCoordinator.ChromeState.ProgressOnly
                            state.bottomOccupant = com.mytvb.feature.player.PlaybackUiCoordinator.BottomOccupant.FullChrome
                            state.hudState = com.mytvb.feature.player.PlaybackUiCoordinator.HudState.Chrome
                        }
                    }
                }
                UX_STATE_NONE_VISIBLE -> {
                    coordinator.withState { state ->
                        state.chromeState = com.mytvb.feature.player.PlaybackUiCoordinator.ChromeState.Hidden
                        state.bottomOccupant = com.mytvb.feature.player.PlaybackUiCoordinator.BottomOccupant.SlimTimeline
                        state.hudState = com.mytvb.feature.player.PlaybackUiCoordinator.HudState.Ambient
                    }
                }
            }
        }
    }
}
