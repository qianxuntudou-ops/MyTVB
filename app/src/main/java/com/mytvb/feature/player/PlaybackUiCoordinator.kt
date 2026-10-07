package com.mytvb.feature.player


class PlaybackUiCoordinator {

    enum class ChromeState {
        Hidden,
        ProgressOnly,
        Full
    }

    enum class BottomOccupant {
        None,
        SlimTimeline,
        FullChrome,
        BottomPanel
    }

    enum class SeekState {
        None,
        TapSeek,
        HoldSeek,
        SpeedMode,
        SwipeSeek,
        DoubleTapSeek
    }

    enum class PanelState {
        None,
        Settings,
        Episode,
        Related,
        Action,
        Owner,
        Comment,
        NextUp,
        Interaction,
        ResumeHint
    }

    enum class FocusOwner {
        PlayerRoot,
        Controller,
        Panel,
        Dialog,
        Interaction
    }

    enum class HudState {
        Ambient,
        Chrome,
        Seek,
        Panel,
        Completion
    }

    var chromeState: ChromeState = ChromeState.Hidden
        internal set
    var bottomOccupant: BottomOccupant = BottomOccupant.None
        internal set
    var seekState: SeekState = SeekState.None
        internal set
    var panelState: PanelState = PanelState.None
        internal set
    var focusOwner: FocusOwner = FocusOwner.PlayerRoot
        internal set
    var hudState: HudState = HudState.Ambient
        internal set

    var seekPreviewTargetPositionMs: Long = 0L
        internal set
    var seekPreviewDurationMs: Long = 0L
        internal set

    private val listeners = mutableListOf<OnStateChangedListener>()

    fun addListener(listener: OnStateChangedListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnStateChangedListener) {
        listeners.remove(listener)
    }

    fun transition(event: UiEvent) {
        when (event) {
            is UiEvent.ToggleChrome -> handleToggleChrome()
            is UiEvent.ChromeTimeout -> handleChromeTimeout()
            is UiEvent.ChromeShowAll -> handleChromeShowAll()

            is UiEvent.SeekStarted -> handleSeekStarted()
            is UiEvent.SeekTypeChanged -> updateSeekType(event.type)
            is UiEvent.SeekFinished -> handleSeekFinished()
            is UiEvent.SeekCancelled -> handleSeekCancelled()

            is UiEvent.SpeedModeStarted -> handleSpeedModeStarted()
            is UiEvent.SpeedModeFinished -> handleSpeedModeFinished()
            is UiEvent.SpeedModeSteppedUp -> { }

            is UiEvent.PanelOpened -> handlePanelOpened(event.panel)
            is UiEvent.PanelClosed -> handlePanelClosed()

            is UiEvent.PlaybackEnded -> handlePlaybackEnded()
            is UiEvent.PlaybackResumed -> handlePlaybackResumed()

            is UiEvent.InteractionStarted -> handleInteractionStarted()
            is UiEvent.InteractionEnded -> handleInteractionEnded()

            is UiEvent.ResumeHintShown -> handleResumeHintShown()
            is UiEvent.ResumeHintDismissed -> handleResumeHintDismissed()

            is UiEvent.NextUpShown -> handleNextUpShown()
            is UiEvent.NextUpAction -> handleNextUpAction()
            is UiEvent.NextUpDismissed -> handleNextUpDismissed()

            is UiEvent.SettingsOpened -> handleSettingsOpened()
            is UiEvent.SettingsClosed -> handleSettingsClosed()
        }
    }

    fun updateSeekPreview(targetPositionMs: Long, durationMs: Long) {
        seekPreviewTargetPositionMs = targetPositionMs
        seekPreviewDurationMs = durationMs
    }

    fun clearSeekPreview() {
        seekPreviewTargetPositionMs = 0L
        seekPreviewDurationMs = 0L
    }

