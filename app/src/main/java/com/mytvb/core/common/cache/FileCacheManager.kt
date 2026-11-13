package com.mytvb.core.common.cache

import com.mytvb.core.common.json.GsonHolder
import com.google.gson.Gson
import com.mytvb.MyBLBLApplication
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.network.http.NetworkClientFactory
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

object FileCacheManager {

    private const val KEY_CACHE_LIMIT = "cache_limit"
    private const val CACHE_SIZE_200_MB: Long = 200L * 1024L * 1024L
    private const val CACHE_SIZE_500_MB: Long = 500L * 1024L * 1024L
    private const val CACHE_SIZE_1_GB: Long = 1024L * 1024L * 1024L

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /** 缓存目录对外可见，供设置页按"受控缓存"口径统计大小。 */
    val cacheDir: File by lazy {
        File(MyBLBLApplication.instance.cacheDir, "BBLLCache").also {
            it.mkdirs()
        }
    }

    /**
     * LRU 缓存映射表，accessOrder = true 使得每次 get/put 操作都会将条目移到链表尾部。
     * 链表头部即为最久未访问的条目，evictOldest 只需移除头部，O(1) 复杂度。
     */
    private val fileMap: java.util.LinkedHashMap<File, Long> =
        java.util.LinkedHashMap(16, 0.75f, true)

