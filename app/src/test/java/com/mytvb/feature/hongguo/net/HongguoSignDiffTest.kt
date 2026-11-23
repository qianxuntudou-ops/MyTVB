package com.mytvb.feature.hongguo.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 签名对拍（2026-11-22 frida hook HGTV L3.e/f/g 抓的真实输入输出对）：同输入跑我方实现逐条比对。
 * 数据：`.zcode/hgtv-frida/sign_pairs.log`（仓库根下的本地材料，不入库；不存在则跳过），行格式
 * GORGON|sec|nonce|query|bodyB64|hgtvOut / HELIOS|sec|out / MEDUSA|url|bodyB64|sec|out
 *
 * - gorgon / helios 都是确定性函数：gorgon 的 nonce 由 hook 一并抓下，helios 的随机种子明文放在
 *   输出前 4 字节，都能取出来做全字符串全等对拍；
 * - medusa 含随机数与进程内状态，整串不可比，其全等对拍在 HongguoMedusaTest（金标准回放）。
 *   这里只断言 HGTV 每条请求的 query 都落在 selector 12——我方只移植了 12 号分支，
 *   「HGTV 把 selector 调谐到 12」是这个前提成立的证据。
 */
class HongguoSignDiffTest {

    /** 向上找到仓库根（以 settings.gradle.kts 为标志），再拼本地材料路径；找不到返回 null（用例跳过）。 */
    private fun repoFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return File(dir, relative)
            dir = dir.parentFile
        }
        return null
    }


    @Test
    fun diffSignLayers() {
        val f = repoFile(".zcode/hgtv-frida/sign_pairs.log")
        if (f == null || !f.exists()) {
            println("SIGNDIFF pairs missing, skipped")
            return
        }
        var gOk = 0; var gBad = 0; var hOk = 0; var hBad = 0; var mSel12 = 0; var mOther = 0
        var firstGorgonDiff: String? = null
        f.readLines().forEach { ln ->
            val parts = ln.split("|")
            when (parts.firstOrNull()) {
                "GORGON" -> {
                    val sec = parts[1].toInt(); val nonce = parts[2].toInt()
                    val query = parts[3]
                    val body = if (parts[4] == "-") null else Base64.getDecoder().decode(parts[4])
                    val expected = parts[5]
                    val ours = HongguoCrypto.gorgon(query, body, sec, nonce)
                    if (ours == expected) gOk++ else {
                        gBad++
                        if (firstGorgonDiff == null) firstGorgonDiff =
                            "sec=$sec nonce=$nonce query=${query.take(60)}…\n  hgtv=$expected\n  ours=$ours"
                    }
                }
                "HELIOS" -> {
                    // helios = base64(le32(随机种子) + Simon 密文)：种子明文放在输出前 4 字节，
                    // 取出来即可确定性对拍（license 固定 646637794）
                    val sec = parts[1].toInt()
                    val expected = parts[2]
                    val raw = Base64.getDecoder().decode(expected)
                    val nonce = java.nio.ByteBuffer.wrap(raw, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
                    val ours = HongguoCrypto.helios(sec, nonce, "646637794")
                    if (ours == expected) hOk++ else {
                        hBad++
                        if (hBad <= 2) println("SIGNDIFF helios sec=$sec nonce=$nonce\n  hgtv=$expected\n  ours=$ours")
                    }
                }
                "MEDUSA" -> {
                    val url = parts[1]
                    val body = if (parts[2] == "-") null else Base64.getDecoder().decode(parts[2])
                    val sec = parts[3].toInt()
                    val query = url.substringBefore('#').substringAfter('?', "")
                    val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
                    val bodyMd5 = if (body == null || body.isEmpty()) ByteArray(16) else HongguoCrypto.md5(body)
                    if (HongguoMedusa.selectorOf(queryHash, bodyMd5, sec) == 12) mSel12++ else mOther++
                }
            }
        }
        println("SIGNDIFF gorgon ok=$gOk bad=$gBad")
        println("SIGNDIFF helios ok=$hOk bad=$hBad")
        println("SIGNDIFF hgtv medusa queries: selector12=$mSel12 other=$mOther")
        firstGorgonDiff?.let { println("SIGNDIFF gorgon-first-diff:\n$it") }
        assertEquals("gorgon 与 HGTV 不等价 $gBad/${gOk + gBad}", 0, gBad)
        assertEquals("helios 与 HGTV 不等价 $hBad/${hOk + hBad}", 0, hBad)
        assertEquals("HGTV 有 ${mOther}条请求的 selector 不在 12", 0, mOther)
    }
}
