package com.mytvb.feature.settings

import com.mytvb.core.common.format.NumberUtils
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.BuildConfig
import com.mytvb.R
import com.mytvb.core.common.media.VideoCodecSupport
import com.mytvb.core.common.update.ApkUpdater
import com.mytvb.databinding.FragmentSettingsBinding
import com.mytvb.model.SettingModel
import com.mytvb.ui.adapter.SettingAdapter
import com.mytvb.ui.adapter.SettingRow
import com.mytvb.ui.adapter.SettingSelectionDialogAdapter
import com.mytvb.core.ui.base.BaseFragment
import com.mytvb.core.ui.base.ScaledTextView
import com.mytvb.core.ui.base.UiCardSize
import com.mytvb.core.ui.base.UiTextScale
import com.mytvb.core.ui.base.UiScale
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.core.ui.decoration.SettingGroupSpacingDecoration
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.cache.FileCacheManager
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.core.ui.system.ScreenUtils
import com.mytvb.feature.player.PlayerInstancePool
import com.mytvb.feature.player.VideoPlayerViewModel
import com.mytvb.feature.player.cache.PlayerMediaCache
import com.mytvb.feature.player.settings.AudioBalanceSettings
import com.mytvb.feature.player.sponsor.SponsorBlockRepository
import com.mytvb.feature.player.view.MyPlayerSettingView
import com.mytvb.core.common.ext.normalizeDanmakuSmartFilterValue
import com.mytvb.core.common.ext.localizedSettingLabel
import com.mytvb.network.cookie.CookieManager
import com.mytvb.ui.activity.MainActivity
import com.mytvb.ui.activity.GaiaVgateActivity
import com.mytvb.ui.widget.NonFocusableScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.Locale
import com.mytvb.core.ui.base.DialogWindowFit

