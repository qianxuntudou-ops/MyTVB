package com.mytvb.feature.player.danmaku

import com.mytvb.core.common.log.AppLog
import com.mytvb.model.live.ChatHostUrlModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class LiveDanmakuClient(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "LiveDanmakuClient"
        private const val WS_URL = "wss://broadcastlv.chat.bilibili.com/sub"
        private const val HEADER_SIZE = 16
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_BODY = "[object Object]"

        private const val PROTO_JSON = 0
        private const val PROTO_HEARTBEAT = 1
        private const val PROTO_DEFLATE = 2
        private const val PROTO_BROTLI = 3

        private const val OP_HEARTBEAT = 2
        private const val OP_HEARTBEAT_REPLY = 3
        private const val OP_COMMAND = 5
        private const val OP_AUTH = 7
        private const val OP_AUTH_REPLY = 8
    }

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    @Volatile
    private var disposed = false
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val _danmakuMessages = MutableSharedFlow<LiveDanmakuMessage>(extraBufferCapacity = 64)
    val danmakuMessages: SharedFlow<LiveDanmakuMessage> = _danmakuMessages.asSharedFlow()

    private val _superChatSignals = MutableSharedFlow<LiveSuperChatSignal>(extraBufferCapacity = 16)
    val superChatSignals: SharedFlow<LiveSuperChatSignal> = _superChatSignals.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, FAILED
    }

    fun connect(
        roomId: Long,
        token: String,
        uid: Long = 0L,
        hostList: List<ChatHostUrlModel> = emptyList()
    ) {
        disconnect()
        disposed = false
        _connectionState.value = ConnectionState.CONNECTING
        AppLog.d(TAG, "connect: roomId=$roomId uid=$uid")

        val request = Request.Builder().url(resolveWsUrl(hostList)).build()

        webSocket = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    AppLog.d(TAG, "WebSocket onOpen")
                    sendAuth(roomId, token, uid)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AppLog.e(TAG, "WebSocket onFailure: ${t.message}", t)
                    _connectionState.value = ConnectionState.FAILED
                    stopHeartbeat()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    AppLog.d(TAG, "WebSocket onClosed: code=$code reason=$reason")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopHeartbeat()
                }
            })
    }

    private fun resolveWsUrl(hostList: List<ChatHostUrlModel>): String {
        val host = hostList.firstOrNull { it.host.isNotBlank() } ?: return WS_URL
        val port = host.wssPort.takeIf { it > 0 }
        return if (port != null) {
            "wss://${host.host}:$port/sub"
        } else {
            "wss://${host.host}/sub"
        }
    }

    fun disconnect() {
        stopHeartbeat()
        disposed = true
        webSocket?.close(1000, "disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun sendAuth(roomId: Long, token: String, uid: Long) {
        val body = JSONObject().apply {
            put("uid", uid)
            put("roomid", roomId)
            put("protover", 2)
            put("platform", "web")
            put("type", 2)
            put("key", token)
        }.toString().toByteArray(Charsets.UTF_8)
        sendPacket(buildPacket(PROTO_JSON, OP_AUTH, body))
    }

    private fun sendHeartbeat() {
        val body = HEARTBEAT_BODY.toByteArray(Charsets.UTF_8)
        sendPacket(buildPacket(PROTO_JSON, OP_HEARTBEAT, body))
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        sendHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendPacket(data: ByteArray) {
        if (webSocket?.send(data.toByteString()) != true) {
            AppLog.w(TAG, "sendPacket failed: WebSocket not ready")
        }
    }

    private var packetCount = 0

    private fun handleBinaryMessage(data: ByteArray) {
        if (disposed) return
        try {
            packetCount++
            val packets = parsePackets(data)
            if (packetCount <= 10 || packets.any { it.operation == OP_COMMAND }) {
                AppLog.d(TAG, "handleBinary[#${packetCount}]: recv=${data.size}B parsed=${packets.size} ops=${packets.map { it.operation }}")
            }
            packets.forEach { processPacket(it) }
        } catch (e: Exception) {
            AppLog.e(TAG, "handleBinaryMessage error: ${e.message}", e)
        }
    }

    private fun processPacket(packet: RawPacket) {
        when (packet.operation) {
            OP_HEARTBEAT_REPLY -> _connectionState.value = ConnectionState.CONNECTED
            OP_AUTH_REPLY -> {
                val bodyStr = packet.body.toString(Charsets.UTF_8)
                AppLog.d(TAG, "Auth reply: $bodyStr")
                _connectionState.value = ConnectionState.CONNECTED
                startHeartbeat()
            }
            OP_COMMAND -> parseCommand(packet.body.toString(Charsets.UTF_8))
            else -> AppLog.d(TAG, "unknown op=${packet.operation} ver=${packet.protoVer} bodyLen=${packet.body.size}")
        }
    }

    private fun parseCommand(json: String) {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return

        var depth = 0
        var inString = false
        var escape = false
        var start = 0

        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{' || c == '[') depth++
            if (c == '}' || c == ']') {
                depth--
                if (depth == 0) {
                    val singleJson = trimmed.substring(start, i + 1).trim()
                    if (singleJson.startsWith("{")) {
                        handleSingleCommand(singleJson)
                    }
                    start = i + 1
                }
            }
        }
    }

    private fun handleSingleCommand(json: String) {
        try {
            val obj = JSONObject(json)
            val cmd = obj.optString("cmd") ?: return
            AppLog.d(TAG, "cmd=$cmd")

            if (cmd.startsWith("DANMU_MSG")) {
                val info = obj.optJSONArray("info") ?: return
                if (info.length() < 2) return
                val content = info.optString(1) ?: return
                val info0 = info.optJSONArray(0)
                // info[0]: [rand, mode, fontsize, color, timestamp, pool, dmidStr, userHash]
                val color = info0?.optInt(3) ?: 0xFFFFFF
                AppLog.d(TAG, "DANMU: $content color=$color")
                val emitted = _danmakuMessages.tryEmit(
                    LiveDanmakuMessage(
                        type = LiveDanmakuMessage.Type.DANMU,
                        content = content,
                        color = color
                    )
                )
                AppLog.d(TAG, "DANMU emitted=$emitted")
            } else if (cmd == "SUPER_CHAT_MESSAGE") {
                handleSuperChatMessage(obj.optJSONObject("data"))
            } else if (cmd == "SUPER_CHAT_MESSAGE_DELETE") {
                val data = obj.optJSONObject("data") ?: return
                val id = data.optLong("id", -1L)
                if (id > 0) {
                    _superChatSignals.tryEmit(LiveSuperChatSignal.Delete(id))
                }
            } else if (cmd.isCmd("ANCHOR_LOT_START") || cmd.isCmd("POPULARITY_RED_POCKET_START")) {
                handleLotteryDanmu(cmd, obj.optJSONObject("data"))
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "handleSingleCommand error: ${e.message}")
        }
    }

    /**
     * 天选时刻 / 人气红包的参与口令：两条命令的 data 都带 danmu（观众参与时代发的弹幕）。
     * 当成一条不上屏的消息交给 Manager 记入本场屏蔽。data 全量打日志——文档只给了示例没给字段表。
     */
    private fun handleLotteryDanmu(cmd: String, data: JSONObject?) {
        val danmu = LiveLotteryDanmakuFilter.keywordOf(data?.optString("danmu"))
        AppLog.d(TAG, "LOTTERY: cmd=$cmd danmu=$danmu data=$data")
        if (danmu == null) return
        val emitted = _danmakuMessages.tryEmit(
            LiveDanmakuMessage(type = LiveDanmakuMessage.Type.LOTTERY_DANMU, content = danmu)
        )
        AppLog.d(TAG, "LOTTERY emitted=$emitted")
    }

    /** 命令名可带冒号后缀（如 DANMU_MSG:4:0:2:2:2:2），按前缀认。 */
    private fun String.isCmd(name: String): Boolean = this == name || startsWith("$name:")

    /** SC 字段形态官方文档未公开，background_color 实测有十进制 int 与 hex 字符串两种，解析全部兜底并打日志供真机校验。 */
    private fun handleSuperChatMessage(data: JSONObject?) {
        if (data == null) return
        val message = data.optString("message", "")
        if (message.isBlank()) return

        val userInfo = data.optJSONObject("user_info")
        val userName = userInfo?.optString("uname", "").orEmpty().ifBlank { "匿名" }
        val avatarUrl = userInfo?.optString("face", "").orEmpty()
        val avatarFrameUrl = userInfo?.optString("face_frame", "").orEmpty()

        val price = data.optLong("price", 0L)
        // time 为 SC 总时长（秒）；缺失时用 end_time - start_time 兜底，再不行给 60s
        val durationSec = when {
            data.optLong("time", 0L) > 0 -> data.optLong("time")
            data.optLong("end_time", 0L) > 0 && data.optLong("start_time", 0L) > 0 ->
                data.optLong("end_time") - data.optLong("start_time")
            else -> 60L
        }.coerceIn(5L, 3600L)

        val backgroundColor = parseSuperChatColor(data.opt("background_color"))

        AppLog.i(
            TAG,
            "SUPER_CHAT: id=${data.optLong("id")} user=$userName price=$price time=${durationSec}s " +
                "bg=#${Integer.toHexString(backgroundColor)} raw_bg=${data.opt("background_color")} data=$data"
        )

        _superChatSignals.tryEmit(
            LiveSuperChatSignal.New(
                id = data.optLong("id", 0L),
                userName = userName,
                avatarUrl = avatarUrl,
                message = message,
                priceYuan = price,
                backgroundColor = backgroundColor,
                durationSec = durationSec,
                avatarFrameUrl = avatarFrameUrl
            )
        )
    }

    private fun parseSuperChatColor(raw: Any?): Int {
        // 兜底官方 SC 蓝紫系主色，保证解析失败时卡片仍有可读底色
        val fallback = 0xFF68C5F0.toInt()
        return when (raw) {
            is Int -> if (raw != 0) (0xFF000000.toInt() or raw) else fallback
            is Long -> if (raw != 0L) (0xFF000000.toInt() or raw.toInt()) else fallback
            is String -> raw.removePrefix("#").trim().takeIf { it.length == 6 }
                ?.let { runCatching { (0xFF000000.toInt() or it.toInt(16)) }.getOrNull() } ?: fallback
            else -> fallback
        }
    }

    // ---- 协议编解码 ----

    private data class RawPacket(
        val protoVer: Int,
        val operation: Int,
        val body: ByteArray
    )

    private fun buildPacket(protoVer: Int, operation: Int, body: ByteArray): ByteArray {
        val totalLen = HEADER_SIZE + body.size
        return ByteBuffer.allocate(totalLen).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(totalLen)
            putShort(HEADER_SIZE.toShort())
            putShort(protoVer.toShort())
            putInt(operation)
            putInt(0)
            put(body)
        }.array()
    }

    private fun parsePackets(data: ByteArray): List<RawPacket> {
        val packets = mutableListOf<RawPacket>()
        var offset = 0

        while (offset + HEADER_SIZE <= data.size) {
            val buf = ByteBuffer.wrap(data, offset, data.size - offset)
            buf.order(ByteOrder.BIG_ENDIAN)

            val totalLen = buf.int
            val headerLen = buf.short.toInt()
            val protoVer = buf.short.toInt()
            val operation = buf.int
            buf.int // sequence

            if (totalLen < HEADER_SIZE || offset + totalLen > data.size) break

            val bodySize = totalLen - headerLen
            val rawBody = if (bodySize > 0) data.copyOfRange(offset + headerLen, offset + totalLen) else ByteArray(0)

            when (protoVer) {
                PROTO_JSON, PROTO_HEARTBEAT -> packets.add(RawPacket(protoVer, operation, rawBody))
                PROTO_DEFLATE -> inflateData(rawBody)?.let { if (it.size > HEADER_SIZE) packets.addAll(parsePackets(it)) }
                PROTO_BROTLI -> brotliDecompress(rawBody)?.let { if (it.size > HEADER_SIZE) packets.addAll(parsePackets(it)) }
                else -> packets.add(RawPacket(protoVer, operation, rawBody))
            }
            offset += totalLen
        }
        return packets
    }

    private fun inflateData(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                output.write(buffer, 0, count)
            }
            inflater.end()
            output.toByteArray()
        } catch (e: Exception) {
            AppLog.w(TAG, "inflateData error: ${e.message}")
            null
        }
    }

    private fun brotliDecompress(data: ByteArray): ByteArray? {
        return try {
            val clazz = Class.forName("org.brotli.dec.BrotliInputStream")
            val ctor = clazz.getConstructor(java.io.InputStream::class.java)
            val input = ctor.newInstance(ByteArrayInputStream(data)) as java.io.InputStream
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                output.write(buffer, 0, count)
            }
            input.close()
            output.toByteArray()
        } catch (_: ClassNotFoundException) {
            inflateData(data)
        } catch (e: Exception) {
            AppLog.w(TAG, "brotliDecompress fallback: ${e.message}")
            inflateData(data)
        }
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
