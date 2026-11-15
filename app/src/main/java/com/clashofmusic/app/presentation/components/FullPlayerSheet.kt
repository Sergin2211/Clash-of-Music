package com.clashofmusic.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.request.ImageRequest
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.clashofmusic.app.data.player.MusicPlayerManager
import com.clashofmusic.app.data.player.SleepTimerMode
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    playerManager: MusicPlayerManager,
    song: DownloadedSong,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val artworkSize = (LocalConfiguration.current.screenHeightDp * 0.30f).dp.coerceIn(160.dp, 300.dp)
    val artworkPixels = with(LocalDensity.current) { artworkSize.roundToPx() }
    val artworkRequest = remember(context, song.thumbnailUrl, artworkPixels) {
        ImageRequest.Builder(context).data(song.thumbnailUrl).size(artworkPixels).allowHardware(false).crossfade(false).build()
    }
    var artworkBitmap by remember(song.thumbnailUrl) { mutableStateOf<Bitmap?>(null) }
    val artworkTint by rememberArtworkTint(song.thumbnailUrl, artworkBitmap)
    val baseBackground = DarkBackgroundSecondary
    val artworkTop = remember(artworkTint, baseBackground) {
        artworkTint?.let { Color(ArtworkColorMath.backdrop(it, baseBackground.toArgb())) } ?: baseBackground
    }
    val artworkGradient = remember(artworkTop, baseBackground) {
        Brush.verticalGradient(0f to artworkTop, .32f to artworkTop, .86f to baseBackground, 1f to baseBackground)
    }
    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()
    val isFavorite by playerManager.isFavorite.collectAsStateWithLifecycle()
    val queue by playerManager.queueState.collectAsStateWithLifecycle()
    val isShuffle = queue.shuffleEnabled
    val repeatMode by playerManager.repeatMode.collectAsStateWithLifecycle()
    val upcoming = remember(queue) { queue.upcoming }
    val userQueue = remember(upcoming) { upcoming.filter { it.isQueued } }
    val upcomingTracks = remember(upcoming) { upcoming.filterNot { it.isQueued } }
    val upcomingKeys = remember(upcoming) { upcoming.map { it.id }.toSet() }
    val manualKeys = remember(userQueue) { userQueue.map { it.id }.toSet() }
    val entryPositions = remember(queue.entries) { queue.entries.withIndex().associate { it.value.id to it.index } }
    val sleepTimerMode by playerManager.sleepTimerMode.collectAsStateWithLifecycle()
    val radio by playerManager.radioState.collectAsStateWithLifecycle()

    var showSleepTimerDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val onQueueTrackSelected: (String) -> Unit = { entryId ->
        playerManager.playQueueEntry(entryId)
        scope.launch { listState.animateScrollToItem(0) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = baseBackground,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().background(artworkTop), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle(color = TextPrimaryDark.copy(alpha = 0.7f))
            }
        }
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.96f).background(artworkGradient)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().queueTrackTaps(listState, upcomingKeys, manualKeys, endContentPadding = 24.dp, onClick = onQueueTrackSelected),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "player-controls", contentType = "controls") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Header bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Cerrar",
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Reproduciendo ahora",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { contentDescription = "REPRODUCIENDO AHORA" },
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                ShareHelper.shareSong(context, song)
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "Compartir canción",
                                    tint = TextSecondaryDark
                                )
                            }

                            IconButton(onClick = { playerManager.toggleFavoriteCurrent() }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorito",
                                    tint = if (isFavorite) AccentBerry else TextSecondaryDark
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Large Album Cover Art with shadow and rounded corners (20.dp)
                    Box(
                        modifier = Modifier
                            .widthIn(max = artworkSize).fillMaxWidth().aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!song.thumbnailUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = artworkRequest,
                                onSuccess = { artworkBitmap = (it.result.drawable as? BitmapDrawable)?.bitmap },
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp),
                                tint = AccentBerry.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Song Info (Title & Artist)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Audio Quality pill badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            border = SubtleCardBorder
                        ) {
                            Text(
                                text = song.format,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Isolated Progress Slider (Prevents full-sheet recompositions!)
                    IsolatedPlayerSlider(playerManager = playerManager)

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary Playback Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle button
                        IconButton(onClick = { playerManager.toggleShuffle() }) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = "Aleatorio",
                                tint = if (isShuffle) AccentBerry else TextMutedDark
                            )
                        }

                        // Previous (Smart: > 3s restarts, <= 3s goes to previous in stack)
                        IconButton(
                            onClick = { playerManager.playPrevious() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Anterior",
                                tint = TextPrimaryDark,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Play / Pause Floating Action Button
                        FilledIconButton(
                            onClick = { playerManager.togglePlayPause() },
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = AccentBerry,
                                contentColor = TextPrimaryDark
                            )
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Next
                        IconButton(
                            onClick = { playerManager.playNext() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Siguiente",
                                tint = TextPrimaryDark,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Repeat Mode Toggle
                        IconButton(onClick = { playerManager.toggleRepeatMode() }) {
                            val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = "Repetir",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) AccentBerry else TextMutedDark
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { if (radio.active) playerManager.stop() else playerManager.startRadioFromCurrent() },
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Radio, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (radio.active) "Terminar radio" else "Iniciar radio")
                        }
                        PlayerSleepTimerButton(playerManager, { showSleepTimerDialog = true }, Modifier.weight(1f))
                    }
                    if (radio.active) {
                        key(song.songId) { RadioDownloadButton(song) }
                        RadioCacheStatus(playerManager)
                    }
                    Spacer(modifier = Modifier.height(28.dp))

                }
            }
            item(key = "queue-heading", contentType = "heading") {
                Column {
                    // Integrated Playback Queue (Fila de reproducción integrada)
                    HorizontalDivider(
                        color = DarkCardBorder.copy(alpha = 0.5f),
                        thickness = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    val totalQueueCount = upcoming.size

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = AccentBerry,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Fila de reproducción",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "($totalQueueCount)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }

                        if (userQueue.isNotEmpty()) {
                            TextButton(onClick = { playerManager.clearQueue() }) {
                                Text("Limpiar cola", color = TextPrimaryDark, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                }
            }
            if (userQueue.isNotEmpty()) {
                item(key = "manual-heading", contentType = "heading") {
                    Text("En tu cola (${userQueue.size})", style = MaterialTheme.typography.labelSmall,
                         fontWeight = FontWeight.Bold, color = TextPrimaryDark,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                }
                items(userQueue, key = { it.id }, contentType = { "manual-track" }) { qEntry ->
                    val qSong = qEntry.song
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onQueueTrackSelected(qEntry.id)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, AccentBerry.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = qSong.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = qSong.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = qSong.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentBerry.copy(alpha = 0.2f),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = "En cola",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimaryDark,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            IconButton(
                                onClick = { playerManager.removeFromQueue(qEntry.id) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Quitar de cola",
                                    tint = TextMutedDark,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (upcomingTracks.isNotEmpty()) {
                item(key = "upcoming-heading", contentType = "heading") {
                    Text("Siguientes (${upcomingTracks.size})", style = MaterialTheme.typography.labelSmall,
                         fontWeight = FontWeight.Bold, color = TextMutedDark,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                }
                items(upcomingTracks, key = { it.id }, contentType = { "list-track" }) { entry ->
                    val item = entry.song
                    val originalIndex = entryPositions.getValue(entry.id)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onQueueTrackSelected(entry.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = DarkBackgroundSecondary
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${originalIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMutedDark,
                                modifier = Modifier.width(22.dp)
                            )
                            AsyncImage(
                                model = item.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (item.durationSeconds > 0) {
                                Text(
                                    text = formatDuration(item.durationSeconds * 1000L),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMutedDark,
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                                )
                            }
                        }
                    }
                }
            } else if (userQueue.isEmpty()) {
                item(key = "queue-end", contentType = "empty") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AllInclusive,
                                contentDescription = null,
                                tint = AccentBerry,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Fin de la lista actual",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "La reproducción automática continuará con canciones de tu biblioteca.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }                }
            }
        }
        }
    }

    // Sleep Timer Dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            containerColor = DarkSurface,
            title = {
                Text(
                    text = "Temporizador de Apagado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "La música se detendrá automáticamente cuando se agote el tiempo seleccionado.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    SleepTimerMode.entries.forEach { mode ->
                        val isSelected = sleepTimerMode == mode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    playerManager.setSleepTimer(mode)
                                    showSleepTimerDialog = false
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) DarkSurfaceVariant else Color.Transparent,
                            border = if (isSelected) CurrentSongBorder else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = TextPrimaryDark
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = AccentBerry,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Cerrar", color = TextPrimaryDark)
                }
            }
        )
    }
}

/**
 * Isolated leaf composable: only this recomposes on playback progress ticks
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IsolatedPlayerSlider(playerManager: MusicPlayerManager) {
    val positionMs by playerManager.positionMs.collectAsStateWithLifecycle()
    val durationMs by playerManager.durationMs.collectAsStateWithLifecycle()

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L).toFloat()
    val sliderValue = if (isDragging) dragPosition else (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = {
                isDragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                val targetMs = (dragPosition * safeDuration).toLong()
                playerManager.seekTo(targetMs)
                isDragging = false
            },
            thumb = {
                Spacer(Modifier.size(12.dp))
            },
            track = { _ ->
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val y = size.height / 2f
                    drawLine(Color.White.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    if (sliderValue > 0f) drawLine(Color.White, Offset(0f, y),
                        Offset(size.width * sliderValue, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(size.width * sliderValue, y))
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = .28f)
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentMsToFormat = if (isDragging) (dragPosition * safeDuration).toLong() else positionMs
            Text(
                text = formatDuration(currentMsToFormat),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondaryDark
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
private fun PlayerSleepTimerButton(playerManager: MusicPlayerManager, onClick: () -> Unit, modifier: Modifier) {
    val mode by playerManager.sleepTimerMode.collectAsStateWithLifecycle()
    val remaining by playerManager.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
    val label = when {
        mode == SleepTimerMode.END_OF_TRACK -> "Fin de pista"
        remaining > 0 -> String.format(java.util.Locale.ROOT, "%02d:%02d", remaining / 60, remaining % 60)
        else -> "Temporizador"
    }
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark),
        border = if (mode != SleepTimerMode.OFF) CurrentSongBorder else SubtleCardBorder) {
        Icon(Icons.Rounded.Timer, contentDescription = "Temporizador", modifier = Modifier.size(18.dp),
            tint = if (mode != SleepTimerMode.OFF) AccentBerry else TextPrimaryDark)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RadioCacheStatus(playerManager: MusicPlayerManager) {
    val radio by playerManager.radioState.collectAsStateWithLifecycle()
    val empty = remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }
    val bytes by (playerManager.radioCache?.bytes ?: empty).collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (radio.loading) "Buscando temas relacionados…" else "Radio temporal  /  ${bytes / (1024 * 1024)} / 96 MiB",
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
        IconButton(onClick = playerManager::retryRadio, enabled = !radio.loading) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Reintentar radio", tint = TextPrimaryDark)
        }
    }
    Text("Hasta ${com.clashofmusic.app.data.player.RadioWindow.BEHIND} anteriores y ${com.clashofmusic.app.data.player.RadioWindow.AHEAD} siguientes. Se limpia al terminar la radio.",
        style = MaterialTheme.typography.bodySmall, color = TextMutedDark)
    radio.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextPrimaryDark) }
}
