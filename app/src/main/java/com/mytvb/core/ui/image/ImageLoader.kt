package com.mytvb.core.ui.image

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import com.mytvb.MyBLBLApplication
import com.mytvb.R
import com.mytvb.core.common.log.AppLog
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.network.NetworkManager
import pl.droidsonroids.gif.GifDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Cache
import org.koin.mp.KoinPlatform
import java.io.File
import java.util.concurrent.Executors
import java.util.WeakHashMap

object ImageLoader {

    private const val TAG = "ImageLoader"
    private const val KEY_IMAGE_QUALITY = "image_quality"
    private const val KEY_IMAGE_QUALITY_LEVEL = "imageQualityLevel"

    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())

    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()

    @Volatile
    private var cachedImageQualityLevel: Int? = null
    private var imageQualityFlowStarted = false
    private val imageQualityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inFlight = WeakHashMap<ImageView, Job>()
    private val imageIoDispatcher = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
    private val visibleImageDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
    private val fastVisibleImageDispatcher = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
    private val highPriorityImageDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val normalPriorityImageDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val lowPriorityImageDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // 独立的 OkHttpClient，不走业务 interceptor
    private val imageOkHttpClient: OkHttpClient by lazy { buildImageOkHttpClient() }

    // 前台 bind 与后台 prefetch 共用同 URL 请求，避免首屏重复下载/解码同一张封面。
    private val imageDataScope = CoroutineScope(SupervisorJob() + imageIoDispatcher)
    private val imageDataInFlight = mutableMapOf<String, InFlightImageRequest>()

    private val preheatScope = CoroutineScope(SupervisorJob() + imageIoDispatcher)
    private val preheated = java.util.concurrent.atomic.AtomicBoolean(false)

    private val fastImageOkHttpClient: OkHttpClient by lazy { buildFastImageOkHttpClient() }
    private val fastCoverPerfLock = Any()
    private var fastCoverSessionSource = ""
    private var fastCoverSessionStartMs = 0L
    private var fastCoverLoadedCount = 0
    private var fastCoverFirstLogged = false
    private var fastCoverTargetLogged = false

    enum class Priority {
        VISIBLE_HIGH,
        HIGH,
        NORMAL,
        LOW
    }

    private data class ImageData(
        val bytes: ByteArray,
        val bitmap: Bitmap?,
        val isGif: Boolean,
        val priority: Priority,
        val queueMs: Long,
        val fetchMs: Long,
        val decodeMs: Long,
        val diskCacheHit: Boolean
    )

    private data class FetchedBytes(
        val bytes: ByteArray,
        val diskCacheHit: Boolean
    )

    private data class ImageDataRequest(
        val deferred: Deferred<ImageData>,
        val priority: Priority,
        val reused: Boolean
    )

    private data class InFlightImageRequest(
        val deferred: Deferred<ImageData>,
        val priority: Priority
    )

    private data class PreDrawDeferredLoad(
        val observer: ViewTreeObserver,
        val listener: ViewTreeObserver.OnPreDrawListener
    )

    // ---------- 公开 API ----------

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0,
        priority: Priority = Priority.NORMAL
    ) {
        val optimizedUrl = buildOptimizedCommonImageUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            priority = priority
        )
    }

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Drawable?,
        error: Drawable?
    ) {
        val optimizedUrl = buildOptimizedCommonImageUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadIntoDrawable(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null
        )
    }

    fun loadCircle(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0,
        priority: Priority = Priority.VISIBLE_HIGH
    ) {
        val optimizedUrl = buildOptimizedAvatarUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = error,
            circleCrop = true,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            priority = priority
        )
    }

    /** 原始尺寸加载（评论图片查看器等全屏场景），不做图床尺寸后缀优化。 */
    fun loadOriginal(
        imageView: ImageView,
        url: String?,
        priority: Priority = Priority.VISIBLE_HIGH
    ) {
        loadInto(
            imageView = imageView,
            url = normalizeUrl(url),
            placeholderRes = 0,
            errorRes = 0,
            priority = priority
        )
    }

    /** 评论图片缩略图（评论面板），圆角与回复预览框一致（px10）。 */
    fun loadCommentPicture(
        imageView: ImageView,
        url: String?,
        priority: Priority = Priority.NORMAL
    ) {
        val optimizedUrl = buildOptimizedCommonImageUrl(url)
        val normalizedUrl = normalizeUrl(url)
        val radiusPx = imageView.context.resources.getDimensionPixelSize(R.dimen.px10).toFloat()
        loadInto(imageView, optimizedUrl, 0, 0,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            cornerRadius = radiusPx,
            priority = priority
        )
    }

    fun loadDrawableRes(
        imageView: ImageView,
        @DrawableRes resId: Int,
        circleCrop: Boolean = false
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        // GIF 资源：用 GifDrawable 解码并自动播放
        val gifDrawable = runCatching { GifDrawable(ctx.resources, resId) }.getOrNull()
        if (gifDrawable != null) {
            gifDrawable.start()
            imageView.setImageDrawable(gifDrawable)
            return
        }

        // 非静态资源
        val drawable = ContextCompat.getDrawable(ctx, resId)
        if (drawable == null) {
            imageView.setImageResource(resId)
            return
        }

        if (circleCrop && drawable is BitmapDrawable) {
            imageView.setImageBitmap(circleCrop(drawable.bitmap))
        } else {
            imageView.setImageDrawable(drawable)
        }
    }

    fun clear(imageView: ImageView) {
        inFlight.remove(imageView)?.cancel()
        clearAttachDeferredLoad(imageView)
        clearPreDrawDeferredLoad(imageView)
        imageView.setTag(R.id.tag_image_loader_url, null)
        imageView.setImageDrawable(null)
    }

    fun loadBitmap(
        context: Context,
        url: String,
        applyBilibiliHeaders: Boolean = true,
        onSuccess: (Bitmap) -> Unit,
        onFailed: () -> Unit = {}
    ): SimpleDisposable {
        val job = scope.launch {
            try {
                val fetched = withContext(imageIoDispatcher) { fetchBytes(url) }
                val bytes = fetched.bytes
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) {
                    cache.put(url, bmp)
                    onSuccess(bmp)
                } else {
                    onFailed()
                }
            } catch (t: Throwable) {
                // 协程正常取消（滑动 seek 时丢弃旧请求）不是失败，不打日志、不回调。
                // 与 loadInto/loadFastVideoCoverInto 的 CancellationException 短路保持一致。
                if (t is CancellationException) throw t
                AppLog.w(TAG, "loadBitmap failed url=${url.takeLast(50)}", t)
                onFailed()
            }
        }
        return SimpleDisposable(job)
    }

    fun loadCenterCrop(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        load(imageView, url, placeholder, error)
    }

    /**
     * 通栏横幅图（如用户空间头图）：显示宽度接近屏宽，走通用优化链会被压成
     * 480/960 方图再拉伸，模糊严重。这里按屏幕宽度请求等比大图，CDN 不放大小图。
     */
    fun loadWideBanner(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0,
        priority: Priority = Priority.NORMAL
    ) {
        val optimizedUrl = buildOptimizedWideBannerUrl(imageView, url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            priority = priority
        )
    }

    fun loadSmallSquare(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0,
        priority: Priority = Priority.LOW
    ) {
        val optimizedUrl = buildOptimizedSmallSquareImageUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            priority = priority
        )
    }

    fun loadVideoCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video,
        priority: Priority = Priority.VISIBLE_HIGH,
        deferUntilPreDraw: Boolean = false,
        onPortraitDetected: ((Boolean) -> Unit)? = null
    ) {
        val optimizedUrl = buildOptimizedVideoCoverUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            priority = priority,
            deferUntilPreDraw = deferUntilPreDraw,
            onSuccess = { bmp ->
                if (onPortraitDetected != null) {
                    onPortraitDetected(bmp.height > bmp.width)
                }
            }
        )
    }

    fun loadSeriesCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video,
        priority: Priority = Priority.LOW
    ) {
        val optimizedUrl = buildOptimizedSeriesCoverUrl(url)
        val normalizedUrl = normalizeUrl(url)
        val radiusPx = imageView.context.resources.getDimensionPixelSize(R.dimen.px15).toFloat()
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            cornerRadius = radiusPx,
            priority = priority
        )
    }

    fun loadFastVideoCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video,
        source: String = "video",
        slot: Int = -1,
        targetCount: Int = 8,
        onPortraitDetected: ((Boolean) -> Unit)? = null
    ) {
        val optimizedUrl = buildOptimizedVideoCoverUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadFastVideoCoverInto(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            source = source,
            slot = slot,
            targetCount = targetCount,
            onSuccess = { bmp ->
                if (onPortraitDetected != null) {
                    onPortraitDetected(bmp.height > bmp.width)
                }
            }
        )
    }

    fun loadFastAvatar(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_avatar,
        error: Int = R.drawable.default_avatar,
        source: String = "avatar",
        slot: Int = -1,
        targetCount: Int = 8
    ) {
        val optimizedUrl = buildFastAvatarUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadFastVideoCoverInto(
            imageView = imageView,
            url = optimizedUrl,
            placeholderRes = placeholder,
            errorRes = error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            source = source,
            slot = slot,
            targetCount = targetCount
        )
    }

    fun loadWithListener(
        imageView: ImageView,
        url: String?,
        onLoadSuccess: () -> Unit = {},
        onLoadFailed: () -> Unit = {}
    ) {
        val optimizedUrl = buildOptimizedCommonImageUrl(url)
        loadInto(imageView, optimizedUrl, 0, 0,
            onSuccess = { onLoadSuccess() },
            onError = { onLoadFailed() }
        )
    }

    fun clearMemory(context: Context) {
        cache.evictAll()
    }

    fun clearDiskCache(context: Context) {
        runCatching { imageOkHttpClient.cache?.evictAll() }
            .onFailure { AppLog.w(TAG, "clearDiskCache failed", it) }
    }

    fun prefetchVideoCovers(context: Context, urls: List<String?>) {
        if (urls.isEmpty()) return
        val finalUrls = urls.asSequence()
            .mapNotNull { it?.takeIf { u -> u.isNotBlank() } }
            .distinct()
            .map { buildVideoCoverUrl(it) }
            .filter { it.isNotBlank() }
            .toList()
        finalUrls.forEach { prefetch(it) }
    }

    fun prefetchSeriesCovers(context: Context, urls: List<String?>) {
        if (urls.isEmpty()) return
        val finalUrls = urls.asSequence()
            .mapNotNull { it?.takeIf { u -> u.isNotBlank() } }
            .distinct()
            .map { buildSeriesCoverUrl(it) }
            .filter { it.isNotBlank() }
            .toList()
        finalUrls.forEach { prefetch(it) }
    }

    fun invalidateImageQualityCache() {
        cachedImageQualityLevel = null
    }

    fun prewarm() {
        prewarmSettings()
        prewarmCdn()
    }

    fun prewarmSettings() {
        resolveImageQualityLevel()
    }

    fun prewarmCdn() {
        preheatCdnHosts()
    }

    /**
     * 并发 HEAD 请求预热图片 CDN：触发 DNS + TCP + TLS 握手，
     * 连接放入 ConnectionPool，首屏图片请求 0 RTT 复用。
     */
    private fun preheatCdnHosts() {
        if (!preheated.compareAndSet(false, true)) return
        val hosts = listOf(
            "https://i0.hdslb.com/",
            "https://i1.hdslb.com/",
            "https://i2.hdslb.com/"
        )
        hosts.forEach { url ->
            preheatScope.launch {
                runCatching {
                    val request = Request.Builder().url(url).head().build()
                    imageOkHttpClient.newCall(request).execute().use { /* discard */ }
                }.onFailure {
                    AppLog.w(TAG, "CDN preheat failed: $url ${it.message}")
                }
                AppLog.i(TAG, "CDN preheat done: $url")
            }
        }
    }

    fun buildVideoCoverUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@240w_135h_1c.webp"
            2 -> "@672w_378h_1c.webp"
            else -> "@480w_270h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    fun buildSeriesCoverUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@160w_213h_1c.webp"
            2 -> "@466w_622h_1c.webp"
            else -> "@320w_426h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun isGifBytes(bytes: ByteArray): Boolean {
        return bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
    }

    // ---------- 内部实现 ----------

    private fun loadInto(
        imageView: ImageView,
        url: String,
        placeholderRes: Int,
        errorRes: Int,
        circleCrop: Boolean = false,
        cornerRadius: Float = 0f,
        fallbackUrl: String? = null,
        priority: Priority = Priority.NORMAL,
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: (() -> Unit)? = null,
        deferUntilPreDraw: Boolean = false
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        // URL 为空：清空
        if (url.isBlank()) {
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
            imageView.setTag(R.id.tag_image_loader_url, null)
            if (placeholderRes != 0) imageView.setImageResource(placeholderRes)
            else imageView.setImageDrawable(placeholder)
            return
        }

        // 同 URL 防复写
        val lastUrl = imageView.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == url) {
            val job = inFlight[imageView]
            if (job != null && job.isActive) return
        } else {
            imageView.setTag(R.id.tag_image_loader_url, url)
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
        }

        transformCacheKey(url, circleCrop, cornerRadius)?.let { key ->
            val transformedCached = cache.get(key)
            if (transformedCached != null) {
                AppLog.i(TAG, "cover transform memory hit url=${url.takeLast(50)}")
                imageView.setImageBitmap(transformedCached)
                onSuccess?.invoke(transformedCached)
                return
            }
        }

        // 内存缓存命中
        val cached = cache.get(url)
        if (cached != null) {
            AppLog.i(TAG, "cover memory hit url=${url.takeLast(50)}")
            imageView.setImageBitmap(applyTransform(url, cached, circleCrop, cornerRadius))
            onSuccess?.invoke(cached)
            return
        }

        // 设占位图
        if (placeholderRes != 0) imageView.setImageResource(placeholderRes)
        else if (imageView.drawable !== placeholder) imageView.setImageDrawable(placeholder)

        if (!imageView.isAttachedToWindow) {
            deferLoadUntilAttached(imageView, url) {
                loadInto(
                    imageView = imageView,
                    url = url,
                    placeholderRes = placeholderRes,
                    errorRes = errorRes,
                    circleCrop = circleCrop,
                    cornerRadius = cornerRadius,
                    fallbackUrl = fallbackUrl,
                    priority = priority,
                    onSuccess = onSuccess,
                    onError = onError,
                    deferUntilPreDraw = deferUntilPreDraw
                )
            }
            return
        }

        if (deferUntilPreDraw && (priority == Priority.NORMAL || priority == Priority.LOW)) {
            deferLoadUntilPreDraw(imageView, url) {
                loadInto(
                    imageView = imageView,
                    url = url,
                    placeholderRes = placeholderRes,
                    errorRes = errorRes,
                    circleCrop = circleCrop,
                    cornerRadius = cornerRadius,
                    fallbackUrl = fallbackUrl,
                    priority = priority,
                    onSuccess = onSuccess,
                    onError = onError,
                    deferUntilPreDraw = false
                )
            }
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val job = scope.launch {
            try {
                val request = loadImageData(url, priority)
                val awaitStartMs = SystemClock.elapsedRealtime()
                val data = request.deferred.await()
                val awaitMs = SystemClock.elapsedRealtime() - awaitStartMs
                val bytes = data.bytes
                val fetchMs = data.fetchMs
                if (data.isGif) {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        val gifDrawable = withContext(imageIoDispatcher) {
                            GifDrawable(bytes).also { it.start() }
                        }
                        imageView.setImageDrawable(gifDrawable)
                    }
                    AppLog.i(TAG, "gif loaded: elapsed=${SystemClock.elapsedRealtime() - startMs}ms await=${awaitMs}ms queue=${data.queueMs}ms fetch=${fetchMs}ms priority=${data.priority} diskCache=${data.diskCacheHit} bytes=${bytes.size} url=${url.takeLast(50)}")
                } else {
                    val bmp = data.bitmap
                    val decodeMs = data.decodeMs
                    if (bmp != null) {
                        cache.put(url, bmp)
                        var applyMs = 0L
                        if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                            val applyStartMs = SystemClock.elapsedRealtime()
                            imageView.setImageBitmap(applyTransform(url, bmp, circleCrop, cornerRadius))
                            applyMs = SystemClock.elapsedRealtime() - applyStartMs
                        }
                        AppLog.i(TAG, "cover loaded: elapsed=${SystemClock.elapsedRealtime() - startMs}ms await=${awaitMs}ms queue=${data.queueMs}ms fetch=${fetchMs}ms decode=${decodeMs}ms apply=${applyMs}ms priority=${data.priority} shared=${request.reused} diskCache=${data.diskCacheHit} bytes=${bytes.size} url=${url.takeLast(50)}")
                        onSuccess?.invoke(bmp)
                    } else {
                        AppLog.w(TAG, "cover decode null: elapsed=${SystemClock.elapsedRealtime() - startMs}ms queue=${data.queueMs}ms fetch=${fetchMs}ms decode=${decodeMs}ms priority=${data.priority} diskCache=${data.diskCacheHit} url=${url.takeLast(50)}")
                        handleLoadError(imageView, url, errorRes, fallbackUrl, circleCrop, cornerRadius, priority, onSuccess, onError)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    return@launch
                }
                val elapsed = SystemClock.elapsedRealtime() - startMs
                AppLog.w(TAG, "cover error: elapsed=${elapsed}ms url=${url.takeLast(50)}", t)
                handleLoadError(imageView, url, errorRes, fallbackUrl, circleCrop, cornerRadius, priority, onSuccess, onError)
            }
        }
        inFlight[imageView] = job
    }

    private fun loadFastVideoCoverInto(
        imageView: ImageView,
        url: String,
        placeholderRes: Int,
        errorRes: Int,
        fallbackUrl: String?,
        source: String,
        slot: Int,
        targetCount: Int,
        onSuccess: ((Bitmap) -> Unit)? = null,
        retriedFallback: Boolean = false
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        if (url.isBlank()) {
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
            imageView.setTag(R.id.tag_image_loader_url, null)
            if (placeholderRes != 0) imageView.setImageResource(placeholderRes)
            else imageView.setImageDrawable(placeholder)
            return
        }

        val lastUrl = imageView.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == url) {
            val job = inFlight[imageView]
            if (job != null && job.isActive) return
        } else {
            imageView.setTag(R.id.tag_image_loader_url, url)
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
        }

        if (slot == 0 && !retriedFallback) {
            startFastCoverPerfSession(source)
        }

        cache.get(url)?.let { cached ->
            imageView.setImageBitmap(cached)
            onSuccess?.invoke(cached)
            recordFastCoverLoaded(source, slot, targetCount, cacheHit = true)
            return
        }

        if (placeholderRes != 0) imageView.setImageResource(placeholderRes)
        else if (imageView.drawable !== placeholder) imageView.setImageDrawable(placeholder)

        if (!imageView.isAttachedToWindow) {
            deferLoadUntilAttached(imageView, url) {
                loadFastVideoCoverInto(
                    imageView = imageView,
                    url = url,
                    placeholderRes = placeholderRes,
                    errorRes = errorRes,
                    fallbackUrl = fallbackUrl,
                    source = source,
                    slot = slot,
                    targetCount = targetCount,
                    onSuccess = onSuccess,
                    retriedFallback = retriedFallback
                )
            }
            return
        }

        val startMs = SystemClock.elapsedRealtime()
        val job = scope.launch {
            try {
                val fetchStartMs = SystemClock.elapsedRealtime()
                val bytes = withContext(fastVisibleImageDispatcher) { fetchBytesFast(url) }
                val fetchMs = SystemClock.elapsedRealtime() - fetchStartMs
                val decodeStartMs = SystemClock.elapsedRealtime()
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                val decodeMs = SystemClock.elapsedRealtime() - decodeStartMs
                if (bmp != null) {
                    cache.put(url, bmp)
                    var applyMs = 0L
                    var applied = false
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        val applyStartMs = SystemClock.elapsedRealtime()
                        imageView.setImageBitmap(bmp)
                        applyMs = SystemClock.elapsedRealtime() - applyStartMs
                        applied = true
                    }
                    AppLog.i(
                        TAG,
                        "fast cover loaded: elapsed=${SystemClock.elapsedRealtime() - startMs}ms fetch=${fetchMs}ms decode=${decodeMs}ms apply=${applyMs}ms source=$source slot=$slot bytes=${bytes.size} url=${url.takeLast(50)}"
                    )
                    if (applied) {
                        onSuccess?.invoke(bmp)
                        recordFastCoverLoaded(source, slot, targetCount, cacheHit = false)
                    }
                } else if (fallbackUrl != null && !retriedFallback) {
                    loadFastVideoCoverInto(
                        imageView = imageView,
                        url = fallbackUrl,
                        placeholderRes = errorRes,
                        errorRes = errorRes,
                        fallbackUrl = null,
                        source = source,
                        slot = slot,
                        targetCount = targetCount,
                        onSuccess = onSuccess,
                        retriedFallback = true
                    )
                } else {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        if (errorRes != 0) imageView.setImageResource(errorRes)
                    }
                    AppLog.w(TAG, "fast cover decode null: elapsed=${SystemClock.elapsedRealtime() - startMs}ms source=$source slot=$slot url=${url.takeLast(50)}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    return@launch
                }
                if (fallbackUrl != null && !retriedFallback) {
                    loadFastVideoCoverInto(
                        imageView = imageView,
                        url = fallbackUrl,
                        placeholderRes = errorRes,
                        errorRes = errorRes,
                        fallbackUrl = null,
                        source = source,
                        slot = slot,
                        targetCount = targetCount,
                        onSuccess = onSuccess,
                        retriedFallback = true
                    )
                } else {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        if (errorRes != 0) imageView.setImageResource(errorRes)
                    }
                    AppLog.w(TAG, "fast cover error: elapsed=${SystemClock.elapsedRealtime() - startMs}ms source=$source slot=$slot url=${url.takeLast(50)}", t)
                }
            }
        }
        inFlight[imageView] = job
    }

    private fun loadIntoDrawable(
        imageView: ImageView,
        url: String,
        placeholderDrawable: Drawable?,
        errorDrawable: Drawable?,
        fallbackUrl: String? = null,
        priority: Priority = Priority.NORMAL
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        if (url.isBlank()) {
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
            imageView.setTag(R.id.tag_image_loader_url, null)
            imageView.setImageDrawable(placeholderDrawable ?: placeholder)
            return
        }

        val lastUrl = imageView.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == url) {
            val job = inFlight[imageView]
            if (job != null && job.isActive) return
        } else {
            imageView.setTag(R.id.tag_image_loader_url, url)
            inFlight.remove(imageView)?.cancel()
            clearAttachDeferredLoad(imageView)
            clearPreDrawDeferredLoad(imageView)
        }

        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(placeholderDrawable ?: placeholder)

        if (!imageView.isAttachedToWindow) {
            deferLoadUntilAttached(imageView, url) {
                loadIntoDrawable(imageView, url, placeholderDrawable, errorDrawable, fallbackUrl, priority)
            }
            return
        }

        val job = scope.launch {
            try {
                val data = loadImageData(url, priority).deferred.await()
                val bytes = data.bytes
                if (data.isGif) {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        val gifDrawable = withContext(imageIoDispatcher) {
                            GifDrawable(bytes).also { it.start() }
                        }
                        imageView.setImageDrawable(gifDrawable)
                    }
                } else {
                    val bmp = data.bitmap
                    if (bmp != null) {
                        cache.put(url, bmp)
                        if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                            imageView.setImageBitmap(bmp)
                        }
                    } else if (fallbackUrl != null) {
                        loadIntoDrawable(imageView, fallbackUrl, placeholderDrawable, errorDrawable, priority = priority)
                    } else {
                        if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                            imageView.setImageDrawable(errorDrawable)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    return@launch
                }
                AppLog.w(TAG, "load failed url=${url.takeLast(50)}", t)
                if (fallbackUrl != null) {
                    loadIntoDrawable(imageView, fallbackUrl, placeholderDrawable, errorDrawable, priority = priority)
                } else if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                    imageView.setImageDrawable(errorDrawable)
                }
            }
        }
        inFlight[imageView] = job
    }

    private fun handleLoadError(
        imageView: ImageView,
        url: String,
        errorRes: Int,
        fallbackUrl: String?,
        circleCrop: Boolean,
        cornerRadius: Float,
        priority: Priority,
        onSuccess: ((Bitmap) -> Unit)?,
        onError: (() -> Unit)?
    ) {
        if (fallbackUrl != null) {
            loadInto(imageView, fallbackUrl, errorRes, errorRes,
                circleCrop = circleCrop,
                cornerRadius = cornerRadius,
                priority = priority,
                onSuccess = onSuccess,
                onError = onError
            )
        } else {
            if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                if (errorRes != 0) imageView.setImageResource(errorRes)
            }
            onError?.invoke()
        }
    }

    private fun deferLoadUntilAttached(
        imageView: ImageView,
        url: String,
        startLoad: () -> Unit
    ) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                clearAttachDeferredLoad(imageView)
                if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                    startLoad()
                }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        imageView.setTag(R.id.tag_image_loader_attach_listener, listener)
        imageView.addOnAttachStateChangeListener(listener)
    }

    private fun clearAttachDeferredLoad(imageView: ImageView) {
        val listener = imageView.getTag(R.id.tag_image_loader_attach_listener) as? View.OnAttachStateChangeListener
        if (listener != null) {
            imageView.removeOnAttachStateChangeListener(listener)
            imageView.setTag(R.id.tag_image_loader_attach_listener, null)
        }
    }

    private fun deferLoadUntilPreDraw(
        imageView: ImageView,
        url: String,
        startLoad: () -> Unit
    ) {
        clearPreDrawDeferredLoad(imageView)
        val observer = imageView.viewTreeObserver
        if (!observer.isAlive) {
            imageView.post {
                if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                    startLoad()
                }
            }
            return
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                clearPreDrawDeferredLoad(imageView)
                if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                    imageView.post {
                        if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                            AppLog.i(TAG, "cover deferred_start after_pre_draw url=${url.takeLast(50)}")
                            startLoad()
                        }
                    }
                }
                return true
            }
        }
        imageView.setTag(R.id.tag_image_loader_predraw_listener, PreDrawDeferredLoad(observer, listener))
        observer.addOnPreDrawListener(listener)
    }

    private fun clearPreDrawDeferredLoad(imageView: ImageView) {
        val deferred = imageView.getTag(R.id.tag_image_loader_predraw_listener) as? PreDrawDeferredLoad
            ?: return
        if (deferred.observer.isAlive) {
            deferred.observer.removeOnPreDrawListener(deferred.listener)
        } else {
            imageView.viewTreeObserver.removeOnPreDrawListener(deferred.listener)
        }
        imageView.setTag(R.id.tag_image_loader_predraw_listener, null)
    }

    private fun prefetch(url: String, priority: Priority = Priority.LOW) {
        if (url.isBlank()) return
        if (cache.get(url) != null) return
        imageDataScope.launch {
            try {
                val request = loadImageData(url, priority)
                val data = request.deferred.await()
                val bmp = data.bitmap
                if (bmp != null) {
                    cache.put(url, bmp)
                    AppLog.i(
                        TAG,
                        "prefetch cover cached url=${url.takeLast(50)} priority=${data.priority} shared=${request.reused} diskCache=${data.diskCacheHit} bytes=${data.bytes.size}"
                    )
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "prefetch failed url=${url.takeLast(50)}", t)
            }
        }
    }

    private fun loadImageData(url: String, priority: Priority): ImageDataRequest {
        val requestKey = "${priority.name}:$url"
        synchronized(imageDataInFlight) {
            imageDataInFlight[requestKey]?.takeIf { it.deferred.isActive }?.let {
                return ImageDataRequest(it.deferred, priority = it.priority, reused = true)
            }
            val queuedAtMs = SystemClock.elapsedRealtime()
            val dispatcher = dispatcherFor(priority)
            val deferred = imageDataScope.async(dispatcher) {
                val fetchStartMs = SystemClock.elapsedRealtime()
                val fetched = fetchBytes(url)
                val bytes = fetched.bytes
                val fetchMs = SystemClock.elapsedRealtime() - fetchStartMs
                val isGif = isGifBytes(bytes)
                val decodeStartMs = SystemClock.elapsedRealtime()
                val bitmap = if (isGif) {
                    null
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                ImageData(
                    bytes = bytes,
                    bitmap = bitmap,
                    isGif = isGif,
                    priority = priority,
                    queueMs = fetchStartMs - queuedAtMs,
                    fetchMs = fetchMs,
                    decodeMs = SystemClock.elapsedRealtime() - decodeStartMs,
                    diskCacheHit = fetched.diskCacheHit
                )
            }
            deferred.invokeOnCompletion {
                synchronized(imageDataInFlight) {
                    if (imageDataInFlight[requestKey]?.deferred === deferred) {
                        imageDataInFlight.remove(requestKey)
                    }
                }
            }
            imageDataInFlight[requestKey] = InFlightImageRequest(deferred, priority)
            return ImageDataRequest(deferred, priority = priority, reused = false)
        }
    }

    private fun dispatcherFor(priority: Priority) = when (priority) {
        Priority.VISIBLE_HIGH -> visibleImageDispatcher
        Priority.HIGH -> highPriorityImageDispatcher
        Priority.NORMAL -> normalPriorityImageDispatcher
        Priority.LOW -> lowPriorityImageDispatcher
    }

    // ---------- 网络 ----------

    // B 站图床的三个等价 CDN，同一 path 换 host 可取到同一张图。
    private val BILI_CDN_HOSTS = listOf("i0.hdslb.com", "i1.hdslb.com", "i2.hdslb.com")

    // 视为可换 host 重试的网络异常：证书/hostname 校验、连接拒绝、超时、域名解析、socket 等。
    // SSLPeerUnverifiedException 是 SSLException 子类，列出仅为可读性。
    private fun isRetryableNetworkError(t: Throwable): Boolean {
        return t is javax.net.ssl.SSLException ||
            t is javax.net.ssl.SSLPeerUnverifiedException ||
            t is java.net.ConnectException ||
            t is java.net.SocketTimeoutException ||
            t is java.net.UnknownHostException ||
            (t is java.io.IOException && t !is CancellationException)
    }

    // 仅对 i0/i1/i2.hdslb.com 的 https URL 换 host，返回除当前 host 外的候选（顺序随机化以分散压力）。
    private fun alternateBiliCdnUrls(url: String): List<String> {
        val currentHost = BILI_CDN_HOSTS.firstOrNull { url.startsWith("https://$it/", ignoreCase = true) }
            ?: return emptyList()
        val tail = url.substringAfter("https://$currentHost", "")
        return (BILI_CDN_HOSTS - currentHost).shuffled().map { "https://$it$tail" }
    }

    // 单次请求，复用 header 构造与磁盘缓存命中判定。抛出的异常由外层 failover 决定是否重试。
    private fun fetchBytesOnce(url: String, client: OkHttpClient): FetchedBytes {
        val requestBuilder = Request.Builder().url(url)
        if (isBilibiliImageUrl(url)) {
            requestBuilder
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", NetworkManager.getCurrentUserAgent())
        }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("Empty body for $url")
            FetchedBytes(
                bytes = body.bytes(),
                diskCacheHit = response.cacheResponse != null
            )
        }
    }

    private fun fetchBytes(url: String): FetchedBytes {
        // B 站图床 CDN host 容错：某个节点（如 i0）证书/连接异常时自动换 i1/i2 重试。
        val alternates = alternateBiliCdnUrls(url)
        if (alternates.isEmpty()) return fetchBytesOnce(url, imageOkHttpClient)
        var lastError: Throwable? = null
        try {
            return fetchBytesOnce(url, imageOkHttpClient)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            lastError = t
            if (!isRetryableNetworkError(t)) throw t
        }
        for (alt in alternates) {
            try {
                val data = fetchBytesOnce(alt, imageOkHttpClient)
                AppLog.i(TAG, "cover cdn_failover url=${url.takeLast(50)} alt_host=${alt.substringAfter("https://").substringBefore('/')}")
                return data
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                if (!isRetryableNetworkError(t)) throw t
            }
        }
        throw lastError ?: java.io.IOException("CDN failover exhausted for $url")
    }

    private fun fetchBytesFast(url: String): ByteArray {
        val alternates = alternateBiliCdnUrls(url)
        if (alternates.isEmpty()) return fetchBytesOnce(url, fastImageOkHttpClient).bytes
        var lastError: Throwable? = null
        try {
            return fetchBytesOnce(url, fastImageOkHttpClient).bytes
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            lastError = t
            if (!isRetryableNetworkError(t)) throw t
        }
        for (alt in alternates) {
            try {
                val bytes = fetchBytesOnce(alt, fastImageOkHttpClient).bytes
                AppLog.i(TAG, "cover cdn_failover url=${url.takeLast(50)} alt_host=${alt.substringAfter("https://").substringBefore('/')}")
                return bytes
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                if (!isRetryableNetworkError(t)) throw t
            }
        }
        throw lastError ?: java.io.IOException("CDN failover exhausted for $url")
    }

    // ---------- 变换 ----------

    private fun applyTransform(url: String, bmp: Bitmap, circleCrop: Boolean, cornerRadius: Float): Bitmap {
        val key = transformCacheKey(url, circleCrop, cornerRadius) ?: return bmp
        cache.get(key)?.let { return it }
        val transformed = when {
            circleCrop -> circleCrop(bmp)
            cornerRadius > 0f -> roundCorners(bmp, cornerRadius)
            else -> bmp
        }
        if (transformed !== bmp) {
            cache.put(key, transformed)
        }
        return transformed
    }

    private fun transformCacheKey(url: String, circleCrop: Boolean, cornerRadius: Float): String? {
        if (circleCrop) return "$url#circle"
        if (cornerRadius > 0f) return "$url#round:${cornerRadius.toInt()}"
        return null
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2

        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.translate(-x.toFloat(), -y.toFloat())
        val r = size / 2f
        canvas.drawCircle(x + r, y + r, r, paint)
        return result
    }

    private fun roundCorners(src: Bitmap, radius: Float): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), radius, radius, paint)
        return result
    }

    // ---------- OkHttpClient ----------

    private fun buildImageOkHttpClient(): OkHttpClient {
        val mainClient = NetworkManager.getOkHttpClient()
        val cacheDir = File(MyBLBLApplication.instance.cacheDir, "image_http_cache")
        return OkHttpClient.Builder()
            .dns(mainClient.dns)
            .protocols(mainClient.protocols)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .connectionPool(okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(cacheDir, 64L * 1024L * 1024L))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (isCacheableImageUrl(chain.request().url.toString())) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=604800")
                        .build()
                } else {
                    response
                }
            }
            .build()
    }

    private fun buildFastImageOkHttpClient(): OkHttpClient {
        val mainClient = NetworkManager.getOkHttpClient()
        return mainClient.newBuilder()
            .cache(null)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            })
            .connectionPool(okhttp3.ConnectionPool(12, 5, java.util.concurrent.TimeUnit.MINUTES))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun startFastCoverPerfSession(source: String) {
        synchronized(fastCoverPerfLock) {
            fastCoverSessionSource = source
            fastCoverSessionStartMs = SystemClock.elapsedRealtime()
            fastCoverLoadedCount = 0
            fastCoverFirstLogged = false
            fastCoverTargetLogged = false
        }
        AppLog.i("PagePerf", "$source fast_cover_session start")
    }

    private fun recordFastCoverLoaded(
        source: String,
        slot: Int,
        targetCount: Int,
        cacheHit: Boolean
    ) {
        if (slot < 0) return
        val message: String? = synchronized(fastCoverPerfLock) {
            if (source != fastCoverSessionSource || fastCoverSessionStartMs <= 0L) {
                return
            }
            fastCoverLoadedCount += 1
            val elapsed = SystemClock.elapsedRealtime() - fastCoverSessionStartMs
            when {
                !fastCoverFirstLogged -> {
                    fastCoverFirstLogged = true
                    "$source fast_cover_first elapsed=${elapsed}ms slot=$slot cacheHit=$cacheHit"
                }
                !fastCoverTargetLogged && fastCoverLoadedCount >= targetCount.coerceAtLeast(1) -> {
                    fastCoverTargetLogged = true
                    "$source fast_cover_target elapsed=${elapsed}ms count=$fastCoverLoadedCount target=$targetCount"
                }
                else -> null
            }
        }
        if (message != null) {
            AppLog.i("PagePerf", message)
        }
    }

    // ---------- URL 规范化 / 尺寸优化 ----------

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            url.startsWith("//") -> "https:$url"
            url.startsWith("/bfs/") -> "https://i0.hdslb.com$url"
            url.startsWith("/face/") -> "https://i0.hdslb.com/bfs$url"
            url.startsWith("face/") -> "https://i0.hdslb.com/bfs/$url"
            url.startsWith("bfs/") -> "https://i0.hdslb.com/$url"
            else -> url
        }
    }

    private fun buildOptimizedVideoCoverUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@240w_135h_1c.webp"
            2 -> "@672w_378h_1c.webp"
            else -> "@480w_270h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedCommonImageUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@240w_240h_1c.webp"
            2 -> "@960w_960h_1c.webp"
            else -> "@480w_480h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedWideBannerUrl(imageView: ImageView, url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val screenWidth = imageView.resources.displayMetrics.widthPixels
        val targetW = when (resolveImageQualityLevel()) {
            0 -> (screenWidth / 2).coerceAtMost(1280)
            else -> screenWidth
        }.coerceIn(480, 2560)
        return appendImageSuffix(normalized, "@${targetW}w.webp")
    }

    private fun buildOptimizedAvatarUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@80w_80h_1c.webp"
            2 -> "@160w_160h_1c.webp"
            else -> "@100w_100h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildFastAvatarUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        return appendImageSuffix(normalized, "@80w_80h_1c.webp")
    }

    private fun buildOptimizedSmallSquareImageUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@80w_80h_1c.webp"
            2 -> "@240w_240h_1c.webp"
            else -> "@160w_160h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun buildOptimizedSeriesCoverUrl(url: String?): String {
        val normalized = normalizeUrl(url)
        if (!isBilibiliImageUrl(normalized)) return normalized
        val suffix = when (resolveImageQualityLevel()) {
            0 -> "@160w_213h_1c.webp"
            2 -> "@466w_622h_1c.webp"
            else -> "@320w_426h_1c.webp"
        }
        return appendImageSuffix(normalized, suffix)
    }

    private fun resolveImageQualityLevel(): Int {
        cachedImageQualityLevel?.let { return it }
        ensureImageQualityFlowCollection()
        appSettings.getCachedString(KEY_IMAGE_QUALITY)?.let { label ->
            return qualityLabelToLevel(label).also { cachedImageQualityLevel = it }
        }
        val level = appSettings.getCachedInt(KEY_IMAGE_QUALITY_LEVEL, -1)
        if (level >= 0) {
            return level.coerceIn(0, 2).also { cachedImageQualityLevel = it }
        }
        return 1.also { cachedImageQualityLevel = it }
    }

    @Synchronized
    private fun ensureImageQualityFlowCollection() {
        if (imageQualityFlowStarted) return
        imageQualityFlowStarted = true
        imageQualityScope.launch {
            kotlinx.coroutines.flow.combine(
                appSettings.getStringFlow(KEY_IMAGE_QUALITY),
                appSettings.getIntFlow(KEY_IMAGE_QUALITY_LEVEL, -1)
            ) { _, _ -> }.collect {
                cachedImageQualityLevel = null
            }
        }
    }

    private fun qualityLabelToLevel(label: String): Int {
        return when (label.trim()) {
            "低尺寸" -> 0
            "高尺寸" -> 2
            else -> 1
        }
    }

    private fun isBilibiliImageUrl(url: String): Boolean {
        return url.contains("hdslb.com", ignoreCase = true) ||
            url.contains("biliimg.com", ignoreCase = true) ||
            url.startsWith("bfs/")
    }

    private fun isCacheableImageUrl(url: String): Boolean {
        return isBilibiliImageUrl(url) ||
            url.contains("cctvpic.com", ignoreCase = true)
    }

    private fun appendImageSuffix(url: String, suffix: String): String {
        if (url.isBlank()) return url
        val queryPart = url.substringAfter('?', "")
        val baseUrl = if (queryPart.isEmpty()) url else url.substringBefore('?')
        val cleanedBase = stripExistingImageProcessSuffix(baseUrl)
        val optimized = cleanedBase + suffix
        return if (queryPart.isEmpty()) optimized else "$optimized?$queryPart"
    }

    private fun stripExistingImageProcessSuffix(url: String): String {
        val extensionIndex = url.lastIndexOf('.')
        if (extensionIndex == -1 || extensionIndex >= url.length - 1) return url
        val processSuffixIndex = url.indexOf('@', startIndex = extensionIndex + 1)
        if (processSuffixIndex == -1) return url
        return url.substring(0, processSuffixIndex)
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}

class SimpleDisposable(private val job: Job) {
    fun dispose() {
        job.cancel()
    }
}
