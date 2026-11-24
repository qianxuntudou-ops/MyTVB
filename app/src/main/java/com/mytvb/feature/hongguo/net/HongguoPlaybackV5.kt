package com.mytvb.feature.hongguo.net

/**
 * 红果播放 V5 取址协议（服务端 2026-11-14 前后切换，2026-11-17 接入）：
 * 独立播放签名 x-medusa + x-helios；查询串不追加 ts，签名时只调整 `_rticket` 偏移；
 * 版本档案 74332 / 7.4.3.32 / vivo_8662_64 / PGT-AN10 / Android 12（v5tab 路由的画像）。
 *
 * 签名算法在 [HongguoMedusa]——2026-11-23 换成 HGTV 1.3.8.12 的精确移植（官方 App 真实
 * 签名离线对拍一致）。此前是 openhongguo PR#13 Go 版的简化翻译，摘要轮函数、protobuf
 * 消息体、加密布局都与真实现不等价，服务端 11-23 收紧 medusa 校验后被拒（200 空 body）。
 * 本文件只负责 query 编码、把 medusa selector 调谐到 12、组装 V5 头集。
 *
 * **selector 必须是 12**：medusa 的摘要分支由 C(SM3(query)+MD5(body)+le32(ts)) & 15 选定，
 * 服务端按请求自带的 selector 选分支验签；官方 SDK 16 个分支都实现，HGTV 与我方只移植了
 * 12 号，所以 query 必须经本文件调谐（[sign]），否则 15/16 的概率被拒。
 *
 * 背景：11-10 定版的 Argus/Ladon 档案在 player/v1 已被服务端切协议弃用——
 * 症状=HTTP 200 空 body（register/feed/video_detail 等普通接口不受影响）。
 */
internal object HongguoPlaybackV5 {

    private const val LICENSE = "646637794"

    /** UA 保留 V5 协议模板原值（与查询串版本号分别处理，PR#13 PlaybackUserAgent）。 */
    const val PLAYBACK_UA =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    internal class SignedV5(
        val query: String, val headers: List<Pair<String, String>>,
        /** x-medusa 覆盖的 protobuf 明文（对拍/自检用）。 */
        internal val proto: ByteArray,
    )

    /**
     * 给一组请求参数签名：`_rticket` 取「毫秒 + 偏移」（0..1023 内挑使 medusa selector==12 的那个；
     * 参数里已有 `_rticket` 则原位覆盖，否则追加到末尾），返回最终 query 与 V5 头集
     * （x-khronos / x-helios / x-medusa，有 body 时再加 Content-Type 与 x-ss-stub）。
     * 画像从参数里取；[body] 须是实际发送的字节（POST 为 gzip 后的同一份）。
     */
    fun sign(pairs: List<Pair<String, String>>, nowMillis: Long, body: ByteArray?): SignedV5 {
        val signer = HongguoMedusa.signer(
            HongguoMedusa.Profile.fromParams { k -> pairs.firstOrNull { it.first == k }?.second }
        )
        return signWith(pairs, nowMillis, body, signer, HongguoCrypto.randomU32())
    }

    /** 固定签名器与 helios 随机数的签名核心（单测用）。 */
    internal fun signWith(
        pairs: List<Pair<String, String>>, nowMillis: Long, body: ByteArray?,
        signer: HongguoMedusa.Signer, heliosRandom: Int
    ): SignedV5 {
        val ts = (nowMillis / 1000).toInt()
        val bodyMd5 = if (body != null) HongguoCrypto.md5(body) else ByteArray(16)
        val withTicket = pairs.toMutableList()
        var ticketIdx = withTicket.indexOfFirst { it.first == "_rticket" }
        if (ticketIdx < 0) {
            withTicket.add("_rticket" to "")
            ticketIdx = withTicket.lastIndex
        }
        for (offset in 0 until 1024) {
            withTicket[ticketIdx] = "_rticket" to (nowMillis + offset).toString()
            val query = encodeQuery(withTicket)
            val queryHash = HongguoCrypto.sm3(query.toByteArray(Charsets.UTF_8))
            if (HongguoMedusa.selectorOf(queryHash, bodyMd5, ts) != 12) continue
            val signed = HongguoMedusa.signWith(signer, query, body, ts, nowMillis + offset, HongguoMedusa.drawRandoms())
            val headers = mutableListOf(
                "x-khronos" to ts.toString(),
                "x-helios" to HongguoCrypto.helios(ts, heliosRandom, LICENSE),
                "x-medusa" to signed.medusa
            )
            if (body != null) {
                headers.add("Content-Type" to "application/json; charset=utf-8")
                headers.add("x-ss-stub" to HongguoCrypto.toHex(bodyMd5).uppercase())
            }
            return SignedV5(query, headers, signed.proto)
        }
        throw IllegalStateException("playback V5 signing branch unavailable")
    }

    /**
     * 直接对一条已定型的 query 串签 x-medusa（埋点等「medusa 只是补充头」的通道用）。
     * 注意本函数不调谐：query 的 selector 不是 12 时签名与请求不自洽，播放取址请走 [sign]。
     */
    fun medusaHeaderFor(query: String, body: ByteArray?, tsSec: Int): String =
        HongguoMedusa.medusaFor(query, body, tsSec)

    /** 保序拼接（V5 模板顺序参与签名），unreserved=-_.!~*'()，其余 %XX 大写。
     *  空键空值渲染成空段（官方抓包里有 `...is_64_device=true&&host_abi=...` 这样的空参数，
     *  签名要覆盖同一串，所以这里照原样输出）。 */
    internal fun encodeQuery(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) ->
            if (k.isEmpty() && v.isEmpty()) "" else "${escapeV5(k)}=${escapeV5(v)}"
        }

    private fun escapeV5(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.toByteArray(Charsets.UTF_8)) {
            val c = ch.toInt() and 0xFF
            if (c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '!'.code ||
                c == '~'.code || c == '*'.code || c == '\''.code || c == '('.code || c == ')'.code
            ) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }
}
