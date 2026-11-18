package com.mytvb.feature.hongguo

import android.content.Context
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.hongguo.net.CencDecryptDataSource
import com.mytvb.feature.hongguo.net.HongguoCrypto
import com.mytvb.feature.hongguo.net.HongguoHeadCache

/**
 * 红果软解密 MediaSource 工厂（2026-11-18 架构级优化提取，全屏 Activity 与详情弹窗
 * 内嵌播放共用）：CencDecryptDataSource（DataSource 层 AES-CTR 还原，零设备 DRM
 * 依赖）+ mp4fork predecrypted extractor（加密语义旁路，codec 只见明文）。
 */
object HongguoSoftDecryptSources {

    fun build(context: Context, source: HongguoRepository.PlayableSource): androidx.media3.exoplayer.source.MediaSource {
        AppLog.d("Hongguo", "soft-decrypt src url=${source.uri.take(180)}")
        val key = HongguoCrypto.hexToBytes(source.keyHex!!)
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(HongguoHeadCache.CDN_UA)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        HongguoHeadCache.ensureDir(context)
        val dataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            CencDecryptDataSource(httpFactory.createDataSource(), key, source.cacheKey)
        }
        // 普通 MP4 语义（无 DRM 配置）+ predecrypted fork extractor（加密语义旁路）
        val mediaItem = androidx.media3.common.MediaItem.Builder().setUri(source.uri).build()
        return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            dataSourceFactory,
            androidx.media3.extractor.ExtractorsFactory {
                arrayOf(
                    com.mytvb.feature.hongguo.mp4fork.Mp4Extractor(
                        androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED,
                        /* flags= */ 0,
                        /* predecrypted= */ true
                    )
                )
            }
        )
            .createMediaSource(mediaItem)
    }
}
