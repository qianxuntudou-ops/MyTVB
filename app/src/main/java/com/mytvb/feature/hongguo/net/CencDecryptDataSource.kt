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

    /** 详细日志去重标志（2026-11-24 排查 Source error）：EOF / 解密异常只在首次打，
     *  除非换源（open 重置）——read 是每帧热路径，不能刷屏。 */
    private var loggedEof = false
    private var decryptFailCount = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        pos = dataSpec.position
        header = null
        headerPos = 0
        loggedEof = false
        // 防「上一源的区间表残留」错位解密（2026-11-18 真机教训：换源后 ranges 未
        // 重置，旧样本布局解新数据=必错位）：每次 open 清零，解析失败即抛错走整集兜底
        ranges = emptyList()
        val url = dataSpec.uri.toString()
        if (dataSpec.position == 0L && tryOpenWithHead(url, dataSpec)) {
            val total = headLen() + tailLen
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo",
                "soft-decrypt open=0 head=${headLen()}B tail=$tailLen total=$total " +
                    "ranges=${ranges.size} key=$cacheKey"
            )
            return total
        }
        // position>0：media3 seek / CDN 断流后的 Range 续传走这里（数据源工厂每次 new 实例，
        // ranges 已清零，tryOpenWithHead 只服务 position==0）——**加密图必须在此重建**
        // （2026-11-24 修复：此前续传段 ranges 恒空、密文透传，实测在 10.7MB 处连续重开
        // 7 次后 Mp4Extractor 抛 Invalid NAL length → Source error，position≈119s）。
        ensureRangesForSeek(dataSpec)
        val len = openUpstream(url, dataSpec)
        com.mytvb.core.common.log.AppLog.d(
            "Hongguo",
            "soft-decrypt open=${dataSpec.position} len=$len ranges=${ranges.size} key=$cacheKey"
        )
        return len
    }

    /**
     * 续传/seek 重开时的加密图重建（2026-11-24）：头部缓存优先（预取与首播已落盘，
     * 同集同片源数据不变），miss 时按 Range 读一次 ftyp+moov 再关连接；两者都失败
     * 直接抛 IOException 走整集下载兜底——密文透传给 codec 只会得到无信息量的
     * "Invalid NAL length"，不如在这里就报出真实原因。
     */
    private fun ensureRangesForSeek(dataSpec: DataSpec) {
        val cached = runCatching { HongguoHeadCache.load(cacheKey) }.getOrNull()
        if (cached != null && tryParseRanges(cached.bytes)) {
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo", "soft-decrypt seek ranges from cache=${ranges.size} key=$cacheKey"
            )
            return
        }
        var headError: Throwable? = null
        val raw = try {
            upstream.open(dataSpec.buildUpon().setPosition(0L).setLength(-1L).build())
            readHeadBoxes()
        } catch (e: Throwable) {
            headError = e
            null
        }
        runCatching { upstream.close() }
        if (raw != null && tryParseRanges(raw)) {
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo", "soft-decrypt seek ranges from net=${ranges.size} key=$cacheKey"
            )
            return
        }
        throw IOException(
            "soft-decrypt ranges unavailable at ${dataSpec.position} " +
                "(cache=${cached?.bytes?.size ?: 0}B net=${raw?.size ?: 0}B " +
                "err=${headError?.javaClass?.simpleName}:${headError?.message})"
        )
    }

    private var tailLen = -1L

    private fun headLen(): Long = header?.size?.toLong() ?: 0L

    /** upstream open 统一入口：403/404 = 签名地址过期，顺手清头部缓存再抛。 */
    private fun openUpstream(url: String, spec: DataSpec): Long = try {
        upstream.open(spec)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        // 地址过期/被拒的现场：位置与 Range 长度是判断「首段就挂」还是「续传挂」的关键
        com.mytvb.core.common.log.AppLog.w(
            "Hongguo",
            "soft-decrypt upstream http=${e.responseCode} pos=${spec.position} len=${spec.length} key=$cacheKey"
        )
        if (e.responseCode == 403 || e.responseCode == 404) HongguoHeadCache.drop(cacheKey)
        throw e
    } catch (e: HttpDataSource.HttpDataSourceException) {
        com.mytvb.core.common.log.AppLog.w(
            "Hongguo",
            "soft-decrypt upstream io type=${e.type} pos=${spec.position} len=${spec.length} " +
                "msg=${e.message} cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message} key=$cacheKey"
        )
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
        if (raw != null) {
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo",
                "soft-decrypt head cache hit ${raw.size}B total=${cached.totalLen} key=$cacheKey"
            )
        } else {
            // 网络预读 ftyp+moov（临时连接，读完即关）
            var headError: Throwable? = null
            raw = try {
                upstream.open(dataSpec)
                readHeadBoxes()
            } catch (e: Throwable) {
                headError = e
                null
            }
            runCatching { upstream.close() }
            if (raw == null) {
                // 预读失败：不静默（此前 runCatching 吞掉原因，Source error 排查无从下手）
                com.mytvb.core.common.log.AppLog.w(
                    "Hongguo",
                    "soft-decrypt head read fail (net) " +
                        "${headError?.javaClass?.simpleName}:${headError?.message} → 全网络透传 key=$cacheKey"
                )
                return false
            }
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo", "soft-decrypt head read from net ${raw.size}B key=$cacheKey"
            )
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
            // 注意：open 返回 headLen()+tailLen，len2<=0 时 it 会算出「头部长度-1」这种
            // 假总长（2026-11-24 排查标记，先记录实际取值再决定修法）
            com.mytvb.core.common.log.AppLog.w(
                "Hongguo",
                "soft-decrypt upstream len=$len2 (未知/0)，头部直供 head=${raw.size}B key=$cacheKey"
            )
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
    }.onFailure {
        // 形态意外（moov 找不到/box 尺寸无效）：记清原因，上一级抛 IOException 走整集兜底
        com.mytvb.core.common.log.AppLog.w(
            "Hongguo",
            "soft-decrypt ranges parse fail ${it.javaClass.simpleName}: ${it.message} head=${head.size}B key=$cacheKey"
        )
    }.isSuccess

    /** 从 upstream（已 open）顺序读 ftyp..moov 完整链。 */
    private fun readHeadBoxes(): ByteArray? {
        val boxes = ArrayList<ByteArray>(2)
        for (i in 0 until 8) {
            val box = readBox() ?: run {
                com.mytvb.core.common.log.AppLog.w(
                    "Hongguo", "soft-decrypt head box fail at #$i key=$cacheKey"
                )
                return null
            }
            if (box.size >= 4 && String(box, 4, 4, Charsets.US_ASCII) == "moov") {
                boxes.add(box); break
            }
            boxes.add(box)
        }
        if (String(boxes.last(), 4, 4, Charsets.US_ASCII) != "moov") {
            com.mytvb.core.common.log.AppLog.w(
                "Hongguo",
                "soft-decrypt head no moov in ${boxes.size} boxes last=${String(boxes.last(), 4, 4, Charsets.US_ASCII)} key=$cacheKey"
            )
            return null
        }
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
            if (n < 0) {
                com.mytvb.core.common.log.AppLog.w(
                    "Hongguo", "soft-decrypt box header eof got=$got/8 key=$cacheKey"
                )
                return null
            }
            got += n
        }
        val size = ((hdr[0].toInt() and 0xFF).toLong() shl 24) or
            ((hdr[1].toInt() and 0xFF).toLong() shl 16) or
            ((hdr[2].toInt() and 0xFF).toLong() shl 8) or
            (hdr[3].toInt() and 0xFF).toLong()
        if (size == 1L || size == 0L || size > 16L * 1024 * 1024) {
            com.mytvb.core.common.log.AppLog.w(
                "Hongguo", "soft-decrypt box size invalid size=$size key=$cacheKey"
            )
            return null
        }
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
                if (pos >= 0) decryptQuietly(buffer, offset, n, pos)
                headerPos += n
                if (headerPos >= h.size) header = null
                pos += n
                return n
            }
            header = null
        }
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            if (pos >= 0) decryptQuietly(buffer, offset, n, pos)
            pos += n
        } else if (n < 0 && !loggedEof) {
            // EOF 现场：位置 vs 总长能直接区分「播完正常结束」和「中途断流被判结束」
            loggedEof = true
            com.mytvb.core.common.log.AppLog.d(
                "Hongguo",
                "soft-decrypt eof pos=$pos ranges=${ranges.size} tailLen=$tailLen key=$cacheKey"
            )
        }
        return n
    }

    /** [decryptRegion] 的守护版：解密抛错记现场（位置/长度/区间数），控制流保持历史行为
     *  （静默不抛）——失败样本会原样喂给 codec，这条日志是唯一定位线索（2026-11-24）。 */
    private fun decryptQuietly(buffer: ByteArray, offset: Int, len: Int, start: Long) {
        try {
            decryptRegion(buffer, offset, len, start)
        } catch (e: Throwable) {
            if (decryptFailCount++ == 0) {
                com.mytvb.core.common.log.AppLog.w(
                    "Hongguo",
                    "soft-decrypt decrypt fail pos=$start len=$len ranges=${ranges.size} " +
                        "${e.javaClass.simpleName}: ${e.message} key=$cacheKey"
                )
            }
        }
    }

    override fun getUri() = upstream.uri

    override fun close() {
        header = null
        headerPos = 0
        upstream.close()
    }
}
