package com.mytvb.feature.player.shortcut

import android.app.Activity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import org.json.JSONArray
import org.json.JSONObject
import org.koin.mp.KoinPlatform

/**
 * 播放器自定义快捷键：任意非系统键（默认数字 0-9）→ 动作的绑定表（issue#116）。
 * 存储为 appSettings 单键 JSON：{"v":1,"items":[{"k":7,"a":"triple"}]}，一键一动作。
 * 拦截入口在各播放器宿主 Activity.dispatchKeyEvent 最前段（数字键全空闲，见各宿主），
 * 设置页「通用·交互·自定义快捷键」负责绑定编辑（按键捕获/改动作/删除/清空）。
 */
enum class PlayerShortcutAction(val id: String) {
    LIKE("like"),
    COIN("coin"),
    TRIPLE("triple"),
    DEFAULT_SPEED("default_speed"),
    SPONSOR_TOGGLE("sponsor_toggle"),
    SUBTITLE_MODE("subtitle_mode"),
    BOTTOM_PROGRESS("bottom_progress"),
    CHAPTER_BAR("chapter_bar"),
    ONLINE_WATCHING("online_watching"),
    SUPER_CHAT("super_chat"),
    ALWAYS_CLOCK("always_clock"),
    DANMAKU("danmaku"),
    COMMENT_PANEL("comment_panel"),
    SMART_SHIELD("smart_shield"),
    MERGE_DUPLICATE("merge_duplicate"),
    DOUBLE_BACK_EXIT("double_back_exit"),
    ;

    companion object {
        fun fromId(raw: String?): PlayerShortcutAction? = entries.firstOrNull { it.id == raw }
    }
}

/** 触发方式：同一键位短按/长按各占一个槽位（长按阈值与红果 OK 长按一致 500ms）。 */
enum class PlayerShortcutTrigger(val id: String) {
    SHORT("s"),
    LONG("l"),
    ;

    companion object {
        fun fromId(raw: String?): PlayerShortcutTrigger = if (raw == LONG.id) LONG else SHORT
    }
}

data class PlayerShortcut(
    val keyCode: Int,
    val action: PlayerShortcutAction,
    val trigger: PlayerShortcutTrigger = PlayerShortcutTrigger.SHORT,
)

/**
 * 快捷键动作落盘用的设置 key：与 SettingsFragment / PlayerSettingsStore 同键
 * （双份定义惯例），存储值口径也一致——开关存中文"开/关"，倍速存数字串，字幕存三态中文。
 */
object ShortcutSettingKeys {
    const val DEFAULT_PLAY_SPEED = "default_play_speed"
    const val SUBTITLE_DEFAULT_MODE = "subtitle_default_mode"
    const val SPONSOR_BLOCK_ENABLED = "sponsor_block_enabled"
    const val SHOW_BOTTOM_PROGRESS_BAR = "show_bottom_progress_bar"
    const val SHOW_CHAPTER_BAR = "show_chapter_bar"
    const val SHOW_ONLINE_WATCHING = "show_online_watching"
    const val SHOW_ALWAYS_ON_CLOCK = "show_always_on_clock"
    const val SHOW_LIVE_SUPER_CHAT = "show_live_super_chat"
}

object PlayerShortcutStore {
    private const val TAG = "PlayerShortcutStore"
    private const val STORE_KEY = "player_custom_shortcuts"
    private const val JSON_VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_ITEMS = "items"
    private const val KEY_KEYCODE = "k"
    private const val KEY_ACTION = "a"
    private const val KEY_TRIGGER = "m"

