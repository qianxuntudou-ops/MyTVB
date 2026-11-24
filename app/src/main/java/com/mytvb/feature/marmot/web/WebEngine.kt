package com.mytvb.feature.marmot.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import com.tencent.smtt.sdk.QbSdk
import com.mytvb.feature.marmot.MarmotJsBridge
import com.mytvb.feature.marmot.MarmotLiveData

/**
 * WebView 引擎封装（统一系统 WebView 与 X5 WebView 的创建/配置/操作）。
 *
 * 系统 WebView（`android.webkit.WebView`）和 X5 WebView（`com.tencent.smtt.sdk.WebView`）
 * API 几乎一致，但 `WebViewClient`/`WebChromeClient`/`WebResourceResponse` 包名不同。
 * 本类按 [useX5] 创建对应 WebView，对外暴露统一的操作接口（loadUrl/evaluateJavascript 等）。
 *
 * @param context Activity 上下文
 * @param useX5 true 用 X5 WebView（需 canLoadX5 为 true），false 用系统 WebView
 * @param jsBridge JS 桥（addJavascriptInterface 用，两者 API 一致）
 * @param onProgress 加载进度回调
 * @param onPageLoaded 页面加载完成回调
 */
class WebEngine(
    private val context: Context,
    private val useX5: Boolean,
    private val jsBridge: MarmotJsBridge,
    private val onProgress: (Int) -> Unit,
    private val onPageLoaded: (url: String) -> Unit,
    private val onShowCustomView: (android.view.View) -> Unit = {},
    private val onHideCustomView: () -> Unit = {}
) {
    companion object {
        private const val TAG = "WebEngine"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /** 实际 WebView 实例（系统或 X5）。对外用 [loadUrl] 等方法操作。 */
    val view: android.view.View

    /** 是否实际使用 X5 内核（X5 创建失败回退系统后为 false）。 */
    val usingX5: Boolean

    init {
        var x5Ok = useX5
        // 对标参考 BaseActivity.initWebViewFallback：X5 创建失败则降级系统 WebView
        view = if (useX5) {
            try {
                createX5WebView()
            } catch (e: Throwable) {
                Log.e(TAG, "X5 WebView 初始化失败，降级系统 WebView: ${e.message}", e)
                x5Ok = false
                createSystemWebView()
            }
        } else {
            createSystemWebView()
        }
        usingX5 = x5Ok
    }

    // ==================== 公共操作（系统/X5 共用） ====================

    fun loadUrl(url: String) {
        if (usingX5) {
            (view as com.tencent.smtt.sdk.WebView).loadUrl(url)
        } else {
            (view as android.webkit.WebView).loadUrl(url)
        }
    }

    /** 加载自构 HTML（CCTV createLivePlayer 方式用）。系统/X5 共用。 */
    fun loadDataWithBaseURL(baseUrl: String, html: String) {
        if (usingX5) {
            (view as com.tencent.smtt.sdk.WebView)
                .loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        } else {
            (view as android.webkit.WebView).loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }
    }

    fun evaluateJavascript(js: String) {
        if (usingX5) {
            (view as com.tencent.smtt.sdk.WebView).evaluateJavascript(js, null)
        } else {
            (view as android.webkit.WebView).evaluateJavascript(js, null)
        }
    }

    /** 执行 JS 并回调结果（result 为字符串，可能带引号转义）。系统/X5 共用。 */
    fun evaluateJavascriptWithResult(js: String, callback: (result: String) -> Unit) {
        if (usingX5) {
            (view as com.tencent.smtt.sdk.WebView)
                .evaluateJavascript(js) { value -> callback(value ?: "") }
        } else {
            (view as android.webkit.WebView)
                .evaluateJavascript(js) { value -> callback(value ?: "") }
        }
    }

    fun onPause() {
        if (usingX5) runCatching { (view as com.tencent.smtt.sdk.WebView).onPause() }
        else runCatching { (view as android.webkit.WebView).onPause() }
    }

    fun onResume() {
        if (usingX5) runCatching { (view as com.tencent.smtt.sdk.WebView).onResume() }
        else runCatching { (view as android.webkit.WebView).onResume() }
    }

    fun requestFocus() = view.requestFocus()

    fun removeJavascriptInterface(name: String) {
        if (usingX5) (view as com.tencent.smtt.sdk.WebView).removeJavascriptInterface(name)
        else (view as android.webkit.WebView).removeJavascriptInterface(name)
    }

    fun stopLoading() {
        if (usingX5) (view as com.tencent.smtt.sdk.WebView).stopLoading()
        else (view as android.webkit.WebView).stopLoading()
    }

    /**
     * 彻底销毁内部 WebView。
     *
     * 只调 `destroy()` 不够：页面渲染树、媒体元素与内核侧缓存会留到 GC 之前
     * （CCTV 直播 H5 播 HLS 时页面内存可达数十 MB）。另外顺序有讲究——
     * `stopLoading()` 必须在切空白页之前，否则空白导航自己会被停住、页面不卸载。
     * 顺序：停加载 → 切空白页让页面卸载 → 清历史与缓存 → 剥离子 View → destroy。
     */
    fun destroy() {
        when (val v = view) {
            is com.tencent.smtt.sdk.WebView -> runCatching {
                v.stopLoading()
                v.loadUrl("about:blank")
                v.clearHistory()
                v.clearCache(true)
                v.removeAllViews()
                v.destroy()
            }
            is android.webkit.WebView -> runCatching {
                v.stopLoading()
                v.loadUrl("about:blank")
                v.clearHistory()
                v.clearCache(true)
                v.removeAllViews()
                v.destroy()
            }
        }
    }

    // ==================== 系统 WebView 创建 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createSystemWebView(): android.webkit.WebView {
        val wv = android.webkit.WebView(context)
        configSystemSettings(wv.settings)
        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        wv.addJavascriptInterface(jsBridge, jsBridge.bridgeName)
        wv.webViewClient = MarmotSystemWebViewClient(context, onProgress, onPageLoaded)
        wv.webChromeClient = SystemChromeClient(onProgress, onPageLoaded)
        wv.requestFocus()
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createX5WebView(): com.tencent.smtt.sdk.WebView {
        val wv = com.tencent.smtt.sdk.WebView(context)
        configX5Settings(wv.settings)
        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        com.tencent.smtt.sdk.CookieManager.getInstance().setAcceptCookie(true)
        com.tencent.smtt.sdk.WebView.setWebContentsDebuggingEnabled(true)
        wv.addJavascriptInterface(jsBridge, jsBridge.bridgeName)
        wv.webViewClient = MarmotX5WebViewClient(context, onProgress, onPageLoaded)
        wv.webChromeClient = X5ChromeClient(onProgress, onPageLoaded)
        // 对标参考 setWebChromeClientExtension：X5 视频全屏/播放必须设置此扩展，否则视频无法全屏
        runCatching { wv.setWebChromeClientExtension(createX5ChromeExtension()) }
        wv.requestFocus()
        return wv
    }

    /**
     * X5 WebChromeClient 扩展（对标参考 X5WebChromeClientExtension）。
     * 方法全为空实现，但 setWebChromeClientExtension 调用本身是 X5 视频全屏功能的开关。
     */
    @Suppress("ObjectExpressionToLambda", "DeprecatedCallableAddReplaceWith")
    private fun createX5ChromeExtension(): com.tencent.smtt.export.external.extension.interfaces.IX5WebChromeClientExtension {
        return object : com.tencent.smtt.export.external.extension.interfaces.IX5WebChromeClientExtension {
            override fun getX5WebChromeClientInstance(): Any? = null
            override fun getVideoLoadingProgressView(): android.view.View? = null
            override fun onAllMetaDataFinished(
                ext: com.tencent.smtt.export.external.extension.interfaces.IX5WebViewExtension?,
                p1: java.util.HashMap<String, String>?
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
                p1: java.util.HashMap<String, String>?
            ) {}
            override fun onSavePassword(p0: String?, p1: String?, p2: String?, p3: Boolean, p4: android.os.Message?): Boolean = false
            override fun onSavePassword(
                p0: android.webkit.ValueCallback<String>?,
                p1: String?, p2: String?, p3: String?, p4: String?, p5: String?, p6: Boolean
            ): Boolean = false
            override fun onX5ReadModeAvailableChecked(p0: java.util.HashMap<String, String>?) {}
            override fun addFlashView(p0: android.view.View?, p1: android.view.ViewGroup.LayoutParams?) {}
            override fun h5videoRequestFullScreen(p0: String?) {
                Log.i(TAG, "X5 h5videoRequestFullScreen")
            }
            override fun h5videoExitFullScreen(p0: String?) {}
            override fun requestFullScreenFlash() {
                Log.i(TAG, "X5 requestFullScreenFlash")
            }
            override fun exitFullScreenFlash() {}
            override fun jsRequestFullScreen() {
                Log.i(TAG, "X5 jsRequestFullScreen")
            }
            override fun jsExitFullScreen() {}
            override fun acquireWakeLock() {}
            override fun releaseWakeLock() {}
            override fun getApplicationContex(): android.content.Context? = context.applicationContext
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
    }

    @Suppress("DEPRECATION")
    private fun configSystemSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setGeolocationEnabled(false)
        s.userAgentString = DESKTOP_UA
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = false
        }
    }

    @Suppress("DEPRECATION")
    private fun configX5Settings(s: com.tencent.smtt.sdk.WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = com.tencent.smtt.sdk.WebSettings.LOAD_DEFAULT
        // X5 的 mixedContentMode 适配可能不同，用 try-catch 兜底（0=ALLOW）
        runCatching { s.mixedContentMode = 0 }
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setGeolocationEnabled(false)
        s.userAgentString = DESKTOP_UA
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = false
        }
    }

    // ==================== ChromeClient（系统/X5） ====================

    /** 系统 WebChromeClient（含全屏视频处理）。 */
    private inner class SystemChromeClient(
        private val onProgress: (Int) -> Unit,
        private val onPageLoaded: (String) -> Unit
    ) : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            val msg = "webConsole ${consoleMessage?.messageLevel()} ${consoleMessage?.message()}"
            Log.d(TAG, msg)
            com.mytvb.core.common.log.AppLog.d(TAG, msg)
            return true
        }
        override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
            // 上报真实加载进度（1-99），驱动遮罩上的"正在加载网页 x%"文字
            onProgress(newProgress)
        }
        override fun onShowCustomView(view: android.view.View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
            Log.i(TAG, "系统 onShowCustomView")
            onShowCustomView.invoke(view)
        }
        override fun onHideCustomView() {
            Log.i(TAG, "系统 onHideCustomView")
            onHideCustomView.invoke()
        }
    }

    /** X5 WebChromeClient（含全屏视频处理）。 */
    private inner class X5ChromeClient(
        private val onProgress: (Int) -> Unit,
        private val onPageLoaded: (String) -> Unit
    ) : com.tencent.smtt.sdk.WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: com.tencent.smtt.export.external.interfaces.ConsoleMessage?): Boolean {
            val msg = "x5Console ${consoleMessage?.messageLevel()} ${consoleMessage?.message()}"
            Log.d(TAG, msg)
            com.mytvb.core.common.log.AppLog.d(TAG, msg)
            return true
        }
        override fun onProgressChanged(view: com.tencent.smtt.sdk.WebView?, newProgress: Int) {
            // 上报真实加载进度（1-99），驱动遮罩上的"正在加载网页 x%"文字
            onProgress(newProgress)
        }
        override fun onShowCustomView(view: android.view.View, callback: com.tencent.smtt.export.external.interfaces.IX5WebChromeClient.CustomViewCallback) {
            Log.i(TAG, "X5 onShowCustomView")
            onShowCustomView.invoke(view)
        }
        override fun onHideCustomView() {
            Log.i(TAG, "X5 onHideCustomView")
            onHideCustomView.invoke()
        }
    }
}
