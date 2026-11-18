package com.mytvb.feature.hongguo.net

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * CENC 流式软解密 DataSource（2026-11-18 P3，HGTV 同款架构根治方案）：
 * 设备 DRM 栈不再参与——部分设备（荣耀/华为系实测）ClearKey HAL 判定商业用户后不建
 * 会话，MediaCodec 无 mCrypto 收到加密样本必崩（mCrypto 竞态族问题的根）。本类在
 * read 透传路上按 moov 加密样本区间表做 AES-128-CTR 还原，codec 只见明文；
 * 配套 mp4fork Mp4Extractor 的 predecrypted 模式（加密语义整体旁路）。
 *
 * open(position==0) 三条路：
 * 1. 头部缓存命中（[HongguoHeadCache]，预取/上次播放落盘）：解析加密图，upstream
 *    直接从 headLen 起 Range——moov 零网络（总长校验防流变，不符弃缓存降级）；
 * 2. 缓存 miss：临时连接网络读 ftyp+moov 建图并回写缓存，upstream 同样从 headLen
 *    起——moov 只网络下载这一次（头部字节内存直供，Extractor 读头部零成本）；
 * 3. 形态意外：解密旁路全透传（下游显式报错，不静默错数据）。
 *
 * 解密按 16B 块对齐：样本内偏移 X 的 keystream 块 = IV(8B nonce+8B 零计数器)+X/16
 * （CTR 特性任意位置可解，seek reopen 无需特殊处理）；连续块逐个 ECB update。
 * 403/404（地址过期）顺手清头部缓存。
 */