    /** 小键盘数字键归一到主行数字键：遥控/键盘可能发任一种，用户语义都是同一个数字。 */
    fun normalizeKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_NUMPAD_0 -> KeyEvent.KEYCODE_0
        KeyEvent.KEYCODE_NUMPAD_1 -> KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_NUMPAD_2 -> KeyEvent.KEYCODE_2
        KeyEvent.KEYCODE_NUMPAD_3 -> KeyEvent.KEYCODE_3
        KeyEvent.KEYCODE_NUMPAD_4 -> KeyEvent.KEYCODE_4
        KeyEvent.KEYCODE_NUMPAD_5 -> KeyEvent.KEYCODE_5
        KeyEvent.KEYCODE_NUMPAD_6 -> KeyEvent.KEYCODE_6
        KeyEvent.KEYCODE_NUMPAD_7 -> KeyEvent.KEYCODE_7
        KeyEvent.KEYCODE_NUMPAD_8 -> KeyEvent.KEYCODE_8
        KeyEvent.KEYCODE_NUMPAD_9 -> KeyEvent.KEYCODE_9
        else -> keyCode
    }

    /** 系统导航/确认/菜单/音量键禁绑，保证基础操作永不被快捷键抢占。 */
    fun isForbiddenKeyCode(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }

    private fun appSettings(): AppSettingsDataStore = KoinPlatform.getKoin().get()

    fun load(): List<PlayerShortcut> {
        val raw = appSettings().getCachedString(STORE_KEY).orEmpty()
        if (raw.isBlank()) return emptyList()
        val items = runCatching { JSONObject(raw) }
            .getOrNull()
            ?.takeIf { it.optInt(KEY_VERSION, JSON_VERSION) == JSON_VERSION }
            ?.optJSONArray(KEY_ITEMS)
            ?: return emptyList()
        val out = ArrayList<PlayerShortcut>(items.length())
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val keyCode = obj.optInt(KEY_KEYCODE, 0)
            val action = PlayerShortcutAction.fromId(obj.optString(KEY_ACTION))
            if (keyCode <= 0 || action == null) continue
            if (isForbiddenKeyCode(keyCode)) continue
            val trigger = PlayerShortcutTrigger.fromId(obj.optString(KEY_TRIGGER))
            out.add(PlayerShortcut(keyCode = normalizeKeyCode(keyCode), action = action, trigger = trigger))
        }
        // 历史数据自愈：同键同动作占双槽（旧版无此规则）在读取层就去重，列表展示干净
        return dedup(out)
    }

    fun hasBinding(keyCode: Int): Boolean = load().any { it.keyCode == keyCode }

    fun actionFor(keyCode: Int, trigger: PlayerShortcutTrigger): PlayerShortcutAction? =
        load().firstOrNull { it.keyCode == keyCode && it.trigger == trigger }?.action

    fun save(items: List<PlayerShortcut>) {
        val deduped = dedup(items)
        val arr = JSONArray()
        for (item in deduped) {
            arr.put(
                JSONObject()
                    .put(KEY_KEYCODE, item.keyCode)
                    .put(KEY_ACTION, item.action.id)
                    .put(KEY_TRIGGER, item.trigger.id),
            )
        }
        val json = JSONObject()
            .put(KEY_VERSION, JSON_VERSION)
            .put(KEY_ITEMS, arr)
            .toString()
        appSettings().putStringBlocking(STORE_KEY, json)
        AppLog.d(TAG, "saved ${deduped.size} shortcuts")
    }

    /**
     * 三重去重（后写覆盖先写，禁绑键丢弃）：
     * ① (keyCode, trigger)——一键一槽；
     * ② (action, trigger)——同一动作同一触发全局唯一（重复绑定=改绑）；
     * ③ (keyCode, action)——同一键上同一动作不得占短按+长按双槽（冗余无意义，新绑顶掉旧绑）。
     */
    private fun dedup(items: List<PlayerShortcut>): List<PlayerShortcut> {
        val byKey = LinkedHashMap<Pair<Int, PlayerShortcutTrigger>, PlayerShortcut>()
        for (item in items) {
            if (item.keyCode <= 0 || isForbiddenKeyCode(item.keyCode)) continue
            byKey[item.keyCode to item.trigger] = item
        }
        val byAction = LinkedHashMap<Pair<PlayerShortcutAction, PlayerShortcutTrigger>, PlayerShortcut>()
        for (item in byKey.values) {
            byAction[item.action to item.trigger] = item
        }
        val byKeyAction = LinkedHashMap<Pair<Int, PlayerShortcutAction>, PlayerShortcut>()
        for (item in byAction.values) {
            byKeyAction[item.keyCode to item.action] = item
        }
        return byKeyAction.values.toList()
    }

    fun upsert(shortcut: PlayerShortcut) {
        val existing = load().filterNot { it.keyCode == shortcut.keyCode && it.trigger == shortcut.trigger }
        save(existing + shortcut)
    }

    fun remove(keyCode: Int, trigger: PlayerShortcutTrigger) {
        save(load().filterNot { it.keyCode == keyCode && it.trigger == trigger })
    }

    fun clear() {
        save(emptyList())
    }
}

