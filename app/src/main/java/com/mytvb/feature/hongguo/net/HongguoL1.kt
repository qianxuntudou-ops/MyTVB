package com.mytvb.feature.hongguo.net

/**
 * HGTV 网络层直接搬运：
 * APK 构建后把 HGTV 的 classes.dex（们）以 classes20/21.dex 塞入（scripts/embed_hgtv_dex.py），
 * 本类反射调用 HGTV 的魔改 okhttp + 签名器——同一份字节码发请求/签名，服务端无法区分。
 *
 * 双版本映射（2026-11-10）：1.3.4 与 1.3.6 混淆名不同（1.3.6 网络层重组为 F0/H1 包），
 * 运行时按签名器类名自动探测：
 * - 1.3.4：签名 S0.s1.d / M2 / okhttp=L1 包（x/y/D/C）/ TLS S0.S0 / Protocol L1.z.c
 * - 1.3.6：签名 F0.x1.e / c3 / okhttp=H1 包（z/A/F/E）/ TLS F0.U0 / Protocol H1.B.c
 *   （Headers.Builder=B.Z.u；Request.Builder 的 headers 走内部字段 c；body=b(method,body)）
 * M2/c3 字段同序：a=url / b=headers(Map) / c=body / d=method；Response code/body 同在 c/g。
 */
internal object HongguoL1 {

    /** dex 混淆映射（真实类名均经 dexdump 验证，jadx 的 fNNNN/CNNN 前缀是显示名）。 */
    private class Map(
        val signer: String, val signMethod: String,
        val m2: String,
        val media: String, val bodyFactory: String, val bodyMethod: String,
        val reqBuilder: String, val call: String,
        val clientBuilder: String, val client: String,
        val proto: String, val tls: String
    )

    private val MAP_134 = Map(
        "S0.s1", "d", "S0.M2",
        "L1.w", "H0.j", "b",
        "L1.D", "L1.C",
        "L1.x", "L1.y",
        "L1.z", "S0.S0"
    )
    private val MAP_136 = Map(
        "F0.x1", "e", "F0.c3",
        "H1.y", "H1.H", "c",
        "H1.F", "H1.E",
        "H1.z", "H1.A",
        "H1.B", "F0.U0"
    )

    private val map: Map? by lazy {
        if (runCatching { Class.forName(MAP_134.signer) }.isSuccess) MAP_134
        else if (runCatching { Class.forName(MAP_136.signer) }.isSuccess) MAP_136
        else null
    }

    /** classes20/21.dex 是否已随包加载。 */
    val available: Boolean by lazy { map != null && runCatching { Class.forName(map!!.reqBuilder) }.isSuccess }

    /** HGTV 签名器是否可用（available 的子集，语义分离）。 */
    val signAvailable: Boolean by lazy { available }

    private var cachedClient: Any? = null
    private const val TAG = "Hongguo"

