package com.mytvb.feature.cctv.cmg

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mytvb.core.common.log.AppLog
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 央视频（yangshipin）签名链 WebView 执行器。
 *
 * get_live_info 端点按 TLS 指纹风控（OkHttp 同签名同参数被拒 20401、curl/浏览器放行，
 * 2026-10-31 MuMu+桌面 curl 双向实证），三步签名链的 HTTP 请求须浏览器栈发出。
 * 对齐 nTv YangshipinWebResolver 的 native-light 模式：离屏 1px WebView 承担
 * auth → open/token → get_live_info 三步 XHR（api-page/live-request 模板来自 cjs
 * sites/yangshipin.cn，`NtvYspSigner` JavascriptInterface 桥接到本工程 wasm），
 * Kotlin 侧只负责签名材料计算与结果解析。**只签名不播放**，完成即销毁。
 *
 * 单实例串行复用（主线程创建，挂在宿主 decorView 上）。
 */
class CmgWebSigner private constructor(private val context: android.content.Context) {

    companion object {
        private const val TAG = "CmgWebSigner"
        private const val TIMEOUT_MS = 20_000L
        private const val AUTH_PREFIX = "__NTV_YSP_AUTH__"
        private const val RESULT_PREFIX = "__NTV_YSP_API__"
        private const val ERROR_PREFIX = "__NTV_YSP_API_ERROR__"
        private const val HOME_URL = "https://www.yangshipin.cn/"

        private var instance: CmgWebSigner? = null

        /** 主线程获取单例。必须传 Activity——WebView 须 attach 到窗口才会加载
         *  （applicationContext 下 as? Activity 为 null、view 不挂树，X5 环境实测不执行）。 */
        fun get(context: android.content.Context): CmgWebSigner =
            instance ?: CmgWebSigner(context).also { instance = it }

        /** 模板渲染：live-request.js 的全部占位（静态——Resolver 在 auth 回调里调用）。 */
        fun renderLiveScript(
            context: android.content.Context,
            pid: String, guid: String, ticketRandom: String,
            serverSeconds: Long, token: String,
            liveBody: String, sdkInput: String, requestId: String
        ): String {
            val template = context.assets.open("cmg/live-request.js").bufferedReader().use { it.readText() }
            return template
                .replace("{{ERROR_PREFIX}}", jsonQuotedStatic(ERROR_PREFIX))
                .replace("{{RESULT_PREFIX}}", jsonQuotedStatic(RESULT_PREFIX))
                .replace("{{PID}}", jsonQuotedStatic(pid))
                .replace("{{SERVER_SECONDS}}", serverSeconds.toString())
                .replace("{{GUID}}", jsonQuotedStatic(guid))
                .replace("{{TICKET_RANDOM}}", jsonQuotedStatic(ticketRandom))
                .replace("{{TOKEN}}", jsonQuotedStatic(token))
                .replace("{{REQUEST_ID}}", jsonQuotedStatic(requestId))
                .replace("{{SDK_INPUT}}", jsonQuotedStatic(sdkInput))
                .replace("{{LIVE_BODY}}", jsonQuotedStatic(liveBody))
                .replace("{{TOKEN_TIME}}", (serverSeconds * 1000L).toString())
                .replace("{{FULL_SDK_INPUT}}", jsonQuotedStatic("$sdkInput-$guid-1-$requestId"))
        }

        private fun jsonQuotedStatic(value: String): String =
            org.json.JSONObject.quote(value)
    }

    /** 一次签名请求的完整材料（Kotlin 侧算好，模板占位替换用）。 */
    data class Request(
        val pid: String,
        val cnlid: String,
        val defn: String,
        val guid: String,
        val ticketRandom: String,
        val authBody: String,
        /** auth 响应服务器秒到达后回调（Kotlin 侧补算 liveBody/sdkInput 等）。 */
        val buildLiveScript: (serverSeconds: Long, token: String) -> String
    )

    /** 结果：playurl（已拼 extended_param）+ 服务器秒。 */
    data class Result(val playUrl: String, val serverSeconds: Long)

    class SignException(message: String) : Exception(message)

    private var webView: WebView? = null
    private var currentRequest: Request? = null
    private var resultSink: ((Result?, String?) -> Unit)? = null
    private var authDone = false
    private val timeoutRunnable = Runnable { finish(null, "签名超时") }
    private val generation = AtomicInteger()

