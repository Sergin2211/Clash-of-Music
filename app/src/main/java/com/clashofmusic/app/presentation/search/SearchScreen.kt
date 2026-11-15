package com.clashofmusic.app.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clashofmusic.app.domain.model.AlbumItem
import com.clashofmusic.app.domain.model.AlbumTrackItem
import com.clashofmusic.app.domain.model.PlaylistSource
import com.clashofmusic.app.domain.model.RemotePlaylistInfo
import com.clashofmusic.app.presentation.components.*
import com.clashofmusic.app.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToSongDetails: (songId: String) -> Unit,
    initialQuery: String? = null,
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshDownloadAvailability()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            viewModel.onQueryChanged(initialQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Text(
            "Buscar",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimaryDark,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp)
        )
        // Top Tab Row (Buscador / Descargar Enlace)
        TabRow(
            selectedTabIndex = state.selectedTab,
            containerColor = DarkBackground,
            contentColor = TextPrimaryDark
        ) {
            Tab(
                selected = state.selectedTab == 0,
                onClick = { viewModel.onTabSelected(0) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Búsqueda", fontWeight = FontWeight.Bold)
                    }
                },
                selectedContentColor = AccentBerry,
                unselectedContentColor = TextSecondaryDark
            )
            Tab(
                selected = state.selectedTab == 1,
                onClick = { viewModel.onTabSelected(1) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descargar Enlace", fontWeight = FontWeight.Bold)
                    }
                },
                selectedContentColor = AccentBerry,
                unselectedContentColor = TextSecondaryDark
            )
        }

        if (state.selectedTab == 0) {
            // TAB 0: NORMAL SEARCH
            SearchTabContent(
                query = state.query,
                isSearching = state.isSearching,
                results = state.searchResults,
                albums = state.albumResults,
                searchFilter = state.searchFilter,
                history = state.searchHistory,
                errorMessage = state.errorMessage,
                downloadingSongIds = state.downloadingSongIds,
                downloadedSongIds = state.downloadedSongIds,
                onQueryChange = viewModel::onQueryChanged,
                onFilterChange = viewModel::onSearchFilterChanged,
                onAlbumClick = viewModel::selectAlbum,
                onHistoryItemClick = viewModel::onHistoryItemClicked,
                onDeleteHistoryItem = viewModel::deleteHistoryItem,
                onClearHistory = viewModel::clearHistory,
                onSongClick = { onNavigateToSongDetails(it.id) },
                onDownloadClick = viewModel::downloadSong
            )
        } else {
            // TAB 1: LINK DOWNLOADER (YOUTUBE & SPOTIFY)
            LinkDownloaderTabContent(
                linkInput = state.linkInput,
                isExtracting = state.isExtractingLink,
                isQueueing = state.isQueueingLink,
                downloadState = state.linkDownloadState,
                extractedPlaylist = state.extractedPlaylist,
                selectedFormat = state.selectedLinkFormat,
                errorMessage = state.linkErrorMessage,
                successMessage = state.linkSuccessMessage,
                onLinkChange = viewModel::onLinkInputChanged,
                onFormatSelect = viewModel::onLinkFormatSelected,
                onPasteClick = {
                    clipboardManager.getText()?.text?.let { pasted ->
                        viewModel.onLinkInputChanged(pasted)
                    }
                },
                onExtractClick = { viewModel.extractLink(state.linkInput) },
                onDownloadAll = viewModel::downloadExtractedPlaylist
            )
        }

        if (state.selectedAlbum != null) {
            AlbumDetailDialog(
                album = state.selectedAlbum!!,
                tracks = state.albumTracks,
                isLoadingTracks = state.isLoadingAlbumTracks,
                downloadingSongIds = state.downloadingSongIds,
                downloadedSongIds = state.downloadedSongIds,
                onDismiss = { viewModel.selectAlbum(null) },
                selectedFormat = state.selectedAlbumFormat,
                isQueueing = state.isQueueingAlbum,
                downloadState = state.albumDownloadState,
                downloadMessage = state.albumDownloadMessage,
                trackMessages = state.albumTrackMessages,
                resolvingTracks = state.resolvingAlbumTracks,
                onFormatSelect = viewModel::onAlbumFormatSelected,
                onDownloadAlbum = { viewModel.downloadAlbum(state.selectedAlbum!!, state.albumTracks) },
                onDownloadTrack = { track -> viewModel.downloadAlbumTrack(state.selectedAlbum!!, track) }
            )
        }
    }
}

