package com.mytvb.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.media.VideoCodecSupport
import com.mytvb.feature.player.settings.AudioBalanceSettings
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.model.video.quality.VideoCodecEnum

@UnstableApi
object PlayerInstancePool {
    private const val TAG = "PlayerInstancePool"
    private const val IDLE_RELEASE_DELAY_MS = 45_000L
    private const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024 // 20MB

    // PCM AudioTrack 缓冲：日志里 500ms 缓冲出现 underrun，放宽到 750-2000ms 抵御软解/GC 抢占。
    private const val MIN_PCM_BUFFER_DURATION_US = 750_000
    private const val MAX_PCM_BUFFER_DURATION_US = 2_000_000

    // WiFi 下保持较快起播，但提高最小缓冲和重缓冲门槛，减少播放中反复 BUFFERING。
    private const val WIFI_MIN_BUFFER_MS = 12_000
    private const val WIFI_MAX_BUFFER_MS = 40_000
    private const val WIFI_BUFFER_FOR_PLAYBACK_MS = 1_000
    private const val WIFI_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    // 移动数据下的缓冲参数：更保守，减少卡顿
    private const val CELLULAR_MIN_BUFFER_MS = 16_000
    private const val CELLULAR_MAX_BUFFER_MS = 50_000
    private const val CELLULAR_BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val CELLULAR_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_500

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedPlayer: ExoPlayer? = null
    private var isAttached = false
    private var pendingReleaseRunnable: Runnable? = null
    @Volatile
    private var codecPrewarmStarted = false

    /**
     * player 当前实际挂载的 MediaSource 标识（"bvid#cid"）。
     *
     * 这是 zero_overhead_reuse 判定"player 上是否真是这个视频"的**唯一事实源**。
     * VM 缓存按 bvid+cid 存（容量 2），但 player 单例只能挂 1 个 MediaSource——
     * 两套键空间基数不同。VM 命中缓存不代表 player 上挂的就是同一视频
     * （退出→看别的→退出→看回原来的，player 已被覆盖）。此处显式记录挂载状态，
     * 供 VM 在走暖路径前查询，消除"VM 在无 player 状态信息下决策"的缺陷。
     *
     * 由 setMediaSource 的调用方通过 [rememberAttachedSource] 记录，
     * 由所有"清除 MediaItems 或销毁 player"的路径清空。softDetach 不清空
     * （其语义本就是保留 MediaSource 供热重播）。
     */
    private var attachedSourceKey: String? = null

    /**
     * 查询 player 当前挂载的 MediaSource 是否与请求的 bvid+cid 一致。
     * zero_overhead_reuse 走暖路径前必须调用此方法确认。
     */
    @Synchronized
    fun isAttachedSource(bvid: String?, cid: Long): Boolean {
        return attachedSourceKey != null && attachedSourceKey == sourceKey(bvid, cid)
    }

    /**
     * setMediaSource 成功挂载后，由调用方记录。
     * 仅在冷路径（player.setMediaSource(...)）调用，暖路径跳过 setMediaSource 故不调用。
     */
    @Synchronized
    fun rememberAttachedSource(bvid: String?, cid: Long) {
        attachedSourceKey = sourceKey(bvid, cid)
    }

    private fun sourceKey(bvid: String?, cid: Long): String = "${bvid.orEmpty()}#$cid"

    @Synchronized
    fun prewarm(context: Context) {
        prewarmCodecSupport()
        if (cachedPlayer != null) return
        mainHandler.post {
            synchronized(this) {
                if (cachedPlayer != null) return@synchronized
                cachedPlayer = buildPlayer(context.applicationContext)
            }
        }
    }

