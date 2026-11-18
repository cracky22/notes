package de.cracky.notizen.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

 data class NoteItem(
    val id: String,
    val pos: Double,
    val checked: Boolean,
    val text: String
)

data class NoteData(
    val id: Int,
    val rev: Int,
    val title: String,
    val body: JsonArray,
    val items: List<NoteItem>,
    val isList: Boolean,
    val color: String,
    val pinned: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val contentDeferred: Boolean,
    val labels: List<Int>,
    val ownerId: Int,
    val role: String,
    val updatedAt: Long
) {
    val bodyRaw: String get() = buildString {
        body.forEach { op ->
            if (op.isJsonObject) append(op.asJsonObject.get("insert")?.asString.orEmpty())
        }
    }
    val bodyForEditor: String get() = bodyRaw.removeSuffix("\n")

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("rev", rev)
        addProperty("title", title)
        add("body", body.deepCopy())
        add("items", JsonArray().also { array -> items.forEach { array.add(it.toJson()) } })
        addProperty("is_list", isList)
        addProperty("color", color)
        addProperty("pinned", pinned)
        addProperty("archived", archived)
        addProperty("trashed", trashed)
        addProperty("content_deferred", contentDeferred)
        add("labels", JsonArray().also { a -> labels.forEach { a.add(it) } })
        addProperty("owner_id", ownerId)
        addProperty("role", role)
        addProperty("updated_at", updatedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): NoteData {
            val body = json.getAsJsonArray("body")?.deepCopy() ?: JsonArray().apply {
                add(JsonObject().apply { addProperty("insert", "\n") })
            }
            val items = json.getAsJsonArray("items")?.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                NoteItem(
                    id = obj.string("id"),
                    pos = obj.number("pos").toDouble(),
                    checked = obj.bool("checked"),
                    text = obj.string("text")
                )
            }.orEmpty()
            return NoteData(
                id = json.int("id"), rev = json.int("rev"), title = json.string("title"),
                body = body, items = items, isList = json.bool("is_list"),
                color = json.string("color", "default"), pinned = json.bool("pinned"),
                archived = json.bool("archived"), trashed = json.bool("trashed"),
                contentDeferred = json.bool("content_deferred"),
                labels = json.getAsJsonArray("labels")?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty(),
                ownerId = json.int("owner_id"), role = json.string("role"), updatedAt = json.long("updated_at")
            )
        }

        fun empty(isList: Boolean = false) = NoteData(
            id = 0, rev = 0, title = "", body = JsonArray().apply {
                add(JsonObject().apply { addProperty("insert", "\n") })
            }, items = emptyList(), isList = isList, color = "default", pinned = false,
            archived = false, trashed = false, contentDeferred = false, labels = emptyList(),
            ownerId = 0, role = "owner", updatedAt = 0
        )
    }
}

data class UserData(
    val id: Int,
    val username: String,
    val avatar: Int?,
    val cacheGeneration: Int,
    val settings: JsonObject
) {
    companion object {
        fun fromJson(json: JsonObject) = UserData(
            id = json.int("id"), username = json.string("username"),
            avatar = json.get("avatar")?.takeUnless { it.isJsonNull }?.asInt,
            cacheGeneration = json.int("cache_generation"),
            settings = json.getAsJsonObject("settings") ?: JsonObject()
        )
    }
}

fun NoteItem.toJson() = JsonObject().apply {
    addProperty("id", id); addProperty("pos", pos); addProperty("checked", checked); addProperty("text", text)
}

fun makeItem(text: String = "", pos: Double = 1.0) = NoteItem(
    id = UUID.randomUUID().toString().replace("-", "").take(14), pos = pos, checked = false, text = text
)

private fun JsonObject.string(key: String, fallback: String = "") =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull() ?: fallback
private fun JsonObject.int(key: String, fallback: Int = 0) =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asInt }.getOrNull() ?: fallback
private fun JsonObject.long(key: String, fallback: Long = 0L) =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull() ?: fallback
private fun JsonObject.number(key: String, fallback: Number = 0) =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asNumber }.getOrNull() ?: fallback
private fun JsonObject.bool(key: String, fallback: Boolean = false) =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asBoolean }.getOrNull() ?: fallback
