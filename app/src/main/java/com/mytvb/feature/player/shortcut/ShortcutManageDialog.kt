package com.mytvb.feature.player.shortcut

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.CellShortcutEntryBinding

/**
 * 快捷键管理弹窗（issue#116）：顶部操作行（添加=主 / 清空=次级）+ 下方绑定列表 + 空态。
 * 添加/改绑流程的动作选择、触发方式选择、按键捕获弹窗叠加在本弹窗之上，
 * 完成后列表原地刷新（不关本弹窗，逐层 BACK 退回）。
 */
class ShortcutManageDialog(
    context: Context,
    private val onChanged: () -> Unit = {},
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val entries = mutableListOf<PlayerShortcut>()
    private val adapter = EntryAdapter()

    init {
        DialogWindowFit.overlay(
            this,
            LayoutInflater.from(context).inflate(R.layout.dialog_shortcut_manage, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "shortcutManage",
        )
        findViewById<View>(R.id.dialog_root)?.setOnClickListener { dismiss() }
        findViewById<android.widget.TextView>(R.id.top_title)?.text =
            context.getString(R.string.shortcut_setting)
        findViewById<View>(R.id.btn_add)?.setOnClickListener { pickAction() }
        findViewById<View>(R.id.btn_clear)?.setOnClickListener { confirmClear() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true,
                ),
            )
        }
        refresh()
        // 初始焦点：init 时窗口未 attach 单次 requestFocus 必假失败，
        // onShow + post 逐帧重试（f83b2c55 弹窗焦点三件套同款）
        setOnShowListener { focusAddButtonAfterLayout() }
    }

    private fun focusAddButtonAfterLayout(attemptsLeft: Int = 20) {
        val button = findViewById<View>(R.id.btn_add) ?: return
        button.post {
            if (button.requestFocus()) return@post
            if (attemptsLeft > 0) focusAddButtonAfterLayout(attemptsLeft - 1)
        }
    }

    /** 清空为破坏性批量操作：先弹二次确认（选项=清空/取消），管理弹窗保持不动。 */
    private fun confirmClear() {
        if (PlayerShortcutStore.load().isEmpty()) return
        showShortcutChoiceDialog(
            context,
            context.getString(R.string.shortcut_clear_confirm),
            arrayOf(context.getString(R.string.shortcut_clear_all), context.getString(R.string.cancel)),
            0,
        ) { index ->
            if (index == 0) {
                PlayerShortcutStore.clear()
                refresh()
                notifyChanged()
                AppToast.show(context, R.string.shortcut_cleared)
            }
        }
    }

    private fun refresh() {
        entries.clear()
        entries.addAll(PlayerShortcutStore.load())
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty_hint)?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recyclerView)?.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun notifyChanged() = onChanged()

    // —— 添加流程：动作 → 触发方式 → 按键捕获 ——

    private fun pickAction() {
        val actions = PlayerShortcutAction.entries
        val labels = actions.map { actionLabel(it) }.toTypedArray()
        showShortcutChoiceDialog(context, context.getString(R.string.shortcut_pick_action), labels, 0) { index ->
            actions.getOrNull(index)?.let { pickTrigger(it) }
        }
    }

    private fun pickTrigger(action: PlayerShortcutAction) {
        val shortLabel = context.getString(R.string.shortcut_trigger_short)
        val longLabel = context.getString(R.string.shortcut_trigger_long)
        showShortcutChoiceDialog(
            context,
            context.getString(R.string.shortcut_pick_trigger),
            arrayOf(shortLabel, longLabel),
            0,
        ) { index ->
            val trigger = if (index == 1) PlayerShortcutTrigger.LONG else PlayerShortcutTrigger.SHORT
            capture(action, trigger)
        }
    }

    private fun capture(action: PlayerShortcutAction, trigger: PlayerShortcutTrigger) {
        ShortcutKeyCaptureDialog(context, actionLabel(action) + triggerSuffix(trigger)) { keyCode ->
            val normalized = PlayerShortcutStore.normalizeKeyCode(keyCode)
            PlayerShortcutStore.upsert(PlayerShortcut(normalized, action, trigger))
            refresh()
            notifyChanged()
            AppToast.show(context, entryLabel(PlayerShortcut(normalized, action, trigger)))
        }.show()
    }

    // —— 条目菜单：改绑（保留触发）/ 删除 ——

    private fun entryMenu(shortcut: PlayerShortcut) {
        showShortcutChoiceDialog(
            context,
            entryLabel(shortcut),
            arrayOf(
                context.getString(R.string.shortcut_rebind),
                context.getString(R.string.shortcut_remove),
            ),
            0,
        ) { index ->
            when (index) {
                0 -> ShortcutKeyCaptureDialog(context, actionLabel(shortcut.action) + triggerSuffix(shortcut.trigger)) { keyCode ->
                    val normalized = PlayerShortcutStore.normalizeKeyCode(keyCode)
                    PlayerShortcutStore.upsert(PlayerShortcut(normalized, shortcut.action, shortcut.trigger))
                    refresh()
                    notifyChanged()
                }.show()
                1 -> {
                    PlayerShortcutStore.remove(shortcut.keyCode, shortcut.trigger)
                    refresh()
                    notifyChanged()
                }
            }
        }
    }

    // —— 标签 ——

    private fun actionLabel(action: PlayerShortcutAction): String = with(context) {
        when (action) {
            PlayerShortcutAction.LIKE -> getString(R.string.like)
            PlayerShortcutAction.COIN -> getString(R.string.give_coin)
            PlayerShortcutAction.TRIPLE -> getString(R.string.triple_action)
            PlayerShortcutAction.DEFAULT_SPEED -> getString(R.string.default_play_speed)
            PlayerShortcutAction.SPONSOR_TOGGLE -> getString(R.string.sponsor_block)
            PlayerShortcutAction.SUBTITLE_MODE -> getString(R.string.show_subtitle_default)
            PlayerShortcutAction.BOTTOM_PROGRESS -> getString(R.string.show_bottom_progress_bar)
            PlayerShortcutAction.CHAPTER_BAR -> getString(R.string.show_chapter_bar)
            PlayerShortcutAction.ONLINE_WATCHING -> getString(R.string.show_online_watching)
            PlayerShortcutAction.SUPER_CHAT -> getString(R.string.show_live_super_chat)
            PlayerShortcutAction.ALWAYS_CLOCK -> getString(R.string.show_always_on_clock)
            PlayerShortcutAction.DANMAKU -> getString(R.string.dm_switch)
            PlayerShortcutAction.COMMENT_PANEL -> getString(R.string.player_comment_title)
            PlayerShortcutAction.SMART_SHIELD -> getString(R.string.dm_smart_shield)
            PlayerShortcutAction.MERGE_DUPLICATE -> getString(R.string.dm_merge_duplicate)
            PlayerShortcutAction.DOUBLE_BACK_EXIT -> getString(R.string.exit_double_confirm)
        }
    }

    private fun triggerSuffix(trigger: PlayerShortcutTrigger): String =
        if (trigger == PlayerShortcutTrigger.LONG) "（${context.getString(R.string.shortcut_trigger_long)}）" else ""

    private fun entryLabel(shortcut: PlayerShortcut): String =
        "${PlayerShortcutKeyNames.displayName(shortcut.keyCode)} · ${actionLabel(shortcut.action)}${triggerSuffix(shortcut.trigger)}"

    // —— 列表 ——

    private inner class EntryAdapter : RecyclerView.Adapter<EntryViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder =
            EntryViewHolder(CellShortcutEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            val shortcut = entries[position]
            holder.binding.tvKey.text = PlayerShortcutKeyNames.displayName(shortcut.keyCode)
            holder.binding.tvAction.text = actionLabel(shortcut.action)
            holder.binding.tvTrigger.text =
                if (shortcut.trigger == PlayerShortcutTrigger.LONG) {
                    context.getString(R.string.shortcut_trigger_long)
                } else {
                    ""
                }
            holder.binding.root.setOnClickListener { entryMenu(shortcut) }
        }
    }

    private class EntryViewHolder(val binding: CellShortcutEntryBinding) : RecyclerView.ViewHolder(binding.root)
}
