package com.mytvb.feature.hongguo.net

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 红果 V5 播放签名 x-medusa —— HGTV 1.3.8.12 的逐函数移植
 * （L3.g → C0234w.a → I1.e 的 selector=12 分支 → r0.G 序列化 / r0.E 加密）。
 *
 * 2026-11-23 用 frida 抓到的 48 组 HGTV 真实签名做离线对拍：同输入下 protobuf 逐字节、
 * x-medusa 全字符串全等（[HongguoMedusaGoldenTest]；解密器/参考实现/样本在
 * `.zcode/hgtv-sign/tools/`）。服务端 11-23 起收紧 medusa 校验，此前 PR#13（Go 版）的
 * 简化实现——摘要轮函数、protobuf 消息体、内层包布局、位映射均不等价——即被拒（空 body）。
 *
 * 输入必须是实际发送的那一份：query 串（不含 '?'）、gzip 后的 body 字节、x-khronos 秒、
 * 当前毫秒。query 需先调谐到 selector==12（见 [selectorOf]，由 [HongguoPlaybackV5.sign] 完成）。
 *
 * 链路：
 *  1. 种子 = SM3(query) + MD5(body) + le32(ts)
 *  2. [digest12]：SHA-256 骨架的变体轮函数，出 16 字节 + 4 字节校验
 *  3. protobuf：schema 驱动（HGTV G1.f1751a），主消息 + 环境子消息 + 设备子消息 + 状态 JSON
 *  4. [encryptE]：xmxor(SM3(signKey+rnd+signKey)) → 反序异或 → 位映射 → AES 变种载波 → 位映射回
 *  5. 头 = le32(ts^magic)×5 + rnd 低 2 字节 + 00 01 + 密文，base64
 *
 * 数值口径：int/long 自然回绕，与 Java 语义一致，直接对照 HGTV 源码书写。
 */
internal object HongguoMedusa {

    private fun hex(s: String) = HongguoCrypto.hexToBytes(s)
    private fun le32(n: Int) = HongguoCrypto.leU32(n)
    private fun le64(n: Long) = ByteArray(8) { i -> (n ushr (i * 8)).toByte() }
    private fun md5(b: ByteArray) = HongguoCrypto.md5(b)
    private fun sm3(b: ByteArray) = HongguoCrypto.sm3(b)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun be32At(b: ByteArray, off: Int) = HongguoCrypto.beU32At(b, off)
    private fun le32At(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 255L) or ((b[off + 1].toLong() and 255L) shl 8) or
            ((b[off + 2].toLong() and 255L) shl 16) or ((b[off + 3].toLong() and 255L) shl 24)

