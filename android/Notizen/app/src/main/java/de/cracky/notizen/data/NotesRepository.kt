package de.cracky.notizen.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.cracky.notizen.model.CreatedMagicLink
import de.cracky.notizen.model.IndexStatus
import de.cracky.notizen.model.LabelData
import de.cracky.notizen.model.MagicLinkInfo
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.NoteDoc
import de.cracky.notizen.model.SessionInfo
import de.cracky.notizen.model.Usage
import de.cracky.notizen.model.UserData
import de.cracky.notizen.model.UserInfo
import de.cracky.notizen.model.VersionInfo
import de.cracky.notizen.model.arr
import de.cracky.notizen.model.bool
import de.cracky.notizen.model.int
import de.cracky.notizen.model.intOrNull
import de.cracky.notizen.model.longOrNull
import de.cracky.notizen.model.obj
import de.cracky.notizen.model.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import java.io.File
import java.net.URLEncoder

/** Alle Serverzugriffe und der verschlüsselte lokale Cache. */
class NotesRepository(context: Context) {
    val api = NotizenApi(context)
    val realtime = Realtime(api)
    val cache = LocalNoteCache(context)
    private val generationPrefs = context.getSharedPreferences("notizen_cache_generation", Context.MODE_PRIVATE)

    fun setServerUrl(url: String) = api.setBaseUrl(url)

    // ------------------------------------------------------------------ Konto
    suspend fun login(username: String, password: String): UserData = io {
        UserData.fromJson(api.request("POST", "api/login", JsonObject().apply {
            addProperty("username", username.trim()); addProperty("password", password)
        }).asJsonObject)
    }

    suspend fun redeemMagicLink(token: String): UserData = io {
        UserData.fromJson(api.request("POST", "api/magic-links/redeem", JsonObject().apply { addProperty("token", token) }).asJsonObject)
    }

    suspend fun me(): UserData = io { UserData.fromJson(api.request("GET", "api/me").asJsonObject) }

    /** Gibt true zurück, wenn der lokale Cache wegen eines Admin-Resets verworfen wurde. */
    suspend fun reconcileCacheGeneration(userId: Int, generation: Int): Boolean {
        val key = "user_$userId"
        val known = generationPrefs.getInt(key, Int.MIN_VALUE)
        generationPrefs.edit().putInt(key, generation).apply()
        if (known != Int.MIN_VALUE && known != generation) {
            cache.clearUser(userId)
            return true
        }
        return false
    }

    suspend fun saveSettings(settings: JsonObject) = io {
        api.request("PUT", "api/me/settings", JsonObject().apply { add("settings", settings) })
    }

    suspend fun changePassword(old: String, new: String) = io {
        api.request("POST", "api/password", JsonObject().apply { addProperty("old_password", old); addProperty("new_password", new) })
    }

    suspend fun uploadAvatar(body: RequestBody): UserInfo = io {
        UserInfo.fromJson(api.requestRaw("PUT", "api/me/avatar", body).asJsonObject)
    }

    suspend fun deleteAvatar() = io { api.request("DELETE", "api/me/avatar") }

    suspend fun usage(): Usage = io {
        val o = api.request("GET", "api/me/usage").asJsonObject
        Usage(o.longOrNull("used_bytes") ?: 0L, o.longOrNull("limit_bytes"))
    }

    suspend fun indexStatus(): IndexStatus = io { parseIndex(api.request("GET", "api/me/search-index").asJsonObject) }
    suspend fun rebuildIndex(): IndexStatus = io { parseIndex(api.request("POST", "api/me/search-index/rebuild").asJsonObject) }

    private fun parseIndex(o: JsonObject) = IndexStatus(
        state = o.str("state", "ready"), done = o.int("done"), total = o.int("total"),
        ocrPending = o.int("ocr_pending"), ocrAvailable = o.bool("ocr_available", true),
        error = o.str("error").ifBlank { null }
    )

    suspend fun sessions(): List<SessionInfo> = io {
        api.request("GET", "api/me/sessions").asJsonArray.mapNotNull { e ->
            e.obj()?.let {
                SessionInfo(it.str("id"), it.longOrNull("created_at") ?: 0, it.longOrNull("expires_at") ?: 0,
                    it.longOrNull("last_seen"), it.str("user_agent"), it.bool("current"))
            }
        }
    }

