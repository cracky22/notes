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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, override val message: String) : IOException(message)

class NotizenApi(context: Context) {
    private val prefs = context.getSharedPreferences("notizen_app", Context.MODE_PRIVATE)
    val cookies = PersistentCookieJar(context)
    val clientId: String = UUID.randomUUID().toString().replace("-", "").take(24)
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val baseUrl: String
        get() = prefs.getString("server_url", "http://10.0.2.2:8000/") ?: "http://10.0.2.2:8000/"

    fun setBaseUrl(input: String) {
        val normalized = input.trim().let { if (it.endsWith("/")) it else "$it/" }
        val parsed = normalized.toHttpUrlOrNullCompat()
            ?: throw IllegalArgumentException("Server-Adresse muss mit http:// oder https:// beginnen.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IllegalArgumentException("Bitte keine Zugangsdaten in die Server-Adresse eintragen.")
        }
        prefs.edit().putString("server_url", parsed.toString()).apply()
    }

    fun request(method: String, path: String, body: JsonElement? = null): JsonElement {
        val url = resolve(path) ?: throw IOException("Ungueltige Server-Adresse")
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method in setOf("POST", "PUT", "PATCH", "DELETE")) {
            builder.header("X-Requested-With", "notizen")
            builder.header("X-Client-Id", clientId)
        }
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8")
                .method(method, body.toString().toRequestBody(JSON))
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            builder.method(method, "{}".toRequestBody(JSON))
        } else {
            builder.method(method, null)
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, errorMessage(text, response.code))
            return if (text.isBlank()) JsonObject() else runCatching { JsonParser.parseString(text) }
                .getOrElse { JsonObject() }
        }
    }

    fun openWebSocket(path: String = "ws", listener: WebSocketListener): WebSocket {
        val base = resolve(path) ?: throw IOException("Ungueltige Server-Adresse")
        val wsUrl = base.newBuilder().addQueryParameter("client", clientId).build()
        val request = Request.Builder().url(wsUrl).build()
        return http.newWebSocket(request, listener)
    }

    fun cancelWebSockets() { http.dispatcher.cancelAll() }

    private fun resolve(path: String): HttpUrl? = baseUrl.toHttpUrlOrNullCompat()?.resolve(path)

    private fun errorMessage(text: String, code: Int): String = runCatching {
        JsonParser.parseString(text).asJsonObject.get("detail")?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Serverfehler ($code)"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private fun String.toHttpUrlOrNullCompat(): HttpUrl? = this.toHttpUrlOrNull()
