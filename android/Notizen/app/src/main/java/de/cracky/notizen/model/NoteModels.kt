package de.cracky.notizen.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class ImageRef(val id: Int, val w: Int, val h: Int)
data class PdfRef(val id: Int, val name: String, val size: Long, val pages: Int, val ocr: String)
data class Member(val id: Int, val username: String, val avatar: Int?, val role: String)

data class NoteData(
    val id: Int,
    val rev: Int,
    val doc: NoteDoc,
    val color: String,
    val pinned: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val trashedAt: Long?,
    val contentDeferred: Boolean,
    val labels: List<Int>,
    val ownerId: Int,
    val role: String,
    val position: Double,
    val images: List<ImageRef>,
    val pdfs: List<PdfRef>,
    val members: List<Member>,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: Int?
) {
    val isOwner: Boolean get() = role == "owner"
    val shared: Boolean get() = members.size > 1
    val hasAttachments: Boolean get() = images.isNotEmpty() || pdfs.isNotEmpty()
    val isEmpty: Boolean get() = doc.isBlank && !hasAttachments

    fun plainText(): String = buildString {
        append(doc.title).append('\n')
        if (doc.isList) doc.items.joinTo(this, "\n") { it.text } else append(doc.bodyText)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("rev", rev)
        addProperty("title", doc.title)
        add("body", doc.body.toJson())
        add("items", JsonArray().also { a -> doc.items.forEach { a.add(it.toJson()) } })
        addProperty("is_list", doc.isList)
        addProperty("color", color)
        addProperty("pinned", pinned)
        addProperty("archived", archived)
        addProperty("trashed", trashed)
        trashedAt?.let { addProperty("trashed_at", it) }
        addProperty("content_deferred", contentDeferred)
        add("labels", JsonArray().also { a -> labels.forEach { a.add(it) } })
        addProperty("owner_id", ownerId)
        addProperty("role", role)
        addProperty("position", position)
        add("images", JsonArray().also { a ->
            images.forEach { i -> a.add(JsonObject().apply { addProperty("id", i.id); addProperty("w", i.w); addProperty("h", i.h) }) }
        })
        add("pdfs", JsonArray().also { a ->
            pdfs.forEach { p ->
                a.add(JsonObject().apply {
                    addProperty("id", p.id); addProperty("name", p.name); addProperty("size", p.size)
                    addProperty("pages", p.pages); addProperty("ocr", p.ocr)
                })
            }
        })
        add("members", JsonArray().also { a ->
            members.forEach { m ->
                a.add(JsonObject().apply {
                    addProperty("id", m.id); addProperty("username", m.username)
                    m.avatar?.let { addProperty("avatar", it) }; addProperty("role", m.role)
                })
            }
        })
        addProperty("created_at", createdAt)
        addProperty("updated_at", updatedAt)
        updatedBy?.let { addProperty("updated_by", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): NoteData = NoteData(
            id = json.int("id"),
            rev = json.int("rev"),
            doc = NoteDoc.fromJson(json),
            color = json.str("color", "default").ifBlank { "default" },
            pinned = json.bool("pinned"),
            archived = json.bool("archived"),
            trashed = json.bool("trashed"),
            trashedAt = json.longOrNull("trashed_at"),
            contentDeferred = json.bool("content_deferred"),
            labels = json.arr("labels").mapNotNull { runCatching { it.asInt }.getOrNull() },
            ownerId = json.int("owner_id"),
            role = json.str("role", "owner"),
            position = runCatching { json.get("position").asDouble }.getOrDefault(0.0),
            images = json.arr("images").mapNotNull { e ->
                e.obj()?.let { ImageRef(it.int("id"), it.int("w"), it.int("h")) }
            },
            pdfs = json.arr("pdfs").mapNotNull { e ->
                e.obj()?.let {
                    PdfRef(it.int("id"), it.str("name", "Dokument.pdf"), it.longOrNull("size") ?: 0L,
                        it.int("pages"), it.str("ocr", "pending"))
                }
            },
            members = json.arr("members").mapNotNull { e ->
                e.obj()?.let { Member(it.int("id"), it.str("username"), it.intOrNull("avatar"), it.str("role", "editor")) }
            },
            createdAt = json.longOrNull("created_at") ?: 0L,
            updatedAt = json.longOrNull("updated_at") ?: 0L,
            updatedBy = json.intOrNull("updated_by")
        )
    }
}

data class UserData(
    val id: Int,
    val username: String,
    val avatar: Int?,
    val isAdmin: Boolean,
    val cacheGeneration: Int,
    val settings: JsonObject
) {
    fun toJson() = JsonObject().apply {
        addProperty("id", id); addProperty("username", username)
        avatar?.let { addProperty("avatar", it) }
        addProperty("is_admin", isAdmin); addProperty("cache_generation", cacheGeneration)
        add("settings", settings.deepCopy())
    }

    companion object {
        fun fromJson(json: JsonObject) = UserData(
            id = json.int("id"), username = json.str("username"),
            avatar = json.intOrNull("avatar"),
            isAdmin = json.bool("is_admin"),
            cacheGeneration = json.int("cache_generation"),
            settings = json.getAsJsonObject("settings")?.deepCopy() ?: JsonObject()
        )
    }
}

data class UserInfo(val id: Int, val username: String, val avatar: Int?) {
    companion object {
        fun fromJson(json: JsonObject) = UserInfo(json.int("id"), json.str("username"), json.intOrNull("avatar"))
    }
}

data class LabelMember(val id: Int, val username: String, val avatar: Int?, val canEdit: Boolean)

data class LabelData(
    val id: Int,
    val name: String,
    val ownerId: Int,
    val owner: UserInfo?,
    val role: String,
    val canEdit: Boolean,
    val members: List<LabelMember>,
    val shared: Boolean
) {
    val isOwn: Boolean get() = role == "owner"

    companion object {
        fun fromJson(json: JsonObject) = LabelData(
            id = json.int("id"), name = json.str("name"), ownerId = json.int("owner_id"),
            owner = json.get("owner")?.obj()?.let { UserInfo.fromJson(it) },
            role = json.str("role", "owner"), canEdit = json.bool("can_edit", true),
            members = json.arr("members").mapNotNull { e ->
                e.obj()?.let { LabelMember(it.int("id"), it.str("username"), it.intOrNull("avatar"), it.bool("can_edit", true)) }
            },
            shared = json.bool("shared")
        )
    }
}

data class SessionInfo(val id: String, val createdAt: Long, val expiresAt: Long, val lastSeen: Long?, val userAgent: String, val current: Boolean)
data class MagicLinkInfo(val id: Int, val label: String, val createdAt: Long, val expiresAt: Long, val lastUsedAt: Long?)
data class CreatedMagicLink(val id: Int, val url: String, val expiresAt: Long)
data class VersionInfo(val id: Int, val createdAt: Long, val updatedAt: Long, val editors: List<UserInfo>)
data class Usage(val used: Long, val limit: Long?)
data class IndexStatus(val state: String, val done: Int, val total: Int, val ocrPending: Int, val ocrAvailable: Boolean, val error: String?)

// ---------------------------------------------------------------------------
// JSON-Hilfen
// ---------------------------------------------------------------------------
internal fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
internal fun JsonObject.arr(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
internal fun JsonObject.int(key: String, fallback: Int = 0): Int =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asInt }.getOrNull() ?: fallback
internal fun JsonObject.intOrNull(key: String): Int? =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asInt }.getOrNull()
internal fun JsonObject.longOrNull(key: String): Long? =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()
internal fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asBoolean }.getOrNull() ?: fallback
