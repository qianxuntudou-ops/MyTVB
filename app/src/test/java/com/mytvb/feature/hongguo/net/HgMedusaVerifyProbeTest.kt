package com.mytvb.feature.hongguo.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 2026-11-23 medusa 精确移植后的**单枪**真网验证。构造的就是 App `preload` 路由实际要发的请求
 * （同一套 [HongguoPlay.playQuery] / [HongguoPlay.modelBody] / 签名 / 头集 / HTTP 客户端），
 * 但绕过 `fetchPreloadData` 的「空响应升级 device_level 重发」与熔断——严格只发 1 枪。
 *
 * 两段式，避免探针自身的毛病浪费枪：
 *  - 默认 dry-run：只构造请求并落盘，零播放请求（身份首次注册后缓存到本地文件复用）；
 *  - 仓库根存在 `.zcode/hgtv-sign/tools/FIRE` 才真发，发完即删该文件，重跑不会再发。
 *
 * 落盘 `.zcode/hgtv-sign/tools/last_shot.json`（请求头 + query + gzip body + 响应），
 * 供 `.zcode/hgtv-sign/tools/medusa_dec.py` 离线解密核对。被拒立即停手，不要连跑。
 */
class HgMedusaVerifyProbeTest {

    @Test
    fun probeOnce() {
        var root: File? = File(System.getProperty("user.dir") ?: ".")
        while (root != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile
        val dir = File(root ?: error("找不到仓库根（settings.gradle.kts）"), ".zcode/hgtv-sign/tools")
        dir.mkdirs()
        val fire = File(dir, "FIRE")
        val identityFile = File(dir, "probe_identity.json")

        val identity = if (identityFile.exists()) {
            val o = JSONObject(identityFile.readText(Charsets.UTF_8))
            val cookies = o.optJSONObject("cookies") ?: JSONObject()
            HongguoDeviceRegister.Identity(
                deviceId = o.getString("deviceId"), installId = o.getString("installId"),
                cdid = o.getString("cdid"), openUdid = o.getString("openUdid"),
                cookies = cookies.keys().asSequence().associateWith { cookies.getString(it) },
                klinkEgdi = o.optString("klinkEgdi"), deviceToken = o.optString("deviceToken"),
                clientUdid = o.optString("clientUdid"),
            ).also { println("HGMEDUSA[shot] identity cached did=${it.deviceId}") }
        } else {
            val id = try {
                HongguoDeviceRegister.registerFresh()
            } catch (e: Exception) {
                println("HGMEDUSA[shot] register EXC ${e.javaClass.simpleName}: ${e.message}")
                return
            }
            identityFile.writeText(JSONObject().apply {
                put("deviceId", id.deviceId); put("installId", id.installId); put("cdid", id.cdid)
                put("openUdid", id.openUdid); put("cookies", JSONObject(id.cookies))
                put("klinkEgdi", id.klinkEgdi); put("deviceToken", id.deviceToken); put("clientUdid", id.clientUdid)
            }.toString(1), Charsets.UTF_8)
            println("HGMEDUSA[shot] registered did=${id.deviceId} token=${id.deviceToken.length}ch")
            id
        }

        val play = HongguoPlay()
        val ver = HongguoVersionProfiles.default
        // 与 fetchModelData 一致：明文 body 先 gzip，签名与发送用同一份字节
        val body = play.gzipDeflate(play.modelBody(listOf(VID), 3, HongguoPlay.DR_PLAYER))
        val req = play.buildPlayRequest(
            "${HongguoNetProfiles.playHost()}${HongguoPlay.PLAYER_PATH}", play.playQuery(identity), body,
            HongguoPlay.ua(ver.code)
        )
        val record = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("version", "${ver.code}/${ver.versionName}")
            .put("routes", JSONArray(HongguoNetProfiles.playRoutes()))
            .put("url", req.url.toString())
            .put("headers", JSONArray(req.headers.names().map { JSONArray(listOf(it, req.header(it))) }))
            .put("bodyB64", java.util.Base64.getEncoder().encodeToString(body))
        File(dir, "last_shot.json").writeText(record.toString(1), Charsets.UTF_8)
        println("HGMEDUSA[shot] built did=${identity.deviceId} ver=${ver.code}/${ver.versionName} " +
            "headers=${req.headers.size} body=${body.size}B query=${req.url.encodedQuery?.length}ch")
        println("HGMEDUSA[shot] fire=${fire.absolutePath} exists=${fire.exists()} dir=${dir.absolutePath} files=${dir.listFiles()?.map { it.name }}")
        if (!fire.exists()) {
            println("HGMEDUSA[shot] DRY-RUN 未发送；确认后创建 ${fire.path} 再重跑才会真发")
            return
        }
        fire.delete()

        val started = System.currentTimeMillis()
        HongguoHttp.client().newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val ms = System.currentTimeMillis() - started
            val text = String(bytes, Charsets.UTF_8)
            val payload = runCatching { JSONObject(text) }.getOrNull()
            val code = payload?.let { it.optInt("Code", it.optInt("code", -1)) } ?: -1
            val data = payload?.optJSONObject("data")
            val grouped = data?.optJSONObject("1004") ?: data
            val entry = grouped?.optJSONObject(VID) ?: grouped?.optJSONObject("video_model")
            val hasModel = entry?.let { it.has("video_model") || it.has("video_list") } ?: false
            record.put("resp", JSONObject().put("http", resp.code).put("len", bytes.size).put("ms", ms)
                .put("code", code).put("hasModel", hasModel).put("dataKeys", JSONArray(data?.keys()?.asSequence()?.toList() ?: emptyList<String>()))
                .put("head", text.take(800)))
            File(dir, "last_shot.json").writeText(record.toString(1), Charsets.UTF_8)
            println("HGMEDUSA[shot] RESP http=${resp.code} len=${bytes.size} ms=$ms code=$code hasModel=$hasModel")
        }
    }

    private companion object {
        /** 2026-11-23 HGTV 真机请求里实际请求过的分集。 */
        const val VID = "7709197486916652056"
    }
}
