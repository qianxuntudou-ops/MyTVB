package com.mytvb.core.common.update

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import org.koin.mp.KoinPlatform
import java.io.File

/**
 * 更新包落地位置（issue#129）。
 *
 * 背景：部分电视的 /data 分区被厂商加密或可用空间极小，内部缓存写不进安装包，
 * 更新流程在第一步就失败。反馈者期望「下载到 U 盘并直接从 U 盘拉起安装」。
 *
 * 落地通道只有两档（SAF 自定义目录未做）：
 * - [CHOICE_INTERNAL]：`cacheDir/update/`，与历史行为完全一致；
 * - [CHOICE_USB]：U 盘上的应用专属目录
 *   `/storage/<卷ID>/Android/data/<pkg>/files/update/`。
 *
 * 关键事实：`getExternalFilesDirs()` 在 API 19+ 对所有共享卷（含 U 盘）都会返回
 * 应用专属目录，**读写这部分不需要任何运行时权限、不需要 SAF 授权**，因此这条通道
 * 在 Android 6~15 全线上可写；代价是包只能落在 `Android/data/<pkg>/` 下，写不到
 * U 盘根目录（那需要 SAF，未做）。
 */
object UpdateStorage {

    private const val TAG = "UpdateStorage"
    private const val UPDATE_DIR_NAME = "update"

    /** 保存位置设置键，存值即中文字面量（与全项目设置项惯例一致，显示层翻译） */
    const val KEY_SAVE_LOCATION = "update_save_location"
    const val CHOICE_INTERNAL = "内部存储"
    const val CHOICE_USB = "U盘"

    /** 可用的 U 盘目标（目录已实测可写） */
    data class UsbTarget(
        val dir: File,
        /** 卷 ID（形如 `1A2B-3C4D`），仅用于日志与界面区分多盘；解析不到时为 null */
        val volumeId: String?,
    )

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /**
     * 探测结果短 TTL 缓存：确认弹窗/设置页都在主线程调用，而探测要实际写盘（U 盘首次
     * 还会创建 Android/data 目录）。用户点选级别的调用频率下 3 秒足够去重；真正下载前
     * [resolveDir] 会强制重探，拔盘不会拿着陈旧路径硬写。
     */
    private const val USB_CACHE_TTL_MS = 3_000L

    @Volatile
    private var usbCache: Pair<Long, List<UsbTarget>>? = null

    /** 内部缓存目录（历史默认值） */
    fun internalDir(context: Context): File =
        File(context.applicationContext.cacheDir, UPDATE_DIR_NAME)

    /** 用户当前选择的保存位置（未经可用性校验） */
    fun currentChoice(context: Context): String =
        appSettings.getCachedString(KEY_SAVE_LOCATION) ?: CHOICE_INTERNAL

    fun saveChoice(context: Context, value: String) {
        appSettings.putStringAsync(KEY_SAVE_LOCATION, value)
    }

    /**
     * 枚举所有可写的 U 盘目标（带 3 秒缓存，供 UI 线程点选路径调用）。
     *
     * `getExternalFilesDirs(null)` 的第一项是内置共享存储（`/storage/emulated/0/...`），
     * 不是 U 盘，从第二项起才是可移动卷。可写性必须实测（写一字节探针再删）：
     * FAT32/NTFS 只读挂载、部分 ROM 的 FUSE 权限会骗过 `File.canWrite()`。
     */
    fun listUsbTargets(context: Context): List<UsbTarget> {
        val now = SystemClock.elapsedRealtime()
        usbCache?.let { (at, cached) ->
            if (now - at < USB_CACHE_TTL_MS) return cached
        }
        val detected = detectUsbTargets(context)
        usbCache = now to detected
        return detected
    }

