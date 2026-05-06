package com.tutu.myblbl.network.cookie

import android.content.Context
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.koin.mp.KoinPlatform

class CookieManager : CookieJar {

    private val webCookieManager: android.webkit.CookieManager by lazy {
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
    }
    
    private val appSettings: AppSettingsDataStore get() = KoinPlatform.getKoin().get()
    private val cookieCache = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    @Volatile
    private var lastCookieCleanupTime = 0L

    companion object {
        private const val KEY_COOKIES = "cookies"
        private const val COOKIE_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L
        private val PROTECTED_COOKIE_NAMES = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5"
        )
    }
    
    fun init(context: Context, syncWebViewCookies: Boolean = true) {
        loadCookiesFromPrefs()
        if (syncWebViewCookies) {
            syncFromWebView()
        }
    }

    private fun loadCookiesFromPrefs() {
        cookieCache.clear()
        val cookieStrings = appSettings.getCachedStringSet(KEY_COOKIES)
        cookieStrings.forEach { cookieString ->
            parseCookie(cookieString)?.let(::upsertCookie)
        }
        persistCookieCache()
    }

    fun saveCookies(cookieStrings: List<String>) {
        cookieStrings.forEach { cookieString ->
            parseCookie(cookieString)?.let(::upsertCookie)
        }
        persistCookieCache()
    }

    fun saveCookieObjects(cookies: List<Cookie>) {
        cookies.forEach(::upsertCookie)
        persistCookieCache()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpiredCookies()
        val resultCookies = mutableListOf<Cookie>()
        val domain = url.host

        cookieCache.forEach { (cookieDomain, domainCookies) ->
            if (domain == cookieDomain || domain.endsWith(".$cookieDomain")) {
                resultCookies.addAll(domainCookies.filter(::isCookieActive))
            }
        }

        return resultCookies
            .distinctBy { "${it.name}|${it.domain}|${it.path}" }
            .sortedWith(compareByDescending { it.name == "SESSDATA" })
    }
    
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cookieStrings = cookies.map { encodeCookie(it) }
        saveCookies(cookieStrings)
        syncToWebView(url, cookies)
    }
    
    fun getCsrfToken(): String {
        return findCookie("bili_jct")?.value.orEmpty()
    }

    fun hasSessionCookie(): Boolean {
        return findCookie("SESSDATA") != null
    }

    fun getCookieValue(name: String): String? {
        return findCookie(name)?.value
    }

    fun getCookie(name: String): Cookie? {
        return findCookie(name)
    }

    fun getCookieHeaderFor(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val cookies = loadForRequest(httpUrl)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun clearCookies() {
        cookieCache.clear()
        appSettings.putStringSetAsync(KEY_COOKIES, emptySet())
        runCatching {
            webCookieManager.removeAllCookies(null)
            webCookieManager.flush()
        }
    }

    fun syncFromWebView() {
        val protectedValues = PROTECTED_COOKIE_NAMES.mapNotNull { name ->
            findCookie(name)?.let { name to it.value }
        }.toMap()
        knownCookieUrls.forEach { targetUrl ->
            val rawCookies = runCatching {
                webCookieManager.getCookie(targetUrl)
            }.getOrNull().orEmpty()
            if (rawCookies.isBlank()) {
                return@forEach
            }
            val host = targetUrl.toHttpUrlOrNull()?.host ?: return@forEach
            rawCookies.split(';')
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains('=') }
                .forEach { cookiePair ->
                    val parsed = parseWebViewCookie(host, cookiePair) ?: return@forEach
                    if (parsed.name in PROTECTED_COOKIE_NAMES) {
                        val currentValue = protectedValues[parsed.name]
                        if (currentValue != null && currentValue != parsed.value) {
                            return@forEach
                        }
                    }
                    upsertCookie(parsed)
                }
        }
        persistCookieCache()
    }
    
    private fun parseCookie(cookieString: String): Cookie? {
        return try {
            val parts = cookieString.split(";").map { it.trim() }
            if (parts.isEmpty()) return null
            
            val nameValue = parts[0].split("=", limit = 2)
            if (nameValue.size != 2) return null
            
            val builder = Cookie.Builder()
                .name(nameValue[0])
                .value(nameValue[1])
            
            parts.drop(1).forEach { part ->
                when {
                    part.startsWith("domain=", ignoreCase = true) -> {
                        builder.domain(normalizeDomain(part.substring(7)))
                    }
                    part.startsWith("path=", ignoreCase = true) -> {
                        builder.path(part.substring(5))
                    }
                    part.equals("secure", ignoreCase = true) -> {
                        builder.secure()
                    }
                    part.startsWith("max-age=", ignoreCase = true) -> {
                        builder.expiresAt(parseMaxAge(part.substring(8)))
                    }
                    part.startsWith("expires=", ignoreCase = true) -> {
                        builder.expiresAt(parseExpires(part.substring(8)))
                    }
                }
            }
            
            builder.build()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parseExpires(expiresStr: String): Long {
        return try {
            if (expiresStr.toLongOrNull() != null) {
                expiresStr.toLong()
            } else {
                System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        }
    }

    private fun parseMaxAge(maxAgeStr: String): Long {
        val seconds = maxAgeStr.toLongOrNull()
            ?: return System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        return if (seconds <= 0L) {
            0L
        } else {
            System.currentTimeMillis() + seconds * 1000L
        }
    }
    
    private fun encodeCookie(cookie: Cookie): String {
        val sb = StringBuilder()
        sb.append(cookie.name).append("=").append(cookie.value)
        sb.append("; domain=").append(normalizeDomain(cookie.domain))
        sb.append("; path=").append(cookie.path)
        if (cookie.secure) sb.append("; secure")
        if (cookie.expiresAt != Long.MAX_VALUE) {
            sb.append("; expires=").append(cookie.expiresAt)
        }
        return sb.toString()
    }

    private fun normalizeDomain(domain: String): String {
        return domain.trim().removePrefix(".")
    }

    private fun syncToWebView(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            runCatching {
                webCookieManager.setCookie(url.toString(), encodeCookie(cookie))
            }
        }
        runCatching {
            webCookieManager.flush()
        }
    }

    private fun parseWebViewCookie(host: String, cookiePair: String): Cookie? {
        val nameValue = cookiePair.split("=", limit = 2)
        if (nameValue.size != 2) {
            return null
        }
        return runCatching {
            Cookie.Builder()
                .name(nameValue[0].trim())
                .value(nameValue[1].trim())
                .domain(normalizeDomain(host))
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build()
        }.getOrNull()
    }

    private fun findCookie(name: String): Cookie? {
        removeExpiredCookies()
        return cookieCache.values
            .asSequence()
            .flatten()
            .firstOrNull { it.name == name && isCookieActive(it) }
    }

    private fun upsertCookie(cookie: Cookie) {
        val domain = normalizeDomain(cookie.domain)
        if (domain.isBlank()) {
            return
        }
        if (isProtectedCookieBeingCleared(cookie)) {
            return
        }
        val cookies = cookieCache.getOrPut(domain) { CopyOnWriteArrayList() }
        val existingIndex = cookies.indexOfFirst {
            it.name == cookie.name && it.path == cookie.path
        }
        if (!isCookieActive(cookie)) {
            if (existingIndex >= 0) {
                cookies.removeAt(existingIndex)
            }
            if (cookies.isEmpty()) {
                cookieCache.remove(domain)
            }
            return
        }
        if (existingIndex >= 0) {
            cookies[existingIndex] = cookie
        } else {
            cookies.add(cookie)
        }
    }

    private fun isProtectedCookieBeingCleared(cookie: Cookie): Boolean {
        if (cookie.name !in PROTECTED_COOKIE_NAMES) return false
        if (isCookieActive(cookie)) return false
        val existing = findCookie(cookie.name)
        return existing != null && isCookieActive(existing)
    }

    private fun persistCookieCache() {
        removeExpiredCookies()
        val cookieStrings = cookieCache.values
            .asSequence()
            .flatten()
            .filter(::isCookieActive)
            .map(::encodeCookie)
            .toSet()
        appSettings.putStringSetAsync(KEY_COOKIES, cookieStrings)
    }

    private fun removeExpiredCookies() {
        val now = System.currentTimeMillis()
        if (now - lastCookieCleanupTime < COOKIE_CLEANUP_INTERVAL_MS) return
        lastCookieCleanupTime = now
        val emptyDomains = mutableListOf<String>()
        cookieCache.forEach { (domain, cookies) ->
            cookies.removeAll { !isCookieActive(it) }
            if (cookies.isEmpty()) {
                emptyDomains += domain
            }
        }
        emptyDomains.forEach(cookieCache::remove)
    }

    private fun isCookieActive(cookie: Cookie): Boolean {
        return cookie.value.isNotBlank() && cookie.expiresAt > System.currentTimeMillis()
    }

    private val knownCookieUrls = listOf(
        "https://www.bilibili.com/",
        "https://api.bilibili.com/",
        "https://passport.bilibili.com/",
        "https://live.bilibili.com/"
    )
}
