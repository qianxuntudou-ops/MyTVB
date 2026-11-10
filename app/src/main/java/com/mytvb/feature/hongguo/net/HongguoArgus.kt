package com.mytvb.feature.hongguo.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 真版 X-Argus/X-Ladon（占位版被播放接口风控拒后的完整实现）。
 *
 * 算法骨架：tsukiyo CommentSignature（Argus/Simon 结构，MIT 源 tr4cex/TikTok-Encryption，
 * 评论接口线上验证）；proto 字段表按 HGTV 反编译 AbstractC0240o.e()（播放通道 aid=8662）逐字段对齐。
 * 仅算法结构对照，常量与字节全部来自本仓已授权的 HGTV 反编译与 tsukiyo 线上实现。
 */
internal object HongguoArgus {

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun le(n: Long) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()

    private fun word(b: ByteArray, at: Int) =
        ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun pad(b: ByteArray) = b + ByteArray(16 - b.size % 16) { (16 - b.size % 16).toByte() }

    private fun sm3(b: ByteArray) = HongguoCrypto.sm3(b)

    private fun proto(fields: Map<Int, Any>): ByteArray =
        ByteArrayOutputStream().run {
            fun variable(value: Long) {
                var n = value
                while (n ushr 7 != 0L) {
                    write((n.toInt() and 127) or 128)
                    n = n ushr 7
                }
                write(n.toInt())
            }
            for ((tag, value) in fields) {
                if (value is Number) {
                    variable((tag shl 3).toLong())
                    variable(value.toLong())
                } else {
                    val bytes = if (value is ByteArray) value else value.toString().toByteArray()
                    variable(((tag shl 3) or 2).toLong())
                    variable(bytes.size.toLong())
                    write(bytes)
                }
            }
            toByteArray()
        }

    class Pair7(val argus: String, val ladon: String)

