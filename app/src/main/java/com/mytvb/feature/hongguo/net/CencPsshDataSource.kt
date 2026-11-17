package com.mytvb.feature.hongguo.net

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * CENC 流 pssh 注入 DataSource（2026-11-17 ClearKey 流式解密配套）：
 * 红果加密流只有 sinf/tenc（KID 在 tenc）而**无 pssh box**——media3 的
 * Mp4Extractor 只认 moov 直接 child 里的 pssh 来构造 DrmInitData，缺它则
 * format.cryptoType=NONE，加密样本被当明文解析（Invalid NAL length）。
 *
 * 本类在 open(position==0) 时做一次 box 手术：
 * 1. 预读 ftyp + moov（moov 前置于 mdat，红果流实测 122KB 量级）；
 * 2. 构造 52 字节 PSSH v1（systemID=通用 cenc UUID，携带 KID）；
 * 3. moov 尾部追加 pssh、moov size+52 → moov 之后所有 box（free/mdat）后移 52，
 *    同步修正 stco/co64 全部条目 +52（它们指向 mdat 样本区；saio 指向 moov 内
 *    的 senc 数据区，moov 内已有 box 位置不动，无需修）；
 * 4. 手术后的头部缓存在内存，后续 read 先吐缓冲再透传 upstream。
 *
 * seek 触发的 reopen（position>0）不做注入直接透传（moov 已被 extractor 缓存）。
 * 手术失败（流形态意外）静默降级为透传——播放行为回到注入前，无额外风险。
 */
