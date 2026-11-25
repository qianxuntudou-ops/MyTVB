package com.mytvb.feature.cast

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mytvb.core.common.log.AppLog
import com.mytvb.ui.activity.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 哔哩必连接收端：把手机端投屏指令翻译成本地播放器动作，并把播放状态回传给手机。
 *
 * 设计要点：
 * - 播放与弹幕完全复用 MyTVB 现有链路（[PlayerActivity.start] 起播，
 *   控制走 [NvaPlayerBridge]），登录态、清晰度、弹幕、风控全部沿用 App 现有实现；
 * - 自己不做 playurl；
 * - 所有播放器读写都在主线程（[onBridge]），状态快照由 tick 缓存后供 socket 读线程使用。
 */
internal class NvaCastController private constructor() {

    private val tag = "NvaCast"

    lateinit var context: Context
        private set

    private val started = AtomicBoolean(false)

    /** 启动 / 停止切换的互斥锁，避免"退出瞬间监听又被拉起来"。 */
    private val stateLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var ssdp: NvaSsdpServer? = null

    @Volatile
    private var server: NvaServer? = null

    @Volatile
    private var bridge: NvaPlayerBridge? = null

    private val sessions = CopyOnWriteArrayList<NvaSession>()
    private var lastReportedPlayState = -1
    private var lastReportedPositionSec = -1L
    private var lastReportedQnKey: String? = null
    private var tickScheduled = false
    private var tickRunnable: Runnable? = null

    /** 手机端 dlna 面板模式对同一视频会连发两次 Play（间隔约 200~300ms），用于去重。 */
    private var lastPlayKey: String? = null
    private var lastPlayAtMs = 0L

    /** 健康检查节拍计数（每 tick 加一）与上次自愈时间。 */
    private var healthTickCounter = 0

    @Volatile
    private var lastHealAtMs = 0L

    // 播放状态的最近一次采样（只在主线程写）。GetPlayInfo 在 socket 读线程执行，
    // 直接读播放器会踩"Player accessed on the wrong thread"，所以统一读缓存。
    @Volatile
    private var cachedDurationSec = 0L

    @Volatile
    private var cachedPositionSec = 0L

    @Volatile
    private var cachedPlayState = 7

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        this.context = context.applicationContext
        if (this.context !is Application) {
            started.set(false)
            return
        }

