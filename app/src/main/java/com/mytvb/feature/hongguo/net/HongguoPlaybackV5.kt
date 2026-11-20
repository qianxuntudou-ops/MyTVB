package com.mytvb.feature.hongguo.net

import java.security.SecureRandom

/**
 * 红果播放 V5 取址协议（服务端 2026-11-14 前后切换，2026-11-17 接入）：
 * 独立播放签名 x-medusa + x-helios，**不带** x-argus / x-ladon / x-gorgon；
 * 查询串不追加 ts，签名分支只调整 `_rticket` 偏移（挑 iv&15==12 摘要分支）；
 * 版本档案 74332 / 7.4.3.32 / vivo_8662_64 / PGT-AN10 / Android 12。
 *
 * 背景：11-10 定版的 Argus/Ladon 档案在 player/v1 已被服务端切协议弃用——
 * 症状=HTTP 200 空 body（register/feed/video_detail 等普通接口不受影响）。
 * 参考实现 openhongguo PR#13（codex/fix-playback-v5）逐函数翻译为 Kotlin；
 * 摘要复用 [HongguoCrypto] 的 SM3 / TT02 / BRANCH0_INIT / ror32 / ivTransform。
 *
 * 数值口径：uint32 用 Int 回绕、uint8 每步 and 0xFF，对照 Go 版逐行核对。
 */
internal object HongguoPlaybackV5 {

    private val random = SecureRandom()

    /** V5 线格式常量（与普通接口 AES-V3 分开保存，PR#13 playback_v5_constants.go）。 */
    private const val LICENSE = "646637794"
    private const val APP_ID = "8662"

    /** UA 保留 V5 协议模板原值（与查询串版本号分别处理，PR#13 PlaybackUserAgent）。 */
    const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    private val MAGIC = HongguoCrypto.hexToBytes("2d4b4fca49750d433fb5ae2c226dcc56")
    private val SIGN_KEY = HongguoCrypto.hexToBytes(
        "b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d"
    )

    /** V5 专用 S 盒（1024 字节 = 4 组 ×256，word=ts&3 选组）。 */
    private val SBOX = HongguoCrypto.hexToBytes(
        "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d" +
            "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a" +
            "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d" +
            "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
            "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65" +
            "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe" +
            "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8" +
            "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
            "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d" +
            "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e" +
            "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28" +
            "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
            "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9" +
            "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f" +
            "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168" +
            "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e"
    )

