@file:Suppress("SpellCheckingInspection")

package com.mytvb.core.common.update

import com.mytvb.core.common.format.NumberUtils
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mytvb.BuildConfig
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

object ApkUpdater {

    private const val GITHUB_OWNER = "qianxuntudou-ops"
    private const val GITHUB_REPO = "MyTVB"
    private const val RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** release 附带的版本元数据文件名，与 APK 一起上传；内容见 AGENTS.md 版本升级流程 */
    private const val VERSION_JSON_NAME = "version.json"
    private const val VERSION_JSON_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/$VERSION_JSON_NAME"

    /** 镜像站普遍校验/拒绝无 UA 或 okhttp UA 的请求，统一伪装浏览器 UA */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private const val COOLDOWN_MS = 5_000L
    private const val TAG = "ApkUpdater"
    private const val UPDATE_DIR_NAME = "update"

    /**
     * GitHub release 资产下载镜像（国内加速；前缀拼接到原始直链前，如
     * https://ghfast.top/https://github.com/.../xxx.apk）。GitHub 直连永远作为最后兜底。
     * 2026-10-29 真实 APK 直连实测三轮：ghfast(~2MB/s 稳)、gh.ddlc.top(~2.7MB/s)为
     * 第一梯队；ghproxy.net 慢(~300KB/s)但通；boki 抖动剧烈(2.4MB/s↔15KB/s)垫底。
     * gh-proxy.com 持续 403、akams/dgithub/ygxz/99988866/gitmirror 等直连全灭，均不收。
     * 镜像属公益服务，快慢随时间漂移，列表需定期复核（地址发布站：ghproxy.link）。
     */
    private val DOWNLOAD_MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh.ddlc.top/",
        "https://ghproxy.net/",
        "https://github.boki.moe/",
    )

    /** 下载候选 URL：GitHub 直链时镜像在前、直连兜底；非 GitHub 直链原样单候选。 */
    private fun downloadCandidates(url: String): List<String> =
        if (url.startsWith("https://github.com/")) {
            DOWNLOAD_MIRRORS.map { it + url } + url
        } else {
            listOf(url)
        }

    /** 启动自动检查的忽略版本：用户在自动弹窗点「稍后」后落盘，同版本不再自动弹 */
    const val KEY_IGNORED_VERSION = "update_ignored_version"

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .dns(IPv4OnlyDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 版本元数据（version.json）专用：小文件，紧超时让失败的镜像快速让位换源 */
    private val metaOkHttpLazy: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .dns(IPv4OnlyDns)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private object IPv4OnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            if (ipv4.isNotEmpty()) return ipv4
            throw UnknownHostException("No IPv4 address for $hostname")
        }
    }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val left = (lastStartedAtMs + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String,
    )

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${NumberUtils.formatBytes(downloadedBytes)} / ${NumberUtils.formatBytes(totalBytes)}")
                    } else {
                        append(NumberUtils.formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${NumberUtils.formatBytes(bytesPerSecond)}/s）")
                }
        }

        data object Done : Progress()

        data class Retrying(val attempt: Int, val maxAttempts: Int) : Progress()
    }

    suspend fun fetchLatestRelease(): ReleaseInfo {
        return withContext(Dispatchers.IO) {
            // 首选走与 APK 下载同一套镜像链拉 version.json：不查 api.github.com（国内
            // 间歇可达 + 匿名限流 60 次/小时/出口 IP，运营商 NAT 下容易 403），元数据与
            // 安装包同源同链路。latest release 尚未携带 version.json（存量过渡期）或
            // 全部镜像不可用时，落回 API 直连老路径。
            fetchLatestViaVersionJson()?.let { return@withContext it }
            fetchLatestViaApi()
        }
    }

    /** latest release 不携带 version.json（404 透传）时无需再试其他镜像——同一 origin 结果一致 */
    private class VersionJsonAbsentException(url: String) : IOException("version.json absent: $url")

    private suspend fun fetchLatestViaVersionJson(): ReleaseInfo? {
        val candidates = downloadCandidates(VERSION_JSON_URL)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                return parseVersionJson(fetchText(candidate))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is VersionJsonAbsentException) {
                    AppLog.i(TAG, "release has no version.json yet, fallback to api")
                    return null
                }
                AppLog.w(TAG, "version.json fetch failed via ${candidate.take(48)}: ${t.message}")
            }
        }
        return null
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        metaOkHttpLazy.value.newCall(req).execute().use { r ->
            if (r.code == 404) throw VersionJsonAbsentException(url)
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            return r.body?.string() ?: error("empty body")
        }
    }

    private fun parseVersionJson(raw: String): ReleaseInfo {
        val json = JSONObject(raw)
        val versionName = json.optString("version", "").trim().removePrefix("v")
        check(versionName.isNotBlank()) { "version.json version 为空" }
        check(parseVersion(versionName) != null) { "版本号格式不正确：$versionName" }
        val apkName = json.optString("apk", "").trim()
        check(apkName.isNotBlank()) { "version.json apk 为空" }
        // latest/download/<资产名>：version.json 与 APK 同属 latest release，无需感知 tag 名
        val apkUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/$apkName"
        return ReleaseInfo(
            versionName = versionName,
            apkUrl = apkUrl,
            releaseNotes = json.optString("notes", "").trim(),
        )
    }

    private suspend fun fetchLatestViaApi(): ReleaseInfo {
        var lastError: Throwable? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                return fetchLatestViaApiOnce()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry =
                    attempt < maxAttempts &&
                        (t is IOException || t.message?.startsWith("HTTP ") == true)
                if (!shouldRetry) throw t
                delay((400L * attempt).milliseconds)
            }
        }
        throw lastError ?: IllegalStateException("fetch latest release failed")
    }

    private fun fetchLatestViaApiOnce(): ReleaseInfo {
        val req = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val json = JSONObject(body.string())

            val tagName = json.optString("tag_name", "").trim().removePrefix("v")
            check(tagName.isNotBlank()) { "Release tag_name 为空" }
            check(parseVersion(tagName) != null) { "版本号格式不正确：$tagName" }

            val releaseNotes = json.optString("body", "").trim()

            // Release 同时挂着 release/debug 两个包，assets 顺序不保证（并行上传），
            // 必须显式挑 release 包，否则可能把 debug 包推给用户
            val assets = json.optJSONArray("assets") ?: error("没有找到附件")
            var releaseApkUrl = ""
            var fallbackApkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (name.endsWith("-debug.apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url", "")
                if (url.isBlank()) continue
                if (name.endsWith("-release.apk", ignoreCase = true)) {
                    releaseApkUrl = url
                    break
                }
                if (fallbackApkUrl.isBlank()) fallbackApkUrl = url
            }
            val apkUrl = releaseApkUrl.ifBlank { fallbackApkUrl }
            check(apkUrl.isNotBlank()) { "Release 中没有找到 APK 文件" }

            return ReleaseInfo(
                versionName = tagName,
                apkUrl = apkUrl,
                releaseNotes = releaseNotes,
            )
        }
    }

    fun isRemoteNewer(
        remoteVersionName: String,
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName)
            ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    /**
     * 查找当前会话内已下载完成、且版本号与目标版本一致的本地安装包（供复用免重下）。
     * 下载完成才会 rename 为 update.apk（中断只留 .part 文件），存在即完整；
     * 再用 PackageManager 解析包内 versionName 比对，防止旧版本残留包被误用。
     * 版本比对走 parseVersion 归一化：GitHub tag 带 v 前缀而 APK 内 versionName 不带，
     * 裸字符串比对永远不命中。
     */
    fun findDownloadedApk(context: Context, versionName: String): File? {
        val apk = File(File(context.cacheDir, UPDATE_DIR_NAME), "update.apk")
        if (!apk.isFile || apk.length() == 0L) return null
        val remote = parseVersion(versionName) ?: return null
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()
        val cached = info?.versionName?.let { parseVersion(it) }
        if (cached != null && cached == remote) {
            AppLog.i(TAG, "findDownloadedApk: reuse downloaded apk cached=${info.versionName} want=$versionName")
            return apk
        }
        AppLog.i(TAG, "findDownloadedApk: no match, cached=${info?.versionName} want=$versionName")
        return null
    }

    /**
     * 启动时清理上一次会话遗留的安装包。复用（findDownloadedApk）只在同一会话内
     * 发生（如安装界面被关掉后再次点下载），跨会话没有复用路径，残留包在磁盘上
     * 只会白占缓存空间（几十 MB），因此启动时整目录删除即可；下次下载会在同目录
     * 重建，不影响更新流程。
     */
    fun cleanupStaleDownload(context: Context) {
        Thread {
            val dir = File(context.applicationContext.cacheDir, UPDATE_DIR_NAME)
            if (!dir.exists()) return@Thread
            val deleted = dir.deleteRecursively()
            AppLog.i(TAG, "cleanupStaleDownload dir=$dir deleted=$deleted")
        }.apply { isDaemon = true }.start()
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        // 逐候选换源重试（镜像→…→直连）：国内访问 GitHub 直链经常龟速/失败，
        // 换一个源远比对同一源干等重试有效。
        val candidates = downloadCandidates(url)
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index == 0) onProgress(Progress.Connecting)
            try {
                AppLog.i(TAG, "download attempt ${index + 1}/${candidates.size} via=${candidate.take(64)}")
                return downloadApkToCacheOnce(context, candidate, onProgress)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                AppLog.w(TAG, "download failed via ${candidate.take(64)}: ${t.message}")
                if (index < candidates.lastIndex) {
                    onProgress(Progress.Retrying(index + 1, candidates.size))
                    delay(800L.milliseconds)
                }
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private suspend fun downloadApkToCacheOnce(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        val dir = File(context.cacheDir, UPDATE_DIR_NAME).apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond =
                                    (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(
                                    Progress.Downloading(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = bytesPerSecond,
                                    )
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly = cleaned.takeWhile { ch -> ch.isDigit() || ch == '.' }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
