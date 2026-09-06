package com.tutu.myblbl.feature.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tutu.myblbl.core.common.log.AppLog

/**
 * 构建多清晰度 DASH MediaSource（无缝清晰度切换的播放源）。
 *
 * 与现有 Progressive+Merging 链路的关键差异：
 * - MPD 内嵌全部 (qn, codec) 档位 + 选中音轨，一次挂载后切档不再重建；
 * - MPD 保存在内存（ByteArrayDataSource），不落盘——MediaSource 对象被
 *   VideoPlayerViewModel 的 cachedPlaybacks 复用时内容天然正确；
 * - 每条 Representation 一个 [VideoPlayerCdnFailoverDataSourceFactory]（主 URL +
 *   backup 候选），按请求 URL 路由分发，CDN 故障切换能力与旧链路对齐。
 */
@UnstableApi
internal class SeamlessDashMediaSourceFactory(
    private val baseDataSourceFactory: DataSource.Factory,
    private val urlNormalizer: (String) -> String
) {

    internal class CreatedSource(
        val mediaSource: MediaSource,
        val cdnFailoverStates: List<VideoPlayerCdnFailoverState>,
        val catalog: SeamlessQualityCatalog
    )

    fun createMediaSource(catalog: SeamlessQualityCatalog): CreatedSource {
        val mpd = SeamlessDashMpdBuilder.buildAdaptiveOnDemandMpd(catalog)
        val mpdBytes = mpd.toByteArray(Charsets.UTF_8)
        val mpdUri = Uri.parse("seamless://dash/${System.nanoTime()}")
        val routeFactories = LinkedHashMap<String, DataSource.Factory>()
        val cdnStates = ArrayList<VideoPlayerCdnFailoverState>()

        fun routeFactoryFor(urls: List<String>): DataSource.Factory {
            val candidates = urls
                .map(urlNormalizer)
                .filter { it.isNotBlank() }
                .distinct()
            if (candidates.size <= 1) return baseDataSourceFactory
            val state = VideoPlayerCdnFailoverState(candidates = candidates.map(Uri::parse))
            cdnStates.add(state)
            return VideoPlayerCdnFailoverDataSourceFactory(
                upstreamFactory = baseDataSourceFactory,
                state = state
            )
        }

        catalog.options.forEach { option ->
            val urls = listOf(option.representation.baseUrl) + option.representation.backupUrls
            routeFactories[canonicalUriKey(option.representation.baseUrl)] = routeFactoryFor(urls)
        }
        catalog.audioRepresentation?.let { audio ->
            val urls = listOf(audio.baseUrl) + audio.backupUrls
            routeFactories[canonicalUriKey(audio.baseUrl)] = routeFactoryFor(urls)
        }

        val routedFactory = SeamlessDataSourceFactory(
            mpdUri = mpdUri,
            mpdBytes = mpdBytes,
            routeFactories = routeFactories,
            fallbackFactory = baseDataSourceFactory
        )
        val mediaItem = MediaItem.Builder()
            .setUri(mpdUri)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()
        val mediaSource = DefaultMediaSourceFactory(routedFactory).createMediaSource(mediaItem)
        AppLog.i(
            TAG,
            "seamless source built: representations=${catalog.options.size} " +
                "qns=${catalog.qualityIds} initialQn=${catalog.initialQualityId} " +
                "initialCodec=${catalog.initialCodec} cdnRoutes=${routeFactories.size}"
        )
        return CreatedSource(
            mediaSource = mediaSource,
            cdnFailoverStates = cdnStates,
            catalog = catalog
        )
    }

    private fun canonicalUriKey(url: String): String = Uri.parse(url).toString()

    companion object {
        private const val TAG = "SeamlessQuality"
    }
}

/**
 * 按 DataSpec.uri 分发的 DataSource 工厂：
 * - manifest 请求（mpdUri）→ 内存 MPD；
 * - 各 Representation 的 init/index/chunk 请求 → 该流自己的 CDN failover 工厂。
 */
@UnstableApi
private class SeamlessDataSourceFactory(
    private val mpdUri: Uri,
    private val mpdBytes: ByteArray,
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        SeamlessDataSource(mpdUri, mpdBytes, routeFactories, fallbackFactory)
}

@UnstableApi
private class SeamlessDataSource(
    private val mpdUri: Uri,
    private val mpdBytes: ByteArray,
    private val routeFactories: Map<String, DataSource.Factory>,
    private val fallbackFactory: DataSource.Factory
) : DataSource {

    private var transferListener: TransferListener? = null
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = resolveFactory(dataSpec)
        val source = factory.createDataSource()
        transferListener?.let(source::addTransferListener)
        delegate = source
        return source.open(dataSpec)
    }

    private fun resolveFactory(dataSpec: DataSpec): DataSource.Factory {
        if (dataSpec.uri == mpdUri) {
            return DataSource.Factory { ByteArrayDataSource(mpdBytes) }
        }
        val routed = routeFactories[canonicalUriKey(dataSpec.uri)]
        if (routed != null) return routed
        AppLog.w(
            "SeamlessQuality",
            "unrouted dataSpec uri=${dataSpec.uri} keys=${routeFactories.keys} → fallback factory"
        )
        return fallbackFactory
    }

    private fun canonicalUriKey(uri: Uri): String = uri.toString()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
