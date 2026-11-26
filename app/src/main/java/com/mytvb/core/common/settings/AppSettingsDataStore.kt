package com.mytvb.core.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.os.SystemClock
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.appDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Any>()
    private val cacheInitialized = AtomicBoolean(false)
    private var initJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "AppSettingsDataStore"
    }

    /**
     * 非阻塞：启动后台协程从 DataStore 读取全部设置到 [cache]。
     * DataStore 首次 `data.first()` 在电视上约 30~80ms（磁盘 IO + XML 解析），
     * 放到后台不阻塞 app.onCreate 主线程。
     * `getCached...` 在 cache miss 时会同步 fallback，保证正确性。
     */
    fun initCache() {
        if (cacheInitialized.get()) return
        if (initJob != null) return
        synchronized(this) {
            if (cacheInitialized.get()) return
            if (initJob != null) return
            initJob = scope.launch {
                val startMs = SystemClock.elapsedRealtime()
                val prefs = dataStore.data.first()
                prefs.asMap().forEach { (key, value) ->
                    cache[key.name] = value
                }
                cacheInitialized.set(true)
                AppLog.i(TAG, "initCache async loaded keys=${cache.size} elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
            }
        }
    }

    /**
     * Application 启动阶段的显式同步初始化。这样所有 getCachedXxx 都只读内存，
     * 不再在任意页面/任意主线程 getter 中随机等待 DataStore 首读。
     */
    fun initCacheBlocking(reason: String = "startup") {
        if (cacheInitialized.get()) return
        val startMs = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (cacheInitialized.get()) return
            // BaseActivity 阶段 initCache() 可能已在后台读盘：join 它而不是取消重读，
            // 避免同一份 XML 读两次，且读盘已与其他启动步骤重叠。
            val pending = initJob
            if (pending != null) {
                runBlocking { pending.join() }
                if (cacheInitialized.get()) return
                // 异步读失败（如读盘异常）时仍走下面的同步兜底
                initJob = null
            }
            runBlocking(Dispatchers.IO) {
                val prefs = dataStore.data.first()
                prefs.asMap().forEach { (key, value) ->
                    cache[key.name] = value
                }
            }
            cacheInitialized.set(true)
        }
        AppLog.i(TAG, "initCacheBlocking reason=$reason keys=${cache.size} elapsed=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    private fun ensureCacheReady(key: String) {
        if (cacheInitialized.get()) return
        AppLog.w(TAG, "getCached($key) before cache ready on thread=${Thread.currentThread().name}; starting async cache load and returning fallback if cache miss")
        initCache()
    }

    fun getCachedString(key: String, defaultValue: String? = null): String? {
        cache[key]?.let { return it as? String ?: defaultValue }
        ensureCacheReady(key)
        return cache[key] as? String ?: defaultValue
    }

    fun getCachedInt(key: String, defaultValue: Int = 0): Int {
        val cached = cache[key]
        if (cached != null) return cached as? Int ?: defaultValue
        ensureCacheReady(key)
        return cache[key] as? Int ?: defaultValue
    }

    fun getCachedBoolean(key: String, defaultValue: Boolean = false): Boolean {
        val cached = cache[key]
        if (cached != null) return cached as? Boolean ?: defaultValue
        ensureCacheReady(key)
        return cache[key] as? Boolean ?: defaultValue
    }

    fun getCachedLong(key: String, defaultValue: Long = 0L): Long {
        val cached = cache[key]
        if (cached != null) return cached as? Long ?: defaultValue
        ensureCacheReady(key)
        return cache[key] as? Long ?: defaultValue
    }

    fun getCachedStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        @Suppress("UNCHECKED_CAST")
        cache[key]?.let { return it as? Set<String> ?: defaultValue }
        ensureCacheReady(key)
        @Suppress("UNCHECKED_CAST")
        return cache[key] as? Set<String> ?: defaultValue
    }

    suspend fun getString(key: String, defaultValue: String? = null): String? {
        cache[key]?.let { return it as? String ?: defaultValue }
        return dataStore.data.first()[stringPreferencesKey(key)]?.also { cache[key] = it } ?: defaultValue
    }

    suspend fun getInt(key: String, defaultValue: Int = 0): Int {
        val cached = cache[key]
        if (cached != null) return cached as? Int ?: defaultValue
        return (dataStore.data.first()[intPreferencesKey(key)] ?: defaultValue).also { cache[key] = it }
    }

    suspend fun getLong(key: String, defaultValue: Long = 0L): Long {
        val cached = cache[key]
        if (cached != null) return cached as? Long ?: defaultValue
        return (dataStore.data.first()[longPreferencesKey(key)] ?: defaultValue).also { cache[key] = it }
    }

    fun getStringFlow(key: String, defaultValue: String? = null): Flow<String?> {
        return dataStore.data.map { it[stringPreferencesKey(key)] ?: defaultValue }
    }

    fun getIntFlow(key: String, defaultValue: Int = 0): Flow<Int> {
        return dataStore.data.map { it[intPreferencesKey(key)] ?: defaultValue }
    }

    suspend fun putString(key: String, value: String?) {
        if (value != null) cache[key] = value else cache.remove(key)
        dataStore.edit { prefs ->
            if (value != null) prefs[stringPreferencesKey(key)] = value
            else prefs.remove(stringPreferencesKey(key))
        }
    }

    fun putStringSetBlocking(key: String, value: Set<String>) {
        cache[key] = value
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[stringSetPreferencesKey(key)] = value
            }
        }
    }

    // —— 同步落盘写入（issue#144 验收发现）——
    // 原 putXxxAsync 为「更新内存缓存 + scope.launch 异步写盘」，进程被强杀（force-stop）
    // 时协程未执行完的写会随进程蒸发，磁盘留旧值，重启后设置回退（实测切主题后立刻
    // 强杀一次保住一次丢失）。全量调用点（94 处）均为低频用户动作（设置点击、播放器
    // 画质记忆、青少年模式 15s tick 等），改为同步落盘：主线程单次阻塞 ~1-30ms 可接受，
    // 换取「写函数返回即已落盘」，彻底消除丢写窗口。

    fun putStringBlocking(key: String, value: String?) {
        if (value != null) cache[key] = value else cache.remove(key)
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                if (value != null) prefs[stringPreferencesKey(key)] = value
                else prefs.remove(stringPreferencesKey(key))
            }
        }
    }

    fun putIntBlocking(key: String, value: Int) {
        cache[key] = value
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[intPreferencesKey(key)] = value
            }
        }
    }

    fun putLongBlocking(key: String, value: Long) {
        cache[key] = value
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[longPreferencesKey(key)] = value
            }
        }
    }

    fun removeBlocking(key: String) {
        cache.remove(key)
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(key))
                prefs.remove(intPreferencesKey(key))
                prefs.remove(longPreferencesKey(key))
                prefs.remove(booleanPreferencesKey(key))
                prefs.remove(stringSetPreferencesKey(key))
            }
        }
    }

    fun putBooleanBlocking(key: String, value: Boolean) {
        cache[key] = value
        runBlocking(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[booleanPreferencesKey(key)] = value
            }
        }
    }

    suspend fun remove(key: String) {
        cache.remove(key)
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(intPreferencesKey(key))
            prefs.remove(longPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(stringSetPreferencesKey(key))
        }
    }

}