@Suppress("SpellCheckingInspection")
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {

    companion object {
        fun newInstance() = SettingsFragment()

        // 分类 tab（与左侧按钮自上而下一一对应）
        private const val CATEGORY_COMMON = 0
        private const val CATEGORY_DISPLAY = 1
        private const val CATEGORY_PLAY = 2
        private const val CATEGORY_PLAYER_UI = 3
        private const val CATEGORY_DM = 4
        private const val CATEGORY_TEEN = 5
        private const val CATEGORY_ABOUT = 6

        private const val KEY_CACHE_LIMIT = "cache_limit"
        private const val KEY_DEFAULT_START_PAGE = "default_start_page"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_THEME = "theme"
        private const val KEY_LIVE_ENTRY = "live_entry"
        private const val KEY_CCTV_LIVE_ENTRY = "cctv_live_entry"
        private const val KEY_MINOR_PROTECTION = "minor_protection"
        private const val KEY_WATCH_TIME_LIMIT = "teen_watch_limit_min"
        private const val KEY_REST_TIME_LIMIT = "teen_rest_limit_min"
        private const val KEY_PSAS_ENABLED = "teen_psas_enabled"
        private const val KEY_PSAS_INTERVAL = "teen_psas_interval_min"
        private const val KEY_DEFAULT_VIDEO_QUALITY = "default_video_quality"
        private const val KEY_DEFAULT_AUDIO_TRACK = "default_audio_track"
        private const val KEY_DEFAULT_PLAY_SPEED = "default_play_speed"
        private const val KEY_AFTER_PLAY = "after_play"
        private const val KEY_PLAY_FINISH_EXIT_PLAYER = "play_finish_exit_player"
        private const val KEY_VIDEO_CODEC = "video_codec"
        private const val KEY_SUBTITLE_DEFAULT_MODE = "subtitle_default_mode"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_SHOW_VIDEO_DETAIL = "show_video_detail"
        private const val KEY_SHOW_BOTTOM_PROGRESS_BAR = "show_bottom_progress_bar"
        private const val KEY_GIVE_COIN_NUMBER = "give_coin_number"
        private const val KEY_SHOW_NEXT_PREVIOUS = "show_next_previous"
        private const val KEY_SHOW_DM_SWITCH = "show_dm_switch"
        private const val KEY_SHOW_PLAY_SPEED_BUTTON = "show_play_speed_button"
        private const val KEY_SHOW_PLAYBACK_RATE = "show_playback_rate"
        private const val KEY_MUSIC_ZONE_NORMAL_SPEED = "music_zone_normal_speed"
        private const val KEY_DM_SWITCH = "dm_enable"
        private const val KEY_DM_ALPHA = "dm_alpha"
        private const val KEY_DM_TEXT_SIZE = "dm_text_size"
        private const val KEY_DM_SCREEN_AREA = "dm_area"
        private const val KEY_DM_SPEED = "dm_speed"
        private const val KEY_DM_TRACK_SPACING = "dm_track_spacing"
        private const val KEY_DM_ALLOW_TOP = "dm_allow_top"
        private const val KEY_DM_ALLOW_BOTTOM = "dm_allow_bottom"
        private const val KEY_DM_FILTER_WEIGHT = "dm_filter_weight"
        private const val KEY_DM_ALLOW_VIP_COLORFUL_DM = "dm_allow_vip_colorful_dm"
        private const val KEY_DM_MERGE_DUPLICATE = "dm_merge_duplicate"
        private const val KEY_DM_SMART_SHIELD = "dm_smart_shield"
        private const val KEY_GAIA_VGATE_V_VOUCHER = "gaia_vgate_v_voucher"
        private const val KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS = "gaia_vgate_v_voucher_saved_at_ms"
        private const val KEY_IPV4_ONLY = "ipv4_only"
        private const val KEY_DOUYIN_MODE = "douyin_mode"
        private const val KEY_RESUME_PLAYBACK = "resume_playback"
        private const val KEY_SPONSOR_BLOCK_ENABLED = "sponsor_block_enabled"
        private const val KEY_AUDIO_NORMALIZE_LEGACY = "audio_normalize"
        private const val KEY_AUDIO_BALANCE = "audio_balance"
        private val AUDIO_BALANCE_OPTIONS = arrayOf("关", "低", "中", "高")
        private const val KEY_SEAMLESS_QUALITY_SWITCH = "seamless_quality_switch"

        // —— 动作/信息型伪 key（无落盘值，仅作点击分发与条目刷新寻址）——
        private const val KEY_CLEAR_CACHE = "action_clear_cache"
        private const val KEY_UI_LANGUAGE = "ui_language"
        private const val KEY_RISK_CONTROL = "action_risk_control"
        private const val KEY_APP_VERSION = "info_app_version"
        private const val KEY_CHECK_UPDATE = "action_check_update"
        private const val KEY_X5_CORE = "action_x5_core"
        private const val KEY_LOG_RECORD = "action_log_record"
        private const val KEY_DEBUG_LOG = "action_debug_log"
        private const val KEY_DEVICE_MODEL = "info_device_model"
        private const val KEY_SYSTEM_VERSION = "info_system_version"
        private const val KEY_SDK_VERSION = "info_sdk_version"
        private const val KEY_CPU_ABI = "info_cpu_abi"
        private const val KEY_SCREEN_RESOLUTION = "info_screen_resolution"
        private const val KEY_CODEC = "info_codec"

        /**
         * 主题存储值数组（历史落盘格式为中文字面量，toLegacyTheme/toThemeName 依赖）。
         * 不放 arrays.xml：资源数组会随语言目录被翻译，破坏存储格式。
         */
        private val THEME_OPTIONS = arrayOf("黑色", "白色", "经典主题", "粉色", "蓝色", "紫色", "红色")
        private val DM_SMART_FILTER_OPTIONS = arrayOf("关", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

        /**
         * 青少年模式-单次观看时长/休息时长选项：0=不限制，1 分钟为测试用，之后步进 10 分钟，最长 120 分钟。
         * 1 分钟仅供功能自测，正式使用从 10 分钟起。
         */
        private val TEEN_TIME_OPTIONS = arrayOf("0", "1") + (10..120 step 10).map { it.toString() }

        private val HOME_START_PAGE_OPTIONS = arrayOf("推荐", "热门", "番剧", "影视", "动态")

        /** 界面语言选项：tag 空串=跟随系统；语言名固定显示各自语言原文（业界惯例，不随 UI 语言翻译）。 */
        private val UI_LANGUAGE_TAGS = arrayOf("", "zh-CN", "zh-TW", "en")
        private val UI_LANGUAGE_NAMES = arrayOf("简体中文", "繁體中文（台灣）", "English")
    }

    private lateinit var commonGroups: List<SettingGroup>
    private lateinit var displayGroups: List<SettingGroup>
    private lateinit var playerGroups: List<SettingGroup>
    private lateinit var playerUiGroups: List<SettingGroup>
    private lateinit var dmGroups: List<SettingGroup>
    private lateinit var teenGroups: List<SettingGroup>
    private lateinit var aboutGroups: List<SettingGroup>
    private val appSettings: AppSettingsDataStore by inject()
    private val cookieManager: CookieManager by inject()

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private var downloadJob: Job? = null

    private sealed interface UpdateCheckState {
        data object Idle : UpdateCheckState
        data object Checking : UpdateCheckState
        data class Latest(val latestVersion: String) : UpdateCheckState
        data class UpdateAvailable(val latestVersion: String) : UpdateCheckState
        data class Error(val message: String) : UpdateCheckState
    }

    private lateinit var adapter: SettingAdapter
    private var currentCategory = -1
    private var categorySwitchVersion = 0
    private var shouldRequestInitialCategoryFocus = false

    private val gaiaVgateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val gaiaVtoken = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)
            if (!gaiaVtoken.isNullOrBlank()) {
                onGaiaVgateResult(gaiaVtoken)
            }
        }
    }

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSettingsBinding {
        return FragmentSettingsBinding.inflate(inflater, container, false)
    }

    override fun initView() {
        binding.tvTitle.text = getString(R.string.setting)
        binding.buttonBack.setOnClickListener {
            navigateBackFromUi()
        }
        initSettings()
        setupRecyclerView()
        setupCategoryButtons()
    }

    override fun initData() {
        shouldRequestInitialCategoryFocus = true
        showCategory(CATEGORY_COMMON)
        requestInitialCategoryFocus()
    }

    override fun onResume() {
        super.onResume()
        requestInitialCategoryFocus()
        updateRiskControlStatus()
    }

    /** 构造存储型条目：value=稳定存储值，info=本地化显示文案。 */
    private fun stored(key: String, titleRes: Int, defaultStored: String): SettingModel =
        SettingModel(key, getString(titleRes), labelOf(defaultStored), defaultStored)

    /** 构造动作/信息型条目：无存储值，info 直接展示。 */
    private fun plain(key: String, titleRes: Int, info: String = ""): SettingModel =
        SettingModel(key, getString(titleRes), info)

    /** Fragment 内便捷入口：localizedSettingLabel 是 Context 扩展。 */
    private fun labelOf(stored: String): String =
        requireContext().localizedSettingLabel(stored)

    private fun initSettings() {
        // 通用：浏览与互动 / 网络与账号 / 存储
        commonGroups = listOf(
            SettingGroup(R.string.setting_group_browse, listOf(
                stored(KEY_DEFAULT_START_PAGE, R.string.default_start_page, "热门"),
                stored(KEY_LIVE_ENTRY, R.string.live_entry, "关"),
                stored(KEY_CCTV_LIVE_ENTRY, R.string.cctv_live, "关"),
                stored(KEY_SHOW_VIDEO_DETAIL, R.string.show_video_detail_page, "关"),
                stored(KEY_DOUYIN_MODE, R.string.douyin_mode, "关"),
                stored(KEY_GIVE_COIN_NUMBER, R.string.give_coin_number, "2")
            )),
            SettingGroup(R.string.setting_group_network, listOf(
                stored(KEY_IPV4_ONLY, R.string.ipv4_only, "开"),
                plain(KEY_RISK_CONTROL, R.string.risk_control_verify, getString(R.string.risk_status_none))
            )),
            SettingGroup(R.string.setting_group_storage, listOf(
                plain(KEY_CLEAR_CACHE, R.string.clear_cache, "0.0kb"),
                stored(KEY_CACHE_LIMIT, R.string.cache_limit, "200 MB")
            ))
        )

        // 显示：外观 / 缩放与画质
        displayGroups = listOf(
            SettingGroup(R.string.setting_group_appearance, listOf(
                stored(KEY_THEME, R.string.theme, "黑色"),
                plain(KEY_UI_LANGUAGE, R.string.ui_language, currentLanguageDisplay())
            )),
            SettingGroup(R.string.setting_group_scale, listOf(
                stored(UiScale.KEY_UI_SCALE, R.string.ui_scale, "100"),
                stored(UiTextScale.KEY_UI_TEXT_SCALE, R.string.ui_text_size, "标准"),
                stored(UiCardSize.KEY_UI_CARD_SIZE, R.string.ui_card_size, "标准"),
                stored(KEY_IMAGE_QUALITY, R.string.image_quality, "中尺寸")
            ))
        )

        // 播放：默认参数 / 播放行为（同维度相邻：画质↔无缝切换、音质↔音量均衡、倍速↔音乐区倍速）
        playerGroups = listOf(
            SettingGroup(R.string.setting_group_play_defaults, listOf(
                stored(KEY_DEFAULT_VIDEO_QUALITY, R.string.default_video_quality, "1080P"),
                stored(KEY_SEAMLESS_QUALITY_SWITCH, R.string.seamless_quality_switch, "开"),
                stored(KEY_DEFAULT_AUDIO_TRACK, R.string.default_audio_track, "192kbps"),
                stored(KEY_AUDIO_BALANCE, R.string.audio_balance, "中"),
                stored(KEY_DEFAULT_PLAY_SPEED, R.string.default_play_speed, "1.0"),
                stored(KEY_MUSIC_ZONE_NORMAL_SPEED, R.string.music_zone_normal_speed, "关"),
                stored(KEY_VIDEO_CODEC, R.string.video_codec, "HEVC")
            )),
            SettingGroup(R.string.setting_group_play_behavior, listOf(
                stored(KEY_RESUME_PLAYBACK, R.string.resume_playback, "开"),
                stored(KEY_AFTER_PLAY, R.string.after_play, "播推荐视频"),
                stored(KEY_PLAY_FINISH_EXIT_PLAYER, R.string.play_finish_exit_player, "开"),
                stored(KEY_SPONSOR_BLOCK_ENABLED, R.string.sponsor_block, "关")
            ))
        )

        // 播放界面：字幕 / 控制栏显示
        playerUiGroups = listOf(
            SettingGroup(R.string.setting_group_subtitle, listOf(
                stored(KEY_SUBTITLE_DEFAULT_MODE, R.string.show_subtitle_default, "自动字幕"),
                stored(KEY_SUBTITLE_TEXT_SIZE, R.string.subtitle_text_size, "45")
            )),
            SettingGroup(R.string.setting_group_controls, listOf(
                stored(KEY_SHOW_BOTTOM_PROGRESS_BAR, R.string.show_bottom_progress_bar, "关"),
                stored(KEY_SHOW_NEXT_PREVIOUS, R.string.show_next_previous, "关"),
                stored(KEY_SHOW_PLAY_SPEED_BUTTON, R.string.show_play_speed_button, "关"),
                stored(KEY_SHOW_PLAYBACK_RATE, R.string.show_playback_rate, "关")
            ))
        )

        // 弹幕：开关 / 样式 / 显示区域 / 过滤
        dmGroups = listOf(
            SettingGroup(R.string.setting_group_dm_switch, listOf(
                stored(KEY_DM_SWITCH, R.string.dm_switch, "开"),
                stored(KEY_SHOW_DM_SWITCH, R.string.show_dm_switch, "关")
            )),
            SettingGroup(R.string.setting_group_dm_style, listOf(
                stored(KEY_DM_TEXT_SIZE, R.string.dm_text_size, "40"),
                stored(KEY_DM_ALPHA, R.string.dm_alpha, "1.0"),
                stored(KEY_DM_TRACK_SPACING, R.string.dm_track_spacing, "标准"),
                stored(KEY_DM_SPEED, R.string.dm_speed, "4"),
                stored(KEY_DM_ALLOW_VIP_COLORFUL_DM, R.string.allow_vip_colorful_dm, "开")
            )),
            SettingGroup(R.string.setting_group_dm_area, listOf(
                stored(KEY_DM_SCREEN_AREA, R.string.dm_screen_area, "1/2"),
                stored(KEY_DM_ALLOW_TOP, R.string.dm_allow_top, "关"),
                stored(KEY_DM_ALLOW_BOTTOM, R.string.dm_allow_bottom, "关")
            )),
            SettingGroup(R.string.setting_group_dm_filter, listOf(
                stored(KEY_DM_SMART_SHIELD, R.string.dm_smart_shield, "关"),
                stored(KEY_DM_FILTER_WEIGHT, R.string.dm_filter_weight, "关"),
                stored(KEY_DM_MERGE_DUPLICATE, R.string.dm_merge_duplicate, "开")
            ))
        )

        // 青少年模式：时间限制 / 公益广告
        teenGroups = listOf(
            SettingGroup(R.string.setting_group_teen_time, listOf(
                stored(KEY_MINOR_PROTECTION, R.string.minor_protection, "开"),
                plain(KEY_WATCH_TIME_LIMIT, R.string.watch_time_limit, getString(R.string.setting_value_unlimited)),
                plain(KEY_REST_TIME_LIMIT, R.string.rest_time_limit, getString(R.string.setting_value_unlimited))
            )),
            SettingGroup(R.string.setting_group_teen_psas, listOf(
                stored(KEY_PSAS_ENABLED, R.string.psas_enabled, "关"),
                plain(KEY_PSAS_INTERVAL, R.string.psas_interval, getString(R.string.setting_minutes_format, 20))
            ))
        )

        // 关于：应用 / 组件 / 诊断 / 设备信息
        aboutGroups = listOf(
            SettingGroup(R.string.setting_group_about_app, listOf(
                plain(KEY_APP_VERSION, R.string.app_version, BuildConfig.VERSION_NAME),
                plain(KEY_CHECK_UPDATE, R.string.check_update, getString(R.string.update_click_to_check))
            )),
            SettingGroup(R.string.setting_group_components, listOf(
                plain(KEY_X5_CORE, R.string.x5_core_replace, getString(R.string.x5_status_not_installed))
            )),
            SettingGroup(R.string.setting_group_diagnostics, listOf(
                plain(KEY_LOG_RECORD, R.string.log_record, getString(if (AppLog.isEnabled) R.string.on else R.string.off)),
                plain(KEY_DEBUG_LOG, R.string.debug_log)
            )),
            SettingGroup(R.string.device_info, listOf(
                plain(KEY_DEVICE_MODEL, R.string.device_model, Build.MODEL),
                plain(KEY_SYSTEM_VERSION, R.string.system_version, "Android ${Build.VERSION.RELEASE}"),
                plain(KEY_SDK_VERSION, R.string.sdk_version, Build.VERSION.SDK_INT.toString()),
                plain(KEY_CPU_ABI, R.string.cpu_arch, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                plain(KEY_SCREEN_RESOLUTION, R.string.screen_resolution, ScreenUtils.getRealScreenInfo(requireContext()).toString()),
                plain(KEY_CODEC, R.string.hardware_decode, "")
            ))
        )

        restoreSavedSettings()
        updateCacheSizeAsync()
        updateCodecSupportAsync()
    }

    private fun groupsOf(category: Int): List<SettingGroup> = when (category) {
        CATEGORY_COMMON -> commonGroups
        CATEGORY_DISPLAY -> displayGroups
        CATEGORY_PLAY -> playerGroups
        CATEGORY_PLAYER_UI -> playerUiGroups
        CATEGORY_DM -> dmGroups
        CATEGORY_TEEN -> teenGroups
        else -> aboutGroups
    }

    /** 分组数据平铺为列表行：每组先出组头，再按组内位置标记条目的框内拼接 slot。 */
    private fun buildRows(groups: List<SettingGroup>): List<SettingRow> {
        val rows = mutableListOf<SettingRow>()
        groups.forEach { group ->
            rows += SettingRow.Header(getString(group.titleRes))
            group.items.forEachIndexed { index, item ->
                val slot = when {
                    group.items.size == 1 -> SettingAdapter.SLOT_SINGLE
                    index == 0 -> SettingAdapter.SLOT_FIRST
                    index == group.items.lastIndex -> SettingAdapter.SLOT_LAST
                    else -> SettingAdapter.SLOT_MIDDLE
                }
                rows += SettingRow.Item(item, slot)
            }
        }
        return rows
    }

    /** 全部分类中按 key 找条目（key 全局唯一，调换分组/顺序不影响寻址）。 */
    private fun itemOf(key: String): SettingModel? {
        sequenceOf(
            commonGroups, displayGroups, playerGroups, playerUiGroups, dmGroups, teenGroups, aboutGroups
        ).forEach { groups ->
            groups.forEach { group ->
                group.items.forEach { if (it.key == key) return it }
            }
        }
        return null
    }

    /** 当前展示列表中该条目的平铺下标（组头占位），仅当条目属于当前分类时有值。 */
    private fun currentRowIndexOf(key: String): Int {
        adapter.currentList.forEachIndexed { index, row ->
            if (row is SettingRow.Item && row.model.key == key) return index
        }
        return -1
    }

    private fun refreshItem(key: String) {
        // initSettings（恢复落盘值）先于 setupRecyclerView 执行，adapter 未就绪时只改数据不刷 UI
        if (!this::adapter.isInitialized) return
        val index = currentRowIndexOf(key)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
        }
    }

    private fun updateInfo(key: String, info: String) {
        itemOf(key)?.info = info
        refreshItem(key)
    }

    /** 写入存储值并同步刷新本地化显示。 */
    private fun updateStored(key: String, stored: String) {
        itemOf(key)?.let { it.value = stored; it.info = labelOf(stored) }
        refreshItem(key)
    }

    /** 仅赋值（不触发 UI 刷新），恢复落盘值时用。 */
    private fun setStored(key: String, stored: String) {
        itemOf(key)?.let { it.value = stored; it.info = labelOf(stored) }
    }

    private fun applyStored(key: String) {
        appSettings.getCachedString(key)?.let {
            // 字号存储值可能是旧版 30~100 逐档值，归一到当前 30~60 步进 2 档位再显示
            val normalized = if (key == KEY_DM_TEXT_SIZE) {
                MyPlayerSettingView.coerceDmTextSize(it.toIntOrNull() ?: 40).toString()
            } else {
                it
            }
            setStored(key, normalized)
        }
    }

    private fun toggle(
        key: String,
        persist: (String) -> Unit = { appSettings.putStringAsync(key, it) }
    ) {
        val setting = itemOf(key) ?: return
        val newValue = if (setting.value == "开") "关" else "开"
        updateStored(key, newValue)
        persist(newValue)
        Toast.makeText(
            requireContext(),
            getString(R.string.toast_setting_value_format, setting.title, labelOf(newValue)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupRecyclerView() {
        adapter = SettingAdapter { position, item ->
            onSettingItemClick(position, item)
        }
        val layoutManager = createExtraSpaceLayoutManager(
            resources.getDimensionPixelSize(R.dimen.px200)
        )
        binding.recyclerViewSetting.layoutManager = layoutManager
        binding.recyclerViewSetting.adapter = adapter
        binding.recyclerViewSetting.itemAnimator = null
        // 组内条目间距 0 拼接为一框，组间距由组头行上方提供
        binding.recyclerViewSetting.addItemDecoration(
            SettingGroupSpacingDecoration(resources.getDimensionPixelSize(R.dimen.px20)) { position ->
                adapter.currentList.getOrNull(position) is SettingRow.Header
            }
        )
    }

    private fun setupCategoryButtons() {
        binding.buttonSettingCommon.setOnClickListener { showCategory(CATEGORY_COMMON) }
        binding.buttonSettingDisplay.setOnClickListener { showCategory(CATEGORY_DISPLAY) }
        binding.buttonSettingPlay.setOnClickListener { showCategory(CATEGORY_PLAY) }
        binding.buttonSettingPlayerUi.setOnClickListener { showCategory(CATEGORY_PLAYER_UI) }
        binding.buttonSettingDm.setOnClickListener { showCategory(CATEGORY_DM) }
        binding.buttonSettingTeen.setOnClickListener { showCategory(CATEGORY_TEEN) }
        binding.buttonSettingAbout.setOnClickListener { showCategory(CATEGORY_ABOUT) }
    }

    private fun showCategory(category: Int) {
        if (currentCategory == category) {
            return
        }
        val animate = currentCategory != -1
        currentCategory = category
        updateCategorySelection(category)
        showListCategory(groupsOf(category), animate)
    }

    private fun updateCategorySelection(category: Int) {
        binding.buttonSettingCommon.isSelected = category == CATEGORY_COMMON
        binding.buttonSettingDisplay.isSelected = category == CATEGORY_DISPLAY
        binding.buttonSettingPlay.isSelected = category == CATEGORY_PLAY
        binding.buttonSettingPlayerUi.isSelected = category == CATEGORY_PLAYER_UI
        binding.buttonSettingDm.isSelected = category == CATEGORY_DM
        binding.buttonSettingTeen.isSelected = category == CATEGORY_TEEN
        binding.buttonSettingAbout.isSelected = category == CATEGORY_ABOUT
        val buttons = listOf(
            binding.buttonSettingCommon,
            binding.buttonSettingDisplay,
            binding.buttonSettingPlay,
            binding.buttonSettingPlayerUi,
            binding.buttonSettingDm,
            binding.buttonSettingTeen,
            binding.buttonSettingAbout
        )
        buttons.forEach { button ->
            val selected = button.isSelected
            button.animate().cancel()
            button.animate()
                .scaleX(if (selected) 1.02f else 1f)
                .scaleY(if (selected) 1.02f else 1f)
                .setDuration(120L)
                .start()
        }
    }

    private fun onSettingItemClick(position: Int, item: SettingModel) {
        // 青少年分类的任意修改都需先通过魂斗罗秘籍验证，防止孩子关闭保护或调大时长绕过限制。
        if (currentCategory == CATEGORY_TEEN) {
            showMinorProtectionVerifyDialog { dispatchSettingClick(item.key) }
        } else {
            dispatchSettingClick(item.key)
        }
    }

    /** 按 key 统一分发点击（key 全局唯一，与分组/顺序解耦）。 */
    private fun dispatchSettingClick(key: String) {
        when (key) {
            // —— 通用·浏览与互动 ——
            KEY_DEFAULT_START_PAGE -> showCommonChoiceDialog(key, HOME_START_PAGE_OPTIONS)
            KEY_LIVE_ENTRY -> toggle(key) {
                appSettings.putStringAsync(key, it)
                (activity as? MainActivity)?.applyLiveEntryVisibility()
            }
            KEY_CCTV_LIVE_ENTRY -> toggle(key) {
                appSettings.putStringAsync(key, it)
                (activity as? MainActivity)?.applyCctvLiveEntryVisibility()
            }
            KEY_SHOW_VIDEO_DETAIL -> toggle(key)
            KEY_DOUYIN_MODE -> toggle(key)
            KEY_GIVE_COIN_NUMBER -> showCommonChoiceDialog(key, arrayOf("1", "2"))
            // —— 通用·网络与账号 ——
            KEY_IPV4_ONLY -> toggle(key)
            KEY_RISK_CONTROL -> showRiskControlDialog()
            // —— 通用·存储 ——
            KEY_CLEAR_CACHE -> clearCache()
            KEY_CACHE_LIMIT -> showCacheLimitDialog()
            // —— 显示·外观 ——
            KEY_THEME -> showCommonChoiceDialog(key, THEME_OPTIONS)
            KEY_UI_LANGUAGE -> showLanguageChoiceDialog()
            // —— 显示·缩放与画质 ——
            UiScale.KEY_UI_SCALE -> showUiScaleChoiceDialog()
            UiTextScale.KEY_UI_TEXT_SCALE -> showUiTextScaleDialog()
            UiCardSize.KEY_UI_CARD_SIZE -> showCardSizeChoiceDialog()
            KEY_IMAGE_QUALITY -> showCommonChoiceDialog(key, arrayOf("低尺寸", "中尺寸", "高尺寸"))
            // —— 播放·默认参数 ——
            KEY_DEFAULT_VIDEO_QUALITY -> showPlayerChoiceDialog(key, arrayOf("自动", "8K 超高清", "杜比视界", "HDR Vivid", "HDR 真彩", "4K 超高清", "1080P 60帧", "1080P 高码率", "智能修复", "1080P 高清", "720P 60帧", "720P 准高清", "480P 标清", "360P 流畅", "240P 极速"))
            KEY_SEAMLESS_QUALITY_SWITCH -> toggle(key)
            KEY_DEFAULT_AUDIO_TRACK -> showPlayerChoiceDialog(key, arrayOf("192kbps", "132kbps", "64kbps", "杜比全景声", "Hi-Res无损"))
            KEY_AUDIO_BALANCE -> showAudioBalanceChoiceDialog()
            KEY_DEFAULT_PLAY_SPEED -> showPlayerChoiceDialog(key, arrayOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0"))
            KEY_MUSIC_ZONE_NORMAL_SPEED -> toggle(key)
            KEY_VIDEO_CODEC -> showPlayerChoiceDialog(key, arrayOf("AVC", "HEVC", "AV1"))
            // —— 播放·播放行为 ——
            KEY_RESUME_PLAYBACK -> toggle(key)
            KEY_AFTER_PLAY -> showPlayerChoiceDialog(key, arrayOf("什么都不做", "播推荐视频", "播列表中的下一个", "播放合集中的下一个"))
            KEY_PLAY_FINISH_EXIT_PLAYER -> toggle(key)
            KEY_SPONSOR_BLOCK_ENABLED -> toggleSponsorBlock()
            // —— 播放界面·字幕 ——
            KEY_SUBTITLE_DEFAULT_MODE -> showPlayerChoiceDialog(key, arrayOf("关闭字幕", "开启字幕", "自动字幕"))
            KEY_SUBTITLE_TEXT_SIZE -> showPlayerChoiceDialog(key, arrayOf("35", "40", "45", "50", "55", "60"))
            // —— 播放界面·控制栏显示 ——
            KEY_SHOW_BOTTOM_PROGRESS_BAR -> toggle(key)
            KEY_SHOW_NEXT_PREVIOUS -> toggle(key)
            KEY_SHOW_PLAY_SPEED_BUTTON -> toggle(key)
            KEY_SHOW_PLAYBACK_RATE -> toggle(key)
            // —— 弹幕·开关 ——
            KEY_DM_SWITCH -> toggle(key)
            KEY_SHOW_DM_SWITCH -> toggle(key)
            // —— 弹幕·样式 ——
            KEY_DM_TEXT_SIZE -> showDmChoiceDialog(
                key,
                MyPlayerSettingView.DM_TEXT_SIZE_VALUES.map(Int::toString).toTypedArray()
            )
            KEY_DM_ALPHA -> showDmChoiceDialog(key, arrayOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"))
            KEY_DM_TRACK_SPACING -> showDmChoiceDialog(key, arrayOf("紧凑", "标准", "宽松", "特宽"))
            KEY_DM_SPEED -> showDmChoiceDialog(key, arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9"))
            KEY_DM_ALLOW_VIP_COLORFUL_DM -> toggle(key)
            // —— 弹幕·显示区域 ——
            KEY_DM_SCREEN_AREA -> showDmChoiceDialog(key, arrayOf("1/8", "1/6", "1/4", "1/2", "3/4", "全屏"))
            KEY_DM_ALLOW_TOP -> toggle(key)
            KEY_DM_ALLOW_BOTTOM -> toggle(key)
            // —— 弹幕·过滤 ——
            KEY_DM_SMART_SHIELD -> toggle(key)
            KEY_DM_FILTER_WEIGHT -> showDmChoiceDialog(key, DM_SMART_FILTER_OPTIONS)
            KEY_DM_MERGE_DUPLICATE -> toggle(key)
            // —— 青少年·时间限制 ——
            KEY_MINOR_PROTECTION -> toggle(key) {
                appSettings.putStringAsync(key, it)
                (activity as? MainActivity)?.applyCategoryEntryVisibility()
            }
            KEY_WATCH_TIME_LIMIT -> showTeenTimeChoiceDialog(key)
            KEY_REST_TIME_LIMIT -> showTeenTimeChoiceDialog(key)
            // —— 青少年·公益广告 ——
            KEY_PSAS_ENABLED -> toggle(key) {
                appSettings.putStringAsync(key, it)
                com.mytvb.core.common.content.TeenModeTimer.resetForLimitChange()
            }
            KEY_PSAS_INTERVAL -> showPsasIntervalChoiceDialog()
            // —— 关于 ——
            KEY_CHECK_UPDATE -> checkForUpdate()
            KEY_X5_CORE -> startXdDownload()
            KEY_LOG_RECORD -> {
                val newValue = if (AppLog.isEnabled) "关" else "开"
                AppLog.setEnabled(newValue == "开")
                updateInfo(key, labelOf(newValue))
                Toast.makeText(
                    requireContext(),
                    getString(R.string.log_enabled_toast, labelOf(newValue)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            KEY_DEBUG_LOG -> {
                val activity = activity as? MainActivity
                activity?.openOverlayFragment(DebugLogFragment.newInstance(), "debug_log")
            }
        }
    }

    /** 触发 X5 TBS 内核下载安装（复刻 APP 升级同款下载弹窗）。 */
    @android.annotation.SuppressLint("InflateParams")
    private fun startXdDownload() {
        val activity = activity ?: return
        val x5 = com.mytvb.feature.marmot.x5.X5TbsDownloader

        // 已加载或已安装 → 无需重复操作
        if (x5.isX5Loaded(activity) || x5.isInstalled(activity)) {
            Toast.makeText(activity, getString(R.string.x5_installed_toast), Toast.LENGTH_SHORT).show()
            return
        }
        // 正在处理 → 提示等待
        if (x5.isBusy()) {
            Toast.makeText(activity, getString(R.string.processing_toast), Toast.LENGTH_SHORT).show()
            return
        }

        // —— 复刻 APP 升级下载弹窗（AppCompatDialog + ProgressBar + 进度文字 + 取消按钮）——
        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = androidx.appcompat.app.AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
        }
        val titleView = ScaledTextView(requireContext()).apply {
            text = getString(R.string.x5_downloading_title)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px40, px35, px40, px20) }
        }
        root.addView(titleView)
        root.addView(android.view.View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.px2)
            ).apply { setMargins(px18, 0, px18, 0) }
        })
        val progressBar = android.widget.ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.px20)
            ).apply { setMargins(px40, px20, px40, 0) }
        }
        root.addView(progressBar)
        val progressText = ScaledTextView(requireContext()).apply {
            text = getString(R.string.connecting)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px26))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px40, px14, px40, 0) }
        }
        root.addView(progressText)
        val cancelButton = ScaledTextView(requireContext()).apply {
            text = getString(R.string.cancel); setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
            setPadding(resources.getDimensionPixelSize(R.dimen.px16), px14, resources.getDimensionPixelSize(R.dimen.px16), px14)
            isClickable = true; isFocusable = true
            setBackgroundResource(R.drawable.bg_dialog_button)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px18, px20, px18, px18); gravity = android.view.Gravity.END }
        }
        root.addView(cancelButton)
        dialog.setContentView(root)
        dialog.show()
        DialogWindowFit.apply(dialog.window, requireContext(), resources.getDimensionPixelSize(R.dimen.px800))

        updateX5StatusItem(getString(R.string.x5_status_downloading))

        // 启动下载（X5TbsDownloader 回调已切主线程）
        x5.download(activity, object : com.mytvb.feature.marmot.x5.X5TbsDownloader.Callback {
            override fun onProgress(hint: String) {
                if (!isAdded) return
                progressText.text = hint
                // 解析百分比更新进度条
                Regex("(\\d+)%").find(hint)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { p ->
                    progressBar.isIndeterminate = false
                    progressBar.progress = p
                }
                updateX5StatusItem(hint)
            }
            override fun onComplete(success: Boolean, message: String) {
                if (!isAdded) return
                dialog.dismiss()
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                updateX5StatusItem(
                    if (success) getString(R.string.x5_status_installed_restart)
                    else getString(R.string.x5_status_install_failed)
                )
            }
        })

        cancelButton.setOnClickListener {
            dialog.dismiss()
            updateX5StatusItem(getString(R.string.x5_status_cancelled))
        }
    }

    /** 更新 X5 设置项的子标题（显示状态/进度）。 */
    private fun updateX5StatusItem(status: String) {
        updateInfo(KEY_X5_CORE, status)
    }

    /** 恢复 X5 设置项状态（进设置页时刷新）。 */
    private fun updateX5Status() {
        val activity = activity ?: return
        val x5 = com.mytvb.feature.marmot.x5.X5TbsDownloader
        // 已安装 → 直播用 X5（installLocalTbsCore 成功后直接 new X5 WebView，不查 canLoadX5）
        val status = when {
            x5.isInstalled(activity) -> getString(R.string.x5_status_installed_live)
            x5.isDownloaded(activity) -> getString(R.string.x5_status_downloaded)
            else -> getString(R.string.x5_status_not_installed)
        }
        updateX5StatusItem(status)
    }

    /** 青少年时长选项的本地化显示数组：与 TEEN_TIME_OPTIONS 按下标一一对应。 */
    private fun teenTimeDisplayOptions(): Array<String> =
        Array(TEEN_TIME_OPTIONS.size) { index -> formatTeenTimeDisplay(TEEN_TIME_OPTIONS[index]) }

    /** 公益广告间隔选择：与休息计时独立，间隔可任意设置（0=不播）。 */
    private fun showPsasIntervalChoiceDialog() {
        val item = itemOf(KEY_PSAS_INTERVAL) ?: return
        val displayOptions = teenTimeDisplayOptions()
        showChoiceDialog(
            title = item.title,
            currentValue = item.info,
            options = displayOptions
        ) { selected ->
            val index = displayOptions.indexOf(selected).coerceAtLeast(0)
            val rawValue = TEEN_TIME_OPTIONS[index]
            item.value = rawValue
            item.info = selected
            refreshItem(KEY_PSAS_INTERVAL)
            appSettings.putStringAsync(KEY_PSAS_INTERVAL, rawValue)
            com.mytvb.core.common.content.TeenModeTimer.resetForLimitChange()
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_setting_value_format, item.title, selected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showTeenTimeChoiceDialog(key: String) {
        val item = itemOf(key) ?: return
        val displayOptions = teenTimeDisplayOptions()
        showChoiceDialog(
            title = item.title,
            currentValue = item.info,
            options = displayOptions
        ) { selected ->
            // 把显示值映射回数字字符串存储（"不限制" → "0"）
            val index = displayOptions.indexOf(selected).coerceAtLeast(0)
            val rawValue = TEEN_TIME_OPTIONS[index]
            item.value = rawValue
            item.info = selected
            refreshItem(key)
            appSettings.putStringAsync(key, rawValue)
            // 改时长设置：清掉累计观看时长与休息戳，避免脏状态
            com.mytvb.core.common.content.TeenModeTimer.resetForLimitChange()
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_setting_value_format, item.title, selected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 恢复青少年模式时长选项显示（数字 → "不限制"/"X 分钟"）。 */
    private fun restoreTeenTimeLimits() {
        applyTeenTimeDisplay(KEY_WATCH_TIME_LIMIT, appSettings.getCachedString(KEY_WATCH_TIME_LIMIT))
        applyTeenTimeDisplay(KEY_REST_TIME_LIMIT, appSettings.getCachedString(KEY_REST_TIME_LIMIT))
        // 公益广告开关 + 间隔
        applyStored(KEY_PSAS_ENABLED)
        applyTeenTimeDisplay(KEY_PSAS_INTERVAL, appSettings.getCachedString(KEY_PSAS_INTERVAL) ?: "20")
    }

    private fun applyTeenTimeDisplay(key: String, raw: String?) {
        val item = itemOf(key) ?: return
        val stored = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: "0"
        item.value = stored
        item.info = formatTeenTimeDisplay(stored)
    }

    private fun formatTeenTimeDisplay(raw: String?): String {
        val idx = TEEN_TIME_OPTIONS.indexOf(raw?.trim())
        if (idx < 0) return getString(R.string.setting_value_unlimited)
        val value = TEEN_TIME_OPTIONS[idx].toIntOrNull() ?: 0
        return if (value == 0) {
            getString(R.string.setting_value_unlimited)
        } else {
            getString(R.string.setting_minutes_format, value)
        }
    }

    private var cachedReleaseInfo: ApkUpdater.ReleaseInfo? = null

    private fun checkForUpdate() {
        val cooldown = ApkUpdater.cooldownLeftMs()
        if (cooldown > 0) {
            Toast.makeText(requireContext(), getString(R.string.toast_try_later), Toast.LENGTH_SHORT).show()
            return
        }
        ApkUpdater.markStarted()
        updateCheckState.value = UpdateCheckState.Checking
        updateUpdateEntry()

        updateScope.launch {
            try {
                val releaseInfo = ApkUpdater.fetchLatestRelease()
                cachedReleaseInfo = releaseInfo
                if (ApkUpdater.isRemoteNewer(releaseInfo.versionName)) {
                    updateCheckState.value = UpdateCheckState.UpdateAvailable(releaseInfo.versionName)
                    updateUpdateEntry()
                    showUpdateConfirmDialog(releaseInfo)
                } else {
                    updateCheckState.value = UpdateCheckState.Latest(releaseInfo.versionName)
                    updateUpdateEntry()
                }
            } catch (e: Exception) {
                AppLog.e("SettingsFragment", "check update failed", e)
                val msg = e.message ?: getString(R.string.unknown_error)
                updateCheckState.value = UpdateCheckState.Error(msg)
                updateUpdateEntry()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.update_check_failed_format, msg),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateUpdateEntry() {
        if (!isAdded) return
        val info = when (val state = updateCheckState.value) {
            is UpdateCheckState.Idle -> getString(R.string.update_click_to_check)
            is UpdateCheckState.Checking -> getString(R.string.update_checking)
            is UpdateCheckState.Latest -> getString(R.string.update_latest_format, state.latestVersion)
            is UpdateCheckState.UpdateAvailable -> getString(R.string.update_new_version_format, state.latestVersion)
            is UpdateCheckState.Error -> getString(R.string.update_check_failed)
        }
        itemOf(KEY_CHECK_UPDATE)?.info = info
        refreshItem(KEY_CHECK_UPDATE)
    }

    private fun showUpdateConfirmDialog(releaseInfo: ApkUpdater.ReleaseInfo) {
        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val px10 = resources.getDimensionPixelSize(R.dimen.px10)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(ScaledTextView(requireContext()).apply {
            text = getString(R.string.update_found_new)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        })

        root.addView(View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        // 更新日志可能很长：正文钳到 55% 屏高转为滚动，避免整个弹窗高度超屏把按钮顶出屏幕
        val notesScroll = NonFocusableScrollView(requireContext()).apply {
            maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            // 滚动条常驻且加粗（内容不满 maxHeight 不显示，不会误显示），
            // 上下渐隐边缘提示还有未滚到的内容
            persistentScrollBar = true
            // ScrollView 构造默认 focusable，不关掉会抢走弹窗初始焦点，按钮永远落不上
            isFocusable = false
            scrollBarWidth = resources.getDimensionPixelSize(R.dimen.px8)
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(resources.getDimensionPixelSize(R.dimen.px30))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        }
        notesScroll.addView(ScaledTextView(requireContext()).apply {
            val notes = if (releaseInfo.releaseNotes.isNotBlank()) {
                getString(R.string.update_release_notes_format, releaseInfo.releaseNotes)
            } else ""
            text = getString(
                R.string.update_confirm_message_format,
                BuildConfig.VERSION_NAME,
                releaseInfo.versionName,
                notes
            )
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px30))
            setLineSpacing(resources.getDimension(R.dimen.px6), 1f)
        })
        root.addView(notesScroll)

        val actionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            layoutParams = lp
        }

        var cancelButton: ScaledTextView? = null
        listOf(getString(R.string.cancel) to { dialog.dismiss() }, getString(R.string.update_download_action) to {
            dialog.dismiss()
            val apkUrl = cachedReleaseInfo?.apkUrl
            if (apkUrl != null) startDownloadApk(apkUrl)
        }).forEach { (text, action) ->
            val button = ScaledTextView(requireContext()).apply {
                this.text = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
                setPadding(px16, px14, px16, px14)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                setBackgroundResource(R.drawable.bg_dialog_button)
            }
            if (text == getString(R.string.cancel)) cancelButton = button
            actionContainer.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }

        root.addView(actionContainer)
        dialog.setContentView(root)
        // 焦点在按钮上时 DPAD 上/下不会路由进 ScrollView，由弹窗层转发驱动正文滚动；
        // 左右/确认/返回不拦，按钮焦点切换与关闭行为不受影响
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = resources.getDimensionPixelSize(R.dimen.px120)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    notesScroll.smoothScrollBy(0, -step)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    notesScroll.smoothScrollBy(0, step)
                    true
                }
                else -> false
            }
        }
        dialog.show()
        DialogWindowFit.apply(dialog.window, requireContext(), resources.getDimensionPixelSize(R.dimen.px800))
        // TV 弹窗惯例显式落焦点（其余弹窗均有）：默认在「取消」安全侧，防误触直接开下载；
        // 遥控器左右在两按钮间切换，上下滚正文。show 后立即调会被 ViewRootImpl 的
        // 初始焦点分配覆盖，必须等首次布局完成
        root.post {
            cancelButton?.requestFocus()
        }
    }

    private fun startDownloadApk(apkUrl: String) {
        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
        }

        val titleView = ScaledTextView(requireContext()).apply {
            text = getString(R.string.update_downloading_title)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        }
        root.addView(titleView)

        root.addView(View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px20))
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        }
        root.addView(progressBar)

        val progressText = ScaledTextView(requireContext()).apply {
            text = getString(R.string.connecting)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px26))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px14, px40, 0)
            layoutParams = lp
        }
        root.addView(progressText)

        val cancelButton = ScaledTextView(requireContext()).apply {
            text = getString(R.string.cancel)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
            setPadding(resources.getDimensionPixelSize(R.dimen.px16), px14, resources.getDimensionPixelSize(R.dimen.px16), px14)
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_dialog_button)
            setOnClickListener {
                downloadJob?.cancel()
                dialog.dismiss()
                updateCheckState.value = UpdateCheckState.Idle
                updateUpdateEntry()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            lp.gravity = Gravity.END
            layoutParams = lp
        }
        root.addView(cancelButton)

        dialog.setContentView(root)
        dialog.show()
        DialogWindowFit.apply(dialog.window, requireContext(), resources.getDimensionPixelSize(R.dimen.px800))

        downloadJob = updateScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    ApkUpdater.downloadApkToCache(requireContext(), apkUrl) { progress ->
                        view?.post {
                            if (!isAdded) return@post
                            when (progress) {
                                is ApkUpdater.Progress.Connecting -> {
                                    progressText.text = getString(R.string.connecting)
                                    progressBar.isIndeterminate = true
                                }
                                is ApkUpdater.Progress.Downloading -> {
                                    progressBar.isIndeterminate = false
                                    progress.percent?.let { progressBar.progress = it }
                                    progressText.text = progress.hint
                                }
                                is ApkUpdater.Progress.Done -> {}
                                is ApkUpdater.Progress.Retrying -> {
                                    progressText.text = getString(R.string.retrying_format, progress.attempt, progress.maxAttempts)
                                    progressBar.isIndeterminate = true
                                }
                            }
                        }
                    }
                }
                dialog.dismiss()
                ApkUpdater.installApk(requireContext(), apkFile)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                dialog.dismiss()
                AppLog.e("SettingsFragment", "download apk failed", e)
                val hint = when (e) {
                    is java.net.SocketTimeoutException -> getString(R.string.net_timeout_toast)
                    is java.net.UnknownHostException -> getString(R.string.net_unavailable_toast)
                    is java.io.IOException -> getString(R.string.net_error_toast)
                    else -> getString(R.string.download_failed_toast)
                }
                Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
                updateCheckState.value = UpdateCheckState.Idle
                updateUpdateEntry()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        downloadJob?.cancel()
        updateScope.cancel()
    }

    private fun clearCache() {
        try {
            val context = requireContext()
            PlayerMediaCache.clear(context)
            // 关键：播放缓存被 release 后，VideoPlayerViewModel 缓存的 MediaSource
            // 仍攥着已失效的 SimpleCache 引用，player 上挂的旧源同理。
            // 必须同步失效这两处，否则 2 分钟内重播同一视频会走暖路径 prepare()
            // 旧源，触发 SimpleCache.getContentMetadata checkState 崩溃。
            VideoPlayerViewModel.clearCachedPlayback()
            PlayerInstancePool.clearAttachedSource()
            FileCacheManager.clear()
            runCatching { ImageLoader.clearMemory(context) }
            ImageLoader.clearDiskCache(context)
            deleteDir(context.cacheDir)
            context.externalCacheDir?.let { deleteDir(it) }
            itemOf(KEY_CLEAR_CACHE)?.info = NumberUtils.formatBytes(getCurrentCacheSize())
            refreshItem(KEY_CLEAR_CACHE)
            Toast.makeText(requireContext(), getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.e("SettingsFragment", "clearCache failed", e)
        }
    }

    private fun showCacheLimitDialog() {
        val item = itemOf(KEY_CACHE_LIMIT) ?: return
        showStoredChoiceDialog(
            title = item.title,
            currentStored = item.value,
            storedOptions = arrayOf("不限制", "200 MB", "500 MB", "1 GB")
        ) { value ->
            updateStored(KEY_CACHE_LIMIT, value)
            appSettings.putStringAsync(KEY_CACHE_LIMIT, value)
            FileCacheManager.trimToLimit()
            // SimpleCache 创建后上限不可改，必须释放对象让下次播放按新上限重建。
            // 同步失效暖复用快照与挂载源，否则 2 分钟内重播同视频会撞已 release 的 cache。
            PlayerMediaCache.reset(requireContext())
            VideoPlayerViewModel.clearCachedPlayback()
            PlayerInstancePool.clearAttachedSource()
            itemOf(KEY_CLEAR_CACHE)?.info = NumberUtils.formatBytes(getCurrentCacheSize())
            refreshItem(KEY_CLEAR_CACHE)
        }
    }

    private fun updateCacheSizeAsync() {
        updateScope.launch {
            val size = withContext(Dispatchers.IO) { getCurrentCacheSize() }
            if (!isAdded) return@launch
            itemOf(KEY_CLEAR_CACHE)?.info = NumberUtils.formatBytes(size)
            refreshItem(KEY_CLEAR_CACHE)
        }
    }

    private fun updateCodecSupportAsync() {
        updateScope.launch {
            val text = withContext(Dispatchers.Default) { buildCodecSupportText() }
            if (!isAdded) return@launch
            updateInfo(KEY_CODEC, text)
        }
    }

    private fun getFolderSize(folder: java.io.File): Long {
        var size: Long = 0
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isDirectory) {
                    getFolderSize(file)
                } else {
                    file.length()
                }
            }
        }
        return size
    }

    private fun deleteDir(folder: java.io.File) {
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    deleteDir(file)
                } else {
                    file.delete()
                }
            }
        }
    }

    private fun restoreSavedSettings() {
        // —— 通用 ——
        applyStored(KEY_CACHE_LIMIT)
        val defaultStartPage = appSettings.getCachedInt("defaultStartPage", -1)
        if (defaultStartPage >= 0) {
            val stored = HOME_START_PAGE_OPTIONS
                .getOrNull(defaultStartPage)
                ?: HOME_START_PAGE_OPTIONS.first()
            setStored(KEY_DEFAULT_START_PAGE, stored)
        } else {
            applyStored(KEY_DEFAULT_START_PAGE)
        }
        itemOf(KEY_DEFAULT_START_PAGE)?.let { item ->
            if (item.value !in HOME_START_PAGE_OPTIONS) {
                setStored(KEY_DEFAULT_START_PAGE, HOME_START_PAGE_OPTIONS.first())
            }
        }
        applyStored(KEY_LIVE_ENTRY)
        applyStored(KEY_CCTV_LIVE_ENTRY)
        updateRiskControlStatus()
        applyStored(KEY_SHOW_VIDEO_DETAIL)
        applyStored(KEY_DOUYIN_MODE)
        applyStored(KEY_GIVE_COIN_NUMBER)
        applyStored(KEY_IPV4_ONLY)

        // —— 显示 ——
        val theme = appSettings.getCachedInt("theme", 1)
        setStored(KEY_THEME, theme.toThemeName())
        val uiScalePercent = appSettings.getCachedString(UiScale.KEY_UI_SCALE)?.toIntOrNull()
            ?: UiScale.recommendedPercent(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
        itemOf(UiScale.KEY_UI_SCALE)?.let { it.value = uiScalePercent.toString(); it.info = uiScalePercent.toString() }
        val textScaleName = UiTextScale.nameOf(
            appSettings.getCachedString(UiTextScale.KEY_UI_TEXT_SCALE)?.toIntOrNull()
                ?: UiTextScale.DEFAULT_PERCENT
        )
        setStored(UiTextScale.KEY_UI_TEXT_SCALE, textScaleName)
        val cardSizeName = UiCardSize.nameOf(
            appSettings.getCachedString(UiCardSize.KEY_UI_CARD_SIZE)?.toIntOrNull() ?: 0
        )
        setStored(UiCardSize.KEY_UI_CARD_SIZE, cardSizeName)
        applyStored(KEY_IMAGE_QUALITY)

        // —— 青少年模式 ——
        applyStored(KEY_MINOR_PROTECTION)
        restoreTeenTimeLimits()

        // —— 播放：默认参数 ——
        applyStored(KEY_DEFAULT_VIDEO_QUALITY)
        applyStored(KEY_SEAMLESS_QUALITY_SWITCH)
        applyStored(KEY_DEFAULT_AUDIO_TRACK)
        // 音量均衡：新 key（关/低/中/高）优先显示；未设置时旧布尔"开"显示为"中"。
        val audioBalanceStored = audioBalanceStoredValue(
            appSettings.getCachedString(KEY_AUDIO_BALANCE),
            appSettings.getCachedString(KEY_AUDIO_NORMALIZE_LEGACY)
        )
        setStored(KEY_AUDIO_BALANCE, audioBalanceStored)
        applyStored(KEY_DEFAULT_PLAY_SPEED)
        applyStored(KEY_MUSIC_ZONE_NORMAL_SPEED)
        applyStored(KEY_VIDEO_CODEC)
        // —— 播放：播放行为 ——
        applyStored(KEY_RESUME_PLAYBACK)
        applyStored(KEY_AFTER_PLAY)
        applyStored(KEY_PLAY_FINISH_EXIT_PLAYER)
        applyStored(KEY_SPONSOR_BLOCK_ENABLED)

        // —— 播放界面 ——
        // 字幕设置项：读新 key（subtitle_default_mode），未选过时默认显示"自动字幕"
        val subtitleStored = subtitleModeStoredName(appSettings.getCachedString(KEY_SUBTITLE_DEFAULT_MODE))
        setStored(KEY_SUBTITLE_DEFAULT_MODE, subtitleStored)
        applyStored(KEY_SUBTITLE_TEXT_SIZE)
        applyStored(KEY_SHOW_BOTTOM_PROGRESS_BAR)
        applyStored(KEY_SHOW_NEXT_PREVIOUS)
        applyStored(KEY_SHOW_PLAY_SPEED_BUTTON)
        applyStored(KEY_SHOW_PLAYBACK_RATE)

        // —— 弹幕 ——
        applyStored(KEY_DM_SWITCH)
        applyStored(KEY_SHOW_DM_SWITCH)
        applyStored(KEY_DM_TEXT_SIZE)
        applyStored(KEY_DM_ALPHA)
        applyStored(KEY_DM_TRACK_SPACING)
        applyStored(KEY_DM_SPEED)
        applyStored(KEY_DM_ALLOW_VIP_COLORFUL_DM)
        applyStored(KEY_DM_SCREEN_AREA)
        applyStored(KEY_DM_ALLOW_TOP)
        applyStored(KEY_DM_ALLOW_BOTTOM)
        itemOf(KEY_DM_FILTER_WEIGHT)?.let { item ->
            val storedValue = normalizeDanmakuSmartFilterValue(
                appSettings.getCachedString(KEY_DM_FILTER_WEIGHT) ?: item.value
            )
            item.value = storedValue
            item.info = labelOf(storedValue)
        }
        applyStored(KEY_DM_SMART_SHIELD)
        applyStored(KEY_DM_MERGE_DUPLICATE)

        // —— 关于 ——
        updateX5Status()
    }

    /** 音量均衡存储值：新 key 有值直接用；未设置时旧布尔开关"开"归一为"中"，其余为"关"。 */
    private fun audioBalanceStoredValue(value: String?, legacyValue: String?): String {
        if (!value.isNullOrBlank()) return value
        return if (legacyValue?.trim() == "开") "中" else "关"
    }

    /** 字幕三态的存储值归一化：未设置/旧值一律为"自动字幕"（自动为全新默认档）。 */
    private fun subtitleModeStoredName(saved: String?): String = when (saved?.trim()) {
        "开启字幕" -> "开启字幕"
        "关闭字幕" -> "关闭字幕"
        "自动字幕" -> "自动字幕"
        else -> "自动字幕"
    }

    private fun Int.toThemeName(): String {
        return when (this) {
            1 -> "黑色"
            2 -> "白色"
            3 -> "经典主题"
            4 -> "粉色"
            5 -> "蓝色"
            6 -> "紫色"
            7 -> "红色"
            else -> "黑色"
        }
    }

    /** 当前生效的应用语言 tag："" = 跟随系统。 */
    private fun currentAppLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ""
        val locale = locales[0] ?: return ""
        return when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "en" -> "en"
            else -> ""
        }
    }

    private fun currentLanguageDisplay(): String {
        return when (val tag = currentAppLanguageTag()) {
            "" -> getString(R.string.follow_system)
            "zh-TW" -> UI_LANGUAGE_NAMES[1]
            "en" -> UI_LANGUAGE_NAMES[2]
            else -> UI_LANGUAGE_NAMES[0]
        }
    }

    /** 界面语言选择：appcompat 托管持久化并自动重建全部界面，无需手动 recreate / 落盘。 */
    private fun showLanguageChoiceDialog() {
        val item = itemOf(KEY_UI_LANGUAGE) ?: return
        val options = Array(UI_LANGUAGE_TAGS.size) { index ->
            if (index == 0) getString(R.string.follow_system) else UI_LANGUAGE_NAMES[index - 1]
        }
        showChoiceDialog(
            item.title,
            currentLanguageDisplay(),
            options
        ) { selected ->
            val index = options.indexOf(selected).coerceAtLeast(0)
            val tag = UI_LANGUAGE_TAGS[index]
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
            item.info = currentLanguageDisplay()
            refreshItem(KEY_UI_LANGUAGE)
        }
    }

    private fun showCommonChoiceDialog(key: String, options: Array<String>) {
        val item = itemOf(key) ?: return
        showStoredChoiceDialog(item.title, item.value, options) { value ->
            updateStored(key, value)
            appSettings.putStringAsync(key, value)
            when (key) {
                KEY_DEFAULT_START_PAGE -> {
                    appSettings.putIntAsync("defaultStartPage", HOME_START_PAGE_OPTIONS.indexOf(value).coerceAtLeast(0))
                }
                KEY_IMAGE_QUALITY -> {
                    val qualityLevel = when (value) {
                        "低尺寸" -> 0
                        "高尺寸" -> 2
                        else -> 1
                    }
                    appSettings.putIntAsync("imageQualityLevel", qualityLevel)
                    ImageLoader.invalidateImageQualityCache()
                }
                KEY_THEME -> {
                    appSettings.putIntAsync("theme", value.toLegacyTheme())
                    activity?.recreate()
                }
            }
        }
    }

    private fun String.toLegacyTheme(): Int {
        return when (this) {
            "黑色" -> 1
            "白色" -> 2
            "经典主题" -> 3
            "粉色" -> 4
            "蓝色" -> 5
            "紫色" -> 6
            "红色" -> 7
            "自动" -> if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                1
            } else {
                2
            }
            else -> 1
        }
    }

    private fun showPlayerChoiceDialog(key: String, options: Array<String>) {
        val item = itemOf(key) ?: return
        showStoredChoiceDialog(item.title, item.value, options) { value ->
            updateStored(key, value)
            appSettings.putStringAsync(key, value)
        }
    }

    private fun showAudioBalanceChoiceDialog() {
        val item = itemOf(KEY_AUDIO_BALANCE) ?: return
        showStoredChoiceDialog(item.title, item.value, AUDIO_BALANCE_OPTIONS) { value ->
            updateStored(KEY_AUDIO_BALANCE, value)
            appSettings.putStringAsync(KEY_AUDIO_BALANCE, value)
            // 刷新全局档位：正在播放的 player 下一个音频块即生效，无需重建播放器。
            AudioBalanceSettings.applySettingValue(value)
        }
    }

    /** 界面缩放：选完 recreate，全 UI 经 density 通道统一生效（含代码 dp/Toast/Dialog）。 */
    private fun showUiScaleChoiceDialog() {
        val item = itemOf(UiScale.KEY_UI_SCALE) ?: return
        showStoredChoiceDialog(
            item.title,
            item.value,
            UiScale.PERCENTS.map { it.toString() }.toTypedArray()
        ) { value ->
            updateStored(UiScale.KEY_UI_SCALE, value)
            appSettings.putStringAsync(UiScale.KEY_UI_SCALE, value)
            activity?.recreate()
        }
    }

    /** UI 文字大小：档位选择 + 底部实时预览，确认后 recreate 让全界面生效。 */
    private fun showUiTextScaleDialog() {
        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_ui_text_scale)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = itemOf(UiTextScale.KEY_UI_TEXT_SCALE)?.title

        // 预览文字按选中档位精确渲染：豁免 UI 缩放，由这里全权控制字号
        val preview = dialog.findViewById<TextView>(R.id.preview_text)
        ScaledTextView.exempt(preview)

        val percents = UiTextScale.PERCENTS
        val applyPreview: (Int) -> Unit = { index ->
            preview?.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.px32) * percents[index] / 100f
            )
        }

        val savedPercent = appSettings.getCachedString(UiTextScale.KEY_UI_TEXT_SCALE)
            ?.toIntOrNull() ?: UiTextScale.DEFAULT_PERCENT
        val selectedIndex = UiTextScale.indexOf(savedPercent)
        val options = UiTextScale.NAMES.mapIndexed { i, name -> "${labelOf(name)} ${percents[i]}%" }

        val choiceAdapter = SettingSelectionDialogAdapter(
            options = options,
            selectedIndex = selectedIndex,
            onFocused = applyPreview
        ) { index ->
            val percent = percents[index]
            updateInfo(UiTextScale.KEY_UI_TEXT_SCALE, options[index])
            appSettings.putStringAsync(UiTextScale.KEY_UI_TEXT_SCALE, percent.toString())
            activity?.recreate()
            dialog.dismiss()
        }
        val dialogLayoutManager = createExtraSpaceLayoutManager(
            resources.getDimensionPixelSize(R.dimen.px100)
        )
        recyclerView?.layoutManager = dialogLayoutManager
        recyclerView?.adapter = choiceAdapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }
        applyPreview(selectedIndex)

        dialog.setOnShowListener {
            recyclerView?.post {
                choiceAdapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
        DialogWindowFit.apply(
            dialog.window, requireContext(),
            resources.getDimensionPixelSize(R.dimen.px800),
            resources.getDimensionPixelSize(R.dimen.px740)
        )
    }

    /** 视频卡片大小：选完 recreate，所有视频网格经 adaptiveSpanCount 统一生效。 */
    private fun showCardSizeChoiceDialog() {
        val item = itemOf(UiCardSize.KEY_UI_CARD_SIZE) ?: return
        val savedOffset = appSettings.getCachedString(UiCardSize.KEY_UI_CARD_SIZE)
            ?.toIntOrNull() ?: 0
        val displayOptions = UiCardSize.NAMES.map { labelOf(it) }.toTypedArray()
        showChoiceDialog(
            item.title,
            labelOf(UiCardSize.nameOf(savedOffset)),
            displayOptions
        ) { selected ->
            val index = displayOptions.indexOf(selected).coerceAtLeast(0)
            val offset = UiCardSize.offsetAt(index)
            item.value = UiCardSize.NAMES.getOrNull(index) ?: "标准"
            updateInfo(UiCardSize.KEY_UI_CARD_SIZE, selected)
            appSettings.putStringAsync(UiCardSize.KEY_UI_CARD_SIZE, offset.toString())
            activity?.recreate()
        }
    }

    private fun getCurrentCacheSize(): Long {
        // 统计口径与"缓存限制"一致：只算受该设置管辖的两个目录
        // （JSON 数据缓存 + 播放器媒体缓存）。图片/HTTP 缓存与升级包
        // 不归这个设置管，不计入，避免显示值永远超限。
        val mediaCacheDir = PlayerMediaCache.getCacheDir(requireContext())
        return getFolderSize(FileCacheManager.cacheDir) + getFolderSize(mediaCacheDir)
    }

    private fun showDmChoiceDialog(key: String, options: Array<String>) {
        val item = itemOf(key) ?: return
        showStoredChoiceDialog(item.title, item.value, options) { value ->
            updateStored(key, value)
            persistDmSetting(key, value)
        }
    }

    /**
     * 存储值版单选弹窗：入参与回调均为稳定存储值（中文字面量/数字），
     * 弹窗内展示本地化文案，选中后按显示值反查下标回传存储值。
     */
    private fun showStoredChoiceDialog(
        title: String,
        currentStored: String,
        storedOptions: Array<String>,
        onSelected: (String) -> Unit
    ) {
        val displayOptions = storedOptions.map { labelOf(it) }.toTypedArray()
        showChoiceDialog(title, labelOf(currentStored), displayOptions) { selected ->
            val index = displayOptions.indexOf(selected).coerceAtLeast(0)
            onSelected(storedOptions.getOrElse(index) { storedOptions.first() })
        }
    }

    private fun showChoiceDialog(
        title: String,
        currentValue: String,
        options: Array<String>,
        onSelected: (String) -> Unit
    ) {
        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_setting_choice)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = title

        val choiceAdapter = SettingSelectionDialogAdapter(
            options = options.toList(),
            selectedIndex = options.indexOf(currentValue).coerceAtLeast(0)
        ) { selectedIndex ->
            options.getOrNull(selectedIndex)?.let(onSelected)
            dialog.dismiss()
        }

        val dialogLayoutManager = createExtraSpaceLayoutManager(
            resources.getDimensionPixelSize(R.dimen.px100)
        )
        recyclerView?.layoutManager = dialogLayoutManager
        recyclerView?.adapter = choiceAdapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                choiceAdapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
        DialogWindowFit.apply(
            dialog.window, requireContext(),
            resources.getDimensionPixelSize(R.dimen.px800),
            resources.getDimensionPixelSize(R.dimen.px615)
        )
    }

    private fun showListCategory(groups: List<SettingGroup>, animate: Boolean) {
        swapPanels(animate = animate)
        updateSettingsList(buildRows(groups), animate)
    }

    private fun swapPanels(animate: Boolean) {
        val recyclerView = binding.recyclerViewSetting
        if (recyclerView.visibility == View.VISIBLE) {
            return
        }
        recyclerView.animate().cancel()
        if (!animate) {
            recyclerView.visibility = View.VISIBLE
            recyclerView.alpha = 1f
            recyclerView.translationX = 0f
            return
        }
        val offset = resources.getDimension(R.dimen.px20)
        recyclerView.visibility = View.VISIBLE
        recyclerView.alpha = 0f
        recyclerView.translationX = offset
        recyclerView.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(150L)
            .start()
    }

    private fun updateSettingsList(rows: List<SettingRow>, animate: Boolean) {
        val recyclerView = binding.recyclerViewSetting
        val switchVersion = ++categorySwitchVersion
        recyclerView.animate().cancel()
        if (!animate) {
            adapter.setData(rows)
            recyclerView.alpha = 1f
            recyclerView.translationY = 0f
            recyclerView.scrollToPosition(0)
            return
        }
        val offset = resources.getDimension(R.dimen.px8)
        recyclerView.animate()
            .alpha(0.55f)
            .translationY(offset)
            .setDuration(90L)
            .withEndAction {
                if (switchVersion != categorySwitchVersion) {
                    return@withEndAction
                }
                adapter.setData(rows)
                recyclerView.scrollToPosition(0)
                recyclerView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(140L)
                    .start()
            }
            .start()
    }

    private fun persistDmSetting(key: String, value: String) {
        val persistedValue = if (key == KEY_DM_FILTER_WEIGHT) {
            normalizeDanmakuSmartFilterValue(value)
        } else {
            value
        }
        appSettings.putStringAsync(key, persistedValue)
    }

    private fun requestInitialCategoryFocus() {
        if (!shouldRequestInitialCategoryFocus) {
            return
        }
        binding.buttonSettingCommon.post {
            if (!isAdded || !shouldRequestInitialCategoryFocus) {
                return@post
            }
            binding.buttonSettingCommon.requestFocus()
            shouldRequestInitialCategoryFocus = false
        }
    }

    private fun toggleSponsorBlock() {
        val setting = itemOf(KEY_SPONSOR_BLOCK_ENABLED) ?: return
        val newValue = if (setting.value == "开") "关" else "开"
        updateStored(KEY_SPONSOR_BLOCK_ENABLED, newValue)
        appSettings.putStringAsync(KEY_SPONSOR_BLOCK_ENABLED, newValue)
        val title = getString(R.string.sponsor_block)

        if (newValue == "关") {
            Toast.makeText(requireContext(), getString(R.string.toast_setting_value_format, title, labelOf(newValue)), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "${getString(R.string.toast_setting_value_format, title, labelOf(newValue))}，${getString(R.string.sponsor_testing)}", Toast.LENGTH_SHORT).show()
        updateScope.launch {
            val connError = SponsorBlockRepository.testConnection(requireContext())
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (connError != null) {
                    Toast.makeText(requireContext(), connError, Toast.LENGTH_LONG).show()
                    return@withContext
                }
            }
            val fetchResult = SponsorBlockRepository.testFetch(requireContext())
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), fetchResult, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getRiskControlStatus(): String {
        val now = System.currentTimeMillis()
        val tokenCookie = cookieManager.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val voucherOk = !appSettings.getCachedString(KEY_GAIA_VGATE_V_VOUCHER).isNullOrBlank()
        return when {
            tokenOk -> getString(R.string.risk_status_passed)
            voucherOk -> getString(R.string.risk_status_pending)
            else -> getString(R.string.risk_status_none)
        }
    }

    private fun updateRiskControlStatus() {
        updateInfo(KEY_RISK_CONTROL, getRiskControlStatus())
    }

    private fun onGaiaVgateResult(gaiaVtoken: String) {
        val expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        cookieManager.saveCookies(
            listOf(
                "x-bili-gaia-vtoken=$gaiaVtoken; domain=bilibili.com; path=/; secure; expires=$expiresAt"
            )
        )
        appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER, null)
        appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, null)
        updateRiskControlStatus()
        Toast.makeText(requireContext(), getString(R.string.verify_success), Toast.LENGTH_SHORT).show()
    }

    private fun showRiskControlDialog() {
        val now = System.currentTimeMillis()
        val tokenCookie = cookieManager.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val expiresAt = tokenCookie?.expiresAt ?: -1L

        val vVoucher = appSettings.getCachedString(KEY_GAIA_VGATE_V_VOUCHER).orEmpty().trim()
        val hasVoucher = vVoucher.isNotBlank()
        val savedAt = appSettings.getCachedString(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS)?.toLongOrNull() ?: -1L

        val msg = buildString {
            append(getString(R.string.risk_dialog_desc))
            append("\n\n")
            append(getString(R.string.risk_verify_status))
            append(getString(if (tokenOk) R.string.risk_status_passed else R.string.risk_not_verified))
            if (tokenOk && expiresAt > 0L) {
                append("\n")
                append(getString(R.string.risk_expire_time_format, DateFormat.format("yyyy-MM-dd HH:mm", expiresAt)))
            }
            append("\n\n")
            append(getString(R.string.risk_voucher_label))
            append(getString(if (hasVoucher) R.string.risk_voucher_saved else R.string.risk_voucher_none))
            if (hasVoucher && savedAt > 0L) {
                append("\n")
                append(getString(R.string.risk_saved_time_format, DateFormat.format("yyyy-MM-dd HH:mm", savedAt)))
            }
            append("\n\n")
            append(getString(R.string.risk_dialog_tip))
        }

        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val px10 = resources.getDimensionPixelSize(R.dimen.px10)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(ScaledTextView(requireContext()).apply {
            text = getString(R.string.risk_control_verify)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        })

        root.addView(View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        root.addView(ScaledTextView(requireContext()).apply {
            text = msg
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px30))
            setLineSpacing(resources.getDimension(R.dimen.px6), 1f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        })

        val actionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            layoutParams = lp
        }

        val actions = listOf(
            getString(R.string.risk_close),
            getString(R.string.risk_edit_voucher),
            getString(if (hasVoucher) R.string.risk_start_verify else R.string.risk_fill_voucher)
        )

        actions.forEachIndexed { index, actionText ->
            actionContainer.addView(ScaledTextView(requireContext()).apply {
                text = actionText
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
                setPadding(px16, px14, px16, px14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    when (index) {
                        1 -> showGaiaVgateVoucherDialog()
                        2 -> {
                            if (hasVoucher) {
                                gaiaVgateLauncher.launch(
                                    Intent(requireContext(), GaiaVgateActivity::class.java)
                                        .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher)
                                )
                            } else {
                                showGaiaVgateVoucherDialog()
                            }
                        }
                    }
                }
                setBackgroundResource(R.drawable.bg_dialog_button)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }

        root.addView(actionContainer)
        dialog.setContentView(root)
        dialog.show()
        // 固定弹窗宽度（与其他设置弹窗一致）：wrap_content 在窄屏上会把
        // 底部按钮排到没空间，最后一个按钮被压窄导致文字逐字竖排
        DialogWindowFit.apply(dialog.window, requireContext(), resources.getDimensionPixelSize(R.dimen.px800))
        actionContainer.getChildAt(0)?.requestFocus()
    }

    private fun showGaiaVgateVoucherDialog() {
        val initial = appSettings.getCachedString(KEY_GAIA_VGATE_V_VOUCHER).orEmpty()

        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val px10 = resources.getDimensionPixelSize(R.dimen.px10)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
        }

        root.addView(ScaledTextView(requireContext()).apply {
            text = getString(R.string.edit_voucher_title)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        })

        root.addView(View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        val editTextId = View.generateViewId()
        val firstActionId = View.generateViewId()
        val editText = EditText(requireContext()).apply {
            id = editTextId
            hint = getString(R.string.voucher_hint)
            inputType = EditorInfo.TYPE_CLASS_TEXT
            setText(initial)
            setTextColor(textColor)
            setHintTextColor(0x80FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px30))
            setPadding(px16, px16, px16, px16)
            setBackgroundResource(R.drawable.bg_search_input)
            nextFocusDownId = firstActionId
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px150))
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        }
        root.addView(editText)

        val actionContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px18, px20, px18, px18)
            layoutParams = lp
        }

        fun clearVoucher() {
            appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER, null)
            appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, null)
            updateRiskControlStatus()
            Toast.makeText(requireContext(), getString(R.string.voucher_cleared), Toast.LENGTH_SHORT).show()
        }

        fun saveVoucher() {
            val v = editText.text?.toString()?.trim().orEmpty()
            if (v.isNotBlank()) {
                appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER, v)
                appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, System.currentTimeMillis().toString())
                updateRiskControlStatus()
                Toast.makeText(requireContext(), getString(R.string.voucher_saved_toast), Toast.LENGTH_SHORT).show()
            } else {
                clearVoucher()
            }
            dialog.dismiss()
        }

        listOf(getString(R.string.clear) to { clearVoucher(); dialog.dismiss() },
               getString(R.string.cancel) to { dialog.dismiss() },
               getString(R.string.confirm_save) to { saveVoucher() }).forEachIndexed { index, (text, action) ->
            actionContainer.addView(ScaledTextView(requireContext()).apply {
                this.text = text
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px32))
                setPadding(px16, px14, px16, px14)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                setBackgroundResource(R.drawable.bg_dialog_button)
                if (index == 0) {
                    id = firstActionId
                    nextFocusUpId = editTextId
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }

        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                actionContainer.getChildAt(0)?.requestFocus()
                true
            } else {
                false
            }
        }

        root.addView(actionContainer)
        dialog.setContentView(root)
        dialog.setOnShowListener { editText.requestFocus() }
        dialog.show()
        DialogWindowFit.apply(dialog.window, requireContext(), resources.getDimensionPixelSize(R.dimen.px800))
    }

    private fun showMinorProtectionVerifyDialog(onVerified: () -> Unit) {
        val konamiCode = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )
        val inputSequence = mutableListOf<Int>()

        val px40 = resources.getDimensionPixelSize(R.dimen.px40)
        val px35 = resources.getDimensionPixelSize(R.dimen.px35)
        val px20 = resources.getDimensionPixelSize(R.dimen.px20)
        val px18 = resources.getDimensionPixelSize(R.dimen.px18)
        val px16 = resources.getDimensionPixelSize(R.dimen.px16)
        val px14 = resources.getDimensionPixelSize(R.dimen.px14)
        val textColor = resources.getColor(R.color.textColor, null)

        val dialog = AppCompatDialog(requireContext(), R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)

        val codeDisplayView = ScaledTextView(requireContext()).apply {
            text = "? ? ? ? ? ? ? ?"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px40))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(px16, px14, px16, px14)
        }

        var tapCount = 0
        var lastTapTime = 0L

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.dialog_background)
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                defaultFocusHighlightEnabled = false
            }
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime > 3000L) {
                    tapCount = 1
                } else {
                    tapCount++
                }
                lastTapTime = now
                if (tapCount >= 7) {
                    tapCount = 0
                    dialog.dismiss()
                    onVerified()
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            inputSequence.add(keyCode)
                            val count = minOf(inputSequence.size, 8)
                            val display = buildString {
                                repeat(count) { append("* ") }
                                repeat(8 - count) { append("? ") }
                            }.trimEnd()
                            codeDisplayView.text = display
                            if (inputSequence.size >= 8) {
                                val last8 = inputSequence.takeLast(8)
                                if (last8 == konamiCode) {
                                    dialog.dismiss()
                                    onVerified()
                                } else {
                                    inputSequence.clear()
                                    codeDisplayView.text = "? ? ? ? ? ? ? ?"
                                    Toast.makeText(requireContext(), getString(R.string.konami_wrong), Toast.LENGTH_SHORT).show()
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
        }

        root.addView(ScaledTextView(requireContext()).apply {
            text = getString(R.string.minor_protection)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px36))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px35, px40, px20)
            layoutParams = lp
        })

        root.addView(View(requireContext()).apply {
            setBackgroundColor(0x1FFFFFFF)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.px2))
            lp.setMargins(px18, 0, px18, 0)
            layoutParams = lp
        })

        root.addView(ScaledTextView(requireContext()).apply {
            text = getString(R.string.konami_hint)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.px30))
            setLineSpacing(resources.getDimension(R.dimen.px6), 1f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px20, px40, 0)
            layoutParams = lp
        })

        root.addView(codeDisplayView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(px40, px20, px40, 0) })

        dialog.setContentView(root)
        dialog.setOnShowListener { root.post { root.requestFocus() } }
        dialog.show()
    }

    private fun buildCodecSupportText(): String {
        return VideoCodecSupport.buildSupportSummary(
            VideoCodecSupport.getHardwareSupportedCodecs()
        )
    }

    private fun createExtraSpaceLayoutManager(extraLayoutSpacePx: Int): LinearLayoutManager {
        return object : LinearLayoutManager(requireContext()) {
            override fun calculateExtraLayoutSpace(
                state: RecyclerView.State,
                extraLayoutSpace: IntArray
            ) {
                extraLayoutSpace[0] = extraLayoutSpacePx
                extraLayoutSpace[1] = extraLayoutSpacePx
            }

            // 列表首尾按上/下键时焦点留在原地，不回退到全局搜索甩到列表外
            override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
                val position = itemLayoutPosition(focused)
                if (position != RecyclerView.NO_POSITION) {
                    if (direction == View.FOCUS_UP && position == 0) {
                        return focused
                    }
                    if (direction == View.FOCUS_DOWN && position == itemCount - 1) {
                        return focused
                    }
                }
                return super.onInterceptFocusSearch(focused, direction)
            }

            /** 焦点可能在 item 内部的 click_view 上，向上找到 RecyclerView 的直接 child 再取位置。 */
            private fun itemLayoutPosition(focused: View): Int {
                var target: View = focused
                var parent = target.parent
                while (parent is ViewGroup && parent !is RecyclerView) {
                    target = parent
                    parent = target.parent
                }
                if (parent !is RecyclerView) {
                    return RecyclerView.NO_POSITION
                }
                return getPosition(target)
            }
        }
    }

}