        // socket 绑定放到后台线程，避免拖慢冷启动；日志会记录成功或失败。
        val appContext = this.context
        thread(name = "nva-boot", isDaemon = true) {
            // 投屏是旁路能力，线程内任何 Throwable 都不许冒泡成未捕获异常：
            // Android 上任意线程抛未捕获异常都会直接杀进程，表现为「启动即闪退、首页都看不到」。
            // 实测（Fengmi Xiaomi Peppa Series Projector / Android 9 / R8 release 包）该线程
            // 类加载失败抛 NoClassDefFoundError: Invalid descriptor: .，App 每次冷启动必死；
            // 父层 install 的 runCatching 只包住"创建线程"，包不住线程体，必须在这里兜。
            // 失败代价仅为「手机搜不到本设备」，不影响 App 使用。
            runCatching {
                // 与 shutdown 共用一把锁：否则"退出"刚好发生在绑定过程中时，
                // 监听会被重新拉起来，退出后手机仍然能搜到这台设备。
                synchronized(stateLock) {
                    if (!started.get()) return@runCatching
                    ssdp = NvaSsdpServer(appContext).also { it.start() }
                    server = NvaServer(this@NvaCastController).also { it.start() }
                }
            }.onFailure { AppLog.w(tag, "投屏接收端启动失败（降级为不可投屏，App 不受影响）", it) }
        }
        scheduleTick()
        AppLog.i(tag, "哔哩必连接收端已启动 device=${NvaDevice.displayName(this.context)} usn=${NvaDevice.usn(this.context)}")
    }

    /**
     * 停止投屏服务。
     *
     * 必须在"退出应用"时调用：SSDP 组播线程与 9958 监听线程是进程级的，
     * 不会随 Activity 结束而消失，否则会出现"已经退出、手机还能投屏"。
     */
    fun shutdown() {
        if (!started.compareAndSet(true, false)) return
        stopTick()
        synchronized(stateLock) {
            runCatching { ssdp?.stop() }
            ssdp = null
            runCatching { server?.stop() }
            server = null
        }
        sessions.clear()
        bridge = null
        lastReportedPlayState = -1
        lastReportedPositionSec = -1L
        lastReportedQnKey = null
        lastPlayKey = null
        lastPlayAtMs = 0L
        cachedPlayState = 7
        cachedPositionSec = 0L
        cachedDurationSec = 0L
        AppLog.i(tag, "投屏服务已停止（应用退出），手机端将搜不到本设备")
    }

    /**
     * 设置页改完设备名后调用：立刻把新名字广播出去。
     *
     * **不重建 socket**。改名需要的是"让控制点重拉描述"：BOOTID 递增 → LOCATION 变化
     * （`/desc/<bootId>/description.xml`）→ 下一轮 NOTIFY 就带新地址，客户端自然会重新 GET。
     * 重建 socket 一旦端口没及时释放就 bind 失败，结果是**改名后这台电视直接从
     * 投屏列表里消失**，比"名字不对"严重得多。
     */
    fun onDeviceNameChanged() {
        val name = NvaDevice.displayName(context)
        val target = ssdp
        if (target == null) {
            AppLog.w(tag, "SSDP 尚未启动，设备名「$name」将在下次启动时生效")
            return
        }
        // 兜底再递增一次：LOCATION 里带的就是这个值，没变就等于没生效。
        NvaDevice.bumpBootId(context)
        AppLog.i(tag, "设备名称已更新为「$name」，立即重播 SSDP 通知")
        target.announceNow()
    }

    /**
     * 广播自愈：bind 失败或收发线程意外退出时，退避重启 SSDP。
     *
     * 没有这个兜底，一次 bind 失败就等于这台电视永远搜不到（只能重启 App）。
     * 重试间隔至少 60 秒，避免端口被别的进程长期占用时反复重启刷日志。
     */
    private fun ensureSsdpHealthy() {
        val target = ssdp ?: return
        if (target.isHealthy()) return
        val now = System.currentTimeMillis()
        if (now - lastHealAtMs < HEAL_MIN_INTERVAL_MS) return
        lastHealAtMs = now
        AppLog.w(tag, "SSDP 广播不健康（端口占用或线程退出），安排自动重启")
        thread(name = "nva-ssdp-heal", isDaemon = true) {
            synchronized(stateLock) {
                if (!started.get()) return@thread
                val current = ssdp ?: return@thread
                if (current.isHealthy()) return@thread
                runCatching { current.restart() }
                    .onFailure { AppLog.e(tag, "SSDP 自愈重启失败", it) }
            }
        }
    }

    // region 播放页桥接

    /** 播放页 onStart 时注册；注册成功即向手机端同步一次当前状态。 */
    fun attachBridge(playerBridge: NvaPlayerBridge) {
        if (!started.get()) return
        bridge = playerBridge
        AppLog.i(tag, "播放页已就绪，开始状态同步")
        mainHandler.post {
            sessions.forEach { pushStateTo(it, force = true) }
        }
    }

    /** 播放页 onDestroy 时注销；无播放页后手机端收到"已停止"。 */
    fun detachBridge(playerBridge: NvaPlayerBridge) {
        if (bridge === playerBridge) {
            bridge = null
            lastReportedPlayState = -1
            lastReportedPositionSec = -1L
            lastReportedQnKey = null
            sessions.forEach { it.sendCommand("OnPlayState", JSONObject().put("playState", 7)) }
        }
    }

    // endregion

    // region 会话管理

    fun onSessionOpened(session: NvaSession) {
        sessions.add(session)
        AppLog.i(tag, "手机端已连接 client=${session.clientUuid} session=${session.sessionId}")
        // 空闲期 tick 是 5s 一档，连上后立刻切回 1s 节奏，避免控制条进度出现空窗。
        rescheduleTickNow()
        // 新连接立刻同步一次当前状态，便于手机端恢复控制条。
        mainHandler.post {
            if (bridge == null) {
                // 没有播放页时必须回一个明确的"已停止"：直接 return 的话，
                // 手机端收不到任何状态帧，控制条会一直停在加载态，表现为"投屏没反应"。
                session.sendCommand("OnPlayState", JSONObject().put("playState", 7))
            } else {
                pushStateTo(session, force = true)
            }
        }
    }

    fun onSessionClosed(session: NvaSession) {
        sessions.remove(session)
        AppLog.i(tag, "手机端已断开 client=${session.clientUuid} 剩余=${sessions.size}")
    }

    // endregion

    // region 命令处理

    fun handleCommand(session: NvaSession, method: String, params: JSONObject?) {
        when (method) {
            "Play" -> handlePlay(params)
            "PlayUrl" -> handlePlayUrl(params)
            "Pause" -> onBridgeAndSync(session) { it.pause() }
            "Resume" -> onBridgeAndSync(session) { it.resume() }
            "Stop" -> onBridge { it.stopAndExit() }
            "Seek" -> {
                val seekTs = params?.optLong("seekTs", 0L) ?: 0L
                onBridgeAndSync(session) { it.seekTo(seekTs.coerceAtLeast(0L) * 1000L) }
            }
            "SwitchSpeed" -> {
                val speed = params?.optDouble("speed", 1.0)?.toFloat() ?: 1f
                onBridgeAndSync(session) { it.setSpeed(speed.coerceIn(0.25f, 4f)) }
            }
            "SwitchQn" -> {
                val qn = params?.optInt("qn", 0) ?: 0
                if (qn > 0) onBridgeAndSync(session) { it.selectQuality(qn) }
            }
            "SwitchDanmaku" -> {
                // 注意：手机端下发的 open 是字符串 "true"/"false"
                val raw = params?.opt("open")
                val enabled = raw == true || raw?.toString()?.equals("true", ignoreCase = true) == true
                onBridgeAndSync(session) { it.setDanmakuEnabled(enabled) }
            }
            "SetVolume" -> {
                val volume = params?.optInt("volume", -1) ?: -1
                if (volume >= 0) setVolumePercent(volume)
            }
            "SendDanmaku" -> {
                // 发送弹幕需要 TV 端登录态，暂不实现；不影响接收与播放。
                AppLog.i(tag, "忽略 SendDanmaku（未实现发送）")
            }
            // 正常走 NvaSession 的即时应答分支，不会到这里；保留两种拼写防客户端版本差异。
            "GetTVInfo", "GetTvInfo" -> Unit
            else -> AppLog.w(tag, "未处理的命令: $method")
        }
    }

    private fun handlePlay(params: JSONObject?) {
        if (params == null) return
        val aid = params.optLong("aid", 0L)
        val cid = params.optLong("cid", 0L)
        val epId = params.optLong("epId", params.optLong("epid", 0L))
        val seasonId = params.optLong("seasonId", params.optLong("season_id", 0L))
        val roomId = params.optLong("roomId", 0L)
        val seekTs = params.optLong("seekTs", 0L)
        val desiredQn = params.optInt("userDesireQn", params.optInt("desire_qn", 0))

        AppLog.i(
            tag,
            "Play aid=$aid cid=$cid epId=$epId seasonId=$seasonId roomId=$roomId seekTs=$seekTs qn=$desiredQn",
        )

        if (roomId > 0) {
            AppLog.w(tag, "暂不支持直播投屏 roomId=$roomId")
            return
        }
        if (aid <= 0 && epId <= 0) {
            AppLog.w(tag, "Play 缺少可用标识 aid/epId")
            return
        }

        // 手机端 dlna 面板模式对同一视频会连发两次 Play（实测间隔 200~300ms），
        // 第二次会触发整套 detail/playurl 重请求，加重风控（-400 请求错误），直接忽略。
        val playKey = "$aid|$cid|$epId|$seasonId"
        val now = SystemClock.elapsedRealtime()
        if (playKey == lastPlayKey && now - lastPlayAtMs < PLAY_DEDUPE_WINDOW_MS) {
            AppLog.i(tag, "忽略 ${now - lastPlayAtMs}ms 内的重复 Play key=$playKey")
            return
        }
        lastPlayKey = playKey
        lastPlayAtMs = now

        mainHandler.post {
            runCatching {
                // PlayerActivity 已实现 onNewIntent 换源：在播时 CLEAR_TOP+singleTop
                // 直接走 onNewIntent 切视频，无需像参考实现那样先 finish 旧实例。
                // fromCast=true：跳过 tag 内容过滤，避免手机端选好的视频被 TV 端过滤
                // 拦截后"投屏即退出"（拦截还会顺带拉黑+点踩，污染用户黑名单）。
                PlayerActivity.start(
                    context = context,
                    aid = aid,
                    cid = cid,
                    epId = epId,
                    seasonId = seasonId,
                    seekPositionMs = seekTs.coerceAtLeast(0L) * 1000L,
                    desiredQn = desiredQn,
                    fromCast = true,
                )
                // 手机端每次投新视频都会重置分辨率面板等待新的 OnQnSwitch；换源后
                // 即使"当前档+可选档"指纹与上一视频相同也必须重发，否则面板永远
                // 停在"自动分辨率"。置空指纹让下一个 tick（≤1s）强制下发完整列表。
                lastReportedQnKey = null
            }.onFailure {
                // 后台启动 Activity 受限（Android 10+）等场景会到这里，日志留痕。
                AppLog.e(tag, "启动播放页失败（App 可能在后台，Android 10+ 限制后台启动界面）", it)
            }
        }
    }

    /** iOS HD 版走 PlayUrl，把播放信息塞在 url 的 nva_ext 查询参数里。 */
    private fun handlePlayUrl(params: JSONObject?) {
        val url = params?.optString("url").orEmpty()
        if (url.isBlank()) return
        val nvaExt = runCatching { Uri.parse(url).getQueryParameter("nva_ext") }.getOrNull() ?: return
        val root = runCatching { JSONObject(nvaExt) }.getOrNull() ?: return
        val content = root.optJSONObject("content") ?: return
        handlePlay(content)
    }

    // endregion

    // region 状态上报

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        val runnable =
            object : Runnable {
                override fun run() {
                    if (!started.get()) {
                        // 服务已停止，轮询也必须自己结束，否则每 1 秒空转一次。
                        tickScheduled = false
                        tickRunnable = null
                        return
                    }
                    runCatching { pushStateToAll() }
                    // 每 15 个 tick 顺手体检一次广播线程，bind 失败也能自己恢复。
                    if (++healthTickCounter >= HEALTH_CHECK_TICKS) {
                        healthTickCounter = 0
                        runCatching { ensureSsdpHealthy() }
                    }
                    mainHandler.postDelayed(this, currentTickIntervalMs())
                }
            }
        tickRunnable = runnable
        mainHandler.postDelayed(runnable, currentTickIntervalMs())
    }

    /** 有无手机连接决定轮询节奏：有连接 1s（控制条要跟手），无连接 5s。 */
    private fun currentTickIntervalMs(): Long =
        if (sessions.isEmpty()) IDLE_STATE_TICK_MS else STATE_TICK_MS

    /**
     * 会话变化的瞬间按新节奏重排下一次 tick。
     * 不重排的话，从空闲切到有会话时最长要等 5s 才会恢复 1Hz，
     * 手机端控制条会出现一段"位置不动"的空窗。
     */
    private fun rescheduleTickNow() {
        val runnable = tickRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        mainHandler.postDelayed(runnable, 0L)
    }

    private fun stopTick() {
        tickRunnable?.let { mainHandler.removeCallbacks(it) }
        tickRunnable = null
        tickScheduled = false
    }

    private fun pushStateToAll() {
        // 本方法只由主线程 tick 调用，播放器读写都必须在主线程完成。
        // 同时刷新缓存，供 GetPlayInfo（socket 读线程）使用。
        val playerBridge = bridge
        val snapshot = playerBridge?.stateSnapshot()
        if (snapshot != null) {
            cachedDurationSec = snapshot.durationSec
            cachedPositionSec = snapshot.positionSec
            cachedPlayState = snapshot.playState
        } else {
            cachedPlayState = 7
        }

        if (sessions.isEmpty() || playerBridge == null || snapshot == null) return

        val durationSec = snapshot.durationSec
        val positionSec = snapshot.positionSec
        val playState = snapshot.playState

        // 手机端"分辨率"菜单的档位列表完全来自 OnQnSwitch 的 supportQnList，
        // 所以指纹含"当前档+可选档列表"：换视频后即使档位相同也要重发。
        val qnKey = qnReportKey(snapshot)
        val qnReportable = snapshot.currentQn > 0 || snapshot.supportQnList.isNotEmpty()

        sessions.forEach { session ->
            if (positionSec != lastReportedPositionSec || playState != session.lastPlayState) {
                session.sendCommand(
                    "OnProgress",
                    JSONObject().put("duration", durationSec).put("position", positionSec),
                )
            }

            if (playState != session.lastPlayState) {
                session.lastPlayState = playState
                session.sendCommand("OnPlayState", JSONObject().put("playState", playState))
            }

            if (qnReportable && qnKey != lastReportedQnKey) {
                session.sendCommand("OnQnSwitch", buildQnSwitchPayload(snapshot))
            }
        }

        lastReportedPositionSec = positionSec
        lastReportedPlayState = playState
        if (qnReportable) lastReportedQnKey = qnKey
    }

    private fun pushStateTo(session: NvaSession, force: Boolean) {
        val playerBridge = bridge ?: return
        val snapshot = playerBridge.stateSnapshot()
        if (force || snapshot.positionSec != lastReportedPositionSec) {
            session.sendCommand("OnProgress", JSONObject().put("duration", snapshot.durationSec).put("position", snapshot.positionSec))
        }
        if (force || snapshot.playState != session.lastPlayState) {
            session.lastPlayState = snapshot.playState
            session.sendCommand("OnPlayState", JSONObject().put("playState", snapshot.playState))
        }
        // 手机端（重）连后必须立刻拿到可选清晰度列表，"分辨率"菜单才不会停在"自动"。
        if (force && (snapshot.currentQn > 0 || snapshot.supportQnList.isNotEmpty())) {
            session.sendCommand("OnQnSwitch", buildQnSwitchPayload(snapshot))
        }
    }

    /** 上次已上报的清晰度指纹：当前档 + 可选档序列。 */
    private fun qnReportKey(snapshot: NvaPlayerSnapshot): String =
        buildString {
            append(snapshot.currentQn)
            snapshot.supportQnList.forEach { append('|').append(it.quality) }
        }

    /** OnQnSwitch 载荷（结构对齐官方 TV 端，supportQnList 是手机端分辨率菜单的数据源）。 */
    private fun buildQnSwitchPayload(snapshot: NvaPlayerSnapshot): JSONObject =
        JSONObject()
            .put("curQn", snapshot.currentQn)
            .put("userDesireQn", snapshot.currentQn)
            .put(
                "supportQnList",
                JSONArray().apply {
                    snapshot.supportQnList.forEach { option ->
                        put(
                            JSONObject()
                                .put("quality", option.quality)
                                .put("displayDesc", option.displayDesc)
                                .put("description", option.description)
                                .put("needLogin", option.needLogin)
                                .put("needVip", option.needVip)
                                .put("superscript", option.superscript),
                        )
                    }
                },
            )

    // endregion

    // region 查询接口

    fun currentVolume(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
    }

    private fun setVolumePercent(percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (percent.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    fun tvInfo(): JSONObject =
        JSONObject()
            .put("deviceName", NvaDevice.displayName(context))
            .put("deviceId", NvaDevice.usn(context))
            .put("version", "1")
            .put("capability", 254)

    fun playInfo(): JSONObject =
        JSONObject().apply {
            // 注意：本方法在 socket 读线程被调用，禁止直接访问播放器。
            put("duration", cachedDurationSec)
            put("position", cachedPositionSec)
            put("playState", cachedPlayState)
        }

    // endregion

    private fun onBridge(block: (NvaPlayerBridge) -> Unit) {
        mainHandler.post {
            val playerBridge = bridge ?: return@post
            runCatching { block(playerBridge) }.onFailure { AppLog.w(tag, "播放器操作失败", it) }
        }
    }

    /**
     * 执行控制命令后立刻向该手机强制同步一次完整状态。
     *
     * 幂等命令（如 TV 已在播放时收到 Resume）不会引起状态变化，常规 tick 只在
     * 状态变化时才回传 OnPlayState——手机端面板会卡在旧状态，表现为"点暂停/
     * 播放没反应"（实测用户连点 5 次 Resume）。命令回包 + 状态强推双通道对齐。
     */
    private fun onBridgeAndSync(session: NvaSession, block: (NvaPlayerBridge) -> Unit) {
        mainHandler.post {
            val playerBridge = bridge ?: return@post
            runCatching { block(playerBridge) }.onFailure { AppLog.w(tag, "播放器操作失败", it) }
            pushStateTo(session, force = true)
        }
    }

    companion object {
        /** 有手机连接时：1s 刷新一次状态快照，供手机端控制条使用。 */
        private const val STATE_TICK_MS = 1_000L

        /**
         * 无连接时降到 5s。投屏绝大多数时间没人用，1Hz 的主线程唤醒纯属空转；
         * 会话建立时 [onSessionOpened] 会立即重排，不会出现"刚连上要等 5s"的空窗。
         */
        private const val IDLE_STATE_TICK_MS = 5_000L

        /** 同一视频重复 Play 的去重窗口。 */
        private const val PLAY_DEDUPE_WINDOW_MS = 800L

        /** 每 15 个 tick（约 15 秒）做一次 SSDP 健康检查。 */
        private const val HEALTH_CHECK_TICKS = 15

        /** 自愈重启的最小间隔，避免端口被长期占用时反复重启。 */
        private const val HEAL_MIN_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: NvaCastController? = null

        /**
         * 按设置开关安装接收端。开关关闭时不启动任何线程；已启动且开关被关闭时
         * 由 [setEnabled] 负责 shutdown。幂等：退出后重新进入主界面再次调用会重新拉起。
         */
        fun install(context: Context) {
            if (!NvaDevice.isReceiveEnabled(context)) {
                get()?.shutdown()
                return
            }
            val controller =
                instance ?: synchronized(this) {
                    instance ?: NvaCastController().also { instance = it }
                }
            // start 内部用 CAS 保证幂等。
            controller.start(context)
        }

        /**
         * 设置页切换"手机投屏"开关：写存储并立即生效（开=拉起，关=停止）。
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            NvaDevice.setReceiveEnabled(context, enabled)
            if (!enabled) {
                get()?.shutdown()
                return
            }
            install(context)
        }

        internal fun get(): NvaCastController? = instance
    }
}
