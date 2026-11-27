package de.cracky.notizen.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.gson.JsonPrimitive
import de.cracky.notizen.NotesViewModel
import de.cracky.notizen.SearchFilter
import de.cracky.notizen.ViewKey
import de.cracky.notizen.model.Change
import de.cracky.notizen.model.Delta
import de.cracky.notizen.model.DocItem
import de.cracky.notizen.model.LabelData
import de.cracky.notizen.model.NoteData
import de.cracky.notizen.model.NoteDoc
import de.cracky.notizen.model.SubOp
import de.cracky.notizen.model.rid
import de.cracky.notizen.util.Media
import de.cracky.notizen.util.PickedFile
import de.cracky.notizen.util.formatBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.text.DateFormat
import java.util.Date

private val KeepYellow = Color(0xFFFBBC04)
private val KeepPalette = listOf(
    "default" to "Standard", "red" to "Rot", "orange" to "Orange", "yellow" to "Gelb",
    "green" to "Grün", "teal" to "Türkis", "blue" to "Blau", "darkblue" to "Dunkelblau",
    "purple" to "Lila", "pink" to "Rosa", "brown" to "Braun", "gray" to "Grau"
)
private val LightNoteColors = mapOf(
    "default" to Color(0xFFFFFFFF), "red" to Color(0xFFFAAFA8), "orange" to Color(0xFFF39F76),
    "yellow" to Color(0xFFFFF8B8), "green" to Color(0xFFE2F6D3), "teal" to Color(0xFFB4DDD3),
    "blue" to Color(0xFFD4E4ED), "darkblue" to Color(0xFFAECCDC), "purple" to Color(0xFFD3BFDB),
    "pink" to Color(0xFFF6E2DD), "brown" to Color(0xFFE9E3D4), "gray" to Color(0xFFEFEFF1)
)
private val DarkNoteColors = mapOf(
    "default" to Color(0xFF202124), "red" to Color(0xFF77172E), "orange" to Color(0xFF692B17),
    "yellow" to Color(0xFF7C4A03), "green" to Color(0xFF264D3B), "teal" to Color(0xFF0C625D),
    "blue" to Color(0xFF256377), "darkblue" to Color(0xFF284255), "purple" to Color(0xFF472E5B),
    "pink" to Color(0xFF6C394F), "brown" to Color(0xFF4B443A), "gray" to Color(0xFF232427)
)

@Composable
fun NotizenApp(viewModel: NotesViewModel? = null) {
    val vm = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel<NotesViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (state.themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
    val colors = remember(dark) { keepScheme(dark) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            val result = snackbar.showSnackbar(
                message = msg.text,
                actionLabel = msg.actionLabel,
                withDismissAction = msg.important,
                duration = if (msg.important) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) msg.action?.invoke()
        }
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = colors.background.toArgbCompat()
            window.navigationBarColor = colors.background.toArgbCompat()
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = KeepYellow, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Lightbulb, null, tint = Color(0xFF202124), modifier = Modifier.padding(9.dp))
                        }
                        Text("Notizen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (state.user == null) {
                LoginScreen(state.serverUrl, state.loginBusy, state.loginError, vm)
            } else {
                MainNotesScreen(state, vm, snackbar)
            }
        }
    }
}

private fun Color.toArgbCompat() = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

private fun keepScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF202124), secondary = Color(0xFFFDD663),
    background = Color(0xFF202124), surface = Color(0xFF202124), surfaceVariant = Color(0xFF303134),
    onSurface = Color(0xFFE8EAED), onSurfaceVariant = Color(0xFFBDC1C6), outline = Color(0xFF5F6368),
    error = Color(0xFFF28B82)
) else lightColorScheme(
    primary = Color(0xFF1A73E8), onPrimary = Color.White, secondary = Color(0xFF5F6368),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF1F3F4),
    onSurface = Color(0xFF202124), onSurfaceVariant = Color(0xFF5F6368), outline = Color(0xFFDADCE0),
    error = Color(0xFFB3261E)
)

