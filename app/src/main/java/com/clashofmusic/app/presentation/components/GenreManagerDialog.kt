package com.clashofmusic.app.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.LibraryRepository
import com.clashofmusic.app.data.media.GenreLibraryManager
import kotlinx.coroutines.launch

@Composable
fun GenreManagerDialog(onDismiss: () -> Unit, library: LibraryRepository = ServiceLocator.libraryRepository,
    manager: GenreLibraryManager = ServiceLocator.genreManager) {
    val songs by remember(library) { library.observeDownloadedSongs() }.collectAsStateWithLifecycle(emptyList())
    val state by manager.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var onlyMissing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DownloadedSong?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visible = remember(songs, query, onlyMissing) { songs.filter { s ->
        (!onlyMissing || s.genreNames.isEmpty()) && (query.isBlank() || (s.title + " " + s.artist + " " + s.genreNames.joinToString()).contains(query, true))
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Géneros de la biblioteca", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${songs.count { it.genreNames.isNotEmpty() }} de ${songs.size} canciones con género", style = MaterialTheme.typography.bodyMedium)
                Text("Se combinan varios géneros. Los del artista son referencias y no confirman el estilo de cada canción. Tus correcciones tienen prioridad.", style = MaterialTheme.typography.bodySmall)
                Text("La búsqueda consulta título y artista en MusicBrainz; no envía audio. Respeta su límite de consultas y puede tardar varios minutos.", style = MaterialTheme.typography.bodySmall)
                if (state.running) {
                    LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    Text("${state.done}/${state.total}  /  ${state.current}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = manager::cancel) { Text("Detener análisis") }
                } else Button(onClick = manager::start, enabled = songs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Buscar y completar géneros") }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(query, { query = it }, label = { Text("Buscar canción, artista o género") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilterChip(selected = onlyMissing, onClick = { onlyMissing = !onlyMissing }, label = { Text("Sólo sin género") })
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { song ->
                        OutlinedCard(onClick = { editing = song; input = song.genreNames.joinToString(", "); error = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall)
                                if (song.genreAssignments.isEmpty()) Text("Sin género  /  tocar para editar", style = MaterialTheme.typography.bodySmall)
                                song.genreAssignments.forEach { genre -> Text("${genre.name}  /  ${genre.origin.label}" +
                                    if (genre.origin == GenreOrigin.ARTIST && genre.entityName != null) "  /  ${genre.entityName}" else "",
                                    style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cerrar") }
            }
        }
    }
    editing?.let { song ->
        AlertDialog(onDismissRequest = { if (!saving) editing = null }, title = { Text("Editar géneros") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song.title)
                OutlinedTextField(input, { input = it }, label = { Text("Géneros separados por comas") }, modifier = Modifier.fillMaxWidth())
                Text("Puedes añadir varios subgéneros o dejarlo vacío. La búsqueda automática respetará tu elección.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try { manager.saveManual(song, input); editing = null }
                    catch (_: Exception) { error = "No se pudieron guardar los géneros." }
                    finally { saving = false }
                }
            }) { Text("Guardar") }
        }, dismissButton = {
            Row {
                TextButton(enabled = !saving, onClick = { scope.launch {
                    saving = true
                    try { manager.reset(song); editing = null } catch (_: Exception) { error = "No se pudo restablecer." }
                    finally { saving = false }
                } }) { Text("Volver a automático") }
                TextButton(enabled = !saving, onClick = { editing = null }) { Text("Cancelar") }
            }
        })
    }
}
