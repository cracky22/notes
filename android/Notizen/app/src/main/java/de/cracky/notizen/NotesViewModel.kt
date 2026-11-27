package de.cracky.notizen

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import de.cracky.notizen.data.ApiException
import de.cracky.notizen.model.Change
import de.cracky.notizen.model.CreatedMagicLink
import de.cracky.notizen.model.Delta
import de.cracky.notizen.model.DocItem
import de.cracky.notizen.model.IndexStatus
import de.cracky.notizen.model.LabelData
import de.cracky.notizen.model.MagicLinkInfo
import de.cracky.notizen.model.Member
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.NoteDoc
import de.cracky.notizen.model.SessionInfo
import de.cracky.notizen.model.Usage
import de.cracky.notizen.model.UserData
import de.cracky.notizen.model.UserInfo
import de.cracky.notizen.model.VersionInfo
import de.cracky.notizen.model.changeFromJson
import de.cracky.notizen.model.rid
import de.cracky.notizen.sync.NoteSession
import de.cracky.notizen.util.Media
import de.cracky.notizen.util.PickedFile
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ViewKey(val kind: String, val labelId: Int? = null) {
    companion object {
        val NOTES = ViewKey("notes")
        val ARCHIVE = ViewKey("archive")
        val TRASH = ViewKey("trash")
    }
}

/** Suchfilter wie im Web: type | label | thing | color. */
data class SearchFilter(val kind: String, val value: String, val label: String)

data class EditorUi(
    val key: Long,
    val noteId: Int?,
    val doc: NoteDoc,
    val loading: Boolean = false,
    val pending: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isNew: Boolean = false,
    val remoteTick: Int = 0,
    val uploading: Int = 0
)

data class AppState(
    val initialized: Boolean = false,
    val serverUrl: String = "",
    val user: UserData? = null,
    val notes: Map<Int, NoteData> = emptyMap(),
    val labels: List<LabelData> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val view: ViewKey = ViewKey.NOTES,
    val searchActive: Boolean = false,
    val query: String = "",
    val filter: SearchFilter? = null,
    val searchIds: Set<Int>? = null,
    val selected: Set<Int> = emptySet(),
    val gridLayout: Boolean = true,
    val themeMode: String = "system",
    val online: Boolean = false,
    val refreshing: Boolean = false,
    val firstLoad: Boolean = false,
    val editor: EditorUi? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null
) {
    fun setting(key: String, default: Boolean): Boolean =
        runCatching { user?.settings?.get(key)?.takeUnless { it.isJsonNull }?.asBoolean }.getOrNull() ?: default

    val addItemsBottom: Boolean get() = setting("addItemsBottom", true)
    val checkedBottom: Boolean get() = setting("checkedBottom", true)
    val snackbarsEnabled: Boolean get() = setting("snackbarsEnabled", true)
}

