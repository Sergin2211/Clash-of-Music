package com.clashofmusic.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.clashofmusic.app.data.player.MusicPlayerManager
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.presentation.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayerBottomBar(
    modifier: Modifier = Modifier,
    playerManager: MusicPlayerManager = ServiceLocator.playerManager
) {
    val currentSong by playerManager.currentSong.collectAsStateWithLifecycle()
    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()
    val isFavorite by playerManager.isFavorite.collectAsStateWithLifecycle()

    var showFullPlayer by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = currentSong != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        currentSong?.let { song ->
            key(song.id) {
                SwipeAwayBox(
                    onDismiss = { playerManager.stop() },
                    dismissLabel = "Dejar de reproducir"
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable { showFullPlayer = true },
                        shape = RoundedCornerShape(16.dp),
                        color = DarkSurfaceVariant,
                        border = SubtleCardBorder,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Leaf-isolated thin progress bar on top
                            MiniPlayerProgressIndicator(playerManager = playerManager)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(DarkSurfaceVariant)
                                ) {
                                    if (!song.thumbnailUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = song.thumbnailUrl,
                                            contentDescription = song.title,
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
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Title & Artist
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondaryDark,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Favorite heart button
                                IconButton(onClick = { playerManager.toggleFavoriteCurrent() }) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = "Favorito",
                                        tint = if (isFavorite) AccentBerry else TextMutedDark,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                // Play/Pause button
                                IconButton(
                                    onClick = { playerManager.togglePlayPause() },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                        tint = TextPrimaryDark,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showFullPlayer) {
                FullPlayerSheet(
                    playerManager = playerManager,
                    song = song,
                    onDismiss = { showFullPlayer = false }
                )
            }
        }
    }
}

/**
 * Isolated leaf progress bar: only this recomposes every 500ms
 */
@Composable
private fun MiniPlayerProgressIndicator(playerManager: MusicPlayerManager) {
    val positionMs by playerManager.positionMs.collectAsStateWithLifecycle()
    val durationMs by playerManager.durationMs.collectAsStateWithLifecycle()

    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp),
        color = TextPrimaryDark,
        trackColor = DarkCardBorder
    )
}
