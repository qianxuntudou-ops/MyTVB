package com.mytvb.feature.hongguo.net

import android.util.Base64
import android.util.Log
import com.mytvb.core.common.log.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果播放地址解析：multi_video_model/v1（签名 POST）→ video_model 内联/fallback 二跳
 * → video_list 编码过滤 → spade_a 密钥派生 → URL 解密 + CDN 域名白名单。
 * 对照 Node 版 app-source.js fetchAppPlayUrl/parseEntryPlayInfo（线上验证链路）。
 */
class HongguoPlay {

    private val http: OkHttpClient by lazy {
        // 共享连接池单例：播放必须复用注册连接（连接会话模型，见 HongguoHttp）
        HongguoHttp.ok
    }

    data class PlayUrl(
        val url: String,
        val contentKeyHex: String?,
        val codec: String,
        val quality: String,
        val width: Int,
        val height: Int,
        val durationSec: Long,
        val vid: String,
        /** 当前所选档位（竖屏取 min(width,height)，无尺寸信息时 0）。 */
        val pixels: Int = 0,
        /** 本集全部可用档位（兼容编码候选，pixels 降序去重）；空 = 片源未带尺寸信息。 */
        val availableQualities: List<Int> = emptyList(),
        /** 官方进度条雪碧图（player/v1 big_thumbs，HEIC 网格）；null = 本集未下发，不启用预览图。 */
        val bigThumbs: BigThumbs? = null,
        /** CENC 内容密钥 ID（hex 32 字符；来自 encrypt_info.kid，与 contentKeyHex 配对，
         *  ClearKey 流式解密用——MediaDrm 会话按 kid 匹配 mp4 pssh/tenc）。null=未下发。 */
        val kidHex: String? = null
    )

    /**
     * 官方进度预览雪碧图（video_model.big_thumbs，2026-11-13 官方抓包实锤）：
     * HEIC 容器整图（实测 2400×1360 ≈ 278KB，签名 URL 约 6h 有效，图床模板转换 403 锁死），
     * 按 [intervalSec] 每秒一格铺 [xLen] 列网格，格子尺寸 [cellWidth]×[cellHeight]
     * （横屏剧 240×136 / 竖屏剧 136×240，按字段取勿假设方向）。
     */
    data class BigThumbs(
        val imageUrl: String,
        val cellWidth: Int,
        val cellHeight: Int,
        val xLen: Int,
        val intervalSec: Int,
        val frameCount: Int
    ) {
        /** 官方格位公式：idx = 秒/interval，钳到 img_num-1（img_num 缺失时钳 0）。
         *  除法结果在 Long 域钳界后再转 Int（防极端 positionMs 溢出回绕成负）。 */
        fun cellIndexFor(positionMs: Long): Int {
            val maxIdx = if (frameCount > 0) frameCount - 1 else 0
            val raw = positionMs.coerceAtLeast(0L) / 1000L / intervalSec
            return raw.coerceAtMost(maxIdx.toLong()).toInt()
        }
    }

    class PlayException(message: String, val code: String) : IllegalStateException(message)

    /**
     * @param identity 真注册设备身份（multi_video_model 校验设备真实性，
     * 匿名随机 id 会被空响应拒绝）。可用 [HongguoDeviceRegister.registerFresh] 获取后持久化复用。
     * @param preferredPixels 目标画质档位（竖屏剧取 min(width,height)，如 1080/720/540/480）；
     * 0 = 默认取最高档。目标档缺失时就近取不超目标的最高档，全超目标取最低档。
     */
    fun playUrl(
        vid: String,
        identity: HongguoDeviceRegister.Identity,
        preferredPixels: Int = 0,
    ): PlayUrl {
        require(vid.matches(Regex("\\d{1,30}"))) { "分集编号无效" }
        // 沿革：11-13 弃 73932 档（code=100001）→ 11-14 起 74532 空 body 换 V5 头集
        // → 11-17 接 multi player/v1+V5 → **11-20 multi 系两端点被服务端全量 200 空
        // body 封禁**（V5/Argus 双头集、74532/74332 双版本同挂），切单条直取
        // video_model/v1（对齐 HGTV 1.3.8.4，其 multi 也只留 preload）。
        // 旧 multi 兜底链（3 轮 host 轮询+换号+M1 双端点）已删：服务端封禁是单向
        // 换代，multi 永远救不了直取的失败，留着只在失败路径白拖 3-4 秒。
        // ByteVC2-only 重试同删：直取首发即 video_platform=3（通用 h264/hevc），
        // 再遇 ByteVC2-only 属设备不支持，重试无意义。
        val entry = fetchDirectModelEntry(vid, identity)
        return parseEntry(vid, entry, preferredPixels)
    }

