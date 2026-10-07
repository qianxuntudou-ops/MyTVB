package com.mytvb.core.ui.user

import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.network.api.BiliApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户标识（大会员/头像框/等级）统一补齐层：
 * 各页面接口下发的用户字段不全（如视频 owner 只有头像昵称），
 * 统一走 [fetch] 按 mid 缓存并从空间接口（x/space/wbi/acc/info）补拉。
 */
data class UserBadges(
    val mid: Long,
    val name: String = "",
    val avatar: String? = null,
    val level: Int? = null,
    val isVip: Boolean = false,
    val pendantUrl: String? = null,
)

object UserBadgesStore {

    private const val TAG = "UserBadgesStore"

    private val cache = ConcurrentHashMap<Long, UserBadges>()
    private val inFlight = ConcurrentHashMap<Long, CompletableDeferred<UserBadges?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(mid: Long): UserBadges? = cache[mid]

    suspend fun fetch(mid: Long): UserBadges? {
        if (mid <= 0L) return null
        cache[mid]?.let { return it }
        inFlight[mid]?.let { return it.await() }
        val deferred = CompletableDeferred<UserBadges?>()
        inFlight[mid] = deferred
        try {
            val badges = runCatching { BiliApi.spaceAccInfo(mid) }
                .onFailure { AppLog.w(TAG, "spaceAccInfo failed mid=$mid", it as? Exception) }
                .getOrNull()
            if (badges != null) cache[mid] = badges
            deferred.complete(badges)
            return badges
        } finally {
            inFlight.remove(mid)
        }
    }

    /**
     * UI 侧入口：命中缓存立即回调（主线程）；[allowFetch]=false 时仅读缓存不补拉
     * （列表场景一屏几十个用户逐个补拉会请求风暴，只在用户看过详情/空间后自然升级）。
     * 调用方需自行校验 view 复用（tag mid）。
     */
    fun enqueue(mid: Long, allowFetch: Boolean = true, onReady: (UserBadges) -> Unit) {
        cached(mid)?.let {
            withContextMain { onReady(it) }
            return
        }
        if (!allowFetch) return
        scope.launch {
            val badges = fetch(mid) ?: return@launch
            withContextMain { onReady(badges) }
        }
    }

    private fun withContextMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }
}

/** 用户名官方配色：大会员粉（#FB7299），普通用户用页面默认色。 */
object UserBadgeText {

    fun bind(textView: TextView, name: String, isVip: Boolean) {
        textView.text = name
        val colorRes = if (isVip) R.color.biliPink else R.color.textColor
        textView.setTextColor(ContextCompat.getColor(textView.context, colorRes))
    }
}