@Composable
private fun LoginScreen(server: String, busy: Boolean, error: String?, vm: NotesViewModel) {
    var url by remember(server) { mutableStateOf(server) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var magic by remember { mutableStateOf("") }
    var showMagic by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().widthIn(max = 440.dp).padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = KeepYellow, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(72.dp)) {
                Icon(Icons.Default.Lightbulb, null, tint = Color(0xFF202124), modifier = Modifier.padding(18.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Notizen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Deine Gedanken. Sicher synchronisiert.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp, bottom = 26.dp))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Server-Adresse") }, singleLine = true, keyboardOptions = KeyboardOptions.Default)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Benutzername") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Button(onClick = { vm.login(url.trim(), username.trim(), password) }, enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp), shape = RoundedCornerShape(14.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Anmelden")
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { showMagic = true }) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Anmeldelink") }
                TextButton(onClick = { showQr = true }) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("QR-Code") }
            }
            Text("Verbindung nur zu deinem konfigurierten Notizen-Server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (showMagic) AlertDialog(
        onDismissRequest = { showMagic = false }, title = { Text("Mit Anmeldelink anmelden") },
        text = { OutlinedTextField(magic, { magic = it }, Modifier.fillMaxWidth(), label = { Text("Link einfügen") }, minLines = 2) },
        confirmButton = { TextButton(onClick = { showMagic = false; vm.loginWithMagicLink(magic) }) { Text("Anmelden") } },
        dismissButton = { TextButton(onClick = { showMagic = false }) { Text("Abbrechen") } }
    )
    if (showQr) QrScannerDialog(onDismiss = { showQr = false }, onScanned = { showQr = false; vm.loginWithMagicLink(it) })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainNotesScreen(state: de.cracky.notizen.AppState, vm: NotesViewModel, snackbar: SnackbarHostState) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showCreateLabel by remember { mutableStateOf(false) }
    var labelToManage by remember { mutableStateOf<LabelData?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    val filtered = remember(state.notes, state.view, state.searchActive, state.query, state.searchIds, state.filter, state.selected) {
        state.notes.values.filter { n ->
            val inView = when (state.view.kind) {
                "archive" -> n.archived && !n.trashed
                "trash" -> n.trashed
                "label" -> !n.archived && !n.trashed && state.view.labelId?.let { it in n.labels } == true
                else -> !n.archived && !n.trashed
            }
            val inSearch = if (state.searchActive && state.query.isNotBlank()) {
                val ids = state.searchIds
                if (ids != null) n.id in ids else n.plainText().contains(state.query, true)
            } else true
            val f = state.filter
            val inFilter = when (f?.kind) {
                "type" -> if (f.value == "list") n.doc.isList else !n.doc.isList
                "thing" -> when (f.value) { "image" -> n.images.isNotEmpty(); "pdf" -> n.pdfs.isNotEmpty(); else -> true }
                "label" -> f.value.toIntOrNull()?.let { it in n.labels } ?: false
                "color" -> n.color == f.value
                else -> true
            }
            inView && inSearch && inFilter
        }.sortedWith(compareByDescending<NoteData> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.position })
    }
    BackHandler(enabled = state.editor != null || state.searchActive || state.selected.isNotEmpty() || drawer.isOpen) {
        when {
            state.editor != null -> vm.closeEditor()
            state.searchActive -> vm.closeSearch()
            state.selected.isNotEmpty() -> vm.clearSelection()
            drawer.isOpen -> scope.launch { drawer.close() }
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 330.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 26.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = KeepYellow, shape = RoundedCornerShape(11.dp), modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Lightbulb, null, tint = Color(0xFF202124), modifier = Modifier.padding(8.dp)) }
                    Text("Notizen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                }
                NavigationDrawerItem(label = { Text("Notizen") }, selected = state.view.kind == "notes", icon = { Icon(Icons.Default.Lightbulb, null) }, onClick = { vm.setView(ViewKey.NOTES); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Archiv") }, selected = state.view.kind == "archive", icon = { Icon(Icons.Default.Archive, null) }, onClick = { vm.setView(ViewKey.ARCHIVE); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Papierkorb") }, selected = state.view.kind == "trash", icon = { Icon(Icons.Default.Delete, null) }, onClick = { vm.setView(ViewKey.TRASH); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Labels", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showCreateLabel = true }) { Icon(Icons.Default.Add, "Label hinzufügen") }
                }
                state.labels.forEach { label ->
                    NavigationDrawerItem(
                        label = { Text(label.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = state.view.kind == "label" && state.view.labelId == label.id,
                        icon = { Icon(Icons.Default.Label, null) },
                        badge = { if (label.shared) Icon(Icons.Default.People, "Geteilt", modifier = Modifier.size(16.dp)) },
                        onClick = { vm.setView(ViewKey("label", label.id)); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp).combinedClickable(onClick = { vm.setView(ViewKey("label", label.id)); scope.launch { drawer.close() } }, onLongClick = { labelToManage = label })
                    )
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                ListItem(
                    headlineContent = { Text(state.user?.username ?: "Konto") },
                    supportingContent = { Text(if (state.online) "Synchronisiert" else "Offline - Änderungen bleiben lokal") },
                    leadingContent = { Icon(if (state.online) Icons.Default.CloudDone else Icons.Default.CloudOff, null) },
                    trailingContent = { IconButton(onClick = { showSettings = true; scope.launch { drawer.close() } }) { Icon(Icons.Default.Settings, "Einstellungen") } },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (state.selected.isNotEmpty()) {
                    SelectionTopBar(state, vm) { if (state.view.kind == "trash") confirmPermanent = true else confirmTrash = true }
                } else {
                    MainTopBar(state, vm, onMenu = { scope.launch { drawer.open() } }, onSettings = { showSettings = true }, onFilter = { showFilter = true })
                }
            },
            floatingActionButton = {
                if (state.view.kind != "trash" && state.editor == null && state.selected.isEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(expanded) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                SmallFabAction("Neue Liste", Icons.Default.Checklist) { expanded = false; vm.newNote(true) }
                                SmallFabAction("Neue Notiz", Icons.Default.EditNote) { expanded = false; vm.newNote(false) }
                            }
                        }
                        FloatingActionButton(onClick = { expanded = !expanded }, containerColor = KeepYellow, contentColor = Color(0xFF202124), shape = RoundedCornerShape(18.dp)) {
                            Icon(if (expanded) Icons.Default.Close else Icons.Default.Add, if (expanded) "Schließen" else "Neue Notiz")
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.searchActive) SearchFilterChips(state, vm, onOpenFilter = { showFilter = true })
                if (state.view.kind == "trash" && filtered.isNotEmpty()) {
                    TextButton(onClick = { confirmTrash = true }, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) { Text("Papierkorb leeren") }
                }
                if (filtered.isEmpty()) {
                    EmptyNotesState(state, vm)
                } else if (state.gridLayout) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 104.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { note -> NoteCard(note, state, vm, onOpen = { openNoteOrRestore(note, state, vm) }, onSelectTrash = { confirmTrash = true }) }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { note -> NoteCard(note, state, vm, onOpen = { openNoteOrRestore(note, state, vm) }, onSelectTrash = { confirmTrash = true }) }
                    }
                }
            }
        }
    }
    state.editor?.let { editor -> EditorDialog(state, editor, vm) }
    if (showSettings) SettingsDialog(state, vm, onDismiss = { showSettings = false })
    if (showFilter) FilterDialog(state, vm, onDismiss = { showFilter = false })
    if (showCreateLabel) LabelNameDialog("Neues Label", "", onDismiss = { showCreateLabel = false }) { name -> scope.launch { vm.createLabel(name) }; showCreateLabel = false }
    labelToManage?.let { label -> LabelManageDialog(label, state, vm, onDismiss = { labelToManage = null }) }
    if (confirmPermanent) AlertDialog(
        onDismissRequest = { confirmPermanent = false },
        title = { Text("Endgültig löschen?") },
        text = { Text("${state.selected.size} ausgewählte Notiz(en) können danach nicht wiederhergestellt werden.") },
        confirmButton = { TextButton(onClick = { vm.deleteForever(state.selected); confirmPermanent = false }) { Text("Endgültig löschen") } },
        dismissButton = { TextButton(onClick = { confirmPermanent = false }) { Text("Abbrechen") } }
    )
    if (confirmTrash) AlertDialog(
        onDismissRequest = { confirmTrash = false },
        title = { Text(if (state.view.kind == "trash") "Papierkorb leeren?" else "Notiz löschen?") },
        text = { Text(if (state.view.kind == "trash") "Alle Notizen im Papierkorb werden endgültig gelöscht." else "Die ausgewählten Notizen werden in den Papierkorb verschoben.") },
        confirmButton = { TextButton(onClick = { if (state.view.kind == "trash") vm.emptyTrash() else vm.trash(state.selected); confirmTrash = false }) { Text(if (state.view.kind == "trash") "Leeren" else "Löschen") } },
        dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Abbrechen") } }
    )
}

private fun openNoteOrRestore(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel) {
    if (state.view.kind == "trash") return
    if (state.selected.isNotEmpty()) vm.toggleSelect(note.id) else vm.openNote(note.id)
}

@Composable
private fun MainTopBar(state: de.cracky.notizen.AppState, vm: NotesViewModel, onMenu: () -> Unit, onSettings: () -> Unit, onFilter: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Navigation öffnen") }
            if (state.searchActive) {
                OutlinedTextField(
                    value = state.query, onValueChange = vm::setQuery,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    placeholder = { Text("Notizen durchsuchen") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Suche leeren") } },
                    shape = RoundedCornerShape(28.dp)
                )
                IconButton(onClick = vm::closeSearch) { Icon(Icons.Default.Close, "Suche schließen") }
            } else {
                Text(
                    when (state.view.kind) { "archive" -> "Archiv"; "trash" -> "Papierkorb"; "label" -> state.labels.firstOrNull { it.id == state.view.labelId }?.name ?: "Label"; else -> "Notizen" },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = vm::openSearch) { Icon(Icons.Default.Search, "Suchen") }
                IconButton(onClick = onFilter) { Icon(Icons.Default.FilterList, "Suchfilter") }
                IconButton(onClick = vm::toggleLayout) { Icon(if (state.gridLayout) Icons.Default.ViewAgenda else Icons.Default.GridView, "Ansicht wechseln") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
            }
        }
    }
}

@Composable
private fun SelectionTopBar(state: de.cracky.notizen.AppState, vm: NotesViewModel, onTrash: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Auswahl aufheben") }
            Text("${state.selected.size} ausgewählt", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { colors = true }) { Icon(Icons.Default.Palette, "Farbe") }
            IconButton(onClick = { labels = true }) { Icon(Icons.Default.Label, "Labels") }
            IconButton(onClick = { vm.copy(state.selected) }) { Icon(Icons.Default.ContentCopy, "Kopieren") }
            IconButton(onClick = { if (state.view.kind == "trash") vm.restore(state.selected) else vm.setArchived(state.selected, state.view.kind != "archive") }) {
                Icon(when (state.view.kind) { "trash" -> Icons.Default.RestoreFromTrash; "archive" -> Icons.Default.Unarchive; else -> Icons.Default.Archive }, if (state.view.kind == "trash") "Wiederherstellen" else "Archivieren")
            }
            IconButton(onClick = onTrash) { Icon(if (state.view.kind == "trash") Icons.Default.DeleteForever else Icons.Default.Delete, "Löschen") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Weitere Aktionen") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Anheften / lösen") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { vm.setPinned(state.selected, state.selected.any { state.notes[it]?.pinned != true }); menu = false })
                DropdownMenuItem(text = { Text("Alle auswählen") }, leadingIcon = { Icon(Icons.Default.SelectAll, null) }, onClick = { vm.selectAll(state.notes.values.filter { when (state.view.kind) { "archive" -> it.archived && !it.trashed; "trash" -> it.trashed; else -> !it.archived && !it.trashed } }.map { it.id }); menu = false })
            }
        }
    }
    if (colors) ColorPickerDialog(onDismiss = { colors = false }) { vm.setColor(state.selected, it); colors = false }
    if (labels) MultiLabelDialog(state, vm, onDismiss = { labels = false })
}

