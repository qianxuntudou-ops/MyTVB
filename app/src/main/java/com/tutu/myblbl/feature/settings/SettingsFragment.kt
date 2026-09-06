package com.tutu.myblbl.feature.settings

import com.tutu.myblbl.core.common.format.NumberUtils
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.media.VideoCodecSupport
import com.tutu.myblbl.core.common.update.ApkUpdater
import com.tutu.myblbl.databinding.FragmentSettingsBinding
import com.tutu.myblbl.model.SettingModel
import com.tutu.myblbl.ui.adapter.SettingAdapter
import com.tutu.myblbl.ui.adapter.SettingSelectionDialogAdapter
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.decoration.LinearSpacingItemDecoration
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.cache.FileCacheManager
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.core.ui.image.ImageLoader
import com.tutu.myblbl.core.ui.navigation.navigateBackFromUi
import com.tutu.myblbl.core.ui.system.ScreenUtils
import com.tutu.myblbl.feature.player.PlayerInstancePool
import com.tutu.myblbl.feature.player.VideoPlayerViewModel
import com.tutu.myblbl.feature.player.cache.PlayerMediaCache
import com.tutu.myblbl.feature.player.sponsor.SponsorBlockRepository
import com.tutu.myblbl.core.common.ext.normalizeDanmakuSmartFilterValue
import com.tutu.myblbl.network.cookie.CookieManager
import com.tutu.myblbl.ui.activity.MainActivity
import com.tutu.myblbl.ui.activity.GaiaVgateActivity
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

@Suppress("SpellCheckingInspection")
class SettingsFragment : BaseFragment<FragmentSettingsBinding>() {

