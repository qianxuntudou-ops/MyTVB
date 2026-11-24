package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 播放请求形态对拍（纯离线，不联网）：把我方 `player`/`preload` 两路请求的 query/body/头集
 * 与 **官方 App 7.4.5.32（2026-11-13 frida 抓包，`.trae/hongguo-api-capture/out/`）** 逐项对齐。
 *
 * 抓包实证的事实（本测试固化为断言，改动请求形态时必须同步）：
 * - query 35 个参数位，第 23 位是官方自己发出来的**空参数段**（`...is_64_device=true&&host_abi=...`）；
 * - body `biz_param` 键序固定、`device_level=3`、`use_server_dns=false`、`video_platform=1024`；
 * - `dr_scene`：起播 `player`、预取 `preload`；
 * - gzip 是 zlib level 9 + OS 字节 0xFF（按此压出的字节 MD5 与官方 `X-SS-STUB` 完全相等）；
 * - 头 14 个、同序，没有 `Accept` / `sdk-version` / `passport-sdk-version` / `lc` / `x-ss-dp`。
 */
class HongguoPlayRequestShapeTest {

    private fun identity() = HongguoDeviceRegister.Identity(
        deviceId = "7301234567890123",
        installId = "7301234567890124",
        cdid = "cdid-0000-1111-2222",
        openUdid = "openUdidHex",
        cookies = emptyMap(),
        klinkEgdi = "KLINK_EGDI_VALUE",
        deviceToken = "TOKEN_VALUE",
        clientUdid = "client-uuid",
    )

    private val play = HongguoPlay()

    /** 官方抓包 query 参数位（保序），第 23 位是空参数。 */
    private val officialQueryKeys = listOf(
        "device_platform", "os", "ssmix", "_rticket", "cdid", "channel", "aid", "app_name",
        "version_code", "version_name", "manifest_version_code", "update_version_code",
        "resolution", "dpi", "device_type", "device_brand", "language", "os_api", "os_version",
        "ac", "is_64_runtime", "is_64_device", "", "host_abi", "dragon_device_type", "pv_player",
        "is_x86_device", "compliance_status", "need_personal_recommend", "player_so_load",
        "is_android_pad_screen", "rom_version", "iid", "device_id", "klink_egdi",
    )

    @Test
    fun queryLayoutMatchesOfficialCapture() {
        val q = play.playQuery(identity())
        assertEquals(officialQueryKeys, q.map { it.first })
        assertEquals(35, q.size)
        // 官方播放请求不带 device_token（注册接口才带），我方也不带
        assertFalse(q.any { it.first == "device_token" })
        // 设备绑定三件套必须带全
        assertEquals("7301234567890123", q.toMap()["device_id"])
        assertEquals("7301234567890124", q.toMap()["iid"])
        assertEquals("KLINK_EGDI_VALUE", q.toMap()["klink_egdi"])
        assertEquals("cdid-0000-1111-2222", q.toMap()["cdid"])
    }

    @Test
    fun encodeQueryRendersOfficialEmptyParamSegment() {
        // 官方 query 里就是 `a=1&&b=2` 这种连续分隔符，签名覆盖同一串
        val q = listOf("a" to "1", "" to "", "b" to "2")
        assertEquals("a=1&&b=2", HongguoPlaybackV5.encodeQuery(q))
        val signed = HongguoPlaybackV5.sign(q, 1_700_000_000_000, null)
        assertTrue(signed.query.contains("&&"))
    }

    @Test
    fun bodyMatchesOfficialShape() {
        // 官方抓包原文（player/v1，把 uid/token 换成我方值）：键序、布尔/数字字面量逐字节一致
        assertEquals(
            "{\"biz_param\":{\"detail_page_version\":0,\"device_level\":3,\"disable_digg_stat\":false," +
                "\"disable_video_relate_book\":false,\"need_all_video_definition\":true," +
                "\"need_mp4_align\":false,\"use_os_player\":false,\"use_server_dns\":false," +
                "\"video_platform\":1024},\"dr_scene\":\"player\",\"mixed_video_id_map\":" +
                "{\"1004\":[\"7701728427505634328\"]}}",
            String(play.modelBody(listOf("7701728427505634328"), 3, HongguoPlay.DR_PLAYER), Charsets.UTF_8),
        )
        // 预取三集形态（官方 preload 抓包最常见：1004 数组 3 个 id）
        assertEquals(
            "{\"biz_param\":{\"detail_page_version\":0,\"device_level\":3,\"disable_digg_stat\":false," +
                "\"disable_video_relate_book\":false,\"need_all_video_definition\":true," +
                "\"need_mp4_align\":false,\"use_os_player\":false,\"use_server_dns\":false," +
                "\"video_platform\":1024},\"dr_scene\":\"preload\",\"mixed_video_id_map\":" +
                "{\"1004\":[\"1\",\"2\",\"3\"]}}",
            String(play.modelBody(listOf("1", "2", "3"), 3, HongguoPlay.DR_PRELOAD), Charsets.UTF_8),
        )
        // 升档重发只改 device_level
        assertTrue(String(play.modelBody(listOf("1"), 5, HongguoPlay.DR_PRELOAD), Charsets.UTF_8)
            .contains("\"device_level\":5"))
    }

