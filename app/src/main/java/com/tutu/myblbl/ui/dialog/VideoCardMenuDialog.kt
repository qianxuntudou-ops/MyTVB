@file:Suppress("SpellCheckingInspection")

package com.tutu.myblbl.ui.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.ui.decoration.LinearSpacingItemDecoration
import com.tutu.myblbl.databinding.DialogVideoCardMenuBinding
import com.tutu.myblbl.event.AppEventHub
import com.tutu.myblbl.model.favorite.FavoriteFolderModel
import com.tutu.myblbl.model.video.VideoModel
import com.tutu.myblbl.network.session.NetworkSessionGateway
import com.tutu.myblbl.repository.FavoriteRepository
import com.tutu.myblbl.repository.VideoRepository
import com.tutu.myblbl.core.navigation.VideoRouteNavigator
import com.tutu.myblbl.ui.adapter.FavoriteFolderDialogAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class VideoCardMenuDialog(
    context: Context,
    private val video: VideoModel,
    private val onDislikeVideo: (() -> Unit)? = null,
    private val onDislikeUp: ((String) -> Unit)? = null,
    private val onFavoriteRemoved: (() -> Unit)? = null,
    private val onHistoryRecordDeleted: (() -> Unit)? = null
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    companion object {
        private const val TAG = "VideoCardMenuDialog"
        private const val REASON_ID_NOT_INTERESTED = 1
        private const val REASON_ID_DISLIKE_UP = 4
        private const val MAX_TITLE_CHARS = 30
        private const val TITLE_ELLIPSIS = "···"
    }

    private val binding = DialogVideoCardMenuBinding.inflate(LayoutInflater.from(context))
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val sessionGateway: NetworkSessionGateway by inject()
    private val appEventHub: AppEventHub by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isInWatchLater = false
    private var isFavorited = false
    private var isActionInProgress = false
    private val isLiveFeedbackCard = video.goto.equals("live", ignoreCase = true) ||
        video.roomId > 0L ||
        video.isLive ||
        video.historyBusiness == "live"
    private val supportsWatchLater = !isLiveFeedbackCard &&
        (video.aid > 0L || video.bvid.isNotBlank())
    private val supportsHistoryRecordDelete = onHistoryRecordDeleted != null &&
        video.historyRecordKid.isNotBlank()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        val screenWidth = context.resources.displayMetrics.widthPixels
        window?.setLayout((screenWidth * 0.45).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        setCanceledOnTouchOutside(true)
        configureContent()
        initListeners()
        refreshWatchLaterState()
        refreshFavoriteState()
        setOnShowListener {
            binding.root.post {
                if (supportsHistoryRecordDelete) {
                    binding.buttonDeleteHistoryRecord.requestFocus()
                } else if (supportsWatchLater) {
                    binding.buttonWatchLater.requestFocus()
                } else {
                    binding.buttonUpSpace.requestFocus()
                }
            }
        }
    }

    private fun initListeners() {
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

        binding.buttonWatchLater.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            if (isInWatchLater) {
                removeWatchLater()
            } else {
                addWatchLater()
            }
        }

        binding.buttonDeleteHistoryRecord.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            if (!supportsHistoryRecordDelete) return@setOnClickListener
            setActionInProgress(true)
            deleteHistoryRecord()
        }

        binding.buttonUpSpace.setOnClickListener {
            val owner = video.owner
            if (owner == null || owner.mid <= 0L) {
                toast("UP主信息未加载完成")
                return@setOnClickListener
            }
            dismiss()
            OwnerDetailDialog(
                context = context,
                owner = owner,
                onOpenSpace = { _ -> },
                onPlayVideo = { v, playQueue ->
                    VideoRouteNavigator.openVideo(
                        context = context,
                        video = v,
                        playQueue = playQueue
                    )
                },
                currentAid = video.aid,
                currentVideoId = video.bvid
            ).show()
        }

        binding.buttonFavorite.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            toggleFavorite()
        }

        binding.buttonFavorite.setOnLongClickListener {
            if (!checkCsrfAndLogin()) return@setOnLongClickListener true
            showFavoriteFolderDialog()
            true
        }

        binding.buttonDislikeVideo.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            setActionInProgress(true)
            dislikeVideo(REASON_ID_NOT_INTERESTED)
        }

        binding.buttonDislikeUp.setOnClickListener {
            if (isActionInProgress) return@setOnClickListener
            if (!checkCsrfAndLogin()) return@setOnClickListener
            if (!canDislikeUp()) return@setOnClickListener
            setActionInProgress(true)
            dislikeVideo(REASON_ID_DISLIKE_UP)
        }
    }

    private fun dislikeVideo(reasonId: Int) {
        scope.launch {
            runCatching {
                videoRepository.dislikeFeed(video, reasonId)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    if (reasonId == REASON_ID_DISLIKE_UP) {
                        toast(context.getString(R.string.toast_dislike_up_success))
                        val upName = video.authorName.trim()
                        if (upName.isNotBlank()) {
                            onDislikeUp?.invoke(upName)
                        }
                    } else {
                        toast(
                            context.getString(
                                if (supportsWatchLater) {
                                    R.string.toast_dislike_video_success
                                } else if (isLiveFeedbackCard) {
                                    R.string.toast_dislike_live_success
                                } else {
                                    R.string.toast_dislike_video_success
                                }
                            )
                        )
                        onDislikeVideo?.invoke()
                    }
                    dismiss()
                } else {
                    toast(
                        context.getString(
                            if (reasonId == REASON_ID_DISLIKE_UP) {
                                R.string.toast_dislike_up_failed
                            } else if (isLiveFeedbackCard) {
                                R.string.toast_dislike_live_failed
                            } else if (supportsWatchLater) {
                                R.string.toast_dislike_video_failed
                            } else {
                                R.string.toast_dislike_video_failed
                            }
                        )
                    )
                    setActionInProgress(false)
                }
            }.onFailure {
                AppLog.e(
                    TAG,
                    "dislikeVideo failure: reasonId=$reasonId, aid=${video.aid}, bvid=${video.bvid}, ownerMid=${video.owner?.mid ?: 0L}, message=${it.message}",
                    it
                )
                toast(
                    context.getString(
                        if (reasonId == REASON_ID_DISLIKE_UP) {
                            R.string.toast_dislike_up_failed
                        } else if (isLiveFeedbackCard) {
                            R.string.toast_dislike_live_failed
                        } else if (supportsWatchLater) {
                            R.string.toast_dislike_video_failed
                        } else {
                            R.string.toast_dislike_video_failed
                        }
                    )
                )
                setActionInProgress(false)
            }
        }
    }

    private fun deleteHistoryRecord() {
        val kid = video.historyRecordKid
        if (kid.isBlank()) {
            toast(context.getString(R.string.toast_delete_history_record_failed))
            setActionInProgress(false)
            return
        }
        scope.launch {
            runCatching {
                videoRepository.deleteHistoryRecord(kid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    toast(context.getString(R.string.toast_delete_history_record_success))
                    onHistoryRecordDeleted?.invoke()
                    dismiss()
                } else {
                    handleActionError(response.code, response.errorMessage)
                    setActionInProgress(false)
                }
            }.onFailure {
                AppLog.e(
                    TAG,
                    "deleteHistoryRecord failure: kid=$kid, aid=${video.aid}, bvid=${video.bvid}, message=${it.message}",
                    it
                )
                toast(context.getString(R.string.toast_delete_history_record_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun addWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.addWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = true
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_add_watch_later_success))
                    dismiss()
                } else {
                    val msg = response.errorMessage
                    when (val error = sessionGateway.classifyActionError(response.code, msg)) {
                        is NetworkSessionGateway.ActionError.SessionExpired -> handleAuthExpired()
                        is NetworkSessionGateway.ActionError.CsrfMismatch -> toast(context.getString(R.string.toast_add_watch_later_failed))
                        is NetworkSessionGateway.ActionError.RiskControl -> toast(context.getString(R.string.toast_add_watch_later_failed))
                        is NetworkSessionGateway.ActionError.FrequencyLimit -> toast(error.message)
                        is NetworkSessionGateway.ActionError.Other -> {
                            if (msg.contains("90001") || msg.contains("上限") || msg.contains("已满")) {
                                toast(context.getString(R.string.toast_watch_later_full))
                            } else {
                                toast(context.getString(R.string.toast_add_watch_later_failed))
                            }
                        }
                        is NetworkSessionGateway.ActionError.CsrfMissing -> handleAuthExpired()
                    }
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_add_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun removeWatchLater() {
        scope.launch {
            runCatching {
                videoRepository.removeWatchLater(video.aid, video.bvid)
            }.onSuccess { response ->
                if (response.isSuccess) {
                    isInWatchLater = false
                    renderWatchLaterState()
                    toast(context.getString(R.string.toast_remove_watch_later_success))
                    appEventHub.dispatch(
                        AppEventHub.Event.WatchLaterVideoRemoved(
                            aid = video.aid,
                            bvid = video.bvid
                        )
                    )
                    dismiss()
                } else {
                    toast(context.getString(R.string.toast_remove_watch_later_failed))
                }
                setActionInProgress(false)
            }.onFailure {
                toast(context.getString(R.string.toast_remove_watch_later_failed))
                setActionInProgress(false)
            }
        }
    }

    private fun refreshWatchLaterState() {
        if (!supportsWatchLater) return
        if (!sessionGateway.isLoggedIn()) return
        scope.launch {
            runCatching {
                videoRepository.checkWatchLater(video.aid, video.bvid)
            }.onSuccess { inList ->
                isInWatchLater = inList
                renderWatchLaterState()
            }
        }
    }

    private fun toggleFavorite() {
        scope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
                ?: video.owner?.mid?.takeIf { it > 0L }
                ?: 0L
            if (currentUserMid <= 0L) {
                toast("收藏夹信息未加载完成")
                setActionInProgress(false)
                return@launch
            }
            val folderResult = favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
            val folders = folderResult.getOrNull()?.data?.list.orEmpty()
            if (folders.isEmpty()) {
                toast("暂无可用收藏夹")
                setActionInProgress(false)
                return@launch
            }
            val actualIsFavorited = folders.any { it.favState == 1 }
            val targetFolder = if (actualIsFavorited) {
                folders.firstOrNull { it.favState == 1 }
            } else {
                folders.firstOrNull()
            }
            if (targetFolder == null) {
                toast("暂无可用收藏夹")
                setActionInProgress(false)
                return@launch
            }
            val folderId = targetFolder.id.toString()
            val result = if (actualIsFavorited) {
                favoriteRepository.removeFavorite(video.aid, folderId)
            } else {
                favoriteRepository.addFavorite(video.aid, folderId)
            }
            result.onSuccess { response ->
                if (response.isSuccess) {
                    isFavorited = !actualIsFavorited
                    renderFavoriteState()
                    toast(
                        if (isFavorited) context.getString(R.string.collection_)
                        else context.getString(R.string.collection)
                    )
                    if (!isFavorited) {
                        onFavoriteRemoved?.invoke()
                    }
                    dismiss()
                } else {
                    handleActionError(response.code, response.errorMessage)
                }
                setActionInProgress(false)
            }.onFailure {
                toast(it.message ?: "操作失败")
                setActionInProgress(false)
            }
        }
    }

    private fun showFavoriteFolderDialog() {
        val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L }
            ?: video.owner?.mid?.takeIf { it > 0L }
            ?: 0L
        if (currentUserMid <= 0L) {
            toast("收藏夹信息未加载完成")
            return
        }
        scope.launch {
            favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
                .onSuccess { response ->
                    if (!response.isSuccess) {
                        toast(response.errorMessage)
                        return@onSuccess
                    }
                    val folders = response.data?.list.orEmpty()
                    if (folders.isEmpty()) {
                        toast("暂无可用收藏夹")
                        return@onSuccess
                    }
                    val actualIsFavorited = folders.any { it.favState == 1 }
                    if (actualIsFavorited != isFavorited) {
                        isFavorited = actualIsFavorited
                        renderFavoriteState()
                    }
                    displayFavoriteFolderChooser(folders)
                }
                .onFailure { toast(it.message ?: "加载收藏夹失败") }
        }
    }

    private fun displayFavoriteFolderChooser(folders: List<FavoriteFolderModel>) {
        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setContentView(R.layout.dialog_favorite_folder)
        dialog.setCanceledOnTouchOutside(true)
        dialog.findViewById<View>(R.id.dialog_root)?.setOnClickListener { dialog.dismiss() }

        val titleView = dialog.findViewById<android.widget.TextView>(R.id.top_title)
        val recyclerView = dialog.findViewById<RecyclerView>(R.id.recyclerView)
        titleView?.text = if (isFavorited) context.getString(R.string.collection_)
        else context.getString(R.string.collection)

        val adapter = FavoriteFolderDialogAdapter(folders) { position ->
            val folder = folders.getOrNull(position) ?: return@FavoriteFolderDialogAdapter
            dialog.dismiss()
            scope.launch {
                val result = if (isFavorited) {
                    favoriteRepository.removeFavorite(video.aid, folder.id.toString())
                } else {
                    favoriteRepository.addFavorite(video.aid, folder.id.toString())
                }
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        val wasFavorited = isFavorited
                        isFavorited = !isFavorited
                        renderFavoriteState()
                        toast(
                            if (isFavorited) {
                                "已收藏到 ${folder.title}"
                            } else {
                                "已从 ${folder.title} 取消收藏"
                            }
                        )
                        if (wasFavorited && !isFavorited) {
                            onFavoriteRemoved?.invoke()
                        }
                    } else {
                        handleActionError(response.code, response.errorMessage)
                    }
                }.onFailure { toast(it.message ?: "操作失败") }
            }
        }

        recyclerView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        recyclerView?.adapter = adapter
        if (recyclerView != null && recyclerView.itemDecorationCount == 0) {
            recyclerView.addItemDecoration(
                LinearSpacingItemDecoration(
                    context.resources.getDimensionPixelSize(R.dimen.px2),
                    includeBottom = true
                )
            )
        }

        dialog.setOnShowListener {
            recyclerView?.post {
                adapter.requestInitialFocus(recyclerView)
            }
        }
        dialog.show()
    }

    private fun refreshFavoriteState() {
        if (!sessionGateway.isLoggedIn()) return
        scope.launch {
            val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: return@launch
            favoriteRepository.getFavoriteFolders(currentUserMid, rid = video.aid)
                .onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = response.data?.list.orEmpty().any { it.favState == 1 }
                        renderFavoriteState()
                    }
                }
        }
    }

    private fun renderFavoriteState() {
        if (isFavorited) {
            binding.textFavoriteTitle.text = context.getString(R.string.menu_already_favorited)
            binding.textFavoriteTitle.setTextColor(
                ContextCompat.getColor(context, R.color.pink)
            )
        } else {
            binding.textFavoriteTitle.text = context.getString(R.string.menu_favorite)
            binding.textFavoriteTitle.setTextColor(
                ContextCompat.getColor(context, R.color.textColor)
            )
        }
    }

    private fun renderWatchLaterState() {
        if (isInWatchLater) {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_already_in_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                ContextCompat.getColor(context, R.color.pink)
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_added_summary)
        } else {
            binding.textWatchLaterTitle.text = context.getString(R.string.menu_add_watch_later)
            binding.textWatchLaterTitle.setTextColor(
                ContextCompat.getColor(context, R.color.textColor)
            )
            binding.textWatchLaterSummary.text = context.getString(R.string.menu_watch_later_summary)
        }
    }

    private fun configureContent() {
        binding.textTitle.text = formatDialogTitle(
            video.title.ifBlank { context.getString(R.string.video) }
        )
        binding.buttonDeleteHistoryRecord.visibility =
            if (supportsHistoryRecordDelete) View.VISIBLE else View.GONE
        binding.buttonWatchLater.visibility = if (supportsWatchLater) View.VISIBLE else View.GONE
        binding.buttonDeleteHistoryRecord.nextFocusDownId = when {
            supportsWatchLater -> R.id.button_watch_later
            else -> R.id.button_up_space
        }
        binding.buttonWatchLater.nextFocusUpId =
            if (supportsHistoryRecordDelete) R.id.button_delete_history_record else View.NO_ID
        binding.buttonUpSpace.nextFocusUpId = when {
            supportsWatchLater -> R.id.button_watch_later
            supportsHistoryRecordDelete -> R.id.button_delete_history_record
            else -> View.NO_ID
        }
        binding.textFavoriteSummary.text = context.getString(R.string.menu_favorite_summary)
        binding.textSubtitle.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_live_card_subtitle
            } else {
                R.string.menu_video_card_subtitle
            }
        )
        binding.textDislikeVideoTitle.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_dislike_live
            } else {
                R.string.menu_dislike_video
            }
        )
        binding.textDislikeVideoSummary.text = context.getString(
            if (isLiveFeedbackCard) {
                R.string.menu_dislike_live_summary
            } else {
                R.string.menu_dislike_video_summary
            }
        )
        renderWatchLaterState()
        renderFavoriteState()
        renderDislikeUpState()
    }

    private fun renderDislikeUpState() {
        val upName = video.authorName.trim()
        val hasUpName = upName.isNotBlank()
        binding.textDislikeUpTitle.text = if (hasUpName) {
            context.getString(R.string.menu_dislike_up_title_with_name, upName)
        } else {
            context.getString(R.string.menu_dislike_up)
        }
        binding.textDislikeUpSummary.text = if (hasUpName) {
            context.getString(
                if (isLiveFeedbackCard) {
                    R.string.menu_dislike_live_up_summary
                } else {
                    R.string.menu_dislike_up_summary
                }
            )
        } else {
            context.getString(R.string.menu_dislike_up_unknown_summary)
        }
        val enabled = canDislikeUp() && !isActionInProgress
        binding.buttonDislikeUp.isEnabled = enabled
        binding.buttonDislikeUp.isClickable = enabled
        binding.buttonDislikeUp.isFocusable = enabled
        binding.buttonDislikeUp.alpha = if (canDislikeUp()) 1f else 0.45f
    }

    private fun setActionInProgress(inProgress: Boolean) {
        isActionInProgress = inProgress
        val watchLaterEnabled = supportsWatchLater && !inProgress
        val favoriteEnabled = !inProgress
        val dislikeVideoEnabled = !inProgress
        val deleteHistoryEnabled = supportsHistoryRecordDelete && !inProgress
        binding.buttonDeleteHistoryRecord.isEnabled = deleteHistoryEnabled
        binding.buttonDeleteHistoryRecord.isClickable = deleteHistoryEnabled
        binding.buttonWatchLater.isEnabled = watchLaterEnabled
        binding.buttonWatchLater.isClickable = watchLaterEnabled
        binding.buttonFavorite.isEnabled = favoriteEnabled
        binding.buttonFavorite.isClickable = favoriteEnabled
        binding.buttonDislikeVideo.isEnabled = dislikeVideoEnabled
        binding.buttonDislikeVideo.isClickable = dislikeVideoEnabled
        binding.buttonWatchLater.alpha = if (watchLaterEnabled) 1f else 0.6f
        binding.buttonDeleteHistoryRecord.alpha = if (deleteHistoryEnabled) 1f else 0.6f
        binding.buttonFavorite.alpha = if (favoriteEnabled) 1f else 0.6f
        binding.buttonDislikeVideo.alpha = if (dislikeVideoEnabled) 1f else 0.6f
        renderDislikeUpState()
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            toast(context.getString(R.string.toast_need_login))
            return false
        }
        return true
    }

    private fun checkCsrfAndLogin(): Boolean {
        if (!checkLogin()) return false
        if (sessionGateway.requireCsrfToken() == null) {
            toast("登录凭据异常，请稍后重试")
            return false
        }
        return true
    }

    private fun handleActionError(code: Int, message: String?) {
        when (val error = sessionGateway.classifyActionError(code, message)) {
            is NetworkSessionGateway.ActionError.SessionExpired -> handleAuthExpired()
            is NetworkSessionGateway.ActionError.CsrfMismatch -> toast("操作失败，请稍后重试")
            is NetworkSessionGateway.ActionError.RiskControl -> toast("账号被风控了，请到B站官方App或网页端完成验证后再试")
            is NetworkSessionGateway.ActionError.FrequencyLimit -> toast(error.message)
            is NetworkSessionGateway.ActionError.Other -> toast(error.message)
            is NetworkSessionGateway.ActionError.CsrfMissing -> toast("登录凭据异常，请稍后重试")
        }
    }

    private fun handleAuthExpired() {
        toast(context.getString(R.string.login_expired))
        dismiss()
    }

    private fun canDislikeUp(): Boolean {
        return video.owner?.mid?.let { it > 0L } == true
    }

    private fun formatDialogTitle(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.length <= MAX_TITLE_CHARS) {
            trimmed
        } else {
            trimmed.take(MAX_TITLE_CHARS - TITLE_ELLIPSIS.length) + TITLE_ELLIPSIS
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

}
