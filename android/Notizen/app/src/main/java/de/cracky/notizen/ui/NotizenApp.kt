package de.brainlab.notizen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import de.brainlab.notizen.NotesViewModel
import de.brainlab.notizen.model.NoteData
import de.brainlab.notizen.model.NoteItem
import de.brainlab.notizen.model.makeItem
import kotlinx.coroutines.launch

@Composable
fun NotizenApp(vm: NotesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var lastEditor by remember { mutableStateOf<de.brainlab.notizen.EditorModel?>(null) }
    LaunchedEffect(Unit) { vm.bootstrap() }
    LaunchedEffect(state.editor) { state.editor?.let { lastEditor = it } }
    val screen = when {
        state.user == null -> "login"
        state.editor != null -> "editor"
        else -> "home"
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 18 }) togetherWith
                (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 24 })
        },
        label = "screen-transition"
    ) { target ->
        when (target) {
            "login" -> LoginScreen(state.serverUrl, state.busy, state.error) { url, name, password -> vm.login(url, name, password) }
            "editor" -> {
                val editor = state.editor ?: lastEditor
                if (editor == null) HomeScreen(state, vm) else EditorScreen(
                    note = editor.note,
                    creatingList = editor.creatingList,
                    busy = state.busy,
                    error = state.error,
                    readOnly = !state.online,
                    onBack = vm::closeEditor,
                    onSave = { title, body, isList, items -> vm.saveEditor(title, body, isList, items) }
                )
            }
            else -> HomeScreen(state, vm)
        }
    }
}