    /** 强制重新探测（下载前解析目标用，不能吃缓存） */
    private fun detectUsbTargets(context: Context): List<UsbTarget> {
        val app = context.applicationContext
        val dirs = runCatching { app.getExternalFilesDirs(null) }.getOrElse { t ->
            AppLog.w(TAG, "detect getExternalFilesDirs failed: ${t.javaClass.simpleName} ${t.message}")
            return emptyList()
        }
        // 逐卷落日志：定位「为什么没出现 U 盘选项」只看这一行
        AppLog.i(
            TAG,
            "detect volumeDirs=${dirs.size} [" +
                dirs.mapIndexed { i, d -> "$i:${d?.absolutePath ?: "null"}" }.joinToString(", ") + "]"
        )
        if (dirs.size <= 1) {
            AppLog.w(TAG, "detect only primary volume present, no removable candidate")
            return emptyList()
        }

        val primary = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()
        val result = ArrayList<UsbTarget>(dirs.size - 1)
        dirs.filterNotNull().drop(1).forEach { filesDir ->
            // 防御：个别 ROM 的顺序不保证第一项是主存储，主存储目录不算 U 盘
            if (primary != null && filesDir.absolutePath.startsWith(primary)) {
                AppLog.i(TAG, "detect skip primary-like dir=${filesDir.absolutePath}")
                return@forEach
            }
            val updateDir = File(filesDir, UPDATE_DIR_NAME)
            val error = probeWritable(updateDir)
            if (error != null) {
                AppLog.w(TAG, "detect skip dir=${updateDir.absolutePath} reason=$error")
                return@forEach
            }
            val target = UsbTarget(updateDir, parseVolumeId(filesDir.absolutePath))
            AppLog.i(
                TAG,
                "detect ok dir=${target.dir.absolutePath} vol=${target.volumeId} " +
                    "free=${usableSpace(target.dir)}B exists=${target.dir.exists()}"
            )
            result += target
        }
        AppLog.i(TAG, "detect done writableCount=${result.size}")
        return result
    }

    /**
     * 按用户设置解析出本次下载目录；U 盘不可用（没插/只读/换盘后创建失败）时
     * 静默回退内部缓存，调用方可据 [isInternalDir] 判断是否发生了回退。
     */
    fun resolveDir(context: Context): File {
        val choice = currentChoice(context)
        if (choice != CHOICE_USB) {
            AppLog.i(TAG, "resolveDir choice=$choice -> internal ${internalDir(context).absolutePath}")
            return internalDir(context)
        }
        val detected = detectUsbTargets(context)
        usbCache = SystemClock.elapsedRealtime() to detected
        val usb = detected.firstOrNull()
        if (usb == null) {
            AppLog.w(TAG, "resolveDir choice=$choice but no writable volume -> fallback internal")
            return internalDir(context)
        }
        AppLog.i(
            TAG,
            "resolveDir choice=$choice -> ${usb.dir.absolutePath} vol=${usb.volumeId} " +
                "free=${usableSpace(usb.dir)}B"
        )
        return usb.dir
    }

    /** 目录是否落在应用内部缓存里（决定安装走 FileProvider 还是 PackageInstaller Session） */
    fun isInternalDir(context: Context, dir: File): Boolean =
        dir.absolutePath.startsWith(context.applicationContext.cacheDir.absolutePath)

    /** 目标目录剩余可用字节数，用于下载前空间预检 */
    fun usableSpace(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)

    /** 探针可写性检测；成功返回 null，失败返回可读原因（写进日志给用户排查用） */
    private fun probeWritable(dir: File): String? {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return "mkdirs failed(${dir.absolutePath})"
            val probe = File(dir, ".write_probe_${SystemClock.elapsedRealtime()}")
            probe.outputStream().use { it.write(1) }
            probe.delete()
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 从 `/storage/1A2B-3C4D/...` 抠卷 ID；主存储（emulated）与异常路径返回 null */
    private fun parseVolumeId(path: String): String? =
        Regex("/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/").find(path)?.groupValues?.getOrNull(1)
}
