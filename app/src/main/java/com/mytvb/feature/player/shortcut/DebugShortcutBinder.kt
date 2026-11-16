package com.mytvb.feature.player.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mytvb.BuildConfig
import com.mytvb.core.ui.base.AppToast

/**
 * 仅 debug 构建生效的测试通道：一键写入快捷键绑定表，免去 UI 手工逐条绑定。
 * 用法：adb shell am broadcast -a com.mytvb.DEBUG_BIND --es bindings "8:like,9:coin"
 * （bindings 格式 keyCode:actionId[,keyCode:actionId...]，触发方式默认短按，可用 keyCode:actionId:l 指定长按）
 */
class DebugShortcutBinder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val raw = intent.getStringExtra("bindings") ?: return
        val items = raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val keyCode = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val action = PlayerShortcutAction.fromId(parts.getOrNull(1)) ?: return@mapNotNull null
            val trigger = if (parts.getOrNull(2) == "l") PlayerShortcutTrigger.LONG else PlayerShortcutTrigger.SHORT
            PlayerShortcut(keyCode, action, trigger)
        }
        PlayerShortcutStore.save(items)
        AppToast.show(context, "debug bind ${items.size} shortcuts")
    }
}
