package de.cracky.notizen.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.security.SecureRandom

/** Teil-Operationen einer Änderung – identisch zum Protokoll in ot.py. */
sealed class SubOp {
    data class Text(val f: String, val d: Delta) : SubOp()
    data class ItemAdd(val id: String, val pos: Double, val checked: Boolean, val text: String) : SubOp()
    data class ItemDel(val id: String) : SubOp()
    data class ItemSet(val id: String, val checked: Boolean? = null, val pos: Double? = null) : SubOp()
    data class SetList(val isList: Boolean) : SubOp()
}

typealias Change = List<SubOp>

data class DocItem(val id: String, val pos: Double, val checked: Boolean, val text: String)

/** Inhalt einer Notiz (der Teil, der per OT synchronisiert wird). */
data class NoteDoc(
    val title: String = "",
    val body: Delta = Delta.emptyBody(),
    val items: List<DocItem> = emptyList(),
    val isList: Boolean = false
) {
    /** Text ohne den abschließenden Zeilenumbruch – so wie er im Editor erscheint. */
    val bodyText: String by lazy { body.text().removeSuffix("\n") }

    val isBlank: Boolean get() = title.isBlank() && bodyText.isBlank() && items.all { it.text.isBlank() }

    fun apply(change: Change): NoteDoc {
        var title = title
        var body = body
        val items = items.toMutableList()
        var isList = isList
        for (sub in change) {
            when (sub) {
                is SubOp.Text -> when {
                    sub.f == "body" -> body = body.compose(sub.d)
                    sub.f == "title" -> title = Delta.plain(title).compose(sub.d).text()
                    sub.f.startsWith("item:") -> {
                        val i = items.indexOfFirst { it.id == sub.f.substring(5) }
                        if (i >= 0) items[i] = items[i].copy(text = Delta.plain(items[i].text).compose(sub.d).text())
                    }
                }
                is SubOp.ItemAdd -> if (items.none { it.id == sub.id }) items += DocItem(sub.id, sub.pos, sub.checked, sub.text)
                is SubOp.ItemDel -> items.removeAll { it.id == sub.id }
                is SubOp.ItemSet -> {
                    val i = items.indexOfFirst { it.id == sub.id }
                    if (i >= 0) items[i] = items[i].copy(
                        checked = sub.checked ?: items[i].checked,
                        pos = sub.pos ?: items[i].pos
                    )
                }
                is SubOp.SetList -> isList = sub.isList
            }
        }
        items.sortWith(compareBy<DocItem>({ it.pos }, { it.id }))
        return NoteDoc(title, body, items, isList)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("title", title)
        add("body", body.toJson())
        add("items", JsonArray().also { a -> items.forEach { a.add(it.toJson()) } })
        addProperty("is_list", isList)
    }

    companion object {
        fun fromJson(json: JsonObject): NoteDoc {
            val body = Delta.fromJson(json.get("body")).let { if (it.isEmpty()) Delta.emptyBody() else it }
            val items = json.getAsJsonArray("items")?.mapNotNull { e ->
                if (!e.isJsonObject) return@mapNotNull null
                val o = e.asJsonObject
                DocItem(
                    id = o.str("id"),
                    pos = runCatching { o.get("pos").asDouble }.getOrDefault(0.0),
                    checked = runCatching { o.get("checked").asBoolean }.getOrDefault(false),
                    text = o.str("text")
                )
            }.orEmpty().sortedWith(compareBy<DocItem>({ it.pos }, { it.id }))
            return NoteDoc(
                title = json.str("title"),
                body = body,
                items = items,
                isList = runCatching { json.get("is_list").asBoolean }.getOrDefault(false)
            )
        }
    }
}

fun DocItem.toJson() = JsonObject().apply {
    addProperty("id", id); addProperty("pos", pos); addProperty("checked", checked); addProperty("text", text)
}

private val rnd = SecureRandom()
private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/** Zufällige ID im Format, das der Server akzeptiert ([A-Za-z0-9_-]{1,40}). */
fun rid(length: Int = 12): String = buildString { repeat(length) { append(ID_CHARS[rnd.nextInt(ID_CHARS.length)]) } }

// ---------------------------------------------------------------------------
// JSON
// ---------------------------------------------------------------------------
fun SubOp.toJson(): JsonObject = JsonObject().apply {
    when (val s = this@toJson) {
        is SubOp.Text -> { addProperty("k", "text"); addProperty("f", s.f); add("d", s.d.toJson()) }
        is SubOp.ItemAdd -> {
            addProperty("k", "item_add"); addProperty("id", s.id); addProperty("pos", s.pos)
            addProperty("checked", s.checked); addProperty("text", s.text)
        }
        is SubOp.ItemDel -> { addProperty("k", "item_del"); addProperty("id", s.id) }
        is SubOp.ItemSet -> {
            addProperty("k", "item_set"); addProperty("id", s.id)
            s.checked?.let { addProperty("checked", it) }
            s.pos?.let { addProperty("pos", it) }
        }
        is SubOp.SetList -> { addProperty("k", "set"); addProperty("is_list", s.isList) }
    }
}

fun Change.toJsonArray(): JsonArray = JsonArray().also { a -> forEach { a.add(it.toJson()) } }

