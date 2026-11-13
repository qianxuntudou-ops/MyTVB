package com.mytvb.feature.hongguo

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.databinding.ActivityHongguoPlayerBinding
import com.mytvb.feature.player.settings.PlayerSettingsStore
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
 * - 快进预览图：本地解密 MP4 用 MediaMetadataRetriever 取帧，浮窗跟随进度条滑块；
 *   未加密远程直链集降级为纯时间提示
 * - OK 键分流：短按播放/暂停，长按（≥500ms）画质弹窗；控制栏隐藏态短按同时呼出控制栏
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

    private var controlsVisible = true
    private var hideRunnable = Runnable {
        // 暂停时控制栏常驻（B 站 shouldShowControllerIndefinitely：!playWhenReady 不自动隐藏）
        if (player?.isPlaying != true) return@Runnable
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
        alwaysOnClock = PlayerSettingsStore.load(this).showAlwaysOnClock
        binding.textTitle.text = "$title · 第${episodeIndex}集"
        binding.playerView.useController = false
        binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        setupSeekPreview()
        setupExit()
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
        // 播放/暂停、快退/快进按钮（组件复用 B 站 PlayerControlButton，点击接全局按键同款逻辑）
        binding.buttonPlay.setOnClickListener { togglePlayPause() }
        binding.buttonRewind.setOnClickListener { rewindOrForwardBy(-10_000L) }
        binding.buttonFastForward.setOnClickListener { rewindOrForwardBy(10_000L) }
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
     * 加密流（CENC）落地：下载整集 MP4 → AES-128-CTR 逐样本解密+moov 重建 → 本地文件。
     * 未加密直接返回原 URL。Node 同款方案（hongguo.js decryptMp4Buffer，单集约 30MB）。
     */
    private fun resolvePlayable(play: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl): String {
        val keyHex = play.contentKeyHex ?: return play.url
        val key = com.mytvb.feature.hongguo.net.HongguoCrypto.hexToBytes(keyHex)
        val cacheDir = com.mytvb.feature.hongguo.HongguoMediaCache.dir(this)
        // 缓存文件名带画质档（同集不同档各自落地，避免切画质误命中旧档流）
        val outFile = java.io.File(cacheDir, cachedFileName(play))
        if (outFile.isFile && outFile.length() > 0) return android.net.Uri.fromFile(outFile).toString()
        android.util.Log.d("Hongguo", "cenc download start ${play.vid} q=${play.pixels}")
        val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val bytes = http.newCall(
            okhttp3.Request.Builder().url(play.url)
                .header("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 15; zh_CN; 2211133C; Build/V417IR)")
                .header("Range", "bytes=0-")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("片源下载 HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IllegalStateException("片源下载为空")
        }
        android.util.Log.d("Hongguo", "cenc downloaded ${bytes.size}B, decrypting")
        val plain = com.mytvb.feature.hongguo.net.HongguoMp4Decrypt.decryptMp4Buffer(bytes, key)
        java.io.File(cacheDir, "${play.vid}.tmp").let { tmp ->
            tmp.writeBytes(plain)
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
        }
        android.util.Log.d("Hongguo", "cenc decrypted ${plain.size}B -> ${outFile.name}")
        // 落地后按「缓存限制」设置修剪旧集（LRU），防无限堆积
        com.mytvb.feature.hongguo.HongguoMediaCache.enforceLimit(this)
        return android.net.Uri.fromFile(outFile).toString()
    }

    /** CENC 缓存文件名：同集按画质档区分（vid@1080.mp4；无档位信息回落 vid.mp4 兼容旧缓存）。 */
    private fun cachedFileName(play: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl): String =
        if (play.pixels > 0) "${play.vid}@${play.pixels}.mp4" else "${play.vid}.mp4"

    /** 切画质成功后清理同集其它档位的落地缓存（单档约 30MB，防止逐档堆积）。 */
    private fun cleanupQualityCache(play: com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl) {
        val keep = cachedFileName(play)
        com.mytvb.feature.hongguo.HongguoMediaCache.dir(this).listFiles()?.forEach { f ->
            val name = f.name
            if (name != keep && name.startsWith("${play.vid}") &&
                (name == "${play.vid}.mp4" || name.matches(Regex("${play.vid}@\\d+\\.mp4")))
            ) f.delete()
        }
    }

    /** 本集画质弹窗：列实际下发档位（降序），当前档高亮；无档位/仅一档时提示后不弹。 */
    private fun showQualityDialog() {
        val play = currentPlay ?: return
        val options = play.availableQualities
        if (options.isEmpty() || options.size <= 1) {
            AppToast.show(this, getString(R.string.hongguo_quality_single, play.quality.ifEmpty { "唯一" }))
            return
        }
        val labels = options.map { "${it}P" }
        val selectedIndex = options.indexOf(play.pixels).takeIf { it >= 0 } ?: 0
        com.mytvb.core.ui.base.ChoiceDialog.show(
            this,
            getString(R.string.hongguo_quality_pick),
            labels,
            selectedIndex
        ) { index ->
            val target = options.getOrNull(index) ?: return@show
            if (target == play.pixels) return@show
            switchQuality(target)
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
                val playable = withContext(Dispatchers.IO) { resolvePlayable(play) }
                binding.progressBuffering.visibility = View.GONE
                currentPlay = play
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                cleanupQualityCache(play)
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
                val playable = withContext(Dispatchers.IO) { resolvePlayable(play) }
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

    private fun startPlayer(url: String) {
        player?.release()
        val p = ExoPlayer.Builder(this).build()
        player = p
        binding.playerView.player = p
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (resumeMs > 0) p.seekTo(resumeMs)
        p.playWhenReady = true
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
        bumpControls()
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
            binding.buttonRewind.visibility = View.INVISIBLE
            binding.buttonFastForward.visibility = View.INVISIBLE
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
        seekFromTimebar = false
        seekPreview?.hideNow()
        hideSeekHint()
        if (!controlsVisible && binding.panelBottom.visibility == View.VISIBLE) {
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
        binding.buttonRewind.visibility = View.VISIBLE
        binding.buttonFastForward.visibility = View.VISIBLE
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

    /** 控制栏快退/快进按钮：立即 ±10s 提交 + 预览闪现（B 站 dispatchRewind/FastForward 形态）。 */
    private fun rewindOrForwardBy(deltaMs: Long) {
        val p = player ?: return
        val dur = durationMs()
        if (dur <= 0L) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, seekUpperBoundMs(dur))
        p.seekTo(target)
        renderSeekPreview(target, dur)
        seekPreview?.hideAfter(500L)
        hideSeekHint()
        bumpControls()
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
                val playable = withContext(Dispatchers.IO) { resolvePlayable(play) }
                binding.progressBuffering.visibility = View.GONE
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                bindThumbs(play)
                startPlayer(playable)
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
            binding.buttonRewind.visibility = View.VISIBLE
            binding.buttonFastForward.visibility = View.VISIBLE
        }
        updateAlwaysClock()
    }

    /** 常驻时钟：设置开 + 控制栏隐藏 + 无 seek 界面时显示（B 站 setAlwaysOnClock 避让逻辑）。 */
    private fun updateAlwaysClock() {
        binding.textClockAlways.visibility =
            if (alwaysOnClock && !controlsVisible && !seekUiActive) View.VISIBLE else View.GONE
        updatePauseBigIcon()
    }

    /** 暂停大图标：用户暂停 + 控制栏隐藏 + 非 seek 会话 + 非播完时右下角显示（对齐红果官方）。
     * 用 playWhenReady 判定而非 isPlaying——BUFFERING 的 isPlaying=false 不是"停了"，不能亮图标。 */
    private fun updatePauseBigIcon() {
        val p = player
        binding.imagePauseBig.visibility =
            if (p != null && !p.playWhenReady && !seekUiActive && !controlsVisible &&
                p.playbackState != Player.STATE_ENDED
            ) View.VISIBLE else View.GONE
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
