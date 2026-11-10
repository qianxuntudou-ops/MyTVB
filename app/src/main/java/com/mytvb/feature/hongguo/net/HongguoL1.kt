package com.mytvb.feature.hongguo.net

/**
 * HGTV 网络层直接搬运（2026-11-10 终极方案）：
 * APK 构建后把 HGTV 的 classes.dex/classes2.dex 以 classes20/21.dex 塞入（ART 全量加载，
 * 重复类先到优先——宿主 androidx/media3 在前，HGTV 的 L1 纯 java 网络层不受影响）。
 * 本类反射调用 HGTV 的魔改 okhttp（L1.x/y/D/C/G/J + H0.j RequestBody + L1.w MediaType +
 * S0.S0 TLS 配置）——同一份字节码发请求，服务端无法与 HGTV 本尊区分。
 * 配置完全照抄 HGTV S(0,null)：protocols=[HTTP_1_1] + S0.a(x)（tls-7 定制），不挂 U0.d 拦截器（播放匿名）。
 */
internal object HongguoL1 {

    /** classes20/21.dex 是否已随包加载。 */
    val available: Boolean by lazy {
        runCatching { Class.forName("L1.y"); Class.forName("H0.j"); Class.forName("S0.S0") }.isSuccess
    }

    private var cachedClient: Any? = null
    private const val TAG = "Hongguo"

    @Synchronized
    private fun client(): Any {
        cachedClient?.let { return it }
        val xClass = Class.forName("L1.x")
        val builder = xClass.getDeclaredConstructor().newInstance()
        // 超时（方法名混淆不定，按签名找 15/30/45s）
        val timeUnit = java.util.concurrent.TimeUnit.SECONDS
        for (m in xClass.methods) {
            if (m.parameterCount == 2 && m.parameterTypes[0] == Long::class.javaPrimitiveType &&
                m.parameterTypes[1] == java.util.concurrent.TimeUnit::class.java
            ) {
                runCatching { m.invoke(builder, 20L, timeUnit) }
            }
        }
        // protocols = [HTTP/1.1]（L1.z 枚举常量 c，dexdump 实证；jadx 的 f2450c 是显示名）
        val zClass = Class.forName("L1.z")
        val http11 = zClass.getDeclaredField("c").get(null)
        xClass.getMethod("c", java.util.List::class.java)
            .invoke(builder, java.util.Collections.singletonList(http11))
        // S0.S0.a(builder)：tls-7 定制（ConnectionSpecs/SSLSocketFactory 包装/DNS P0）
        Class.forName("S0.S0").getMethod("a", xClass).invoke(null, builder)
        val client = Class.forName("L1.y").getDeclaredConstructor(xClass).newInstance(builder)
        cachedClient = client
        android.util.Log.d(TAG, "L1 client built (HGTV transport)")
        return client
    }

    class L1Exception(message: String) : IllegalStateException(message)

    /** HGTV 签名器（S0.s1.d 字节码）是否可用。 */
    val signAvailable: Boolean by lazy {
        runCatching {
            val c = Class.forName("S0.s1")
            c.getMethod(
                "d", c, String::class.java, java.util.LinkedHashMap::class.java,
                ByteArray::class.java, String::class.java, java.util.LinkedHashMap::class.java,
                Int::class.javaPrimitiveType
            )
        }.isSuccess
    }

