package com.mytvb.feature.hongguo.net

import com.mytvb.core.common.log.AppLog
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * 红果 applog 埋点上报（2026-11-21，窗口期韧性补齐第 1 项）：
 * 设备注册后零上报的 device_id 是「哑巴设备」，有被服务端清洗的嫌疑——
 * 本类在首身份就绪后向 AppLog 引擎补发 `__activate`+`app_launch` 两个
 * 标准事件，让注册号带上最小行为史。
 *
 * 形态已由真网探针四发全通实证（HongguoExpandProbeTest.probeApplog，
 * 2026-11-21：gzip/明文、1/2 事件、主 host/lq host 全 200 `message:success`）：
 * POST {host}/service/2/app_log/?{设备 query}，body=gzip(明文 JSON
 * {magic_tag:ss_app_log, header, events, seq})，签名头与 device_register
 * 同引擎同源（假 Argus+Gorgon+Khronos+X-SS-STUB）。
 *
 * 逆向参照：HGTV 1.3.8.6 自己不发埋点（dex 字符串表仅 device_register 端点，
 * 无 magic_tag/events 组包代码）——本类是超越 HGTV 的对拍真红果行为补齐，
 * 非对齐 HGTV。事件刻意克制（仅激活+启动两个 AppLog 通用事件）：没有
 * 真机事件名样本，乱编业务事件反而露馅。
 *
 * 隔离约束：fire-and-forget 单线程串行上报，任何失败静默吞掉——埋点
 * 绝不影响主链路；每进程只发一轮（重复 activate 无意义且异常）。
 */
internal object HongguoApplog {

    private const val TAG = "HgApplog"
    private const val APPLOG_API = "https://log5-applog.fqnovel.com"

    /** 单线程串行（不占 IO 池；daemon 不阻退出）。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hg-applog").apply { isDaemon = true }
    }

    /** 每进程一轮去重。 */
    private val reported = AtomicBoolean(false)

    /** 会话内事件序号（AppLog 单调 seq）。 */
    private val seq = AtomicLong(0)

    /** 最近一次合法身份（行为事件 onVideoPlay/onFeedExposed 用）。 */
    @Volatile
    private var lastIdentity: HongguoDeviceRegister.Identity? = null

    // video_play 节流（同 vid 60s 一发：失败重试/快速重进不重复计足迹）
    private var lastVideoPlayKey: String? = null
    private var lastVideoPlayAt = 0L

    /** 仅测试用：复位去重标记与身份。 */
    internal fun resetForTest() {
        reported.set(false)
        lastIdentity = null
        lastVideoPlayKey = null
        lastVideoPlayAt = 0L
    }

    /**
     * 首身份就绪入口（挂 HongguoRepository.identity() 出口，覆盖新注册与
     * 已注册恢复两路）。已发过/身份非法直接返回，永不重入。
     */
    fun onIdentity(id: HongguoDeviceRegister.Identity) {
        if (!reported.compareAndSet(false, true)) return
        if (id.deviceId.isEmpty() || id.installId.isEmpty() || id.deviceId == "0") {
            reported.set(false) // 非法身份不算已发，下次合法再来
            return
        }
        lastIdentity = id
        worker.execute {
            runCatching { report(id, buildReport(id, System.currentTimeMillis())) }
                .onFailure { AppLog.w(TAG, "report failed: ${it.message}") }
            // 同轮追加消息同步（klink 长连接的 HTTP 拉模式降级形态，见 syncAll 注释）
            runCatching { syncAll(id) }
                .onFailure { AppLog.w(TAG, "syncAll failed: ${it.message}") }
        }
    }

