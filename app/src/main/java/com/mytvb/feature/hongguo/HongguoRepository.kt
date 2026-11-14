package com.mytvb.feature.hongguo

import android.content.Context
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.hongguo.net.HongguoAccount
import com.mytvb.feature.hongguo.net.HongguoClient
import com.mytvb.feature.hongguo.net.HongguoDeviceRegister
import com.mytvb.feature.hongguo.net.HongguoPlay
import com.mytvb.feature.hongguo.net.HongguoSearch
import com.mytvb.feature.hongguo.net.HongguoSession
import com.mytvb.feature.hongguo.net.HongguoSync
import com.mytvb.feature.hongguo.net.HongguoWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果数据门面：统一身份管理（真注册持久化）+ feed/详情/播放/搜索/榜单 + 账号 + 本地收藏/进度。
 * 全部 IO 走 Dispatchers.IO；红果会话独立于 B 站。
 */
class HongguoRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val session = HongguoSession(appContext)
    private val client = HongguoClient(session = session)
    private val web = HongguoWeb()
    // 设备自愈钩子（HGTV "device healed" 同款）：空响应=号被拒 → 换新号并持久化
    private val play = HongguoPlay(rotateIdentity = { registerAndSave() })
    private val search = HongguoSearch(client)
    val account = HongguoAccount(session)

    // ---- 身份（真注册，multi_video_model 需要） ----
    @Volatile
    private var cachedIdentity: HongguoDeviceRegister.Identity? = null

    /** 身份注册互斥（多接口并发首启防竞态烧号）。 */
    private val identityLock = Any()

    private fun identity(): HongguoDeviceRegister.Identity {
        cachedIdentity?.let { return it }
        session.takeIf { it.registered && it.cdid.isNotEmpty() }?.let { s ->
            HongguoDeviceRegister.Identity(
                s.deviceId, s.installId, s.cdid, s.openUdid, s.cookies,
                s.klinkEgdi, s.deviceToken, s.clientUdid
            ).also { cachedIdentity = it; return it }
        }
        return synchronized(identityLock) {
            // 双检：等锁期间其它线程可能已注册
            cachedIdentity ?: registerAndSave()
        }
    }

    /**
     * 注册新号并持久化到 session（HGTV O0/E.java registered-* 持久化复用模型）。
     * 有旧号时走身份继承重注册（registerFresh(carry)：声明旧三件套+设备指纹沿用，
     * 对照 HGTV b.java r0「device healed」的换号继承语义），首装/null 全新随机。
     */
    private fun registerAndSave(): HongguoDeviceRegister.Identity {
        val carry = cachedIdentity ?: session.takeIf { it.registered && it.cdid.isNotEmpty() }?.let { s ->
            HongguoDeviceRegister.Identity(
                s.deviceId, s.installId, s.cdid, s.openUdid, s.cookies,
                s.klinkEgdi, s.deviceToken, s.clientUdid
            )
        }
        val fresh = HongguoDeviceRegister.registerFresh(carry)
        saveIdentity(fresh)
        return fresh
    }

    private fun saveIdentity(id: HongguoDeviceRegister.Identity) {
        session.deviceId = id.deviceId
        session.installId = id.installId
        session.cdid = id.cdid
        session.openUdid = id.openUdid
        session.clientUdid = id.clientUdid
        session.cookies = id.cookies.toMutableMap()
        session.klinkEgdi = id.klinkEgdi
        session.deviceToken = id.deviceToken
        cachedIdentity = id
    }

    /** 空响应=身份被拒时轮换新注册身份重试一次（对照 Node getPlaybackDevice.reset）。 */
    private fun withIdentity(block: (HongguoDeviceRegister.Identity) -> Unit): Boolean {
        return try {
            block(identity()); true
        } catch (e: Exception) {
            // 走 AppLog：release 包 LAN 日志可见（android.util.Log 的 W 级在 release 不落任何盘）
            AppLog.w("Hongguo", "withIdentity failed: ${e.javaClass.simpleName}: ${e.message}", e)
            cachedIdentity = null
            session.clearAll()
            false
        }
    }

    // ---- 内容缓存（首屏 TTL；进程内存级，翻页不缓存） ----
    // 一进红果页 ViewPager2 全离屏会并发拉 6 个子页（推荐/分类×3/榜单/历史），
    // 冷启动又全量重拉；榜单/分类内容变化频率低，首屏缓存 TTL 内秒显，过期才真请求。
    private data class ContentEntry(val data: Any, val at: Long)

    private val contentCache = HashMap<String, ContentEntry>()
    private val contentCacheLock = Any()

    /** 缓存时长：30 分钟。 */
    private val contentTtlMs = 30L * 60_000L

    /** 详情缓存：30 分钟（对齐 HGTV），倒计时到点释放。 */
    private val DETAIL_TTL_MS = 30L * 60_000L

    @Suppress("UNCHECKED_CAST")
    private fun <T> contentCacheGet(key: String, ttlMs: Long = contentTtlMs): T? = synchronized(contentCacheLock) {
        val entry = contentCache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > ttlMs) {
            contentCache.remove(key); return null
        }
        entry.data as? T
    }

    /** 写入时顺手清扫全表过期条目：从不被 get 的条目到点也释放，缓存总量不随时间膨胀。 */
    private fun contentCachePut(key: String, data: Any) = synchronized(contentCacheLock) {
        val now = System.currentTimeMillis()
        if (contentCache.isNotEmpty()) {
            contentCache.values.removeAll { now - it.at > contentTtlMs }
        }
        contentCache[key] = ContentEntry(data, now)
    }

    // ---- feed ----
    suspend fun feed(
        path: String, offset: Int, sessionId: String,
        filters: Map<String, String> = emptyMap(), withPanel: Boolean = false
    ): HongguoClient.CategoryPage {
        // 仅首屏（offset=0 且带筛选面板）走缓存；翻页/换筛选会话照常请求
        val cacheKey = if (offset == 0 && withPanel) "feed|$path|${filters.toSortedMap()}" else null
        cacheKey?.let { contentCacheGet<HongguoClient.CategoryPage>(it)?.let { page -> return page } }
        val page = withContext(Dispatchers.IO) { client.category(path, offset, sessionId, filters, withPanel) }
        cacheKey?.let { contentCachePut(it, page) }
        return page
    }

    // ---- 榜单（四板；fqnovel 被风控时网页 SSR 兜底） ----
    suspend fun rank(boardIndex: Int, offset: Int, sessionId: String): HongguoClient.RankPage =
        withContext(Dispatchers.IO) {
            // 仅首屏走缓存；App/网页兜底两条通道的成功结果同样入缓存
            val cacheKey = if (offset == 0) "rank|$boardIndex" else null
            cacheKey?.let { contentCacheGet<HongguoClient.RankPage>(it)?.let { page -> return@withContext page } }
            try {
                client.rank(boardIndex, offset, sessionId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // App 通道空响应（模拟器传输层风控）→ 网页榜单兜底（offset 即页码）
                web.rankFromWeb(boardIndex, if (offset <= 0) 1 else offset)
            }.also { page -> cacheKey?.let { contentCachePut(it, page) } }
        }

    // ---- 搜索 ----
    /**
     * 按设置分流（存储值 hongguo_search_source："App"/"官网"，默认 App）。
     * 官网源直接走免登录分页接口（offset 即页码-1）；App 源空响应在签名通道内
     * 已轮换身份重试 3 次，全败自动回退官网一次（HGTV「App 搜索接口没有数据，
     * 已自动改用官网搜索」同款），官网也败抛原始错。
     */
    suspend fun search(
        keyword: String, offset: Int,
        cursor: HongguoSearch.SearchPage.Cursor?
    ): HongguoSearch.SearchPage = withContext(Dispatchers.IO) {
        if (appSettings.getCachedString("hongguo_search_source") == "官网") {
            return@withContext search.searchWeb(keyword, offset + 1)
        }
        try {
            search.searchTab(keyword, offset, cursor)
        } catch (e: HongguoSearch.EmptyResponseException) {
            try {
                search.searchWeb(keyword, offset + 1)
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (_: Exception) {
                throw e
            }
        }
    }

    suspend fun suggestions(keyword: String): List<String> =
        withContext(Dispatchers.IO) { search.suggestions(keyword) }

    // ---- 搜索发现（猜你想搜 + 热搜榜；匿名元数据读取，失败返回空由 UI 隐藏该区） ----
    suspend fun searchDiscovery(): HongguoClient.DiscoveryData? =
        withContext(Dispatchers.IO) {
            try {
                client.discovery()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("Hongguo", "discovery fail: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    // ---- 搜索历史（hongguo_local SP，最新在前、去重、≤10 条） ----
    fun searchHistory(): List<String> {
        val raw = localPrefs.getString("search_history", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveSearchKeyword(keyword: String): List<String> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return searchHistory()
        val updated = searchHistory().toMutableList().apply {
            removeAll { it == kw }
            add(0, kw)
            while (size > 10) removeAt(lastIndex)
        }
        val arr = org.json.JSONArray()
        updated.forEach(arr::put)
        localPrefs.edit().putString("search_history", arr.toString()).apply()
        return updated
    }

    fun clearSearchHistory() {
        localPrefs.edit().remove("search_history").apply()
    }

    fun accountMethod(): String = session.loginMethod

    // ---- 详情 ----
    // 内存缓存(30min 倒计时过期) → 原生 video_detail/v1（~18KB/170ms，2026-11-14 替换
    // SSR 网页主通道——450KB HTML 冷路径 0.8~1.7s）→ SSR 回退兜底。30 分钟对齐 HGTV，
    // 短于追更感知周期，集数更新不受影响。
    suspend fun detail(seriesId: String): HongguoWeb.SeriesDetail {
        val cacheKey = "detail|$seriesId"
        contentCacheGet<HongguoWeb.SeriesDetail>(cacheKey, ttlMs = DETAIL_TTL_MS)?.let { return it }
        val d = withContext(Dispatchers.IO) {
            try {
                client.videoDetail(seriesId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("Hongguo", "video_detail fail, fallback SSR: ${e.message}")
                web.seriesDetail(seriesId)
            }
        }
        contentCachePut(cacheKey, d)
        return d
    }

    // ---- 长按菜单：相关作品 / 创作者（数据通道对照 HGTV 详情页 authorBox/relatedBox） ----

    /**
     * 相关作品（三级数据源，2026-11-13 openhongguo 反哺定稿）：
     * 1) client.related = 系列剧（plan/v1 from=single_col_same_ip_series 的 547 cell，
     *    同 IP 全部季）→ 相关推荐（from=select_panel_recommend，匿名回 401 通用推荐）；
     * 2) 仍空 → 剧名系列主干搜索（同 IP 其它季；搜索通道匿名可用）。短 TTL 同详情。
     */
    suspend fun related(seriesId: String, title: String): List<HongguoClient.Drama> {
        val cacheKey = "related|$seriesId"
        contentCacheGet<List<HongguoClient.Drama>>(cacheKey, ttlMs = DETAIL_TTL_MS)?.let { return it }
        val list = withContext(Dispatchers.IO) {
            coroutineScope {
                // 系列/推荐两路并行（原串行：单季剧系列跳常态 101116 空等，白付一跳 RTT；
                // 并行后总耗时=max，系列季命中时推荐路仅多一路轻请求）
                val seasons = async { runCatching { client.planSeriesSeasons(seriesId) }.getOrDefault(emptyList()) }
                val recommend = async { runCatching { client.planRecommend(seriesId) }.getOrDefault(emptyList()) }
                seasons.await().ifEmpty { recommend.await() }
            }.ifEmpty { relatedByTitleSearch(seriesId, title) }
        }
        contentCachePut(cacheKey, list)
        return list
    }

    /** 标题系列搜索兜底：主干搜索 → 标题含主干且非本剧。 */
    private suspend fun relatedByTitleSearch(
        seriesId: String,
        title: String
    ): List<HongguoClient.Drama> {
        val stem = seriesStem(title)
        if (stem.length < 2) return emptyList()
        val page = try {
            searchRaw(stem)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val stemNorm = normalizeAuthor(stem)
        return page.items.asSequence()
            .filter { it.seriesId != seriesId }
            .filter { normalizeAuthor(it.title).contains(stemNorm) }
            .map { it.toDrama() }
            .toList()
    }

    /**
     * 剧名系列主干：「我家后山藏真龙第四季」→「我家后山藏真龙」、
     * 「小镇是非多之同床异梦」→「小镇是非多」；无系列词返回全名。
     */
    internal fun seriesStem(title: String): String {
        val t = title.trim()
        val m = Regex("^(.{2,}?)(第[0-9一二三四五六七八九十百]+[季部篇曲]|之.+)").find(t)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } ?: t
    }

    /**
     * 本剧创作者（官方口径两级，2026-11-13 openhongguo 反哺）：
     * ① inner_feed_detail（出品方 user_name+user_id 优先，主演领衔兜底）；
     * ② miss 回落 multi-detail 书城 author/source（纯名无 userId）。
     * 命中才落 5min 缓存（空不缓存，区分「确认无」与网络失败）。
     */
    suspend fun creatorInfo(seriesId: String): HongguoClient.Creator? {
        val cacheKey = "creator|$seriesId"
        contentCacheGet<HongguoClient.Creator>(cacheKey, ttlMs = DETAIL_TTL_MS)?.let { return it }
        val creator = withContext(Dispatchers.IO) {
            coroutineScope {
                // 两路并行（原串行 miss 才发第二跳；并行后总耗时=max。HGTV 详情页
                // 出品方批量预取+LRU 命中的同思路——压缩首响延迟）
                val inner = async {
                    runCatching { client.innerFeedDetailCreators(listOf(seriesId))[seriesId] }.getOrNull()
                }
                val fallback = async {
                    runCatching { client.authorsOf(listOf(seriesId))[seriesId] }.getOrNull()
                }
                inner.await() ?: fallback.await()?.takeIf { it.isNotEmpty() }
                    ?.let { HongguoClient.Creator(userId = "", name = it) }
            }
        }
        if (creator != null) contentCachePut(cacheKey, creator)
        return creator
    }

    /**
     * 创作者作品（两级，2026-11-13 hook 官方 App 实测定稿）：
     * ① userId 直拉 personWorks——官方主页「作品›剧作」tab（sinfonlinec 域）；
     * ② 无 userId（multi-detail 兜底名场景）→ 搜名 + 搜索结果内嵌发布者
     *    user_info 直配（HGTV 原链路的最小子集，无换名/target 魔法）。
     * 结果 5min 缓存。
     */
    suspend fun authorWorks(creator: HongguoClient.Creator?, excludeSeriesId: String): List<HongguoClient.Drama> {
        val author = creator?.name.orEmpty()
        if (normalizeAuthor(author).isEmpty()) return emptyList()
        // 缓存键不含本剧 id：同作者全局一份（原键含 excludeSeriesId 致同作者每部剧
        // 各拉各的；HGTV 按出品方全局去重同款）。存全量、排除本剧在读取端过滤。
        val cacheKey = "authorworks|$author"
        contentCacheGet<List<HongguoClient.Drama>>(cacheKey, ttlMs = DETAIL_TTL_MS)
            ?.let { return it.filter { d -> d.seriesId != excludeSeriesId } }
        val works = withContext(Dispatchers.IO) {
            // ① 官方直拉：主页的「剧作」tab（有 userId 才能走）
            val direct = creator?.userId?.takeIf { it.isNotEmpty() }
                ?.let { uid -> client.personWorks(uid) }.orEmpty()
            if (direct.isNotEmpty()) {
                direct
            } else {
                worksBySearchMatch(author)
            }
        }
        contentCachePut(cacheKey, works)
        return works.filter { it.seriesId != excludeSeriesId }
    }

    /** 搜索名匹配兜底（无 userId 时）：搜索结果内嵌发布者 user_info 直配。 */
    private suspend fun worksBySearchMatch(
        author: String
    ): List<HongguoClient.Drama> = withContext(Dispatchers.IO) {
        val page = try {
            searchRaw(author)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        page.items.asSequence()
            .filter { matchesAuthor(it.author, author) }
            .map { it.toDrama() }
            .toList()
    }

    /** 原始搜索（关闭相关性过滤 + 不落搜索历史；通道内已重试，失败抛错由调用方兜底）。 */
    private suspend fun searchRaw(keyword: String): HongguoSearch.SearchPage =
        withContext(Dispatchers.IO) { search.searchTab(keyword, 0, null, relevanceFilter = false) }

    /** 作者名归一（HGTV E0：去空白 + 小写）。 */
    private fun normalizeAuthor(value: String): String =
        value.filterNot { it.isWhitespace() }.lowercase()

    /** 归一后非空且相等（HGTV D0）。 */
    private fun matchesAuthor(candidate: String, target: String): Boolean {
        val c = normalizeAuthor(candidate)
        return c.isNotEmpty() && c == normalizeAuthor(target)
    }

    private fun HongguoSearch.SearchResult.toDrama(): HongguoClient.Drama = HongguoClient.Drama(
        seriesId = seriesId,
        title = title,
        cover = cover,
        intro = intro,
        score = "",
        playCount = playCount,
        episodeCount = episodeCount,
        vid = "",
        tags = emptyList(),
        heatText = "",
        statusTags = emptyList(),
        seasonIndex = 0,
        multiSeason = false,
        contentType = "",
        seriesStatus = ""
    )

    // ---- 播放 ----

    /**
     * 播放地址预取缓存（官方 preload/v1 批量预取的落点）：条目 = PlayUrl 纯字符串
     * 轻量对象（每条 <1KB），内存驻留可忽略。URL 有效期约 6h → TTL 5h 留余量，
     * 过期/未命中走实时解析（player/v1 主链路）。
     */
    private val playUrlCacheLock = Any()
    private val playUrlCache = HashMap<String, HongguoPlay.PlayUrl>()
    private val playUrlCacheAt = HashMap<String, Long>()

    private fun takeCachedPlayUrl(key: String): HongguoPlay.PlayUrl? = synchronized(playUrlCacheLock) {
        val cached = playUrlCache[key] ?: return null
        if (System.currentTimeMillis() - (playUrlCacheAt[key] ?: 0L) > PLAY_URL_TTL_MS) {
            playUrlCache.remove(key); playUrlCacheAt.remove(key)
            return null
        }
        return cached
    }

    private fun cachePlayUrl(key: String, value: HongguoPlay.PlayUrl) = synchronized(playUrlCacheLock) {
        playUrlCache[key] = value
        playUrlCacheAt[key] = System.currentTimeMillis()
    }

    /** 播放地址解析（纯 Kotlin 签名+JVM okhttp；WebView 通道已删——实证从未成功）。
     *  预取命中直接返回，未命中走 player/v1 实时解析并回填缓存。 */
    suspend fun playUrl(vid: String, preferredPixels: Int = 0): HongguoPlay.PlayUrl =
        withContext(Dispatchers.IO) {
            val key = "$vid@$preferredPixels"
            takeCachedPlayUrl(key)?.let {
                AppLog.d("Hongguo", "playUrl cache hit vid=$vid q=$preferredPixels")
                return@withContext it
            }
            var result: HongguoPlay.PlayUrl? = null
            for (attempt in 0 until 2) {
                if (withIdentity { id -> result = play.playUrl(vid, id, preferredPixels) }) break
            }
            result?.also { cachePlayUrl(key, it) }
                ?: run {
                    // 两次 attempt 全败的真实原因在 withIdentity 逐条 AppLog（LAN 日志可见）
                    AppLog.w("Hongguo", "playUrl resolve failed vid=$vid q=$preferredPixels after 2 attempts")
                    throw IllegalStateException("红果播放地址解析失败")
                }
        }

    /**
     * 起播/切集成功后后台预热后续集（当前集+1 起 2 集；[vids] 首位=下一集）：
     * 1) 官方 preload/v1 批量预取地址（服务端 200 空体时全挂，走 2 兜底）；
     * 2) 下一集地址缓存 miss 时实时解析（player/v1 → K-M1 fallback，历史实证可用）；
     * 3) 下一集为加密流时整集预下载落地（切集秒开，[resolvePlayableFile] 在途去重
     *    防与切集并发双下）。释放同池：落地即受「缓存限制」LRU 修剪，无独立释放点。
     * 全部失败静默，切集实时解析兜底。
     */
    fun preloadNextEpisodes(vids: List<String>) {
        val pixels = defaultQualityPixels()
        val pending = vids.filter { it.isNotEmpty() && takeCachedPlayUrl("$it@$pixels") == null }
        if (pending.isNotEmpty()) {
            AppLog.d("Hongguo", "preload episodes start ${pending.size} q=$pixels")
            cloudScope.launch {
                withIdentity { id ->
                    runCatching { play.preloadEntries(pending, id, pixels) }
                        .onSuccess { entries ->
                            entries.forEach { (vid, url) -> cachePlayUrl("$vid@$pixels", url) }
                            AppLog.d("Hongguo", "preload episodes ok ${entries.keys}")
                        }
                        .onFailure { AppLog.w("Hongguo", "preload episodes fail: ${it.message}") }
                }
            }
        }
        val next = vids.firstOrNull { it.isNotEmpty() } ?: return
        cloudScope.launch {
            runCatching {
                // playUrl 内部先查地址缓存（preload/v1 命中免请求），miss 走 player/v1→K-M1
                val play = playUrl(next, pixels)
                if (play.contentKeyHex != null) {
                    resolvePlayableFile(play)
                    AppLog.d("Hongguo", "prefetch download ok vid=$next q=${play.pixels}")
                } else {
                    AppLog.d("Hongguo", "prefetch next plain vid=$next (直链不落地)")
                }
            }.onFailure { AppLog.d("Hongguo", "prefetch next fail vid=$next: ${it.message}") }
        }
    }

    // ---- CENC 解密流整集落地（起播/切集/预下载共用，从 Activity 平移） ----

    /** CENC 片源下载客户端（复用连接池；此前 Activity 每次下载新建 OkHttpClient）。 */
    private val cencHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 同文件在途下载去重：切集与预下载并发命中同一集时共享一份，防双下 8~27MB。 */
    private val inflightLock = Any()
    private val inflightDownloads = HashMap<String, kotlinx.coroutines.Deferred<String>>()

    /** CENC 缓存文件名：同集按画质档区分（vid@1080.mp4；无档位回落 vid.mp4 兼容旧缓存）。 */
    private fun cachedName(play: HongguoPlay.PlayUrl): String =
        if (play.pixels > 0) "${play.vid}@${play.pixels}.mp4" else "${play.vid}.mp4"

    /**
     * 播放地址 → 可播 URI：未加密直链原样返回；加密流缓存命中直接给本地 URI，
     * 未命中整集下载 + AES-128-CTR 解密落地（Node 同款方案，单集约 30MB）。
     * 在途去重：同文件并发调用共享一份下载。下载在 cloudScope（Supervisor）跑——
     * 调用方（播放页）销毁/取消只解除等待不中断下载，退出后仍下完落地，下次秒开。
     * 等来的在途下载失败不连坐：自己重下一次（CDN 抖动重试可过），二次失败才如实抛
     * （实测 v11-reading-video host 下载 11.5s 被 Connection reset，切集等在途被连坐报「切换失败」）。
     */
    suspend fun resolvePlayableFile(play: HongguoPlay.PlayUrl): String {
        val keyHex = play.contentKeyHex ?: return play.url
        val key = com.mytvb.feature.hongguo.net.HongguoCrypto.hexToBytes(keyHex)
        val outFile = java.io.File(HongguoMediaCache.dir(appContext), cachedName(play))
        if (outFile.isFile && outFile.length() > 0) return android.net.Uri.fromFile(outFile).toString()
        val fileName = outFile.name
        var retried = false
        while (true) {
            val deferred = synchronized(inflightLock) {
                inflightDownloads[fileName] ?: cloudScope.async(kotlinx.coroutines.Dispatchers.IO) {
                    downloadDecryptLand(play, key, outFile)
                }.also { d ->
                    inflightDownloads[fileName] = d
                    d.invokeOnCompletion {
                        synchronized(inflightLock) { if (inflightDownloads[fileName] === d) inflightDownloads.remove(fileName) }
                    }
                }
            }
            try {
                return deferred.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retried) throw e
                retried = true
                AppLog.w("Hongguo", "cenc await fail (${e.message}), retry self vid=${play.vid}")
            }
        }
    }

    /** 下载整集 + 解密 + 原子落地（tmp 改名防半截文件被缓存命中）+ 落地后 LRU 修剪。 */
    private fun downloadDecryptLand(play: HongguoPlay.PlayUrl, key: ByteArray, outFile: java.io.File): String {
        AppLog.d("Hongguo", "cenc download start ${play.vid} q=${play.pixels}")
        val bytes = cencHttpClient.newCall(
            okhttp3.Request.Builder().url(play.url)
                .header("User-Agent", "com.phoenix.read/71332 (Linux; U; Android 15; zh_CN; 2211133C; Build/V417IR)")
                .header("Range", "bytes=0-")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("片源下载 HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IllegalStateException("片源下载为空")
        }
        AppLog.d("Hongguo", "cenc downloaded ${bytes.size}B, decrypting")
        val plain = com.mytvb.feature.hongguo.net.HongguoMp4Decrypt.decryptMp4Buffer(bytes, key)
        java.io.File(outFile.parentFile, "${play.vid}.tmp").let { tmp ->
            tmp.writeBytes(plain)
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
        }
        AppLog.d("Hongguo", "cenc decrypted ${plain.size}B -> ${outFile.name}")
        // 落地后按「缓存限制」设置修剪旧集（LRU），预下载与观看缓存同池同口径
        HongguoMediaCache.enforceLimit(appContext)
        return android.net.Uri.fromFile(outFile).toString()
    }

    /** 切画质成功后清理同集其它档位的落地缓存（单档约 30MB，防止逐档堆积）。 */
    fun cleanupOtherQualityCaches(play: HongguoPlay.PlayUrl) {
        val keep = cachedName(play)
        HongguoMediaCache.dir(appContext).listFiles()?.forEach { f ->
            val name = f.name
            if (name != keep && name.startsWith("${play.vid}") &&
                (name == "${play.vid}.mp4" || name.matches(Regex("${play.vid}@\\d+\\.mp4")))
            ) f.delete()
        }
    }

    /**
     * 设置页「红果短剧·默认画质」存储值 → 目标档位 pixels（"默认"=0 取最高，
     * "1080P"→1080 等数字直提；无数字/未设置回落 0）。播放器起播与切集时读取。
     */
    fun defaultQualityPixels(): Int {
        val stored = appSettings.getCachedString("hongguo_quality") ?: return 0
        return Regex("\\d+").find(stored)?.value?.toIntOrNull() ?: 0
    }

    /**
     * 设置页「推荐受众」存储值（"不限"/"男频"/"女频"，默认不限；对照 HGTV recommendGender）。
     * 推荐流首拉拿到 gender 筛选面板后按名字匹配注入，会话内手动改筛选以手动为准。
     */
    fun recommendGenderStored(): String =
        appSettings.getCachedString("hongguo_recommend_gender") ?: "不限"

    private val appSettings: com.mytvb.core.common.settings.AppSettingsDataStore by lazy {
        org.koin.mp.KoinPlatform.getKoin().get()
    }

    // ---- 本地收藏 / 观看进度（独立 SP，与 B 站零共享） ----
    data class WatchRecord(
        val seriesId: String, val title: String, val cover: String,
        val episodeIndex: Int, val episodeCount: Int,
        val positionMs: Long, val durationMs: Long, val updatedAt: Long
    )

    private val localPrefs by lazy { appContext.getSharedPreferences("hongguo_local", Context.MODE_PRIVATE) }

    // ---- 账号云同步（登录态才发；本地为准、云端尽力，失败静默不影响主流程） ----

    val sync: HongguoSync by lazy { HongguoSync(session, client) }

    private val cloudScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private fun cloudAsync(block: suspend HongguoSync.() -> Unit) {
        if (!session.loggedIn) return
        cloudScope.launch {
            runCatching { sync.block() }
                .onFailure {
                    if (it is HongguoSync.SyncException && it.code == "SESSION_EXPIRED") {
                        AppLog.w("HongguoSync", "登录态过期，云同步停用（下次登录恢复）")
                    } else {
                        AppLog.d("HongguoSync", "cloud sync fail: ${it.message}")
                    }
                }
        }
    }

    /** 预约列表（数据层就位，UI 入口后续接）。 */
    suspend fun subscribeList(onlyOnline: Boolean): List<HongguoSync.SubscribeItem> =
        withContext(Dispatchers.IO) { sync.subscribeList(onlyOnline) }

    /** 取消预约（我的页点选）。 */
    suspend fun cancelSubscribe(seriesId: String): Boolean =
        withContext(Dispatchers.IO) { sync.cancelSubscribe(seriesId) }

    /** 云端观看历史（手机 App 同款账号的观看记录，按最近播放倒序）。 */
    suspend fun cloudHistory(): List<HongguoSync.CloudHistoryItem> =
        withContext(Dispatchers.IO) { sync.cloudHistory() }

    /** 登录态便捷判断。 */
    val loggedIn: Boolean get() = session.loggedIn

    /**
     * 历史 tab 数据源：本地记录 + 登录态拉云端合并。
     * 同剧集新者胜（updatedAt），title/cover/集数/时长缺失时用另一侧互补；云端失败回落本地。
     */
    suspend fun mergedHistory(): List<WatchRecord> {
        val local = history()
        if (!session.loggedIn) return local
        return try {
            val cloud = withContext(Dispatchers.IO) { sync.cloudHistory() }.map { c ->
                WatchRecord(
                    seriesId = c.seriesId, title = c.title, cover = c.cover,
                    episodeIndex = (c.episodeIndex1 - 1).coerceAtLeast(0), episodeCount = c.episodeCount,
                    positionMs = c.positionMs, durationMs = c.durationMs, updatedAt = c.lastPlayedAtMs
                )
            }
            mergeByIdNewerWins(local, cloud)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Hongguo", "cloud history merge fail: ${e.message}")
            local
        }
    }

    /** 用 other 补齐本条缺失的展示字段（新记录可能不带标题/封面）。 */
    private fun WatchRecord.fillFrom(other: WatchRecord) = copy(
        title = title.ifEmpty { other.title },
        cover = cover.ifEmpty { other.cover },
        episodeCount = if (episodeCount > 0) episodeCount else other.episodeCount,
        durationMs = if (durationMs > 0) durationMs else other.durationMs
    )

    /** 云端历史删除（本地历史清空时同步）。 */
    fun deleteHistoryCloud(seriesIds: List<String>) =
        cloudAsync { deleteHistory(seriesIds) }

    /**
     * 收藏 tab 数据源：本地记录 + 登录态拉云端书架合并（同剧集新者胜）；
     * 云端失败回落本地。2026-11-14 补云端（此前只有本地，用户反馈收藏不是云端数据）。
     * 书架接口只回 id+collect_time（无剧卡），云端条目缺 title 时走 detail 通道补卡
     * （contentCache 30min 复用——点开详情弹窗同源秒开）；本地已有的直接借本地卡面。
     */
    suspend fun mergedFavorites(): List<WatchRecord> {
        val local = favorites()
        if (!session.loggedIn) return local
        return try {
            val localById = local.associateBy { it.seriesId }
            val cloud = withContext(Dispatchers.IO) { sync.cloudFavorites() }.map { c ->
                val base = WatchRecord(
                    seriesId = c.seriesId, title = c.title, cover = c.cover,
                    episodeIndex = 0, episodeCount = c.episodeCount,
                    positionMs = 0L, durationMs = 0L, updatedAt = c.collectedAtMs
                )
                val borrowed = localById[c.seriesId]
                if (borrowed != null && borrowed.title.isNotEmpty() && borrowed.cover.isNotEmpty()) {
                    // 本机收藏过：直接借本地卡面（零请求）
                    base.copy(title = borrowed.title, cover = borrowed.cover,
                        episodeCount = if (base.episodeCount > 0) base.episodeCount else borrowed.episodeCount)
                } else base
            }
            // 云端新条目（本机没收藏过）逐条补剧卡（title/cover 任缺即补）；补失败保留原条
            val enriched = cloud.map { rec ->
                if (rec.title.isNotEmpty() && rec.cover.isNotEmpty()) return@map rec
                try {
                    val d = detail(rec.seriesId)
                    rec.copy(title = d.drama.title, cover = d.drama.cover,
                        episodeCount = d.drama.episodeCount)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    rec
                }
            }
            mergeByIdNewerWins(local, enriched)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Hongguo", "cloud favorites merge fail: ${e.message}")
            local
        }
    }

    /** 同剧集新者胜（updatedAt），缺失字段互补；结果按 updatedAt 倒序。 */
    private fun mergeByIdNewerWins(local: List<WatchRecord>, cloud: List<WatchRecord>): List<WatchRecord> {
        val byId = LinkedHashMap<String, WatchRecord>()
        for (r in local + cloud) {
            val old = byId[r.seriesId]
            byId[r.seriesId] = when {
                old == null -> r
                r.updatedAt > old.updatedAt -> r.fillFrom(old)
                else -> old.fillFrom(r)
            }
        }
        return byId.values.sortedByDescending { it.updatedAt }
    }

    /**
     * 登录态数据就绪事件（登录成功+云端同步落库后 emit）：红果页 ViewPager2 打开时就
     * 预载了历史/收藏/我的子页，未登录时它们已用空数据初始化，登录后不会自己刷新——
     * 各子页 collect 本事件重拉。同步失败也 emit（页面至少能重拉本地）。
     */
    val loginEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 登录成功后立即同步（2026-11-14：此前登录后要重启才见云历史/收藏——回调只刷了
     * 账号行，云端数据没落本地，已加载的 tab 不会自己刷新）：bootVerify 保活 + 云历史/
     * 云收藏合并**落本地库**（writeRecords 直写不走 saveProgress，避免同值回传云端），
     * 完成后发 [loginEvents] 通知已预载子页重拉。
     */
    fun syncAfterLogin() {
        if (!session.loggedIn) return
        cloudScope.launch {
            runCatching {
                sync.bootVerify()
                // 云历史 → 本地（同 mergedHistory 语义，新者胜）
                val cloudHistory = sync.cloudHistory().map { c ->
                    WatchRecord(
                        seriesId = c.seriesId, title = c.title, cover = c.cover,
                        episodeIndex = (c.episodeIndex1 - 1).coerceAtLeast(0),
                        episodeCount = c.episodeCount,
                        positionMs = c.positionMs, durationMs = c.durationMs,
                        updatedAt = c.lastPlayedAtMs
                    )
                }
                val mergedHistory = mergeByIdNewerWins(history(), cloudHistory)
                writeRecords("history", mergedHistory.take(200))
                // 云收藏 → 本地：只同步本地已有条目的时间戳（云端新条目不落库——书架
                // 接口无剧卡，完整列表由 mergedFavorites 动态补卡，本地库保持纯本机操作记录）
                val localFav = favorites().associateBy { it.seriesId }
                val cloudFavIds = sync.cloudFavorites().associateBy { it.seriesId }
                val syncedFav = localFav.values.map { l ->
                    val c = cloudFavIds[l.seriesId]
                    if (c != null && c.collectedAtMs > l.updatedAt) l.copy(updatedAt = c.collectedAtMs) else l
                }
                writeRecords("favorites", syncedFav)
                AppLog.d("Hongguo", "syncAfterLogin done: history=${mergedHistory.size} favorites=${syncedFav.size}")
            }.onFailure {
                AppLog.d("HongguoSync", "sync after login fail: ${it.message}")
            }
            loginEvents.tryEmit(Unit)
        }
    }

    /** 登录态保活（播放启动等低频场景调用；30 分钟阈值内部判定）。 */
    fun beatIfNeeded() {
        if (!session.loggedIn) return
        cloudScope.launch {
            runCatching { sync.beatIfNeeded(scene = "play") }
                .onFailure { AppLog.d("HongguoSync", "beat fail: ${it.message}") }
        }
    }

    fun favorites(): List<WatchRecord> = readRecords("favorites")

    fun isFavorite(seriesId: String): Boolean =
        localPrefs.getString("favorites", null)?.contains("\"$seriesId\"") == true

    fun toggleFavorite(record: WatchRecord): Boolean {
        val list = favorites().toMutableList()
        val existing = list.indexOfFirst { it.seriesId == record.seriesId }
        val added = if (existing >= 0) {
            list.removeAt(existing); writeRecords("favorites", list); false
        } else {
            list.add(0, record); writeRecords("favorites", list); true
        }
        // 云同步（bookshelf/video/update；登录态才发，失败不回滚本地——本地为准，云端尽力）
        cloudAsync { syncFavorite(record.seriesId, cancel = !added) }
        return added
    }

    fun history(): List<WatchRecord> = readRecords("history")

    fun saveProgress(record: WatchRecord, vid: String = record.seriesId, pushCloud: Boolean = true) {
        val list = history().filter { it.seriesId != record.seriesId }.toMutableList()
        list.add(0, record)
        writeRecords("history", list.take(200))
        // 云同步（进度+历史双通道：read_progress/upload 支持跨设备续播；节流 20s 防连发；
        // 云端进度回落本地时 pushCloud=false 防同值回传）
        val now = System.currentTimeMillis()
        val key = "last_cloud_push_${record.seriesId}"
        if (pushCloud && now - localPrefs.getLong(key, 0L) > 20_000L) {
            localPrefs.edit().putLong(key, now).apply()
            cloudAsync {
                uploadProgress(
                    record.seriesId, vid, (record.episodeIndex + 1).coerceAtLeast(1),
                    record.positionMs, record.durationMs, record.episodeCount
                )
                uploadHistory(
                    record.seriesId, (record.episodeIndex + 1).coerceAtLeast(1),
                    record.positionMs, record.durationMs, record.episodeCount
                )
                Unit
            }
        }
    }

    fun progressOf(seriesId: String): WatchRecord? =
        history().firstOrNull { it.seriesId == seriesId }

    /**
     * 云端单剧进度刷新（read_progress/get，官方进播放页必拉）：详情弹窗打开时异步调，
     * 云端比本地新（换设备/重装场景）则落本地（不回传云端）并返回 true 由调用方重算 UI；
     * 未登录/无云端记录/失败一律返回 false 静默。
     */
    suspend fun refreshProgressFromCloud(seriesId: String): Boolean {
        if (!session.loggedIn) return false
        return try {
            val cloud = withContext(Dispatchers.IO) { sync.cloudProgress(seriesId) } ?: return false
            val local = progressOf(seriesId)
            if (local == null || cloud.lastPlayedAtMs > local.updatedAt) {
                saveProgress(
                    WatchRecord(
                        seriesId = cloud.seriesId,
                        title = cloud.title.ifEmpty { local?.title.orEmpty() },
                        cover = cloud.cover.ifEmpty { local?.cover.orEmpty() },
                        episodeIndex = (cloud.episodeIndex1 - 1).coerceAtLeast(0),
                        episodeCount = if (cloud.episodeCount > 0) cloud.episodeCount else local?.episodeCount ?: 0,
                        positionMs = cloud.positionMs,
                        durationMs = cloud.durationMs,
                        updatedAt = cloud.lastPlayedAtMs
                    ),
                    pushCloud = false
                )
                AppLog.d("Hongguo", "cloud progress applied: $seriesId ep=${cloud.episodeIndex1} pos=${cloud.positionMs}")
                true
            } else {
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.d("Hongguo", "cloud progress refresh fail: ${e.message}")
            false
        }
    }

    private fun readRecords(key: String): List<WatchRecord> {
        val raw = localPrefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                WatchRecord(
                    o.optString("sid"), o.optString("t"), o.optString("c"),
                    o.optInt("e", 0), o.optInt("n", 0),
                    o.optLong("p", 0L), o.optLong("d", 0L), o.optLong("u", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeRecords(key: String, list: List<WatchRecord>) {
        val arr = org.json.JSONArray()
        for (r in list) {
            arr.put(
                org.json.JSONObject()
                    .put("sid", r.seriesId).put("t", r.title).put("c", r.cover)
                    .put("e", r.episodeIndex).put("n", r.episodeCount)
                    .put("p", r.positionMs).put("d", r.durationMs).put("u", r.updatedAt)
            )
        }
        localPrefs.edit().putString(key, arr.toString()).apply()
    }

    init {
        migrateEpisodeIndexOnce()
        // 「稍后看」功能已移除（红果官方无此接口，自建本地书签下线），清掉存量孤儿数据
        localPrefs.edit().remove("watch_later").apply()
    }

    /**
     * 一次性迁移（2026-11-14）：修复前播放器把 1-based episodeIndex 直接落库，
     * 存量「看过」记录全部偏 +1（表现为详情弹窗「继续播放 第N+1集」、断点不续）。
     * 统一回 0-based：仅 e>0 减 1；e=0 是「从未播放」的收藏占位，保持不变。
     */
    private fun migrateEpisodeIndexOnce() {
        if (localPrefs.getBoolean(PREF_KEY_EP_INDEX_MIGRATED, false)) return
        for (key in listOf("history", "favorites")) {
            val list = readRecords(key)
            if (list.any { it.episodeIndex > 0 }) {
                writeRecords(
                    key,
                    list.map { if (it.episodeIndex > 0) it.copy(episodeIndex = it.episodeIndex - 1) else it }
                )
                AppLog.d("Hongguo", "migrate episodeIndex key=$key count=${list.size}")
            }
        }
        localPrefs.edit().putBoolean(PREF_KEY_EP_INDEX_MIGRATED, true).apply()
    }

    fun clearLocal() {
        localPrefs.edit().clear().apply()
    }

    companion object {
        /** 预取地址有效期约 6h（x-expires），缓存 5h 留余量防过期 URL 起播 403。 */
        private const val PLAY_URL_TTL_MS = 5L * 60 * 60 * 1000

        /** episodeIndex 0-based 迁移标记（见 migrateEpisodeIndexOnce）。 */
        private const val PREF_KEY_EP_INDEX_MIGRATED = "migrated_episode_index_v1"

        @Volatile
        private var instance: HongguoRepository? = null

        fun get(context: Context): HongguoRepository =
            instance ?: synchronized(this) {
                instance ?: HongguoRepository(context.applicationContext).also { instance = it }
            }
    }
}
