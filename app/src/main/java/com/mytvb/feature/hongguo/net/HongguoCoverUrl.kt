package com.mytvb.feature.hongguo.net

/**
 * 红果封面 URL 改写（照抄 HGTV 1.3.6 `F0.p` 的申请逻辑，MuMu/线上双向实测通过）。
 *
 * 背景：feed/搜索接口下发的封面是签名 URL
 * `https://p3-reading-sign.fqnovelpic.com/novel-pic/<md5>~tplv-<模板>.heic?lk3s=..&x-signature=..`：
 * - 模板段被 x-signature 锁定，改尺寸/格式一律 403（Tengine 拒，2026-11-10 实测）；
 * - 输出 .heic 在 Android API<28 解不了（minSdk 23）；
 * - x-expires 过期后旧 URL 失效（历史/收藏存量记录救不回）。
 *
 * 同一张图在公开图床 `p{3,6}-novel.byteimg.com/novel-pic/<md5>` 可免签名访问，
 * 模板段可自由改（HGTV 同款结论）。改写为固定 `~tplv-shrink:640:0.webp`：
 * 竖版海报源图 700×1000，640 宽基本吃满源图质量；webp 比同内容 JPEG 省 ~29%；
 * shrink 只缩不放（请求超原图宽返回原图字节，无放大失真）。
 *
 * 幂等：已是公开域的 URL 只替换模板段为同值；非 novel-pic 形态原样返回。
 */
object HongguoCoverUrl {

    /** feed/搜索下发的签名图床域（-sign 后缀）。 */
    private val SIGN_HOST = Regex("^p\\d+-reading-sign\\.fqnovelpic\\.com$")

    /** 网页版公开图床域（免签名、模板可改）。 */
    private val PUBLIC_HOST = Regex("^p\\d+-novel\\.byteimg\\.com$")

    /** 模板段匹配（`~tplv-` 到 `?` 或 `/` 之前）。 */
    private val TPLV = Regex("~tplv-[^?/]+")

    /** HGTV 固定申请档：640 宽、高自适应、webp 输出。 */
    private const val TEMPLATE = "~tplv-shrink:640:0.webp"

    fun rewrite(url: String): String {
        if (url.isEmpty()) return url
        val schemeEnd = url.indexOf("//")
        if (schemeEnd < 0) return url
        val afterScheme = url.substring(schemeEnd + 2)
        val host = afterScheme.substringBefore('/')
        return when {
            SIGN_HOST.matches(host) -> rewriteSignHost(afterScheme, url)
            PUBLIC_HOST.matches(host) -> rewritePublicHost(url)
            else -> url
        }
    }

    /** 签名 URL：取 `novel-pic/<md5>` 路径整条改投公开域（签名 query 一并丢弃）。 */
    private fun rewriteSignHost(afterScheme: String, original: String): String {
        if ('~' !in original) return original
        val path = afterScheme.substringAfter('/').substringBefore('~')
        if (path.isEmpty()) return original
        return "https://p3-novel.byteimg.com/$path$TEMPLATE"
    }

    /** 公开域 URL：保留 host+path，仅替换模板段（query 保留不动）。 */
    private fun rewritePublicHost(url: String): String {
        val match = TPLV.find(url) ?: return url
        return url.substring(0, match.range.first) + TEMPLATE + url.substring(match.range.last + 1)
    }
}