    /**
     * 播放事件（官方行为足迹第 2 项，2026-11-21 官方 APK 逆向落地）：官方红果
     * 每次进入 PLAYING 首发去重上报 `video_play`（classes17 TtsFeedTabView.o()，
     * params 带 group_id/item_id/position）。挂 HongguoPlayerActivity 起播+切集
     * 两处；画质切换不发（官方同一播放会话只计一次）。同 vid 60s 节流。
     */
    fun onVideoPlay(enterFrom: String, seriesId: String, vid: String, position: Int) {
        val id = lastIdentity ?: return
        if (vid.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (vid == lastVideoPlayKey && now - lastVideoPlayAt < 60_000L) return
            lastVideoPlayKey = vid
            lastVideoPlayAt = now
        }
        worker.execute {
            runCatching {
                val events = org.json.JSONArray().put(
                    JSONObject().put("time", now).put("event", "video_play")
                        .put(
                            "params", JSONObject()
                                .put("group_id", seriesId)
                                .put("item_id", vid)
                                .put("enter_from", enterFrom)
                                .put("position", position)
                        )
                )
                report(id, buildReport(id, now, events))
                AppLog.i(TAG, "video_play sent vid=${vid.take(8)}… enter=$enterFrom")
            }.onFailure { AppLog.w(TAG, "video_play failed: ${it.message}") }
        }
    }

    /**
     * 推荐流曝光回传（官方行为足迹第 3 项）：官方红果把推荐流所见条目回传推荐
     * 系统（POST /reading/distribution/recommend_stat/report/v1/，抓包实证
     * scene=1+stat_data[]{client_time,content_type,group_id,recommend_info,
     * stat_item_type:1}，recommend_info 为 feed 响应内嵌串**原样回传**——
     * 推荐闭环=服务端由此知道号在看什么，行为史核心组成）。仅回传带
     * recommend_info 的条目（官方语义原样回传；landpage 无此字段的流自然静默，
     * 不自造字段防露馅）。挂 HongguoClient.category/rank 成功路径。
     */
    fun onFeedExposed(source: String, rows: org.json.JSONArray?) {
        val id = lastIdentity ?: return
        val stat = buildRecoStat(rows, System.currentTimeMillis())
        if (stat.length() == 0) return
        worker.execute {
            runCatching {
                val body = JSONObject().put("scene", 1).put("stat_data", stat)
                    .toString().toByteArray(Charsets.UTF_8)
                val code = signedJsonPost(
                    "/reading/distribution/recommend_stat/report/v1/", body, id
                )
                AppLog.i(TAG, "reco_stat src=$source n=${stat.length()} $code")
            }.onFailure { AppLog.w(TAG, "reco_stat failed: ${it.message}") }
        }
    }

