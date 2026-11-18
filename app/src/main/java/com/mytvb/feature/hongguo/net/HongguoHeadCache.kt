package com.mytvb.feature.hongguo.net

import android.content.Context
import com.mytvb.core.common.log.AppLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * CENC 流头部磁盘缓存（2026-11-18 P2，对齐 HGTV 1.3.7.9 反编译的头部落盘机制——
 * C0174j3.c() 的魔数+总长+头部格式同款思路）：
 * 存「ftyp+moov 原始字节 + 流总长」，命中时 CencPsshDataSource 免去起播关键路径上的
 * 头部网络下载（红果 moov 实测 122KB 量级，慢网 ~370ms），切集/重进同集即点即开。
 *
 * - 文件名 = sha256(url) 前 16 hex（url 换签后天然不命中，旧条目由 LRU 修剪回收）；
 * - 格式：魔数 0x48475456("HGTV") + version 1 + totalLen + headLen + headBytes
 *   （totalLen 来自预取响应 Content-Range；读侧用它校验「流未变」，对齐 HGTV 的
 *   VideoChangedException 防御——总长不符即弃缓存走网络）；
 * - 上限 64MB LRU（按 lastModified），挂 GlobalCacheBudget 总账。
 */
object HongguoHeadCache {

    private const val MAGIC = 0x48475456 // "HGTV"（对齐参考实现魔数）
    private const val VERSION = 1
    private const val DIR_NAME = "hongguo_heads"
    private const val MAX_BYTES = 64L * 1024 * 1024
    /** 预取单次拉取上限（HGTV 下集预取 1MB / 当前集 512KB，红果 moov ~122KB 取中庸）。 */
    const val PREFETCH_LIMIT = 512L * 1024

    /** CDN 直链下载 UA（Repository 整集/头部预取与播放器 HttpDataSource 共用同款，
     *  实证可用于红果视频 CDN）。 */
    const val CDN_UA =
        "com.phoenix.read/71332 (Linux; U; Android 15; zh_CN; 2211133C; Build/V417IR)"

    @Volatile
    private var dirRef: File? = null

    /** 目录唯一来源（播放器 DataSource 工厂与 Repository 预取都先走这里）。 */
    fun ensureDir(context: Context): File =
        dirRef ?: File(context.applicationContext.cacheDir, DIR_NAME).apply {
            mkdirs()
            com.mytvb.core.common.cache.GlobalCacheBudget.register(this)
            dirRef = this
        }

    class Head(val totalLen: Long, val bytes: ByteArray)

    private fun fileFor(url: String): File? {
        val dir = dirRef ?: return null
        val d = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val name = d.joinToString("") { "%02x".format(it) }.take(16) + ".head"
        return File(dir, name)
    }

    fun load(url: String): Head? = runCatching {
        val f = fileFor(url) ?: return null
        if (!f.isFile) return null
        f.inputStream().use { ins ->
            val din = java.io.DataInputStream(ins)
            if (din.readInt() != MAGIC || din.readInt() != VERSION) return null
            val totalLen = din.readLong()
            val headLen = din.readInt()
            if (headLen <= 0 || headLen > 16L * 1024 * 1024) return null
            val bytes = ByteArray(headLen)
            din.readFully(bytes)
            Head(totalLen, bytes)
        }
    }.getOrNull()

    /** 原子写（.tmp+rename）；写完触发 LRU 修剪（节流在 GlobalCacheBudget 内部）。 */
    fun save(url: String, totalLen: Long, headBytes: ByteArray) {
        runCatching {
            val dir = dirRef ?: return
            val f = fileFor(url) ?: return
            val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)).use {
                it.writeInt(MAGIC)
                it.writeInt(VERSION)
                it.writeLong(totalLen)
                it.writeInt(headBytes.size)
                it.write(headBytes)
            }
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return
            }
            enforce(dir)
        }.onFailure { AppLog.d("Hongguo", "head cache save fail: ${it.message}") }
    }

    fun drop(url: String) {
        runCatching { fileFor(url)?.delete() }
    }

    /** 64MB LRU 修剪（刚写的最新天然存活）。 */
    private fun enforce(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        com.mytvb.core.common.cache.GlobalCacheBudget.kick()
    }

    /**
     * 头部字节里扫描顶层 box 链，返回 ftyp..moov 结束的长度（moov 不在头部内返回 -1；
     * size=0/1/超大等形态意外返回 -1，调用方放弃缓存）。
     */
    fun headEndOf(bytes: ByteArray): Int {
        var off = 0
        var moovEnd = -1
        while (off + 8 <= bytes.size) {
            val size = ((bytes[off].toInt() and 0xFF).toLong() shl 24) or
                ((bytes[off + 1].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[off + 2].toInt() and 0xFF).toLong() shl 8) or
                (bytes[off + 3].toInt() and 0xFF).toLong()
            if (size < 8 || off + size > bytes.size) return -1
            val type = String(bytes, off + 4, 4, Charsets.US_ASCII)
            off += size.toInt()
            if (type == "moov") {
                moovEnd = off
                break
            }
        }
        return moovEnd
    }
}
