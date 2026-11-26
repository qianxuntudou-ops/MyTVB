@file:Suppress("SpellCheckingInspection")

package com.mytvb.core.common.content

import android.content.Context
import android.os.SystemClock
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.mp.KoinPlatform

/**
 * 青少年模式 - 观看/休息时长计时器（单例）。
 *
 * 设计要点：
 * - `teen_accumulated_ms` 是**持久化的累计观看毫秒**，每段播放结束后立即写回 DataStore，
 *   关 APP / 杀进程 / 重启后继续累加。
 * - 播放段计时用 [SystemClock.elapsedRealtime]：只关心"实际播放了多久"，抗改系统时间。
 * - 休息判定用墙钟 [System.currentTimeMillis]：孩子改系统时间或杀进程都绕不过休息。
 * - 休息上限为 0 → 整个时间限制关闭（不计时也不拦截，青少年保护开关仍独立生效）。
 */
object TeenModeTimer {

    private const val TAG = "TeenModeTimer"

    private const val KEY_WATCH_LIMIT_MIN = "teen_watch_limit_min"
    private const val KEY_REST_LIMIT_MIN = "teen_rest_limit_min"
    private const val KEY_ACCUMULATED_MS = "teen_accumulated_ms"
    private const val KEY_REST_START_MS = "teen_rest_start_ms"
    private const val KEY_PSAS_ENABLED = "teen_psas_enabled"
    private const val KEY_PSAS_INTERVAL_MIN = "teen_psas_interval_min"
    private const val KEY_PSAS_ACCUMULATED_MS = "teen_psas_accumulated_ms"

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    /** 当前播放段的起始 elapsedRealtime；null 表示未在播放。 */
    private var segmentStartElapsedMs: Long? = null

    /** 是否处于休息状态。供 PlayerActivity 观察以达上限立即退出。 */
    private val _restingFlow = MutableStateFlow(false)
    val restingFlow: StateFlow<Boolean> = _restingFlow.asStateFlow()

    @Volatile
    private var initialized = false

    /** Application 启动阶段同步刷新一次休息状态（与 AppSettingsDataStore.initCache 配合）。 */
    fun initOnStartup() {
        if (initialized) return
        initialized = true
        refreshRestingState()
    }

    /** 时间限制总开关：休息上限 > 0 才启用计时与拦截。 */
    fun isTimeLimitEnabled(): Boolean {
        return getRestLimitMin() > 0
    }

    /** 当前观看上限（毫秒），0 表示不限。 */
    private fun getWatchLimitMs(): Long {
        return getWatchLimitMin().toLong() * 60_000L
    }

    fun getWatchLimitMin(): Int {
        // 设置页用 putStringAsync 存（字符串），这里必须用同类型读取，否则强类型 DataStore 读不出。
        return appSettings.getCachedString(KEY_WATCH_LIMIT_MIN)?.trim()?.toIntOrNull() ?: 0
    }

    fun getRestLimitMin(): Int {
        return appSettings.getCachedString(KEY_REST_LIMIT_MIN)?.trim()?.toIntOrNull() ?: 0
    }

    /** 休息总时长（毫秒），供倒计时计算用。 */
    fun getRestLimitMs(): Long {
        return getRestLimitMin().toLong() * 60_000L
    }

    /** 休息开始墙钟戳（毫秒），未进入休息返回 0。供倒计时计算用。 */
    fun getRestStartMs(): Long {
        return appSettings.getCachedLong(KEY_REST_START_MS, 0L)
    }

    // ==================== 公益广告（PSAS）====================

    /** 公益广告开关是否开启。 */
    fun isPsasEnabled(): Boolean {
        if (!isTimeLimitEnabled()) return false
        return appSettings.getCachedString(KEY_PSAS_ENABLED) == "开"
    }

    /** 公益广告间隔（分钟），默认 20；必须 < 观看上限才生效。 */
    fun getPsasIntervalMin(): Int {
        return appSettings.getCachedString(KEY_PSAS_INTERVAL_MIN)?.trim()?.toIntOrNull() ?: 20
    }