    /**
     * 单条直取通道（2026-11-20 播放全挂救援）：`/novel/player/video_model/v1/`，
     * 对齐 HGTV 1.3.8.4 主通道（HGTV AbstractC0201p0.T + AbstractC0162h1.O，
     * "model-direct"）。当天 multi 系两端点（player/v1 与 multi_video_model/v1/）
     * 被服务端全量 200 空 body 拒绝（V5 与 Argus/Ladon 双头集同挂），HGTV 同期
     * 版本恰把主播放切到本端点。要点：
     * - body 为单集 `video_id` 形态（弃用 mixed_video_id_map），
     *   device_level=4 / use_server_dns=false / source=4 / video_platform=3（通用
     *   h264/hevc，HGTV 默认值）；
     * - 传输走 [postVideoModelV5]（V5 签名 medusa/helios + 手机端 74332 query
     *   模板，实测通过；kotlinSignedPost 老 Argus/Ladon 头集在本端点同样被拒）；
     * - 响应 `data` 即外层 entry，`data.video_model` 为**字符串形态**（JSON-in-
     *   string），由 [parseEntry] 的字符串二跳解开；extractEntry 的 1004 分组
     *   逻辑不适用本端点。
     */
    private fun fetchDirectModelEntry(
        vid: String,
        identity: HongguoDeviceRegister.Identity
    ): JSONObject {
        Log.w(TAG, "play direct video_model/v1 vid=$vid device=${identity.deviceId}")
        // 诊断期：响应摘要塞进异常 message（android_logs 只回传异常行，message 可观测；
        // 真机验证稳定后 resp 探针可缩短或移除）
        var respProbe = ""
        return runCatching {
            val payload = postVideoModelV5("/novel/player/video_model/v1/", directModelBody(vid, 3), identity)
            respProbe = payload.toString().take(1200)
            val code = payload.optInt("Code", payload.optInt("code", 0))
            if (code != 0) throw PlayException("播放接口 code=$code", "BUSINESS_REJECTED")
            payload.optJSONObject("data") ?: payload
        }.getOrElse { e ->
            throw PlayException("${e.message} || resp=$respProbe", (e as? PlayException)?.code ?: "DIRECT_FAIL")
        }
    }

    /** video_model/v1 单集 body（HGTV AbstractC0162h1.O 同构）。use_server_dns 实验定案
     *  （2026-11-20）：HGTV 发 true+cold_start_stage，MyBLBL 发 false 同样通过且直接
     *  下发播放模型（true 会多一层 server_dns 解析结构，无配套二跳不采用）。 */
    private fun directModelBody(vid: String, videoPlatform: Int): ByteArray = JSONObject()
        .put("video_id", vid)
        .put("content_type", 1004)
        .put(
            "biz_param", JSONObject()
                .put("detail_page_version", 0)
                .put("device_level", 4)
                .put("disable_digg_stat", false)
                .put("disable_video_relate_book", false)
                .put("from_video_id", "")
                .put("need_all_video_definition", true)
                .put("need_mp4_align", false)
                .put("use_os_player", false)
                .put("use_server_dns", false)
                .put("source", 4)
                .put("video_platform", videoPlatform)
        )
        .toString().toByteArray(Charsets.UTF_8)