@Composable
private fun LoginScreen(
    initialUrl: String,
    busy: Boolean,
    error: String?,
    onLogin: (String, String, String) -> Unit
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Surface(color = Color(0xFFDCEAF5), shape = RoundedCornerShape(18.dp), modifier = Modifier.size(58.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = Color(0xFF145CA8), modifier = Modifier.size(30.dp)) }
            }
            Spacer(Modifier.height(20.dp))
            Text("Notizen", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Dein Projektgedaechtnis, direkt auf dem Server.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Server-Adresse") }, singleLine = true, supportingText = { Text("Beispiel: https://notizen.example.org/notes/") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Benutzername") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Passwort") }, singleLine = true)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onLogin(url, username, password) },
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Anmelden")
            }
            Text("Bei Zugriff ueber ein oeffentliches Netzwerk HTTPS verwenden.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: de.brainlab.notizen.AppState, vm: NotesViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var menuNote by remember { mutableStateOf<NoteData?>(null) }
    var pendingChallenge by remember { mutableStateOf<PairToken?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    var approvingQr by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            showScanner = true
        } else {
            qrError = "Kamera-Zugriff wurde nicht erlaubt. Du kannst ihn spaeter in den App-Einstellungen aktivieren."
        }
    }
    fun startQrScan() {
        qrError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(state.info, state.error) {
        val message = state.error ?: state.info
        if (!message.isNullOrBlank()) snackbar.showSnackbar(message)
        vm.dismissMessages()
    }

    val visible = state.notes.filter { note ->
        val inTab = when (state.tab) {
            "archive" -> note.archived && !note.trashed
            "trash" -> note.trashed
            else -> !note.archived && !note.trashed
        }
        val match = if (state.query.isBlank()) true else {
            val ids = state.searchIds
            if (ids != null) note.id in ids
            else (note.title + " " + note.bodyRaw + " " + note.items.joinToString(" ") { it.text }).contains(state.query, ignoreCase = true)
        }
        inTab && match
    }.sortedWith(compareByDescending<NoteData> { it.pinned }.thenByDescending { it.updatedAt })

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(when (state.tab) { "archive" -> "Archiv"; "trash" -> "Papierkorb"; else -> "Notizen" }, fontWeight = FontWeight.Bold)
                        Text(state.user?.username.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (!state.online) Icon(Icons.Default.WifiOff, contentDescription = "Offline", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 7.dp))
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); vm.refreshNotes() }) { Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren") }
                    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); startQrScan() }, enabled = state.online) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Browser mit QR-Code anmelden")
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menue") }
                        DropdownMenu(expanded, { expanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Browser per QR anmelden") },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                                enabled = state.online,
                                onClick = { expanded = false; startQrScan() }
                            )
                            DropdownMenuItem(text = { Text("Abmelden") }, leadingIcon = { Icon(Icons.Default.Logout, null) }, onClick = { expanded = false; vm.logout() })
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("notes" to "Notizen", "archive" to "Archiv", "trash" to "Papierkorb").forEach { (tab, label) ->
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); vm.setTab(tab) },
                        icon = { Text(when (tab) { "archive" -> "▤"; "trash" -> "⌫"; else -> "▦" }) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.tab == "notes" && state.online) ExtendedFloatingActionButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); showCreate = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Neue Notiz") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Notizen durchsuchen") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            if (state.busy && state.notes.isEmpty()) LinearLoading()
            if (visible.isEmpty() && !state.busy) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.query.isNotBlank()) "Keine Treffer" else when (state.tab) { "archive" -> "Das Archiv ist leer"; "trash" -> "Der Papierkorb ist leer"; else -> "Noch keine Notizen" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (state.online) "Erstelle eine Notiz oder aktualisiere die Ansicht." else "Verbinde dich mit dem Server, um neue Notizen zu erstellen.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.id }) { note ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(180)),
                            exit = fadeOut(tween(130)) + shrinkVertically(animationSpec = tween(130))
                        ) {
                            NoteCard(
                                note,
                                onOpen = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); vm.openNote(note) },
                                onMore = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); menuNote = note }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) AlertDialog(
        onDismissRequest = { showCreate = false },
        title = { Text("Neue Notiz erstellen") },
        text = { Text("Was moechtest du festhalten?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { showCreate = false; vm.openNew(false) }) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("Textnotiz") }
                TextButton(onClick = { showCreate = false; vm.openNew(true) }) { Icon(Icons.Default.Checklist, null); Spacer(Modifier.width(8.dp)); Text("Checkliste") }
            }
        },
        dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Abbrechen") } }
    )

    if (showScanner) {
        QrScannerDialog(
            onDismiss = { showScanner = false },
            onScanned = { raw ->
                showScanner = false
                val parsed = parsePairQr(raw)
                if (parsed == null) {
                    qrError = "Das ist kein gueltiger Notizen-Geraeteanmeldecode."
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    pendingChallenge = parsed
                }
            }
        )
    }

    pendingChallenge?.let { pair ->
        AlertDialog(
            onDismissRequest = { if (!approvingQr) pendingChallenge = null },
            title = { Text("Browser-Anmeldung bestaetigen?") },
            text = {
                Column {
                    Text("QR-Code fuer: ${pair.host}", fontWeight = FontWeight.SemiBold)
                    Text("Nur bestaetigen, wenn du die Anmeldung in diesem Browser selbst gestartet hast. Der Code ist nur kurze Zeit gueltig.", modifier = Modifier.padding(top = 8.dp))
                    qrError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !approvingQr, onClick = {
                    scope.launch {
                        approvingQr = true
                        qrError = null
                        try {
                            vm.approveDeviceLogin(pair.challenge)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingChallenge = null
                            snackbar.showSnackbar("Browser-Anmeldung freigegeben.")
                        } catch (error: Exception) {
                            qrError = error.message ?: "Anmeldung konnte nicht freigegeben werden."
                        } finally { approvingQr = false }
                    }
                }) {
                    if (approvingQr) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Anmeldung freigeben")
                }
            },
            dismissButton = { TextButton(enabled = !approvingQr, onClick = { pendingChallenge = null; qrError = null }) { Text("Abbrechen") } }
        )
    }

    if (!qrError.isNullOrBlank() && pendingChallenge == null && !showScanner) {
        LaunchedEffect(qrError) {
            snackbar.showSnackbar(qrError.orEmpty())
            qrError = null
        }
    }

    menuNote?.let { note ->
        NoteActionsDialog(
            note = note, tab = state.tab,
            onDismiss = { menuNote = null },
            onPin = { vm.toggleMeta(note, "pinned", !note.pinned); menuNote = null },
            onArchive = { vm.toggleMeta(note, "archived", !note.archived); menuNote = null },
            onTrash = { vm.moveToTrash(note); menuNote = null },
            onRestore = { vm.toggleMeta(note, "trashed", false); menuNote = null },
            onDelete = { vm.deleteForever(note); menuNote = null }
        )
    }
}

@Composable
private fun NoteActionsDialog(
    note: NoteData, tab: String, onDismiss: () -> Unit,
    onPin: () -> Unit, onArchive: () -> Unit, onTrash: () -> Unit,
    onRestore: () -> Unit, onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(note.title.ifBlank { if (note.isList) "Checkliste" else "Notiz" }) },
        text = {
            Column {
                if (tab == "trash") {
                    TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Wiederherstellen") }
                    if (note.role == "owner") TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Endgueltig loeschen") }
                } else {
                    TextButton(onClick = onPin, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PushPin, null); Spacer(Modifier.width(8.dp)); Text(if (note.pinned) "Losloesen" else "Anheften") }
                    TextButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(8.dp)); Text(if (note.archived) "Aus Archiv holen" else "Archivieren") }
                    if (note.role == "owner") TextButton(onClick = onTrash, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("In Papierkorb") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } }
    )
}