    private fun handleToggleChrome() {
        when (chromeState) {
            ChromeState.Hidden -> {
                chromeState = ChromeState.Full
                bottomOccupant = BottomOccupant.FullChrome
                hudState = HudState.Chrome
                focusOwner = FocusOwner.Controller
            }
            ChromeState.ProgressOnly -> {
                chromeState = ChromeState.Full
                bottomOccupant = BottomOccupant.FullChrome
                hudState = HudState.Chrome
                focusOwner = FocusOwner.Controller
            }
            ChromeState.Full -> {
                chromeState = ChromeState.Hidden
                bottomOccupant = BottomOccupant.SlimTimeline
                hudState = HudState.Ambient
                focusOwner = FocusOwner.PlayerRoot
            }
        }
        notifyStateChanged()
    }

    private fun handleChromeTimeout() {
        when (chromeState) {
            ChromeState.Full -> {
                chromeState = ChromeState.ProgressOnly
                bottomOccupant = BottomOccupant.FullChrome
            }
            ChromeState.ProgressOnly -> {
                chromeState = ChromeState.Hidden
                bottomOccupant = BottomOccupant.SlimTimeline
                hudState = HudState.Ambient
                focusOwner = FocusOwner.PlayerRoot
            }
            ChromeState.Hidden -> { }
        }
        notifyStateChanged()
    }

    private fun handleChromeShowAll() {
        chromeState = ChromeState.Full
        bottomOccupant = BottomOccupant.FullChrome
        hudState = HudState.Chrome
        focusOwner = FocusOwner.Controller
        notifyStateChanged()
    }

    private fun handleSeekStarted() {
        if (panelState != PanelState.None) return
        hudState = HudState.Seek
        notifyStateChanged()
    }

    private fun updateSeekType(type: SeekType) {
        seekState = when (type) {
            SeekType.TAP -> SeekState.TapSeek
            SeekType.HOLD -> SeekState.HoldSeek
            SeekType.SWIPE -> SeekState.SwipeSeek
            SeekType.DOUBLE_TAP -> SeekState.DoubleTapSeek
            SeekType.SPEED_MODE -> SeekState.SpeedMode
        }
        notifyStateChanged()
    }

    private fun handleSeekFinished() {
        seekState = SeekState.None
        clearSeekPreview()
        when (chromeState) {
            ChromeState.Full -> {
                hudState = HudState.Chrome
                bottomOccupant = BottomOccupant.FullChrome
            }
            ChromeState.ProgressOnly -> {
                hudState = HudState.Chrome
                bottomOccupant = BottomOccupant.FullChrome
            }
            ChromeState.Hidden -> {
                hudState = HudState.Ambient
                bottomOccupant = BottomOccupant.SlimTimeline
            }
        }
        notifyStateChanged()
    }

    private fun handleSeekCancelled() {
        seekState = SeekState.None
        clearSeekPreview()
        when (chromeState) {
            ChromeState.Full -> {
                hudState = HudState.Chrome
                bottomOccupant = BottomOccupant.FullChrome
            }
            ChromeState.ProgressOnly -> {
                hudState = HudState.Chrome
                bottomOccupant = BottomOccupant.FullChrome
            }
            ChromeState.Hidden -> {
                hudState = HudState.Ambient
                bottomOccupant = BottomOccupant.SlimTimeline
            }
        }
        notifyStateChanged()
    }

    private fun handleSpeedModeStarted() {
        seekState = SeekState.SpeedMode
        hudState = HudState.Seek
        notifyStateChanged()
    }

    private fun handleSpeedModeFinished() {
        seekState = SeekState.None
        when (chromeState) {
            ChromeState.Full -> hudState = HudState.Chrome
            else -> hudState = HudState.Ambient
        }
        notifyStateChanged()
    }

    private fun handlePanelOpened(panel: PanelType) {
        panelState = when (panel) {
            PanelType.EPISODE -> PanelState.Episode
            PanelType.RELATED -> PanelState.Related
            PanelType.ACTION -> PanelState.Action
            PanelType.OWNER -> PanelState.Owner
            PanelType.COMMENT -> PanelState.Comment
            PanelType.NEXT_UP -> PanelState.NextUp
            PanelType.INTERACTION -> PanelState.Interaction
            PanelType.RESUME_HINT -> PanelState.ResumeHint
            PanelType.SETTINGS -> PanelState.Settings
        }
        bottomOccupant = BottomOccupant.BottomPanel
        hudState = HudState.Panel
        focusOwner = when (panel) {
            PanelType.EPISODE,
            PanelType.ACTION,
            PanelType.OWNER,
            PanelType.COMMENT -> FocusOwner.Dialog
            PanelType.RELATED,
            PanelType.NEXT_UP,
            PanelType.SETTINGS -> FocusOwner.Panel
            PanelType.INTERACTION -> FocusOwner.Interaction
            PanelType.RESUME_HINT -> focusOwner
        }
        notifyStateChanged()
    }