class CencDecryptDataSource(
    private val upstream: DataSource,
    private val key: ByteArray,
    /** 头部缓存 key（vid@pixels）：同集同片源在 CDN 间换 host 数据不变，url-hash 会
     *  因负载均衡误 miss（2026-11-18 真机实测：预热后播放 URL 换了 v5-se2 host）；
     *  一致性由总长校验兜底。 */
    private val cacheKey: String
) : DataSource {

    /** 全局加密样本区间（按 start 升序）；空 = 明文流（解密全旁路）。 */
    private var ranges: List<HongguoMp4Decrypt.EncryptedRange> = emptyList()
    /** 头部字节（缓存命中/网络建图时直供，read 先吐再透传）。 */
    private var header: ByteArray? = null
    private var headerPos = 0
    /** 当前流逻辑位置（解密坐标）。 */
    private var pos = -1L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        pos = dataSpec.position
        header = null
        headerPos = 0
        // 防「上一源的区间表残留」错位解密（2026-11-18 真机教训：换源后 ranges 未
        // 重置，旧样本布局解新数据=必错位）：每次 open 清零，解析失败即抛错走整集兜底
        ranges = emptyList()
        val url = dataSpec.uri.toString()
        if (dataSpec.position == 0L && tryOpenWithHead(url, dataSpec)) {
            return headLen() + tailLen
        }
        val len = openUpstream(url, dataSpec)
        return len
    }

    private var tailLen = -1L

    private fun headLen(): Long = header?.size?.toLong() ?: 0L

    /** upstream open 统一入口：403/404 = 签名地址过期，顺手清头部缓存再抛。 */
    private fun openUpstream(url: String, spec: DataSpec): Long = try {
        upstream.open(spec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 403 || e.responseCode == 404) HongguoHeadCache.drop(cacheKey)
        throw e
    }

    /**
     * 头部直供模式（position==0 专用）：拿原始头部（缓存优先，miss 网络预读+回写），
     * 解析加密图成功后 upstream 从 headLen 起开 Range。头部形态意外返回 false
     * （调用方走全网络透传）。
     */
    private fun tryOpenWithHead(url: String, dataSpec: DataSpec): Boolean {
        val cached = runCatching { HongguoHeadCache.load(cacheKey) }.getOrNull()
        var raw = cached?.bytes
        if (raw == null) {
            // 网络预读 ftyp+moov（临时连接，读完即关）
            raw = runCatching {
                upstream.open(dataSpec)
                readHeadBoxes()
            }.getOrNull()
            runCatching { upstream.close() }
            if (raw == null) return false
        }
        // 头部在手但加密图解析失败：形态意外，抛错走整集兜底（密文透传只会喂坏 codec）
        if (!tryParseRanges(raw)) throw IOException("soft-decrypt head parse fail")
        val restLen = if (dataSpec.length < 0L) -1L else dataSpec.length - raw.size
        val spec = dataSpec.buildUpon().setPosition(raw.size.toLong()).setLength(restLen).build()
        val len2 = try {
            openUpstream(url, spec)
        } catch (e: IOException) {
            throw e
        }
        if (len2 <= 0L) {
            // 长度未知：无法校验总长，头部直供照给（len2=-1 时调用方拿 -1 走未知长度语义）
            tailLen = len2
            header = raw
            if (cached == null && len2 > 0L) saveHead(raw.size.toLong() + len2, raw)
            return true
        }
        if (cached != null) {
            if (raw.size.toLong() + len2 == cached.totalLen) {
                // 正常 206 分段响应
                com.mytvb.core.common.log.AppLog.d("Hongguo", "head cache hit (206) key=$cacheKey")
            } else if (len2 == cached.totalLen) {
                // Range 被忽略（200 全量）：upstream 从 0 起给了全流，读偏移修正
                com.mytvb.core.common.log.AppLog.d("Hongguo", "head cache hit (200-full) key=$cacheKey")
            } else {
                // 总长真不符（流变，对齐 HGTV VideoChanged 防御）：弃缓存降级全网络
                com.mytvb.core.common.log.AppLog.w(
                    "Hongguo", "head cache stale (total ${cached.totalLen} vs ${raw.size + len2}), drop key=$cacheKey"
                )
                HongguoHeadCache.drop(cacheKey)
                runCatching { upstream.close() }
                return false
            }
        }
        tailLen = if (cached != null && len2 == cached.totalLen) cached.totalLen - raw.size else len2
        header = raw
        if (cached == null) saveHead(raw.size.toLong() + len2, raw)
        return true
    }

    private fun saveHead(totalLen: Long, raw: ByteArray) {
        if (totalLen > raw.size) runCatching { HongguoHeadCache.save(cacheKey, totalLen, raw) }
    }

    private fun tryParseRanges(head: ByteArray): Boolean = runCatching {
        ranges = HongguoMp4Decrypt.parseEncryptedRanges(head)
        com.mytvb.core.common.log.AppLog.d(
            "Hongguo", "soft-decrypt ranges=${ranges.size} head=${head.size}B"
        )
        true
    }.getOrDefault(false)

    /** 从 upstream（已 open）顺序读 ftyp..moov 完整链。 */
    private fun readHeadBoxes(): ByteArray? {
        val boxes = ArrayList<ByteArray>(2)
        for (i in 0 until 8) {
            val box = readBox() ?: return null
            if (box.size >= 4 && String(box, 4, 4, Charsets.US_ASCII) == "moov") {
                boxes.add(box); break
            }
            boxes.add(box)
        }
        if (String(boxes.last(), 4, 4, Charsets.US_ASCII) != "moov") return null
        return ByteArray(boxes.sumOf { it.size }).also { buf ->
            var off = 0
            for (b in boxes) { System.arraycopy(b, 0, buf, off, b.size); off += b.size }
        }
    }

    /** 从 upstream 完整读一个顶层 box（size=0/1/超大视为形态意外）。 */
    private fun readBox(): ByteArray? {
        val hdr = ByteArray(8)
        var got = 0
        while (got < 8) {
            val n = upstream.read(hdr, got, 8 - got)
            if (n < 0) return null
            got += n
        }
        val size = ((hdr[0].toInt() and 0xFF).toLong() shl 24) or
            ((hdr[1].toInt() and 0xFF).toLong() shl 16) or
            ((hdr[2].toInt() and 0xFF).toLong() shl 8) or
            (hdr[3].toInt() and 0xFF).toLong()
        if (size == 1L || size == 0L || size > 16L * 1024 * 1024) return null
        val box = ByteArray(size.toInt())
        System.arraycopy(hdr, 0, box, 0, 8)
        var off = 8
        while (off < box.size) {
            val n = upstream.read(box, off, box.size - off)
            if (n < 0) return null
            off += n
        }
        return box
    }

    // ---- AES-128-CTR keystream（8B nonce + 8B 大端计数器，逐块 ECB update） ----

    private val ecbCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = ByteArray(16)
    private val scratch = ByteArray(16)

    /** 大端计数器 +1（低 8B 为计数器段）。 */
    private fun incCounter() {
        for (i in 15 downTo 8) {
            val v = (counter[i].toInt() and 0xFF) + 1
            counter[i] = v.toByte()
            if (v <= 255) return
        }
    }

    /** 计数器直接跳 n 块（nonce 后 8B 计数器段大数加法）。 */
    private fun addBlock(n: Int) {
        var carry = n.toLong()
        for (i in 15 downTo 8) {
            carry += (counter[i].toInt() and 0xFF).toLong()
            counter[i] = (carry and 0xFF).toByte()
            carry = carry ushr 8
            if (carry == 0L) return
        }
    }

    /** 解密 buffer[off, off+len)（对应流位置 start）：与加密区间求交，逐 16B 块还原。 */
    private fun decryptRegion(buffer: ByteArray, off: Int, len: Int, start: Long) {
        if (ranges.isEmpty()) return
        var lo = 0
        var hi = ranges.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ranges[mid].end > start) hi = mid else lo = mid + 1
        }
        var i = lo
        var cur = start
        val endPos = start + len
        while (i < ranges.size && ranges[i].start < endPos) {
            val r = ranges[i]
            if (r.end > cur) {
                val s = maxOf(cur, r.start)
                val e = minOf(endPos, r.end)
                if (s < e) decryptRange(buffer, off + (s - start).toInt(), (e - s).toInt(), r, s)
                cur = e
            }
            i++
        }
    }

    /** 单区间连续段：按 16B 块切，每块 counter = iv + (块在样本内的序号)。 */
    private fun decryptRange(
        buffer: ByteArray, off: Int, len: Int,
        r: HongguoMp4Decrypt.EncryptedRange, streamPos: Long
    ) {
        val relBlock = ((streamPos - r.start) / 16).toInt()
        val inBlock = ((streamPos - r.start) % 16).toInt()
        java.util.Arrays.fill(counter, 0)
        System.arraycopy(r.iv, 0, counter, 0, 8)
        addBlock(relBlock)
        var p = off
        var rem = len
        var skip = inBlock
        while (rem > 0) {
            ecbCipher.update(counter, 0, 16, scratch, 0)
            var k = skip
            while (k < 16 && rem > 0) {
                buffer[p] = (buffer[p].toInt() xor scratch[k].toInt()).toByte()
                p++; k++; rem--
            }
            skip = 0
            incCounter()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // 头部直供（moov + 预取整段携带的 mdat 前段样本——后者落在加密区间内必须解密，
        // 2026-11-18 真机教训：512KB 整段头部被当纯 moov 直吐密文，首 GOP 解码卡死）
        val h = header
        if (h != null) {
            if (headerPos < h.size) {
                val n = minOf(length, h.size - headerPos)
                System.arraycopy(h, headerPos, buffer, offset, n)
                if (pos >= 0) runCatching { decryptRegion(buffer, offset, n, pos) }
                headerPos += n
                if (headerPos >= h.size) header = null
                pos += n
                return n
            }
            header = null
        }
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            if (pos >= 0) runCatching { decryptRegion(buffer, offset, n, pos) }
            pos += n
        }
        return n
    }

    override fun getUri() = upstream.uri

    override fun close() {
        header = null
        headerPos = 0
        upstream.close()
    }
}