data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val important: Boolean = false,
    val action: (() -> Unit)? = null
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NotizenApplication).repository
    private val prefs = application.getSharedPreferences("notizen_ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        AppState(
            serverUrl = repo.api.baseUrl,
            gridLayout = prefs.getBoolean("grid", true),
            themeMode = prefs.getString("theme", "system") ?: "system"
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val sessions = HashMap<Int, NoteSession>()
    private var draft: NoteSession? = null
    private var draftCreation: Deferred<Int?>? = null
    private var createDebounce: Job? = null
    private var searchJob: Job? = null
    private var settingsJob: Job? = null
    private var cacheJob: Job? = null
    private val dirtyNotes = HashSet<Int>()
    private var editorKey = 0L
    private var lastRefresh = 0L
    private var pendingShare: SharePayload? = null

    data class SharePayload(val title: String, val text: String, val files: List<PickedFile>)

    init {
        viewModelScope.launch { repo.realtime.events.collect { handleWs(it) } }
        viewModelScope.launch { repo.realtime.connected.collect { c -> _state.update { it.copy(online = c) } } }
        start()
    }

    // =====================================================================
    // Start: zuerst lokaler Cache (sofort sichtbar), danach still im Hintergrund synchronisieren
    // =====================================================================
    private fun start() = viewModelScope.launch {
        if (!prefs.getBoolean("signed_in", false)) {
            _state.update { it.copy(initialized = true) }
            return@launch
        }
        val user = runCatching { repo.cache.loadUser() }.getOrNull()
        if (user == null) {
            _state.update { it.copy(initialized = true) }
            return@launch
        }
        val notes = runCatching { repo.cache.loadNotes(user.id) }.getOrDefault(emptyList())
        val labels = runCatching { repo.cache.loadLabels(user.id) }.getOrDefault(emptyList())
        val users = runCatching { repo.cache.loadUsers(user.id) }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                initialized = true, user = user, notes = notes.associateBy { n -> n.id }, labels = labels,
                users = users, themeMode = themeOf(user), firstLoad = notes.isEmpty()
            )
        }
        goOnline()
        pendingShare?.let { pendingShare = null; createFromShare(it) }
    }

    private fun goOnline() {
        repo.realtime.start()
        refreshAll()
    }

    fun onForeground() {
        if (_state.value.user == null) return
        repo.realtime.start()
        if (System.currentTimeMillis() - lastRefresh > 60_000) refreshAll()
    }

    fun onBackground() {
        flushCache()
        // Verbindung offen lassen, solange noch Änderungen unterwegs sind
        viewModelScope.launch {
            var waited = 0
            while ((sessions.values.any { it.pending } || draftCreation != null) && waited < 20) { delay(500); waited++ }
            repo.realtime.stop()
        }
    }

    fun refreshAll(showIndicator: Boolean = false) {
        val user = _state.value.user ?: return
        lastRefresh = System.currentTimeMillis()
        viewModelScope.launch {
            if (showIndicator) _state.update { it.copy(refreshing = true) }
            try {
                val me = repo.me()
                applyUser(me)
                if (repo.reconcileCacheGeneration(me.id, me.cacheGeneration)) {
                    _state.update { it.copy(notes = emptyMap()) }
                }
                val progressive = _state.value.notes.isEmpty()
                val fresh = repo.fetchNotes(user.id) { page ->
                    if (progressive) _state.update { s -> s.copy(notes = mergeServer(page, s.notes)) }
                }
                _state.update { s -> s.copy(notes = mergeServer(fresh, s.notes), firstLoad = false) }
                launch { runCatching { repo.labels(user.id) }.onSuccess { l -> _state.update { it.copy(labels = l) } } }
                launch { runCatching { repo.users(user.id) }.onSuccess { u -> _state.update { it.copy(users = u) } } }
            } catch (e: ApiException) {
                if (e.status == 401) sessionExpired() else message(e.message)
            } catch (_: Exception) {
                if (showIndicator) message("Keine Verbindung zum Server – du siehst den gespeicherten Stand.")
            } finally {
                _state.update { it.copy(refreshing = false, firstLoad = false) }
            }
        }
    }

    /** Serverliste übernehmen, ohne lokale (evtl. noch nicht bestätigte) Inhalte zu überschreiben. */
    private fun mergeServer(server: List<NoteData>, current: Map<Int, NoteData>): Map<Int, NoteData> {
        val out = LinkedHashMap<Int, NoteData>(server.size)
        for (n in server) out[n.id] = overlay(n, current[n.id])
        return out
    }

    private fun overlay(server: NoteData, current: NoteData?): NoteData {
        val session = sessions[server.id]
        if (session != null) return server.copy(doc = session.doc, contentDeferred = false)
        if (server.contentDeferred && current != null && !current.contentDeferred && current.updatedAt == server.updatedAt) {
            return server.copy(doc = current.doc, images = current.images, pdfs = current.pdfs, contentDeferred = false)
        }
        return server
    }

    private fun upsert(note: NoteData) {
        _state.update { s -> s.copy(notes = s.notes + (note.id to overlay(note, s.notes[note.id]))) }
        markDirty(note.id)
    }

    private fun removeNote(id: Int) {
        sessions.remove(id)
        _state.update { s -> s.copy(notes = s.notes - id, selected = s.selected - id) }
        val uid = _state.value.user?.id ?: return
        viewModelScope.launch { runCatching { repo.cache.deleteNote(uid, id) } }
    }

    private fun markDirty(id: Int) {
        dirtyNotes += id
        if (cacheJob?.isActive == true) return
        cacheJob = viewModelScope.launch { delay(2500); flushCache() }
    }

    private fun flushCache() {
        val uid = _state.value.user?.id ?: return
        if (dirtyNotes.isEmpty()) return
        val notes = dirtyNotes.mapNotNull { _state.value.notes[it] }
        dirtyNotes.clear()
        viewModelScope.launch { runCatching { repo.cache.saveNotes(uid, notes) } }
    }

    private fun applyUser(me: UserData) {
        _state.update { it.copy(user = me, themeMode = themeOf(me)) }
        prefs.edit().putString("theme", themeOf(me)).putBoolean("signed_in", true).apply()
        viewModelScope.launch { runCatching { repo.cache.saveUser(me) } }
    }

    private fun themeOf(user: UserData): String =
        runCatching { user.settings.get("theme")?.asString }.getOrNull()?.takeIf { it in setOf("system", "light", "dark") } ?: "system"

    // =====================================================================
    // Anmeldung
    // =====================================================================
    fun login(serverUrl: String, username: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(loginBusy = true, loginError = null) }
        try {
            repo.setServerUrl(serverUrl)
            onSignedIn(repo.login(username, password))
        } catch (e: Exception) {
            _state.update { it.copy(loginBusy = false, loginError = e.message ?: "Anmeldung fehlgeschlagen.") }
        }
    }

    /** Anmeldelink (z. B. per QR-Code gescannt): https://server/pfad/#?magic=TOKEN */
    fun loginWithMagicLink(raw: String) = viewModelScope.launch {
        _state.update { it.copy(loginBusy = true, loginError = null) }
        try {
            val uri = Uri.parse(raw.trim())
            val fragment = uri.fragment.orEmpty().removePrefix("?")
            val token = Uri.parse("https://x.invalid/?$fragment").getQueryParameter("magic") ?: uri.getQueryParameter("magic")
            if (token.isNullOrBlank() || uri.scheme !in setOf("http", "https")) throw IllegalArgumentException("Das ist kein gültiger Anmeldelink.")
            val base = raw.trim().substringBefore("#").substringBefore("?")
            repo.setServerUrl(base)
            onSignedIn(repo.redeemMagicLink(token))
        } catch (e: Exception) {
            _state.update { it.copy(loginBusy = false, loginError = e.message ?: "Anmeldung fehlgeschlagen.") }
        }
    }

    private suspend fun onSignedIn(user: UserData) {
        val previous = runCatching { repo.cache.loadUser() }.getOrNull()
        if (previous != null && previous.id != user.id) repo.cache.clearAll()
        repo.reconcileCacheGeneration(user.id, user.cacheGeneration)
        applyUser(user)
        val notes = runCatching { repo.cache.loadNotes(user.id) }.getOrDefault(emptyList())
        _state.update {
            it.copy(loginBusy = false, loginError = null, serverUrl = repo.api.baseUrl, notes = notes.associateBy { n -> n.id }, firstLoad = notes.isEmpty())
        }
        goOnline()
        pendingShare?.let { pendingShare = null; createFromShare(it) }
    }

    private fun sessionExpired() {
        repo.realtime.stop()
        prefs.edit().putBoolean("signed_in", false).apply()
        sessions.clear()
        draft = null
        _state.update {
            AppState(initialized = true, serverUrl = repo.api.baseUrl, gridLayout = it.gridLayout, themeMode = it.themeMode,
                loginError = "Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.")
        }
    }

    fun logout() = viewModelScope.launch {
        repo.logout()
        prefs.edit().putBoolean("signed_in", false).apply()
        sessions.clear()
        draft = null
        _state.update { AppState(initialized = true, serverUrl = repo.api.baseUrl, gridLayout = it.gridLayout, themeMode = it.themeMode) }
    }

    // =====================================================================
    // Echtzeit
    // =====================================================================
    private fun handleWs(msg: JsonObject) {
        val t = msg.get("t")?.takeUnless { it.isJsonNull }?.asString ?: return
        val id = runCatching { msg.get("id")?.asInt }.getOrNull()
        when (t) {
            "connected" -> {
                sessions.values.forEach { it.onReconnect() }
                if (System.currentTimeMillis() - lastRefresh > 5000) refreshAll()
                retryDraft()
            }
            "auth_lost" -> viewModelScope.launch {
                try { repo.me() } catch (e: ApiException) { if (e.status == 401) sessionExpired() } catch (_: Exception) { }
            }
            "hello" -> {
                val uid = _state.value.user?.id ?: return
                val gen = runCatching { msg.get("cache_generation").asInt }.getOrDefault(0)
                viewModelScope.launch { if (repo.reconcileCacheGeneration(uid, gen)) refreshAll() }
            }
            "cache_reset" -> {
                val uid = _state.value.user?.id ?: return
                val gen = runCatching { msg.get("generation").asInt }.getOrDefault(0)
                viewModelScope.launch { repo.reconcileCacheGeneration(uid, gen); refreshAll() }
            }
            "op" -> if (id != null) onRemoteOp(id, msg)
            "ack" -> if (id != null) {
                val s = sessionFor(id) ?: return
                s.ack(runCatching { msg.get("rev").asInt }.getOrDefault(s.rev), msg.get("cid")?.asString.orEmpty())
                syncSession(s, remote = false)
                cleanupSessions()
            }
            "caught" -> if (id != null) sessionFor(id)?.caught()
            "resync" -> if (id != null) resync(id)
            "note" -> msg.getAsJsonObject("note")?.let { upsert(NoteData.fromJson(it)) }
            "gone" -> if (id != null) {
                val wasOpen = _state.value.editor?.noteId == id
                removeNote(id)
                if (wasOpen) {
                    _state.update { it.copy(editor = null) }
                    message("Die Notiz wurde gelöscht oder nicht mehr mit dir geteilt.", important = true)
                }
            }
            "labels" -> reloadLabels()
            "settings" -> msg.getAsJsonObject("settings")?.let { settings ->
                _state.value.user?.let { u -> applyUser(u.copy(settings = settings)) }
            }
            "user" -> msg.getAsJsonObject("user")?.let { o ->
                val info = UserInfo.fromJson(o)
                _state.update { s ->
                    s.copy(
                        users = s.users.map { if (it.id == info.id) info else it },
                        user = s.user?.let { u -> if (u.id == info.id) u.copy(avatar = info.avatar) else u },
                        notes = s.notes.mapValues { (_, n) ->
                            if (n.members.none { it.id == info.id }) n
                            else n.copy(members = n.members.map { m -> if (m.id == info.id) Member(m.id, info.username, info.avatar, m.role) else m })
                        }
                    )
                }
            }
            "positions" -> {
                val map = repo.positionsFrom(msg.get("positions"))
                _state.update { s -> s.copy(notes = s.notes.mapValues { (k, n) -> map[k]?.let { n.copy(position = it) } ?: n }) }
            }
            "error" -> message("Synchronisationsfehler: ${msg.get("message")?.asString.orEmpty()}", important = true)
        }
    }

    private fun sessionFor(id: Int): NoteSession? = sessions[id] ?: draft?.takeIf { it.id == id }

    private fun onRemoteOp(id: Int, msg: JsonObject) {
        val me = _state.value.user?.id
        val byOther = runCatching { msg.get("by").asInt }.getOrNull() != me ||
            msg.get("client")?.takeUnless { it.isJsonNull }?.asString != repo.api.clientId
        val session = sessionFor(id)
        if (session != null) {
            if (session.remote(msg, byOther) == true) syncSession(session, remote = true)
            return
        }
        val note = _state.value.notes[id] ?: return
        val rev = runCatching { msg.get("rev").asInt }.getOrDefault(0)
        if (!note.contentDeferred && rev == note.rev + 1) {
            val updated = note.copy(doc = note.doc.apply(changeFromJson(msg.get("change"))), rev = rev,
                updatedAt = System.currentTimeMillis() / 1000)
            _state.update { s -> s.copy(notes = s.notes + (id to updated)) }
            markDirty(id)
        } else if (rev > note.rev) {
            fetchQuietly(id)
        }
    }

    private fun fetchQuietly(id: Int) {
        val uid = _state.value.user?.id ?: return
        viewModelScope.launch { runCatching { repo.fetchNote(uid, id) }.onSuccess { upsert(it) } }
    }

    private fun resync(id: Int) {
        val uid = _state.value.user?.id ?: return
        viewModelScope.launch {
            try {
                val fresh = repo.fetchNote(uid, id)
                val s = sessionFor(id)
                if (s != null) {
                    val lost = s.reset(fresh)
                    syncSession(s, remote = true)
                    if (lost) message("Einige Änderungen konnten nicht übernommen werden. Die Notiz wurde neu geladen.", important = true)
                }
                upsert(fresh)
            } catch (_: Exception) { }
        }
    }

    private fun cleanupSessions() {
        val open = _state.value.editor?.noteId
        sessions.entries.removeAll { (id, s) -> id != open && !s.pending }
    }

    /** Zustand einer Sitzung in Editor und Notizliste spiegeln. */
    private fun syncSession(s: NoteSession, remote: Boolean) {
        val id = s.id
        _state.update { st ->
            val ed = st.editor
            val editorMatches = ed != null && ((id != null && ed.noteId == id) || (ed.noteId == null && s === draft))
            val newEditor = if (editorMatches && ed != null) ed.copy(
                doc = s.doc, pending = s.pending || s.id == null, canUndo = s.canUndo, canRedo = s.canRedo,
                remoteTick = if (remote) ed.remoteTick + 1 else ed.remoteTick, noteId = id ?: ed.noteId
            ) else ed
            val notes = if (id != null) st.notes[id]?.let { n ->
                st.notes + (id to n.copy(doc = s.doc, rev = maxOf(n.rev, s.rev), updatedAt = if (remote) n.updatedAt else System.currentTimeMillis() / 1000))
            } ?: st.notes else st.notes
            st.copy(editor = newEditor, notes = notes)
        }
        if (id != null) markDirty(id)
    }

    // =====================================================================
    // Editor
    // =====================================================================
    fun openNote(id: Int) {
        val note = _state.value.notes[id] ?: return
        val key = ++editorKey
        val existing = sessions[id]
        if (existing != null) {
            _state.update { it.copy(editor = EditorUi(key, id, existing.doc, pending = existing.pending, canUndo = existing.canUndo, canRedo = existing.canRedo)) }
            existing.onReconnect()
            return
        }
        if (note.contentDeferred) {
            _state.update { it.copy(editor = EditorUi(key, id, note.doc, loading = true)) }
            val uid = _state.value.user?.id ?: return
            viewModelScope.launch {
                try {
                    val full = repo.fetchNote(uid, id)
                    upsert(full)
                    val s = newSession(full)
                    _state.update { st -> if (st.editor?.key == key) st.copy(editor = EditorUi(key, id, s.doc)) else st }
                } catch (_: Exception) {
                    _state.update { st -> if (st.editor?.key == key) st.copy(editor = null) else st }
                    message("Diese Archivnotiz ist offline nicht verfügbar.", important = true)
                }
            }
            return
        }
        val s = newSession(note)
        _state.update { it.copy(editor = EditorUi(key, id, s.doc)) }
    }

    private fun newSession(note: NoteData): NoteSession {
        val s = NoteSession(note.id, note.rev, note.doc, repo.api.clientId) { repo.realtime.send(it) }
        sessions[note.id] = s
        s.onReconnect()
        return s
    }

    fun newNote(isList: Boolean, title: String = "", text: String = "", files: List<PickedFile> = emptyList()) {
        if (_state.value.user == null) return
        val body = if (text.isEmpty()) Delta.emptyBody() else Delta().insert(text.trimEnd('\n') + "\n")
        val doc = if (isList) NoteDoc(title = title, isList = true, items = listOf(DocItem(rid(), 1.0, false, "")))
        else NoteDoc(title = title, body = body)
        val s = NoteSession(null, 0, doc, repo.api.clientId) { repo.realtime.send(it) }
        draft = s
        draftCreation = null
        draftLabels = _state.value.view.labelId?.let { listOf(it) } ?: emptyList()
        _state.update { it.copy(editor = EditorUi(++editorKey, null, doc, isNew = true), searchActive = false, selected = emptySet()) }
        if (files.isNotEmpty() || title.isNotEmpty() || text.isNotEmpty()) {
            viewModelScope.launch {
                if (files.isNotEmpty()) addAttachments(files) else ensureCreated()
            }
        }
    }

    private var draftLabels: List<Int> = emptyList()

    fun closeEditor() {
        val ed = _state.value.editor ?: return
        _state.update { it.copy(editor = null) }
        val d = draft
        if (ed.noteId == null && d != null && d.id == null) {
            if (d.doc.isBlank && draftCreation == null) {
                draft = null
                return
            }
            viewModelScope.launch {
                val id = ensureCreated()
                if (id == null) message("Notiz wird gespeichert, sobald eine Verbindung besteht.", important = true)
            }
            return
        }
        val id = ed.noteId ?: return
        repo.realtime.send(JsonObject().apply { addProperty("t", "close"); addProperty("id", id) })
        val note = _state.value.notes[id]
        if (ed.isNew && note != null && note.isEmpty && note.isOwner) {
            sessions.remove(id)
            removeNote(id)
            viewModelScope.launch { runCatching { repo.deleteForever(id) } }
            message("Leere Notiz verworfen")
            return
        }
        cleanupSessions()
        if (draft?.id == id) draft = null
        flushCache()
    }

    private fun currentSession(): NoteSession? {
        val ed = _state.value.editor ?: return null
        return if (ed.noteId == null) draft else sessions[ed.noteId] ?: draft?.takeIf { it.id == ed.noteId }
    }

    /** Vom Editor erzeugte Änderung anwenden und senden. */
    fun applyLocal(change: Change, groupField: String? = null) {
        if (change.isEmpty()) return
        val s = currentSession() ?: return
        s.applyLocal(change, groupField = groupField)
        syncSession(s, remote = false)
        if (s.id == null && !s.creating && !s.doc.isBlank) {
            createDebounce?.cancel()
            createDebounce = viewModelScope.launch { delay(900); ensureCreated() }
        }
    }

    fun undo() { currentSession()?.let { if (it.undo()) syncSession(it, remote = true) } }
    fun redo() { currentSession()?.let { if (it.redo()) syncSession(it, remote = true) } }

    /** Legt einen Entwurf auf dem Server an (falls nötig) und liefert die Notiz-ID. */
    private suspend fun ensureCreated(): Int? {
        val ed = _state.value.editor
        ed?.noteId?.let { return it }
        val s = draft ?: return null
        s.id?.let { return it }
        draftCreation?.let { return it.await() }
        val job = viewModelScope.async {
            val snapshot = s.beginCreate()
            try {
                val note = repo.createNote(snapshot, "default", false, false, draftLabels)
                s.created(note)
                sessions[note.id] = s
                repo.realtime.send(JsonObject().apply { addProperty("t", "open"); addProperty("id", note.id) })
                upsert(note.copy(doc = s.doc))
                _state.update { st ->
                    val e = st.editor
                    if (e != null && e.noteId == null && draft === s) st.copy(editor = e.copy(noteId = note.id, pending = s.pending)) else st
                }
                if (_state.value.editor?.noteId != note.id) {
                    repo.realtime.send(JsonObject().apply { addProperty("t", "close"); addProperty("id", note.id) })
                    if (draft === s) draft = null
                    cleanupSessions()
                }
                note.id
            } catch (e: Exception) {
                s.createFailed()
                if (e is ApiException) message(e.message, important = true)
                null
            } finally {
                draftCreation = null
            }
        }
        draftCreation = job
        if (job.isCompleted) draftCreation = null
        return job.await()
    }

    private fun retryDraft() {
        val d = draft ?: return
        if (d.id == null && !d.doc.isBlank && draftCreation == null) viewModelScope.launch { ensureCreated() }
    }

    // ----------------------------------------------------------------- Anhänge
    fun addAttachments(files: List<PickedFile>) = viewModelScope.launch {
        if (files.isEmpty()) return@launch
        val id = ensureCreated() ?: run {
            message("Für Anhänge ist eine Serververbindung nötig.", important = true)
            return@launch
        }
        val uid = _state.value.user?.id ?: return@launch
        val ctx = getApplication<Application>()
        _state.update { s -> s.copy(editor = s.editor?.let { it.copy(uploading = it.uploading + files.size) }) }
        for (f in files) {
            try {
                if (f.isPdf) repo.uploadPdf(id, f.name, Media.streamBody(ctx, f.copy(mime = "application/pdf")))
                else repo.uploadImage(id, Media.imageBody(ctx, f))
            } catch (e: Exception) {
                message("${f.name}: ${e.message ?: "Hochladen fehlgeschlagen"}", important = true)
            } finally {
                _state.update { s -> s.copy(editor = s.editor?.let { it.copy(uploading = (it.uploading - 1).coerceAtLeast(0)) }) }
            }
        }
        runCatching { repo.fetchNote(uid, id) }.onSuccess { upsert(it) }
    }

    fun deleteImage(noteId: Int, imageId: Int) = viewModelScope.launch {
        _state.update { s -> s.copy(notes = s.notes[noteId]?.let { n -> s.notes + (noteId to n.copy(images = n.images.filterNot { it.id == imageId })) } ?: s.notes) }
        try { repo.deleteImage(noteId, imageId) } catch (e: Exception) { message(e.message ?: "Löschen fehlgeschlagen", important = true); fetchQuietly(noteId) }
    }

    fun deletePdf(noteId: Int, pdfId: Int) = viewModelScope.launch {
        _state.update { s -> s.copy(notes = s.notes[noteId]?.let { n -> s.notes + (noteId to n.copy(pdfs = n.pdfs.filterNot { it.id == pdfId })) } ?: s.notes) }
        try { repo.deletePdf(noteId, pdfId) } catch (e: Exception) { message(e.message ?: "Löschen fehlgeschlagen", important = true); fetchQuietly(noteId) }
    }

    suspend fun downloadPdf(pdfId: Int, name: String): File {
        val dir = File(getApplication<Application>().cacheDir, "shared").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "Dokument.pdf" }
        val target = File(dir, safe)
        repo.downloadPdf(pdfId, target)
        return target
    }

    fun imageUrl(id: Int) = repo.imageUrl(id)
    fun thumbUrl(id: Int) = repo.thumbUrl(id)
    fun pdfThumbUrl(id: Int) = repo.pdfThumbUrl(id)
    fun pdfPageUrl(id: Int, page: Int, width: Int) = repo.pdfPageUrl(id, page, width)
    fun avatarUrl(userId: Int, avatar: Int) = repo.avatarUrl(userId, avatar)
    suspend fun pdfPageCount(id: Int) = repo.pdfPageCount(id)

    // =====================================================================
    // Notiz-Aktionen (Karten, Mehrfachauswahl, Editor)
    // =====================================================================
    private fun editorNoteIdOrCreate(block: (Int) -> Unit) {
        val ed = _state.value.editor
        val id = ed?.noteId
        if (id != null) { block(id); return }
        viewModelScope.launch { ensureCreated()?.let(block) }
    }

    fun editorAction(block: (Int) -> Unit) = editorNoteIdOrCreate(block)

    private fun patch(ids: Collection<Int>, body: JsonObject, local: (NoteData) -> NoteData) {
        _state.update { s -> s.copy(notes = s.notes.mapValues { (k, n) -> if (k in ids) local(n) else n }) }
        viewModelScope.launch {
            for (id in ids) {
                try { upsert(repo.patchNote(id, body)) } catch (e: Exception) {
                    message(e.message ?: "Änderung fehlgeschlagen", important = true)
                    fetchQuietly(id)
                }
            }
        }
    }

    fun setPinned(ids: Collection<Int>, pinned: Boolean) {
        val body = JsonObject().apply { addProperty("pinned", pinned); if (pinned) addProperty("archived", false) }
        patch(ids, body) { it.copy(pinned = pinned, archived = if (pinned) false else it.archived) }
        clearSelection()
    }

    fun setArchived(ids: Collection<Int>, archived: Boolean, undoable: Boolean = true) {
        val list = ids.toList()
        val body = JsonObject().apply { addProperty("archived", archived); if (archived) addProperty("pinned", false) }
        val previousPinned = list.associateWith { _state.value.notes[it]?.pinned ?: false }
        patch(list, body) { it.copy(archived = archived, pinned = if (archived) false else it.pinned) }
        clearSelection()
        if (undoable) {
            val text = if (archived) (if (list.size == 1) "Notiz archiviert" else "${list.size} Notizen archiviert")
            else (if (list.size == 1) "Archivierung aufgehoben" else "Archivierung von ${list.size} Notizen aufgehoben")
            message(text, "Rückgängig") {
                setArchived(list, !archived, undoable = false)
                if (archived) list.filter { previousPinned[it] == true }.takeIf { it.isNotEmpty() }?.let { setPinned(it, true) }
            }
        }
    }

    fun setColor(ids: Collection<Int>, color: String) {
        patch(ids, JsonObject().apply { addProperty("color", color) }) { it.copy(color = color) }
    }

    fun setLabels(id: Int, labels: List<Int>) {
        val arr = com.google.gson.JsonArray().also { a -> labels.forEach { a.add(it) } }
        patch(listOf(id), JsonObject().apply { add("labels", arr) }) { it.copy(labels = labels) }
    }

    fun toggleLabelForNotes(ids: Collection<Int>, labelId: Int, add: Boolean) {
        for (id in ids) {
            val note = _state.value.notes[id] ?: continue
            val next = if (add) (note.labels + labelId).distinct() else note.labels - labelId
            if (next != note.labels) setLabels(id, next)
        }
    }

    fun trash(ids: Collection<Int>, undoable: Boolean = true) {
        val list = ids.toList()
        val notes = list.mapNotNull { _state.value.notes[it] }
        val own = notes.filter { it.isOwner }.map { it.id }
        val foreign = notes.filterNot { it.isOwner }.map { it.id }
        if (own.isNotEmpty()) patch(own, JsonObject().apply { addProperty("trashed", true) }) { it.copy(trashed = true, trashedAt = System.currentTimeMillis() / 1000) }
        for (id in foreign) {
            removeNote(id)
            viewModelScope.launch { try { repo.leaveNote(id) } catch (e: Exception) { message(e.message ?: "Fehler", important = true); refreshAll() } }
        }
        clearSelection()
        if (_state.value.editor?.noteId?.let { it in list } == true) _state.update { it.copy(editor = null) }
        if (own.isNotEmpty() && undoable) {
            message(if (own.size == 1) "Notiz in den Papierkorb verschoben" else "${own.size} Notizen in den Papierkorb verschoben", "Rückgängig") {
                restore(own)
            }
        } else if (foreign.isNotEmpty()) message("Notiz entfernt")
    }

    fun restore(ids: Collection<Int>) {
        patch(ids, JsonObject().apply { addProperty("trashed", false) }) { it.copy(trashed = false, trashedAt = null) }
        clearSelection()
    }

    fun deleteForever(ids: Collection<Int>) {
        val list = ids.toList()
        list.forEach { removeNote(it) }
        clearSelection()
        if (_state.value.editor?.noteId?.let { it in list } == true) _state.update { it.copy(editor = null) }
        viewModelScope.launch {
            for (id in list) runCatching { repo.deleteForever(id) }.onFailure { message(it.message ?: "Löschen fehlgeschlagen", important = true) }
        }
    }

    fun emptyTrash() = viewModelScope.launch {
        try {
            repo.emptyTrash()
            val ids = _state.value.notes.values.filter { it.trashed && it.isOwner }.map { it.id }
            ids.forEach { removeNote(it) }
            message("Papierkorb geleert")
        } catch (e: Exception) { message(e.message ?: "Fehler", important = true) }
    }

    fun copy(ids: Collection<Int>) = viewModelScope.launch {
        clearSelection()
        for (id in ids) {
            try { upsert(repo.copyNote(id)) } catch (e: Exception) { message(e.message ?: "Kopieren fehlgeschlagen", important = true) }
        }
        message(if (ids.size == 1) "Kopie erstellt" else "${ids.size} Kopien erstellt")
    }

    /** Notiz in der aktuellen Reihenfolge nach vorne (-1) oder hinten (+1) verschieben. */
    fun move(id: Int, direction: Int, orderedIds: List<Int>) = viewModelScope.launch {
        val i = orderedIds.indexOf(id)
        val j = i + direction
        if (i < 0 || j !in orderedIds.indices) return@launch
        val other = orderedIds[j]
        val ids = if (direction < 0) listOf(id, other) else listOf(other, id)
        try {
            val positions = repo.reorder(ids)
            _state.update { s -> s.copy(notes = s.notes.mapValues { (k, n) -> positions[k]?.let { n.copy(position = it) } ?: n }) }
        } catch (e: Exception) { message(e.message ?: "Verschieben fehlgeschlagen", important = true) }
    }

    fun setCollaborators(noteId: Int, userIds: List<Int>) = viewModelScope.launch {
        try {
            val res = repo.setCollaborators(noteId, userIds)
            if (res == null) {
                removeNote(noteId)
                if (_state.value.editor?.noteId == noteId) _state.update { it.copy(editor = null) }
            } else upsert(res)
        } catch (e: Exception) { message(e.message ?: "Speichern fehlgeschlagen", important = true) }
    }

    fun leaveNote(noteId: Int) = viewModelScope.launch {
        try {
            repo.leaveNote(noteId)
            removeNote(noteId)
            if (_state.value.editor?.noteId == noteId) _state.update { it.copy(editor = null) }
            message("Du hast die Notiz verlassen")
        } catch (e: Exception) { message(e.message ?: "Fehler", important = true) }
    }

    // ----------------------------------------------------------------- Auswahl
    fun toggleSelect(id: Int) = _state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
    fun selectAll(ids: Collection<Int>) = _state.update { it.copy(selected = ids.toSet()) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    // ----------------------------------------------------------------- Ansicht & Suche
    fun setView(view: ViewKey) = _state.update { it.copy(view = view, selected = emptySet(), searchActive = false, query = "", filter = null, searchIds = null) }

    fun toggleLayout() {
        val grid = !_state.value.gridLayout
        prefs.edit().putBoolean("grid", grid).apply()
        _state.update { it.copy(gridLayout = grid) }
    }

    fun openSearch() = _state.update { it.copy(searchActive = true, selected = emptySet()) }
    fun closeSearch() = _state.update { it.copy(searchActive = false, query = "", filter = null, searchIds = null) }
    fun setFilter(filter: SearchFilter?) = _state.update { it.copy(filter = filter, searchActive = true) }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchIds = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            val uid = _state.value.user?.id ?: return@launch
            try {
                val (ready, ids) = repo.search(query)
                if (!ready) return@launch
                val missing = ids.filter { it !in _state.value.notes }.take(40)
                for (m in missing) runCatching { repo.fetchNote(uid, m) }.onSuccess { upsert(it) }
                _state.update { it.copy(searchIds = ids.toSet()) }
            } catch (_: Exception) {
                _state.update { it.copy(searchIds = null) }
            }
        }
    }

    // ----------------------------------------------------------------- Einstellungen
    fun updateSetting(key: String, value: JsonElement) {
        val user = _state.value.user ?: return
        val settings = user.settings.deepCopy().apply { add(key, value) }
        applyUser(user.copy(settings = settings))
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            delay(300)
            try { repo.saveSettings(settings) } catch (e: Exception) { message(e.message ?: "Einstellungen nicht gespeichert", important = true) }
        }
    }

    fun setTheme(mode: String) = updateSetting("theme", JsonPrimitive(mode))
    fun setBoolSetting(key: String, value: Boolean) = updateSetting(key, JsonPrimitive(value))

    fun clearLocalCache() = viewModelScope.launch {
        val uid = _state.value.user?.id ?: return@launch
        repo.cache.clearUser(uid)
        message("Lokaler Cache geleert – wird neu geladen")
        refreshAll(showIndicator = true)
    }

    // ----------------------------------------------------------------- Labels
    fun reloadLabels() {
        val uid = _state.value.user?.id ?: return
        viewModelScope.launch { runCatching { repo.labels(uid) }.onSuccess { l -> _state.update { it.copy(labels = l) } } }
    }

    suspend fun createLabel(name: String): LabelData? = try {
        repo.createLabel(name.trim()).also { reloadLabels() }
    } catch (e: Exception) { message(e.message ?: "Label konnte nicht erstellt werden", important = true); null }

    fun renameLabel(id: Int, name: String) = viewModelScope.launch {
        try { repo.renameLabel(id, name.trim()); reloadLabels() } catch (e: Exception) { message(e.message ?: "Umbenennen fehlgeschlagen", important = true) }
    }

    fun deleteLabel(id: Int) = viewModelScope.launch {
        try {
            repo.deleteLabel(id)
            _state.update { s ->
                s.copy(labels = s.labels.filterNot { it.id == id }, notes = s.notes.mapValues { (_, n) -> if (id in n.labels) n.copy(labels = n.labels - id) else n },
                    view = if (s.view.labelId == id) ViewKey.NOTES else s.view)
            }
        } catch (e: Exception) { message(e.message ?: "Löschen fehlgeschlagen", important = true) }
    }

    fun leaveLabel(id: Int) = viewModelScope.launch {
        try { repo.leaveLabel(id); reloadLabels(); refreshAll() } catch (e: Exception) { message(e.message ?: "Fehler", important = true) }
    }

    fun setLabelMembers(id: Int, members: List<Pair<Int, Boolean>>) = viewModelScope.launch {
        try { repo.setLabelMembers(id, members); reloadLabels() } catch (e: Exception) { message(e.message ?: "Teilen fehlgeschlagen", important = true) }
    }

    fun loadUsers() {
        val uid = _state.value.user?.id ?: return
        viewModelScope.launch { runCatching { repo.users(uid) }.onSuccess { u -> _state.update { it.copy(users = u) } } }
    }

    // ----------------------------------------------------------------- Konto
    suspend fun usage(): Usage = repo.usage()
    suspend fun indexStatus(): IndexStatus = repo.indexStatus()
    suspend fun rebuildIndex(): IndexStatus = repo.rebuildIndex()
    suspend fun sessionsList(): List<SessionInfo> = repo.sessions()
    suspend fun revokeSession(id: String) = repo.revokeSession(id)
    suspend fun magicLinks(): List<MagicLinkInfo> = repo.magicLinks()
    suspend fun createMagicLink(label: String, days: Int): CreatedMagicLink = repo.createMagicLink(label, days)
    suspend fun deleteMagicLink(id: Int) = repo.deleteMagicLink(id)
    suspend fun changePassword(old: String, new: String) = repo.changePassword(old, new)
    suspend fun approveDeviceLogin(challenge: String) = repo.approveDeviceLogin(challenge)
    suspend fun versions(noteId: Int): List<VersionInfo> = repo.versions(noteId)
    suspend fun version(noteId: Int, versionId: Int): NoteDoc = repo.version(noteId, versionId)

    fun restoreVersion(noteId: Int, versionId: Int) = viewModelScope.launch {
        try { repo.restoreVersion(noteId, versionId); message("Version wiederhergestellt") }
        catch (e: Exception) { message(e.message ?: "Wiederherstellen fehlgeschlagen", important = true) }
    }

    fun uploadAvatar(uri: Uri) = viewModelScope.launch {
        try {
            val info = repo.uploadAvatar(Media.avatarBody(getApplication(), uri))
            _state.value.user?.let { applyUser(it.copy(avatar = info.avatar)) }
            message("Profilbild aktualisiert")
        } catch (e: Exception) { message(e.message ?: "Profilbild konnte nicht gespeichert werden", important = true) }
    }

    fun removeAvatar() = viewModelScope.launch {
        try { repo.deleteAvatar(); _state.value.user?.let { applyUser(it.copy(avatar = null)) } }
        catch (e: Exception) { message(e.message ?: "Fehler", important = true) }
    }

    fun adminUrl(): String = repo.api.url("notes/admin")

    // ----------------------------------------------------------------- Teilen aus anderen Apps
    fun handleShare(title: String, text: String, uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val files = uris.map { Media.describe(ctx, it) }.filter { it.isPdf || it.isImage }
        val payload = SharePayload(title, text, files)
        if (_state.value.user == null) { pendingShare = payload; return }
        createFromShare(payload)
    }

    private fun createFromShare(p: SharePayload) {
        if (_state.value.editor != null) closeEditor()
        newNote(isList = false, title = p.title.take(900).replace('\n', ' '), text = p.text, files = p.files)
    }

    fun message(text: String?, actionLabel: String? = null, important: Boolean = false, action: (() -> Unit)? = null) {
        if (text.isNullOrBlank()) return
        if (!important && action == null && !_state.value.snackbarsEnabled) return
        messageChannel.trySend(UiMessage(text, actionLabel, important || action != null, action))
    }

    override fun onCleared() {
        flushCache()
        super.onCleared()
    }
}
