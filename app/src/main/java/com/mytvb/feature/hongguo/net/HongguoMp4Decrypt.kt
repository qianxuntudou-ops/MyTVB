package com.mytvb.feature.hongguo.net

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果 CENC-AES-CTR 加密 MP4 整集解密（2026-11-10，Node hongguo.js decryptMp4Buffer 的 Kotlin 移植）。
 *
 * 加密形态：非分片 MP4；每 track 的 stbl 内嵌 senc box（sample count 与 stsz 一致、
 * 每 sample 8 字节 IV）；样本密文按 AES-128-CTR（8B nonce + 8B 零计数器）加密；
 * stsd 里 encv/enca 样本条目经 sinf/frma 包装。解密后需重建 moov（剥加密标记盒、
 * 还原原始 codec 条目、stco 重定位、moov 前移到 mdat 之前）供常规播放器直接播放。
 */
internal object HongguoMp4Decrypt {

    class DecryptException(message: String) : IllegalStateException(message)

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta")

    class Box(val typ: String, val off: Int, val size: Int, val hdr: Int, var children: MutableList<Box>?)

    private fun type(data: ByteArray, off: Int): String =
        String(data, off, 4, Charsets.ISO_8859_1)

    private fun u32(data: ByteArray, off: Int): Int {
        return ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

    private fun put32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte(); buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte(); buf[off + 3] = v.toByte()
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int, depth: Int = 0): MutableList<Box> {
        if (depth > 32) throw DecryptException("MP4 box 嵌套超限")
        val boxes = mutableListOf<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = u32(data, off)
            val typ = type(data, off + 4)
            var hdr = 8
            if (size == 1) {
                if (off + 16 > end) throw DecryptException("MP4 box 头截断")
                val hi = u32(data, off + 8).toLong() and 0xFFFFFFFFL
                val lo = u32(data, off + 12).toLong() and 0xFFFFFFFFL
                size = ((hi shl 32) or lo).toInt()
                hdr = 16
            } else if (size == 0) {
                size = end - off
            }
            if (size < hdr || off.toLong() + size > end) throw DecryptException("MP4 box 尺寸无效: $typ")
            val children = if (typ in CONTAINERS) parseBoxes(data, off + hdr, off + size, depth + 1) else null
            boxes.add(Box(typ, off, size, hdr, children))
            off += size
        }
        if (off != end) throw DecryptException("MP4 box 截断")
        return boxes
    }

    private fun find(boxes: List<Box>?, typ: String): Box? = boxes?.firstOrNull { it.typ == typ }

    private fun walk(boxes: List<Box>, out: MutableList<Box> = mutableListOf()): MutableList<Box> {
        for (b in boxes) {
            b.children?.let { walk(it, out) }
            out.add(b)
        }
        return out
    }

    private fun hasSampleEncryption(boxes: List<Box>): Boolean =
        boxes.any { it.typ == "senc" || (it.children?.let { c -> hasSampleEncryption(c) } == true) }

    private class TrackInfo(val sizes: IntArray, val offs: IntArray, val ivs: Array<ByteArray>, val encrypted: Boolean)

    private fun trackInfo(data: ByteArray, top: List<Box>): List<TrackInfo> {
        if (top.any { it.typ == "moof" }) throw DecryptException("分片 MP4 暂不支持")
        val stbls = walk(top).filter { it.typ == "stbl" }
        val tracks = mutableListOf<TrackInfo>()
        var sampleBudget = 0
        for (stbl in stbls) {
            val kids = stbl.children ?: continue
            val stsz = find(kids, "stsz") ?: continue
            val stco = find(kids, "stco") ?: find(kids, "co64") ?: continue
            val stsc = find(kids, "stsc") ?: continue
            val senc = find(kids, "senc")
            val stsd = find(kids, "stsd")
            var encrypted = false
            if (stsd != null) {
                val count = u32(data, stsd.off + 12)
                var cursor = stsd.off + 16
                for (i in 0 until count) {
                    if (cursor + 8 > stsd.off + stsd.size) throw DecryptException("样本描述截断")
                    val size = u32(data, cursor)
                    if (size < 8 || cursor + size > stsd.off + stsd.size) throw DecryptException("样本描述无效")
                    val t = type(data, cursor + 4)
                    if (t == "encv" || t == "enca") encrypted = true
                    cursor += size
                }
            }
            if (encrypted && senc == null) throw DecryptException("加密轨道缺少 senc")
            val ss = u32(data, stsz.off + 12)
            val n = u32(data, stsz.off + 16)
            sampleBudget += n
            if (sampleBudget > 100_000) throw DecryptException("样本数超限")
            val sizes = IntArray(n)
            if (ss == 0) {
                if (20 + n * 4 > stsz.size) throw DecryptException("样本尺寸表无效")
                for (i in 0 until n) sizes[i] = u32(data, stsz.off + 20 + i * 4)
            } else {
                java.util.Arrays.fill(sizes, ss)
            }
            val nc = u32(data, stco.off + 12)
            val stride = if (stco.typ == "co64") 8 else 4
            if (16 + nc * stride > stco.size) throw DecryptException("chunk 偏移表无效")
            val chunkOffs = IntArray(nc)
            for (i in 0 until nc) {
                val p = stco.off + 16 + i * stride
                chunkOffs[i] = if (stride == 8) {
                    val hi = u32(data, p).toLong() and 0xFFFFFFFFL
                    val lo = u32(data, p + 4).toLong() and 0xFFFFFFFFL
                    ((hi shl 32) or lo).toInt()
                } else u32(data, p)
            }
            val ns = u32(data, stsc.off + 12)
            if (16 + ns * 12 > stsc.size) throw DecryptException("sample-to-chunk 表无效")
            val stscTab = arrayOfNulls<IntArray>(ns)
            for (i in 0 until ns) {
                stscTab[i] = intArrayOf(
                    u32(data, stsc.off + 16 + i * 12),
                    u32(data, stsc.off + 20 + i * 12),
                    u32(data, stsc.off + 24 + i * 12)
                )
            }
            val ivs = mutableListOf<ByteArray>()
            if (senc != null) {
                val sc = u32(data, senc.off + 12)
                if (u32(data, senc.off + 8) != 0 || sc != n || senc.size != 16 + sc * 8) {
                    throw DecryptException("CENC 布局不支持（需全样本+8字节IV）")
                }
                for (i in 0 until sc) {
                    ivs.add(data.copyOfRange(senc.off + 16 + i * 8, senc.off + 24 + i * 8))
                }
            }
            if (senc == null && kids.any { it.typ == "saio" || it.typ == "saiz" }) {
                throw DecryptException("外部 CENC 辅助数据暂不支持")
            }
            if (n > 0 && (stscTab.isEmpty() || stscTab[0]!![0] != 1)) throw DecryptException("首 chunk 映射无效")
            for (i in stscTab.indices) {
                val f = stscTab[i]!![0]
                if (f < 1 || f > nc || (i > 0 && f <= stscTab[i - 1]!![0])) throw DecryptException("chunk 映射无效")
            }
            val chunkSpc = HashMap<Int, Int>()
            for (i in stscTab.indices) {
                val fc = stscTab[i]!![0] - 1
                val spc = stscTab[i]!![1]
                val nxt = if (i + 1 < stscTab.size) stscTab[i + 1]!![0] - 1 else nc
                for (ci in fc until nxt) chunkSpc[ci] = spc
            }
            val sampleOffs = IntArray(n)
            var si = 0
            for (ci in 0 until nc) {
                var off = chunkOffs[ci]
                val spc = chunkSpc[ci] ?: 1
                for (s in 0 until spc) {
                    if (si >= n) break
                    sampleOffs[si] = off
                    off += sizes[si]
                    si++
                }
            }
            if (si != n) throw DecryptException("sample-to-chunk 数量不匹配")
            tracks.add(TrackInfo(sizes, sampleOffs, ivs.toTypedArray(), encrypted))
        }
        return tracks
    }

    /** AES-128-CTR：IV = 8B nonce + 8B 零计数器。 */
    private fun decryptSample(key: ByteArray, nonce: ByteArray, cipher: ByteArray): ByteArray {
        val iv = ByteArray(16)
        System.arraycopy(nonce, 0, iv, 0, 8)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(cipher)
    }

    /**
     * 解密并重建：返回可独立播放的 MP4 字节（moov 已剥加密盒、还原 codec、重定位、前移）。
     * 输入数组会被就地修改（样本解密），调用方传入副本。
     */
    fun decryptMp4Buffer(input: ByteArray, key: ByteArray?): ByteArray {
        val data = input
        val total = data.size
        val top = parseBoxes(data, 0, total)
        val moov = find(top, "moov") ?: throw DecryptException("MP4 缺少 moov")
        val tracks = trackInfo(data, top)
        val encryptedAny = hasSampleEncryption(top) || tracks.any { it.encrypted || it.ivs.isNotEmpty() }
        if (encryptedAny && (key == null || key.size != 16)) throw DecryptException("CENC 密钥无效")

        for (ti in tracks) {
            for (i in ti.offs.indices) {
                val off = ti.offs[i]
                val sz = ti.sizes[i]
                if (off.toLong() + sz > total) throw DecryptException("样本越界")
                if (i < ti.ivs.size && key != null) {
                    val plain = decryptSample(key, ti.ivs[i], data.copyOfRange(off, off + sz))
                    System.arraycopy(plain, 0, data, off, sz)
                }
            }
        }

        // box 顺序：moov 前移到首个 mdat 之前（流式播放友好）
        val order = top.toMutableList()
        val firstMedia = order.indexOfFirst { it.typ == "mdat" }
        if (firstMedia >= 0 && order.indexOf(moov) > firstMedia) {
            order.remove(moov)
            order.add(firstMedia, moov)
        }
        val newMoov = rebuildMoov(data, moov, top, order)
        val out = ByteArrayOutputStream(total)
        for (box in order) {
            if (box === moov) out.write(newMoov)
            else out.write(data, box.off, box.size)
        }
        return out.toByteArray()
    }

    /** 重建 moov：prune 加密标记盒、encv/enca→原始条目、stco 按新布局重定位。 */
    private fun rebuildMoov(data: ByteArray, moov: Box, top: List<Box>, order: List<Box>): ByteArray {
        fun clone(box: Box): Box = Box(box.typ, box.off, box.size, box.hdr, box.children?.map { clone(it) }?.toMutableList())
        fun prune(box: Box): Box {
            val kids = box.children ?: return box
            val keep = mutableListOf<Box>()
            for (c in kids) {
                if (c.typ in setOf("senc", "saio", "saiz", "sgpd", "sbgp")) continue
                keep.add(prune(c))
            }
            box.children = keep
            return box
        }
        val tree = prune(clone(moov))
        val patches = HashMap<Int, ByteArray>()

        for (b in walk(listOf(tree))) {
            if (b.typ != "stsd") continue
            val content = b.off + 8
            val count = u32(data, content + 4)
            var p = content + 8
            val entries = mutableListOf<ByteArray>()
            for (i in 0 until count) {
                val esize = u32(data, p)
                val etyp = type(data, p + 4)
                if (etyp == "encv" || etyp == "enca") {
                    val hdrSize = if (etyp == "encv") 78 else 28
                    val children = parseBoxes(data, p + 8 + hdrSize, p + esize)
                    val sinf = find(children, "sinf")
                    val frma = sinf?.let { find(parseBoxes(data, it.off + it.hdr, it.off + it.size), "frma") }
                    val original = if (frma != null && frma.size == frma.hdr + 4) type(data, frma.off + frma.hdr) else null
                    var newType = original
                    if (etyp == "enca") {
                        if (u16(data, p + 16) != 0) throw DecryptException("音频条目版本不支持")
                        if (newType !in setOf("mp4a", "ac-3", "ec-3", "Opus", "fLaC")) {
                            throw DecryptException("受保护音频编码不支持")
                        }
                    } else {
                        val avc = children.any { it.typ == "avcC" }
                        val hevc = children.any { it.typ == "hvcC" }
                        if (newType == null) newType = when {
                            avc && !hevc -> "avc1"; hevc && !avc -> "hvc1"; else -> null
                        }
                        if (newType !in setOf("avc1", "avc3", "hvc1", "hev1") ||
                            (if (newType!!.startsWith("avc")) !avc || hevc else !hevc || avc)
                        ) throw DecryptException("无法确定加密视频原始编码")
                    }
                    // 新条目头（原样复制固定段）+ 原 sinf 之外子盒
                    val entry = ByteArray(8 + hdrSize)
                    put32(entry, 0, 0)
                    System.arraycopy(newType!!.toByteArray(Charsets.ISO_8859_1), 0, entry, 4, 4)
                    System.arraycopy(data, p + 8, entry, 8, hdrSize)
                    val extras = children.filter { it.typ != "sinf" }.map { data.copyOfRange(it.off, it.off + it.size) }
                    val full = ByteArrayOutputStream()
                    full.write(entry)
                    for (e in extras) full.write(e)
                    val fullBytes = full.toByteArray()
                    put32(fullBytes, 0, fullBytes.size)
                    entries.add(fullBytes)
                } else {
                    entries.add(data.copyOfRange(p, p + esize))
                }
                p += esize
            }
            val stsdBytes = ByteArrayOutputStream()
            stsdBytes.write(data, b.off, content + 8 - b.off)
            for (e in entries) stsdBytes.write(e)
            val fullStsd = stsdBytes.toByteArray()
            put32(fullStsd, 0, fullStsd.size)
            put32(fullStsd, 12, entries.size)
            patches[b.off] = fullStsd
        }

        fun serialize(box: Box): ByteArray {
            val kids = box.children
            if (kids == null) {
                return patches[box.off] ?: data.copyOfRange(box.off, box.off + box.size)
            }
            val body = ByteArrayOutputStream()
            for (c in kids) body.write(serialize(c))
            val bodyBytes = body.toByteArray()
            val hdrb = ByteArray(box.hdr)
            System.arraycopy(data, box.off, hdrb, 0, box.hdr)
            if (box.hdr == 8) {
                put32(hdrb, 0, 8 + bodyBytes.size)
            } else {
                put32(hdrb, 0, 1)
                val v = (16 + bodyBytes.size).toLong()
                for (i in 0 until 8) hdrb[8 + i] = (v ushr ((7 - i) * 8)).toByte()
            }
            val out = ByteArrayOutputStream()
            out.write(hdrb)
            out.write(bodyBytes)
            return out.toByteArray()
        }

        val first = serialize(tree)
        // 各顶层 box 的新起始位置
        val destinations = HashMap<Int, Int>()
        var destination = 0
        for (box in order) {
            destinations[box.off] = destination
            destination += if (box === moov) first.size else box.size
        }
        // stco/co64 重定位
        for (b in walk(listOf(tree))) {
            if (b.typ != "stco" && b.typ != "co64") continue
            val count = u32(data, b.off + b.hdr + 4)
            val stride = if (b.typ == "co64") 8 else 4
            if (b.hdr + 8 + count * stride > b.size) throw DecryptException("chunk 表无效")
            for (i in 0 until count) {
                val position = b.off + b.hdr + 8 + i * stride
                val old = if (stride == 8) {
                    val hi = u32(data, position).toLong() and 0xFFFFFFFFL
                    val lo = u32(data, position + 4).toLong() and 0xFFFFFFFFL
                    ((hi shl 32) or lo).toInt()
                } else u32(data, position)
                val source = top.firstOrNull { box -> old >= box.off && old < box.off + box.size }
                    ?: throw DecryptException("chunk 偏移越界")
                val updated = old + (destinations[source.off] ?: 0) - source.off
                if (stride == 8) {
                    val v = updated.toLong()
                    for (k in 0 until 8) data[position + k] = (v ushr ((7 - k) * 8)).toByte()
                } else put32(data, position, updated)
            }
        }
        // stco 就地重定位后再序列化（对齐 Node：return serialize(tree) 在重定位之后）
        return serialize(tree)
    }
}
