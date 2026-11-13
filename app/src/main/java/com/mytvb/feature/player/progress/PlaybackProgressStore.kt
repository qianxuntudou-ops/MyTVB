package com.mytvb.feature.player.progress

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放进度本地双 key 存储：
 * - `"bvid#cid"` 精确键：播放器内按分 P 区分，续播读取优先；
 * - `"bvid"` 兜底键：UP 空间等拿不到 cid 的入口显示进度用。
 *
 * 写入/删除成对维护；超过 95% 视为看完在 save 路径直接双 key 清除，
 * 读取侧无需再特判"看完"。调用方集中在主线程（播放心跳 tick / 列表装配）。
 */
object PlaybackProgressStore {

    private const val PREFS_NAME = "video_progress"
    private const val MIN_PROGRESS_TO_SAVE_MS = 5_000L
    private const val MAX_PERCENT_TO_RESTORE = 0.95f
    // 每视频最多 2 个 key，4096 条可覆盖极长观看历史
    private const val MAX_CACHED_ENTRIES = 4096

    private data class ProgressEntry(val positionMs: Long, val savedAtMs: Long)

    private var prefs: SharedPreferences? = null
    private val memoryCache = ConcurrentHashMap<String, ProgressEntry>()

    /** Application 启动时同步装载（文件小、毫秒级），未 init 时读写均安全 no-op。 */
    fun init(context: Context) {
        if (prefs != null) return
        attach(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    internal fun attach(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
        memoryCache.clear()
        // savedAtMs 只用于淘汰排序不落盘；装载顺序即写入顺序
        var loadOrder = 0L
        sharedPreferences.all.forEach { (key, value) ->
            val positionMs = (value as? Long) ?: return@forEach
            memoryCache[key] = ProgressEntry(positionMs, loadOrder++)
        }
    }

    fun savePosition(bvid: String, cid: Long, positionMs: Long, durationMs: Long) {
        if (bvid.isBlank() || positionMs < MIN_PROGRESS_TO_SAVE_MS) return
        if (durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() > MAX_PERCENT_TO_RESTORE) {
            // 视为看完：双 key 一起清除，卡片不再显示、续播归零
            clearPosition(bvid, cid)
            return
        }
        val now = System.currentTimeMillis()
        val exactKey = exactKey(bvid, cid)
        val fallbackKey = bvid
        if (cid > 0) {
            memoryCache[exactKey] = ProgressEntry(positionMs, now)
            memoryCache[fallbackKey] = ProgressEntry(positionMs, now)
        } else {
            memoryCache[fallbackKey] = ProgressEntry(positionMs, now)
        }
        evictOldestIfNeeded()
        val editor = prefs?.edit() ?: return
        editor.putLong(fallbackKey, positionMs)
        if (cid > 0) editor.putLong(exactKey, positionMs)
        editor.apply()
    }

    /**
     * cid>0 时只查精确键，miss 即无进度返回 0；cid=0（UP 空间等拿不到 cid 的入口）用 bvid 兜底键。
     * 精确键 miss 不得回落兜底键：多P稿件所有分P共用 bvid，savePath 会把最后观看分P的进度
     * 写进兜底键，回落会把旧分P进度串给从未看过的分P（issue#112：切集起播跳到上一集位置）。
     */
    fun getPosition(bvid: String, cid: Long = 0L): Long {
        if (bvid.isBlank()) return 0L
        if (cid > 0) {
            return memoryCache[exactKey(bvid, cid)]?.positionMs ?: 0L
        }
        return memoryCache[bvid]?.positionMs ?: 0L
    }

    fun clearPosition(bvid: String, cid: Long = 0L) {
        memoryCache.remove(bvid)
        val editor = prefs?.edit() ?: run {
            return
        }
        editor.remove(bvid)
        if (cid > 0) {
            memoryCache.remove(exactKey(bvid, cid))
            editor.remove(exactKey(bvid, cid))
        }
        editor.apply()
    }

    /** 超上限时按写入时间淘汰最旧条目（读不刷新时间戳，非严格 LRU）。 */
    private fun evictOldestIfNeeded() {
        val overflow = memoryCache.size - MAX_CACHED_ENTRIES
        if (overflow <= 0) return
        val evictKeys = memoryCache.entries
            .sortedBy { it.value.savedAtMs }
            .take(overflow)
            .map { it.key }
        val editor = prefs?.edit()
        evictKeys.forEach { key ->
            memoryCache.remove(key)
            editor?.remove(key)
        }
        editor?.apply()
    }

    private fun exactKey(bvid: String, cid: Long): String = "$bvid#$cid"
}
