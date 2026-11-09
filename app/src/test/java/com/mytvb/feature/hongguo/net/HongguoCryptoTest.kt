package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.TreeMap

/**
 * 红果签名栈验证：
 * 1) SM3 用国标 GB/T 32905 公开向量验证（手写压缩函数最易错，先钉死基座）；
 * 2) 匿名 feed 联网冒烟——直接验证 Gorgon/Helios/Medusa 全套信封能否过风控。
 */
class HongguoCryptoTest {

    @Test
    fun sm3StandardVectors() {
        // 权威向量：python gmssl 库（GB/T 32905 参考实现）生成
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            HongguoCrypto.toHex(HongguoCrypto.sm3("abc".toByteArray()))
        )
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            HongguoCrypto.toHex(HongguoCrypto.sm3(ByteArray(0)))
        )
        // 64 字节：跨块（两轮分组），验证块间衔接
        assertEquals(
            "616ec433c359e7c2b19f360e2b8f2a1b6e9ed76b8dc1a7d207b31a5341c611e9",
            HongguoCrypto.toHex(HongguoCrypto.sm3(ByteArray(64) { 'a'.code.toByte() }))
        )
        // 65 字节：跨块 + 长度位独立成块（padding 整块场景）
        assertEquals(
            "3d1d94afa238ec3e2bbc20ad504702b24c16f2889c94973f2f8da3526c44e4bc",
            HongguoCrypto.toHex(HongguoCrypto.sm3(ByteArray(65) { 'a'.code.toByte() }))
        )
    }

    @Test
    fun base64MatchesStandard() {
        assertEquals("aGVsbG8=", HongguoCrypto.base64("hello".toByteArray()))
        assertEquals("", HongguoCrypto.base64(ByteArray(0)))
        assertEquals("AA==", HongguoCrypto.base64(byteArrayOf(0)))
    }

    @Test
    fun gorgonCrossCheck() {
        // 基准来自 .trae/hongguo-open-player app-source.js（线上验证版）node_gorgon_probe.js
        val query = "aid=8662&resolution=1280*2772&ts=1794212010&version_name=7.3.5.32"
        val body = "{\"filter_ids\":\"\",\"req_scene\":\"default\",\"offset\":0,\"limit\":15}".toByteArray()
        assertEquals(
            "84047856400101344d45afa5dfcf57b9bea69da8e1bd1a3fdace",
            HongguoCrypto.gorgon(query, body, 1794212010, 0x5678)
        )
        assertEquals(
            "8404341240011d3d86d6d9fe6cc91364e71df6105ab658b32e07",
            HongguoCrypto.gorgon(query, null, 1794212010, 0x1234)
        )
    }

    @Test
    fun f13AndHeliosCrossCheck() {
        // 基准来自 node_medusa_probe.js（app-source.js 参数化提取）
        val q = HongguoCrypto.hexToBytes("afae9719ffaad5a53e1df100dd9f8ebae249af91ebf265fc8d8febe810dec35a")
        val m = HongguoCrypto.hexToBytes("b9fe69f538f8b325a3466ec43f0f2968")
        assertEquals(
            "7db7f9093b6854b2cf1ed4940737189aed46cce8",
            HongguoCrypto.toHex(HongguoCrypto.f13(2, 1794212010, q, m))
        )
        assertEquals(
            "7026f5fcd98ddf11a4d7c6bf6267f0cebe6a65c5",
            HongguoCrypto.toHex(HongguoCrypto.f13(14, 1794212010, q, m))
        )
        assertEquals(
            "8N68mira1ay5oL2qHRx72QeYJamrsjG7Nnep6e4KlrQ6lDSs",
            HongguoCrypto.helios(1794212010, -0x65432110) // 0x9ABCDEF0
        )
    }

    @Test
    fun anonymousFeedSmoke() {
        // 联网冒烟：匿名 landpage feed。签名错误时服务器返回 HTTP 200 + 空体
        //（Go 版实测行为），客户端会轮换身份重试 3 次后抛异常。
        HongguoClient.HongguoClientDebug.enabled = false
        val client = HongguoClient()
        val page = client.category("/category/short-drama", 0, "")
        assertTrue("feed 应返回至少一条剧目", page.items.isNotEmpty())
        val first = page.items.first()
        assertTrue("剧目应有 seriesId", first.seriesId.isNotEmpty())
        assertTrue("剧目应有标题", first.title.isNotEmpty())
        println("HG-SMOKE first=${first.title} id=${first.seriesId} eps=${first.episodeCount} hasMore=${page.hasMore} nextOffset=${page.nextOffset}")
    }

    @Test
    fun medusaRelayDump() {
        // 中继对拍：把 Kotlin 生成的播放请求(url/body/headers)落盘，由 node_medusa_relay.js
        // 用 Node 线上版信封逐头替换验证（定位 x-medusa 翻译是否正确）
        val payload = ("{\"biz_param\":{\"detail_page_version\":0,\"device_level\":3,\"disable_digg_stat\":false," +
            "\"need_all_video_definition\":true,\"need_mp4_align\":false,\"use_os_player\":false,\"use_server_dns\":false," +
            "\"video_platform\":1024},\"mixed_video_id_map\":{\"1004\":[\"7701937936731737112\"]}}")
            .toByteArray(Charsets.UTF_8)
        val q = TreeMap<String, String>()
        q["ac"] = "wifi"; q["aid"] = "8662"; q["app_name"] = "novelread"
        q["channel"] = "update_64"; q["device_brand"] = "Redmi"
        q["device_id"] = "1234567890123456789"
        q["device_platform"] = "android"; q["device_type"] = "25053RT47C"
        q["dpi"] = "520"; q["iid"] = "9876543210987654321"
        q["cdid"] = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        q["openudid"] = "0011223344556677"
        q["language"] = "zh"; q["manifest_version_code"] = "71332"
        q["os"] = "android"; q["os_api"] = "36"; q["os_version"] = "16"
        q["resolution"] = "1280*2772"; q["ssmix"] = "a"
        q["host_abi"] = "arm64-v8a"; q["dragon_device_type"] = "phone"
        q["pv_player"] = "71332"; q["compliance_status"] = "0"
        q["need_personal_recommend"] = "1"; q["player_so_load"] = "1"
        q["is_android_pad_screen"] = "0"
        q["update_version_code"] = "71332"; q["version_code"] = "71332"
        q["version_name"] = "7.1.3.32"
        val signed = HongguoCrypto.signRequest(
            q, payload, System.currentTimeMillis(), "1234567890123456789",
            targetBranch = 0, version = "7.1.3.32"
        )
        val out = JSONObject()
        out.put("url", "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?${signed.query}")
        out.put("body", String(payload, Charsets.UTF_8))
        out.put("headers", JSONObject(signed.headers))
        java.io.File("E:/MyTool/MyBLBL/.trae/hg-verify/kotlin_request.json").writeText(out.toString())
        println("HG-RELAY dumped query=${signed.query.take(60)}...")
    }

    @Test
    fun dumpOkHttpRequest() {
        // 原始报文对比：OkHttp 发到本地 dump 服务器（node dump_server.js）
        HongguoPlay.HongguoPlayDebug.baseUrlOverride = "http://127.0.0.1:18123"
        try {
            HongguoPlay().playUrl(
                "7701937936731737112",
                HongguoDeviceRegister.Identity("1234567890123456789", "9876543210987654321", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "0011223344556677")
            )
        } catch (_: Exception) {
        }
        HongguoPlay.HongguoPlayDebug.baseUrlOverride = null
    }

    @Test
    fun minimalPlaySmoke() {
        // 终局对照：真注册身份 vs 假身份，各打一次播放接口（OkHttp 直发）
        val fake = HongguoDeviceRegister.Identity(
            deviceId = "1234567890123456789",
            installId = "9876543210987654321",
            cdid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            openUdid = "0011223344556677"
        )
        val real: HongguoDeviceRegister.Identity? = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-PLAY register fail: ${e.message}")
            null
        }
        Thread.sleep(800)
        val cases = ArrayList<Pair<String, HongguoDeviceRegister.Identity>>()
        real?.let { cases.add("real" to it) }
        cases.add("fake" to fake)
        for ((tag, id) in cases) {
            try {
                val play = HongguoPlay().playUrl("7701937936731737112", id)
                println("HG-PLAY[$tag] OK codec=${play.codec} quality=${play.quality} key=${play.contentKeyHex != null}")
                assertTrue("播放地址应为 https CDN", play.url.startsWith("https://"))
            } catch (e: Exception) {
                println("HG-PLAY[$tag] FAIL ${e.message}")
            }
            Thread.sleep(800)
        }
    }

    @Test
    fun endToEndPlaySmoke() {
        // 端到端：feed → 网页详情（选集）→ 播放地址解析
        val feed = HongguoClient().category("/category/short-drama", 0, "")
        val drama = feed.items.firstOrNull { it.episodeCount > 0 } ?: return
        val detail = HongguoWeb().seriesDetail(drama.seriesId)
        println("HG-E2E detail=${detail.drama.title} episodes=${detail.episodes.size} related=${detail.related.size}")
        val ep = detail.episodes.firstOrNull { !it.locked } ?: detail.episodes.first()
        // 假固定身份即可通过（中继实验 C1 证实），避免注册后立即请求的联动风控
        val identity = HongguoDeviceRegister.Identity(
            deviceId = "1234567890123456789",
            installId = "9876543210987654321",
            cdid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            openUdid = "0011223344556677"
        )
        val play = HongguoPlay().playUrl(ep.videoId, identity)
        println("HG-E2E play vid=${play.vid} codec=${play.codec} quality=${play.quality} ${play.width}x${play.height} key=${play.contentKeyHex != null} url=${play.url.take(80)}")
        assertTrue("播放地址应为 https", play.url.startsWith("https://"))
    }
}
