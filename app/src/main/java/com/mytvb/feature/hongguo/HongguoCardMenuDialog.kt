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
import com.mytvb.feature.hongguo.net.HongguoClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 红果剧卡片长按菜单（弹出形式对齐 [com.mytvb.ui.dialog.VideoCardMenuDialog]）：
 * 「收藏」= [HongguoRepository.toggleFavorite] 本地切换 + 登录态云同步（详情弹窗同款）；
 * 「创作者」= multi-detail 查作者名 → 搜作者 + 归一精确匹配过滤（HGTV authorBox 同款）；
 * 「相关作品」= plan/v1 from=detail_page_more_related（HGTV relatedBox 同款）。
 * 后两个入口都弹 [HongguoDramaListDialog] 网格展示，点卡片进详情弹窗。
 * 菜单构造即并行预取两路数据（用户浏览菜单的间隙跑完请求，点击秒弹；
 * 未就绪时按钮副标题先显「正在加载」反馈）。
 */
class HongguoCardMenuDialog(
    context: Context,
    private val item: DramaUi,
    private val repository: HongguoRepository
) : AppCompatDialog(context, R.style.DialogTheme) {

    private val binding = DialogHongguoCardMenuBinding.inflate(LayoutInflater.from(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var actionInProgress = false

    /** 创作者链一次加载结果（creator=null 表示确认无创作者，与网络失败区分）。 */
    private class CreatorLoad(val creator: HongguoClient.Creator?, val works: List<HongguoClient.Drama>)

    // 菜单弹出即预取两路数据（用户浏览菜单的间隙把请求跑完，点击秒弹）；
    // 返回 null=预取网络失败，点击时降级直拉重试
    private val creatorWorksDeferred: Deferred<CreatorLoad?> = scope.async {
        try {
            val load = loadCreatorWorks()
            load.creator?.takeIf { it.name.isNotBlank() }?.let {
                binding.textCreatorSummary.text = it.name
            }
            load
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private val relatedDeferred: Deferred<List<HongguoClient.Drama>?> = scope.async {
        try {
            repository.related(item.seriesId, item.title)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

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
        // 数据未就绪才提示加载（就绪时 await 同帧返回，不闪"正在加载"）
        if (!creatorWorksDeferred.isCompleted) {
            binding.textCreatorSummary.setText(R.string.loading)
        }
        scope.launch {
            try {
                val load = creatorWorksDeferred.await() ?: loadCreatorWorks()
                val creator = load.creator
                if (creator == null || creator.name.isBlank()) {
                    toast(context.getString(R.string.hongguo_menu_creator_unknown))
                    return@launch
                }
                binding.textCreatorSummary.text = creator.name
                when {
                    load.works.isEmpty() ->
                        toast(context.getString(R.string.hongguo_menu_creator_only_one))
                    else -> {
                        dismiss()
                        HongguoDramaListDialog(
                            context = context,
                            repository = repository,
                            title = context.getString(R.string.hongguo_menu_creator),
                            items = load.works
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

    private suspend fun loadCreatorWorks(): CreatorLoad {
        val creator = repository.creatorInfo(item.seriesId)
        if (creator == null || creator.name.isBlank()) return CreatorLoad(null, emptyList())
        return CreatorLoad(creator, repository.authorWorks(creator, item.seriesId))
    }

    private fun openRelated() {
        if (actionInProgress) return
        actionInProgress = true
        if (!relatedDeferred.isCompleted) {
            binding.textRelatedSummary.setText(R.string.loading)
        }
        scope.launch {
            try {
                val related = relatedDeferred.await()
                    ?: repository.related(item.seriesId, item.title)
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
