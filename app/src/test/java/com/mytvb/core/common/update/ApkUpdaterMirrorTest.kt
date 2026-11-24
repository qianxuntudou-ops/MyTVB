package com.mytvb.core.common.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * version.json 的 apk_mirrors 校验（更新下载源热更）：
 * 非法项剔除、地址归一化（补尾部斜杠）、去重、条数上限。
 */
class ApkUpdaterMirrorTest {

    @Test
    fun normalizesHttpsMirrorsAndKeepsOrder() {
        val parsed = ApkUpdater.parseMirrorList(
            listOf(
                "https://gh-proxy.com",
                " https://ghfast.top/ ",
                "https://gh.monlor.com/sub/",
            )
        )
        assertEquals(
            listOf("https://gh-proxy.com/", "https://ghfast.top/", "https://gh.monlor.com/sub/"),
            parsed
        )
    }

    @Test
    fun dropsInvalidEntries() {
        val parsed = ApkUpdater.parseMirrorList(
            listOf(
                "",
                "http://gh-proxy.com/",
                "ftp://example.com/",
                "https://localhost/",
                "https://bad host/",
                "https://gh-proxy.com/",
            )
        )
        assertEquals(listOf("https://gh-proxy.com/"), parsed)
    }

    @Test
    fun deduplicatesSameMirror() {
        val parsed = ApkUpdater.parseMirrorList(
            listOf("https://ghfast.top/", "https://ghfast.top", "https://ghfast.top//")
        )
        assertEquals(listOf("https://ghfast.top/"), parsed)
    }

    @Test
    fun capsRemoteMirrorCount() {
        val many = (1..20).map { "https://mirror$it.example.com/" }
        assertEquals(8, ApkUpdater.parseMirrorList(many).size)
    }

    @Test
    fun emptyInputFallsBackToEmptyList() {
        assertEquals(emptyList<String>(), ApkUpdater.parseMirrorList(emptyList()))
    }
}