object PlayerShortcutKeyNames {
    /** 遥控常用键给可读名，其余退化为 KeyEvent.keyCodeToString 去前缀。 */
    fun displayName(keyCode: Int): String {
        when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> return "0"
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> return "1"
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> return "2"
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> return "3"
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> return "4"
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> return "5"
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> return "6"
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> return "7"
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> return "8"
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> return "9"
            KeyEvent.KEYCODE_PROG_RED -> return "红键"
            KeyEvent.KEYCODE_PROG_GREEN -> return "绿键"
            KeyEvent.KEYCODE_PROG_YELLOW -> return "黄键"
            KeyEvent.KEYCODE_PROG_BLUE -> return "蓝键"
        }
        // KEYCODE_A..Z(29..54)：字母键
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return "${('A' + (keyCode - KeyEvent.KEYCODE_A))} 键"
        }
        return KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

/**
 * 宿主按键调度：DOWN 记时刻并挂 [longPressMs] 定时器（该键有任意槽位绑定即先吞）；
 * 按住到点**立即触发长按槽**（不等松手，与红果 OK 长按同款），<阈值松手则触发短按槽。
 * 长按连发(repeat)事件吞掉不动作。绑定候选键（数字键等）对两播放器无其他用途，前置吞掉无副作用；
 * 唯一例外是文本输入框持焦（弹幕发送/评论输入），此时数字键属于文字输入，一律放行。
 */
class PlayerShortcutKeyDispatcher(
    private val longPressMs: Long = 500L,
    private val isTextInputActive: () -> Boolean = { false },
    private val perform: (PlayerShortcutAction) -> Unit,
) {
    private companion object {
        const val TAG = "PlayerShortcut"
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingKeyCode = 0
    private var longFired = false
    private var longFireRunnable: Runnable? = null

    fun dispatch(event: KeyEvent): Boolean {
        if (isTextInputActive()) return false
        val keyCode = PlayerShortcutStore.normalizeKeyCode(event.keyCode)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return pendingKeyCode != 0
                if (PlayerShortcutStore.hasBinding(keyCode)) {
                    AppLog.d(TAG, "dispatch DOWN consumed keyCode=$keyCode")
                    pendingKeyCode = keyCode
                    longFired = false
                    scheduleLongFire(keyCode)
                    return true
                }
                return false
            }
            KeyEvent.ACTION_UP -> {
                if (pendingKeyCode != keyCode) return false
                cancelLongFire()
                pendingKeyCode = 0
                if (longFired) {
                    // 长按已到点触发（或长按槽为空被静默吞掉），UP 只收尾
                    AppLog.d(TAG, "dispatch UP keyCode=$keyCode after long fired")
                    longFired = false
                    return true
                }
                val action = PlayerShortcutStore.actionFor(keyCode, PlayerShortcutTrigger.SHORT)
                AppLog.d(TAG, "dispatch UP keyCode=$keyCode trigger=SHORT action=$action")
                // 未绑定的槽位（如只绑了长按却短按）静默：未绑定=无操作
                action?.let(perform)
                return true
            }
        }
        return false
    }

    /** 到点立即触发长按槽；槽位为空也置 [longFired]（松手不再补发短按，保持空槽静默）。 */
    private fun scheduleLongFire(keyCode: Int) {
        cancelLongFire()
        val runnable = Runnable {
            longFired = true
            val action = PlayerShortcutStore.actionFor(keyCode, PlayerShortcutTrigger.LONG)
            AppLog.d(TAG, "dispatch LONG fired keyCode=$keyCode action=$action")
            if (action != null) perform(action)
        }
        longFireRunnable = runnable
        handler.postDelayed(runnable, longPressMs)
    }

    private fun cancelLongFire() {
        longFireRunnable?.let { handler.removeCallbacks(it) }
        longFireRunnable = null
    }
}

/** 宿主是否有文本输入框持焦（弹幕发送/评论/搜索输入），此时数字键属于文字输入，快捷键不拦截。 */
fun Activity.hasTextInputFocus(): Boolean {
    var focus: android.view.View? = currentFocus ?: return false
    while (focus != null) {
        if (focus is EditText) return true
        // 注意：ViewGroup 自身持焦时 findFocus() 返回 this，不前进即终止，
        // 否则 while 死循环把主线程打满（MuMu 实测 ANR：Waited 25s for KeyEvent）
        focus = if (focus is ViewGroup) {
            val next = focus.findFocus()
            if (next === focus) null else next
        } else {
            null
        }
    }
    return false
}