    /**
     * X-Helios（HGTV C0257s1.d 内联 Simon 版，2026-11-10 静态对齐）：
     * 种子=md5hex(le32(rand)+"8662")，34 轮 Simon 密钥链（与 Ladon 同构），
     * 明文="$ts-1588093228-8662"（Helios 专属 salt），输出 base64(le32(rand)+密文)。
     * 替代 HongguoCrypto.helios 的 Node 简易版——模拟器环境服务端差异化校验。
     */
    fun helios(tsSec: Int): String {
        val randInt = SecureRandom().nextInt()
        val le32 = byteArrayOf(
            randInt.toByte(), (randInt ushr 8).toByte(),
            (randInt ushr 16).toByte(), (randInt ushr 24).toByte()
        )
        val seed = md5(le32 + "8662".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.toByteArray()
        val queue = java.util.ArrayDeque<Long>()
        queue.add(word(seed, 16))
        queue.add(word(seed, 24))
        var left = word(seed, 0)
        var right = word(seed, 8)
        val keys = LongArray(34)
        for (i in keys.indices) {
            keys[i] = left
            val next = (java.lang.Long.rotateRight(right, 8) + left) xor i.toLong()
            queue.add(next)
            left = next xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }
        val data = pad("$tsSec-1588093228-8662".toByteArray())
        val out = ByteArrayOutputStream()
        out.write(le32)
        var at = 0
        while (at < data.size) {
            var a = word(data, at)
            var b = word(data, at + 8)
            for (k in keys) {
                b = k xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateLeft(a, 3)
            }
            out.write(le(a))
            out.write(le(b))
            at += 16
        }
        return HongguoCrypto.base64(out.toByteArray())
    }


    /**
     * 生成播放请求的 X-Argus/X-Ladon。
     * @param query 签名 query 串（与 X-Gorgon 同源）
     * @param body 请求体（null=GET）
     * @param deviceProfile field23 设备三元组（device_type/os_version/channel）。
     *   HGTV AbstractC0240o.e 从环境 map 动态取（"25053RT47C" 等仅是 fallback 默认），
     *   proto 必须与 query 环境一致，否则自相矛盾被拒（2026-11-10 实测实证）。
     */
    fun generate(
        query: String,
        body: ByteArray?,
        tsSec: Int,
        versionName: String = "7.1.3.32",
        deviceId: String,
        deviceProfile: Triple<String, String, String> = Triple("25053RT47C", "16", "update_64")
    ): Pair7 {
        val nonce = ByteArray(11).also { SecureRandom().nextBytes(it) }
        return Pair7(
            argus = argus(query, body, tsSec, nonce, versionName, deviceId, deviceProfile),
            ladon = ladon(tsSec, nonce.copyOfRange(2, 6))
        )
    }

    // ---- Ladon：md5(salt+aid) 派生 34 轮密钥，Simon 变种加密 "$ts-license-aid" ----

    private fun ladon(ts: Int, salt: ByteArray): String {
        val ascii = md5(salt + "8662".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
            .toByteArray()
        val queue = java.util.ArrayDeque<Long>()
        queue.add(word(ascii, 16))
        queue.add(word(ascii, 24))
        var left = word(ascii, 0)
        var right = word(ascii, 8)
        val keys = LongArray(34)
        for (i in keys.indices) {
            keys[i] = left
            val next = (java.lang.Long.rotateRight(right, 8) + left) xor i.toLong()
            queue.add(next)
            left = next xor java.lang.Long.rotateLeft(left, 3)
            right = queue.removeFirst()
        }
        val data = pad("$ts-1611921764-8662".toByteArray())
        val result = ByteArrayOutputStream()
        result.write(salt)
        var at = 0
        while (at < data.size) {
            var a = word(data, at)
            var b = word(data, at + 8)
            for (k in keys) {
                b = k xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateLeft(a, 3)
            }
            result.write(le(a))
            result.write(le(b))
            at += 16
        }
        return HongguoCrypto.base64(result.toByteArray())
    }

    // ---- Argus：设备 proto → Simon 72 轮 → d04f 混淆 → AES-CBC(ac1adaae key) ----

    private fun argus(
        query: String,
        body: ByteArray?,
        ts: Int,
        nonce: ByteArray,
        versionName: String,
        deviceId: String,
        deviceProfile: Triple<String, String, String>
    ): String {
        val random =
            ByteBuffer.wrap(nonce, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        // field15 嵌套（HGTV bArrR：d(1,1)/d(2,1)/d(3,1)/d(7,3348294860)）
        val nested = proto(
            linkedMapOf(
                1 to 1L, 2 to 1L, 3 to 1L, 7 to 3348294860L
            )
        )
        // field23 嵌套（HGTV bArrR3：设备三元组+保留 varint；三元组与 query 环境一致）
        val nested23 = proto(
            linkedMapOf(
                1 to deviceProfile.first, 2 to deviceProfile.second, 3 to deviceProfile.third,
                4 to 2147483648L
            )
        )
        // 最外层字段表逐项对齐 HGTV AbstractC0240o.e()
        val bodyMd5 = if (body != null && body.isNotEmpty()) md5(body) else ByteArray(16)
        val message = proto(
            linkedMapOf(
                1 to 1077940818L,
                2 to 2L,
                3 to random,
                4 to "8662",
                5 to deviceId,
                6 to "1611921764",
                7 to versionName,
                8 to "v04.07.01-ml-android",
                9 to 135135744L,
                10 to ByteArray(8),
                11 to 0L,
                12 to ts.toLong() * 2,
                13 to bodyMd5.copyOf(6),
                14 to sm3(query.toByteArray()).copyOf(6),
                15 to nested,
                16 to ByteArray(0),
                20 to "none",
                21 to 738L,
                23 to nested23,
                25 to 2L
            )
        )
        val secret = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c")
        val derived = sm3(secret + hex("3ccc567b") + secret)
        val keys = LongArray(72)
        for (i in 0..3) keys[i] = word(derived, i * 8)
        for (i in 4..71) {
            val x = java.lang.Long.rotateRight(keys[i - 1], 3) xor keys[i - 3]
            keys[i] =
                keys[i - 4].inv() xor
                    x xor
                    java.lang.Long.rotateRight(x, 1) xor
                    ((0x3dc94c3a046d678bL ushr ((i - 4) % 62)) and 1) xor
                    3
        }
        val blocks = pad(message)
        val encrypted = ByteArrayOutputStream()
        var at = 0
        while (at < blocks.size) {
            var a = word(blocks, at)
            var b = word(blocks, at + 8)
            for (k in keys) {
                val next = a xor
                    (java.lang.Long.rotateLeft(b, 1) and java.lang.Long.rotateLeft(b, 8)) xor
                    java.lang.Long.rotateLeft(b, 2) xor k
                a = b
                b = next
            }
            encrypted.write(le(a))
            encrypted.write(le(b))
            at += 16
        }
        val mask = hex("d04ffdffd04ffdff")
        val scrambled = encrypted.toByteArray()
            .mapIndexed { i, b -> (b.toInt() xor mask[i % 8].toInt()).toByte() }
            .toByteArray()
        val container = hex("a6e783ee7001100918") + (mask + scrambled).reversedArray() + hex("567b")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(md5(secret.copyOfRange(0, 16)), "AES"),
            IvParameterSpec(md5(secret.copyOfRange(16, 32)))
        )
        return HongguoCrypto.base64(hex("3ccc") + cipher.doFinal(container))
    }
}
