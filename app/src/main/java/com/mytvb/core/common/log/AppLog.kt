package com.mytvb.core.common.log

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.mytvb.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object AppLog {

    private const val PREFS_NAME = "app_log_prefs"
    private const val KEY_ENABLED = "log_enabled"

    // 旧版崩溃日志（单文件覆盖写），保留兼容读取；新版写入 CRASH_LOG_DIR 多份轮转
    private const val LEGACY_CRASH_LOG_FILE = "debug.txt"
    private const val CRASH_LOG_DIR = "crash_logs"
    private const val CRASH_LOG_PREFIX = "logs_crash_"
    private const val CRASH_LOG_SUFFIX = ".log"
    private const val CRASH_LOG_MAX_COUNT = 10
    private const val LOGCAT_TAIL_LINES = 2000
    private const val LOGCAT_READ_TIMEOUT_MS = 2000L

    // 快照导出目录与滚动上限；LEGACY 前缀是旧版导出文件，清理白名单与 LanLogServer 一致
    private const val SNAPSHOT_DIR = "logs"
    private const val SNAPSHOT_PREFIX = "mytvb_log_"
    private const val LEGACY_SNAPSHOT_PREFIX = "myblbl_log_"
    private const val SNAPSHOT_MAX_COUNT = 10

    // @Volatile：开关在主线程翻转，action/cache 等后台线程依赖其可见性做热路径门控。
    @Volatile
    var isEnabled = false
        private set

    private lateinit var prefs: SharedPreferences
    private lateinit var crashLogDir: File
    private var appContext: Context? = null

    data class LogEntry(
        val timestamp: Long,
        val level: Int,
        val tag: String,
        val message: String
    ) {
        companion object {
            const val LEVEL_VERBOSE = 0
            const val LEVEL_DEBUG = 1
            const val LEVEL_INFO = 2
            const val LEVEL_WARN = 3
            const val LEVEL_ERROR = 4

            val LEVEL_LABELS = mapOf(
                LEVEL_VERBOSE to "V",
                LEVEL_DEBUG to "D",
                LEVEL_INFO to "I",
                LEVEL_WARN to "W",
                LEVEL_ERROR to "E"
            )
        }
    }

    object LogBuffer {
        private const val MAX_SIZE = 2000
        private val buffer = ArrayDeque<LogEntry>()

        fun add(entry: LogEntry) {
            synchronized(buffer) {
                buffer.addLast(entry)
                while (buffer.size > MAX_SIZE) {
                    buffer.removeFirst()
                }
            }
        }

        fun getLogs(): List<LogEntry> {
            synchronized(buffer) {
                return buffer.toList()
            }
        }

        fun clear() {
            synchronized(buffer) {
                buffer.clear()
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // debug 包默认开（排查依赖日志），release 默认关；用户手动设置后以 prefs 为准
        isEnabled = prefs.getBoolean(KEY_ENABLED, BuildConfig.DEBUG)
        crashLogDir = File(context.filesDir, CRASH_LOG_DIR)
        installCrashHandler()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    /** 崩溃日志文件列表（mtime 降序），含旧版 debug.txt 兼容项。 */
    fun getCrashLogFiles(): List<File> {
        if (!::crashLogDir.isInitialized) return emptyList()
        val files = runCatching {
            crashLogDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(CRASH_LOG_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
        }.getOrNull().orEmpty()
        val legacy = File(crashLogDir.parentFile, LEGACY_CRASH_LOG_FILE)
        return if (legacy.exists()) files + legacy else files
    }

    /** 读取最近一份崩溃日志（兼容旧版 debug.txt）。 */
    fun getCrashLog(): String? {
        getCrashLogFiles().firstOrNull()?.let { newest ->
            return runCatching { newest.readText() }.getOrNull()
        }
        return null
    }

    fun clearCrashLog() {
        if (!::crashLogDir.isInitialized) return
        runCatching {
            getCrashLogFiles().forEach { it.delete() }
        }
    }

    /**
     * 把内存缓冲全量 + 最近崩溃日志导出为快照文件，
     * 写入外部私有目录 logs/（UTF-8 BOM，Windows 记事本兼容）。
     */
    fun exportSnapshot(): File? {
        val context = appContext ?: return null
        return runCatching {
            val fileName = "mytvb_log_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(System.currentTimeMillis())
            }.txt"
            val logsDir = File(context.getExternalFilesDir(null), SNAPSHOT_DIR).apply {
                if (!exists()) mkdirs()
            }
            val file = File(logsDir, fileName).apply {
                // 写入 UTF-8 BOM，避免 Windows 记事本等编辑器因无 BOM 误判为 GBK 导致中文乱码
                outputStream().buffered().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(formatLogsAsText().toByteArray(Charsets.UTF_8))
                }
            }
            trimSnapshots(logsDir)
            file
        }.getOrNull()
    }

    /** 只保留最近 SNAPSHOT_MAX_COUNT 份快照（含旧版 myblbl_log_ 前缀）。 */
    private fun trimSnapshots(dir: File) {
        runCatching {
            val files = dir.listFiles()
                ?.filter {
                    it.isFile && it.name.endsWith(".txt") &&
                        (it.name.startsWith(SNAPSHOT_PREFIX) || it.name.startsWith(LEGACY_SNAPSHOT_PREFIX))
                }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            if (files.size > SNAPSHOT_MAX_COUNT) {
                files.drop(SNAPSHOT_MAX_COUNT).forEach { it.delete() }
            }
        }
    }

    /** 内存缓冲 + 崩溃日志合成的纯文本（导出/快照共用格式）。 */
    fun formatLogsAsText(): String {
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val sb = StringBuilder()
        for (entry in LogBuffer.getLogs()) {
            val level = LogEntry.LEVEL_LABELS[entry.level] ?: "?"
            val time = timeFormat.format(entry.timestamp)
            sb.appendLine("$time $level ${entry.tag}: ${entry.message}")
        }
        val crashLog = getCrashLog()
        if (crashLog != null) {
            sb.appendLine()
            sb.appendLine("========== CRASH LOG ==========")
            sb.append(crashLog)
        }
        return sb.toString()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashLog(thread, throwable)
            }
            defaultHandler.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        if (!::crashLogDir.isInitialized) return
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val time = timeFormat.format(System.currentTimeMillis())
        val stackTrace = Log.getStackTraceString(throwable)
        val logBufferText = formatLogsAsText()
        val content = buildString {
            append("=== CRASH ===\n")
            append("Time: $time\n")
            append("Thread: ${thread.name}\n")
            append("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
            append("Stack:\n")
            append(stackTrace)
            append("\n")
            append("\n=== DEVICE ===\n")
            append("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
            if (logBufferText.isNotBlank()) {
                append("\n=== APP LOG (${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())}) ===\n")
                append(logBufferText)
            }
            val logcatTail = captureLogcatTail()
            if (logcatTail.isNotBlank()) {
                append("\n=== LOGCAT TAIL (last $LOGCAT_TAIL_LINES lines) ===\n")
                append(logcatTail)
            }
        }
        if (!crashLogDir.exists()) crashLogDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val file = File(crashLogDir, "$CRASH_LOG_PREFIX$stamp$CRASH_LOG_SUFFIX")
        // 与旧版一致写 UTF-8 BOM，保证 Windows 端打开不乱码
        file.outputStream().buffered().use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.write(content.toByteArray(Charsets.UTF_8))
        }
        trimCrashLogs()
    }

    /** 只保留最近 CRASH_LOG_MAX_COUNT 份崩溃日志。 */
    private fun trimCrashLogs() {
        runCatching {
            val files = crashLogDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(CRASH_LOG_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            if (files.size > CRASH_LOG_MAX_COUNT) {
                files.drop(CRASH_LOG_MAX_COUNT).forEach { it.delete() }
            }
        }
    }

    /**
     * 抓取本进程可见的 logcat 尾部（第三方应用从 4.1 起只能读到自己 uid 的日志）。
     * 崩溃场景下必须限时：读取线程 2s 未完成则销毁进程放弃，不影响后续默认崩溃流程。
     */
    private fun captureLogcatTail(): String = runCatching {
        val proc = Runtime.getRuntime()
            .exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_TAIL_LINES.toString()))
        val sb = StringBuilder()
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    synchronized(sb) {
                        lines.forEach { sb.append(it).append('\n') }
                    }
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()
        readerThread.join(LOGCAT_READ_TIMEOUT_MS)
        if (readerThread.isAlive) {
            proc.destroy()
        }
        synchronized(sb) { sb.toString() }
    }.getOrDefault("")

    private fun logToBuffer(level: Int, tag: String, message: String) {
        LogBuffer.add(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    fun d(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_DEBUG, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_INFO, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.i(tag, message) }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_ERROR, tag, appendThrowableChain(message, throwable))
        if (BuildConfig.DEBUG) runCatching { Log.e(tag, message, throwable) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_WARN, tag, appendThrowableChain(message, throwable))
        if (BuildConfig.DEBUG) runCatching { Log.w(tag, message, throwable) }
    }

    /**
     * 落盘消息附加异常链摘要（类名+消息，最多 3 层）。
     *
     * logcat 分支始终带完整堆栈，但内存缓冲（导出快照）此前只存 message，
     * "Unexpected runtime error" 这类外层文案会把真实根因（如 vendor 解码器抛的
     * RuntimeException）完全遮住；快照无系统 logcat 可看，必须在这里带上。
     */
    private fun appendThrowableChain(message: String, throwable: Throwable?): String {
        if (throwable == null) {
            return message
        }
        val chain = buildString {
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < 3) {
                if (depth > 0) {
                    append(" <- ")
                }
                append(current.javaClass.simpleName)
                current.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ").append(it.take(160))
                }
                current = current.cause
                depth++
            }
        }
        return "$message [$chain]"
    }

    fun v(tag: String, message: String) {
        if (!isEnabled) return
        logToBuffer(LogEntry.LEVEL_VERBOSE, tag, message)
        if (BuildConfig.DEBUG) runCatching { Log.v(tag, message) }
    }
}
