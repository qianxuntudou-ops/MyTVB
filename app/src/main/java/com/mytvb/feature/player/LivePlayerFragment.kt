package com.mytvb.feature.player

import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mytvb.BuildConfig
import com.mytvb.feature.player.extractor.FlvHevcExtractor
import com.mytvb.feature.player.danmaku.LiveSuperChatSignal
import com.mytvb.feature.player.settings.AudioBalanceSettings
import com.mytvb.feature.player.settings.PlayerSettingsStore
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.databinding.FragmentLivePlayerBinding
import com.mytvb.core.ui.navigation.navigateBackFromUi
import com.mytvb.core.ui.system.ViewUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.mytvb.core.ui.base.AppToast

@UnstableApi
class LivePlayerFragment : Fragment() {

    companion object {
        private const val TAG = "LivePlayerFragment"
        const val ARG_ROOM_ID = "room_id"
        private const val MAX_RETRY_COUNT = 3

        fun newInstance(roomId: Long): LivePlayerFragment {
            return LivePlayerFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ROOM_ID, roomId)
                }
            }
        }
    }

    private var _binding: FragmentLivePlayerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LivePlayerViewModel by viewModel()
    private val okHttpClient: OkHttpClient by inject()

    private var player: ExoPlayer? = null
    private var roomId: Long = 0L
    private var retryCount = 0
    private var isRetrying = false
    private var debugScReceiver: BroadcastReceiver? = null
    private var debugScPool: List<com.mytvb.model.live.LiveRoomItem> = emptyList()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "live player error: ${error.message}", error)
            if (isRetrying) return
            if (retryCount < MAX_RETRY_COUNT) {
                retryCount++
                isRetrying = true
                AppLog.d(TAG, "auto retry ($retryCount/$MAX_RETRY_COUNT) for error type=${error.errorCodeName}")
                binding.textError.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                val delayMs = retryCount * 1000L
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(delayMs)
                    if (_binding == null) return@launch
                    val needRebuild = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> true
                        else -> false
                    }
                    if (needRebuild) rebuildPlayer()
                    viewModel.retryLiveStream(roomId)
                    isRetrying = false
                }
            } else {
                binding.textError.text = getString(R.string.live_retry_failed_format, error.message, MAX_RETRY_COUNT)
                binding.textError.visibility = View.VISIBLE
            }
            syncPlaybackEnvironment()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaybackEnvironment()
            when (playbackState) {
                Player.STATE_BUFFERING -> binding.playerView.pauseDanmaku()
                Player.STATE_READY -> {
                    retryCount = 0
                    isRetrying = false
                    if (player?.playWhenReady == true) binding.playerView.resumeDanmaku()
                }
                Player.STATE_ENDED -> binding.playerView.stopDanmaku()
                Player.STATE_IDLE -> binding.playerView.pauseDanmaku()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackEnvironment()
            if (isPlaying) {
                retryCount = 0
                binding.playerView.resumeDanmaku()
            } else if (player?.playbackState != Player.STATE_BUFFERING) {
                binding.playerView.pauseDanmaku()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLivePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let { args ->
            roomId = args.getLong(ARG_ROOM_ID, -1L)
        }
        setupPlayer()
        setupObservers()
        if (BuildConfig.DEBUG) {
            registerDebugScReceiver()
            // 假 SC 数据池：登录账号关注且正在直播的 UP（真实头像+名字）
            viewLifecycleOwner.lifecycleScope.launch {
                debugScPool = runCatching { com.mytvb.network.api.BiliApi.attentionLiveList(1).second }
                    .onFailure { AppLog.w(TAG, "debug SC pool load failed: ${it.message}") }
                    .getOrDefault(emptyList())
                AppLog.i(TAG, "debug SC pool loaded: ${debugScPool.size} anchors")
            }
        }
        if (roomId > 0) {
            viewModel.loadLiveStream(roomId)
        }
    }

    /**
     * 调试用：adb 注入假 SC 验证卡片队列（release 不注册）。
     * adb shell am broadcast -a com.mytvb.DEBUG_SC --es msg "超长内容..." --es user 用户名 --ei price 50 --ei time 15
     */
    private fun registerDebugScReceiver() {
        debugScReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras ?: return
                // am broadcast --ei 传的是 Int，直接 getLong 会拿默认值，按实际类型取；
                // BaseBundle.get(String) 已废弃但这里是刻意类型无关（--ei/--el 混用），无 typed 等价物
                @Suppress("DEPRECATION")
                fun extraLong(name: String, def: Long): Long = when (val v = extras.get(name)) {
                    is Long -> v
                    is Int -> v.toLong()
                    else -> def
                }
                val id = System.currentTimeMillis()
                val pick = debugScPool.randomOrNull()
                binding.playerView.showLiveSuperChat(
                    LiveSuperChatSignal.New(
                        id = id,
                        userName = extras.getString("user") ?: pick?.uname?.takeIf { it.isNotBlank() } ?: "调试用户$id",
                        avatarUrl = extras.getString("avatar") ?: pick?.face.orEmpty(),
                        avatarFrameUrl = extras.getString("frame").orEmpty(),
                        message = extras.getString("msg") ?: "调试 SC 内容",
                        priceYuan = extraLong("price", 50L),
                        backgroundColor = 0xFF000000.toInt() or extraLong("bg", 0x68C5F0L).toInt(),
                        durationSec = extraLong("time", 60L)
                    )
                )
            }
        }
        // EXPORTED 才能收到 adb shell 广播；仅 DEBUG 构建注册
        ContextCompat.registerReceiver(
            requireContext(),
            debugScReceiver!!,
            IntentFilter("com.mytvb.DEBUG_SC"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun setupPlayer() {
        binding.playerView.setupDanmakuEngine()
        val liveHeaders = mapOf(
            "Origin" to "https://live.bilibili.com/",
            "Referer" to if (roomId > 0L) {
                "https://live.bilibili.com/$roomId"
            } else {
                "https://live.bilibili.com"
            }
        )
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
            )
            .setDefaultRequestProperties(liveHeaders)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 30_000, 1_000, 2_000)
            .build()
        val extractorsFactory = ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map { extractor ->
                if (extractor is FlvExtractor) FlvHevcExtractor() else extractor
            }.toTypedArray()
        }
        player = ExoPlayer.Builder(requireContext())
            .setRenderersFactory(PlayerInstancePool.createRenderersFactory(requireContext()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(loadControl)
            .build()
            .also {
                PlayerPlaybackPolicy.apply(it)
                // 音量均衡：PCM 处理器随 createRenderersFactory 挂在 sink 上，此处同步全局档位即可。
                AudioBalanceSettings.level = PlayerSettingsStore.load(requireContext()).audioBalance
                it.addListener(playerListener)
            }
        binding.playerView.setPlayer(player)
        binding.playerView.setControllerAutoShow(true)
        // 「时间常驻显示」：B 站直播宿主（与 PlayerActivity/VideoPlayerFragment 同款开关）
        binding.playerView.showAlwaysOnClock(PlayerSettingsStore.load(requireContext()).showAlwaysOnClock)
        binding.playerView.showHideFfRe(false)
        binding.playerView.showHideNextPrevious(false)
        binding.playerView.showHideEpisodeButton(false)
        binding.playerView.showHideActionButton(false)
        binding.playerView.showHideRelatedButton(false)
        binding.playerView.showHideCommentButton(false)
        binding.playerView.showHideRepeatButton(false)
        binding.playerView.showHideSubtitleButton(false)
        binding.playerView.showHideDmSwitchButton(true)
        binding.playerView.setShowHideOwnerInfo(false)
        binding.playerView.showSettingButton(false)
        binding.playerView.showHideLiveSettingButton(false)
        binding.playerView.showHideTimeBar(false)
        binding.playerView.showHideTimeText(false)
        binding.playerView.showHideRefreshButton(true)
        binding.playerView.showHideMirrorButton(true)
        binding.playerView.showHideLineButton(false)
        binding.playerView.setOnVideoSettingChangeListener(object : com.mytvb.feature.player.view.OnVideoSettingChangeListener {
            override fun onLiveSettings() {
                binding.playerView.showLiveQualityMenu()
            }

            override fun onSuperChatSwitch(enabled: Boolean) {
                PlayerSettingsStore.saveShowLiveSuperChat(enabled)
                binding.playerView.setLiveSuperChatEnabled(enabled)
            }

            override fun onLiveLineSettings() {
                binding.playerView.showLiveLineMenu()
            }

            override fun onRefresh() {
                if (roomId > 0) {
                    AppToast.show(requireContext(), getString(R.string.live_toast_refreshing))
                    viewModel.refreshLiveStream(roomId)
                }
            }

            override fun onClose() {
                navigateBackFromUi()
            }

            override fun onMirrorChange(enabled: Boolean) {
                binding.playerView.setMirrorEnabled(enabled)
            }
        })
        binding.playerView.setOnPlayerSettingChange(object : com.mytvb.feature.player.view.OnPlayerSettingChange {
            override fun onVideoQualityChange(quality: com.mytvb.model.video.quality.VideoQuality) {}
            override fun onAudioQualityChange(quality: com.mytvb.model.video.quality.AudioQuality) {}
            override fun onPlaybackSpeedChange(speed: Float) {}
            override fun onSubtitleChange(position: Int) {}
            override fun onVideoCodecChange(codec: com.mytvb.model.video.quality.VideoCodecEnum) {}
            override fun onAspectRatioChange(ratio: Int) {}
            override fun onLiveQualityChange(qn: Int) {
                viewModel.switchQuality(qn)
            }

            override fun onLiveLineChange(index: Int) {
                viewModel.switchLine(index)
            }
        })

        // 初始化直播弹幕：启用弹幕，启动引擎（不等待数据）
        binding.playerView.setDanmakuEnabled(true)
        binding.playerView.startLiveDanmaku()

        // SC（醒目留言）独立于弹幕开关，默认开
        binding.playerView.setLiveSuperChatEnabled(PlayerSettingsStore.load(requireContext()).showLiveSuperChat)
        binding.playerView.showHideScSwitchButton(true)

        syncPlaybackEnvironment()
    }

    private fun rebuildPlayer() {
        player?.removeListener(playerListener)
        binding.playerView.stopDanmaku()
        player?.release()
        player = null
        setupPlayer()
    }

    private fun syncPlaybackEnvironment() {
        val currentPlayer = player
        val keepScreenOn = currentPlayer != null &&
            currentPlayer.playWhenReady &&
            currentPlayer.playbackState != Player.STATE_IDLE &&
            currentPlayer.playbackState != Player.STATE_ENDED
        activity?.let { ViewUtils.keepScreenOn(it, keepScreenOn) }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playUrl.collect { url ->
                url?.let {
                    binding.textError.visibility = View.GONE
                    val mediaItem = MediaItem.Builder()
                        .setUri(it)
                        .setMimeType(resolveMimeType(it))
                        .build()
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lines.collect { lines ->
                binding.playerView.setLiveLines(lines, viewModel.selectedLineIndex.value)
                binding.playerView.showHideLineButton(lines.size >= 2)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedLineIndex.collect { idx ->
                binding.playerView.selectLiveLine(idx)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { error ->
                error?.let {
                    binding.textError.text = it
                    binding.textError.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.qualities.collect { qualities ->
                binding.playerView.setLiveQualities(qualities)
                binding.playerView.showHideLiveSettingButton(qualities.isNotEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedQuality.collect { quality ->
                quality?.let {
                    binding.playerView.selectLiveQuality(it.qn)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.roomTitle.collect { title ->
                binding.playerView.setTitle(title)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.anchorName.collect { name ->
                binding.playerView.setSubTitle(name)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveDuration.collect { duration ->
                binding.playerView.setLiveDuration(if (duration.isNotEmpty()) getString(R.string.live_streaming_duration_format, duration) else "")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.refreshEvent.collect { msg ->
                AppToast.show(requireContext(), msg)
            }
        }

        // 实时弹幕：用引擎当前时间作为 position 注入
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveDanmaku.collect { dmModel ->
                    binding.playerView.addLiveDanmaku(dmModel)
                }
            }
        }

        // SC（醒目留言）：左侧卡片队列，独立于弹幕开关；每条消息时读设置，改设置即时生效
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.superChat.collect { signal ->
                    val enabled = PlayerSettingsStore.load(requireContext()).showLiveSuperChat
                    binding.playerView.setLiveSuperChatEnabled(enabled)
                    if (enabled) {
                        when (signal) {
                            is LiveSuperChatSignal.New -> binding.playerView.showLiveSuperChat(signal)
                            is LiveSuperChatSignal.Delete -> binding.playerView.removeLiveSuperChat(signal.id)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
        syncPlaybackEnvironment()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
        binding.playerView.pauseDanmaku()
        syncPlaybackEnvironment()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        debugScReceiver?.let {
            runCatching { requireContext().unregisterReceiver(it) }
        }
        debugScReceiver = null
        player?.removeListener(playerListener)
        binding.playerView.stopDanmaku()
        binding.playerView.clearLiveSuperChat()
        binding.playerView.destroy()
        player?.release()
        activity?.let { ViewUtils.keepScreenOn(it, false) }
        player = null
        _binding = null
    }

    private fun resolveMimeType(url: String): String? {
        val normalized = url.substringBefore('?').lowercase()
        return when {
            normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            normalized.endsWith(".flv") -> MimeTypes.VIDEO_FLV
            else -> null
        }
    }
}