    companion object {
        fun newInstance() = SettingsFragment()
        const val CATEGORY_COMMON = 0
        const val CATEGORY_PLAY = 1
        const val CATEGORY_DM = 2
        const val CATEGORY_DEVICE = 3
        const val CATEGORY_TV = 4
        const val CATEGORY_TEEN = 5

        private const val DEVICE_POSITION_VERSION = 0
        private const val DEVICE_POSITION_CHECK_UPDATE = 1
        private const val DEVICE_POSITION_DEVICE_MODEL = 2
        private const val DEVICE_POSITION_SYSTEM_VERSION = 3
        private const val DEVICE_POSITION_SDK_VERSION = 4
        private const val DEVICE_POSITION_CPU_ABI = 5
        private const val DEVICE_POSITION_SCREEN = 6
        private const val DEVICE_POSITION_CODEC = 7

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
        private const val KEY_SHOW_SUBTITLE_DEFAULT = "show_subtitle_default"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_SHOW_DEBUG = "show_debug"
        private const val KEY_SHOW_VIDEO_DETAIL = "show_video_detail"
        private const val KEY_SHOW_BOTTOM_PROGRESS_BAR = "show_bottom_progress_bar"
        private const val KEY_GIVE_COIN_NUMBER = "give_coin_number"
        private const val KEY_SHOW_NEXT_PREVIOUS = "show_next_previous"
        private const val KEY_SHOW_DM_SWITCH = "show_dm_switch"
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
        private const val KEY_AUDIO_NORMALIZE = "audio_normalize"
        private const val KEY_SEAMLESS_QUALITY_SWITCH = "seamless_quality_switch"
        private const val COMMON_POSITION_RISK_CONTROL = 6
        private val DM_SMART_FILTER_OPTIONS = arrayOf("关", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

        /**
         * 青少年模式-单次观看时长/休息时长选项：0=不限制，1 分钟为测试用，之后步进 10 分钟，最长 120 分钟。
         * 1 分钟仅供功能自测，正式使用从 10 分钟起。
         */
        private val TEEN_TIME_OPTIONS = arrayOf("0", "1") + (10..120 step 10).map { it.toString() }
        private val TEEN_TIME_DISPLAY = TEEN_TIME_OPTIONS.copyOf().also { it[0] = "不限制" }

        private val HOME_START_PAGE_OPTIONS = arrayOf("推荐", "热门", "番剧", "影视", "动态")
    }

    private lateinit var commonSettings: MutableList<SettingModel>
    private lateinit var playerSettings: MutableList<SettingModel>
    private lateinit var dmSettings: MutableList<SettingModel>
    private lateinit var teenSettings: MutableList<SettingModel>
    private lateinit var tvSettings: MutableList<SettingModel>
    private val deviceSettings = mutableListOf<SettingModel>()
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

    private fun initSettings() {
        commonSettings = mutableListOf(
            SettingModel(getString(R.string.clear_cache), "0.0kb"),
            SettingModel(getString(R.string.cache_limit), "200 MB"),
            SettingModel(getString(R.string.default_start_page), "热门"),
            SettingModel(getString(R.string.image_quality), "中尺寸"),
            SettingModel(getString(R.string.theme), "黑色"),
            SettingModel(getString(R.string.live_entry), "关"),
            SettingModel(getString(R.string.risk_control_verify), "无"),
            SettingModel(getString(R.string.show_video_detail_page), "关"),
            SettingModel(getString(R.string.give_coin_number), "2"),
            SettingModel(getString(R.string.ipv4_only), "开"),
            SettingModel(getString(R.string.douyin_mode), "关")
        )

        // 青少年模式分类：青少年保护开关 + 单次观看时长 + 休息时长 + 公益广告开关 + 公益广告间隔
        teenSettings = mutableListOf(
            SettingModel(getString(R.string.minor_protection), "开"),
            SettingModel(getString(R.string.watch_time_limit), "不限制"),
            SettingModel(getString(R.string.rest_time_limit), "不限制"),
            SettingModel(getString(R.string.psas_enabled), "关"),
            SettingModel(getString(R.string.psas_interval), "20分钟")
        )

        // 电视直播分类：CCTV 直播开关（从通用设置迁移）+ X5 内核替换
        tvSettings = mutableListOf(
            SettingModel(getString(R.string.cctv_live), "关"),
            SettingModel("X5内核替换", "未安装")
        )

        playerSettings = mutableListOf(
            SettingModel(getString(R.string.default_video_quality), "1080P"),
            SettingModel(getString(R.string.default_audio_track), "192kbps"),
            SettingModel(getString(R.string.default_play_speed), "1.0"),
            SettingModel(getString(R.string.after_play), "播推荐视频"),
            SettingModel(getString(R.string.play_finish_exit_player), "开"),
            SettingModel(getString(R.string.video_codec), "HEVC"),
            SettingModel(getString(R.string.show_subtitle_default), "关"),
            SettingModel(getString(R.string.subtitle_text_size), "45"),
            SettingModel(getString(R.string.show_debug), "关"),
            SettingModel(getString(R.string.show_bottom_progress_bar), "关"),
            SettingModel(getString(R.string.show_next_previous), "关"),
            SettingModel(getString(R.string.resume_playback), "开"),
            SettingModel("空降助手", "关"),
            SettingModel("音量均衡", "关"),
            SettingModel("无缝切换清晰度", "关")
        )

        dmSettings = mutableListOf(
            SettingModel(getString(R.string.dm_switch), "开"),
            SettingModel(getString(R.string.dm_alpha), "1.0"),
            SettingModel(getString(R.string.dm_text_size), "40"),
            SettingModel(getString(R.string.dm_screen_area), "1/2"),
            SettingModel(getString(R.string.dm_speed), "4"),
            SettingModel(getString(R.string.dm_track_spacing), "标准"),
            SettingModel(getString(R.string.dm_allow_top), "关"),
            SettingModel(getString(R.string.dm_allow_bottom), "关"),
            SettingModel(getString(R.string.dm_filter_weight), "关"),
            SettingModel(getString(R.string.allow_vip_colorful_dm), "开"),
            SettingModel(getString(R.string.dm_merge_duplicate), "开"),
            SettingModel(getString(R.string.dm_smart_shield), "关"),
            SettingModel(getString(R.string.show_dm_switch), "关")
        )

        deviceSettings.add(DEVICE_POSITION_VERSION, SettingModel("应用版本", BuildConfig.VERSION_NAME))
        deviceSettings.add(DEVICE_POSITION_CHECK_UPDATE, SettingModel("检查更新", "点击检查"))
        deviceSettings.add(DEVICE_POSITION_DEVICE_MODEL, SettingModel("设备型号", Build.MODEL))
        deviceSettings.add(DEVICE_POSITION_SYSTEM_VERSION, SettingModel("系统版本", "Android ${Build.VERSION.RELEASE}"))
        deviceSettings.add(DEVICE_POSITION_SDK_VERSION, SettingModel("SDK版本", Build.VERSION.SDK_INT.toString()))
        deviceSettings.add(DEVICE_POSITION_CPU_ABI, SettingModel("CPU架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))
        deviceSettings.add(DEVICE_POSITION_SCREEN, SettingModel("屏幕分辨率", ScreenUtils.getRealScreenInfo(requireContext()).toString()))
        deviceSettings.add(DEVICE_POSITION_CODEC, SettingModel("硬解支持", ""))

        commonSettings.add(SettingModel("日志记录", if (AppLog.isEnabled) "开" else "关"))
        commonSettings.add(SettingModel("调试日志", ""))

        restoreSavedSettings()
        updateCacheSizeAsync()
        updateCodecSupportAsync()
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
    }

    private fun setupCategoryButtons() {
        binding.buttonSettingCommon.setOnClickListener { showCategory(CATEGORY_COMMON) }
        binding.buttonSettingPlay.setOnClickListener { showCategory(CATEGORY_PLAY) }
        binding.buttonSettingDm.setOnClickListener { showCategory(CATEGORY_DM) }
        binding.buttonSettingTeen.setOnClickListener { showCategory(CATEGORY_TEEN) }
        binding.buttonSettingDevice.setOnClickListener { showCategory(CATEGORY_DEVICE) }
        binding.buttonSettingTv.setOnClickListener { showCategory(CATEGORY_TV) }
    }

    private fun showCategory(category: Int) {
        if (currentCategory == category) {
            return
        }
        val previousCategory = currentCategory
        val animate = previousCategory != -1
        currentCategory = category
        updateCategorySelection(category)

        when (category) {
            CATEGORY_COMMON -> {
                showListCategory(commonSettings, animate)
            }
            CATEGORY_PLAY -> {
                showListCategory(playerSettings, animate)
            }
            CATEGORY_DM -> {
                showListCategory(dmSettings, animate)
            }
            CATEGORY_TEEN -> {
                showListCategory(teenSettings, animate)
            }
            CATEGORY_DEVICE -> {
                showListCategory(deviceSettings, animate)
            }
            CATEGORY_TV -> {
                showListCategory(tvSettings, animate)
            }
        }
    }

    private fun updateCategorySelection(category: Int) {
        binding.buttonSettingCommon.isSelected = category == CATEGORY_COMMON
        binding.buttonSettingPlay.isSelected = category == CATEGORY_PLAY
        binding.buttonSettingDm.isSelected = category == CATEGORY_DM
        binding.buttonSettingTeen.isSelected = category == CATEGORY_TEEN
        binding.buttonSettingDevice.isSelected = category == CATEGORY_DEVICE
        binding.buttonSettingTv.isSelected = category == CATEGORY_TV
        val buttons = listOf(
            binding.buttonSettingCommon,
            binding.buttonSettingPlay,
            binding.buttonSettingDm,
            binding.buttonSettingTeen,
            binding.buttonSettingDevice,
            binding.buttonSettingTv
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
        when (currentCategory) {
            CATEGORY_COMMON -> handleCommonSettingClick(position, item)
            CATEGORY_PLAY -> handlePlayerSettingClick(position, item)
            CATEGORY_DM -> handleDmSettingClick(position, item)
            CATEGORY_TEEN -> handleTeenSettingClick(position, item)
            CATEGORY_DEVICE -> handleDeviceSettingClick(position)
            CATEGORY_TV -> handleTvSettingClick(position)
        }
    }

    /** 电视直播分类点击处理：CCTV 开关 + X5 内核替换。 */
    private fun handleTvSettingClick(position: Int) {
        when (position) {
            // 0: CCTV 直播开关
            0 -> toggleSetting(tvSettings, 0, KEY_CCTV_LIVE_ENTRY) { value ->
                appSettings.putStringAsync(KEY_CCTV_LIVE_ENTRY, value)
                val activity = activity as? MainActivity
                activity?.applyCctvLiveEntryVisibility()
            }
            // 1: X5 内核替换（华为云下载安装）
            1 -> startXdDownload()
        }
    }

    /** 触发 X5 TBS 内核下载安装（复刻 APP 升级同款下载弹窗）。 */
    @android.annotation.SuppressLint("InflateParams")
    private fun startXdDownload() {
        val activity = activity ?: return
        val x5 = com.tutu.myblbl.feature.marmot.x5.X5TbsDownloader

        // 已加载或已安装 → 无需重复操作
        if (x5.isX5Loaded(activity) || x5.isInstalled(activity)) {
            Toast.makeText(activity, "X5 内核已安装", Toast.LENGTH_SHORT).show()
            return
        }
        // 正在处理 → 提示等待
        if (x5.isBusy()) {
            Toast.makeText(activity, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show()
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
        val titleView = android.widget.TextView(requireContext()).apply {
            text = "正在下载 X5 内核"
            setTextColor(textColor); textSize = 14f
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
        val progressText = android.widget.TextView(requireContext()).apply {
            text = "连接中…"
            setTextColor(textColor); textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(px40, px14, px40, 0) }
        }
        root.addView(progressText)
        val cancelButton = android.widget.TextView(requireContext()).apply {
            text = "取消"; setTextColor(textColor); textSize = 12f
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
        dialog.window?.setLayout(resources.getDimensionPixelSize(R.dimen.px800), ViewGroup.LayoutParams.WRAP_CONTENT)

        updateX5StatusItem("下载中...")

        // 启动下载（X5TbsDownloader 回调已切主线程）
        x5.download(activity, object : com.tutu.myblbl.feature.marmot.x5.X5TbsDownloader.Callback {
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
                updateX5StatusItem(if (success) "✓ 已安装（重启中）" else "安装失败，点击重试")
            }
        })

        cancelButton.setOnClickListener {
            dialog.dismiss()
            updateX5StatusItem("已取消")
        }
    }

    /** 更新 X5 设置项的子标题（显示状态/进度）。 */
    private fun updateX5StatusItem(status: String) {
        tvSettings.getOrNull(1)?.let { item ->
            item.info = status
            if (view != null && currentCategory == CATEGORY_TV) {
                binding.recyclerViewSetting.adapter?.notifyItemChanged(1)
            }
        }
    }

    /** 恢复 X5 设置项状态（进设置页时刷新）。 */
    private fun updateX5Status() {
        val activity = activity ?: return
        val x5 = com.tutu.myblbl.feature.marmot.x5.X5TbsDownloader
        // 已安装 → 直播用 X5（installLocalTbsCore 成功后直接 new X5 WebView，不查 canLoadX5）
        val status = when {
            x5.isInstalled(activity) -> "✓ 已安装（直播用 X5 内核）"
            x5.isDownloaded(activity) -> "已下载，点击安装"
            else -> "未安装"
        }
        updateX5StatusItem(status)
    }

    private fun handleCommonSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> clearCache()
            1 -> showCacheLimitDialog()
            2 -> showCommonChoiceDialog(position, KEY_DEFAULT_START_PAGE, HOME_START_PAGE_OPTIONS)
            3 -> showCommonChoiceDialog(position, KEY_IMAGE_QUALITY, arrayOf("低尺寸", "中尺寸", "高尺寸"))
            4 -> showCommonChoiceDialog(position, KEY_THEME, resources.getStringArray(R.array.themes).drop(1).toTypedArray())
            5 -> toggleSetting(commonSettings, 5, KEY_LIVE_ENTRY) { value ->
                appSettings.putStringAsync(KEY_LIVE_ENTRY, value)
                val activity = activity as? MainActivity
                activity?.applyLiveEntryVisibility()
            }
            6 -> showRiskControlDialog()
            7 -> toggleSetting(commonSettings, 7, KEY_SHOW_VIDEO_DETAIL)
            8 -> showCommonChoiceDialog(position, KEY_GIVE_COIN_NUMBER, arrayOf("1", "2"))
            9 -> toggleSetting(commonSettings, 9, KEY_IPV4_ONLY)
            10 -> toggleSetting(commonSettings, 10, KEY_DOUYIN_MODE)
            commonSettings.lastIndex - 1 -> {
                val newValue = if (AppLog.isEnabled) "关" else "开"
                AppLog.setEnabled(newValue == "开")
                updateSetting(commonSettings, position, newValue)
                Toast.makeText(requireContext(), "日志记录：$newValue", Toast.LENGTH_SHORT).show()
            }
            commonSettings.lastIndex -> {
                if (item.title == "调试日志") {
                    val activity = activity as? MainActivity
                    activity?.openOverlayFragment(DebugLogFragment.newInstance(), "debug_log")
                }
            }
        }
    }

    private fun handlePlayerSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> showPlayerChoiceDialog(position, KEY_DEFAULT_VIDEO_QUALITY, arrayOf("自动", "8K", "杜比视界", "HDR Vivid", "HDR", "4K", "1080P60", "1080P+", "智能修复", "1080P", "720P60", "720P", "480P", "360P", "240P"))
            1 -> showPlayerChoiceDialog(position, KEY_DEFAULT_AUDIO_TRACK, arrayOf("192kbps", "132kbps", "64kbps", "杜比全景声", "Hi-Res无损"))
            2 -> showPlayerChoiceDialog(position, KEY_DEFAULT_PLAY_SPEED, arrayOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "2.0"))
            3 -> showPlayerChoiceDialog(position, KEY_AFTER_PLAY, arrayOf("什么都不做", "播推荐视频", "播列表中的下一个", "播放合集中的下一个"))
            4 -> toggleSetting(playerSettings, 4, KEY_PLAY_FINISH_EXIT_PLAYER)
            5 -> showPlayerChoiceDialog(position, KEY_VIDEO_CODEC, arrayOf("AVC", "HEVC", "AV1"))
            6 -> toggleSetting(playerSettings, 6, KEY_SHOW_SUBTITLE_DEFAULT)
            7 -> showPlayerChoiceDialog(position, KEY_SUBTITLE_TEXT_SIZE, arrayOf("35", "40", "45", "50", "55", "60"))
            8 -> toggleSetting(playerSettings, 8, KEY_SHOW_DEBUG)
            9 -> toggleSetting(playerSettings, 9, KEY_SHOW_BOTTOM_PROGRESS_BAR)
            10 -> toggleSetting(playerSettings, 10, KEY_SHOW_NEXT_PREVIOUS)
            11 -> toggleSetting(playerSettings, 11, KEY_RESUME_PLAYBACK)
            12 -> toggleSponsorBlock()
            13 -> toggleSetting(playerSettings, 13, KEY_AUDIO_NORMALIZE)
            14 -> toggleSetting(playerSettings, 14, KEY_SEAMLESS_QUALITY_SWITCH)
        }
    }

    private fun handleDmSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        when (position) {
            0 -> toggleSetting(dmSettings, 0, KEY_DM_SWITCH) { value ->
                appSettings.putStringAsync(KEY_DM_SWITCH, value)
            }
            1 -> showDmChoiceDialog(position, KEY_DM_ALPHA, arrayOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"))
            2 -> showDmChoiceDialog(position, KEY_DM_TEXT_SIZE, Array(26) { (30 + it).toString() })
            3 -> showDmChoiceDialog(position, KEY_DM_SCREEN_AREA, arrayOf("1/8", "1/6", "1/4", "1/2", "3/4", "全屏"))
            4 -> showDmChoiceDialog(position, KEY_DM_SPEED, arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9"))
            5 -> showDmChoiceDialog(position, KEY_DM_TRACK_SPACING, arrayOf("紧凑", "标准", "宽松", "特宽"))
            6 -> toggleSetting(dmSettings, 6, KEY_DM_ALLOW_TOP) { value ->
                appSettings.putStringAsync(KEY_DM_ALLOW_TOP, value)
            }
            7 -> toggleSetting(dmSettings, 7, KEY_DM_ALLOW_BOTTOM) { value ->
                appSettings.putStringAsync(KEY_DM_ALLOW_BOTTOM, value)
            }
            8 -> showDmChoiceDialog(position, KEY_DM_FILTER_WEIGHT, DM_SMART_FILTER_OPTIONS)
            9 -> toggleSetting(dmSettings, 9, KEY_DM_ALLOW_VIP_COLORFUL_DM) { value ->
                appSettings.putStringAsync(KEY_DM_ALLOW_VIP_COLORFUL_DM, value)
            }
            10 -> toggleSetting(dmSettings, 10, KEY_DM_MERGE_DUPLICATE) { value ->
                appSettings.putStringAsync(KEY_DM_MERGE_DUPLICATE, value)
            }
            11 -> toggleSetting(dmSettings, 11, KEY_DM_SMART_SHIELD) { value ->
                appSettings.putStringAsync(KEY_DM_SMART_SHIELD, value)
            }
            12 -> toggleSetting(dmSettings, 12, KEY_SHOW_DM_SWITCH)
        }
    }

    private fun handleDeviceSettingClick(position: Int) {
        when (position) {
            DEVICE_POSITION_CHECK_UPDATE -> checkForUpdate()
        }
    }

    /**
     * 青少年模式分类点击处理。三个选项任意修改都需先通过魂斗罗秘籍验证，
     * 防止孩子关闭保护或调大时长绕过限制。
     */
    private fun handleTeenSettingClick(position: Int, @Suppress("UNUSED_PARAMETER") item: SettingModel) {
        showMinorProtectionVerifyDialog {
            when (position) {
                // 0: 青少年保护开关（从通用设置迁移）
                0 -> toggleSetting(teenSettings, 0, KEY_MINOR_PROTECTION) { value ->
                    appSettings.putStringAsync(KEY_MINOR_PROTECTION, value)
                    val activity = activity as? MainActivity
                    activity?.applyCategoryEntryVisibility()
                }
                // 1: 单次观看时长（0=不限制，步进10分钟）
                1 -> showTeenTimeChoiceDialog(position, KEY_WATCH_TIME_LIMIT)
                // 2: 休息时长（0=不限制，步进10分钟；为0则整个时间限制关闭）
                2 -> showTeenTimeChoiceDialog(position, KEY_REST_TIME_LIMIT)
                // 3: 公益广告开关
                3 -> toggleSetting(teenSettings, 3, KEY_PSAS_ENABLED) { value ->
                    appSettings.putStringAsync(KEY_PSAS_ENABLED, value)
                    com.tutu.myblbl.core.common.content.TeenModeTimer.resetForLimitChange()
                }
                // 4: 公益广告间隔（步进10分钟，必须 < 观看上限）
                4 -> showPsasIntervalChoiceDialog(position)
            }
        }
    }

    /** 公益广告间隔选择：与休息计时独立，间隔可任意设置（0=不播）。 */
    private fun showPsasIntervalChoiceDialog(position: Int) {
        showChoiceDialog(
            title = teenSettings[position].title,
            currentValue = teenSettings[position].info,
            options = TEEN_TIME_DISPLAY
        ) { selected ->
            val index = TEEN_TIME_DISPLAY.indexOf(selected).coerceAtLeast(0)
            val rawValue = TEEN_TIME_OPTIONS[index]
            updateSetting(teenSettings, position, selected)
            appSettings.putStringAsync(KEY_PSAS_INTERVAL, rawValue)
            com.tutu.myblbl.core.common.content.TeenModeTimer.resetForLimitChange()
            Toast.makeText(requireContext(), "${teenSettings[position].title}：$selected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTeenTimeChoiceDialog(position: Int, key: String) {
        showChoiceDialog(
            title = teenSettings[position].title,
            currentValue = teenSettings[position].info,
            options = TEEN_TIME_DISPLAY
        ) { selected ->
            // 把显示值映射回数字字符串存储（"不限制" → "0"）
            val index = TEEN_TIME_DISPLAY.indexOf(selected).coerceAtLeast(0)
            val rawValue = TEEN_TIME_OPTIONS[index]
            updateSetting(teenSettings, position, selected)
            appSettings.putStringAsync(key, rawValue)
            // 改时长设置：清掉累计观看时长与休息戳，避免脏状态
            com.tutu.myblbl.core.common.content.TeenModeTimer.resetForLimitChange()
            Toast.makeText(
                requireContext(),
                "${teenSettings[position].title}：$selected",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 恢复青少年模式时长选项显示（数字 → "不限制"/"X 分钟"）。 */
    private fun restoreTeenTimeLimits() {
        val watchRaw = appSettings.getCachedString(KEY_WATCH_TIME_LIMIT)
        val restRaw = appSettings.getCachedString(KEY_REST_TIME_LIMIT)
        teenSettings.getOrNull(1)?.info = formatTeenTimeDisplay(watchRaw)
        teenSettings.getOrNull(2)?.info = formatTeenTimeDisplay(restRaw)
        // 公益广告开关 + 间隔
        teenSettings.getOrNull(3)?.info = appSettings.getCachedString(KEY_PSAS_ENABLED) ?: "关"
        teenSettings.getOrNull(4)?.info = formatTeenTimeDisplay(
            appSettings.getCachedString(KEY_PSAS_INTERVAL) ?: "20"
        )
    }

    private fun formatTeenTimeDisplay(raw: String?): String {
        val idx = TEEN_TIME_OPTIONS.indexOf(raw?.trim())
        if (idx < 0) return "不限制"
        val value = TEEN_TIME_OPTIONS[idx].toIntOrNull() ?: 0
        return if (value == 0) "不限制" else "${value}分钟"
    }

    private var cachedReleaseInfo: ApkUpdater.ReleaseInfo? = null

    private fun checkForUpdate() {
        val cooldown = ApkUpdater.cooldownLeftMs()
        if (cooldown > 0) {
            Toast.makeText(requireContext(), "请稍后再试", Toast.LENGTH_SHORT).show()
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
                val msg = e.message ?: "未知错误"
                updateCheckState.value = UpdateCheckState.Error(msg)
                updateUpdateEntry()
                Toast.makeText(requireContext(), "检查失败：$msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUpdateEntry() {
        if (!isAdded) return
        val info = when (val state = updateCheckState.value) {
            is UpdateCheckState.Idle -> "点击检查"
            is UpdateCheckState.Checking -> "检查中…"
            is UpdateCheckState.Latest -> "已是最新版（${state.latestVersion}）"
            is UpdateCheckState.UpdateAvailable -> "新版本 ${state.latestVersion}"
            is UpdateCheckState.Error -> "检查失败"
        }
        deviceSettings.getOrNull(DEVICE_POSITION_CHECK_UPDATE)?.info = info
        if (currentCategory == CATEGORY_DEVICE) {
            adapter.notifyItemChanged(DEVICE_POSITION_CHECK_UPDATE)
        }
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

        root.addView(TextView(requireContext()).apply {
            text = "发现新版本"
            setTextColor(textColor)
            textSize = 14f
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

        root.addView(TextView(requireContext()).apply {
            val notes = if (releaseInfo.releaseNotes.isNotBlank()) {
                getString(R.string.update_release_notes_format, releaseInfo.releaseNotes.take(300))
            } else ""
            text = getString(
                R.string.update_confirm_message_format,
                BuildConfig.VERSION_NAME,
                releaseInfo.versionName,
                notes
            )
            setTextColor(textColor)
            textSize = 12f
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

        listOf("取消" to { dialog.dismiss() }, "下载更新" to {
            dialog.dismiss()
            val apkUrl = cachedReleaseInfo?.apkUrl
            if (apkUrl != null) startDownloadApk(apkUrl)
        }).forEach { (text, action) ->
            actionContainer.addView(TextView(requireContext()).apply {
                this.text = text
                setTextColor(textColor)
                textSize = 12f
                setPadding(px16, px14, px16, px14)
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
                setBackgroundResource(R.drawable.bg_dialog_button)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px10, 0, px10, 0)
            })
        }

        root.addView(actionContainer)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            resources.getDimensionPixelSize(R.dimen.px800),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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

        val titleView = TextView(requireContext()).apply {
            text = "正在下载更新"
            setTextColor(textColor)
            textSize = 14f
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

        val progressText = TextView(requireContext()).apply {
            text = "连接中…"
            setTextColor(textColor)
            textSize = 11f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(px40, px14, px40, 0)
            layoutParams = lp
        }
        root.addView(progressText)

        val cancelButton = TextView(requireContext()).apply {
            text = "取消"
            setTextColor(textColor)
            textSize = 12f
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
        dialog.window?.setLayout(
            resources.getDimensionPixelSize(R.dimen.px800),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        downloadJob = updateScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    ApkUpdater.downloadApkToCache(requireContext(), apkUrl) { progress ->
                        view?.post {
                            if (!isAdded) return@post
                            when (progress) {
                                is ApkUpdater.Progress.Connecting -> {
                                    progressText.text = "连接中…"
                                    progressBar.isIndeterminate = true
                                }
                                is ApkUpdater.Progress.Downloading -> {
                                    progressBar.isIndeterminate = false
                                    progress.percent?.let { progressBar.progress = it }
                                    progressText.text = progress.hint
                                }
                                is ApkUpdater.Progress.Done -> {}
                                is ApkUpdater.Progress.Retrying -> {
                                    progressText.text = "连接失败，正在重试（${progress.attempt}/${progress.maxAttempts}）…"
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
                    is java.net.SocketTimeoutException -> "网络连接超时，请检查网络后重试"
                    is java.net.UnknownHostException -> "网络不可用，请检查网络连接"
                    is java.io.IOException -> "网络异常，请稍后重试"
                    else -> "下载失败，请稍后重试"
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
            commonSettings[0].info = NumberUtils.formatBytes(getCurrentCacheSize())
            adapter.notifyItemChanged(0)
            Toast.makeText(requireContext(), "缓存已清除", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AppLog.e("SettingsFragment", "clearCache failed", e)
        }
    }

    private fun showCacheLimitDialog() {
        showChoiceDialog(
            title = commonSettings[1].title,
            currentValue = commonSettings[1].info,
            options = arrayOf("不限制", "200 MB", "500 MB", "1 GB")
        ) { value ->
            updateSetting(commonSettings, 1, value)
            appSettings.putStringAsync(KEY_CACHE_LIMIT, value)
            FileCacheManager.trimToLimit()
            // SimpleCache 创建后上限不可改，必须释放对象让下次播放按新上限重建。
            // 同步失效暖复用快照与挂载源，否则 2 分钟内重播同视频会撞已 release 的 cache。
            PlayerMediaCache.reset(requireContext())
            VideoPlayerViewModel.clearCachedPlayback()
            PlayerInstancePool.clearAttachedSource()
            commonSettings[0].info = NumberUtils.formatBytes(getCurrentCacheSize())
            adapter.notifyItemChanged(0)
        }
    }

    private fun updateCacheSizeAsync() {
        updateScope.launch {
            val size = withContext(Dispatchers.IO) { getCurrentCacheSize() }
            if (!isAdded) return@launch
            commonSettings[0].info = NumberUtils.formatBytes(size)
            if (currentCategory == CATEGORY_COMMON) {
                adapter.notifyItemChanged(0)
            }
        }
    }

    private fun updateCodecSupportAsync() {
        updateScope.launch {
            val text = withContext(Dispatchers.Default) { buildCodecSupportText() }
            if (!isAdded) return@launch
            deviceSettings.getOrNull(DEVICE_POSITION_CODEC)?.info = text
            if (currentCategory == CATEGORY_DEVICE) {
                adapter.notifyItemChanged(DEVICE_POSITION_CODEC)
            }
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
        applySavedValue(commonSettings, 1, KEY_CACHE_LIMIT)
        val defaultStartPage = appSettings.getCachedInt("defaultStartPage", -1)
        if (defaultStartPage >= 0) {
            commonSettings[2].info = HOME_START_PAGE_OPTIONS
                .getOrNull(defaultStartPage)
                ?: HOME_START_PAGE_OPTIONS.first()
        } else {
            applySavedValue(commonSettings, 2, KEY_DEFAULT_START_PAGE)
        }
        if (commonSettings[2].info !in HOME_START_PAGE_OPTIONS) {
            commonSettings[2].info = HOME_START_PAGE_OPTIONS.first()
        }
        applySavedValue(commonSettings, 3, KEY_IMAGE_QUALITY)
        val theme = appSettings.getCachedInt("theme", 1)
        commonSettings[4].info = theme.toThemeName()
        applySavedValue(commonSettings, 5, KEY_LIVE_ENTRY)
        updateRiskControlStatus()
        applySavedValue(commonSettings, 7, KEY_SHOW_VIDEO_DETAIL)
        applySavedValue(commonSettings, 8, KEY_GIVE_COIN_NUMBER)
        applySavedValue(commonSettings, 9, KEY_IPV4_ONLY)
        applySavedValue(commonSettings, 10, KEY_DOUYIN_MODE)

        // 青少年模式分类：保护开关（从通用设置迁移）+ 观看时长 + 休息时长
        applySavedValue(teenSettings, 0, KEY_MINOR_PROTECTION)
        restoreTeenTimeLimits()

        // 电视直播分类：CCTV 开关（从通用设置迁移过来）
        applySavedValue(tvSettings, 0, KEY_CCTV_LIVE_ENTRY)
        updateX5Status()

        applySavedValue(playerSettings, 0, KEY_DEFAULT_VIDEO_QUALITY)
        applySavedValue(playerSettings, 1, KEY_DEFAULT_AUDIO_TRACK)
        applySavedValue(playerSettings, 2, KEY_DEFAULT_PLAY_SPEED)
        applySavedValue(playerSettings, 3, KEY_AFTER_PLAY)
        applySavedValue(playerSettings, 4, KEY_PLAY_FINISH_EXIT_PLAYER)
        applySavedValue(playerSettings, 5, KEY_VIDEO_CODEC)
        applySavedValue(playerSettings, 6, KEY_SHOW_SUBTITLE_DEFAULT)
        applySavedValue(playerSettings, 7, KEY_SUBTITLE_TEXT_SIZE)
        applySavedValue(playerSettings, 8, KEY_SHOW_DEBUG)
        applySavedValue(playerSettings, 9, KEY_SHOW_BOTTOM_PROGRESS_BAR)
        applySavedValue(playerSettings, 10, KEY_SHOW_NEXT_PREVIOUS)
        applySavedValue(playerSettings, 11, KEY_RESUME_PLAYBACK)
        applySavedValue(playerSettings, 12, KEY_SPONSOR_BLOCK_ENABLED)
        applySavedValue(playerSettings, 13, KEY_AUDIO_NORMALIZE)
        applySavedValue(playerSettings, 14, KEY_SEAMLESS_QUALITY_SWITCH)

        applySavedValue(dmSettings, 0, KEY_DM_SWITCH)
        applySavedValue(dmSettings, 1, KEY_DM_ALPHA)
        applySavedValue(dmSettings, 2, KEY_DM_TEXT_SIZE)
        applySavedValue(dmSettings, 3, KEY_DM_SCREEN_AREA)
        applySavedValue(dmSettings, 4, KEY_DM_SPEED)
        applySavedValue(dmSettings, 5, KEY_DM_TRACK_SPACING)
        applySavedValue(dmSettings, 6, KEY_DM_ALLOW_TOP)
        applySavedValue(dmSettings, 7, KEY_DM_ALLOW_BOTTOM)
        dmSettings[8].info = normalizeDanmakuSmartFilterValue(
            appSettings.getCachedString(KEY_DM_FILTER_WEIGHT) ?: dmSettings[8].info
        )
        applySavedValue(dmSettings, 9, KEY_DM_ALLOW_VIP_COLORFUL_DM)
        applySavedValue(dmSettings, 10, KEY_DM_MERGE_DUPLICATE)
        applySavedValue(dmSettings, 11, KEY_DM_SMART_SHIELD)
        applySavedValue(dmSettings, 12, KEY_SHOW_DM_SWITCH)
    }

    private fun applySavedValue(target: MutableList<SettingModel>, index: Int, key: String) {
        appSettings.getCachedString(key)?.let { saved ->
            target.getOrNull(index)?.info = saved
        }
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

    private fun showCommonChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(commonSettings[position].title, commonSettings[position].info, options) { value ->
            updateSetting(commonSettings, position, value)
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

    private fun showPlayerChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(playerSettings[position].title, playerSettings[position].info, options) { value ->
            updateSetting(playerSettings, position, value)
            appSettings.putStringAsync(key, value)
        }
    }

    private fun getCurrentCacheSize(): Long {
        // 统计口径与"缓存限制"一致：只算受该设置管辖的两个目录
        // （JSON 数据缓存 + 播放器媒体缓存）。图片/HTTP 缓存与升级包
        // 不归这个设置管，不计入，避免显示值永远超限。
        val mediaCacheDir = PlayerMediaCache.getCacheDir(requireContext())
        return getFolderSize(FileCacheManager.cacheDir) + getFolderSize(mediaCacheDir)
    }

    private fun showDmChoiceDialog(position: Int, key: String, options: Array<String>) {
        showChoiceDialog(dmSettings[position].title, dmSettings[position].info, options) { value ->
            updateSetting(dmSettings, position, value)
            persistDmSetting(key, value)
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
    }

    private fun showListCategory(settings: MutableList<SettingModel>, animate: Boolean) {
        swapPanels(animate = animate)
        updateSettingsList(settings, animate)
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

    private fun updateSettingsList(settings: MutableList<SettingModel>, animate: Boolean) {
        val recyclerView = binding.recyclerViewSetting
        val switchVersion = ++categorySwitchVersion
        recyclerView.animate().cancel()
        if (!animate) {
            adapter.setData(settings)
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
                adapter.setData(settings)
                recyclerView.scrollToPosition(0)
                recyclerView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(140L)
                    .start()
            }
            .start()
    }

    private fun updateSetting(target: MutableList<SettingModel>, position: Int, value: String) {
        target.getOrNull(position)?.info = value
        if (isCurrentCategoryList(target)) {
            adapter.notifyItemChanged(position)
        }
    }

    private fun isCurrentCategoryList(target: MutableList<SettingModel>): Boolean {
        return when (currentCategory) {
            CATEGORY_COMMON -> target === commonSettings
            CATEGORY_PLAY -> target === playerSettings
            CATEGORY_DM -> target === dmSettings
            CATEGORY_TEEN -> target === teenSettings
            CATEGORY_TV -> target === tvSettings
            else -> false
        }
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

    private fun toggleSetting(
        target: MutableList<SettingModel>,
        position: Int,
        key: String,
        persist: (String) -> Unit = { appSettings.putStringAsync(key, it) }
    ) {
        val setting = target.getOrNull(position) ?: return
        val newValue = if (setting.info == "开") "关" else "开"
        updateSetting(target, position, newValue)
        persist(newValue)
        Toast.makeText(requireContext(), "${setting.title}：$newValue", Toast.LENGTH_SHORT).show()
    }

    private fun toggleSponsorBlock() {
        val setting = playerSettings.getOrNull(12) ?: return
        val currentValue = setting.info
        val newValue = if (currentValue == "开") "关" else "开"
        updateSetting(playerSettings, 12, newValue)
        appSettings.putStringAsync(KEY_SPONSOR_BLOCK_ENABLED, newValue)

        if (newValue == "关") {
            Toast.makeText(requireContext(), "空降助手：关", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "空降助手：开，正在测试…", Toast.LENGTH_SHORT).show()
        updateScope.launch {
            val connError = SponsorBlockRepository.testConnection()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (connError != null) {
                    Toast.makeText(requireContext(), connError, Toast.LENGTH_LONG).show()
                    return@withContext
                }
            }
            val fetchResult = SponsorBlockRepository.testFetch()
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
            tokenOk -> "已通过"
            voucherOk -> "待验证"
            else -> "无"
        }
    }

    private fun updateRiskControlStatus() {
        val status = getRiskControlStatus()
        commonSettings.getOrNull(COMMON_POSITION_RISK_CONTROL)?.info = status
        if (currentCategory == CATEGORY_COMMON) {
            adapter.notifyItemChanged(COMMON_POSITION_RISK_CONTROL)
        }
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
        Toast.makeText(requireContext(), "验证成功", Toast.LENGTH_SHORT).show()
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
            append("当账号被B站风控时，播放视频会触发人机验证。")
            append("\n\n")
            append("验证状态：")
            append(if (tokenOk) "已通过" else "未验证")
            if (tokenOk && expiresAt > 0L) {
                append("\n")
                append("到期时间：").append(DateFormat.format("yyyy-MM-dd HH:mm", expiresAt))
            }
            append("\n\n")
            append("验证凭证：")
            append(if (hasVoucher) "已保存" else "暂无")
            if (hasVoucher && savedAt > 0L) {
                append("\n")
                append("保存时间：").append(DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
            }
            append("\n\n")
            append("提示：点赞/投币/三连等操作被拦截时，需到B站官方App或网页端完成验证。")
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

        root.addView(TextView(requireContext()).apply {
            text = "风控验证"
            setTextColor(textColor)
            textSize = 14f
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

        root.addView(TextView(requireContext()).apply {
            text = msg
            setTextColor(textColor)
            textSize = 12f
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

        val actions = listOf("关闭", "编辑凭证", if (hasVoucher) "开始验证" else "填写凭证")

        actions.forEachIndexed { index, actionText ->
            actionContainer.addView(TextView(requireContext()).apply {
                text = actionText
                setTextColor(textColor)
                textSize = 12f
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

        root.addView(TextView(requireContext()).apply {
            text = "编辑验证凭证"
            setTextColor(textColor)
            textSize = 14f
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
            hint = "请粘贴验证凭证"
            inputType = EditorInfo.TYPE_CLASS_TEXT
            setText(initial)
            setTextColor(textColor)
            setHintTextColor(0x80FFFFFF.toInt())
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
            Toast.makeText(requireContext(), "凭证已清除", Toast.LENGTH_SHORT).show()
        }

        fun saveVoucher() {
            val v = editText.text?.toString()?.trim().orEmpty()
            if (v.isNotBlank()) {
                appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER, v)
                appSettings.putStringAsync(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, System.currentTimeMillis().toString())
                updateRiskControlStatus()
                Toast.makeText(requireContext(), "凭证已保存", Toast.LENGTH_SHORT).show()
            } else {
                clearVoucher()
            }
            dialog.dismiss()
        }

        listOf("清除" to { clearVoucher(); dialog.dismiss() },
               "取消" to { dialog.dismiss() },
               "保存" to { saveVoucher() }).forEachIndexed { index, (text, action) ->
            actionContainer.addView(TextView(requireContext()).apply {
                this.text = text
                setTextColor(textColor)
                textSize = 12f
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

        val codeDisplayView = TextView(requireContext()).apply {
            text = "? ? ? ? ? ? ? ?"
            setTextColor(textColor)
            textSize = 16f
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
                                    Toast.makeText(requireContext(), "魂斗罗秘籍错误", Toast.LENGTH_SHORT).show()
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

        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.minor_protection)
            setTextColor(textColor)
            textSize = 14f
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

        root.addView(TextView(requireContext()).apply {
            text = "使用遥控器方向键输入魂斗罗秘籍才能关闭！"
            setTextColor(textColor)
            textSize = 12f
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
