package com.tutu.myblbl.feature.player.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.feature.player.settings.AfterPlayMode
import com.tutu.myblbl.feature.player.LiveQualityInfo
import com.tutu.myblbl.model.dm.DmScreenArea
import com.tutu.myblbl.model.subtitle.SubtitleInfoModel
import com.tutu.myblbl.model.video.quality.AudioQuality
import com.tutu.myblbl.model.video.quality.VideoCodecEnum
import com.tutu.myblbl.model.video.quality.VideoQuality

class MyPlayerSettingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), View.OnClickListener {

    companion object {
        internal val PLAYBACK_SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        internal val DM_ALPHA_VALUES = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        internal val DM_TEXT_SIZE_VALUES = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55)
        internal val DM_AREA_VALUES = arrayOf(
            DmScreenArea.OneEighth,
            DmScreenArea.OneSixth,
            DmScreenArea.Quarter,
            DmScreenArea.Half,
            DmScreenArea.ThreeQuarter,
            DmScreenArea.Full
        )
        internal val DM_SPEED_VALUES = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        internal const val PREFS_NAME = "app_settings"
        internal const val KEY_DM_ENABLE = "dm_enable"
        internal const val KEY_DM_ALPHA = "dm_alpha"
        internal const val KEY_DM_TEXT_SIZE = "dm_text_size"
        internal const val KEY_DM_SPEED = "dm_speed"
        internal const val KEY_DM_AREA = "dm_area"
        internal const val KEY_DM_ALLOW_TOP = "dm_allow_top"
        internal const val KEY_DM_ALLOW_BOTTOM = "dm_allow_bottom"
        internal const val KEY_DM_MERGE_DUPLICATE = "dm_merge_duplicate"
        internal const val KEY_DM_SMART_SHIELD = "dm_smart_shield"

        private const val LEVEL_MAIN = 1
        private const val LEVEL_SUB = 2
        private const val LEVEL_DM = 3
        private const val PANEL_ANIMATION_DURATION_MS = 180L
        private const val MENU_FADE_OUT_DURATION_MS = 60L
        private const val MENU_FADE_IN_DURATION_MS = 120L

