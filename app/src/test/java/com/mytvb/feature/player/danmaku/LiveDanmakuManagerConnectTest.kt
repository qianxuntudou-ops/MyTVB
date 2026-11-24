package com.mytvb.feature.player.danmaku

import com.mytvb.core.common.log.AppLog
import com.mytvb.model.BaseResponse
import com.mytvb.model.live.ChatHostUrlModel
import com.mytvb.model.live.ChatRoomWrapper
import com.mytvb.network.api.ApiService
import com.mytvb.network.session.NetworkSessionGateway
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.net.Socket

/**
 * 弹幕连接的「假断开」回归：连接状态的订阅若先于 connect() 执行，会把状态流的初始值 DISCONNECTED
 * 当成已断开，首连白费。两个用例合起来钉住：没失败不能当成断开，真失败必须当成断开。
 */
class LiveDanmakuManagerConnectTest {

    /** 让 launch 的协程体立刻在当前线程执行：订阅连接状态与 connect() 谁先跑由代码顺序决定，不看调度运气。 */
    private val inlineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    private lateinit var server: ServerSocket
    private val held = mutableListOf<Socket>()

    @Before
    fun setUp() {
        AppLog.setEnabled(true)
        AppLog.LogBuffer.clear()
        // 只接受 TCP 连接、不回应 WebSocket 握手：连接停在「握手中」，不会自己失败
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(held) { held += socket }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun tearDown() {
        server.close()
        synchronized(held) {
            held.forEach { runCatching { it.close() } }
            held.clear()
        }
        AppLog.setEnabled(false)
        AppLog.LogBuffer.clear()
    }

    @Test(timeout = 15_000)
    fun firstConnectIsNotMistakenForDisconnect() = runBlocking {
        val manager = managerTo(server.localPort)
        manager.start(1L)
        try {
            // 首连一直停在握手阶段等服务器，这期间不该被当成断开
            delay(300)
            assertFalse("首连还没失败就被当成断开重连", treatedAsDisconnected())
        } finally {
            manager.release()
        }
    }

    @Test(timeout = 15_000)
    fun realConnectFailureIsStillDetected() = runBlocking {
        // 没人监听的端口：连接被拒，握手失败
        val closedPort = ServerSocket(0).use { it.localPort }
        val manager = managerTo(closedPort)
        manager.start(1L)
        try {
            withTimeout(10_000) {
                while (!treatedAsDisconnected()) delay(50)
            }
        } finally {
            manager.release()
        }
    }

    private fun treatedAsDisconnected(): Boolean =
        AppLog.LogBuffer.getLogs().any { it.message.startsWith("WebSocket disconnected") }

    private fun managerTo(wssPort: Int): LiveDanmakuManager = LiveDanmakuManager(
        apiService = proxy(ApiService::class.java) { method, _ ->
            check(method == "getLiveDanmuInfoSigned") { "unexpected $method" }
            BaseResponse(
                code = 0,
                data = ChatRoomWrapper(
                    token = "token",
                    hostList = listOf(ChatHostUrlModel(host = "127.0.0.1", wssPort = wssPort))
                )
            )
        } as ApiService,
        okHttpClient = OkHttpClient(),
        sessionGateway = proxy(NetworkSessionGateway::class.java) { method, _ ->
            when (method) {
                "getUserInfo" -> null
                "getWbiKeys" -> "img" to "sub"
                "ensureWbiKeys" -> Unit
                else -> error("unexpected $method")
            }
        } as NetworkSessionGateway,
        ioDispatcher = inlineDispatcher,
        isLotteryDanmakuBlocked = { false }
    )

    /** 接口桩：方法直接返回给定结果（suspend 方法等于同步返回，不挂起）。 */
    private fun proxy(type: Class<*>, answer: (String, Array<out Any?>) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            answer(method.name, args ?: emptyArray())
        }
}
