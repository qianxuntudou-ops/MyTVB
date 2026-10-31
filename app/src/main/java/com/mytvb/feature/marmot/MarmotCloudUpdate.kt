package com.mytvb.feature.marmot

import com.mytvb.core.common.json.GsonHolder
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.marmot.domain.CloudConfig
import com.mytvb.feature.marmot.domain.CloudRes
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 云端资源更新（对标参考 utao `UpdateService.checkOnlineVersion` + `FileUtil.unzipFile`）。
 *
 * 流程：
 * 1. [getConfig] 从 `api.vonchange.com/utao/config/update.json` 拉云端配置（含 res 版本/url）
 * 2. [checkAndUpdateRes] 对比本地 `filesDir/tv-web/update.json` 的 res.version，新版下载 tv-web.zip 解压到 filesDir/tv-web
 * 3. 之后 [MarmotLiveData]/[MarmotJsBridge] 读取时优先 filesDir/tv-web，无则 assets/tv-web
 *
 * 用独立 OkHttpClient（不注入 B 站 cookie/buvid header，避免污染 vonchange 服务器请求）。
 * 必须在 IO 线程调用。
 */
object MarmotCloudUpdate {
    private const val TAG = "MarmotCloudUpdate"
    private const val UPDATE_URL = "http://api.vonchange.com/utao/config/update.json"
    private const val TV_WEB_DIR = "tv-web"
    private const val UPDATE_JSON_NAME = "update.json"
    private val gson = GsonHolder.DEFAULT

