package de.brainlab.notizen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import de.brainlab.notizen.data.ApiException
import de.brainlab.notizen.model.NoteData
import de.brainlab.notizen.model.NoteItem
import de.brainlab.notizen.model.UserData
import de.brainlab.notizen.model.makeItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class EditorModel(val note: NoteData?, val creatingList: Boolean = false)

data class AppState(
    val serverUrl: String = "http://10.0.2.2:8000/",
    val user: UserData? = null,
    val notes: List<NoteData> = emptyList(),
    val tab: String = "notes",
    val query: String = "",
    val searchIds: Set<Int>? = null,
    val editor: EditorModel? = null,
    val busy: Boolean = false,
    val online: Boolean = true,
    val error: String? = null,
    val info: String? = null
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as NotizenApplication).repository
    private val _state = MutableStateFlow(AppState(serverUrl = repo.api.baseUrl))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var bootstrapped = false
    private var searchJob: Job? = null

    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        viewModelScope.launch {
            try {
                val user = repo.me()
                repo.reconcileCacheGeneration(user)
                _state.update { it.copy(user = user, online = true, serverUrl = repo.api.baseUrl) }
                refreshNotes()
            } catch (_: Exception) {
                _state.update { it.copy(online = false) }
            }
        }
    }

    fun login(serverUrl: String, username: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null, info = null) }
        try {
            repo.setServerUrl(serverUrl)
            val user = repo.login(username, password)
            repo.reconcileCacheGeneration(user)
            _state.update { it.copy(user = user, serverUrl = repo.api.baseUrl, busy = false, online = true) }
            refreshNotes()
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, online = false, error = e.message ?: "Anmeldung fehlgeschlagen.") }
        }
    }

    fun refreshNotes() = viewModelScope.launch {
        val userId = _state.value.user?.id ?: return@launch
        _state.update { it.copy(busy = true, error = null) }
        try {
            val currentUser = repo.me()
            repo.reconcileCacheGeneration(currentUser)
            val notes = repo.fetchNotes(userId)
            _state.update { it.copy(user = currentUser, notes = notes, busy = false, online = true, info = null) }
        } catch (e: Exception) {
            val cached = runCatching { repo.cachedNotes(userId) }.getOrDefault(emptyList())
            _state.update { it.copy(notes = cached, busy = false, online = false, info = "Offline: gespeicherter Cache wird angezeigt.") }
        }
    }

    fun setTab(tab: String) { _state.update { it.copy(tab = tab, query = "", searchIds = null) } }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchIds = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            val userId = _state.value.user?.id ?: return@launch
            try {
                val (ready, ids) = repo.search(query)
                if (ready) {
                    val present = _state.value.notes.map { it.id }.toSet()
                    val extra = ids.filter { it !in present }.take(40).mapNotNull { id -> runCatching { repo.fetchNote(id) }.getOrNull() }
                    _state.update { s -> s.copy(searchIds = ids.toSet(), notes = (s.notes + extra).distinctBy { it.id }, online = true) }
                } else {
                    _state.update { it.copy(searchIds = null, info = "Suchindex wird noch aufgebaut; lokale Treffer werden angezeigt.") }
                }
            } catch (_: Exception) {
                _state.update { it.copy(searchIds = null, online = false, info = "Offline-Suche im gespeicherten Cache.") }
            }
        }
    }

    fun openNew(isList: Boolean) {
        _state.update { it.copy(editor = EditorModel(null, isList), error = null) }
    }

    fun openNote(note: NoteData) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        try {
            val full = repo.fetchNote(note.id)
            _state.update { s -> s.copy(editor = EditorModel(full), busy = false, online = true) }
        } catch (e: Exception) {
            if (note.contentDeferred) {
                _state.update { s -> s.copy(editor = null, busy = false, online = false, error = "Diese Archivnotiz ist nicht im lokalen Cache gespeichert.") }
            } else {
                _state.update { s -> s.copy(editor = EditorModel(note), busy = false, online = false, info = "Offline: Notiz ist schreibgeschuetzt.") }
            }
        }
    }

    fun closeEditor() { _state.update { it.copy(editor = null, error = null) } }

    fun saveEditor(title: String, body: String, isList: Boolean, items: List<NoteItem>) = viewModelScope.launch {
        val editor = _state.value.editor ?: return@launch
        if (!_state.value.online) {
            _state.update { it.copy(error = "Zum Speichern ist eine Serververbindung erforderlich.") }
            return@launch
        }
        _state.update { it.copy(busy = true, error = null) }
        try {
            val saved = if (editor.note == null) repo.saveNew(title, body, isList, items)
            else repo.saveExisting(editor.note, title, body, isList, items)
            val updated = _state.value.notes.filterNot { it.id == saved.id } + saved
            _state.update { it.copy(notes = updated, editor = null, busy = false, online = true, info = "Gespeichert.") }
            _state.value.user?.let { runCatching { repo.fetchNotes(it.id) }.onSuccess { fresh -> _state.update { s -> s.copy(notes = fresh) } } }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = e.message ?: "Speichern fehlgeschlagen.") }
        }
    }

    fun toggleMeta(note: NoteData, field: String, value: Boolean) = viewModelScope.launch {
        try {
            val updated = repo.setNoteMeta(note.id, field, value)
            replaceNote(updated)
        } catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Aenderung fehlgeschlagen.") } }
    }

    fun moveToTrash(note: NoteData) = viewModelScope.launch {
        try { replaceNote(repo.moveToTrash(note.id)) }
        catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Loeschen fehlgeschlagen.") } }
    }

    fun deleteForever(note: NoteData) = viewModelScope.launch {
        try {
            repo.deleteForever(note.id)
            _state.update { s -> s.copy(notes = s.notes.filterNot { it.id == note.id }, info = "Notiz endgueltig geloescht.") }
        } catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Loeschen fehlgeschlagen.") } }
    }

    suspend fun approveDeviceLogin(challenge: String) {
        repo.approveDeviceLogin(challenge)
    }

    fun logout() = viewModelScope.launch {
        runCatching { repo.logout() }
        _state.value = AppState(serverUrl = repo.api.baseUrl)
    }

    fun dismissMessages() { _state.update { it.copy(error = null, info = null) } }

    private fun replaceNote(note: NoteData) {
        _state.update { s -> s.copy(notes = (s.notes.filterNot { it.id == note.id } + note)) }
    }
}
