package com.mytvb.ui.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Toast
import com.mytvb.core.ui.base.AppToast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mytvb.R
import com.mytvb.core.ui.decoration.LinearSpacingItemDecoration
import com.mytvb.databinding.DialogActionBinding
import com.mytvb.model.favorite.FavoriteFolderModel
import com.mytvb.network.session.ActionError
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.FavoriteRepository
import com.mytvb.repository.VideoRepository
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.ui.adapter.FavoriteFolderDialogAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.mytvb.core.ui.base.DialogWindowFit

class PlayerActionDialog(
    context: Context,
    private val aid: Long,
    private val bvid: String,
    private val ownerMid: Long = 0L
) : AppCompatDialog(context, R.style.DialogTheme), KoinComponent {

    private val binding = DialogActionBinding.inflate(LayoutInflater.from(context))
    private val appSettings: AppSettingsDataStore by inject()
    private val videoRepository: VideoRepository by inject()
    private val favoriteRepository: FavoriteRepository by inject()
    private val sessionGateway: SessionStateRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val safeBvid: String? get() = bvid.takeIf { it.isNotBlank() }

    private var isLiked = false
    private var isFavorited = false
    private var isCoined = false
    private var isInWatchLater = false
    private var selectedCoinMultiply = loadCoinMultiply()

    init {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
        DialogWindowFit.overlay(
            this,
            DialogWindowFit.wrapContentInScroll(context, binding.root),
            context.resources.getDimensionPixelSize(R.dimen.px1000),
            tag = "playerAction"
        )
        // 点击关闭只由 DialogWindowFit 容器的暗区监听承担（点卡片外关闭），内容根不挂 dismiss
        initListeners()
        renderState()
        setOnShowListener {
            binding.buttonLike.requestFocus()
            refreshState()
        }
    }

    private fun initListeners() {
        binding.buttonLike.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                runCatching {
                    videoRepository.like(aid, safeBvid, if (isLiked) 2 else 1)
                }.onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = !isLiked
                        renderState()
                        AppToast.show(context,if (isLiked) context.getString(R.string.liked_) else context.getString(R.string.like))
                    } else {
                        AppLog.w("PlayerAction", "like failed: code=${response.code}, message=${response.message}")
                        handleActionError(response.code, response.message)
                    }
                }.onFailure {
                    AppLog.e("PlayerAction", "like failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
            }
        }

        binding.buttonCoin.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            if (isCoined) {
                toast(context.getString(R.string.give_coin_))
                return@setOnClickListener
            }
            scope.launch {
                runCatching {
                    // 投币同时点赞（issue#140）：与详情页同款开关，走接口原生 select_like
                    val alsoLike = !isLiked && appSettings.getCachedString("coin_also_like") != "关"
                    videoRepository.giveCoin(
                        aid, safeBvid,
                        multiply = selectedCoinMultiply,
                        selectLike = if (alsoLike) 1 else 0
                    )
                }.onSuccess { response ->
                    if (response.isSuccess) {
                        isCoined = true
                        renderState()
                        toast(context.getString(R.string.dialog_coin_success))
                    } else {
                        handleActionError(response.code, response.message)
                    }
                }.onFailure {
                    AppLog.e("PlayerAction", "coin failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
            }
        }
        binding.buttonCoin.setOnLongClickListener {
            if (!checkLogin()) return@setOnLongClickListener true
            if (isCoined) {
                toast(context.getString(R.string.give_coin_))
                return@setOnLongClickListener true
            }
            showCoinCountDialog()
            true
        }

        binding.buttonCollection.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: ownerMid
                if (currentUserMid <= 0L) {
                    toast(context.getString(R.string.dialog_favorite_folder_not_loaded))
                    return@launch
                }
                val folderResult = favoriteRepository.getFavoriteFolders(currentUserMid)
                val folders = folderResult.getOrNull()?.data?.list.orEmpty()
                val defaultFolder = folders.firstOrNull()
                if (defaultFolder == null) {
                    toast(context.getString(R.string.dialog_no_available_favorite_folder))
                    return@launch
                }
                val folderId = defaultFolder.id.toString()
                val result = if (isFavorited) {
                    favoriteRepository.removeFavorite(aid, folderId)
                } else {
                    favoriteRepository.addFavorite(aid, folderId)
                }
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = !isFavorited
                        renderState()
                        toast(
                        if (isFavorited) context.getString(R.string.collection_)
                        else context.getString(R.string.dialog_unfavorite)
                        )
                    } else {
                        handleActionError(response.code, response.errorMessage)
                    }
                }.onFailure { toast(it.message ?: context.getString(R.string.dialog_action_failed)) }
            }
        }
        binding.buttonCollection.setOnLongClickListener {
            if (!checkLogin()) return@setOnLongClickListener true
            showFavoriteFolderDialog()
            true
        }

        binding.buttonTriple.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                runCatching {
                    videoRepository.tripleAction(aid, safeBvid ?: bvid)
                }.onSuccess { response ->
                    if (response.isSuccess) {
                        isLiked = true
                        isCoined = true
                        isFavorited = true
                        renderState()
                        if (response.data?.isRisk == true) {
                            AppToast.show(context, R.string.dialog_triple_success_risk_marked, Toast.LENGTH_LONG)
                        } else {
                            toast(context.getString(R.string.triple_action))
                        }
                    } else {
                        handleActionError(response.code, response.message)
                    }
                }.onFailure {
                    AppLog.e("PlayerActionDialog", "tripleAction failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
            }
        }

        binding.buttonWatchLater.setOnClickListener {
            if (!checkLogin()) return@setOnClickListener
            scope.launch {
                runCatching {
                    if (isInWatchLater) {
                        videoRepository.removeWatchLater(aid, bvid)
                    } else {
                        videoRepository.addWatchLater(aid, bvid)
                    }
                }.onSuccess { response ->
                    if (response.isSuccess) {
                        isInWatchLater = !isInWatchLater
                        renderState()
                        toast(
                            if (isInWatchLater) context.getString(R.string.later_watch_added)
                            else context.getString(R.string.later_watch_removed)
                        )
                    } else {
                        handleActionError(response.code, response.errorMessage)
                    }
                }.onFailure {
                    AppLog.e("PlayerAction", "watchLater failed", it)
                    toast(it.message ?: context.getString(R.string.dialog_action_failed))
                }
            }
        }
    }

    private fun refreshState() {
        if (!sessionGateway.isLoggedIn()) {
            return
        }
        scope.launch {
            runCatching { videoRepository.getArchiveRelation(aid, safeBvid) }
                .onSuccess { response ->
                    if (response.isSuccess) {
                        val data = response.data
                        if (data != null) {
                            isLiked = data.like
                            isCoined = data.coin > 0
                            isFavorited = data.favorite
                            renderState()
                        }
                    }
                }
            runCatching { videoRepository.checkWatchLater(aid, bvid) }
                .onSuccess { isInList ->
                    isInWatchLater = isInList
                    renderState()
                }
        }
    }

    private fun renderState() {
        binding.iconLike.alpha = 1f
        binding.textLike.alpha = 1f
        binding.textLike.text = context.getString(if (isLiked) R.string.liked_ else R.string.like)
        binding.iconCoin.alpha = 1f
        binding.textCoin.alpha = 1f
        binding.textCoin.text = context.getString(if (isCoined) R.string.give_coin_ else R.string.give_coin)
        binding.iconCollection.alpha = 1f
        binding.textCollection.alpha = 1f
        binding.textCollection.text = context.getString(
            if (isFavorited) R.string.collection_ else R.string.collection
        )
        binding.iconWatchLater.alpha = 1f
        binding.textWatchLater.alpha = 1f
        binding.textWatchLater.text = context.getString(
            if (isInWatchLater) R.string.later_watch_ else R.string.later_watch
        )
        updateActionAppearance(
            enabled = isLiked,
            iconView = binding.iconLike,
            textView = binding.textLike
        )
        updateActionAppearance(
            enabled = isCoined,
            iconView = binding.iconCoin,
            textView = binding.textCoin
        )
        updateActionAppearance(
            enabled = isFavorited,
            iconView = binding.iconCollection,
            textView = binding.textCollection
        )
        updateActionAppearance(
            enabled = isInWatchLater,
            iconView = binding.iconWatchLater,
            textView = binding.textWatchLater
        )
        binding.buttonTriple.isVisible = true
        val allDone = isLiked && isCoined && isFavorited
        binding.buttonTriple.setTextColor(
            ContextCompat.getColor(
                context,
                if (allDone) R.color.pink else R.color.white
            )
        )
    }

    private fun updateActionAppearance(
        enabled: Boolean,
        iconView: androidx.appcompat.widget.AppCompatImageView,
        textView: androidx.appcompat.widget.AppCompatTextView
    ) {
        val color = ContextCompat.getColor(
            context,
            if (enabled) R.color.pink else R.color.textColor
        )
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(color)
        textView.setTextColor(color)
    }

    private fun showCoinCountDialog() {
        val options = context.resources.getStringArray(R.array.give_coin_number)
        if (options.isEmpty()) {
            return
        }
        var selectedIndex = (selectedCoinMultiply - 1).coerceIn(0, options.lastIndex)
        AlertDialog.Builder(context, R.style.DialogTheme)
            .setTitle(R.string.give_coin)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.confirm) { dialog, _ ->
                selectedCoinMultiply = (selectedIndex + 1).coerceAtLeast(1)
                persistCoinMultiply(selectedCoinMultiply)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFavoriteFolderDialog() {
        val currentUserMid = sessionGateway.getUserInfo()?.mid?.takeIf { it > 0L } ?: ownerMid
        if (currentUserMid <= 0L) {
            toast(context.getString(R.string.dialog_favorite_folder_not_loaded))
            return
        }
        scope.launch {
            favoriteRepository.getFavoriteFolders(currentUserMid)
                .onSuccess { response ->
                    if (!response.isSuccess) {
                        toast(response.errorMessage)
                        return@onSuccess
                    }
                    val folders = response.data?.list.orEmpty()
                    if (folders.isEmpty()) {
                        toast(context.getString(R.string.dialog_no_available_favorite_folder))
                        return@onSuccess
                    }
                    displayFavoriteFolderChooser(folders)
                }
                .onFailure { toast(it.message ?: context.getString(R.string.dialog_load_favorite_folder_failed)) }
        }
    }

    private fun displayFavoriteFolderChooser(folders: List<FavoriteFolderModel>) {
        val dialog = AppCompatDialog(context, R.style.DialogTheme)
        dialog.setCanceledOnTouchOutside(true)
        // 固定尺寸卡片（布局根 match_parent 依赖窗口定尺寸），fill 载体下显式传高
        DialogWindowFit.overlay(
            dialog,
            LayoutInflater.from(context).inflate(R.layout.dialog_favorite_folder, null),
            context.resources.getDimensionPixelSize(R.dimen.px800),
            heightPx = context.resources.getDimensionPixelSize(R.dimen.px615),
            tag = "favFolder"
        )
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
                    favoriteRepository.removeFavorite(aid, folder.id.toString())
                } else {
                    favoriteRepository.addFavorite(aid, folder.id.toString())
                }
                result.onSuccess { response ->
                    if (response.isSuccess) {
                        isFavorited = !isFavorited
                        renderState()
                        toast(
                            if (isFavorited) {
                                context.getString(R.string.dialog_favorited_to_format, folder.title)
                            } else {
                                context.getString(R.string.dialog_unfavorited_from_format, folder.title)
                            }
                        )
                    } else {
                        handleActionError(response.code, response.errorMessage)
                    }
                }.onFailure { toast(it.message ?: context.getString(R.string.dialog_action_failed)) }
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

    private fun loadCoinMultiply(): Int {
        return appSettings.getCachedString(KEY_GIVE_COIN_NUMBER_SETTINGS)?.toIntOrNull()?.coerceIn(1, 2) ?: 2
    }

    private fun persistCoinMultiply(value: Int) {
        val normalized = value.coerceAtLeast(1)
        appSettings.putStringAsync(KEY_GIVE_COIN_NUMBER_SETTINGS, normalized.toString())
    }

    private fun checkLogin(): Boolean {
        if (!sessionGateway.isLoggedIn()) {
            toast(context.getString(R.string.need_sign_in))
            return false
        }
        return true
    }

    private fun toast(message: String) {
        AppToast.show(context, message)
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (binding.buttonCoin.isFocused) {
                if (!checkLogin()) {
                    return true
                }
                if (isCoined) {
                    toast(context.getString(R.string.give_coin_))
                    return true
                }
                showCoinCountDialog()
                return true
            }
            if (binding.buttonCollection.isFocused) {
                if (!checkLogin()) {
                    return true
                }
                showFavoriteFolderDialog()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun handleActionError(code: Int, message: String?) {
        when (val error = sessionGateway.classifyActionError(code, message)) {
            is ActionError.SessionExpired -> {
                toast(context.getString(R.string.login_expired))
                dismiss()
            }
            is ActionError.CsrfMismatch -> {
                toast(context.getString(R.string.dialog_action_failed_retry_later))
            }
            is ActionError.RiskControl -> {
                AppToast.show(context, R.string.dialog_risk_control_verify_hint, Toast.LENGTH_LONG)
            }
            is ActionError.FrequencyLimit -> {
                toast(error.message)
            }
            is ActionError.Other -> toast(error.message)
            is ActionError.CsrfMissing -> {
                toast(context.getString(R.string.dialog_credential_error_retry_later))
            }
        }
    }



    private companion object {
        const val KEY_GIVE_COIN_NUMBER_SETTINGS = "give_coin_number"
    }
}
