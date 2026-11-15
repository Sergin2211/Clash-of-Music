package com.clashofmusic.app.presentation.library

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.clashofmusic.app.presentation.components.DismissibleSnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.presentation.components.*
import com.clashofmusic.app.presentation.theme.*

private val CardShape12 = RoundedCornerShape(12.dp)
private val ThumbShape8 = RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToPlaylist: (playlistId: String) -> Unit,
    onNavigateToSearch: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val currentSong by viewModel.playerManager.currentSong.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val playbackQueue by viewModel.playerManager.queueState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var selectedSongForPlaylist by remember { mutableStateOf<String?>(null) }
    var selectedSongForEdit by remember { mutableStateOf<DownloadedSong?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showBatchActionsModal by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.syncMessage) {
        state.syncMessage?.let {
            try { snackbarHostState.showSnackbar(it) }
            finally { viewModel.dismissSyncMessage(it) }
        }
    }

    // Stable item click handlers to enable skippable composables
    val onSongClick = remember<(DownloadedSong) -> Unit>(state.isSelectionMode) {
        { song ->
            if (state.isSelectionMode) {
                viewModel.toggleSongSelection(song.songId)
            } else {
                viewModel.playSong(song)
            }
        }
    }

    val onFavoriteSongClick = remember<(DownloadedSong) -> Unit>(state.isSelectionMode, state.filteredFavoriteSongs) {
        { song ->
            if (state.isSelectionMode) {
                viewModel.toggleSongSelection(song.songId)
            } else {
                viewModel.playSong(song, state.filteredFavoriteSongs)
            }
        }
    }

    val onSongLongClick = remember<((DownloadedSong) -> Unit)?>(state.isSelectionMode) {
        if (state.isSelectionMode) {
            { song ->
                if (song.songId !in state.selectedSongIds) {
                    viewModel.toggleSongSelection(song.songId)
                }
                showBatchActionsModal = true
            }
        } else null
    }

    val onAddToPlaylist = remember<(String) -> Unit> {
        { songId -> selectedSongForPlaylist = songId }
    }

    val onEditMetadata = remember<(DownloadedSong) -> Unit> {
        { song -> viewModel.loadMetadata(song) { selectedSongForEdit = it } }
    }

    val onDeleteSong = remember<(DownloadedSong) -> Unit> {
        { song -> viewModel.deleteSong(song) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Contextual Selection Bar OR Normal Header & Tabs
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
                            text = if (state.isDeleting) "Eliminando canciones…" else "${state.selectedSongIds.size} seleccionadas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark,
                            modifier = Modifier.weight(1f)
                        )

                        // Select All
                        val currentTabSongs = when (state.selectedTab) {
                            0 -> state.filteredSongs
                            1 -> state.filteredFavoriteSongs
                            3 -> state.songs.filter { song ->
                                song.artist.equals(state.selectedArtist, ignoreCase = true) &&
                                    (state.searchQuery.isBlank() || song.title.contains(state.searchQuery, true) || song.album?.contains(state.searchQuery, true) == true)
                            }
                            4 -> state.selectedAlbum?.songs.orEmpty()
                            else -> emptyList()
                        }
                        val areAllSelected = currentTabSongs.isNotEmpty() &&
                                currentTabSongs.all { it.songId in state.selectedSongIds }

                        TextButton(
                            onClick = {
                                if (areAllSelected) {
                                    viewModel.clearSelection()
                                } else {
                                    viewModel.selectAll(currentTabSongs)
                                }
                            }
                        ) {
                            Text(
                                text = if (areAllSelected) "Deseleccionar" else "Todas",
                                color = TextPrimaryDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Header: Title & Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biblioteca",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "${state.songs.size} canciones descargadas",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Song selection belongs to the same top header in every song collection.
                        if (state.selectedTab == 0 || state.selectedTab == 1 ||
                            state.selectedTab == 3 && state.selectedArtist != null ||
                            state.selectedTab == 4 && state.selectedAlbum != null) {
                            IconButton(onClick = { viewModel.setSelectionMode(true) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Checklist,
                                    contentDescription = "Selección múltiple",
                                    tint = TextPrimaryDark
                                )
                            }
                        }

                        // Sync button
                        IconButton(
                            onClick = { viewModel.syncStorage() },
                            enabled = !state.isSyncing
                        ) {
                            if (state.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentBerry
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = "Sincronizar almacenamiento",
                                    tint = TextSecondaryDark
                                )
                            }
                        }

                        // Sort Menu (only relevant for Canciones and Favoritos)
                        if (state.selectedTab == 0 || state.selectedTab == 1) {
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Sort,
                                        contentDescription = "Ordenar",
                                        tint = TextPrimaryDark
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier.background(DarkSurfaceVariant)
                                ) {
                                    SortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option.displayName,
                                                    color = TextPrimaryDark,
                                                    fontWeight = if (state.sortOption == option) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                viewModel.onSortOptionChanged(option)
                                                showSortMenu = false
                                            },
                                            leadingIcon = {
                                                if (state.sortOption == option) {
                                                    Icon(
                                                        Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = AccentBerry
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Storage Permission Banner if needed
                val needsManageStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
                if (needsManageStorage) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .clickable {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    context.startActivity(intent)
                                }
                            },
                        shape = CardShape12,
                        color = DarkSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentBerry.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.FolderSpecial, contentDescription = null, tint = AccentBerry, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Permiso de almacenamiento requerido",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark
                                )
                                Text(
                                    text = "Toca para conceder 'Acceso a todos los archivos' y permitir el borrado definitivo de canciones.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimaryDark
                                )
                            }
                        }
                    }
                }

                // Tabs: Canciones, Favoritos, Playlists, Artistas, Álbumes
                ScrollableTabRow(
                    selectedTabIndex = state.selectedTab,
                    containerColor = DarkBackground,
                    contentColor = TextPrimaryDark,
                    edgePadding = 12.dp
                ) {
                    Tab(
                        selected = state.selectedTab == 0,
                        onClick = { viewModel.onTabSelected(0) },
                        text = { Text("Canciones (${state.songs.size})", fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = AccentBerry,
                        unselectedContentColor = TextSecondaryDark
                    )
                    Tab(
                        selected = state.selectedTab == 1,
                        onClick = { viewModel.onTabSelected(1) },
                        text = { Text("Favoritos (${state.favoriteSongs.size})", fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = AccentBerry,
                        unselectedContentColor = TextSecondaryDark
                    )
                    Tab(
                        selected = state.selectedTab == 2,
                        onClick = { viewModel.onTabSelected(2) },
                        text = { Text("Playlists (${state.playlists.count { !it.isAlbum }})", fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = AccentBerry,
                        unselectedContentColor = TextSecondaryDark
                    )
                    Tab(
                        selected = state.selectedTab == 3,
                        onClick = { viewModel.onTabSelected(3) },
                        text = { Text("Artistas (${state.artists.size})", fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = AccentBerry,
                        unselectedContentColor = TextSecondaryDark
                    )
                    Tab(
                        selected = state.selectedTab == 4,
                        onClick = { viewModel.onTabSelected(4) },
                        text = { Text("Álbumes (${state.albums.size})", fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = AccentBerry,
                        unselectedContentColor = TextSecondaryDark
                    )
                }

                // Search filter bar
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Buscar en biblioteca", color = TextMutedDark, fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.FilterList, contentDescription = null, tint = TextPrimaryDark)
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpiar", tint = TextMutedDark)
                            }
                        }
                    },
                    singleLine = true,
                    shape = CardShape12,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Main Content Area per Tab
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.selectedTab) {
                    0 -> {
                        // CANCIONES TAB
                        if (state.filteredSongs.isEmpty()) {
                            EmptyStateView(
                                title = if (state.searchQuery.isEmpty()) "Biblioteca Vacía" else "Sin coincidencias",
                                subtitle = if (state.searchQuery.isEmpty()) "Aún no has descargado ninguna canción. Usa el buscador para agregar música." else "No se encontraron canciones para '${state.searchQuery}'.",
                                icon = Icons.Rounded.MusicOff,
                                actionButtonText = if (state.searchQuery.isEmpty()) "Buscar Música" else null,
                                onActionClick = onNavigateToSearch
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) 140.dp else 90.dp)
                            ) {
                                items(
                                    items = state.filteredSongs,
                                    key = { it.id },
                                    contentType = { "song_item" }
                                ) { song ->
                                    val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                                    val isSelected = song.songId in state.selectedSongIds
                                    DownloadedSongItem(
                                        song = song,
                                        isCurrent = isCurrent,
                                        isPlaying = isPlaying && isCurrent,
                                        isSelectionMode = state.isSelectionMode,
                                        isSelected = isSelected,
                                        onSongClick = onSongClick,
                                        onSongLongClick = onSongLongClick,
                                        onAddToPlaylistClick = onAddToPlaylist,
                                        onEditMetadataClick = onEditMetadata,
                                        onDeleteClick = onDeleteSong
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // FAVORITOS TAB
                        if (state.filteredFavoriteSongs.isEmpty()) {
                            EmptyStateView(
                                title = if (state.searchQuery.isEmpty()) "Sin canciones favoritas" else "Sin coincidencias",
                                subtitle = if (state.searchQuery.isEmpty())
                                    "Toca el icono de corazón en el reproductor para guardar tus canciones favoritas aquí."
                                else "No se encontraron favoritas para '${state.searchQuery}'.",
                                icon = Icons.Rounded.FavoriteBorder
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) 140.dp else 90.dp)
                            ) {
                                item(key = "fav_actions", contentType = "header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.playFavorites() },
                                            modifier = Modifier.weight(1f),
                                            shape = CardShape12,
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isPlaying && currentSong?.songId in state.favoriteSongs.map { it.songId }) "Reproduciendo" else "Reproducir",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.shuffleFavorites() },
                                            shape = CardShape12,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentBerry.copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                                        ) {
                                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Aleatorio")
                                        }
                                    }
                                }

                                items(
                                    items = state.filteredFavoriteSongs,
                                    key = { it.id },
                                    contentType = { "song_item" }
                                ) { song ->
                                    val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                                    val isSelected = song.songId in state.selectedSongIds
                                    DownloadedSongItem(
                                        song = song,
                                        isCurrent = isCurrent,
                                        isPlaying = isPlaying && isCurrent,
                                        isSelectionMode = state.isSelectionMode,
                                        isSelected = isSelected,
                                        onSongClick = onFavoriteSongClick,
                                        onSongLongClick = onSongLongClick,
                                        onAddToPlaylistClick = onAddToPlaylist,
                                        onEditMetadataClick = onEditMetadata,
                                        onDeleteClick = onDeleteSong
                                    )
                                }
                            }
                        }
                    }
                    2 -> {
                        // PLAYLISTS TAB
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                shape = CardShape12,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Crear Nueva Playlist", fontWeight = FontWeight.Bold)
                            }

                            // Pinned "Canciones Favoritas" card
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { viewModel.onTabSelected(1) },
                                shape = CardShape12,
                                color = DarkSurface,
                                tonalElevation = 0.dp,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentBerry.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(ThumbShape8)
                                            .background(
                                                androidx.compose.ui.graphics.Brush.linearGradient(
                                                    listOf(AccentBerry, TextPrimaryDark)
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Favorite,
                                            contentDescription = null,
                                            tint = TextPrimaryDark,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Canciones Favoritas",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${state.favoriteSongs.size} canciones guardadas",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimaryDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = TextMutedDark
                                    )
                                }
                            }

                            val userOnlyPlaylists = remember(state.filteredPlaylists) {
                                state.filteredPlaylists.filter { !it.isAlbum }
                            }

                            if (userOnlyPlaylists.isEmpty() && state.searchQuery.isNotEmpty()) {
                                EmptyStateView(
                                    title = "Sin coincidencias",
                                    subtitle = "No se encontraron playlists para '${state.searchQuery}'.",
                                    icon = Icons.Rounded.QueueMusic
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 90.dp)
                                ) {
                                    items(
                                        items = userOnlyPlaylists,
                                        key = { it.id },
                                        contentType = { "playlist_item" }
                                    ) { playlist ->
                                        PlaylistCard(
                                            playlist = playlist,
                                            onClick = { onNavigateToPlaylist(playlist.id) },
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        // ARTISTAS TAB
                        if (state.selectedArtist != null) {
                            val rawArtistSongs = remember(state.songs, state.selectedArtist) {
                                state.songs.filter { it.artist.equals(state.selectedArtist, ignoreCase = true) }
                            }
                            val artistSongs = remember(rawArtistSongs, state.searchQuery) {
                                if (state.searchQuery.isBlank()) rawArtistSongs else {
                                    rawArtistSongs.filter {
                                        it.title.contains(state.searchQuery, ignoreCase = true) ||
                                                (it.album?.contains(state.searchQuery, ignoreCase = true) == true)
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { viewModel.selectArtist(null) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowBack,
                                            contentDescription = "Volver a Artistas",
                                            tint = AccentBerry
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.selectedArtist!!,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${artistSongs.size} canciones descargadas",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondaryDark
                                        )
                                    }


                                    val isArtistPlaying = isPlaying && currentSong?.artist.equals(state.selectedArtist, ignoreCase = true)
                                    Button(
                                        onClick = {
                                            if (isArtistPlaying) {
                                                viewModel.playerManager.togglePlayPause()
                                            } else {
                                                viewModel.playAllForArtist(state.selectedArtist!!, artistSongs)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        if (isArtistPlaying) {
                                            PlayingEqualizerBars(modifier = Modifier.size(16.dp), color = LocalContentColor.current)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Reproduciendo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reproducir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) 140.dp else 90.dp)
                                ) {
                                    items(
                                        items = artistSongs,
                                        key = { it.id },
                                        contentType = { "song_item" }
                                    ) { song ->
                                        val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                                        val isSelected = song.songId in state.selectedSongIds
                                        DownloadedSongItem(
                                            song = song,
                                            isCurrent = isCurrent,
                                            isPlaying = isPlaying && isCurrent,
                                            isSelectionMode = state.isSelectionMode,
                                            isSelected = isSelected,
                                            onSongClick = { s ->
                                                if (state.isSelectionMode) {
                                                    viewModel.toggleSongSelection(s.songId)
                                                } else {
                                                    viewModel.playSong(s, artistSongs)
                                                }
                                            },
                                            onSongLongClick = onSongLongClick,
                                            onAddToPlaylistClick = onAddToPlaylist,
                                            onEditMetadataClick = onEditMetadata,
                                            onDeleteClick = onDeleteSong
                                        )
                                    }
                                }
                            }
                        } else if (state.filteredArtists.isEmpty()) {
                            EmptyStateView(
                                title = if (state.searchQuery.isEmpty()) "No hay artistas" else "Sin coincidencias",
                                subtitle = if (state.searchQuery.isEmpty()) "Los artistas de tus canciones descargadas aparecerán aquí." else "No se encontraron artistas para '${state.searchQuery}'.",
                                icon = Icons.Rounded.Person
                            )
                        } else {
                            val artistCounts = remember(state.songs) {
                                state.songs.groupingBy { it.artist }.eachCount()
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)
                            ) {
                                items(
                                    items = state.filteredArtists,
                                    key = { it },
                                    contentType = { "artist_item" }
                                ) { artist ->
                                    val count = artistCounts[artist] ?: 0
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable { viewModel.selectArtist(artist) },
                                        color = DarkSurface,
                                        shape = CardShape12
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(RoundedCornerShape(22.dp))
                                                    .background(DarkSurfaceVariant)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .align(Alignment.Center),
                                                    tint = AccentBerry
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = artist,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimaryDark
                                                )
                                                Text(
                                                    text = "$count canciones descargadas",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondaryDark
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Rounded.ChevronRight,
                                                contentDescription = null,
                                                tint = TextMutedDark
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    4 -> {
                        // ÁLBUMES TAB
                        if (state.selectedAlbum != null) {
                            val albumSongs = remember(state.songs, state.selectedAlbum) {
                                state.selectedAlbum!!.songs
                            }
                            val albumArtist = state.selectedAlbum!!.artist

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { viewModel.selectAlbum(null) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.ArrowBack,
                                            contentDescription = "Volver a Álbumes",
                                            tint = AccentBerry
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = state.selectedAlbum!!.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$albumArtist  /  ${albumSongs.size} canciones",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondaryDark
                                        )
                                    }


                                    val baseQueue = remember(playbackQueue.entries) {
                                        playbackQueue.entries.filter { it.origin == com.clashofmusic.app.data.player.QueueEntryOrigin.LIST }.map { it.song }
                                    }
                                    val isAlbumPlaying = isPlaying && state.selectedAlbum!!.matchesPlayingQueue(currentSong, baseQueue)
                                    Button(
                                        onClick = {
                                            if (isAlbumPlaying) {
                                                viewModel.playerManager.togglePlayPause()
                                            } else {
                                                viewModel.playAllForAlbum(state.selectedAlbum!!, albumSongs)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        if (isAlbumPlaying) {
                                            PlayingEqualizerBars(modifier = Modifier.size(16.dp), color = LocalContentColor.current)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Reproduciendo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        } else {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Reproducir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = if (state.isSelectionMode && state.selectedSongIds.isNotEmpty()) 140.dp else 90.dp)
                                ) {
                                    items(
                                        items = albumSongs,
                                        key = { it.id },
                                        contentType = { "song_item" }
                                    ) { song ->
                                        val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                                        val isSelected = song.songId in state.selectedSongIds
                                        DownloadedSongItem(
                                            song = song,
                                            isCurrent = isCurrent,
                                            isPlaying = isPlaying && isCurrent,
                                            isSelectionMode = state.isSelectionMode,
                                            isSelected = isSelected,
                                            onSongClick = { s ->
                                                if (state.isSelectionMode) {
                                                    viewModel.toggleSongSelection(s.songId)
                                                } else {
                                                    viewModel.playSong(s, albumSongs)
                                                }
                                            },
                                            onSongLongClick = onSongLongClick,
                                            onAddToPlaylistClick = onAddToPlaylist,
                                            onEditMetadataClick = onEditMetadata,
                                            onDeleteClick = onDeleteSong
                                        )
                                    }
                                }
                            }
                        } else if (state.filteredAlbums.isEmpty()) {
                            EmptyStateView(
                                title = if (state.searchQuery.isEmpty()) "No hay álbumes" else "Sin coincidencias",
                                subtitle = if (state.searchQuery.isEmpty()) "Los álbumes descargados se organizarán automáticamente aquí." else "No se encontraron álbumes para '${state.searchQuery}'.",
                                icon = Icons.Rounded.Album
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)
                            ) {
                                items(
                                    items = state.filteredAlbums,
                                    key = { it.key.stableId },
                                    contentType = { "album_item" }
                                ) { album ->
                                    val albumThumbnail = album.thumbnailUrl
                                    val albumArtist = album.artist
                                    val albumGenre = album.genre
                                    val count = album.songs.size

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable { viewModel.selectAlbum(album) },
                                        color = DarkSurface,
                                        shape = CardShape12
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(ThumbShape8)
                                                    .background(DarkSurfaceVariant)
                                            ) {
                                                if (!albumThumbnail.isNullOrEmpty()) {
                                                    val context = LocalContext.current
                                                    val req = remember(albumThumbnail) {
                                                        ImageRequest.Builder(context)
                                                            .data(albumThumbnail)
                                                            .crossfade(false)
                                                            .build()
                                                    }
                                                    AsyncImage(
                                                        model = req,
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Album,
                                                        contentDescription = null,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .align(Alignment.Center),
                                                        tint = AccentBerry
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimaryDark,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = albumArtist,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondaryDark,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (albumGenre.isNullOrEmpty()) "$count canciones" else "$albumGenre  /  $count canciones",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextPrimaryDark
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Rounded.ChevronRight,
                                                contentDescription = null,
                                                tint = TextMutedDark
                                            )
                                        }
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
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showBatchAddToPlaylistDialog = true }) {
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = TextPrimaryDark, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Añadir a playlist", color = TextPrimaryDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            VerticalDivider(modifier = Modifier.height(24.dp), color = DarkCardBorder)

                            TextButton(onClick = { showBatchDeleteConfirm = true }) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AccentRed, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Eliminar (${state.selectedSongIds.size})", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        DismissibleSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }

    // Dialogs
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            containerColor = DarkBackground,
            title = {
                Text(
                    text = "¿Eliminar ${state.selectedSongIds.size} canciones?",
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Esta acción eliminará permanentemente las canciones seleccionadas del almacenamiento de tu dispositivo.",
                    color = TextSecondaryDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBatchDeleteConfirm = false
                        viewModel.deleteSelectedSongs()
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
            containerColor = DarkBackground,
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
                            showBatchDeleteConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AccentRed)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Eliminar canciones del dispositivo", color = AccentRed, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
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

    if (showBatchAddToPlaylistDialog) {
        AddToPlaylistDialog(
            songId = "",
            onDismiss = { showBatchAddToPlaylistDialog = false },
            onPlaylistSelected = { pId ->
                viewModel.addSelectedSongsToPlaylist(pId)
                showBatchAddToPlaylistDialog = false
            },
            onCreateNewPlaylist = {
                showCreatePlaylistDialog = true
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name, desc -> viewModel.createPlaylist(name, desc) }
        )
    }

    selectedSongForPlaylist?.let { songId ->
        AddToPlaylistDialog(
            songId = songId,
            onDismiss = { selectedSongForPlaylist = null },
            onPlaylistSelected = { pId -> viewModel.addSongToPlaylist(pId, songId) },
            onCreateNewPlaylist = { showCreatePlaylistDialog = true }
        )
    }

    selectedSongForEdit?.let { song ->
        EditMetadataDialog(
            initialTitle = song.title,
            initialArtist = song.artist,
            initialAlbum = song.album,
            initialYear = song.year,
            onDismiss = { selectedSongForEdit = null },
            onConfirmWithYear = { title, artist, album, year ->
                viewModel.updateMetadata(song.songId, title, artist, album, year)
            }
        )
    }
}

/**
 * Highly optimized, skippable item composable.
 * Compose will skip recomposing this item during scrolling or playback state changes
 * if its parameters (song, isCurrent, isPlaying, isSelected) remain unchanged.
 */
@Composable
private fun DownloadedSongItem(
    song: DownloadedSong,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSongClick: (DownloadedSong) -> Unit,
    onSongLongClick: ((DownloadedSong) -> Unit)?,
    onAddToPlaylistClick: (String) -> Unit,
    onEditMetadataClick: (DownloadedSong) -> Unit,
    onDeleteClick: (DownloadedSong) -> Unit,
    deleteLabel: String? = null,
    onPlayNextClick: ((DownloadedSong) -> Unit)? = null,
    onAddToQueueClick: ((DownloadedSong) -> Unit)? = null,
    onShareClick: ((DownloadedSong) -> Unit)? = null
) {
    val context = LocalContext.current
    val onClickLambda = remember(song, onSongClick) { { onSongClick(song) } }
    val onLongClickLambda = remember(song, onSongLongClick) { onSongLongClick?.let { { it(song) } } }
    val onAddToPlaylistLambda = remember(song.songId, onAddToPlaylistClick) { { onAddToPlaylistClick(song.songId) } }
    val onEditMetadataLambda = remember(song, onEditMetadataClick) { { onEditMetadataClick(song) } }
    val onDeleteLambda = remember(song, onDeleteClick) { { onDeleteClick(song) } }
    val onPlayNextLambda = remember(song, onPlayNextClick) {
        {
            if (onPlayNextClick != null) {
                onPlayNextClick(song)
            } else {
                com.clashofmusic.app.di.ServiceLocator.playerManager.playNextInQueue(song)
                android.widget.Toast.makeText(context, "'${song.title}' se reproducirá a continuación", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onAddToQueueLambda = remember(song, onAddToQueueClick) {
        {
            if (onAddToQueueClick != null) {
                onAddToQueueClick(song)
            } else {
                com.clashofmusic.app.di.ServiceLocator.playerManager.addToQueue(song)
                android.widget.Toast.makeText(context, "'${song.title}' añadida a la cola", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onShareLambda = remember(song, onShareClick) {
        {
            if (onShareClick != null) {
                onShareClick(song)
            } else {
                ShareHelper.shareSong(context, song)
            }
        }
    }

    SongCard(
        title = song.title,
        artist = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        durationSeconds = song.durationSeconds,
        isDownloaded = true,
        isCurrentSong = isCurrent,
        isPlayingNow = isPlaying,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onClick = onClickLambda,
        onLongClick = onLongClickLambda,
        onPlayNextClick = onPlayNextLambda,
        onAddToQueueClick = onAddToQueueLambda,
        onShareClick = onShareLambda,
        onAddToPlaylistClick = onAddToPlaylistLambda,
        onEditMetadataClick = onEditMetadataLambda,
        onSetAsClick = rememberToneAction(song),
        onDeleteClick = onDeleteLambda,
        deleteLabel = deleteLabel,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = DarkSurface,
        shape = CardShape12,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(ThumbShape8)
                    .background(DarkSurfaceVariant)
            ) {
                if (!playlist.thumbnailUrl.isNullOrEmpty()) {
                    val context = LocalContext.current
                    val req = remember(playlist.thumbnailUrl) {
                        ImageRequest.Builder(context)
                            .data(playlist.thumbnailUrl)
                            .size(140, 140)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = req,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (playlist.isAlbum) Icons.Rounded.Album else Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .align(Alignment.Center),
                        tint = AccentBerry
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = playlist.description ?: if (playlist.isAlbum) "Álbum local" else "Playlist local",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextMutedDark
            )
        }
    }
}
