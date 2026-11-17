package com.mytvb.feature.hongguo.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** hashV5 层级对拍（输入与 v5_hash_layers.json 一致，Go 参考值）。 */
class HongguoV5HashLayersTest {

    private val ts = 1763388000
    private val qh = HongguoCrypto.sm3("aid=8662".toByteArray())
    private val bodyMd5 = HongguoCrypto.md5("hello".toByteArray())

    private fun hexU32(v: Long): String = "%08x".format(v and 0xFFFFFFFFL)

    @Test
    fun layers() {
        // 复刻 hashV5 内部（与实现同步骤）
        val seed = 0x9d4a97c2.toInt()
        val count = seed + ts
        val padShift = (count + 2) and 7
        val padSeed = byteArrayOf(0xFA.toByte(), 0x45, 0x61, 0xD7.toByte())
        val pad = ByteArray(4) { i ->
            val v = padSeed[i].toInt() and 0xFF
            (((v or (v shl 8)) ushr padShift) and 0xFF).toByte()
        }
        org.junit.Assert.assertEquals("af54167d", HongguoCrypto.toHex(pad))

        val data = qh + bodyMd5 + HongguoCrypto.leU32(ts) + pad +
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0xA0.toByte())
        org.junit.Assert.assertEquals(
            "6213b7d76328bbde133f057ee5ca27f49e11941e0bb2b684aeeb3a1e7e7642495d41402abc4b2a76b9719d911017c592602a1b69af54167d00000000000001a0",
            HongguoCrypto.toHex(data)
        )

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
        val goWords16 = listOf(1645459415L,1663613918L,322897278L,3855230964L,2651952158L,196261508L,2934651422L,2121679433L,1564557354L,3159042678L,3111230865L,269993362L,1613372265L,2941523581L,0L,416L)
        for (i in 0 until 16) {
            assertEquals("words16[$i]", hexU32(goWords16[i]), hexU32(di[i].toLong()))
        }
        assertEquals("words127", hexU32(2152730600L), hexU32(di[127].toLong()))

        val initial = IntArray(8) { HongguoCrypto.ror32(HongguoCrypto.BRANCH0_INIT[it], count) }
        val goInitial = listOf(514757618L,3653605697L,419495552L,3439580235L,246076228L,2898745614L,1792273147L,538103316L)
        for (i in 0 until 8) {
            assertEquals("initial[$i]", hexU32(goInitial[i]), hexU32(initial[i].toLong()))
        }

        val tt = IntArray(HongguoCrypto.TT02.size) { HongguoCrypto.ror32(HongguoCrypto.TT02[it], count + 1) }
        val rounds = intArrayOf(96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7)
        val idx = { k: Int -> rounds[k] }
        val state = initial.copyOf()
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
        val goState = listOf(4277165404L,1040434548L,3449842508L,2520210760L,1042372926L,2400119579L,2573758652L,3128236196L)
        for (i in 0 until 8) {
            assertEquals("state[$i]", hexU32(goState[i]), hexU32(state[i].toLong()))
        }

        // digest
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
        assertEquals("5152ddcc2c1f1a9ce29d327bb9bcd72b", HongguoCrypto.toHex(folded))
        assertEquals(
            "5152ddcc2c1f1a9ce29d327bb9bcd72b361d15cd",
            HongguoCrypto.toHex(folded + HongguoCrypto.leU32(HongguoCrypto.sumMd5Check(folded) or 0x20))
        )
    }
}
