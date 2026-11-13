package com.mytvb.feature.hongguo

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.databinding.ActivityHongguoPlayerBinding
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.feature.player.speed.PlaybackSpeedPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果独立播放器（media3）：竖屏剧按比例自适应，UP/DOWN 切集，进度回写红果本地存储。
 * 与 B 站 PlayerActivity 完全隔离；加密流（CENC spade）下载整集解密后播本地缓存。
 *
 * 遥控交互对齐 B 站播放页（MyPlayerView 体系）的轻量实现：
 * - 焦点路由：控制栏显示时方向键在按钮/进度条间导航，UP 显式聚焦进度条（不走 FocusFinder）；
 *   暂停时控制栏常驻且焦点落播放/暂停按钮
 * - 快进：左右键短按累积 ±10s（450ms 无操作合并提交）、长按冻结画面 + tick 连续推进
 *   （步长按片长自适应，恒定 10s 走完全片）+ 松手立即提交；焦点在进度条时同一状态机
 * - 触摸快进（对齐 B 站双路径）：屏幕水平滑动 = 瞬时条+预览浮窗实时预览、松手提交
 *   （滑满一屏 = 100% 时长）；进度条触摸拖动走同一 seek 会话，拖动不冻结画面
 * - 快进预览图：本地解密 MP4 用 MediaMetadataRetriever 取帧，浮窗跟随进度条滑块；
 *   未加密远程直链集降级为纯时间提示
 * - OK 键分流：短按播放/暂停，长按（≥500ms）画质弹窗；控制栏隐藏态短按同时呼出控制栏
 * - 右侧竖版面板（HongguoRightPanelDialog，右缘滑入贴满高度）：画质/选集（纯数字网格）/倍速，
 *   打开即聚焦当前项、关面板焦点回来源按钮、面板期间控制栏不隐藏；倍速会话级（切集保持）
 */
class HongguoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHongguoPlayerBinding
    private val repository by lazy { HongguoRepository.get(this) }
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var seekPreview: HongguoSeekPreviewView? = null
    private var thumbProvider: HongguoThumbProvider? = null

    private var seriesId = ""
    private var title = ""
    private var cover = ""
    private var episodeIndex = 1
    private var episodeCount = 0
    private var resumeMs = 0L
    /** 会话级倍速（起播取设置页默认倍速；切集/切画质后由 startPlayer 重新应用）。 */
    private var speed = 1.0f
    /** 当前打开的右侧面板（画质/选集/倍速）：hideRunnable 守卫与 dismiss 回焦用。 */
    private var rightPanel: HongguoRightPanelDialog? = null
    /** 当前集视频 id（云端进度上传 item_id）。 */
    private var currentVid = ""
    /** 当前集播放信息（画质弹窗数据源：可用档位 + 正播档位）。 */
    private var currentPlay: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl? = null
    /** 换源互斥：画质切换/切集进行中时拦截再入，防止并发起播错乱。 */
    private var switchingSource = false
    /** OK 键按下时刻（长按弹画质弹窗、短按播放暂停）。 */
    private var okDownAt = 0L
    /** 本次 OK 键序列是否被路由接管（false = 焦点按钮自身 click，放行框架分发）。 */
    private var okKeyTaken = false

    private var controlsVisible = false
    private var hideRunnable: Runnable = Runnable { onControlsHideTick() }

    /** 控制栏 4s 超时隐藏：暂停时常驻；右侧面板打开期间续期不隐藏。 */
    private fun onControlsHideTick() {
        // 暂停时控制栏常驻（B 站 shouldShowControllerIndefinitely：!playWhenReady 不自动隐藏）
        if (player?.isPlaying != true) return
        // 右侧面板打开期间控制栏不隐藏，关面板时 bumpControls 重排计时
        if (rightPanel?.isShowing == true) {
            android.os.Handler(mainLooper).postDelayed(hideRunnable, 500L)
            return
        }
        hideControls()
    }
    /** 双击 BACK 退出确认：与 B 站 PlayerActivity 同款（exitTime/exitInterval）。 */
    private var exitTime = 0L
    private val exitInterval = 2000L

    /** 「时间常驻显示」设置（show_always_on_clock）：开=控制栏隐藏期间右上角常驻时钟。 */
    private var alwaysOnClock = false

    // ==================== seek 状态机（对齐 MyPlayerView 双路径合并） ====================
    private val holdStartDelayMs = 400L      // 长按起步延迟（B 站 holdStartDelayMs）
    private val holdTickMs = 40L             // 长按 tick 间隔（B 站 HOLD_SCRUB_TICK_MS）
    private val holdTraverseMs = 10_000L     // 长按恒定时长走完全片（B 站 HOLD_SCRUB_TRAVERSE_MS）
    private val tapCommitDelayMs = 450L      // 短按累积合并提交延迟（B 站 tapCommitDelayMs）
    private val exitSeekUiDelayMs = 400L     // 瞬时条提交后停留时长
    private val okLongPressMs = 500L         // OK 长按画质弹窗阈值

    private var seekUiActive = false
    /** 触发会话时焦点是否在进度条（决定收尾形态：完整控制栏保持 vs 瞬时条退出） */
    private var seekFromTimebar = false
    private var holdStarted = false
    private var seekForward = true
    private var seekAnchorMs = 0L            // 会话起点位置（中央提示 delta 基准）
    private var holdTargetMs = 0L
    private var holdStartMs = 0L             // 0 = 长 tick 尚未跑过首拍（零位移窗口）
    private var tapBaseMs = 0L
    private var tapDeltaMs = 0L
    private var previewPositionMs: Long? = null
    private var lastHintSeconds = Int.MIN_VALUE
    private var holdPlaybackFrozen = false
    private var holdWasPlaying = false
    private var holdPendingRunnable: Runnable? = null
    private var holdTickRunnable: Runnable? = null
    private var tapCommitRunnable: Runnable? = null
    private var exitSeekUiRunnable: Runnable? = null

    private val trackBounds = Rect()
    private val hostLoc = IntArray(2)

    // ==================== 触摸滑动快进（对齐 B 站 handleSwipeSeekTouch） ====================
    // 构造期 base context 尚未 attach，直接 ViewConfiguration.get(this) 会 NPE，须 lazy
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var isSwipeSeeking = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeStartMs = 0L
    private var swipeTargetMs = 0L
    /** 垂直滑动切集会话（与水平 seek 互斥，按主导轴判定）。 */
    private var isSwipeEpisode = false
    private var swipeEpisodeArmed = false
    /** 切集触发阈值：屏高 12%（约 130px）。 */
    private val episodeSwipeThresholdPx by lazy {
        (resources.displayMetrics.heightPixels * 0.12f).toInt().coerceAtLeast(80)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHongguoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        episodeIndex = intent.getIntExtra(EXTRA_EPISODE, 1)
        episodeCount = intent.getIntExtra(EXTRA_EPISODE_COUNT, 0)
        resumeMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L)
        PlayerSettingsStore.load(this).let {
            alwaysOnClock = it.showAlwaysOnClock
            speed = it.defaultPlaybackSpeed
        }
        binding.textTitle.text = "$title · 第${episodeIndex}集"
        binding.playerView.useController = false
        binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        setupSeekPreview()
        setupTimebarScrub()
        setupExit()
        // 进入播放页不显示播放 UI：先同步隐藏顶/底栏（起播也不再自动呼出）
        setControlsVisible(false)
        loadAndPlay()
    }

    /** 快进预览浮窗 + 本地取帧器（加密集有本地解密文件才有图，远程直链集降级纯时间提示）。 */
    private fun setupSeekPreview() {
        val preview = HongguoSeekPreviewView(this)
        // 竖屏剧预览窗：竖版固定尺寸（约画面 9:16），帧未就绪时降级为纯时间提示
        val widthPx = resources.getDimensionPixelSize(R.dimen.px127)
        val heightPx = resources.getDimensionPixelSize(R.dimen.px225)
        binding.root.addView(preview, FrameLayout.LayoutParams(widthPx, heightPx))
        seekPreview = preview
        thumbProvider = HongguoThumbProvider(widthPx) { _, bitmap ->
            preview.setBitmap(bitmap)
        }
    }

    /** 沉浸全屏：独立 Activity 不走 BaseActivity 全屏逻辑，状态栏须自行隐藏。 */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 焦点恢复时系统栏可能被系统重新带出，重新压回去
        if (hasFocus) enterImmersiveFullscreen()
    }

    /** 退出按钮单击直接退出（对齐 B 站 button_back → onClose → finish）；BACK 双击确认。 */
    private fun setupExit() {
        binding.buttonExit.setOnClickListener {
            bumpControls()
            finish()
        }
        // 触屏点击画面 toggle 播放 UI（对齐 B 站点击显隐控制层；按钮自身点击被消费不冒泡）
        binding.root.setOnClickListener {
            if (seekUiActive) {
                cancelSeekSession()
                return@setOnClickListener
            }
            if (controlsVisible) hideControls() else showControls(focusPlayPause = true)
        }
        // 播放/暂停按钮（组件复用 B 站 PlayerControlButton，点击接全局按键同款逻辑）
        binding.buttonPlay.setOnClickListener { togglePlayPause() }
        // 画质/选集/倍速：各开右侧竖版面板
        binding.buttonQuality.setOnClickListener { showQualityDialog() }
        binding.buttonEpisodes.setOnClickListener { showEpisodePanel() }
        binding.buttonSpeed.setOnClickListener { showSpeedPanel() }
        // 顶栏画质文本=画质弹窗入口（触屏；遥控走长按 OK / MENU 键，见 onKeyDown）
        binding.textQuality.setOnClickListener {
            bumpControls()
            showQualityDialog()
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (seekUiActive) {
                    // BACK 取消进行中的快进（B 站 handleBackCancelSeekKeys 同款）
                    cancelSeekSession()
                    return
                }
                if (controlsVisible) {
                    // BACK 优先收播放 UI（对齐 B 站：控制条 → 双击确认退出）
                    hideControls()
                    return
                }
                if (System.currentTimeMillis() - exitTime <= exitInterval) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    AppToast.show(applicationContext, R.string.activity_exit_player_hint)
                }
            }
        })
    }

    /**
     * 播放地址 → 可播 URI：加密流（CENC）整集下载+解密落地，未加密直链原样返回。
     * 实现平移至 Repository（起播/切集/后台预下载共用，含同集在途下载去重）。
     */
    private suspend fun resolvePlayable(play: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl): String =
        repository.resolvePlayableFile(play)

    /** 本集画质面板（右侧竖版）：列实际下发档位（降序），当前档高亮；无档位/仅一档时提示后不弹。 */
    private fun showQualityDialog() {
        val play = currentPlay ?: return
        val options = play.availableQualities
        if (options.isEmpty() || options.size <= 1) {
            AppToast.show(this, getString(R.string.hongguo_quality_single, play.quality.ifEmpty { "唯一" }))
            return
        }
        val labels = options.map { "${it}P" }
        val selectedIndex = options.indexOf(play.pixels).takeIf { it >= 0 } ?: 0
        val panel = HongguoRightPanelDialog.show(
            this, getString(R.string.hongguo_quality_pick), labels, selectedIndex
        ) { index ->
            rightPanel?.dismiss()
            val target = options.getOrNull(index) ?: return@show
            if (target != play.pixels) switchQuality(target)
        }
        registerPanel(panel, binding.buttonQuality)
    }

    /** 选集面板（右侧竖版纯数字网格）：当前集高亮，点击切集（选当前集仅关面板）。 */
    private fun showEpisodePanel() {
        if (episodeCount <= 0) {
            AppToast.show(this, "暂无选集信息")
            return
        }
        val labels = (1..episodeCount).map { it.toString() }
        // 标题带总数：面板一屏约 9 行，标出"共N集"防止误读为只有可见数量
        val panel = HongguoRightPanelDialog.show(
            this, "选集 · 共${episodeCount}集", labels, episodeIndex - 1, spanCount = 6
        ) { index ->
            rightPanel?.dismiss()
            val target = index + 1
            if (target != episodeIndex) switchEpisode(target)
        }
        registerPanel(panel, binding.buttonEpisodes)
    }

    /** 倍速面板（右侧竖版）：档位与 B 站同源（PlaybackSpeedPolicy），当前档高亮；选中即时生效。 */
    private fun showSpeedPanel() {
        val options = PlaybackSpeedPolicy.DEFAULT_PLAYBACK_SPEED_OPTIONS
        val arrayLabels = resources.getStringArray(R.array.exo_controls_playback_speeds)
        val labels = if (arrayLabels.size == options.size) arrayLabels.toList()
        else options.map { if (it == it.toInt().toFloat()) "${it.toInt()}x" else "${it}x" }
        val selectedIndex = options.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
            .takeIf { it >= 0 } ?: options.indexOfFirst { it == 1.0f }.coerceAtLeast(0)
        val panel = HongguoRightPanelDialog.show(
            this, getString(R.string.playSpeed), labels, selectedIndex
        ) { index ->
            rightPanel?.dismiss()
            options.getOrNull(index)?.let { picked ->
                speed = picked
                player?.playbackParameters = PlaybackParameters(speed)
            }
        }
        registerPanel(panel, binding.buttonSpeed)
    }

    /** 面板登记：dismiss 后焦点回来源按钮 + 控制栏重置 4s 计时（面板期间 hideRunnable 守卫不隐藏）。 */
    private fun registerPanel(panel: HongguoRightPanelDialog, sourceButton: View) {
        rightPanel = panel
        panel.setOnDismissListener {
            if (rightPanel === panel) rightPanel = null
            bumpControls()
            binding.root.post { sourceButton.requestFocus() }
        }
    }

    /** 临时切画质：按目标档重新解析地址，从当前进度续播（不影响设置页默认画质）。 */
    private fun switchQuality(pixels: Int) {
        if (switchingSource || currentVid.isEmpty()) return
        switchingSource = true
        val resumePos = player?.currentPosition ?: 0L
        player?.pause()
        lifecycleScope.launch {
            try {
                val play = resolvePlayUrl(currentVid, pixels)
                binding.progressBuffering.visibility = View.VISIBLE
                val playable = resolvePlayable(play)
                binding.progressBuffering.visibility = View.GONE
                currentPlay = play
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                repository.cleanupOtherQualityCaches(play)
                resumeMs = resumePos
                bindThumbs(play)
                startPlayer(playable)
                resumeMs = 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Hongguo", "switch quality failed", e)
                AppToast.show(this@HongguoPlayerActivity, e.message ?: "切换画质失败")
            } finally {
                switchingSource = false
                bumpControls()
            }
        }
    }

    /** 取播放地址（纯 Kotlin 签名+JVM okhttp 传输；WebView 通道已删——实证从未成功）。
     *  默认按设置页「红果短剧·默认画质」就近选档；临时切档时显式传 [preferredPixels]。 */
    private suspend fun resolvePlayUrl(
        vid: String,
        preferredPixels: Int = repository.defaultQualityPixels()
    ): com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl =
        repository.playUrl(vid, preferredPixels)

    /** 本集官方雪碧图（big_thumbs）绑定；未下发/解码失败 = 预览降级纯时间提示。 */
    private fun bindThumbs(play: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl) {
        thumbProvider?.bind(play.bigThumbs)
    }

    /** 起播/切集成功后预取后续集播放地址（官方 preload/v1 批量；地址缓存见 Repository）。 */
    private fun preloadFollowingEpisodes() {
        if (seriesId.isEmpty()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val episodes = repository.detail(seriesId).episodes
                // episodeIndex 为 1-based：下一集 = getOrNull(episodeIndex)
                val vids = listOf(episodeIndex, episodeIndex + 1)
                    .mapNotNull { episodes.getOrNull(it) }
                    .map { it.videoId }
                repository.preloadNextEpisodes(vids)
            }
        }
    }

    private fun loadAndPlay() {
        val vid = intent.getStringExtra(EXTRA_VID) ?: return
        currentVid = vid
        // 登录态心跳（30 分钟阈值内部判定，未登录空操作）
        repository.beatIfNeeded()
        switchingSource = true
        lifecycleScope.launch {
            try {
                android.util.Log.d("Hongguo", "player load vid=$vid ep=$episodeIndex")
                val play = resolvePlayUrl(vid).also { currentPlay = it }
                android.util.Log.d("Hongguo", "player got url codec=${play.codec} q=${play.quality} key=${play.contentKeyHex != null}")
                binding.progressBuffering.visibility = View.VISIBLE
                val playable = resolvePlayable(play)
                binding.progressBuffering.visibility = View.GONE
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                bindThumbs(play)
                startPlayer(playable)
                preloadFollowingEpisodes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Hongguo", "player load failed", e)
                AppToast.show(this@HongguoPlayerActivity, e.message ?: "播放失败")
                finish()
            } finally {
                switchingSource = false
            }
        }
    }

    /** [revealControls]=false（默认）：进入播放/切集/切画质均保持控制栏隐藏，只有用户主动呼出才显示。 */
    private fun startPlayer(url: String, revealControls: Boolean = false) {
        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        binding.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (resumeMs > 0) p.seekTo(resumeMs)
        p.playWhenReady = true
        // 切集/切画质重建 player 后保持会话倍速（B 站换视频速度不重置）
        p.playbackParameters = PlaybackParameters(speed)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.d("Hongguo", "player state=$state")
                binding.progressBuffering.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                updatePauseBigIcon()
                if (state == Player.STATE_READY) saveProgress(p.currentPosition)
                if (state == Player.STATE_ENDED) {
                    saveProgress(durationMs())
                    playNext()
                }
            }

            // 暂停/恢复第一时间联动右下角大图标（isPlaying 要等状态机确认，慢半拍）
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePauseBigIcon()
            }

            // 播放/暂停按钮图标跟随真实状态（对齐 B 站 updatePlayPauseButton）
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.buttonPlay.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.buttonPlay.contentDescription = getString(
                    if (isPlaying) R.string.pause else R.string.play
                )
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("Hongguo", "player error code=0x${Integer.toHexString(error.errorCode)}", error)
                AppToast.show(this@HongguoPlayerActivity, "播放出错：${error.errorCodeName}")
            }
        })
        startPositionWatcher()
        if (revealControls) bumpControls()
    }

    // ==================== 按键路由（对齐 MyPlayerView.dispatchKeyEvent 模型） ====================

    private fun isSeekKeyCode(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (player == null) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event) // BACK 走 OnBackPressedCallback（Android13+ 双通道安全）
        }
        // seek 会话激活：非 seek 键先取消会话（清 UI 不 seek），按键继续正常分发（B 站同款）
        if (seekUiActive && event.action == KeyEvent.ACTION_DOWN && !isSeekKeyCode(keyCode)) {
            cancelSeekSession()
        }
        if (seekUiActive && isSeekKeyCode(keyCode)) {
            return routeSeekKey(event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && routeVerticalKey(keyCode)) return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    okKeyTaken = routeOkKeyDown()
                    if (okKeyTaken) {
                        if (event.repeatCount == 0) okDownAt = SystemClock.uptimeMillis()
                        return true
                    }
                } else if (okKeyTaken) {
                    okKeyTaken = false
                    val heldMs = if (okDownAt > 0) SystemClock.uptimeMillis() - okDownAt else 0L
                    okDownAt = 0L
                    handleOkKeyUp(heldMs)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return routeSeekKey(event)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * UP/DOWN 路由。控制栏可见：UP 显式聚焦进度条（B 站对齐，不走 FocusFinder），
     * 进度条持焦时 UP 收控制栏、DOWN 回播放按钮；控制栏隐藏：UP/DOWN 切集
     * （红果语义，对应 B 站隐藏态 UP 呼选集面板）。
     */
    private fun routeVerticalKey(keyCode: Int): Boolean {
        if (controlsVisible) {
            if (binding.timeBar.hasFocus()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    hideControls()
                } else {
                    binding.buttonPlay.requestFocus()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                binding.timeBar.requestFocus()
                return true
            }
            return false // DOWN：焦点在按钮上走默认导航
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (episodeIndex > 1) switchEpisode(episodeIndex - 1)
        } else if (episodeCount == 0 || episodeIndex < episodeCount) {
            switchEpisode(episodeIndex + 1)
        }
        return true
    }

    /**
     * OK DOWN 路由。焦点在控制栏按钮上：返回 false 放行框架（按钮自身 click）。
     * 控制栏隐藏 / 进度条持焦：接管本次 OK 序列（DOWN 记时刻，UP 分流长短按）。
     */
    private fun routeOkKeyDown(): Boolean {
        if (controlsVisible && !binding.timeBar.hasFocus()) {
            return false // 焦点按钮自身 click（super 分发）
        }
        return true
    }

    /** OK UP 分流：长按画质弹窗；短按隐藏态=呼出控制栏+切换播放暂停，进度条持焦=播放暂停。 */
    private fun handleOkKeyUp(heldMs: Long) {
        if (heldMs >= okLongPressMs) {
            showQualityDialog()
            return
        }
        if (!controlsVisible) {
            showControls(focusPlayPause = true)
            togglePlayPause()
        } else {
            togglePlayPause() // 进度条持焦：OK=播放/暂停（B 站对齐，红果无章节弹窗）
        }
    }

    /** LEFT/RIGHT 路由：会话中/控制栏隐藏/进度条持焦 → seek 状态机；按钮持焦 → 默认导航。 */
    private fun routeSeekKey(event: KeyEvent): Boolean {
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        return if (event.action == KeyEvent.ACTION_DOWN) {
            if (seekUiActive || !controlsVisible || binding.timeBar.hasFocus()) {
                handleSeekKeyDown(forward)
                true
            } else {
                false
            }
        } else {
            if (seekUiActive) {
                handleSeekKeyUp(forward)
                true
            } else {
                false
            }
        }
    }

    // ==================== seek 状态机 ====================

    private fun handleSeekKeyDown(forward: Boolean) {
        cancelExitSeekUi()
        if (!seekUiActive) {
            seekUiActive = true
            seekFromTimebar = controlsVisible && binding.timeBar.hasFocus()
            seekForward = forward
            seekAnchorMs = player?.currentPosition ?: 0L
            enterSeekUi()
            scheduleHoldStart()
        } else if (holdStarted && seekForward != forward) {
            // 长按中换向：停 tick 重排起步延迟（B 站 SeekSession.changeDirection 同款）
            cancelHoldTick()
            seekForward = forward
            holdStartMs = 0L
            scheduleHoldStart()
        }
    }

    private fun handleSeekKeyUp(forward: Boolean) {
        if (!holdStarted || holdStartMs == 0L) {
            // 短按（含 400ms 已过但首个 40ms tick 未跑的零位移窗口，B 站同款回退短按）：
            // 累积 ±10s，450ms 无操作合并提交（连按一次跳 N×10s）
            cancelPendingHoldStart()
            cancelHoldTick()
            accumulateTap(forward)
        } else {
            // 长按松手：立即停 tick 并提交（B 站 finishHoldSeekNow：延迟提交会凭空多跳）
            commitHoldSeek()
        }
    }

    /** 起会话时若控制栏隐藏 → 瞬时条模式（只留进度条+时间，标题/按钮 INVISIBLE 不塌缩）。 */
    private fun enterSeekUi() {
        if (!seekFromTimebar) {
            binding.panelTop.visibility = View.GONE
            binding.textTitle.visibility = View.INVISIBLE
            binding.buttonPlay.visibility = View.INVISIBLE
            binding.panelBottom.visibility = View.VISIBLE
            binding.timeBar.requestFocus()
        }
        updateAlwaysClock()
    }

    private fun scheduleHoldStart() {
        cancelPendingHoldStart()
        val r = Runnable {
            holdPendingRunnable = null
            if (!seekUiActive) return@Runnable
            holdStarted = true
            beginHoldFreeze()
            startHoldTick()
        }
        holdPendingRunnable = r
        binding.root.postDelayed(r, holdStartDelayMs)
    }

    private fun startHoldTick() {
        cancelHoldTick()
        val r = object : Runnable {
            override fun run() {
                if (!holdStarted || !seekUiActive) return
                doHoldTick()
                binding.root.postDelayed(this, holdTickMs)
            }
        }
        holdTickRunnable = r
        binding.root.postDelayed(r, holdTickMs)
    }

    /** 长按 tick：步长按片长自适应（duration×tick/traverse，恒定 traverse 走完全片）。 */
    private fun doHoldTick() {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        if (dur <= 0L) return
        if (holdStartMs == 0L) {
            holdTargetMs = p.currentPosition
            holdStartMs = SystemClock.uptimeMillis()
        }
        val dir = if (seekForward) 1L else -1L
        val step = kotlin.math.round(dur.toDouble() * holdTickMs / holdTraverseMs)
            .toLong().coerceAtLeast(1L) * dir
        holdTargetMs = (holdTargetMs + step).coerceIn(0L, seekUpperBoundMs(dur))
        renderSeekPreview(holdTargetMs, dur)
    }

    /** 短按累积 ±10s：统一进度条预览 + 浮窗 + 中央提示，450ms 无操作提交。 */
    private fun accumulateTap(forward: Boolean) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        if (dur <= 0L) return
        cancelTapCommit()
        if (tapDeltaMs == 0L) tapBaseMs = p.currentPosition
        tapDeltaMs += 10_000L * if (forward) 1L else -1L
        val target = (tapBaseMs + tapDeltaMs).coerceIn(0L, seekUpperBoundMs(dur))
        renderSeekPreview(target, dur)
        val r = Runnable {
            tapCommitRunnable = null
            val pp = player ?: return@Runnable
            // duration 暂不可用（切集/换流窗口期）时放弃本次提交，防 seekTo(0) 凭空跳回片头
            if (pp.duration > 0L) {
                pp.seekTo((tapBaseMs + tapDeltaMs).coerceIn(0L, seekUpperBoundMs(pp.duration)))
            }
            finishSeekUi()
        }
        tapCommitRunnable = r
        binding.root.postDelayed(r, tapCommitDelayMs)
    }

    private fun commitHoldSeek() {
        cancelHoldTick()
        cancelPendingHoldStart()
        val p = player ?: return
        endHoldFreeze()
        if (holdStartMs != 0L) p.seekTo(holdTargetMs)
        finishSeekUi()
    }

    /** 其它键（OK/UP/DOWN）打断 seek：清 UI 不 seek，解冻恢复播放（B 站同款）。 */
    private fun cancelSeekSession() {
        cancelPendingHoldStart()
        cancelHoldTick()
        cancelTapCommit()
        cancelExitSeekUi()
        endHoldFreeze()
        previewPositionMs = null
        seekUiActive = false
        holdStarted = false
        holdStartMs = 0L
        tapBaseMs = 0L
        tapDeltaMs = 0L
        val wasInstantBar = !seekFromTimebar // 瞬时条会话（控制栏隐藏态按键/触摸滑动）才立即收
        seekFromTimebar = false
        seekPreview?.hideNow()
        hideSeekHint()
        if (wasInstantBar && binding.panelBottom.visibility == View.VISIBLE) {
            exitSeekUi() // 瞬时条立即收（B 站取消路径 exitSeekProgressOnly 同款）
        }
        updateAlwaysClock()
    }

    /** 短按/长按正常提交后的收尾：预览淡出、状态复位、按触发路径决定瞬时条去留。 */
    private fun finishSeekUi() {
        previewPositionMs = null
        seekPreview?.hideAfter(500L)
        hideSeekHint()
        seekUiActive = false
        holdStarted = false
        holdStartMs = 0L
        tapBaseMs = 0L
        tapDeltaMs = 0L
        if (seekFromTimebar) {
            // 焦点在进度条路径：完整控制栏保持，进度条回真实位置（progressJob 恢复驱动）
            if (controlsVisible) binding.timeBar.requestFocus()
        } else {
            // 瞬时条路径：进度条停留片刻后整体退出回隐藏态（B 站 finishSeekPreviewProgressOnly）
            val r = Runnable {
                exitSeekUiRunnable = null
                exitSeekUi()
            }
            exitSeekUiRunnable = r
            binding.root.postDelayed(r, exitSeekUiDelayMs)
        }
        seekFromTimebar = false
        updateAlwaysClock()
    }

    /** 瞬时条退出回隐藏态：还原底栏完整元素（下次呼出无残留）。 */
    private fun exitSeekUi() {
        binding.textTitle.visibility = View.VISIBLE
        binding.buttonPlay.visibility = View.VISIBLE
        binding.panelTop.visibility = View.GONE
        binding.panelBottom.visibility = View.GONE
        controlsVisible = false
        updateAlwaysClock()
    }

    /** 长按拖动期间暂停播放器（画面定格），幂等（B 站 beginHoldPlaybackFreeze 同款）。 */
    private fun beginHoldFreeze() {
        if (holdPlaybackFrozen) return
        val p = player ?: return
        holdWasPlaying = p.playWhenReady
        holdPlaybackFrozen = true
        if (holdWasPlaying) p.pause()
    }

    /** 长按结束后恢复原播放状态；返回是否把播放从冻结暂停中恢复。 */
    private fun endHoldFreeze(): Boolean {
        if (!holdPlaybackFrozen) return false
        holdPlaybackFrozen = false
        val wasPlaying = holdWasPlaying
        holdWasPlaying = false
        if (wasPlaying) player?.play()
        return wasPlaying
    }

    // ==================== seek 预览渲染 ====================

    private fun renderSeekPreview(targetMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        previewPositionMs = targetMs
        binding.timeBar.setPosition(targetMs)
        binding.timeBar.setDuration(durationMs)
        binding.textPosition.text = formatMs(targetMs)
        showSeekPreviewWindow(targetMs, durationMs)
        showSeekHint(targetMs - seekAnchorMs)
    }

    /** 预览浮窗定位到进度条上方并跟随预览位置；取帧器异步出图。 */
    private fun showSeekPreviewWindow(targetMs: Long, durationMs: Long) {
        val preview = seekPreview ?: return
        val timeBar = binding.timeBar
        if (!timeBar.isAttachedToWindow || timeBar.width <= 0) return
        timeBar.getLocationInWindow(hostLoc)
        trackBounds.set(hostLoc[0], hostLoc[1], hostLoc[0] + timeBar.width, hostLoc[1] + timeBar.height)
        binding.root.getLocationInWindow(hostLoc)
        trackBounds.offset(-hostLoc[0], -hostLoc[1])
        preview.show(targetMs, durationMs, trackBounds)
        thumbProvider?.request(targetMs)
    }

    /** 中央快进/快退提示：秒数变化才刷文本（长按 tick 40ms 一次不能每拍都重排）。 */
    private fun showSeekHint(deltaMs: Long) {
        val seconds = kotlin.math.abs(deltaMs / 1000L).toInt().coerceAtLeast(1)
        if (seconds == lastHintSeconds) return
        lastHintSeconds = seconds
        binding.textSeekHint.text = if (deltaMs >= 0) "快进 ${seconds}s" else "快退 ${seconds}s"
        binding.textSeekHint.visibility = View.VISIBLE
    }

    private fun hideSeekHint() {
        lastHintSeconds = Int.MIN_VALUE
        binding.textSeekHint.visibility = View.GONE
    }

    private fun cancelPendingHoldStart() {
        holdPendingRunnable?.let { binding.root.removeCallbacks(it) }
        holdPendingRunnable = null
    }

    private fun cancelHoldTick() {
        holdTickRunnable?.let { binding.root.removeCallbacks(it) }
        holdTickRunnable = null
    }

    private fun cancelTapCommit() {
        tapCommitRunnable?.let { binding.root.removeCallbacks(it) }
        tapCommitRunnable = null
    }

    private fun cancelExitSeekUi() {
        exitSeekUiRunnable?.let { binding.root.removeCallbacks(it) }
        exitSeekUiRunnable = null
    }

    private fun seekUpperBoundMs(durationMs: Long): Long =
        (durationMs - 1_000L).coerceAtLeast(0L) // 距片尾留余量防误触连播（B 站 SEEK_END_GUARD_MS）

    /** 播放/暂停切换（按钮与遥控 OK 键共用）。暂停时控制栏常驻（hideRunnable 守卫）。 */
    private fun togglePlayPause() {
        player?.let {
            val willPause = it.isPlaying
            if (willPause) it.pause() else it.play()
            // 变暂停即把焦点收到播放键：暂停后控制栏常驻，播放键是常驻控制栏的操作起点
            //（覆盖 OK 隐藏态呼出/进度条持焦/按钮 click 全部暂停路径；恢复播放不抢焦点）
            if (willPause && binding.buttonPlay.visibility == View.VISIBLE) {
                binding.buttonPlay.requestFocus()
            }
        }
        bumpControls()
    }

    private fun startPositionWatcher() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                player?.let { p ->
                    val dur = p.duration.coerceAtLeast(0)
                    binding.textDuration.text = formatMs(dur)
                    binding.timeBar.setDuration(dur)
                    val preview = previewPositionMs
                    if (preview != null) {
                        // seek 预览期间进度条由预览位置驱动，真实位置不覆盖
                        binding.timeBar.setPosition(preview)
                    } else {
                        binding.textPosition.text = formatMs(p.currentPosition)
                        binding.timeBar.setPosition(p.currentPosition)
                        saveProgress(p.currentPosition)
                    }
                }
            }
        }
    }

    private fun durationMs(): Long = player?.duration?.coerceAtLeast(0) ?: 0L

    private fun saveProgress(positionMs: Long) {
        if (seriesId.isEmpty()) return
        repository.saveProgress(
            HongguoRepository.WatchRecord(
                seriesId, title, cover, episodeIndex, episodeCount,
                positionMs, durationMs(), System.currentTimeMillis()
            ),
            vid = currentVid.ifEmpty { seriesId }
        )
    }

    private fun playNext() {
        if (episodeCount > 0 && episodeIndex < episodeCount) {
            switchEpisode(episodeIndex + 1)
        } else {
            finish()
        }
    }

    private fun switchEpisode(index: Int) {
        if (switchingSource) return
        switchingSource = true
        lifecycleScope.launch {
            try {
                cancelSeekSession()
                val detail = repository.detail(seriesId)
                val episode = detail.episodes.getOrNull(index - 1) ?: return@launch
                episodeIndex = index
                resumeMs = 0L
                currentVid = episode.videoId
                binding.textTitle.text = "$title · 第${episodeIndex}集"
                val play = resolvePlayUrl(episode.videoId).also { currentPlay = it }
                binding.progressBuffering.visibility = View.VISIBLE
                val playable = resolvePlayable(play)
                binding.progressBuffering.visibility = View.GONE
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                bindThumbs(play)
                startPlayer(playable, revealControls = false)
                preloadFollowingEpisodes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(this@HongguoPlayerActivity, e.message ?: "切换失败")
            } finally {
                switchingSource = false
            }
        }
    }

    // ==================== 控制栏显隐与焦点 ====================

    private fun bumpControls() {
        setControlsVisible(true)
        android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
        android.os.Handler(mainLooper).postDelayed(hideRunnable, 4000)
    }

    /** 呼出控制栏并路由焦点：焦点已在进度条上不抢（B 站 LayoutManager.show 同款）。 */
    private fun showControls(focusPlayPause: Boolean) {
        setControlsVisible(true)
        binding.root.post {
            if (binding.timeBar.hasFocus()) return@post
            if (focusPlayPause) binding.buttonPlay.requestFocus()
        }
        bumpControls()
    }

    private fun hideControls() {
        android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
        setControlsVisible(false)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.panelTop.visibility = if (visible) View.VISIBLE else View.GONE
        binding.panelBottom.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            // 从瞬时条恢复时还原底栏完整元素
            binding.textTitle.visibility = View.VISIBLE
            binding.buttonPlay.visibility = View.VISIBLE
        }
        updateAlwaysClock()
    }

    /** 常驻时钟：设置开 + 控制栏隐藏 + 无 seek 界面时显示（B 站 setAlwaysOnClock 避让逻辑）。 */
    private fun updateAlwaysClock() {
        binding.textClockAlways.visibility =
            if (alwaysOnClock && !controlsVisible && !seekUiActive) View.VISIBLE else View.GONE
        updatePauseBigIcon()
    }

    /** 暂停大图标：用户暂停（playWhenReady=false 且 READY）即显示，与控制栏并存叠加（对齐 B 站
     * updatePauseIndicator，无 controlsVisible 条件——红果暂停时控制栏常驻，旧条件会把图标永远压住）。
     * 布局中位于底部控制栏之后，z 序天然在上层。用 playWhenReady 判定而非 isPlaying——
     * BUFFERING 的 isPlaying=false 不是"停了"，不能亮图标。 */
    private fun updatePauseBigIcon() {
        val p = player
        binding.imagePauseBig.visibility =
            if (p != null && !p.playWhenReady && p.playbackState == Player.STATE_READY
            ) View.VISIBLE else View.GONE
    }

    // ==================== 触摸 seek（对齐 B 站双路径） ====================

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 滑动会话进行中优先接管（含从按钮起滑被拦截的流）；否则检测水平滑动起会话
        if (handleSwipeSeekTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    /** 屏幕水平滑动快进（B 站 MyPlayerView.handleSwipeSeekTouch 同款）：
     * 超过 touchSlop 且水平为主的拖动 → 瞬时条+预览浮窗跟随实时预览，松手提交、取消回退。
     * 滑动期间画面不冻结（与 B 站一致），交由现有 seek 会话状态机统一渲染。 */
    private fun handleSwipeSeekTouch(event: MotionEvent): Boolean {
        val p = player ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                swipeStartMs = p.currentPosition.coerceAtLeast(0L)
                swipeTargetMs = swipeStartMs
                isSwipeEpisode = false
                swipeEpisodeArmed = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                if (!isSwipeSeeking && !isSwipeEpisode) {
                    val horizontal = kotlin.math.abs(dx) > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                    val vertical = kotlin.math.abs(dy) > touchSlop &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                    if (horizontal) {
                        val dur = p.duration.coerceAtLeast(0L)
                        if (dur <= 0L || !p.isCurrentMediaItemSeekable) return false
                        isSwipeSeeking = true
                        cancelExitSeekUi()
                        seekUiActive = true
                        seekFromTimebar = false // 瞬时条形态（B 站 enterSeekProgressOnly）
                        seekAnchorMs = swipeStartMs
                        enterSeekUi()
                    } else if (vertical) {
                        // 垂直滑动 = 切集（上滑下一集 / 下滑上一集），与水平 seek 按主导轴区分
                        isSwipeEpisode = true
                    } else {
                        return false
                    }
                }
                if (isSwipeEpisode) {
                    val armed = kotlin.math.abs(dy) >= episodeSwipeThresholdPx
                    if (armed != swipeEpisodeArmed) {
                        swipeEpisodeArmed = armed
                        if (armed) showEpisodeSwipeHint(if (dy < 0) "松开播放下一集" else "松开播放上一集")
                        else hideSeekHint()
                    }
                    return true
                }
                val dur = p.duration.coerceAtLeast(0L)
                if (dur <= 0L) return false
                swipeTargetMs = (swipeStartMs + computeSwipeDeltaMs(dx, dur))
                    .coerceIn(0L, seekUpperBoundMs(dur))
                renderSeekPreview(swipeTargetMs, dur)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwipeEpisode) {
                    val dy = event.y - swipeDownY
                    val trigger = event.actionMasked == MotionEvent.ACTION_UP &&
                        kotlin.math.abs(dy) >= episodeSwipeThresholdPx
                    isSwipeEpisode = false
                    swipeEpisodeArmed = false
                    hideSeekHint()
                    if (trigger) {
                        if (dy < 0) switchEpisodeRelative(1) else switchEpisodeRelative(-1)
                    }
                    return true
                }
                if (!isSwipeSeeking) return false
                isSwipeSeeking = false
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    p.seekTo(swipeTargetMs)
                    finishSeekUi()
                } else {
                    cancelSeekSession()
                }
                return true
            }
        }
        return false
    }

    /** 上下滑动切集：+1=下一集，-1=上一集（边界静默忽略）。 */
    private fun switchEpisodeRelative(delta: Int) {
        val target = episodeIndex + delta
        if (target < 1) return
        if (episodeCount > 0 && target > episodeCount) return
        if (switchingSource) return
        switchEpisode(target)
    }

    /** 切集滑动提示复用中央快退/快进提示位。 */
    private fun showEpisodeSwipeHint(text: String) {
        binding.textSeekHint.text = text
        binding.textSeekHint.visibility = View.VISIBLE
    }

    /** 滑满一整屏宽 = 100% 时长，滑动比例即时长比例（B 站 computeSwipeSeekDeltaMs 同款手感）。 */
    private fun computeSwipeDeltaMs(deltaX: Float, durationMs: Long): Long {
        val widthPx = binding.root.width.coerceAtLeast(1).toFloat()
        val ratio = (deltaX / widthPx).coerceIn(-1f, 1f)
        return (durationMs * ratio).toLong()
    }

    /** 进度条触摸拖动（B 站 MyPlayerControlView OnScrubListener 同款）：拖动不冻结画面，
     * 实时预览（浮窗+时间），松手提交走 finishSeekUi 完整控制栏路径收尾。 */
    private fun setupTimebarScrub() {
        binding.timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                cancelExitSeekUi()
                seekUiActive = true
                seekFromTimebar = true // 控制栏显示态拖动，完整控制栏保持
                seekAnchorMs = player?.currentPosition ?: 0L
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                val dur = durationMs()
                if (dur > 0L) renderSeekPreview(position.coerceIn(0L, seekUpperBoundMs(dur)), dur)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                val p = player
                if (!canceled && p != null && p.duration > 0L) {
                    // duration 暂不可用（切集窗口期）时放弃提交，防 seekTo(0) 凭空跳回片头
                    p.seekTo(position.coerceIn(0L, seekUpperBoundMs(p.duration)))
                    finishSeekUi()
                } else {
                    cancelSeekSession()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // DPAD 全部在 dispatchKeyEvent 路由消费，到不了这里；MENU 只剩画质弹窗入口
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showQualityDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        cancelPendingHoldStart()
        cancelHoldTick()
        cancelTapCommit()
        cancelExitSeekUi()
        android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
        thumbProvider?.release()
        player?.release()
        player = null
    }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    companion object {
        private const val EXTRA_SERIES_ID = "hg_series_id"
        private const val EXTRA_TITLE = "hg_title"
        private const val EXTRA_COVER = "hg_cover"
        private const val EXTRA_VID = "hg_vid"
        private const val EXTRA_EPISODE = "hg_episode"
        private const val EXTRA_EPISODE_COUNT = "hg_episode_count"
        private const val EXTRA_RESUME_MS = "hg_resume_ms"

        fun start(
            context: Context, seriesId: String, title: String,
            vid: String, episode: Int, episodeCount: Int, resumeMs: Long,
            cover: String = ""
        ) {
            context.startActivity(
                Intent(context, HongguoPlayerActivity::class.java).apply {
                    putExtra(EXTRA_SERIES_ID, seriesId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_COVER, cover)
                    putExtra(EXTRA_VID, vid)
                    putExtra(EXTRA_EPISODE, episode)
                    putExtra(EXTRA_EPISODE_COUNT, episodeCount)
                    putExtra(EXTRA_RESUME_MS, resumeMs)
                }
            )
        }
    }
}
