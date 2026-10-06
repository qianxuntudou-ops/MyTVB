package com.mytvb.feature.player.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.settings.AfterPlayMode
import com.mytvb.feature.player.LiveLineInfo
import com.mytvb.feature.player.LiveQualityInfo
import com.mytvb.model.dm.DmScreenArea
import com.mytvb.model.subtitle.SubtitleInfoModel
import com.mytvb.model.video.quality.AudioQuality
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality

class MyPlayerSettingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), View.OnClickListener {

    companion object {
        private const val SETTING_FOCUS_LOG_ENABLED = false
        internal val PLAYBACK_SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        internal val DM_ALPHA_VALUES = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)
        // 30-55 为历史档位；56-100 供超宽/低密度屏（如 5120×1600）放大使用
        /** 弹幕字号档位：30~60 步进 2（16 档，视觉放大由引擎 toBlblTextScale 档位系数承担）。 */
        internal val DM_TEXT_SIZE_VALUES = IntArray(16) { 30 + it * 2 }

        /** 字号存储值归一到最近档位（兼容旧版 30~100 逐档存储值，如 39/55/100）。 */
        internal fun coerceDmTextSize(value: Int): Int =
            DM_TEXT_SIZE_VALUES.minBy { kotlin.math.abs(it - value) }
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
        internal const val KEY_DM_TRACK_SPACING = "dm_track_spacing"

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
        internal const val ITEM_LIVE_LINE = 11
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

