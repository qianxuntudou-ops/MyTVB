package com.mytvb.feature.hongguo.net

import org.junit.Test

/**
 * personWorks 联网冒烟：泫九文化（639230362271212）官方 App 实测主页
 * 「作品›剧作」tab 有 1 部（归墟第一季，2026-11-13 hook 实证）——匿名直连
 * 应拿到同数据；拿不到即请求层与官方仍有差异（版本/域名/头）。
 */
class HongguoPersonWorksSmokeTest {

    @Test
    fun personWorksSmoke() {
        HongguoClient.HongguoClientDebug.enabled = true
        val works = HongguoClient().personWorks("639230362271212")
        println("HG-PERSON works=${works.size}")
        works.take(3).forEach { println("HG-PERSON ${it.title} id=${it.seriesId} eps=${it.episodeCount}") }
    }
}
