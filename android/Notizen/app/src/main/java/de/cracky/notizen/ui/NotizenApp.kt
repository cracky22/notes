package de.cracky.notizen.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
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

private object Haptic {
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun press(v: View) { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun longPress(v: View) { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    fun confirm(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }
    fun threshold(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
private fun noteBackground(key: String): Color {
    val scheme = MaterialTheme.colorScheme
    if (key == "default") return scheme.background
    val dark = scheme.background.luminance() < .2f
    return (if (dark) DarkNoteColors else LightNoteColors)[key] ?: scheme.background
}

@Composable
fun NotizenApp(viewModel: NotesViewModel? = null) {
    val vm = viewModel ?: androidx.lifecycle.viewmodel.compose.viewModel<NotesViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (state.themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
    val appContext = LocalContext.current
    val colors = remember(dark) {
        if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(appContext) else dynamicLightColorScheme(appContext) } else keepScheme(dark)
    }
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
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // Zurück-Geste: auf Root-Ebene wird die App nie geschlossen. Spezifischere Handler
    // (Editor, Suche, Auswahl, Drawer, Ansicht) werden später registriert und haben Vorrang.
    BackHandler(enabled = true) { }
    val screen = when {
        !state.initialized -> 0
        state.user == null -> 1
        else -> 2
    }
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), color = MaterialTheme.colorScheme.background) {
                Crossfade(targetState = screen, animationSpec = tween(280), label = "screen") { current ->
                    when (current) {
                        0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(color = KeepYellow, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(42.dp)) {
                                    Icon(Icons.Default.Lightbulb, null, tint = Color(0xFF202124), modifier = Modifier.padding(9.dp))
                                }
                                Text("Notizen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        1 -> LoginScreen(state.serverUrl, state.loginBusy, state.loginError, vm)
                        else -> MainNotesScreen(state, vm, snackbar)
                    }
                }
            }
            // Editor liegt als Vollbild-Overlay über der Liste (animiert + Predictive Back)
            if (state.user != null) EditorHost(state, vm)
        }
    }
}

@Composable
private fun EditorHost(state: de.cracky.notizen.AppState, vm: NotesViewModel) {
    // Letzten Editor-Stand merken, damit die Ausblend-Animation noch Inhalt hat
    val holder = remember { arrayOfNulls<de.cracky.notizen.EditorUi>(1) }
    val current = state.editor
    if (current != null) holder[0] = current
    LaunchedEffect(current == null) {
        if (current == null) { delay(600); holder[0] = null }
    }
    val decelerate = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn(tween(200)) + slideInVertically(tween(360, easing = decelerate)) { it / 8 } + scaleIn(tween(360, easing = decelerate), initialScale = 0.92f),
        exit = fadeOut(tween(200, delayMillis = 40)) + slideOutVertically(tween(260, easing = accelerate)) { it / 10 } + scaleOut(tween(260, easing = accelerate), targetScale = 0.94f)
    ) {
        holder[0]?.let { EditorScreen(state, it, vm) }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MainNotesScreen(state: de.cracky.notizen.AppState, vm: NotesViewModel, snackbar: SnackbarHostState) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val files = uris.map { Media.describe(context, it) }.filter { it.isImage }
        if (files.isNotEmpty()) vm.newNote(false, files = files)
        else if (uris.isNotEmpty()) vm.message("Bitte Bilder auswählen.")
    }
    var showSettings by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showCreateLabel by remember { mutableStateOf(false) }
    var showEditLabels by remember { mutableStateOf(false) }
    var labelToManage by remember { mutableStateOf<LabelData?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    val hasTrash = remember(state.notes) { state.notes.values.any { it.trashed } }
    BackHandler(enabled = state.editor != null || state.searchActive || state.selected.isNotEmpty() || drawer.isOpen || state.view.kind != "notes") {
        when {
            state.editor != null -> vm.closeEditor()
            state.searchActive -> vm.closeSearch()
            state.selected.isNotEmpty() -> vm.clearSelection()
            drawer.isOpen -> scope.launch { drawer.close() }
            else -> vm.setView(ViewKey.NOTES)
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        // Wischen soll Notizen archivieren, nicht das Menü öffnen: Gesten nur zum Schließen
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, modifier = Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.fillMaxHeight().padding(bottom = 16.dp)) {
                    Row(Modifier.padding(start = 28.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Notizen", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        if (!state.online) Text("  ·  offline", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DrawerItem("Notizen", Icons.Outlined.Lightbulb, state.view.kind == "notes") { Haptic.tick(view); vm.setView(ViewKey.NOTES); scope.launch { drawer.close() } }
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Labels", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            "Bearbeiten", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { Haptic.tick(view); showEditLabels = true; scope.launch { drawer.close() } }.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    state.labels.forEach { label ->
                        DrawerItem(label.name, Icons.Outlined.Label, state.view.kind == "label" && state.view.labelId == label.id, onLongClick = { Haptic.longPress(view); labelToManage = label }) {
                            Haptic.tick(view); vm.setView(ViewKey("label", label.id)); scope.launch { drawer.close() }
                        }
                    }
                    DrawerItem("Neues Label erstellen", Icons.Default.Add, false) { Haptic.tick(view); showCreateLabel = true; scope.launch { drawer.close() } }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DrawerItem("Archiv", Icons.Outlined.Archive, state.view.kind == "archive") { Haptic.tick(view); vm.setView(ViewKey.ARCHIVE); scope.launch { drawer.close() } }
                    DrawerItem("Papierkorb", Icons.Outlined.Delete, state.view.kind == "trash") { Haptic.tick(view); vm.setView(ViewKey.TRASH); scope.launch { drawer.close() } }
                    DrawerItem("Einstellungen", Icons.Outlined.Settings, false) { Haptic.tick(view); showSettings = true; scope.launch { drawer.close() } }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Crossfade(targetState = state.selected.isNotEmpty(), animationSpec = tween(180), label = "topbar") { selecting ->
                    if (selecting) {
                        SelectionTopBar(state, vm) { if (state.view.kind == "trash") confirmPermanent = true else confirmTrash = true }
                    } else {
                        KeepTopBar(state, vm, onMenu = { Haptic.tick(view); scope.launch { drawer.open() } }, onFilter = { showFilter = true }, onAvatar = { showSettings = true })
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = state.view.kind != "trash" && state.editor == null && state.selected.isEmpty(),
                    enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(160)),
                    exit = scaleOut(tween(150)) + fadeOut(tween(150))
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    val rotation by animateFloatAsState(if (expanded) 135f else 0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium), label = "fabRotation")
                    val dark = MaterialTheme.colorScheme.background.luminance() < .2f
                    Column(horizontalAlignment = Alignment.End) {
                        AnimatedVisibility(
                            visible = expanded,
                            enter = fadeIn(tween(160)) + scaleIn(spring(stiffness = Spring.StiffnessMedium), transformOrigin = TransformOrigin(1f, 1f)) + slideInVertically(tween(220)) { it / 3 },
                            exit = fadeOut(tween(110)) + scaleOut(tween(130), transformOrigin = TransformOrigin(1f, 1f)) + slideOutVertically(tween(130)) { it / 4 }
                        ) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                SmallFabAction("Neues Bild", Icons.Default.Image) { Haptic.confirm(view); expanded = false; imagePicker.launch(arrayOf("image/*")) }
                                SmallFabAction("Neue Liste", Icons.Default.Checklist) { Haptic.confirm(view); expanded = false; vm.newNote(true) }
                                SmallFabAction("Neue Notiz", Icons.Default.EditNote) { Haptic.confirm(view); expanded = false; vm.newNote(false) }
                            }
                        }
                        // Einstellung "Plus legt direkt Notiz an": Tippen = leere Notiz, langes Drücken = Auswahlmenü
                        Surface(
                            shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp,
                            color = if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (dark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(
                                Modifier.fillMaxSize().combinedClickable(
                                    onClick = {
                                        if (expanded) { Haptic.press(view); expanded = false }
                                        else if (state.quickNote) { Haptic.confirm(view); vm.newNote(false) }
                                        else { Haptic.press(view); expanded = true }
                                    },
                                    onLongClick = { Haptic.longPress(view); expanded = !expanded }
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, if (expanded) "Schließen" else "Neue Notiz", Modifier.rotate(rotation))
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = state.searchActive,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                ) { SearchFilterChips(state, vm, onOpenFilter = { showFilter = true }) }
                AnimatedVisibility(
                    visible = state.view.kind == "trash" && hasTrash,
                    enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(160))
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmTrash = true }, modifier = Modifier.padding(end = 12.dp)) { Text("Papierkorb leeren") }
                    }
                }
                // Pull-to-Refresh: zieht den Stand vom Server nach (inkl. Labels und Nutzer)
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { Haptic.confirm(view); vm.refreshAll(showIndicator = true) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    AnimatedContent(
                        targetState = state.view to state.gridLayout,
                        transitionSpec = {
                            (fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(240, delayMillis = 70), initialScale = 0.97f)) togetherWith fadeOut(tween(100))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "notesView"
                    ) { (viewKey, grid) ->
                        val notes = remember(viewKey, state.notes, state.searchActive, state.query, state.searchIds, state.filter) { visibleNotes(state, viewKey) }
                        if (notes.isEmpty()) EmptyNotesState(state, vm)
                        else NotesContent(notes, grid, state, vm) { note -> openNoteOrRestore(note, state, vm) }
                    }
                }
            }
        }
    }
    if (showSettings) SettingsDialog(state, vm, onDismiss = { showSettings = false })
    if (showFilter) FilterDialog(state, vm, onDismiss = { showFilter = false })
    if (showCreateLabel) LabelNameDialog("Neues Label", "", onDismiss = { showCreateLabel = false }) { name -> scope.launch { vm.createLabel(name) }; showCreateLabel = false }
    if (showEditLabels) LabelsEditDialog(state, onDismiss = { showEditLabels = false }, onEdit = { showEditLabels = false; labelToManage = it }, onCreate = { showEditLabels = false; showCreateLabel = true })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(56.dp).clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
        Text(
            label, Modifier.padding(start = 18.dp), color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LabelsEditDialog(state: de.cracky.notizen.AppState, onDismiss: () -> Unit, onEdit: (LabelData) -> Unit, onCreate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = onCreate) { Icon(Icons.Default.Add, null); Text("Neues Label erstellen", Modifier.padding(start = 8.dp)) }
                state.labels.forEach { label ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onEdit(label) }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Label, null)
                        Text(label.name, Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.Edit, "Bearbeiten")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesContent(notes: List<NoteData>, grid: Boolean, state: de.cracky.notizen.AppState, vm: NotesViewModel, onOpen: (NoteData) -> Unit) {
    val pinned = remember(notes) { notes.filter { it.pinned } }
    val others = remember(notes, pinned) { if (pinned.isEmpty()) notes else notes.filter { !it.pinned } }
    val sections = pinned.isNotEmpty()
    val padding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 104.dp)
    if (grid) {
        // Staggered-Grid: jede Notiz ist genau so hoch wie ihr Inhalt
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 168.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sections) item(key = "header_pinned", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Angeheftet") }
            items(pinned, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
            if (sections && others.isNotEmpty()) item(key = "header_other", span = StaggeredGridItemSpan.FullLine) { SectionLabel("Sonstige") }
            if (sections) items(others, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
            else items(notes, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sections) item(key = "header_pinned") { SectionLabel("Angeheftet") }
            if (sections) {
                items(pinned, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
                if (others.isNotEmpty()) item(key = "header_other") { SectionLabel("Sonstige") }
                items(others, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
            } else {
                items(notes, key = { it.id }) { note -> SwipeableNote(note, state, vm, onOpen = { onOpen(note) }, modifier = Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
    )
}

/** Wischen (links oder rechts) archiviert die Notiz bzw. holt sie aus dem Archiv. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNote(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val inArchive = state.view.kind == "archive"
    val canSwipe = state.view.kind != "trash" && state.selected.isEmpty()
    val dismissState = rememberSwipeToDismissBoxState(positionalThreshold = { total -> total * 0.4f })
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) Haptic.threshold(view)
    }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            Haptic.confirm(view)
            vm.setArchived(listOf(note.id), !inArchive)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = canSwipe,
        enableDismissFromEndToStart = canSwipe,
        backgroundContent = {
            val fromStart = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd) {
                if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    Icon(if (inArchive) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (inArchive) "Aus Archiv holen" else "Archivieren", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) {
        NoteCard(note, state, vm, onOpen)
    }
}

private fun visibleNotes(state: de.cracky.notizen.AppState, view: ViewKey): List<NoteData> =
        state.notes.values.filter { n ->
            val inView = when (view.kind) {
                "archive" -> n.archived && !n.trashed
                "trash" -> n.trashed
                "label" -> !n.archived && !n.trashed && view.labelId?.let { it in n.labels } == true
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

private fun openNoteOrRestore(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel) {
    if (state.view.kind == "trash") return
    if (state.selected.isNotEmpty()) vm.toggleSelect(note.id) else vm.openNote(note.id)
}

@Composable
private fun KeepTopBar(state: de.cracky.notizen.AppState, vm: NotesViewModel, onMenu: () -> Unit, onFilter: () -> Unit, onAvatar: () -> Unit) {
    val view = LocalView.current
    val scheme = MaterialTheme.colorScheme
    val mode = if (state.searchActive) 2 else if (state.view.kind == "notes") 0 else 1
    Crossfade(targetState = mode, animationSpec = tween(180), label = "topbarMode", modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) { m ->
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (m) {
                2 -> {
                    IconButton(onClick = { Haptic.tick(view); vm.closeSearch() }) { Icon(Icons.Default.ArrowBack, "Suche schließen") }
                    SearchField(state, vm, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                }
                0 -> {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Navigation öffnen") }
                    Surface(onClick = { Haptic.tick(view); vm.openSearch() }, shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(56.dp)) {
                        Row(Modifier.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Notizen durchsuchen", color = scheme.onSurfaceVariant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { Haptic.tick(view); vm.toggleLayout() }) { Icon(if (state.gridLayout) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, "Ansicht wechseln") }
                            IconButton(onClick = { Haptic.tick(view); onFilter() }) { Icon(Icons.Default.FilterList, "Suchfilter") }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AvatarButton(state, vm) { Haptic.tick(view); onAvatar() }
                    Spacer(Modifier.width(8.dp))
                }
                else -> {
                    IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Navigation öffnen") }
                    Text(
                        when (state.view.kind) { "archive" -> "Archiv"; "trash" -> "Papierkorb"; "label" -> state.labels.firstOrNull { it.id == state.view.labelId }?.name ?: "Label"; else -> "Notizen" },
                        fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    IconButton(onClick = { Haptic.tick(view); vm.openSearch() }) { Icon(Icons.Default.Search, "Suchen") }
                    IconButton(onClick = { Haptic.tick(view); vm.toggleLayout() }) { Icon(if (state.gridLayout) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, "Ansicht wechseln") }
                    AvatarButton(state, vm) { Haptic.tick(view); onAvatar() }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchField(state: de.cracky.notizen.AppState, vm: NotesViewModel, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(modifier.height(56.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        TextField(
            value = state.query, onValueChange = vm::setQuery, singleLine = true,
            modifier = Modifier.fillMaxSize().focusRequester(focus),
            placeholder = { Text("Notizen durchsuchen") },
            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Suche leeren") } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun AvatarButton(state: de.cracky.notizen.AppState, vm: NotesViewModel, onClick: () -> Unit) {
    val user = state.user
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (user?.avatar != null) AsyncImage(vm.avatarUrl(user.id, user.avatar), "Konto", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text(user?.username?.take(1)?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SelectionTopBar(state: de.cracky.notizen.AppState, vm: NotesViewModel, onTrash: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun NoteCard(note: NoteData, state: de.cracky.notizen.AppState, vm: NotesViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val selected = note.id in state.selected
    val bg = noteBackground(note.color)
    val fg = if (bg.luminance() < 0.38f) Color(0xFFF1F3F4) else Color(0xFF202124)
    val cardScale by animateFloatAsState(if (selected) 0.965f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "cardScale")
    val cardBg by animateColorAsState(bg, tween(250), label = "cardBg")
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val borderColor by animateColorAsState(if (selected) primaryColor else if (note.color == "default") outlineColor else Color.Transparent, tween(200), label = "cardBorder")
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(200), label = "cardBorderWidth")
    val labels = state.labels.filter { it.id in note.labels }
    val hasText = note.doc.title.isNotBlank() || (note.doc.isList && note.doc.items.isNotEmpty()) || note.doc.bodyText.isNotBlank() || note.pdfs.isNotEmpty() || labels.isNotEmpty() || note.shared
    Card(
        modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = cardScale; scaleY = cardScale }.combinedClickable(
            onClick = {
                if (state.view.kind == "trash") { if (state.selected.isNotEmpty()) { Haptic.tick(view); vm.toggleSelect(note.id) } }
                else { if (state.selected.isNotEmpty()) Haptic.tick(view); onOpen() }
            },
            onLongClick = { Haptic.longPress(view); vm.toggleSelect(note.id) }
        ),
        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(borderWidth, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box {
            Column {
                if (note.images.isNotEmpty()) {
                    // Bilder in natürlichem Seitenverhältnis, ohne feste Höhe
                    AsyncImage(vm.thumbUrl(note.images.first().id), null, Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                    val more = note.images.drop(1).take(3)
                    if (more.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        more.forEach { image -> AsyncImage(vm.thumbUrl(image.id), null, Modifier.weight(1f).aspectRatio(1f), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        repeat(3 - more.size) { Spacer(Modifier.weight(1f)) }
                    }
                } else if (note.pdfs.isNotEmpty()) {
                    AsyncImage(vm.pdfThumbUrl(note.pdfs.first().id), null, Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                }
                if (hasText) Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (note.doc.title.isNotBlank()) Text(
                        note.doc.title, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = fg,
                        modifier = Modifier.padding(end = if (note.pinned && note.images.isEmpty() && note.pdfs.isEmpty()) 24.dp else 0.dp)
                    )
                    if (note.doc.isList && note.doc.items.isNotEmpty()) {
                        note.doc.items.take(60).forEach { item ->
                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                                Icon(if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, modifier = Modifier.size(18.dp), tint = fg.copy(alpha = .72f))
                                Text(item.text.ifBlank { "Leeres Listenelement" }, modifier = Modifier.padding(start = 8.dp), fontSize = 14.sp, lineHeight = 19.sp, color = fg.copy(alpha = if (item.checked) .55f else .92f), textDecoration = if (item.checked) TextDecoration.LineThrough else null)
                            }
                        }
                        if (note.doc.items.size > 60) Text("+ ${note.doc.items.size - 60} weitere", color = fg.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    } else if (note.doc.bodyText.isNotBlank()) {
                        Text(note.doc.bodyText, color = fg.copy(alpha = .92f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 80, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (note.doc.title.isNotBlank()) 6.dp else 0.dp))
                    }
                    if (note.pdfs.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = .08f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, null, tint = fg, modifier = Modifier.size(20.dp))
                            Text(note.pdfs.first().name, Modifier.weight(1f).padding(start = 7.dp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (note.pdfs.size > 1) Text("+${note.pdfs.size - 1}", color = fg, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (labels.isNotEmpty() || note.shared) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (note.shared) Icon(Icons.Outlined.People, "Geteilt", Modifier.size(18.dp), tint = fg.copy(alpha = .75f))
                        labels.take(3).forEach { label ->
                            Surface(color = Color.Transparent, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, fg.copy(alpha = .28f))) {
                                Text(label.name, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = fg.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (labels.size > 3) Text("+${labels.size - 3}", color = fg.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (note.pinned) Icon(Icons.Default.PushPin, "Angeheftet", Modifier.align(Alignment.TopEnd).padding(10.dp).size(18.dp), tint = fg.copy(alpha = .7f))
            androidx.compose.animation.AnimatedVisibility(selected, modifier = Modifier.align(Alignment.TopStart), enter = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn(tween(120)), exit = scaleOut(tween(120)) + fadeOut(tween(100))) {
                Surface(Modifier.padding(8.dp).size(27.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(5.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyNotesState(state: de.cracky.notizen.AppState, vm: NotesViewModel) {
    val (icon, title, caption) = when {
        state.searchActive && state.query.isNotBlank() -> Triple(Icons.Default.SearchOff, "Keine Treffer", "Versuche einen anderen Suchbegriff oder Filter.")
        state.view.kind == "trash" -> Triple(Icons.Default.DeleteOutline, "Papierkorb ist leer", "Gelöschte Notizen bleiben hier, bis du sie endgültig entfernst.")
        state.view.kind == "archive" -> Triple(Icons.Default.Archive, "Noch nichts archiviert", "Archivierte Notizen findest du hier wieder.")
        else -> Triple(Icons.Default.Lightbulb, "Deine Notizen erscheinen hier", "Tippe auf +, um eine Idee festzuhalten.")
    }
    LazyColumn(Modifier.fillMaxSize()) { item {
    Column(Modifier.fillParentMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        if (state.firstLoad) TextButton(onClick = { vm.refreshAll(showIndicator = true) }, modifier = Modifier.padding(top = 12.dp)) { Icon(Icons.Default.Refresh, null); Text("Jetzt synchronisieren", Modifier.padding(start = 6.dp)) }
    }
    } }
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
private fun EditorScreen(state: de.cracky.notizen.AppState, editor: de.cracky.notizen.EditorUi, vm: NotesViewModel) {
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
    val noteBg = noteBackground(draftColor)
    val noteFg = if (noteBg.luminance() < .38f) Color(0xFFF1F3F4) else Color(0xFF202124)
    val view = LocalView.current
    var showFormat by remember(editor.key) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(editor.key, editor.loading) {
        if (editor.isNew && !editor.loading) runCatching { titleFocus.requestFocus() }
    }
    val editedLabel = when {
        editor.pending || editor.uploading > 0 -> "Speichern…"
        note != null && note.updatedAt > 0 -> {
            val ms = note.updatedAt * 1000
            "Bearbeitet: " + if (android.text.format.DateUtils.isToday(ms)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms)) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
        }
        else -> ""
    }
    // Predictive Back: Editor schrumpft beim Wischen mit, bei Abbruch federt er zurück
    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = state.editor != null) { progress ->
        try {
            progress.collect { event -> backProgress.snapTo(event.progress) }
            Haptic.tick(view)
            vm.closeEditor()
        } catch (e: CancellationException) {
            backProgress.animateTo(0f, tween(180))
        }
    }
    Surface(
        Modifier.fillMaxSize().pointerInput(Unit) {}.graphicsLayer {
            val p = backProgress.value
            val factor = 1f - 0.08f * p
            scaleX = factor
            scaleY = factor
            translationY = p * 12.dp.toPx()
            if (p > 0f) {
                clip = true
                shape = RoundedCornerShape(p * 28.dp.toPx())
            }
        },
        color = noteBg
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ---- obere Leiste (wie Google Notizen): zurück | rückgängig, wiederholen, anheften, archivieren
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Haptic.tick(view); vm.closeEditor() }) { Icon(Icons.Default.ArrowBack, "Zurück", tint = noteFg) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Haptic.tick(view); vm.undo() }, enabled = editor.canUndo) { Icon(Icons.Default.Undo, "Rückgängig", tint = noteFg.copy(alpha = if (editor.canUndo) 1f else .35f)) }
                IconButton(onClick = { Haptic.tick(view); vm.redo() }, enabled = editor.canRedo) { Icon(Icons.Default.Redo, "Wiederholen", tint = noteFg.copy(alpha = if (editor.canRedo) 1f else .35f)) }
                IconButton(onClick = { note?.let { Haptic.confirm(view); vm.setPinned(listOf(it.id), !it.pinned) } }, enabled = note != null) {
                    Icon(if (note?.pinned == true) Icons.Default.PushPin else Icons.Outlined.PushPin, if (note?.pinned == true) "Lösen" else "Anheften", tint = noteFg)
                }
                IconButton(onClick = { note?.let { Haptic.confirm(view); vm.setArchived(listOf(it.id), !it.archived); vm.closeEditor() } }, enabled = note != null) {
                    Icon(if (note?.archived == true) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (note?.archived == true) "Aus Archiv holen" else "Archivieren", tint = noteFg)
                }
            }
            // ---- Inhalt
            if (editor.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    note?.images?.forEach { image ->
                        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable { imagePreview = image.id }) {
                            AsyncImage(vm.imageUrl(image.id), null, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                            IconButton(onClick = { vm.deleteImage(note.id, image.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(.45f), CircleShape)) { Icon(Icons.Default.Close, "Bild entfernen", tint = Color.White) }
                        }
                    }
                    note?.pdfs?.forEach { pdf ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(noteFg.copy(alpha = .08f)).clickable {
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
                Column(Modifier.padding(horizontal = 20.dp)) {
                    BasicTextField(
                        value = title, onValueChange = { next ->
                            val delta = diffDelta(title.text, next.text)
                            title = next
                            if (!delta.isEmpty()) vm.applyLocal(listOf(SubOp.Text("title", delta)), "title")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).focusRequester(titleFocus),
                        textStyle = TextStyle(color = noteFg, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
                        cursorBrush = SolidColor(noteFg), decorationBox = { inner ->
                            Box { if (title.text.isEmpty()) Text("Titel", color = noteFg.copy(alpha = .5f), fontSize = 22.sp, fontWeight = FontWeight.Medium); inner() }
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(top = 4.dp, bottom = 16.dp),
                            textStyle = TextStyle(color = noteFg, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
                            cursorBrush = SolidColor(noteFg), visualTransformation = VisualTransformation { value ->
                                val currentStyled = if (value.text == body.text) display else AnnotatedString(value.text)
                                androidx.compose.ui.text.input.TransformedText(currentStyled, androidx.compose.ui.text.input.OffsetMapping.Identity)
                            },
                            decorationBox = { inner -> Box { if (body.text.isEmpty()) Text("Notiz", color = noteFg.copy(alpha = .5f), fontSize = 16.sp, fontWeight = FontWeight.Normal); inner() } }
                        )
                    }
                    if (editor.uploading > 0) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  ${editor.uploading} Datei(en) werden hochgeladen", color = noteFg.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall) }
                    if (note != null && note.labels.isNotEmpty()) Row(Modifier.padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.labels.filter { it.id in note.labels }.forEach { AssistChip(onClick = { showLabels = true }, label = { Text(it.name) }, leadingIcon = { Icon(Icons.Default.Label, null, Modifier.size(16.dp)) }) }
                    }
                }
            }
            // ---- Formatierung (nur bei Textnotizen, über "Aa" ein-/ausblendbar)
            AnimatedVisibility(visible = showFormat && !editor.doc.isList && !editor.loading, enter = fadeIn(tween(160)) + expandVertically(tween(200)), exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) {
                Column {
                    HorizontalDivider(color = noteFg.copy(alpha = .12f))
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
            }
            // ---- untere Leiste: hinzufügen | Farbe | Aa | Bearbeitet-Zeit | Menü
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { Haptic.tick(view); attachmentMenu = true }) { Icon(Icons.Outlined.AddBox, "Hinzufügen", tint = noteFg) }
                    DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("Foto aufnehmen") }, leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) }, onClick = {
                            attachmentMenu = false
                            runCatching {
                                val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                                val file = java.io.File.createTempFile("notiz_", ".jpg", dir)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                cameraUri = uri
                                camera.launch(uri)
                            }.onFailure { vm.message(it.message ?: "Kamera konnte nicht gestartet werden.", important = true) }
                        })
                        DropdownMenuItem(text = { Text("Bild oder PDF auswählen") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = { attachmentMenu = false; picker.launch(arrayOf("image/*", "application/pdf")) })
                        DropdownMenuItem(
                            text = { Text(if (editor.doc.isList) "Kontrollkästchen ausblenden" else "Kontrollkästchen anzeigen") },
                            leadingIcon = { Icon(if (editor.doc.isList) Icons.Outlined.EditNote else Icons.Outlined.Checklist, null) },
                            onClick = { attachmentMenu = false; vm.applyLocal(listOf(SubOp.SetList(!editor.doc.isList))) }
                        )
                    }
                }
                IconButton(onClick = { Haptic.tick(view); showColors = true }) { Icon(Icons.Outlined.Palette, "Farbe", tint = noteFg) }
                if (!editor.doc.isList) IconButton(onClick = { Haptic.tick(view); showFormat = !showFormat }) {
                    Icon(Icons.Outlined.TextFormat, "Textformatierung", tint = if (showFormat) MaterialTheme.colorScheme.primary else noteFg)
                }
                Text(editedLabel, Modifier.weight(1f), textAlign = TextAlign.Center, color = noteFg.copy(alpha = .65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { Haptic.tick(view); showMenu = true }) { Icon(Icons.Default.MoreVert, "Weitere Aktionen", tint = noteFg) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, enabled = note != null, onClick = { note?.let { vm.trash(listOf(it.id)) }; vm.closeEditor(); showMenu = false })
                        DropdownMenuItem(text = { Text("Kopie erstellen") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, enabled = note != null, onClick = { note?.let { vm.copy(listOf(it.id)) }; showMenu = false })
                        DropdownMenuItem(text = { Text("Senden") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, editor.doc.title + "\n" + if (editor.doc.isList) editor.doc.items.joinToString("\n") { if (it.checked) "☑ ${it.text}" else "☐ ${it.text}" } else editor.doc.bodyText)
                            context.startActivity(Intent.createChooser(share, "Notiz teilen")); showMenu = false
                        })
                        DropdownMenuItem(text = { Text("Labels") }, leadingIcon = { Icon(Icons.Outlined.Label, null) }, onClick = { showLabels = true; showMenu = false })
                        DropdownMenuItem(text = { Text("Zusammenarbeit") }, leadingIcon = { Icon(Icons.Outlined.People, null) }, enabled = note != null, onClick = { vm.loadUsers(); showCollaborators = true; showMenu = false })
                        DropdownMenuItem(text = { Text("Versionen") }, leadingIcon = { Icon(Icons.Outlined.History, null) }, enabled = note != null, onClick = { showVersions = true; showMenu = false })
                    }
                }
            }
        }
    }
    if (showColors) ColorSheet(draftColor, onDismiss = { showColors = false }) { color ->
        Haptic.tick(view)
        draftColor = color
        if (note != null) vm.setColor(listOf(note.id), color) else vm.editorAction { id -> vm.setColor(listOf(id), color) }
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
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSheet(selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Farbe", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KeepPalette.forEach { (id, label) ->
                val color = noteBackground(id)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { onSelect(id) },
                    contentAlignment = Alignment.Center
                ) {
                    if (id == selected) Icon(Icons.Default.Check, label, tint = if (color.luminance() < .4f) Color.White else Color(0xFF202124), modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
    }
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
            SettingSwitch("Plus-Button legt direkt leere Notiz an (Liste/Bild per langem Drücken)", state.quickNote, { vm.setQuickNote(it) })
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
