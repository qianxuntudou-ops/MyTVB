package com.tutu.myblbl.network.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.tutu.myblbl.BuildConfig
import com.tutu.myblbl.model.adapter.FlexibleIntAdapter
import com.tutu.myblbl.model.adapter.FlexibleLongAdapter
import com.tutu.myblbl.network.interceptor.DeflateInterceptor
import com.tutu.myblbl.network.interceptor.TvLoginInterceptor
import com.tutu.myblbl.network.interceptor.HeaderInterceptor
import com.tutu.myblbl.network.cookie.CookieManager
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClientFactory {

    private const val HTTP_CACHE_SIZE = 64L * 1024 * 1024

    fun createOkHttpClient(
        cookieManager: CookieManager,
        userAgentProvider: () -> String,
        acceptLanguageProvider: () -> String,
        cacheDir: File? = null,
        ipv4OnlyEnabled: () -> Boolean = { true },
        deviceBuvidProvider: () -> String = { "" }
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieManager)
            .dns(ipv4OnlyDns(ipv4OnlyEnabled))
            .addInterceptor(
                HeaderInterceptor(
                    userAgentProvider = userAgentProvider,
                    acceptLanguageProvider = acceptLanguageProvider
                )
            )
            .addInterceptor(TvLoginInterceptor(deviceBuvidProvider))
            .addInterceptor(DeflateInterceptor())
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.eventListenerFactory(DebugNetworkEventListener.Factory())
        }

        if (cacheDir != null) {
            val httpCacheDir = File(cacheDir, "http_cache")
            builder.cache(Cache(httpCacheDir, HTTP_CACHE_SIZE))
        }

        return builder.build()
    }

    fun createGson(): Gson {
        return GsonBuilder()
            .registerTypeAdapter(Long::class.javaPrimitiveType, FlexibleLongAdapter())
            .registerTypeAdapter(Long::class.javaObjectType, FlexibleLongAdapter())
            .registerTypeAdapter(Int::class.javaPrimitiveType, FlexibleIntAdapter())
            .registerTypeAdapter(Int::class.javaObjectType, FlexibleIntAdapter())
            .addSerializationExclusionStrategy(LazyExclusionStrategy)
            .addDeserializationExclusionStrategy(LazyExclusionStrategy)
            .create()
    }

    fun createRetrofit(
        baseUrl: String,
        client: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private fun ipv4OnlyDns(ipv4OnlyEnabled: () -> Boolean): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val host = hostname.trim()
                if (host.isBlank()) throw UnknownHostException("hostname is blank")
                val addresses = Dns.SYSTEM.lookup(host)
                if (!ipv4OnlyEnabled()) return addresses
                val ipv4 = addresses.filterIsInstance<Inet4Address>()
                if (ipv4.isNotEmpty()) return ipv4
                throw UnknownHostException("No IPv4 address for $host")
            }
        }

    private object LazyExclusionStrategy : ExclusionStrategy {
        override fun shouldSkipField(f: FieldAttributes): Boolean {
            return f.declaredType == Lazy::class.java
        }
        override fun shouldSkipClass(clazz: Class<*>): Boolean {
            return clazz == Lazy::class.java
        }
    }
}