    /** V5 AES 轮参数（word=ts&3 选择）。 */
    private val AES_INIT = intArrayOf(0xbbb5de72.toInt(), 0xd4638d5b.toInt(), 0x92a5f73b.toInt(), 0x89f8a1c9.toInt())
    private val AES_CON2 = arrayOf(
        intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 2, 0), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 1, 0)
    )
    private val AES_ORDER = arrayOf(
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
        intArrayOf(0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7)
    )

    /** 签名进程状态（客户端生命周期内稳定，PR#13 PlaybackV5Signer）。 */
    private val startedAt: Long = System.currentTimeMillis()
    private val nonce: String by lazy {
        val buf = ByteArray(18); random.nextBytes(buf)
        // base64 RawURL（无填充，18 字节 → 24 字符）
        HongguoCrypto.base64(buf).let {
            if (it.endsWith("==")) it.dropLast(2) else if (it.endsWith("=")) it.dropLast(1) else it
        }.replace('+', '-').replace('/', '_')
    }

    internal class SignedV5(val query: String, val headers: List<Pair<String, String>>)

    /**
     * 单独生成 x-medusa 头（2026-11-21）：preload/v1 Argus 头集（kotlinSignedPost）
     * 补齐对齐 HGTV 1.3.8.6——它播放请求的主力内容签名就是 medusa（G1.c v5 签名器），
     * argus/ladon 反而是 8 字符假头（b64(le32(ts))/b64(rand4)）。query 须传实际发送
     * 的完整 query 串（含 ts/_rticket），body 为实际发送的 gzip 字节。
     */
    fun medusaHeaderFor(query: String, gzipBody: ByteArray, tsSec: Int): String {
        val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
        val bodyMd5 = HongguoCrypto.md5(gzipBody)
        return medusa(query, queryHash, bodyMd5, tsSec, drawEntropy())
    }

    internal class Entropy(val messageRandom: Int, val random: Int, val packedRandom: Int, val heliosRandom: Int)

    private fun drawEntropy(): Entropy {
        val buf = ByteArray(16); random.nextBytes(buf)
        fun le32at(off: Int) = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or ((buf[off + 3].toInt() and 0xFF) shl 24)
        return Entropy(le32at(0), le32at(4), le32at(8), le32at(12))
    }

    /**
     * 对实际发送的 gzip 请求体签名。查询串不添加 ts，选择分支时只调整 _rticket
     * （PR#13 Sign：offset 0..1023 挑 iv&15==12）。
     */
    fun sign(pairs: List<Pair<String, String>>, nowMillis: Long, body: ByteArray?): SignedV5 =
        signWith(pairs, nowMillis, body, drawEntropy(), startedAt, nonce)

    /** 固定进程态/熵的签名核心（单测对拍用，对照 Go 版 Sign）。 */
    internal fun signWith(
        pairs: List<Pair<String, String>>, nowMillis: Long, body: ByteArray?,
        ent: Entropy, started: Long, nonceV: String
    ): SignedV5 {
        val ts = (nowMillis / 1000).toInt()
        val bodyMd5 = if (body != null) HongguoCrypto.md5(body) else ByteArray(16)
        val withTicket = pairs.toMutableList()
        withTicket.add("_rticket" to "")
        for (offset in 0L until 1024) {
            withTicket[withTicket.size - 1] = "_rticket" to (nowMillis + offset).toString()
            val query = encodeQuery(withTicket)
            val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
            var iv = HongguoCrypto.ivTransform(queryHash, 0x20230928)
            iv = HongguoCrypto.ivTransform(bodyMd5, iv)
            iv = HongguoCrypto.ivTransform(HongguoCrypto.leU32(ts), iv)
            if (iv and 15 != 12) continue
            val headers = mutableListOf(
                "x-khronos" to ts.toUInt().toString(),
                "x-helios" to HongguoCrypto.helios(ts, ent.heliosRandom, LICENSE),
                "x-medusa" to medusa(query, queryHash, bodyMd5, ts, ent, started, nonceV)
            )
            if (body != null) {
                headers.add("Content-Type" to "application/json; charset=utf-8")
                headers.add("x-ss-stub" to HongguoCrypto.toHex(bodyMd5).uppercase())
            }
            return SignedV5(query, headers)
        }
        throw IllegalStateException("playback V5 signing branch unavailable")
    }

    // ---- V5 摘要（PR#13 playbackV5Hash：branch0TT/Init 固定种子变体）----

    internal fun hashV5(queryHash: ByteArray, bodyMd5: ByteArray, ts: Int): ByteArray {
        val seed = 0x9d4a97c2.toInt()
        val count = seed + ts
        val tt = IntArray(HongguoCrypto.TT02.size) { HongguoCrypto.ror32(HongguoCrypto.TT02[it], count + 1) }
        val padShift = (count + 2) and 7
        val padSeed = byteArrayOf(0xFA.toByte(), 0x45, 0x61, 0xD7.toByte())
        val pad = ByteArray(4) { i ->
            val v = padSeed[i].toInt() and 0xFF
            (((v or (v shl 8)) ushr padShift) and 0xFF).toByte()
        }
        // data = queryHash(32) + bodyMd5(16) + le(ts)(4) + pad(4) + {0,0,0,0,0,0,1,0xa0}(8)
        val data = queryHash + bodyMd5 + HongguoCrypto.leU32(ts) + pad +
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0xA0.toByte())
        val di = ArrayList<Int>(128)
        for (i in 0 until 16) di.add(HongguoCrypto.beU32At(data, i * 4))
        var di0 = di[0]
        for (i in 0 until 112) {
            val di1 = di[i + 1]
            val di14 = di[i + 14]
            val r1 = Integer.rotateLeft(di1, 14) xor Integer.rotateLeft(di1, 25) xor (di1 ushr 3)
            val r2 = Integer.rotateLeft(di14, 13) xor Integer.rotateLeft(di14, 15) xor (di14 ushr 10)
            di.add(di0 + di[i + 9] + r1 + r2)
            di0 = di1
        }
        val initial = IntArray(8) { HongguoCrypto.ror32(HongguoCrypto.BRANCH0_INIT[it], count) }
        // V5 固定轮表（对照 PR#13 Sign 内 mixRounds(..., {96,1,3,4,0,6,5,2,0,7,7}, ..., seed)）
        val rounds = intArrayOf(96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7)
        val state = initial.copyOf()
        val idx = { k: Int -> rounds[k] }
        for (i in 0 until rounds[0]) {
            val base = seed + i
            val w = di[base and 127]
            val n1 = ((state[idx(3)] xor state[idx(4)]) and state[idx(1)]) xor state[idx(3)]
            val n2 = Integer.rotateLeft(state[idx(1)], 26) xor Integer.rotateLeft(state[idx(1)], 21) xor Integer.rotateLeft(state[idx(1)], 7)
            val n4 = w + n1 + n2 + tt[base and 63] + state[idx(5)]
            val n5 = Integer.rotateLeft(state[idx(2)], 30) xor Integer.rotateLeft(state[idx(2)], 19) xor Integer.rotateLeft(state[idx(2)], 10)
            val n6 = (state[idx(2)] and state[idx(6)]) or ((state[idx(2)] or state[idx(6)]) and state[idx(7)])
            val n7 = n5 + n6
            val old = state[idx(9)]
            val tail = state[7]
            for (k in 7 downTo 1) state[k] = state[k - 1]
            state[0] = tail
            state[idx(10)] = n7 + n4
            state[idx(8)] = old + n4
        }
        val result = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i] + initial[i]
            result[i * 4] = ((v ushr 24) and 0xFF).toByte()
            result[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            result[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            result[i * 4 + 3] = (v and 0xFF).toByte()
        }
        val folded = ByteArray(16)
        for (i in 0 until 16) folded[i] = ((result[i].toInt() and 0xFF) xor (result[i + 16].toInt() and 0xFF)).toByte()
        return folded + HongguoCrypto.leU32(HongguoCrypto.sumMd5Check(folded) or 0x20)
    }

    // ---- X-Medusa V5（PR#13 medusa：protobuf 组包 + xmxor + V5 AES 载波）----

    internal fun medusa(
        query: String, queryHash: ByteArray, bodyMd5: ByteArray, ts: Int, ent: Entropy,
        started: Long = startedAt, nonceV: String = nonce
    ): String {
        val deviceID = queryValue(query, "device_id")
        val version = queryValue(query, "version_name")
        // brand 从 query 取（preload 全参 query 带 device_brand=Redmi）；缺失回落
        // HONOR（PR#13 手机端模板口径，保 V5 向量单测对拍不变）
        val brand = queryValue(query, "device_brand").ifEmpty { "HONOR" }
        val device = concat(
            protoSInt(1, 1), protoSInt(2, 2), protoString(3, APP_ID), protoString(4, deviceID),
            protoString(5, nonceV), protoString(6, "!noperm!"), protoString(12, "Asia/Shanghai,8"),
            protoString(13, "zh_CN"), protoString(22, queryValue(query, "os_version")), protoSInt(25, started),
            protoString(30, queryValue(query, "device_type")), protoString(31, brand),
            protoString(32, queryValue(query, "device_type")), protoString(34, brand)
        )
        val env = concat(
            protoString(6, "v04.10.03.04-bugfix"), protoString(8, queryValue(query, "channel")),
            protoBytes(12, device), protoString(14, version)
        )
        val digest = hashV5(queryHash, bodyMd5, ts)
        val flags = ByteArray(8)
        val message = concat(
            protoBytes(1, MAGIC), protoSInt(2, 5), protoSInt(3, ent.messageRandom.toLong() and 0xFFFFFFFFL),
            protoString(4, APP_ID), protoString(5, deviceID), protoString(6, LICENSE),
            protoString(7, version), protoString(8, "v04.10.03-ml-android"), protoSInt(9, 0x040a0300),
            protoBytes(10, flags), protoSInt(12, ts.toLong() and 0xFFFFFFFFL), protoBytes(13, digest),
            protoBytes(14, queryHash.copyOf(6)),
            protoBytes(15, concat(protoSInt(1, 1), protoSInt(2, 1), protoSInt(3, 1), protoSInt(5, 1))),
            protoSInt(17, ts.toLong() and 0xFFFFFFFFL), protoString(20, "none"), protoSInt(21, 377),
            protoBytes(23, env),
            protoString(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":11,\"vpn\":0,\"kd\":0,\"dyn\":\"\",\"do\":1,\"tk\":true}")
        )
        val (hash, mask) = keyHash(SIGN_KEY, ent.random)
        var transformed = flags + xmxor(message, hash)
        transformed.reverse()
        for (i in transformed.indices) {
            transformed[i] = ((transformed[i].toInt() and 0xFF) xor (mask[(i.inv()) and 3].toInt() and 0xFF)).toByte()
        }
        val check = 0x18000001.toInt() or (((queryHash[0].toInt() and 63)) shl 14) or ((digest[0].toInt() and 63) shl 8)
        val packed = concat(
            byteArrayOf(0xCA.toByte()), HongguoCrypto.leU32(ent.packedRandom), HongguoCrypto.leU32(check),
            transformed,
            byteArrayOf(((ent.random ushr 16) and 0xFF).toByte(), ((ent.random ushr 24) and 0xFF).toByte())
        )
        val key = HongguoCrypto.md5(SIGN_KEY.copyOf(16))
        val iv = HongguoCrypto.md5(SIGN_KEY.copyOfRange(16, 32))
        val encrypted = encryptV5(packed, key, iv, ts)
        val prefix = HongguoCrypto.leU32(5) + MAGIC
        for (i in prefix.indices) {
            prefix[i] = ((prefix[i].toInt() and 0xFF) xor ((ts ushr ((i and 3) * 8)) and 0xFF)).toByte()
        }
        val result = concat(
            prefix, byteArrayOf((ent.random and 0xFF).toByte(), ((ent.random ushr 8) and 0xFF).toByte(), 0, 1),
            encrypted
        )
        return HongguoCrypto.base64(result)
    }

    /** SM3(signKey + le32(random) + signKey) → (32 字节 hash, 4 字节 mask)。 */
    internal fun keyHash(signKey: ByteArray, random: Int): Pair<ByteArray, ByteArray> {
        val buf = signKey + HongguoCrypto.leU32(random) + signKey
        val hash = HongguoCrypto.sm3(buf)
        val d1 = (random ushr 16) and 0xFF
        var d2 = ((d1 shl 11) or (random ushr 24))
        d2 = d2 xor ((d1 ushr 5) and 0xFF)
        d2 = d2 xor d1
        d2 = d2.inv()
        return hash to HongguoCrypto.leU32(d2)
    }

    /** PR#13 xmxor（uint8 域回绕，Kotlin 每步 and 0xFF）。 */
    internal fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val n = data.size
        val enc = ByteArray(n)
        for (i in 0 until n) {
            val at = (i * 4) and 28
            val d0 = key[at].toInt() and 0xFF
            val d1 = key[at + 1].toInt() and 0xFF
            var d2 = ((((data[i].toInt() and 0xFF) shl 4) or ((data[i].toInt() and 0xFF) ushr 4)) and 0xFF) + d0 and 0xFF
            d2 = ((d2.toInt().inv() and 0xFF) xor d1) and 0xFF
            d2 = (((d2 shl 3) or (d2 ushr 5)) and 0xFF)
            d2 = (d2 + d1) and 0xFF
            d2 = d2 xor d0
            enc[n - i - 1] = (d2.toInt().inv() and 0xFF).toByte()
        }
        val last = (enc[n - 1].toInt() and 0xFF) xor (enc[n - 2].toInt() and 0xFF)
        val first = enc[0].toInt() and 0xFF
        enc[0] = ((last.toInt().inv() and 0xFF) + first and 0xFF).toByte()
        enc[1] = (((enc[0].toInt() and 0xFF) xor (enc[n - 1].toInt() and 0xFF) xor 254) + (enc[1].toInt() and 0xFF) and 0xFF).toByte()
        val shifted = ((enc[1].toInt() and 0xFF) shl 3 or ((enc[1].toInt() and 0xFF) ushr 5)) and 0xFF
        val fix2 = (((last - first) and 0xFF) xor (shifted xor 2))
        enc[2] = ((enc[2].toInt() and 0xFF) + fix2 and 0xFF).toByte()
        for (i in 0 until n - 4) {
            val sh = (((enc[i + 2].toInt() and 0xFF) shl 3) or ((enc[i + 2].toInt() and 0xFF) ushr 5)) and 0xFF
            val temp = sh xor (enc[i + 1].toInt() and 0xFF) xor ((i + 3) and 0xFF)
            enc[i + 3] = ((temp.toInt().inv() and 0xFF) + (enc[i + 3].toInt() and 0xFF) and 0xFF).toByte()
        }
        enc[n - 1] = ((enc[n - 1].toInt() and 0xFF) xor (enc[n - 2].toInt() and 0xFF)).toByte()
        var sum = 0
        for (i in 1 until n) sum += enc[i].toInt() and 0xFF
        enc[0] = (((enc[0].toInt() and 0xFF) xor (enc[1].toInt() and 0xFF)) + (sum and 0xFF) and 0xFF).toByte()
        return enc
    }

    // ---- V5 AES 载波（PR#13 encryptPlaybackV5：既有轮函数 + V5 S 盒/轮密钥/位映射）----

    private class CipherV5(val box: ByteArray, val con2: IntArray, val order: IntArray, val keys: Array<ByteArray>)

    private fun expandAESWith(key: ByteArray, init: Int, box: ByteArray): Array<ByteArray> {
        val initial = ByteArray(16)
        for (i in 0 until 4) System.arraycopy(HongguoCrypto.leU32(init), 0, initial, i * 4, 4)
        var expanded = initial.copyOf(16).also { dst ->
            for (i in dst.indices) dst[i] = (dst[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        expanded += ByteArray(32)
        var rounds = 8
        for (i in 4 until 12) {
            val at = 4 * (i - 1)
            var a = expanded[at].toInt() and 0xFF
            var b = expanded[at + 1].toInt() and 0xFF
            var c = expanded[at + 2].toInt() and 0xFF
            var d = expanded[at + 3].toInt() and 0xFF
            if (i and 3 == 0) {
                val t = ((init ushr (rounds and 24)) xor (box[b].toInt() and 0xFF)) and 0xFF
                b = box[c].toInt() and 0xFF
                c = box[d].toInt() and 0xFF
                d = box[a].toInt() and 0xFF
                a = t
            }
            rounds += 2
            expanded[at + 4] = ((a xor (expanded[at - 12].toInt() and 0xFF)) and 0xFF).toByte()
            expanded[at + 5] = ((b xor (expanded[at - 11].toInt() and 0xFF)) and 0xFF).toByte()
            expanded[at + 6] = ((c xor (expanded[at - 10].toInt() and 0xFF)) and 0xFF).toByte()
            expanded[at + 7] = ((d xor (expanded[at - 9].toInt() and 0xFF)) and 0xFF).toByte()
        }
        return Array(12) { i -> expanded.copyOfRange(i * 4, i * 4 + 4) }
    }

    /** AES 轮块加密（PR#13 aesV3.block：sub/shift/mix + 轮密钥加）。 */
    private fun block(c: CipherV5, value: ByteArray): ByteArray {
        val state = Array(4) { ByteArray(4) }
        for (i in 0 until 4) System.arraycopy(value, i * 4, state[i], 0, 4)
        fun addCon(keys: List<ByteArray>) {
            for (i in 0 until 4) for (j in 0 until 4) {
                state[i][j] = ((state[i][j].toInt() and 0xFF) xor (keys[i][c.con2[j]].toInt() and 0xFF)).toByte()
            }
        }
        addCon(c.keys.slice(0..3))
        for (i in 1 until 3) {
            // sub：S 盒替换 + 行按 con2 重排
            val old = Array(4) { state[it].copyOf() }
            for (r in 0 until 4) for (j in 0 until 4) {
                state[r][j] = c.box[(old[r][j].toInt() and 0xFF)]
            }
            val subbed = Array(4) { state[it].copyOf() }
            for (r in 0 until 4) System.arraycopy(subbed[c.con2[r]], 0, state[r], 0, 4)
            // shift：flat16 按 order 重排
            val flat = ByteArray(16)
            for (r in 0 until 4) for (j in 0 until 4) flat[r * 4 + j] = state[r][j]
            for (k in 0 until 16) state[k / 4][k % 4] = flat[c.order[k]]
            if (i == 1) {
                // shiftCon：列内按 con2 重排
                val prev = Array(4) { state[it].copyOf() }
                for (r in 0 until 4) for (j in 0 until 4) state[r][j] = prev[r][c.con2[j]]
                // mixColumns
                fun xt(x: Int): Int {
                    val v = (x shl 1) and 0xFF
                    return if (x and 128 != 0) (v xor 0x1b) and 0xFF else v
                }
                for (col in 0 until 4) {
                    val s0 = state[0][col].toInt() and 0xFF; val s1 = state[1][col].toInt() and 0xFF
                    val s2 = state[2][col].toInt() and 0xFF; val s3 = state[3][col].toInt() and 0xFF
                    val u = s0
                    val t = s0 xor s1 xor s2 xor s3
                    state[0][col] = ((s0 xor t xor xt(s0 xor s1)) and 0xFF).toByte()
                    state[1][col] = ((s1 xor t xor xt(s1 xor s2)) and 0xFF).toByte()
                    state[2][col] = ((s2 xor t xor xt(s2 xor s3)) and 0xFF).toByte()
                    state[3][col] = ((s3 xor t xor xt(s3 xor u)) and 0xFF).toByte()
                }
            }
            addCon(c.keys.slice(i * 4 until i * 4 + 4))
        }
        for (i in 0 until 4) for (j in 0 until 4) {
            state[i][j] = ((state[i][j].toInt() and 0xFF) xor (c.keys[4 + i][j].toInt() and 0xFF)).toByte()
        }
        val out = ByteArray(16)
        for (i in 0 until 4) System.arraycopy(state[i], 0, out, i * 4, 4)
        return out
    }

    internal fun encryptV5(data: ByteArray, key: ByteArray, iv: ByteArray, ts: Int): ByteArray {
        val word = ts and 3
        val box = SBOX.copyOfRange(word * 256, (word + 1) * 256)
        val cipher = CipherV5(box, AES_CON2[word], AES_ORDER[word], expandAESWith(key, AES_INIT[word], box))
        val wire = intArrayOf(6, 5, 3, 4, 2, 7, 1, 0)
        val compact = intArrayOf(1, 7, 3, 6, 2, 0, 4, 5)
        val plain = ByteArray(32)
        for (i in 0 until 31) {
            for (j in 0 until 8) {
                val at = i * 8 + j
                if (at < data.size) {
                    plain[i] = ((plain[i].toInt() and 0xFF) or
                        ((((data[at].toInt() and 0xFF) ushr wire[j]) and 1) shl compact[j])).toByte()
                }
            }
        }
        plain[31] = 1
        fun xorBytes(a: ByteArray, b: ByteArray): ByteArray =
            ByteArray(a.size) { i -> ((a[i].toInt() and 0xFF) xor (b[i % b.size].toInt() and 0xFF)).toByte() }
        val first = block(cipher, xorBytes(plain.copyOfRange(0, 16), iv))
        val bits = first + block(cipher, xorBytes(plain.copyOfRange(16, 32), first))
        val out = data.copyOf()
        for (i in 0 until 31) {
            for (j in 0 until 8) {
                val at = i * 8 + j
                if (at < out.size) {
                    val clear = ((1 shl wire[j]).inv())
                    out[at] = (((out[at].toInt() and 0xFF) and (clear and 0xFF)) or
                        ((((bits[i].toInt() ushr compact[j]) and 1)) shl wire[j])).toByte()
                }
            }
        }
        return byteArrayOf(bits[31]) + out
    }

    // ---- 小原语 ----

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
        return out
    }

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>(4)
        while ((v ushr 7) != 0L) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    private fun protoKey(tag: Int, wire: Int): ByteArray = varint(((tag.toLong() shl 3) or wire.toLong()))
    private fun protoBytes(tag: Int, body: ByteArray): ByteArray =
        if (body.isEmpty()) ByteArray(0) else protoKey(tag, 2) + varint(body.size.toLong()) + body
    private fun protoString(tag: Int, s: String): ByteArray = protoBytes(tag, s.toByteArray(Charsets.UTF_8))
    private fun protoSInt(tag: Int, value: Long): ByteArray =
        protoKey(tag, 0) + varint((value shl 1) xor (value shr 63))

    /** 保序拼接（V5 模板顺序参与签名），unreserved=-_.!~*'()，其余 %XX 大写。 */
    internal fun encodeQuery(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> "${escapeV5(k)}=${escapeV5(v)}" }

    private fun escapeV5(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.toByteArray(Charsets.UTF_8)) {
            val c = ch.toInt() and 0xFF
            if (c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '!'.code ||
                c == '~'.code || c == '*'.code || c == '\''.code || c == '('.code || c == ')'.code
            ) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }

    /** 从签好的 query 串取参数值（%XX 解码；Medusa 组包用）。 */
    internal fun queryValue(query: String, name: String): String {
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            if (pair.substring(0, i) != name) continue
            val raw = pair.substring(i + 1)
            val sb = StringBuilder(raw.length)
            var j = 0
            while (j < raw.length) {
                val c = raw[j]
                if (c == '%' && j + 2 < raw.length) {
                    val hex = raw.substring(j + 1, j + 3).toIntOrNull(16)
                    if (hex != null) { sb.append(hex.toChar()); j += 3; continue }
                }
                sb.append(c); j++
            }
            return sb.toString()
        }
        return ""
    }
}
