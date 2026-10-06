package com.mytvb.ui.activity

import com.mytvb.R
import com.mytvb.core.common.json.GsonHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.mytvb.feature.marmot.quality.CctvQualityProvider
import com.mytvb.feature.marmot.quality.LiveQualityProvider
import com.mytvb.feature.marmot.quality.MarmotQualityProvider
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.ext.toast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.databinding.ActivityMarmotLiveBinding
import com.mytvb.feature.marmot.MarmotCloudUpdate
import com.mytvb.feature.marmot.MarmotJsBridge
import com.mytvb.feature.marmot.MarmotLiveData
import com.mytvb.feature.marmot.domain.HzItem
import com.mytvb.feature.marmot.domain.MarmotModels.Live
import com.mytvb.feature.marmot.domain.MarmotModels.Vod
import com.mytvb.ui.adapter.QualityAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV 直播主界面（对标参考 utao `LiveActivity.java`，基于系统 WebView）。
 *
 * 核心能力：
 * 1. **播放**：系统 WebView 加载官网直播页（如 tv.cctv.com），[MarmotWebViewClient] 注入 tv.user.js 劫持播放。
 * 2. **频道列表**（确认键弹）：[showChannelMenu] 显示分组+频道，D-pad 左右切分组、上下选频道。
 * 3. **画质选择**（菜单键弹）：[showQualityMenu] 横向列表，数据来自页面 JS 上报的 videoQuality。
 * 4. **切台**：上下左右键触发 [goNext]，调 [MarmotLiveData.liveNext] 四向环形切台，延迟 1s 加载防抖。
 *
 * 按键映射（按需求，与参考不同）：
 * - **确认键(OK/ENTER)** → [showChannelMenu] 频道列表
 * - **菜单键(MENU)** → [showQualityMenu] 画质选择
 * - **上下左右** → [goNext] 切台 + 转发页面脚本
 * - **返回键** → 关闭浮层/退出
 */
class MarmotLiveActivity : BaseActivity<ActivityMarmotLiveBinding>() {