@Composable
private fun SmallFabAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ExtendedFloatingActionButton(onClick = onClick, icon = { Icon(icon, null) }, text = { Text(label) }, containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onOpen: () -> Unit, onSelectTrash: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var labelPicker by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    val selected = note.id in state.selected
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.2f
    val bg = (if (dark) DarkNoteColors else LightNoteColors)[note.color] ?: MaterialTheme.colorScheme.surface
    val fg = if (bg.luminance() < 0.38f) Color(0xFFF1F3F4) else Color(0xFF202124)
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (state.view.kind == "trash") { if (state.selected.isNotEmpty()) vm.toggleSelect(note.id) } else onOpen() },
            onLongClick = { vm.toggleSelect(note.id) }
        ),
        shape = RoundedCornerShape(13.dp), colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else if (note.color == "default") MaterialTheme.colorScheme.outline.copy(alpha = .55f) else Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Box {
                if (note.images.isNotEmpty()) {
                    val refs = note.images.take(4)
                    Column(Modifier.fillMaxWidth().height(if (refs.size == 1) 148.dp else 184.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        refs.chunked(2).forEach { rowImages ->
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                rowImages.forEach { image -> AsyncImage(vm.thumbUrl(image.id), null, Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                            }
                        }
                    }
                } else if (note.pdfs.isNotEmpty()) {
                    AsyncImage(vm.pdfThumbUrl(note.pdfs.first().id), null, Modifier.fillMaxWidth().height(116.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                if (note.pinned) Icon(Icons.Default.PushPin, "Angeheftet", Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), tint = fg.copy(alpha = .7f))
                if (selected) Surface(Modifier.align(Alignment.TopStart).padding(8.dp).size(27.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(5.dp)) }
                if (note.shared) Icon(Icons.Default.People, "Geteilt", Modifier.align(Alignment.TopStart).padding(10.dp).size(18.dp), tint = fg.copy(alpha = .75f))
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (note.doc.title.isNotBlank()) Text(note.doc.title, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = fg, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (note.doc.isList && note.doc.items.isNotEmpty()) {
                    note.doc.items.take(4).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                            Icon(if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, modifier = Modifier.size(17.dp), tint = fg.copy(alpha = .72f))
                            Text(item.text.ifBlank { "Leeres Listenelement" }, modifier = Modifier.padding(start = 7.dp), color = fg.copy(alpha = if (item.checked) .55f else .9f), textDecoration = if (item.checked) TextDecoration.LineThrough else null, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (note.doc.items.size > 4) Text("+ ${note.doc.items.size - 4} weitere", color = fg.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                } else if (note.doc.bodyText.isNotBlank()) {
                    Text(note.doc.bodyText, color = fg.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (note.doc.title.isNotBlank()) 4.dp else 0.dp))
                }
                if (note.pdfs.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = .08f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PictureAsPdf, null, tint = fg, modifier = Modifier.size(20.dp))
                        Text(note.pdfs.first().name, Modifier.weight(1f).padding(start = 7.dp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        if (note.pdfs.size > 1) Text("+${note.pdfs.size - 1}", color = fg, style = MaterialTheme.typography.labelSmall)
                    }
                }
                val labels = state.labels.filter { it.id in note.labels }
                if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    labels.take(3).forEach { label -> Surface(color = fg.copy(alpha = .1f), shape = RoundedCornerShape(20.dp)) { Text(label.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = fg.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall, maxLines = 1) } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (note.updatedAt > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(note.updatedAt * 1000)) else "", color = fg.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Notizaktionen", tint = fg.copy(alpha = .65f), modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (state.view.kind == "trash") {
                            DropdownMenuItem(text = { Text("Wiederherstellen") }, leadingIcon = { Icon(Icons.Default.RestoreFromTrash, null) }, onClick = { vm.restore(listOf(note.id)); menu = false })
                            DropdownMenuItem(text = { Text("Endgültig löschen") }, leadingIcon = { Icon(Icons.Default.DeleteForever, null) }, onClick = { confirmPermanent = true; menu = false })
                        } else {
                            DropdownMenuItem(text = { Text(if (note.pinned) "Lösen" else "Anheften") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { vm.setPinned(listOf(note.id), !note.pinned); menu = false })
                            DropdownMenuItem(text = { Text(if (note.archived) "Aus Archiv holen" else "Archivieren") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { vm.setArchived(listOf(note.id), !note.archived); menu = false })
                            DropdownMenuItem(text = { Text("Farbe ändern") }, leadingIcon = { Icon(Icons.Default.Palette, null) }, onClick = { colorPicker = true; menu = false })
                            DropdownMenuItem(text = { Text("Labels bearbeiten") }, leadingIcon = { Icon(Icons.Default.Label, null) }, onClick = { labelPicker = true; menu = false })
                            DropdownMenuItem(text = { Text("Kopie erstellen") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { vm.copy(listOf(note.id)); menu = false })
                            DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { vm.trash(listOf(note.id)); menu = false })
                        }
                    }
                }
            }
        }
    }
    if (confirmPermanent) AlertDialog(onDismissRequest = { confirmPermanent = false }, title = { Text("Endgültig löschen?") }, text = { Text("Die Notiz kann danach nicht wiederhergestellt werden.") }, confirmButton = { TextButton(onClick = { vm.deleteForever(listOf(note.id)); confirmPermanent = false }) { Text("Löschen") } }, dismissButton = { TextButton(onClick = { confirmPermanent = false }) { Text("Abbrechen") } })
    if (colorPicker) ColorPickerDialog(onDismiss = { colorPicker = false }) { vm.setColor(listOf(note.id), it); colorPicker = false }
    if (labelPicker) LabelPickerDialog(state.labels, note.labels, onDismiss = { labelPicker = false }) { vm.setLabels(note.id, it); labelPicker = false }
}

@Composable
private fun EmptyNotesState(state: de.cracky.notizen.AppState, vm: NotesViewModel) {
    val (icon, title, caption) = when {
        state.searchActive && state.query.isNotBlank() -> Triple(Icons.Default.SearchOff, "Keine Treffer", "Versuche einen anderen Suchbegriff oder Filter.")
        state.view.kind == "trash" -> Triple(Icons.Default.DeleteOutline, "Papierkorb ist leer", "Gelöschte Notizen bleiben hier, bis du sie endgültig entfernst.")
        state.view.kind == "archive" -> Triple(Icons.Default.Archive, "Noch nichts archiviert", "Archivierte Notizen findest du hier wieder.")
        else -> Triple(Icons.Default.Lightbulb, "Deine Notizen erscheinen hier", "Tippe auf +, um eine Idee festzuhalten.")
    }
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        if (state.firstLoad) TextButton(onClick = { vm.refreshAll(showIndicator = true) }, modifier = Modifier.padding(top = 12.dp)) { Icon(Icons.Default.Refresh, null); Text("Jetzt synchronisieren", Modifier.padding(start = 6.dp)) }
    }
}

@Composable
private fun SearchFilterChips(state: de.cracky.notizen.AppState, vm: NotesViewModel, onOpenFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text("Alle") })
        FilterChip(selected = state.filter?.value == "list", onClick = { vm.setFilter(SearchFilter("type", "list", "Listen")) }, label = { Text("Listen") })
        FilterChip(selected = state.filter?.value == "image", onClick = { vm.setFilter(SearchFilter("thing", "image", "Bilder")) }, label = { Text("Bilder") })
        FilterChip(selected = state.filter?.value == "pdf", onClick = { vm.setFilter(SearchFilter("thing", "pdf", "PDFs")) }, label = { Text("PDFs") })
        AssistChip(onClick = onOpenFilter, label = { Text("Filter") }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) })
    }
}


