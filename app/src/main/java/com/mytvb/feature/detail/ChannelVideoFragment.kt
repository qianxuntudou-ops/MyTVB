package com.mytvb.feature.detail

import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.mytvb.R
import com.mytvb.core.common.content.ContentFilter
import com.mytvb.core.navigation.VideoRouteNavigator
import com.mytvb.core.ui.base.BaseListFragment
import com.mytvb.core.ui.base.adaptiveSpanCount
import com.mytvb.core.ui.decoration.GridSpacingItemDecoration
import com.mytvb.model.video.VideoModel
import com.mytvb.repository.VideoRepository
import com.mytvb.ui.adapter.VideoAdapter
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ChannelVideoFragment : BaseListFragment<VideoModel>(), com.mytvb.ui.activity.MainActivity.OnVideoBlockedListener {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TAG_ID = "tagId"

        fun newInstance(title: String, tagId: Long): ChannelVideoFragment {
            return ChannelVideoFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_TAG_ID to tagId
                )
            }
        }
    }

    private var title: String = ""
    private var tagId: Long = 0L
    private var offset: String = ""
    private val videoRepository: VideoRepository by inject()

    override val autoLoad: Boolean = false
    override val enableTvListFocusController: Boolean = true
    override val deferSwipeRefreshUntilFirstDraw: Boolean = true

    override fun getSpanCount(): Int = resources.adaptiveSpanCount()

    override fun initArguments() {
        title = arguments?.getString(ARG_TITLE).orEmpty()
        tagId = arguments?.getLong(ARG_TAG_ID) ?: 0L
    }

    override fun isTopBarVisible(): Boolean = true

    override fun createAdapter(): VideoAdapter {
        return VideoAdapter(
            onItemClick = ::onVideoClick,
            onItemFocusedWithView = { view, position ->
                tvFocusController?.onItemFocused(view, position)
                // 焦点导航下 RV 常不滚动（dy=0），onScrolled 里的 checkLoadMore 收不到事件，
                // 必须在焦点落定时补查，否则要等焦点顶死最后一行才触发加载（体感"触底加载慢"）
                checkLoadMore()
            },
            onItemDpad = { view, keyCode, event ->
                tvFocusController?.handleKey(view, keyCode, event) == true
            }
        )
    }

    override fun initView() {
        super.initView()
        setMainTitle(title)
        recyclerView?.addItemDecoration(
            GridSpacingItemDecoration(getSpanCount(), resources.getDimensionPixelSize(R.dimen.px20), true)
        )
    }

    override fun initData() {
        showLoading(true)
        loadData(1)
    }

    override fun loadData(page: Int) {
        if (isLoading || tagId <= 0) return
        isLoading = true
        lifecycleScope.launch {
            videoRepository.getChannelVideos(
                channelId = tagId,
                offset = offset,
                pageSize = 30
            ).onSuccess { result ->
                isLoading = false
                hasMore = result.hasMore
                offset = result.offset
                adapter?.setShowLoadMore(hasMore)
                val hasExistingItems = (adapter?.contentCount() ?: 0) > 0
                val videos = ContentFilter.filterVideos(requireContext(), result.videos)
                if (videos.isEmpty() && !hasExistingItems) {
                    showLoading(false)
                    showEmpty()
                } else {
                    showLoading(false)
                    showContent()
                    if (!hasExistingItems) {
                        setAdapterData(videos)
                    } else {
                        (adapter as? VideoAdapter)?.addData(videos)
                    }
                    // 落地即续拉：焦点仍贴着列表尾部时立刻预取下一页，
                    // 消除"按到底→等网络→再按一次"的空窗（与首页推荐同款修法）
                    checkLoadMore()
                }
            }.onFailure {
                isLoading = false
                showLoading(false)
                if ((adapter?.contentCount() ?: 0) == 0) {
                    showError(it.message)
                }
            }
        }
    }

    override fun onRetryClick() {
        offset = ""
        showLoading(true)
        loadData(1)
    }

    override fun refresh() {
        offset = ""
        super.refresh()
    }

    private fun onVideoClick(video: VideoModel) {
        VideoRouteNavigator.openVideo(
            context = requireContext(),
            video = video,
            playQueue = com.mytvb.ui.activity.PlayerActivity.buildPlayQueue(
                (adapter as? VideoAdapter)?.getItemsSnapshot().orEmpty(),
                video
            )
        )
    }

    override fun onVideoBlocked(aid: Long, bvid: String) {
        (adapter as? VideoAdapter)?.removeByVideoId(aid, bvid)
    }
}
