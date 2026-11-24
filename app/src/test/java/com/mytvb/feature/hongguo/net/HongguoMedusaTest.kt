package com.mytvb.feature.hongguo.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * x-medusa 对拍，三层证据由强到弱：
 *
 *  1. **入库合成向量**（`medusa_vectors.json`，虚构身份，CI 必跑）：期望值由已与 HGTV 48 组真实签名
 *     逐字节等价的参考实现产出，覆盖 ts&3 的四个 AES 分支与「画像取默认值」的分支；
 *  2. **HGTV 真实签名金标准**（本地 `.zcode/hgtv-sign/tools/golden.jsonl`，不存在则跳过）：
 *     给定解密得到的全部内部状态，protobuf 逐字节 + x-medusa 全字符串全等；
 *  3. **官方 App 真实签名**（本地 `official_dec.json`）：selector 落在 12 且抓包 URL 精确的请求，
 *     摘要必须与官方 SDK 的输出一致。
 */
class HongguoMedusaTest {

    private fun toHex(b: ByteArray) = HongguoCrypto.toHex(b)

    /** 向上找到仓库根（以 settings.gradle.kts 为标志），再拼本地材料路径；找不到返回 null（用例跳过）。 */
    private fun repoFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return File(dir, relative)
            dir = dir.parentFile
        }
        return null
    }


    private data class Case(
        val name: String, val url: String, val query: String, val body: ByteArray?, val sec: Int, val nowMillis: Long,
        val rnd: LongArray, val signer: HongguoMedusa.Signer, val expectedMedusa: String, val expectedProto: ByteArray,
        val stages: JSONObject? = null
    )

    private fun caseOf(o: JSONObject): Case {
        val st = o.getJSONObject("state")
        val profile = HongguoMedusa.Profile(
            deviceId = st.getString("deviceId"), deviceToken = st.optString("deviceToken"),
            versionName = st.getString("versionName"), channel = st.getString("channel"),
            resolution = st.optString("resolution"), osVersion = st.getString("osVersion"),
            deviceType = st.getString("deviceType"), deviceBrand = st.getString("deviceBrand"),
        )
        val signer = HongguoMedusa.Signer(
            profile, st.getLong("l3StartedAt"), st.getLong("createdAt"), st.getString("processNonce")
        )
        signer.counter = st.getLong("counter")
        signer.tls = st.getLong("tlsIn")
        val arr = o.getJSONArray("rnd")
        return Case(
            name = o.optString("name"),
            url = o.getString("url").also { require('?' in it) { "向量 url 必须带 query: $it" } },
            // 向量存的是完整 URL（HGTV 抓到的原样）；签名入口只收纯 query
            query = o.getString("url").substringBefore('#').substringAfter('?', ""),
            body = if (o.isNull("bodyB64")) null else java.util.Base64.getDecoder().decode(o.getString("bodyB64")),
            sec = o.getInt("sec"), nowMillis = o.getLong("nowMillis"),
            rnd = LongArray(arr.length()) { arr.getLong(it) },
            signer = signer, expectedMedusa = o.getString("expectedMedusa"),
            expectedProto = HongguoCrypto.hexToBytes(o.getString("expectedProto")),
            stages = o.optJSONObject("stages")
        )
    }

    private fun verify(c: Case, label: String) {
        val signed = HongguoMedusa.signWith(c.signer, c.query, c.body, c.sec, c.nowMillis, c.rnd)
        assertEquals("$label: protobuf 明文不一致", toHex(c.expectedProto), toHex(signed.proto))
        if (signed.medusa != c.expectedMedusa && c.stages != null) {
            // 分层定位：期望值来自参考实现，逐层比对报出第一个分叉的阶段
            val queryHash = HongguoCrypto.sm3(c.query.toByteArray(Charsets.UTF_8))
            val bodyMd5 = bodyMd5Of(c.body)
            val digest = HongguoMedusa.digest12(queryHash, bodyMd5, c.sec)
            val check = ((queryHash[0].toLong() and 63L) shl 46) or 0x1800000100000000L or
                ((digest[0].toLong() and 63L) shl 40) or (1L shl 32) or (c.rnd[2] and 0xFFFFFFFFL)
            val flags = ByteArray(8) { i -> (0x8504L ushr (i * 8)).toByte() }
            val st = HongguoMedusa.encryptStages(c.expectedProto, c.sec, c.rnd[1], check, flags)
            val sg = c.stages
            val layers = listOf(
                "hash(SM3 密钥流)" to (sg.getString("hash") to toHex(st.hash)),
                "x(xmxor 混淆)" to (sg.getString("x") to toHex(st.x)),
                "inner(内层包)" to (sg.getString("inner") to toHex(st.inner)),
                "plain(位装入)" to (sg.getString("plain") to toHex(st.plain)),
                "AES iv" to (sg.getString("iv") to toHex(st.iv)),
                "AES 轮密钥" to (sg.getString("rk") to toHex(st.rk)),
            ).toMutableList()
            val steps = listOf("输入异或", "ark0", "sub", "perm", "mix", "ark1", "sub", "ark2", "输出")
            val blkExp = sg.getJSONArray("blk")
            for (k in 0 until blkExp.length()) {
                layers += "AES 第一块·${steps[k]}" to (blkExp.getString(k) to toHex(st.blk[k]))
            }
            layers += "bits(AES 两块)" to (sg.getString("bits") to toHex(st.bits))
            layers += "outp(位写回)" to (sg.getString("outp") to toHex(st.outp))
            for ((name, pair) in layers) {
                if (pair.first != pair.second) {
                    val at = (pair.first.zip(pair.second).indexOfFirst { it.first != it.second } / 2).coerceAtLeast(0)
                    val from = (at - 4).coerceAtLeast(0)
                    fun window(h: String) = h.substring(from * 2).take(64)
                    fail("$label: 加密阶段「$name」不一致（期望 ${pair.first.length / 2} 字节，实际 ${pair.second.length / 2} 字节，首个差异在字节 $at）\n" +
                        "  期望 @$from: ${window(pair.first)}\n  实际 @$from: ${window(pair.second)}")
                }
            }
        }
        assertEquals("$label: x-medusa 不一致", c.expectedMedusa, signed.medusa)
        // 请求自身的 selector 必须落在我方唯一实现的分支上
        assertEquals("$label: selector", 12, HongguoMedusa.selectorOf(HongguoCrypto.sm3(c.query.toByteArray()), bodyMd5Of(c.body), c.sec))
    }

    @Test
    fun syntheticVectors() {
        val text = javaClass.classLoader!!.getResource("medusa_vectors.json")!!.readText(Charsets.UTF_8)
        val arr = JSONArray(text)
        val words = HashSet<Int>()
        for (i in 0 until arr.length()) {
            val c = caseOf(arr.getJSONObject(i))
            verify(c, c.name)
            words += c.sec and 3
        }
        assertTrue("向量应覆盖 ts&3 全部四个分支: $words", words == setOf(0, 1, 2, 3))
    }

    @Test
    fun goldenHgtvReplay() {
        val f = repoFile(".zcode/hgtv-sign/tools/golden.jsonl")
        if (f == null || !f.exists()) {
            println("MEDUSA golden file missing, skipped")
            return
        }
        var n = 0; var bad = 0
        f.readLines().filter { it.isNotBlank() }.forEach { ln ->
            val o = JSONObject(ln)
            n++
            try {
                verify(caseOf(o), "hgtv#$n")
            } catch (e: AssertionError) {
                bad++
                if (bad <= 3) println("MEDUSA DIFF #$n: ${e.message?.take(160)}")
            }
        }
        println("MEDUSA golden hgtv total=$n bad=$bad")
        assertEquals("HGTV 金标准对拍失败 $bad/$n", 0, bad)
    }

    /** 极简 protobuf 顶层字段读取（只取 wire=2 的长度前缀字段）。 */
    private fun lengthDelimited(b: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var i = 0
        fun varint(): Long {
            var v = 0L; var shift = 0
            while (true) {
                val c = b[i++].toInt() and 0xFF
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return v
                shift += 7
            }
        }
        while (i < b.size) {
            val key = varint()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                2 -> {
                    val len = varint().toInt()
                    out[(key ushr 3).toInt()] = b.copyOfRange(i, i + len)
                    i += len
                }
                5 -> i += 4
                else -> throw IllegalStateException("wire type")
            }
        }
        return out
    }

    @Test
    fun officialAppDigest() {
        val f = repoFile(".zcode/hgtv-sign/tools/official_dec.json")
        if (f == null || !f.exists()) {
            println("MEDUSA official samples missing, skipped")
            return
        }
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        var total = 0; var sel12 = 0; var checked = 0; var bad = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            total++
            val proto = HongguoCrypto.hexToBytes(o.getString("proto"))
            val query = o.getString("url").substringBefore('#').substringAfter('?', "")
            val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
            val stub = o.optString("stub").trim()
            val bodyMd5 = if (stub.length == 32) HongguoCrypto.hexToBytes(stub) else ByteArray(16)
            val sec = o.getInt("sec")
            if (HongguoMedusa.selectorOf(queryHash, bodyMd5, sec) != 12) continue
            sel12++
            val fields = lengthDelimited(proto)
            // 抓包记录的 URL 被规范化过的样本种子对不上，排除
            if (!fields[14].contentEquals(queryHash.copyOf(6))) continue
            checked++
            if (!fields[13].contentEquals(HongguoMedusa.digest12(queryHash, bodyMd5, sec))) bad++
        }
        println("MEDUSA official total=$total selector12=$sel12 urlExact=$checked digestBad=$bad")
        // 样本数取决于本机抓包大小：2026-11-13 官方 App 抓包里 selector==12 且有 80 条 URL 精确，
        // 阈值取 40 保证有足够覆盖即可，真正的门是下面的「零不一致」。
        assertTrue("官方样本过少: $checked", checked >= 40)
        assertEquals("摘要与官方 SDK 不一致 $bad/$checked", 0, bad)
    }

    private fun bodyMd5Of(body: ByteArray?): ByteArray =
        if (body == null || body.isEmpty()) ByteArray(16) else HongguoCrypto.md5(body)

    /**
     * 生产入口回归：[HongguoPlaybackV5.signWith] 喂给 medusa 的是纯 query（不带 `?`），签名必须覆盖它。
     * 2026-11-23 曾因签名入口对纯 query 做 `substringAfter('?')` 取到空串，等于给空 query 签名——
     * 离线金标准喂的是完整 URL 所以没暴露，发枪前的 dry-run 解密核对才拦下。
     */
    @Test
    fun productionPathSignsTheQueryItSends() {
        val pairs = listOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74532", "version_name" to "7.4.5.32",
            "channel" to "update_64", "device_platform" to "android", "device_type" to "25053RT47C",
            "os_version" to "16", "iid" to "7400000000000000003", "device_id" to "7300000000000000003",
            "device_token" to "SYNTHETIC0TOKEN0000000000000000000000001", "resolution" to "1280*2772"
        )
        val plain = JSONObject().put("dr_scene", "preload")
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put("7700000000000000003"))).toString()
        val gzip = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.GZIPOutputStream(bos).use { it.write(plain.toByteArray()) }
        }.toByteArray()
        val now = 1795500000123L
        val signer = HongguoMedusa.signer(HongguoMedusa.Profile.fromParams { k -> pairs.firstOrNull { it.first == k }?.second })
        val signed = HongguoPlaybackV5.signWith(pairs, now, gzip, signer, 0x1234)
        val ts = (now / 1000).toInt()
        assertFalse("生产入口的 query 不应带 ?", '?' in signed.query)
        val queryHash = HongguoCrypto.sm3(signed.query.toByteArray(Charsets.UTF_8))
        val bodyMd5 = HongguoCrypto.md5(gzip)
        val f = lengthDelimited(signed.proto)
        assertEquals("x-medusa 必须覆盖实际发送的 query", toHex(queryHash.copyOf(6)), toHex(f[14]!!))
        assertEquals("摘要必须基于实际发送的 query 与 body", toHex(HongguoMedusa.digest12(queryHash, bodyMd5, ts)), toHex(f[13]!!))
        assertEquals("x-ss-stub 必须是同一份 gzip body 的 MD5", HongguoCrypto.toHex(bodyMd5).uppercase(), signed.headers.toMap()["x-ss-stub"])
        assertEquals(12, HongguoMedusa.selectorOf(queryHash, bodyMd5, ts))
        // 负例：空 query 的摘要确实不同，否则上面的断言没有区分力
        val blank = HongguoMedusa.digest12(HongguoCrypto.sm3(ByteArray(0)), bodyMd5, ts)
        assertTrue("断言需有区分力", !blank.contentEquals(f[13]!!))
    }

    @Test
    fun signTunesSelectorAndBuildsHeaders() {
        val pairs = listOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74332", "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64", "device_platform" to "android", "device_type" to "PGT-AN10",
            "os_version" to "12", "update_version_code" to "74332", "iid" to "7400000000000000001",
            "cdid" to "abcdef0123456789abcdef0123456789", "device_id" to "7300000000000000002",
            "video_id" to "7700000000000000001", "video_id_type" to "0"
        )
        val now = 1795500000123L
        val signer = HongguoMedusa.signer(HongguoMedusa.Profile.fromParams { k -> pairs.firstOrNull { it.first == k }?.second })
        for (second in 0..5) {
            val signed = HongguoPlaybackV5.signWith(pairs, now + second * 1000L, null, signer, 0x1234)
            val ts = ((now + second * 1000L) / 1000).toInt()
            val queryHash = HongguoCrypto.sm3(signed.query.toByteArray(Charsets.UTF_8))
            assertEquals("selector", 12, HongguoMedusa.selectorOf(queryHash, ByteArray(16), ts))
            val headers = signed.headers.toMap()
            assertEquals(ts.toString(), headers["x-khronos"])
            assertEquals(HongguoCrypto.helios(ts, 0x1234, "646637794"), headers["x-helios"])
            assertTrue("medusa 长度", (headers["x-medusa"] ?: "").length > 1200)
            // _rticket 与 x-khronos 同源：偏移不跨秒时调谐才有意义（offset < 1000）
            val ticket = signed.query.substringAfter("_rticket=").substringBefore('&').toLong()
            assertTrue(ticket - (now + second * 1000L) in 0 until 1000)
        }
    }

    @Test
    fun profileDefaultsMatchHgtv() {
        val p = HongguoMedusa.Profile.fromParams { k ->
            mapOf("device_id" to "1", "device_type" to "SDY-AN00", "resolution" to "1920*1080")[k]
        }
        assertEquals("SDY-AN00", p.deviceType)
        assertEquals("Physical size: 1080x1920", p.physicalSize())
        // 空白/缺失回落 HGTV 默认
        assertEquals("OnePlus", p.deviceBrand)
        assertEquals("12", p.osVersion)
        assertEquals("7.4.3.32", p.versionName)
        assertEquals("update_64", p.channel)
        assertEquals("Physical size: 1080x2400", HongguoMedusa.Profile("1").physicalSize())
        assertEquals("Physical size: 1080x2400", HongguoMedusa.Profile("1", resolution = "abc").physicalSize())
        // 无 device_token 时 cachedToken 的种子退化用 deviceId
        val withToken = HongguoMedusa.Profile("1", deviceToken = "1")
        assertEquals(HongguoMedusa.cachedTokenOf(HongguoMedusa.Profile("1")), HongguoMedusa.cachedTokenOf(withToken))
        assertEquals(24, HongguoMedusa.cachedTokenOf(HongguoMedusa.Profile("1")).length)
    }
}
