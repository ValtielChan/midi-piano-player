package com.fabien.midipiano

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val player = Player()
    private var externalUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        externalUri = intent?.data
        setContent { App(player, SettingsStore(this), externalUri) { externalUri = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { externalUri = it }
    }

    override fun onResume() { super.onResume(); player.start() }
    override fun onPause() { super.onPause(); player.suspend() }
    override fun onDestroy() { super.onDestroy(); player.release() }
}

private val BG = Color(0xFF07080F)
private val PANEL = Color(0xFF10121C)

@Composable
fun App(player: Player, store: SettingsStore, externalUri: Uri?, onExternalConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(store.load()) }
    val update = { s: Settings -> settings = s; store.save(s) }
    var songName by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var pos by remember { mutableDoubleStateOf(0.0) }

    fun open(uri: Uri, persist: Boolean) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    MidiParser.parse(bytes) to displayName(ctx, uri)
                }
            }.onSuccess { (song, name) ->
                player.load(song); songName = name
                if (persist) store.lastUri = uri.toString()
            }.onFailure {
                Toast.makeText(ctx, "Impossible d'ouvrir ce fichier : ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        open(uri, true)
    }
    val autoReopen = remember { externalUri == null }
    LaunchedEffect(Unit) { if (autoReopen) store.lastUri?.let { open(Uri.parse(it), false) } }
    LaunchedEffect(externalUri) { externalUri?.let { open(it, false); onExternalConsumed() } }
    LaunchedEffect(Unit) { while (true) withFrameNanos { pos = player.position() } }

    val song = player.song
    val pal = PALETTES[settings.palette]
    val displayPos = drag?.toDouble() ?: pos

    MaterialTheme(colorScheme = darkColorScheme(primary = pal.right, secondary = pal.left, surface = PANEL, background = BG)) {
        Box(Modifier.fillMaxSize().background(BG)) {
            Column(Modifier.fillMaxSize()) {
                PianoCanvas(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { showControls = !showControls } },
                    song, displayPos, settings,
                )
                AnimatedVisibility(showControls, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    TransportBar(player, song, displayPos, pal,
                        onDrag = { drag = it },
                        onDragEnd = { drag?.let { player.seek(it.toDouble()) }; drag = null })
                }
            }
            AnimatedVisibility(showControls, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                TopBar(songName, onOpen = { picker.launch(arrayOf("*/*")) }, onSettings = { showSettings = true })
            }
            if (song == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Aucun morceau", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text("Choisis un fichier MIDI sur ton téléphone", color = Color(0xFF9AA0B5))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = pal.right, contentColor = Color.Black)) {
                        Text("Ouvrir un fichier MIDI", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (showSettings) SettingsSheet(settings, song, update) { showSettings = false }
        }
    }
}

private fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
    }
    return uri.lastPathSegment ?: "Fichier MIDI"
}

private fun fmt(ms: Double): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TopBar(name: String?, onOpen: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onOpen) { Text("Ouvrir") }
        Text(
            name ?: "", Modifier.weight(1f).padding(horizontal = 12.dp),
            color = Color.White, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages", tint = Color.White) }
    }
}

private enum class Tr { REWIND, PLAY, PAUSE, STOP }

@Composable
private fun TrIcon(kind: Tr, color: Color, dp: Dp) = Canvas(Modifier.size(dp)) {
    val w = size.width; val h = size.height
    when (kind) {
        Tr.PLAY -> drawPath(Path().apply { moveTo(w * 0.22f, 0f); lineTo(w, h / 2); lineTo(w * 0.22f, h); close() }, color)
        Tr.PAUSE -> {
            drawRoundRect(color, Offset(w * 0.14f, 0f), Size(w * 0.27f, h), CornerRadius(w * 0.06f))
            drawRoundRect(color, Offset(w * 0.59f, 0f), Size(w * 0.27f, h), CornerRadius(w * 0.06f))
        }
        Tr.STOP -> drawRoundRect(color, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), CornerRadius(w * 0.14f))
        Tr.REWIND -> {
            drawRoundRect(color, Offset(w * 0.08f, 0f), Size(w * 0.18f, h), CornerRadius(w * 0.05f))
            drawPath(Path().apply { moveTo(w * 0.95f, 0f); lineTo(w * 0.32f, h / 2); lineTo(w * 0.95f, h); close() }, color)
        }
    }
}

