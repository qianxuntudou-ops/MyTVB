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
 * 「收藏」= [HongguoRepository.toggleFavorite] 本地切换 + 登录态云同步（详情弹窗同款）；
 * 「创作者」= multi-detail 查作者名 → 搜作者 + 归一精确匹配过滤（HGTV authorBox 同款）；
 * 「相关作品」= plan/v1 from=detail_page_more_related（HGTV relatedBox 同款）。
 * 后两个入口都弹 [HongguoDramaListDialog] 网格展示，点卡片进详情弹窗。
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
        binding.buttonFavorite.setOnClickListener { toggleFavorite() }
        binding.buttonCreator.setOnClickListener { openCreatorWorks() }
        binding.buttonRelated.setOnClickListener { openRelated() }
        refreshFavoriteState()
        setOnShowListener {
            binding.root.post { binding.buttonFavorite.requestFocus() }
        }
    }

    // ---- 收藏（第一个选项；本地秒切，登录态后台云同步） ----

    /** 收藏按钮态：主标题「收藏/取消收藏」+ 副标题当前状态，点击后原地刷新。 */
    private fun refreshFavoriteState() {
        val favorite = repository.isFavorite(item.seriesId)
        binding.textFavoriteTitle.setText(
            if (favorite) R.string.hongguo_unfavorite else R.string.hongguo_favorite
        )
        binding.textFavoriteSummary.setText(
            if (favorite) R.string.hongguo_menu_unfavorite_summary
            else R.string.hongguo_menu_favorite_summary
        )
    }

    private fun toggleFavorite() {
        // 有观看记录则沿用其进度（收藏页「看到 N/M 集」不丢），否则用卡片信息新建
        val progress = repository.progressOf(item.seriesId)
        repository.toggleFavorite(
            HongguoRepository.WatchRecord(
                seriesId = item.seriesId,
                title = item.title,
                cover = item.cover,
                episodeIndex = progress?.episodeIndex ?: 0,
                episodeCount = if (item.episodeCount > 0) item.episodeCount else progress?.episodeCount ?: 0,
                positionMs = progress?.positionMs ?: 0L,
                durationMs = progress?.durationMs ?: 0L,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshFavoriteState()
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
