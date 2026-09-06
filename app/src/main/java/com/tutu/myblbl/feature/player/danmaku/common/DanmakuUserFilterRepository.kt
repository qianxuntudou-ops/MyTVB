package com.tutu.myblbl.feature.player.danmaku.common

import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.network.BiliClient
import com.tutu.myblbl.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * 云端用户弹幕屏蔽仓库：拉取 B 站账号下同步的屏蔽规则（x/dm/filter/user，需登录）。
 *
 * **非阻塞语义**：[get] 永不等待网络——命中（TTL 内）返回缓存；TTL 过期返回旧值并异步刷新；
 * 无缓存返回 EMPTY 并异步刷新。代价是冷启动后首个视频的首段弹幕不带用户屏蔽
 * （后续分段/下一个视频生效），换取弹幕首帧注入不被网络阻塞。
 *
 * - 内存缓存绑定 mid + 10 分钟 TTL；换号自动失效；
 * - code=-101（登录失效）清空缓存返回 EMPTY，不再沿用陈旧规则；其他失败保留旧值。
 *
 * 对齐 blbl.cat3399 VideoApi.dmFilterUser（blbl 在起播元数据阶段同步拉，此处因处于
 * 弹幕数据关键路径改为异步刷新）。
 */
internal object DanmakuUserFilterRepository {
    private const val TAG = "DanmakuUserFilter"
    private const val CACHE_TTL_MS: Long = 10L * 60 * 1000
    private const val FILTER_URL = "https://api.bilibili.com/x/dm/filter/user"

    private data class CacheEntry(
        val mid: Long,
        val fetchedAtMs: Long,
        val filter: DanmakuUserFilter,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cache: CacheEntry? = null

    private val refreshMutex = Mutex()

    @Volatile
    private var refreshInFlight: Boolean = false

    /** 非阻塞读取：见类注释。可在任意协程上下文调用，命中缓存时零开销。 */
    suspend fun get(): DanmakuUserFilter {
        val mid = NetworkManager.currentMid() ?: return DanmakuUserFilter.EMPTY
        if (!NetworkManager.hasLoginSession()) return DanmakuUserFilter.EMPTY

        val now = System.currentTimeMillis()
        val cached = cache
        if (cached != null && cached.mid == mid) {
            if (now - cached.fetchedAtMs < CACHE_TTL_MS) return cached.filter
            // TTL 过期：旧值继续用（规则低频变化），后台刷新。
            refreshAsync(mid)
            return cached.filter
        }
        // 无缓存/换号：后台拉取，本次返回 EMPTY。
        refreshAsync(mid)
        return DanmakuUserFilter.EMPTY
    }

    private fun refreshAsync(mid: Long) {
        if (refreshInFlight) return
        scope.launch {
            refreshInFlight = true
            try {
                refreshMutex.withLock {
                    // 双检：等锁期间可能已被并发刷新完成。
                    val now = System.currentTimeMillis()
                    val current = cache
                    if (current != null && current.mid == mid && now - current.fetchedAtMs < CACHE_TTL_MS) {
                        return@withLock
                    }
                    try {
                        val filter = fetch(mid)
                        cache = CacheEntry(mid = mid, fetchedAtMs = System.currentTimeMillis(), filter = filter)
                    } catch (t: Throwable) {
                        if (t is BiliClient.ApiException && t.code == -101) {
                            // 登录失效：cookie 已过期，不沿用陈旧规则。
                            cache = null
                            AppLog.w(TAG, "dm/filter/user auth invalid, drop cached rules")
                        } else {
                            // 其他失败：保留旧缓存继续用，下批数据重试。
                            AppLog.w(TAG, "dm/filter/user failed mid=$mid", t)
                        }
                    }
                }
            } finally {
                refreshInFlight = false
            }
        }
    }

    private suspend fun fetch(mid: Long): DanmakuUserFilter {
        val json = BiliClient.getJson(FILTER_URL)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliClient.ApiException(code, msg.ifEmpty { "请求失败" }, "dm/filter/user")
        }

        val list: JSONArray = json.optJSONObject("data")?.optJSONArray("rule") ?: JSONArray()
        val keywords = ArrayList<String>(minOf(64, list.length()))
        val regexes = ArrayList<Regex>(minOf(32, list.length()))
        val blockedMidHashes = LinkedHashSet<String>()

        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val type = obj.optInt("type", -1)
            val raw = obj
                .optString("filter", obj.optString("filter_content", obj.optString("content", "")))
                .trim()
            if (raw.isBlank()) continue

            when (type) {
                0 -> keywords.add(raw)
                1 -> {
                    val r = DanmakuUserFilter.normalizeRegexRule(raw)
                    if (r == null) {
                        AppLog.w(TAG, "bad regex rule: ${raw.take(120)}")
                    } else {
                        regexes.add(r)
                    }
                }
                2 -> DanmakuUserFilter.normalizeMidHashRule(raw)?.let { blockedMidHashes.add(it) }
            }
        }

        val filter = DanmakuUserFilter(
            keywords = keywords.distinct(),
            regexes = regexes.distinctBy { it.pattern },
            blockedUserMidHashes = blockedMidHashes,
        )
        AppLog.i(TAG, "refresh ok mid=$mid keywords=${filter.keywords.size} regexes=${filter.regexes.size} users=${filter.blockedUserMidHashes.size}")
        return filter
    }
}
