@file:Suppress("SpellCheckingInspection")

package com.mytvb.core.common.media

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.mytvb.core.common.log.AppLog
import com.mytvb.model.video.quality.VideoCodecEnum
import com.mytvb.model.video.quality.VideoQuality

object VideoCodecSupport {

    private const val TAG = "VideoCodecSupport"

    @Volatile
    private var cachedHardwareCodecs: Set<VideoCodecEnum>? = null

    @Volatile
    private var cachedHdrSupported: Boolean? = null

    @Volatile
    private var cachedDolbyVisionSupported: Boolean? = null

    private val codecPriorityOrder = listOf(
        VideoCodecEnum.AV1,
        VideoCodecEnum.HEVC,
        VideoCodecEnum.AVC
    )

    private val softwareFallbackPriorityOrder = listOf(
        VideoCodecEnum.AVC,
        VideoCodecEnum.HEVC,
        VideoCodecEnum.AV1
    )

    fun getHardwareSupportedCodecs(): Set<VideoCodecEnum> {
        cachedHardwareCodecs?.let { return it }
        if (isEmulatorLikeDevice()) {
            cachedHardwareCodecs = emptySet()
            return emptySet()
        }
        val result = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .filter(::isHardwareDecoder)
                .flatMap { info ->
                    info.supportedTypes
                        .asSequence()
                        .mapNotNull(::codecFromMimeType)
                }
                .toSet()
        }.getOrDefault(emptySet())
        cachedHardwareCodecs = result
        return result
    }

    private fun isEmulatorLikeDevice(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val board = Build.BOARD.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val abiSummary = Build.SUPPORTED_ABIS.joinToString("|").lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("vbox") ||
            fingerprint.contains("test-keys") && (hardware.contains("goldfish") || hardware.contains("ranchu")) ||
            board.contains("goldfish") ||
            board.contains("ranchu") ||
            brand.startsWith("generic") ||
            device.contains("generic") ||
            device.contains("emulator") ||
            device.contains("ranchu") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("vbox") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("vbox") ||
            abiSummary.contains("x86") && (
                fingerprint.contains("generic") ||
                    brand.startsWith("generic") ||
                    product.contains("sdk") ||
                    product.contains("emulator") ||
                    hardware.contains("ranchu") ||
                    hardware.contains("goldfish")
                ) ||
            manufacturer.contains("genymotion")
    }

    fun buildSupportSummary(supportedCodecs: Collection<VideoCodecEnum>): String {
        val supported = supportedCodecs.toSet()
        return codecPriorityOrder.joinToString("  ") { codec ->
            if (codec in supported) "✓${codec.name}" else "✗${codec.name}"
        }
    }

    fun orderCandidates(
        availableCodecs: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        val uniqueAvailable = availableCodecs.toSet()
        if (uniqueAvailable.isEmpty()) {
            return emptyList()
        }

        val hardwareSupported = hardwareSupportedCodecs.toSet()
        val hardwarePreferredCodec = preferredCodec
            ?.takeIf { it in uniqueAvailable && it in hardwareSupported }

        return orderByHardwareThenPriority(
            codecs = uniqueAvailable,
            hardwareSupportedCodecs = hardwareSupported,
            preferredHardwareCodec = hardwarePreferredCodec
        )
    }

    private fun orderByHardwareThenPriority(
        codecs: Set<VideoCodecEnum>,
        hardwareSupportedCodecs: Collection<VideoCodecEnum>,
        preferredHardwareCodec: VideoCodecEnum?
    ): List<VideoCodecEnum> {
        if (codecs.isEmpty()) return emptyList()

        val hardwareSupported = hardwareSupportedCodecs.toSet()
        val hardwareAvailable = codecs.filterTo(linkedSetOf()) { it in hardwareSupported }
        val softwareFallback = codecs.filterTo(linkedSetOf()) { it !in hardwareAvailable }

        return if (hardwareAvailable.isNotEmpty()) {
            orderWithinTier(hardwareAvailable, preferredHardwareCodec, codecPriorityOrder) +
                orderWithinTier(softwareFallback, null, softwareFallbackPriorityOrder)
        } else {
            orderWithinTier(codecs, null, softwareFallbackPriorityOrder)
        }
    }

    private fun orderWithinTier(
        availableCodecs: Collection<VideoCodecEnum>,
        preferredCodec: VideoCodecEnum?,
        priorityOrder: List<VideoCodecEnum>
    ): List<VideoCodecEnum> {
        val available = availableCodecs.toSet()
        return buildList {
            preferredCodec
                ?.takeIf { it in available }
                ?.let(::add)
            priorityOrder
                .filter { it in available && it !in this }
                .forEach(::add)
            available
                .filter { it !in this }
                .sortedBy { codecPriority(it, priorityOrder) }
                .forEach(::add)
        }
    }

    private fun codecFromMimeType(mimeType: String): VideoCodecEnum? {
        return when (mimeType.lowercase()) {
            "video/av01" -> VideoCodecEnum.AV1
            "video/hevc" -> VideoCodecEnum.HEVC
            "video/avc" -> VideoCodecEnum.AVC
            else -> null
        }
    }

    /** 是否硬件解码器（编解码信息页亦复用此判定）。 */
    internal fun isHardwareDecoder(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly && !info.isAlias
        }

        val codecName = info.name.lowercase()
        return when {
            codecName.startsWith("omx.google.") -> false
            codecName.startsWith("c2.android.") -> false
            codecName.startsWith("c2.google.") -> false
            codecName.contains(".sw.") -> false
            codecName.contains("software") -> false
            codecName.contains("ffmpeg") -> false
            else -> codecName.startsWith("omx.") || codecName.startsWith("c2.")
        }
    }

    private fun codecPriority(codec: VideoCodecEnum, priorityOrder: List<VideoCodecEnum>): Int {
        return priorityOrder.indexOf(codec).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    // ===== 解码方式（硬解优先 / 软解）与起播能力预检 =====

    /**
     * 软解偏好数据源：由外层（Application 启动）注册为「读全局设置开关」的 lambda。
     * core 层经此读取开关而无需依赖设置模块；未注册时默认硬解优先。
     */
    @Volatile
    var softwareDecoderPreferenceSource: (() -> Boolean)? = null

    fun isSoftwareDecoderPreferred(): Boolean =
        softwareDecoderPreferenceSource?.invoke() ?: false

    /**
     * 当前生效的视频解码器选择器。
     *
     * 软解开关打开时仅对视频流（video 前缀 MIME）采用 [MediaCodecSelector.PREFER_SOFTWARE]
     * （软解优先，初始化失败仍按排序回退硬解——渲染工厂已开 setEnableDecoderFallback）；
     * 音频与其余场景保持 media3 默认（硬解优先）。
     *
     * 每次解码器选择（起播/换轨/重建）都会实时询问本 selector，读开关即时生效。
     * 播放侧（PlayerInstancePool）与能力预检（[isVideoTrackDecodable]）共用同一入口，
     * 保证「判定与选择一致」，避免预检判可解、播放却落到不同解码器。
     */
    @OptIn(UnstableApi::class)
    fun videoCodecSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val selector = if (mimeType.lowercase().startsWith("video/") && isSoftwareDecoderPreferred()) {
                MediaCodecSelector.PREFER_SOFTWARE
            } else {
                MediaCodecSelector.DEFAULT
            }
            val decoders = selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) && !isSoftwareDecoderPreferred()) {
                prependHevcDolbyVisionDecoders(decoders)
            } else {
                decoders
            }
        }

    /**
     * 给杜比视界解码器候选表前插「HEVC 容器 DV」组件（名字含 dvhe/dvh1 的硬解）。
     *
     * 背景（海信 MT9952 实测）：MTK 的 c2.mtk.dvhe.* 组件在 MediaCodecList 里不注册
     * video/dolby-vision 类型，media3 默认选择器永远查不到它们；而注册了该类型的
     * dvav 系组件（AVC/AV1 容器 DV）解不了 HEVC-DV 码流。B 站 126 档恰是 HEVC 容器
     * DV 流，这里按组件名定向补位并排到最前，让渲染器优先落到真正的 dvhe 组件上；
     * 后续 dvhe 组件初始化失败时仍有 decoderFallback 逐个回退（dvav → 报错走
     * PlaybackFallbackController 换编码），不会卡死。
     *
     * 软解偏好时不前插：用户显式选软解的意图优先（DV 无软解，交给回退链处理）。
     */
    @OptIn(UnstableApi::class)
    private fun prependHevcDolbyVisionDecoders(decoders: List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>):
        List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val existing = decoders.toMutableList()
        val injected = ArrayList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>()
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { androidInfo -> !androidInfo.isEncoder }
                .filter(::isHardwareDecoder)
                .filter { androidInfo ->
                    val name = androidInfo.name.lowercase()
                    name.contains("dvhe") || name.contains("dvh1")
                }
                .forEach { androidInfo ->
                    if (existing.any { it.name == androidInfo.name }) return@forEach
                    val codecMimeType = androidInfo.supportedTypes.firstOrNull() ?: return@forEach
                    val capabilities = runCatching {
                        androidInfo.getCapabilitiesForType(codecMimeType)
                    }.getOrNull()
                    val vendor = androidInfo.name.lowercase().let {
                        it.startsWith("omx.") || it.startsWith("c2.")
                    }
                    injected.add(
                        androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
                            androidInfo.name,
                            MimeTypes.VIDEO_DOLBY_VISION,
                            codecMimeType,
                            capabilities,
                            /* hardwareAccelerated = */ true,
                            /* softwareOnly = */ false,
                            vendor,
                            /* forceDisableAdaptive = */ false,
                            /* forceSecure = */ false
                        )
                    )
                }
        }
        if (injected.isEmpty()) return decoders
        if (!loggedDolbyVisionInjection) {
            loggedDolbyVisionInjection = true
            AppLog.i(
                TAG,
                "dolby vision decoder injection: ${injected.joinToString { it.name }} " +
                    "codecMimes=${injected.map { it.codecMimeType }.distinct()}"
            )
        }
        return injected + existing
    }

    @Volatile
    private var loggedDolbyVisionInjection: Boolean = false

    /**
     * 杜比视界 MPD 改写值：两段式串（无 level 段）。media3 对该形态既不做
     * profile/level 能力拒绝（解析不出 profile 即假定支持），也不触发 HEVC
     * base-layer 回落（拿不到 profile 无法判可回落），从而把流严格交给
     * video/dolby-vision 解码器处理（配合上面的 dvhe 组件前插）。
     */
    const val DOLBY_VISION_REWRITE_CODECS = "dvhe.05"

    /**
     * 该视频轨本机能否解码——复用 media3 [MediaCodecVideoRenderer.supportsFormat] 全规格判定
     * （mime/profile/level/尺寸×帧率），与播放实际选码策略同语义。
     *
     * 判定口径：仅 `FORMAT_UNSUPPORTED_*` 系列视为不可解码；`FORMAT_EXCEEDS_CAPABILITIES`
     * （超过申报能力但仍可处理，渲染器可解码/丢帧降级）与 `FORMAT_HANDLED` 都按可解码处理。
     * 保守取向：mime/尺寸元数据缺失或查询异常时返回 true，避免误伤正常播放。
     */
    @OptIn(UnstableApi::class)
    fun isVideoTrackDecodable(
        context: Context,
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean {
        if (mimeType.isNullOrBlank() || width <= 0 || height <= 0) {
            return true
        }
        return runCatching {
            // B 站 DASH 的 mimeType 字段常为容器级 video/mp4，真正编解码类型在 codecs 串
            // （avc1.x→video/avc、hvc1/hev1→video/hevc、av01→video/av01），按 codecs 优先解析。
            val resolvedMime = resolveVideoMime(mimeType, codecs)
            val format = Format.Builder()
                .setSampleMimeType(resolvedMime)
                .setWidth(width)
                .setHeight(height)
                .apply { if (!codecs.isNullOrBlank()) setCodecs(codecs) }
                .apply { if (frameRate > 0f) setFrameRate(frameRate) }
                .build()
            val support = MediaCodecVideoRenderer.supportsFormat(context, videoCodecSelector(), format)
            val formatSupport = support and RendererCapabilities.FORMAT_SUPPORT_MASK
            val decodable = formatSupport >= C.FORMAT_EXCEEDS_CAPABILITIES
            AppLog.i(
                TAG,
                "capability_probe mime=$resolvedMime ${width}x${height} fps=$frameRate codecs=$codecs " +
                    "support=$support formatSupport=$formatSupport decodable=$decodable"
            )
            decodable
        }.getOrDefault(true)
    }

    /** 从 codecs 串（RFC 6381）解析具体视频 MIME；无法识别时回退传入值。 */
    private fun resolveVideoMime(mimeType: String, codecs: String?): String {
        val c = codecs?.lowercase().orEmpty()
        return when {
            c.startsWith("avc1") || c.startsWith("avc3") -> "video/avc"
            c.startsWith("hvc1") || c.startsWith("hev1") -> "video/hevc"
            c.startsWith("av01") -> "video/av01"
            c.startsWith("dvh1") || c.startsWith("dvhe") || c.startsWith("dav1") -> "video/dolby-vision"
            else -> mimeType
        }
    }

    /**
     * 设备感知的默认视频编码（仅作为用户「未显式设置过」时的默认值，手动选择永远优先）。
     *
     * 疑似 MTK 电视芯片的设备默认 AVC：MTK 电视 SoC 的 HEVC 硬解存在「初始化成功但输出
     * 坏帧（花屏）」类设备缺陷（androidx/media#2765 同类），且花屏无错误回调、任何自动
     * 降级都无法感知，默认避开 HEVC 走兼容性最好的 H.264；其余设备保持 HEVC。
     */
    fun defaultPreferredCodec(context: Context): VideoCodecEnum {
        cachedDefaultCodec?.let { return it }
        val mtkSuspect = isSuspectMediatekTvDevice(context)
        val result = if (mtkSuspect) {
            VideoCodecEnum.AVC
        } else {
            VideoCodecEnum.HEVC
        }
        AppLog.i(
            TAG,
            "codec default: mtk-suspect=$mtkSuspect tv=${isTvDevice(context)} " +
                "default=$result markers=${buildDeviceMarkers()}"
        )
        cachedDefaultCodec = result
        return result
    }

    @Volatile
    private var cachedDefaultCodec: VideoCodecEnum? = null

    /** MTK 电视芯片特征：mt96xx/mt58xx/mt59xx 等 SoC 命名或厂商字段含 mediatek。 */
    private val mediatekChipPattern = Regex("(^|[| _\\-/])mt\\d{4}")

    private fun isSuspectMediatekTvDevice(context: Context): Boolean {
        if (!isTvDevice(context)) return false
        val markers = buildDeviceMarkers()
        if (markers.contains("mediatek")) return true
        return mediatekChipPattern.containsMatchIn(markers)
    }

    private fun isTvDevice(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    /** 参与芯片识别的设备字段原文（小写、| 分隔）；识别未命中时据此回溯该往规则里补什么特征。 */
    private fun buildDeviceMarkers(): String = buildString {
        append(Build.HARDWARE).append('|')
        append(Build.BOARD).append('|')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            append(Build.SOC_MODEL).append('|')
            append(Build.SOC_MANUFACTURER)
        }
    }.lowercase()

    @Suppress("unused")
    fun isHdrSupported(context: Context? = null): Boolean {
        cachedHdrSupported?.let { return it }
        val decoderOk = hasDecoder("video/hevc")
        if (!decoderOk) {
            cachedHdrSupported = false
            return false
        }
        if (context == null) {
            return true
        }
        val result = hasHdrDisplaySupport(context)
        cachedHdrSupported = result
        return result
    }

    @Suppress("unused")
    fun isDolbyVisionSupported(context: Context? = null): Boolean {
        cachedDolbyVisionSupported?.let { return it }
        val mediaCodecListHit = hasDecoder("video/dolby-vision") ||
            hasDecoder("video/dvhe") ||
            hasDecoder("video/dvav")
        val media3Hit = hasMedia3DolbyVisionDecoder()
        AppLog.i(
            TAG,
            "dolby vision decoder probe: mediaCodecList=$mediaCodecListHit media3=$media3Hit"
        )
        if (!mediaCodecListHit && !media3Hit) {
            cachedDolbyVisionSupported = false
            return false
        }
        if (context == null) {
            return true
        }
        val result = hasDolbyVisionDisplaySupport(context)
        cachedDolbyVisionSupported = result
        return result
    }

    /**
     * 该视频轨是否为杜比视界流。
     *
     * B 站 126 档杜比视界流的 codecs 是 HEVC 形态（实测 hvc1.2.4.H150.90），不带 dvhe
     * 前缀，必须按档位 id 判定；dvhe/dvh1/dovi 前缀与 MIME 判定兜底其它下发形态。
     */
    fun isDolbyVisionTrack(qualityId: Int, mimeType: String?, codecs: String?): Boolean {
        if (qualityId == VideoQuality.QUALITY_DOLBY_VISION.id) return true
        if (mimeType?.lowercase()?.contains("dolby-vision") == true) return true
        val c = codecs?.lowercase().orEmpty()
        return c.startsWith("dvhe") || c.startsWith("dvh1") || c.contains("dovi")
    }

    /**
     * 杜比全景声音轨（B 站 id=30250，EAC3 码流，API 泛标 ac-3）本机能否真正出声。
     *
     * 判定两路并联：系统 MediaCodecList 有 audio/ac-3 或 audio/eac3 解码器（电视常见，
     * 走硬解）；或 media3 FFmpeg 扩展可用（org.jellyfin.media3:media3-ffmpeg-decoder，
     * eac3 解码器为 AC-3/E-AC-3 超集实现）。两者皆无的设备 media3 对解不了的音频轨
     * 不报错、直接跳轨，表现为「视频正常但无声」（华为真机实测：切杜比全景声后
     * audio decoder init 一次都不出现）。音轨选择前用它预检：UI 主动选择时拦截提示，
     * 初始偏好/重建选轨时回落到可用 AAC 档。
     */
    fun isDolbyAtmosTrackPlayable(): Boolean {
        cachedDolbyAtmosPlayable?.let { return it }
        val systemDecoder = hasDecoder("audio/ac-3") || hasDecoder("audio/eac3")
        val ffmpegDecoder = runCatching {
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat(
                androidx.media3.common.MimeTypes.AUDIO_E_AC3
            )
        }.getOrDefault(false)
        val result = systemDecoder || ffmpegDecoder
        AppLog.i(
            TAG,
            "dolby atmos decoder probe: playable=$result " +
                "system=$systemDecoder ffmpeg=$ffmpegDecoder"
        )
        cachedDolbyAtmosPlayable = result
        return result
    }

    @Volatile
    private var cachedDolbyAtmosPlayable: Boolean? = null

    /**
     * media3 级杜比视界解码器查询，与 [hasDecoder] 的 MediaCodecList 原生查询并联：
     * media3 额外认厂商私有别名（如 OMX.MS.HEVCDV 的 video/hevcdv、RealTek 的
     * video/dv_hevc），部分电视只以别名暴露 DV 解码器。
     */
    @OptIn(UnstableApi::class)
    private fun hasMedia3DolbyVisionDecoder(): Boolean {
        return runCatching {
            MediaCodecUtil.getDecoderInfos(
                MimeTypes.VIDEO_DOLBY_VISION,
                /* requiresSecureDecoder = */ false,
                /* requiresTunnelingDecoder = */ false
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasDecoder(mimeType: String): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && mimeType.lowercase() in it.supportedTypes.map { t -> t.lowercase() } }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun hasHdrDisplaySupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = getDisplay(context) ?: return false
        val hdrCaps = display.hdrCapabilities ?: return false
        return hdrCaps.supportedHdrTypes.isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun hasDolbyVisionDisplaySupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val display = getDisplay(context) ?: return false
        val hdrCaps = display.hdrCapabilities ?: return false
        return hdrCaps.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    }

    private fun getDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }
}