    private fun extractEntry(vid: String, payload: JSONObject): JSONObject {
        val code = payload.optInt("Code", payload.optInt("code", 0))
        if (code != 0) {
            val msg = payload.optString("message", payload.optString("Message", ""))
            AppLog.w(TAG, "play biz code=$code msg=$msg")
            throw PlayException("播放接口 code=$code${if (msg.isNotEmpty()) "：$msg" else ""}", "BUSINESS_REJECTED")
        }
        val data0 = payload.optJSONObject("data") ?: JSONObject()
        // V5 分组响应（2026-11-17 服务端切换）：data["1004"][vid].video_model
        val data = data0.optJSONObject("1004") ?: data0
        // 2026-11-20：video_model/v1 直取响应的 data.video_model 是「字符串形态」
        // （JSON-in-string，需二次 parse；对照 HGTV AbstractC0162h1.N 的容错）
        return data.optJSONObject("video_model")
            ?: data.optString("video_model").takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: data.optJSONObject(vid)
            ?: runCatching { data.optJSONObject(data.keys().next()) }.getOrNull()
            ?: throw PlayException("播放接口未返回所选分集", "MISSING_MODEL")
    }

    private class EmptyBodyException(detail: String = "empty body") : IllegalStateException(detail)

    private companion object {
        const val TAG = "Hongguo"

        /** 播放 host 集群（HGTV F0.n3.f2118b：a/b/c + lf/lq/hl 共 6 个，取前 3 轮询）。 */
        val PLAY_HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-sinfonlineb.fqnovel.com",
            "https://api5-normal-sinfonlinec.fqnovel.com"
        )

    }

    /** V5 签名传输（2026-11-17 接入，11-20 起为 video_model/v1 直取唯一通道）：
     *  gzip body 同字节签名发送 + 74332 手机端模板 query（保序、无 ts）+
     *  x-medusa/x-helios 独立播放签名（旧 Argus/Ladon 头集已被服务端弃用）。 */
    private fun postVideoModelV5(
        path: String, raw: ByteArray, identity: HongguoDeviceRegister.Identity,
        hostOverride: String? = null
    ): JSONObject {
        val body = gzipDeflate(raw)
        // V5 查询模板（PR#13 PlaybackV5Pairs：顺序参与签名；签名时追加 _rticket 挑分支）
        val pairs = buildList {
            add("aid" to "8662"); add("app_name" to "novelread")
            add("version_code" to "74332"); add("version_name" to "7.4.3.32")
            add("channel" to "vivo_8662_64"); add("device_platform" to "android")
            add("device_type" to "PGT-AN10"); add("os_version" to "12")
            add("update_version_code" to "74332")
            add("iid" to identity.installId); add("cdid" to identity.cdid)
            add("device_id" to identity.deviceId)
            // 真注册号带 device_token（注册响应字段；匿名号无此值，PR 模板不含）
            if (identity.deviceToken.isNotEmpty()) add("device_token" to identity.deviceToken)
            if (identity.klinkEgdi.isNotEmpty()) add("klink_egdi" to identity.klinkEgdi)
        }
        val signed = HongguoPlaybackV5.sign(pairs, System.currentTimeMillis(), body)
        val host = hostOverride ?: PLAY_HOSTS.first()
        val url = "$host$path?${signed.query}"
        // 2026-11-20：叠 HGTV 1.3.8.4 T() 头改写（AbstractC0201p0:680-690）——
        // lc=101 / x-ss-dp=8662 / x-vc-bdturing-sdk-version / passport-sdk-version /
        // x-tt-trace-id，video_model/v1 直取实测要求的完整头形态。
        val headers = signed.headers + listOf(
            "User-Agent" to HongguoPlaybackV5.PLAYBACK_UA,
            "Accept" to "application/json",
            "Content-Encoding" to "gzip",
            "passport-sdk-version" to "5051452",
            "lc" to "101",
            "x-ss-dp" to "8662",
            "x-vc-bdturing-sdk-version" to "4.0.3.cn",
            "x-tt-trace-id" to traceId()
        )
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            // 诊断期裸 Log（2026-11-20 端点切换排查，稳定后回 AppLog.d）；
            // http 码塞进异常 message——android_logs 工具只回传异常行，message 可观测
            Log.w(
                TAG, "play V5 resp http=${resp.code} len=${bytes.size} " +
                "tls=${resp.handshake?.tlsVersion} cipher=${resp.handshake?.cipherSuite}"
            )
            if (bytes.isEmpty()) throw EmptyBodyException("empty body http=${resp.code} path=$path")
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    /** 标准 gzip（HGTV G0 用 GZIPOutputStream；同一份字节既签名又发送）。 */
    private fun gzipDeflate(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }


    /** x-tt-trace-id（W3C traceparent 变体，对齐 HGTV W2.b()）。 */
    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    // ---- entry 解析：video_model 内联或 fallback_api 二跳（对照 parseEntryPlayInfo） ----
    private fun parseEntry(vid: String, entry: JSONObject, preferredPixels: Int = 0): PlayUrl {
        var model = entry.optJSONObject("video_model")
        if (model == null) {
            val modelStr = entry.optString("video_model")
            if (modelStr.isNotEmpty()) {
                try { model = JSONObject(modelStr) } catch (_: Exception) { }
            }
        }
        if (model == null) {
            throw PlayException("播放接口未返回本集片源模型", "MISSING_MODEL")
        }
        var info = model
        var inline = info.optJSONArray("video_list")?.length() ?: 0 > 0
        if (info.optJSONArray("video_list") == null) inline = false
        if (!inline) {
            // fallback_api 二跳：GET 返回 video_info.data
            val fallbackRaw = model.opt("fallback_api")
            val fallbackUrl = when (fallbackRaw) {
                is String -> {
                    val parsed = try { JSONObject(fallbackRaw) } catch (_: Exception) { null }
                    parsed?.optString("fallback_api")?.ifEmpty { fallbackRaw } ?: fallbackRaw
                }
                is org.json.JSONArray -> fallbackRaw.optString(0)
                else -> {
                    val obj = fallbackRaw as? JSONObject
                    obj?.optString("fallback_api") ?: ""
                }
            }
            if (fallbackUrl.isEmpty()) throw PlayException("播放接口未返回片源地址", "MISSING_MODEL")
            val outerText = http.newCall(
                Request.Builder().url(fallbackUrl)
                    .header("User-Agent", HongguoDeviceRegister.USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw PlayException("片源接口 HTTP ${resp.code}", "BUSINESS_REJECTED")
                resp.body?.string() ?: ""
            }
            if (outerText.isBlank()) throw PlayException("片源接口空响应", "EMPTY_RESPONSE")
            val outer = JSONObject(outerText)
            val outerCode = outer.optInt("Code", outer.optInt("code", 0))
            if (outerCode != 0) throw PlayException("片源接口拒绝", "BUSINESS_REJECTED")
            info = outer.optJSONObject("video_info")?.optJSONObject("data")
                ?: throw PlayException("片源接口缺少数据", "MISSING_MODEL")
        }

        // video_list 双形态：multi/v1=JSONObject(键值)；player/v1(fplay 同构)=JSONArray
        data class Candidate(
            val obj: JSONObject, val codec: String, val codecRank: Int,
            val quality: String, val width: Int, val height: Int, val pixels: Int
        )
        val candidates = ArrayList<Candidate>()
        fun addItem(item: JSONObject?) {
            if (item == null) return
            if (item.optString("main_url").isEmpty() && item.optString("backup_url").isEmpty() &&
                item.optJSONArray("backup_url_list") == null
            ) return
            val meta = item.optJSONObject("video_meta") ?: JSONObject()
            val codec = item.optString("codec_type").ifEmpty { meta.optString("codec_type") }.lowercase()
            val codecRank = when {
                codec in listOf("h264", "avc", "avc1") -> 0
                codec in listOf("hevc", "h265", "hvc1", "hev1", "bytevc1") -> 1
        false -> 2  // bytevc2 自研编码不支持（占位保序）
                else -> 3
            }
            val quality = item.optString("quality_desc").ifEmpty {
                item.optString("definition").ifEmpty { meta.optString("definition") }
            }
            var width = 0; var height = 0
            for (pair in listOf(
                listOf(item.optInt("vwidth", 0), item.optInt("vheight", 0)),
                listOf(item.optInt("width", 0), item.optInt("height", 0)),
                listOf(meta.optInt("vwidth", 0), meta.optInt("vheight", 0)),
                listOf(meta.optInt("width", 0), meta.optInt("height", 0))
            )) {
                if (pair[0] > 0 && pair[1] > 0) { width = pair[0]; height = pair[1]; break }
            }
            val pixels = if (width > 0 && height > 0) minOf(width, height)
            else Regex("\\d+").find(quality)?.value?.toIntOrNull() ?: 0
            candidates.add(Candidate(item, codec, codecRank, quality, width, height, pixels))
        }
        when (val vl = info.opt("video_list")) {
            is org.json.JSONArray -> for (i in 0 until vl.length()) addItem(vl.optJSONObject(i))
            is JSONObject -> for (key in vl.keys()) addItem(vl.optJSONObject(key))
        }
        val hasCompatible = candidates.any { it.codecRank < 2 }
        val rows = candidates.filter { it.codecRank < if (hasCompatible) 2 else 3 }
            .sortedWith(compareBy({ it.codecRank }, { -it.pixels }))
        if (rows.isEmpty()) {
            // 排障 dump：看 fplay/multi 响应里到底给了什么（2026-11-10 player/v1 形态；
            // video_list 为 JSONArray 时 optJSONObject 恒 null，附 codecs 汇总与二跳线索）
            runCatching {
                val codecs = candidates.joinToString(",") { it.codec }
                AppLog.w(TAG, "no codec candidates; info.keys=" + info.keys().asSequence().toList() +
                    " codecs=[" + codecs + "]" +
                    " video_list=" + (info.optJSONArray("video_list")?.toString()
                        ?.take(600) ?: "null") +
                    " fallback_api=" + model.opt("fallback_api")?.toString()?.take(200))
            }
            if (candidates.any { it.codec in listOf("bytevc2", "bvc2") } && !hasCompatible) {
                throw PlayException("本集使用 ByteVC2 编码，当前播放器暂不支持", "UNSUPPORTED_BYTEVC2")
            }
            throw PlayException("本集暂未提供兼容的 H.264/HEVC 片源", "MISSING_MODEL")
        }
        // 按目标档位选候选（Candidate 是本函数局部类，匹配逻辑收在函数内）：
        // preferred<=0 或候选全无尺寸信息 = 默认取最高档（rows.first）；
        // 有目标时取「不超过目标的最高档」，全超目标取最低档（就近降级），
        // 同档内保持编码兼容优先（rows 已按编码兼容优先、像素降序排好）。
        fun pickByPreference(preferred: Int): Candidate {
            if (preferred <= 0) return rows.first()
            val withPixels = rows.filter { it.pixels > 0 }
            if (withPixels.isEmpty()) return rows.first()
            val target = withPixels.filter { it.pixels <= preferred }.maxByOrNull { it.pixels }
                ?: withPixels.minByOrNull { it.pixels }!!
            return rows.first { it.pixels == target.pixels }
        }
        val best = pickByPreference(preferredPixels)
        // 进度条雪碧图（big_thumbs 与 video_list 平级，恒在 video_model 层；多条取格子最大者）
        val bigThumbs = model.optJSONArray("big_thumbs")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .maxByOrNull { it.optInt("img_x_size", 0) }
                ?.let { t ->
                    val u = t.optString("img_url").ifEmpty {
                        t.optJSONArray("img_urls")?.optString(0) ?: ""
                    }
                    val cellW = t.optInt("img_x_size", 0)
                    val cellH = t.optInt("img_y_size", 0)
                    val xLen = t.optInt("img_x_len", 0)
                    val interval = t.optInt("interval", 0)
                    if (u.isNotEmpty() && cellW > 0 && cellH > 0 && xLen > 0 && interval > 0) {
                        BigThumbs(
                            imageUrl = u,
                            cellWidth = cellW,
                            cellHeight = cellH,
                            xLen = xLen,
                            intervalSec = interval,
                            frameCount = t.optInt("img_num", 0)
                        )
                    } else null
                }
        }
        // 本集全部可用档位（兼容编码候选，降序去重）供播放 UI 画质弹窗展示
        val availableQualities = candidates
            .filter { it.codecRank < (if (hasCompatible) 2 else 3) && it.pixels > 0 }
            .map { it.pixels }.distinct().sortedDescending()
        val spade = best.obj.optString("spade_a").ifEmpty {
            best.obj.optJSONObject("encrypt_info")?.optString("spade_a") ?: ""
        }
        // kid（CENC key id）：encrypt_info.kid / model 顶层 / 选中流对象三处依次找
        // （同集各档同 kid，openhongguo 2026-10-24 观察）。形态可能是 hex(32) 或 base64(24/22)，
        // 统一归一到 32 位 hex；解不出时留 null（ClearKey 不可用，回落整集解密）。
        val kidRaw = best.obj.optJSONObject("encrypt_info")?.optString("kid")?.takeIf { it.isNotEmpty() }
            ?: model.optString("kid").takeIf { it.isNotEmpty() }
            ?: best.obj.optString("kid").takeIf { it.isNotEmpty() }
        val kidHex = kidRaw?.let { raw -> normalizeKeyHex(raw) }
        if (kidRaw != null && kidHex == null) {
            AppLog.w(TAG, "kid unparsable len=${kidRaw.length} vid=$vid")
        }
        // K-M1（multi/v1）响应的 spade_a 可能是非常规形态（base64 解不出/长度不足），
        // 容错按无密钥明文处理（URL 为明文流时正确；真加密流会在播放期自然报错）
        val contentKey = if (spade.isNotEmpty()) runCatching { deriveContentKey(spade) }.getOrNull() else null
        val keySeed = info.optString("key_seed")
        val urls = ArrayList<String>()
        best.obj.optString("main_url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        best.obj.optString("backup_url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
        best.obj.optJSONArray("backup_url_list")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            }
        }
        for (candidate in urls) {
            try {
                val media = if (keySeed.isNotEmpty()) {
                    decryptSpadeUrl(candidate, decodeFlexibleBase64(keySeed))
                } else {
                    val plain = cleanUrl(candidate)
                    if (plain.startsWith("http")) plain
                    else runCatching {
                        // V5 起 URL 可能以 Base64 文本下发（PR#13 playbackURL 明文变体）
                        String(decodeFlexibleBase64(candidate), Charsets.UTF_8)
                            .trimEnd('\u0000').trim()
                    }.getOrElse { plain }
                }
                val checked = checkedUrl(media)
                AppLog.d(
                    TAG, "play picked codec=${best.codec} quality=${best.quality} " +
                        "${best.width}x${best.height} encrypted=${contentKey != null} host=${java.net.URI(checked).host}" +
                        " preferred=$preferredPixels available=$availableQualities"
                )
                return PlayUrl(
                    url = checked,
                    contentKeyHex = contentKey?.let { HongguoCrypto.toHex(it) },
                    codec = when (best.codec) {
                        "bytevc1" -> "hevc"
                        "bvc2" -> "bytevc2"
                        else -> best.codec
                    },
                    quality = best.quality.ifEmpty { if (best.pixels > 0) "${best.pixels}p" else "" },
                    width = best.width,
                    height = best.height,
                    durationSec = model.optLong("video_duration", info.optLong("video_duration", 0L)),
                    vid = vid,
                    pixels = best.pixels,
                    availableQualities = availableQualities,
                    bigThumbs = bigThumbs,
                    kidHex = kidHex
                )
            } catch (_: Exception) {
                // 尝试下一候选 URL
            }
        }
        throw PlayException("本集片源地址无法解析", "INVALID_RESPONSE")
    }

    // ---- spade_a 内容密钥派生（对照 deriveContentKey） ----
    private fun deriveContentKey(value: String): ByteArray {
        val normalized = value.replace("-", "+").replace("_", "/")
        val raw = Base64.decode(normalized, Base64.DEFAULT)
        require(raw.size >= 3) { "spade_a too short" }
        var v8 = raw.size - ((raw[0].toInt() and 0xFF) xor (raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)) + 47
        v8 = minOf(v8, raw.size - 1)
        require(v8 >= 33) { "spade_a key length invalid" }
        val work = raw.copyOfRange(1, 1 + v8)
        var a = 85; var b = 246
        for (i in 0 until v8) {
            val old = work[i].toInt() and 0xFF
            val previous = if (i and 1 == 1) a else b
            if (i and 1 == 1) a = old else b = old
            val pop = Integer.bitCount(i)
            work[i] = ((-21 - pop + (previous xor old)) and 0xFF).toByte()
        }
        val hex = String(work.copyOfRange(1, 33), Charsets.US_ASCII)
        require(hex.matches(Regex("^[0-9a-fA-F]{32}$"))) { "App 视频密钥格式无效" }
        return HongguoCrypto.hexToBytes(hex.lowercase())
    }

    // ---- spade URL 解密（对照 decryptSpadeUrl：头 0xA8 xx 0x01 0x00 + AES-128-CBC） ----
    private fun decryptSpadeUrl(value: String, seed: ByteArray): String {
        val raw = decodeFlexibleBase64(value)
        require(raw.size >= 5) { "spade URL too short" }
        require((raw[0].toInt() and 0xFF) == 0xA8 && (raw[2].toInt() and 0xFF) == 0x01 && (raw[3].toInt() and 0xFF) == 0x00) {
            "spade URL header format error"
        }
        val cipherLength = (raw.size - 4) / 16 * 16
        require(cipherLength > 0) { "spade URL no block" }
        val constants = HongguoCrypto.hexToBytes(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
        )
        val h1 = HongguoCrypto.sha512(seed)
        val h2 = HongguoCrypto.sha512(h1 + constants)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(h2.copyOfRange(0, 16), "AES"), IvParameterSpec(h2.copyOfRange(16, 32)))
        var plaintext = cipher.doFinal(raw.copyOfRange(4, 4 + cipherLength))
        if (plaintext.isNotEmpty()) {
            val padding = plaintext[plaintext.size - 1].toInt() and 0xFF
            if (padding in 1..16 && padding <= plaintext.size) {
                plaintext = plaintext.copyOfRange(0, plaintext.size - padding)
            }
        }
        var end = plaintext.size
        while (end > 0 && plaintext[end - 1] == 0.toByte()) end--
        return String(plaintext.copyOfRange(0, end), Charsets.UTF_8)
    }

    private fun cleanUrl(value: String): String {
        // 去掉可能的外层引号与空白
        return value.trim().trim('"')
    }

    /** URL/地址字段可能是明文 http(s)、标准 Base64 或 URL-safe 无填充变体（V5 起，
     *  PR#13 decodePlaybackBase64）；统一规范化到标准填充形态再解。 */
    private fun decodeFlexibleBase64(value: String): ByteArray {
        val normalized = value.trim().replace("-", "+").replace("_", "/")
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    /** kid/key 字段形态归一：hex(32) 原样；base64(24/22, 可能 URL-safe) 解码转 hex；
     *  其余返回 null（调用方回落旧路径）。 */
    private fun normalizeKeyHex(raw: String): String? {
        val s = raw.trim()
        if (s.matches(Regex("^[0-9a-fA-F]{32}$"))) return s.lowercase()
        if (s.length in 22..24) {
            return runCatching {
                HongguoCrypto.toHex(decodeFlexibleBase64(s))
            }.getOrNull()?.takeIf { it.length == 32 }
        }
        return null
    }

    private fun checkedUrl(value: String): String {
        val uri = java.net.URI(value)
        val host = uri.host ?: throw PlayException("片源地址格式无效", "INVALID_RESPONSE")
        val domains = listOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
        val okHost = domains.any { host == it || host.endsWith(".$it") }
        if (uri.scheme != "https" || !okHost) {
            throw PlayException("片源返回了不受支持的服务地址", "SECURITY_BLOCKED")
        }
        return value
    }
}