@Composable
private fun FilterDialog(state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(state.filter?.takeIf { it.kind == "type" }?.value ?: "") }
    var thing by remember { mutableStateOf(state.filter?.takeIf { it.kind == "thing" }?.value ?: "") }
    var label by remember { mutableStateOf(state.filter?.takeIf { it.kind == "label" }?.value?.toIntOrNull()) }
    var color by remember { mutableStateOf(state.filter?.takeIf { it.kind == "color" }?.value ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notizen filtern") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("Typ", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    FilterChip(type == "", { type = "" }, label = { Text("Alle") })
                    FilterChip(type == "note", { type = "note" }, label = { Text("Notizen") })
                    FilterChip(type == "list", { type = "list" }, label = { Text("Listen") })
                }
                Text("Enthält", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    FilterChip(thing == "image", { thing = if (thing == "image") "" else "image" }, label = { Text("Bilder") })
                    FilterChip(thing == "pdf", { thing = if (thing == "pdf") "" else "pdf" }, label = { Text("PDFs") })
                }
                if (state.labels.isNotEmpty()) {
                    Text("Label", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    state.labels.forEach { l -> Row(Modifier.fillMaxWidth().clickable { label = if (label == l.id) null else l.id }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(label == l.id, { label = l.id }); Text(l.name, Modifier.padding(start = 8.dp)) } }
                }
                Text("Farbe", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                ColorSwatches(color) { color = if (color == it) "" else it }
            }
        },
        confirmButton = { TextButton(onClick = {
            val f = when {
                label != null -> SearchFilter("label", label.toString(), state.labels.firstOrNull { it.id == label }?.name ?: "Label")
                thing.isNotBlank() -> SearchFilter("thing", thing, if (thing == "image") "Bilder" else "PDFs")
                type.isNotBlank() -> SearchFilter("type", type, if (type == "list") "Listen" else "Notizen")
                color.isNotBlank() -> SearchFilter("color", color, KeepPalette.firstOrNull { it.first == color }?.second ?: "Farbe")
                else -> null
            }
            vm.setFilter(f); onDismiss()
        }) { Text("Anwenden") } },
        dismissButton = { TextButton(onClick = { vm.setFilter(null); onDismiss() }) { Text("Zurücksetzen") } }
    )
}