    suspend fun revokeSession(id: String) = io { api.request("DELETE", "api/me/sessions/${enc(id)}") }

    suspend fun magicLinks(): List<MagicLinkInfo> = io {
        api.request("GET", "api/magic-links").asJsonArray.mapNotNull { e ->
            e.obj()?.let {
                MagicLinkInfo(it.int("id"), it.str("label"), it.longOrNull("created_at") ?: 0,
                    it.longOrNull("expires_at") ?: 0, it.longOrNull("last_used_at"))
            }
        }
    }

    suspend fun createMagicLink(label: String, days: Int): CreatedMagicLink = io {
        val o = api.request("POST", "api/magic-links", JsonObject().apply { addProperty("label", label); addProperty("days", days) }).asJsonObject
        CreatedMagicLink(o.int("id"), o.str("url"), o.longOrNull("expires_at") ?: 0)
    }

    suspend fun deleteMagicLink(id: Int) = io { api.request("DELETE", "api/magic-links/$id") }

    suspend fun approveDeviceLogin(challenge: String) = io {
        api.request("POST", "api/device-login/approve", JsonObject().apply { addProperty("challenge", challenge) })
    }

    suspend fun logout() {
        runCatching { io { api.request("POST", "api/logout", JsonObject()) } }
        realtime.stop()
        api.cookies.clear()
        cache.clearAll()
    }

    suspend fun users(userId: Int): List<UserInfo> = io {
        val arr = api.request("GET", "api/users").asJsonArray
        cache.saveUsers(userId, arr)
        arr.mapNotNull { e -> e.obj()?.let { UserInfo.fromJson(it) } }
    }

    // ------------------------------------------------------------------ Notizen
    suspend fun fetchNotes(userId: Int, onPage: (List<NoteData>) -> Unit = {}): List<NoteData> = io {
        val collected = mutableListOf<NoteData>()
        var offset = 0
        while (collected.size < 20000) {
            val response = api.request("GET", "api/notes?offset=$offset&limit=200").asJsonObject
            val page = response.arr("notes").mapNotNull { e -> e.obj()?.let { NoteData.fromJson(it) } }
            collected += page
            onPage(collected.toList())
            val next = response.intOrNull("next_offset")
            if (next == null || next <= offset) break
            offset = next
        }
        cache.replaceSnapshot(userId, collected)
        collected
    }

    suspend fun fetchNote(userId: Int, id: Int): NoteData {
        val note = io { NoteData.fromJson(api.request("GET", "api/notes/$id").asJsonObject) }
        cache.saveNotes(userId, listOf(note))
        return note
    }

    suspend fun createNote(doc: NoteDoc, color: String, pinned: Boolean, archived: Boolean, labels: List<Int>): NoteData = io {
        val request = doc.toJson().apply {
            addProperty("color", color)
            addProperty("pinned", pinned)
            addProperty("archived", archived)
            add("labels", JsonArray().also { a -> labels.forEach { a.add(it) } })
            add("collaborators", JsonArray())
        }
        NoteData.fromJson(api.request("POST", "api/notes", request).asJsonObject)
    }

    suspend fun patchNote(id: Int, patch: JsonObject): NoteData = io {
        NoteData.fromJson(api.request("PATCH", "api/notes/$id", patch).asJsonObject)
    }

    suspend fun deleteForever(id: Int) = io { api.request("DELETE", "api/notes/$id") }
    suspend fun leaveNote(id: Int) = io { api.request("POST", "api/notes/$id/leave") }
    suspend fun copyNote(id: Int): NoteData = io { NoteData.fromJson(api.request("POST", "api/notes/$id/copy").asJsonObject) }
    suspend fun emptyTrash() = io { api.request("DELETE", "api/trash") }

    suspend fun setCollaborators(id: Int, userIds: List<Int>): NoteData? = io {
        val o = api.request("PUT", "api/notes/$id/collaborators", JsonObject().apply {
            add("user_ids", JsonArray().also { a -> userIds.forEach { a.add(it) } })
        }).asJsonObject
        if (o.bool("left")) null else NoteData.fromJson(o)
    }

    suspend fun reorder(ids: List<Int>): Map<Int, Double> = io {
        val o = api.request("POST", "api/notes/reorder", JsonObject().apply {
            add("ids", JsonArray().also { a -> ids.forEach { a.add(it) } })
        }).asJsonObject
        positionsFrom(o.get("positions"))
    }

