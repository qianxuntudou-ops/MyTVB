package com.mytvb.feature.player

sealed class UiEvent {
    object ToggleChrome : UiEvent()
    object ChromeTimeout : UiEvent()
    object ChromeShowAll : UiEvent()

    object SeekStarted : UiEvent()
    data class SeekTypeChanged(val type: SeekType) : UiEvent()
    object SeekFinished : UiEvent()
    object SeekCancelled : UiEvent()

    object SpeedModeStarted : UiEvent()
    object SpeedModeFinished : UiEvent()
    object SpeedModeSteppedUp : UiEvent()

    data class PanelOpened(val panel: PanelType) : UiEvent()
    object PanelClosed : UiEvent()

    object PlaybackEnded : UiEvent()
    object PlaybackResumed : UiEvent()

    object InteractionStarted : UiEvent()
    object InteractionEnded : UiEvent()

    object ResumeHintShown : UiEvent()
    object ResumeHintDismissed : UiEvent()

    object NextUpShown : UiEvent()
    object NextUpAction : UiEvent()
    object NextUpDismissed : UiEvent()

    object SettingsOpened : UiEvent()
    object SettingsClosed : UiEvent()
}

enum class SeekType {
    TAP,
    HOLD,
    SWIPE,
    DOUBLE_TAP,
    SPEED_MODE
}

enum class PanelType {
    SETTINGS,
    EPISODE,
    RELATED,
    ACTION,
    OWNER,
    COMMENT,
    NEXT_UP,
    INTERACTION,
    RESUME_HINT
}