    companion object {
        private const val TAG = "MarmotLiveActivity"
        /** DataStore key：上次观看频道的 URL（下次进直播恢复）。 */
        private const val KEY_LAST_CHANNEL_URL = "marmot_last_channel_url"
        /** 播放UI无操作自动隐藏延时（对齐普通视频播放器控制栏 5s）。 */
        private const val CONTROLLER_HIDE_DELAY_MS = 5_000L

        /** 启动入口。 */
        fun start(context: Context) {
            // 青少年模式：休息期间拦截 TV 直播入口
            com.mytvb.core.common.content.TeenModeTimer.consumeBlockReason(context)?.let {
                context.toast(it)
                return
            }
            context.startActivity(Intent(context, MarmotLiveActivity::class.java))
        }

        /** CCTV 官方直播播放器脚本（createLivePlayer 来源）。 */
        private val CCTV_PLAYER_SCRIPTS = listOf(
            "https://r.img.cctvpic.com/photoAlbum/templet/js/jquery-1.7.2.min.js",
            "https://js.player.cntv.cn/creator/swfobject.js",
            "https://js.player.cntv.cn/creator/liveplayer.js"
        )

        /** 从 url 提取 CCTV 频道 id：`https://tv.cctv.com/live/cctv13/` → `cctv13`；非 CCTV 返回 null。 */
        fun extractCctvChannelId(url: String): String? =
            Regex("""tv\.cctv\.com/live/([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = GsonHolder.DEFAULT

    /** 青少年模式：直播期间每 15 秒结算观看时长，达上限触发休息退出。 */
    private val teenModeTicker = object : Runnable {
        override fun run() {
            com.mytvb.core.common.content.TeenModeTimer.tick()
            if (com.mytvb.core.common.content.TeenModeTimer.isResting()) {
                val restMin = com.mytvb.core.common.content.TeenModeTimer.getRestLimitMin().coerceAtLeast(1)
                android.widget.Toast.makeText(this@MarmotLiveActivity, getString(R.string.activity_teen_rest_needed_format, restMin), android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            // 公益广告触发：启动锁死播放，原 CCTV 进入后台暂停，回来 onResume 重新加载当前频道
            if (com.mytvb.core.common.content.TeenModeTimer.checkAndConsumePsasTrigger()) {
                com.mytvb.core.common.content.PsasRepository.launchRandomPsas(this@MarmotLiveActivity)
            }
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    /** 当前播放频道。 */
    private var currentVod: Vod? = null
    /** 全部分组（频道列表数据）。 */
    private val provinces = ArrayList<Live>()
    /** 频道列表当前选中的分组索引。 */
    private var currentProvinceIndex = 0
    /** 频道列表是否显示。 */
    private var isChannelMenuShowing = false
    /** 画质列表是否显示。 */
    private var isQualityMenuShowing = false
    /** 页面上报的画质数据（JSON 字符串）。 */
    private var videoQualityData: String? = null
    /** 已自动应用最高画质的频道 URL（避免重复执行）。 */
    private var lastQualityAppliedUrl: String? = null
    /** CCTV 画质 Provider（写死 4 档，createLivePlayer 的 br 参数来源）。 */
    private val cctvQualityProvider = CctvQualityProvider { playCurrent() }
    /** 两次返回退出计时（对标 PlayerActivity.setupBackHandler）。 */
    private var exitTime: Long = 0
    private val exitInterval = 2000L
    /** 播放UI（顶部栏+底部控制栏）是否显示。 */
    private var isControllerShowing = false
    /** 播放UI按钮的 OK 已在 DOWN 消费，对应 UP 直接吞掉（防误触 performClick）。 */
    private var consumeNextOkUp = false
    /** 播放UI无操作 5s 自动收起（焦点在按钮上时不计时）。 */
    private val hideControllerRunnable = Runnable { hideController() }

    /** 切台防抖：延迟 1s 后真正加载（避免快速连按）。 */
    private val switchChannelRunnable = Runnable {
        // 切台走 playCurrent（统一保存 URL 记录，以便下次恢复）
        playCurrent()
    }

    private var webEngine: com.mytvb.feature.marmot.web.WebEngine? = null

    /** JS 桥回调实现。 */
    private val jsCallbacks = object : MarmotJsBridge.Callbacks {
        override fun onVideoQuality(rawData: String) {
            videoQualityData = rawData
            AppLog.i(TAG, "收到画质数据（${rawData.length} 字符）: ${rawData.take(120)}")
            Log.i(TAG, "收到画质数据（${rawData.length} 字符）")
            // 自动切换到最高画质：JS 桥回调在子线程，必须切主线程执行 evaluateJavascript
            runOnUiThread { applyBestQualityOnce(rawData) }
        }
        override fun onEvalJs(js: String) {
            runOnUiThread { webEngine?.evaluateJavascript(js) }
        }
        override fun onSimulateKey(keyCodeStr: String) {
            // 页面请求模拟按键（数字），参考 BaseActivity.keyEventAll
            try {
                val keyCode = keyCodeStr.toInt()
                Thread {
                    try {
                        val inst = android.app.Instrumentation()
                        inst.sendKeyDownUpSync(keyCode)
                    } catch (e: Exception) {
                        Log.w(TAG, "模拟按键失败: $keyCode", e)
                    }
                }.start()
            } catch (e: NumberFormatException) {
                // key 事件支持映射（SPACE/F），参考 keyCodeMap
                val mapped = when (keyCodeStr) {
                    "SPACE" -> KeyEvent.KEYCODE_SPACE
                    "F" -> KeyEvent.KEYCODE_F
                    else -> return
                }
                Thread {
                    try {
                        android.app.Instrumentation().sendKeyDownUpSync(mapped)
                    } catch (e: Exception) { }
                }.start()
            }
        }
    }
    private lateinit var jsBridge: MarmotJsBridge

    override fun getViewBinding(): ActivityMarmotLiveBinding =
        ActivityMarmotLiveBinding.inflate(layoutInflater)

    @SuppressLint("SetJavaScriptEnabled")
    override fun initView() {
        jsBridge = MarmotJsBridge(this, jsCallbacks)
        // 青少年模式：启动观看时长定时器
        mainHandler.postDelayed(teenModeTicker, 15_000L)
        initWebView()
        setupChannelMenu()
        setupQualityMenu()
        setupMenuDismiss()
        setupPlayerController()
    }

    /** 创建并配置 WebView（对标参考 `BaseActivity.initWebView`）。
     *  按 X5 内核是否加载选择系统 WebView 或 X5 WebView（WebEngine 封装）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        // 对标参考 APP：已安装 X5（installLocalTbsCore 成功过）就直接用 X5 WebView，
        // 不查 canLoadX5（内核加载是异步的，参考 APP 也是直接 new X5 WebView 让 SDK 自行加载）。
        val useX5 = com.mytvb.feature.marmot.x5.X5TbsDownloader.isInstalled(this)
        Log.i(TAG, "WebView 内核：${if (useX5) "X5" else "系统"}")
        val engine = com.mytvb.feature.marmot.web.WebEngine(
            context = this,
            useX5 = useX5,
            jsBridge = jsBridge,
            onProgress = { progress ->
                // 遮罩显示期间保持加载转圈可见（阶段文字由 videoCoverPollRunnable 按 <video> 是否创建判定）
                if (binding.videoCover.visibility == View.VISIBLE) {
                    binding.progressBar.visibility = View.VISIBLE
                } else {
                    binding.progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                }
            },
            onPageLoaded = { url ->
                // 注意：这里不再调用 showLiveName。
                // 频道名提示已在 goNext / playCurrent / 频道列表选台时显示。
                // 若在此再次显示，实机 WebView 加载慢时 onPageLoaded 可能在
                // showLiveName 的 2s 隐藏计时触发后才回调，导致「频道名消失后又出现」的闪断。
                // 因此页面加载完成仅负责驱动遮罩轮询，不再重复显示频道名。
                // 页面加载完成：遮罩下的阶段文字由 videoCoverPollRunnable 按 <video> 是否创建判定，
                // 这里无需干预，轮询已在 showVideoCover 启动
                if (binding.videoCover.visibility == View.VISIBLE) {
                    startVideoCoverPolling()
                }
            },
            onShowCustomView = { customView ->
                // 全屏视频：把播放器视图加到 fullscreenContainer，隐藏 WebView 容器
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.addView(
                    customView,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webviewWrapper.visibility = View.GONE
                // 注意：不再在此移除遮罩。onShowCustomView 可能在视频刚进全屏但尚未真正出画面时过早触发，
                // 过早移除会露出仍在加载的网页。遮罩移除统一由 videoCoverPollRunnable 检测到
                // <video> 真正播放（currentTime>0 连续 2 次）或 60s 兜底来主导。
            },
            onHideCustomView = {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webviewWrapper.visibility = View.VISIBLE
            }
        )
        webEngine = engine
        binding.webviewWrapper.addView(
            engine.view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        engine.requestFocus()
    }

    override fun initData() {
        // 协程：云端更新 + 加载频道 + 播放首个频道
        CoroutineScope(Dispatchers.Main).launch {
            // 1. 云端更新 tv-web（静默失败，用内置种子兜底）
            val updated = withContext(Dispatchers.IO) {
                runCatching { MarmotCloudUpdate.checkAndUpdateRes(this@MarmotLiveActivity) }
                    .onFailure { AppLog.e(TAG, "云端更新异常: ${it.javaClass.simpleName}: ${it.message}", it) }
                    .getOrDefault(false)
            }
            AppLog.i(TAG, "initData: 云端更新结果=$updated")
            // 2. 加载频道表
            val loaded = withContext(Dispatchers.IO) { MarmotLiveData.load(this@MarmotLiveActivity) }
            AppLog.i(TAG, "initData: 频道表加载结果=$loaded")
            if (!loaded) {
                AppLog.e(TAG, "initData: 频道数据加载失败，请检查网络/明文流量权限/filesDir 是否损坏")
                Toast.makeText(this@MarmotLiveActivity, R.string.activity_channel_load_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            provinces.clear()
            // 方案 C（CCTV 优先）：只保留能用 createLivePlayer 播放的 CCTV 频道
            // （url 含 tv.cctv.com/live/）。省台/央视频等暂不显示（后续逐站点维护再加）。
            // PR #53 曾在此「放开全部频道」，属贡献者自述的逐站点实测状态，暂不采纳，
            // 待实测收敛为白名单后再放开；下方 playCurrent 的 Marmot 整页劫持分支与
            // 全黑遮罩机制保留，为后续放开做预留。
            val allLives = MarmotLiveData.getLives()
            var cctvIndex = 0
            for (live in allLives) {
                // 筛选该分组下 url 是 tv.cctv.com/live/ 的频道，重建 Vod 的导航索引
                val cctvVods = live.vods.filter { extractCctvChannelId(it.url) != null }
                if (cctvVods.isEmpty()) continue
                // 构造只含 CCTV 频道的分组副本（重新编 tagIndex/detailIndex）
                val filteredLive = Live(tag = live.tag, name = live.name, index = cctvIndex,
                    vods = cctvVods.toMutableList())
                cctvVods.forEachIndexed { j, vod ->
                    vod.tagIndex = cctvIndex
                    vod.detailIndex = j
                    vod.key = "${cctvIndex}_$j"
                }
                provinces.add(filteredLive)
                cctvIndex++
            }
            AppLog.i(TAG, "initData: CCTV 频道过滤后 ${provinces.size} 个分组，" +
                "${provinces.sumOf { it.vods.size }} 个频道")
            if (provinces.isEmpty()) {
                Toast.makeText(this@MarmotLiveActivity, R.string.activity_no_cctv_channels, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 3. 恢复上次观看频道：优先按保存的 URL 反查（必须是 CCTV 频道），否则回退首个 CCTV 频道
            val lastUrl = appSettings.getCachedString(KEY_LAST_CHANNEL_URL, null)
            currentVod = if (!lastUrl.isNullOrEmpty() && extractCctvChannelId(lastUrl) != null) {
                provinces.flatMap { it.vods }.firstOrNull { it.url == lastUrl }
                    ?: provinces.first().vods.first()
            } else {
                provinces.first().vods.first()
            }
            if (currentVod == null) {
                Toast.makeText(this@MarmotLiveActivity, R.string.activity_no_channels, Toast.LENGTH_SHORT).show()
                return@launch
            }
            playCurrent()
            Toast.makeText(this@MarmotLiveActivity, R.string.activity_remote_switch_channel_tip, Toast.LENGTH_SHORT).show()
        }
    }

    /** 播放当前频道，并记录 URL 以便下次恢复（对标参考 `HistoryDaoX.updateChannel`）。 */
    private fun playCurrent() {
        val vod = currentVod ?: return
        mainHandler.removeCallbacks(switchChannelRunnable)
        // CCTV 直播频道（tv.cctv.com/live/）：用 createLivePlayer 干净 HTML 播放（画质可切，对标 v1.5.8）；
        // 其它频道：维持原 Marmot 方式（加载完整页面 + tv.user.js 劫持）。
        val channelId = extractCctvChannelId(vod.url)
        if (channelId != null) {
            val baseUrl = "https://tv.cctv.com/live/$channelId/m/" +
                "#${com.mytvb.feature.marmot.web.MarmotSystemWebViewClient.MYBILI_CCTV_NATIVE_MARKER}"
            val html = buildCctvPlayerHtml(channelId, cctvQualityProvider.current.br)
            AppLog.i(TAG, "playCurrent: CCTV 原生播放 channelId=$channelId quality=${cctvQualityProvider.current.label}")
            // CCTV 自构 HTML 无网页闪现，确保清掉可能残留的遮罩
            hideVideoCover()
            webEngine?.loadDataWithBaseURL(baseUrl, html)
        } else {
            AppLog.i(TAG, "playCurrent: Marmot 模式 url=${vod.url}")
            // 非 CCTV 整页劫持：先显示全黑遮罩，并确保遮罩真正绘制上屏后再加载官网网页，
            // 避免 WebView 首帧快于遮罩布局导致官网网页闪现
            showVideoCover {
                webEngine?.loadUrl(vod.url)
            }
        }
        showLiveName(vod.name)
        // 播放UI若还显示着（刷新按钮触发），同步刷新控制栏标题
        if (isControllerShowing) updateControllerTitle()
        // 记录上次观看频道（IO 写入，不阻塞）
        appSettings.putStringAsync(KEY_LAST_CHANNEL_URL, vod.url)
    }

    /**
     * 构造 CCTV 直播播放 HTML（对标 v1.5.8 `CctvPlayerActivity.buildPlayerHtml`）。
     *
     * 加载 CCTV 官方 liveplayer.js，调 `createLivePlayer({t: channelId, br: 画质})` 初始化播放器。
     * [br] 参数直接指定画质码率（1080/720/540/360），切画质 = 改 br 重建（真正切流，区别于 hzChoose 的 click 假切）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildCctvPlayerHtml(channelId: String, br: String): String {
        val scriptTags = CCTV_PLAYER_SCRIPTS.joinToString("\n") { url ->
            """              <script src="$url"></script>"""
        }
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
              <title>MyTVB $channelId</title>
              <style>
                html,body,#player{width:100%;height:100%;margin:0;padding:0;background:#000;overflow:hidden;}
                #player{position:fixed;left:0;top:0;z-index:1;}
                video,canvas,object,embed,iframe{
                  position:fixed!important;left:0!important;top:0!important;
                  width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;
                  object-fit:contain!important;background:#000!important;
                }
              </style>
$scriptTags
            </head>
            <body>
              <div id="player"></div>
              <script>
                (function() {
                  var channel = '$channelId';
                  function size() {
                    return {
                      w: Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0, 1280),
                      h: Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0, 720)
                    };
                  }
                  function start() {
                    if (typeof createLivePlayer !== 'function') {
                      console.log('mybili cctv wait createLivePlayer');
                      setTimeout(start, 300);
                      return;
                    }
                    var s = size();
                    var playerParas = {
                      divId: 'player', w: s.w, h: s.h, t: channel,
                      isAutoPlay: 'true', ruleVisible: 'false', br: '$br',
                      posterImg: '', isLive4k: 'false', isHttps: 'true', wmode: 'opaque',
                      hasBarrage: 'false', playerType: 'hw', webFullScreenOn: 'false',
                      isLeftBottom: 'false', jumpToApp: 'false', others: ''
                    };
                    console.log('mybili cctv createLivePlayer ' + channel + ' ' + s.w + 'x' + s.h + ' br=$br');
                    createLivePlayer(playerParas);
                  }
                  window.addEventListener('resize', function() {
                    var v = document.getElementsByTagName('video')[0];
                    if (v) { v.play && v.play().catch(function(){}); }
                  });
                  start();
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** 短暂显示频道名（2 秒后清除）。同一频道被 playCurrent / onPageLoaded 等多次调用时会重置计时，不会中途闪断。 */
    private fun showLiveName(name: String) {
        mainHandler.removeCallbacks(hideLiveNameRunnable)
        binding.liveName.text = name
        binding.liveName.visibility = View.VISIBLE
        mainHandler.postDelayed(hideLiveNameRunnable, 2000)
    }

    private val hideLiveNameRunnable = Runnable { binding.liveName.visibility = View.GONE }

    // ==================== 全黑遮罩（非 CCTV 整页劫持加载期间盖住官网网页） ====================

    /** 遮罩最常驻时长：超过后强制移除，防止视频迟迟不出导致黑屏卡死。 */
    private val videoCoverTimeoutMs = 60_000L

    /**
     * 显示全黑遮罩盖住 WebView，避免非 CCTV 整页劫持加载期间闪现官网网页。
     *
     * 关键：用 [android.view.View.post] 确保遮罩**真正布局并绘制上屏**后再执行 [afterShown]
     * （通常是加载官网网页），从而彻底盖住网页的加载/渲染过程，杜绝首帧闪现。
     *
     * 遮罩显示期间同时显示居中的加载进度转圈（[binding.progressBar]），给用户明确的加载提示。
     * 视频就绪（[hideVideoCover] 由轮询检测到播放触发）或超时后自动移除。
     */
    private fun showVideoCover(afterShown: (() -> Unit)? = null) {
        mainHandler.removeCallbacks(hideVideoCoverRunnable)
        if (binding.videoCover.visibility != View.VISIBLE) {
            binding.videoCover.visibility = View.VISIBLE
        }
        // 显示加载进度转圈与状态文字提示（位于遮罩之上）
        binding.progressBar.visibility = View.VISIBLE
        updateVideoCoverText(getString(R.string.activity_initializing))
        // 定时兜底：防止某些源视频迟迟不出导致遮罩一直黑屏
        mainHandler.postDelayed(hideVideoCoverRunnable, videoCoverTimeoutMs)
        // 遮罩绘制上屏后再执行 afterShown（loadUrl），确保网页全程在遮罩背后渲染
        binding.videoCover.post {
            if (binding.videoCover.visibility == View.VISIBLE) {
                afterShown?.invoke()
                // 遮罩期间持续轮询，按 <video> 是否创建/播放判定"正在加载网页/正在加载视频/就绪移除"
                startVideoCoverPolling()
            }
        }
    }

    /** 移除遮罩（视频就绪或超时），并停止视频播放轮询。 */
    private fun hideVideoCover() {
        mainHandler.removeCallbacks(hideVideoCoverRunnable)
        mainHandler.removeCallbacks(videoCoverPollRunnable)
        if (binding.videoCover.visibility != View.GONE) {
            binding.videoCover.visibility = View.GONE
        }
        // 遮罩移除时隐藏加载进度与状态文字
        binding.progressBar.visibility = View.GONE
        binding.videoCoverText.visibility = View.GONE
    }

    /** 更新遮罩层上的加载状态文字（仅遮罩显示期间生效）。 */
    private fun updateVideoCoverText(text: String) {
        if (binding.videoCover.visibility != View.VISIBLE) return
        if (binding.videoCoverText.visibility != View.VISIBLE) {
            binding.videoCoverText.visibility = View.VISIBLE
        }
        binding.videoCoverText.text = text
    }

    private val hideVideoCoverRunnable = Runnable { hideVideoCover() }

    /** 轮询检测页面 `<video>` 是否真正开始播放（持续播放），一旦确认即移除遮罩（每 500ms 一次）。 */
    private fun startVideoCoverPolling() {
        videoCoverPlayingCount = 0
        videoCoverLastTime = 0.0
        mainHandler.removeCallbacks(videoCoverPollRunnable)
        mainHandler.post(videoCoverPollRunnable)
    }

    /** 视频连续播放检测计数（每次启动轮询重置）。 */
    private var videoCoverPlayingCount = 0

    /** 上一次轮询到的 <video> currentTime，用于判断时间轴是否在增长（真正在播放），避免静止误判。 */
    private var videoCoverLastTime = 0.0

    private val videoCoverPollRunnable: Runnable = object : Runnable {
        override fun run() {
            // 遮罩已移除则停止轮询
            if (binding.videoCover.visibility == View.GONE) return
            // 返回 <video> 的 currentTime：-1=无 video；0=有 video 未播；>0=时间轴（判断是否增长）
            webEngine?.evaluateJavascriptWithResult(
                "(function(){try{var v=document.querySelector('video');" +
                    "if(!v){return '-1';}" +
                    "return (v.currentTime||0)+'';" +
                    "}catch(e){return '-1';}})();"
            ) { result ->
                val timeStr = result?.trim()?.removeSurrounding("\"")
                val time = timeStr?.toDoubleOrNull() ?: -1.0
                when {
                    // 页面尚未创建 <video>，仍处于网页加载阶段
                    time < 0 -> {
                        videoCoverPlayingCount = 0
                        videoCoverLastTime = 0.0
                        updateVideoCoverText(getString(R.string.activity_loading_web))
                    }
                    // 已创建 <video> 但尚未真正播放（currentTime 未前进）
                    time == 0.0 || time <= videoCoverLastTime -> {
                        videoCoverPlayingCount = 0
                        videoCoverLastTime = time
                        updateVideoCoverText(getString(R.string.activity_loading_video))
                    }
                    // 时间轴在增长，确认真正在播放；连续多次才移除遮罩，避免骨架/静止 video 误判
                    else -> {
                        videoCoverLastTime = time
                        if (++videoCoverPlayingCount >= 2) {
                            AppLog.i(TAG, "视频时间轴持续增长，移除遮罩")
                            hideVideoCover()
                            return@evaluateJavascriptWithResult
                        }
                    }
                }
                if (binding.videoCover.visibility != View.GONE) {
                    mainHandler.postDelayed(videoCoverPollRunnable, 500)
                }
            }
        }
    }

    // ==================== 频道列表浮层（确认键弹出）====================

    /** 频道列表适配器（RecyclerView，对标截图样式）。 */
    private val channelAdapter = com.mytvb.ui.adapter.ChannelAdapter { vod ->
        if (vod.url.isNotEmpty()) {
            currentVod = vod
            playCurrent()
            hideChannelMenu()
        }
    }
    /** 分组列表适配器（左排）。点击/确认展开右排，焦点留在左排。 */
    private val groupAdapter = com.mytvb.ui.adapter.GroupAdapter { index, live ->
        currentProvinceIndex = index
        // 展开右排频道列表（不切焦点，焦点留在左排当前分组）
        channelAdapter.currentPlayingUrl = currentVod?.url
        channelAdapter.submitList(live.vods)
    }

    /** 初始化频道列表控件（双排：groupList + channelList）。 */
    private fun setupChannelMenu() {
        binding.groupList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.groupList.adapter = groupAdapter
        binding.channelList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        // 互相关联：OnKeyListener 里跨列表切焦点用
        groupAdapter.channelRecyclerView = binding.channelList
        channelAdapter.groupRecyclerView = binding.groupList
        channelAdapter.groupAdapter = groupAdapter
        // 空白区点击关闭
        binding.menuBlankArea.setOnClickListener { hideChannelMenu() }
    }

    /** 显示频道列表浮层（双排）。 */
    private fun showChannelMenu() {
        if (provinces.isEmpty()) {
            Toast.makeText(this, R.string.activity_channel_loading, Toast.LENGTH_SHORT).show()
            return
        }
        isChannelMenuShowing = true
        binding.menuContainer.visibility = View.VISIBLE
        // 定位到当前频道所在分组
        currentVod?.let { currentProvinceIndex = it.tagIndex }
        // 初始化左排分组列表（滚动定位当前分组，不抢焦点）
        groupAdapter.currentSelected = currentProvinceIndex
        groupAdapter.submitList(provinces) {
            binding.groupList.scrollToPosition(currentProvinceIndex)
        }
        // 右排显示当前分组频道，焦点给当前播放频道（用户直接选频道）
        if (currentProvinceIndex < provinces.size) {
            val live = provinces[currentProvinceIndex]
            channelAdapter.currentPlayingUrl = currentVod?.url
            channelAdapter.submitList(live.vods) {
                binding.channelList.post {
                    val idx = if (currentVod != null && currentVod!!.tagIndex == currentProvinceIndex) {
                        currentVod!!.detailIndex.coerceAtLeast(0).coerceAtMost(live.vods.lastIndex)
                    } else 0
                    binding.channelList.scrollToPosition(idx)
                    // 焦点给右排当前播放频道
                    binding.channelList.layoutManager?.findViewByPosition(idx)?.requestFocus()
                }
            }
        }
    }

    /** 隐藏频道列表浮层。 */
    private fun hideChannelMenu() {
        isChannelMenuShowing = false
        binding.menuContainer.visibility = View.GONE
    }

    // ==================== 画质选择浮层（菜单键弹出）====================

    private fun setupQualityMenu() {
        binding.qualityList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.qualityContainer.setOnClickListener { hideQualityMenu() }
    }

    /**
     * 显示画质选择浮层。
     *
     * 按当前频道 url 路由到对应的 [LiveQualityProvider]（方案 C：逐播放源维护画质）：
     * - CCTV（tv.cctv.com/live/）→ [CctvQualityProvider]（写死 4 档，createLivePlayer br 重建）
     * - 其它源 → 沿用 Marmot 方式（页面上报的 videoQualityData + hzChoose 脚本）
     *
     * 菜单 UI（横向列表 + 当前项高亮 + 焦点定位）对所有源统一，只有「可用画质/当前项/切换动作」三件事按源不同。
     */
    private fun showQualityMenu() {
        val provider = resolveQualityProvider()
        val items = provider.availableQualities()
        AppLog.i(TAG, "showQualityMenu: provider=${provider.javaClass.simpleName} 画质 ${items.size} 项")
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.activity_no_quality_options, Toast.LENGTH_SHORT).show()
            return
        }
        val currentIdx = provider.currentQualityIndex(items)
        isQualityMenuShowing = true
        binding.qualityContainer.visibility = View.VISIBLE
        binding.qualityList.adapter = QualityAdapter(items) { item ->
            val switched = provider.switchTo(item) { AppLog.i(TAG, "画质切换：${item.name}") }
            if (!switched) {
                AppLog.w(TAG, "画质切换：${item.name} 无效或与当前相同")
            }
            hideQualityMenu()
        }
        // 焦点稳定定位到当前画质项：setAdapter 后 itemView 尚未布局，单次 post 可能落空，
        // 用 OnGlobalLayoutListener 等布局真正完成后再 scrollToPosition + requestFocus。
        binding.qualityList.requestFocus()
        binding.qualityList.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    binding.qualityList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (!isQualityMenuShowing) return // 菜单已关闭，不抢焦点
                    binding.qualityList.scrollToPosition(currentIdx)
                    binding.qualityList.post {
                        binding.qualityList.layoutManager?.findViewByPosition(currentIdx)?.requestFocus()
                            ?: binding.qualityList.requestFocus()
                    }
                }
            }
        )
    }

    /**
     * 按当前频道 url 解析画质 Provider。
     * 新增播放源时在此加一个分支（url 域名匹配 → 对应 Provider）。
     */
    private fun resolveQualityProvider(): LiveQualityProvider {
        val url = currentVod?.url
        if (url != null && extractCctvChannelId(url) != null) {
            return cctvQualityProvider
        }
        // 兜底：Marmot 方式（页面上报的 videoQualityData + action 脚本）
        return MarmotQualityProvider({ videoQualityData }) { action ->
            webEngine?.evaluateJavascript(action)
        }
    }

    private fun hideQualityMenu() {
        isQualityMenuShowing = false
        binding.qualityContainer.visibility = View.GONE
    }

    /**
     * 自动切换到最高画质（第一项），每个频道只执行一次（对标"默认最好画质"）。
     * 页面上报 videoQuality 后调用，执行第一项（通常"超清"/"1080P"）的 action。
     */
    private fun applyBestQualityOnce(rawData: String) {
        val url = currentVod?.url ?: return
        // 同一频道已应用过，跳过（切台后 URL 变化才会重新应用）
        if (url == lastQualityAppliedUrl) return
        // 只对非 CCTV 源（Marmot 方式）自动应用最高画质；CCTV 源 createLivePlayer 已按 br 初始化
        if (extractCctvChannelId(url) != null) return
        val provider = resolveQualityProvider()
        val items = provider.availableQualities()
        if (items.isEmpty()) return
        // 第一项通常是最高画质
        val best = items[0]
        provider.switchTo(best) {
            AppLog.i(TAG, "自动切换最高画质: ${best.name}")
            Log.i(TAG, "自动切换最高画质: ${best.name}")
            lastQualityAppliedUrl = url
        }
    }

    /** 点击空白处关闭浮层。 */
    private fun setupMenuDismiss() {
        binding.marmotRoot.setOnClickListener {
            if (isChannelMenuShowing) hideChannelMenu()
            if (isQualityMenuShowing) hideQualityMenu()
        }
    }

    // ==================== 播放UI（顶栏返回+时钟、底部控制栏，呼出/自动隐藏对齐普通视频播放器） =====================

    private fun setupPlayerController() {
        // 返回：与普通视频播放器 button_back 一致，单击直接退出播放器；触摸点击带按压缩放反馈
        binding.buttonBack.touchPressFeedback = true
        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonChannel.setOnClickListener {
            hideController()
            showChannelMenu()
        }
        binding.buttonQuality.setOnClickListener {
            hideController()
            showQualityMenu()
        }
        binding.buttonRefresh.setOnClickListener {
            hideController()
            playCurrent()
        }
        // 焦点进入按钮后取消自动隐藏，避免操作中途控制栏消失
        val cancelAutoHide = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) mainHandler.removeCallbacks(hideControllerRunnable)
        }
        binding.buttonBack.onFocusChangeListener = cancelAutoHide
        binding.buttonChannel.onFocusChangeListener = cancelAutoHide
        binding.buttonQuality.onFocusChangeListener = cancelAutoHide
        binding.buttonRefresh.onFocusChangeListener = cancelAutoHide
    }

    private fun showController() {
        isControllerShowing = true
        updateControllerTitle()
        binding.topBar.visibility = View.VISIBLE
        binding.controller.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControllerRunnable)
        mainHandler.postDelayed(hideControllerRunnable, CONTROLLER_HIDE_DELAY_MS)
    }

    private fun hideController() {
        isControllerShowing = false
        mainHandler.removeCallbacks(hideControllerRunnable)
        binding.topBar.visibility = View.GONE
        binding.controller.visibility = View.GONE
        // 收起后焦点回到播放区，避免落在已隐藏的按钮上
        if (!binding.marmotRoot.isFocused) {
            binding.marmotRoot.requestFocus()
        }
    }

    private fun toggleController() {
        if (isControllerShowing) hideController() else showController()
    }

    /** 控制栏标题 = 分组名 · 频道名（切台/刷新后若控制栏仍显示则同步刷新）。 */
    private fun updateControllerTitle() {
        val vod = currentVod
        val groupName = provinces.getOrNull(vod?.tagIndex ?: -1)?.name
        binding.textChannelTitle.text = when {
            vod == null -> ""
            groupName != null -> "$groupName · ${vod.name}"
            else -> vod.name
        }
    }

    /** 焦点是否在底部控制栏按钮（频道/画质/刷新）上。 */
    private fun isBottomControllerButtonFocused(): Boolean =
        binding.buttonChannel.isFocused || binding.buttonQuality.isFocused || binding.buttonRefresh.isFocused

    /** 触摸是否落在播放UI可交互元素（左上返回按钮、底部控制栏）范围内，命中则正常分发。 */
    private fun isTouchInPlayerUi(ev: MotionEvent): Boolean {
        if (!isControllerShowing) return false
        val rect = android.graphics.Rect()
        return listOf(binding.buttonBack, binding.controller).any { target ->
            target.visibility == View.VISIBLE &&
                target.getGlobalVisibleRect(rect) &&
                rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
        }
    }

    // ==================== 按键交互 ====================

    /**
     * 四向切台（基于过滤后的 provinces 做 D-pad 上下左右环形切台，延迟 1s 加载防抖）。
     */
    private fun goNext(direction: String): Boolean {
        if (provinces.isEmpty()) return false
        val vod = currentVod ?: provinces.first().vods.firstOrNull()?.also { currentVod = it } ?: return false
        // 基于过滤后的 provinces 做 D-pad 四向环形切台（不再用 MarmotLiveData 的全量导航 map）
        val tagIdx = vod.tagIndex.coerceIn(0, provinces.lastIndex)
        val detailIdx = vod.detailIndex.coerceIn(0, provinces[tagIdx].vods.lastIndex)
        val next: Vod = when (direction) {
            "up" -> {
                val group = provinces[tagIdx].vods
                val ni = if (detailIdx == 0) group.lastIndex else detailIdx - 1
                group[ni]
            }
            "down" -> {
                val group = provinces[tagIdx].vods
                val ni = if (detailIdx == group.lastIndex) 0 else detailIdx + 1
                group[ni]
            }
            "left" -> {
                val ng = if (tagIdx == 0) provinces.lastIndex else tagIdx - 1
                provinces[ng].vods.first()
            }
            "right" -> {
                val ng = if (tagIdx == provinces.lastIndex) 0 else tagIdx + 1
                provinces[ng].vods.first()
            }
            else -> return false
        }
        currentVod = next
        showLiveName(next.name)
        // 延迟 1s 加载（参考 handler.sendMessageDelayed），避免快速连按多次加载
        mainHandler.removeCallbacks(switchChannelRunnable)
        mainHandler.postDelayed(switchChannelRunnable, 1000)
        return true
    }

    /** 转发遥控按键到页面脚本（参考反编译版 `_messageCtrl.ctrl(action)`）。 */
    private fun forwardKeyToPage(action: String) {
        val js = "(function(){try{if(window._messageCtrl&&typeof window._messageCtrl.ctrl==='function'){window._messageCtrl.ctrl('$action');}}catch(e){}})();"
        webEngine?.evaluateJavascript(js)
    }

    /**
     * 触屏点击：呼出/收起播放UI（对标普通视频播放器点击屏幕 toggle 控制栏，统一交互），
     * 同时拦截触摸阻止 WebView video 默认暂停。
     * 浮层显示时触摸交给浮层；落在播放UI按钮上时正常分发（按钮可点）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isChannelMenuShowing && !isQualityMenuShowing && ev.action == MotionEvent.ACTION_DOWN) {
            if (isTouchInPlayerUi(ev)) {
                AppLog.i(TAG, "touch: 命中播放UI按钮区域")
                return super.dispatchTouchEvent(ev)
            }
            AppLog.i(TAG, "touch: toggle 控制栏 showing=$isControllerShowing")
            toggleController()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            // 播放UI按钮的 OK 已在 ACTION_DOWN 消费（聚焦/激活），吞掉对应 UP
            // 防止 UP 落到刚获焦的按钮上触发默认 performClick（如聚焦即弹出画质菜单）
            if (consumeNextOkUp && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                consumeNextOkUp = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val keyCode = event.keyCode

        // —— 频道列表浮层（双排：FocusLockRecyclerView 在 focusSearch 层防溢出）——
        if (isChannelMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { hideChannelMenu(); return true }
            }
            return super.dispatchKeyEvent(event)
        }
        if (isQualityMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    AppLog.i(TAG, "画质菜单关闭: keyCode=$keyCode")
                    hideQualityMenu(); return true
                }
                // 确认键：若焦点在画质项上则触发其点击（执行切换）再关闭；
                // 否则仅关闭。不能把确认键一并拦截——否则 TV 上选画质永远不触发切换。
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val focused = currentFocus
                    val inList = focused != null &&
                        generateSequence(focused.parent) { it.parent }.contains(binding.qualityList)
                    if (inList) {
                        AppLog.i(TAG, "画质确认：点击项 ${(focused as? android.widget.TextView)?.text}")
                        focused?.performClick()
                    } else {
                        AppLog.w(TAG, "画质确认：未命中画质项，焦点=$focused，仅关闭")
                    }
                    hideQualityMenu()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // —— 播放UI显示时：方向键在 返回↔底部按钮行 间导航，BACK 收起 ——
        if (isControllerShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { hideController(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isBottomControllerButtonFocused()) {
                        // 底部按钮行上移到右上角返回按钮
                        binding.buttonBack.requestFocus()
                    } else {
                        // 已在返回按钮或无焦点：收起播放UI回到切台模式
                        hideController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 进入底部按钮行（已在内则不重复聚焦；从返回按钮下来同样入行，默认聚焦频道）
                    if (!isBottomControllerButtonFocused()) binding.buttonChannel.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 底部三按钮 频道→画质→刷新 左右循环；返回按钮左右进入按钮行
                    when {
                        binding.buttonBack.isFocused -> binding.buttonChannel.requestFocus()
                        binding.buttonChannel.isFocused -> binding.buttonQuality.requestFocus()
                        binding.buttonQuality.isFocused -> binding.buttonRefresh.requestFocus()
                        else -> binding.buttonChannel.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    consumeNextOkUp = true
                    when {
                        binding.buttonBack.isFocused -> binding.buttonBack.performClick()
                        binding.buttonChannel.isFocused -> binding.buttonChannel.performClick()
                        binding.buttonQuality.isFocused -> binding.buttonQuality.performClick()
                        binding.buttonRefresh.isFocused -> binding.buttonRefresh.performClick()
                        else -> {
                            val ok = binding.buttonChannel.requestFocus()
                            AppLog.i(TAG, "controller OK 聚焦频道按钮: requestFocus=$ok focused=${binding.buttonChannel.isFocused} rootFocused=${binding.marmotRoot.isFocused}")
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> { showChannelMenu(); return true }
                else -> return super.dispatchKeyEvent(event)
            }
        }

        // —— 无浮层时 ——
        when (keyCode) {
            // 确认键 → 呼出播放UI（统一普通视频播放器的确定键行为）
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showController(); return true }
            // 菜单键 → 频道选择（统一普通视频播放器的菜单键呼出面板行为）
            KeyEvent.KEYCODE_MENU -> { showChannelMenu(); return true }
            // 上下左右 → 切台 + 转发页面脚本
            KeyEvent.KEYCODE_DPAD_UP -> { forwardKeyToPage("up"); return goNext("up") }
            KeyEvent.KEYCODE_DPAD_DOWN -> { forwardKeyToPage("down"); return goNext("down") }
            KeyEvent.KEYCODE_DPAD_LEFT -> { forwardKeyToPage("left"); return goNext("left") }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { forwardKeyToPage("right"); return goNext("right") }
            KeyEvent.KEYCODE_BACK -> {
                if (System.currentTimeMillis() - exitTime <= exitInterval) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    Toast.makeText(this, R.string.activity_exit_live_hint, Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        webEngine?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webEngine?.onResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(switchChannelRunnable)
        mainHandler.removeCallbacks(teenModeTicker)
        mainHandler.removeCallbacks(hideControllerRunnable)
        webEngine?.let { engine ->
            engine.removeJavascriptInterface(jsBridge.bridgeName)
            engine.loadUrl("about:blank")
            engine.stopLoading()
            engine.destroy()
        }
        webEngine = null
        super.onDestroy()
    }
}
