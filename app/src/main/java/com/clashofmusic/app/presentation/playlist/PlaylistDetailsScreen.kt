package com.clashofmusic.app.presentation.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.presentation.components.AddToPlaylistDialog
import com.clashofmusic.app.presentation.components.rememberToneAction
import com.clashofmusic.app.presentation.components.CreatePlaylistDialog
import com.clashofmusic.app.presentation.components.EmptyStateView
import com.clashofmusic.app.presentation.components.LoadingStateView
import com.clashofmusic.app.presentation.components.PlayingEqualizerBars
import com.clashofmusic.app.presentation.components.ShareHelper
import com.clashofmusic.app.presentation.components.SongCard
import com.clashofmusic.app.presentation.theme.*

@Composable
fun PlaylistDetailsScreen(
    playlistId: String,
    onNavigateBack: () -> Unit,
    viewModel: PlaylistDetailsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentSong by viewModel.playerManager.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("No se pudo completar la operación") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("Aceptar") }
            }
        )
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var selectedSongForPlaylist by remember { mutableStateOf<String?>(null) }
    var songToDeletePermanently by remember { mutableStateOf<DownloadedSong?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showBatchActionsModal by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val songKeys by rememberUpdatedState(state.songs.map { it.id })
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val reorder = remember(listState, viewModel, edgePx) {
        LongPressReorderState(listState, edgePx, { songKeys }, viewModel::beginReorder,
            viewModel::moveSong, viewModel::finishReorder)
    }
    LaunchedEffect(reorder.draggedKey) { if (reorder.draggedKey != null) reorder.autoScroll() }
    DisposableEffect(reorder) { onDispose { reorder.finish(true) } }
    BackHandler(enabled = state.isReordering) { reorder.finish(true) }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Contextual Selection Bar OR Normal Top App Bar
        if (state.isSelectionMode) {
            BackHandler {
                viewModel.setSelectionMode(false)
            }

            Surface(
                color = DarkSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.setSelectionMode(false) }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancelar selección",
                            tint = TextPrimaryDark
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = "${state.selectedSongIds.size} seleccionadas",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        modifier = Modifier.weight(1f)
                    )

                    val allSelected = state.songs.isNotEmpty() && state.songs.all { it.songId in state.selectedSongIds }
                    IconButton(onClick = {
                        if (allSelected) viewModel.clearSelection() else viewModel.selectAll()
                    }) {
                        Icon(
                            imageVector = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                            contentDescription = if (allSelected) "Deseleccionar todo" else "Seleccionar todo",
                            tint = TextPrimaryDark
                        )
                    }

                    IconButton(onClick = { showBatchAddToPlaylistDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = "Añadir a playlist",
                            tint = TextPrimaryDark
                        )
                    }

                    IconButton(onClick = { showBatchRemoveConfirm = true }) {
                        Icon(
                            imageVector = Icons.Rounded.RemoveCircleOutline,
                            contentDescription = "Quitar de playlist",
                            tint = TextPrimaryDark
                        )
                    }

                    IconButton(onClick = { showBatchDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteForever,
                            contentDescription = "Eliminar del dispositivo",
                            tint = AccentRed
                        )
                    }
                }
            }
        } else {
            // Normal Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Volver",
                        tint = TextPrimaryDark
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.songs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSelectionMode(true) }) {
                            Icon(
                                imageVector = Icons.Rounded.Checklist,
                                contentDescription = "Seleccionar canciones",
                                tint = AccentBerry
                            )
                        }
                    }

                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Eliminar playlist",
                            tint = AccentRed
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.isLoading) {
                LoadingStateView(message = "Cargando canciones de la lista...")
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = !state.isReordering,
                    modifier = Modifier.fillMaxSize().then(reorder.detector(!state.isSelectionMode && !state.isSavingOrder && !state.isDeleting)),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) 140.dp else 90.dp)
                ) {
                    // Playlist Header Card
                    item(key = "playlist-header") {
                        val playlist = state.playlist
                        if (playlist != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = DarkSurface,
                                tonalElevation = 0.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(140.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(DarkSurfaceVariant)
                                    ) {
                                        if (!playlist.thumbnailUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = playlist.thumbnailUrl,
                                                contentDescription = playlist.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (playlist.isAlbum) Icons.Rounded.Album else Icons.Rounded.QueueMusic,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .align(Alignment.Center),
                                                tint = AccentBerry
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (!playlist.description.isNullOrEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = playlist.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondaryDark,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${state.songs.size} canciones en total",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimaryDark
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Action Buttons (Play All / Shuffle)
                                    if (state.songs.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            val isPlaylistPlaying = isPlaying && state.songs.any { it.songId == currentSong?.songId || it.id == currentSong?.id }
                                            Button(
                                                onClick = {
                                                    if (isPlaylistPlaying) {
                                                        viewModel.playerManager.togglePlayPause()
                                                    } else {
                                                        viewModel.playAll()
                                                    }
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                                            ) {
                                                if (isPlaylistPlaying) {
                                                    PlayingEqualizerBars(modifier = Modifier.size(16.dp), color = LocalContentColor.current)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Reproduciendo", fontWeight = FontWeight.Bold)
                                                } else {
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Reproducir", fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = { viewModel.playShuffle() },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                                            ) {
                                                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Aleatorio", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.songs.isEmpty()) {
                        item {
                            EmptyStateView(
                                title = "Playlist vacía",
                                subtitle = "Añade canciones a esta playlist desde tu biblioteca o buscador.",
                                icon = Icons.Rounded.MusicOff
                            )
                        }
                    } else {
                        itemsIndexed(items = state.songs, key = { _, s -> s.id }) { index, song ->
                            val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                            val isSelected = song.songId in state.selectedSongIds

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .then(reorder.row(song.id)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SongCard(
                                        title = song.title,
                                        artist = song.artist,
                                        thumbnailUrl = song.thumbnailUrl,
                                        durationSeconds = song.durationSeconds,
                                        isDownloaded = true,
                                        longPressEnabled = state.isSelectionMode,
                                        isCurrentSong = isCurrent,
                                        isPlayingNow = isPlaying && isCurrent,
                                        isSelectionMode = state.isSelectionMode,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (state.isSelectionMode) {
                                                viewModel.toggleSongSelection(song.songId)
                                            } else {
                                                viewModel.playSong(song)
                                            }
                                        },
                                        onLongClick = if (state.isSelectionMode) {
                                            {
                                                if (!isSelected) {
                                                    viewModel.toggleSongSelection(song.songId)
                                                }
                                                showBatchActionsModal = true
                                            }
                                        } else null,
                                        onPlayNextClick = {
                                            viewModel.playerManager.playNextInQueue(song)
                                            android.widget.Toast.makeText(context, "'${song.title}' se reproducirá a continuación", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onAddToQueueClick = {
                                            viewModel.playerManager.addToQueue(song)
                                            android.widget.Toast.makeText(context, "'${song.title}' añadida a la cola", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onShareClick = {
                                            ShareHelper.shareSong(context, song)
                                        },
                                        onSetAsClick = rememberToneAction(song),
                                        onAddToPlaylistClick = { selectedSongForPlaylist = song.songId },
                                        onRemoveFromPlaylistClick = { viewModel.removeSong(song.songId) },
                                        onDeleteClick = { songToDeletePermanently = song }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom floating action bar for selection mode
            if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurfaceVariant,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showBatchAddToPlaylistDialog = true }) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = TextPrimaryDark, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Añadir", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        VerticalDivider(modifier = Modifier.height(24.dp), color = DarkCardBorder)

                        TextButton(onClick = { showBatchRemoveConfirm = true }) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null, tint = TextPrimaryDark, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Quitar", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        VerticalDivider(modifier = Modifier.height(24.dp), color = DarkCardBorder)

                        TextButton(onClick = { showBatchDeleteConfirm = true }) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AccentRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Borrar (${state.selectedSongIds.size})", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Dialogs
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = DarkSurface,
                title = { Text("¿Eliminar Playlist?", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                text = { Text("Esta acción eliminará la playlist de tu biblioteca. Las canciones descargadas permanecerán en tu dispositivo.", color = TextSecondaryDark) },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            viewModel.deletePlaylist(onComplete = onNavigateBack)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Text("Eliminar", color = TextPrimaryDark)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancelar", color = TextSecondaryDark)
                    }
                }
            )
        }

        // Single song permanent delete confirmation
        songToDeletePermanently?.let { song ->
            AlertDialog(
                onDismissRequest = { songToDeletePermanently = null },
                containerColor = DarkSurface,
                title = { Text("¿Eliminar canción del dispositivo?", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                text = { Text("Se borrará '${song.title}' permanentemente del almacenamiento de tu dispositivo.", color = TextSecondaryDark) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSongPermanently(song.songId)
                            songToDeletePermanently = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Text("Eliminar archivo", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { songToDeletePermanently = null }) {
                        Text("Cancelar", color = TextSecondaryDark)
                    }
                }
            )
        }

        // Batch remove from playlist confirmation
        if (showBatchRemoveConfirm) {
            AlertDialog(
                onDismissRequest = { showBatchRemoveConfirm = false },
                containerColor = DarkSurface,
                title = { Text("¿Quitar de la playlist?", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                text = { Text("Se quitarán ${state.selectedSongIds.size} canciones de esta playlist. Seguirán disponibles en tu biblioteca y dispositivo.", color = TextSecondaryDark) },
                confirmButton = {
                    Button(
                        onClick = {
                            showBatchRemoveConfirm = false
                            viewModel.removeSelectedSongsFromPlaylist()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                    ) {
                        Text("Quitar de la playlist", color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchRemoveConfirm = false }) {
                        Text("Cancelar", color = TextSecondaryDark)
                    }
                }
            )
        }

        // Batch permanent delete from device confirmation
        if (showBatchDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showBatchDeleteConfirm = false },
                containerColor = DarkSurface,
                title = { Text("¿Eliminar del dispositivo?", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                text = { Text("Esta acción eliminará permanentemente ${state.selectedSongIds.size} canciones del almacenamiento de tu teléfono.", color = TextSecondaryDark) },
                confirmButton = {
                    Button(
                        onClick = {
                            showBatchDeleteConfirm = false
                            viewModel.deleteSelectedSongsPermanently()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Text("Eliminar de golpe", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteConfirm = false }) {
                        Text("Cancelar", color = TextSecondaryDark)
                    }
                }
            )
        }

        // Modal triggered when long-pressing a selected song
        if (showBatchActionsModal) {
            AlertDialog(
                onDismissRequest = { showBatchActionsModal = false },
                containerColor = DarkSurface,
                title = { Text("Opciones (${state.selectedSongIds.size} seleccionadas)", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                showBatchActionsModal = false
                                showBatchAddToPlaylistDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = AccentBerry)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Añadir a playlist", color = TextPrimaryDark, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = {
                                showBatchActionsModal = false
                                showBatchRemoveConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null, tint = TextPrimaryDark)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Quitar de esta playlist", color = TextPrimaryDark, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = {
                                showBatchActionsModal = false
                                showBatchDeleteConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AccentRed)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Eliminar del dispositivo", color = AccentRed, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showBatchActionsModal = false }) {
                        Text("Cerrar", color = TextSecondaryDark)
                    }
                }
            )
        }

        // Add to playlist dialogs
        selectedSongForPlaylist?.let { songId ->
            AddToPlaylistDialog(
                songId = songId,
                onDismiss = { selectedSongForPlaylist = null },
                onPlaylistSelected = { pId ->
                    viewModel.addSelectedSongsToPlaylist(pId)
                    selectedSongForPlaylist = null
                },
                onCreateNewPlaylist = { showCreatePlaylistDialog = true }
            )
        }

        if (showBatchAddToPlaylistDialog) {
            AddToPlaylistDialog(
                songId = "",
                onDismiss = { showBatchAddToPlaylistDialog = false },
                onPlaylistSelected = { pId ->
                    viewModel.addSelectedSongsToPlaylist(pId)
                    showBatchAddToPlaylistDialog = false
                },
                onCreateNewPlaylist = { showCreatePlaylistDialog = true }
            )
        }

        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreatePlaylistDialog = false },
                onConfirm = { name, desc ->
                    // Creates playlist via Library / Playlist repo
                    showCreatePlaylistDialog = false
                }
            )
        }
    }
}
