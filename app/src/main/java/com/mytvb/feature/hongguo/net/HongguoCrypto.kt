package com.mytvb.feature.hongguo.net

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 红果（字节系）请求签名原语：SM3 / X-Gorgon / X-Helios / F13 / X-Medusa(Proto+XMXOR+AESV3)。
 *
 * 由本机自研 Go 版（.trae/hongguo-tv core/cmd/hongguotv/passport_sign.go，已在线上验证）
 * 逐函数对照翻译为 Kotlin；本文件为本机资产，不涉及第三方许可。
 *
 * 数值口径：全部 uint 运算用 Kotlin Int/Long 按二进制回绕模拟；
 * 逻辑右移必须 ushr（对照 Go 的无符号 >>），此处已逐处核对。
 */
internal object HongguoCrypto {

    private val random = SecureRandom()

    fun randomU32(): Int = random.nextInt()

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    private fun leU32(n: Int): ByteArray = byteArrayOf(
        n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()
    )

    /** 自写标准 Base64（android.util.Base64 在 JVM 单测不可用；minSdk23 无 java.util.Base64）。 */
    internal fun base64(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63])
                .append(table[(n ushr 6) and 63]).append(table[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
            sb.append(table[(n ushr 18) and 63]).append(table[(n ushr 12) and 63])
                .append(table[(n ushr 6) and 63]).append("=")
        }
        return sb.toString()
    }

    private fun leU32At(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun beU32At(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun rol8(x: Int, n: Int): Int =
        (((x shl n) or (x ushr (8 - n))) and 0xFF)

    private fun rev8(x: Int): Int = Integer.reverse(x) ushr 24

    // ---- SM3（GB/T 32905），对照 passportSM3 ----
    fun sm3(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8
        val b = data + byteArrayOf(0x80.toByte()) +
            ByteArray((56 - (data.size + 1).mod(64)).mod(64)) { 0 } +
            byteArrayOf(
                (bitLen ushr 56).toByte(), (bitLen ushr 48).toByte(), (bitLen ushr 40).toByte(),
                (bitLen ushr 32).toByte(), (bitLen ushr 24).toByte(), (bitLen ushr 16).toByte(),
                (bitLen ushr 8).toByte(), bitLen.toByte()
            )
        var v = intArrayOf(
            0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
            0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt()
        )
        fun p0(x: Int) = x xor Integer.rotateLeft(x, 9) xor Integer.rotateLeft(x, 17)
        fun p1(x: Int) = x xor Integer.rotateLeft(x, 15) xor Integer.rotateLeft(x, 23)
        var off = 0
        while (off < b.size) {
            val w = IntArray(68)
            for (i in 0 until 16) w[i] = beU32At(b, off + i * 4)
            for (i in 16 until 68) {
                w[i] = p1(w[i - 16] xor w[i - 9] xor Integer.rotateLeft(w[i - 3], 15)) xor
                    Integer.rotateLeft(w[i - 13], 7) xor w[i - 6]
            }
            var a = v[0]; var c = v[1]; var d = v[2]; var e = v[3]
            var f = v[4]; var g = v[5]; var h = v[6]; var k = v[7]
            for (j in 0 until 64) {
                val t: Int; val ff: Int; val gg: Int
                if (j < 16) {
                    t = 0x79CC4519; ff = a xor c xor d; gg = f xor g xor h
                } else {
                    t = 0x7A879D8A; ff = (a and c) or (a and d) or (c and d)
                    gg = (f and g) or (f.inv() and h)
                }
                val s1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + f + Integer.rotateLeft(t, j), 7)
                val s2 = s1 xor Integer.rotateLeft(a, 12)
                val u1 = ff + e + s2 + (w[j] xor w[j + 4])
                val u2 = gg + k + s1 + w[j]
                e = d; d = Integer.rotateLeft(c, 9); c = a; a = u1
                k = h; h = Integer.rotateLeft(g, 19); g = f; f = p0(u2)
            }
            val tmp = intArrayOf(a, c, d, e, f, g, h, k)
            for (i in 0 until 8) v[i] = v[i] xor tmp[i]
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    // ---- 签名分支 IV，对照 passportIV / passportBranch ----
    private fun ivTransform(data: ByteArray, iv: Int): Int {
        var acc = iv
        for (i in data.indices) {
            val x = data[i].toInt() and 0xFF
            acc = if (i and 1 == 0) {
                (acc ushr 4) xor acc xor (acc shl 6) xor x
            } else {
                ((acc ushr 7) xor acc xor (x or (acc shl 12))).inv()
            }
        }
        return acc
    }

    internal class Branch(val iv: Int, val queryHash: ByteArray, val bodyMd5: ByteArray)

    internal fun branch(query: String, body: ByteArray?, ts: Int): Branch {
        val q = sm3(query.toByteArray(Charsets.UTF_8))
        val m = if (body != null && body.isNotEmpty()) md5(body) else ByteArray(16)
        var iv = ivTransform(q, 0x20230928)
        iv = ivTransform(m, iv)
        iv = ivTransform(leU32(ts), iv)
        return Branch(iv, q, m)
    }

    // ---- X-Gorgon，对照 passportGorgon ----
    fun gorgon(query: String, body: ByteArray?, ts: Int, nonce: Int): String {
        val q = md5(query.toByteArray(Charsets.UTF_8))
        val m = if (body != null && body.isNotEmpty()) md5(body) else ByteArray(16)
        val data = ByteArray(20)
        System.arraycopy(q, 0, data, 0, 4)
        System.arraycopy(m, 0, data, 4, 4)
        System.arraycopy(leU32(67503104), 0, data, 12, 4)
        data[16] = ((ts ushr 24) and 0xFF).toByte()
        data[17] = ((ts ushr 16) and 0xFF).toByte()
        data[18] = ((ts ushr 8) and 0xFF).toByte()
        data[19] = (ts and 0xFF).toByte()
        val key = byteArrayOf(0x4A, 0x40, 0x16, ((nonce ushr 8) and 0xFF).toByte(), 0x47, 0x6C, 1, (nonce and 0xFF).toByte())
        val s = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            j = (j + (s[i].toInt() and 0xFF) + (key[i and 7].toInt() and 0xFF)) and 255
            // 非标准 RC4：单向覆盖（s[i]=s[j]），s[j] 不回写（对照 Go/Node 两版一致）
            s[i] = s[j]
        }
        j = 0
        for (i in data.indices) {
            j += s[i + 1].toInt() and 0xFF
            val y = s[j and 255]
            s[i + 1] = y
            data[i] = ((data[i].toInt() and 0xFF) xor (s[(((y.toInt() and 0xFF) * 2) and 255)].toInt() and 0xFF)).toByte()
        }
        for (i in data.indices) {
            data[i] = rol8(data[i].toInt() and 0xFF, 4).toByte()
            val next = if (i < 19) data[i + 1] else data[0]
            data[i] = ((rev8((data[i].toInt() and 0xFF) xor (next.toInt() and 0xFF)) xor 20).inv()).toByte()
        }
        val out = byteArrayOf(0x84.toByte(), 4, (nonce and 0xFF).toByte(), ((nonce ushr 8) and 0xFF).toByte(), 0x40, 1) + data
        return toHex(out)
    }

    // ---- X-Helios，对照 passportHelios ----
    fun helios(ts: Int, nonce: Int): String {
        val key = md5(leU32(nonce) + "8662".toByteArray(Charsets.UTF_8))
        val raw = toHex(key).toByteArray(Charsets.UTF_8)
        fun u64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        var k = u64(raw, 0)
        var p0 = u64(raw, 8); var p1 = u64(raw, 16); var p2 = u64(raw, 24)
        val rounds = ArrayList<Long>(34)
        rounds.add(k)
        for (i in 0 until 33) {
            val x = (java.lang.Long.rotateLeft(p0, -8) + k) xor i.toLong()
            p0 = p1; p1 = p2; p2 = x
            k = x xor java.lang.Long.rotateLeft(k, 3)
            rounds.add(k)
        }
        var plain = "$ts-1588093228-8662".toByteArray(Charsets.UTF_8)
        val pad = 16 - plain.size % 16
        plain += ByteArray(pad) { pad.toByte() }
        val out = ArrayList<Byte>(4 + plain.size)
        for (b in leU32(nonce)) out.add(b)
        var off = 0
        while (off < plain.size) {
            var a = u64(plain, off)
            var b = u64(plain, off + 8)
            for (r in rounds) {
                b = r xor (a + java.lang.Long.rotateLeft(b, -8))
                a = b xor java.lang.Long.rotateLeft(a, 3)
            }
            for (i in 0 until 8) out.add(((a ushr (8 * i)) and 0xFF).toByte())
            for (i in 0 until 8) out.add(((b ushr (8 * i)) and 0xFF).toByte())
            off += 16
        }
        val arr = out.toByteArray()
        return base64(arr)
    }

    // ---- F13（变体 MD5），对照 passportF13 ----
    private val ORDERS = hexToBytes(
        "0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003"
    )
    private val MD5_CONSTANTS = intArrayOf(
        0xa7aefe20.toInt(), 0x7149f1d6, 0x47e4ca07, 0xe9b58f67.toInt(), 0x93b924de.toInt(), 0xc614d0f5.toInt(),
        0x38afe0ef, 0xb2bbad73.toInt(), 0xe24444c3.toInt(), 0x9d3aec9b.toInt(), 0xdf7b37e4.toInt(), 0xd8b16d40.toInt(),
        0xf8ac31b8.toInt(), 0x76b9a90b, 0x31d833ee, 0x953fce64.toInt(), 0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925.toInt(),
        0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0.toInt(), 0x114ca35e, 0x8193f984.toInt(), 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9,
        0xa6d84453.toInt(), 0x3a18500c, 0x798ec47a, 0x97a76b28.toInt(), 0x66c4ff96, 0x51716443, 0x0dd2fc3b, 0xb5696da7.toInt(),
        0xbbeb3ac5.toInt(), 0x5c53d204, 0xd32608ce.toInt(), 0x7279b9ec.toInt(), 0xf4188ecf.toInt(), 0xf7d793db.toInt(),
        0x332cc491, 0xab76ae15.toInt(), 0x9bebe727.toInt(), 0x18a01384, 0x5be9f8a7, 0x5f90a754, 0x39b663c0, 0x36673c83,
        0x7c92f514, 0x9d7d94d7.toInt(), 0xe2e8d9aa.toInt(), 0x5f7e9ea9, 0x7abd4551, 0x569e05da, 0x40a25632, 0x3df5a9a5,
        0xbab37d80.toInt(), 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7.toInt(), 0xa5ab26a3.toInt()
    )

    internal fun f13(iv: Int, ts: Int, q: ByteArray, m: ByteArray): ByteArray {
        val group = ((iv and 15) - 2) / 3
        val seeds = intArrayOf(0x8980f29b.toInt(), 0xeb549c7f.toInt(), 0xb08726db.toInt(), 0xd40cb5e6.toInt(), 0xe8f559e4.toInt())
        val count = seeds[group] + ts
        val shift = (count + 5) and 7
        var msg = q + m + leU32(ts)
        val magic = hexToBytes("8496779dd4150bf8")
        val extra = ByteArray(8)
        for (i in 0 until 8) {
            extra[i] = ((((magic[i].toLong() and 0xFF) * 257) ushr shift)).toByte()
        }
        msg += extra
        msg += byteArrayOf(0xA0.toByte(), 1, 0, 0)
        val start = intArrayOf(0x79e0f2fb.toInt(), 0xc8b52570.toInt(), 0xebc2f8cd.toInt(), 0x7c104d93)
        val end = intArrayOf(0x19be4866, 0xe85986b4.toInt(), 0xe19b326e.toInt(), 0x71d1d7d4)
        for (i in 0 until 4) {
            start[i] = Integer.rotateLeft(start[i], -(count and 31))
            end[i] = Integer.rotateLeft(end[i], -((count + 6) and 31))
        }
        var a = start[0]; var b = start[1]; var c = start[2]; var d = start[3]
        val shifts = arrayOf(
            intArrayOf(7, 12, 17, 22), intArrayOf(5, 9, 14, 20),
            intArrayOf(4, 11, 16, 23), intArrayOf(6, 10, 15, 21)
        )
        for (i in 0 until 64) {
            val f: Int = when (i / 16) {
                0 -> (b and c) or (b.inv() and d)
                1 -> (b and d) or (c and d.inv())
                2 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val word = leU32At(msg, (ORDERS[group * 64 + i].toInt() and 0xFF) * 4)
            val x = b + Integer.rotateLeft(
                a + f + word + Integer.rotateLeft(MD5_CONSTANTS[i], -((count + 1) and 31)),
                shifts[i / 16][i % 4]
            )
            a = d; d = c; c = b; b = x
        }
        val regs = intArrayOf(a, b, c, d)
        val out = ByteArray(16)
        for (i in 0 until 4) {
            System.arraycopy(leU32((start[i] + regs[i]) xor end[i]), 0, out, i * 4, 4)
        }
        var check = 0x20220420
        for (i in 0 until 12) {
            val x = out[i].toInt() and 0xFF
            check = if (i and 1 == 0) {
                ((check ushr 3) xor check) xor x xor (check shl 7)
            } else {
                ((check ushr 5) xor check) xor ((x or (check shl 11)).inv())
            }
        }
        return out + leU32((check or 4) xor 0x1000000)
    }

    // ---- F13 branch 0（tt02 变体：112 轮消息扩展 + 8 变量轮函数），对照 Node hashF13 ----
    private val TT02 = intArrayOf(
        0xebb64faf.toInt(), 0x07aadcC2, 0xcf3187bf.toInt(), 0xe01138ff.toInt(), 0x6d0bfcff.toInt(), 0x5a30a3be.toInt(), 0xb41ad638.toInt(), 0x34180eb8.toInt(),
        0xf233eb6f.toInt(), 0xb1a584cc.toInt(), 0xccc30dc7.toInt(), 0x47d1db51.toInt(), 0xd55653de.toInt(), 0x70a84fa1.toInt(), 0x57473c12.toInt(), 0xf76f0288.toInt(),
        0x2c077f0a.toInt(), 0xda0dcad0.toInt(), 0xfbb86f6c.toInt(), 0xfdc4cf00.toInt(), 0x688a020d.toInt(), 0xe676c6a6.toInt(), 0x8cd6338b.toInt(), 0x1a3c8d0e.toInt(),
        0xcce8b06b.toInt(), 0x6ad0ed0b.toInt(), 0xa0522717.toInt(), 0xdc71ac83.toInt(), 0x2285db71.toInt(), 0xd5b4dda6.toInt(), 0x736f8650.toInt(), 0x6560306c.toInt(),
        0x617ce2a6.toInt(), 0xe423417e.toInt(), 0x0a40e143, 0x544e4032, 0x88dffb2a.toInt(), 0x716c1ae0.toInt(), 0x4c467a88.toInt(), 0x05b23bb3,
        0xe1d0b866.toInt(), 0xbaa3dcb8.toInt(), 0xae3374d3.toInt(), 0xc3381a50.toInt(), 0x1702f75b.toInt(), 0xfe6da368.toInt(), 0xf0b4cf48.toInt(), 0x4e0ffbb8.toInt(),
        0x72aad10d.toInt(), 0x26c53a3d.toInt(), 0xf2bce0f6.toInt(), 0xb4557581.toInt(), 0x4a257fdd.toInt(), 0x8c3182a2.toInt(), 0xab0b3b86.toInt(), 0x3d5dfb14.toInt(),
        0x4f103634.toInt(), 0xd37b52d7.toInt(), 0x444eff16.toInt(), 0xeb0a33d1.toInt(), 0x6ca86f6e.toInt(), 0x0284ba07, 0x08387cfa, 0x5fb37586.toInt()
    )
    private val BRANCH0_INIT = intArrayOf(
        0x7aba4fc8.toInt(), 0x67166507, 0x6403fa00.toInt(), 0x340f512f, 984304912, 3005047866.toInt(),
        2874125293.toInt(), 2152413264.toInt()
    )
    private val BRANCH0_CHOSEN = arrayOf(
        intArrayOf(101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3),
        intArrayOf(96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4),
        intArrayOf(96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5),
        intArrayOf(99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6),
        intArrayOf(96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4),
        intArrayOf(100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5)
    )
    private val IV1_LIST = intArrayOf(
        0xc4a78580.toInt(), 0xb3c0fd39.toInt(), 0xc58c5686.toInt(),
        0xc9aa3ba7.toInt(), 0xf5a7adf2.toInt(), 0x963c2ed1.toInt()
    )

    private fun ror32(v: Int, n: Int): Int {
        val k = n and 31
        return if (k == 0) v else Integer.rotateLeft(v, 32 - k)
    }

    internal fun f13Branch0(iv: Int, ts: Int, q: ByteArray, m: ByteArray): ByteArray {
        val low = iv and 15
        val ivV0 = (low * 171) shr 9
        val ivV1 = IV1_LIST[ivV0]
        val countV1 = ivV1 + ts + 1 and 255
        val countV2 = ivV1 + ts
        val tt = IntArray(64) { ror32(TT02[it], countV1) }
        val n0 = (countV2 + 2) and 7
        val padSeed = byteArrayOf(0xFA.toByte(), 0x45, 0x61, 0xD7.toByte())
        val pad = ByteArray(4) { i ->
            val v = padSeed[i].toInt() and 0xFF
            (((v or (v shl 8)) ushr n0) and 0xFF).toByte()
        }
        // data = q(32) + m(16) + le(ts)(4) + pad(4) + hex(00000000000001a0)(8) = 64
        var data = q + m + leU32(ts) + pad +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0x1A.toByte(), 0xA0.toByte())
        // 16 个 BE u32 + 112 轮扩展
        val di = ArrayList<Int>(128)
        for (i in 0 until 16) {
            di.add(beU32At(data, i * 4))
        }
        var di0 = di[0]
        for (i in 0 until 112) {
            val di1 = di[i + 1]
            val di14 = di[i + 14]
            val r1 = Integer.rotateLeft(di1, 14) xor Integer.rotateLeft(di1, 25) xor (di1 ushr 3)
            val r2 = Integer.rotateLeft(di14, 13) xor Integer.rotateLeft(di14, 15) xor (di14 ushr 10)
            di.add(di0 + di[i + 9] + r1 + r2)
            di0 = di1
        }
        val init = IntArray(8) { ror32(BRANCH0_INIT[it], countV2 and 31) }
        val chosen = BRANCH0_CHOSEN[ivV0]
        val d = init.copyOf()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n0v = di[base and 127]
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = Integer.rotateLeft(d[chosen[1]], 26) xor Integer.rotateLeft(d[chosen[1]], 21) xor Integer.rotateLeft(d[chosen[1]], 7)
            val n4 = n0v + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = Integer.rotateLeft(d[chosen[2]], 30) xor Integer.rotateLeft(d[chosen[2]], 19) xor Integer.rotateLeft(d[chosen[2]], 10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val n7 = n5 + n6
            val old = d[chosen[9]]
            // 循环右移：尾弹出插到头部（对照 d.unshift(d.pop())）
            val tail = d[7]
            for (k in 7 downTo 1) d[k] = d[k - 1]
            d[0] = tail
            d[chosen[10]] = n7 + n4
            d[chosen[8]] = old + n4
        }
        val ret = ByteArray(32)
        for (i in 0 until 8) {
            val v = d[i] + init[i]
            ret[i * 4] = ((v ushr 24) and 0xFF).toByte()
            ret[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            ret[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            ret[i * 4 + 3] = (v and 0xFF).toByte()
        }
        val folded = ByteArray(16)
        for (i in 0 until 16) {
            folded[i] = ((ret[i].toInt() and 0xFF) xor (ret[16 + i].toInt() and 0xFF)).toByte()
        }
        System.arraycopy(folded, 0, ret, 0, 16)
        return ret.copyOfRange(0, 16) + leU32(sumMd5Check(ret.copyOfRange(0, 16)))
    }

    private fun sumMd5Check(out16: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val x = out16[i].toInt() and 0xFF
            check = if (i and 1 == 0) {
                ((check ushr 3) xor check) xor x xor (check shl 7)
            } else {
                ((check ushr 5) xor check) xor ((x or (check shl 11)).inv())
            }
        }
        return (check or 4) xor 0x1000000
    }

    // ---- Proto writer，对照 protocolProto ----
    private class Proto {
        val buf = ArrayList<Byte>()

        private fun uvarint(v: Long) {
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) { buf.add(b.toByte()); return }
                buf.add((b or 0x80).toByte())
            }
        }

        fun sint(field: Int, n: Long) {
            uvarint((field shl 3).toLong())
            uvarint((n shl 1) xor (n shr 63))
        }

        fun blob(field: Int, b: ByteArray) {
            if (b.isEmpty()) return
            uvarint(((field shl 3) or 2).toLong())
            uvarint(b.size.toLong())
            buf.addAll(b.toList())
        }

        fun text(field: Int, s: String) = blob(field, s.toByteArray(Charsets.UTF_8))

        fun float(field: Int, f: Float) {
            uvarint(((field shl 3) or 5).toLong())
            val bits = java.lang.Float.floatToRawIntBits(f)
            for (b in leU32(bits)) buf.add(b)
        }

        fun bytes(): ByteArray = buf.toByteArray()
    }

    // ---- X-Medusa 三件套：proto / XMXOR / AESV3，对照同函数 ----
    private val SBOX = hexToBytes(
        "fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec4915c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f964312c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c0073607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e425b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f8408c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec02628a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5ff72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d814858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835cd5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413"
    )

    private fun medusaProto(query: String, ts: Int, nonce: Int, deviceId: String, q: ByteArray, m: ByteArray, f13: ByteArray, version: String): ByteArray {
        val device = Proto(); val env = Proto(); val report = Proto(); val count = Proto()
        device.sint(1, 1); device.sint(2, 2)
        device.text(3, "8662"); device.text(4, deviceId)
        device.text(5, "Ai6svO3PyrwDOUSmO6ZcResxu")
        device.text(6, "!noperm!")
        for (i in intArrayOf(7, 8)) device.sint(i, -888888L)
        device.sint(9, 3); device.sint(10, -888888L)
        device.text(11, "!notset!")
        device.text(12, "Asia/Shanghai,8")
        device.text(13, "zh_CN")
        device.sint(14, 4)
        val floats = floatArrayOf(255.24993896484375f, 35.58599090576172f, 3.467449188232422f, 3.467449188232422f, 255.1754913330078f, 42.17544174194336f)
        for (i in floats.indices) device.float(i + 16, floats[i])
        device.text(22, "16"); device.sint(23, 41); device.sint(24, 36)
        for (i in 25..28) {
            var n = 1728388016635L
            if (i == 28) n += 2
            device.sint(i, n)
        }
        device.sint(29, -1)
        val strings = arrayOf("25053RT47C", "Redmi", "25053RT47C", "25053RT47C", "Redmi", "Redmi", "Redmi")
        for (i in strings.indices) device.text(i + 30, strings[i])
        device.sint(38, 31)
        report.sint(1, ts.toLong()); report.sint(2, -2); report.sint(4, 200)
        env.sint(1, 110); env.sint(2, 146331399); env.sint(3, 146331396); env.sint(5, 7)
        env.text(6, "v04.06.04.03-bugfix")
        env.sint(7, 11001)
        env.blob(12, device.bytes())
        env.blob(13, report.bytes())
        env.text(14, version)
        count.sint(1, 111); count.sint(2, 10); count.sint(3, 694367); count.sint(5, 586952199)
        val p = Proto()
        p.blob(1, hexToBytes("f7e85ffad7d7dc3bd62ac87057cf6118"))
        p.sint(2, 3)
        p.sint(3, nonce.toLong())
        p.text(4, "8662"); p.text(5, deviceId)
        p.text(6, "1588093228")
        p.text(7, version)
        p.text(8, "v04.06.04-ml-android")
        p.sint(9, 67503104)
        p.blob(10, hexToBytes("4001000000000000"))
        p.sint(12, ts.toLong())
        p.blob(13, f13)
        p.blob(14, q.copyOfRange(0, 6))
        p.blob(15, count.bytes())
        p.text(16, "AXYQOS6n2m60x1fVZHIrH3iol")
        p.sint(17, ts.toLong())
        p.blob(19, sm3(query.toByteArray(Charsets.UTF_8) + m + "none".toByteArray(Charsets.UTF_8)))
        p.text(20, "none")
        p.sint(21, 312)
        p.blob(23, env.bytes())
        p.text(24, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""")
        return p.bytes()
    }

    private fun xmXor(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            val x = data[i].toInt() and 0xFF
            val k = (i * 4) and 28
            val a = key[k].toInt() and 0xFF
            val b = key[k + 1].toInt() and 0xFF
            var v = ((((rol8(x, 4) + a) and 0xFF) xor 0xFF) xor b) and 0xFF
            v = (rol8(v, 3) + b) and 0xFF
            out[data.size - 1 - i] = ((v xor a) xor 0xFF).toByte()
        }
        val n = out.size
        val flag = (out[n - 1].toInt() and 0xFF) xor (out[n - 2].toInt() and 0xFF)
        val old = out[0].toInt() and 0xFF
        out[0] = (out[0] + (flag.inv())).toByte()
        out[1] = (out[1] + ((out[0].toInt() and 0xFF) xor (out[n - 1].toInt() and 0xFF) xor 254)).toByte()
        out[2] = (out[2] + (((flag - old) xor rol8(out[1].toInt() and 0xFF, 3) xor 2))).toByte()
        for (i in 0 until n - 4) {
            out[i + 3] = (out[i + 3] + ((rol8(out[i + 2].toInt() and 0xFF, 3) xor (out[i + 1].toInt() and 0xFF) xor (i + 3)).inv())).toByte()
        }
        out[n - 1] = ((out[n - 1].toInt() and 0xFF) xor (out[n - 2].toInt() and 0xFF)).toByte()
        var sum = 0
        for (i in 1 until n) sum += out[i].toInt() and 0xFF
        out[0] = (((out[0].toInt() and 0xFF) xor (out[1].toInt() and 0xFF)) + sum).toByte()
        return out
    }

    private fun aesV3(data: ByteArray, ts: Int): ByteArray {
        require(data.size >= 248) { "signature payload is too short" }
        val i = ts and 3
        fun sboxAt(idx: Int) = SBOX[idx].toInt() and 0xFF
        val con = arrayOf(
            intArrayOf(1, 0, 2, 3), intArrayOf(2, 0, 3, 1),
            intArrayOf(0, 1, 3, 2), intArrayOf(1, 0, 2, 3)
        )[i]
        val order = arrayOf(
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
            intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
            intArrayOf(0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15),
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3)
        )[i]
        val init = intArrayOf(0xca025ddc.toInt(), 0x823dc546.toInt(), 0xc9420583.toInt(), 0xc298225f.toInt())[i]
        val key = hexToBytes("f1593376766ea98d34f31b057a9d5be4")
        val mk = ByteArray(48)
        for (j in 0 until 16) {
            mk[j] = (key[j].toInt() and 0xFF xor ((init ushr ((j % 4) * 8)) and 0xFF)).toByte()
        }
        var r = 8
        for (j in 4 until 12) {
            var prev = byteArrayOf(mk[j * 4 - 4], mk[j * 4 - 3], mk[j * 4 - 2], mk[j * 4 - 1])
            if (j % 4 == 0) {
                prev = byteArrayOf(
                    ((init ushr (r and 24)) and 0xFF xor sboxAt((prev[1].toInt() and 0xFF))).toByte(),
                    sboxAt(prev[2].toInt() and 0xFF).toByte(),
                    sboxAt(prev[3].toInt() and 0xFF).toByte(),
                    sboxAt(prev[0].toInt() and 0xFF).toByte()
                )
            }
            r += 2
            for (k in 0 until 4) {
                mk[j * 4 + k] = ((prev[k].toInt() and 0xFF) xor (mk[j * 4 + k - 16].toInt() and 0xFF)).toByte()
            }
        }
        fun block(src: ByteArray): ByteArray {
            val b = src.copyOf()
            fun add(base: Int) {
                for (j in b.indices) {
                    b[j] = (b[j].toInt() and 0xFF xor (mk[base + (j / 4) * 4 + con[j % 4]].toInt() and 0xFF)).toByte()
                }
            }
            add(0)
            for (round in 1..2) {
                var prev = b.copyOf()
                for (j in b.indices) {
                    val index = order[j]
                    b[j] = sboxAt(prev[con[index / 4] * 4 + index % 4].toInt() and 0xFF).toByte()
                }
                if (round == 1) {
                    prev = b.copyOf()
                    for (j in b.indices) {
                        b[j] = prev[(j / 4) * 4 + con[j % 4]]
                    }
                    fun xt(x: Int): Int = if (x and 128 != 0) ((x shl 1) xor 0x1B) and 0xFF else (x shl 1) and 0xFF
                    for (col in 0 until 4) {
                        val a0 = b[col].toInt() and 0xFF
                        val c0 = b[4 + col].toInt() and 0xFF
                        val d0 = b[8 + col].toInt() and 0xFF
                        val e0 = b[12 + col].toInt() and 0xFF
                        val t = a0 xor c0 xor d0 xor e0
                        b[col] = (a0 xor t xor xt(a0 xor c0)).toByte()
                        b[4 + col] = (c0 xor t xor xt(c0 xor d0)).toByte()
                        b[8 + col] = (d0 xor t xor xt(d0 xor e0)).toByte()
                        b[12 + col] = (e0 xor t xor xt(e0 xor a0)).toByte()
                    }
                }
                add(round * 16)
            }
            for (j in b.indices) {
                b[j] = ((b[j].toInt() and 0xFF) xor (mk[16 + j].toInt() and 0xFF)).toByte()
            }
            return b
        }
        val plain = ByteArray(32)
        for (j in 0 until 31) {
            fun d(k: Int) = data[j * 8 + k].toInt() and 0xFF
            plain[j] = (
                ((d(0) ushr 4) and 2) or (d(1) and 64) or ((d(2) ushr 2) and 1) or
                    ((d(3) shl 3) and 128) or ((d(4) ushr 1) and 4) or ((d(5) shl 3) and 16) or
                    ((d(6) shl 5) and 32) or ((d(7) ushr 4) and 8)
                ).toByte()
        }
        plain[31] = 1
        var previous = hexToBytes("1fe109a4125283f418de9e051a969e12")
        val encrypted = ArrayList<Byte>()
        for (j in 0 until 32 step 16) {
            for (k in 0 until 16) {
                plain[j + k] = ((plain[j + k].toInt() and 0xFF) xor (previous[k].toInt() and 0xFF)).toByte()
            }
            previous = block(plain.copyOfRange(j, j + 16))
            encrypted.addAll(previous.toList())
        }
        val enc = encrypted.toByteArray()
        val out = ByteArray(1 + data.size)
        out[0] = enc[31]
        System.arraycopy(data, 0, out, 1, data.size)
        for (j in 0 until 31) {
            val kk = enc[j].toInt() and 0xFF
            fun at(k: Int): Int = out[1 + j * 8 + k].toInt() and 0xFF
            fun set(k: Int, v: Int) { out[1 + j * 8 + k] = v.toByte() }
            set(0, (at(0) and 0xDF.toInt()) or ((kk shl 4) and 32))
            set(1, (at(1) and 0xBF) or (kk and 64))
            set(2, (at(2) and 0xFB) or ((kk shl 2) and 4))
            set(3, (at(3) and 0xEF) or ((kk ushr 3) and 16))
            set(4, (at(4) and 0xF7) or ((kk shl 1) and 8))
            set(5, (at(5) and 0xFD) or ((kk ushr 3) and 2))
            set(6, (at(6) and 0xFE) or ((kk ushr 5) and 1))
            set(7, (at(7) and 0x7F) or ((kk shl 4) and 128))
        }
        return out
    }

    internal fun medusa(
        query: String, ts: Int, nonce: Int, hashRand: Int, xmRand: Int,
        deviceId: String, iv: Int, q: ByteArray, m: ByteArray, version: String,
        targetBranch: Int = 2
    ): String {
        val f13 = if (targetBranch == 0) f13Branch0(iv, ts, q, m) else f13(iv, ts, q, m)
        var p = medusaProto(query, ts, nonce, deviceId, q, m, f13, version)
        val signKey = hexToBytes("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
        val key = sm3(signKey + leU32(hashRand) + signKey)
        val d1 = (hashRand ushr 16) and 255
        // Go 优先级 ^ 高于 |：(d1<<11) | ((hashRand>>24) ^ (d1>>5) ^ d1)；Kotlin 位运算同层左结合，必须显式括号
        val seed = leU32(((d1 shl 11) or ((hashRand ushr 24) xor (d1 ushr 5) xor d1)).inv())
        p = hexToBytes("4001000000000000") + xmXor(p, key)
        p.reverse()
        for (idx in p.indices) {
            p[idx] = ((p[idx].toInt() and 0xFF) xor (seed[(idx.inv()) and 3].toInt() and 0xFF)).toByte()
        }
        val check = (0x18000001) or ((q[0].toInt() and 63) shl 14) or ((f13[0].toInt() and 63) shl 8)
        var body = byteArrayOf(0x35.toByte()) + leU32(xmRand) + leU32(check) + p
        body += leU32(hashRand).copyOfRange(2, 4)
        val enc = aesV3(body, ts)
        val header = hexToBytes("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
        for (j in 0 until 20 step 4) {
            val x = leU32At(header, j) xor ts
            header[j] = x.toByte(); header[j + 1] = (x ushr 8).toByte()
            header[j + 2] = (x ushr 16).toByte(); header[j + 3] = (x ushr 24).toByte()
        }
        val full = header + leU32(hashRand).copyOfRange(0, 2) + byteArrayOf(0, 1) + enc
        return base64(full)
    }

    /** 业务请求全量签名：返回(定稿 query, 请求头)。对照 signPassportRequest + Node playbackRequest。
     *  @param targetBranch F13 分支：账号/landpage 走 2（Go 版路径），播放接口走 0（Node 版路径）。 */
    internal class SignedRequest(val query: String, val headers: Map<String, String>)

    internal fun signRequest(
        params: Map<String, String>,
        body: ByteArray?,
        stampMs: Long,
        deviceId: String,
        targetBranch: Int = 2,
        version: String = "7.3.5.32"
    ): SignedRequest {
        // 分支调参：仅变 _rticket 重选签名分支（branchOf = (iv&15)%3）
        var raw = ""
        var br: Branch? = null
        val tuned = LinkedHashMap(params)
        for (tweak in 0 until 256L) {
            tuned["_rticket"] = (stampMs + tweak).toString()
            raw = encodeQuery(tuned)
            val cand = branch(raw, body, (stampMs / 1000).toInt())
            if ((cand.iv and 15) % 3 == targetBranch) {
                br = cand
                break
            }
        }
        val branchObj = br ?: throw IllegalStateException("unable to select signature branch")
        val n0 = randomU32(); val n1 = randomU32(); val n2 = randomU32(); val n3 = randomU32()
        val ts = (stampMs / 1000).toInt()
        val med = medusa(raw, ts, n0, n1, n2, deviceId, branchObj.iv, branchObj.queryHash, branchObj.bodyMd5, version, targetBranch)
        val headers: LinkedHashMap<String, String> = linkedMapOf(
            "X-Khronos" to ts.toUInt().toString(),
            // branch 调参会改写 tuned._rticket（签名 query 里带的才是最终值），
            // 取原 params 恒为空串——服务端校验 ticket/query 一致性时空串必拒。
            "X-SS-Req-Ticket" to (tuned["_rticket"] ?: params["_rticket"] ?: ""),
            "X-Helios" to helios(ts, n3),
            "X-Medusa" to med,
            "X-Gorgon" to gorgon(raw, body, ts, n0 and 0xFFFF)
        )
        if (body != null && body.isNotEmpty()) {
            headers["X-SS-STUB"] = toHex(branchObj.bodyMd5).uppercase()
        }
        return SignedRequest(raw, headers)
    }

    /** 与 Go url.Values.Encode() 字节级一致：key 字典序 + Go QueryEscape 保留集（-_.~）。 */
    internal fun encodeQuery(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${escapeGo(k)}=${escapeGo(v)}"
        }
    }

    private fun escapeGo(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~' ->
                    sb.append(ch)
                // 2026-11-10 对齐 pythonUrlEncode（Node M1 实证风格）：`*` 不编码、
                // 空格 %20——官方 TTNet query 从不出现 %2A/+，风控按形态评分。
                ch == '*' -> sb.append('*')
                ch == ' ' -> sb.append("%20")
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append(String.format("%02X", b))
                    }
                }
            }
        }
        return sb.toString()
    }
}
