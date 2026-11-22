package com.example.commonsneedsphoto.data.network

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the Wikimedia Commons session cookies between app launches.
 *
 * The app never stores the bot password: after a successful login MediaWiki
 * sets session cookies, and those are what we persist (the file is excluded
 * from Android backups).
 */
class CommonsSession(context: Context) : CookieJar {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = ConcurrentHashMap<String, StoredCookie>()

    init {
        load()
    }

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value).apply()
        }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        var changed = false
        for (cookie in cookies) {
            val key = "${cookie.name}|${cookie.domain}|${cookie.path}"
            val stored = StoredCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookie.domain,
                path = cookie.path,
                expiresAt = cookie.expiresAt,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly,
            )
            if (this.cookies.put(key, stored) != stored) {
                changed = true
            }
        }
        if (changed) persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val valid = ArrayList<Cookie>()
        val expiredKeys = ArrayList<String>()
        for ((key, stored) in cookies) {
            if (stored.expiresAt in 1 until now) {
                expiredKeys += key
                continue
            }
            val cookie = stored.toCookie() ?: continue
            if (cookie.matches(url)) valid += cookie
        }
        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach(cookies::remove)
            persist()
        }
        return valid
    }

    fun clear() {
        cookies.clear()
        prefs.edit().clear().apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return
        runCatching {
            json.decodeFromString<List<StoredCookie>>(raw).forEach { stored ->
                cookies["${stored.name}|${stored.domain}|${stored.path}"] = stored
            }
        }
    }

    private fun persist() {
        val snapshot = cookies.values.toList()
        prefs.edit().putString(KEY_COOKIES, json.encodeToString(snapshot)).apply()
    }

    private fun StoredCookie.toCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                path(path)
                if (expiresAt > 0) expiresAt(expiresAt)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    )

    private companion object {
        const val PREFS_NAME = "commons_session"
        const val KEY_COOKIES = "cookies"
        const val KEY_USER_NAME = "user_name"
    }
}
