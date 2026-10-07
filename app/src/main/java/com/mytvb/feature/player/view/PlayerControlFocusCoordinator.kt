package com.mytvb.feature.player.view

import android.os.Handler
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * Centralizes focus routing and focus restoration for the controller chrome.
 * This keeps MyPlayerControlView focused on player actions and rendering state.
 */
internal class PlayerControlFocusCoordinator(
    private val buttonPlay: View,
    private val buttonPrevious: View,
    private val buttonNext: View,
    private val buttonRewind: View,
    private val buttonFastForward: View,
    private val buttonDmSwitch: View,
    private val buttonMirror: View,
    private val buttonSettings: View,
    private val buttonChooseEpisode: View,
    private val buttonMore: View,
    private val buttonUpInfo: View,
    private val buttonComment: View,
    private val buttonSubtitle: View,
    private val buttonRelated: View,
    private val buttonRepeat: View,
    private val buttonLiveSettings: View,
    private val buttonRefresh: View,
    private val buttonLine: View,
    private val buttonClose: View,
    private val buttonBack: View,
    private val timeBar: View,
    private val bottomBar: ViewGroup
) {

    private enum class FocusTarget {
        PLAY_PAUSE,
        PREVIOUS,
        NEXT,
        REWIND,
        FAST_FORWARD,
        DM_SWITCH,
        MIRROR,
        SETTINGS,
        EPISODE,
        MORE,
        OWNER,
        COMMENT,
        SUBTITLE,
        RELATED,
        REPEAT,
        LIVE_SETTINGS,
        REFRESH,
        LINE,
        CLOSE,
        BACK,
        TIME_BAR
    }

    private var focusRestoreTarget = FocusTarget.PLAY_PAUSE
    private var onVisibleFocusSettled: (() -> Unit)? = null
    private var isHostVisible: (() -> Boolean)? = null
    private val focusStabilizeRunnable = Runnable {
        if (isHostVisible?.invoke() == true) {
            onVisibleFocusSettled?.invoke()
        }
    }

    fun setupFocusTracking(
        handler: Handler,
        isHostVisible: () -> Boolean,
        onVisibleFocusSettled: () -> Unit,
        vararg views: View
    ) {
        this.isHostVisible = isHostVisible
        this.onVisibleFocusSettled = onVisibleFocusSettled
        views.forEach { view ->
            view.setOnFocusChangeListener { _, hasFocus ->
                handler.removeCallbacks(focusStabilizeRunnable)
                if (hasFocus && isHostVisible()) {
                    handler.postDelayed(focusStabilizeRunnable, 80)
                }
            }
        }
    }

    fun clearPendingFocusStabilization(handler: Handler) {
        handler.removeCallbacks(focusStabilizeRunnable)
    }

    fun handleDpadWhenSuperNotHandled(event: KeyEvent, focused: View?): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focused == null) {
                    requestPlayPauseFocus()
                    return true
                }
                if (focused is PlayerControlButton || focused.parent == bottomBar) {
                    timeBar.requestFocus()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focused == null) {
                    requestPlayPauseFocus()
                    return true
                }
                if (focused === timeBar) {
                    requestPlayPauseFocus()
                    return true
                }
                if (focused is PlayerControlButton) {
                    val canJumpToRelated = buttonRelated.visibility == View.VISIBLE &&
                        (buttonPlay.isFocused || buttonFastForward.isFocused || buttonRewind.isFocused || buttonSettings.isFocused)
                    if (canJumpToRelated && buttonLiveSettings.visibility != View.VISIBLE) {
                        buttonRelated.performClick()
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focused == null) {
                    requestPlayPauseFocus()
                    return true
                }
                if (focused is PlayerControlButton) {
                    return moveFocusWithinParent(
                        focused = focused,
                        step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    )
                }
                if (focused === timeBar) {
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (focused == null) {
                    requestPlayPauseFocus()
                    return true
                }
                if (focused === timeBar) {
                    buttonPlay.performClick()
                    return true
                }
            }
        }
        return false
    }

    fun focusButtonByKeyDown(
        event: KeyEvent,
        onPlayPauseClick: () -> Unit,
        onRewind: () -> Unit,
        onFastForward: () -> Unit
    ) {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                requestPlayPauseFocus()
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    onPlayPauseClick()
                }
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> onRewind()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> onFastForward()
        }
    }

    fun requestPlayPauseFocus() {
        if (buttonPlay.visibility == View.VISIBLE) {
            buttonPlay.requestFocus()
        }
    }

    fun requestTimeBarFocus() {
        if (timeBar.visibility == View.VISIBLE) {
            timeBar.requestFocus()
        }
    }

    fun requestSettingButtonFocus() {
        requestFocusOrFallback(buttonSettings)
    }

    fun requestRelatedButtonFocus() {
        requestFocusOrFallback(buttonRelated)
    }

    fun requestEpisodeButtonFocus() {
        requestFocusOrFallback(buttonChooseEpisode)
    }

    fun requestMoreButtonFocus() {
        requestFocusOrFallback(buttonMore)
    }

    fun requestOwnerButtonFocus() {
        requestFocusOrFallback(buttonUpInfo)
    }

    fun requestCommentButtonFocus() {
        requestFocusOrFallback(buttonComment)
    }

    fun requestSubtitleButtonFocus() {
        requestFocusOrFallback(buttonSubtitle)
    }

    fun rememberCurrentFocusTarget() {
        focusRestoreTarget = when {
            timeBar.isFocused -> FocusTarget.TIME_BAR
            buttonPrevious.isFocused -> FocusTarget.PREVIOUS
            buttonNext.isFocused -> FocusTarget.NEXT
            buttonRewind.isFocused -> FocusTarget.REWIND
            buttonFastForward.isFocused -> FocusTarget.FAST_FORWARD
            buttonDmSwitch.isFocused -> FocusTarget.DM_SWITCH
            buttonMirror.isFocused -> FocusTarget.MIRROR
            buttonSettings.isFocused -> FocusTarget.SETTINGS
            buttonChooseEpisode.isFocused -> FocusTarget.EPISODE
            buttonMore.isFocused -> FocusTarget.MORE
            buttonUpInfo.isFocused -> FocusTarget.OWNER
            buttonComment.isFocused -> FocusTarget.COMMENT
            buttonSubtitle.isFocused -> FocusTarget.SUBTITLE
            buttonRelated.isFocused -> FocusTarget.RELATED
            buttonRepeat.isFocused -> FocusTarget.REPEAT
            buttonLiveSettings.isFocused -> FocusTarget.LIVE_SETTINGS
            buttonRefresh.isFocused -> FocusTarget.REFRESH
            buttonLine.isFocused -> FocusTarget.LINE
            buttonClose.isFocused -> FocusTarget.CLOSE
            buttonBack.isFocused -> FocusTarget.BACK
            else -> FocusTarget.PLAY_PAUSE
        }
    }

    fun restoreRememberedFocus() {
        when (focusRestoreTarget) {
            FocusTarget.PLAY_PAUSE -> requestPlayPauseFocus()
            FocusTarget.PREVIOUS -> requestViewOrFallback(buttonPrevious, requireEnabled = true)
            FocusTarget.NEXT -> requestViewOrFallback(buttonNext, requireEnabled = true)
            FocusTarget.REWIND -> requestViewOrFallback(buttonRewind)
            FocusTarget.FAST_FORWARD -> requestViewOrFallback(buttonFastForward)
            FocusTarget.DM_SWITCH -> requestViewOrFallback(buttonDmSwitch)
            FocusTarget.MIRROR -> requestViewOrFallback(buttonMirror)
            FocusTarget.SETTINGS -> requestSettingButtonFocus()
            FocusTarget.EPISODE -> requestEpisodeButtonFocus()
            FocusTarget.MORE -> requestMoreButtonFocus()
            FocusTarget.OWNER -> requestOwnerButtonFocus()
            FocusTarget.COMMENT -> requestCommentButtonFocus()
            FocusTarget.SUBTITLE -> requestSubtitleButtonFocus()
            FocusTarget.RELATED -> requestRelatedButtonFocus()
            FocusTarget.REPEAT -> requestViewOrFallback(buttonRepeat)
            FocusTarget.LIVE_SETTINGS -> requestViewOrFallback(buttonLiveSettings)
            FocusTarget.REFRESH -> requestViewOrFallback(buttonRefresh)
            FocusTarget.LINE -> requestViewOrFallback(buttonLine)
            FocusTarget.CLOSE -> requestViewOrFallback(buttonClose)
            FocusTarget.BACK -> requestViewOrFallback(buttonBack)
            FocusTarget.TIME_BAR -> requestViewOrFallback(timeBar, requireEnabled = true)
        }
    }

    fun isAnyPrimaryControlFocused(): Boolean {
        return buttonPlay.isFocused ||
            buttonPrevious.isFocused ||
            buttonNext.isFocused ||
            buttonRewind.isFocused ||
            buttonFastForward.isFocused ||
            buttonDmSwitch.isFocused ||
            buttonMirror.isFocused ||
            buttonSettings.isFocused ||
            buttonChooseEpisode.isFocused ||
            buttonMore.isFocused ||
            buttonUpInfo.isFocused ||
            buttonComment.isFocused ||
            buttonSubtitle.isFocused ||
            buttonRelated.isFocused ||
            buttonRepeat.isFocused ||
            buttonLiveSettings.isFocused ||
            buttonRefresh.isFocused ||
            buttonLine.isFocused ||
            buttonClose.isFocused ||
            buttonBack.isFocused
    }

    private fun requestFocusOrFallback(target: View) {
        if (target.visibility == View.VISIBLE) {
            target.requestFocus()
        } else {
            requestPlayPauseFocus()
        }
    }

    private fun requestViewOrFallback(target: View, requireEnabled: Boolean = false) {
        val canFocus = target.visibility == View.VISIBLE && (!requireEnabled || target.isEnabled)
        if (canFocus) {
            target.requestFocus()
        } else {
            requestPlayPauseFocus()
        }
    }

    private fun moveFocusWithinParent(focused: View, step: Int): Boolean {
        val parent = focused.parent as? ViewGroup ?: return true
        val currentIndex = parent.indexOfChild(focused)
        var nextIndex = currentIndex + step
        while (nextIndex in 0 until parent.childCount) {
            val child = parent.getChildAt(nextIndex)
            if (child.visibility == View.VISIBLE && child.isFocusable && child.isEnabled) {
                child.requestFocus()
                return true
            }
            nextIndex += step
        }
        return true
    }
}
