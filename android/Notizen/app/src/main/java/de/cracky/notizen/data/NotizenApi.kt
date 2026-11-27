package de.cracky.notizen.data

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, override val message: String) : IOException(message)

class NotizenApi(context: Context) {
    private val prefs = context.getSharedPreferences("notizen_app", Context.MODE_PRIVATE)
    val cookies = PersistentCookieJar(context)

    /** Stabil über Neustarts, damit der Server erneut gesendete Befehle (gleiche cid) als Duplikat erkennt. */
    val clientId: String = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(24).also {
        prefs.edit().putString("client_id", it).apply()
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val wsClient: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    val baseUrl: String
        get() = prefs.getString("server_url", DEFAULT_URL) ?: DEFAULT_URL

    fun setBaseUrl(input: String) {
        val trimmed = input.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val normalized = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Server-Adresse muss mit http:// oder https:// beginnen.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IllegalArgumentException("Bitte keine Zugangsdaten in die Server-Adresse eintragen.")
        }
        prefs.edit().putString("server_url", parsed.toString()).apply()
    }

    fun url(path: String): String = resolve(path)?.toString() ?: path

    fun request(method: String, path: String, body: JsonElement? = null): JsonElement {
        val payload = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" || method == "PUT" || method == "PATCH" -> "{}".toRequestBody(JSON)
            else -> null
        }
        return execute(method, path, payload)
    }

    fun requestRaw(method: String, path: String, body: RequestBody): JsonElement = execute(method, path, body)

    private fun execute(method: String, path: String, payload: RequestBody?): JsonElement {
        val url = resolve(path) ?: throw IOException("Ungültige Server-Adresse")
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method in WRITE_METHODS) {
            builder.header("X-Requested-With", "notizen")
            builder.header("X-Client-Id", clientId)
        }
        builder.method(method, payload)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text, response.code))
            return if (text.isBlank()) JsonObject() else runCatching { JsonParser.parseString(text) }.getOrElse { JsonObject() }
        }
    }

    /** Lädt eine Datei (z. B. PDF) in [target]. */
    fun download(path: String, target: File) {
        val url = resolve(path) ?: throw IOException("Ungültige Server-Adresse")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "Download fehlgeschlagen (${response.code})")
            val body = response.body ?: throw IOException("Leere Antwort")
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    fun openWebSocket(path: String = "ws", listener: WebSocketListener): WebSocket {
        val base = resolve(path) ?: throw IOException("Ungültige Server-Adresse")
        val wsUrl = base.newBuilder().addQueryParameter("client", clientId).build()
        return wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    private fun resolve(path: String): HttpUrl? = baseUrl.toHttpUrlOrNull()?.resolve(path)

    private fun errorMessage(text: String, code: Int): String = runCatching {
        JsonParser.parseString(text).asJsonObject.get("detail")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: when (code) {
        401 -> "Nicht angemeldet"
        413 -> "Die Datei ist zu groß."
        else -> "Serverfehler ($code)"
    }

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8000/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
