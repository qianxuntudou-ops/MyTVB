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

import androidx.activity.OnBackPressedCallback
import com.google.gson.Gson
import com.mytvb.feature.marmot.quality.CctvQualityProvider
import com.mytvb.feature.marmot.quality.LiveQualityProvider
import com.mytvb.feature.marmot.quality.MarmotQualityProvider
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.ext.toast
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.BaseActivity
import com.mytvb.core.ui.base.ChoiceDialog
import com.mytvb.databinding.ActivityMarmotLiveBinding
import com.mytvb.feature.cctv.CctvLiveQuality
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.feature.cctv.CctvLiveQualityHook
import com.mytvb.feature.marmot.MarmotCloudUpdate
import com.mytvb.feature.marmot.MarmotJsBridge
import com.mytvb.feature.marmot.MarmotLiveData
import com.mytvb.feature.marmot.domain.HzItem
import com.mytvb.feature.marmot.domain.MarmotModels.Live
import com.mytvb.feature.marmot.domain.MarmotModels.Vod
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
 * 3. **画质选择**（播放UI画质按钮弹）：[showQualityMenu] ChoiceDialog 纵向列表（与 CCTV 影视同款），数据按源路由（见 [LiveQualityProvider]）。
 * 4. **切台**：上下左右键触发 [goNext]，调 [MarmotLiveData.liveNext] 四向环形切台，延迟 1s 加载防抖。
 *
 * 按键映射（按需求）：
 * - **确认键(OK/ENTER)** → 呼出/导航播放UI
 * - **菜单键(MENU)** → [showChannelMenu] 频道列表
 * - **上下左右** → 无浮层时 [goNext] 切台 + 转发页面脚本；播放UI显示时在按钮间导航
 * - **返回键** → 优先级 浮层关闭 > 收起播放UI > 双击退出（见 [handleBackKey]）
 */
class MarmotLiveActivity : BaseActivity<ActivityMarmotLiveBinding>() {