    private fun prewarmCodecSupport() {
        if (codecPrewarmStarted) return
        codecPrewarmStarted = true
        Thread({
            runCatching { VideoCodecSupport.getHardwareSupportedCodecs() }
        }, "player-codec-prewarm").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun isAttached(): Boolean = isAttached

    @Synchronized
    fun acquire(context: Context, expectBvid: String? = null, expectCid: Long = 0L): ExoPlayer {
        cancelPendingRelease()
        val player = cachedPlayer ?: buildPlayer(context.applicationContext).also {
            cachedPlayer = it
        }
        // 复用池实例时同步编码偏好的"冷启动兜底"：仅当 selector 上还从未注入过偏好时才用
        // 设置里的默认编码打底。此前每次 acquire 无条件覆盖（设置默认编码），playInfo 返回后
        // applySelectionSnapshot 再用会话编码覆盖回来——两次注入之间正是选轨窗口（seamless
        // MPD 是内存串，setMediaSource 后 TrackGroup 几乎立即可见），选轨拿到的是 acquire 的
        // 值而非会话值，出现"route plan/锁定写 HEVC、实际 init AV1"的分裂（issue#101 实测：
        // 设置默认编码=AV1 的设备上锁 125-HEVC 必 init av1 解码器）。
        // 注入源统一为会话值（applySelectionSnapshot），跨视频延续上个会话的编码选择。
        if (lastPreferredVideoMime == null) {
            setPreferredVideoCodec(PlayerSettingsStore.load(context).defaultVideoCodec)
        }
        // 复用实例可能还挂着上一个视频的 MediaSource 与播放位置（softDetach 只 stop
        // 不清 position、不清 MediaItems）。本次要播的身份与挂载源不一致时必须立即硬
        // 重置，否则会有两个串台症状：
        // 1. 换源前 progressCoordinator 把旧视频 position publish 给新会话的 VM，
        //    污染 pendingSeekPositionMs——没看过的新视频带着上个视频的退出位置续播；
        // 2. 换源前旧视频画面残留在新播放页上——点开 B 显示的是 A 的内容。
        // 同 bvid（重进同一视频，intent 往往不带 cid）保留挂载源以维持热重播路径。
        if (attachedSourceKey != null && !matchesExpectedSource(expectBvid, expectCid)) {
            AppLog.w(
                TAG,
                "acquire source mismatch, hardReset: expectBvid=$expectBvid expectCid=$expectCid attached=$attachedSourceKey"
            )
            hardReset(player)
        }
        isAttached = true
        return player
    }

    private fun matchesExpectedSource(expectBvid: String?, expectCid: Long): Boolean {
        val key = attachedSourceKey ?: return true
        // 调用方未提供任何期望身份（抖音模式等延续播放场景）时不做判定，保留挂载源，
        // 进度污染由 VideoPlayerViewModel.updatePlaybackPosition 的 cid gate 拦截。
        if (expectBvid.isNullOrBlank() && expectCid <= 0L) return true
        val attachedBvid = key.substringBefore('#')
        val attachedCid = key.substringAfter('#')
        return when {
            !expectBvid.isNullOrBlank() -> attachedBvid == expectBvid
            else -> attachedCid == expectCid.toString()
        }
    }

    /**
     * player 实际挂载源的 cid 是否与 [cid] 一致（cid 是流与进度的真正归属键）。
     *
     * 供进度/心跳链路做归属校验：VM 的当前会话 cid 与 player 实际挂载 cid 不一致时，
     * player 上报的 position 属于别的视频，不能写入本会话状态（详见
     * VideoPlayerViewModel.updatePlaybackPosition 的 gate）。
     */
    @Synchronized
    fun isAttachedCid(cid: Long): Boolean {
        val key = attachedSourceKey ?: return false
        return cid > 0L && key.substringAfter('#') == cid.toString()
    }

    /**
     * 池内 player 的实时播放位置（调用方须先经 [isAttachedCid] 确认位置归属）。
     *
     * 本地进度落盘用：VM 的 _currentPosition 只在 publishProgressState=true 时更新
     * （PlayerActivity 宿主底部进度条设置关时播放中恒 false，位置仅暂停瞬间刷新），
     * 直接读会拿到旧值/0，导致播放中的节拍保存失效、异常退出丢进度。
     */
    @Synchronized
    fun attachedPlayerPositionMs(): Long? {
        val player = cachedPlayer ?: return null
        if (!isAttached) return null
        return runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrNull()
    }

    @Synchronized
    fun softDetach(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        player.pause()
        isAttached = false
        player.playWhenReady = false
        player.stop()
        player.clearVideoSurface()
        // 不调用 clearMediaItems()，保留 MediaSource 以便同一视频热重播。
        // reuseSameSource 路径只需 prepare() + seekTo()，跳过 setMediaSource()。
        schedule_release()
    }

    @Synchronized
    fun hardReset(player: ExoPlayer?) {
        if (player == null || player !== cachedPlayer) return
        player.playWhenReady = false
        player.clearMediaItems()
        player.stop()
        player.playbackParameters = PlaybackParameters(1f)
        // MediaItems 被清除，挂载状态归零。
        attachedSourceKey = null
    }

    /**
     * 清空挂载状态标记，但不释放 player 实例。
     *
     * 用于播放缓存被外部主动释放（如设置页"清除缓存"）后，强制下一次播放走冷路径
     * （setMediaSource 重建），避免暖复用仍读到已 release 的 SimpleCache：
     *   clearCache → PlayerMediaCache.clear() release 了 CacheDataSource 引用的 SimpleCache，
     *   但 VideoPlayerViewModel.cachedPlaybacks 里的 MediaSource 还攥着旧引用，
     *   暖路径 prepare() 旧 MediaSource 会触发 SimpleCache.getContentMetadata 的
     *   checkState(contentIndex != null) 崩溃。把 attachedSourceKey 归零后，
     *   isAttachedSource() 返回 false → zero_overhead_reuse 降级，重建源即安全。
     */
    @Synchronized
    fun clearAttachedSource() {
        attachedSourceKey = null
    }

    @Synchronized
    fun detach(player: ExoPlayer?, allowReuse: Boolean) {
        if (player == null || player !== cachedPlayer) return
        if (!allowReuse) {
            releaseNow("detach_without_reuse")
            return
        }
        softDetach(player)
    }

    @Synchronized
    fun releaseNow(reason: String) {
        cancelPendingRelease()
        isAttached = false
        cachedPlayer?.release()
        cachedPlayer = null
        // player 销毁，挂载状态归零。
        attachedSourceKey = null
    }

    @Synchronized
    private fun schedule_release() {
        cancelPendingRelease()
        val releaseRunnable = Runnable {
            synchronized(this) {
                if (isAttached) return@synchronized
                cachedPlayer?.release()
                cachedPlayer = null
                // player 销毁，挂载状态归零。
                attachedSourceKey = null
                pendingReleaseRunnable = null
            }
        }
        pendingReleaseRunnable = releaseRunnable
        mainHandler.postDelayed(releaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    @Synchronized
    private fun cancelPendingRelease() {
        pendingReleaseRunnable?.let(mainHandler::removeCallbacks)
        pendingReleaseRunnable = null
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        // 音量均衡档位在构建 sink 前同步到全局 store：AudioProcessor 创建后每次取块
        // 都读全局档位，设置页切换即时生效，无需重建播放器。
        AudioBalanceSettings.level = PlayerSettingsStore.load(context).audioBalance
        // 视口压制失能（issue#101）：media3 默认用物理屏幕尺寸推算"值得下载的最大像素数"，
        // 组内存在 ≤ 屏幕的轨时更大的 4K/HDR 轨会被判超约束（SELECTION_ELIGIBILITY_FIXED），
        // 组间比较里 isWithinMaxConstraints 的优先级高于 preferredVideoMimeType——
        // 锁定 125/126 档时 TrackSelector 会转而选择 1080p 自适应组，锁定被架空
        // （电视实测 route plan 125-HEVC 实际 init AV1 解码器）。
        // 全屏播放场景视频轨本就不该被屏幕尺寸截断，设 MAX_VALUE 让全部轨进自适应候选。
        // 注入时机与编码偏好同步（setPreferredVideoCodec 同一 selector），每次构建必达。
        val isFastNetwork = isOnFastNetwork(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (isFastNetwork) WIFI_MIN_BUFFER_MS else CELLULAR_MIN_BUFFER_MS,
                if (isFastNetwork) WIFI_MAX_BUFFER_MS else CELLULAR_MAX_BUFFER_MS,
                if (isFastNetwork) WIFI_BUFFER_FOR_PLAYBACK_MS else CELLULAR_BUFFER_FOR_PLAYBACK_MS,
                if (isFastNetwork) WIFI_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS else CELLULAR_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            // blbl 同款：保留约一个前向缓冲窗的回看数据（从关键帧起），
            // 单击后退/来回扫这类"落在已播区间内"的 seek 直接命中内存缓冲，
            // 不再丢弃数据重新走网络
            .setBackBuffer(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, true)
            .build()
        // 无缝清晰度的组间钦点（锁定 qn/codec 直接选对应编码组，绕开 media3 组间比较的
        // 不可控行为——issue#101 实测偏好注入方向正确仍被 AV1 组反超，见该类注释）。
        val trackSelector = SeamlessQualityTrackSelector(context, SeamlessQualityTrackSelectionFactory())
        // 全屏播放不按屏幕像素截断视频轨（见本函数头部说明）。MAX_VALUE 使
        // getMaxVideoPixelsToRetainForViewport 直接短路返回 MAX_VALUE，全部轨 suitable。
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
        )
        activeTrackSelector = trackSelector
        return ExoPlayer.Builder(context)
            .setRenderersFactory(createRenderersFactory(context))
            .setLoadControl(loadControl)
            // 无缝清晰度切换：多清晰度 DASH MPD 源的视频轨选择交给 SeamlessQualitySelector
            // 控制；非无缝源（format.id 解析不出 qn/codec）自动退回默认自适应逻辑，行为不变。
            .setTrackSelector(trackSelector)
            .build()
            .also(PlayerPlaybackPolicy::apply)
            .also { player ->
                // 诊断锚点：DefaultTrackSelector 组间选择的最终结果（选中 representation 的
                // id 含 qn/codec）。route plan 是应用层计划，此处是渲染器层事实，两者不一致
                // 即选轨被架空（issue#101 电视 125-HEVC 计划实际 init AV1 的实证通道）。
                player.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        for (group in tracks.groups) {
                            if (group.type != C.TRACK_TYPE_VIDEO) continue
                            for (i in 0 until group.length) {
                                if (!group.isTrackSelected(i)) continue
                                val format = group.getTrackFormat(i)
                                AppLog.i(
                                    TAG,
                                    "track selection result: video=${format.id} " +
                                        "${format.width}x${format.height} " +
                                        "mime=${format.sampleMimeType} bitrate=${format.bitrate} " +
                                        "support=${group.getTrackSupport(i)}"
                                )
                            }
                        }
                    }
                })
            }
    }

