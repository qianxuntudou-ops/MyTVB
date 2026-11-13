package com.mytvb.core.common.cache

import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.koin.mp.KoinPlatform

/**
 * 「缓存限制」的全局总账：受管缓存目录统一核算，总占用超上限时跨目录按
 * lastModified 升序（LRU）淘汰，保证 App 缓存总量 ≤ 上限。
 *
 * 各目录管理器（FileCacheManager/PlayerMediaCache/HongguoMediaCache）自身的
 * 单目录上限是写入时即时防线，本类是跨目录兜底——语义 = 上限是 App 缓存
 * 总配额，而非每目录各自配额。
 *
 * core 层不倒挂依赖 feature：目录走注册制，Application.onCreate 注册三目录。
 *
 * 外部删除安全性：B 站分片被删后 SimpleCache 访问时按 cache miss 回源自愈；
 * FileCacheManager 账本经 [FileCacheManager.resync] 归位；淘汰跳过 60s 内
 * 有写入的文件，避开活跃写入/正在播放的缓存分片。
 */
object GlobalCacheBudget {

    private const val TAG = "CacheBudget"
    private const val KEY_CACHE_LIMIT = "cache_limit"
    private const val CACHE_SIZE_200_MB: Long = 200L * 1024L * 1024L
    private const val CACHE_SIZE_500_MB: Long = 500L * 1024L * 1024L
    private const val CACHE_SIZE_1_GB: Long = 1024L * 1024L * 1024L

    /** 连续写入时的节流窗口：10s 内只全量修剪一次。 */
    private const val THROTTLE_MS = 10_000L

    /** 活跃写入保护：mtime 距今不足该值的文件不淘汰（正在播放/写入的缓存分片）。 */
    private const val ACTIVE_WRITE_GUARD_MS = 60_000L

    private val managedDirs = CopyOnWriteArrayList<File>()
    private val lastRunAt = AtomicLong(0)
    private val lock = java.util.concurrent.locks.ReentrantLock()

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /** 受管目录注册（目录不存在也注册，统计按 0 计）。Application.onCreate 统一注册。 */
    fun register(dir: File) {
        if (managedDirs.none { it.absolutePath == dir.absolutePath }) managedDirs.add(dir)
    }

    /** 三目录当前总占用字节。 */
    fun totalBytes(): Long = managedDirs.sumOf { folderSize(it) }

    /** 写入侧触发：节流窗口内忽略，否则后台线程跑一次全局修剪（目录高频写入点调用无压力）。 */
    fun kick() {
        val now = System.currentTimeMillis()
        if (now - lastRunAt.get() < THROTTLE_MS) return
        Thread { enforce() }.apply {
            isDaemon = true
            name = "cache-budget"
        }.start()
    }

    /**
     * 全局修剪：总占用 ≤ 上限直接返回；否则跨目录按 mtime 升序删最旧直到达标。
     * force = 改「缓存上限」设置后的立即修剪（跳过节流）。
     */
    fun enforce(force: Boolean = false) {
        val limit = resolveMaxBytes() ?: return
        if (!lock.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (!force && now - lastRunAt.get() < THROTTLE_MS) return
            lastRunAt.set(now)
            val guard = now - ACTIVE_WRITE_GUARD_MS
            val files = managedDirs.flatMap { collectFiles(it, guard) }
            var total = files.sumOf { it.length() }
            if (total <= limit) return
            var deleted = 0
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= limit) break
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    deleted++
                }
            }
            // 账本归位：被删的 BBLLCache 文件在 FileCacheManager 里还有记账条目
            FileCacheManager.resync()
            AppLog.i(TAG, "enforce limit=${limit / 1048576}MB deleted=$deleted now=${total / 1048576}MB")
        } finally {
            lock.unlock()
        }
    }

    /** 递归收集目录下可淘汰文件：跳过 .uid（SimpleCache 索引标识）与 60s 内活跃写入文件。 */
    private fun collectFiles(dir: File, guardBefore: Long): List<File> {
        if (!dir.isDirectory) return emptyList()
        return try {
            dir.walkTopDown()
                .filter { it.isFile && it.name != ".uid" && it.lastModified() <= guardBefore }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun folderSize(dir: File): Long = collectFiles(dir, Long.MAX_VALUE).sumOf { it.length() }

    /** 与三个目录管理器同款换算；「不限制」返回 null（不修剪）。 */
    private fun resolveMaxBytes(): Long? = when (appSettings.getCachedString(KEY_CACHE_LIMIT)?.trim()) {
        "不限制" -> null
        "500 MB" -> CACHE_SIZE_500_MB
        "1 GB" -> CACHE_SIZE_1_GB
        else -> CACHE_SIZE_200_MB
    }
}