@Composable
private fun SearchTabContent(
    query: String,
    isSearching: Boolean,
    results: List<com.clashofmusic.app.domain.model.SongItem>,
    albums: List<com.clashofmusic.app.domain.model.AlbumItem>,
    searchFilter: Int,
    history: List<com.clashofmusic.app.domain.model.SearchHistoryItem>,
    errorMessage: String?,
    downloadingSongIds: Set<String> = emptySet(),
    downloadedSongIds: Set<String> = emptySet(),
    onQueryChange: (String) -> Unit,
    onFilterChange: (Int) -> Unit,
    onAlbumClick: (com.clashofmusic.app.domain.model.AlbumItem) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSongClick: (com.clashofmusic.app.domain.model.SongItem) -> Unit,
    onDownloadClick: (com.clashofmusic.app.domain.model.SongItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search Input
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar canción, artista o álbum...", color = TextMutedDark) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = AccentBerry)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Limpiar", tint = TextMutedDark)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
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
                .padding(16.dp)
        )

        // Filter chips when results exist
        if (!isSearching && (results.isNotEmpty() || albums.isNotEmpty())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = searchFilter == 0,
                    onClick = { onFilterChange(0) },
                    label = { Text("Todos") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBerry,
                        selectedLabelColor = TextPrimaryDark,
                        containerColor = DarkBackground,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = searchFilter == 1,
                    onClick = { onFilterChange(1) },
                    label = { Text("Canciones (${results.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBerry,
                        selectedLabelColor = TextPrimaryDark,
                        containerColor = DarkBackground,
                        labelColor = TextSecondaryDark
                    )
                )
                FilterChip(
                    selected = searchFilter == 2,
                    onClick = { onFilterChange(2) },
                    label = { Text("Álbumes (${albums.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentBerry,
                        selectedLabelColor = TextPrimaryDark,
                        containerColor = DarkBackground,
                        labelColor = TextSecondaryDark
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)
        ) {
            // Search History Chips (visible when query is empty)
            if (query.isEmpty() && history.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Búsquedas recientes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        TextButton(onClick = onClearHistory) {
                            Text("Borrar todo", color = TextPrimaryDark, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        items(history, key = { it.id }) { item ->
                            InputChip(
                                selected = false,
                                onClick = { onHistoryItemClick(item.query) },
                                label = { Text(item.query, color = TextPrimaryDark) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Eliminar",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onDeleteHistoryItem(item.query) },
                                        tint = TextMutedDark
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = DarkBackground,
                                    labelColor = TextPrimaryDark
                                ),
                                border = InputChipDefaults.inputChipBorder(
                                    borderColor = DarkCardBorder,
                                    enabled = true,
                                    selected = false
                                )
                            )
                        }
                    }
                }
            }

            // Loading state
            if (isSearching) {
                item {
                    LoadingStateView(message = "Buscando canciones y álbumes...")
                }
            }

            // Error state
            if (errorMessage != null && !isSearching) {
                item {
                    ErrorStateView(message = errorMessage)
                }
            }

            // SECTION: ALBUMS (if filter is Todos or Álbumes)
            if (!isSearching && albums.isNotEmpty() && (searchFilter == 0 || searchFilter == 2)) {
                item {
                    Text(
                        text = "Álbumes (${albums.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (searchFilter == 0) {
                    // Horizontal scroll for compact view in "Todos"
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            items(albums, key = { it.id }) { album ->
                                AlbumCardHorizontal(
                                    album = album,
                                    onClick = { onAlbumClick(album) }
                                )
                            }
                        }
                    }
                } else {
                    // Vertical list in "Álbumes" view
                    items(albums, key = { it.id }) { album ->
                        AlbumCardVertical(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }
                }
            }

            // SECTION: SONGS (if filter is Todos or Canciones)
            if (!isSearching && results.isNotEmpty() && (searchFilter == 0 || searchFilter == 1)) {
                item {
                    Text(
                        text = "Canciones (${results.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(items = results, key = { it.id }, contentType = { "search_result" }) { song ->
                    val isDownloading = song.id in downloadingSongIds
                    val isDownloaded = song.id in downloadedSongIds
                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        SongCard(
                            title = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.thumbnailUrl,
                            durationSeconds = song.durationSeconds,
                            isDownloading = isDownloading,
                            isDownloaded = isDownloaded,
                            onSetAsClick = rememberToneAction(song.id, isDownloaded),
                            onClick = { onSongClick(song) },
                            onDownloadClick = { onDownloadClick(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCardHorizontal(
    album: com.clashofmusic.app.domain.model.AlbumItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .size(134.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
            ) {
                if (!album.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = album.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.Album,
                        contentDescription = null,
                        tint = AccentBerry,
                        modifier = Modifier.align(Alignment.Center).size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!album.genre.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    color = AccentBerry.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = album.genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimaryDark,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumCardVertical(
    album: com.clashofmusic.app.domain.model.AlbumItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
            ) {
                if (!album.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = album.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.Album, contentDescription = null, tint = AccentBerry)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
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
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark
                )
                val subtitle = buildString {
                    if (!album.genre.isNullOrEmpty()) append("${album.genre}  /  ")
                    if (!album.year.isNullOrEmpty()) append("${album.year}  /  ")
                    append("${album.trackCount} canciones")
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMutedDark
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMutedDark)
        }
    }
}

@Composable
private fun AlbumDetailDialog(
    album: com.clashofmusic.app.domain.model.AlbumItem,
    tracks: List<com.clashofmusic.app.domain.model.AlbumTrackItem>,
    isLoadingTracks: Boolean,
    downloadingSongIds: Set<String> = emptySet(),
    downloadedSongIds: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    selectedFormat: com.clashofmusic.app.domain.model.AudioFormat,
    isQueueing: Boolean,
    downloadState: com.clashofmusic.app.domain.usecase.CollectionDownloadState,
    downloadMessage: String?,
    trackMessages: Map<Int, String>,
    resolvingTracks: Set<Int>,
    onFormatSelect: (com.clashofmusic.app.domain.model.AudioFormat) -> Unit,
    onDownloadAlbum: () -> Unit,
    onDownloadTrack: (com.clashofmusic.app.domain.model.AlbumTrackItem) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Top header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                        ) {
                            if (!album.thumbnailUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = album.thumbnailUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Album,
                                    contentDescription = null,
                                    tint = AccentBerry,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = album.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!album.genre.isNullOrEmpty()) {
                                    Surface(
                                        color = AccentBerry.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = album.genre,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextPrimaryDark,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                val subtitle = buildString {
                                    if (!album.year.isNullOrEmpty()) append("${album.year}  /  ")
                                    append("${album.trackCount} canciones")
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMutedDark
                                )
                            }
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = TextMutedDark)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Formato de audio", color = TextPrimaryDark)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(com.clashofmusic.app.domain.model.AudioFormat.entries) { format ->
                        FilterChip(selected = selectedFormat == format,
                            onClick = { onFormatSelect(format) }, label = { Text(format.name) })
                    }
                }

                Text("Las canciones en otro formato se reemplazan al terminar. Las que ya coinciden se conservan.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
                // Download full album button
                Button(
                    onClick = onDownloadAlbum,
                    enabled = tracks.isNotEmpty() && !isLoadingTracks && !isQueueing && downloadState.canDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                ) {
                    if (isQueueing || downloadState.active) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isQueueing || downloadState.active -> "Descargando álbum"
                            downloadState.checking -> "Comprobando descargas…"
                            downloadState.complete -> "Álbum descargado"
                            else -> "Descargar Álbum (${downloadState.missing} ${if (downloadState.missing == 1) "canción" else "canciones"})"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                downloadMessage?.let { Text(it, color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(color = DarkCardBorder)
                Spacer(modifier = Modifier.height(6.dp))

                // Track listing
                if (isLoadingTracks) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBerry)
                    }
                } else if (tracks.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Cargando información del álbum...", color = TextMutedDark)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(tracks) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${track.trackNumber}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMutedDark,
                                    modifier = Modifier.width(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimaryDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val durationStr = if (track.durationSeconds > 0) {
                                        val min = track.durationSeconds / 60
                                        val sec = track.durationSeconds % 60
                                        String.format("%d:%02d", min, sec)
                                    } else ""
                                    Text(
                                        text = "${track.artist}${if (durationStr.isNotEmpty()) "  /  $durationStr" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondaryDark
                                    )
                                    trackMessages[track.trackNumber]?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
                                    }
                                }
                                val isTrackDownloading = track.trackNumber in resolvingTracks
                                if (isTrackDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp).padding(2.dp),
                                        strokeWidth = 2.dp,
                                        color = AccentBerry
                                    )
                                } else {
                                    IconButton(enabled = !isQueueing && resolvingTracks.isEmpty(), onClick = { onDownloadTrack(track) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Descargar canción",
                                            tint = AccentBerry
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkDownloaderTabContent(
    linkInput: String,
    isExtracting: Boolean,
    isQueueing: Boolean,
    downloadState: com.clashofmusic.app.domain.usecase.CollectionDownloadState,
    extractedPlaylist: RemotePlaylistInfo?,
    selectedFormat: com.clashofmusic.app.domain.model.AudioFormat,
    errorMessage: String?,
    successMessage: String?,
    onLinkChange: (String) -> Unit,
    onFormatSelect: (com.clashofmusic.app.domain.model.AudioFormat) -> Unit,
    onPasteClick: () -> Unit,
    onExtractClick: () -> Unit,
    onDownloadAll: (RemotePlaylistInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        item {
            Text(
                text = "Descargador de Playlists y Álbumes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pega un enlace de YouTube, YouTube Music o Spotify para extraer todas las canciones y guardarlas en tu Biblioteca.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Link Input Box
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = linkInput,
                    onValueChange = onLinkChange,
                    placeholder = { Text("YouTube, Spotify o SoundCloud", color = TextMutedDark, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = onPasteClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = DarkSurfaceVariant)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Pegar", tint = AccentBerry)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Spacer(modifier = Modifier.height(14.dp))

            // Selector de Formato de Audio (MP3, M4A, OPUS)
            Text(
                text = "Formato de Audio:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    com.clashofmusic.app.domain.model.AudioFormat.MP3,
                    com.clashofmusic.app.domain.model.AudioFormat.M4A,
                    com.clashofmusic.app.domain.model.AudioFormat.OPUS,
                    com.clashofmusic.app.domain.model.AudioFormat.ORIGINAL
                ).forEach { fmt ->
                    val isSel = selectedFormat == fmt
                    FilterChip(
                        selected = isSel,
                        onClick = { onFormatSelect(fmt) },
                        label = { Text(fmt.name, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentBerry,
                            selectedLabelColor = TextPrimaryDark,
                            containerColor = DarkBackground,
                            labelColor = TextSecondaryDark
                        ),
                        border = if (isSel) null else FilterChipDefaults.filterChipBorder(enabled = true, selected = false, borderColor = DarkCardBorder)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (selectedFormat == com.clashofmusic.app.domain.model.AudioFormat.ORIGINAL)
                    "Audio original sin conversión." else "Conversión automática de alta calidad. No mejora la calidad de la fuente.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark
            )

            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onExtractClick,
                enabled = linkInput.isNotBlank() && !isExtracting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = TextPrimaryDark
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analizando enlace...", color = TextPrimaryDark)
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analizar Enlace", color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Messages
        if (successMessage != null) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    color = AccentMint.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AccentMint)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = successMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark
                        )
                    }
                }
            }
        }

        if (errorMessage != null) {
            item {
                ErrorStateView(message = errorMessage)
            }
        }

        // Extracted Playlist Preview Card
        if (extractedPlaylist != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = if (extractedPlaylist.tracks.isEmpty()) RoundedCornerShape(20.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = DarkSurface,
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurfaceVariant)
                            ) {
                                if (!extractedPlaylist.thumbnailUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = extractedPlaylist.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Album,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .align(Alignment.Center),
                                        tint = AccentBerry
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = extractedPlaylist.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${extractedPlaylist.author}  /  ${extractedPlaylist.trackCount} canciones",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                                extractedPlaylist.readWarning?.let { warning ->
                                    Text(warning, style = MaterialTheme.typography.bodySmall, color = TextPrimaryDark)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                SuggestionChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = if (extractedPlaylist.source == PlaylistSource.SPOTIFY) "Spotify" else "YouTube",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = AccentBerry.copy(alpha = 0.15f),
                                        labelColor = TextPrimaryDark
                                    ),
                                    border = null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onDownloadAll(extractedPlaylist) },
                            enabled = !isQueueing && downloadState.canDownload,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                        ) {
                            if (isQueueing || downloadState.active) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.DownloadForOffline, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isQueueing || downloadState.active -> if (extractedPlaylist.isAlbum) "Descargando álbum" else "Descargando playlist"
                                    downloadState.checking -> "Comprobando descargas…"
                                    downloadState.complete -> if (extractedPlaylist.isAlbum) "Álbum descargado" else "Playlist descargada"
                                    else -> if (extractedPlaylist.isAlbum) "Descargar Álbum (${downloadState.missing} ${if (downloadState.missing == 1) "canción" else "canciones"})" else "Descargar Todo y Crear Playlist"
                                },
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider(color = DarkCardBorder)
                        Spacer(Modifier.height(16.dp))
                        Text("Canciones en la lista (${extractedPlaylist.tracks.size})",
                            color = TextPrimaryDark, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            itemsIndexed(extractedPlaylist.tracks, key = { index, _ -> "${extractedPlaylist.id}:$index" }, contentType = { _, _ -> "linked-track" }) { index, track ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(),
                    color = DarkSurface,
                    shape = if (index == extractedPlaylist.tracks.lastIndex) RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp) else RoundedCornerShape(0.dp)
                ) {
                    Column {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}", modifier = Modifier.width(28.dp), color = TextMutedDark,
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (index != extractedPlaylist.tracks.lastIndex) HorizontalDivider(
                        modifier = Modifier.padding(start = 54.dp, end = 16.dp), color = DarkCardBorder.copy(alpha = .5f))
                    else Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