    /**
     * 应用层编码偏好注入选轨器（PR#104 遗留问题修复）。
     *
     * 现状：编码选择只作用于拉流路由与 seamless 锁组，media3 DefaultTrackSelector 收不到
     * 偏好，跨编码组时按默认排序自选——华为真机 route plan 写 HEVC 实际却 instantiate
     * c2.qti.av1.decoder（能播但不符意图），TCL Amlogic 上 AV1 硬解是坏的则白等 3 秒回退
     * HEVC（issue#100 停摆的源头之一）。设置 preferredVideoMimeType 后选轨器优先选对应
     * 编码组的轨，偏好组不可用时仍回退其他组（media3 原生回退语义）。
     */
    @OptIn(UnstableApi::class)
    fun setPreferredVideoCodec(codec: VideoCodecEnum?) {
        val selector = activeTrackSelector ?: return
        val mime = when (codec) {
            VideoCodecEnum.HEVC -> MimeTypes.VIDEO_H265
            VideoCodecEnum.AV1 -> MimeTypes.VIDEO_AV1
            VideoCodecEnum.AVC -> MimeTypes.VIDEO_H264
            null -> null
        }
        if (lastPreferredVideoMime == mime) {
            // 静默去重时也留一次参数快照：确认 selector 上的偏好仍然在位
            // （池化复用/重建链路是否会丢参数，此前只能靠猜）。
            AppLog.i(
                TAG,
                "track selection preferred video mime unchanged: $mime " +
                    "(selector=${selector.parameters.preferredVideoMimeTypes})"
            )
            return
        }
        lastPreferredVideoMime = mime
        selector.setParameters(selector.buildUponParameters().setPreferredVideoMimeType(mime))
        AppLog.i(TAG, "track selection preferred video mime=$mime codec=$codec")
    }