    /** 独立 client：不带 B 站 cookie/buvid，纯净请求 vonchange。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 拉云端配置。返回 null 表示失败。 */
    fun getConfig(): CloudConfig? {
        AppLog.i(TAG, "getConfig: GET $UPDATE_URL")
        return try {
            val resp = client.newCall(Request.Builder().url(UPDATE_URL).build()).execute()
            AppLog.i(TAG, "getConfig: code=${resp.code} msg=${resp.message} len=${resp.body?.contentLength() ?: -1}")
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "getConfig: 非 2xx，放弃（可能需要明文流量权限或被运营商劫持）")
                return null
            }
            val body = resp.body?.string() ?: run {
                AppLog.w(TAG, "getConfig: 响应体为空")
                return null
            }
            gson.fromJson(body, CloudConfig::class.java).also {
                // res 为 null 但 body 非空 → 典型 R8 keep 缺失导致 Gson 反射置 null（非网络问题）。
                // 打印 body 前缀用于区分「云端未返回 res」与「反射失败」。
                AppLog.i(TAG, "getConfig: 解析结果 res.version=${it?.res?.version} update=${it?.res?.update}（body 前 120 字符=${body.take(120)}）")
            }
        } catch (t: Throwable) {
            // 常见：Cleartext HTTP traffic not permitted / UnknownHost / SocketTimeout / SSLHandshake
            AppLog.e(TAG, "getConfig 失败: ${t.javaClass.simpleName}: ${t.message}（url=$UPDATE_URL）", t)
            Log.w(TAG, "getConfig 失败: ${t.message}")
            null
        }
    }

    /**
     * 检查并更新前端资源（对应参考 `checkOnlineVersion`）。
     * 云端 res.version > 本地 → 下载 zip 解压到 filesDir/tv-web。
     * @return true 表示有更新并已下载。
     */
    fun checkAndUpdateRes(context: Context): Boolean {
        val config = getConfig() ?: return false
        val resNew = config.res ?: run {
            AppLog.w(TAG, "checkAndUpdateRes: config.res 为空，无更新信息")
            return false
        }
        if (!resNew.update) {
            AppLog.i(TAG, "云端 res.update=false，无需更新")
            Log.i(TAG, "云端 res.update=false，无需更新")
            return false
        }
        // 读本地 update.json 版本（filesDir/tv-web/update.json）
        val localVersion = readLocalVersion(context)
        if (resNew.version <= localVersion) {
            AppLog.i(TAG, "本地版本 $localVersion 已最新，云端 ${resNew.version}，不更新")
            Log.i(TAG, "本地版本 $localVersion 已最新，云端 ${resNew.version}")
            return false
        }
        AppLog.i(TAG, "版本更新：$localVersion → ${resNew.version}，下载 ${resNew.url}")
        Log.i(TAG, "版本更新：$localVersion → ${resNew.version}，下载 ${resNew.url}")
        return downloadAndUnzip(context, resNew.url, resNew.skipFirst ?: false, resNew.version)
    }

    /** 读本地 update.json 的 res.version。无则 0。 */
    /**
     * 读本地 update.json 的 res.version：**filesDir 与 assets 内置版本取大者**。
     *
     * 只认 filesDir 的话，新装/升级设备的 filesDir 版本为 0，第三方云端任何版本
     * （如 vonchange v62）都会下载覆盖，把 assets 里随版本发布的频道表更新冲掉
     * （issue#106 补全频道被顶掉的根因）。纳入 assets 内置版本后：云端版本必须
     * 高于我们发版内置版本才允许热更——第三方旧表永不降级覆盖我们的资源。
     * 补充频道另有代码化兜底（[CctvExtraChannels]，云端覆盖后合并仍在）。
     */
    private fun readLocalVersion(context: Context): Int {
        val filesVersion = readVersionFromJson(
            runCatching {
                File(context.filesDir, "$TV_WEB_DIR/$UPDATE_JSON_NAME").readText()
            }.getOrDefault("")
        )
        val assetsVersion = readVersionFromJson(
            runCatching {
                context.assets.open("$TV_WEB_DIR/$UPDATE_JSON_NAME")
                    .use { it.readBytes().decodeToString() }
            }.getOrDefault("")
        )
        return maxOf(filesVersion, assetsVersion)
    }

    private fun readVersionFromJson(json: String): Int {
        if (json.isBlank()) return 0
        return try {
            gson.fromJson(json, CloudConfig::class.java)?.res?.version ?: 0
        } catch (t: Throwable) { 0 }
    }

    /**
     * 下载 tv-web.zip 并解压到 filesDir/tv-web（对应参考 `FileUtil.unzipFile`）。
     * @param skipFirst true 时跳过 zip 内顶层目录前缀（参考 `Res.skipFirst`）。
     */
    private fun downloadAndUnzip(context: Context, url: String, skipFirst: Boolean, version: Int): Boolean {
        AppLog.i(TAG, "downloadAndUnzip: url=$url skipFirst=$skipFirst version=$version")
        return try {
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            AppLog.i(TAG, "downloadAndUnzip: 下载 code=${resp.code} len=${resp.body?.contentLength() ?: -1}")
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "downloadAndUnzip: 下载失败 code=${resp.code}")
                return false
            }
            val targetDir = File(context.filesDir, TV_WEB_DIR)
            if (!targetDir.exists()) targetDir.mkdirs()
            var entryCount = 0
            resp.body?.byteStream()?.use { input ->
                ZipInputStream(input).use { zis ->
                    // skipFirst 时取首个条目的顶层目录前缀，解压时剥除（参考 FileUtil.unzipFile）
                    var firstName: String? = null
                    if (skipFirst) {
                        val first = zis.nextEntry
                        if (first != null) {
                            val idx = first.name.indexOf("/")
                            firstName = if (idx > 0) first.name.substring(0, idx + 1) else first.name
                            zis.closeEntry()
                        }
                    }
                    var entry = zis.nextEntry
                    while (entry != null) {
                        var name = entry.name
                        // 剥除顶层目录前缀
                        if (firstName != null && name.startsWith(firstName)) {
                            name = name.substring(firstName.length)
                        }
                        if (name.isBlank()) { zis.closeEntry(); entry = zis.nextEntry; continue }
                        val outFile = File(targetDir, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buf = ByteArray(8192); var n: Int
                                while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                            }
                            entryCount++
                        }
                        zis.closeEntry(); entry = zis.nextEntry
                    }
                }
            }
            // 写入新版本号到 update.json
            val updateJson = gson.toJson(CloudConfig(res = CloudRes(version = version)))
            File(targetDir, UPDATE_JSON_NAME).writeText(updateJson)
            // 校验关键文件 tv2.json 是否解压成功（避免半截更新导致频道表损坏）
            val tvJson = File(targetDir, "js/cctv/tv2.json")
            AppLog.i(TAG, "downloadAndUnzip: 解压完成 $entryCount 文件，update.json 已写，tv2.json exists=${tvJson.exists()} len=${tvJson.length()}")
            Log.i(TAG, "tv-web 更新完成，版本 $version")
            true
        } catch (t: Throwable) {
            // 写入中断/磁盘满/zip 损坏都会走到这里，此时 filesDir 可能残留半截 tv2.json
            AppLog.e(TAG, "下载解压 tv-web.zip 失败: ${t.javaClass.simpleName}: ${t.message}（可能残留半截文件）", t)
            Log.e(TAG, "下载解压 tv-web.zip 失败: ${t.message}", t)
            false
        }
    }
}
