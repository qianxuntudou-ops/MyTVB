package com.mytvb.feature.player.danmaku

import com.mytvb.core.common.coroutine.catchingRequest
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.player.settings.PlayerSettingsStore
import com.mytvb.model.dm.DmModel
import com.mytvb.model.live.ChatRoomWrapper
import com.mytvb.network.WbiGenerator
import com.mytvb.network.api.ApiService
import com.mytvb.network.session.NetworkSessionGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class LiveDanmakuManager(
    private val apiService: ApiService,
    private val okHttpClient: OkHttpClient,
    private val sessionGateway: NetworkSessionGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 设置→弹幕→过滤「屏蔽抽奖弹幕」，每条弹幕读一次（读的是内存缓存，改设置即时生效）。 */
    private val isLotteryDanmakuBlocked: () -> Boolean = { PlayerSettingsStore.isLotteryDanmakuBlocked() }
) {
    companion object {
        private const val TAG = "LiveDanmakuManager"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val WEB_LOCATION_LIVE_ROOM = "444.8"
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var connectJob: Job? = null
    private var currentRoomId: Long = 0L
    private var danmakuIdCounter = 0L

    // 本场抽奖口令（天选/人气红包的 danmu）：断线重连保留，换房或退出直播清空
    private val blockedLotteryDanmu: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _danmakuFlow = MutableSharedFlow<DmModel>(extraBufferCapacity = 128)
    val danmakuFlow: SharedFlow<DmModel> = _danmakuFlow.asSharedFlow()

    private val _superChatFlow = MutableSharedFlow<LiveSuperChatSignal>(extraBufferCapacity = 16)
    val superChatFlow: SharedFlow<LiveSuperChatSignal> = _superChatFlow.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun start(roomId: Long) {
        // 同一场重复 start（重建连接）保留已收到的口令，换房才清
        val sameRoom = roomId == currentRoomId
        stop()
        if (!sameRoom) blockedLotteryDanmu.clear()
        currentRoomId = roomId
        connectJob = scope.launch {
            var attempt = 0
            while (attempt < MAX_RECONNECT_ATTEMPTS && scope.isActive) {
                try {
                    connectOnce(roomId)
                    // connectOnce 正常返回说明连接断开了，重置计数后重试
                    attempt = 0
                    AppLog.w(TAG, "WebSocket disconnected, reconnecting in ${RECONNECT_DELAY_MS}ms")
                    delay(RECONNECT_DELAY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    AppLog.w(TAG, "connect failed (attempt $attempt/$MAX_RECONNECT_ATTEMPTS): ${e.message}")
                    if (attempt < MAX_RECONNECT_ATTEMPTS) {
                        delay(RECONNECT_DELAY_MS)
                    }
                }
            }
            AppLog.e(TAG, "reconnect exhausted for roomId=$roomId")
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        currentRoomId = 0L
        _isConnected.value = false
    }

    fun release() {
        stop()
        blockedLotteryDanmu.clear()
        scope.cancel()
    }

    private suspend fun connectOnce(roomId: Long) {
        val danmuConfig = getDanmuConfig(roomId)
        val hostList = danmuConfig.hostList.orEmpty()
        AppLog.d(TAG, "connectOnce: token=${danmuConfig.token.take(10)}... hosts=${hostList.size}")

        val uid = try {
            sessionGateway.getUserInfo()?.mid ?: 0L
        } catch (_: Exception) { 0L }

        val client = LiveDanmakuClient(okHttpClient, ioDispatcher)

        val messageJob = scope.launch {
            client.danmakuMessages.collect { message ->
                when (message.type) {
                    LiveDanmakuMessage.Type.DANMU -> {
                        if (LiveLotteryDanmakuFilter.shouldDrop(
                                message.content, isLotteryDanmakuBlocked(), blockedLotteryDanmu
                            )
                        ) {
                            AppLog.d(TAG, "DANMU dropped as lottery danmu: ${message.content}")
                            return@collect
                        }
                        val dmModel = DmModel(
                            id = ++danmakuIdCounter,
                            content = message.content,
                            color = message.color,
                            mode = 1,
                            fontSize = 25,
                            progress = 0
                        )
                        _danmakuFlow.tryEmit(dmModel)
                    }
                    // 抽奖口令只登记不上屏；开关关着也登记，中途打开开关时本场已开的抽奖立即生效
                    LiveDanmakuMessage.Type.LOTTERY_DANMU -> {
                        val keyword = LiveLotteryDanmakuFilter.normalize(message.content)
                        if (blockedLotteryDanmu.add(keyword)) {
                            AppLog.i(TAG, "lottery danmu registered keyword=$keyword total=${blockedLotteryDanmu.size}")
                        }
                    }
                    else -> Unit
                }
            }
        }

        // SC 是付费内容，不进弹幕过滤，全量转发
        val superChatJob = scope.launch {
            client.superChatSignals.collect { signal ->
                _superChatFlow.tryEmit(signal)
            }
        }

        // 与 WS 并行补拉一次当前抽奖口令（此时弹幕服务器配置已拿到，密钥是新的，直接复用）
        val lotteryJob = scope.launch { prefetchLotteryDanmu(roomId, uid) }

        var stateJob: Job? = null
        try {
            client.connect(roomId, danmuConfig.token, uid, hostList)
            // 监听连接状态，断开时取消 messageJob 让 connectOnce 结束。
            // 必须在 connect() 之后再订阅：状态流的初始值是 DISCONNECTED，和 connect() 并发启动时，
            // 订阅若先跑会把这个初始值当成「已断开」而取消 messageJob——首连白费，空等一个重连间隔。
            // connect() 返回时状态已是 CONNECTING（或更早的 FAILED），首次值不会再是初始值
            stateJob = scope.launch {
                client.connectionState.collect { state ->
                    _isConnected.value = state == LiveDanmakuClient.ConnectionState.CONNECTED
                    if (state == LiveDanmakuClient.ConnectionState.FAILED ||
                        state == LiveDanmakuClient.ConnectionState.DISCONNECTED
                    ) {
                        messageJob.cancel()
                    }
                }
            }
            // 等待 messageJob 结束（连接断开时会 cancel）
            messageJob.join()
        } finally {
            stateJob?.cancel()
            lotteryJob.cancel()
            client.release()
            superChatJob.cancel()
        }
    }

    /**
     * 补拉本场当前进行中的抽奖口令。WS 只在抽奖开启那一刻推一次，中途进房（含断线重连）收不到，
     * 从 getLotteryInfoWeb 取。访客态该接口实测直接 -352，所以只在已登录且开关打开时发；
     * 失败不重试——补不到只是本场前半段的口令屏蔽不到。
     */
    private suspend fun prefetchLotteryDanmu(roomId: Long, uid: Long) {
        if (!isLotteryDanmakuBlocked()) {
            AppLog.d(TAG, "lottery prefetch skipped: switch off")
            return
        }
        if (uid <= 0L) {
            AppLog.d(TAG, "lottery prefetch skipped: not logged in")
            return
        }
        catchingRequest {
            val (imgKey, subKey) = sessionGateway.getWbiKeys()
            apiService.getLiveLotteryInfoWeb(
                WbiGenerator.generateWbiParams(
                    mapOf(
                        "roomid" to roomId.toString(),
                        "web_location" to WEB_LOCATION_LIVE_ROOM
                    ),
                    imgKey, subKey
                )
            )
        }.onSuccess { response ->
            val data = response.data
            AppLog.i(TAG, "lottery prefetch code=${response.code} msg=${response.message} data=$data")
            if (response.code != 0 || data == null) return@onSuccess
            // 天选在 anchor，人气红包在 popularity_red_pocket
            for (key in listOf("anchor", "popularity_red_pocket")) {
                for (keyword in LiveLotteryDanmakuFilter.keywordsOf(data.get(key))) {
                    if (blockedLotteryDanmu.add(keyword)) {
                        AppLog.i(
                            TAG,
                            "lottery danmu registered(prefetch $key) keyword=$keyword total=${blockedLotteryDanmu.size}"
                        )
                    }
                }
            }
        }.onFailure { AppLog.w(TAG, "lottery prefetch failed: ${it.message}") }
    }

    private suspend fun getDanmuConfig(roomId: Long): ChatRoomWrapper {
        runCatching { sessionGateway.ensureWbiKeys() }
            .onFailure { AppLog.w(TAG, "ensureWbiKeys failed: ${it.message}") }

        val unsignedParams = mapOf(
            "id" to roomId.toString(),
            "type" to "0",
            "web_location" to WEB_LOCATION_LIVE_ROOM
        )
        val (imgKey, subKey) = sessionGateway.getWbiKeys()
        var response = apiService.getLiveDanmuInfoSigned(
            WbiGenerator.generateWbiParams(unsignedParams, imgKey, subKey)
        )
        if (response.code != 0) {
            // getDanmuInfo 无签名版已被服务端风控（对标 BiliPai 的"强制 WBI 不回退"
            // 结论），失败时唯一正确的路是强制刷新密钥换 key 重试一次，不降级到
            // 无签名请求——那只会再吃一次 -352
            AppLog.w(TAG, "getDanmuConfig: WBI call failed code=${response.code}, refreshing keys for one retry")
            runCatching { sessionGateway.ensureWbiKeys() }
                .onFailure { AppLog.w(TAG, "ensureWbiKeys retry failed: ${it.message}") }
            val (newImgKey, newSubKey) = sessionGateway.getWbiKeys()
            response = apiService.getLiveDanmuInfoSigned(
                WbiGenerator.generateWbiParams(unsignedParams, newImgKey, newSubKey)
            )
        }
        val data = response.data
        if (response.code == 0 && data != null && data.token.isNotBlank()) {
            return data
        }
        AppLog.e(TAG, "getDanmuConfig: WBI call failed after key refresh, code=${response.code} msg=${response.message}")
        throw IllegalStateException("获取弹幕服务器配置失败: code=${response.code}")
    }
}