    fun positionsFrom(el: JsonElement?): Map<Int, Double> {
        val obj = el?.obj() ?: return emptyMap()
        return obj.entrySet().mapNotNull { (k, v) -> k.toIntOrNull()?.let { id -> runCatching { id to v.asDouble }.getOrNull() } }.toMap()
    }

    suspend fun search(query: String): Pair<Boolean, List<Int>> = io {
        val o = api.request("POST", "api/search", JsonObject().apply { addProperty("q", query) }).asJsonObject
        o.bool("ready") to o.arr("ids").mapNotNull { runCatching { it.asInt }.getOrNull() }
    }

    // ------------------------------------------------------------------ Anhänge
    suspend fun uploadImage(noteId: Int, body: RequestBody) = io { api.requestRaw("POST", "api/notes/$noteId/images", body) }
    suspend fun deleteImage(noteId: Int, imageId: Int) = io { api.request("DELETE", "api/notes/$noteId/images/$imageId") }
    suspend fun uploadPdf(noteId: Int, name: String, body: RequestBody) =
        io { api.requestRaw("POST", "api/notes/$noteId/pdfs?name=${enc(name)}", body) }
    suspend fun deletePdf(noteId: Int, pdfId: Int) = io { api.request("DELETE", "api/notes/$noteId/pdfs/$pdfId") }
    suspend fun downloadPdf(pdfId: Int, target: File) = io { api.download("api/pdfs/$pdfId", target) }
    suspend fun pdfPageCount(pdfId: Int): Int = io { api.request("GET", "api/pdfs/$pdfId/info").asJsonObject.int("pages") }

    fun imageUrl(id: Int) = api.url("api/images/$id")
    fun thumbUrl(id: Int) = api.url("api/images/$id/thumb")
    fun pdfThumbUrl(id: Int) = api.url("api/pdfs/$id/thumb")
    fun pdfPageUrl(id: Int, page: Int, width: Int) = api.url("api/pdfs/$id/pages/$page?w=$width")
    fun avatarUrl(userId: Int, avatar: Int) = api.url("api/avatars/$userId?v=$avatar")

    // ------------------------------------------------------------------ Versionen
    suspend fun versions(noteId: Int): List<VersionInfo> = io {
        api.request("GET", "api/notes/$noteId/versions").asJsonArray.mapNotNull { e ->
            e.obj()?.let { o ->
                VersionInfo(o.int("id"), o.longOrNull("created_at") ?: 0, o.longOrNull("updated_at") ?: 0,
                    o.arr("editors").mapNotNull { u -> u.obj()?.let { UserInfo.fromJson(it) } })
            }
        }
    }

    suspend fun version(noteId: Int, versionId: Int): NoteDoc = io {
        NoteDoc.fromJson(api.request("GET", "api/notes/$noteId/versions/$versionId").asJsonObject)
    }

    suspend fun restoreVersion(noteId: Int, versionId: Int) = io {
        api.request("POST", "api/notes/$noteId/versions/$versionId/restore")
    }

    // ------------------------------------------------------------------ Labels
    suspend fun labels(userId: Int): List<LabelData> = io {
        val arr = api.request("GET", "api/labels").asJsonArray
        cache.saveLabels(userId, arr)
        arr.mapNotNull { e -> e.obj()?.let { LabelData.fromJson(it) } }
    }

    suspend fun createLabel(name: String): LabelData = io {
        LabelData.fromJson(api.request("POST", "api/labels", JsonObject().apply { addProperty("name", name) }).asJsonObject)
    }

    suspend fun renameLabel(id: Int, name: String): LabelData = io {
        LabelData.fromJson(api.request("PATCH", "api/labels/$id", JsonObject().apply { addProperty("name", name) }).asJsonObject)
    }

    suspend fun deleteLabel(id: Int) = io { api.request("DELETE", "api/labels/$id") }
    suspend fun leaveLabel(id: Int) = io { api.request("POST", "api/labels/$id/leave") }

    suspend fun setLabelMembers(id: Int, members: List<Pair<Int, Boolean>>): LabelData = io {
        LabelData.fromJson(api.request("PUT", "api/labels/$id/members", JsonObject().apply {
            add("members", JsonArray().also { a ->
                members.forEach { (uid, canEdit) -> a.add(JsonObject().apply { addProperty("id", uid); addProperty("can_edit", canEdit) }) }
            })
        }).asJsonObject)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
