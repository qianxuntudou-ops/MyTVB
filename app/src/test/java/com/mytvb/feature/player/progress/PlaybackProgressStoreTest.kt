package com.mytvb.feature.player.progress

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlaybackProgressStoreTest {

    private val fakePrefs = InMemorySharedPreferences()

    @Before
    fun setUp() {
        PlaybackProgressStore.attach(fakePrefs)
    }

    @Test
    fun saveWritesExactAndFallbackKeys() {
        PlaybackProgressStore.savePosition(
            bvid = "BV1xx", cid = 100L, positionMs = 42_000L, durationMs = 600_000L
        )

        assertEquals(42_000L, PlaybackProgressStore.getPosition("BV1xx", 100L))
        // 无 cid 入口（UP 空间卡片）读兜底键
        assertEquals(42_000L, PlaybackProgressStore.getPosition("BV1xx"))
        assertEquals(42_000L, fakePrefs.store["BV1xx"])
        assertEquals(42_000L, fakePrefs.store["BV1xx#100"])
    }

    @Test
    fun saveWithoutCidOnlyWritesFallbackKey() {
        PlaybackProgressStore.savePosition(
            bvid = "BV1yy", cid = 0L, positionMs = 20_000L, durationMs = 600_000L
        )

        assertEquals(20_000L, PlaybackProgressStore.getPosition("BV1yy"))
        assertEquals(false, fakePrefs.store.containsKey("BV1yy#0"))
    }

    @Test
    fun shortProgressIsNotSaved() {
        PlaybackProgressStore.savePosition(
            bvid = "BV1short", cid = 1L, positionMs = 4_999L, durationMs = 600_000L
        )

        assertEquals(0L, PlaybackProgressStore.getPosition("BV1short", 1L))
    }

    @Test
    fun watchedCompleteClearsBothKeys() {
        PlaybackProgressStore.savePosition(
            bvid = "BV1done", cid = 1L, positionMs = 580_000L, durationMs = 600_000L
        )

        assertEquals(0L, PlaybackProgressStore.getPosition("BV1done", 1L))
        assertEquals(0L, PlaybackProgressStore.getPosition("BV1done"))
    }

    @Test
    fun clearPositionRemovesBothKeys() {
        PlaybackProgressStore.savePosition(
            bvid = "BV1clear", cid = 1L, positionMs = 30_000L, durationMs = 600_000L
        )
        PlaybackProgressStore.clearPosition("BV1clear", 1L)

        assertEquals(0L, PlaybackProgressStore.getPosition("BV1clear", 1L))
        assertEquals(0L, PlaybackProgressStore.getPosition("BV1clear"))
    }

    @Test
    fun exactKeyPreferredOverFallbackKey() {
        // 分P1 进度 10s、分P2 进度 50s：兜底键是最后写入的 50s
        PlaybackProgressStore.savePosition("BV1p", 1L, 10_000L, 600_000L)
        PlaybackProgressStore.savePosition("BV1p", 2L, 50_000L, 600_000L)

        assertEquals(10_000L, PlaybackProgressStore.getPosition("BV1p", 1L))
        assertEquals(50_000L, PlaybackProgressStore.getPosition("BV1p", 2L))
        assertEquals(50_000L, PlaybackProgressStore.getPosition("BV1p"))
    }

    @Test
    fun unwatchedPageDoesNotInheritFallbackProgress() {
        // issue#112：P1 播到 300s（未看完）后切 P2，P2 精确键 miss 时不得经 bvid 兜底键
        // 继承 P1 进度（多P共用 bvid，兜底键存的是最后观看分P的位置）
        PlaybackProgressStore.savePosition("BV1multi", 1L, 300_000L, 600_000L)

        assertEquals(300_000L, PlaybackProgressStore.getPosition("BV1multi", 1L))
        assertEquals(0L, PlaybackProgressStore.getPosition("BV1multi", 2L))
        // cid=0 的显示入口（UP 空间卡片）仍走兜底键
        assertEquals(300_000L, PlaybackProgressStore.getPosition("BV1multi"))
    }

    @Test
    fun reloadFromPrefsRestoresProgress() {
        PlaybackProgressStore.savePosition("BV1reload", 1L, 66_000L, 600_000L)

        // 重新 attach 模拟进程重启：从 SP 装载
        PlaybackProgressStore.attach(fakePrefs)

        assertEquals(66_000L, PlaybackProgressStore.getPosition("BV1reload", 1L))
    }

    @Test
    fun unknownBvidReturnsZero() {
        assertEquals(0L, PlaybackProgressStore.getPosition("BV1none", 1L))
        assertEquals(0L, PlaybackProgressStore.getPosition(""))
    }

    /** 纯 JVM 内存 SharedPreferences（仅覆盖本 Store 用到的 Long 读写）。 */
    private class InMemorySharedPreferences : SharedPreferences {
        val store = LinkedHashMap<String, Any>()

        override fun getAll(): Map<String, *> = store
        override fun getString(key: String, defValue: String?): String? = store[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST") // 测试内存版 SharedPreferences，元素类型由写入方保证
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = store[key] as? Set<String> ?: defValues
        override fun getBoolean(key: String, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun getInt(key: String, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun contains(key: String): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()

            override fun putString(key: String, value: String?) = apply2(key, value)
            override fun putStringSet(key: String, values: Set<String>?) = apply2(key, values)
            override fun putInt(key: String, value: Int) = apply2(key, value)
            override fun putLong(key: String, value: Long) = apply2(key, value)
            override fun putFloat(key: String, value: Float) = apply2(key, value)
            override fun putBoolean(key: String, value: Boolean) = apply2(key, value)
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = REMOVE
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                store.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value === REMOVE) store.remove(key) else store[key] = value!!
                }
                pending.clear()
            }

            private fun apply2(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
        }

        private companion object {
            private val REMOVE = Any()
        }
    }
}