    /** wasm 签名桥（模板内名字固定为 NtvYspSigner，对齐 cjs）。 */
    private inner class SignerBridge {
        @JavascriptInterface
        fun tokenRnd(guid: String, timestampMs: String): String =
            try {
                CmgNativeBridge.tokenRnd(guid, timestampMs).orEmpty()
            } catch (t: Throwable) {
                AppLog.e(TAG, "tokenRnd wasm 异常", t)
                ""
            }

        @JavascriptInterface
        fun signature(guid: String, token: String, input: String): String =
            try {
                CmgNativeBridge.signature(guid, token, input).orEmpty()
            } catch (t: Throwable) {
                AppLog.e(TAG, "signature wasm 异常", t)
                ""
            }
    }

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
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        }
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = CmgLiveStreamResolver.DESKTOP_USER_AGENT
        view.addJavascriptInterface(SignerBridge(), "NtvYspSigner")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                url: String
            ): WebResourceResponse? {
                val request = currentRequest ?: return null
                if (url == HOME_URL || url == "$HOME_URL/") {
                    val html = renderApiPage(request)
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                }
                return null
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                val text = message?.message().orEmpty()
                when {
                    text.startsWith(AUTH_PREFIX) -> onAuth(text.substring(AUTH_PREFIX.length))
                    text.startsWith(RESULT_PREFIX) -> onResult(text.substring(RESULT_PREFIX.length))
                    text.startsWith(ERROR_PREFIX) -> finish(null, "API ${text.substring(ERROR_PREFIX.length).take(80)}")
                }
                return true
            }
        }
        webView = view
        return view
    }

    /**
     * 执行一次签名链（主线程调用）。回调在主线程。
     * @param sink 成功传 [Result]，失败传 (null, 原因)
     */
    fun request(request: Request, sink: (Result?, String?) -> Unit) {
        val view = ensureWebView()
        cancel()
        currentRequest = request
        resultSink = sink
        authDone = false
        generation.incrementAndGet()
        seedCookies(request)
        view.removeCallbacks(timeoutRunnable)
        view.postDelayed(timeoutRunnable, TIMEOUT_MS)
        AppLog.i(TAG, "签名链开始 pid=${request.pid} guid=${request.guid}")
        view.loadUrl("about:blank")
        view.postDelayed({ view.loadUrl(HOME_URL) }, 30L)
    }

    fun cancel() {
        webView?.removeCallbacks(timeoutRunnable)
        resultSink = null
        currentRequest = null
        webView?.stopLoading()
    }

    /** 整体销毁（页面退出时）。 */
    fun destroy() {
        cancel()
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    private fun onAuth(payload: String) {
        val request = currentRequest ?: return
        if (authDone) return
        val separator = payload.lastIndexOf('|')
        if (separator <= 0) {
            finish(null, "auth 响应格式异常")
            return
        }
        val token = android.net.Uri.decode(payload.substring(0, separator))
        val serverSeconds = payload.substring(separator + 1).toLongOrNull() ?: 0L
        if (token.isBlank() || serverSeconds <= 0L) {
            finish(null, "auth 缺 token/ts")
            return
        }
        authDone = true
        AppLog.i(TAG, "auth 完成 serverSeconds=$serverSeconds")
        val script = request.buildLiveScript(serverSeconds, token)
        webView?.evaluateJavascript(script, null)
    }

    private fun onResult(payload: String) {
        try {
            val response = android.net.Uri.decode(payload)
            val root = org.json.JSONObject(response)
            val data = root.optJSONObject("data")
            var url = data?.optString("playurl").orEmpty()
            if (root.optInt("code") != 0 || url.isBlank()) {
                finish(null, "get_live_info code=${root.optInt("code")} msg=${root.optString("msg")}")
                return
            }
            if (!url.contains(".m3u8") || !url.contains("ysp.cctv.cn")) {
                finish(null, "playurl 非预期线路")
                return
            }
            val extended = data?.optString("extended_param").orEmpty()
            if (extended.isNotBlank() && !url.contains(extended)) {
                url += extended
            }
            val seconds = data?.optLong("svrtime") ?: 0L
            finish(Result(url, seconds), null)
        } catch (t: Throwable) {
            AppLog.e(TAG, "结果解析异常", t)
            finish(null, "结果解析异常")
        }
    }

    private fun finish(result: Result?, reason: String?) {
        val sink = resultSink ?: return
        resultSink = null
        webView?.removeCallbacks(timeoutRunnable)
        webView?.post { webView?.loadUrl("about:blank") }
        currentRequest = null
        if (result != null) {
            AppLog.i(TAG, "签名链成功 url=${result.playUrl.take(90)}")
        } else {
            AppLog.w(TAG, "签名链失败：$reason")
        }
        sink(result, reason)
    }

    private fun seedCookies(request: Request) {
        val cookies = CookieManager.getInstance()
        val domain = "; Domain=.yangshipin.cn; Path=/"
        cookies.setCookie(HOME_URL, "guid=${request.guid}$domain")
        cookies.setCookie(HOME_URL, "versionName=99.99.99$domain")
        cookies.setCookie(HOME_URL, "versionCode=999999$domain")
        cookies.setCookie(HOME_URL, "vplatform=109$domain")
        cookies.setCookie(HOME_URL, "platformVersion=Chrome$domain")
        cookies.setCookie(HOME_URL, "deviceModel=148$domain")
        cookies.setCookie(HOME_URL, "newLogin=1$domain")
        cookies.setCookie(HOME_URL, "pc_version=1.1.16$domain")
        cookies.setCookie(HOME_URL, "ysp_uinfo_pc=$domain")
        cookies.setCookie(HOME_URL, "nseqId=1$domain")
    }

    /** 模板渲染：api-page.html 的 {{ERROR_PREFIX}}/{{AUTH_PREFIX}}/{{AUTH_BODY}}。 */
    private fun renderApiPage(request: Request): String {
        val template = context.assets.open("cmg/api-page.html").bufferedReader().use { it.readText() }
        return template
            .replace("{{ERROR_PREFIX}}", jsonQuoted(ERROR_PREFIX))
            .replace("{{AUTH_PREFIX}}", jsonQuoted(AUTH_PREFIX))
            .replace("{{AUTH_BODY}}", jsonQuoted(request.authBody))
    }

    private fun jsonQuoted(value: String): String =
        org.json.JSONObject.quote(value)
}