    private fun handlePanelClosed() {
        panelState = PanelState.None
        when (chromeState) {
            ChromeState.Full -> {
                bottomOccupant = BottomOccupant.FullChrome
                hudState = HudState.Chrome
                focusOwner = FocusOwner.Controller
            }
            ChromeState.ProgressOnly -> {
                bottomOccupant = BottomOccupant.FullChrome
                hudState = HudState.Chrome
                focusOwner = FocusOwner.Controller
            }
            ChromeState.Hidden -> {
                bottomOccupant = BottomOccupant.SlimTimeline
                hudState = HudState.Ambient
                focusOwner = FocusOwner.PlayerRoot
            }
        }
        notifyStateChanged()
    }

    private fun handlePlaybackEnded() {
        hudState = HudState.Completion
        notifyStateChanged()
    }

    private fun handlePlaybackResumed() {
        if (hudState == HudState.Completion) {
            hudState = HudState.Ambient
        }
        notifyStateChanged()
    }

    private fun handleInteractionStarted() {
        panelState = PanelState.Interaction
        focusOwner = FocusOwner.Interaction
        notifyStateChanged()
    }

    private fun handleInteractionEnded() {
        panelState = PanelState.None
        focusOwner = when (chromeState) {
            ChromeState.Full, ChromeState.ProgressOnly -> FocusOwner.Controller
            else -> FocusOwner.PlayerRoot
        }
        notifyStateChanged()
    }

    private fun handleResumeHintShown() {
        panelState = PanelState.ResumeHint
        notifyStateChanged()
    }

    private fun handleResumeHintDismissed() {
        if (panelState == PanelState.ResumeHint) {
            panelState = PanelState.None
        }
        notifyStateChanged()
    }

    private fun handleNextUpShown() {
        panelState = PanelState.NextUp
        notifyStateChanged()
    }

    private fun handleNextUpAction() {
        panelState = PanelState.None
        notifyStateChanged()
    }

    private fun handleNextUpDismissed() {
        if (panelState == PanelState.NextUp) {
            panelState = PanelState.None
        }
        notifyStateChanged()
    }

    private fun handleSettingsOpened() {
        panelState = PanelState.Settings
        notifyStateChanged()
    }

    private fun handleSettingsClosed() {
        if (panelState == PanelState.Settings) {
            panelState = PanelState.None
        }
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(this) }
    }

    fun hasBlockingPanel(): Boolean = panelState != PanelState.None

    fun handleBackPress(
        isSettingShowing: Boolean,
        hideSetting: () -> Unit,
        isControllerFullyVisible: Boolean,
        hideController: () -> Unit,
        hidePanel: () -> Unit,
        exitPlayer: () -> Unit,
    ) {
        when {
            isSettingShowing -> hideSetting()
            hasVisiblePanel() -> hidePanel()
            isControllerFullyVisible -> hideController()
            else -> exitPlayer()
        }
    }

    fun onRelatedPanelShown() {
        transition(UiEvent.PanelOpened(PanelType.RELATED))
    }

    fun onRelatedPanelHidden() {
        transition(UiEvent.PanelClosed)
    }

    fun hasVisiblePanel(): Boolean = panelState != PanelState.None

    fun syncAmbientChrome(showBottomProgressBar: Boolean) {
        chromeState = ChromeState.Hidden
        bottomOccupant = if (showBottomProgressBar) BottomOccupant.SlimTimeline else BottomOccupant.None
        hudState = HudState.Ambient
        focusOwner = FocusOwner.PlayerRoot
    }

    fun <T> withState(block: (PlaybackUiCoordinator) -> T): T = block(this)

    interface OnStateChangedListener {
        fun onStateChanged(coordinator: PlaybackUiCoordinator)
    }
}
