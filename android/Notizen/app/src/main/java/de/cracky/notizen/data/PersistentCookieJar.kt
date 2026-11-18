package de.cracky.notizen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Persists the backend's HttpOnly session cookie encrypted with AndroidX Security. */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "notizen_session_cookies",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val editor = prefs.edit()
        cookies.forEach { cookie ->
            val key = cookieKey(cookie)
            editor.putString(key, cookieJson(cookie).toString())
        }
        editor.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val valid = mutableListOf<Cookie>()
        val remove = mutableListOf<String>()
        prefs.all.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val cookie = runCatching { cookieFromJson(JsonParser.parseString(raw).asJsonObject) }.getOrNull()
            if (cookie == null || (cookie.persistent && cookie.expiresAt < System.currentTimeMillis())) {
                remove += key
            } else if (cookie.matches(url)) {
                valid += cookie
            }
        }
        if (remove.isNotEmpty()) prefs.edit().also { edit -> remove.forEach { edit.remove(it) } }.apply()
        return valid
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun cookieKey(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}"

    private fun cookieJson(cookie: Cookie) = JsonObject().apply {
        addProperty("name", cookie.name); addProperty("value", cookie.value)
        addProperty("domain", cookie.domain); addProperty("path", cookie.path)
        addProperty("expiresAt", cookie.expiresAt); addProperty("secure", cookie.secure)
        addProperty("httpOnly", cookie.httpOnly); addProperty("hostOnly", cookie.hostOnly)
        addProperty("persistent", cookie.persistent)
    }

    private fun cookieFromJson(json: JsonObject): Cookie {
        val builder = Cookie.Builder()
            .name(json.get("name").asString)
            .value(json.get("value").asString)
            .path(json.get("path").asString)
        val domain = json.get("domain").asString
        if (json.get("hostOnly").asBoolean) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (json.get("persistent").asBoolean) builder.expiresAt(json.get("expiresAt").asLong)
        if (json.get("secure").asBoolean) builder.secure()
        if (json.get("httpOnly").asBoolean) builder.httpOnly()
        return builder.build()
    }
}
