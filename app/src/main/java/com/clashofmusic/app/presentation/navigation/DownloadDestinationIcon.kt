package com.clashofmusic.app.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.services.DownloadBadgeState
import com.clashofmusic.app.presentation.theme.AccentBerry
import com.clashofmusic.app.presentation.theme.TextPrimaryDark

@Composable
internal fun RowScope.DownloadNavigationItem(selected: Boolean, onClick: () -> Unit,
    monitor: com.clashofmusic.app.services.DownloadActivityMonitor = ServiceLocator.downloadActivity) {
    val state by monitor.badge.collectAsStateWithLifecycle()
    // Material clears icon semantics when its label is visible; status belongs on the item itself.
    NavigationBarItem(selected = selected, onClick = onClick,
        modifier = Modifier.semantics { stateDescription = downloadDescription(state) },
        icon = { DownloadDestinationIcon(Screen.Downloads.icon!!, state) },
        label = { Text("Descargas", fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AccentBerry else TextPrimaryDark) },
        colors = NavigationBarItemDefaults.colors(selectedIconColor = AccentBerry, selectedTextColor = AccentBerry,
            indicatorColor = Color.Transparent, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
}

private fun downloadDescription(state: DownloadBadgeState) = when {
    state.activeCount == 1 -> "1 descarga activa"
    state.activeCount > 1 -> "${state.activeCount} descargas activas"
    state.unseenCompletion -> "Descargas completadas sin revisar"
    else -> "Sin descargas activas"
}

@Composable
internal fun DownloadDestinationIcon(icon: ImageVector, state: DownloadBadgeState) {
    val description = downloadDescription(state)
    Box(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Icon(icon, contentDescription = null)
        if (state.activeCount > 0 || state.unseenCompletion) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-7).dp)
                .background(Color.White, CircleShape).heightIn(min = 18.dp).widthIn(min = 18.dp)
                .padding(horizontal = if (state.activeCount > 0) 5.dp else 2.dp), contentAlignment = Alignment.Center) {
                if (state.activeCount > 0) Text(state.activeCount.toString(), color = Color(0xff151216),
                    fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                else Icon(Icons.Rounded.Check, null, tint = Color(0xff151216), modifier = Modifier.size(14.dp))
            }
        }
    }
}