@Composable
private fun EditorDialog(state: de.cracky.notizen.AppState, editor: de.cracky.notizen.EditorUi, vm: NotesViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note = editor.noteId?.let { state.notes[it] }
    var title by remember(editor.key) { mutableStateOf(TextFieldValue(editor.doc.title)) }
    var body by remember(editor.key) { mutableStateOf(TextFieldValue(editor.doc.bodyText)) }
    LaunchedEffect(editor.key, editor.doc.title) {
        if (title.text != editor.doc.title) title = TextFieldValue(editor.doc.title, selection = androidx.compose.ui.text.TextRange(editor.doc.title.length))
    }
    LaunchedEffect(editor.key, editor.doc.bodyText) {
        if (body.text != editor.doc.bodyText) body = TextFieldValue(editor.doc.bodyText, selection = androidx.compose.ui.text.TextRange(minOf(body.selection.end, editor.doc.bodyText.length)))
    }
    var showColors by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var showCollaborators by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var imagePreview by remember { mutableStateOf<Int?>(null) }
    var draftColor by remember(editor.key) { mutableStateOf(note?.color ?: "default") }
    LaunchedEffect(note?.id, note?.color) { if (note != null) draftColor = note.color }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.map { Media.describe(context, it) }.filter { it.isImage || it.isPdf }
        if (files.isNotEmpty()) vm.addAttachments(files)
        else if (uris.isNotEmpty()) vm.message("Bitte Bilder oder PDF-Dateien auswählen.")
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) vm.addAttachments(listOf(Media.describe(context, uri)))
        cameraUri = null
    }
    var attachmentMenu by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
    val noteBg = (if (dark) DarkNoteColors else LightNoteColors)[draftColor] ?: MaterialTheme.colorScheme.surface
    val noteFg = if (noteBg.luminance() < .38f) Color(0xFFF1F3F4) else Color(0xFF202124)
    BackHandler { vm.closeEditor() }
    Dialog(onDismissRequest = { vm.closeEditor() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = noteBg) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::closeEditor) { Icon(Icons.Default.ArrowBack, "Schließen", tint = noteFg) }
                    if (editor.pending || editor.uploading > 0) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = noteFg.copy(alpha = .75f))
                    } else Icon(Icons.Default.CloudDone, "Gespeichert", tint = noteFg.copy(alpha = .6f))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = vm::undo, enabled = editor.canUndo) { Icon(Icons.Default.Undo, "Rückgängig", tint = noteFg) }
                    IconButton(onClick = vm::redo, enabled = editor.canRedo) { Icon(Icons.Default.Redo, "Wiederholen", tint = noteFg) }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Weitere Aktionen", tint = noteFg) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text(if (note?.pinned == true) "Lösen" else "Anheften") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, enabled = note != null, onClick = { note?.let { vm.setPinned(listOf(it.id), !it.pinned) }; showMenu = false })
                            DropdownMenuItem(text = { Text(if (note?.archived == true) "Aus Archiv holen" else "Archivieren") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, enabled = note != null, onClick = { note?.let { vm.setArchived(listOf(it.id), !it.archived) }; vm.closeEditor(); showMenu = false })
                            DropdownMenuItem(text = { Text("Versionen") }, leadingIcon = { Icon(Icons.Default.History, null) }, enabled = note != null, onClick = { showVersions = true; showMenu = false })
                            DropdownMenuItem(text = { Text("Zusammenarbeit") }, leadingIcon = { Icon(Icons.Default.People, null) }, enabled = note != null, onClick = { vm.loadUsers(); showCollaborators = true; showMenu = false })
                            DropdownMenuItem(text = { Text("Kopie erstellen") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, enabled = note != null, onClick = { note?.let { vm.copy(listOf(it.id)) }; showMenu = false })
                            DropdownMenuItem(text = { Text("In Papierkorb") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, enabled = note != null, onClick = { note?.let { vm.trash(listOf(it.id)) }; vm.closeEditor(); showMenu = false })
                            DropdownMenuItem(text = { Text("Notiz teilen") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, editor.doc.title + "\n" + if (editor.doc.isList) editor.doc.items.joinToString("\n") { if (it.checked) "☑ ${it.text}" else "☐ ${it.text}" } else editor.doc.bodyText)
                                context.startActivity(Intent.createChooser(share, "Notiz teilen")); showMenu = false
                            })
                        }
                    }
                }
                if (editor.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                    BasicTextField(
                        value = title, onValueChange = { next ->
                            val delta = diffDelta(title.text, next.text)
                            title = next
                            if (!delta.isEmpty()) vm.applyLocal(listOf(SubOp.Text("title", delta)), "title")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                        textStyle = TextStyle(color = noteFg, fontSize = 23.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp),
                        cursorBrush = SolidColor(noteFg), decorationBox = { inner ->
                            Box { if (title.text.isEmpty()) Text("Titel", color = noteFg.copy(alpha = .58f), fontSize = 23.sp); inner() }
                        }
                    )
                    if (editor.doc.isList) {
                        ChecklistEditor(editor.doc.items, noteFg, state.addItemsBottom, state.checkedBottom,
                            onToggle = { item -> vm.applyLocal(listOf(SubOp.ItemSet(item.id, checked = !item.checked))) },
                            onText = { item, value ->
                                val delta = diffDelta(item.text, value)
                                if (!delta.isEmpty()) vm.applyLocal(listOf(SubOp.Text("item:${item.id}", delta)))
                            },
                            onAdd = { after ->
                                val items = editor.doc.items.sortedBy { it.pos }
                                val pos = if (items.isEmpty()) 1.0 else if (state.addItemsBottom) items.last().pos + 1.0 else items.first().pos - 1.0
                                vm.applyLocal(listOf(SubOp.ItemAdd(rid(), pos, false, "")))
                            },
                            onDelete = { item -> vm.applyLocal(listOf(SubOp.ItemDel(item.id))) },
                            onMove = { item, direction ->
                                val items = editor.doc.items.sortedBy { it.pos }
                                val index = items.indexOfFirst { it.id == item.id }
                                val otherIndex = index + direction
                                if (index >= 0 && otherIndex in items.indices) {
                                    val other = items[otherIndex]
                                    vm.applyLocal(listOf(SubOp.ItemSet(item.id, pos = other.pos), SubOp.ItemSet(other.id, pos = item.pos)))
                                }
                            }
                        )
                    } else {
                        val display = remember(editor.doc.body, body.text) { styledBody(editor.doc.body, body.text) }
                        BasicTextField(
                            value = body, onValueChange = { next ->
                                val delta = diffDelta(body.text, next.text)
                                body = next
                                if (!delta.isEmpty()) vm.applyLocal(listOf(SubOp.Text("body", delta)), "body")
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 230.dp).padding(top = 4.dp, bottom = 16.dp),
                            textStyle = TextStyle(color = noteFg, fontSize = 16.sp, lineHeight = 25.sp),
                            cursorBrush = SolidColor(noteFg), visualTransformation = VisualTransformation { value ->
                                val currentStyled = if (value.text == body.text) display else AnnotatedString(value.text)
                                androidx.compose.ui.text.input.TransformedText(currentStyled, androidx.compose.ui.text.input.OffsetMapping.Identity)
                            },
                            decorationBox = { inner -> Box { if (body.text.isEmpty()) Text("Notiz", color = noteFg.copy(alpha = .58f), fontSize = 16.sp); inner() } }
                        )
                        RichToolbar(noteFg, body, editor.doc.body) { attr, value ->
                            val selection = body.selection.let { minOf(it.start, it.end) to maxOf(it.start, it.end) }
                            val (start, end) = selection
                            val len = if (start == end) body.text.length else end
                            val from = if (start == end) 0 else start
                            if (len <= from) return@RichToolbar
                            val delta = if (attr == "header") {
                                lineHeaderDelta(editor.doc.body.text(), from, len, value)
                            } else Delta().apply {
                                if (from > 0) retain(from)
                                retain(len - from, mapOf(attr to value))
                                chop()
                            }
                            vm.applyLocal(listOf(SubOp.Text("body", delta)))
                        }
                    }
                    if (editor.uploading > 0) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  ${editor.uploading} Datei(en) werden hochgeladen", color = noteFg.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall) }
                    note?.images?.forEach { image ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).clickable { imagePreview = image.id }) {
                            AsyncImage(vm.imageUrl(image.id), null, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                            IconButton(onClick = { vm.deleteImage(note.id, image.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(.45f), CircleShape)) { Icon(Icons.Default.Close, "Bild entfernen", tint = Color.White) }
                        }
                    }
                    note?.pdfs?.forEach { pdf ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(noteFg.copy(alpha = .08f)).clickable {
                            scope.launch { openPdf(context, vm, pdf.id, pdf.name) }
                        }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, null, tint = noteFg, modifier = Modifier.size(34.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(pdf.name, color = noteFg, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("${formatBytes(pdf.size)}  ·  ${pdf.pages} Seiten", color = noteFg.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.deletePdf(note.id, pdf.id) }) { Icon(Icons.Default.DeleteOutline, "PDF entfernen", tint = noteFg) }
                        }
                    }
                    if (note != null && note.labels.isNotEmpty()) Row(Modifier.padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.labels.filter { it.id in note.labels }.forEach { AssistChip(onClick = { showLabels = true }, label = { Text(it.name) }, leadingIcon = { Icon(Icons.Default.Label, null, Modifier.size(16.dp)) }) }
                    }
                }
                HorizontalDivider(color = noteFg.copy(alpha = .15f))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { showColors = true }) { Icon(Icons.Default.Palette, "Notizfarbe", tint = noteFg) }
                    Box {
                        IconButton(onClick = { attachmentMenu = true }) { Icon(Icons.Default.AttachFile, "Bild oder PDF anhängen", tint = noteFg) }
                        DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                            DropdownMenuItem(text = { Text("Bild oder PDF auswählen") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { attachmentMenu = false; picker.launch(arrayOf("image/*", "application/pdf")) })
                            DropdownMenuItem(text = { Text("Foto aufnehmen") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) }, onClick = {
                                attachmentMenu = false
                                runCatching {
                                    val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = java.io.File.createTempFile("notiz_", ".jpg", dir)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    cameraUri = uri
                                    camera.launch(uri)
                                }.onFailure { vm.message(it.message ?: "Kamera konnte nicht gestartet werden.", important = true) }
                            })
                        }
                    }
                    IconButton(onClick = { showLabels = true }) { Icon(Icons.Default.Label, "Labels", tint = noteFg) }
                    if (note != null) {
                        IconButton(onClick = { vm.loadUsers(); showCollaborators = true }) { Icon(Icons.Default.People, "Zusammenarbeit", tint = noteFg) }
                        IconButton(onClick = { showVersions = true }) { Icon(Icons.Default.History, "Versionsverlauf", tint = noteFg) }
                    }
                    IconButton(onClick = { vm.applyLocal(listOf(SubOp.SetList(!editor.doc.isList))) }) { Icon(if (editor.doc.isList) Icons.Default.EditNote else Icons.Default.Checklist, "Listenmodus", tint = noteFg) }
                }
            }
        }
    }
    if (showColors) ColorPickerDialog(draftColor, onDismiss = { showColors = false }) { color ->
        draftColor = color
        if (note != null) vm.setColor(listOf(note.id), color) else vm.editorAction { id -> vm.setColor(listOf(id), color) }
        showColors = false
    }
    if (showLabels) LabelPickerDialog(state.labels, note?.labels.orEmpty(), onDismiss = { showLabels = false }) { selectedLabels ->
        if (note != null) vm.setLabels(note.id, selectedLabels) else vm.editorAction { id -> vm.setLabels(id, selectedLabels) }
        showLabels = false
    }
    if (showCollaborators && note != null) CollaboratorsDialog(note, state, vm, onDismiss = { showCollaborators = false })
    if (showVersions && note != null) VersionsDialog(note, vm, onDismiss = { showVersions = false })
    imagePreview?.let { id -> Dialog(onDismissRequest = { imagePreview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) { Box(Modifier.fillMaxSize().background(Color.Black).clickable { imagePreview = null }, contentAlignment = Alignment.Center) { AsyncImage(vm.imageUrl(id), null, Modifier.fillMaxWidth().padding(16.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit); IconButton(onClick = { imagePreview = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Default.Close, "Schließen", tint = Color.White) } } } }
}

@Composable
private fun ChecklistEditor(items: List<DocItem>, fg: Color, addBottom: Boolean, checkedBottom: Boolean, onToggle: (DocItem) -> Unit, onText: (DocItem, String) -> Unit, onAdd: (DocItem?) -> Unit, onDelete: (DocItem) -> Unit, onMove: (DocItem, Int) -> Unit) {
    val sorted = items.sortedWith(compareBy<DocItem>({ if (checkedBottom && it.checked) 1 else 0 }, { it.pos }))
    Column {
        sorted.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item.checked, { onToggle(item) }, modifier = Modifier.size(42.dp), colors = CheckboxDefaults.colors(checkedColor = fg, uncheckedColor = fg.copy(alpha = .62f), checkmarkColor = MaterialTheme.colorScheme.background))
                var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text)) }
                LaunchedEffect(item.id, item.text) { if (value.text != item.text) value = TextFieldValue(item.text, selection = androidx.compose.ui.text.TextRange(minOf(value.selection.end, item.text.length))) }
                BasicTextField(value, { next -> value = next; onText(item, next.text) }, Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp), textStyle = TextStyle(color = fg.copy(alpha = if (item.checked) .56f else 1f), fontSize = 16.sp, textDecoration = if (item.checked) TextDecoration.LineThrough else null), cursorBrush = SolidColor(fg), decorationBox = { inner -> Box { if (value.text.isEmpty()) Text("Listenelement", color = fg.copy(alpha = .48f)); inner() } })
                var actions by remember(item.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { actions = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Elementaktionen", tint = fg.copy(alpha = .65f), modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text("Nach oben") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { onMove(item, -1); actions = false })
                        DropdownMenuItem(text = { Text("Nach unten") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { onMove(item, 1); actions = false })
                        DropdownMenuItem(text = { Text("Entfernen") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { onDelete(item); actions = false })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { onAdd(sorted.lastOrNull()) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, Modifier.padding(horizontal = 10.dp), tint = fg.copy(alpha = .6f))
            Text(if (addBottom) "Listenelement hinzufügen" else "Neues Element", color = fg.copy(alpha = .6f))
        }
    }
}

@Composable
private fun RichToolbar(fg: Color, body: TextFieldValue, delta: Delta, onFormat: (String, Any?) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val hasSelection = body.selection.start != body.selection.end
        val start = minOf(body.selection.start, body.selection.end)
        val end = maxOf(body.selection.start, body.selection.end)
        val bold = selectedHasFormat(delta, start, end, "bold", true)
        val italic = selectedHasFormat(delta, start, end, "italic", true)
        val underline = selectedHasFormat(delta, start, end, "underline", true)
        val header1 = selectedLinesHaveHeader(delta, start, end, 1)
        val header2 = selectedLinesHaveHeader(delta, start, end, 2)
        FormatButton("B", "Fett", bold, FontWeight.Bold) { if (hasSelection) onFormat("bold", if (bold) null else true) }
        FormatButton("I", "Kursiv", italic, style = FontStyle.Italic) { if (hasSelection) onFormat("italic", if (italic) null else true) }
        FormatButton("U", "Unterstrichen", underline, underline = true) { if (hasSelection) onFormat("underline", if (underline) null else true) }
        TextButton(onClick = { if (hasSelection) onFormat("header", if (header1) null else 1) }, enabled = hasSelection) { Text("H1", color = fg, fontWeight = if (header1) FontWeight.Bold else FontWeight.Normal) }
        TextButton(onClick = { if (hasSelection) onFormat("header", if (header2) null else 2) }, enabled = hasSelection) { Text("H2", color = fg, fontWeight = if (header2) FontWeight.Bold else FontWeight.Normal) }
        if (!hasSelection) Text("Text markieren zum Formatieren", color = fg.copy(alpha = .5f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FormatButton(label: String, description: String, selected: Boolean, weight: FontWeight? = null, style: FontStyle? = null, underline: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = true, shape = RoundedCornerShape(8.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .4f))) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = weight, fontStyle = style, textDecoration = if (underline) TextDecoration.Underline else null, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun selectedHasFormat(delta: Delta, start: Int, end: Int, key: String, expected: Any): Boolean {
    if (start >= end) return false
    var cursor = 0
    var covered = 0
    var allMatch = true
    delta.ops.forEach { op ->
        val text = op.insert ?: return@forEach
        val from = maxOf(start, cursor)
        val to = minOf(end, cursor + text.length)
        if (to > from) {
            covered += to - from
            if (op.attributes?.get(key) != expected) allMatch = false
        }
        cursor += text.length
    }
    return covered == end - start && allMatch
}

private fun selectedLinesHaveHeader(delta: Delta, selectionStart: Int, selectionEnd: Int, header: Int): Boolean {
    val text = delta.text()
    if (selectionStart >= selectionEnd || text.isEmpty()) return false
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val firstLine = (text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) -1 else it } + 1).coerceAtLeast(0)
    val lastNewline = text.indexOf('\n', end).let { if (it < 0) text.length - 1 else it }
    val breaks = (firstLine until text.length).filter { text[it] == '\n' && it <= lastNewline }
    if (breaks.isEmpty()) return false
    return breaks.all { position ->
        var cursor = 0
        var headerValue: Any? = null
        for (op in delta.ops) {
            val inserted = op.insert ?: continue
            if (position in cursor until cursor + inserted.length) {
                headerValue = op.attributes?.get("header")
                break
            }
            cursor += inserted.length
        }
        (headerValue as? Number)?.toInt() == header
    }
}

private fun lineHeaderDelta(fullText: String, selectionStart: Int, selectionEnd: Int, value: Any?): Delta {
    val start = selectionStart.coerceIn(0, fullText.length)
    val end = selectionEnd.coerceIn(start, fullText.length)
    val firstLine = (fullText.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) -1 else it } + 1).coerceAtLeast(0)
    val lastNewline = fullText.indexOf('\n', end).let { if (it < 0) fullText.length - 1 else it }
    val targetEnd = lastNewline.coerceAtLeast(firstLine)
    val delta = Delta()
    var cursor = 0
    var lineStart = firstLine
    while (lineStart <= targetEnd && lineStart < fullText.length) {
        val newline = fullText.indexOf('\n', lineStart).let { if (it < 0) fullText.length else it }
        if (newline > cursor) delta.retain(newline - cursor)
        if (newline < fullText.length) {
            delta.retain(1, mapOf("header" to value))
            cursor = newline + 1
            lineStart = newline + 1
        } else break
    }
    return delta.chop()
}

