package com.tutu.myblbl.core.ui.image

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
import android.widget.ImageView
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import com.tutu.myblbl.R
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import com.tutu.myblbl.network.NetworkManager
import pl.droidsonroids.gif.GifDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.mp.KoinPlatform
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

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // 独立的 OkHttpClient，不走业务 interceptor
    private val imageOkHttpClient: OkHttpClient by lazy { buildImageOkHttpClient() }

    // 前台 bind 与后台 prefetch 共用同 URL 请求，避免首屏重复下载/解码同一张封面。
    private val imageDataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageDataInFlight = mutableMapOf<String, Deferred<ImageData>>()

    private val preheatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preheated = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class ImageData(
        val bytes: ByteArray,
        val bitmap: Bitmap?,
        val isGif: Boolean,
        val fetchMs: Long,
        val decodeMs: Long
    )

    private data class ImageDataRequest(
        val deferred: Deferred<ImageData>,
        val reused: Boolean
    )

    // ---------- 公开 API ----------

    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Int = 0,
        error: Int = 0
    ) {
        val optimizedUrl = buildOptimizedCommonImageUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null
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
        error: Int = 0
    ) {
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, normalizedUrl, placeholder, error, circleCrop = true)
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
                val bytes = withContext(Dispatchers.IO) { fetchBytes(url) }
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

    fun loadVideoCover(
        imageView: ImageView,
        url: String?,
        placeholder: Int = R.drawable.default_video,
        error: Int = R.drawable.default_video,
        onPortraitDetected: ((Boolean) -> Unit)? = null
    ) {
        val optimizedUrl = buildOptimizedVideoCoverUrl(url)
        val normalizedUrl = normalizeUrl(url)
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
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
        error: Int = R.drawable.default_video
    ) {
        val optimizedUrl = buildOptimizedSeriesCoverUrl(url)
        val normalizedUrl = normalizeUrl(url)
        val radiusPx = imageView.context.resources.getDimensionPixelSize(R.dimen.px15).toFloat()
        loadInto(imageView, optimizedUrl, placeholder, error,
            fallbackUrl = if (optimizedUrl != normalizedUrl) normalizedUrl else null,
            cornerRadius = radiusPx
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
        // 无磁盘缓存，no-op
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
        onSuccess: ((Bitmap) -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        // URL 为空：清空
        if (url.isBlank()) {
            inFlight.remove(imageView)?.cancel()
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
        }

        // 内存缓存命中
        val cached = cache.get(url)
        if (cached != null) {
            AppLog.i(TAG, "cover memory hit url=${url.takeLast(50)}")
            imageView.setImageBitmap(applyTransform(cached, circleCrop, cornerRadius))
            onSuccess?.invoke(cached)
            return
        }

        // 设占位图
        if (placeholderRes != 0) imageView.setImageResource(placeholderRes)
        else if (imageView.drawable !== placeholder) imageView.setImageDrawable(placeholder)

        val startMs = SystemClock.elapsedRealtime()
        val job = scope.launch {
            try {
                val request = loadImageData(url)
                val data = request.deferred.await()
                val bytes = data.bytes
                val fetchMs = data.fetchMs
                if (data.isGif) {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        val gifDrawable = withContext(Dispatchers.IO) {
                            GifDrawable(bytes).also { it.start() }
                        }
                        imageView.setImageDrawable(gifDrawable)
                    }
                    AppLog.i(TAG, "gif loaded: elapsed=${SystemClock.elapsedRealtime() - startMs}ms fetch=${fetchMs}ms bytes=${bytes.size} url=${url.takeLast(50)}")
                } else {
                    val bmp = data.bitmap
                    val decodeMs = data.decodeMs
                    if (bmp != null) {
                        cache.put(url, bmp)
                        if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                            imageView.setImageBitmap(applyTransform(bmp, circleCrop, cornerRadius))
                        }
                        AppLog.i(TAG, "cover loaded: elapsed=${SystemClock.elapsedRealtime() - startMs}ms fetch=${fetchMs}ms decode=${decodeMs}ms shared=${request.reused} bytes=${bytes.size} url=${url.takeLast(50)}")
                        onSuccess?.invoke(bmp)
                    } else {
                        AppLog.w(TAG, "cover decode null: elapsed=${SystemClock.elapsedRealtime() - startMs}ms fetch=${fetchMs}ms decode=${decodeMs}ms url=${url.takeLast(50)}")
                        handleLoadError(imageView, url, errorRes, fallbackUrl, circleCrop, cornerRadius, onSuccess, onError)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    return@launch
                }
                val elapsed = SystemClock.elapsedRealtime() - startMs
                AppLog.w(TAG, "cover error: elapsed=${elapsed}ms url=${url.takeLast(50)}", t)
                handleLoadError(imageView, url, errorRes, fallbackUrl, circleCrop, cornerRadius, onSuccess, onError)
            }
        }
        inFlight[imageView] = job
    }

    private fun loadIntoDrawable(
        imageView: ImageView,
        url: String,
        placeholderDrawable: Drawable?,
        errorDrawable: Drawable?,
        fallbackUrl: String? = null
    ) {
        val ctx = imageView.context
        if (ctx is Activity && ctx.isDestroyed) return

        if (url.isBlank()) {
            inFlight.remove(imageView)?.cancel()
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
        }

        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(placeholderDrawable ?: placeholder)

        val job = scope.launch {
            try {
                val data = loadImageData(url).deferred.await()
                val bytes = data.bytes
                if (data.isGif) {
                    if ((imageView.getTag(R.id.tag_image_loader_url) as? String) == url) {
                        val gifDrawable = withContext(Dispatchers.IO) {
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
                        loadIntoDrawable(imageView, fallbackUrl, placeholderDrawable, errorDrawable)
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
                    loadIntoDrawable(imageView, fallbackUrl, placeholderDrawable, errorDrawable)
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
        onSuccess: ((Bitmap) -> Unit)?,
        onError: (() -> Unit)?
    ) {
        if (fallbackUrl != null) {
            loadInto(imageView, fallbackUrl, errorRes, errorRes,
                circleCrop = circleCrop,
                cornerRadius = cornerRadius,
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

    private fun prefetch(url: String) {
        if (url.isBlank()) return
        if (cache.get(url) != null) return
        imageDataScope.launch {
            try {
                val request = loadImageData(url)
                val data = request.deferred.await()
                val bmp = data.bitmap
                if (bmp != null) {
                    cache.put(url, bmp)
                    AppLog.i(
                        TAG,
                        "prefetch cover cached url=${url.takeLast(50)} shared=${request.reused} bytes=${data.bytes.size}"
                    )
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "prefetch failed url=${url.takeLast(50)}", t)
            }
        }
    }

    private fun loadImageData(url: String): ImageDataRequest {
        synchronized(imageDataInFlight) {
            imageDataInFlight[url]?.takeIf { it.isActive }?.let {
                return ImageDataRequest(it, reused = true)
            }
            val deferred = imageDataScope.async {
                val fetchStartMs = SystemClock.elapsedRealtime()
                val bytes = fetchBytes(url)
                val fetchMs = SystemClock.elapsedRealtime() - fetchStartMs
                val isGif = isGifBytes(bytes)
                val decodeStartMs = SystemClock.elapsedRealtime()
                val bitmap = if (isGif) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                ImageData(
                    bytes = bytes,
                    bitmap = bitmap,
                    isGif = isGif,
                    fetchMs = fetchMs,
                    decodeMs = SystemClock.elapsedRealtime() - decodeStartMs
                )
            }
            deferred.invokeOnCompletion {
                synchronized(imageDataInFlight) {
                    if (imageDataInFlight[url] === deferred) {
                        imageDataInFlight.remove(url)
                    }
                }
            }
            imageDataInFlight[url] = deferred
            return ImageDataRequest(deferred, reused = false)
        }
    }

    // ---------- 网络 ----------

    private fun fetchBytes(url: String): ByteArray {
        val requestBuilder = Request.Builder().url(url)
        if (isBilibiliImageUrl(url)) {
            requestBuilder
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", NetworkManager.getCurrentUserAgent())
        }
        return imageOkHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("Empty body for $url")
            body.bytes()
        }
    }

    // ---------- 变换 ----------

    private fun applyTransform(bmp: Bitmap, circleCrop: Boolean, cornerRadius: Float): Bitmap {
        if (circleCrop) return circleCrop(bmp)
        if (cornerRadius > 0f) return roundCorners(bmp, cornerRadius)
        return bmp
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
            .build()
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
