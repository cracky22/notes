package de.cracky.notizen.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.NoteItem
import de.cracky.notizen.model.UserData
import de.cracky.notizen.model.toJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import kotlin.math.max

class NotesRepository(context: Context) {
    val api = NotizenApi(context)
    private val cache = LocalNoteCache(context)
    private val generationPrefs = context.getSharedPreferences("notizen_cache_generation", Context.MODE_PRIVATE)
    @Volatile private var activeUserId: Int? = null

    fun setServerUrl(url: String) = api.setBaseUrl(url)

    suspend fun login(username: String, password: String): UserData = io {
        val result = api.request("POST", "api/login", JsonObject().apply {
            addProperty("username", username.trim()); addProperty("password", password)
        }).asJsonObject
        UserData.fromJson(result).also { activeUserId = it.id }
    }

    suspend fun me(): UserData = io { UserData.fromJson(api.request("GET", "api/me").asJsonObject).also { activeUserId = it.id } }

    suspend fun reconcileCacheGeneration(user: UserData) {
        val key = "user_${user.id}"
        val known = generationPrefs.getInt(key, Int.MIN_VALUE)
        if (known != Int.MIN_VALUE && known != user.cacheGeneration) cache.clearUser(user.id)
        generationPrefs.edit().putInt(key, user.cacheGeneration).apply()
    }

    suspend fun fetchNotes(userId: Int): List<NoteData> = io {
        val collected = mutableListOf<NoteData>()
        var offset = 0
        do {
            val response = api.request("GET", "api/notes?offset=$offset&limit=200").asJsonObject
            response.getAsJsonArray("notes")?.forEach { if (it.isJsonObject) collected += NoteData.fromJson(it.asJsonObject) }
            val next = response.get("next_offset")?.takeUnless { it.isJsonNull }?.asInt
            if (next == null || next <= offset) break
            offset = next
        } while (collected.size < 5000)
        cache.replaceSnapshot(userId, collected)
        collected
    }

    suspend fun cachedNotes(userId: Int) = cache.load(userId)

    suspend fun fetchNote(id: Int): NoteData {
        val note = io { NoteData.fromJson(api.request("GET", "api/notes/$id").asJsonObject) }
        activeUserId?.let { cache.saveOne(it, note) }
        return note
    }

    suspend fun saveNew(title: String, body: String, isList: Boolean, items: List<NoteItem>): NoteData = io {
        val bodyText = body.withTerminalNewline()
        val request = JsonObject().apply {
            addProperty("title", title)
            add("body", JsonArray().apply { add(JsonObject().apply { addProperty("insert", bodyText) }) })
            add("items", JsonArray().also { a -> items.forEach { a.add(it.toJson()) } })
            addProperty("is_list", isList)
            addProperty("color", "default")
            addProperty("pinned", false)
            addProperty("archived", false)
            add("labels", JsonArray())
            add("collaborators", JsonArray())
        }
        NoteData.fromJson(api.request("POST", "api/notes", request).asJsonObject)
    }

    suspend fun saveExisting(base: NoteData, title: String, body: String, isList: Boolean, items: List<NoteItem>): NoteData {
        val change = buildChange(base, title, body, isList, items)
        if (change.size() > 0) pushOperation(base.id, base.rev, change)
        return fetchNote(base.id)
    }

    suspend fun setNoteMeta(id: Int, field: String, value: Boolean): NoteData = io {
        val payload = JsonObject().apply { addProperty(field, value) }
        NoteData.fromJson(api.request("PATCH", "api/notes/$id", payload).asJsonObject)
    }

    suspend fun moveToTrash(id: Int): NoteData = io {
        NoteData.fromJson(api.request("PATCH", "api/notes/$id", JsonObject().apply { addProperty("trashed", true) }).asJsonObject)
    }

    suspend fun deleteForever(id: Int) = io { api.request("DELETE", "api/notes/$id") }

    suspend fun search(query: String): Pair<Boolean, List<Int>> = io {
        val response = api.request("POST", "api/search", JsonObject().apply { addProperty("q", query) }).asJsonObject
        val ready = response.get("ready")?.asBoolean ?: false
        val ids = response.getAsJsonArray("ids")?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty()
        ready to ids
    }