    /** 曝光条目提取（纯函数，可单测）：group_id/content_type/recommend_info。 */
    internal fun buildRecoStat(rows: org.json.JSONArray?, now: Long): org.json.JSONArray {
        val stat = org.json.JSONArray()
        rows ?: return stat
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val reco = item.optString("recommend_info").takeIf { it.isNotEmpty() } ?: continue
            val gid = item.optString("series_id_str").ifEmpty {
                item.optString("book_id_str").ifEmpty {
                    item.optLong("series_id", 0L).takeIf { it > 0 }?.toString() ?: ""
                }
            }
            if (gid.isEmpty()) continue
            stat.put(
                JSONObject()
                    .put("client_time", now)
                    .put("content_type", item.opt("content_type") ?: JSONObject.NULL)
                    .put("group_id", gid)
                    .put("recommend_info", reco)
                    .put("stat_item_type", 1)
            )
        }
        return stat
    }

    /**
     * 消息同步（klink 等效落地，2026-11-21 真网一发即通 code=0）：
     * klink TCP 私有协议无实现素材（HGTV 纯 Java 无 so 无长连接代码，HTTP
     * 抓包只有凭证随带），真机同步消息的 HTTP 降级通道就是本端点——周期拉取
     * 等效「设备在线+消息同步」足迹。最小落地：首身份一轮（消息本身对我们
     * 无功能价值，价值在在线足迹），游标 min_seq 从 0 全量拉。
     */
    private fun syncAll(id: HongguoDeviceRegister.Identity) {
        val body = JSONObject()
            .put(
                "synclist",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("count", 0).put("data_type", 0)
                        .put("max_seq", java.lang.Long.MAX_VALUE)
                        .put("min_seq", 0L)
                )
            )
            .toString().toByteArray(Charsets.UTF_8)
        val summary = signedJsonPost("/reading/msgapi/syncAll/v", body, id)
        AppLog.i(TAG, "syncAll $summary")
    }

    /** 组包产物：gzip body + 签名输入 query（纯函数，可单测）。 */
    internal data class Report(val gzBody: ByteArray, val rawQuery: String, val now: Long, val nowSec: Long) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** 事件+header+query 组装（与注册 payload/播放 query 同源；纯函数，可单测）。
     *  events 传 null 用默认两事件（activate+app_launch），行为事件传自组数组。 */
    internal fun buildReport(
        id: HongguoDeviceRegister.Identity, now: Long,
        events: org.json.JSONArray? = null
    ): Report {
        val profile = HongguoVersionProfiles.default
        val nowSec = now / 1000

        // header 与注册 payload 同源（HongguoDeviceRegister.payload 同字段集）
        // + 会话身份（device_id/iid/openudid）+ 版本走档案（与播放通道同源自洽）
        val header = JSONObject()
            .put("aid", 8662).put("app_id", 8662)
            .put("appver", profile.versionName).put("app_version", profile.versionName)
            .put("channel", HongguoEnv.CHANNEL)
            .put("device_brand", HongguoEnv.deviceBrand)
            .put("device_model", HongguoEnv.deviceType)
            .put("device_platform", "android")
            .put("os", "android")
            .put("os_api", HongguoEnv.osApi.toInt())
            .put("os_version", HongguoEnv.osVersion)
            .put("resolution", HongguoEnv.resolution.replace('*', 'x'))
            .put("language", "zh").put("region", "CN")
            .put("timezone", 8).put("tz_name", "Asia/Shanghai").put("tz_offset", 28800)
            .put("access", "wifi").put("package", "com.phoenix.read")
            .put("version_code", profile.code)
            .put("device_id", id.deviceId)
            .put("iid", id.installId)
            .put("openudid", id.openUdid)
            .put("cdid", id.cdid)
            .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            .put("sdk_flavor", "china")
        if (id.deviceToken.isNotEmpty()) header.put("ss_app_token", id.deviceToken)

        // 默认事件刻意只有两个 AppLog 通用事件（克制原则，见类注释）
        val ev = events ?: org.json.JSONArray().apply {
            put(JSONObject().put("time", now).put("event", "__activate").put("params", JSONObject()))
            put(
                JSONObject().put("time", now).put("event", "app_launch")
                    .put("params", JSONObject().put("launch_from", "cold").put("duration_ms", 0))
            )
        }
        val raw = JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("events", ev)
            .put("seq", seq.incrementAndGet())
            .put("_gen_time", now)
            .toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }

        // query 与注册/播放同源（设备 query + 身份四件套 + ts/_rticket）
        val q = deviceQuery(id, profile, now, nowSec)
        q["tt_data"] = "a"
        q["package"] = "com.phoenix.read"
        return Report(bos.toByteArray(), HongguoCrypto.encodeQuery(q), now, nowSec)
    }

    /** 设备 query 公共段（注册/播放/app_log 同源；TreeMap 序与 device_register 一致）。 */
    private fun deviceQuery(
        id: HongguoDeviceRegister.Identity,
        profile: HongguoVersionRef,
        now: Long,
        nowSec: Long
    ): TreeMap<String, String> {
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = HongguoEnv.CHANNEL
        q["device_brand"] = HongguoEnv.deviceBrand
        q["device_platform"] = "android"; q["device_type"] = HongguoEnv.deviceType
        q["dpi"] = HongguoEnv.dpi; q["language"] = "zh"
        q["manifest_version_code"] = profile.code; q["os"] = "android"
        q["os_api"] = HongguoEnv.osApi; q["os_version"] = HongguoEnv.osVersion
        q["resolution"] = HongguoEnv.resolution; q["ssmix"] = "a"
        q["update_version_code"] = profile.code; q["version_code"] = profile.code
        q["version_name"] = profile.versionName
        q["cdid"] = id.cdid
        q["openudid"] = id.openUdid
        q["device_id"] = id.deviceId
        q["iid"] = id.installId
        if (id.klinkEgdi.isNotEmpty()) q["klink_egdi"] = id.klinkEgdi
        q["ts"] = nowSec.toString()
        q["_rticket"] = now.toString()
        return q
    }

    private fun report(id: HongguoDeviceRegister.Identity, r: Report) {
        val body = r.gzBody
        val profile = HongguoVersionProfiles.default

        val req = Request.Builder()
            .url("$APPLOG_API/service/2/app_log/?${r.rawQuery}")
            .apply {
                header("User-Agent", HongguoEnv.ua(profile.code))
                header("Content-Type", "application/json; charset=utf-8")
                header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                header("Content-Encoding", "gzip")
                header("X-Tt-Request-Tag", "t=0;n=1")
                header("Lc", "102")
                header("Sdk-Version", "2")
                header("X-SS-DP", "8662")
                header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                header("X-Khronos", r.nowSec.toString())
                header(
                    "X-Argus",
                    java.util.Base64.getEncoder().encodeToString(
                        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(r.nowSec.toInt()).array()
                    )
                )
                header(
                    "X-Ladon",
                    java.util.Base64.getEncoder().encodeToString(
                        ByteArray(4).also { SecureRandom().nextBytes(it) }
                    )
                )
                header("X-SS-Req-Ticket", r.now.toString())
                header("X-SS-STUB", HongguoCrypto.toHex(HongguoCrypto.md5(body)))
                header(
                    "X-Gorgon",
                    HongguoCrypto.gorgon(r.rawQuery, body, r.nowSec.toInt(), HongguoCrypto.randomU32() and 0xFFFF)
                )
                post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            }.build()
        HongguoHttp.ok.newCall(req).execute().use { resp ->
            val text = resp.body?.bytes()?.toString(Charsets.UTF_8) ?: ""
            val message = JSONObject(text).optString("message")
            AppLog.i(TAG, "app_log http=${resp.code} message=$message did=${id.deviceId.take(6)}…")
        }
    }

    /**
     * 签名 JSON POST（syncAll/recommend_stat 共用头集：signRequest 全签名 +
     * 假 Argus/Ladon + Gorgon/Helios/Medusa，与播放通道同源自洽）。
     * 返回 "http=… code=…" 摘要（日志锚点用）。
     */
    private fun signedJsonPost(path: String, body: ByteArray, id: HongguoDeviceRegister.Identity): String {
        val profile = HongguoVersionProfiles.default
        val now = System.currentTimeMillis()
        val q = deviceQuery(id, profile, now, now / 1000)
        val signed = HongguoCrypto.signRequest(
            q, body, now, id.deviceId, targetBranch = 0, version = profile.versionName
        )
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (now / 1000).toInt()
        val req = Request.Builder()
            .url("${HongguoNetProfiles.playHost()}$path?${signed.query}")
            .apply {
                header("User-Agent", HongguoEnv.ua(profile.code))
                header("Accept", "application/json; charset=utf-8,application/x-protobuf")
                header("Content-Type", "application/json; charset=UTF-8")
                header("X-SS-DP", "8662")
                header("X-Tt-Store-Region", "cn")
                header("X-Tt-Store-Region-Src", "did")
                header("X-XS-From-Web", "0")
                header("Sdk-Version", "2")
                header("x-khronos", (signed.headers["X-Khronos"] ?: ""))
                header("x-argus", java.util.Base64.getEncoder().encodeToString(
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(nowSec).array()
                ))
                header("x-ladon", java.util.Base64.getEncoder().encodeToString(
                    ByteArray(4).also { SecureRandom().nextBytes(it) }
                ))
                header("x-gorgon", (signed.headers["X-Gorgon"] ?: ""))
                header("x-helios", HongguoArgus.helios(nowSec))
                header("x-medusa", HongguoPlaybackV5.medusaHeaderFor(signed.query, body, nowSec))
                header("x-vc-bdturing-sdk-version", "4.0.3.cn")
                header("x-ss-stub", (signed.headers["X-SS-STUB"] ?: ""))
                post(body.toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()))
            }.build()
        HongguoHttp.ok.newCall(req).execute().use { resp ->
            val text = resp.body?.bytes()?.toString(Charsets.UTF_8) ?: ""
            val code = runCatching { JSONObject(text).optInt("code", -1) }.getOrDefault(-1)
            return "http=${resp.code} code=$code"
        }
    }
}
