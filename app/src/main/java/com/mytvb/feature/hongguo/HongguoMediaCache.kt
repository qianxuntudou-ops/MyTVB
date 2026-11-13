package com.mytvb.feature.hongguo

import android.content.Context
import java.io.File

/**
 * 红果 CENC 解密流落地缓存（cacheDir/hongguo，单集约 30MB/集）管理。
 * 与「缓存限制」设置同一口径：落地后按 lastModified 升序（LRU）修剪到上限；
 * 设置页的缓存统计/清除同走本类——此前该目录游离在 cache_limit 管辖外
 * （看百集可堆 3GB+，设置页既不显示也清不掉）。
 */
object HongguoMediaCache {

    private const val DIR_NAME = "hongguo"
    private const val KEY_CACHE_LIMIT = "cache_limit"
    private const val CACHE_SIZE_200_MB: Long = 200L * 1024L * 1024L
    private const val CACHE_SIZE_500_MB: Long = 500L * 1024L * 1024L
    private const val CACHE_SIZE_1_GB: Long = 1024L * 1024L * 1024L

    private val appSettings: com.mytvb.core.common.settings.AppSettingsDataStore by lazy {
        org.koin.mp.KoinPlatform.getKoin().get()
    }

    /** 缓存目录唯一来源（播放器落地/设置页统计清除统一走这里）。 */
    fun dir(context: Context): File =
        File(context.applicationContext.cacheDir, DIR_NAME).apply { mkdirs() }.also {
            com.mytvb.core.common.cache.GlobalCacheBudget.register(it)
        }

    fun size(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * 超限修剪：按 lastModified 升序删最旧（刚落地/正在播的当前集最新，天然存活）；
     * 顺手清下载中断残留的 .tmp。与 PlayerMediaCache 同语义：改设置不立即修剪，下次落地生效。
     * 每次落地必触发全局总配额兜底（本目录 ≤ 上限时其它目录可能已超总账）。
     */
    fun enforceLimit(context: Context) {
        val dir = dir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val limit = resolveMaxBytes()
        if (limit != null) {
            var total = files.sumOf { it.length() }
            if (total > limit) {
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= limit) break
                    val len = f.length()
                    if (f.delete()) total -= len
                }
            }
        }
        // 不限制时也触发（全局总账不设限则内部直接返回，无害）；节流在 kick 内部
        com.mytvb.core.common.cache.GlobalCacheBudget.kick()
    }

    /** 与 PlayerMediaCache/FileCacheManager 同款换算；「不限制」返回 null。 */
    private fun resolveMaxBytes(): Long? = when (appSettings.getCachedString(KEY_CACHE_LIMIT)?.trim()) {
        "不限制" -> null
        "500 MB" -> CACHE_SIZE_500_MB
        "1 GB" -> CACHE_SIZE_1_GB
        else -> CACHE_SIZE_200_MB
    }
}