    suspend fun approveDeviceLogin(challenge: String) = io {
        api.request("POST", "api/device-login/approve", JsonObject().apply { addProperty("challenge", challenge) })
    }

    suspend fun logout() {
        runCatching { io { api.request("POST", "api/logout", JsonObject()) } }
        api.cookies.clear()
        api.cancelWebSockets()
        cache.clearAll()
        activeUserId = null
    }

    private suspend fun pushOperation(noteId: Int, revision: Int, change: JsonArray) = withContext(Dispatchers.IO) {
        val cid = UUID.randomUUID().toString().replace("-", "").take(24)
        val messages = Channel<JsonObject>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()
        val socketRef = arrayOfNulls<WebSocket>(1)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef[0] = webSocket
                webSocket.send(JsonObject().apply { addProperty("t", "open"); addProperty("id", noteId) }.toString())
                webSocket.send(JsonObject().apply {
                    addProperty("t", "op"); addProperty("id", noteId); addProperty("rev", revision)
                    addProperty("cid", cid); add("change", change)
                }.toString())
                ready.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()?.let { messages.trySend(it) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = onMessage(webSocket, bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) ready.completeExceptionally(t)
                messages.close(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        }
        val socket = api.openWebSocket(listener = listener)
        socketRef[0] = socket
        try {
            withTimeout(20_000) {
                ready.await()
                while (true) {
                    val message = messages.receive()
                    when (message.get("t")?.asString) {
                        "ack" -> if (message.get("cid")?.asString == cid) return@withTimeout
                        "resync" -> throw ApiException(409, "Die Notiz wurde inzwischen geaendert. Bitte erneut oeffnen und speichern.")
                        "error" -> throw ApiException(400, message.get("message")?.asString ?: "Aenderung abgelehnt")
                        "gone" -> throw ApiException(404, "Notiz ist nicht mehr verfuegbar.")
                    }
                }
            }
        } finally {
            socketRef[0]?.close(1000, "save complete")
            messages.close()
        }
    }

    private fun buildChange(base: NoteData, title: String, body: String, isList: Boolean, items: List<NoteItem>): JsonArray {
        val change = JsonArray()
        val titleChange = textChange(base.title, title, "title")
        if (titleChange != null) change.add(titleChange)
        val oldBody = base.bodyRaw.ifEmpty { "\n" }
        val newBody = body.withTerminalNewline()
        val bodyChange = textChange(oldBody, newBody, "body", isBody = true)
        if (bodyChange != null) change.add(bodyChange)
        if (isList != base.isList) change.add(JsonObject().apply { addProperty("k", "set"); addProperty("is_list", isList) })

        val oldById = base.items.associateBy { it.id }
        val newById = items.associateBy { it.id }
        oldById.values.filter { it.id !in newById }.forEach { old ->
            change.add(JsonObject().apply { addProperty("k", "item_del"); addProperty("id", old.id) })
        }
        items.forEachIndexed { index, item ->
            val old = oldById[item.id]
            if (old == null) {
                change.add(JsonObject().apply {
                    addProperty("k", "item_add"); addProperty("id", item.id)
                    addProperty("pos", item.pos.takeIf { it > 0 } ?: (index + 1).toDouble())
                    addProperty("checked", item.checked); addProperty("text", item.text)
                })
            } else {
                if (old.text != item.text) textChange(old.text, item.text, "item:${item.id}")?.let(change::add)
                if (old.checked != item.checked || old.pos != item.pos) {
                    change.add(JsonObject().apply {
                        addProperty("k", "item_set"); addProperty("id", item.id)
                        addProperty("checked", item.checked); addProperty("pos", item.pos)
                    })
                }
            }
        }
        return change
    }

    private fun textChange(old: String, new: String, field: String, isBody: Boolean = false): JsonObject? {
        if (old == new) return null
        val delta = JsonArray()
        val oldLength = old.length // Kotlin String.length counts UTF-16 code units, as this backend does.
        if (oldLength > 0) delta.add(JsonObject().apply { addProperty("delete", oldLength) })
        if (new.isNotEmpty()) delta.add(JsonObject().apply { addProperty("insert", new) })
        return JsonObject().apply {
            addProperty("k", "text"); addProperty("f", field); add("d", delta)
        }
    }

    private fun String.withTerminalNewline() = if (endsWith("\n")) this else "$this\n"

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
