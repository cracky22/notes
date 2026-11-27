package de.cracky.notizen.sync

import com.google.gson.JsonObject
import de.cracky.notizen.model.Change
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.NoteDoc
import de.cracky.notizen.model.changeFromJson
import de.cracky.notizen.model.composeChanges
import de.cracky.notizen.model.invertChange
import de.cracky.notizen.model.rid
import de.cracky.notizen.model.toJsonArray
import de.cracky.notizen.model.xform

/**
 * Client-Seite des OT-Protokolls (Jupiter-Modell, wie static/js/sync.js):
 * höchstens ein Befehl "inflight", weitere lokale Änderungen sammeln sich im Puffer.
 * Entfernte Befehle werden gegen beide transformiert. Läuft vollständig auf dem Main-Thread.
 *
 * id == null: Entwurf, der noch nicht auf dem Server angelegt ist.
 */
class NoteSession(
    var id: Int?,
    var rev: Int,
    var doc: NoteDoc,
    private val clientId: String,
    private val send: (JsonObject) -> Boolean
) {
    private var inflightCid: String? = null
    private var inflight: Change? = null
    private var buffer: Change? = null
    private var catching = false
    var creating = false

    // Rückgängig/Wiederholen: Einträge sind Umkehr-Änderungen, nach Zeit gruppiert
    private data class UndoEntry(val change: Change, val at: Long, val field: String?)
    private val undoStack = ArrayList<UndoEntry>()
    private val redoStack = ArrayList<Change>()

    val pending: Boolean get() = inflight != null || buffer != null
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Lokale Änderung des Nutzers. */
    fun applyLocal(change: Change, recordUndo: Boolean = true, groupField: String? = null) {
        if (change.isEmpty()) return
        if (recordUndo) {
            val inverse = invertChange(doc, change)
            val now = System.currentTimeMillis()
            val last = undoStack.lastOrNull()
            if (last != null && groupField != null && last.field == groupField && now - last.at < 1200) {
                undoStack[undoStack.size - 1] = UndoEntry(composeChanges(inverse, last.change), now, groupField)
            } else {
                undoStack += UndoEntry(inverse, now, groupField)
                if (undoStack.size > 200) undoStack.removeAt(0)
            }
            redoStack.clear()
        }
        doc = doc.apply(change)
        enqueue(change)
    }

    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        val redo = invertChange(doc, entry.change)
        doc = doc.apply(entry.change)
        enqueue(entry.change)
        redoStack += redo
        return true
    }

    fun redo(): Boolean {
        val change = redoStack.removeLastOrNull() ?: return false
        val inverse = invertChange(doc, change)
        doc = doc.apply(change)
        enqueue(change)
        undoStack += UndoEntry(inverse, 0L, null)
        return true
    }

    private fun enqueue(change: Change) {
        val noteId = id
        if (noteId == null) {
            // Entwurf: Inhalt steckt komplett in doc. Nur während des Anlegens puffern.
            if (creating) buffer = buffer?.let { composeChanges(it, change) } ?: change
            return
        }
        if (inflight != null || catching) {
            buffer = buffer?.let { composeChanges(it, change) } ?: change
        } else {
            inflight = change
            inflightCid = rid(20)
            sendInflight()
        }
    }

    /** Anlegen auf dem Server beginnt: Momentaufnahme liefern. */
    fun beginCreate(): NoteDoc {
        creating = true
        buffer = null
        return doc
    }

    fun createFailed() {
        creating = false
        buffer = null
    }

    fun created(note: NoteData) {
        creating = false
        id = note.id
        rev = note.rev
        val queued = buffer
        buffer = null
        if (queued != null) {
            inflight = queued
            inflightCid = rid(20)
            sendInflight()
        }
    }

    fun sendInflight() {
        val noteId = id ?: return
        val change = inflight ?: return
        val cid = inflightCid ?: return
        send(JsonObject().apply {
            addProperty("t", "op"); addProperty("id", noteId); addProperty("rev", rev)
            addProperty("cid", cid); add("change", change.toJsonArray())
        })
    }

    fun ack(newRev: Int, cid: String) {
        if (inflight == null || inflightCid != cid) return
        rev = newRev
        inflight = null
        inflightCid = null
        val queued = buffer
        if (queued != null && !catching) {
            buffer = null
            inflight = queued
            inflightCid = rid(20)
            sendInflight()
        }
    }

    /**
     * Entfernter Befehl. Rückgabe: true = angewendet, false = ignoriert (alt),
     * null = Lücke erkannt → Catch-up wurde angefordert.
     */
    fun remote(msg: JsonObject, byOther: Boolean): Boolean? {
        val msgRev = msg.get("rev")?.asInt ?: return false
        if (msgRev <= rev) return false
        if (msgRev > rev + 1) {
            catchup()
            return null
        }
        val cid = msg.get("cid")?.takeUnless { it.isJsonNull }?.asString
        val client = msg.get("client")?.takeUnless { it.isJsonNull }?.asString
        if (inflight != null && client == clientId && cid == inflightCid) {
            ack(msgRev, cid ?: "")
            return true
        }
        var s = changeFromJson(msg.get("change"))
        inflight?.let { infl ->
            val (c2, s2) = xform(infl, s)
            inflight = c2
            s = s2
        }
        buffer?.let { buf ->
            val (b2, s2) = xform(buf, s)
            buffer = b2
            s = s2
        }
        doc = doc.apply(s)
        rev = msgRev
        if (byOther) {
            // Positionen im Rückgängig-Stapel stimmen nach fremden Änderungen nicht mehr
            undoStack.clear()
            redoStack.clear()
        }
        return true
    }

    fun catchup() {
        val noteId = id ?: return
        if (catching) return
        catching = send(JsonObject().apply { addProperty("t", "catchup"); addProperty("id", noteId); addProperty("rev", rev) })
    }

    /** Nach (Wieder-)Verbindung: verpasste Befehle holen, danach Ausstehendes senden. */
    fun onReconnect() {
        val noteId = id ?: return
        catching = false
        send(JsonObject().apply { addProperty("t", "open"); addProperty("id", noteId) })
        catchup()
        if (!catching) sendInflight()
    }

    fun caught() {
        catching = false
        if (inflight != null) {
            sendInflight()
        } else {
            val queued = buffer
            if (queued != null) {
                buffer = null
                inflight = queued
                inflightCid = rid(20)
                sendInflight()
            }
        }
    }

    /** Serverstand übernehmen (ausstehende lokale Änderungen gehen verloren). */
    fun reset(note: NoteData): Boolean {
        val lost = pending
        rev = note.rev
        doc = note.doc
        inflight = null
        inflightCid = null
        buffer = null
        catching = false
        undoStack.clear()
        redoStack.clear()
        return lost
    }
}