    /**
     * 检查是否触发公益广告：开关开 + 间隔>0 + 广告累计已达间隔 + 广告列表就绪。
     * 触发后清零「广告累计」（重新开始计），返回 true 让调用方启动公益广告。
     * 独立于休息计时：用 KEY_PSAS_ACCUMULATED_MS，不影响休息的累计值。
     */
    fun checkAndConsumePsasTrigger(): Boolean {
        if (!isPsasEnabled()) return false
        // 广告列表未就绪时不触发（避免白白清零累计，等列表加载好再触发）
        if (!PsasRepository.isReady()) return false
        val intervalMin = getPsasIntervalMin()
        if (intervalMin <= 0) return false
        val intervalMs = intervalMin.toLong() * 60_000L
        val psasAccumulated = appSettings.getCachedInt(KEY_PSAS_ACCUMULATED_MS, 0)
        if (psasAccumulated < intervalMs) return false
        // 触发：清零广告累计，重新开始计
        appSettings.putIntBlocking(KEY_PSAS_ACCUMULATED_MS, 0)
        AppLog.i(TAG, "psas triggered, reset psas accumulated. psasAccumulated=$psasAccumulated intervalMs=$intervalMs")
        return true
    }

    /**
     * 播放开始（isPlaying 变 true）。
     * 已进入休息状态时不重新计时，避免后台播放在休息期间累计时长。
     */
    fun onPlayStart() {
        if (!isTimeLimitEnabled()) return
        // 进入休息后不再累计，直到休息结束由 [refreshRestingState] 清零。
        if (_restingFlow.value) return
        if (segmentStartElapsedMs != null) return
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * 播放停止（isPlaying 变 false / 退出播放器）。
     * 结算本段实际播放时长，累加到持久化累计值；达上限则进入休息。
     */
    fun onPlayStop() {
        if (!isTimeLimitEnabled()) return
        val startMs = segmentStartElapsedMs ?: return
        segmentStartElapsedMs = null
        settleSegment(startMs)
    }

    /**
     * 播放中周期性结算（每 ~15 秒由 PlayerActivity 调用）。
     *
     * 设计为自给自足：不依赖 onPlayStart 是否被调用过——若 segmentStart 为空，
     * 说明播放监听器未触发（部分机型/某些播放路径下 onIsPlayingChanged 不可靠），
     * 这里自动用当前时刻初始化起点，保证只要定时器在跑，计时就一定工作。
     */
    fun tick() {
        if (!isTimeLimitEnabled()) return
        // 休息中（含到期清零）：丢弃当前计时段，等休息真正结束后从头计时。
        // 关键：必须清 segmentStart，否则休息结束后第一次结算会把"休息时长"误算进去立即触发。
        if (isResting()) {
            segmentStartElapsedMs = null
            return
        }
        // 关键修复：segmentStart 为空时自动初始化，避免空转导致永不计时
        if (segmentStartElapsedMs == null) {
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            return
        }
        settleSegment(segmentStartElapsedMs!!)
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * 把 [fromElapsedMs] 到现在的实际播放时长同时累加到「休息累计」和「公益广告累计」。
     * 两套累计独立：休息累计达观看上限→进休息；广告累计达间隔→播广告（由 checkAndConsumePsasTrigger 判断）。
     * 达休息上限时进入休息，并清零广告累计（休息期间不算观看，重新开始）。
     */
    private fun settleSegment(fromElapsedMs: Long) {
        val delta = (SystemClock.elapsedRealtime() - fromElapsedMs).coerceAtLeast(0L)
        if (delta <= 0L) return
        val deltaInt = delta.toInt()
        // 1) 休息累计
        val accumulated = appSettings.getCachedInt(KEY_ACCUMULATED_MS, 0) + deltaInt
        val limitMs = getWatchLimitMs()
        appSettings.putIntBlocking(KEY_ACCUMULATED_MS, accumulated)
        // 2) 公益广告累计（独立计数器，和休息互不干扰）
        val psasAccumulated = appSettings.getCachedInt(KEY_PSAS_ACCUMULATED_MS, 0) + deltaInt
        appSettings.putIntBlocking(KEY_PSAS_ACCUMULATED_MS, psasAccumulated)
        if (limitMs in 1..accumulated.toLong()) {
            // 达休息上限：写休息开始戳、触发休息状态，并清零广告累计（休息后重新计）
            appSettings.putLongBlocking(KEY_REST_START_MS, System.currentTimeMillis())
            appSettings.putIntBlocking(KEY_PSAS_ACCUMULATED_MS, 0)
            _restingFlow.value = true
            AppLog.i(TAG, "watch limit reached, enter rest. accumulated=$accumulated")
        }
    }

    /**
     * 是否处于休息状态。过期会自动清零累计与休息戳。
     * 同时是三个入口（视频/直播/TV直播）的统一查询点。
     */
    fun isResting(): Boolean {
        if (!isTimeLimitEnabled()) {
            return false
        }
        val restStart = appSettings.getCachedLong(KEY_REST_START_MS, 0L)
        if (restStart <= 0L) {
            return false
        }
        val restLimitMs = getRestLimitMin().toLong() * 60_000L
        val elapsed = System.currentTimeMillis() - restStart
        if (elapsed >= restLimitMs) {
            // 休息到期：清零累计、清休息戳、清计时段起点、清广告累计，恢复观看从头计时。
            appSettings.removeBlocking(KEY_REST_START_MS)
            appSettings.putIntBlocking(KEY_ACCUMULATED_MS, 0)
            appSettings.putIntBlocking(KEY_PSAS_ACCUMULATED_MS, 0)
            segmentStartElapsedMs = null
            _restingFlow.value = false
            AppLog.i(TAG, "rest ended, reset accumulated. elapsed=$elapsed restLimitMs=$restLimitMs")
            return false
        }
        _restingFlow.value = true
        return true
    }

    /**
     * 入口拦截：休息中返回带剩余时间的提示文案，否则返回 null 放行。
     * 剩余分钟向上取整（不足 1 分钟按 1 分钟显示）。
     */
    fun consumeBlockReason(): String? =
        remainingRestMin()?.let { "请关闭电视注意休息，还需休息 $it 分钟" }

    /** 多语言版本的 [consumeBlockReason]：返回经字符串资源格式化的提示文案。 */
    fun consumeBlockReason(context: Context): String? =
        remainingRestMin()?.let { context.getString(R.string.core_teen_rest_needed_format, it) }

    private fun remainingRestMin(): Int? {
        if (!isResting()) return null
        val restStart = appSettings.getCachedLong(KEY_REST_START_MS, 0L)
        val restLimitMs = getRestLimitMin().toLong() * 60_000L
        val remainingMs = (restLimitMs - (System.currentTimeMillis() - restStart)).coerceAtLeast(0L)
        // 向上取整：剩余 30 秒也显示 1 分钟
        return ((remainingMs + 60_000L - 1) / 60_000L).toInt().coerceAtLeast(1)
    }

    /**
     * 修改时长设置时调用：清掉累计观看时长与休息戳，避免脏状态。
     * 例如把上限调大后，旧累计值可能已超新上限，会立刻误触发休息。
     */
    fun resetForLimitChange() {
        appSettings.putIntBlocking(KEY_ACCUMULATED_MS, 0)
        appSettings.putIntBlocking(KEY_PSAS_ACCUMULATED_MS, 0)
        appSettings.removeBlocking(KEY_REST_START_MS)
        segmentStartElapsedMs = null
        _restingFlow.value = false
        AppLog.i(TAG, "reset for limit change")
    }

    /** App 启动时刷新一次休息状态（让 restingFlow 反映持久化中的真实值）。 */
    private fun refreshRestingState() {
        _restingFlow.value = isResting()
    }
}
