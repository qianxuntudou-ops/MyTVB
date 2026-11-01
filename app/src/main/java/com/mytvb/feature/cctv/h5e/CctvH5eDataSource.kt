package com.mytvb.feature.cctv.h5e

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.log.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * CCTV H5e 加密流解密 DataSource（issue#106 阶段二）。
 *
 * 包装上游 HTTP DataSource：整段读入响应体，识别 TS 传输流（首字节 0x47 同步字 +
 * 长度 188 对齐）则经 [CctvH5eDecryptor.decryptTransportStream] 解密后回放给
 * media3 提取器；m3u8 清单（文本）与非 TS 资源原样透传——无需按 URL 后缀分流。
 * 解密在 open() 内同步完成（分片级，native 侧毫秒级），read() 只从内存缓存出。
 *
 * [vodMode]=true 走点播解密入口（影视 hls_h5e 流：player_container_player tag
 * + NAL25 门控，未加密段透传），供 CctvVodPlayerActivity 原生播放复用。
 */
/** H5e 解密失败（wasm trap/会话未就绪）——确定性失败，重试无意义，
 *  让 LoadErrorHandlingPolicy 立即放弃并触发播放器级回退。 */
class H5eDecryptException(message: String) : IOException(message)

class CctvH5eDataSource(
    private val upstream: DataSource,
    private val vodMode: Boolean = false,
    /** 同段解密失败达到 [TRAP_FAIL_LIMIT] 时的直通通知（宿主用于立即回退，
     *  不等 media3 错误传播链）。在加载线程回调，宿主自行切主线程。 */
    private val fatalListener: ((String) -> Unit)? = null
) : DataSource {

    companion object {
        private const val TAG = "CctvH5eDataSource"
        private const val TS_PACKET_SIZE = 188
        // 同一分片解密失败的容忍次数。前几次抛 IOException（policy 零延迟放弃，
        // Loader 可能仍按 media-chunk 自愈逻辑重开）；超过阈值说明该段确定性解不
        // 出（wasm trap），抛非 IOException 让 media3 作为 source error 直接上抛，
        // 触发播放器的 nativeRetry/WebView 回退链，避免无限快速重试。
        private const val TRAP_FAIL_LIMIT = 3
        private val trapFailCounts = HashMap<String, Int>()

        /** 换集/换会话时清零（由播放器调用）。 */
        fun resetTrapFailures() {
            synchronized(trapFailCounts) { trapFailCounts.clear() }
        }
    }


    private val transferListeners = ArrayList<TransferListener>(2)
    private var decryptedBuffer: ByteArray? = null
    private var bufferOffset = 0
    private var openedUri: android.net.Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        openedUri = dataSpec.uri
        upstream.open(dataSpec)

        // 整段读入（HLS 分片响应有 Content-Length；流式兜底读到 EOF）
        val output = ByteArrayOutputStream()
        val scratch = ByteArray(64 * 1024)
        while (true) {
            val n = upstream.read(scratch, 0, scratch.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            if (n < 0) break
            output.write(scratch, 0, n)
        }
        var bytes = output.toByteArray()
        upstream.close()

        if (looksLikeTransportStream(bytes)) {
            val started = android.os.SystemClock.elapsedRealtime()
            val decrypted = try {
                if (vodMode) {
                    CctvH5eDecryptor.decryptVodTransportStream(bytes)
                } else {
                    CctvH5eDecryptor.decryptTransportStream(bytes)
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "H5e 解密异常 uri=${dataSpec.uri.lastPathSegment}", t)
                throw IOException("H5e decrypt failed: ${t.message}", t)
            }
            if (decrypted == null) {
                val key = dataSpec.uri.toString()
                val fails = synchronized(trapFailCounts) { trapFailCounts.merge(key, 1, Int::plus)!! }
                AppLog.e(TAG, "H5e 解密返回空（trap/会话未就绪）uri=${dataSpec.uri.lastPathSegment} fail#$fails")
                if (fails >= TRAP_FAIL_LIMIT) {
                    // 确定性失败：直接通知宿主回退（media3 对 source error 的内部
                    // 恢复/重试链要拖几十秒，等不起），同时抛非 IOException 让
                    // ExoPlayer 尽快把错误状态收掉。
                    fatalListener?.invoke(key)
                    throw RuntimeException("H5e decrypt rejected segment repeatedly: $key")
                }
                throw H5eDecryptException("H5e decrypt rejected segment")
            }
            val cost = android.os.SystemClock.elapsedRealtime() - started
            AppLog.i(TAG, "H5e 分片解密 ok size=${decrypted.size} costMs=$cost vod=$vodMode")
            bytes = decrypted
        }
        decryptedBuffer = bytes
        bufferOffset = 0
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = decryptedBuffer ?: return androidx.media3.common.C.RESULT_END_OF_INPUT
        if (bufferOffset >= data.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
        val toRead = minOf(length, data.size - bufferOffset)
        System.arraycopy(data, bufferOffset, buffer, offset, toRead)
        bufferOffset += toRead
        return toRead
    }

    override fun getUri(): android.net.Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.getResponseHeaders()

    override fun close() {
        decryptedBuffer = null
        bufferOffset = 0
        openedUri = null
        runCatching { upstream.close() }
    }

    private fun looksLikeTransportStream(bytes: ByteArray): Boolean {
        if (bytes.size < TS_PACKET_SIZE) return false
        if (bytes.size % TS_PACKET_SIZE != 0) return false
        // 首包与第二包（若有）同步字都要是 0x47，排除恰好 188 倍数的其它负载
        if (bytes[0] != 0x47.toByte()) return false
        if (bytes.size >= TS_PACKET_SIZE * 2 && bytes[TS_PACKET_SIZE] != 0x47.toByte()) return false
        return true
    }

    /** HLS 解密链 DataSource 工厂：包装上游 HTTP 工厂（带 Referer 等 header 由上游配置）。 */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val vodMode: Boolean = false,
        var fatalDecryptListener: ((String) -> Unit)? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CctvH5eDataSource(upstreamFactory.createDataSource(), vodMode, fatalDecryptListener)
    }
}