    private var lastPreferredVideoMime: String? = null

    @Volatile
    private var activeTrackSelector: DefaultTrackSelector? = null

    fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        // 每个 player 一枚音量均衡处理器：档位读全局 store，关档 passthrough（仅一次 buffer 拷贝）。
        val volumeBalanceProcessor = VolumeBalanceAudioProcessor()
        return object : DefaultRenderersFactory(context.applicationContext) {
            init {
                // 硬解优先链路：流选择层（VideoCodecSupport.orderCandidates）已保证尽量选有硬解的编码，
                // 这里在渲染器层显式强化：允许解码器回退兜底（避免硬解瞬时失败导致无法播放）。
                setEnableDecoderFallback(true)
                // 音频扩展（FFmpeg）用 ON 而非 PREFER：系统解码器/直通优先，FFmpeg 只兜底。
                // PREFER 曾让 FFmpeg 抢在系统 MediaCodecAudioRenderer 之前把 EAC-3 全部软解成
                // PCM——电视的 EC-3 直通链路收不到原始码流，杜比全景声识别全灭（issue#101
                // 2.1.21 实测，audio decoder=ffmpegLavc-eac3 实锤）；ON 模式下电视走系统解码
                // /直通点亮全景声，无系统能力的手机（华为无 AC-3 授权）仍回落 FFmpeg 出声。
                // FLAC 例外：系统 flac 解码器 32KB input buffer 解不了 Hi-Res 大帧（顿挫根源），
                // 由 VideoCodecSupport.videoCodecSelector 在解码器查找层对 audio/flac 返回空，
                // 恒定交给 FFmpeg。
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

                // 解码方式（硬解优先 / 软解）：与能力预检共用同一 selector（见
                // VideoCodecSupport.videoCodecSelector），软解开关打开时 video/* 走
                // PREFER_SOFTWARE、失败可回退硬解（fallback 已开启）。selector 在每次
                // 解码器选择（起播/换轨/重建）时实时读取开关，切换后无需重建播放器池。
                setMediaCodecSelector(VideoCodecSupport.videoCodecSelector())

                // 构建期可观测性：记录检测到的硬解集合，便于诊断"是否落到软解"。
                // getHardwareSupportedCodecs 已有缓存与后台预热，此处为常量时间。
                val hwCodecs = runCatching { VideoCodecSupport.getHardwareSupportedCodecs() }
                    .getOrDefault(emptySet())
                AppLog.i(
                    TAG,
                    "renderers_factory built extensionMode=PREFER fallback=true " +
                        "softDecoderPreferred=${VideoCodecSupport.isSoftwareDecoderPreferred()} hwCodecs=$hwCodecs"
                )
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val delegate = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    // 1.9.3 的 setAudioProcessors 是普通数组参数（非 vararg），需显式包装。
                    .setAudioProcessors(arrayOf<AudioProcessor>(volumeBalanceProcessor))
                    .setAudioOutputProvider(
                        AudioTrackAudioOutputProvider.Builder(context)
                            .setAudioTrackBufferSizeProvider(
                                DefaultAudioTrackBufferSizeProvider.Builder()
                                    .setMinPcmBufferDurationUs(MIN_PCM_BUFFER_DURATION_US)
                                    .setMaxPcmBufferDurationUs(MAX_PCM_BUFFER_DURATION_US)
                                    .build()
                            )
                            .build()
                    )
                    .build()
                return SkipSampleTolerantAudioSink(delegate)
            }
        }
    }

    /**
     * 兜住 media3 1.9.3 skip-sample 容错路径与 DefaultAudioSink same-buffer 断言的状态冲突。
     *
     * 因果链（issue#101 海信 DV「一帧一帧卡顿」/ 华为真机 error code=1004 风暴实测定位）：
     * B 站 Hi-Res 无损 FLAC（id=30251，2.28Mbps）单帧可达 34KB，超过 c2.android.flac.decoder
     * 的 32KB input buffer → MediaCodecRenderer.feedInputBuffer 捕获 InsufficientCapacityException
     * 后走「跳过该样本 + flushCodec()」容错；但 codec flush 后输出的 buffer 实例已更换，而
     * DefaultAudioSink.handleBuffer 首行 checkArgument(inputBuffer==null || buffer==inputBuffer)
     * 仍持旧实例未消费完 → 断言炸成 ExoPlaybackException TYPE_UNEXPECTED（code=1004），
     * fallback 重建不换音轨 → 每轮必炸 → 错误风暴死循环（表现为视频一帧一帧播放）。
     *
     * 修法：断言违例时 flush 重置 sink 状态后重试一次（flush 会释放残留 inputBuffer 并清空
     * 待写数据，代价为丢一小段音频，好于整轮崩溃重建）。二次失败则上抛走原错误链。
     */
    private class SkipSampleTolerantAudioSink(
        private val delegate: AudioSink
    ) : AudioSink by delegate {

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            return try {
                delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            } catch (e: IllegalArgumentException) {
                AppLog.w(
                    TAG,
                    "audio sink same-buffer assert violated, flush and retry " +
                        "(skip-sample path after flac large frame): ${e.message}"
                )
                delegate.flush()
                delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }
    }

    private fun isOnFastNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