private fun styledBody(delta: Delta, text: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    var cursor = 0
    delta.ops.forEach { op ->
        val insert = op.insert ?: return@forEach
        val attrs = op.attributes.orEmpty()
        val start = cursor.coerceIn(0, text.length)
        val end = (cursor + insert.length).coerceIn(start, text.length)
        if (end > start) {
            val inline = SpanStyle(
                fontWeight = if (attrs["bold"] == true) FontWeight.Bold else null,
                fontStyle = if (attrs["italic"] == true) FontStyle.Italic else null,
                textDecoration = if (attrs["underline"] == true) TextDecoration.Underline else null
            )
            if (attrs.containsKey("bold") || attrs.containsKey("italic") || attrs.containsKey("underline")) builder.addStyle(inline, start, end)
            val header = (attrs["header"] as? Number)?.toInt() ?: 0
            if (header > 0) {
                var local = 0
                while (local < insert.length) {
                    val nl = insert.indexOf('\n', local)
                    if (nl < 0) break
                    val lineEnd = (cursor + nl).coerceIn(0, text.length)
                    val lineStart = (text.lastIndexOf('\n', (lineEnd - 1).coerceAtLeast(0)).let { if (lineEnd == 0) -1 else it } + 1).coerceIn(0, lineEnd)
                    if (lineEnd > lineStart) builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (header == 1) 22.sp else 19.sp), lineStart, lineEnd)
                    local = nl + 1
                }
            }
        }
        cursor += insert.length
    }
    return builder.toAnnotatedString()
}

