package com.mytvb.feature.cctv.cmg

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mytvb.core.common.log.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 央视频（yangshipin）CMG 加密流解密 DataSource（卫视/超清组原生直拉）。
 *
 * 与 [com.mytvb.feature.cctv.h5e.CctvH5eDataSource] 同构：整段读入上游响应，
 * 识别 TS 传输流（0x47 同步字 + 188 对齐）则经 [CmgNativeBridge.decryptLiveSegment]
 * 有状态解密（每段重启 wasm runtime + 段内逐 NAL 推进）后回放给 media3；
 * m3u8 清单与非 TS 资源原样透传。解密在 open() 内同步完成；分片请求的
 * Referer/Origin 由上游 HTTP DataSource 工厂统一配置。
 */
class CmgDataSource(
    private val upstream: DataSource
) : DataSource {

    companion object {
        private const val TAG = "CmgDataSource"
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

        val output = ByteArrayOutputStream()
        val scratch = ByteArray(64 * 1024)
        while (true) {
            val n = upstream.read(scratch, 0, scratch.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
            if (n < 0) break
            output.write(scratch, 0, n)
        }
        val bytes = output.toByteArray()
        upstream.close()

        if (looksLikeTransportStream(bytes)) {
            val started = android.os.SystemClock.elapsedRealtime()
            val ok = try {
                CmgNativeBridge.decryptLiveSegment(bytes)
            } catch (t: Throwable) {
                AppLog.e(TAG, "CMG 解密异常 uri=${dataSpec.uri.lastPathSegment}", t)
                throw IOException("CMG decrypt failed: ${t.message}", t)
            }
            if (!ok) {
                AppLog.e(TAG, "CMG 解密会话未配置 uri=${dataSpec.uri.lastPathSegment}")
                throw IOException("CMG session not configured")
            }
            val cost = android.os.SystemClock.elapsedRealtime() - started
            AppLog.i(TAG, "CMG 分片解密 ok size=${bytes.size} costMs=$cost")
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
        if (bytes[0] != 0x47.toByte()) return false
        if (bytes.size >= TS_PACKET_SIZE * 2 && bytes[TS_PACKET_SIZE] != 0x47.toByte()) return false
        return true
    }

    /** CMG 解密链 DataSource 工厂：包装上游 HTTP 工厂（Referer 由上游配置）。 */
    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CmgDataSource(upstreamFactory.createDataSource())
    }
}