    // ---- 常量（全部取自 HGTV 1.3.8.12 dex，经 tools/extract_consts.py 从字节码数据段直读）----
    private val MAGIC = hex("2d4b4fca49750d433fb5ae2c226dcc56")
    private val SIGN_KEY = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
    private val PAD_SEED = hex("fa4561d7")
        private val TT = hex("ebb64faf07aadcc2cf3187bfe01138ff6d0bfcff5a30a3beb41ad63834180eb8f233eb6fb1a584ccccc30dc747d1db51d55653de70a84fa157473c12f76f0288"
            + "2c077f0ada0dcad0fbb86f6cfdc4cf00688a020de676c6a68cd6338b1a3c8d0ecce8b06b6ad0ed0ba0522717dc71ac832285db71d5b4dda6736f86506560306c"
            + "617ce2a6e423417e0a40e143544e403288dffb2a716c1ae04c467a8805b23bb3e1d0b866baa3dcb8ae3374d3c3381a501702f75bfe6da368f0b4cf484e0ffbb8"
            + "72aad10d26c53a3df2bce0f6b45575814a257fdd8c3182a2ab0b3b863d5dfb144f103634d37b52d7444eff16eb0a33d16ca86f6e00284ba708387cfa5fb37586")
        private val INIT8 = hex("7aba4fc8671665076403fa00340f512f3aab4d10b31d643aab4fabed804b3850")
        private val SBOX = hex("53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0df37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a"
            + "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1dd12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912"
            + "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe"
            + "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c894545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc"
            + "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8de0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e"
            + "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd288fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5"
            + "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac91fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f"
            + "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f44591681335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e")
        private val TBL64 = hex("3a322a221a120a023c342c241c140c043e362e261e160e06403830282018100839312921191109013b332b231b130b033d352d251d150d053f372f271f170f07")
        private val AES_INIT = longArrayOf(3149258354L, 3563294043L, 2460350267L, 2314772937L)
        private val AES_Q = arrayOf(intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 2, 0), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 1, 0))
        private val AES_R = arrayOf(intArrayOf(15, 7, 3, 11), intArrayOf(11, 3, 15, 7), intArrayOf(3, 15, 11, 7), intArrayOf(15, 3, 11, 7))
        private val BIT_IN = arrayOf(intArrayOf(5, 7), intArrayOf(0, 6), intArrayOf(4, 2), intArrayOf(2, 3), intArrayOf(6, 1), intArrayOf(7, 0), intArrayOf(3, 4), intArrayOf(1, 5))
        private val BIT_OUT = arrayOf(intArrayOf(7, 1, 5), intArrayOf(6, 3, 4), intArrayOf(3, 2, 3), intArrayOf(5, 7, 0), intArrayOf(4, 6, 1), intArrayOf(1, 0, 6), intArrayOf(0, 5, 7), intArrayOf(2, 4, 2))

    /** 头部 magic 的五个字（le32(ts ^ w) 逐个写出）。 */
    private val MAGIC_WORDS = longArrayOf(5, 3394194221L, 1124955465L, 749647167L, 1456237858L)
    private const val LICENSE = "646637794"
    private const val APP_ID = "8662"
    /** detectionTypes {2,8,10,15} 的位图。 */
    private const val DETECT = 0x8504L
    /** 设备子消息 16..21 的六个 float（HGTV 写死的存储画像）。 */
    private val DEV_FLOATS = floatArrayOf(124.99121f, 121.888664f, 3.8510208f, 0.09474182f, 249.98242f, 243.77733f)

    private val RANDOM = SecureRandom()

    // ---- 基础原语 ----

    /** r0.C：字节流式校验（与 [HongguoCrypto.ivTransform] 同构，参数化移位量）。 */
    internal fun chk(b: ByteArray, seed: Long, i2: Int, i3: Int, i4: Int, i5: Int): Long {
        var j = seed and 0xFFFFFFFFL
        for (i in b.indices) {
            val x = b[i].toLong() and 255L
            j = if (i and 1 == 0) {
                ((j ushr i3) xor (x xor (((j shl i2) and 0xFFFFFFFFL) xor j))) and 0xFFFFFFFFL
            } else {
                ((j xor ((x or ((j shl i4) and 0xFFFFFFFFL)) xor (j ushr i5))) xor -1L) and 0xFFFFFFFFL
            }
        }
        return j
    }

    /** I1.d：CRC-64 变体（多项式 0x95AC9329AC4BC9B5，初值 0，结果不取反）。 */
    internal fun crc64(b: ByteArray): Long {
        var j = 0L
        for (byte in b) {
            var j3 = ((byte.toLong() and 255L) xor j) and 255L
            for (i in 0 until 8) j3 = if (1L and j3 != 0L) (j3 ushr 1) xor -7661587058870466123L else j3 ushr 1
            j = (j ushr 8) xor j3
        }
        return j
    }

    private val CRC64_LIC = crc64(LICENSE.toByteArray())

    /** selector = C(种子) & 15；V5 的 x-khronos/_rticket 调谐目标为 12。 */
    internal fun selectorOf(queryHash: ByteArray, bodyMd5: ByteArray, ts: Int): Int =
        (chk(queryHash + bodyMd5 + le32(ts), 539167016L, 6, 4, 12, 7) and 15L).toInt()

    private fun rotr(x: Int, n: Int) = Integer.rotateLeft(x, -n)

    /** r0.g0：8 位循环左移。 */
    private fun rotl8(x: Int, n: Int): Int {
        val v = x and 255
        val k = n and 7
        return if (k == 0) v else ((v ushr (8 - k)) or (v shl k)) and 255
    }

    private fun base64Url(b: ByteArray): String =
        HongguoCrypto.base64(b).trimEnd('=').replace('+', '-').replace('/', '_')

    // ---- 第 2 层：selector=12 摘要（K1：索引表 {3,5,2,7,1,0,4,6}、计数偏移 66、轮数 96）----

    internal fun digest12(queryHash: ByteArray, bodyMd5: ByteArray, ts: Int): ByteArray {
        val count = ts + 66
        val block = ByteArray(64)
        System.arraycopy(queryHash, 0, block, 0, 32)
        System.arraycopy(bodyMd5, 0, block, 32, 16)
        System.arraycopy(le32(ts), 0, block, 48, 4)
        for (i in 0 until 4) block[52 + i] = rotl8(PAD_SEED[i].toInt(), -(count + 2)).toByte()
        // be64(416)
        block[62] = 1
        block[63] = 0xA0.toByte()

        val di = IntArray(128)
        for (i in 0 until 16) di[i] = be32At(block, i * 4)
        for (i in 16 until 128) {
            val a = di[i - 15]
            val b = di[i - 2]
            di[i] = di[i - 16] + (rotr(a, 18) xor rotr(a, 7) xor (a ushr 3)) + di[i - 7] +
                (rotr(b, 17) xor rotr(b, 19) xor (b ushr 10))
        }
        val initial = IntArray(8) { rotr(be32At(INIT8, it * 4), count) }
        val tt = IntArray(64) { rotr(be32At(TT, it * 4), count + 1) }

        var st = initial.copyOf()
        for (r in 0 until 96) {
            // 轮常量下标从计数偏移（66）起逐轮 +1，是不含时间戳的固定下标
            val base = 66 + r
            val s = st
            val a = s[3]; val b = s[5]; val c = s[2]; val d = s[7]
            val e = s[1]; val f = s[0]; val g = s[4]; val h = s[6]
            val maj = ((a or b) and c) or (a and b)
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val t1 = h + (rotr(e, 25) xor rotr(e, 11) xor rotr(e, 6)) + (g xor (e and (f xor g))) +
                tt[base and 63] + di[base and 127]
            val ns = intArrayOf(s[7], s[0], s[1], s[2], s[3], s[4], s[5], s[6])
            ns[0] = d + t1
            ns[7] = t1 + s0 + maj
            st = ns
        }
        val wide = ByteArray(32)
        for (i in 0 until 8) {
            val v = st[i] + initial[i]
            wide[i * 4] = (v ushr 24).toByte()
            wide[i * 4 + 1] = (v ushr 16).toByte()
            wide[i * 4 + 2] = (v ushr 8).toByte()
            wide[i * 4 + 3] = v.toByte()
        }
        val out = ByteArray(20)
        for (i in 0 until 16) out[i] = (wide[i].toInt() xor wide[i + 16].toInt()).toByte()
        val check = chk(out.copyOfRange(0, 12), 539100192L, 7, 3, 11, 5).toInt()
        for (i in 0 until 4) out[16 + i] = (check ushr (i * 8)).toByte()
        out[16] = (out[16].toInt() or 4 or 32).toByte()
        // HGTV M0(47) 的 configCount 标志运行时为真（48 组样本 dg[19] 实证；jadx 把该三元式显示反了）
        out[19] = (out[19].toInt() xor 1).toByte()
        return out
    }

    // ---- 第 3 层：schema 驱动的 protobuf（r0.G + G1.f1751a）----

    private const val T_I32 = 1      // zigzag varint
    private const val T_I64 = 4      // zigzag varint（64 位）
    private const val T_F32 = 6      // 定长 32 位
    private const val T_FLOAT = 10   // IEEE float
    private const val T_VARINT = 13  // 原样 varint
    private const val T_CSTR = 14    // bytes/string，截到首个 0 字节
    private const val T_BYTES = 15   // bytes/string
    private const val T_MSG = 16     // 嵌套消息

    private fun schema(vararg entries: Pair<Int, Int>): LinkedHashMap<Int, Int> = linkedMapOf(*entries)

    private fun msg(vararg entries: Pair<Int, Any>): LinkedHashMap<Int, Any> = linkedMapOf(*entries)

    private val SC_MAIN = schema(
        1 to T_BYTES, 2 to T_I32, 3 to T_I32, 4 to T_CSTR, 5 to T_CSTR, 6 to T_CSTR, 7 to T_CSTR, 8 to T_CSTR,
        9 to T_I32, 10 to T_BYTES, 11 to T_VARINT, 12 to T_I64, 13 to T_BYTES, 14 to T_BYTES,
        15 to T_MSG, 16 to T_CSTR, 17 to T_I32, 18 to T_BYTES, 19 to T_BYTES, 20 to T_CSTR, 21 to T_I32,
        22 to T_I32, 23 to T_MSG, 24 to T_CSTR, 25 to T_MSG, 26 to T_I32, 27 to T_BYTES, 28 to T_I32, 29 to T_F32
    )
    private val SC_15 = schema(1 to T_I32, 2 to T_I32, 3 to T_I32, 4 to T_I32, 5 to T_I32)
    private val SC_ENV = schema(
        1 to T_I32, 2 to T_I64, 3 to T_I64, 4 to T_I32, 5 to T_I32, 6 to T_CSTR, 7 to T_I32, 8 to T_CSTR,
        9 to T_CSTR, 10 to T_CSTR, 11 to T_CSTR, 12 to T_MSG, 13 to T_MSG, 14 to T_CSTR, 15 to T_I32,
        16 to T_I64, 17 to T_I64, 18 to T_I64, 19 to T_I32, 20 to T_I64, 21 to T_I32, 22 to T_I32,
        23 to T_I32, 24 to T_I32
    )
    private val SC_DEV = schema(
        1 to T_I32, 2 to T_I32, 3 to T_CSTR, 4 to T_CSTR, 5 to T_CSTR, 6 to T_CSTR, 7 to T_I32, 8 to T_I32,
        9 to T_I32, 10 to T_I32, 11 to T_CSTR, 12 to T_CSTR, 13 to T_CSTR, 14 to T_I32, 15 to T_CSTR,
        16 to T_FLOAT, 17 to T_FLOAT, 18 to T_FLOAT, 19 to T_FLOAT, 20 to T_FLOAT, 21 to T_FLOAT, 22 to T_CSTR,
        23 to T_I32, 24 to T_I32, 25 to T_I64, 26 to T_I64, 27 to T_I64, 28 to T_I64, 29 to T_I32,
        30 to T_CSTR, 31 to T_CSTR, 32 to T_CSTR, 33 to T_CSTR, 34 to T_CSTR, 35 to T_CSTR, 36 to T_CSTR,
        37 to T_CSTR, 38 to T_I64, 39 to T_BYTES, 40 to T_I64
    )
    private val SC_ENV13 = schema(1 to T_I64, 2 to T_VARINT, 3 to T_CSTR, 4 to T_I32, 5 to T_I32, 6 to T_I32)
    private val SC_25 = schema(1 to T_CSTR, 2 to T_CSTR, 3 to T_CSTR, 4 to T_I32)

    /** 嵌套子消息的 schema，键为字段路径（根路径为空串，如 "23.12"）。 */
    private val SC_NESTED = mapOf(
        "15" to SC_15, "23" to SC_ENV, "23.12" to SC_DEV, "23.13" to SC_ENV13, "25" to SC_25
    )

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (java.lang.Long.compareUnsigned(v, 128) >= 0) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    /**
     * r0.G：按 schema 顺序输出。值的编码由 schema 类型决定；Int/Long 需按源码里的装箱类型给
     * （int32 走 [T_I32]、long 走 [T_I64]），0 值与空串不输出，嵌套消息即使为空也输出。
     */
    private fun serialize(m: Map<Int, Any>, sc: Map<Int, Int>, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        for ((id, type) in sc) {
            val v = m[id] ?: continue
            when (type) {
                T_I32 -> {
                    val i = v as Int
                    if (i == 0) continue
                    writeVarint(out, (id shl 3).toLong())
                    writeVarint(out, (((i shr 31) xor (i shl 1)).toLong()) and 0xFFFFFFFFL)
                }
                T_I64 -> {
                    val l = v as Long
                    if (l == 0L) continue
                    writeVarint(out, (id shl 3).toLong())
                    writeVarint(out, (l shl 1) xor (l shr 63))
                }
                T_VARINT -> {
                    val l = v as Long
                    if (l == 0L) continue
                    writeVarint(out, (id shl 3).toLong())
                    writeVarint(out, l)
                }
                T_F32 -> {
                    val i = v as Int
                    if (i == 0) continue
                    writeVarint(out, ((id shl 3) or 5).toLong())
                    out.write(le32(i))
                }
                T_FLOAT -> {
                    val fv = v as Float
                    if (fv == 0.0f) continue
                    writeVarint(out, ((id shl 3) or 5).toLong())
                    out.write(le32(java.lang.Float.floatToRawIntBits(fv)))
                }
                T_MSG -> {
                    val subPath = "$path$id."
                    @Suppress("UNCHECKED_CAST")
                    val sub = serialize(v as Map<Int, Any>, SC_NESTED.getValue(subPath.dropLast(1)), subPath)
                    writeVarint(out, ((id shl 3) or 2).toLong())
                    writeVarint(out, sub.size.toLong())
                    out.write(sub)
                }
                else -> {
                    var b = when (v) {
                        is ByteArray -> v
                        is String -> v.toByteArray(Charsets.UTF_8)
                        else -> throw IllegalStateException("invalid Medusa bytes")
                    }
                    if (type == T_CSTR) {
                        val zero = b.indexOf(0)
                        if (zero >= 0) b = b.copyOf(zero)
                    }
                    if (b.isEmpty()) continue
                    writeVarint(out, ((id shl 3) or 2).toLong())
                    writeVarint(out, b.size.toLong())
                    out.write(b)
                }
            }
        }
        return out.toByteArray()
    }

    /** I1.a(f, 2..6)：由签名器构造时刻派生的 9 元 fl 数组（仅下标 2..6 非零）。 */
    internal fun stepFl(j6: Long): IntArray {
        val arr = IntArray(9)
        arr[2] = ((j6 ushr 32) xor (j6 and 0xFFFFFFFFL)).toInt()
        arr[3] = Integer.reverseBytes(arr[2])
        arr[4] = chk(le32(arr[3]) + ByteArray(4), 2102535L, 6, 4, 12, 7).toInt()
        // F1.f1729c 低两位恒为 1（511）→ 旋转两次
        var rot = Integer.rotateLeft(arr[4], 2)
        var v = 0
        for (k in 0 until 4) v = v or ((TBL64[(rot ushr (k * 8)) and 63].toInt() and 0xFF) shl (k * 8))
        arr[5] = v
        val j4 = arr[5].toLong() and 0xFFFFFFFFL
        val j5 = j4 xor (j4 shl 13)
        val i3 = (j5 xor (j5 ushr 17)).toInt()
        arr[6] = (i3 shl 5) xor i3
        return arr
    }

    /** cachedToken = base64url(SHA-256("hgtv-sdk-token\0" + token + "\0" + deviceId)[:18])；无 token 时 token 取 deviceId。 */
    internal fun cachedTokenOf(p: Profile): String {
        val token = p.deviceToken.ifBlank { p.deviceId }
        val seed = "hgtv-sdk-token\u0000$token\u0000${p.deviceId}".toByteArray(Charsets.UTF_8)
        return base64Url(sha256(seed).copyOf(18))
    }

    /** C0229v.f2652d：J1.f1816a 的链式初值（依赖签名器构造时刻的秒）。 */
    internal fun tlsInitOf(createdAt: Long): Long {
        val lic = CRC64_LIC xor 523425248256L xor ((createdAt / 1000) and 0xFFFFFFFFL)
        return ((java.lang.Long.rotateLeft(lic, 4) and 0xFFFFFFFFL) xor 4103350170L) and 0xFFFFFFFFL
    }

    /**
     * 组包（I1.e 后半）。状态全部显式传入以便对拍，字段值逐项对照源码。
     * @param tls 本次已演化的链式值（J1.f1816a）
     */
    internal fun buildProto(
        p: Profile, queryHash: ByteArray, digest: ByteArray, ts: Int, rnd: LongArray, nowMillis: Long,
        counter: Long, tls: Long, createdAt: Long, l3StartedAt: Long, processNonce: String
    ): ByteArray {
        val rec8 = 511L
        val j6 = createdAt - 180663
        val startedSec = createdAt / 1000 - 180
        val firstTpidr = 518304718848L
        val callbackAddress = 519321784724L
        val tsL = ts.toLong()

        val main = msg(
            1 to MAGIC, 2 to 5, 3 to (rnd[0] and 0xFFFFFFFFL).toInt(), 4 to APP_ID, 5 to p.deviceId,
            6 to LICENSE, 7 to p.versionName, 8 to "v04.10.03-ml-android", 9 to 67764992, 10 to le64(DETECT),
            12 to tsL, 13 to digest, 14 to queryHash.copyOf(6),
            15 to msg(1 to counter.toInt(), 2 to 5, 3 to 694367, 5 to 281826712),
            16 to processNonce, 17 to ts, 20 to "none", 21 to 312
        )
        val env = msg(
            // jadx 显示为「startedAtSeconds == 0 才写」，48 组样本实证相反：非零时写 (int)(ts - startedAtSeconds)
            1 to ((tsL - startedSec) and 0xFFFFFFFFL).toInt(),
            2 to 247235599L, 3 to 247235596L, 5 to 7, 6 to "v04.10.03.04-bugfix", 7 to 22088,
            8 to p.channel, 14 to p.versionName, 15 to 2113, 16 to 27L, 17 to 2491L,
            18 to ((firstTpidr xor callbackAddress) xor tsL),
            19 to ((1804L xor tsL) and 0xFFFFFFFFL).toInt(),
            20 to (callbackAddress xor tsL), 22 to -780914011, 23 to -780914011
        )
        val dev = msg(
            1 to 1, 2 to 2, 3 to APP_ID, 4 to p.deviceId, 5 to cachedTokenOf(p), 6 to "!noperm!", 7 to -888888,
            8 to -888888, 9 to 2, 10 to -888888, 11 to "!notset!", 12 to "Asia/Shanghai,8", 13 to "zh_CN",
            14 to 3, 15 to p.physicalSize(), 22 to p.osVersion, 23 to 102, 24 to 5, 25 to l3StartedAt,
            26 to createdAt - 180290, 27 to createdAt - 6117375, 28 to nowMillis, 29 to -1,
            30 to p.deviceType, 31 to p.deviceBrand, 32 to "kona", 33 to "arm64_mac_qemu", 34 to "OnePlus8Pro",
            35 to "OnePlus", 36 to "unknown", 38 to 21L, 40 to j6
        )
        for (i in DEV_FLOATS.indices) dev[16 + i] = DEV_FLOATS[i]
        env[12] = dev
        // cache168 = j6/1000+3、cache176 = 3、cache200 = 200（均非零，不走回退分支）
        env[13] = msg(1 to j6 / 1000 + 3, 2 to 3L, 4 to 200)
        main[23] = env

        val arr = stepFl(j6)
        val lp = "2|${rec8 xor 520830913368L}|${tls xor 520830913368L}|${j6 xor 520830913368L}|523425248256"
        val fl = (0..8).joinToString("|") { (arr[it].toLong() and 0xFFFFFFFFL).toString() } + "|" + (tls and 0xFFFFFFFFL)
        main[24] = ("{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1893996536,\"vpn\":0,\"sts\":32251,\"kd\":0," +
            "\"fkd\":2187746471,\"pd\":1417818606,\"lp\":\"$lp\",\"fl\":\"$fl\",\"dyn\":\"\",\"do\":1,\"tk\":true}")
        return serialize(main, SC_MAIN, "")
    }

    // ---- 签名器：对应 I1.c 构造的 C0234w（同一身份复用一个实例，内部状态逐次演化）----

    /**
     * 签名画像：一律「请求参数里有就用、没有用 HGTV 的默认值」（I1.c 的口径）。
     * [resolution] 为请求参数原值（如 `1920*1080`）。
     */
    class Profile(
        val deviceId: String,
        val deviceToken: String = "",
        val versionName: String = "7.4.3.32",
        val channel: String = "update_64",
        val resolution: String = "",
        val osVersion: String = "12",
        val deviceType: String = "PGT-AN10",
        val deviceBrand: String = "OnePlus",
    ) {
        /** "Physical size: 短边x长边"；解析失败回落 HGTV 默认 1080x2400。 */
        internal fun physicalSize(): String = runCatching {
            val parts = resolution.split('*')
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            "Physical size: ${minOf(a, b)}x${maxOf(a, b)}"
        }.getOrDefault("Physical size: 1080x2400")

        companion object {
            private fun String?.orDefault(d: String): String = this?.trim()?.takeIf { it.isNotEmpty() } ?: d

            /** 从请求参数取画像（[get] 取不到返回 null）；空白值回落默认。 */
            fun fromParams(get: (String) -> String?): Profile = Profile(
                deviceId = get("device_id").orDefault(""),
                deviceToken = get("device_token").orDefault(""),
                versionName = get("version_name").orDefault("7.4.3.32"),
                channel = get("channel").orDefault("update_64"),
                resolution = get("resolution").orDefault(""),
                osVersion = get("os_version").orDefault("12"),
                deviceType = get("device_type").orDefault("PGT-AN10"),
                deviceBrand = get("device_brand").orDefault("OnePlus"),
            )
        }
    }

    class Signer internal constructor(
        internal val profile: Profile,
        /** L3.f1856f：L3 构造时刻 - 6936472ms（进程级）。 */
        internal val l3StartedAt: Long,
        /** 本签名器构造时刻（毫秒）。 */
        internal val createdAt: Long,
        /** L3.f1854d：进程级 18 随机字节的 base64url。 */
        internal val processNonce: String,
    ) {
        internal var counter = 316L
        internal var tls: Long = tlsInitOf(createdAt)

        /**
         * 对一条请求签名。[query] 是**纯 query 串**——实际发送的 `?` 之后那一段，不含 `?`；
         * [body] 是实际发送的字节。签名覆盖的就是这两个入参，调用方必须传最终发出的原样。
         */
        @Synchronized
        fun sign(query: String, body: ByteArray?, tsSec: Int, nowMillis: Long = System.currentTimeMillis()): String =
            signWith(this, query, body, tsSec, nowMillis, drawRandoms()).medusa
    }

    internal class Signed(val medusa: String, val proto: ByteArray)

    /** f2674f：每次签名重抽 12 字节 → 3 个 le32（清符号位）。 */
    internal fun drawRandoms(): LongArray {
        val b = ByteArray(12)
        RANDOM.nextBytes(b)
        return LongArray(3) { le32At(b, it * 4) and 0x7FFFFFFFL }
    }

    /**
     * 一次签名的纯函数形态：给定全部状态与随机数算出结果，并把 [s] 的链式值与计数器前进一步。
     * 顺序对照 C0234w.a + I1.e：先演化 tls（J1.f1816a ^= rotl(crc ^ ts, 4)），组包加密，最后 counter+1。
     *
     * [query] 是纯 query 串（不含 `?`）。HGTV 的 I1.e 收的是完整 URL、内部再截 `?` 之后；这里不截，
     * 由调用方给纯 query——2026-11-23 曾因为对纯 query 做 `substringAfter('?')` 取到空串，
     * 等于给空 query 签名，发枪前的 dry-run 解密核对才拦下。
     */
    internal fun signWith(
        s: Signer, query: String, body: ByteArray?, tsSec: Int, nowMillis: Long, rnd: LongArray
    ): Signed {
        val lic = java.lang.Long.rotateLeft(CRC64_LIC xor (tsSec.toLong() and 0xFFFFFFFFL), 4) and 0xFFFFFFFFL
        val tls = (s.tls xor lic) and 0xFFFFFFFFL
        val queryHash = sm3(query.toByteArray(Charsets.UTF_8))
        val bodyMd5 = if (body == null || body.isEmpty()) ByteArray(16) else md5(body)
        val digest = digest12(queryHash, bodyMd5, tsSec)
        val proto = buildProto(
            s.profile, queryHash, digest, tsSec, rnd, nowMillis, s.counter, tls,
            s.createdAt, s.l3StartedAt, s.processNonce
        )
        val check = ((queryHash[0].toLong() and 63L) shl 46) or 0x1800000100000000L or
            ((digest[0].toLong() and 63L) shl 40) or (1L shl 32) or (rnd[2] and 0xFFFFFFFFL)
        val medusa = encryptE(proto, tsSec, rnd[1], check, le64(DETECT))
        s.tls = tls
        s.counter++
        return Signed(medusa, proto)
    }

    // ---- 进程级状态与签名器缓存（L3 单例 + f1850g 以 deviceId/token/version/channel/v5 为键）----

    private val l3StartedAt: Long by lazy { System.currentTimeMillis() - 6936472L }
    private val processNonce: String by lazy {
        val b = ByteArray(18)
        RANDOM.nextBytes(b)
        base64Url(b)
    }
    private val signers = HashMap<String, Signer>()

    fun signer(p: Profile): Signer = synchronized(signers) {
        val key = "${p.deviceId}\u0000${p.deviceToken}\u0000${p.versionName}\u0000${p.channel}\u0000v5"
        signers.getOrPut(key) {
            Signer(p, l3StartedAt, System.currentTimeMillis(), processNonce)
        }
    }

    /** 便捷入口：给定 query 串与 body 直接签（内部按 [Profile.fromParams] 取画像并复用签名器）。 */
    fun medusaFor(query: String, body: ByteArray?, tsSec: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val signer = signer(Profile.fromParams { queryValue(query, it) })
        return signer.sign(query, body, tsSec, nowMillis)
    }

    /** 从 query 串取参数值（%XX 解码）。 */
    internal fun queryValue(query: String, name: String): String? {
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0 || pair.substring(0, i) != name) continue
            val raw = pair.substring(i + 1)
            val sb = StringBuilder(raw.length)
            var j = 0
            while (j < raw.length) {
                val c = raw[j]
                if (c == '%' && j + 2 < raw.length) {
                    val v = raw.substring(j + 1, j + 3).toIntOrNull(16)
                    if (v != null) {
                        sb.append(v.toChar())
                        j += 3
                        continue
                    }
                }
                sb.append(c)
                j++
            }
            return sb.toString()
        }
        return null
    }

    // ---- 第 4 层：r0.E（xmxor → 反序异或 → 位映射 → AES 变种载波 → 位映射回）----

    /** r0.E 的 xmxor 段：逐字节混淆后反序写入，再做首尾的链式修正。 */
    private fun xmxor(p: ByteArray, h: ByteArray): ByteArray {
        val n = p.size
        val e = ByteArray(n)
        for (i in 0 until n) {
            val i5 = h[(i * 4) % 32].toInt() and 255
            val i6 = h[(i * 4 + 1) % 32].toInt() and 255
            val t = ((rotl8(p[i].toInt() and 255, 4) + i5) and 255) xor -1
            val u = rotl8((t xor i6) and 255, 3)
            e[n - 1 - i] = (((u + i6) and 255) xor -1 xor i5).toByte()
        }
        val last = n - 1
        val prev = n - 2
        if (last < 2) throw IllegalArgumentException("Medusa protobuf too short")
        val b2 = (e[0] + ((e[last].toInt() xor e[prev].toInt()) xor -1)).toByte()
        e[0] = b2
        e[1] = (e[1] + ((b2.toInt() xor e[last].toInt()) xor 254)).toByte()
        var i10 = 0
        while (i10 < n - 3) {
            val b3 = e[i10]
            val i11 = i10 + 1
            val i12 = i10 + 2
            e[i12] = (e[i12] + ((rotl8(e[i11].toInt() and 255, 3) xor b3.toInt() xor i12) xor -1)).toByte()
            i10 = i11
        }
        e[last] = (e[last].toInt() xor e[prev].toInt()).toByte()
        var sum = 0
        for (i in 1 until n) sum = (sum + (e[i].toInt() and 255)) and 255
        e[0] = ((e[0].toInt() xor e[1].toInt()) + sum).toByte()
        return e
    }

    /**
     * 载波的单块加密（r0.E 的循环体）：输入先异或链向量，再做两轮「轮密钥加 → S 盒替换/位置换」，
     * 中间夹一次列重排与列混合，输出再异或一次第 1 轮密钥（同时作为下一块的链向量）。
     * [trace] 非空时逐步记录状态：输入异或 / ark0 / sub / perm / mix / ark1 / sub / ark2 / 输出。
     */
    private fun aesBlock(
        input: ByteArray, prev: ByteArray, word: Int, rk: ByteArray, trace: MutableList<ByteArray>?
    ): ByteArray {
        val s = ByteArray(16) { (input[it].toInt() xor prev[it].toInt()).toByte() }
        fun mark() { trace?.add(s.copyOf()) }
        mark()
        addRoundKey(s, word, rk.copyOfRange(0, 16))
        mark()
        subPerm(s, word)
        mark()
        // perm：按列（AES_Q）重排后再做列混合
        val t = IntArray(16)
        var c = 0
        while (c < 16) {
            for (j in 0 until 4) t[c + j] = s[c + AES_Q[word][j]].toInt() and 255
            c += 4
        }
        trace?.add(t.map { it.toByte() }.toByteArray())
        for (k in 0 until 4) {
            val a = t[k]; val b = t[k + 4]; val cc = t[k + 8]; val d = t[k + 12]
            s[k] = (xt(a) xor xt(b) xor b xor cc xor d).toByte()
            s[k + 4] = (xt(b) xor a xor xt(cc) xor cc xor d).toByte()
            s[k + 8] = (a xor b xor xt(cc) xor xt(d) xor d).toByte()
            s[k + 12] = (xt(a) xor a xor b xor cc xor xt(d)).toByte()
        }
        mark()
        addRoundKey(s, word, rk.copyOfRange(16, 32))
        mark()
        subPerm(s, word)
        mark()
        addRoundKey(s, word, rk.copyOfRange(32, 48))
        mark()
        val out = ByteArray(16) { (s[it].toInt() xor rk[16 + it].toInt()).toByte() }
        trace?.add(out.copyOf())
        return out
    }

    /** r0.J：GF(2^8) 乘 2（多项式 0x11B）。 */
    internal fun xt(x: Int): Int = if (x and 128 != 0) ((x shl 1) xor 27) and 255 else (x shl 1) and 255

    /** r0.H：S 盒替换 + 两级位置换。 */
    internal fun subPerm(b: ByteArray, word: Int) {
        val copy = b.copyOf()
        val src = IntArray(16) { (it % 4) + AES_Q[word][it / 4] * 4 }
        val perm = src.copyOf()
        val pairs = intArrayOf(1, 9, 5, 13, 9, 1, 13, 5, 2, 14, 6, 2, 10, 6, 14, 10)
        var i = 0
        while (i <= 14) {
            perm[pairs[i]] = src[pairs[i + 1]]
            i += 2
        }
        val tail = intArrayOf(3, 7, 11, 15)
        for (k in 0 until 4) perm[tail[k]] = src[AES_R[word][k]]
        val box = word * 256
        for (k in 0 until 16) b[k] = SBOX[box + (copy[perm[k]].toInt() and 255)]
    }

    /** r0.D：轮密钥加（密钥字节先按列置换）。 */
    internal fun addRoundKey(b: ByteArray, word: Int, rk: ByteArray) {
        var c = 0
        while (c < 16) {
            for (j in 0 until 4) b[c + j] = (b[c + j].toInt() xor rk[AES_Q[word][j] + c].toInt()).toByte()
            c += 4
        }
    }

    /** 轮密钥扩展：md5(signKey[:16]) 为密钥，逐轮做 S 盒替换并混入初始向量的字节。 */
    internal fun expandKey(word: Int, key: ByteArray): ByteArray {
        val init = AES_INIT[word]
        val rk = ByteArray(48)
        for (i in 0 until 16) rk[i] = (key[i].toInt() xor (init ushr ((i % 4) * 8)).toInt()).toByte()
        val box = word * 256
        for (i in 4 until 12) {
            val at = i * 4
            var a = rk[at - 4].toInt() and 255
            var b = rk[at - 3].toInt() and 255
            var c = rk[at - 2].toInt() and 255
            var d = rk[at - 1].toInt() and 255
            if (i % 4 == 0) {
                val oa = a
                val sb = SBOX[box + b].toInt() and 255
                a = (((init ushr ((i * 2) and 24)) and (if (word == 3) 249L else 255L)).toInt() and 255) xor sb
                b = SBOX[box + c].toInt() and 255
                c = SBOX[box + d].toInt() and 255
                d = SBOX[box + oa].toInt() and 255
            }
            val vals = intArrayOf(a, b, c, d)
            for (j in 0 until 4) rk[at + j] = (vals[j] xor (rk[(i - 4) * 4 + j].toInt() and 255)).toByte()
        }
        return rk
    }

    /** r0.E 各阶段产物（对拍定位用）：SM3 密钥流 / xmxor 输出 / 内层包 / 位装入后的 32 字节 / AES 两块输出。 */
    internal class Stages(
        val hash: ByteArray, val x: ByteArray, val inner: ByteArray, val plain: ByteArray, val bits: ByteArray,
        val outp: ByteArray, val rk: ByteArray, val iv: ByteArray, val blk: List<ByteArray>
    )

    /** r0.E 的前半：混淆、内层包、位映射装入、AES 载波。 */
    internal fun encryptStages(proto: ByteArray, ts: Int, rnd1: Long, check: Long, flags: ByteArray): Stages {
        val rndLe = le32(rnd1.toInt())
        val hash = sm3(SIGN_KEY + rndLe + SIGN_KEY)
        val x = xmxor(proto, hash)
        val mask = chk(rndLe.copyOfRange(2, 4), 0L, 7, 3, 11, 5).toInt()
        val u1 = flags + x
        val n = u1.size
        val b6 = ByteArray(n)
        // 掩码按大端 4 字节使用（Java 侧是 ByteBuffer.putInt 得到的字节）
        for (i in 0 until n) b6[i] = (u1[n - 1 - i].toInt() xor (mask ushr ((3 - (i and 3)) * 8))).toByte()

        // 内层包：[C(signKey)] + le64(check) + 反序异或后的消息 + rnd 的高 2 字节
        val inner = byteArrayOf(chk(SIGN_KEY, 0L, 7, 3, 11, 5).toByte()) + le64(check) + b6 + rndLe.copyOfRange(2, 4)
        require(inner.size >= 248) { "unsupported Medusa inner length" }

        // 位映射装入：plain[i] 的第 j 位 = inner[i*8 + BIT_IN[j][0]] 的第 BIT_IN[j][1] 位；末字节恒为 1
        val plain = ByteArray(32)
        for (i in 0 until 31) {
            for (j in 0 until 8) {
                val s = BIT_IN[j]
                val bit = (inner[i * 8 + s[0]].toInt() ushr (s[1] and 7)) and 1
                plain[i] = (plain[i].toInt() or (bit shl j)).toByte()
            }
        }
        plain[31] = 1

        val word = ts and 3
        val rk = expandKey(word, md5(SIGN_KEY.copyOfRange(0, 16)))
        var prev = md5(SIGN_KEY.copyOfRange(16, 32))
        val iv = prev
        val bits = ByteArray(32)
        // 只记录第一块的分步状态（对拍定位用）
        val blk = ArrayList<ByteArray>(9)
        var off = 0
        while (off < plain.size) {
            val out = aesBlock(plain.copyOfRange(off, off + 16), prev, word, rk, if (off == 0) blk else null)
            System.arraycopy(out, 0, bits, off, 16)
            prev = out
            off += 16
        }

        // 位映射写回：outp[i*8 + BIT_OUT[j][1]] 的第 BIT_OUT[j][2] 位 = bits[i] 的第 BIT_OUT[j][0] 位
        val outp = inner.copyOf()
        for (i in 0 until 31) {
            for (j in 0 until 8) {
                val t = BIT_OUT[j]
                val pos = i * 8 + t[1]
                val sh = t[2] and 7
                val bit = ((bits[i].toInt() ushr t[0]) and 1) shl sh
                outp[pos] = (bit or (outp[pos].toInt() and (1 shl sh).inv())).toByte()
            }
        }
        return Stages(hash, x, inner, plain, bits, outp, rk, iv, blk)
    }

    /**
     * r0.E 的封装段。[proto] 为 protobuf 明文，[rnd1] 为随机数 1（同时进 SM3 种子与头部），
     * [check] 为 64 位校验（摘要与 queryHash 的高位 + 随机数 2），[flags] 为检测位图。
     * 输出 = le32(ts^magic)×5 + rnd 低 2 字节 + 00 01 + AES 末字节 + 写回位映射的内层包。
     */
    internal fun encryptE(proto: ByteArray, ts: Int, rnd1: Long, check: Long, flags: ByteArray): String {
        val st = encryptStages(proto, ts, rnd1, check, flags)
        val rndLe = le32(rnd1.toInt())
        val res = ByteArrayOutputStream()
        for (i in 0 until 5) res.write(le32((ts.toLong() xor MAGIC_WORDS[i]).toInt()))
        res.write(rndLe[0].toInt())
        res.write(rndLe[1].toInt())
        res.write(0)
        res.write(st.bits.size - 31)
        res.write(st.bits[31].toInt())
        res.write(st.outp)
        return HongguoCrypto.base64(res.toByteArray())
    }
}
