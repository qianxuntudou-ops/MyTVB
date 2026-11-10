package com.mytvb.feature.hongguo.net

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 红果播放接口 WebView 执行器（TLS 指纹通道）。
 *
 * multi_video_model 对传输层 TLS 指纹风控：OkHttp（JVM SunJSSE 与 Android Conscrypt）空响应，
 * Node axios（Node 原生 TLS）与 HGTV（混淆 okhttp 特定栈）可通（2026-11-09 MuMu 双向实证）。
 * 签名与头经中继验证有效，仅传输层被拒——与 CCTV H5e get_live_info 同款行为，
 * 因此签名链 HTTP 走 WebView（Chromium 网络栈，红果网页版自身即此栈，必然放行）。
 *
 * 模式对齐 CmgWebSigner：离屏 1px WebView 挂宿主 decorView，shouldInterceptRequest
 * 注入与目标 API 同源的桥接页，JS 用 XHR 发请求（setRequestHeader 保留大写 X- 头名），
 * 响应文本经 console 桥回。单实例串行复用，主线程调用。
 */
class HongguoWebSigner private constructor(private val context: android.content.Context) {

    companion object {
        private const val TAG = "Hongguo"
        private const val RESULT_PREFIX = "__HG_BRIDGE_RES__"
        private const val ERROR_PREFIX = "__HG_BRIDGE_ERR__"
        private const val BRIDGE_PATH = "/__hg_bridge__"

        /** 目标 API 域（桥接页与请求同源，XHR 无 CORS）。 */
        var bridgeOrigin: String = "https://api5-normal-sinfonlineb.fqnovel.com"

        private var instance: HongguoWebSigner? = null

        /** 主线程获取单例。必须传 Activity——WebView 须 attach 到窗口才会执行（同 CmgWebSigner 实测约束）。 */
        fun get(context: android.content.Context): HongguoWebSigner =
            instance ?: HongguoWebSigner(context).also { instance = it }
    }

    /** 一次 WebView 请求的完整材料（Kotlin 侧签好，JS 只做 XHR 转发）。
     *  bodyBase64=true 时 bodyText 为 base64（gzip 二进制安全），JS 端 atob 还原。 */
    class BridgeRequest(
        val url: String,
        val headers: Map<String, String>,
        val bodyText: String,
        val bodyBase64: Boolean = false
    )

    class BridgeException(message: String, val code: String) : IllegalStateException(message)