@Composable
private fun TransportBar(player: Player, song: MidiSong?, displayPos: Double, pal: Palette, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val duration = (song?.duration ?: 0.0).toFloat().coerceAtLeast(1f)
    val enabled = song != null

    val buttons: @Composable () -> Unit = {
        IconButton(onClick = { player.stop() }, enabled = enabled) { TrIcon(Tr.REWIND, Color.White, 20.dp) }
        FilledIconButton(
            onClick = { player.toggle() }, enabled = enabled, modifier = Modifier.size(52.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = pal.right, contentColor = Color.Black),
        ) { TrIcon(if (player.playing) Tr.PAUSE else Tr.PLAY, Color.Black, 22.dp) }
        IconButton(onClick = { player.pause(); player.seek(0.0) }, enabled = enabled) { TrIcon(Tr.STOP, Color.White, 18.dp) }
    }
    val slider: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(displayPos), color = Color.White, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = displayPos.toFloat().coerceIn(0f, duration), onValueChange = onDrag, onValueChangeFinished = onDragEnd,
                valueRange = 0f..duration, enabled = enabled, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(fmt(duration.toDouble()), color = Color(0xFF9AA0B5), style = MaterialTheme.typography.labelLarge)
        }
    }
    val speed: @Composable () -> Unit = {
        var open by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { open = true }) { Text("${player.speed}×", color = Color.White) }
            DropdownMenu(open, { open = false }) {
                for (v in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) DropdownMenuItem(text = { Text("${v}×") }, onClick = { player.changeSpeed(v); open = false })
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().background(PANEL).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                buttons(); Spacer(Modifier.width(8.dp)); slider(Modifier.weight(1f)); speed()
            }
        } else {
            slider(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Spacer(Modifier.weight(1f)); buttons(); Spacer(Modifier.weight(1f)); speed()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(s: Settings, song: MidiSong?, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF161927)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Réglages", style = MaterialTheme.typography.titleLarge, color = Color.White)

            Label("Clavier")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in KEYBOARD_PRESETS) FilterChip(selected = s.keyboardPreset == k, onClick = { onChange(s.copy(keyboardPreset = k)) }, label = { Text(if (k == 0) "Auto" else "$k touches") })
            }

            Label("Durée de chute : ${"%.1f".format(s.windowSec)} s")
            Slider(s.windowSec, { onChange(s.copy(windowSec = it)) }, valueRange = 1f..8f)

            Label("Hauteur du clavier : ${(s.keyboardFrac * 100).roundToInt()} %")
            Slider(s.keyboardFrac, { onChange(s.copy(keyboardFrac = it)) }, valueRange = 0.15f..0.45f)

            Label("Couleurs (main droite / main gauche)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PALETTES.forEachIndexed { i, p ->
                    FilterChip(selected = s.palette == i, onClick = { onChange(s.copy(palette = i)) }, label = { Text(p.name) },
                        leadingIcon = { Row { Dot(p.right); Spacer(Modifier.width(3.dp)); Dot(p.left) } })
                }
            }

            val autoLabel = when (song?.autoHandMode) { 1 -> " (pistes)"; 2 -> " (canaux)"; 3 -> " (hauteur)"; else -> "" }
            Label("Séparation des mains")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto$autoLabel", "Pistes", "Canaux", "Hauteur").forEachIndexed { i, t ->
                    FilterChip(selected = s.handMode == i, onClick = { onChange(s.copy(handMode = i)) }, label = { Text(t) })
                }
            }
            val effective = if (s.handMode == 0) song?.autoHandMode ?: 3 else s.handMode
            if (effective == 3) {
                Label("Main droite à partir de ${noteName(s.splitNote, s.noteNames == 2)}")
                Slider(s.splitNote.toFloat(), { onChange(s.copy(splitNote = it.roundToInt())) }, valueRange = 36f..84f, steps = 47)
            }
            SwitchRow("Inverser les mains", s.swapHands) { onChange(s.copy(swapHands = it)) }
            Label("Nom sur les notes qui tombent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Aucun", "C D E F G A B", "Do Ré Mi Fa Sol La Si").forEachIndexed { i, t ->
                    FilterChip(selected = s.noteNames == i, onClick = { onChange(s.copy(noteNames = i)) }, label = { Text(t) })
                }
            }
            SwitchRow("Afficher le nom des ${if (s.noteNames == 2) "Do" else "C"} sur le clavier", s.showLabels) { onChange(s.copy(showLabels = it)) }
        }
    }
}

@Composable
private fun Label(t: String) = Text(t, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge, color = Color(0xFFB4B9CC))

@Composable
private fun Dot(c: Color) = Box(Modifier.size(12.dp).background(c, CircleShape))

@Composable
private fun SwitchRow(t: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, Modifier.weight(1f), color = Color.White)
        Switch(v, on)
    }
}
