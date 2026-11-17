package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V5 播放签名移植对拍：向量来自 openhongguo PR#13（Go 参考实现）
 * 的固定输入输出（v5_vectors.json，见仓库 internal/protocol/sign/
 * v5_vector_export_test.go）。逐层比对定位移植差异。
 */
class HongguoPlaybackV5VectorTest {

    private val vec: JSONObject = java.io.File(
        javaClass.classLoader!!.getResource("v5_vectors.json")!!.toURI()
    ).readText(Charsets.UTF_8).let { JSONObject(it) }

    private fun hex(s: String) = HongguoCrypto.hexToBytes(s)
    private fun toHex(b: ByteArray) = HongguoCrypto.toHex(b)

    private val pairs = listOf(
        "aid" to "8662", "app_name" to "novelread",
        "version_code" to "74332", "version_name" to "7.4.3.32",
        "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12",
        "update_version_code" to "74332",
        "iid" to "7400000000000000001",
        "cdid" to "abcdef0123456789abcdef0123456789",
        "device_id" to "7300000000000000002"
    )

    private val ent = HongguoPlaybackV5.Entropy(
        vec.getJSONObject("entropy").getLong("MessageRandom").toInt(),
        vec.getJSONObject("entropy").getLong("Random").toInt(),
        vec.getJSONObject("entropy").getLong("PackedRandom").toInt(),
        vec.getJSONObject("entropy").getLong("HeliosRandom").toInt()
    )

    private val body = hex(vec.getString("bodyGzipHex"))
    private val nowMillis = vec.getLong("nowMillis")
    private val started = vec.getLong("started")
    private val nonce = vec.getString("nonce")

    @Test
    fun t01_query() {
        val withTicket = pairs + ("_rticket" to (nowMillis + vec.getLong("offset")).toString())
        assertEquals(vec.getString("query"), HongguoPlaybackV5.encodeQuery(withTicket))
    }

    @Test
    fun t02_queryHash_and_bodyMd5() {
        assertEquals(vec.getString("queryHash"), toHex(HongguoCrypto.sm3(vec.getString("query").toByteArray())))
        assertEquals(vec.getString("bodyMd5"), toHex(HongguoCrypto.md5(body)))
    }

    @Test
    fun t03_branch_iv() {
        val ts = (nowMillis / 1000).toInt()
        val withTicket = pairs.toMutableList()
        withTicket.add("_rticket" to "")
        var hitIv = 0
        var hitOff = -1L
        for (offset in 0L until 1024) {
            withTicket[withTicket.size - 1] = "_rticket" to (nowMillis + offset).toString()
            val query = HongguoPlaybackV5.encodeQuery(withTicket)
            var iv = HongguoCrypto.ivTransform(HongguoCrypto.sm3(query.toByteArray()), 0x20230928)
            iv = HongguoCrypto.ivTransform(HongguoCrypto.md5(body), iv)
            iv = HongguoCrypto.ivTransform(HongguoCrypto.leU32(ts), iv)
            if (iv and 15 == 12) { hitIv = iv; hitOff = offset; break }
        }
        assertEquals(vec.getLong("offset"), hitOff)
        assertEquals(vec.getLong("branchIv").toInt(), hitIv)
    }

    @Test
    fun t04_digest_hashV5() {
        assertEquals(
            vec.getString("digest"),
            toHex(HongguoPlaybackV5.hashV5(hex(vec.getString("queryHash")), hex(vec.getString("bodyMd5")), vec.getLong("ts").toInt()))
        )
    }

    @Test
    fun t05_helios() {
        val h = HongguoCrypto.helios(
            vec.getLong("ts").toInt(),
            vec.getJSONObject("entropy").getLong("HeliosRandom").toInt(),
            "646637794"
        )
        assertEquals(vec.getString("helios"), h)
    }

    @Test
    fun t06_keyHash() {
        val signKey = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
        val (hash, mask) = HongguoPlaybackV5.keyHash(signKey, ent.random)
        assertEquals(vec.getString("keyHash"), toHex(hash))
        assertEquals(vec.getString("keyMask"), toHex(mask))
    }

    @Test
    fun t07_xmxor() {
        val msg = hex(vec.getString("medusaMsg"))
        val (hash, _) = HongguoPlaybackV5.keyHash(
            hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d"), ent.random
        )
        assertEquals(vec.getString("xmxorOut"), toHex(HongguoPlaybackV5.xmxor(msg, hash)))
    }

    @Test
    fun t08_encryptV5() {
        val signKey = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
        assertEquals(
            vec.getString("encrypted"),
            toHex(HongguoPlaybackV5.encryptV5(
                hex(vec.getString("packed")),
                HongguoCrypto.md5(signKey.copyOf(16)),
                HongguoCrypto.md5(signKey.copyOfRange(16, 32)),
                vec.getLong("ts").toInt()
            ))
        )
    }

    @Test
    fun t09_medusa() {
        assertEquals(
            vec.getString("medusa"),
            HongguoPlaybackV5.medusa(
                vec.getString("query"), hex(vec.getString("queryHash")),
                hex(vec.getString("bodyMd5")), vec.getLong("ts").toInt(), ent, started, nonce
            )
        )
    }

    @Test
    fun t10_sign_full() {
        val signed = HongguoPlaybackV5.signWith(pairs, nowMillis, body, ent, started, nonce)
        assertEquals(vec.getString("query"), signed.query)
        val headers = signed.headers.toMap()
        assertEquals(vec.getJSONObject("headers").getString("x-khronos"), headers["x-khronos"])
        assertEquals(vec.getJSONObject("headers").getString("x-helios"), headers["x-helios"])
        assertEquals(vec.getJSONObject("headers").getString("x-medusa"), headers["x-medusa"])
        assertEquals(vec.getJSONObject("headers").getString("x-ss-stub"), headers["x-ss-stub"])
    }
}