private fun diffDelta(old: String, new: String): Delta {
    var prefix = 0
    val minLength = minOf(old.length, new.length)
    while (prefix < minLength && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    val d = Delta()
    if (prefix > 0) d.retain(prefix)
    val removed = old.length - prefix - suffix
    if (removed > 0) d.delete(removed)
    val inserted = new.substring(prefix, new.length - suffix)
    if (inserted.isNotEmpty()) d.insert(inserted)
    return d.chop()
}

@Composable
private fun ColorPickerDialog(selected: String = "default", onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Farbe auswählen") }, text = { ColorSwatches(selected, onSelect) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
}

@Composable
private fun ColorSwatches(selected: String, onSelect: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KeepPalette.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { (id, label) ->
                    val color = (if (dark) DarkNoteColors else LightNoteColors)[id] ?: Color.White
                    Box(Modifier.size(38.dp).clip(CircleShape).background(color).clickable { onSelect(id) }, contentAlignment = Alignment.Center) {
                        if (id == selected) Icon(Icons.Default.Check, label, tint = if (color.luminance() < .4f) Color.White else Color(0xFF202124), modifier = Modifier.size(20.dp))
                        if (id == "default") Box(Modifier.matchParentSize().clip(CircleShape).borderCompat(MaterialTheme.colorScheme.outline))
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.borderCompat(color: Color): Modifier = this.border(1.dp, color, CircleShape)

@Composable
private fun LabelPickerDialog(labels: List<LabelData>, selected: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    var checked by remember { mutableStateOf(selected.toSet()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Labels") }, text = {
        if (labels.isEmpty()) Text("Noch keine Labels vorhanden. Erstelle sie über das Menü.") else Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            labels.forEach { label -> Row(Modifier.fillMaxWidth().clickable { checked = if (label.id in checked) checked - label.id else checked + label.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in checked, { checked = if (it) checked + label.id else checked - label.id }); Text(label.name, Modifier.padding(start = 8.dp)) } }
        }
    }, confirmButton = { TextButton(onClick = { onSave(checked.toList()) }) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun MultiLabelDialog(state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    val ids = state.selected.toList()
    val notes = ids.mapNotNull { state.notes[it] }
    val existing = state.labels.filter { l -> notes.isNotEmpty() && notes.all { l.id in it.labels } }.map { it.id }.toSet()
    var checked by remember { mutableStateOf(existing) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Labels für Auswahl") }, text = {
        if (state.labels.isEmpty()) Text("Es gibt noch keine Labels.") else Column {
            state.labels.forEach { label -> Row(Modifier.fillMaxWidth().clickable { checked = if (label.id in checked) checked - label.id else checked + label.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(label.id in checked, { checked = if (it) checked + label.id else checked - label.id }); Text(label.name, Modifier.padding(start = 8.dp)) } }
        }
    }, confirmButton = { TextButton(onClick = { notes.forEach { vm.setLabels(it.id, checked.toList()) }; onDismiss() }) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun LabelNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text("Labelname") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun LabelManageDialog(label: LabelData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(label.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label.name) },
        text = {
            Column {
                if (edit) OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                else Text(if (label.shared) "Geteiltes Label" else "Dein Label")
                if (!edit && !confirm && label.isOwn) TextButton(onClick = { vm.loadUsers(); showMembers = true }) { Icon(Icons.Default.People, null); Text("Freigabe verwalten", Modifier.padding(start = 8.dp)) }
                if (confirm) Text("Label wirklich löschen? Zugeordnete Notizen bleiben erhalten.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    edit -> { vm.renameLabel(label.id, name); onDismiss() }
                    confirm -> { vm.deleteLabel(label.id); onDismiss() }
                    else -> edit = true
                }
            }, enabled = !edit || name.isNotBlank()) { Text(if (edit) "Speichern" else if (confirm) "Endgültig löschen" else "Umbenennen") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    when {
                        edit || confirm -> { edit = false; confirm = false }
                        label.shared -> { vm.leaveLabel(label.id); onDismiss() }
                        else -> confirm = true
                    }
                }) { Text(if (edit || confirm) "Abbrechen" else if (label.shared) "Verlassen" else "Löschen") }
                if (!edit && !confirm) TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        }
    )
    if (showMembers) LabelMembersDialog(label, state, vm, onDismiss = { showMembers = false })
}

@Composable
private fun LabelMembersDialog(label: LabelData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    val users = state.users.filter { it.id != state.user?.id }
    var permissions by remember(label.id, state.users) { mutableStateOf(label.members.associate { it.id to it.canEdit }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label freigeben") },
        text = {
            if (users.isEmpty()) Text("Keine weiteren Konten auf diesem Server gefunden.")
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Wähle Konten und Bearbeitungsrechte aus.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                users.forEach { user ->
                    val selected = user.id in permissions
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, { on -> permissions = if (on) permissions + (user.id to true) else permissions - user.id })
                        Text(user.username, Modifier.weight(1f).padding(start = 4.dp))
                        if (selected) Switch(permissions[user.id] == true, { canEdit -> permissions = permissions + (user.id to canEdit) }, modifier = Modifier.scale(.82f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setLabelMembers(label.id, permissions.map { it.key to it.value }); onDismiss() }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun CollaboratorsDialog(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    val candidates = state.users.filter { it.id != state.user?.id }
    var checked by remember(note.id, state.users) { mutableStateOf(note.members.map { it.id }.filter { it != state.user?.id }.toSet()) }
    var confirmLeave by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Zusammenarbeit") }, text = {
        if (candidates.isEmpty()) Text("Keine weiteren Konten auf diesem Server gefunden.") else Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
            Text("Konten mit Zugriff auswählen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidates.forEach { user -> Row(Modifier.fillMaxWidth().clickable { checked = if (user.id in checked) checked - user.id else checked + user.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(user.id in checked, { checked = if (it) checked + user.id else checked - user.id }); Text(user.username, Modifier.padding(start = 8.dp)) } }
            if (!note.isOwner) TextButton(onClick = { confirmLeave = true }) { Text("Geteilte Notiz verlassen") }
        }
    }, confirmButton = { TextButton(onClick = { vm.setCollaborators(note.id, checked.toList()); onDismiss() }, enabled = note.isOwner) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Notiz verlassen?") }, text = { Text("Die Notiz wird aus deiner Liste entfernt.") }, confirmButton = { TextButton(onClick = { vm.leaveNote(note.id); confirmLeave = false; onDismiss() }) { Text("Verlassen") } }, dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Abbrechen") } })
}

@Composable
private fun VersionsDialog(note: NoteData, vm: NotesViewModel, onDismiss: () -> Unit) {
    var versions by remember(note.id) { mutableStateOf<List<de.cracky.notizen.model.VersionInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Pair<Int, NoteDoc>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(note.id) { versions = runCatching { vm.versions(note.id) }.getOrNull(); loading = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Versionsverlauf") }, text = {
        when {
            loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            versions.isNullOrEmpty() -> Text("Für diese Notiz sind keine älteren Versionen verfügbar.")
            else -> Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                versions.orEmpty().forEach { version ->
                    ListItem(headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(version.updatedAt * 1000))) }, supportingContent = { Text(version.editors.joinToString { it.username }.ifBlank { "Unbekannter Bearbeiter" }) }, trailingContent = { TextButton(onClick = { scope.launch { preview = runCatching { vm.version(note.id, version.id) }.getOrNull()?.let { version.id to it } } }) { Text("Ansehen") } })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
    preview?.let { (versionId, doc) -> AlertDialog(onDismissRequest = { preview = null }, title = { Text(doc.title.ifBlank { "Versionsvorschau" }) }, text = { Text(if (doc.isList) doc.items.joinToString("\n") { "${if (it.checked) "☑" else "☐"} ${it.text}" } else doc.bodyText, maxLines = 14, overflow = TextOverflow.Ellipsis) }, confirmButton = { TextButton(onClick = { vm.restoreVersion(note.id, versionId); preview = null; onDismiss() }) { Text("Wiederherstellen") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Schließen") } }) }
}

@Composable
private fun SettingsDialog(state: de.cracky.notizen.AppState, vm: NotesViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var accountInfo by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<de.cracky.notizen.model.SessionInfo>?>(null) }
    var magicLinks by remember { mutableStateOf<List<de.cracky.notizen.model.MagicLinkInfo>?>(null) }
    var showMagic by remember { mutableStateOf(false) }
    var magicLabel by remember { mutableStateOf("Android") }
    var magicDays by remember { mutableStateOf("30") }
    var createdUrl by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var indexStatus by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::uploadAvatar) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Einstellungen") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Darstellung", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel").forEach { (id, label) -> FilterChip(state.themeMode == id, { vm.setTheme(id) }, label = { Text(label) }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Notizen", style = MaterialTheme.typography.titleSmall)
            SettingSwitch("Rasteransicht", state.gridLayout, { vm.toggleLayout() })
            SettingSwitch("Neue Listenelemente unten einfügen", state.addItemsBottom, { vm.setBoolSetting("addItemsBottom", it) })
            SettingSwitch("Abgehakte Elemente unten sammeln", state.checkedBottom, { vm.setBoolSetting("checkedBottom", it) })
            SettingSwitch("Hinweismeldungen anzeigen", state.snackbarsEnabled, { vm.setBoolSetting("snackbarsEnabled", it) })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Konto · ${state.user?.username.orEmpty()}", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val user = state.user
                if (user?.avatar != null) AsyncImage(vm.avatarUrl(user.id, user.avatar), null, Modifier.size(42.dp).clip(CircleShape))
                else Icon(Icons.Default.AccountCircle, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { avatarPicker.launch("image/*") }) { Text("Profilbild ändern") }
                if (user?.avatar != null) IconButton(onClick = vm::removeAvatar) { Icon(Icons.Default.DeleteOutline, "Profilbild entfernen") }
            }
            TextButton(onClick = { showQr = true }) { Icon(Icons.Default.QrCodeScanner, null); Text("Browser-Anmeldung freigeben", Modifier.padding(start = 8.dp)) }
            TextButton(onClick = { scope.launch { accountInfo = runCatching { val u = vm.usage(); "Speicher: ${formatBytes(u.used)}${u.limit?.let { " von ${formatBytes(it)}" } ?: ""}" }.getOrElse { it.message ?: "Nicht verfügbar" } } }) { Icon(Icons.Default.Storage, null); Text("Speichernutzung", Modifier.padding(start = 8.dp)) }
            TextButton(onClick = { scope.launch { sessions = runCatching { vm.sessionsList() }.getOrNull() } }) { Icon(Icons.Default.Devices, null); Text("Angemeldete Geräte", Modifier.padding(start = 8.dp)) }
            TextButton(onClick = { scope.launch { magicLinks = runCatching { vm.magicLinks() }.getOrNull() }; showMagic = true }) { Icon(Icons.Default.Link, null); Text("Anmeldelinks verwalten", Modifier.padding(start = 8.dp)) }
            TextButton(onClick = { showPassword = true }) { Icon(Icons.Default.Lock, null); Text("Passwort ändern", Modifier.padding(start = 8.dp)) }
            TextButton(onClick = { vm.clearLocalCache() }) { Icon(Icons.Default.CleaningServices, null); Text("Lokalen Cache neu aufbauen", Modifier.padding(start = 8.dp)) }
            state.user?.takeIf { it.isAdmin }?.let {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.adminUrl()))) }.onFailure { accountInfo = it.message } }) { Icon(Icons.Default.AdminPanelSettings, null); Text("Admin-Oberfläche öffnen") }
                TextButton(onClick = { scope.launch { indexStatus = runCatching { val i = vm.indexStatus(); "${i.state}: ${i.done}/${i.total} · OCR ausstehend: ${i.ocrPending}" }.getOrNull() } }) { Icon(Icons.Default.Search, null); Text("Suchindex-Status") }
                TextButton(onClick = { scope.launch { indexStatus = runCatching { val i = vm.rebuildIndex(); "${i.state}: ${i.done}/${i.total}" }.getOrElse { it.message ?: "Fehler" } } }) { Icon(Icons.Default.Refresh, null); Text("Suchindex neu aufbauen") }
            }
            indexStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            accountInfo?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } }, dismissButton = { TextButton(onClick = { vm.logout(); onDismiss() }) { Text("Abmelden") } })
    if (showPassword) AlertDialog(onDismissRequest = { showPassword = false }, title = { Text("Passwort ändern") }, text = { Column { OutlinedTextField(oldPassword, { oldPassword = it }, label = { Text("Aktuelles Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true); OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Neues Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) } }, confirmButton = { TextButton(onClick = { scope.launch { runCatching { vm.changePassword(oldPassword, newPassword) }.onSuccess { accountInfo = "Passwort geändert" }.onFailure { accountInfo = it.message }; showPassword = false } }) { Text("Ändern") } }, dismissButton = { TextButton(onClick = { showPassword = false }) { Text("Abbrechen") } })
    if (showMagic) AlertDialog(onDismissRequest = { showMagic = false }, title = { Text("Anmeldelinks") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(magicLabel, { magicLabel = it }, label = { Text("Bezeichnung") }, singleLine = true)
        OutlinedTextField(magicDays, { magicDays = it.filter(Char::isDigit) }, label = { Text("Gültigkeit in Tagen") }, singleLine = true)
        TextButton(onClick = { scope.launch { createdUrl = runCatching { vm.createMagicLink(magicLabel, magicDays.toIntOrNull() ?: 30).url }.getOrNull() } }) { Text("Neuen Link erstellen") }
        createdUrl?.let { Text("$it\nDer Link ist ein Geheimnis. Teile ihn nur mit der vorgesehenen Person.", color = MaterialTheme.colorScheme.error) }
        magicLinks.orEmpty().forEach { link -> ListItem(headlineContent = { Text(link.label) }, supportingContent = { Text("Läuft ab: ${DateFormat.getDateInstance().format(Date(link.expiresAt * 1000))}") }, trailingContent = { IconButton(onClick = { scope.launch { vm.deleteMagicLink(link.id); magicLinks = magicLinks.orEmpty().filterNot { it.id == link.id } } }) { Icon(Icons.Default.DeleteOutline, "Link löschen") } }) }
    } }, confirmButton = { TextButton(onClick = { showMagic = false }) { Text("Schließen") } })
    sessions?.let { list -> AlertDialog(onDismissRequest = { sessions = null }, title = { Text("Angemeldete Geräte") }, text = { if (list.isEmpty()) Text("Keine Sitzungen gefunden.") else Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { list.forEach { session -> ListItem(headlineContent = { Text(if (session.current) "Dieses Gerät" else session.userAgent.take(50)) }, supportingContent = { Text("Zuletzt aktiv: ${session.lastSeen?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) } ?: "unbekannt"}") }, trailingContent = { if (!session.current) IconButton(onClick = { scope.launch { vm.revokeSession(session.id); sessions = sessions.orEmpty().filterNot { it.id == session.id } } }) { Icon(Icons.Default.Logout, "Abmelden") } }) } } }, confirmButton = { TextButton(onClick = { sessions = null }) { Text("Schließen") } }) }
    if (showQr) QrScannerDialog(onDismiss = { showQr = false }, onScanned = { raw ->
        showQr = false
        val fragment = Uri.parse(raw).fragment.orEmpty().removePrefix("?")
        val pair = Uri.parse("https://x.invalid/?$fragment").getQueryParameter("pair")
        if (pair.isNullOrBlank()) vm.message("Der QR-Code enthält keinen Geräte-Anmeldeschlüssel.", important = true)
        else scope.launch { runCatching { vm.approveDeviceLogin(pair) }.onSuccess { vm.message("Browser-Anmeldung freigegeben") }.onFailure { vm.message(it.message ?: "Freigabe fehlgeschlagen", important = true) } }
    })
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange) }
}

private suspend fun openPdf(context: android.content.Context, vm: NotesViewModel, id: Int, name: String) {
    try {
        val file = vm.downloadPdf(id, name)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        vm.message("Keine PDF-App gefunden.", important = true)
    } catch (e: Exception) {
        vm.message(e.message ?: "PDF konnte nicht geöffnet werden.", important = true)
    }
}