    @Synchronized
    private fun client(): Any {
        cachedClient?.let { return it }
        val m = map ?: throw L1Exception("L1 dex 未加载")
        val builderClass = Class.forName(m.clientBuilder)
        val builder = builderClass.getDeclaredConstructor().newInstance()
        // 超时（方法名混淆不定，按签名找 (long, TimeUnit)；1.3.6 实测 b/d/a=15/30/45s）
        val timeUnit = java.util.concurrent.TimeUnit.SECONDS
        for (method in builderClass.methods) {
            if (method.parameterCount == 2 && method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                method.parameterTypes[1] == java.util.concurrent.TimeUnit::class.java
            ) {
                runCatching { method.invoke(builder, 20L, timeUnit) }
            }
        }
        // protocols = [HTTP/1.1]（枚举常量 c，1.3.4 L1.z.c / 1.3.6 H1.B.c 同名）
        val protoClass = Class.forName(m.proto)
        val http11 = protoClass.getDeclaredField("c").get(null)
        builderClass.methods.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == java.util.List::class.java }
            ?.invoke(builder, java.util.Collections.singletonList(http11))
        // TLS 定制（1.3.4 S0.S0.a / 1.3.6 F0.U0.a：ConnectionSpecs+SSLSocketFactory 包装+DNS）
        Class.forName(m.tls).getMethod("a", builderClass).invoke(null, builder)
        val client = Class.forName(m.client).getDeclaredConstructor(builderClass).newInstance(builder)
        cachedClient = client
        android.util.Log.d(TAG, "L1 client built (HGTV transport, ${if (m === MAP_136) "1.3.6" else "1.3.4"})")
        return client
    }

    class L1Exception(message: String) : IllegalStateException(message)

    /**
     * 同步 POST（调用方保证 IO 线程）。返回响应文本；HTTP 非 200 或网络异常抛 [L1Exception]。
     */
    fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray): String {
        try {
            val m = map ?: throw L1Exception("L1 dex 未加载")
            val client = client()
            val mediaType = Class.forName(m.media).getMethod("a", String::class.java)
                .invoke(null, "application/json; charset=utf-8")
            val requestBody = Class.forName(m.bodyFactory)
                .getMethod(m.bodyMethod, Class.forName(m.media), ByteArray::class.java)
                .invoke(null, mediaType, body)
            val builderClass = Class.forName(m.reqBuilder)
            val builder = builderClass.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(0)
            builderClass.methods.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java && it.name == "d" }
                ?.invoke(builder, url) ?: builderClass.getMethod("d", String::class.java).invoke(builder, url)
            // headers：1.3.4 builder.b(k,v)；1.3.6 走内部字段 c（B.Z 的 u 方法）
            val addHeader134 = builderClass.methods.firstOrNull {
                it.parameterCount == 2 && it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java && it.name == "b"
            }
            val headersField136 = runCatching {
                val hb = builderClass.getDeclaredField("c").apply { isAccessible = true }.get(builder)
                val z = Class.forName("B.Z")
                val u = z.getMethod("u", String::class.java, String::class.java)
                if (hb!!.javaClass.name == "B.Z") u to hb else null
            }.getOrNull()
            for ((k, v) in headers) {
                if (addHeader134 != null) addHeader134.invoke(builder, k, v)
                else if (headersField136 != null) headersField136.first.invoke(headersField136.second, k, v)
                else throw L1Exception("L1 headers 通道不可用")
            }
            // body 设置：1.3.6 的 F.b(String,H) 可能非 public——用 declaredMethods+可访问
            val bodyFactoryClass = Class.forName(m.bodyFactory)
            val bodyMethod2 = builderClass.declaredMethods.firstOrNull {
                it.parameterCount == 2 && it.name == "b" && it.parameterTypes[1] == bodyFactoryClass
            }?.apply { isAccessible = true }
            if (bodyMethod2 != null) {
                bodyMethod2.invoke(builder, "POST", requestBody)
            } else {
                val bodyMethod1 = builderClass.declaredMethods.firstOrNull {
                    it.parameterCount == 1 && it.parameterTypes[0] == bodyFactoryClass
                }?.apply { isAccessible = true }
                    ?: throw L1Exception("L1 body 方法未找到（${builderClass.name}）")
                bodyMethod1.invoke(builder, requestBody)
            }
            val request = builderClass.getMethod("a").invoke(builder)
            val call = Class.forName(m.call)
                .getMethod("e", Class.forName(m.client), request.javaClass)
                .invoke(null, client, request)
            val response = Class.forName(m.call).getMethod("d").invoke(call)
            val code = response.javaClass.getField("c").getInt(response)
            val bodyObj = response.javaClass.getField("g").get(response)
            val text = if (bodyObj != null) {
                bodyObj.javaClass.getMethod("string").invoke(bodyObj) as? String ?: ""
            } else ""
            android.util.Log.d(TAG, "L1 resp http=$code len=${text.length}")
            if (code != 200) throw L1Exception("L1 通道 HTTP $code")
            return text
        } catch (e: L1Exception) {
            android.util.Log.w(TAG, "L1 post fail: ${e.message}")
            throw e
        } catch (e: java.lang.reflect.InvocationTargetException) {
            android.util.Log.w(TAG, "L1 post fail: ${e.targetException}")
            throw L1Exception("L1 通道异常: ${e.targetException?.message ?: e.message}")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "L1 post fail", e)
            throw L1Exception("L1 通道异常: ${e.message}")
        }
    }

    /**
     * HGTV 字节码全链路：签名器（S0.s1.d / F0.x1.e）产出 url+签名头集 → G0/S0 改写 →
     * E0/Q0 外层头 → L1 okhttp 发送。签名/传输全部与 HGTV 本尊同字节，仅号是我们的。
     *
     * @param baseUrl 如 https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1
     * @param query   播放 query（klink_egdi/iid/device_id/... 环境+档案，无 ts/_rticket——签名器内部注入）
     * @param body    请求体（gzip=true 时须传 gzip 后的字节；stub/ladon/argus 与发送用同一份）
     * @param gzip    body 是否已 gzip（播放=true；feed 明文=false 不带 Content-Encoding 头）
     */
    @Suppress("UNCHECKED_CAST")
    fun signAndPost(baseUrl: String, query: java.util.LinkedHashMap<String, String>, body: ByteArray, gzip: Boolean = true): String {
        try {
            val m = map ?: throw L1Exception("L1 dex 未加载")
            val signerClass = Class.forName(m.signer)
            val inst = signerClass.getDeclaredConstructor().newInstance()
            val m2 = signerClass.getMethod(
                m.signMethod, signerClass, String::class.java, java.util.LinkedHashMap::class.java,
                ByteArray::class.java, String::class.java, java.util.LinkedHashMap::class.java,
                Int::class.javaPrimitiveType
            ).invoke(null, inst, baseUrl, query, body, null, query, 8)
                ?: throw L1Exception("签名器返回 null")
            val url = m2.javaClass.getField("a").get(m2) as String
            // G0/S0 头改写（HGTV 同款）：删 2 头 + passport 5051452 + 追加加密组
            val heads = m2.javaClass.getField("b").get(m2) as java.util.LinkedHashMap<String, String>
            heads.remove("x-tt-dt")
            heads.remove("x-tt-request-tag")
            heads["passport-sdk-version"] = "5051452"
            heads["lc"] = "101"
            heads["x-ss-dp"] = "8662"
            heads["x-vc-bdturing-sdk-version"] = "4.0.3.cn"
            heads["x-tt-trace-id"] = traceId()
            if (gzip) heads["Content-Encoding"] = "gzip"
            heads["X-Tt-Store-Region"] = "cn"
            heads["X-Tt-Store-Region-Src"] = "did"
            // E0/Q0 外层头（addHeader 双份是 HGTV 真实报文形态）
            val headerList = mutableListOf<Pair<String, String>>()
            for ((k, v) in heads) headerList.add(k to v)
            val ua = heads["User-Agent"] ?: ""
            if (ua.isNotEmpty()) headerList.add(0, "User-Agent" to ua)
            headerList.add(1, "Accept-Language" to "zh-CN,zh;q=0.9")
            headerList.add(2, "X-SS-DP" to "8662")
            headerList.add(3, "X-XS-From-Web" to "0")
            headerList.add(4, "Sdk-Version" to "2")
            return post(url, headerList, body)
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
}
