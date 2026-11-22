package com.mytvb.feature.cctv.h5e

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mytvb.core.common.log.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * CCTV H5e 影视点播解密——离屏 WebView 方案。
 *
 * 背景（2026-11-22 定案）：native wasm2c 宿主对 VOD 大 NAL 存在尚未定位的
 * VMP 状态分叉（jsdecVOD8 算出越界地址 trap），逐层排查（黄金内存注入/时钟
 * 对齐/globals/table）均无法收敛。而官方 worker（emscripten JS glue + wasm）
 * 在真 JS 引擎里被上游参照项目（cctv-h5e-decrypt-ref，MIT）与本地浏览器
 * 双重实证：三个分片与 node CLI 输出字节级一致。
 *
 * 因此 VOD 解密改走离屏 WebView：assets/h5e/h5e_bundle.js 内嵌官方 worker
 * （H5player.json 拉取已替换为 data:URI，无网络依赖），bridge 协议：
 *   H5EBridge.beginSession() -> "ok"
 *   H5EBridge.decryptSegment(b64) -> b64（"err:" 前缀表示失败）
 *   H5EBridge.endSession() -> "ok"
 * 大流量走 base64（2.5MB 分片实测 ~300-1000ms 含编解码，JS 引擎内解密亚秒级）。
 *
 * 会话模型与上游 CLI 一致：一个视频一个 session，跨分片保持状态；
 * 换视频（新 guid/换集）调用 [endSession] 后下一次解密自动重建。
 * 直播仍走 native（CctvH5eDecryptor）——直播短会话 + 小 NAL native 一直正常。
 */
object H5eWebViewDecryptor {

    private const val TAG = "H5eWebViewDecrypt"
    private const val INIT_TIMEOUT_MS = 15_000L
    private const val SEGMENT_TIMEOUT_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var initLatch = CountDownLatch(1)
    private var initFailed: Throwable? = null
    private var sessionBegun = false
    private val requestIds = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (String) -> Unit>()

