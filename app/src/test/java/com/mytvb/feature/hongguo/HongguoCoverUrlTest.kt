package com.mytvb.feature.hongguo

import com.mytvb.feature.hongguo.net.HongguoCoverUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 红果封面 URL 改写（HGTV F0.p 逻辑移植）行为基线。
 * 实测依据（2026-11-10）：签名域模板被 x-signature 锁定改了 403；
 * 公开域 p3-novel.byteimg.com 免签、模板可改、shrink:640:0.webp 实测 200。
 */
class HongguoCoverUrlTest {

    private val md5 = "84a71f1a5d7ef5c9c16a73b74011ef75"

    /** feed 下发的签名 URL：整条丢弃（含签名 query），改投公开域 + 固定模板。 */
    @Test
    fun signUrlRewrittenToPublicHostWithFixedTemplate() {
        val sign = "https://p3-reading-sign.fqnovelpic.com/novel-pic/$md5" +
            "~tplv-81nmtwyey9-superreso-aifit:400:0.heic" +
            "?lk3s=64477e16&x-expires=1794299114&x-signature=H%2Bnpz3DelDQsiiCZaUdTQG0Rhjg%3D"
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/$md5~tplv-shrink:640:0.webp",
            HongguoCoverUrl.rewrite(sign)
        )
    }

    /** 签名域但无模板段（异常形态）：原样放行，不猜。 */
    @Test
    fun signUrlWithoutTemplateUntouched() {
        val noTpl = "https://p3-reading-sign.fqnovelpic.com/novel-pic/$md5?a=1"
        assertEquals(noTpl, HongguoCoverUrl.rewrite(noTpl))
    }

    /** 已是公开域：只替换模板段，query 保留。 */
    @Test
    fun publicUrlTemplateReplacedInPlace() {
        val pub = "https://p6-novel.byteimg.com/novel-pic/$md5~tplv-shrink:640:0.webp?biz_tag=x"
        assertEquals(
            "https://p6-novel.byteimg.com/novel-pic/$md5~tplv-shrink:640:0.webp?biz_tag=x",
            HongguoCoverUrl.rewrite(pub)
        )
        val otherTpl = "https://p3-novel.byteimg.com/novel-pic/$md5~tplv-shrink:300:0.image"
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/$md5~tplv-shrink:640:0.webp",
            HongguoCoverUrl.rewrite(otherTpl)
        )
    }

    /** 非红果图床（B 站封面等）原样返回。 */
    @Test
    fun foreignUrlUntouched() {
        val bili = "https://i0.hdslb.com/bfs/archive/abc.jpg@480w_270h.webp"
        assertEquals(bili, HongguoCoverUrl.rewrite(bili))
        assertEquals("", HongguoCoverUrl.rewrite(""))
    }

    /** 幂等：改写结果再过一遍不变。 */
    @Test
    fun rewriteIsIdempotent() {
        val sign = "https://p3-reading-sign.fqnovelpic.com/novel-pic/$md5~tplv-x:400:0.heic?s=1"
        val once = HongguoCoverUrl.rewrite(sign)
        assertEquals(once, HongguoCoverUrl.rewrite(once))
    }
}