        /** 列表首个可聚焦项的 adapter 位置（position 0 是 Header 标题，不可聚焦）。 */
        private const val FIRST_FOCUSABLE_ITEM_POSITION = 1
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
                    // 入场动画结束才补聚焦主菜单第 1 行；但若动画期间面板已被切到子菜单
                    // （如控制栏"播放速度"按键直接打开倍速子菜单），此处不能再抢焦点，
                    // 否则会把子菜单已定位到当前倍速的焦点拉回主菜单第 1 行。
                    if (adapter.currentMenuKey == ITEM_MAIN_MENU) {
                        requestMenuFocus()
                    }
                    onVisibilityStateChanged?.invoke(true)
                }
            })
            .start()
    }

    fun onBack(): Boolean {
        logSettingFocus("onBack level=$menuLevel menuKey=${adapter.currentMenuKey} mainPos=$lastFocusedMainItemPosition dmPos=$lastFocusedDmItemPosition")
        return when {
            menuLevel == LEVEL_MAIN -> {
                showHide(false)
                true
            }

            adapter.currentMenuKey == ITEM_LIVE_QUALITY -> {
                showHide(false)
                true
            }

            adapter.currentMenuKey == ITEM_LIVE_LINE -> {
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
                    if (focused.parent === recyclerView && isLastAdapterItem(focused)) return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val focused = findFocus() ?: return super.dispatchKeyEvent(event)
                    if (focused.parent === recyclerView && isFirstFocusableAdapterItem(focused)) {
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 列表边界吞方向键的判定必须用 adapter 位置，不能用 getChildAt(0)/getChildAt(N-1)：
     * 那是 attach 缓存的首尾项，滚动中途会指向列表中间——长列表（如弹幕字号 30-100）
     * 滚到底再回头向上时，焦点走到 attach 顶就被误吞 UP 键，表现为"只能回滚一屏、
     * 必须退出重进才能继续选更小字号"。
     */
    private fun isFirstFocusableAdapterItem(view: View): Boolean =
        recyclerView.getChildAdapterPosition(view) <= FIRST_FOCUSABLE_ITEM_POSITION

    private fun isLastAdapterItem(view: View): Boolean {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        return itemCount > 0 && recyclerView.getChildAdapterPosition(view) >= itemCount - 1
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

    /** 控制栏"播放速度"按键入口：直接打开倍速子菜单（面板未显示时先入场再打开）。 */
    fun showPlaybackSpeedMenu() {
        if (!isShowing()) {
            showHide(true)
            post { showPlaybackSpeedSubMenu() }
        } else {
            showPlaybackSpeedSubMenu()
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
        showSubMenu(ITEM_LIVE_QUALITY, menuBuilder.buildLiveQualityMenu(panelState), focusPosition = liveQualityFocusPosition())
    }

    fun setLiveLines(lines: List<LiveLineInfo>, selectedIndex: Int) {
        updateState { state ->
            val nextIndex = if (selectedIndex !in lines.indices) 0 else selectedIndex
            state.copy(liveLines = lines, currentLiveLineIndex = nextIndex)
        }
        refreshCurrentMenu()
    }

    fun selectLiveLine(index: Int) {
        updateState { it.copy(currentLiveLineIndex = index) }
        refreshCurrentMenu()
    }

    fun showLiveLineMenu() {
        if (panelState.liveLines.isEmpty()) return
        if (!isShowing()) {
            visibility = VISIBLE
            menuLevel = LEVEL_SUB
            updateBackIcon()
            showLiveLineSubMenu()
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
            showLiveLineSubMenu()
        }
    }

    private fun showLiveLineSubMenu() {
        showSubMenu(ITEM_LIVE_LINE, menuBuilder.buildLiveLineMenu(panelState), focusPosition = liveLineFocusPosition())
    }

    private fun liveQualityFocusPosition(): Int =
        panelState.liveQualities
            .indexOfFirst { it.qn == panelState.currentLiveQualityQn }
            .let { if (it >= 0) it + 1 else 1 }

    private fun liveLineFocusPosition(): Int = panelState.currentLiveLineIndex + 1

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
    fun getDmTrackSpacingPref(): String = preferenceStore.getTrackSpacingPref()

    fun setScreenMirrorEnabled(enabled: Boolean) {
        updateState { state ->
            state.copy(
                screenMirrorEnabled = enabled,
                dmSmartShield = if (enabled) false else state.dmSmartShield
            )
        }
        refreshCurrentMenuInPlace()
    }

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
        logSettingFocus("onItemClicked level=$menuLevel item=${item.id} title=${item.title} focusedPos=$focusedPos currentMain=$lastFocusedMainItemPosition currentDm=$lastFocusedDmItemPosition menuKey=${adapter.currentMenuKey}")
        when (menuLevel) {
            LEVEL_MAIN -> {
                lastFocusedMainItemPosition = focusedPos
                logSettingFocus("  -> saved mainPos=$lastFocusedMainItemPosition")
                handleMainMenuClick(item.id)
            }
            LEVEL_SUB -> {
                if (adapter.currentMenuKey == ITEM_DM_SETTING) {
                    lastFocusedDmItemPosition = focusedPos
                    logSettingFocus("  -> saved dmPos=$lastFocusedDmItemPosition")
                }
                handleSubMenuClick(item.id)
            }
            LEVEL_DM -> handleDmMenuClick(item.id)
        }
    }

    private fun handleMainMenuClick(itemId: Int) {
        when (itemId) {
            ITEM_VIDEO_QUALITY -> showVideoQualityMenu()
            ITEM_PLAYBACK_SPEED -> showPlaybackSpeedSubMenu()
            ITEM_AFTER_PLAY -> showAfterPlayMenu()
            ITEM_SUBTITLE -> showSubtitles()
            ITEM_VIDEO_CODEC -> showVideoCodecMenu()
            ITEM_AUDIO_QUALITY -> showAudioQualityMenu()
            ITEM_ASPECT_RATIO -> showScreenRatioMenu()
            ITEM_DM_SETTING -> showDmSettingMenu()
            ITEM_SCREEN_MIRROR -> {
                val newValue = !panelState.screenMirrorEnabled
                updateState { it.copy(screenMirrorEnabled = newValue, dmSmartShield = if (newValue) false else it.dmSmartShield) }
                if (newValue) {
                    onPlayerSettingInnerChange?.onDmSmartShield(false)
                }
                onPlayerSettingChange?.onScreenMirrorChange(newValue)
                val label = if (newValue) context.getString(R.string.on) else context.getString(R.string.off)
                Toast.makeText(context, "${context.getString(R.string.screen_mirror)}：$label", Toast.LENGTH_SHORT).show()
                refreshCurrentMenuInPlace()
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

            adapter.currentMenuKey == ITEM_LIVE_LINE && itemId in panelState.liveLines.indices -> {
                updateState { it.copy(currentLiveLineIndex = itemId) }
                onPlayerSettingChange?.onLiveLineChange(itemId)
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
                val newValue = itemId == 0 && !panelState.screenMirrorEnabled
                updateState { it.copy(dmSmartShield = newValue) }
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
                newValue = !panelState.dmSmartShield && !panelState.screenMirrorEnabled
                updateState { it.copy(dmSmartShield = newValue) }
                onPlayerSettingInnerChange?.onDmSmartShield(newValue)
                title = context.getString(R.string.dm_smart_shield)
            }
            else -> return false
        }
        val label = if (newValue) context.getString(R.string.on) else context.getString(R.string.off)
        Toast.makeText(context, "$title：$label", Toast.LENGTH_SHORT).show()
        refreshCurrentMenuInPlace()
        return true
    }

    private fun showVideoQualityMenu() {
        showSubMenu(ITEM_VIDEO_QUALITY, menuBuilder.buildVideoQualityMenu(panelState), focusPosition = videoQualityFocusPosition())
    }

    private fun showPlaybackSpeedSubMenu() {
        showSubMenu(ITEM_PLAYBACK_SPEED, menuBuilder.buildPlaybackSpeedMenu(panelState), focusPosition = playbackSpeedFocusPosition())
    }

    private fun showSubtitles() {
        if (panelState.subtitles.isEmpty()) {
            return
        }
        showSubMenu(ITEM_SUBTITLE, menuBuilder.buildSubtitleMenu(panelState), focusPosition = subtitleFocusPosition())
    }

    private fun showVideoCodecMenu() {
        showSubMenu(ITEM_VIDEO_CODEC, menuBuilder.buildVideoCodecMenu(panelState), focusPosition = videoCodecFocusPosition())
    }

    private fun showAudioQualityMenu() {
        showSubMenu(ITEM_AUDIO_QUALITY, menuBuilder.buildAudioQualityMenu(panelState), focusPosition = audioQualityFocusPosition())
    }

    private fun showAfterPlayMenu() {
        showSubMenu(ITEM_AFTER_PLAY, menuBuilder.buildAfterPlayMenu(panelState), focusPosition = afterPlayFocusPosition())
    }

    private fun showScreenRatioMenu() {
        showSubMenu(ITEM_ASPECT_RATIO, menuBuilder.buildScreenRatioMenu(panelState), focusPosition = screenRatioFocusPosition())
    }

    // All choice sub-menus have a Header as row 0, so the focused row is always selectedIndex + 1.

    private fun videoQualityFocusPosition(): Int =
        panelState.videoQualities
            .indexOfFirst { it.id == panelState.currentVideoQuality?.id }
            .let { if (it >= 0) it + 1 else 1 }

    private fun playbackSpeedFocusPosition(): Int {
        val speedIndex = PLAYBACK_SPEEDS.indexOfFirst { it == panelState.currentSpeed }
        logSettingFocus("showPlaybackSpeedMenu speed=${panelState.currentSpeed} speedIndex=$speedIndex")
        return if (speedIndex >= 0) speedIndex + 1 else 1
    }

    // Menu layout: [Header(row 0), "关闭"(row 1, currentSubtitlePosition == -1), subtitle0(row 2), subtitle1(row 3), ...].
    // So the focused row is currentSubtitlePosition + 2: -1 -> 1 ("关闭"), 0 -> 2 (first subtitle).
    private fun subtitleFocusPosition(): Int = panelState.currentSubtitlePosition + 2

    private fun videoCodecFocusPosition(): Int =
        panelState.videoCodecs
            .indexOfFirst { it == panelState.currentVideoCodec }
            .let { if (it >= 0) it + 1 else 1 }

    private fun audioQualityFocusPosition(): Int =
        panelState.audioQualities
            .indexOfFirst { it.id == panelState.currentAudioQuality?.id }
            .let { if (it >= 0) it + 1 else 1 }

    private fun afterPlayFocusPosition(): Int =
        menuBuilder.AFTER_PLAY_OPTIONS
            .indexOfFirst { it.first == panelState.afterPlayMode }
            .let { if (it >= 0) it + 1 else 1 }

    private fun screenRatioFocusPosition(): Int = panelState.currentScreenRatio + 1

    private fun showDmSettingMenu() {
        showDmSettingMenu(animateTransition = true)
    }

    private fun showDmSettingMenu(animateTransition: Boolean) {
        logSettingFocus("showDmSettingMenu dmPos=$lastFocusedDmItemPosition animate=$animateTransition")
        menuLevel = LEVEL_SUB
        updateBackIcon()
        submitMenuRows(
            menuKey = ITEM_DM_SETTING,
            rows = menuBuilder.buildDmSettingMenu(panelState),
            animateTransition = animateTransition,
            focusPosition = lastFocusedDmItemPosition
        )
    }

    private fun showSubMenu(
        menuKey: Int,
        rows: List<PlayerSettingRow>,
        animateTransition: Boolean = true,
        focusPosition: Int = 1
    ) {
        menuLevel = LEVEL_SUB
        updateBackIcon()
        submitMenuRows(menuKey = menuKey, rows = rows, animateTransition = animateTransition, focusPosition = focusPosition)
    }

    private fun goBackToMainMenu() {
        logSettingFocus("goBackToMainMenu mainPos=$lastFocusedMainItemPosition")
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
            LEVEL_MAIN -> updateMainMenu(animateTransition = false, focusPosition = lastFocusedMainItemPosition)
            LEVEL_SUB -> when (adapter.currentMenuKey) {
                ITEM_VIDEO_QUALITY -> showSubMenu(
                    ITEM_VIDEO_QUALITY,
                    menuBuilder.buildVideoQualityMenu(panelState),
                    animateTransition = false,
                    focusPosition = videoQualityFocusPosition()
                )
                ITEM_PLAYBACK_SPEED -> showSubMenu(
                    ITEM_PLAYBACK_SPEED,
                    menuBuilder.buildPlaybackSpeedMenu(panelState),
                    animateTransition = false,
                    focusPosition = playbackSpeedFocusPosition()
                )
                ITEM_SUBTITLE -> {
                    if (panelState.subtitles.isNotEmpty()) {
                        showSubMenu(
                            ITEM_SUBTITLE,
                            menuBuilder.buildSubtitleMenu(panelState),
                            animateTransition = false,
                            focusPosition = subtitleFocusPosition()
                        )
                    }
                }
                ITEM_VIDEO_CODEC -> showSubMenu(
                    ITEM_VIDEO_CODEC,
                    menuBuilder.buildVideoCodecMenu(panelState),
                    animateTransition = false,
                    focusPosition = videoCodecFocusPosition()
                )
                ITEM_AFTER_PLAY -> showSubMenu(
                    ITEM_AFTER_PLAY,
                    menuBuilder.buildAfterPlayMenu(panelState),
                    animateTransition = false,
                    focusPosition = afterPlayFocusPosition()
                )
                ITEM_AUDIO_QUALITY -> showSubMenu(
                    ITEM_AUDIO_QUALITY,
                    menuBuilder.buildAudioQualityMenu(panelState),
                    animateTransition = false,
                    focusPosition = audioQualityFocusPosition()
                )
                ITEM_ASPECT_RATIO -> showSubMenu(
                    ITEM_ASPECT_RATIO,
                    menuBuilder.buildScreenRatioMenu(panelState),
                    animateTransition = false,
                    focusPosition = screenRatioFocusPosition()
                )
                ITEM_DM_SETTING -> showDmSettingMenu(animateTransition = false)
                ITEM_LIVE_QUALITY -> showSubMenu(
                    ITEM_LIVE_QUALITY,
                    menuBuilder.buildLiveQualityMenu(panelState),
                    animateTransition = false,
                    focusPosition = liveQualityFocusPosition()
                )
                ITEM_LIVE_LINE -> showSubMenu(
                    ITEM_LIVE_LINE,
                    menuBuilder.buildLiveLineMenu(panelState),
                    animateTransition = false,
                    focusPosition = liveLineFocusPosition()
                )
            }

            LEVEL_DM -> showDmOptionSubMenu(adapter.currentMenuKey, animateTransition = false)
        }
    }

    private fun refreshCurrentMenuInPlace() {
        val rows = currentMenuRows() ?: run {
            refreshCurrentMenu()
            return
        }
        recyclerView.animate().cancel()
        recyclerView.animate().setListener(null)
        recyclerView.alpha = 1f
        clearTransientItemStates()
        adapter.submitRows(adapter.currentMenuKey, rows)
    }

    private fun currentMenuRows(): List<PlayerSettingRow>? {
        return when (menuLevel) {
            LEVEL_MAIN -> menuBuilder.buildMainMenu(panelState)
            LEVEL_SUB -> when (adapter.currentMenuKey) {
                ITEM_VIDEO_QUALITY -> menuBuilder.buildVideoQualityMenu(panelState)
                ITEM_PLAYBACK_SPEED -> menuBuilder.buildPlaybackSpeedMenu(panelState)
                ITEM_SUBTITLE -> menuBuilder.buildSubtitleMenu(panelState).takeIf { panelState.subtitles.isNotEmpty() }
                ITEM_VIDEO_CODEC -> menuBuilder.buildVideoCodecMenu(panelState)
                ITEM_AFTER_PLAY -> menuBuilder.buildAfterPlayMenu(panelState)
                ITEM_AUDIO_QUALITY -> menuBuilder.buildAudioQualityMenu(panelState)
                ITEM_ASPECT_RATIO -> menuBuilder.buildScreenRatioMenu(panelState)
                ITEM_DM_SETTING -> menuBuilder.buildDmSettingMenu(panelState)
                ITEM_LIVE_QUALITY -> menuBuilder.buildLiveQualityMenu(panelState)
                ITEM_LIVE_LINE -> menuBuilder.buildLiveLineMenu(panelState)
                else -> null
            }
            LEVEL_DM -> {
                val menu = menuBuilder.buildDmChoiceMenu(adapter.currentMenuKey, panelState) ?: return null
                mutableListOf<PlayerSettingRow>(PlayerSettingRow.Header(title = menu.title)).apply {
                    addAll(menu.values.mapIndexed { index, value ->
                        PlayerSettingRow.Item(
                            id = index,
                            title = value,
                            checked = index == menu.selectedIndex,
                            showArrow = false
                        )
                    })
                }
            }
            else -> null
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
        val focusPosition = selectedIndex + 1 // +1 to skip Header row
        submitMenuRows(menuKey = menuKey, rows = rows, animateTransition = animateTransition, focusPosition = focusPosition)
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
                    applyMenuRows(menuKey, rows, focusPosition = focusPosition)
                    recyclerView.alpha = 0f
                    recyclerView.animate()
                        .alpha(1f)
                        .setDuration(MENU_FADE_IN_DURATION_MS)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                recyclerView.animate().setListener(null)
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
            // Clear any existing focus before submitting so the system does not auto-focus the
            // first visible item during the relayout (which would cause a visible focus "jump"
            // before we explicitly place focus on the intended item).
            recyclerView.clearFocus()
            adapter.submitRows(menuKey, rows) {
                // Scroll directly to the focus target so its view is laid out before we request focus.
                recyclerView.scrollToPosition(focusPosition)
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
        // 面板未显示时不请求焦点：GONE 容器内的列表自身仍标记 VISIBLE，
        // requestFocus() 只查自身可见性不查祖先，会把焦点从可见 UI 抢到隐形列表上。
        if (!isShowing()) return
        logSettingFocus("requestMenuFocus preferred=$preferredPosition itemCount=${adapter.itemCount} menuKey=${adapter.currentMenuKey}")
        recyclerView.post {
            // post 执行时面板可能已被关闭（关闭动画后 GONE），同样不请求焦点
            if (!isShowing()) return@post
            val pos = preferredPosition.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
            val targetView = recyclerView.findViewHolderForAdapterPosition(pos)?.itemView
                ?: recyclerView.layoutManager?.findViewByPosition(pos)
            logSettingFocus("  post: pos=$pos targetView=$targetView isFocusable=${targetView?.isFocusable}")
            if (targetView?.isFocusable == true) {
                targetView.requestFocus()
            } else {
                // Target not laid out yet; scroll to it then retry, instead of falling back to
                // recyclerView.requestFocus() (which lets the system focus the first visible item).
                recyclerView.scrollToPosition(pos)
                recyclerView.post {
                    // retry 执行时面板同样可能已关闭
                    if (!isShowing()) return@post
                    val retryView = recyclerView.findViewHolderForAdapterPosition(pos)?.itemView
                        ?: recyclerView.layoutManager?.findViewByPosition(pos)
                    if (retryView?.isFocusable == true) {
                        retryView.requestFocus()
                    } else {
                        recyclerView.requestFocus()
                    }
                    logSettingFocus("  retry: pos=$pos retryView=$retryView focusAfter=${findFocus()}")
                }
            }
            logSettingFocus("  post: focusAfter=${findFocus()}")
        }
    }

    private fun logSettingFocus(message: String) {
        if (SETTING_FOCUS_LOG_ENABLED) {
            AppLog.d("SettingFocus", message)
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
