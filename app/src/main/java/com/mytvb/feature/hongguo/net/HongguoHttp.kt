package com.mytvb.feature.hongguo.net

import com.mytvb.core.common.log.AppLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 红果共享 HTTP 单例（2026-11-10 连接会话模型定案）：
 * 播放接口对新注册设备号做**连接级会话校验**——号绑定注册时的 TCP/TLS 连接，
 * 换连接（哪怕同 IP 同号）立即空响应（Node axios 同池注册即播 ✅ / 同号新连接 ❌ 双实证）。
 * 因此注册（HongguoDeviceRegister）与播放（HongguoPlay）必须共用同一 Call.Factory，
 * 让播放请求复用注册建立的 keep-alive 连接。连接被服务端断开时由上层重注册换新号。
 *
 * 2026-11-22 入口 IP 段豁免定案（12h+ 长窗口排查收官）：清洗挂在**公网 DNS 解析出的
 * 入口段**（222.210.37.x/125.64.109.x）——同刻同 IP 下 HGTV（纯 okhttp 栈，TLS/query/
 * 签名与我们全同款）能播的机制=其 TNC httpDns 表（字节官方 settings 下发）把
 * sinfonlinea/b/lq/hl 解析到独占入口段 101.126.1.155~162 / 122.14.229.x，不走公网 DNS。
 * 治本=Dns 覆写直连 TNC 段（表内置+version.json `hg_dns` 热更可覆盖，见
 * [HongguoNetProfiles]）；表外域名回落系统解析，表内 IP 全部失败也回落（保证常态可用）。
 */
internal object HongguoHttp {

    val ok: OkHttpClient by lazy {
        HongguoTls.apply(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .dns(TncDns)
        ).build()
    }

    /** 实际出口（okhttp 单例；注册+播放同池保会话模型）。 */
    fun client(): okhttp3.Call.Factory = ok

    /**
     * TNC httpDns 直连：命中表→逐 IP 解析（字符串 IP 构造 InetAddress 不走 DNS），
     * 全败或未命中→系统 DNS。okhttp 对 Dns 抛异常会直接失败，故兜底必须在此层完成。
     */
    private object TncDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            HongguoNetProfiles.dnsTable()[hostname]?.forEach { ip ->
                runCatching { return listOf(java.net.InetAddress.getByName(ip)) }
            }
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    /** 表热更后重建立即生效（OkHttpClient 的 Dns 每次解析实时查表，无需重建 client）。 */
    fun refreshDns() {
        AppLog.i("HgHttp", "dns table refreshed: ${HongguoNetProfiles.dnsTable().size} hosts")
    }
}
