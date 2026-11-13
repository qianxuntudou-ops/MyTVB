package com.mytvb.feature.hongguo

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.base.DialogWindowFit
import com.mytvb.databinding.DialogHongguoCardMenuBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 红果剧卡片长按菜单（弹出形式对齐 [com.mytvb.ui.dialog.VideoCardMenuDialog]）：
 * 「创作者」= multi-detail 查作者名 → 搜作者 + 归一精确匹配过滤（HGTV authorBox 同款）；
 * 「相关作品」= plan/v1 from=detail_page_more_related（HGTV relatedBox 同款）。
 * 两个入口都弹 [HongguoDramaListDialog] 网格展示，点卡片进详情弹窗。
 */
class HongguoCardMenuDialog(
    context: Context,
    private val item: DramaUi,
    private val repository: HongguoRepository
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogHongguoCardMenuBinding.inflate(LayoutInflater.from(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var actionInProgress = false

    companion object {
        private const val MAX_TITLE_CHARS = 30
        private const val TITLE_ELLIPSIS = "···"

        fun show(context: Context, repository: HongguoRepository, item: DramaUi) {
            HongguoCardMenuDialog(context, item, repository).show()
        }
    }

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        // fill 全屏窗口 + 居中卡片，宽度与 VideoCardMenuDialog 同款 45% 屏宽
        DialogWindowFit.overlay(
            this, binding.root,
            (context.resources.displayMetrics.widthPixels * 0.45).toInt(),
            tag = "hongguoMenu"
        )
        setCanceledOnTouchOutside(true)
        binding.textTitle.text = formatTitle(item.title)
        binding.root.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
                && event.action == KeyEvent.ACTION_UP
            ) {
                dismiss()
                true
            } else {
                false
            }
        }
        binding.buttonCreator.setOnClickListener { openCreatorWorks() }
        binding.buttonRelated.setOnClickListener { openRelated() }
        setOnShowListener {
            binding.root.post { binding.buttonCreator.requestFocus() }
        }
    }

    private fun openCreatorWorks() {
        if (actionInProgress) return
        actionInProgress = true
        scope.launch {
            try {
                val creator = repository.creatorInfo(item.seriesId)
                if (creator == null || creator.name.isBlank()) {
                    toast(context.getString(R.string.hongguo_menu_creator_unknown))
                    return@launch
                }
                binding.textCreatorSummary.text = creator.name
                val works = repository.authorWorks(creator, item.seriesId)
                when {
                    works.isEmpty() ->
                        toast(context.getString(R.string.hongguo_menu_creator_only_one))
                    else -> {
                        dismiss()
                        HongguoDramaListDialog(
                            context = context,
                            repository = repository,
                            title = context.getString(R.string.hongguo_menu_creator),
                            items = works
                        ).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(context.getString(R.string.hongguo_menu_creator_failed))
            } finally {
                actionInProgress = false
            }
        }
    }

    private fun openRelated() {
        if (actionInProgress) return
        actionInProgress = true
        scope.launch {
            try {
                val related = repository.related(item.seriesId, item.title)
                if (related.isEmpty()) {
                    toast(context.getString(R.string.hongguo_menu_related_empty))
                    return@launch
                }
                dismiss()
                HongguoDramaListDialog(
                    context = context,
                    repository = repository,
                    title = context.getString(R.string.hongguo_menu_related),
                    items = related
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(context.getString(R.string.hongguo_menu_related_failed))
            } finally {
                actionInProgress = false
            }
        }
    }

    private fun formatTitle(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.length <= MAX_TITLE_CHARS) {
            trimmed
        } else {
            trimmed.take(MAX_TITLE_CHARS - TITLE_ELLIPSIS.length) + TITLE_ELLIPSIS
        }
    }

    private fun toast(message: String) {
        AppToast.show(context, message)
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }
}