        internal const val ITEM_MAIN_MENU = 0
        internal const val ITEM_VIDEO_QUALITY = 1
        internal const val ITEM_PLAYBACK_SPEED = 2
        internal const val ITEM_AFTER_PLAY = 10
        internal const val ITEM_SUBTITLE = 3
        internal const val ITEM_VIDEO_CODEC = 4
        internal const val ITEM_AUDIO_QUALITY = 5
        internal const val ITEM_ASPECT_RATIO = 6
        internal const val ITEM_DM_SETTING = 7
        internal const val ITEM_LIVE_QUALITY = 8
        internal const val ITEM_SCREEN_MIRROR = 9
        internal const val ITEM_DM_ENABLE = 101
        internal const val ITEM_DM_ALPHA = 102
        internal const val ITEM_DM_TEXT_SIZE = 103
        internal const val ITEM_DM_AREA = 104
        internal const val ITEM_DM_SPEED = 105
        internal const val ITEM_DM_ALLOW_TOP = 106
        internal const val ITEM_DM_ALLOW_BOTTOM = 107
        internal const val ITEM_DM_MERGE_DUPLICATE = 108
        internal const val ITEM_DM_SMART_SHIELD = 109
    }

    private val panelWidthPx by lazy { resources.getDimensionPixelSize(R.dimen.px650) }
    private val menuBuilder = MyPlayerSettingMenuBuilder(context)
    private val preferenceStore = MyPlayerSettingPreferenceStore(context)

    private val recyclerView: RecyclerView
    private val iconBack: ImageView
    private val adapter: PlayerSettingListAdapter
    private var onVisibilityStateChanged: ((Boolean) -> Unit)? = null

    private var menuLevel: Int = LEVEL_MAIN
    private var onPlayerSettingChange: OnPlayerSettingChange? = null
    private var onPlayerSettingInnerChange: OnPlayerSettingInnerChange? = null
    private var panelState = preferenceStore.loadDanmakuState(
        MyPlayerSettingMenuBuilder.PanelState()
    )
    private var lastFocusedMainItemPosition = 1
    private var lastFocusedDmItemPosition = 1

    init {
        LayoutInflater.from(context).inflate(R.layout.my_player_setting_view, this, true)
        recyclerView = findViewById(R.id.recyclerView)
        iconBack = findViewById(R.id.icon_back)
        findViewById<View>(R.id.view_button).setOnClickListener(this)

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = PlayerSettingListAdapter(::onItemClicked)
        recyclerView.adapter = adapter
        recyclerView.itemAnimator = null

        updateMainMenu()
        updateBackIcon()
        visibility = GONE
        alpha = 0f
        translationX = panelWidthPx.toFloat()
        isFocusable = true
        isClickable = true
    }

    override fun onClick(view: View?) {
        if (view?.id == R.id.view_button) {
            onBack()
        }
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    fun showHide(show: Boolean) {
        animate().cancel()
        if (!show) {
            animate()
                .translationX(panelWidthPx.toFloat())
                .alpha(0f)
                .setDuration(PANEL_ANIMATION_DURATION_MS)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = GONE
                        onVisibilityStateChanged?.invoke(false)
                    }
                })
                .start()
            return
        }

        visibility = VISIBLE
        menuLevel = LEVEL_MAIN
        lastFocusedMainItemPosition = 1
        updateMainMenu()
        updateBackIcon()
        translationX = panelWidthPx.toFloat()
        alpha = 0f
        animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(PANEL_ANIMATION_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    requestMenuFocus()
                    onVisibilityStateChanged?.invoke(true)
                }
            })
            .start()
    }

    fun onBack(): Boolean {
        Log.d("SettingFocus", "onBack level=$menuLevel menuKey=${adapter.currentMenuKey} mainPos=$lastFocusedMainItemPosition dmPos=$lastFocusedDmItemPosition")
        return when {
            menuLevel == LEVEL_MAIN -> {
                showHide(false)
                true
            }

            adapter.currentMenuKey == ITEM_LIVE_QUALITY -> {
                showHide(false)
                true
            }

            menuLevel == LEVEL_DM -> {
                showDmSettingMenu()
                true
            }

            else -> {
                menuLevel = LEVEL_MAIN
                updateMainMenu(focusPosition = lastFocusedMainItemPosition)
                updateBackIcon()
                true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isShowing() && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> return onBack()
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val focused = findFocus() ?: return super.dispatchKeyEvent(event)
                    if (focused.parent === recyclerView) {
                        val lastChild = recyclerView.getChildAt(recyclerView.childCount - 1)
                        if (focused === lastChild) return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val focused = findFocus() ?: return super.dispatchKeyEvent(event)
                    if (focused.parent === recyclerView) {
                        val firstChild = recyclerView.getChildAt(0)
                        if (focused === firstChild) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing()) return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            val recyclerContainer = findViewById<View>(R.id.recycler_container)
            val location = IntArray(2)
            recyclerContainer.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            val right = left + recyclerContainer.width
            val bottom = top + recyclerContainer.height
            val x = event.rawX
            val y = event.rawY
            if (x < left || x > right || y < top || y > bottom) {
                onBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setVideoQualities(qualities: List<VideoQuality>) {
        updateState { state ->
            val nextQuality = if (
                state.currentVideoQuality == null ||
                !qualities.any { it.id == state.currentVideoQuality.id }
            ) {
                qualities.firstOrNull()
            } else {
                state.currentVideoQuality
            }
            state.copy(videoQualities = qualities, currentVideoQuality = nextQuality)
        }
        refreshCurrentMenu()
    }

    fun setAudioQualities(qualities: List<AudioQuality>) {
        updateState { state ->
            val nextAudio = if (
                state.currentAudioQuality == null ||
                !qualities.any { it.id == state.currentAudioQuality.id }
            ) {
                qualities.firstOrNull()
            } else {
                state.currentAudioQuality
            }
            state.copy(audioQualities = qualities, currentAudioQuality = nextAudio)
        }
        refreshCurrentMenu()
    }

    fun setVideoCodecs(codecs: List<VideoCodecEnum>) {
        updateState { state ->
            val nextCodec = if (state.currentVideoCodec == null || !codecs.contains(state.currentVideoCodec)) {
                codecs.firstOrNull()
            } else {
                state.currentVideoCodec
            }
            state.copy(videoCodecs = codecs, currentVideoCodec = nextCodec)
        }
        refreshCurrentMenu()
    }

    fun setSubtitles(models: List<SubtitleInfoModel>) {
        updateState { state ->
            val nextPosition = state.currentSubtitlePosition.takeIf { it in models.indices } ?: -1
            state.copy(subtitles = models, currentSubtitlePosition = nextPosition)
        }
        refreshCurrentMenu()
    }

    fun setCurrentVideoQuality(quality: VideoQuality) {
        updateState { it.copy(currentVideoQuality = quality) }
        refreshCurrentMenu()
    }

    fun setCurrentAudioQuality(quality: AudioQuality) {
        updateState { it.copy(currentAudioQuality = quality) }
        refreshCurrentMenu()
    }

    fun setCurrentVideoCodec(codec: VideoCodecEnum) {
        updateState { it.copy(currentVideoCodec = codec) }
        refreshCurrentMenu()
    }

    fun setCurrentSubtitlePosition(position: Int) {
        updateState { it.copy(currentSubtitlePosition = position) }
        refreshCurrentMenu()
    }

    fun setCurrentSpeed(speed: Float) {
        updateState { it.copy(currentSpeed = speed) }
        refreshCurrentMenu()
    }

    fun setCurrentScreenRatio(ratio: Int) {
        updateState { it.copy(currentScreenRatio = ratio) }
        refreshCurrentMenu()
    }

    fun setAfterPlayMode(mode: AfterPlayMode) {
        updateState { it.copy(afterPlayMode = mode) }
        refreshCurrentMenu()
    }

    fun getAfterPlayMode(): AfterPlayMode = panelState.afterPlayMode

    fun showSubtitleMenu() {
        if (!isShowing()) {
            showHide(true)
            post { showSubtitles() }
        } else {
            showSubtitles()
        }
    }

    fun setLiveQualities(qualities: List<LiveQualityInfo>) {
        updateState { state ->
            val nextQn = if (state.currentLiveQualityQn == null ||
                !qualities.any { it.qn == state.currentLiveQualityQn }
            ) {
                qualities.firstOrNull()?.qn
            } else {
                state.currentLiveQualityQn
            }
            state.copy(liveQualities = qualities, currentLiveQualityQn = nextQn)
        }
        refreshCurrentMenu()
    }

    fun selectLiveQuality(qn: Int) {
        updateState { it.copy(currentLiveQualityQn = qn) }
        refreshCurrentMenu()
    }

    fun showLiveQualityMenu() {
        if (panelState.liveQualities.isEmpty()) return
        if (!isShowing()) {
            visibility = VISIBLE
            menuLevel = LEVEL_SUB
            updateBackIcon()
            showLiveQualitySubMenu()
            translationX = panelWidthPx.toFloat()
            alpha = 0f
            animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PANEL_ANIMATION_DURATION_MS)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        requestMenuFocus()
                        onVisibilityStateChanged?.invoke(true)
                    }
                })
                .start()
        } else {
            showLiveQualitySubMenu()
        }
    }

    private fun showLiveQualitySubMenu() {
        showSubMenu(ITEM_LIVE_QUALITY, menuBuilder.buildLiveQualityMenu(panelState))
    }

    fun dmEnableClick() {
        updateState { it.copy(dmEnabled = !it.dmEnabled) }
        onPlayerSettingInnerChange?.onDmEnableChange(panelState.dmEnabled)
        refreshCurrentMenu()
    }

    fun getDmEnable(): Boolean = panelState.dmEnabled
    fun getDmAlpha(): Float = panelState.dmAlpha
    fun getDmTextScaleParam(): Int = panelState.dmTextSize
    fun getDmSpeedParam(): Int = panelState.dmSpeed
    fun getScreenPartParam(): Int = panelState.dmArea
    fun getDmAllowTop(): Boolean = panelState.dmAllowTop
    fun getDmAllowBottom(): Boolean = panelState.dmAllowBottom
    fun getDmMergeDuplicate(): Boolean = panelState.dmMergeDuplicate
    fun getDmSmartShield(): Boolean = panelState.dmSmartShield

    fun setOnPlayerSettingChange(listener: OnPlayerSettingChange?) {
        onPlayerSettingChange = listener
    }

    fun setOnPlayerSettingInnerChange(listener: OnPlayerSettingInnerChange?) {
        onPlayerSettingInnerChange = listener
    }

    fun setOnVisibilityStateChangedListener(listener: ((Boolean) -> Unit)?) {
        onVisibilityStateChanged = listener
    }

    private fun onItemClicked(item: PlayerSettingRow.Item) {
        val focusedPos = findFocusedAdapterPosition()
        Log.d("SettingFocus", "onItemClicked level=$menuLevel item=${item.id} title=${item.title} focusedPos=$focusedPos currentMain=$lastFocusedMainItemPosition currentDm=$lastFocusedDmItemPosition menuKey=${adapter.currentMenuKey}")
        when (menuLevel) {
            LEVEL_MAIN -> {
                lastFocusedMainItemPosition = focusedPos
                Log.d("SettingFocus", "  -> saved mainPos=$lastFocusedMainItemPosition")
                handleMainMenuClick(item.id)
            }
            LEVEL_SUB -> {
                if (adapter.currentMenuKey == ITEM_DM_SETTING) {
                    lastFocusedDmItemPosition = focusedPos
                    Log.d("SettingFocus", "  -> saved dmPos=$lastFocusedDmItemPosition")
                }
                handleSubMenuClick(item.id)
            }
            LEVEL_DM -> handleDmMenuClick(item.id)
        }
    }

    private fun handleMainMenuClick(itemId: Int) {
        when (itemId) {
            ITEM_VIDEO_QUALITY -> showVideoQualityMenu()
            ITEM_PLAYBACK_SPEED -> showPlaybackSpeedMenu()
            ITEM_AFTER_PLAY -> showAfterPlayMenu()
            ITEM_SUBTITLE -> showSubtitles()
            ITEM_VIDEO_CODEC -> showVideoCodecMenu()
            ITEM_AUDIO_QUALITY -> showAudioQualityMenu()
            ITEM_ASPECT_RATIO -> showScreenRatioMenu()
            ITEM_DM_SETTING -> showDmSettingMenu()
            ITEM_SCREEN_MIRROR -> {
                val newValue = !panelState.screenMirrorEnabled
                updateState { it.copy(screenMirrorEnabled = newValue) }
                onPlayerSettingChange?.onScreenMirrorChange(newValue)
                val label = if (newValue) context.getString(R.string.on) else context.getString(R.string.off)
                Toast.makeText(context, "${context.getString(R.string.screen_mirror)}：$label", Toast.LENGTH_SHORT).show()
                refreshCurrentMenu()
            }
        }
    }

    private fun handleSubMenuClick(itemId: Int) {
        if (adapter.currentMenuKey == ITEM_DM_SETTING) {
            if (handleDmToggleClick(itemId)) return
            showDmOptionSubMenu(itemId)
            return
        }

        when {
            adapter.currentMenuKey == ITEM_VIDEO_QUALITY && itemId in panelState.videoQualities.indices -> {
                val selected = panelState.videoQualities[itemId]
                updateState { it.copy(currentVideoQuality = selected) }
                onPlayerSettingChange?.onVideoQualityChange(selected)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_PLAYBACK_SPEED && itemId in PLAYBACK_SPEEDS.indices -> {
                val selected = PLAYBACK_SPEEDS[itemId]
                updateState { it.copy(currentSpeed = selected) }
                onPlayerSettingChange?.onPlaybackSpeedChange(selected)
                onPlayerSettingInnerChange?.onPlaybackSpeedChange(selected)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_SUBTITLE && (itemId == -1 || itemId in panelState.subtitles.indices) -> {
                updateState { it.copy(currentSubtitlePosition = itemId) }
                onPlayerSettingChange?.onSubtitleChange(itemId)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_VIDEO_CODEC && itemId in panelState.videoCodecs.indices -> {
                val selected = panelState.videoCodecs[itemId]
                updateState { it.copy(currentVideoCodec = selected) }
                onPlayerSettingChange?.onVideoCodecChange(selected)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_AFTER_PLAY -> {
                val options = menuBuilder.AFTER_PLAY_OPTIONS
                val selected = options.getOrNull(itemId)?.first ?: return
                updateState { it.copy(afterPlayMode = selected) }
                onPlayerSettingChange?.onAfterPlayModeChange(selected)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_AUDIO_QUALITY && itemId in panelState.audioQualities.indices -> {
                val selected = panelState.audioQualities[itemId]
                updateState { it.copy(currentAudioQuality = selected) }
                onPlayerSettingChange?.onAudioQualityChange(selected)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_ASPECT_RATIO && itemId in screenRatioLabels.indices -> {
                updateState { it.copy(currentScreenRatio = itemId) }
                onPlayerSettingChange?.onAspectRatioChange(itemId)
                onPlayerSettingInnerChange?.onAspectRatioChange(itemId)
                goBackToMainMenu()
            }

            adapter.currentMenuKey == ITEM_LIVE_QUALITY && itemId in panelState.liveQualities.indices -> {
                val selected = panelState.liveQualities[itemId]
                updateState { it.copy(currentLiveQualityQn = selected.qn) }
                onPlayerSettingChange?.onLiveQualityChange(selected.qn)
                showHide(false)
            }
        }
    }

    private fun handleDmMenuClick(itemId: Int) {
        when (adapter.currentMenuKey) {
            ITEM_DM_ENABLE -> {
                updateState { it.copy(dmEnabled = itemId == 0) }
                onPlayerSettingInnerChange?.onDmEnableChange(panelState.dmEnabled)
            }

            ITEM_DM_ALPHA -> {
                val selected = DM_ALPHA_VALUES.getOrNull(itemId) ?: return
                updateState { it.copy(dmAlpha = selected) }
                onPlayerSettingInnerChange?.onDmAlpha(selected)
            }

            ITEM_DM_TEXT_SIZE -> {
                val selected = DM_TEXT_SIZE_VALUES.getOrNull(itemId) ?: return
                updateState { it.copy(dmTextSize = selected) }
                onPlayerSettingInnerChange?.onDmTextSize(selected)
            }

            ITEM_DM_AREA -> {
                val selected = DM_AREA_VALUES.getOrNull(itemId) ?: return
                updateState { it.copy(dmArea = selected.area) }
                onPlayerSettingInnerChange?.onDmScreenArea(selected.area)
            }

            ITEM_DM_SPEED -> {
                val selected = DM_SPEED_VALUES.getOrNull(itemId) ?: return
                updateState { it.copy(dmSpeed = selected) }
                onPlayerSettingInnerChange?.onDmSpeed(selected)
            }

            ITEM_DM_ALLOW_TOP -> {
                updateState { it.copy(dmAllowTop = itemId == 0) }
                onPlayerSettingInnerChange?.onDmAllowTop(panelState.dmAllowTop)
            }

            ITEM_DM_ALLOW_BOTTOM -> {
                updateState { it.copy(dmAllowBottom = itemId == 0) }
                onPlayerSettingInnerChange?.onDmAllowBottom(panelState.dmAllowBottom)
            }

            ITEM_DM_MERGE_DUPLICATE -> {
                updateState { it.copy(dmMergeDuplicate = itemId == 0) }
                onPlayerSettingInnerChange?.onDmMergeDuplicate(panelState.dmMergeDuplicate)
            }

            ITEM_DM_SMART_SHIELD -> {
                updateState { it.copy(dmSmartShield = itemId == 0) }
                onPlayerSettingInnerChange?.onDmSmartShield(panelState.dmSmartShield)
            }
        }
        showDmSettingMenu(animateTransition = true)
    }

    private fun handleDmToggleClick(itemId: Int): Boolean {
        val title: String
        val newValue: Boolean
        when (itemId) {
            ITEM_DM_ENABLE -> {
                newValue = !panelState.dmEnabled
                updateState { it.copy(dmEnabled = newValue) }
                onPlayerSettingInnerChange?.onDmEnableChange(newValue)
                title = context.getString(R.string.dm_switch)
            }
            ITEM_DM_ALLOW_TOP -> {
                newValue = !panelState.dmAllowTop
                updateState { it.copy(dmAllowTop = newValue) }
                onPlayerSettingInnerChange?.onDmAllowTop(newValue)
                title = context.getString(R.string.dm_allow_top)
            }
            ITEM_DM_ALLOW_BOTTOM -> {
                newValue = !panelState.dmAllowBottom
                updateState { it.copy(dmAllowBottom = newValue) }
                onPlayerSettingInnerChange?.onDmAllowBottom(newValue)
                title = context.getString(R.string.dm_allow_bottom)
            }
            ITEM_DM_MERGE_DUPLICATE -> {
                newValue = !panelState.dmMergeDuplicate
                updateState { it.copy(dmMergeDuplicate = newValue) }
                onPlayerSettingInnerChange?.onDmMergeDuplicate(newValue)
                title = context.getString(R.string.dm_merge_duplicate)
            }
            ITEM_DM_SMART_SHIELD -> {
                newValue = !panelState.dmSmartShield
                updateState { it.copy(dmSmartShield = newValue) }
                onPlayerSettingInnerChange?.onDmSmartShield(newValue)
                title = context.getString(R.string.dm_smart_shield)
            }
            else -> return false
        }
        val label = if (newValue) context.getString(R.string.on) else context.getString(R.string.off)
        Toast.makeText(context, "$title：$label", Toast.LENGTH_SHORT).show()
        when {
            itemId in ITEM_DM_ENABLE..ITEM_DM_SMART_SHIELD -> showDmSettingMenu(animateTransition = true)
        }
        return true
    }

    private fun showVideoQualityMenu() {
        showSubMenu(ITEM_VIDEO_QUALITY, menuBuilder.buildVideoQualityMenu(panelState))
    }

    private fun showPlaybackSpeedMenu() {
        showSubMenu(ITEM_PLAYBACK_SPEED, menuBuilder.buildPlaybackSpeedMenu(panelState))
    }

    private fun showSubtitles() {
        if (panelState.subtitles.isEmpty()) {
            return
        }
        showSubMenu(ITEM_SUBTITLE, menuBuilder.buildSubtitleMenu(panelState))
    }

    private fun showVideoCodecMenu() {
        showSubMenu(ITEM_VIDEO_CODEC, menuBuilder.buildVideoCodecMenu(panelState))
    }

    private fun showAudioQualityMenu() {
        showSubMenu(ITEM_AUDIO_QUALITY, menuBuilder.buildAudioQualityMenu(panelState))
    }

    private fun showAfterPlayMenu() {
        showSubMenu(ITEM_AFTER_PLAY, menuBuilder.buildAfterPlayMenu(panelState))
    }

    private fun showScreenRatioMenu() {
        showSubMenu(ITEM_ASPECT_RATIO, menuBuilder.buildScreenRatioMenu(panelState))
    }

    private fun showDmSettingMenu() {
        showDmSettingMenu(animateTransition = true)
    }

    private fun showDmSettingMenu(animateTransition: Boolean) {
        Log.d("SettingFocus", "showDmSettingMenu dmPos=$lastFocusedDmItemPosition animate=$animateTransition")
        menuLevel = LEVEL_SUB
        updateBackIcon()
        submitMenuRows(
            menuKey = ITEM_DM_SETTING,
            rows = menuBuilder.buildDmSettingMenu(panelState),
            animateTransition = animateTransition,
            focusPosition = lastFocusedDmItemPosition
        )
    }

    private fun showSubMenu(menuKey: Int, rows: List<PlayerSettingRow>) {
        showSubMenu(menuKey, rows, animateTransition = true)
    }

    private fun showSubMenu(
        menuKey: Int,
        rows: List<PlayerSettingRow>,
        animateTransition: Boolean
    ) {
        menuLevel = LEVEL_SUB
        updateBackIcon()
        submitMenuRows(menuKey = menuKey, rows = rows, animateTransition = animateTransition)
    }

    private fun goBackToMainMenu() {
        Log.d("SettingFocus", "goBackToMainMenu mainPos=$lastFocusedMainItemPosition")
        menuLevel = LEVEL_MAIN
        updateMainMenu(animateTransition = true, focusPosition = lastFocusedMainItemPosition)
        updateBackIcon()
    }

    private fun updateMainMenu(animateTransition: Boolean = false, focusPosition: Int = 1) {
        submitMenuRows(
            menuKey = ITEM_MAIN_MENU,
            rows = menuBuilder.buildMainMenu(panelState),
            animateTransition = animateTransition,
            focusPosition = focusPosition
        )
    }

    // Refreshes only the currently visible menu so state changes do not rebuild unrelated levels.
    private fun refreshCurrentMenu() {
        when (menuLevel) {
            LEVEL_MAIN -> updateMainMenu(animateTransition = false)
            LEVEL_SUB -> when (adapter.currentMenuKey) {
                ITEM_VIDEO_QUALITY -> showSubMenu(
                    ITEM_VIDEO_QUALITY,
                    menuBuilder.buildVideoQualityMenu(panelState),
                    animateTransition = false
                )
                ITEM_PLAYBACK_SPEED -> showSubMenu(
                    ITEM_PLAYBACK_SPEED,
                    menuBuilder.buildPlaybackSpeedMenu(panelState),
                    animateTransition = false
                )
                ITEM_SUBTITLE -> {
                    if (panelState.subtitles.isNotEmpty()) {
                        showSubMenu(
                            ITEM_SUBTITLE,
                            menuBuilder.buildSubtitleMenu(panelState),
                            animateTransition = false
                        )
                    }
                }
                ITEM_VIDEO_CODEC -> showSubMenu(
                    ITEM_VIDEO_CODEC,
                    menuBuilder.buildVideoCodecMenu(panelState),
                    animateTransition = false
                )
                ITEM_AFTER_PLAY -> showSubMenu(
                    ITEM_AFTER_PLAY,
                    menuBuilder.buildAfterPlayMenu(panelState),
                    animateTransition = false
                )
                ITEM_AUDIO_QUALITY -> showSubMenu(
                    ITEM_AUDIO_QUALITY,
                    menuBuilder.buildAudioQualityMenu(panelState),
                    animateTransition = false
                )
                ITEM_ASPECT_RATIO -> showSubMenu(
                    ITEM_ASPECT_RATIO,
                    menuBuilder.buildScreenRatioMenu(panelState),
                    animateTransition = false
                )
                ITEM_DM_SETTING -> showDmSettingMenu(animateTransition = false)
                ITEM_LIVE_QUALITY -> showSubMenu(
                    ITEM_LIVE_QUALITY,
                    menuBuilder.buildLiveQualityMenu(panelState),
                    animateTransition = false
                )
            }

            LEVEL_DM -> showDmOptionSubMenu(adapter.currentMenuKey, animateTransition = false)
        }
    }

    private fun updateBackIcon() {
        iconBack.setImageResource(if (menuLevel == LEVEL_MAIN) R.drawable.ic_close else R.drawable.ic_back)
    }

    private fun showDmOptionSubMenu(itemId: Int) {
        showDmOptionSubMenu(itemId, animateTransition = true)
    }

    private fun showDmOptionSubMenu(itemId: Int, animateTransition: Boolean) {
        val menu = menuBuilder.buildDmChoiceMenu(itemId, panelState) ?: return
        showDmChoiceMenu(
            menuKey = menu.menuKey,
            title = menu.title,
            values = menu.values,
            selectedIndex = menu.selectedIndex,
            animateTransition = animateTransition
        )
    }

    private fun showDmChoiceMenu(
        menuKey: Int,
        title: String,
        values: List<String>,
        selectedIndex: Int,
        animateTransition: Boolean
    ) {
        menuLevel = LEVEL_DM
        updateBackIcon()
        val rows = mutableListOf<PlayerSettingRow>(PlayerSettingRow.Header(title = title))
        rows += values.mapIndexed { index, value ->
            PlayerSettingRow.Item(
                id = index,
                title = value,
                checked = index == selectedIndex,
                showArrow = false
            )
        }
        submitMenuRows(menuKey = menuKey, rows = rows, animateTransition = animateTransition)
    }

    private fun submitMenuRows(
        menuKey: Int,
        rows: List<PlayerSettingRow>,
        animateTransition: Boolean,
        focusPosition: Int = 1
    ) {
        recyclerView.animate().cancel()
        recyclerView.animate().setListener(null)
        clearTransientItemStates()
        if (!animateTransition || !isShowing() || adapter.itemCount == 0) {
            recyclerView.alpha = 1f
            applyMenuRows(menuKey, rows, focusPosition = focusPosition)
            return
        }

        recyclerView.animate()
            .alpha(0f)
            .setDuration(MENU_FADE_OUT_DURATION_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    recyclerView.animate().setListener(null)
                    applyMenuRows(menuKey, rows, requestFocusAfter = false, focusPosition = focusPosition)
                    recyclerView.alpha = 0f
                    recyclerView.animate()
                        .alpha(1f)
                        .setDuration(MENU_FADE_IN_DURATION_MS)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                recyclerView.animate().setListener(null)
                                requestMenuFocus(focusPosition)
                            }
                        })
                        .start()
                }
            })
            .start()
    }

    private fun applyMenuRows(
        menuKey: Int,
        rows: List<PlayerSettingRow>,
        requestFocusAfter: Boolean = true,
        focusPosition: Int = 1
    ) {
        if (requestFocusAfter) {
            adapter.submitRows(menuKey, rows) {
                recyclerView.scrollToPosition(0)
                requestMenuFocus(focusPosition)
            }
        } else {
            adapter.submitRows(menuKey, rows)
            recyclerView.scrollToPosition(0)
        }
    }

    private fun clearTransientItemStates() {
        recyclerView.isPressed = false
        recyclerView.jumpDrawablesToCurrentState()
        for (index in 0 until recyclerView.childCount) {
            recyclerView.getChildAt(index)?.let { child ->
                child.isPressed = false
                child.isActivated = false
                child.isSelected = false
                child.jumpDrawablesToCurrentState()
            }
        }
    }

    private fun requestMenuFocus(preferredPosition: Int = 1) {
        Log.d("SettingFocus", "requestMenuFocus preferred=$preferredPosition itemCount=${adapter.itemCount} menuKey=${adapter.currentMenuKey}")
        recyclerView.post {
            val pos = preferredPosition.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            val targetView = recyclerView.findViewHolderForAdapterPosition(pos)?.itemView
                ?: recyclerView.layoutManager?.findViewByPosition(pos)
            Log.d("SettingFocus", "  post: pos=$pos targetView=$targetView isFocusable=${targetView?.isFocusable}")
            if (targetView?.isFocusable == true) {
                targetView.requestFocus()
            } else {
                recyclerView.requestFocus()
            }
            Log.d("SettingFocus", "  post: focusAfter=${findFocus()}")
        }
    }

    private fun findFocusedAdapterPosition(): Int {
        val focused = findFocus() ?: return 1
        val itemView = recyclerView.findContainingItemView(focused)
        return if (itemView != null) {
            recyclerView.getChildAdapterPosition(itemView).coerceAtLeast(1)
        } else {
            1
        }
    }

    private fun updateState(
        transform: (MyPlayerSettingMenuBuilder.PanelState) -> MyPlayerSettingMenuBuilder.PanelState
    ) {
        panelState = transform(panelState)
    }

    private val screenRatioLabels: Array<String>
        get() = menuBuilder.screenRatioLabels()
}