    private val totalSize = AtomicLong(0)
    private val gson: Gson by lazy { GsonHolder.CONFIGURED }

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        GlobalCacheBudget.register(cacheDir)
        Thread {
            scanCacheDir()
            trimToLimit()
        }.start()
    }

    /**
     * 在 Application.onCreate 里提前将指定缓存 key 对应的文件内容读入 OS 页缓存。
     * 后续 Fragment 真正调用 getAsync 时命中页缓存，避免冷启动首次磁盘寻道延迟。
     */
    @Suppress("unused")
    fun prewarmKeys(vararg keys: String) {
        if (keys.isEmpty()) return
        Thread {
            for (key in keys) {
                try {
                    readFile(key)
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun scanCacheDir() {
        val files = cacheDir.listFiles() ?: return
        synchronized(fileMap) {
            for (file in files) {
                if (file.isFile) {
                    totalSize.addAndGet(file.length())
                    fileMap[file] = file.lastModified()
                }
            }
        }
    }

    fun <T> put(key: String, data: T) {
        init()
        try {
            val json = gson.toJson(data)
            val bytes = json.toByteArray(Charsets.UTF_8)
            writeFile(key, bytes)
            // 单目录 trimToLimit 已在 registerFile 跑过；这里是全局总配额兜底
            GlobalCacheBudget.kick()
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "put failed: key=$key", e)
        }
    }

    suspend fun <T> putAsync(key: String, data: T) {
        withContext(Dispatchers.IO) {
            put(key, data)
        }
    }

    fun <T> get(key: String, type: java.lang.reflect.Type): T? {
        init()
        try {
            val bytes = readFile(key) ?: return null
            val json = String(bytes, Charsets.UTF_8)
            // 访问时更新 LRU 顺序
            touchFile(key)
            return gson.fromJson<T>(json, type)
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "get failed: key=$key", e)
            return null
        }
    }

    suspend fun <T> getAsync(key: String, type: java.lang.reflect.Type): T? {
        return withContext(Dispatchers.IO) {
            get(key, type)
        }
    }

    private fun keyToFile(key: String): File {
        return File(cacheDir, key.hashCode().toString())
    }

    private fun writeFile(key: String, data: ByteArray) {
        val file = keyToFile(key)
        // 写入前记录旧文件大小，覆盖写入时用于准确扣减 totalSize。
        // 必须在 writeBytes 之前读取，否则拿到的是新长度。
        val oldSize = if (file.isFile) file.length() else 0L
        try {
            file.writeBytes(data)
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "writeFile failed: key=$key", e)
        }
        registerFile(file, oldSize)
    }

    private fun readFile(key: String): ByteArray? {
        val file = keyToFile(key)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            AppLog.e("FileCacheManager", "readFile failed: key=$key", e)
            null
        }
    }

    /**
     * get 命中后调用，更新 LRU 访问顺序。
     */
    private fun touchFile(key: String) {
        val file = keyToFile(key)
        synchronized(fileMap) {
            if (fileMap.containsKey(file)) {
                val now = System.currentTimeMillis()
                file.setLastModified(now)
                // LinkedHashMap accessOrder=true，put 会将条目移到尾部（最近访问）
                fileMap[file] = now
            }
        }
    }

    private fun registerFile(file: File, oldSize: Long) {
        val length = file.length()
        synchronized(fileMap) {
            val maxCacheSize = resolveMaxCacheSize()
            // 淘汰直到总大小不超限，每次最多移除一个最旧条目
            while (maxCacheSize != Long.MAX_VALUE && totalSize.get() + length - oldSize > maxCacheSize) {
                val evicted = evictOldestInternal()
                if (evicted <= 0L) {
                    break
                }
                totalSize.addAndGet(-evicted)
            }

            totalSize.addAndGet(length - oldSize)
            val now = System.currentTimeMillis()
            file.setLastModified(now)
            fileMap[file] = now
        }
    }

    /**
     * 在已持有 fileMap 锁的上下文中调用。
     * LinkedHashMap accessOrder=true，迭代顺序即访问顺序，从头开始找最久未访问的条目。
     * 正常情况第一个条目就能删掉，O(1)；delete 失败（文件被系统回收走/被外部占用）时
     * 跳过该条目继续找下一个，且绝不扣减 totalSize——文件还在磁盘上就必须继续占账面，
     * 否则每次淘汰都会重复扣同一个文件，账面虚低后淘汰停摆、磁盘占用无限增长。
     * 消失的幽灵条目（系统清了 cacheDir 但表里还有）直接移出表，不产生回收量。
     */
    private fun evictOldestInternal(): Long {
        if (fileMap.isEmpty()) return 0L
        val iterator = fileMap.entries.iterator()
        while (iterator.hasNext()) {
            val (file, _) = iterator.next()
            if (!file.exists()) {
                iterator.remove()
                continue
            }
            val length = file.length()
            if (file.delete()) {
                iterator.remove()
                return length
            }
            AppLog.w("FileCacheManager", "evict delete failed, skip: ${file.name}")
        }
        return 0L
    }

    fun remove(key: String) {
        init()
        val file = keyToFile(key)
        synchronized(fileMap) {
            if (file.exists()) {
                val length = file.length()
                if (file.delete()) {
                    fileMap.remove(file)
                    totalSize.addAndGet(-length)
                }
            }
        }
    }

    fun clearUserCaches() {
        val userCacheKeys = listOf(
            "followingAnimationCacheList",
            "followingSeriesCacheList",
            "historyCacheList",
            "watchLaterCacheList",
            "collectionCacheList"
        )
        for (key in userCacheKeys) {
            remove(key)
        }
    }

    fun clear() {
        synchronized(fileMap) {
            val files = cacheDir.listFiles() ?: return
            for (file in files) {
                // 用 deleteRecursively 兜住可能存在的子目录，避免漏删导致缓存目录无法真正归零。
                file.deleteRecursively()
            }
            fileMap.clear()
            totalSize.set(0)
        }
    }

    fun trimToLimit() {
        init()
        val maxCacheSize = resolveMaxCacheSize()
        if (maxCacheSize == Long.MAX_VALUE) {
            return
        }
        synchronized(fileMap) {
            while (totalSize.get() > maxCacheSize) {
                val evicted = evictOldestInternal()
                if (evicted <= 0L) {
                    break
                }
                totalSize.addAndGet(-evicted)
            }
        }
    }

    /**
     * 账本归位：GlobalCacheBudget 跨目录淘汰可能删掉本目录文件，但被删文件
     * 的记账条目还在表里占账面（totalSize 虚高会驱使后续淘汰多删真实文件）。
     * 全局修剪后调用：移除幽灵条目并按磁盘实况重算 totalSize。
     */
    fun resync() {
        init()
        synchronized(fileMap) {
            val iterator = fileMap.entries.iterator()
            while (iterator.hasNext()) {
                if (!iterator.next().key.exists()) iterator.remove()
            }
            totalSize.set(fileMap.keys.sumOf { it.length() })
        }
    }

    private fun resolveMaxCacheSize(): Long {
        return when (appSettings.getCachedString(KEY_CACHE_LIMIT)?.trim()) {
            "不限制" -> Long.MAX_VALUE
            "200 MB" -> CACHE_SIZE_200_MB
            "500 MB" -> CACHE_SIZE_500_MB
            "1 GB" -> CACHE_SIZE_1_GB
            else -> CACHE_SIZE_200_MB
        }
    }
}