    /**
     * HGTV 字节码全链路：S0.s1.d 签名（其字节码产出 url+签名头集）→ G0 改写 →
     * E0 外层头 → L1 okhttp 发送。签名/传输全部与 HGTV 本尊同字节，仅号是我们的。
     *
     * @param baseUrl 如 https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1
     * @param query   播放 query（klink_egdi/iid/device_id/... 环境+档案，无 ts/_rticket——d 内部注入）
     * @param bodyGzip gzip 后的请求体（stub/ladon/argus 与发送用同一份字节）
     */
    @Suppress("UNCHECKED_CAST")
    fun signAndPost(baseUrl: String, query: java.util.LinkedHashMap<String, String>, bodyGzip: ByteArray): String {
        try {
            val s1Class = Class.forName("S0.s1")
            val inst = s1Class.getDeclaredConstructor().newInstance()
            val m2 = s1Class.getMethod(
                "d", s1Class, String::class.java, java.util.LinkedHashMap::class.java,
                ByteArray::class.java, String::class.java, java.util.LinkedHashMap::class.java,
                Int::class.javaPrimitiveType
            ).invoke(null, inst, baseUrl, query, bodyGzip, null, query, 8)
                ?: throw L1Exception("签名器返回 null")
            val url = m2.javaClass.getField("a").get(m2) as String
            // G0 头改写（b.G0 同款）：删 2 头 + passport 5051452 + 追加 7 头
            val heads = m2.javaClass.getField("b").get(m2) as java.util.LinkedHashMap<String, String>
            heads.remove("x-tt-dt")
            heads.remove("x-tt-request-tag")
            heads["passport-sdk-version"] = "5051452"
            heads["lc"] = "101"
            heads["x-ss-dp"] = "8662"
            heads["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
            heads["x-tt-trace-id"] = traceId()
            heads["Content-Encoding"] = "gzip"
            heads["X-Tt-Store-Region"] = "cn"
            heads["X-Tt-Store-Region-Src"] = "did"
            // E0 外层头（addHeader 双份是 HGTV 真实报文形态）
            val headerList = mutableListOf<Pair<String, String>>()
            for ((k, v) in heads) headerList.add(k to v)
            val ua = heads["User-Agent"] ?: ""
            if (ua.isNotEmpty()) headerList.add(0, "User-Agent" to ua)
            headerList.add(1, "Accept-Language" to "zh-CN,zh;q=0.9")
            headerList.add(2, "X-SS-DP" to "8662")
            headerList.add(3, "X-XS-From-Web" to "0")
            headerList.add(4, "Sdk-Version" to "2")
            return post(url, headerList, bodyGzip)
        } catch (e: L1Exception) {
            throw e
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw L1Exception("L1 签名通道异常: ${e.targetException?.message ?: e.message}")
        } catch (e: Exception) {
            throw L1Exception("L1 签名通道异常: ${e.message}")
        }
    }

    private fun traceId(): String {
        val hex = java.util.UUID.randomUUID().toString().replace("-", "")
        return "00-$hex-${hex.substring(0, 16)}-01"
    }

    /**
     * 同步 POST（调用方保证 IO 线程）。返回响应文本；HTTP 非 200 或网络异常抛 [L1Exception]。
     */
    fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray): String {
        try {
            val client = client()
            val wClass = Class.forName("L1.w")
            val jClass = Class.forName("H0.j")
            val mediaType = wClass.getMethod("a", String::class.java)
                .invoke(null, "application/json; charset=utf-8")
            val requestBody = jClass.getMethod("b", wClass, ByteArray::class.java)
                .invoke(null, mediaType, body)
            val dClass = Class.forName("L1.D")
            val eClass = Class.forName("L1.E")
            val yClass = Class.forName("L1.y")
            val cClass = Class.forName("L1.C")
            // HGTV E0 同款：new D(0)——(I)V 合成构造的 0 分支=默认 Builder
            val builder = dClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(0)
            dClass.getMethod("f", String::class.java).invoke(builder, url)
            val addHeader = dClass.getMethod("b", String::class.java, String::class.java)
            for ((k, v) in headers) addHeader.invoke(builder, k, v)
            dClass.getMethod("d", jClass).invoke(builder, requestBody)
            val request = dClass.getMethod("a").invoke(builder)
            val call = cClass.getMethod("e", yClass, eClass).invoke(null, client, request)
            val response = cClass.getMethod("d").invoke(call)
            val code = response.javaClass.getField("c").getInt(response)
            val bodyObj = response.javaClass.getField("g").get(response)
            val text = if (bodyObj != null) {
                bodyObj.javaClass.getMethod("string").invoke(bodyObj) as? String ?: ""
            } else ""
            android.util.Log.d(TAG, "L1 resp http=$code len=${text.length}")
            if (code != 200) throw L1Exception("L1 通道 HTTP $code")
            return text
        } catch (e: L1Exception) {
            throw e
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw L1Exception("L1 通道异常: ${e.targetException?.message ?: e.message}")
        } catch (e: Exception) {
            throw L1Exception("L1 通道异常: ${e.message}")
        }
    }
}
