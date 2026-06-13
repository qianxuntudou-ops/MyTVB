package com.tutu.myblbl.feature.cctv

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.tutu.myblbl.R
import com.tutu.myblbl.core.ui.base.BaseFragment
import com.tutu.myblbl.core.ui.decoration.GridSpacingItemDecoration
import com.tutu.myblbl.core.ui.focus.tv.GridTvFocusStrategy
import com.tutu.myblbl.core.ui.focus.tv.TvListFocusController
import com.tutu.myblbl.core.ui.layout.WrapContentGridLayoutManager
import com.tutu.myblbl.databinding.FragmentCctvLiveBinding
import com.tutu.myblbl.ui.activity.CctvPlayerActivity
import com.tutu.myblbl.ui.fragment.main.MainTabFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject

class CctvLiveFragment : BaseFragment<FragmentCctvLiveBinding>(), MainTabFocusTarget {

    private val okHttpClient: OkHttpClient by inject()
    private lateinit var adapter: CctvChannelAdapter
    private val programRepository by lazy { CctvProgramRepository(okHttpClient) }
    private var tvFocusController: TvListFocusController? = null
    // 从 savedInstanceState 读到、待布局后恢复焦点的频道 id
    private var savedFocusChannelId: String? = null
    // 最近一次聚焦的频道 id，供 onSaveInstanceState 跨销毁重建持久化
    private var lastFocusedChannelId: String? = null
    // 诊断用：窗口级焦点变化监听，记录焦点去向
    private var focusTraceListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedFocusChannelId = savedInstanceState?.getString(KEY_FOCUS_CHANNEL_ID)
    }

    override fun getViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentCctvLiveBinding {
        return FragmentCctvLiveBinding.inflate(inflater, container, false)
    }

    override fun useLightBaseContainer(): Boolean = true

    override fun initView() {
        adapter = CctvChannelAdapter(
            onItemClick = ::openChannel,
            onTopEdgeUp = { false }
        )
        binding.recyclerView.layoutManager = WrapContentGridLayoutManager(requireContext(), SPAN_COUNT)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.addItemDecoration(
            GridSpacingItemDecoration(
                SPAN_COUNT,
                resources.getDimensionPixelSize(R.dimen.px20),
                true
            )
        )
        installTvFocusController()
    }

    private fun installTvFocusController() {
        tvFocusController = TvListFocusController(
            recyclerView = binding.recyclerView,
            adapter = adapter,
            strategy = GridTvFocusStrategy { SPAN_COUNT },
            canLoadMore = { false },
            loadMore = {},
            debugName = "cctv"
        )
    }

    override fun initData() {
        CctvWebViewPrewarmer.prewarm(requireContext())
        loadNowPrograms()
        binding.recyclerView.post {
            // 仅在被系统回收重建时恢复到之前聚焦的频道。
            // 首次点击进入不抢焦点，让焦点留在 CCTV 按钮上，与侧边栏整体行为一致；
            // 用户按右键导航进入内容区时，由 onTabNavigateRight 触发 focusEntryFromMainTab。
            val restoredId = savedFocusChannelId
            savedFocusChannelId = null
            if (restoreFocusForChannel(restoredId)) {
                Log.d(TAG, "initData restoreFocus id=$restoredId")
            }
        }
    }

    override fun onStop() {
        CctvWebViewPrewarmer.cancelIfPending()
        super.onStop()
    }

    override fun onPause() {
        // 短时返回靠控制器内存锚点恢复；这里同步记下频道 id，用于长时被系统回收后
        // 通过 saved state 跨销毁重建恢复焦点。
        detachFocusTrace()
        tvFocusController?.captureCurrentAnchor()
        lastFocusedChannelId = currentFocusedChannelId() ?: lastFocusedChannelId
        Log.d(TAG, "onPause focusChannelId=$lastFocusedChannelId focus=${describeFocus()}")
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val id = lastFocusedChannelId ?: currentFocusedChannelId()
        if (!id.isNullOrBlank()) {
            outState.putString(KEY_FOCUS_CHANNEL_ID, id)
            Log.d(TAG, "onSaveInstanceState focusId=$id")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(
            TAG,
            "onResume enter savedFocusChannelId=$savedFocusChannelId lastFocusedChannelId=$lastFocusedChannelId focus=${describeFocus()}"
        )
        attachFocusTrace()
        // 重建后首次恢复：savedFocusChannelId 尚未消费，交给 initData 的 post 按频道 id
        // 精准恢复，这里不抢先落焦点。
        if (savedFocusChannelId != null) {
            return
        }
        // 非重建场景（短时返回）：用控制器内存里的锚点还原进入播放器前的频道卡片。
        val restored = tvFocusController?.restoreCapturedAnchor() == true
        if (!restored) {
            tvFocusController?.ensureValidFocus("resume", allowWhenFocusOutside = true)
        }
        Log.d(TAG, "onResume after restore restored=$restored focus=${describeFocus()}")
        binding.recyclerView.postDelayed({
            if (!isAdded) return@postDelayed
            Log.d(TAG, "onResume settled@500ms focus=${describeFocus()}")
        }, 500)
    }

    override fun onDestroyView() {
        detachFocusTrace()
        tvFocusController?.release()
        tvFocusController = null
        super.onDestroyView()
    }

    private fun loadNowPrograms() {
        viewLifecycleOwner.lifecycleScope.launch {
            val programs = withContext(Dispatchers.IO) {
                programRepository.fetchNowPrograms(CctvChannels.list())
            }
            if (programs.isNotEmpty()) {
                adapter.submitNowPrograms(programs)
            }
        }
    }

    override fun focusEntryFromMainTab(): Boolean {
        val layoutManager = binding.recyclerView.layoutManager ?: return false
        val view = layoutManager.findViewByPosition(0)
        if (view != null) {
            return view.requestFocus()
        }
        binding.recyclerView.scrollToPosition(0)
        binding.recyclerView.post {
            binding.recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
        return true
    }

    private fun currentFocusedChannelId(): String? {
        val rv = binding.recyclerView
        val focused = rv.findFocus() ?: return null
        val itemView = rv.findContainingItemView(focused) ?: return null
        val pos = rv.getChildAdapterPosition(itemView)
        if (pos == RecyclerView.NO_POSITION) return null
        return CctvChannels.list().getOrNull(pos)?.id
    }

    private fun restoreFocusForChannel(channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        val pos = CctvChannels.list().indexOfFirst { it.id == channelId }
        if (pos < 0) return false
        val controller = tvFocusController ?: return false
        // 走控制器的健壮聚焦路径（带滚动、重试与就近回退）。被系统回收重建时 RecyclerView 刚重建，
        // 目标 ViewHolder 的 attach 时机不确定，原先一次性 requestFocus 会静默失败导致焦点丢失。
        // allowOutsideFocus：重建后焦点可能暂时停在侧边栏，需允许从 RV 外部夺回焦点。
        return controller.requestFocusPosition(pos, allowOutsideFocus = true)
    }

    private fun describeView(view: View?): String {
        if (view == null) return "null"
        val rv = binding.recyclerView
        val itemView = rv.findContainingItemView(view)
        val pos = itemView?.let { rv.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        val idName = if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("id?")
        } else {
            "no-id"
        }
        return "${view.javaClass.simpleName}(id=$idName,inList=${itemView != null},pos=$pos)"
    }

    private fun describeFocus(): String = describeView(binding.recyclerView.rootView?.findFocus())

    private fun attachFocusTrace() {
        detachFocusTrace()
        val root = binding.root.rootView ?: return
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
            Log.d(TAG, "focusTrace old=${describeView(oldFocus)} new=${describeView(newFocus)}")
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        focusTraceListener = listener
        Log.d(TAG, "focusTrace attached")
    }

    private fun detachFocusTrace() {
        val listener = focusTraceListener ?: return
        focusTraceListener = null
        runCatching {
            val root = binding.root.rootView
            if (root != null && root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
            }
        }
    }

    private fun openChannel(position: Int) {
        val intent = Intent(requireContext(), CctvPlayerActivity::class.java)
            .putExtra(CctvPlayerActivity.EXTRA_CHANNEL_INDEX, position)
        startActivity(intent)
    }

    companion object {
        private const val TAG = "CctvLiveFragment"
        private const val SPAN_COUNT = 4
        private const val KEY_FOCUS_CHANNEL_ID = "focus_channel_id"

        fun newInstance(): CctvLiveFragment = CctvLiveFragment()
    }
}
