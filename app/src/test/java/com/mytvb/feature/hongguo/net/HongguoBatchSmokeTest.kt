package com.mytvb.feature.hongguo.net

import org.junit.Test

/**
 * 批量地址预取真网冒烟（2026-11-21 B 改造）：一次 preload/v1 带 3 集，
 * 验证 mixed_video_id_map 批量响应逐集可解析（对齐 HGTV 1.3.8.6 批量预取形态）。
 */
class HongguoBatchSmokeTest {

    @Test
    fun batchSmoke() {
        val id = try {
            HongguoDeviceRegister.registerFresh()
        } catch (e: Exception) {
            println("HG-BATCH register fail: ${e.message}")
            return
        }
        Thread.sleep(800)
        // 同剧 3 集（穿越八零 第1集=已验证 vid，另两集取自详情结构）
        val vids = listOf("7705826165692304409", "7705826348597529625", "7705826451638817989")
        val batch = try {
            HongguoPlay().playUrlBatch(vids, id)
        } catch (e: Exception) {
            println("HG-BATCH FAIL ${e.message}")
            return
        }
        println("HG-BATCH size=${batch.size}/${vids.size}")
        batch.forEach { (vid, play) ->
            println("HG-BATCH vid=$vid codec=${play.codec} q=${play.quality} ${play.width}x${play.height} key=${play.contentKeyHex != null}")
        }
    }
}
