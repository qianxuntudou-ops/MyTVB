package com.mytvb.core.common.log

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import com.google.gson.Gson
import com.mytvb.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 局域网日志服务器（自研，零第三方依赖）。
 *
 * ServerSocket 随机端口 + 每连接一线程，只处理 GET、Connection: close。
 * 电视端打开「局域网日志」页面时启动，离开页面即停止；
 * 同网段设备扫二维码（URL 自带 token）即可在浏览器查看实时日志、
 * 崩溃日志与快照，不再依赖 adb。
 *
 * 路由：
 * - `GET /` — 网页 UI（assets/lan_log/index.html，静态壳放行，无 token 也可打开）
 * - `GET /api/status` — 版本/设备/日志开关状态
 * - `GET /api/logs/current` — 内存缓冲实时日志
 * - `GET /api/files/list` — 崩溃日志 + 快照文件列表
 * - `GET /api/files/download?type=crash|snapshot&name=...` — 白名单文件下载
 * - `GET /api/snapshot/create-and-download` — 生成快照并下载
 *
 * 所有 `api` 路由均需 `t=token` 校验，防止同网段他人直接拉取日志。
 */
class LanLogServer(private val context: Context) {

    companion object {
        private const val INDEX_ASSET_PATH = "lan_log/index.html"
        private const val SNAPSHOT_DIR_NAME = "logs"
        private const val SNAPSHOT_PREFIX = "mytvb_log_"
        // 旧版导出前缀，白名单仍放行以便下载历史快照
        private const val LEGACY_SNAPSHOT_PREFIX = "myblbl_log_"
        private const val SOCKET_TIMEOUT_MS = 10_000
        // 冷却期内重复请求复用同一份快照：手机浏览器对 attachment 下载常是
        // WebView 探测 + 下载器重发两次请求（或用户双击），每次都新建会落成对文件
        private const val SNAPSHOT_REUSE_WINDOW_MS = 10_000L
        private val gson = Gson()
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var lastSnapshotFile: File? = null

    @Volatile
    private var lastSnapshotAtMs: Long = 0

    private var connectionPool: ExecutorService? = null

    /** 本次会话随机 token，start() 时重新生成并拼进二维码 URL。 */
    var token: String = ""
        private set

    val port: Int?
        get() = serverSocket?.localPort

    val isRunning: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false

    fun start(): Boolean {
        if (isRunning) return true
        return runCatching {
            token = List(8) { "0123456789abcdef".random() }.joinToString("")
            serverSocket = ServerSocket(0)
            connectionPool = Executors.newCachedThreadPool { r ->
                Thread(r).apply { isDaemon = true }
            }
            acceptThread = thread(isDaemon = true, name = "LanLogServer-accept") {
                acceptLoop()
            }
            true
        }.getOrElse {
            stop()
            AppLog.e("LanLogServer", "start failed", it)
            false
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        connectionPool?.shutdown()
        connectionPool = null
        acceptThread = null
        lastSnapshotFile = null
        lastSnapshotAtMs = 0
    }

    /** 展示用完整地址（当前默认网络 IPv4，见 firstLanIpv4）。 */
    fun buildUrl(): String? {
        val p = port ?: return null
        val ip = firstLanIpv4() ?: return null
        return "http://$ip:$p/?t=$token"
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            connectionPool?.execute { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        runCatching {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { s ->
                // reader 不主动 close（close 会连带关闭 socket），由外层 use 统一收尾
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var requestLine = reader.readLine() ?: return
                // 容忍部分客户端发来的空行前缀
                while (requestLine.isEmpty()) {
                    requestLine = reader.readLine() ?: return
                }
                // 消费剩余请求头：若响应后 socket 接收缓冲仍有未读数据，
                // close 会发 RST 而非 FIN，客户端可能丢弃已收到的响应
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                if (!requestLine.startsWith("GET ")) {
                    respond(s, 405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only")
                    return
                }
                val rawTarget = requestLine.removePrefix("GET ").substringBefore(' ')
                dispatch(s, rawTarget)
            }
        }
    }

    private fun dispatch(socket: Socket, rawTarget: String) {
        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        // 静态壳放行：手动输 IP 的用户也能打开页面（页面内 API 无 token 会给出提示）
        if (path == "/" || path == "/index.html") {
            serveIndex(socket)
            return
        }

        if (!path.startsWith("/api/")) {
            respond(socket, 404, "Not Found", "text/plain; charset=utf-8", "not found")
            return
        }

        if (query["t"] != token) {
            respond(socket, 403, "Forbidden", "application/json; charset=utf-8",
                """{"error":"invalid token, scan the QR code on TV"}""")
            return
        }

        when (path) {
            "/api/status" -> handleStatus(socket)
            "/api/logs/current" -> handleCurrentLogs(socket)
            "/api/files/list" -> handleFileList(socket)
            "/api/files/download" -> handleDownload(socket, query)
            "/api/snapshot/create-and-download" -> handleCreateSnapshot(socket)
            else -> respond(socket, 404, "Not Found", "application/json; charset=utf-8",
                """{"error":"unknown api"}""")
        }
    }

    private fun serveIndex(socket: Socket) {
        val bytes = runCatching {
            context.assets.open(INDEX_ASSET_PATH).use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            respond(socket, 404, "Not Found", "text/plain; charset=utf-8", "index.html missing")
            return
        }
        respond(socket, 200, "OK", "text/html; charset=utf-8", bytes)
    }

    private fun handleStatus(socket: Socket) {
        val body = mapOf(
            "app" to BuildConfig.APPLICATION_ID,
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
            "logEnabled" to AppLog.isEnabled,
            "bufferCount" to AppLog.LogBuffer.getLogs().size
        )
        respondJson(socket, body)
    }

    private fun handleCurrentLogs(socket: Socket) {
        val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        val entries = AppLog.LogBuffer.getLogs().map {
            mapOf(
                "time" to timeFormat.format(it.timestamp),
                "level" to (AppLog.LogEntry.LEVEL_LABELS[it.level] ?: "?"),
                "tag" to it.tag,
                "message" to it.message
            )
        }
        respondJson(socket, mapOf("count" to entries.size, "enabled" to AppLog.isEnabled, "entries" to entries))
    }

    private fun handleFileList(socket: Socket) {
        respondJson(socket, mapOf(
            "crash" to listCrashFiles().map { it.toItem("crash") },
            "snapshot" to listSnapshotFiles().map { it.toItem("snapshot") }
        ))
    }

    private fun File.toItem(type: String): Map<String, Any> = mapOf(
        "name" to name,
        "type" to type,
        "size" to length(),
        "lastModified" to lastModified()
    )

    private fun handleDownload(socket: Socket, query: Map<String, String>) {
        val name = query["name"].orEmpty()
        val file = when (query["type"]) {
            "crash" -> listCrashFiles().find { it.name == name }
            "snapshot" -> listSnapshotFiles().find { it.name == name }
            else -> null
        }
        if (isUnsafeName(name) || file == null || !file.exists()) {
            respond(socket, 404, "Not Found", "application/json; charset=utf-8",
                """{"error":"file not found"}""")
            return
        }
        respondFile(socket, file)
    }

    private fun handleCreateSnapshot(socket: Socket) {
        val file = obtainSnapshot()
        if (file == null || !file.exists()) {
            respond(socket, 500, "Internal Server Error", "application/json; charset=utf-8",
                """{"error":"create snapshot failed"}""")
            return
        }
        respondFile(socket, file)
    }

    /** 生成快照，冷却窗口内直接复用上一份，避免一次点击落成对文件。 */
    private fun obtainSnapshot(): File? = synchronized(this) {
        val last = lastSnapshotFile
        if (last != null && last.exists() &&
            SystemClock.elapsedRealtime() - lastSnapshotAtMs < SNAPSHOT_REUSE_WINDOW_MS
        ) {
            return last
        }
        val fresh = AppLog.exportSnapshot() ?: return null
        lastSnapshotFile = fresh
        lastSnapshotAtMs = SystemClock.elapsedRealtime()
        fresh
    }

    // ── 文件白名单 ────────────────────────────────────────────────────

    /** 崩溃日志白名单：AppLog 管理的 crash_logs 目录 + 旧版 debug.txt。 */
    private fun listCrashFiles(): List<File> = AppLog.getCrashLogFiles()

    /** 快照白名单：外部私有目录 logs/ 下 mytvb_log_*.txt（含旧版 myblbl_log_ 前缀）。 */
    private fun listSnapshotFiles(): List<File> {
        val dir = File(context.getExternalFilesDir(null), SNAPSHOT_DIR_NAME)
        return runCatching {
            dir.listFiles()
                ?.filter {
                    it.isFile && it.name.endsWith(".txt") &&
                        (it.name.startsWith(SNAPSHOT_PREFIX) || it.name.startsWith(LEGACY_SNAPSHOT_PREFIX))
                }
                ?.sortedByDescending { it.lastModified() }
        }.getOrNull().orEmpty()
    }

    private fun isUnsafeName(name: String): Boolean =
        name.isEmpty() || name.contains("..") || name.contains('/') || name.contains('\\')

    // ── HTTP 基础设施 ─────────────────────────────────────────────────

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun respondJson(socket: Socket, body: Any) {
        respond(socket, 200, "OK", "application/json; charset=utf-8", gson.toJson(body).toByteArray())
    }

    private fun respond(socket: Socket, status: Int, reason: String, contentType: String, body: String) {
        respond(socket, status, reason, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun respond(socket: Socket, status: Int, reason: String, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        socket.getOutputStream().use { out ->
            out.write(head)
            out.write(body)
            out.flush()
        }
    }

    private fun respondFile(socket: Socket, file: File) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${file.length()}\r\n")
            append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        socket.getOutputStream().use { out: OutputStream ->
            out.write(head)
            BufferedInputStream(file.inputStream()).use { input ->
                input.copyTo(out, 32 * 1024)
            }
            out.flush()
        }
    }

    /**
     * 取对外可用的局域网 IPv4（issue#98：海信电视曾取到 Wi-Fi Direct 接口 p2p0 的
     * 192.168.49.1，手机扫码连不上日志页）。
     * 首选 ConnectivityManager 当前默认网络的 LinkProperties——系统标记的真正路由网络；
     * 兜底走网卡枚举，排除 p2p 类接口名，并按 wlan→eth 排序（对齐 NvaDevice.localIpv4，
     * 有线/无线均可用，避免多网卡取到虚拟网卡）。
     */
    private fun firstLanIpv4(): String? {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val linkProps = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val activeIp = linkProps?.linkAddresses
                ?.mapNotNull { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
            if (activeIp != null) return activeIp
        }
        return runCatching {
            val candidates =
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    // Wi-Fi Direct / P2P 接口（p2p0、p2p-wlan0-x 等）有自己的 192.168.49.x
                    // 网段但手机实际连的是 wlan0 所在网络，直接排除
                    .filter { !it.name.contains("p2p", ignoreCase = true) }
                    .flatMap { nic ->
                        nic.inetAddresses.asSequence()
                            .filterIsInstance<Inet4Address>()
                            .filter { !it.isLoopbackAddress }
                            .map { nic.name to it.hostAddress }
                    }
                    .toList()
            val preferred =
                candidates.firstOrNull { (name, _) -> name.startsWith("wlan") }
                    ?: candidates.firstOrNull { (name, _) -> name.startsWith("eth") }
                    ?: candidates.firstOrNull()
            preferred?.second
        }.getOrNull()
    }
}
