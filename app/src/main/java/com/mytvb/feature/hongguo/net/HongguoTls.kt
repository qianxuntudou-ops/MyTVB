package com.mytvb.feature.hongguo.net

/**
 * TTNet TLS 伪装（2026-11-10 HGTV 破解成果）：
 * - ALPN=ttp/1.1（TTNet 私有值，官方 app 全家族发这个；okhttp 1.1-only 不设 ALPN，注入得以存活）
 * - Conscrypt 默认 cipher 序（15 套件实测序，CBC 四件必须用 OpenSSL 名 *_CBC_SHA，
 *   JSSE 名 *_SHA 会被 ConnectionSpec 交集过滤掉）
 * - HTTP/1.1（禁 h2）
 * 服务端按注册/播放请求的 TLS 形态给 device_id 打标：MuMu 裸 Conscrypt 注册的号
 * 在播放接口必空响应；同伪装注册的号放行（Node A/B 实证 2026-11-10）。
 */
internal object HongguoTls {

    fun apply(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        val spec = okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_3, okhttp3.TlsVersion.TLS_1_2)
            .cipherSuites(
                okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                okhttp3.CipherSuite.forJavaName("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"),
                okhttp3.CipherSuite.forJavaName("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"),
                okhttp3.CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                okhttp3.CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                okhttp3.CipherSuite.forJavaName("TLS_RSA_WITH_AES_128_CBC_SHA"),
                okhttp3.CipherSuite.forJavaName("TLS_RSA_WITH_AES_256_CBC_SHA")
            )
            .build()
        val trustManager = defaultTrustManager()
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
        return builder
            .connectionSpecs(listOf(spec))
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .sslSocketFactory(TtpSslFactory(sslContext.socketFactory), trustManager)
    }

    private fun defaultTrustManager(): javax.net.ssl.X509TrustManager {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
    }

    private class TtpSslFactory(private val delegate: javax.net.ssl.SSLSocketFactory) :
        javax.net.ssl.SSLSocketFactory() {

        private fun tune(socket: java.net.Socket): java.net.Socket {
            if (socket is javax.net.ssl.SSLSocket) {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val params = socket.sslParameters
                    params.applicationProtocols = arrayOf("ttp/1.1")
                    socket.sslParameters = params
                } else {
                    runCatching {
                        socket.javaClass.getMethod("setAlpnProtocols", Array<String>::class.java)
                            .invoke(socket, arrayOf("ttp/1.1"))
                    }
                }
            }
            return socket
        }

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
            tune(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int) = tune(delegate.createSocket(host, port))
        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
            tune(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: java.net.InetAddress, port: Int) =
            tune(delegate.createSocket(host, port))
        override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
            tune(delegate.createSocket(address, port, localAddress, localPort))
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    }
}
