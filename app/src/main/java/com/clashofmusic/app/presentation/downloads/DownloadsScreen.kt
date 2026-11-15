package com.clashofmusic.app.presentation.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadTask
import com.clashofmusic.app.domain.model.StorageSpaceInfo
import com.clashofmusic.app.presentation.components.EmptyStateView
import com.clashofmusic.app.presentation.components.SectionHeader
import com.clashofmusic.app.presentation.components.rememberToneAction
import com.clashofmusic.app.presentation.theme.*

@Composable
private fun rememberDownloadThumbnail(url: String?): ImageRequest {
    val context = LocalContext.current
    return remember(context, url) {
        // Match SongCard's small artwork size so both screens reuse the decoded image.
        ImageRequest.Builder(context).data(url).size(140, 140).crossfade(false).build()
    }
}

@Composable
fun DownloadsScreen(
    onNavigateToSearch: () -> Unit,
    viewModel: DownloadsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val hasAnyTasks = state.activeTasks.isNotEmpty() || state.completedTasks.isNotEmpty() || state.failedTasks.isNotEmpty()

    state.cancellationError?.let { message ->
        AlertDialog(onDismissRequest = viewModel::dismissCancellationError,
            title = { Text("No se pudo cancelar") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissCancellationError) { Text("Aceptar") } })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Descargas",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Text(
                    text = "${state.activeTasks.size} activas  /  ${state.completedTasks.size} completadas",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondaryDark
                )
            }

            if (state.completedTasks.isNotEmpty() || state.failedTasks.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearCompleted() }) {
                    Text("Limpiar", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (state.isCancellingAll || state.activeTasks.any { it.status != DownloadStatus.SAVING }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = viewModel::cancelAllTasks, enabled = !state.isCancellingAll,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isCancellingAll) "Cancelando…" else "Cancelar todas")
                }
                if (state.activeTasks.any { it.status == DownloadStatus.SAVING })
                    Text("El archivo que ya se está guardando terminará de guardarse.",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark)
            }
        }

        // Storage Status Bar
        StorageStatusBar(
            storageSpace = state.storageSpace,
            onRefresh = { viewModel.refreshStorage() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasAnyTasks) {
            EmptyStateView(
                title = "Sin descargas activas",
                subtitle = "Busca canciones o pega enlaces para comenzar a descargar música en alta velocidad y sin límites.",
                icon = Icons.Rounded.DownloadDone,
                actionButtonText = "Ir al Buscador",
                onActionClick = onNavigateToSearch
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp)
            ) {
                // ACTIVE TASKS
                if (state.activeTasks.isNotEmpty()) {
                    item(key = "active_header", contentType = "header") {
                        SectionHeader(title = "Descargando (${state.activeTasks.size})")
                    }

                    items(state.activeTasks, key = { it.id }, contentType = { "active_download" }) { task ->
                        ActiveDownloadCard(
                            task = task,
                            onPause = { viewModel.pauseTask(task.id) },
                            onResume = { viewModel.resumeTask(task.id) },
                            onCancel = { viewModel.cancelTask(task.id) },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                // COMPLETED TASKS
                if (state.completedTasks.isNotEmpty()) {
                    item(key = "completed_header", contentType = "header") {
                        SectionHeader(title = "Completadas (${state.completedTasks.size})")
                    }

                    items(state.completedTasks, key = { it.id }, contentType = { "completed_download" }) { task ->
                        CompletedDownloadCard(
                            task = task,
                            onPlay = { viewModel.playCompletedSong(task) },
                            onDelete = { viewModel.deleteTask(task.id) },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                // FAILED OR CANCELLED TASKS
                if (state.failedTasks.isNotEmpty()) {
                    item(key = "failed_header", contentType = "header") {
                        SectionHeader(title = "Fallidas / Canceladas (${state.failedTasks.size})")
                    }

                    items(state.failedTasks, key = { it.id }, contentType = { "failed_download" }) { task ->
                        FailedDownloadCard(
                            task = task,
                            onRetry = { viewModel.retryTask(task.id) },
                            onDelete = { viewModel.deleteTask(task.id) },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageStatusBar(
    storageSpace: StorageSpaceInfo,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape14,
        color = DarkSurface,
        border = SubtleCardBorder,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = AccentBerry,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Almacenamiento del Dispositivo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Actualizar almacenamiento",
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val totalGb = storageSpace.totalBytes / (1024f * 1024f * 1024f)
            val freeGb = storageSpace.freeBytes / (1024f * 1024f * 1024f)
            val musicMb = storageSpace.musicBytes / (1024f * 1024f)
            val usedBytes = (storageSpace.totalBytes - storageSpace.freeBytes).coerceAtLeast(0L)
            val progressFraction = if (storageSpace.totalBytes > 0) {
                (usedBytes.toFloat() / storageSpace.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f

            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = TextPrimaryDark,
                trackColor = DarkSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format("Música CoM: %.1f MB", musicMb),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format("Libre: %.1f GB de %.1f GB", freeGb, totalGb),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryDark
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPaused = task.status == DownloadStatus.PAUSED

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DarkSurface,
        shape = CardShape14,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(ThumbShape8)
                        .background(DarkSurfaceVariant)
                ) {
                    if (!task.thumbnailUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = rememberDownloadThumbnail(task.thumbnailUrl),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center),
                            tint = AccentBerry
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${task.artist}  /  ${task.format.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Pause or Resume button
                if (isPaused) {
                    IconButton(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Reanudar",
                            tint = AccentMint
                        )
                    }
                } else {
                    IconButton(onClick = onPause, enabled = task.status != DownloadStatus.SAVING) {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = "Pausar",
                            tint = AccentBerry
                        )
                    }
                }

                // Cancel button
                IconButton(onClick = onCancel, enabled = task.status != DownloadStatus.SAVING) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancelar",
                        tint = AccentRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            val cleanProgress = (task.progress / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { cleanProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isPaused) TextMutedDark else AccentBerry,
                trackColor = DarkCardBorder
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPaused) "En pausa" else task.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPaused) TextMutedDark else TextPrimaryDark
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!task.downloadSpeed.isNullOrEmpty() && !isPaused) {
                        Text(
                            text = task.downloadSpeed,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentMint
                        )
                    }
                    if (task.etaSeconds > 0 && !isPaused) {
                        Text(
                            text = "  /  ETA: ${task.etaSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMutedDark
                        )
                    }
                    Text(
                        text = "  /  ${task.progress.toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun CompletedDownloadCard(
    task: DownloadTask,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val setTone = rememberToneAction(task.songId, true)
    var showToneMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { showToneMenu = true }),
        color = DarkSurface,
        shape = CardShape14,
        tonalElevation = 0.dp
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(ThumbShape8)
                        .background(DarkSurfaceVariant)
                ) {
                    if (!task.thumbnailUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = rememberDownloadThumbnail(task.thumbnailUrl),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center),
                            tint = AccentBerry
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = AccentMint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${task.artist}  /  ${task.format.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = AccentBerry
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Quitar de la lista",
                        tint = TextMutedDark
                    )
                }
            }
            if (showToneMenu) DropdownMenu(expanded = true, onDismissRequest = { showToneMenu = false }) {
                DropdownMenuItem(text = { Text("Establecer como…") }, onClick = { showToneMenu = false; setTone?.invoke() })
            }
        }
    }
}

@Composable
private fun FailedDownloadCard(
    task: DownloadTask,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DarkSurface,
        shape = CardShape14,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(ThumbShape8)
                        .background(DarkSurfaceVariant)
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                        tint = AccentRed
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.errorMessage ?: "Error en la descarga",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reintentar",
                        tint = AccentBerry
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Eliminar de la lista",
                        tint = TextMutedDark
                    )
                }
            }
        }
    }
}
