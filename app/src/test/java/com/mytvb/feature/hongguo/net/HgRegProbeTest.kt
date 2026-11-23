package com.mytvb.feature.hongguo.net

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 2026-11-23 注册环节对照探针：此前所有对比都只对播放请求，没对过「设备号是怎么注册出来的」。
 * 果映TV（tsukiyo-zero/hongguoTV 11-23 10:49 实证可用）的注册=无签名头 + x-neptune +
 * log5-applog-lf + 7.3.9.32/73932；我方=带 Khronos/Argus/Gorgon/Stub + log5-applog + 7.4.3.32/74332。
 * 矩阵：{我方注册 R1, 果映注册 R2} × {TNC 直连, 系统 DNS}，同刻交错打 video_tab（果映 getVideoTab
 * 同形态：无尾斜线 / 74532 / 空 body MD5 / 带 x-ss-stub），再各补一枪现行 preload/v1。
 * 枪间隔 25s。真网测试，失败不挂断言只打日志。
 */
class HgRegProbeTest {

    private val tncHttp: Call.Factory get() = HongguoHttp.client()

    private val sysHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    private val vid = "7701937936731737112"

    private val guoyingAppUa =
        "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"

    private val kdfSalt = HongguoCrypto.hexToBytes(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun md5HexUpper(b: ByteArray) = HongguoCrypto.toHex(HongguoCrypto.md5(b)).uppercase()

    // ---- ttEncrypt / ttDecrypt（果映 DeviceRegister 同款：MAGIC || rnd32 || AES-128-CBC） ----

    private fun deviceKey(seed: ByteArray): ByteArray = sha512(sha512(seed) + kdfSalt)

    private fun ttEncrypt(plain: ByteArray): ByteArray {
        val rnd = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = deviceKey(rnd)
        val body = sha512(plain) + plain
        val padding = 16 - body.size % 16
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"), IvParameterSpec(key.copyOfRange(16, 32)))
        return byteArrayOf(0x74, 0x63, 5, 0x10, 0, 0) + rnd + cipher.doFinal(body + ByteArray(padding) { padding.toByte() })
    }

    private fun ttDecrypt(b: ByteArray): ByteArray {
        val key = deviceKey(b.copyOfRange(6, 38))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(0, 16), "AES"), IvParameterSpec(key.copyOfRange(16, 32)))
        var p = cipher.doFinal(b.copyOfRange(38, b.size))
        val padding = p[p.size - 1].toInt() and 0xFF
        p = p.copyOfRange(0, p.size - padding)
        return p.copyOfRange(64, p.size)
    }

    // ---- R2：果映形态注册（DeviceRegister.payload/headers/parse 同构，无签名头） ----

    private fun guoyingRegister(client: Call.Factory): HongguoDeviceRegister.Identity? {
        val now = System.currentTimeMillis()
        fun uuid() = UUID.randomUUID().toString()
        val header = JSONObject()
            .put("display_name", "红果免费短剧")
            .put("update_version_code", 73932).put("manifest_version_code", 73932)
            .put("app_version_minor", "7.3.9.32").put("aid", 8662)
            .put("channel", "69310258a").put("package", "com.phoenix.read")
            .put("app_version", "7.3.9.32").put("version_code", 73932)
            .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo").put("sdk_target_version", 29)
            .put("git_hash", "3a88f4e")
            .put("os", "Android").put("os_version", "16").put("os_api", 36)
            .put("device_model", "25053RT47C").put("device_brand", "Redmi").put("device_manufacturer", "Redmi")
            .put("cpu_abi", "arm64-v8a").put("release_build", "b5e187f")
            .put("density_dpi", 520).put("display_density", "mdpi")
            .put("resolution", "1280x2772").put("language", "zh").put("timezone", 8)
            .put("access", "wifi").put("not_request_sender", 0).put("carrier", "CHINA MOBILE")
            .put("mcc_mnc", "46000").put("rom", "eng.root.20231116.160340")
            .put("rom_version", "W528JS release-keys").put("cdid", uuid())
            .put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            .put("openudid", uuid().replace("-", "").take(16)).put("clientudid", uuid())
            .put("ipv6_list", JSONArray()).put("region", "CN")
            .put("tz_name", "Asia/Shanghai").put("tz_offset", 28800).put("sim_region", "cn")
            .put("sim_serial_number", JSONArray()).put("oaid_may_support", false)
            .put("req_id", uuid()).put("device_platform", "android")
            .put(
                "custom", JSONObject()
                    .put("account_region_src", "fallback").put("host_bit", 64)
                    .put("account_region", "cn").put("dragon_device_type", "phone")
            )
            .put("pre_installed_channel", "").put("apk_first_install_time", now)
            .put("is_system_app", 0).put("sdk_flavor", "china").put("guest_mode", 0)
        val json = JSONObject().put("magic_tag", "ss_app_log").put("header", header).put("_gen_time", now)
        val body = ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
        val url = "https://log5-applog-lf.fqnovel.com/service/2/device_register/" +
            "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .header("User-Agent", guoyingAppUa)
            .header("Content-Type", "application/octet-stream;tt-data=a")
            .header("log-encode-type", "gzip")
            .header("x-ss-dp", "8662")
            .header("x-ss-req-ticket", now.toString())
            .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
            .header("lc", "101")
            .header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
            .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
            .header("x-ss-stub", md5HexUpper(body))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                var bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.size > 38 && bytes[0] == 0x74.toByte() && bytes[1] == 0x63.toByte()) bytes = ttDecrypt(bytes)
                val text = String(bytes, Charsets.UTF_8)
                if (resp.code != 200 || text.isBlank()) {
                    println("HGREG[R2] register http=${resp.code} len=${bytes.size} head=${text.take(160)}")
                    return null
                }
                val obj = JSONObject(text)
                fun idOf(a: String, b: String) = obj.optString(a).ifBlank { obj.optString(b) }
                val did = idOf("device_id_str", "device_id")
                val iid = idOf("install_id_str", "install_id")
                val cookies = linkedMapOf<String, String>()
                resp.headers("Set-Cookie").forEach { h ->
                    h.split(';').first().trim().let { pair ->
                        val k = pair.substringBefore('=').trim()
                        val v = pair.substringAfter('=').trim()
                        if (v.isNotEmpty()) cookies[k] = v
                    }
                }
                println("HGREG[R2] register http=${resp.code} did=$did iid=$iid new_user=${obj.optInt("new_user")} cookies=${cookies.keys}")
                if (did.isBlank() || iid.isBlank() || did == "0" || iid == "0") null
                else HongguoDeviceRegister.Identity(
                    deviceId = did, installId = iid, cdid = UUID.randomUUID().toString(), openUdid = "",
                    cookies = cookies.filterKeys { it in setOf("install_id", "ttreq", "store-region", "store-region-src") },
                    deviceToken = obj.optString("device_token")
                )
            }
        } catch (e: Exception) {
            println("HGREG[R2] register EXC ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- 果映 getVideoTab 同形态请求（V5 签名，_rticket 位置可指定） ----

    /** V5 签名核心（HongguoPlaybackV5.signWith 同构），仅 _rticket 插入位置与 body MD5 口径可控。 */
    private class V5Signed(val query: String, val ts: Int, val ticket: String, val medusa: String, val helios: String)

    private fun v5Sign(base: List<Pair<String, String>>, ticketAfter: String, bodyMd5: ByteArray): V5Signed {
        val now = System.currentTimeMillis()
        val ts = (now / 1000).toInt()
        val random = SecureRandom()
        for (offset in 0 until 1024) {
            val ticket = now + offset
            val pairs = ArrayList<Pair<String, String>>(base.size + 1)
            for ((k, v) in base) {
                pairs.add(k to v)
                if (k == ticketAfter) pairs.add("_rticket" to ticket.toString())
            }
            val query = HongguoPlaybackV5.encodeQuery(pairs)
            val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
            var iv = HongguoCrypto.ivTransform(queryHash, 0x20230928)
            iv = HongguoCrypto.ivTransform(bodyMd5, iv)
            iv = HongguoCrypto.ivTransform(HongguoCrypto.leU32(ts), iv)
            if (iv and 15 != 12) continue
            fun u32() = random.nextInt()
            return V5Signed(
                query, ts, ticket.toString(),
                HongguoPlaybackV5.medusa(query, queryHash, bodyMd5, ts, HongguoPlaybackV5.Entropy(u32(), u32(), u32(), u32())),
                HongguoCrypto.helios(ts, random.nextInt(), "646637794")
            )
        }
        throw IllegalStateException("v5 branch unavailable")
    }

    private fun shoot(tag: String, method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?, client: Call.Factory) {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (method == "POST") post((body ?: ByteArray(0)).toRequestBody("application/json; charset=UTF-8".toMediaType()))
        }.build()
        val out = try {
            client.newCall(req).execute().use { "${it.code}/${it.body?.bytes()?.size ?: -1}" }
        } catch (e: Exception) {
            "EXC ${e.javaClass.simpleName}: ${e.message}"
        }
        println("HGREG[$tag] $out")
    }

    /** video_tab 请求形态（果映 getVideoTab 为基准，逐项退化成 aston 形态）。 */
    private data class TabShape(
        val tag: String,
        val trailingSlash: Boolean = false,
        val code: String = "74532",
        val name: String = "7.4.5.32",
        val ua: String = GUOYING_TAB_UA,
        val accept: String = "application/json",
        /** 果映给签名器传空数组：带 x-ss-stub + Content-Type，Medusa 用空 body 的 MD5。 */
        val stub: Boolean = true,
        /** false = aston 口径（无 body，Medusa 用全零 MD5）。 */
        val emptyBodyMd5: Boolean = true,
        /** _rticket 紧随哪个键之后（果映=device_id 之后；aston/我方 sign=末尾）。 */
        val ticketAfter: String = "device_id",
        val extraHeaders: List<Pair<String, String>> = emptyList()
    )

    /** 果映 MobilePlayback.getVideoTab 同构（形态可参数化）。 */
    private fun tabGet(tag: String, id: HongguoDeviceRegister.Identity, client: Call.Factory, shape: TabShape = TabShape(tag)) {
        val s = tabShapeSign(id, shape)
        val path = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1" +
            (if (shape.trailingSlash) "/" else "")
        val headers = mutableListOf(
            "User-Agent" to shape.ua,
            "Accept" to shape.accept,
            "x-khronos" to s.ts.toString(),
            "x-helios" to s.helios,
            "x-medusa" to s.medusa
        )
        if (shape.stub) {
            headers.add("Content-Type" to "application/json; charset=utf-8")
            headers.add("x-ss-stub" to md5HexUpper(ByteArray(0)))
        }
        headers.addAll(shape.extraHeaders)
        shoot(shape.tag, "GET", "$path?${s.query}", headers, null, client)
    }

    private fun tabShapeSign(id: HongguoDeviceRegister.Identity, shape: TabShape): V5Signed {
        val base = listOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to shape.code, "version_name" to shape.name,
            "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12",
            "update_version_code" to shape.code,
            "iid" to id.installId, "cdid" to id.cdid, "device_id" to id.deviceId,
            "video_id" to vid, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024",
            "source" to "0", "from_video_id" to ""
        )
        val bodyMd5 = if (shape.emptyBodyMd5) HongguoCrypto.md5(ByteArray(0)) else ByteArray(16)
        return v5Sign(base, shape.ticketAfter, bodyMd5)
    }

    /** 同刻对照：aston 形态（14:00~14:15 全空）vs 果映形态，并把果映形态逐项退化。 */
    @Test
    fun probeTabShapes() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HGREG[shape] register EXC ${e.javaClass.simpleName}: ${e.message}"); return
        }
        println("HGREG[shape] register did=${id.deviceId}")
        Thread.sleep(1500)
        val guoying = TabShape("S-guoying")
        for (shape in listOf(
            // 基线：果映完整形态
            guoying,
            // 14:00~14:15 全空的那种：尾斜线 + 74332 + aston UA + 无 stub + 全零 MD5 + _rticket 末尾
            TabShape(
                "S-aston-full", trailingSlash = true, code = "74332", name = "7.4.3.32", ua = ASTON_RECOVERY_UA,
                accept = "application/json; charset=utf-8", stub = false, emptyBodyMd5 = false,
                ticketAfter = "from_video_id",
                extraHeaders = listOf("Accept-Language" to "zh-CN,zh;q=0.9")
            ),
            // 我方现成 HongguoPlaybackV5.sign(body=null) 的口径：无 stub + 全零 MD5 + _rticket 末尾
            guoying.copy(tag = "S-native-sign", stub = false, emptyBodyMd5 = false, ticketAfter = "from_video_id"),
            guoying,
            guoying.copy(tag = "S-slash", trailingSlash = true),
            guoying.copy(tag = "S-74332", code = "74332", name = "7.4.3.32"),
            guoying.copy(tag = "S-nostub", stub = false),
            guoying.copy(tag = "S-recoveryua", ua = ASTON_RECOVERY_UA)
        )) {
            tabGet(shape.tag, id, tncHttp, shape)
            Thread.sleep(25_000)
        }
    }

    // ---- 我方现行 preload/v1（HongguoPlay.kotlinSignedPost 同构） ----

    private fun traceId(): String {
        val hex = UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    private fun preloadPost(tag: String, id: HongguoDeviceRegister.Identity, client: Call.Factory) {
        val q = java.util.LinkedHashMap<String, String>()
        q["device_id"] = id.deviceId
        q["iid"] = id.installId
        if (id.deviceToken.isNotEmpty()) q["device_token"] = id.deviceToken
        q["ac"] = "wifi"; q["channel"] = "update_64"; q["aid"] = "8662"
        q["app_name"] = "novelread"
        q["version_code"] = "74532"; q["version_name"] = "7.4.5.32"
        q["device_platform"] = "android"; q["os"] = "android"; q["ssmix"] = "a"
        q["device_type"] = "25053RT47C"
        q["device_brand"] = "Redmi"; q["language"] = "zh"
        q["os_api"] = "36"; q["os_version"] = "16"
        q["manifest_version_code"] = "74532"
        q["resolution"] = "1280*2772"; q["dpi"] = "520"
        q["update_version_code"] = "74532"; q["host_abi"] = "arm64-v8a"
        q["dragon_device_type"] = "phone"; q["pv_player"] = "74532"
        q["compliance_status"] = "0"; q["need_personal_recommend"] = "1"
        q["player_so_load"] = "1"; q["is_android_pad_screen"] = "0"
        val body = gzip(
            JSONObject()
                .put(
                    "biz_param", JSONObject()
                        .put("detail_page_version", 0).put("device_level", 4)
                        .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                        .put("need_all_video_definition", true).put("need_mp4_align", false)
                        .put("use_os_player", false).put("use_server_dns", true)
                        .put("use_server_dns_scene", "cold_start_stage").put("video_platform", 1024)
                )
                .put("dr_scene", "preload")
                .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(vid)))
                .toString().toByteArray(Charsets.UTF_8)
        )
        val signed = HongguoCrypto.signRequest(q, body, System.currentTimeMillis(), id.deviceId, targetBranch = 0, version = "7.4.5.32")
        val nowSec = signed.headers["X-Khronos"]?.toIntOrNull() ?: (System.currentTimeMillis() / 1000).toInt()
        fun b64le32(v: Int) = java.util.Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        )
        val ticket = signed.headers["X-SS-Req-Ticket"] ?: ""
        val rnd = ByteArray(4).also { SecureRandom().nextBytes(it) }
        shoot(
            tag, "POST",
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1?${signed.query}",
            listOf(
                "User-Agent" to "com.phoenix.read/74532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
                    "Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "X-SS-DP" to "8662",
                "X-Tt-Store-Region" to "cn",
                "X-Tt-Store-Region-Src" to "did",
                "X-XS-From-Web" to "0",
                "Sdk-Version" to "2",
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "x-ss-req-ticket" to ticket,
                "sdk-version" to "2",
                "passport-sdk-version" to "5051452",
                "x-xs-from-web" to "0",
                "x-khronos" to nowSec.toString(),
                "x-ladon" to java.util.Base64.getEncoder().encodeToString(rnd),
                "x-argus" to b64le32(nowSec),
                "x-gorgon" to (signed.headers["X-Gorgon"] ?: ""),
                "x-helios" to HongguoArgus.helios(nowSec),
                "x-medusa" to HongguoPlaybackV5.medusaHeaderFor(signed.query, body, nowSec),
                "Content-Type" to "application/json; charset=UTF-8",
                "x-vc-bdturing-sdk-version" to "4.0.3.cn",
                "x-reading-request" to "$ticket-${SecureRandom().nextLong() and 0xffffffffL}",
                "x-ss-stub" to (signed.headers["X-SS-STUB"] ?: ""),
                "Content-Encoding" to "gzip",
                "lc" to "101",
                "x-ss-dp" to "8662",
                "x-tt-trace-id" to traceId()
            ),
            body, client
        )
    }

    /** 注册来源 × 出口 DNS 交错对照，再各补一枪现行 preload/v1。 */
    @Test
    fun probeRegisterSource() {
        val r1 = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HGREG[R1] register EXC ${e.javaClass.simpleName}: ${e.message}"); return
        }
        println("HGREG[R1] register did=${r1.deviceId} iid=${r1.installId}")
        Thread.sleep(1500)
        val r2 = guoyingRegister(sysHttp) ?: return
        Thread.sleep(1500)
        for ((tag, id, client) in listOf(
            Triple("tab-R1-tnc", r1, tncHttp),
            Triple("tab-R2-tnc", r2, tncHttp),
            Triple("tab-R1-sysdns", r1, sysHttp),
            Triple("tab-R2-sysdns", r2, sysHttp),
            Triple("tab-R1-tnc-2", r1, tncHttp),
            Triple("tab-R2-tnc-2", r2, tncHttp)
        )) {
            tabGet(tag, id, client)
            Thread.sleep(25_000)
        }
        preloadPost("preload-R2-tnc", r2, tncHttp)
        Thread.sleep(25_000)
        preloadPost("preload-R1-tnc", r1, tncHttp)
    }

    /**
     * 单枪探测：我方注册 + 直接走 App 实现（反射调 HongguoPlay.fetchTabEntry，含 tabEntryOf），
     * 完整 entry 落盘 tab_dump.json 并打印片源档位。验证的是将要装机的请求形态本身；
     * 不调 parseEntry（依赖 android.util.Base64，JVM 不可用）。
     * 用途——长时静默后判断限流是否解除（每次只花 1 个请求，避免探测本身续封）。
     */
    @Test
    fun probeOnce() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HGREG[once] register EXC ${e.javaClass.simpleName}: ${e.message}"); return
        }
        println("HGREG[once] register did=${id.deviceId}")
        Thread.sleep(1500)
        HongguoPlay.resetCooldownForTest()
        val fetch = HongguoPlay::class.java.getDeclaredMethod(
            "fetchTabEntry", String::class.java, HongguoDeviceRegister.Identity::class.java
        ).apply { isAccessible = true }
        val started = System.currentTimeMillis()
        val out = try {
            val entry = fetch.invoke(HongguoPlay(), vid, id) as JSONObject
            val ms = System.currentTimeMillis() - started
            java.io.File(
                java.io.File(System.getProperty("user.dir")).parentFile, ".trae/hg-log-1123/tab_dump.json"
            ).writeText(entry.toString())
            val raw = entry.opt("video_model")
            val model = when (raw) {
                is String -> JSONObject(raw)
                is JSONObject -> raw
                else -> null
            }
            if (model == null) {
                "OK-without-model keys=${entry.keys().asSequence().toList()} ${ms}ms"
            } else {
                val items = ArrayList<JSONObject>()
                when (val list = model.opt("video_list")) {
                    is JSONArray -> for (i in 0 until list.length()) list.optJSONObject(i)?.let { items += it }
                    is JSONObject -> for (k in list.keys()) list.optJSONObject(k)?.let { items += it }
                }
                val rows = items.joinToString(" | ") {
                    "${it.optString("codec_type")}/${it.optString("definition")}/" +
                        "${it.optInt("vwidth")}x${it.optInt("vheight")}/url=${it.optString("main_url").isNotEmpty()}"
                }
                "OK ${ms}ms duration=${model.optDouble("video_duration")} " +
                    "spade_a=${model.optString("spade_a").length}ch kid=${model.optString("kid").length}ch " +
                    "big_thumbs=${model.optJSONArray("big_thumbs")?.length() ?: -1} rows=${items.size} [$rows]"
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            "FAIL ${e.targetException.javaClass.simpleName}: ${e.targetException.message}"
        }
        println("HGREG[once] $out")
    }

    private companion object {
        const val GUOYING_TAB_UA =
            "com.phoenix.read/74532 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
        const val ASTON_RECOVERY_UA = "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; " +
            "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    }
}
