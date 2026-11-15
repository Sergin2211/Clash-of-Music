package com.clashofmusic.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashofmusic.app.R
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.genreNames
import com.clashofmusic.app.presentation.components.LoadingStateView
import com.clashofmusic.app.presentation.components.SectionHeader
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.presentation.components.PlayingEqualizerBars
import com.clashofmusic.app.presentation.components.SongCard
import com.clashofmusic.app.presentation.components.rememberToneAction
import com.clashofmusic.app.presentation.theme.*

private val CardShape16 = RoundedCornerShape(16.dp)
private val CardShape14 = RoundedCornerShape(14.dp)
private val CardShape12 = RoundedCornerShape(12.dp)
private val ThumbShape8 = RoundedCornerShape(8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: (query: String?) -> Unit,
    onNavigateToSongDetails: (songId: String) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showGenreManager by remember { mutableStateOf(false) }
    if (showGenreManager) com.clashofmusic.app.presentation.components.GenreManagerDialog({ showGenreManager = false })
    val playerManager = remember { ServiceLocator.playerManager }
    val currentSong by playerManager.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()

    val onSongClick = remember<(DownloadedSong) -> Unit> {
        { song -> viewModel.playDownloadedSong(song) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // App Header
        item(key = "header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            text = "Clash of Music",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark,
                            fontSize = 26.sp
                        )
                        Text(
                            text = "Tu música, siempre contigo",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark
                        )
                    }
                }

                if (state.activeDownloadsCount > 0) {
                    Badge(
                        containerColor = AccentBerry,
                        modifier = Modifier.clickable(onClick = onNavigateToDownloads)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TextPrimaryDark
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${state.activeDownloadsCount}",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                        }
                    }
                }
            }
        }

        // Quick search hero banner
        item(key = "search_banner", contentType = "banner") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onNavigateToSearch(null) },
                shape = CardShape16,
                color = DarkSurface,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Buscar",
                        tint = AccentBerry,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Buscar música o pegar un enlace",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark
                    )
                }
            }
        }

        // Stats summary chips
        item(key = "stats", contentType = "stats") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    title = "Descargadas",
                    count = state.downloadedSongs.size.toString(),
                    onClick = onNavigateToLibrary,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Playlists",
                    count = state.playlists.count { !it.isAlbum }.toString(),
                    onClick = onNavigateToLibrary,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Descargas",
                    count = state.activeDownloadsCount.toString(),
                    onClick = onNavigateToDownloads,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Recently Downloaded / Listened Section
        if (state.downloadedSongs.isNotEmpty()) {
            item(key = "recent_header", contentType = "section_header") {
                SectionHeader(
                    title = "Recientes",
                    actionText = "Ver todas",
                    onActionClick = onNavigateToLibrary
                )
            }

            items(
                items = state.downloadedSongs.take(5),
                key = { it.id },
                contentType = { "recent_song" }
            ) { song ->
                val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                DownloadedSongHomeItem(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying && isCurrent,
                    onClick = onSongClick,
                    artworkEmphasis = song.id == state.downloadedSongs.firstOrNull()?.id
                )
            }
        }

        // Genres Recommendation Section (appears dynamically as songs are downloaded)
        if (state.downloadedSongs.isNotEmpty()) {
            item(key = "genres_header", contentType = "section_header") {
                SectionHeader(
                    title = "Explorar por género",
                    actionText = "Gestionar",
                    onActionClick = { showGenreManager = true }
                )
            }

            item(key = "genres_row", contentType = "horizontal_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = state.genres,
                        key = { it.name },
                        contentType = { "genre_item" }
                    ) { genre ->
                        GenreCard(
                            genre = genre,
                            onClick = { viewModel.selectGenre(genre.name) }
                        )
                    }
                }
            }
        }

        // Trending / Recommendations
        item(key = "trending_header", contentType = "section_header") {
            SectionHeader(
                title = "Tendencias",
                actionText = "Explorar",
                onActionClick = { onNavigateToSearch("Tendencias") }
            )
        }

        if (state.isLoadingTrending) {
            item(key = "trending_loading", contentType = "loading") {
                LoadingStateView(message = "Buscando tendencias musicales...")
            }
        } else if (state.trendingSongs.isNotEmpty()) {
            item(key = "trending_row", contentType = "horizontal_row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = state.trendingSongs,
                        key = { it.id },
                        contentType = { "trending_song" }
                    ) { song ->
                        TrendingSongCard(
                            songTitle = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.thumbnailUrl,
                            onClick = { onNavigateToSongDetails(song.id) }
                        )
                    }
                }
            }
        }
    }

    // Modal BottomSheet for songs of selected genre
    if (state.selectedGenre != null) {
        val selectedGenre = state.selectedGenre!!
        val genreSongs = remember(state.downloadedSongs, selectedGenre) {
            state.downloadedSongs.filter { it.genreNames.any { name -> name.equals(selectedGenre, true) } }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val configuration = LocalConfiguration.current
        val maxListHeight = (configuration.screenHeightDp * 0.65f).dp

        ModalBottomSheet(
            onDismissRequest = { viewModel.selectGenre(null) },
            sheetState = sheetState,
            containerColor = DarkSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = AccentBerry) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedGenre,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "${genreSongs.size} canciones en tu biblioteca",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark
                        )
                    }

                    if (genreSongs.isNotEmpty()) {
                        val isGenrePlaying = isPlaying && genreSongs.any { it.songId == currentSong?.songId || it.id == currentSong?.id }
                        Button(
                            onClick = {
                                if (isGenrePlaying) {
                                    playerManager.togglePlayPause()
                                } else {
                                    viewModel.playGenreSongs(selectedGenre)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isGenrePlaying) AccentBerryDark else AccentBerry),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            if (isGenrePlaying) {
                                PlayingEqualizerBars(modifier = Modifier.size(16.dp), color = TextPrimaryDark)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reproduciendo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reproducir todo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = genreSongs,
                        key = { it.id },
                        contentType = { "genre_song" }
                    ) { song ->
                        val isCurrent = currentSong?.songId == song.songId || currentSong?.id == song.id
                        SongCard(
                            title = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.thumbnailUrl,
                            durationSeconds = song.durationSeconds,
                            isDownloaded = true,
                            isCurrentSong = isCurrent,
                            isPlayingNow = isPlaying && isCurrent,
                            onClick = { viewModel.playGenreSong(song, selectedGenre) },
                            onSetAsClick = rememberToneAction(song)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedSongHomeItem(
    song: DownloadedSong,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: (DownloadedSong) -> Unit,
    artworkEmphasis: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onClickLambda = remember(song, onClick) { { onClick(song) } }
    SongCard(
        title = song.title,
        artist = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        durationSeconds = song.durationSeconds,
        isDownloaded = true,
        isCurrentSong = isCurrent,
        isPlayingNow = isPlaying,
        onClick = onClickLambda,
        artworkEmphasis = artworkEmphasis,
        onSetAsClick = rememberToneAction(song),
        onPlayNextClick = {
            com.clashofmusic.app.di.ServiceLocator.playerManager.playNextInQueue(song)
            android.widget.Toast.makeText(context, "'${song.title}' se reproducirá a continuación", android.widget.Toast.LENGTH_SHORT).show()
        },
        onAddToQueueClick = {
            com.clashofmusic.app.di.ServiceLocator.playerManager.addToQueue(song)
            android.widget.Toast.makeText(context, "'${song.title}' añadida a la cola", android.widget.Toast.LENGTH_SHORT).show()
        },
        onShareClick = {
            com.clashofmusic.app.presentation.components.ShareHelper.shareSong(context, song)
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun StatCard(
    title: String,
    count: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = CardShape12,
        color = DarkBackground,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(count, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
            Text(title, style = MaterialTheme.typography.labelMedium, color = TextSecondaryDark)
        }
    }
}

@Composable
private fun TrendingSongCard(
    songTitle: String,
    artist: String,
    thumbnailUrl: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(thumbnailUrl) {
        if (!thumbnailUrl.isNullOrEmpty()) {
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(250, 250)
                .crossfade(false)
                .build()
        } else null
    }

    Surface(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = CardShape12,
        color = DarkBackground,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .clip(ThumbShape8)
                    .background(DarkSurfaceVariant)
            ) {
                if (imageRequest != null) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = songTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center),
                        tint = AccentBerry
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = songTitle,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GenreCard(
    genre: GenreItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(135.dp)
            .height(88.dp)
            .clickable(onClick = onClick),
        shape = CardShape14,
        color = DarkSurfaceVariant,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkSurface)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = TextPrimaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "${genre.count}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                Column {
                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (genre.count == 1) "1 canción" else "${genre.count} canciones",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryDark,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
