package com.mytvb.feature.hongguo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果独立播放器（media3）：竖屏剧按比例自适应，UP/DOWN 切集，进度回写红果本地存储。
 * 与 B 站 PlayerActivity 完全隔离；加密流（CENC spade）下载整集解密后播本地缓存。
 */
class HongguoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHongguoPlayerBinding
    private val repository by lazy { HongguoRepository.get(this) }
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var uiJob: Job? = null

    private var seriesId = ""
    private var title = ""
    private var cover = ""
    private var episodeIndex = 1
    private var episodeCount = 0
    private var resumeMs = 0L
    /** 当前集视频 id（云端进度上传 item_id）。 */
    private var currentVid = ""

    private var controlsVisible = true
    private var hideRunnable = Runnable { setControlsVisible(false) }
    /** 双击 BACK 退出确认：与 B 站 PlayerActivity 同款（exitTime/exitInterval）。 */
    private var exitTime = 0L
    private val exitInterval = 2000L

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
        binding.textTitle.text = "$title · 第${episodeIndex}集"
        binding.playerView.useController = false
        binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        setupExit()
        loadAndPlay()
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
            if (controlsVisible) {
                setControlsVisible(false)
                android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
            } else {
                bumpControls()
            }
        }
        // 播放/暂停、快退/快进按钮（组件复用 B 站 PlayerControlButton，点击接全局按键同款逻辑）
        binding.buttonPlay.setOnClickListener { togglePlayPause() }
        binding.buttonRewind.setOnClickListener { seekBy(-10_000L) }
        binding.buttonFastForward.setOnClickListener { seekBy(10_000L) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsVisible) {
                    // BACK 优先收播放 UI（对齐 B 站：控制条 → 双击确认退出）
                    setControlsVisible(false)
                    android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
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
        val cacheDir = java.io.File(cacheDir, "hongguo").apply { mkdirs() }
        val outFile = java.io.File(cacheDir, "${play.vid}.mp4")
        if (outFile.isFile && outFile.length() > 0) return android.net.Uri.fromFile(outFile).toString()
        android.util.Log.d("Hongguo", "cenc download start ${play.vid}")
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
        return android.net.Uri.fromFile(outFile).toString()
    }

    /** 取播放地址（纯 Kotlin 签名+JVM okhttp 传输；WebView 通道已删——实证从未成功）。 */
    private suspend fun resolvePlayUrl(vid: String): com.mytvb.feature.hongguo.net.HongguoPlay.PlayUrl =
        repository.playUrl(vid)

    private fun loadAndPlay() {
        val vid = intent.getStringExtra(EXTRA_VID) ?: return
        currentVid = vid
        // 登录态心跳（30 分钟阈值内部判定，未登录空操作）
        repository.beatIfNeeded()
        lifecycleScope.launch {
            try {
                android.util.Log.d("Hongguo", "player load vid=$vid ep=$episodeIndex")
                val play = resolvePlayUrl(vid)
                android.util.Log.d("Hongguo", "player got url codec=${play.codec} q=${play.quality} key=${play.contentKeyHex != null}")
                binding.progressBuffering.visibility = View.VISIBLE
                val playable = withContext(Dispatchers.IO) { resolvePlayable(play) }
                binding.progressBuffering.visibility = View.GONE
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                startPlayer(playable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Hongguo", "player load failed", e)
                AppToast.show(this@HongguoPlayerActivity, e.message ?: "播放失败")
                finish()
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
                if (state == Player.STATE_READY) saveProgress(p.currentPosition)
                if (state == Player.STATE_ENDED) {
                    saveProgress(durationMs())
                    playNext()
                }
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

    /** 播放/暂停切换（按钮与遥控 OK 键共用）。 */
    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        bumpControls()
    }

    /** 快退/快进（按钮与遥控 LEFT/RIGHT 共用），到头钳 1s 片尾余量防误触连播。 */
    private fun seekBy(deltaMs: Long) {
        player?.let { p ->
            val dur = durationMs()
            val target = if (deltaMs < 0) {
                (p.currentPosition + deltaMs).coerceAtLeast(0L)
            } else if (dur > 0) {
                (p.currentPosition + deltaMs).coerceAtMost(dur - 1_000L).coerceAtLeast(0L)
            } else {
                p.currentPosition + deltaMs
            }
            p.seekTo(target)
        }
        bumpControls()
    }

    private fun startPositionWatcher() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(1000)
                player?.let { p ->
                    binding.textPosition.text = formatMs(p.currentPosition)
                    binding.textDuration.text = formatMs(p.duration.coerceAtLeast(0))
                    binding.timeBar.setPosition(p.currentPosition)
                    binding.timeBar.setDuration(p.duration.coerceAtLeast(0))
                    saveProgress(p.currentPosition)
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
        lifecycleScope.launch {
            try {
                val detail = repository.detail(seriesId)
                val episode = detail.episodes.getOrNull(index - 1) ?: return@launch
                episodeIndex = index
                resumeMs = 0L
                currentVid = episode.videoId
                binding.textTitle.text = "$title · 第${episodeIndex}集"
                val play = resolvePlayUrl(episode.videoId)
                binding.progressBuffering.visibility = View.VISIBLE
                val playable = withContext(Dispatchers.IO) { resolvePlayable(play) }
                binding.progressBuffering.visibility = View.GONE
                binding.textQuality.text = play.quality.ifEmpty { play.codec }
                startPlayer(playable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppToast.show(this@HongguoPlayerActivity, e.message ?: "切换失败")
            }
        }
    }

    private fun bumpControls() {
        setControlsVisible(true)
        android.os.Handler(mainLooper).removeCallbacks(hideRunnable)
        android.os.Handler(mainLooper).postDelayed(hideRunnable, 4000)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.panelTop.visibility = if (visible) View.VISIBLE else View.GONE
        binding.panelBottom.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (episodeIndex > 1) switchEpisode(episodeIndex - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (episodeCount == 0 || episodeIndex < episodeCount) switchEpisode(episodeIndex + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-10_000L)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(10_000L)
                return true
            }
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
        uiJob?.cancel()
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