fun changeFromJson(element: JsonElement?): Change {
    if (element == null || !element.isJsonArray) return emptyList()
    return element.asJsonArray.mapNotNull { e ->
        if (!e.isJsonObject) return@mapNotNull null
        val o = e.asJsonObject
        when (o.str("k")) {
            "text" -> SubOp.Text(o.str("f"), Delta.fromJson(o.get("d")))
            "item_add" -> SubOp.ItemAdd(
                o.str("id"), runCatching { o.get("pos").asDouble }.getOrDefault(0.0),
                runCatching { o.get("checked").asBoolean }.getOrDefault(false), o.str("text")
            )
            "item_del" -> SubOp.ItemDel(o.str("id"))
            "item_set" -> SubOp.ItemSet(
                o.str("id"),
                if (o.has("checked")) runCatching { o.get("checked").asBoolean }.getOrNull() else null,
                if (o.has("pos")) runCatching { o.get("pos").asDouble }.getOrNull() else null
            )
            "set" -> SubOp.SetList(runCatching { o.get("is_list").asBoolean }.getOrDefault(false))
            else -> null
        }
    }
}

internal fun JsonObject.str(key: String, fallback: String = ""): String =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull() ?: fallback

// ---------------------------------------------------------------------------
// Transformation (Spiegel von xform1/xform in ot.py und delta.js)
// ---------------------------------------------------------------------------
private fun itemOf(s: SubOp): String? = when (s) {
    is SubOp.Text -> if (s.f.startsWith("item:")) s.f.substring(5) else null
    is SubOp.ItemAdd -> s.id
    is SubOp.ItemDel -> s.id
    is SubOp.ItemSet -> s.id
    is SubOp.SetList -> null
}

/** s wurde zuerst (vom Server) angewendet. Ergebnis (c', s') – null = entfällt. */
fun xform1(c: SubOp, s: SubOp): Pair<SubOp?, SubOp?> {
    if (c is SubOp.Text && s is SubOp.Text) {
        if (c.f != s.f) return c to s
        val c2 = s.d.transform(c.d, true)
        val s2 = c.d.transform(s.d, false)
        return (if (c2.isEmpty()) null else c.copy(d = c2)) to (if (s2.isEmpty()) null else s.copy(d = s2))
    }
    if (c is SubOp.SetList && s is SubOp.SetList) return c to null
    val ic = itemOf(c)
    val iss = itemOf(s)
    if (ic == null || iss == null || ic != iss) return c to s
    if (s is SubOp.ItemDel) return if (c is SubOp.ItemDel) null to null else null to s
    if (c is SubOp.ItemDel) return c to null
    if (c is SubOp.ItemAdd && s is SubOp.ItemAdd) return null to s
    if (c is SubOp.ItemSet && s is SubOp.ItemSet) {
        val rest = SubOp.ItemSet(
            s.id,
            checked = if (s.checked != null && c.checked == null) s.checked else null,
            pos = if (s.pos != null && c.pos == null) s.pos else null
        )
        return c to (if (rest.checked != null || rest.pos != null) rest else null)
    }
    return c to s
}

fun xform(cs: Change, ss0: Change): Pair<Change, Change> {
    var ss = ss0
    val outC = ArrayList<SubOp>()
    for (c in cs) {
        var cur: SubOp? = c
        val newS = ArrayList<SubOp>()
        for (i in ss.indices) {
            if (cur == null) {
                newS.addAll(ss.subList(i, ss.size))
                break
            }
            val (c2, s2) = xform1(cur, ss[i])
            cur = c2
            if (s2 != null) newS.add(s2)
        }
        ss = newS
        if (cur != null) outC.add(cur)
    }
    return outC to ss
}

/** Hängt b an a an und fasst aufeinanderfolgende Textänderungen desselben Feldes zusammen. */
fun composeChanges(a: Change, b: Change): Change {
    val out = a.toMutableList()
    for (sub in b) {
        val last = out.lastOrNull()
        if (last is SubOp.Text && sub is SubOp.Text && last.f == sub.f) {
            val d = last.d.compose(sub.d)
            if (d.isEmpty()) out.removeAt(out.size - 1) else out[out.size - 1] = last.copy(d = d)
        } else out.add(sub)
    }
    return out
}

/** Umkehrung einer Änderung relativ zu [doc] (Zustand VOR der Änderung) – für Rückgängig/Wiederholen. */
fun invertChange(doc: NoteDoc, change: Change): Change {
    var work = doc
    val inv = ArrayList<SubOp>()
    for (sub in change) {
        when (sub) {
            is SubOp.Text -> {
                val base = when {
                    sub.f == "body" -> work.body
                    sub.f == "title" -> Delta.plain(work.title)
                    else -> Delta.plain(work.items.firstOrNull { it.id == sub.f.substring(5) }?.text.orEmpty())
                }
                inv.add(0, SubOp.Text(sub.f, sub.d.invert(base)))
            }
            is SubOp.ItemAdd -> inv.add(0, SubOp.ItemDel(sub.id))
            is SubOp.ItemDel -> work.items.firstOrNull { it.id == sub.id }?.let {
                inv.add(0, SubOp.ItemAdd(it.id, it.pos, it.checked, it.text))
            }
            is SubOp.ItemSet -> work.items.firstOrNull { it.id == sub.id }?.let {
                inv.add(0, SubOp.ItemSet(it.id, if (sub.checked != null) it.checked else null, if (sub.pos != null) it.pos else null))
            }
            is SubOp.SetList -> inv.add(0, SubOp.SetList(work.isList))
        }
        work = work.apply(listOf(sub))
    }
    return inv.filterNot { it is SubOp.Text && it.d.isEmpty() }
}
