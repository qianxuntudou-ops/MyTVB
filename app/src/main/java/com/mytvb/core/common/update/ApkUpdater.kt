@file:Suppress("SpellCheckingInspection")

package com.mytvb.core.common.update

import com.mytvb.core.common.format.NumberUtils
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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

    /**
     * 仓库 main 分支的 version.json（发版三件套之一，与 release 附件同源同内容）。
     * 二级容错数据源：release 附件漏传时（网页手动上传靠人脑记清单，v2.1.20 曾漏），
     * 仓库文件因走 git 提交几乎不可能漏，用它兜住检查链路，避免落回被限流的 API。
     */
    private const val RAW_VERSION_JSON_URL =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/raw/main/$VERSION_JSON_NAME"

    /** 镜像站普遍校验/拒绝无 UA 或 okhttp UA 的请求，统一伪装浏览器 UA */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private const val COOLDOWN_MS = 5_000L
    private const val TAG = "ApkUpdater"

    /** version.json 热更镜像条数上限：太多只会拉长逐源尝试的总耗时 */
    private const val MAX_REMOTE_MIRRORS = 8

    /** 慢源看门狗：每 10 秒采样一次平均速度，低于 80KB/s 且远未下完就换下一个候选 */
    private const val WATCHDOG_WINDOW_MS = 10_000L
    private const val WATCHDOG_MIN_BYTES_PER_SEC = 80L * 1024L

    /**
     * GitHub release 资产下载镜像（国内加速；前缀拼接到原始直链前，如
     * https://gh-proxy.com/https://github.com/.../xxx.apk）。GitHub 直连永远作为最后兜底。
     * 2026-11-24 真实 APK 实测（Range 2MB × 3 轮，单线程）：
     * gh-proxy.com ~1.8-2.2MB/s（10-29 曾持续 403，已恢复，现为第一梯队）；
     * ghfast.top ~380KB/s 稳；gh.monlor.com ~280-470KB/s；gh-proxy.cc ~100-170KB/s 偶发超时；
     * ghproxy.net 已降到 ~40-100KB/s；gh.ddlc.top 429 限流；github.boki.moe 反复抖动(≈6KB/s)垫底。
     * 镜像属公益服务，快慢随时间漂移；version.json 的 apk_mirrors 可热更覆盖本列表（见下），
     * 不发版即可换源。定期复核地址发布站：ghproxy.link。
     *
     * 注意：这里的顺序只是「默认优先级」，实际下载还有慢源看门狗
     * （[WATCHDOG_MIN_BYTES_PER_SEC]）——首个源若龟速会自动切下一个，不必靠列表顺序兜住。
     */
    private val DOWNLOAD_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.monlor.com/",
        "https://gh-proxy.cc/",
        "https://ghproxy.net/",
        "https://gh.ddlc.top/",
        "https://github.boki.moe/",
    )

    /**
     * version.json 可选 `apk_mirrors` 下发的镜像（热更，2026-11-24 起）。
     * 与内置列表合并（热更在前、内置兜底、去重）；字段缺失/全非法时回落内置。
     * 只存内存：每次检查更新都会重新拉 version.json，下次会话自动再生效，无需持久化。
     */
    @Volatile
    private var remoteMirrors: List<String> = emptyList()

    /** 当前生效镜像：热更列表 + 内置列表，按归一化地址去重。 */
    private fun activeMirrors(): List<String> {
        val merged = LinkedHashSet<String>()
        (remoteMirrors + DOWNLOAD_MIRRORS).forEach { merged += it }
        return merged.toList()
    }

    /**
     * 校验 version.json 的 apk_mirrors：必须是 https://host[/path]/ 形态，自动补尾部斜杠；
     * 去重、最多 [MAX_REMOTE_MIRRORS] 条；非法项剔除。空/全非法返回空列表（= 回落内置）。
     * 入参为纯字符串列表（调用方从 JSONArray 摊平），便于脱离 org.json 做单测。
     */
    internal fun parseMirrorList(items: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (item in items) {
            val trimmed = item.trim().trim('"').trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) continue
            val host = trimmed.removePrefix("https://").removePrefix("HTTPS://").trimEnd('/')
            if (host.isBlank() || host.contains(' ')) continue
            if (!host.substringBefore('/').contains('.')) continue
            result += "https://$host/"
            if (result.size >= MAX_REMOTE_MIRRORS) break
        }
        return result.toList()
    }

    /** 下载候选 URL：GitHub 直链时镜像在前、直连兜底；非 GitHub 直链原样单候选。 */
    private fun downloadCandidates(url: String): List<String> =
        if (url.startsWith("https://github.com/")) {
            activeMirrors().map { it + url } + url
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
        /**
         * version.json 可选 `hg_versions` 字段原始对象（红果签名版本档案热更，
         * 见 HongguoVersionProfiles）。由检查更新的调用方喂给红果模块；
         * API 兜底链拿不到此字段，为 null（不冲掉已下发的档案）。
         */
        val hongguoVersions: JSONObject? = null,

        /**
         * version.json 可选 `hg_net` 字段原始对象（红果播放 host/画像热更，
         * 见 HongguoNetProfiles；2026-11-21 起）。同 hongguoVersions 的捎带语义。
         */
        val hongguoNet: JSONObject? = null,
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
            // 三级容错链：release 附件 version.json → 仓库 main 的 version.json → API 直连。
            // 前两级走与 APK 下载同一套镜像链，不查 api.github.com（国内间歇可达 + 匿名
            // 限流 60 次/小时/出口 IP，运营商 NAT 下容易 403），元数据与安装包同源同链路。
            // API 仅兜存量远古 release（两条 version.json 都没有）与镜像全灭的极端场景。
            fetchLatestViaVersionJson()?.let { return@withContext it }
            fetchLatestViaApi()
        }
    }

    /** latest release 不携带 version.json（404 透传）时无需再试其他镜像——同一 origin 结果一致 */
    private class VersionJsonAbsentException(url: String) : IOException("version.json absent: $url")

    private suspend fun fetchLatestViaVersionJson(): ReleaseInfo? {
        // 一级：latest release 附件。404=该 release 未携带，换镜像结果一致直接降级；
        // 不再直接落 API——API 匿名限流国内常态 403，落它身上检查必挂。
        fetchVersionJsonViaCandidates(VERSION_JSON_URL, "release")?.let { return it }
        // 二级容错：仓库 main 的 version.json。覆盖「三件套已提交但网页漏传附件」
        // 的事故窗口。注意 raw 文件可能略领先 release（提交后、发 Release 前的几分钟），
        // 极端时提示有新版但 latest/download/<apk> 尚 404 下载失败——窗口极小，可接受。
        return fetchVersionJsonViaCandidates(RAW_VERSION_JSON_URL, "repo raw")
    }

    private suspend fun fetchVersionJsonViaCandidates(url: String, sourceLabel: String): ReleaseInfo? {
        val candidates = downloadCandidates(url)
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                return parseVersionJson(fetchText(candidate))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is VersionJsonAbsentException) {
                    AppLog.i(TAG, "$sourceLabel has no version.json, give up this source")
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
        // 镜像热更（可选）：字段缺失保留上一次下发的值；存在但全非法则回落内置列表
        json.optJSONArray("apk_mirrors")?.let { rawMirrors ->
            remoteMirrors = parseMirrorList(
                (0 until rawMirrors.length()).map { rawMirrors.optString(it, "") }
            )
            if (remoteMirrors.isEmpty()) {
                AppLog.w(TAG, "apk_mirrors invalid, fallback to builtin list")
            } else {
                AppLog.i(
                    TAG,
                    "mirrors hot-updated count=${remoteMirrors.size} first=${remoteMirrors.first().take(48)}"
                )
            }
        }
        return ReleaseInfo(
            versionName = versionName,
            apkUrl = apkUrl,
            releaseNotes = json.optString("notes", "").trim(),
            // 可选字段：红果签名版本档案热更（缺失/非法由红果侧校验回落）
            hongguoVersions = json.optJSONObject("hg_versions"),
            // 可选字段：红果播放 host/画像热更（同上，红果侧校验回落）
            hongguoNet = json.optJSONObject("hg_net"),
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
    fun findDownloadedApk(context: Context, versionName: String, dir: File = UpdateStorage.internalDir(context)): File? {
        val apk = File(dir, "update.apk")
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
            val dir = UpdateStorage.internalDir(context.applicationContext)
            if (!dir.exists()) return@Thread
            val deleted = dir.deleteRecursively()
            AppLog.i(TAG, "cleanupStaleDownload dir=$dir deleted=$deleted")
        }.apply { isDaemon = true }.start()
    }

    suspend fun downloadApk(
        context: Context,
        url: String,
        targetDir: File,
        onProgress: (Progress) -> Unit,
    ): File {
        // 逐候选换源重试（镜像→…→直连）：国内访问 GitHub 直链经常龟速/失败，
        // 换一个源远比对同一源干等重试有效。
        val candidates = downloadCandidates(url)
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index == 0) onProgress(Progress.Connecting)
            // 最后一个候选（GitHub 直连）没有「下一个源」可换，慢源看门狗不能打断它，
            // 否则镜像全灭时连龟速直连也下不完。同理，只剩 1 个候选时全程放行。
            val allowSlow = index == candidates.lastIndex
            try {
                AppLog.i(TAG, "download attempt ${index + 1}/${candidates.size} via=${candidate.take(64)} dir=${targetDir.absolutePath}")
                return downloadApkOnce(context, candidate, targetDir, onProgress, allowSlow = allowSlow)
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

    /** 慢源看门狗触发：已下了一部分但速度远低于阈值，换下一个候选重下。 */
    private class SlowSourceException(message: String) : IOException(message)

    private suspend fun downloadApkOnce(
        context: Context,
        url: String,
        targetDir: File,
        onProgress: (Progress) -> Unit,
        allowSlow: Boolean = false,
    ): File {
        val dir = targetDir.apply { mkdirs() }
        check(dir.isDirectory) { "target dir unavailable: ${dir.absolutePath}" }
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
                        // 慢源看门狗：10 秒窗口平均速度低于阈值且远未下完 → 判定为龟速源，
                        // 交给候选链换下一个镜像（此前只有抛异常才换源，"慢"会一路慢到底，
                        // 用户实测一次更新要 5-10 分钟）。
                        var watchdogStartMs = System.currentTimeMillis()
                        var watchdogBytes = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            watchdogBytes += read

                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond =
                                    (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            if (!allowSlow) {
                                val windowMs = nowMs - watchdogStartMs
                                if (windowMs >= WATCHDOG_WINDOW_MS) {
                                    val windowBps = watchdogBytes * 1_000L / windowMs.coerceAtLeast(1)
                                    // 已过半就不打断：剩下不多，换源重下反而更慢
                                    val farFromDone = total?.let { downloaded < it / 2 } ?: (downloaded < 2L * 1024 * 1024)
                                    if (farFromDone && windowBps < WATCHDOG_MIN_BYTES_PER_SEC) {
                                        throw SlowSourceException(
                                            "slow source: ${windowBps / 1024}KB/s at ${downloaded}B/${total ?: -1L}B"
                                        )
                                    }
                                    watchdogStartMs = nowMs
                                    watchdogBytes = 0L
                                }
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
        // U 盘（FUSE/部分 ROM）上 renameTo 可能失败；同目录复制再删源，语义等价
        if (!part.renameTo(target)) {
            AppLog.w(TAG, "rename failed, fallback copy part=${part.length()}")
            runCatching { target.delete() }
            part.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            check(target.length() == part.length()) { "copy size mismatch" }
            part.delete()
        }
        return target
    }

    /**
     * 拉起系统安装界面。
     *
     * 内部分缓存走的仍是历史链路（FileProvider + ACTION_VIEW），零改动零风险；
     * U 盘上的包不在 FileProvider 的映射范围内（`<external-files-path>` 只覆盖主存储的
     * 应用专属目录，多卷 U 盘不在内），改用 PackageInstaller Session 流式写入安装会话，
     * 完全绕开文件 Uri 与跨进程读权限，任何卷都可装。
     *
     * @return true=已提交安装（含等待用户确认）；false=提交失败，调用方应提示
     *         「已保存到 <路径>，可用文件管理器手动安装」
     */
    suspend fun installApk(context: Context, apkFile: File): Boolean {
        val appContext = context.applicationContext
        if (UpdateStorage.isInternalDir(appContext, apkFile.parentFile ?: apkFile)) {
            AppLog.i(TAG, "install via FileProvider path=${apkFile.absolutePath} size=${apkFile.length()}")
            return withContext(Dispatchers.Main) {
                runCatching { launchInstaller(appContext, apkFile) }
                    .onFailure { AppLog.w(TAG, "install via FileProvider failed: ${it.message}") }
                    .isSuccess
            }
        }
        val submitted = withContext(Dispatchers.IO) { installViaSession(appContext, apkFile) }
        AppLog.i(TAG, "install via session path=${apkFile.absolutePath} submitted=$submitted")
        return submitted
    }

    private fun launchInstaller(context: Context, apkFile: File) {
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

    private fun installViaSession(context: Context, apkFile: File): Boolean {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(apkFile.length())
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val resultIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                    putExtra(UpdateInstallReceiver.EXTRA_APK_PATH, apkFile.absolutePath)
                }
                // API 31+ 必须显式声明 mutable：安装器要向该 Intent 里回填用户确认意图
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "install via session failed", t)
            false
        }
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
