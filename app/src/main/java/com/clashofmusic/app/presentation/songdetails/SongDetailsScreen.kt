package com.clashofmusic.app.presentation.songdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.clashofmusic.app.presentation.components.DismissibleSnackbarHost
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.DownloadEligibility
import com.clashofmusic.app.presentation.components.ErrorStateView
import com.clashofmusic.app.presentation.components.LoadingStateView
import com.clashofmusic.app.presentation.theme.*

@Composable
fun SongDetailsScreen(
    songId: String,
    onNavigateBack: () -> Unit,
    viewModel: SongDetailsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(songId) {
        viewModel.loadDetails(songId)
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            try { snackbarHostState.showSnackbar(it) }
            finally { viewModel.dismissSnackbar(it) }
        }
    }

    Scaffold(
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Bar
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

                IconButton(onClick = { viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (state.isFavorite) AccentBerry else TextPrimaryDark
                    )
                }
            }

            if (state.isLoading) {
                LoadingStateView(message = "Extrayendo información de YouTube...")
            } else if (state.errorMessage != null) {
                ErrorStateView(
                    message = state.errorMessage ?: "Error",
                    onRetry = { viewModel.loadDetails(songId) }
                )
            } else {
                val details = state.songDetails
                if (details != null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 90.dp)
                    ) {
                        // Artwork & Basic info
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(DarkSurfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!details.thumbnailUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = details.thumbnailUrl,
                                            contentDescription = details.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = AccentBerry
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                Text(
                                    text = details.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${details.artist}  /  ${details.channelName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondaryDark
                                )

                                if (details.durationSeconds > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Duración: ${formatSeconds(details.durationSeconds)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimaryDark
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // Audio Format Selection
                        item {
                            Text(
                                text = "Formato de Audio",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AudioFormat.entries.forEach { format ->
                                    val selected = state.selectedFormat == format
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.onFormatSelected(format) },
                                        label = { Text(format.extension.uppercase(), fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AccentBerry,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                            containerColor = DarkSurface,
                                            labelColor = TextPrimaryDark
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = if (selected) AccentBerry else DarkCardBorder,
                                            enabled = true,
                                            selected = selected
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            Text(
                                text = if (state.selectedFormat == AudioFormat.ORIGINAL)
                                    "Audio original sin conversión." else "Conversión automática de alta calidad. No mejora la calidad de la fuente.",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondaryDark
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // Eligibility Notification Banner
                        item {
                            when (state.downloadEligibility) {
                                DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY -> {
                                    Surface(
                                        color = AccentMint.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AccentMint)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Canción ya descargada en este formato en tu Biblioteca.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextPrimaryDark
                                            )
                                        }
                                    }
                                }
                                DownloadEligibility.CAN_OVERWRITE_QUALITY -> {
                                    Surface(
                                        color = TextPrimaryDark.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Upgrade, contentDescription = null, tint = TextPrimaryDark)
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Al terminar, la descarga elegida reemplazará el archivo actual.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextPrimaryDark
                                            )
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }

                        // Action Buttons: Descargar & Escuchar
                        item {
                            Button(
                                onClick = { viewModel.startDownload(context) },
                                enabled = !state.isDownloading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
                            ) {
                                if (state.isDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = TextPrimaryDark
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Descargando Audio...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark
                                    )
                                } else {
                                    Icon(Icons.Rounded.Download, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (state.downloadEligibility == DownloadEligibility.CAN_OVERWRITE_QUALITY) "Reemplazar y descargar" else "Descargar Audio Ahora",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = { viewModel.playRadio() },
                                enabled = !state.isPreviewLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimaryDark)
                            ) {
                                if (state.isPreviewLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = AccentBerry
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Preparando radio…", fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(
                                        imageVector = if (state.isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = AccentBerry
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (state.isPreviewPlaying) "Pausar radio" else "Escuchar  /  modo radio",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}
