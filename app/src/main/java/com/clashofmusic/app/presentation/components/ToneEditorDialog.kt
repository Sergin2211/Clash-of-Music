package com.clashofmusic.app.presentation.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.clashofmusic.app.data.downloader.DownloadSignal
import com.clashofmusic.app.data.media.ToneClipService
import com.clashofmusic.app.data.media.ToneInstaller
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.presentation.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToLong

/** State is allocated per visible row; media/IO resources exist only while its editor is open. */
@Composable
fun rememberToneAction(song: DownloadedSong): (() -> Unit)? = rememberToneAction(
    song.songId, song.filePath.isNotBlank() || song.fileUri.startsWith("content:") || song.fileUri.startsWith("file:"), song)

@Composable
fun rememberToneAction(songId: String, available: Boolean): (() -> Unit)? = rememberToneAction(songId, available, null)

@Composable
private fun rememberToneAction(songId: String, available: Boolean, local: DownloadedSong?): (() -> Unit)? {
    if (!available) return null
    var visible by rememberSaveable(songId) { mutableStateOf(false) }
    if (visible) {
        var song by remember(songId) { mutableStateOf(local) }
        var loading by remember(songId) { mutableStateOf(local == null) }
        LaunchedEffect(songId) {
            if (song == null) {
                song = try { withContext(Dispatchers.IO) { ServiceLocator.libraryRepository.getDownloadedSong(songId)
                    ?: ServiceLocator.libraryRepository.observeDownloadedSongs().first().firstOrNull { it.id == songId || it.songId == songId } } }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                loading = false
            }
        }
        if (song != null) ToneEditorDialog(song!!, onDismiss = { visible = false })
        else AlertDialog(onDismissRequest = { visible = false }, title = { Text("Establecer como…") },
            text = { Text(if (loading) "Buscando la canción descargada…" else "La canción ya no está disponible en la biblioteca.") },
            confirmButton = { TextButton(onClick = { visible = false }) { Text("Cerrar") } })
    }
    return remember(songId) { { visible = true } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToneEditorDialog(song: DownloadedSong, onDismiss: () -> Unit, installer: ToneInstaller? = null,
                     previewVolume: Float = 1f, onBeforePreview: () -> Unit = { ServiceLocator.playerManager.pause() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { ToneClipService(context.applicationContext) }
    val toneInstaller = remember(context, installer) { installer ?: ToneInstaller(context.applicationContext) }
    var duration by remember { mutableLongStateOf(0L) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var initialized by rememberSaveable(song.songId) { mutableStateOf(false) }
    var startText by rememberSaveable(song.songId) { mutableStateOf("0:00") }
    var endText by rememberSaveable(song.songId) { mutableStateOf("0:30") }
    var targetName by rememberSaveable(song.songId) { mutableStateOf(ToneTarget.RINGTONE.name) }
    val target = ToneTarget.valueOf(targetName)
    var busy by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askedPermission by rememberSaveable(song.songId) { mutableStateOf(false) }
    var applyAfterPermission by rememberSaveable(song.songId) { mutableStateOf(false) }
    var canWrite by remember { mutableStateOf(toneInstaller.canWrite()) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var operationSignal by remember { mutableStateOf<DownloadSignal?>(null) }
    val loadingSignal = remember { DownloadSignal() }
    var preview by remember { mutableStateOf<ExoPlayer?>(null) }
    var previewing by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val selection = remember(startText, endText, duration) { runCatching {
        ToneSelection(ToneTime.parse(startText) ?: error("Escribe el inicio como minutos:segundos."),
            ToneTime.parse(endText) ?: error("Escribe el final como minutos:segundos.")).validate(duration)
    } }
    fun stopPreview() { preview?.pause(); preview?.clearMediaItems(); previewing = false }
    fun applySelection() {
        if (busy || success != null) return
        val range = selection.getOrNull() ?: return
        stopPreview(); error = null; busy = true
        val signal = DownloadSignal().also { operationSignal = it }
        operation = scope.launch {
            try {
                service.withClip(song, range, signal) { file ->
                    withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        committing = true
                        // Once publication starts, finish or roll back rather than cancel half a settings change.
                        withContext(NonCancellable) {
                            toneInstaller.install(file, "${song.title} (${ToneTime.format(range.startMs)}–${ToneTime.format(range.endMs)})", song.artist, target)
                            success = if (target == ToneTarget.RINGTONE) "Tono de llamada establecido." else "Alarma predeterminada establecida."
                        }
                    }
                }
            } catch (cancelled: CancellationException) { if (success == null) error = "Operación cancelada."; throw cancelled }
            catch (failure: Exception) {
                android.util.Log.w("ToneEditor", "Could not create/apply tone", failure)
                canWrite = toneInstaller.canWrite()
                error = if (!canWrite) "Falta el permiso para modificar los ajustes del sistema."
                    else "No se pudo establecer el tono. Revisa que la canción siga disponible e inténtalo de nuevo."
            } finally { busy = false; committing = false; operationSignal = null }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canWrite = toneInstaller.canWrite()
        if (askedPermission) {
            askedPermission = false
            if (canWrite) applyAfterPermission = true else error = "No se concedió el permiso. Tu tono no cambió."
        }
    }
    LaunchedEffect(applyAfterPermission, duration, sourceUri) {
        if (applyAfterPermission && selection.isSuccess && sourceUri != null) {
            applyAfterPermission = false
            applySelection()
        }
    }
    LaunchedEffect(song.songId) {
        try {
            duration = service.duration(song, loadingSignal)
            sourceUri = withContext(Dispatchers.IO) { service.previewUri(song) }
            if (!initialized) { endText = ToneTime.format(minOf(duration, 30000L)); initialized = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { applyAfterPermission = false; error = "No se puede leer esta canción. Comprueba que siga descargada." }
    }
    LaunchedEffect(startText, endText) { stopPreview() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopPreview() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            loadingSignal.cancel(); operationSignal?.cancel(); operation?.cancel()
            preview?.release()
        }
    }
    val dismiss = { if (!busy) onDismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxWidth(.94f).heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp),
            shape = MaterialTheme.shapes.large, color = DarkSurface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Establecer como…", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                Text(song.title, color = TextPrimaryDark, style = MaterialTheme.typography.titleMedium)
                Text(song.artist, color = TextSecondaryDark)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneTarget.entries.forEach { option ->
                        FilterChip(selected = target == option, onClick = { targetName = option.name }, enabled = !busy && success == null,
                            label = { Text(if (option == ToneTarget.RINGTONE) "Tono de llamada" else "Alarma") })
                    }
                }
                if (target == ToneTarget.ALARM) Text("Cambia el sonido predeterminado. Las alarmas con un tono propio lo conservan.",
                    color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                if (duration <= 0 && error == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Leyendo duración…", color = TextSecondaryDark) }
                if (duration > 0) {
                    Text("Duración: ${ToneTime.format(duration)}", color = TextSecondaryDark)
                    val minLength = minOf(1000L, duration)
                    val start = (ToneTime.parse(startText) ?: 0).coerceIn(0, duration - minLength)
                    val end = (ToneTime.parse(endText) ?: duration).coerceIn(start + minLength, duration)
                    RangeSlider(value = start / 1000f..end / 1000f, valueRange = 0f..duration / 1000f,
                        enabled = !busy && success == null, onValueChange = { range ->
                            val newStart = ((range.start * 10).roundToLong() * 100).coerceIn(0, duration - minLength)
                            val newEnd = ((range.endInclusive * 10).roundToLong() * 100).coerceIn(newStart + minLength, duration)
                            startText = ToneTime.format(newStart); endText = ToneTime.format(newEnd)
                        })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(startText, { startText = it }, Modifier.weight(1f), label = { Text("Inicio") },
                            singleLine = true, enabled = !busy && success == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                        OutlinedTextField(endText, { endText = it }, Modifier.weight(1f), label = { Text("Final") },
                            singleLine = true, enabled = !busy && success == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { startText = "0:00"; endText = ToneTime.format(minOf(duration, 30000)) }, enabled = !busy && success == null) { Text("Primeros 30 s") }
                        TextButton(onClick = { startText = "0:00"; endText = ToneTime.format(duration) }, enabled = !busy && success == null) { Text("Completa") }
                    }
                    if (selection.isFailure) Text(selection.exceptionOrNull()?.message.orEmpty(), color = AccentRed, style = MaterialTheme.typography.bodySmall)
                    else Text("Fragmento: ${ToneTime.format(selection.getOrThrow().durationMs)}", color = TextSecondaryDark)
                    OutlinedButton(enabled = selection.isSuccess && sourceUri != null && !busy && success == null,
                        onClick = {
                            if (previewing) stopPreview() else {
                                try {
                                    onBeforePreview()
                                    val player = preview ?: ExoPlayer.Builder(context).build().apply {
                                        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                                        setHandleAudioBecomingNoisy(true)
                                        volume = previewVolume
                                        addListener(object : Player.Listener {
                                            override fun onIsPlayingChanged(isPlaying: Boolean) { previewing = isPlaying }
                                            override fun onPlayerError(failure: PlaybackException) {
                                                previewing = false; error = "No se pudo escuchar el fragmento."
                                                android.util.Log.w("TonePreview", "Preview failed", failure)
                                            }
                                        })
                                    }.also { preview = it }
                                    val range = selection.getOrThrow()
                                    player.setMediaItem(MediaItem.Builder().setUri(sourceUri!!).setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build()).build())
                                    player.prepare(); player.play()
                                } catch (_: Exception) { error = "No se pudo escuchar el fragmento." }
                            }
                        }) { Text(if (previewing) "Detener preescucha" else "Escuchar fragmento") }
                }
                Text("Se guardará una copia del fragmento en Tonos o Alarmas.", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                if (!canWrite) Text("Android pedirá permiso para modificar el tono al establecerlo.", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = AccentRed) }
                success?.let { Text(it, color = AccentMint) }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (committing) "Estableciendo tono…" else "Preparando fragmento…", color = TextSecondaryDark) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !committing, onClick = {
                        if (busy) { operationSignal?.cancel(); operation?.cancel() } else onDismiss()
                    }) { Text(if (busy) "Cancelar" else if (success != null) "Listo" else "Cerrar") }
                    if (success == null) Button(enabled = selection.isSuccess && sourceUri != null && !busy, onClick = {
                        canWrite = toneInstaller.canWrite()
                        if (canWrite) applySelection() else {
                            stopPreview(); askedPermission = true
                            try { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                            catch (_: Exception) { askedPermission = false; error = "Abre los ajustes de esta app y permite modificar ajustes del sistema." }
                        }
                    }) { Text("Establecer") }
                }
            }
        }
    }
}
