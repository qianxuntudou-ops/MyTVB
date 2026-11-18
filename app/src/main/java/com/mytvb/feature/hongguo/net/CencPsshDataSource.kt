package com.mytvb.feature.hongguo.net

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
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
 * 2026-11-18 P2 头部磁盘缓存（对齐 HGTV C0174j3 头部落盘）：open(position==0)
 * 优先读 [HongguoHeadCache]（下集预取/上次播放落盘的原始头部），命中则 upstream
 * 直接从头部之后开 Range——起播关键路径免去 moov 网络下载；网络路径首次手术成功后
 * 原始头部回写缓存（同集重进受益）。总长不符（流变）弃缓存降级网络；upstream 403/404
 * （地址过期）顺手清头部缓存，对齐参考实现的 StreamExpired 清理策略。
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
        val url = dataSpec.uri.toString()
        // 头部缓存优先（仅首开 position==0；seek reopen 走下方透传路径）
        if (dataSpec.position == 0L) {
            val cached = runCatching { HongguoHeadCache.load(url) }.getOrNull()
            if (cached != null) {
                val headLen = cached.bytes.size.toLong()
                val restLen = if (dataSpec.length < 0L) -1L else dataSpec.length - headLen
                val spec = dataSpec.buildUpon()
                    .setPosition(headLen)
                    .setLength(restLen)
                    .build()
                header = null
                headerPos = 0
                val len2 = openUpstream(url, spec)
                // 总长校验（对齐 HGTV VideoChanged 防御）：流变了弃缓存走网络重读
                if (len2 > 0 && headLen + len2 == cached.totalLen &&
                    runCatching { injectFromRaw(cached.bytes) }.isSuccess
                ) {
                    com.mytvb.core.common.log.AppLog.d(
                        "Hongguo", "pssh head cache hit ${cached.bytes.size}B uri=${url.take(80)}"
                    )
                    return headLen + len2 + injectedDelta
                }
                // 降级：关掉这次半开的连接，从头走网络路径
                runCatching { upstream.close() }
                if (len2 > 0 && headLen + len2 != cached.totalLen) {
                    HongguoHeadCache.drop(url)
                }
            }
        }
        // seek reopen（position>0）：mdat 区的逻辑坐标平移回物理坐标再透传
        val upstreamSpec =
            if (dataSpec.position == 0L || injectPoint < 0 || dataSpec.position < injectPoint) dataSpec
            else dataSpec.buildUpon().setPosition(dataSpec.position - injectedDelta).build()
        val len = openUpstream(url, upstreamSpec)
        header = null
        headerPos = 0
        // injectedDelta/injectPoint/tailPadded 不在此重置：seek 触发的多次 open 属同一逻辑
        // 流，注入信息必须跨 open 保留（重置会让 seek 平移失效，读错数据黑屏）
        if (dataSpec.position == 0L && len > 0) {
            runCatching { injectHeader(len, url) }
                .onFailure {
                    header = null; headerPos = 0
                    com.mytvb.core.common.log.AppLog.w("Hongguo", "pssh inject fail: ${it.message}")
                }
                .onSuccess {
                    com.mytvb.core.common.log.AppLog.d(
                        "Hongguo", "pssh injected header=${header?.size}B uri=${url.take(80)}"
                    )
                }
        }
        // 注入成功时长度报"物理长+52"：逻辑布局比物理流长 52B。物理 EOF 后由 read
        // 补 52 字节填充对齐总长（mdat 尺寸未变，样本读完即停，填充字节不会被解析），
        // 否则末尾 Range 请求越界 416
        return len + injectedDelta
    }

    /** upstream open 统一入口：403/404 = 签名地址过期，顺手清头部缓存再抛。 */
    private fun openUpstream(url: String, spec: DataSpec): Long = try {
        upstream.open(spec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 403 || e.responseCode == 404) HongguoHeadCache.drop(url)
        throw e
    }

    /** 网络预读 ftyp+moov 并完成 pssh 手术（upstream 读位置停在 moov 结束处）；
     *  成功后原始头部回写磁盘缓存（同集重进/seek 重开受益）。 */
    private fun injectHeader(totalLen: Long, url: String) {
        // ftyp（含可能的其它前置小 box）：按 box 链读到 moov 为止
        val boxes = ArrayList<ByteArray>(2)
        for (i in 0 until 8) {
            val box = readBox() ?: throw IOException("stream ended before moov")
            if (box.size >= 4 && String(box, 4, 4, Charsets.US_ASCII) == "moov") {
                boxes.add(box); break
            }
            boxes.add(box)
        }
        val raw = ByteArray(boxes.sumOf { it.size })
        var off = 0
        for (b in boxes) { System.arraycopy(b, 0, raw, off, b.size); off += b.size }
        // 循环正常收尾 = 末个 box 是 moov（否则 8 个 box 都不是，头部形态意外）
        if (String(boxes.last(), 4, 4, Charsets.US_ASCII) != "moov") {
            throw IOException("moov not found in stream head")
        }
        injectFromRaw(raw)
        // 原始（未手术）头部落盘：头部 box 链完整且总长已知才有资格进缓存
        if (totalLen > raw.size) {
            runCatching { HongguoHeadCache.save(url, totalLen, raw) }
        }
    }

    /** 对原始头部字节（ftyp..moov 完整链）做 pssh 手术，产出去重后的 [header]。
     *  手术在副本上进行——调用方持有的 raw（预取落盘/缓存命中复用）必须保持原始未手术态，
     *  否则再次手术会 stco 双重 +52 错位（2026-11-18 MuMu 实测教训）。 */
    private fun injectFromRaw(rawSrc: ByteArray) {
        // 定位 moov 区间（只读扫描）
        var off = 0
        var moovStart = -1
        var moovEnd = -1
        while (off + 8 <= rawSrc.size) {
            val size = beU32(rawSrc, off).toLong() and 0xFFFFFFFFL
            if (size < 8 || off + size > rawSrc.size) throw IOException("bad head box chain")
            val type = String(rawSrc, off + 4, 4, Charsets.US_ASCII)
            if (type == "moov") { moovStart = off; moovEnd = off + size.toInt(); break }
            off += size.toInt()
        }
        if (moovStart < 0) throw IOException("moov not found in head bytes")
        val pssh = buildPsshV1(kidHex)
        val work = rawSrc.copyOf()
        patchContainer(work, moovStart, moovEnd, pssh.size)
        val out = ByteArray(work.size + pssh.size)
        System.arraycopy(work, 0, out, 0, work.size)
        System.arraycopy(pssh, 0, out, work.size, pssh.size)
        // moov size +52（大端；size 字段在 moovStart）
        putBe32(out, moovStart, beU32(out, moovStart) + pssh.size)
        header = out
        injectedDelta = pssh.size
        // 注入点 = 原始头部物理终点（pssh 之前的布局不变）
        injectPoint = rawSrc.size.toLong()
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

    /** 递归容器遍历（moov/trak/mdia/minf/stbl 层级 + stco/co64 条目平移）。 */
    private fun patchContainer(buf: ByteArray, start: Int, end: Int, delta: Int) {
        var off = start
        while (off + 8 <= end) {
            val size = beU32(buf, off).toLong() and 0xFFFFFFFFL
            if (size < 8 || off + size > end) break
            val type = String(buf, off + 4, 4, Charsets.US_ASCII)
            when (type) {
                "stco" -> {
                    val n = beU32(buf, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 4
                        putBe32(buf, p, beU32(buf, p) + delta)
                    }
                }
                "co64" -> {
                    val n = beU32(buf, off + 12)
                    for (i in 0 until n) {
                        val p = off + 16 + i * 8
                        val hi = beU32(buf, p)
                        val lo = beU32(buf, p + 4)
                        var v = ((hi.toLong() and 0xFFFFFFFFL) shl 32) or (lo.toLong() and 0xFFFFFFFFL)
                        v += delta.toLong()
                        putBe32(buf, p, (v ushr 32).toInt())
                        putBe32(buf, p + 4, v.toInt())
                    }
                }
                "moov", "trak", "mdia", "minf", "stbl" -> patchContainer(buf, off + 8, (off + size).toInt(), delta)
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
