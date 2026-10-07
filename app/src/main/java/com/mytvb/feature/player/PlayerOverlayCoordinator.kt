package com.mytvb.feature.player

import com.mytvb.feature.player.view.MyPlayerView

class PlayerOverlayCoordinator(
    private val exitIntervalMs: Long = 2000L
) {

    enum class Panel {
        NONE,
        RELATED
    }

    enum class FocusTarget {
        PLAY_PAUSE,
        RELATED_BUTTON,
        EPISODE_BUTTON,
        MORE_BUTTON,
        OWNER_BUTTON,
        COMMENT_BUTTON
    }

    private var visiblePanel: Panel = Panel.NONE
    private var focusRestoreTarget: FocusTarget = FocusTarget.PLAY_PAUSE
    private var lastBackPressedAtMs: Long = 0L

    fun onRelatedPanelShown() {
        visiblePanel = Panel.RELATED
        focusRestoreTarget = FocusTarget.RELATED_BUTTON
    }

    fun onRelatedPanelHidden(restoreTarget: FocusTarget = FocusTarget.RELATED_BUTTON) {
        visiblePanel = Panel.NONE
        focusRestoreTarget = restoreTarget
    }

    fun rememberFocusRestoreTarget(target: FocusTarget) {
        focusRestoreTarget = target
    }

    fun hasVisiblePanel(): Boolean = visiblePanel != Panel.NONE

    fun restoreFocus(playerView: MyPlayerView) {
        when (focusRestoreTarget) {
            FocusTarget.RELATED_BUTTON -> playerView.requestRelatedButtonFocus()
            FocusTarget.EPISODE_BUTTON -> playerView.requestEpisodeButtonFocus()
            FocusTarget.MORE_BUTTON -> playerView.requestMoreButtonFocus()
            FocusTarget.OWNER_BUTTON -> playerView.requestOwnerButtonFocus()
            FocusTarget.COMMENT_BUTTON -> playerView.requestCommentButtonFocus()
            FocusTarget.PLAY_PAUSE -> playerView.requestPlayPauseFocus()
        }
    }

    fun handleBackPress(
        nowMs: Long,
        isSettingShowing: Boolean,
        hideSetting: () -> Unit,
        isControllerFullyVisible: Boolean,
        hideController: () -> Unit,
        hidePanel: () -> Unit,
        exitPlayer: () -> Unit,
        showExitPrompt: () -> Unit
    ) {
        when {
            isSettingShowing -> hideSetting()
            hasVisiblePanel() -> hidePanel()
            isControllerFullyVisible -> hideController()
            nowMs - lastBackPressedAtMs <= exitIntervalMs -> exitPlayer()
            else -> {
                lastBackPressedAtMs = nowMs
                showExitPrompt()
            }
        }
    }
}
