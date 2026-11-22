package com.mytvb.feature.hongguo.net

import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * 2026-11-22 签名对拍（frida hook HGTV L3.e/f/g 抓的 35 组输入输出对）：
 * 同输入跑我们的 gorgon/helios/medusa 实现，逐条 diff——定位「豁免=真签名」定案后
 * 究竟哪一层与 HGTV 1.3.8.12 不等价（D2 全套重签仍空的分层归因）。
 * 数据：G:\MyTool\MyBLBL\.zcode\hgtv-frida\sign_pairs.log，行格式
 * GORGON|sec|nonce|query|bodyB64|hgtvOut / HELIOS|sec|out / MEDUSA|url|bodyB64|sec|out
 */
class HongguoSignDiffTest {

    @Test
    fun diffSignLayers() {
        val f = File("G:\\MyTool\\MyBLBL\\.zcode\\hgtv-frida\\sign_pairs.log")
        if (!f.exists()) { println("SIGNDIFF pairs missing"); return }
        var gOk = 0; var gBad = 0; var hOk = 0; var hBad = 0; var mOk = 0; var mBad = 0
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
                    val sec = parts[1].toInt(); val expected = parts[2]
                    val ours = HongguoArgus.helios(sec)
                    if (ours == expected) hOk++ else {
                        hBad++
                        if (hBad <= 2) println("SIGNDIFF helios sec=$sec\n  hgtv=$expected\n  ours=$ours")
                    }
                }
                "MEDUSA" -> {
                    val url = parts[1]
                    val body = if (parts[2] == "-") null else Base64.getDecoder().decode(parts[2])
                    val sec = parts[3].toInt(); val expected = parts[4]
                    val query = url.substringAfter("?", "")
                    val ours = HongguoPlaybackV5.medusaHeaderFor(query, body ?: ByteArray(0), sec)
                    if (ours == expected) mOk++ else {
                        mBad++
                        if (mBad <= 2) println("SIGNDIFF medusa sec=$sec\n  hgtv=${expected.take(80)}…\n  ours=${ours.take(80)}…")
                    }
                }
            }
        }
        println("SIGNDIFF gorgon ok=$gOk bad=$gBad")
        println("SIGNDIFF helios ok=$hOk bad=$hBad")
        println("SIGNDIFF medusa ok=$mOk bad=$mBad")
        firstGorgonDiff?.let { println("SIGNDIFF gorgon-first-diff:\n$it") }
    }
}