    /** 首次解密前调用（主线程之外也可）：确保 WebView 就绪 + session 建立。失败返回 false。 */
    @Synchronized
    fun ensureReady(context: Context): Boolean {
        if (initFailed != null) return false
        if (webView != null && sessionBegun) return true
        if (webView == null) {
            val appContext = context.applicationContext
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    webView = createWebView(appContext)
                    initLatch.countDown()
                } catch (t: Throwable) {
                    initFailed = t
                    initLatch.countDown()
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(10, TimeUnit.SECONDS) || initFailed != null) {
                AppLog.e(TAG, "WebView 初始化失败", initFailed)
                return false
            }
        }
        // 等 bundle（3.1MB 含内嵌 wasm）解析完成、bridge 可用
        if (!waitForBridge(INIT_TIMEOUT_MS)) {
            AppLog.e(TAG, "H5EBridge 未就绪（bundle 加载失败？）")
            initFailed = IllegalStateException("bridge not ready")
            return false
        }
        // session 建立（JS 侧 InitPlayer + H5player.json data:URI 拉取，~600ms）
        if (!sessionBegun) {
            val result = evaluateBlocking("H5EBridge.beginSession()", INIT_TIMEOUT_MS)
            if (result != "ok") {
                AppLog.e(TAG, "beginSession 失败: $result")
                initFailed = IllegalStateException("beginSession: $result")
                return false
            }
            sessionBegun = true
            AppLog.i(TAG, "WebView 解密会话就绪")
        }
        return true
    }

    /**
     * 解密一段 VOD TS 分片。返回解密后的字节数组；失败（初始化/超时/JS 错误）返回 null，
     * 调用方走既有回退链（native → WebView 播放兜底）。
     */
    fun decryptVodSegment(context: Context, segment: ByteArray): ByteArray? {
        if (!ensureReady(context)) return null
        val id = requestIds.getAndIncrement()
        val b64In = java.util.Base64.getEncoder().encodeToString(segment)
        val js = "(function(){var id=$id;H5EBridge.decryptSegment(${
            jsStringLiteral(b64In)
        }).then(function(out){H5eNative.onResult(id,out)}).catch(function(e){H5eNative.onResult(id,'err:'+(e&&e.message||e))});})()"
        val result = evaluateBlockingJs(id, js, SEGMENT_TIMEOUT_MS) ?: run {
            pending.remove(id)
            return null
        }
        if (result.startsWith("err:")) {
            AppLog.e(TAG, "decryptSegment 失败: ${result.take(200)}")
            return null
        }
        return try {
            java.util.Base64.getDecoder().decode(result)
        } catch (t: Throwable) {
            AppLog.e(TAG, "解密输出解码失败", t)
            null
        }
    }

    /** 换视频/退出播放时调用：结束会话（下一次解密自动重建 session）。 */
    @Synchronized
    fun endSession() {
        if (!sessionBegun) return
        sessionBegun = false
        val wv = webView ?: return
        mainHandler.post {
            runCatching { wv.evaluateJavascript("H5EBridge.endSession().then(function(){H5eNative.onResult(-1,'ok')})", null) }
        }
        AppLog.i(TAG, "会话已结束（下次解密重建）")
    }

    /** 释放 WebView（播放器销毁时可选调用；保持常驻可省去下次初始化开销）。 */
    @Synchronized
    fun release() {
        sessionBegun = false
        val wv = webView
        webView = null
        initFailed = null
        if (wv != null) mainHandler.post { runCatching { wv.destroy() } }
    }

    /** WebView 是否可用（车机等无 WebView 环境返回 false，调用方直接走 native/回退）。 */
    fun isWebViewAvailable(context: Context): Boolean = runCatching {
        android.webkit.WebView.getCurrentWebViewPackage() != null
    }.getOrDefault(true)

    // ---- 内部 ----

    /** 轮询 H5EBridge 就绪（bundle 异步加载 + wasm instantiate）。 */
    private fun waitForBridge(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var probeCount = 0
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val latch = CountDownLatch(1)
            var ready = false
            var diag = ""
            mainHandler.post {
                val wv = webView
                if (wv == null) {
                    diag = "no-webview"
                    latch.countDown()
                    return@post
                }
                runCatching {
                    wv.evaluateJavascript(
                        "(typeof H5EBridge !== 'undefined' && typeof H5EBridge.beginSession === 'function')" +
                            "+'|'+document.readyState+'|'+(document.scripts?document.scripts.length:-1)"
                    ) { v ->
                        diag = v ?: "null"
                        // evaluateJavascript 返回 JSON 编码字符串（带双引号），剥掉再判
                        ready = v?.trim('"')?.startsWith("true") == true
                        latch.countDown()
                    }
                }.onFailure {
                    diag = "eval-fail:${it.message}"
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
            probeCount++
            if (probeCount <= 3 || probeCount % 20 == 0) {
                AppLog.i(TAG, "bridgeProbe #$probeCount -> $diag")
            }
            if (ready) return true
            Thread.sleep(150)
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            // 离屏解密专用：无渲染需求，禁掉一切网络与定位
            blockNetworkLoads = false // bundle 内 H5player.json 已是 data:URI，无真实网络
            mediaPlaybackRequiresUserGesture = false
        }
        wv.setBackgroundColor(0)
        wv.layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
        wv.layout(0, 0, 1, 1)
        wv.addJavascriptInterface(Bridge(), "H5eNative")
        wv.loadUrl("file:///android_asset/h5e/h5e_decrypt.html")
        return wv
    }

    /** evaluateJavascript 的 Promise 版：requestId 回调对齐（beginSession 等小调用）。 */
    private fun evaluateBlocking(expr: String, timeoutMs: Long): String? {
        // 一次性小调用：直接用负 id 通道
        val id = -requestIds.getAndIncrement()
        val latch = CountDownLatch(1)
        var out: String? = null
        pending[id] = { r -> out = r; latch.countDown() }
        val posted = mainHandler.post {
            runCatching {
                webView?.evaluateJavascript(
                    "$expr.then(function(out){H5eNative.onResult($id,out)})", null)
            }.onFailure {
                out = "err:${it.message}"
                latch.countDown()
            }
        }
        if (!posted || !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(id)
            return null
        }
        return out
    }

    private fun evaluateBlockingJs(id: Int, js: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        pending[id] = { r -> out = r; latch.countDown() }
        val posted = mainHandler.post {
            runCatching { webView?.evaluateJavascript(js, null) }
                .onFailure {
                    out = "err:${it.message}"
                    latch.countDown()
                }
        }
        if (!posted || !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return null
        }
        return out
    }

    private fun jsStringLiteral(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private class Bridge {
        @JavascriptInterface
        fun onResult(id: Int, result: String) {
            pending.remove(id)?.invoke(result)
        }
    }
}
