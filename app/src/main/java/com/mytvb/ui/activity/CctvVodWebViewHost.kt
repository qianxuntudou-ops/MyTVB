package com.mytvb.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import com.mytvb.core.common.log.AppLog
import com.tencent.smtt.sdk.QbSdk
import java.io.ByteArrayInputStream

/**
 * CCTV 影视播放页的 WebView 宿主：装了 X5 内核就优先用 X5（Chromium 版本较新，
 * postMessage/MSE 行为接近桌面），创建失败或未装则回退系统 WebView。
 *
 * 系统内核与 X5 的 `WebViewClient`/`WebChromeClient`/`WebResourceResponse` 包名不同，
 * 本类把两者收敛成同一套回调，播放页只关心业务逻辑。
 */
class CctvVodWebViewHost(
    private val context: Context,
    private val bridgeName: String,
    private val bridge: Any,
    private val userAgent: String,
    private val listener: Listener
) {

    interface Listener {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onConsole(message: String)
        fun onMainFrameError(description: String)
        fun onShowCustomView(view: View)
        fun onHideCustomView()

        /** true 表示该脚本与播放无关，返回空实现让页面解析继续。 */
        fun shouldSkipScript(url: String): Boolean

        /** true 表示该请求与播放无关（如第三方广告投放），返回空响应不让它出网。 */
        fun shouldBlockRequest(url: String): Boolean

        /** 老内核兜底：返回注入补丁后的主文档 HTML；null 表示不接管。 */
        fun mainDocumentHtml(url: String, cookie: String?): ByteArray?
    }

    val view: View
    val usingX5: Boolean

    private var sysWebView: android.webkit.WebView? = null
    private var x5WebView: com.tencent.smtt.sdk.WebView? = null
    /** 全屏回调（系统/X5 两种类型），退出全屏时要通知页面。 */
    private var customViewCallback: Any? = null

    init {
        // canLoadX5 已被 X5 SDK 标弃用但无等价替代 API，维持原判定
        @Suppress("DEPRECATION")
        val canLoadX5 = runCatching { QbSdk.canLoadX5(context) }.getOrDefault(false)
        val x5 = if (canLoadX5) {
            runCatching { createX5WebView() }
                .onFailure { AppLog.w(TAG, "X5 create failed: ${it.message}") }
                .getOrNull()
        } else {
            null
        }
        if (x5 != null) {
            x5WebView = x5
            view = x5
            usingX5 = true
            AppLog.i(TAG, "engine=x5 tbs=${runCatching { QbSdk.getTbsVersion(context) }.getOrDefault(-1)}")
        } else {
            val sys = createSystemWebView()
            sysWebView = sys
            view = sys
            usingX5 = false
            AppLog.i(TAG, "engine=system")
        }
    }

    // ---------------------------------------------------------------- 操作

    fun loadUrl(url: String) {
        x5WebView?.loadUrl(url) ?: sysWebView?.loadUrl(url)
    }

    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null) {
        x5WebView?.let { it.evaluateJavascript(script) { r -> callback?.invoke(r ?: "") } }
            ?: sysWebView?.evaluateJavascript(script) { r -> callback?.invoke(r ?: "") }
    }

    fun onPause() {
        runCatching { x5WebView?.onPause() }
        runCatching { sysWebView?.onPause() }
    }

    fun onResume() {
        runCatching { x5WebView?.onResume() }
        runCatching { sysWebView?.onResume() }
    }

    fun stopLoading() {
        runCatching { x5WebView?.stopLoading() }
        runCatching { sysWebView?.stopLoading() }
    }

    fun removeJavascriptInterface(name: String) {
        runCatching { x5WebView?.removeJavascriptInterface(name) }
        runCatching { sysWebView?.removeJavascriptInterface(name) }
    }

    fun destroy() {
        runCatching { x5WebView?.let { it.loadUrl("about:blank"); it.destroy() } }
        runCatching { sysWebView?.let { it.loadUrl("about:blank"); it.destroy() } }
    }

    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setKeepScreenOn(on: Boolean) {
        view.keepScreenOn = on
    }

    /**
     * 该 View 是否位于 WebView 内部（含 WebView 自身）。
     * 用于识别"焦点被网页层抢走"：X5 内部子 View 会自行 requestFocus。
     */
    fun isInsideWebView(candidate: View?): Boolean {
        var cur: View = candidate ?: return false
        while (true) {
            if (cur === view) return true
            cur = cur.parent as? View ?: return false
        }
    }

    /** 把服务端 Set-Cookie 写进当前内核的 CookieManager（主文档注入时透传用）。 */
    fun setCookie(url: String, cookie: String) {
        runCatching {
            x5WebView?.let {
                com.tencent.smtt.sdk.CookieManager.getInstance().setCookie(url, cookie)
            }
            sysWebView?.let {
                android.webkit.CookieManager.getInstance().setCookie(url, cookie)
            }
        }
    }

    /** 通知页面"已退出全屏"（系统/X5 两套回调类型不同，按实际持有类型分发）。 */
    fun notifyCustomViewHidden() {
        val callback = customViewCallback ?: return
        customViewCallback = null
        runCatching {
            (callback as? android.webkit.WebChromeClient.CustomViewCallback)?.onCustomViewHidden()
        }
        runCatching {
            (callback as? com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback)
                ?.onCustomViewHidden()
        }
    }

    // ---------------------------------------------------------------- 系统内核

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSystemWebView(): android.webkit.WebView {
        val wv = android.webkit.WebView(context)
        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        // 按键由宿主统一接管（遥控器操作官方控件无效），WebView 不抢焦点
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgent
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(bridge, bridgeName)
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: android.webkit.WebView, url: String?, favicon: android.graphics.Bitmap?) {
                listener.onPageStarted(url)
            }

            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                listener.onPageFinished(url)
            }

            override fun shouldInterceptRequest(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (request.isForMainFrame && request.method == "GET" &&
                    (url.startsWith("http://") || url.startsWith("https://"))
                ) {
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                    listener.mainDocumentHtml(url, cookie)?.let {
                        return android.webkit.WebResourceResponse(
                            "text/html", "utf-8", ByteArrayInputStream(it)
                        )
                    }
                }
                if (listener.shouldSkipScript(url)) {
                    return android.webkit.WebResourceResponse(
                        "application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (listener.shouldBlockRequest(url)) {
                    return android.webkit.WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    listener.onMainFrameError(error?.description?.toString().orEmpty())
                }
            }
        }
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: android.webkit.WebChromeClient.CustomViewCallback?
            ) {
                if (view != null) {
                    customViewCallback = callback
                    listener.onShowCustomView(view)
                }
            }

            override fun onHideCustomView() = listener.onHideCustomView()

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                listener.onConsole(consoleMessage?.message().orEmpty())
                return true
            }
        }
        return wv
    }

    // ---------------------------------------------------------------- X5 内核

    @SuppressLint("SetJavaScriptEnabled")
    private fun createX5WebView(): com.tencent.smtt.sdk.WebView {
        val wv = com.tencent.smtt.sdk.WebView(context)
        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        // X5 是 FrameLayout 壳，内部 webview 子 View 默认可聚焦，且 attach/onResume 时会
        // 主动 requestFocus 把焦点抢回网页层；不阻断的话控制条按钮永远拿不到焦点。
        wv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        wv.settings.apply {
            // X5 WebSettings 弃用了该 setter 但未提供等价替代 API，维持原开关
            @Suppress("DEPRECATION")
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = com.tencent.smtt.sdk.WebSettings.LOAD_DEFAULT
            runCatching { mixedContentMode = 0 }
            userAgentString = userAgent
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        com.tencent.smtt.sdk.CookieManager.getInstance().setAcceptCookie(true)
        runCatching { com.tencent.smtt.sdk.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
        wv.addJavascriptInterface(bridge, bridgeName)
        // X5 播放 H5 视频/全屏需要该扩展（方法体可为空，调用本身是开关）
        runCatching { wv.setWebChromeClientExtension(createX5ChromeExtension()) }
        wv.webViewClient = object : com.tencent.smtt.sdk.WebViewClient() {
            override fun onPageStarted(view: com.tencent.smtt.sdk.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                listener.onPageStarted(url)
            }

            override fun onPageFinished(view: com.tencent.smtt.sdk.WebView?, url: String?) {
                listener.onPageFinished(url)
            }

            override fun shouldInterceptRequest(
                view: com.tencent.smtt.sdk.WebView?,
                request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?
            ): com.tencent.smtt.export.external.interfaces.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (request.isForMainFrame && request.method == "GET" &&
                    (url.startsWith("http://") || url.startsWith("https://"))
                ) {
                    val cookie = com.tencent.smtt.sdk.CookieManager.getInstance().getCookie(url)
                    listener.mainDocumentHtml(url, cookie)?.let {
                        return com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                            "text/html", "utf-8", ByteArrayInputStream(it)
                        )
                    }
                }
                if (listener.shouldSkipScript(url)) {
                    return com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                        "application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (listener.shouldBlockRequest(url)) {
                    return com.tencent.smtt.export.external.interfaces.WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: com.tencent.smtt.sdk.WebView?,
                request: com.tencent.smtt.export.external.interfaces.WebResourceRequest?,
                error: com.tencent.smtt.export.external.interfaces.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    listener.onMainFrameError(error?.description?.toString().orEmpty())
                }
            }
        }
        wv.webChromeClient = object : com.tencent.smtt.sdk.WebChromeClient() {
            override fun onShowCustomView(
                view: View?,
                callback: com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback?
            ) {
                if (view != null) {
                    customViewCallback = callback
                    listener.onShowCustomView(view)
                }
            }

            override fun onHideCustomView() = listener.onHideCustomView()

            override fun onConsoleMessage(
                consoleMessage: com.tencent.smtt.export.external.interfaces.ConsoleMessage?
            ): Boolean {
                listener.onConsole(consoleMessage?.message().orEmpty())
                return true
            }
        }
        return wv
    }

    /** 方法体全空，仅调用 `setWebChromeClientExtension` 打开 X5 的 H5 视频全屏能力。 */
    private fun createX5ChromeExtension(): com.tencent.smtt.export.external.extension.interfaces.IX5WebChromeClientExtension =
        object : com.tencent.smtt.export.external.extension.interfaces.IX5WebChromeClientExtension {
            override fun getX5WebChromeClientInstance(): Any? = null
            override fun getVideoLoadingProgressView(): View? = null
            override fun onAllMetaDataFinished(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                p1: HashMap<String, String>?
            ) {}
            override fun onBackforwardFinished(p0: Int) {}
            override fun onHitTestResultForPluginFinished(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                hit: com.tencent.smtt.export.external.interfaces.IX5WebViewBase.HitTestResult?,
                p2: android.os.Bundle?
            ) {}
            override fun onHitTestResultFinished(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                hit: com.tencent.smtt.export.external.interfaces.IX5WebViewBase.HitTestResult?
            ) {}
            override fun onPromptScaleSaved(ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?) {}
            override fun onPromptNotScalable(ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?) {}
            override fun onAddFavorite(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                p1: String?, p2: String?, p3: com.tencent.smtt.export.external.interfaces.JsResult?
            ): Boolean = false
            override fun onPrepareX5ReadPageDataFinished(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                p1: HashMap<String, String>?
            ) {}
            override fun onSavePassword(p0: String?, p1: String?, p2: String?, p3: Boolean, p4: android.os.Message?): Boolean = false
            override fun onSavePassword(
                p0: android.webkit.ValueCallback<String>?, p1: String?, p2: String?,
                p3: String?, p4: String?, p5: String?, p6: Boolean
            ): Boolean = false
            override fun onX5ReadModeAvailableChecked(p0: HashMap<String, String>?) {}
            override fun addFlashView(p0: View?, p1: android.view.ViewGroup.LayoutParams?) {}
            override fun h5videoRequestFullScreen(p0: String?) {}
            override fun h5videoExitFullScreen(p0: String?) {}
            override fun requestFullScreenFlash() {}
            override fun exitFullScreenFlash() {}
            override fun jsRequestFullScreen() {}
            override fun jsExitFullScreen() {}
            override fun acquireWakeLock() {}
            override fun releaseWakeLock() {}
            override fun getApplicationContex(): Context? = context.applicationContext
            override fun onPageNotResponding(p0: Runnable?): Boolean = false
            override fun onMiscCallBack(p0: String?, p1: android.os.Bundle?): Any? = null
            override fun openFileChooser(p0: android.webkit.ValueCallback<Array<android.net.Uri>>?, p1: String?, p2: String?) {}
            override fun onPrintPage() {}
            override fun onColorModeChanged(p0: Long) {}
            override fun onPermissionRequest(
                p0: String?, p1: Long,
                p2: com.tencent.smtt.export.external.interfaces.MediaAccessPermissionsCallback?
            ): Boolean = true
        }

    companion object {
        private const val TAG = "CctvVodWebHost"
    }
}