    private var webView: WebView? = null
    private var pageReady = false
    private val readyWaiters = ConcurrentLinkedQueue<(Boolean) -> Unit>()
    private var pending: BridgeRequest? = null
    private var sink: ((String?, String?) -> Unit)? = null
    private val generation = AtomicInteger()
    private var timeoutRunnable = Runnable { finish(null, "WebView 请求超时") }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(context)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setAlpha(0.01f)
        view.visibility = View.GONE
        val params = android.widget.FrameLayout.LayoutParams(1, 1)
        params.gravity = Gravity.LEFT or Gravity.TOP
        (context as? android.app.Activity)?.let { activity ->
            (activity.window.decorView as? ViewGroup)?.addView(view, params)
        }
        val settings: WebSettings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // XHR 的 User-Agent 是 forbidden header，必须在 WebView 层设为 App UA（对齐 Node PLAYBACK_UA）
        settings.userAgentString =
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                if (url.substringBefore("?").endsWith(BRIDGE_PATH)) {
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(BRIDGE_HTML.toByteArray()))
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                android.util.Log.d(TAG, "webview pageFinished url=${url.take(80)}")
                if (url.substringBefore("?").endsWith(BRIDGE_PATH)) {
                    pageReady = true
                    drainReady()
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                val text = message?.message().orEmpty()
                android.util.Log.d(TAG, "webview console[${message?.messageLevel()}]: ${text.take(120)}")
                when {
                    text.startsWith("__HG_BRIDGE_STAT__") ->
                        android.util.Log.d(TAG, "webview xhr status/len=${text.substring(18)}")
                    text.startsWith(RESULT_PREFIX) -> onResult(text.substring(RESULT_PREFIX.length))
                    text.startsWith(ERROR_PREFIX) -> finish(null, "XHR ${text.substring(ERROR_PREFIX.length).take(80)}")
                }
                return true
            }
        }
        view.addJavascriptInterface(BridgeJs(), "HgBridge")
        webView = view
        return view
    }

    /** 桥接页须与目标 API 同源（跨域 XHR 会 preflight 失败 status=0）；按请求域加载对应桥接页。 */
    private var loadedOrigin: String? = null

    private fun ensurePageFor(url: String) {
        val origin = Regex("^https://[^/]+").find(url)?.value ?: return
        if (loadedOrigin == origin && pageReady) return
        pageReady = false
        loadedOrigin = origin
        webView?.loadUrl("$origin$BRIDGE_PATH")
    }

    /** JS 桥：Kotlin 主线程调 exec，JS 侧异步完成后 console 桥回。 */
    private inner class BridgeJs {
        @JavascriptInterface
        fun take(): String? {
            val req = pending ?: return null
            pending = null
            val payload = JSONObject()
                .put("url", req.url)
                .put("b64", req.bodyBase64)
                .put("body", req.bodyText)
            val headers = JSONObject()
            req.headers.forEach { (k, v) -> headers.put(k, v) }
            payload.put("headers", headers)
            return payload.toString()
        }
    }

    private fun drainReady() {
        while (true) {
            val waiter = readyWaiters.poll() ?: break
            waiter(true)
        }
    }

    /**
     * 执行一次签名 POST（主线程调用）。回调在主线程。
     * @param sink 成功传响应文本，失败传 (null, 原因)
     */
    fun post(request: BridgeRequest, timeoutMs: Long = 20_000L, sink: (String?, String?) -> Unit) {
        val view = ensureWebView()
        cancel()
        ensurePageFor(request.url)
        this.sink = sink
        generation.incrementAndGet()
        val gen = generation.get()
        fun deliver(ok: Boolean) {
            if (generation.get() != gen) return
            view.post {
                pending = request
                view.evaluateJavascript("window.__hgExec && window.__hgExec()", null)
            }
        }
        if (pageReady) deliver(true) else readyWaiters.add { deliver(it) }
        view.removeCallbacks(timeoutRunnable)
        view.postDelayed(timeoutRunnable, timeoutMs)
    }

    fun cancel() {
        webView?.removeCallbacks(timeoutRunnable)
        sink = null
        pending = null
    }

    fun destroy() {
        cancel()
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        pageReady = false
    }

    private fun onResult(encoded: String) {
        finish(java.net.URLDecoder.decode(encoded, "UTF-8"), null)
    }

    private fun finish(result: String?, reason: String?) {
        val sink = sink ?: return
        this.sink = null
        webView?.removeCallbacks(timeoutRunnable)
        if (result != null && result.isEmpty()) {
            sink(null, "空响应（WebView 通道）")
            return
        }
        sink(result, reason)
    }

    /** 同源桥接页：XHR POST，setRequestHeader 保留头名大小写，响应 encodeURIComponent 后 console 桥回。 */
    private val BRIDGE_HTML = """
        <!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
        <script>
        window.__hgExec = function () {
          try {
            var payload = window.HgBridge && window.HgBridge.take();
            if (!payload) return;
            var spec = JSON.parse(payload);
            var xhr = new XMLHttpRequest();
            xhr.open('POST', spec.url, true);
            var names = Object.keys(spec.headers || {});
            for (var i = 0; i < names.length; i++) {
              try { xhr.setRequestHeader(names[i], spec.headers[names[i]]); } catch (e) {}
            }
            xhr.onreadystatechange = function () {
              if (xhr.readyState !== 4) return;
              var text = xhr.responseText || '';
              console.log('__HG_BRIDGE_STAT__' + xhr.status + '/' + text.length);
              if (xhr.status !== 200) {
                console.log('$ERROR_PREFIX' + 'HTTP ' + xhr.status);
                return;
              }
              console.log('$RESULT_PREFIX' + encodeURIComponent(text));
            };
            if (spec.b64) {
              var bin = atob(spec.body);
              var u8 = new Uint8Array(bin.length);
              for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
              xhr.send(u8);
            } else {
              xhr.send(spec.body);
            }
          } catch (e) {
            console.log('$ERROR_PREFIX' + (e && e.message));
          }
        };
        </script></body></html>
    """.trimIndent()
}
