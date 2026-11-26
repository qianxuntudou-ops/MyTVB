package com.mytvb.feature.search

import android.content.Context
import com.mytvb.core.common.settings.AppSettingsDataStore
import org.json.JSONArray
import org.koin.mp.KoinPlatform

class SearchHistoryStore(
    context: Context
) {

    companion object {
        private const val KEY_RECENT_SEARCH = "recentSearch"
    }

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    fun load(): List<String> {
        val value = appSettings.getCachedString(KEY_RECENT_SEARCH)
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return buildList {
            runCatching {
                val array = JSONArray(value)
                repeat(array.length()) { index ->
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }
    }

    fun clear() {
        persist(emptyList())
    }

    fun save(keyword: String, existing: List<String>): List<String> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) {
            return existing
        }

        val updated = SearchHistoryPlanner.save(normalized, existing)
        persist(updated)
        return updated
    }

    private fun persist(items: List<String>) {
        val array = JSONArray()
        items.forEach(array::put)
        appSettings.putStringBlocking(KEY_RECENT_SEARCH, array.toString())
    }
}

internal object SearchHistoryPlanner {
    private const val MAX_RECENT_SEARCHES = 10
    private const val MAX_RECENT_CHARS = 90

    fun save(keyword: String, existing: List<String>): List<String> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) {
            return existing
        }

        return existing.toMutableList().apply {
            removeAll { it == normalized }
            add(0, normalized)
            trimByTotalLength()
            trimByEntryCount()
        }
    }

    private fun MutableList<String>.trimByTotalLength() {
        var totalLength = sumOf(String::length)
        while (totalLength > MAX_RECENT_CHARS && size > 1) {
            totalLength -= removeAt(lastIndex).length
        }
    }

    private fun MutableList<String>.trimByEntryCount() {
        if (size > MAX_RECENT_SEARCHES) {
            subList(MAX_RECENT_SEARCHES, size).clear()
        }
    }
}
