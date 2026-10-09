package com.mytvb.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class HeaderInterceptor(
    private val userAgentProvider: () -> String,
    private val acceptLanguageProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val host = url.host
        val path = url.encodedPath
        val userAgent = userAgentProvider()
        val acceptLanguage = acceptLanguageProvider()

        val isMainSite = host == "www.bilibili.com"
        val isHtmlDocumentRequest = isMainSite && (path == "/" || path.endsWith(".html"))
        // 空间页专属接口（web-space/*）校验 Referer 必须是 space.bilibili.com，
        // 用 www 的值会被 -400 拒掉（实测 home/seasons_series）；带 mid 时拼全路径更仿真网页版。
        val referer = if (path.contains("web-space")) {
            val mid = url.queryParameter("mid")
            if (mid != null) "https://space.bilibili.com/$mid" else "https://space.bilibili.com"
        } else {
            "https://www.bilibili.com"
        }

        val requestBuilder = originalRequest.newBuilder()
            .header("Origin", referer)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Accept-Language", acceptLanguage)
            .header("sec-ch-ua", buildSecChUa(userAgent))
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")

        if (isHtmlDocumentRequest) {
            requestBuilder
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp," +
                        "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
                )
                .header("Cache-Control", "max-age=0")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
        } else {
            requestBuilder
                .header("Accept", resolveDataAcceptHeader(host))
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", resolveFetchSite(host))
        }

        return chain.proceed(requestBuilder.build())
    }

    private fun buildSecChUa(userAgent: String): String {
        val majorVersion = extractChromeMajorVersion(userAgent)
        return "\"Chromium\";v=\"$majorVersion\", \"Google Chrome\";v=\"$majorVersion\", \"Not_A Brand\";v=\"24\""
    }

    private fun extractChromeMajorVersion(userAgent: String): String {
        val marker = "Chrome/"
        val startIndex = userAgent.indexOf(marker)
        if (startIndex < 0) {
            return "120"
        }
        val versionStart = startIndex + marker.length
        val versionEnd = userAgent.indexOf('.', versionStart).takeIf { it > versionStart } ?: userAgent.length
        return userAgent.substring(versionStart, versionEnd)
    }

    private fun resolveDataAcceptHeader(host: String): String {
        return when {
            host.contains("bilivideo.com") || host.contains("bilivideo.cn") -> "*/*"
            else -> "application/json, text/plain, */*"
        }
    }

    private fun resolveFetchSite(host: String): String {
        return when {
            host == "www.bilibili.com" -> "same-origin"
            host.endsWith("bilibili.com") || host.endsWith("bilivideo.com") || host.endsWith("bilivideo.cn") -> "same-site"
            else -> "cross-site"
        }
    }
}
