package com.mytvb.feature.cctv.vod

import com.mytvb.core.common.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 央视片库（tv.cctv.com/yxg）数据源。全部是央视公开接口，无需登录与签名：
 *
 * 1. 列表：api.cntv.cn/list/getVideoAlbumList（JSONP）——分类 + 类型/地区/年份/首字母筛选 + 分页
 * 2. 分集：api.cntv.cn/NewVideo/getVideoListByAlbumIdNew（JSONP）——一次拿齐该专辑的分集，
 *    每集自带 guid（取流标识）
 * 3. 取流：vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=<guid> → hls_url（标准 HLS，无防盗链）
 */
class CctvVodApi(
    private val okHttpClient: OkHttpClient
) {

    /** 分页拉取片库列表。 */
    fun fetchAlbums(
        category: CctvVodCategory,
        genre: String,
        area: String,
        year: String,
        letter: String,
        page: Int,
        pageSize: Int = PAGE_SIZE
    ): List<CctvAlbum> {
        val url = buildString {
            append("https://api.cntv.cn/list/getVideoAlbumList")
            append("?channelid=").append(category.channelId)
            append("&area=").append(area.encoded())
            append("&sc=").append(genre.encoded())
            append("&fc=").append(category.title.encoded())
            append("&year=").append(year.encoded())
            append("&letter=").append(letter.encoded())
            append("&p=").append(page)
            append("&n=").append(pageSize)
            append("&serviceId=tvcctv&topv=1&t=jsonp&cb=").append(CALLBACK_NAME)
        }
        val raw = get(url) ?: return emptyList()
        return runCatching { parseAlbums(raw, category.episodeMode) }.getOrElse { error ->
            AppLog.e(TAG, "parseAlbums failed: ${error.message}")
            emptyList()
        }
    }

    /**
     * 拉取某专辑的分集列表。**恒以 mode=0（正片）优先，mode=1 只作空列表兜底**：
     * 官方播放页的 cuqie 写死 mode=0 拉列表、按当前页 URL 里的视频 id 匹配后才创建
     * 播放器；mode=1 返回的是碎片看点、id 体系与正片完全不同（同一集两个 vid），
     * 拿 mode=1 的 URL 进播放页永远匹配不上 → video 元素不创建 → 25s 超时报
     * 「播放地址获取失败」（issue#103，纪录片/动画片 category.episodeMode=1 误用所致）。
     */
    fun fetchEpisodes(albumId: String, @Suppress("UNUSED_PARAMETER") episodeMode: Int): List<CctvEpisode> {
        if (albumId.isBlank()) return emptyList()
        val primary = requestEpisodes(albumId, 0)
        if (primary.isNotEmpty()) return primary
        AppLog.w(TAG, "episodeModeFallback albumId=$albumId mode=0 -> 1")
        return requestEpisodes(albumId, 1)
    }

    /** guid → HLS 地址 + 清晰度档位。 */
    fun fetchPlayInfo(guid: String): CctvPlayInfo? {
        if (guid.isBlank()) return null
        val raw = get("https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=$guid") ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val hlsUrl = json.optString("hls_url")
            if (hlsUrl.isBlank()) return@runCatching null
            CctvPlayInfo(
                hlsUrl = hlsUrl,
                title = json.optString("title").ifBlank { "CCTV" },
                qualities = buildQualities(hlsUrl)
            )
        }.getOrElse { error ->
            AppLog.e(TAG, "parsePlayInfo failed: ${error.message}")
            null
        }
    }

    /**
     * 生成清晰度档位。
     *
     * 优先用网页端那套多码率清单（`dh5ws01.v.cntv.cn/asp/h5e/hls/`，含 720P 两档）；
     * 它取不到时退回接口同源的两档（master 只列 480x270，但同路径下 850 目录是
     * 另一路 640x360 转码；再往上的 1200/2000 是占位档，内容与 450 相同）。
     */
    private fun buildQualities(hlsUrl: String): List<CctvQuality> {
        val web = fetchMasterQualities(toH5eMasterUrl(hlsUrl))
        if (web.size >= 2) return web
        return buildOriginQualities(hlsUrl)
    }

    private fun toH5eMasterUrl(hlsUrl: String): String =
        HLS_PATH_REGEX.replace(hlsUrl) { H5E_BASE + "/asp/h5e/hls/" }

    /** 解析 master m3u8 的档位列表（相对路径按 master 自身域名补全，按码率升序）。 */
    private fun fetchMasterQualities(masterUrl: String): List<CctvQuality> {
        val master = get(masterUrl) ?: return emptyList()
        val base = masterUrl.substringBefore("/asp/").takeIf { it.startsWith("http") } ?: return emptyList()
        val lines = master.lines()
        val result = ArrayList<CctvQuality>()
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val resolution = RESOLUTION_REGEX.find(line)?.groupValues
            val variantPath = lines.drop(index + 1)
                .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.trim()
                ?: return@forEachIndexed
            if (bandwidth <= 0) return@forEachIndexed
            result.add(
                CctvQuality(
                    width = resolution?.getOrNull(1)?.toIntOrNull() ?: 0,
                    height = resolution?.getOrNull(2)?.toIntOrNull() ?: 0,
                    bandwidth = bandwidth,
                    url = if (variantPath.startsWith("http")) variantPath else base + variantPath
                )
            )
        }
        return result.sortedBy { it.bandwidth }
    }

    private fun buildOriginQualities(hlsUrl: String): List<CctvQuality> {
        val match = HLS_URL_REGEX.find(hlsUrl) ?: return emptyList()
        val origin = match.groupValues[1]
        val middle = match.groupValues[2]
        val guid = match.groupValues[3]
        val levels = QUALITY_LEVELS.map { (bandwidth, width, height) ->
            CctvQuality(
                width = width,
                height = height,
                bandwidth = bandwidth * 1024,
                url = "$origin/asp/hls/$bandwidth/$middle/$guid/$bandwidth.m3u8?maxbr=2048"
            )
        }
        val high = levels.lastOrNull() ?: return levels
        return if (probeExists(high.url)) levels else listOf(levels.first())
    }

    private fun probeExists(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Referer", "https://tv.cctv.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        okHttpClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun requestEpisodes(albumId: String, mode: Int): List<CctvEpisode> {
        val url = "https://api.cntv.cn/NewVideo/getVideoListByAlbumIdNew" +
            "?id=$albumId&serviceId=tvcctv&pub=1&mode=$mode&p=1&n=$EPISODE_PAGE_SIZE" +
            "&cb=$CALLBACK_NAME"
        val raw = get(url) ?: return emptyList()
        return runCatching { parseEpisodes(raw) }.getOrElse { error ->
            AppLog.e(TAG, "parseEpisodes failed: ${error.message}")
            emptyList()
        }
    }

    private fun parseAlbums(raw: String, episodeMode: Int): List<CctvAlbum> {
        val payload = unwrapJsonp(raw)
        if (!payload.startsWith("{")) return emptyList()
        val list = JSONObject(payload).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val albums = ArrayList<CctvAlbum>(list.length())
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            // 动画片等分类的 video 字段为空对象，专辑 id 取顶层 id
            val albumId = item.optString("id").trim()
            if (albumId.isBlank()) continue
            albums.add(
                CctvAlbum(
                    id = albumId,
                    title = title,
                    coverUrl = item.optString("image").ifBlank { item.optString("image2") },
                    brief = item.optString("brief"),
                    episodeCount = item.optString("count").toIntOrNull() ?: 0,
                    area = item.optString("area"),
                    year = item.optString("year"),
                    genre = item.optString("sc"),
                    actors = item.optString("actors"),
                    episodeMode = episodeMode
                )
            )
        }
        return albums
    }

    private fun parseEpisodes(raw: String): List<CctvEpisode> {
        val payload = unwrapJsonp(raw)
        if (!payload.startsWith("{")) return emptyList()
        val list = JSONObject(payload).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val parsed = ArrayList<RawEpisode>(list.length())
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val guid = item.optString("guid").trim()
            if (guid.isBlank()) continue
            parsed.add(
                RawEpisode(
                    title = item.optString("title").trim(),
                    coverUrl = item.optString("image"),
                    guid = guid,
                    pageUrl = item.optString("url").trim(),
                    part = item.optString("part").toIntOrNull()
                )
            )
        }
        // 有正片集号标记时只保留正片（央视同一接口会混入看点/花絮条目）
        val marked = parsed.filter { it.part != null }
        val chosen = marked.ifEmpty { parsed }
        return chosen.mapIndexed { index, rawItem ->
            CctvEpisode(
                index = rawItem.part ?: (index + 1),
                title = rawItem.title.ifBlank { "第${index + 1}集" },
                coverUrl = rawItem.coverUrl,
                guid = rawItem.guid,
                pageUrl = rawItem.pageUrl
            )
        }.sortedBy { it.index }
    }

    private fun unwrapJsonp(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return trimmed
        return trimmed.substring(start, end + 1)
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://tv.cctv.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.e(TAG, "httpError code=${response.code} url=$url")
                    return@use null
                }
                response.body?.string()
            }
        }.getOrElse { error ->
            AppLog.e(TAG, "requestError ${error.message} url=$url")
            null
        }
    }

    private fun String.encoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private data class RawEpisode(
        val title: String,
        val coverUrl: String,
        val guid: String,
        val pageUrl: String,
        val part: Int?
    )

    private companion object {
        private const val TAG = "CctvVodApi"
        private const val PAGE_SIZE = 24
        private const val EPISODE_PAGE_SIZE = 100
        private const val CALLBACK_NAME = "myTvbCb"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; TV; MyTVB) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

        /** 网页端多档清晰度所在的主机与路径前缀（第三方可直连，但请求过密会被临时限流）。 */
        private const val H5E_BASE = "https://dh5ws01.v.cntv.cn"
        private val HLS_PATH_REGEX = Regex("""^https?://[^/]+/asp/hls/""")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")

        /** hls_url 形如 `<origin>/asp/hls/main/0303000a/3/default/<guid>/main.m3u8?maxbr=2048`。 */
        private val HLS_URL_REGEX =
            Regex("""^(https?://[^/]+)/asp/hls/main/([^/]+/[^/]+/[^/]+)/([0-9a-zA-Z]{32})/main\.m3u8""")

        /** 同源可用的档位：目录名 / 分辨率。目录 1200/2000 是占位档（内容与 450 相同），不列。 */
        private val QUALITY_LEVELS = listOf(
            Triple(450, 480, 270),
            Triple(850, 640, 360)
        )
    }
}
