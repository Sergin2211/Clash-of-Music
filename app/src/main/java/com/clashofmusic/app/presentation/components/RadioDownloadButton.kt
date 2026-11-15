package com.clashofmusic.app.presentation.components

import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashofmusic.app.data.player.RadioLibrarySaver
import com.clashofmusic.app.data.player.RadioSaveState
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong

@Composable
fun RadioDownloadButton(song: DownloadedSong, saver: RadioLibrarySaver = ServiceLocator.radioLibrarySaver) {
    val flow = remember(song.songId, saver) { saver.observe(song.songId) }
    val state by flow.collectAsStateWithLifecycle(initialValue = RadioSaveState())
    OutlinedButton(onClick = { saver.save(song) }, enabled = !state.saved && !state.busy, modifier = Modifier.fillMaxWidth()) {
        Icon(if (state.saved) Icons.Rounded.Check else Icons.Rounded.Download, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp)); Text(state.label)
    }
    if (!state.saved && !state.busy) state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