class CencPsshDataSource(
    private val upstream: DataSource,
    private val kidHex: String
) : DataSource {

    private var header: ByteArray? = null
    private var headerPos = 0
    /** 注入增量（= pssh 字节数）：open 报告的总长只加这个量（头缓冲本身来自原流）。 */
    private var injectedDelta = 0
    /** 原始流里 moov 结束位置（注入点）：其后的逻辑偏移 = 物理偏移 + 注入量。 */
    private var injectPoint = -1L
    /** 尾部填充是否已输出（物理 EOF 后补 injectedDelta 字节对齐逻辑总长）。 */
    private var tailPadded = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // seek reopen（position>0）：mdat 区的逻辑坐标平移回物理坐标再透传
        val upstreamSpec =
            if (dataSpec.position == 0L || injectPoint < 0 || dataSpec.position < injectPoint) dataSpec
            else dataSpec.buildUpon().setPosition(dataSpec.position - injectedDelta).build()
        val len = upstream.open(upstreamSpec)
        header = null
        headerPos = 0
        // injectedDelta/injectPoint/tailPadded 不在此重置：seek 触发的多次 open 属同一逻辑
        // 流，注入信息必须跨 open 保留（重置会让 seek 平移失效，读错数据黑屏）
        if (dataSpec.position == 0L && len > 0) {
            runCatching { injectHeader() }
                .onFailure {
                    header = null; headerPos = 0
                    com.mytvb.core.common.log.AppLog.w("Hongguo", "pssh inject fail: ${it.message}")
                }
                .onSuccess {
                    com.mytvb.core.common.log.AppLog.d(
                        "Hongguo", "pssh injected header=${header?.size}B uri=${dataSpec.uri.toString().take(80)}"
                    )
                }
        }
        // 注入成功时长度报"物理长+52"：逻辑布局比物理流长 52B。物理 EOF 后由 read
        // 补 52 字节填充对齐总长（mdat 尺寸未变，样本读完即停，填充字节不会被解析），
        // 否则末尾 Range 请求越界 416
        return len + injectedDelta
    }

    /** 预读 ftyp+moov 并完成 pssh 手术（upstream 读位置停在 moov 结束处）。 */
    private fun injectHeader() {
        val pssh = buildPsshV1(kidHex)
        // ftyp（含可能的其它前置小 box）：按 box 链读到 moov 为止
        val prefixBoxes = ArrayList<ByteArray>(2)
        var moov: ByteArray? = null
        for (i in 0 until 8) {
            val box = readBox() ?: throw IOException("stream ended before moov")
            if (box.size >= 4 && String(box, 4, 4, Charsets.US_ASCII) == "moov") {
                moov = box; break
            }
            prefixBoxes.add(box)
        }
        moov ?: throw IOException("moov not found in stream head")
        patchStco(moov, pssh.size)
        // moov size 字段 +52（大端），pssh 追加到尾部
        val patchedSize = (beU32(moov, 0) + pssh.size)
        putBe32(moov, 0, patchedSize)
        val out = ByteArray(prefixBoxes.sumOf { it.size } + moov.size + pssh.size)
        var off = 0
        for (b in prefixBoxes) { System.arraycopy(b, 0, out, off, b.size); off += b.size }
        System.arraycopy(moov, 0, out, off, moov.size)
        System.arraycopy(pssh, 0, out, off + moov.size, pssh.size)
        header = out
        injectedDelta = pssh.size
        // 注入点 = 前缀 box 链原始字节 + 原 moov（未含 pssh 的物理终点）
        injectPoint = (out.size - pssh.size).toLong()
    }

    /** 从 upstream 完整读一个顶层 box（长度前缀读取；size=0/1 不支持——头部 box 不该出现）。 */
    private fun readBox(): ByteArray? {
        val hdr = ByteArray(8)
        var got = 0
        while (got < 8) {
            val n = upstream.read(hdr, got, 8 - got)
            if (n < 0) return null
            got += n
        }
        var size = beU32(hdr, 0).toLong() and 0xFFFFFFFFL
        if (size == 1L) throw IOException("largesize box in head unsupported")
        if (size == 0L) throw IOException("box to EOF in head unsupported")
        if (size > 16L * 1024 * 1024) throw IOException("head box too large")
        val box = ByteArray(size.toInt())
        System.arraycopy(hdr, 0, box, 0, 8)
        var off = 8
        while (off < box.size) {
            val n = upstream.read(box, off, box.size - off)
            if (n < 0) throw IOException("stream ended inside head box")
            off += n
        }
        return box
    }

    /** 深度遍历 moov（缓冲内字节），stco/co64 全部条目 +delta。 */
    private fun patchStco(moov: ByteArray, delta: Int) {
        var off = 8
        val end = moov.size
        while (off + 8 <= end) {
            val size = beU32(moov, off).toLong() and 0xFFFFFFFFL
            if (size < 8 || off + size > end) break
            val type = String(moov, off + 4, 4, Charsets.US_ASCII)
            when (type) {
                "stco" -> {
                    val n = beU32(moov, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 4
                        putBe32(moov, p, beU32(moov, p) + delta)
                    }
                }
                "co64" -> {
                    val n = beU32(moov, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 8
                        val hi = beU32(moov, p)
                        val lo = beU32(moov, p + 4)
                        var v = ((hi.toLong() and 0xFFFFFFFFL) shl 32) or (lo.toLong() and 0xFFFFFFFFL)
                        v += delta.toLong()
                        putBe32(moov, p, (v ushr 32).toInt())
                        putBe32(moov, p + 4, v.toInt())
                    }
                }
                "moov", "trak", "mdia", "minf", "stbl" -> patchStcoIn(moov, off + 8, (off + size).toInt(), delta)
            }
            off += size.toInt()
        }
    }

    /** 递归容器（不重入 stco 层）。 */
    private fun patchStcoIn(moov: ByteArray, start: Int, end: Int, delta: Int) {
        var off = start
        while (off + 8 <= end) {
            val size = beU32(moov, off).toLong() and 0xFFFFFFFFL
            if (size < 8 || off + size > end) break
            val type = String(moov, off + 4, 4, Charsets.US_ASCII)
            when (type) {
                "stco" -> {
                    val n = beU32(moov, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 4
                        putBe32(moov, p, beU32(moov, p) + delta)
                    }
                }
                "co64" -> {
                    val n = beU32(moov, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 8
                        val hi = beU32(moov, p)
                        val lo = beU32(moov, p + 4)
                        var v = ((hi.toLong() and 0xFFFFFFFFL) shl 32) or (lo.toLong() and 0xFFFFFFFFL)
                        v += delta.toLong()
                        putBe32(moov, p, (v ushr 32).toInt())
                        putBe32(moov, p + 4, v.toInt())
                    }
                }
                "trak", "mdia", "minf", "stbl" -> patchStcoIn(moov, off + 8, (off + size).toInt(), delta)
            }
            off += size.toInt()
        }
    }

    /** PSSH v1（52 字节）：通用 cenc systemID + 单 KID，无 Data。 */
    private fun buildPsshV1(kidHex: String): ByteArray {
        val kid = HongguoCrypto.hexToBytes(kidHex)
        require(kid.size == 16) { "kid must be 16 bytes" }
        val out = ByteArray(52)
        putBe32(out, 0, 52)
        out[4] = 'p'.code.toByte(); out[5] = 's'.code.toByte()
        out[6] = 's'.code.toByte(); out[7] = 'h'.code.toByte()
        out[8] = 1 // version 1
        // flags 0；systemID = e2719d58-a985-b3c9-781a-b030af78d30e（ClearKey 官方）：
        // DefaultDrmSessionManager 按 UUID 匹配 schemeData，通用 cenc UUID 不匹配
        // CLEARKEY_UUID → session 不建立 → codec 无 mCrypto
        val sysId = HongguoCrypto.hexToBytes("e2719d58a985b3c9781ab030af78d30e")
        System.arraycopy(sysId, 0, out, 12, 16)
        putBe32(out, 28, 1) // KID count
        System.arraycopy(kid, 0, out, 32, 16)
        putBe32(out, 48, 0) // DataSize
        return out
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val h = header
        if (h != null) {
            if (headerPos >= h.size) {
                header = null
            } else {
                val n = minOf(length, h.size - headerPos)
                System.arraycopy(h, headerPos, buffer, offset, n)
                headerPos += n
                if (headerPos >= h.size) header = null
                return n
            }
        }
        val n = upstream.read(buffer, offset, length)
        if (n < 0 && injectedDelta > 0 && !tailPadded) {
            // 物理 EOF：补 injectedDelta 字节填充对齐 open 报告的逻辑总长
            tailPadded = true
            val pad = minOf(length, injectedDelta)
            java.util.Arrays.fill(buffer, offset, offset + pad, 0)
            return pad
        }
        return n
    }

    override fun getUri() = upstream.uri

    override fun close() {
        header = null
        headerPos = 0
        upstream.close()
    }

    private fun beU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
