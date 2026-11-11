package com.mytvb.feature.hongguo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.ui.base.AppToast
import com.mytvb.core.ui.image.ImageLoader
import com.mytvb.databinding.CellHongguoFnCardBinding
import com.mytvb.databinding.FragmentHongguoMineBinding
import com.mytvb.feature.hongguo.net.HongguoSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 红果「我的」页（2026-11-11 重设计）：左账号卡 + 右 2×2 功能卡区。
 * 功能卡=稍后再看/我的预约/本机收藏/观看历史，数量徽标+最近 3 张封面小海报+说明行，
 * 点击进封面货架（HongguoFragment.showShelfOverlay），替代原纯文字 ChoiceDialog。
 * 未登录态：主按钮「登录红果账号」，云端类卡虚线占位引导登录，本机类卡点击仍可用。
 * 红果账号与 B 站完全隔离（票据在 hongguo_session，登出只清红果票据保留设备身份）。
 */
class HongguoMinePage : Fragment(), HongguoPageFocus {

    private var _binding: FragmentHongguoMineBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { HongguoRepository.get(requireContext()) }
    private var loggedIn = false
    private var subscribeItems: List<HongguoSync.SubscribeItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHongguoMineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonLogin.setOnClickListener { showLoginDialog() }
        binding.buttonLogout.setOnClickListener {
            repository.account.logout()
            refreshLoginState()
            AppToast.show(requireContext(), "已退出红果账号")
        }
        binding.buttonClear.setOnClickListener {
            repository.clearLocal()
            refreshLoginState()
            AppToast.show(requireContext(), "已清除本机红果数据")
        }
        // 功能卡点击：云端类（稍后再看/预约）未登录引导登录；本机类（收藏/历史）未登录仍可用
        binding.fnWatchLater.root.setOnClickListener {
            if (loggedIn) openShelf(HongguoShelfPage.Source.WATCH_LATER) else showLoginDialog()
        }
        binding.fnSubscribe.root.setOnClickListener {
            if (loggedIn) openShelf(HongguoShelfPage.Source.SUBSCRIBE) else showLoginDialog()
        }
        binding.fnFavorite.root.setOnClickListener { openShelf(HongguoShelfPage.Source.FAVORITE) }
        binding.fnHistory.root.setOnClickListener { openShelf(HongguoShelfPage.Source.HISTORY) }
        refreshLoginState()
    }

    /** 侧栏 RIGHT / 顶部 tab DOWN 的落点：未登录→登录按钮，已登录→稍后再看卡。 */
    override fun focusFirstCard(): Boolean {
        if (!isAdded || view == null) return false
        val target =
            if (binding.buttonLogin.isVisible) binding.buttonLogin else binding.fnWatchLater.root
        return target.requestFocus()
    }

    private fun showLoginDialog() {
        HongguoLoginDialog.show(requireContext(), repository) { refreshLoginState() }
    }

    private fun openShelf(source: HongguoShelfPage.Source) {
        val host = parentFragment as? HongguoFragment ?: return
        host.showShelfOverlay(source)
    }

    private fun refreshLoginState() {
        val profile = repository.account.storedProfile()
        loggedIn = profile != null
        val stats = getString(
            R.string.hongguo_mine_stats,
            repository.favorites().size,
            repository.history().size
        )
        if (profile != null) {
            binding.textNickname.text = profile.name.ifEmpty { "红果用户" }
            binding.textUid.text = "红果号：${profile.uid}"
            binding.textUid.isVisible = true
            binding.textStats.text = stats
            binding.textSync.setText(R.string.hongguo_cloud_sync_on)
            binding.rowSync.isVisible = true
            binding.buttonLogin.isVisible = false
            binding.groupAccountActions.isVisible = true
            // 启动登录态校验+token 滚动（HGTV H0/g.c() scene=boot 同款；失败静默）
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.sync.bootVerify() } }
            }
            if (profile.avatar.startsWith("http")) {
                binding.imageAvatar.imageTintList = null
                ImageLoader.load(
                    imageView = binding.imageAvatar,
                    url = profile.avatar,
                    placeholder = R.drawable.ic_hongguo,
                    error = R.drawable.ic_hongguo
                )
            } else {
                // 无头像：恢复默认图标+主题色染色（清掉上次网络图残留的 tint=null）
                binding.imageAvatar.setImageResource(R.drawable.ic_hongguo)
                binding.imageAvatar.imageTintList = android.content.res.ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        binding.imageAvatar, androidx.appcompat.R.attr.colorAccent
                    )
                )
            }
            bindLocalCards()
            fetchSubscribe()
        } else {
            binding.textNickname.setText(R.string.hongguo_not_logged_in)
            binding.textUid.setText(R.string.hongguo_login_sync_hint)
            binding.textUid.isVisible = true
            binding.textStats.text = stats
            binding.rowSync.isVisible = false
            binding.buttonLogin.isVisible = true
            binding.groupAccountActions.isVisible = false
            binding.imageAvatar.setImageResource(R.drawable.ic_hongguo)
            bindCardsLoggedOut()
        }
    }

    // ---- 功能卡 ----

    /** 已登录：四卡填真实数据（预约异步补充）。 */
    private fun bindLocalCards() {
        val watchLater = repository.watchLater()
        val favorites = repository.favorites()
        val history = repository.history()
        val recentWatchLater = watchLater.firstOrNull()
        bindCard(
            binding.fnWatchLater,
            titleRes = R.string.hongguo_watch_later,
            iconRes = R.drawable.watch_later,
            count = watchLater.size,
            records = watchLater,
            subText = recentWatchLater?.let {
                getString(R.string.hongguo_fn_sub_recent, it.title)
            } ?: getString(R.string.hongguo_watch_later_empty),
            emptyPlaceholder = false
        )
        bindSubscribeCard()
        bindCard(
            binding.fnFavorite,
            titleRes = R.string.hongguo_tab_favorite,
            iconRes = R.drawable.ic_hongguo_fn_heart,
            count = favorites.size,
            records = favorites,
            subText = getString(R.string.hongguo_fn_sub_sync),
            emptyPlaceholder = false
        )
        val lastHistory = history.firstOrNull()
        bindCard(
            binding.fnHistory,
            titleRes = R.string.hongguo_tab_history,
            iconRes = R.drawable.ic_history,
            count = history.size,
            records = history,
            subText = lastHistory?.let {
                getString(R.string.hongguo_fn_sub_last, it.title)
            } ?: getString(R.string.hongguo_empty),
            emptyPlaceholder = false
        )
    }

    /** 未登录：云端类卡虚线占位「登录后展示」；本机类卡占位「仍可查看本机记录」（点击可用）。 */
    private fun bindCardsLoggedOut() {
        bindCard(
            binding.fnWatchLater, R.string.hongguo_watch_later, R.drawable.watch_later,
            count = null, records = emptyList(), subText = "", emptyPlaceholder = true
        )
        bindCard(
            binding.fnSubscribe, R.string.hongguo_my_subscribe, R.drawable.ic_hongguo_fn_bell,
            count = null, records = emptyList(), subText = "", emptyPlaceholder = true
        )
        bindCard(
            binding.fnFavorite, R.string.hongguo_tab_favorite, R.drawable.ic_hongguo_fn_heart,
            count = null, records = emptyList(), subText = "", emptyPlaceholder = true,
            placeholderTextRes = R.string.hongguo_fn_empty_local
        )
        bindCard(
            binding.fnHistory, R.string.hongguo_tab_history, R.drawable.ic_history,
            count = null, records = emptyList(), subText = "", emptyPlaceholder = true,
            placeholderTextRes = R.string.hongguo_fn_empty_local
        )
    }

    /** 我的预约卡（登录态，云端异步拉取）。 */
    private fun bindSubscribeCard() {
        val card = binding.fnSubscribe
        card.textFnTitle.setText(R.string.hongguo_my_subscribe)
        card.iconFn.setImageResource(R.drawable.ic_hongguo_fn_bell)
        card.textFnCount.text = subscribeItems.size.toString()
        val first = subscribeItems.firstOrNull()
        card.textFnSub.text = when {
            first == null -> getString(R.string.hongguo_empty)
            first.isOnline -> getString(R.string.hongguo_fn_sub_online_done, first.title)
            else -> getString(R.string.hongguo_fn_sub_online_wait, first.title)
        }
        fillCovers(card, subscribeItems.map { MiniItem(it.seriesId, it.cover) })
    }

    private fun fetchSubscribe() {
        lifecycleScope.launch {
            subscribeItems = try {
                withContext(Dispatchers.IO) { repository.subscribeList(onlyOnline = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                emptyList()
            }
            if (_binding == null || !loggedIn) return@launch
            bindSubscribeCard()
        }
    }

    private fun bindCard(
        card: CellHongguoFnCardBinding,
        titleRes: Int,
        iconRes: Int,
        count: Int?,
        records: List<HongguoRepository.WatchRecord>,
        subText: String,
        emptyPlaceholder: Boolean,
        placeholderTextRes: Int = R.string.hongguo_fn_empty_login
    ) {
        card.textFnTitle.setText(titleRes)
        card.iconFn.setImageResource(iconRes)
        card.iconFn.imageTintList = null
        if (count != null) {
            card.textFnCount.text = count.toString()
            card.textFnCount.isVisible = true
        } else {
            card.textFnCount.isVisible = false
        }
        card.textFnEmptyLogin.setText(placeholderTextRes)
        card.textFnEmptyLogin.isVisible = emptyPlaceholder
        card.rowFnCovers.isVisible = !emptyPlaceholder
        card.textFnSub.isVisible = !emptyPlaceholder
        if (emptyPlaceholder) return
        card.textFnSub.text = subText
        fillCovers(card, records.map { MiniItem(it.seriesId, it.cover) })
    }

    /** 最近 3 张封面 + 「+N」溢出位；点小海报直达详情弹窗。 */
    private fun fillCovers(card: CellHongguoFnCardBinding, items: List<MiniItem>) {
        val views = listOf(card.coverFn0, card.coverFn1, card.coverFn2)
        views.forEachIndexed { index, imageView ->
            val item = items.getOrNull(index)
            if (item == null || item.cover.isBlank()) {
                imageView.setImageResource(R.drawable.default_video)
                imageView.setOnClickListener(null)
                return@forEachIndexed
            }
            ImageLoader.loadSeriesCover(
                imageView = imageView,
                url = item.cover,
                placeholder = R.drawable.default_video,
                error = R.drawable.default_video
            )
            imageView.setOnClickListener {
                HongguoDetailDialog.show(requireContext(), repository, item.seriesId)
            }
        }
        val more = items.size - views.size
        card.textFnMore.text = if (more > 0) "+$more" else ""
    }

    /** 功能卡 mini 封面条目（seriesId 供点海报直达详情）。 */
    private data class MiniItem(val seriesId: String, val cover: String)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(): HongguoMinePage = HongguoMinePage()
    }
}
