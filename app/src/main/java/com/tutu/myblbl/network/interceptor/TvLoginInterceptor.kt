package com.tutu.myblbl.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class TvLoginInterceptor(
    private val deviceBuvidProvider: () -> String
) : Interceptor {

    companion object {
        private const val TV_LOGIN_USER_AGENT =
            "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd " +
                "mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("passport-tv-login")) {
            return chain.proceed(request)
        }

        val buvid = deviceBuvidProvider()
        val newRequest = request.newBuilder()
            .header("Referer", "https://passport.bilibili.com/")
            .header("Origin", "https://passport.bilibili.com")
            .header("User-Agent", TV_LOGIN_USER_AGENT)
            .header("buvid", buvid)
            .header("env", "prod")
            .header("app-key", "android_hd")
            .header("x-bili-trace-id", "11111111111111111111111111111111:1111111111111111:0:0")
            .header("x-bili-aurora-eid", "")
            .header("x-bili-aurora-zone", "")
            .header("bili-http-engine", "cronet")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .build()
        return chain.proceed(newRequest)
    }
}
