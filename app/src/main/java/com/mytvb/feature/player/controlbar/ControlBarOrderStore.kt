package com.mytvb.feature.player.controlbar

import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import org.json.JSONArray
import org.json.JSONObject
import org.koin.mp.KoinPlatform

/**
 * 播放控制条按钮自定义排序（issue#144 建议）：控制条 20 个按钮的显示顺序可调。
 * 存储为 appSettings 单键 JSON：{"v":1,"order":["play","next",...]}，只记录用户显式
 * 排过的相对顺序；未覆盖的按钮按默认顺序补尾，未知 id（版本演进删按钮）忽略，
 * 保证 [resolveOrder] 恒返回全量按钮且不重复。应用点在
 * MyPlayerControlViewLayoutManager 构造期读取——设置改动后重进播放页生效。
 */
enum class ControlBarButton(val key: String, val viewId: Int, val labelRes: Int) {
    PREVIOUS("previous", R.id.button_previous, R.string.previous_ep),
    PLAY("play", R.id.button_play, R.string.play_pause),
    NEXT("next", R.id.button_next, R.string.next_ep),
    MORE("more", R.id.button_more, R.string.more),
    COMMENT("comment", R.id.button_comment, R.string.player_comment_title),
    DANMAKU("danmaku", R.id.button_dm_switch, R.string.dm_switch),
    SUBTITLE("subtitle", R.id.button_subtitle, R.string.subtitle),
    SPEED("speed", R.id.button_play_speed, R.string.playSpeed),
    EPISODE("episode", R.id.button_choose_episode, R.string.choose_episode),
    UP_INFO("up_info", R.id.button_up_info, R.string.up_info),
    RELATED("related", R.id.button_related, R.string.related_video),
    REPEAT("repeat", R.id.button_repeat, R.string.play_order),
    SETTINGS("settings", R.id.exo_settings, R.string.setting),
    REFRESH("refresh", R.id.button_refresh, R.string.refresh),
    REWIND("rewind", R.id.button_rewind, R.string.rewind),
    FORWARD("forward", R.id.button_fast_forward, R.string.fast_forward),
    SC_SWITCH("sc_switch", R.id.button_sc_switch, R.string.sc_switch),
    MIRROR("mirror", R.id.button_mirror, R.string.screen_mirror),
    LIVE_SETTINGS("live_settings", R.id.button_live_settings, R.string.live_quality),
    CLOSE("close", R.id.button_close, R.string.close),
    ;

    companion object {
        fun fromKey(raw: String?): ControlBarButton? = entries.firstOrNull { it.key == raw }
    }
}

object ControlBarOrderStore {
    private const val TAG = "ControlBarOrderStore"
    private const val STORE_KEY = "player_control_bar_order"
    private const val JSON_VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_ORDER = "order"

    private fun appSettings(): AppSettingsDataStore = KoinPlatform.getKoin().get()

    /** 落盘的键序列（只含合法 id，去重保序）；未存储/损坏时返回空表=默认顺序。 */
    fun loadKeys(): List<String> {
        val raw = appSettings().getCachedString(STORE_KEY).orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(raw) }
            .getOrNull()
            ?.takeIf { it.optInt(KEY_VERSION, JSON_VERSION) == JSON_VERSION }
            ?.optJSONArray(KEY_ORDER)
            ?: return emptyList()
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val key = arr.optString(i)
            if (ControlBarButton.fromKey(key) != null) seen.add(key)
        }
        return seen.toList()
    }

    /** 展示/应用顺序：用户排过的在前，其余按默认序补尾（保证全量按钮恒不重不漏）。 */
    fun resolveOrder(): List<ControlBarButton> {
        val stored = loadKeys().mapNotNull(ControlBarButton.Companion::fromKey)
        if (stored.isEmpty()) return ControlBarButton.entries.toList()
        val remaining = ControlBarButton.entries.filterNot { button -> stored.any { it.key == button.key } }
        return stored + remaining
    }

    fun isCustomized(): Boolean = loadKeys().isNotEmpty()

    fun save(order: List<ControlBarButton>) {
        val arr = JSONArray()
        order.forEach { arr.put(it.key) }
        val json = JSONObject()
            .put(KEY_VERSION, JSON_VERSION)
            .put(KEY_ORDER, arr)
            .toString()
        appSettings().putStringBlocking(STORE_KEY, json)
        AppLog.d(TAG, "saved order=${order.joinToString { it.key }}")
    }

    /** 清空自定义（回到默认顺序）：写空串即视为未存储，[resolveOrder] 走默认。 */
    fun clear() {
        appSettings().putStringBlocking(STORE_KEY, "")
        AppLog.d(TAG, "cleared")
    }
}
