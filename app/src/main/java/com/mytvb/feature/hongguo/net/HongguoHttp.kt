package com.mytvb.feature.hongguo.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 红果共享 HTTP 单例（2026-11-10 连接会话模型定案）：
 * 播放接口对新注册设备号做**连接级会话校验**——号绑定注册时的 TCP/TLS 连接，
 * 换连接（哪怕同 IP 同号）立即空响应（Node axios 同池注册即播 ✅ / 同号新连接 ❌ 双实证）。
 * 因此注册（HongguoDeviceRegister）与播放（HongguoPlay）必须共用同一 OkHttpClient，
 * 让播放请求复用注册建立的 keep-alive 连接。连接被服务端断开时由上层重注册换新号。
 */
internal object HongguoHttp {
    val ok: OkHttpClient by lazy {
        // HGTV tls-7 复刻（HongguoTls）：HTTP/1.1 + ALPN ttp/1.1 + 15 cipher 实测序
        HongguoTls.apply(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
        ).build()
    }
}
