package com.mytvb.feature.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.mytvb.core.common.log.AppLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SSDP 广播与 M-SEARCH 响应。
 *
 * 手机端通过组播发现设备，只有响应里带上
 * `urn:app-bilibili-com:service:NirvanaControl:3` 才会把它当成哔哩必连设备。
 */
internal class NvaSsdpServer(
    private val context: Context,
    private val port: Int = NvaProtocol.PORT,
) {
    private val tag = "NvaSsdp"
    private val running = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null
    private var notifier: Thread? = null

    /**
     * 启动代次。重启（改名/换网）时旧线程可能还卡在 receive 或 sleep 上，
     * 只靠 [running] 判断会出现"旧线程用已关闭的 socket 继续跑"的假活，
     * 因此每个线程持有自己的代次，代次不匹配即退出。
     */
    @Volatile
    private var generation = 0

    /** 设备标识冲突日志的限流时间戳，避免两台电视互相刷屏。 */
    @Volatile
    private var lastConflictLogAt = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        val myGeneration = ++generation

        val bound = bindWithRetry()
        if (bound == null) {
            // 绑定失败不能就此永久失效：改名后立刻重启最容易撞上"上一个 socket 还没释放"。
            // 交给 NvaCastController 的健康检查做退避重试。
            AppLog.e(tag, "SSDP 绑定 ${NvaProtocol.SSDP_PORT} 失败，等待自动重试")
            running.set(false)
            runCatching { multicastLock?.release() }
            multicastLock = null
            return
        }
        socket = bound
        // 每次（重新）启动都算一次"设备重启"：BOOTID 递增 → LOCATION 变化 →
        // 手机端必然重新拉取设备描述。这是把"改完名字列表仍显示旧名"彻底堵死的关键，
        // 只靠 max-age=1800 的缓存过期要等 30 分钟。
        val bootId = NvaDevice.bumpBootId(context)
        AppLog.i(
            tag,
            "SSDP 已启动 on ${NvaProtocol.SSDP_HOST}:${NvaProtocol.SSDP_PORT} " +
                "ip=${NvaDevice.localIpv4()} name=${NvaDevice.displayName(context)} " +
                "model=${NvaDevice.modelName(context)} brand=${NvaDevice.brandName(context)} " +
                "serial=${NvaDevice.serialNumber(context)} bootId=$bootId",
        )

        worker = thread(name = "nva-ssdp-rx", isDaemon = true) { receiveLoop(bound, myGeneration) }
        notifier = thread(name = "nva-ssdp-tx", isDaemon = true) { notifyLoop(bound, myGeneration) }
    }

    /**
     * 绑定 SSDP 组播端口，失败按 300/600/1200ms 退避重试。
     *
     * 重启时上一个 socket 刚 close，UDP 1900 可能还被内核占用（尤其组播），
     * 一次 bind 失败就放弃会导致该设备彻底不再广播——实测表现为"改了名字后
     * 这台电视在投屏列表里直接消失"。
     */
    private fun bindWithRetry(): MulticastSocket? {
        var delay = BIND_RETRY_MIN_MS
        for (attempt in 1..BIND_MAX_ATTEMPTS) {
            val result =
                runCatching {
                    val group = InetAddress.getByName(NvaProtocol.SSDP_HOST)
                    MulticastSocket(null as SocketAddress?).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(NvaProtocol.SSDP_PORT))
                        timeToLive = 4
                        joinGroup(group)
                    }
                }
            result.getOrNull()?.let { return it }
            AppLog.w(tag, "SSDP 绑定失败（第 $attempt/$BIND_MAX_ATTEMPTS 次）", result.exceptionOrNull())
            if (attempt < BIND_MAX_ATTEMPTS) runCatching { Thread.sleep(delay) }
            delay *= 2
        }
        return null
    }

    /**
     * 重启广播。网络切换等需要整体重建 socket 的场景使用。
     * 改名不需要走这里（见 [announceNow]）。
     */
    fun restart() {
        stop()
        // UDP 1900 释放有微小延迟，稍等再绑，避免 bind 失败导致设备短暂搜不到。
        runCatching { Thread.sleep(RESTART_GAP_MS) }
        start()
    }

    fun stop() {
        // 先让在跑的线程失效，再关 socket，避免它们被唤醒后继续处理。
        generation++
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        AppLog.i(tag, "SSDP 已停止")
    }

    private fun isActive(generation: Int): Boolean = running.get() && this.generation == generation

    /** 健康状态：socket 未关闭、收发线程都还活着，才认为广播正常。 */
    fun isHealthy(): Boolean =
        running.get() &&
            socket?.isClosed == false &&
            worker?.isAlive == true &&
            notifier?.isAlive == true

    /**
     * 立刻重播一轮 NOTIFY，**不重建 socket**。
     *
     * 改名只需要让控制点重新拉取设备描述：BOOTID 已经递增、LOCATION 随之变化，
     * 周期 NOTIFY 自然会带上新 LOCATION，本方法再即时补播一轮，不需要动端口。
     * 以前改名走 [restart]（关端口再绑），一旦 1900 没及时释放就 bind 失败，
     * 反而把这台设备的广播搞死——改名和"重启广播"是两件不该绑定的事。
     */
    fun announceNow() {
        val current = socket ?: return
        if (!running.get()) return
        thread(name = "nva-ssdp-announce", isDaemon = true) {
            runCatching { sendNotify(current) }
                .onFailure { AppLog.w(tag, "立即广播失败", it) }
        }
    }

    private fun acquireMulticastLock() {
        // 没有 MulticastLock 时多数 ROM 会丢弃组播包，导致手机端搜不到设备。
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock =
                wifi?.createMulticastLock("mytvb-nva")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { AppLog.w(tag, "MulticastLock 获取失败", it) }
    }

    private fun receiveLoop(socket: MulticastSocket, generation: Int) {
        val buffer = ByteArray(4096)
        while (isActive(generation)) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                when {
                    message.startsWith("M-SEARCH", ignoreCase = true) ->
                        respondToSearch(message, packet.address, packet.port)

                    message.startsWith("NOTIFY", ignoreCase = true) ->
                        checkPeerIdentity(message, packet.address)
                }
            } catch (_: SocketTimeoutException) {
                // 正常超时，继续轮询
            } catch (t: Throwable) {
                if (!isActive(generation)) return
                AppLog.w(tag, "SSDP 接收异常", t)
            }
        }
    }

    /**
     * 监听同网其它设备广播的 NOTIFY，只做一件事：判断对方是否与本机共用同一个 UDN。
     *
     * 两台电视如果在安装/恢复时被整体复制了 SharedPreferences，UDN 就会完全相同，
     * 手机端会把它们当成"同一台设备的多网卡"，进而放弃 friendlyName、
     * 改用「我的小电视-型号-短码」的合成名展示。这里只做取证打日志（不改标识），
     * 因为当前没有证据表明本机真的发生冲突，误改标识会让手机端多出历史条目。
     */
    private fun checkPeerIdentity(message: String, source: InetAddress) {
        val usn = headerValue(message, "USN") ?: return
        val peerUsn = usn.removePrefix("uuid:").substringBefore("::")
        if (peerUsn != NvaDevice.usn(context)) return
        val host = source.hostAddress ?: return
        if (NvaDevice.isLocalAddress(host)) return

        val now = System.currentTimeMillis()
        if (now - lastConflictLogAt < CONFLICT_LOG_INTERVAL_MS) return
        lastConflictLogAt = now
        AppLog.e(
            tag,
            "设备标识冲突：$host 与本机共用同一 UDN（${peerUsn.take(8)}…），" +
                "手机端会把两台当成同一台设备处理",
        )
    }

    private fun respondToSearch(message: String, address: InetAddress, port: Int) {
        val searchTarget = headerValue(message, "ST") ?: return
        val targets = targetsFor(searchTarget)
        if (targets.isEmpty()) return

        val usn = NvaDevice.usn(context)
        val location = locationUrl() ?: return
        val bootId = NvaDevice.bootId(context)

        targets.forEach { target ->
            val response =
                buildString(512) {
                    append("HTTP/1.1 200 OK\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("DATE: ").append(httpDate()).append("\r\n")
                    append("EXT:\r\n")
                    append("LOCATION: ").append(location).append("\r\n")
                    append("SERVER: Linux/3.0.0 UPnP/1.0 Platinum/1.0.5.13\r\n")
                    append("ST: ").append(target).append("\r\n")
                    append("USN: ").append(usnFor(usn, target)).append("\r\n")
                    append("BOOTID.UPNP.ORG: ").append(bootId).append("\r\n")
                    // CONFIGID 的语义就是"设备描述内容版本"，改名后必须同步变化，
                    // 否则控制点会认为描述没更新而继续用缓存的 friendlyName。
                    append("CONFIGID.UPNP.ORG: ").append(bootId).append("\r\n")
                    append("\r\n")
                }
            runCatching {
                DatagramSocket().use { unicast ->
                    val bytes = response.toByteArray(Charsets.UTF_8)
                    unicast.send(DatagramPacket(bytes, bytes.size, address, port))
                }
            }.onFailure { AppLog.w(tag, "回复 M-SEARCH 失败 st=$target", it) }
        }
        AppLog.i(tag, "已回复 M-SEARCH from=${address.hostAddress}:$port st=$searchTarget count=${targets.size}")
    }

    private fun notifyLoop(socket: MulticastSocket, generation: Int) {
        // 首次立即广播（开机即可被发现），之后每 NOTIFY_INTERVAL_MS 一轮；
        // 每轮都会重算 locationUrl()，顺带适配网络切换。改名走 announceNow() 即时补播。
        while (isActive(generation)) {
            runCatching { sendNotify(socket) }
                .onFailure { AppLog.w(tag, "SSDP NOTIFY 失败", it) }
            sleepWhileActive(NOTIFY_INTERVAL_MS, generation)
        }
    }

    /**
     * 可中断休眠：分段检查存活，停止后最多 [SLEEP_STEP_MS] 就退出线程。
     *
     * [stop] 只置标志、不 join 线程，若在 [notifyLoop] 里整段 `Thread.sleep(30s)`，
     * 关掉投屏开关后旧线程要睡满 30 秒才醒（30s 之前是 3s，问题不可见）；
     * 反复开关会让多个 `nva-ssdp-tx` 叠加残留（实测切换 4 次出现 2 个 tx 线程）。
     */
    private fun sleepWhileActive(totalMs: Long, generation: Int) {
        var remaining = totalMs
        while (remaining > 0 && isActive(generation)) {
            val step = minOf(remaining, SLEEP_STEP_MS)
            if (runCatching { Thread.sleep(step) }.isFailure) return
            remaining -= step
        }
    }

    private fun sendNotify(socket: MulticastSocket) {
        val usn = NvaDevice.usn(context)
        val location = locationUrl() ?: return
        val bootId = NvaDevice.bootId(context)
        val group = InetAddress.getByName(NvaProtocol.SSDP_HOST)

        ALL_TARGETS.forEach { target ->
            val notify =
                buildString(512) {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: ").append(NvaProtocol.SSDP_HOST).append(':').append(NvaProtocol.SSDP_PORT).append("\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("LOCATION: ").append(location).append("\r\n")
                    append("NT: ").append(target).append("\r\n")
                    append("NTS: ssdp:alive\r\n")
                    append("SERVER: Linux/3.0.0 UPnP/1.0 Platinum/1.0.5.13\r\n")
                    append("USN: ").append(usnFor(usn, target)).append("\r\n")
                    append("BOOTID.UPNP.ORG: ").append(bootId).append("\r\n")
                    // CONFIGID 的语义就是"设备描述内容版本"，改名后必须同步变化，
                    // 否则控制点会认为描述没更新而继续用缓存的 friendlyName。
                    append("CONFIGID.UPNP.ORG: ").append(bootId).append("\r\n")
                    append("\r\n")
                }
            val bytes = notify.toByteArray(Charsets.UTF_8)
            runCatching {
                socket.send(DatagramPacket(bytes, bytes.size, group, NvaProtocol.SSDP_PORT))
            }
        }
    }

    private fun targetsFor(searchTarget: String): List<String> =
        when {
            searchTarget.equals("ssdp:all", ignoreCase = true) -> ALL_TARGETS
            searchTarget in ALL_TARGETS -> listOf(searchTarget)
            else -> emptyList()
        }

    private fun usnFor(usn: String, target: String): String =
        if (target == "uuid:$usn") "uuid:$usn" else "uuid:$usn::$target"

    private fun locationUrl(): String? {
        val ip = NvaDevice.localIpv4() ?: return null
        // 必须让 LOCATION 随 BOOTID 变化：手机端是按 LOCATION 缓存整份设备描述的
        // （拿不到描述就没法显示名字），URL 不变时它不会重新 GET，改名后在投屏列表里
        // 就一直是旧名。BOOTID/CONFIGID 只有严格实现 UPnP 的控制点才看，B 站客户端
        // 实际不吃这一套，所以把版本号直接做进**路径**——查询参数有些客户端会丢掉，
        // 路径不会。服务端对任意非 /dlna/ 路径都返回设备描述，加了也不影响解析。
        val bootId = NvaDevice.bootId(context)
        return "http://$ip:$port/desc/$bootId/description.xml"
    }

    private fun headerValue(message: String, header: String): String? =
        message.lineSequence()
            .firstOrNull { it.startsWith("$header:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).format(Date())

    companion object {
        /**
         * 周期性 NOTIFY（ssdp:alive）间隔。
         *
         * 原值 3s 是"改名后手机能马上看到新名字"的权宜做法，代价是常年每 3 秒一轮组播、
         * 持续唤醒 WiFi 网络栈。改名场景已由 [announceNow] 立即重播覆盖，无需高频兜底；
         * 而 NOTIFY 只负责"我还活着 + LOCATION 指向"，发现设备仍靠手机 M-SEARCH 的即时回复
         * （见 receiveLoop）。max-age 声明 1800s，30s 间隔远小于该值，符合 UPnP 惯例。
         */
        private const val NOTIFY_INTERVAL_MS = 30_000L

        /** 可中断休眠的检查粒度：stop 后最多这么久线程就会退出。 */
        private const val SLEEP_STEP_MS = 1_000L

        /** 重启时等端口释放的间隔。200ms 实测偏紧，容易 bind 失败。 */
        private const val RESTART_GAP_MS = 600L

        /** bind 失败的首次退避与最大尝试次数（300/600/1200ms）。 */
        private const val BIND_RETRY_MIN_MS = 300L
        private const val BIND_MAX_ATTEMPTS = 4

        /** 设备标识冲突日志的最小间隔。 */
        private const val CONFLICT_LOG_INTERVAL_MS = 30_000L

        private val ALL_TARGETS =
            listOf(
                "upnp:rootdevice",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:RenderingControl:1",
                "urn:schemas-upnp-org:service:ConnectionManager:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                // B 站专属：缺这一条手机会把它当普通 DLNA 设备
                "urn:app-bilibili-com:service:NirvanaControl:3",
            )
    }
}