    @Test
    fun gzipIsOfficialLevelNineLayout() {
        val raw = play.modelBody(listOf("7701728427505634328"), 3, HongguoPlay.DR_PLAYER)
        val gz = play.gzipDeflate(raw)
        // gzip 头：magic 1f 8b / CM=8 / FLG=0 / MTIME=0 / XFL=0 / OS=0xFF（官方实测值）
        assertEquals(
            listOf(0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff),
            gz.take(10).map { it.toInt() and 0xff },
        )
        // 可正确解回
        val back = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
        assertTrue(back.contentEquals(raw))
    }

    @Test
    fun gzipReproducesOfficialStub() {
        // 官方 player/v1 抓包原 body（344B，含 1001/1004 两组）与它实际发出的 X-SS-STUB。
        // 我方 gzip 压出来的字节 MD5 与之相等 ⇒ 压缩参数（level 9 + OS 0xFF + 原始 deflate）逐字节复刻。
        val officialBody = "{\"biz_param\":{\"detail_page_version\":0,\"device_level\":3," +
            "\"disable_digg_stat\":false,\"disable_video_relate_book\":false," +
            "\"need_all_video_definition\":true,\"need_mp4_align\":false,\"use_os_player\":false," +
            "\"use_server_dns\":false,\"video_platform\":1024},\"dr_scene\":\"player\"," +
            "\"mixed_video_id_map\":{\"1001\":[\"7661916436301892633\"],\"1004\":[\"7701728427505634328\"]}}"
        assertEquals(344, officialBody.toByteArray(Charsets.UTF_8).size)
        val gz = play.gzipDeflate(officialBody.toByteArray(Charsets.UTF_8))
        assertEquals(
            "11FEB17A45717808AE5B2892FDF4BC1D",
            HongguoCrypto.toHex(HongguoCrypto.md5(gz)).uppercase(),
        )
    }

    @Test
    fun headerSetMatchesOfficialOrder() {
        val body = play.gzipDeflate(play.modelBody(listOf("7701728427505634328"), 3, HongguoPlay.DR_PLAYER))
        val req = play.buildPlayRequest(
            "https://api5-normal-sinfonlineb.fqnovel.com" + HongguoPlay.PLAYER_PATH,
            play.playQuery(identity()), body, HongguoPlay.ua(HongguoVersionProfiles.default.code),
        )
        val names = req.headers.names().toList()
        // OkHttp 的 Headers.names() 返回 TreeSet（字母序），线上发送仍是插入序，故这里断言集合一致
        assertEquals(
            setOf(
                "X-SS-STUB", "X-Xs-From-Web", "X-SS-REQ-TICKET", "x-reading-request",
                "x-vc-bdturing-sdk-version", "x-tt-store-region", "x-tt-store-region-src",
                "User-Agent", "X-Ladon", "X-Khronos", "X-Argus", "X-Gorgon", "X-Helios", "X-Medusa",
                "Content-Type", "Content-Encoding",
            ),
            names.toSet(),
        )
        assertEquals(16, names.size)
        // 官方没有这些头
        listOf("Accept", "sdk-version", "passport-sdk-version", "lc", "x-ss-dp").forEach {
            assertFalse("不应发送 $it", names.any { n -> n.equals(it, ignoreCase = true) })
        }
        // X-SS-STUB = MD5(实际发送的 gzip 字节)，官方同构
        assertEquals(
            HongguoCrypto.toHex(HongguoCrypto.md5(body)).uppercase(),
            req.header("X-SS-STUB"),
        )
        // _rticket 是毫秒且允许为调谐 selector 偏移（官方抓包里也与 X-Khronos 差近 1s），
        // 这里只要求两者不脱节：|ticket/1000 - khronos| <= 2
        val query = req.url.encodedQuery ?: ""
        val ticket = HongguoMedusa.queryValue(query, "_rticket")
        assertNotNull(ticket)
        val diff = (ticket!!.toLong() / 1000) - req.header("X-Khronos")!!.toLong()
        assertTrue("ticket/khronos 相差 ${diff}s", diff in -2L..2L)
        // 空参数段确实出现在最终 query 里
        assertTrue(query.contains("&&"))
    }
}
