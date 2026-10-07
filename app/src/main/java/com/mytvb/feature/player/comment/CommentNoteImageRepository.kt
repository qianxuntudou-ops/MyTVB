package com.mytvb.feature.player.comment

import android.util.LruCache
import com.mytvb.core.common.log.AppLog
import com.mytvb.network.BiliClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class CommentNoteImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * 笔记类评论（note_cvid）的图片懒加载：GET /x/note/publish/info，
 * 从 content 中抽前 3 张图；LruCache + waiters 去重合流。
 *
 * 对齐 blbl.cat3399.core.note.NoteImageRepository。
 */
internal object CommentNoteImageRepository {
    private const val TAG = "CommentNoteImageRepo"
    private const val MAX_CACHE_ITEMS = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private val cache =
        object : LruCache<Long, List<CommentNoteImage>>(MAX_CACHE_ITEMS) {
            override fun sizeOf(key: Long, value: List<CommentNoteImage>): Int = 1
        }

    private val inFlight: ConcurrentHashMap<Long, Job> = ConcurrentHashMap()
    private val waiters: ConcurrentHashMap<Long, MutableList<(List<CommentNoteImage>) -> Unit>> = ConcurrentHashMap()

    fun load(cvid: Long, onResult: (List<CommentNoteImage>) -> Unit) {
        val safe = cvid.takeIf { it > 0 } ?: run {
            onResult(emptyList())
            return
        }

        synchronized(lock) {
            val cached = cache.get(safe)
            if (cached != null) {
                onResult(cached)
                return
            }
        }

        synchronized(lock) {
            val list = waiters[safe]
            if (list != null) {
                list.add(onResult)
            } else {
                waiters[safe] = mutableListOf(onResult)
            }

            if (inFlight[safe]?.isActive == true) return

            val job =
                scope.launch {
                    val images =
                        runCatching {
                            withContext(Dispatchers.IO) {
                                fetchNoteImages(cvid = safe)
                            }
                        }.onFailure { t ->
                            AppLog.w(TAG, "load failed cvid=$safe", t)
                        }.getOrDefault(emptyList())

                    synchronized(lock) {
                        cache.put(safe, images)
                    }

                    val callbacks =
                        synchronized(lock) {
                            val out = waiters.remove(safe).orEmpty().toList()
                            inFlight.remove(safe)
                            out
                        }
                    callbacks.forEach { cb ->
                        runCatching { cb(images) }
                    }
                }

            inFlight[safe] = job
        }
    }

    private suspend fun fetchNoteImages(cvid: Long): List<CommentNoteImage> {
        val url =
            BiliClient.buildUrl(
                "https://api.bilibili.com/x/note/publish/info",
                mapOf("cvid" to cvid.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            AppLog.w(TAG, "note publish info failed cvid=$cvid code=$code msg=$msg")
            return emptyList()
        }

        val data = json.optJSONObject("data") ?: JSONObject()
        val content = data.optString("content", "").trim()
        return extractFirstImagesFromContent(content, limit = 3)
    }

    private fun extractFirstImagesFromContent(content: String, limit: Int): List<CommentNoteImage> {
        if (content.isBlank() || limit <= 0) return emptyList()
        if (!content.startsWith("[")) return emptyList()

        val arr = runCatching { JSONArray(content) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CommentNoteImage>(minOf(limit, 3))
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val obj = arr.optJSONObject(i) ?: continue
            val insert = obj.optJSONObject("insert") ?: continue
            val img = insert.optJSONObject("imageUpload") ?: continue
            val rawUrl = img.optString("url", "").trim()
            val url =
                when {
                    rawUrl.startsWith("http") -> rawUrl
                    rawUrl.startsWith("//") -> "https:$rawUrl"
                    else -> continue
                }
            if (url.isBlank()) continue
            if (out.any { it.url == url }) continue
            out.add(
                CommentNoteImage(
                    url = url,
                    width = img.optInt("width", 0).takeIf { it > 0 },
                    height = img.optInt("height", 0).takeIf { it > 0 },
                ),
            )
        }
        return out
    }
}