    companion object {
        private const val TAG = "MarmotLiveActivity"
        /** DataStore key：上次观看频道的 URL（下次进直播恢复）。 */
        private const val KEY_LAST_CHANNEL_URL = "marmot_last_channel_url"
        /** 播放UI无操作自动隐藏延时（对齐普通视频播放器控制栏 5s）。 */
        private const val CONTROLLER_HIDE_DELAY_MS = 5_000L
        /** 掀盖后播放停滞监测：轮询间隔 / 判定卡顿阈值 / 单频道自动重载上限。 */
        private const val STALL_WATCH_INTERVAL_MS = 2_000L
        private const val STALL_RECOVER_THRESHOLD_MS = 8_000L
        private const val STALL_MAX_AUTO_RECOVERY = 3
        /** DataStore key：「CCTV直播原生直拉」开关（存"开/关"，默认开）。 */
        private const val KEY_CCTV_NATIVE_LIVE = "cctv_native_live"
        // CCTV 直播用户手选画质（label 如 "1080P"，跨会话/跨频道；自动降档不回写）
        private const val KEY_CCTV_LIVE_QUALITY = "cctv_live_quality"
        /** 原生直拉起播超时：无首帧先重试一次，再超时回退 WebView。 */
        private const val NATIVE_STARTUP_TIMEOUT_MS = 12_000L

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
                AppToast.show(this@MarmotLiveActivity, getString(R.string.activity_teen_rest_needed_format, restMin))
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
    /** 「时间常驻显示」开关：开时右上角常驻时钟（顶栏呼出时避让）。 */
    private var alwaysOnClockEnabled = false
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
        // BACK 兜底：手势返回等非按键路径统一走 handleBackKey，
        // 防止 OnBackPressedDispatcher 无回调时落到默认 fallback 直接 finish
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackKey()
        })
        // 青少年模式：启动观看时长定时器
        mainHandler.postDelayed(teenModeTicker, 15_000L)
        initWebView()
        setupChannelMenu()
        setupMenuDismiss()
        setupPlayerController()
        // 「时间常驻显示」：开关开时右上角常驻时钟（顶栏呼出时避让，样式与顶栏时钟一致）。
        // 起播遮罩期间被遮罩自然盖住，掀盖后露出，无需挂掀盖回调。
        alwaysOnClockEnabled = PlayerSettingsStore.load(this).showAlwaysOnClock
        binding.alwaysClock.visibility = if (alwaysOnClockEnabled) View.VISIBLE else View.GONE
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
                AppLog.i(TAG, "页面加载完成: url=$url")
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
        // 恢复上次用户手选的 CCTV 直播画质（无记录时 Provider 默认最高档 1080P）
        cctvQualityProvider.restore(appSettings.getCachedString(KEY_CCTV_LIVE_QUALITY))
        // 协程：加载频道 + 播放首个频道；云端更新在首播发起后转后台执行
        CoroutineScope(Dispatchers.Main).launch {
            // 云端更新 tv-web 不在首播路径上串行等待：频道表有 assets 内置种子兜底，
            // 而 MarmotCloudUpdate 的独立 client connectTimeout 有 30s——api.vonchange.com
            // 慢/不可达时会把「点进直播到出画面」拖到半分钟。也不能与 load 并行：
            // 解压 tv2.json 与 load 读取有竞态，半截文件会被 load 判损坏删除，
            // 云端版本号却已记新，更新机制就此假死。故顺序固定为 load → playCurrent → 后台更新。
            // 1. 加载频道表
            val loaded = withContext(Dispatchers.IO) { MarmotLiveData.load(this@MarmotLiveActivity) }
            AppLog.i(TAG, "initData: 频道表加载结果=$loaded")
            if (!loaded) {
                AppLog.e(TAG, "initData: 频道数据加载失败，请检查网络/明文流量权限/filesDir 是否损坏")
                AppToast.show(this@MarmotLiveActivity, R.string.activity_channel_load_failed)
                return@launch
            }
            provinces.clear()
            // 频道白名单（issue#106，二轮补全）：①CCTV 频道（url 含 tv.cctv.com/live/，
            // createLivePlayer/原生直拉）；②「卫视」组的央视频网页频道（yangshipin.cn
            // 整页加载）；③「央视频超清」组（原央视源2）中与央视组不重复的频道——
            // CCTV4K/8K/16-4K、CGTN 六语种、风云/第一/怀旧剧场（用户要求频道补全，
            // 超清档播不播得动由设备自己消化）。与央视组重复的 CCTV1-17 不放（避免
            // 列表重复）；各省地方台站点未实测仍不放开。PR #53 全量放开维持不采纳。
            val allLives = MarmotLiveData.getLives()
            // 央视组的 pid 集合（判重复用：央视频超清组里同 pid = 同频道）
            val cctvGroupPids = allLives
                .firstOrNull { it.name == "央视" }?.vods.orEmpty()
                .mapNotNull { Regex("pid=(\\d+)").find(it.url)?.groupValues?.get(1) }
                .toSet()
            var cctvIndex = 0
            for (live in allLives) {
                // 筛选白名单频道，重建 Vod 的导航索引
                val cctvVods = when (live.name) {
                    "卫视" -> live.vods.filter { it.url.contains("yangshipin.cn") }
                    "央视频超清", "央视源2" -> live.vods.filter { vod ->
                        val pid = Regex("pid=(\\d+)").find(vod.url)?.groupValues?.get(1)
                        pid != null && pid !in cctvGroupPids
                    }
                    else -> live.vods.filter { extractCctvChannelId(it.url) != null }
                }
                if (cctvVods.isEmpty()) continue
                // 构造只含白名单频道的分组副本（重新编 tagIndex/detailIndex）；
                // 「央视源2」是旧数据名，显示统一为「央视频超清」
                val displayName = if (live.name == "央视源2") "央视频超清" else live.name
                val filteredLive = Live(tag = live.tag, name = displayName, index = cctvIndex,
                    vods = cctvVods.toMutableList())
                cctvVods.forEachIndexed { j, vod ->
                    vod.tagIndex = cctvIndex
                    vod.detailIndex = j
                    vod.key = "${cctvIndex}_$j"
                }
                provinces.add(filteredLive)
                cctvIndex++
            }
            AppLog.i(TAG, "initData: 频道白名单过滤后 ${provinces.size} 个分组，" +
                "${provinces.sumOf { it.vods.size }} 个频道")
            if (provinces.isEmpty()) {
                AppToast.show(this@MarmotLiveActivity, R.string.activity_no_cctv_channels)
                return@launch
            }
            // 3. 恢复上次观看频道：优先按保存的 URL 反查（白名单频道），否则回退首个频道
            val lastUrl = appSettings.getCachedString(KEY_LAST_CHANNEL_URL, null)
            currentVod = provinces.flatMap { it.vods }.firstOrNull { it.url == lastUrl }
                ?: provinces.first().vods.first()
            if (currentVod == null) {
                AppToast.show(this@MarmotLiveActivity, R.string.activity_no_channels)
                return@launch
            }
            playCurrent()
            AppToast.show(this@MarmotLiveActivity, R.string.activity_remote_switch_channel_tip)
            // 2. 首播已发起：后台更新 tv-web（版本变化下次进入生效，不打断当前播放）
            launch {
                val updated = withContext(Dispatchers.IO) {
                    runCatching { MarmotCloudUpdate.checkAndUpdateRes(this@MarmotLiveActivity) }
                        .onFailure { AppLog.e(TAG, "云端更新异常: ${it.javaClass.simpleName}: ${it.message}", it) }
                        .getOrDefault(false)
                }
                AppLog.i(TAG, "initData: 云端更新结果=$updated（后台，下次进入生效）")
            }
        }
    }

    /** 播放当前频道，并记录 URL 以便下次恢复（对标参考 `HistoryDaoX.updateChannel`）。 */
    private fun playCurrent() {
        val vod = currentVod ?: return
        mainHandler.removeCallbacks(switchChannelRunnable)
        // 换频道（含首次进入）：停滞自动重载计数清零；同频道刷新/自动重载不清（防循环）
        if (vod.url != stallWatchChannelUrl) {
            stallWatchChannelUrl = vod.url
            stallRecoveryCount = 0
            nativeErrorCount = 0
        }
        // CCTV 直播频道（tv.cctv.com/live/）：优先原生直拉流（vdn 解析 + H5e 原生解密 +
        // ExoPlayer 播放，issue#106 阶段二——WebView 内 JS 解密跟不上是盒子卡顿/先声后画的根因）；
        // 央视频频道（yangshipin.cn/tv/home?pid=，超清/卫视组）：CMG 三步签名链 + 有状态
        // wasm 解密原生直拉（同开关「直播极速模式」，失败回退整页 WebView 劫持）；
        // 原生关闭或失败时回退 WebView 自构 HTML（createLivePlayer，画质可切，对标 v1.5.8）；
        // 其它频道：维持原 Marmot 方式（加载完整页面 + tv.user.js 劫持）。
        val channelId = extractCctvChannelId(vod.url)
        if (channelId != null && nativeLiveEnabled) {
            AppLog.i(TAG, "playCurrent: CCTV 原生直拉 channelId=$channelId quality=${cctvQualityProvider.current.label}")
            startNativeLive(channelId, cctvQualityProvider.current)
        } else {
            val cmgPid = com.mytvb.feature.cctv.cmg.CmgChannelCatalog.pidFromUrl(vod.url)
            val cmgCnlid = cmgPid?.let { com.mytvb.feature.cctv.cmg.CmgChannelCatalog.cnlidOf(it) }
            if (cmgPid != null && cmgCnlid != null && nativeLiveEnabled) {
                AppLog.i(TAG, "playCurrent: 央视频原生直拉 pid=$cmgPid cnlid=$cmgCnlid name=${vod.name}")
                startCmgNativeLive(cmgPid, cmgCnlid)
            } else if (channelId != null) {
                playCctvViaWebView(channelId)
            } else {
                AppLog.i(TAG, "playCurrent: Marmot 模式 url=${vod.url}")
                // 非 CCTV 整页劫持：先显示全黑遮罩，并确保遮罩真正绘制上屏后再加载官网网页，
                // 避免 WebView 首帧快于遮罩布局导致官网网页闪现
                stopNativeLivePlayer()
                showVideoCover {
                    webEngine?.loadUrl(vod.url)
                }
            }
        }
        showLiveName(vod.name)
        // 播放UI若还显示着（刷新按钮触发），同步刷新控制栏标题
        if (isControllerShowing) updateControllerTitle()
        // 记录上次观看频道（IO 写入，不阻塞）
        appSettings.putStringAsync(KEY_LAST_CHANNEL_URL, vod.url)
    }

    // ==================== CCTV 原生直拉流（issue#106 阶段二） ====================

    /** 原生直播 ExoPlayer 实例（独立于 B 站点播池）。 */
    private var nativePlayer: androidx.media3.exoplayer.ExoPlayer? = null

    /** 原生起播协程（vdn 解析 + 播放器组装）。 */
    private var nativeLiveJob: kotlinx.coroutines.Job? = null

    /** 原生路径起播超时（无首帧回退 WebView）。 */
    private val nativeStartupTimeoutRunnable = Runnable { onNativeStartupTimeout() }

    /** 原生路径 BUFFERING 停滞监测（对标 WebView 路径的 stallWatchRunnable）。 */
    private val nativeStallRunnable = object : Runnable {
        override fun run() {
            val player = nativePlayer ?: return
            if (player.playWhenReady && player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                if (nativeBufferingSinceMs == 0L) {
                    nativeBufferingSinceMs = android.os.SystemClock.elapsedRealtime()
                } else if (android.os.SystemClock.elapsedRealtime() - nativeBufferingSinceMs >= STALL_RECOVER_THRESHOLD_MS) {
                    nativeBufferingSinceMs = 0L
                    recoverNativeStall()
                    return
                }
            } else {
                nativeBufferingSinceMs = 0L
            }
            mainHandler.postDelayed(this, STALL_WATCH_INTERVAL_MS)
        }
    }
    private var nativeBufferingSinceMs = 0L

    /** 原生连续错误计数（1 次重试、2 次回退 WebView；切台/手动刷新清零）。 */
    private var nativeErrorCount = 0

    /** 本次会话内原生是否已被熔断（vdn 失败/连续错误后不再尝试，直至重进页面）。 */
    private var nativeLiveBrokenForSession = false

    /** 「CCTV直播原生直拉」开关（默认开；关闭后恒走 WebView 自构 HTML 老路径）。 */
    private val nativeLiveEnabled: Boolean by lazy {
        val stored = appSettings.getCachedString(KEY_CCTV_NATIVE_LIVE, null)
        // 存储值"开/关"口径，与设置页 toggle 一致；未设置=默认开
        stored == null || stored == "开"
    }

    /** WebView 回退路径：自构 HTML + createLivePlayer（原 playCurrent CCTV 分支逻辑）。 */
    private fun playCctvViaWebView(channelId: String) {
        stopNativeLivePlayer()
        val baseUrl = "https://tv.cctv.com/live/$channelId/m/" +
            "#${com.mytvb.feature.marmot.web.MarmotSystemWebViewClient.MYBILI_CCTV_NATIVE_MARKER}"
        val html = buildCctvPlayerHtml(channelId, cctvQualityProvider.current)
        AppLog.i(TAG, "playCctvViaWebView: channelId=$channelId quality=${cctvQualityProvider.current.label}")
        // 自构 HTML 同样要走遮罩：官方 liveplayer.js 初始化期间会显示自家 UI（大播放钮 +
        // 红色 loading 圈），起播失败/慢时还会露出官方错误组件——轮询到 <video> 时间轴
        // 真正推进再掀开（切画质重建页面时也顺带盖住换流空窗）
        showVideoCover {
            webEngine?.loadDataWithBaseURL(baseUrl, html)
        }
    }

    /** 原生直拉失败回退：熔断本会话原生路径并走 WebView。 */
    private fun fallbackToWebView(channelId: String, reason: String) {
        AppLog.w(TAG, "原生直拉回退 WebView：$reason")
        nativeLiveBrokenForSession = true
        playCctvViaWebView(channelId)
    }

    /**
     * 原生直拉主流程：遮罩 → vdn 解析（b= 窗口锁档）→ ExoPlayer + H5e 解密 DataSource。
     * 首帧回调掀盖；12s 无首帧或连续播放错误回退 WebView。
     */
    private fun startNativeLive(channelId: String, quality: com.mytvb.feature.cctv.CctvLiveQuality) {
        stopNativeLivePlayer()
        if (nativeLiveBrokenForSession) {
            playCctvViaWebView(channelId)
            return
        }
        // 原生视图顶替 WebView（遮罩之下切换，用户无感）
        binding.webviewWrapper.visibility = View.GONE
        binding.nativePlayerView.visibility = View.VISIBLE
        showVideoCover(pollWeb = false) {
            nativeLiveJob = CoroutineScope(Dispatchers.Main).launch {
                val stream = try {
                    com.mytvb.feature.cctv.h5e.CctvVdnResolver.resolveLive(channelId, quality.bRange)
                } catch (e: Exception) {
                    if (isFinishing || isDestroyed) return@launch
                    AppLog.e(TAG, "vdn 解析失败 channel=$channelId: ${e.message}")
                    fallbackToWebView(channelId, "vdn ${e.message}")
                    return@launch
                }
                if (isFinishing || isDestroyed) return@launch
                val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf("Referer" to stream.referer))
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(8_000)
                val dsFactory = com.mytvb.feature.cctv.h5e.CctvH5eDataSource.Factory(httpFactory)
                // 直播缓冲调优（实测标定）：CCTV 媒体清单滑窗仅 3 分片=12s（服务端每 4s
                // 淘汰最老分片），targetOffset 不能超过窗口——15s 无处可退会致播放点钉死
                // 淘汰线，周期性微 BUFFERING（0.2~1s 一次=用户感知卡顿）。
                // targetOffset 8s：播放点距淘汰线恒留 4s（一分片下载+解密耗时）；
                // 起播/二次缓冲门槛 4s（窗口小，攒更多只会拖慢起播误触 12s 超时）。
                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 10_000,
                        /* maxBufferMs = */ 15_000,
                        /* bufferForPlaybackMs = */ 4_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 4_000
                    )
                    .build()
                val player = androidx.media3.exoplayer.ExoPlayer.Builder(this@MarmotLiveActivity)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)
                    )
                    .setLoadControl(loadControl)
                    .build()
                nativePlayer = player
                binding.nativePlayerView.player = player
                player.addListener(nativePlayerListener)
                player.addAnalyticsListener(nativeAnalyticsListener)
                val mediaItem = androidx.media3.common.MediaItem.Builder()
                    .setUri(stream.hlsUrl)
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    // 直播边沿退让（实测窗口 12s）：targetOffset 8s 在窗口内且距淘汰线恒留
                    // 一分片余量（4s）；6~10s 区间内 media3 自动微速维持目标偏移
                    .setLiveConfiguration(
                        androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(8_000)
                            .setMinOffsetMs(6_000)
                            .setMaxOffsetMs(10_000)
                            .build()
                    )
                    .build()
                player.setMediaItem(mediaItem)
                player.playWhenReady = true
                player.prepare()
                mainHandler.postDelayed(nativeStartupTimeoutRunnable, NATIVE_STARTUP_TIMEOUT_MS)
                AppLog.i(TAG, "原生直拉已起播请求 url=${stream.hlsUrl.take(80)}")
            }
        }
    }

    /**
     * 央视频（yangshipin）原生直拉主流程：遮罩 → 三步签名链（auth→open/token→
     * get_live_info，纯 HTTP + wasm JNI）→ 配置 CMG 解密会话（自造 tag + 服务器校准
     * 时钟）→ ExoPlayer + [com.mytvb.feature.cctv.cmg.CmgDataSource]。
     * 首帧掀盖；超时/连续错误回退整页 WebView（同 CCTV 原生的重试与熔断节奏）。
     */
    private fun startCmgNativeLive(pid: String, cnlid: String) {
        stopNativeLivePlayer()
        if (nativeLiveBrokenForSession) {
            fallbackCurrentToWebView("央视频原生已被熔断")
            return
        }
        binding.webviewWrapper.visibility = View.GONE
        binding.nativePlayerView.visibility = View.VISIBLE
        showVideoCover(pollWeb = false) {
            nativeLiveJob = CoroutineScope(Dispatchers.Main).launch {
                val stream = try {
                    com.mytvb.feature.cctv.cmg.CmgLiveStreamResolver.resolveLive(
                        this@MarmotLiveActivity, pid, cnlid
                    )
                } catch (e: Exception) {
                    if (isFinishing || isDestroyed) return@launch
                    AppLog.e(TAG, "CMG 解析失败 pid=$pid: ${e.message}")
                    fallbackCurrentToWebView("CMG ${e.message}")
                    return@launch
                }
                if (isFinishing || isDestroyed) return@launch
                // 配置 CMG 解密会话（自造 tag + 服务器时钟偏移 + 播放页 href）
                runCatching {
                    com.mytvb.feature.cctv.cmg.CmgNativeBridge.configureSession(
                        stream.playerTag, stream.clockOffsetMs,
                        "https://www.yangshipin.cn/tv/home?pid=$pid"
                    )
                }.onFailure {
                    AppLog.e(TAG, "CMG configureSession 异常", it)
                }
                val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(
                        mapOf(
                            "Referer" to com.mytvb.feature.cctv.cmg.CmgLiveStreamResolver.REFERER,
                            "Origin" to "https://www.yangshipin.cn",
                            "User-Agent" to com.mytvb.feature.cctv.cmg.CmgLiveStreamResolver.DESKTOP_USER_AGENT
                        )
                    )
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(8_000)
                val dsFactory = com.mytvb.feature.cctv.cmg.CmgDataSource.Factory(httpFactory)
                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 15_000, 4_000, 4_000)
                    .build()
                val player = androidx.media3.exoplayer.ExoPlayer.Builder(this@MarmotLiveActivity)
                    .setMediaSourceFactory(
                        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)
                    )
                    .setLoadControl(loadControl)
                    .build()
                nativePlayer = player
                binding.nativePlayerView.player = player
                player.addListener(nativePlayerListener)
                player.addAnalyticsListener(nativeAnalyticsListener)
                val mediaItem = androidx.media3.common.MediaItem.Builder()
                    .setUri(stream.hlsUrl)
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    .build()
                player.setMediaItem(mediaItem)
                player.playWhenReady = true
                player.prepare()
                mainHandler.postDelayed(nativeStartupTimeoutRunnable, NATIVE_STARTUP_TIMEOUT_MS)
                AppLog.i(TAG, "央视频原生直拉已起播请求 url=${stream.hlsUrl.take(90)} tag=${stream.playerTag}")
            }
        }
    }

    /**
     * 原生直拉回退（按当前频道形态）：CCTV 走自构 HTML，央视频/其它走整页 WebView。
     * 熔断本会话原生路径。
     */
    private fun fallbackCurrentToWebView(reason: String) {
        AppLog.w(TAG, "原生直拉回退 WebView：$reason")
        nativeLiveBrokenForSession = true
        val url = currentVod?.url.orEmpty()
        val channelId = extractCctvChannelId(url)
        if (channelId != null) {
            playCctvViaWebView(channelId)
        } else {
            stopNativeLivePlayer()
            showVideoCover {
                webEngine?.loadUrl(url)
            }
        }
    }

    /** 依当前频道 URL 重启原生播放（错误重试/起播超时/停滞恢复共用入口）。 */
    private fun restartNativeForCurrentChannel(): Boolean {
        val url = currentVod?.url.orEmpty()
        val channelId = extractCctvChannelId(url)
        if (channelId != null) {
            startNativeLive(channelId, cctvQualityProvider.current)
            return true
        }
        val pid = com.mytvb.feature.cctv.cmg.CmgChannelCatalog.pidFromUrl(url)
        val cnlid = pid?.let { com.mytvb.feature.cctv.cmg.CmgChannelCatalog.cnlidOf(it) }
        if (pid != null && cnlid != null) {
            startCmgNativeLive(pid, cnlid)
            return true
        }
        return false
    }

    /** 原生播放诊断：丢帧/解码器（卡顿归因=网络 BUFFERING vs 解码渲染丢帧）。 */
    private val nativeAnalyticsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            if (droppedFrames > 0) {
                AppLog.w(TAG, "nativePlayer 丢帧 dropped=$droppedFrames elapsed=${elapsedMs}ms")
            }
        }

        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            AppLog.i(TAG, "nativePlayer 解码器=$decoderName")
        }
    }

    /** 原生播放事件：首帧掀盖 / 错误重试与回退。 */
    private val nativePlayerListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 状态流转可观测（2=BUFFERING/3=READY）：BUFFERING→READY 往返一次即一次播放卡顿
            val name = when (playbackState) {
                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                androidx.media3.common.Player.STATE_READY -> "READY"
                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                else -> "$playbackState"
            }
            AppLog.i(TAG, "nativePlayer state=$name")
        }

        override fun onRenderedFirstFrame() {
            mainHandler.removeCallbacks(nativeStartupTimeoutRunnable)
            if (binding.videoCover.visibility == View.VISIBLE) {
                AppLog.i(TAG, "原生直拉首帧，掀盖")
                hideVideoCover()
            }
            if (nativeBufferingSinceMs != 0L) nativeBufferingSinceMs = 0L
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.e(TAG, "原生直拉播放错误 count=$nativeErrorCount: ${error.errorCodeName}")
            nativeErrorCount++
            if (nativeErrorCount <= 1) {
                if (!restartNativeForCurrentChannel()) {
                    fallbackCurrentToWebView("连续播放错误 ${error.errorCodeName}")
                }
            } else {
                fallbackCurrentToWebView("连续播放错误 ${error.errorCodeName}")
            }
        }
    }

    private fun onNativeStartupTimeout() {
        // 12s 无首帧：一次重试机会，再超时回退 WebView（老盒子解码慢时 WebView 路径反而能出）
        nativeErrorCount++
        if (nativeErrorCount <= 1) {
            AppLog.w(TAG, "原生直拉起播超时（无首帧），重试一次")
            if (!restartNativeForCurrentChannel()) {
                if (binding.videoCover.visibility == View.VISIBLE) hideVideoCover()
            }
        } else {
            fallbackCurrentToWebView("起播超时无首帧")
        }
    }

    /** 原生路径卡顿自动恢复：复用 WebView 路径的恢复计数与降档策略。 */
    private fun recoverNativeStall() {
        val name = currentVod?.name ?: "?"
        stallRecoveryCount++
        if (stallRecoveryCount > STALL_MAX_AUTO_RECOVERY) {
            AppLog.w(TAG, "nativeStall: 卡顿超自动重载上限 name=$name，停止监测等手动处理")
            stopNativeStallWatch()
            AppToast.show(this@MarmotLiveActivity, R.string.marmot_stall_give_up)
            return
        }
        AppLog.w(TAG, "nativeStall: 缓冲停滞 ${STALL_RECOVER_THRESHOLD_MS}ms 自动恢复 name=$name count=$stallRecoveryCount")
        AppToast.show(this@MarmotLiveActivity, R.string.marmot_stall_auto_recover)
        // 降档仅对 vdn 央视组有意义（CMG 央视频组 fhd 单档）
        if (stallRecoveryCount >= 2 && extractCctvChannelId(currentVod?.url.orEmpty()) != null
            && cctvQualityProvider.dropForRecovery()) {
            AppLog.i(TAG, "nativeStall: 自动降档至 ${cctvQualityProvider.current.label} 后重载")
        }
        restartNativeForCurrentChannel()
    }

    private fun startNativeStallWatch() {
        stopNativeStallWatch()
        nativeBufferingSinceMs = 0L
        mainHandler.postDelayed(nativeStallRunnable, STALL_WATCH_INTERVAL_MS)
    }

    private fun stopNativeStallWatch() {
        mainHandler.removeCallbacks(nativeStallRunnable)
        nativeBufferingSinceMs = 0L
    }

    /** 停止并释放原生播放器（切台到 WebView/其它频道、退出页面时）。 */
    private fun stopNativeLivePlayer() {
        nativeLiveJob?.cancel()
        nativeLiveJob = null
        mainHandler.removeCallbacks(nativeStartupTimeoutRunnable)
        stopNativeStallWatch()
        com.mytvb.feature.cctv.h5e.CctvH5eDecryptor.cancelPendingDecrypts()
        // CMG 会话整体复位（含 forced tag/时钟与 wasm 堆），防旧频道状态泄入下一次解密
        runCatching { com.mytvb.feature.cctv.cmg.CmgNativeBridge.resetSession() }
            .onFailure { AppLog.w(TAG, "CMG resetSession 异常: ${it.message}") }
        nativePlayer?.run {
            removeListener(nativePlayerListener)
            release()
        }
        nativePlayer = null
        binding.nativePlayerView.player = null
        // 还原视图形态：WebView 重新承担播放（WebView/卫视整页路径需要）
        if (binding.webviewWrapper.visibility != View.VISIBLE) {
            binding.webviewWrapper.visibility = View.VISIBLE
        }
        if (binding.nativePlayerView.visibility != View.GONE) {
            binding.nativePlayerView.visibility = View.GONE
        }
    }

    /** 当前是否处于原生直拉形态（决定掀盖后用哪套停滞监测、切台时的清理路径）。 */
    private fun isNativeLiveActive(): Boolean =
        nativePlayer != null && binding.nativePlayerView.visibility == View.VISIBLE


    /**
     * 构造 CCTV 直播播放 HTML（对标 v1.5.8 `CctvPlayerActivity.buildPlayerHtml`）。
     *
     * 加载 CCTV 官方 liveplayer.js，调 `createLivePlayer({t: channelId})` 初始化播放器。
     * 画质：官方脚本已忽略 br 参数，真正切流靠 [CctvLiveQualityHook] 改写 master 清单
     * 请求的 `b=` 码率窗口（整页重建 = hook 按新档位注入，详见 [CctvLiveQuality]）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildCctvPlayerHtml(channelId: String, quality: CctvLiveQuality): String {
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
                ${CctvLiveQualityHook.script(quality.bRange)}
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
                      isAutoPlay: 'true', ruleVisible: 'false', br: '',
                      posterImg: '', isLive4k: 'false', isHttps: 'true', wmode: 'opaque',
                      hasBarrage: 'false', playerType: 'hw', webFullScreenOn: 'false',
                      isLeftBottom: 'false', jumpToApp: 'false', others: ''
                    };
                    console.log('mybili cctv createLivePlayer ' + channel + ' ' + s.w + 'x' + s.h + ' target=${quality.label}');
                    createLivePlayer(playerParas);
                    // 画质验证上报：起播后上报实际流分辨率，用于核对码率窗口是否真正切流
                    // （切换无效时各档上报的分辨率会相同）。60s 未起播打一条诊断。
                    var reported = false;
                    var t0 = Date.now();
                    var infoTimer = setInterval(function() {
                      try {
                        var v = document.getElementsByTagName('video')[0];
                        if (!reported && v && (v.videoWidth || 0) > 0 && !v.paused) {
                          reported = true;
                          console.log('mybili cctv videoInfo channel=' + channel + ' target=${quality.label} ' +
                            v.videoWidth + 'x' + v.videoHeight +
                            ' src=' + (v.currentSrc || v.src || ''));
                          clearInterval(infoTimer);
                          return;
                        }
                        if (Date.now() - t0 > 60000) {
                          console.log('mybili cctv videoInfo timeout channel=' + channel + ' target=${quality.label} video=' +
                            (v ? ((v.videoWidth || 0) + 'x' + (v.videoHeight || 0) + ' paused=' + !!v.paused) : 'none'));
                          clearInterval(infoTimer);
                        }
                      } catch (e) {}
                    }, 500);
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
    private fun showVideoCover(pollWeb: Boolean = true, afterShown: (() -> Unit)? = null) {
        mainHandler.removeCallbacks(hideVideoCoverRunnable)
        // 盖回遮罩（切台/刷新/自动重载）即停停滞监测，避免与新一轮起播轮询并发误判
        stopStallWatch()
        stopNativeStallWatch()
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
                // 遮罩期间持续轮询，按 <video> 是否创建/播放判定"正在加载网页/正在加载视频/就绪移除"；
                // 原生直拉路径（pollWeb=false）无网页可探，掀盖由 ExoPlayer 首帧回调主导
                if (pollWeb) startVideoCoverPolling()
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
        // 掀盖后进入播放停滞监测（60s 兜底掀盖也启动：画面虽未确认但交由监测兜底恢复）；
        // 原生直拉走 ExoPlayer BUFFERING 监测，WebView 路径走 JS 时间轴轮询
        if (isNativeLiveActive()) startNativeStallWatch() else startStallWatch()
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

    // ==================== 掀盖后播放停滞监测（自动重载） ====================

    /** 停滞监测是否在跑（掀盖后启动，盖回/切台时停止）。 */
    private var stallWatchRunning = false
    /** 连续停滞累计时长（currentTime 不增长且非暂停）。 */
    private var stallAccumulatedMs = 0L
    /** 上次轮询到的 currentTime（增长即恢复正常，清零累计）。 */
    private var stallLastTime = 0.0
    /** 当前频道内已自动重载次数（换频道清零，超限放弃自动恢复只提示）。 */
    private var stallRecoveryCount = 0
    /** 停滞监测锁定的频道 URL（用于换台时重置恢复计数）。 */
    private var stallWatchChannelUrl = ""

    /**
     * 掀盖后启动停滞监测：低频轮询 `<video>`，时间轴连续 [STALL_RECOVER_THRESHOLD_MS]
     * 不增长（且非网页层暂停）视为卡死，自动重载当前频道（走完整遮罩流程）。
     * 背景：issue#106 外贸盒子直播中途卡顿，此前掀盖后无任何监测，用户只能手动刷新。
     */
    private fun startStallWatch() {
        stopStallWatch()
        stallWatchRunning = true
        stallAccumulatedMs = 0L
        stallLastTime = 0.0
        mainHandler.postDelayed(stallWatchRunnable, STALL_WATCH_INTERVAL_MS)
    }

    private fun stopStallWatch() {
        stallWatchRunning = false
        mainHandler.removeCallbacks(stallWatchRunnable)
    }

    private val stallWatchRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!stallWatchRunning) return
            webEngine?.evaluateJavascriptWithResult(
                // 返回 "currentTime|paused"：-1=无 video（页面异常/重建中）
                "(function(){try{var v=document.querySelector('video');" +
                    "if(!v){return '-1';}" +
                    "return (v.currentTime||0)+'|'+(v.paused?1:0);}catch(e){return '-1';}})();"
            ) { result ->
                if (!stallWatchRunning) return@evaluateJavascriptWithResult
                val parts = result?.trim()?.removeSurrounding("\"")?.split("|")
                val time = parts?.getOrNull(0)?.toDoubleOrNull()
                val paused = parts?.getOrNull(1) == "1"
                when {
                    // 无 video（异常态）：按停滞累计
                    time == null || time < 0 -> accumulateStall()
                    // 网页层被暂停（用户行为）：不算卡顿
                    paused -> resetStall()
                    // 时间轴在增长：播放正常
                    time > stallLastTime -> resetStall()
                    else -> accumulateStall()
                }
                stallLastTime = if (time != null && time >= 0) time else stallLastTime
                if (stallWatchRunning) {
                    mainHandler.postDelayed(stallWatchRunnable, STALL_WATCH_INTERVAL_MS)
                }
            }
        }
    }

    private fun resetStall() {
        stallAccumulatedMs = 0L
    }

    private fun accumulateStall() {
        stallAccumulatedMs += STALL_WATCH_INTERVAL_MS
        if (stallAccumulatedMs < STALL_RECOVER_THRESHOLD_MS) return
        stallAccumulatedMs = 0L
        stallRecoveryCount++
        val name = currentVod?.name ?: "?"
        if (stallRecoveryCount > STALL_MAX_AUTO_RECOVERY) {
            AppLog.w(TAG, "stallWatch: 卡顿超自动重载上限 name=$name，停止监测等手动处理")
            stopStallWatch()
            AppToast.show(this@MarmotLiveActivity, R.string.marmot_stall_give_up)
            return
        }
        AppLog.w(TAG, "stallWatch: 直播停滞 ${STALL_RECOVER_THRESHOLD_MS}ms 自动重载 name=$name count=$stallRecoveryCount")
        AppToast.show(this@MarmotLiveActivity, R.string.marmot_stall_auto_recover)
        // 第二次起自动降一档再重载（issue#106 盒子 H5e 解密吃力：降码率提高存活率；
        // 正常用户零感知——不卡不降，全局默认档不变）
        if (stallRecoveryCount >= 2 && cctvQualityProvider.dropForRecovery()) {
            AppLog.i(TAG, "stallWatch: 自动降档至 ${cctvQualityProvider.current.label} 后重载")
        }
        // playCurrent 会盖回遮罩（停监测），掀盖成功后重新启动监测
        playCurrent()
    }

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
            webEngine?.evaluateJavascriptWithResult(
                // 返回 <video> 的 currentTime：-1=无 video；0=有 video 未播（含尚未
                // 拿到画面数据：readyState<2 或无 videoWidth 或 totalVideoFrames=0——
                // 时间轴会假推进但首帧从未解码上屏，只看 currentTime 会过早掀盖，
                // issue#106「先出声音过段时间才出画面」即此类；totalVideoFrames>0
                // 表示至少一帧视频真解出来，掀盖必见画面）；
                // >0=时间轴（判断是否增长）
                "(function(){try{var v=document.querySelector('video');" +
                    "if(!v){return '-1';}" +
                    "var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;" +
                    "if(v.readyState<2||(v.videoWidth||0)===0||!q||!(q.totalVideoFrames>0)){return '0';}" +
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
            AppToast.show(this, R.string.activity_channel_loading)
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

    // ==================== 画质选择弹窗（与 CCTV 影视同款 ChoiceDialog） ====================

    /**
     * 显示画质选择弹窗。
     *
     * 按当前频道 url 路由到对应的 [LiveQualityProvider]（方案 C：逐播放源维护画质）：
     * - CCTV（tv.cctv.com/live/）→ [CctvQualityProvider]（写死 4 档，createLivePlayer br 重建）
     * - 其它源 → 沿用 Marmot 方式（页面上报的 videoQualityData + hzChoose 脚本）
     *
     * 弹窗 UI（ChoiceDialog 纵向列表 + 当前项「当前」标记 + 焦点定位）对所有源统一，
     * 只有「可用画质/当前项/切换动作」三件事按源不同。弹窗是独立 Dialog 窗口，
     * 按键/触摸由弹窗自己消费，不走本页 dispatchKeyEvent/dispatchTouchEvent。
     */
    private fun showQualityMenu() {
        val provider = resolveQualityProvider()
        val items = provider.availableQualities()
        AppLog.i(TAG, "showQualityMenu: provider=${provider.javaClass.simpleName} 画质 ${items.size} 项")
        if (items.isEmpty()) {
            AppToast.show(this, R.string.activity_no_quality_options)
            return
        }
        ChoiceDialog.show(
            context = this,
            title = getString(R.string.cctv_quality),
            options = items.map { it.name.orEmpty().ifBlank { "-" } },
            selectedIndex = provider.currentQualityIndex(items)
        ) { index ->
            val item = items.getOrNull(index) ?: return@show
            val switched = provider.switchTo(item) { AppLog.i(TAG, "画质切换：${item.name}") }
            if (switched && provider === cctvQualityProvider && item.name != null) {
                // CCTV 档位持久化：下次进直播（任意频道）沿用用户手选档
                appSettings.putStringAsync(KEY_CCTV_LIVE_QUALITY, item.name!!)
            }
            if (!switched) {
                AppLog.w(TAG, "画质切换：${item.name} 无效或与当前相同")
            }
        }
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

    /** 点击空白处关闭浮层（画质弹窗已是独立 Dialog 窗口，点击由弹窗自消费）。 */
    private fun setupMenuDismiss() {
        binding.marmotRoot.setOnClickListener {
            if (isChannelMenuShowing) hideChannelMenu()
        }
    }

    // ==================== 播放UI（顶栏返回+时钟、底部控制栏，呼出/自动隐藏对齐普通视频播放器） =====================

    private fun setupPlayerController() {
        // 返回按钮 = 单击退出直播（对齐普通视频播放器 button_back → onClose → 退出播放页）；
        // 遥控 BACK 键另走 [handleBackKey] 三层优先级（浮层 > 播放UI > 双击退出）。
        // 触摸点击带按压缩放反馈
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
        binding.alwaysClock.visibility = View.GONE
        binding.controller.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControllerRunnable)
        mainHandler.postDelayed(hideControllerRunnable, CONTROLLER_HIDE_DELAY_MS)
        // 呼出即默认聚焦频道按钮（对齐普通播放器 requestFocus(button_play)）；
        // 聚焦成功会触发 onFocusChange 取消上面排的自动隐藏（焦点在按钮上不计时），
        // 触摸模式下按钮不可 touch 聚焦，requestFocus 失败则 5s 照常收起
        binding.buttonChannel.requestFocus()
    }

    private fun hideController() {
        isControllerShowing = false
        mainHandler.removeCallbacks(hideControllerRunnable)
        binding.topBar.visibility = View.GONE
        binding.alwaysClock.visibility = if (alwaysOnClockEnabled) View.VISIBLE else View.GONE
        binding.controller.visibility = View.GONE
        // 收起播放UI即打断退出连击：避免「关UI」那一下被算作双击退出的第一下
        exitTime = 0L
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
            if (target.visibility != View.VISIBLE || !target.getGlobalVisibleRect(rect)) {
                return@any false
            }
            // controller 顶部是渐变羽化 padding（视觉上仍属视频区），点击应照常 toggle 控制栏
            if (target === binding.controller) {
                rect.top += binding.controller.paddingTop
            }
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
                // 组内只有一个频道（如少儿组仅 CCTV-14）时上下键在组内环形会切回
                // 自己，表现成"按了没反应"——跨组接续：上键到上一组的最后一个频道
                if (group.size <= 1) {
                    val ng = if (tagIdx == 0) provinces.lastIndex else tagIdx - 1
                    provinces[ng].vods.last()
                } else {
                    val ni = if (detailIdx == 0) group.lastIndex else detailIdx - 1
                    group[ni]
                }
            }
            "down" -> {
                val group = provinces[tagIdx].vods
                if (group.size <= 1) {
                    val ng = if (tagIdx == provinces.lastIndex) 0 else tagIdx + 1
                    provinces[ng].vods.first()
                } else {
                    val ni = if (detailIdx == group.lastIndex) 0 else detailIdx + 1
                    group[ni]
                }
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
        if (!isChannelMenuShowing && ev.action == MotionEvent.ACTION_DOWN) {
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

    /**
     * BACK 统一处理，优先级：频道/画质浮层 > 播放UI > 双击退出。
     *
     * 由 [onBackPressedDispatcher] 的 callback 调用（与普通视频播放器同构）：
     * BACK 不在 [dispatchKeyEvent] 里处理——按键 DOWN 走系统 dispatch、UP 走
     * OnBackInvoked 两条路各来一次，DOWN 处再处理会与 UP 形成「伪双击」秒退。
     */
    private fun handleBackKey() {
        AppLog.i(TAG, "handleBackKey ch=$isChannelMenuShowing ui=$isControllerShowing")
        when {
            isChannelMenuShowing -> hideChannelMenu()
            isControllerShowing -> hideController()
            else -> {
                if (System.currentTimeMillis() - exitTime <= exitInterval) {
                    finish()
                } else {
                    exitTime = System.currentTimeMillis()
                    AppToast.show(this, R.string.activity_exit_live_hint)
                }
            }
        }
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
        // BACK 不在此处理：统一走 OnBackPressedCallback → handleBackKey，避免
        // DOWN（本方法）与 UP（系统 OnBackInvoked）各触发一次形成「伪双击」秒退
        if (isChannelMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> { hideChannelMenu(); return true }
            }
            return super.dispatchKeyEvent(event)
        }

        // —— 播放UI显示时：方向键在 返回↔底部按钮行 间导航 ——
        if (isControllerShowing) {
            when (keyCode) {
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
                    // 左右按直觉移动、不循环；最左（频道）/最右（刷新）边界原地不动。
                    // 返回按钮在左上角：LEFT 不动，RIGHT 进入按钮行
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        when {
                            binding.buttonQuality.isFocused -> binding.buttonChannel.requestFocus()
                            binding.buttonRefresh.isFocused -> binding.buttonQuality.requestFocus()
                            binding.buttonChannel.isFocused -> Unit
                            binding.buttonBack.isFocused -> Unit
                            else -> binding.buttonChannel.requestFocus()
                        }
                    } else {
                        when {
                            binding.buttonBack.isFocused -> binding.buttonChannel.requestFocus()
                            binding.buttonChannel.isFocused -> binding.buttonQuality.requestFocus()
                            binding.buttonQuality.isFocused -> binding.buttonRefresh.requestFocus()
                            binding.buttonRefresh.isFocused -> Unit
                            else -> binding.buttonChannel.requestFocus()
                        }
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
            // 确认键 → 呼出播放UI并默认聚焦频道按钮（对齐普通播放器确定键行为）；
            // OK 的 UP 吞掉：防止 UP 落到刚聚焦的频道按钮上触发 performClick 秒开频道菜单
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                consumeNextOkUp = true
                showController()
                return true
            }
            // 菜单键 → 频道选择（统一普通视频播放器的菜单键呼出面板行为）
            KeyEvent.KEYCODE_MENU -> { showChannelMenu(); return true }
            // 上下左右 → 切台 + 转发页面脚本
            KeyEvent.KEYCODE_DPAD_UP -> { forwardKeyToPage("up"); return goNext("up") }
            KeyEvent.KEYCODE_DPAD_DOWN -> { forwardKeyToPage("down"); return goNext("down") }
            KeyEvent.KEYCODE_DPAD_LEFT -> { forwardKeyToPage("left"); return goNext("left") }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { forwardKeyToPage("right"); return goNext("right") }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        webEngine?.onPause()
        // 原生直拉：后台暂停拉流（直播无续播语义，回来重连）
        nativePlayer?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        webEngine?.onResume()
        nativePlayer?.playWhenReady = true
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(switchChannelRunnable)
        mainHandler.removeCallbacks(teenModeTicker)
        mainHandler.removeCallbacks(hideControllerRunnable)
        stopNativeLivePlayer()
        com.mytvb.feature.cctv.h5e.CctvH5eDecryptor.releaseThreadContext()
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
