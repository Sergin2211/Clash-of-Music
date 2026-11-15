package com.clashofmusic.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clashofmusic.app.presentation.theme.*

private val SongCardShape = RoundedCornerShape(12.dp)
private val SongThumbnailShape = RoundedCornerShape(8.dp)

private val SelectedBorder get() = BorderStroke(1.5.dp, AccentBerry)
private val CurrentSongOverlayColor get() = AccentBerry.copy(alpha = 0.15f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    durationSeconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    isPlayingNow: Boolean = false,
    isCurrentSong: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onEditMetadataClick: (() -> Unit)? = null,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    deleteLabel: String? = null,
    onSetAsClick: (() -> Unit)? = null,
    longPressEnabled: Boolean = true,
    artworkEmphasis: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val hasMenuButton = onPlayNextClick != null || onAddToQueueClick != null || onShareClick != null || onAddToPlaylistClick != null || onEditMetadataClick != null || onRemoveFromPlaylistClick != null || onDeleteClick != null
    val hasMenuOptions = hasMenuButton || onSetAsClick != null

    val cardColor = when {
        isSelected -> DarkSurfaceVariant
        isCurrentSong -> CurrentSongCardColor
        else -> DarkBackground
    }

    val cardBorder = when {
        isSelected -> SelectedBorder
        isCurrentSong -> CurrentSongBorder
        else -> null
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!longPressEnabled) null else ({
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isSelectionMode) {
                        onLongClick?.invoke()
                    } else if (hasMenuOptions) {
                        showMenu = true
                    } else {
                        onLongClick?.invoke()
                    }
                })
            ),
        color = cardColor,
        border = cardBorder,
        shape = SongCardShape,
        tonalElevation = 0.dp
    ) {
        Column {
            if (artworkEmphasis && !thumbnailUrl.isNullOrEmpty()) {
                val context = LocalContext.current
                val featuredArtwork = remember(thumbnailUrl) {
                    ImageRequest.Builder(context).data(thumbnailUrl)
                        .size(840, 460).crossfade(false).build()
                }
                AsyncImage(
                    model = featuredArtwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(168.dp).clip(CardShape16),
                    alignment = Alignment.TopCenter,
                    contentScale = ContentScale.Crop
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Multi-selection checkbox
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentBerry,
                            uncheckedColor = TextMutedDark,
                            checkmarkColor = TextPrimaryDark
                        ),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                if (!artworkEmphasis || thumbnailUrl.isNullOrEmpty()) {
                    // Thumbnail
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(SongThumbnailShape)
                            .background(DarkSurfaceVariant)
                    ) {
                        if (!thumbnailUrl.isNullOrEmpty()) {
                            val context = LocalContext.current
                            val imageRequest = remember(thumbnailUrl) {
                                ImageRequest.Builder(context)
                                    .data(thumbnailUrl)
                                    .size(140, 140)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.Center),
                                tint = AccentBerry
                            )
                        }

                        // If currently playing, show subtle highlight overlay on thumbnail
                        if (isCurrentSong && !isSelectionMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(CurrentSongOverlayColor)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                }
                // Title & Subtitle (single Text: 0 nested weights, single layout pass)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    val subtitle = remember(artist, durationSeconds) {
                        if (durationSeconds > 0) "$artist  /  ${formatDuration(durationSeconds)}" else artist
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Playback / Download status icon (only when not in selection mode)
                if (!isSelectionMode) {
                    if (isCurrentSong || isDownloading || isDownloaded || onDownloadClick != null) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (isCurrentSong) {
                                // Show equalizer animation representing active playback
                                PlayingEqualizerBars(
                                    modifier = Modifier
                                        .height(18.dp),
                                    color = TextPrimaryDark,
                                    isPlaying = isPlayingNow
                                )
                            } else if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .semantics { contentDescription = "Descargando" },
                                    strokeWidth = 2.dp,
                                    color = AccentBerry
                                )
                            } else if (isDownloaded) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Descargada",
                                    tint = AccentMint,
                                    modifier = Modifier.size(22.dp)
                                )
                            } else if (onDownloadClick != null) {
                                IconButton(onClick = onDownloadClick) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "Descargar",
                                        tint = AccentBerry,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Options menu (three dots) - only evaluated/composed when open
                    if (hasMenuOptions) {
                        Box {
                            if (hasMenuButton) IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Opciones",
                                    tint = TextMutedDark,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            if (showMenu) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(DarkSurfaceVariant)
                                ) {
                                    if (onPlayNextClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Reproducir a continuación", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.QueueMusic,
                                                    contentDescription = null,
                                                    tint = AccentBerry
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onPlayNextClick()
                                            }
                                        )
                                    }

                                    if (onAddToQueueClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Añadir a la cola", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.PlaylistAdd,
                                                    contentDescription = null,
                                                    tint = TextPrimaryDark
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onAddToQueueClick()
                                            }
                                        )
                                    }

                                    if (onSetAsClick != null) {
                                        DropdownMenuItem(text = { Text("Establecer como…", color = TextPrimaryDark) },
                                            leadingIcon = { Icon(Icons.Rounded.Alarm, contentDescription = null, tint = AccentBerry) },
                                            onClick = { showMenu = false; onSetAsClick() })
                                    }
                                    if (onShareClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Compartir canción", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Share,
                                                    contentDescription = null,
                                                    tint = TextSecondaryDark
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onShareClick()
                                            }
                                        )
                                    }

                                    if (onAddToPlaylistClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Añadir a playlist", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.PlaylistAdd,
                                                    contentDescription = null,
                                                    tint = AccentBerry
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onAddToPlaylistClick()
                                            }
                                        )
                                    }

                                    if (onEditMetadataClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Editar etiquetas", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Edit,
                                                    contentDescription = null,
                                                    tint = TextSecondaryDark
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onEditMetadataClick()
                                            }
                                        )
                                    }

                                    if (onRemoveFromPlaylistClick != null) {
                                        DropdownMenuItem(
                                            text = { Text("Quitar de la playlist", color = TextPrimaryDark) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.RemoveCircleOutline,
                                                    contentDescription = null,
                                                    tint = TextPrimaryDark
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onRemoveFromPlaylistClick()
                                            }
                                        )
                                    }

                                    if (onDeleteClick != null) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = deleteLabel ?: if (onRemoveFromPlaylistClick != null) "Eliminar del dispositivo" else "Eliminar canción",
                                                    color = AccentRed
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Rounded.Delete,
                                                    contentDescription = null,
                                                    tint = AccentRed
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                onDeleteClick()
                                            }
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
}

private fun formatDuration(durationSeconds: Long): String {
    val totalSeconds = durationSeconds.coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
