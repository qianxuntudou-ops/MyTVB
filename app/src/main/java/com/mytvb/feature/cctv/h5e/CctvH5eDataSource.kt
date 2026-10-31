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
 */
class CctvH5eDataSource(private val upstream: DataSource) : DataSource {

    companion object {
        private const val TAG = "CctvH5eDataSource"
        private const val TS_PACKET_SIZE = 188

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
                CctvH5eDecryptor.decryptTransportStream(bytes)
            } catch (t: Throwable) {
                AppLog.e(TAG, "H5e 解密异常 uri=${dataSpec.uri.lastPathSegment}", t)
                throw IOException("H5e decrypt failed: ${t.message}", t)
            }
            if (decrypted == null) {
                AppLog.e(TAG, "H5e 解密返回空（trap/会话未就绪）uri=${dataSpec.uri.lastPathSegment}")
                throw IOException("H5e decrypt rejected segment")
            }
            val cost = android.os.SystemClock.elapsedRealtime() - started
            AppLog.i(TAG, "H5e 分片解密 ok size=${decrypted.size} costMs=$cost")
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
    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CctvH5eDataSource(upstreamFactory.createDataSource())
    }
}