@Composable
private fun NoteCard(note: NoteData, onOpen: () -> Unit, onMore: () -> Unit) {
    val color by androidx.compose.animation.animateColorAsState(noteColor(note.color), animationSpec = tween(260), label = "note-color")
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(200)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.title.ifBlank { if (note.isList) "Checkliste" else "Notiz" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (note.pinned) Icon(Icons.Default.PushPin, contentDescription = "Angeheftet", tint = Color(0xFF145CA8), modifier = Modifier.size(18.dp))
                IconButton(onClick = onMore, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "Notizaktionen") }
            }
            val preview = if (note.isList && note.items.isNotEmpty()) note.items.joinToString("  ·  ") { (if (it.checked) "[x] " else "[ ] ") + it.text }
            else note.bodyForEditor
            if (preview.isNotBlank()) Text(preview, modifier = Modifier.padding(top = 5.dp), maxLines = 5, overflow = TextOverflow.Ellipsis, color = Color(0xFF39434D), style = MaterialTheme.typography.bodyMedium)
            if (note.contentDeferred) Text("Archivinhalt wird beim Oeffnen geladen", style = MaterialTheme.typography.labelSmall, color = Color(0xFF66737E), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun EditorScreen(
    note: NoteData?, creatingList: Boolean, busy: Boolean, error: String?, readOnly: Boolean,
    onBack: () -> Unit, onSave: (String, String, Boolean, List<NoteItem>) -> Unit
) {
    val key = note?.id ?: if (creatingList) -1 else -2
    val haptics = LocalHapticFeedback.current
    var title by rememberSaveable(key) { mutableStateOf(note?.title.orEmpty()) }
    var body by rememberSaveable(key) { mutableStateOf(note?.bodyForEditor.orEmpty()) }
    var isList by rememberSaveable(key) { mutableStateOf(note?.isList ?: creatingList) }
    val items = remember(key) { mutableStateListOf<NoteItem>().apply { addAll(note?.items.orEmpty()) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (note == null) "Neue Notiz" else "Notiz bearbeiten", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Zurueck") } },
                actions = {
                    TextButton(enabled = !busy && !readOnly, onClick = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); onSave(title, body, isList, items.toList()) }) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Speichern")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (readOnly) {
                Text("Offline: gespeicherte Notiz, schreibgeschuetzt.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 10.dp))
            }
            OutlinedTextField(
                value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Titel") }, textStyle = MaterialTheme.typography.titleLarge,
                singleLine = false, minLines = 1, maxLines = 3, readOnly = readOnly
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Checkliste", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                androidx.compose.material3.Switch(checked = isList, onCheckedChange = { isList = it }, enabled = !readOnly)
            }
            if (isList) {
                items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = item.checked, onCheckedChange = { checked -> haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); items[index] = item.copy(checked = checked) }, enabled = !readOnly)
                        OutlinedTextField(
                            value = item.text,
                            onValueChange = { text -> items[index] = item.copy(text = text) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Listenelement") },
                            singleLine = true, readOnly = readOnly
                        )
                        TextButton(enabled = !readOnly, onClick = { items.removeAt(index) }) { Text("Entfernen") }
                    }
                }
                TextButton(enabled = !readOnly, onClick = { items.add(makeItem(pos = (items.size + 1).toDouble())) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Element hinzufuegen") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                label = { Text(if (isList) "Zusatztext" else "Notiztext") },
                placeholder = { Text("Deine Gedanken...") }, minLines = 7, readOnly = readOnly
            )
            Text("Einfache Textbearbeitung. Formatierungen und Bilder werden in dieser mobilen Version nicht bearbeitet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(90.dp))
        }
    }
}

@Composable
private fun LinearLoading() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
    }
}

private fun noteColor(color: String): Color = when (color) {
    "red" -> Color(0xFFFFE4E1)
    "orange" -> Color(0xFFFFEBD7)
    "yellow" -> Color(0xFFFFF4C2)
    "green" -> Color(0xFFE3F2DF)
    "teal" -> Color(0xFFDFF3F1)
    "blue" -> Color(0xFFE0EDFF)
    "darkblue" -> Color(0xFFDCE6F3)
    "purple" -> Color(0xFFEDE4FA)
    "pink" -> Color(0xFFFBE4EE)
    "brown" -> Color(0xFFECE1D8)
    "gray" -> Color(0xFFE9EDF0)
    else -> Color.White
}

private data class PairToken(val challenge: String, val host: String)

private fun parsePairQr(raw: String): PairToken? {
    return runCatching {
        val uri = Uri.parse(raw)
        if (uri.scheme !in setOf("http", "https")) null else {
            val fragmentQuery = uri.fragment?.let { fragment ->
                val query = fragment.removePrefix("?")
                Uri.parse("https://qr.invalid/?$query").getQueryParameter("pair")
            }
            val challenge = uri.getQueryParameter("pair") ?: fragmentQuery
            if (challenge == null || challenge.length !in 32..256) null
            else PairToken(challenge, uri.host ?: "unbekannter Server")
        }
    }.getOrNull()
}
