package com.clashofmusic.app.presentation.settings

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.clashofmusic.app.BuildConfig
import com.clashofmusic.app.R
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.ThemeMode
import com.clashofmusic.app.presentation.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    DisposableEffect(viewModel) {
        // A message may arrive immediately before navigation, before its show effect starts.
        onDispose { viewModel.dismissFeedback() }
    }

    var showFormatDialog by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        viewModel.saveExport(it)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.readImport(it)
    }
    val transferEnabled = !state.isTransferring && !state.isSyncing

    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Importar respaldo ClashVault") },
            text = { Text("El respaldo contiene ${preview.songs.size} canciones y ${preview.playlists.size} colecciones. Se añadirán los datos que falten sin reemplazar tus canciones ni listas actuales.\n\nEl JSON no contiene audio ni imágenes: la música debe estar ya en el teléfono. Las canciones sin archivo identificable se omitirán.") },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("Importar") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport) { Text("Cancelar") } }
        )
    }

    LaunchedEffect(state.feedbackMessage) {
        state.feedbackMessage?.let {
            try { snackbarHostState.showSnackbar(it) }
            finally { viewModel.dismissFeedback(it) }
        }
    }

    Scaffold(
        snackbarHost = { DismissibleSnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Header
            item {
                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Theme & Appearance Section
            item {
                SettingsSectionTitle("Tema y Apariencia")

                ThemeDropdown(selected = state.settings.themeMode, onSelect = viewModel::onThemeModeChanged)

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Audio Preferences Section
            item {
                SettingsSectionTitle("Preferencias de Audio y Descarga")

                SettingsItem(
                    icon = Icons.Rounded.Audiotrack,
                    title = "Formato de audio por defecto",
                    subtitle = state.settings.defaultFormat.displayName,
                    onClick = { showFormatDialog = true }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Conversión automática de alta calidad; Original conserva el audio recibido.",
                    color = TextPrimaryDark, style = MaterialTheme.typography.bodySmall)


                Spacer(modifier = Modifier.height(20.dp))
            }

            // Storage & Maintenance Section
            item {
                SettingsSectionTitle("Almacenamiento y Biblioteca")

                SettingsItem(
                    icon = Icons.Rounded.Folder,
                    title = "Ubicación de almacenamiento",
                    subtitle = "Almacenamiento Compartido: ${state.settings.musicFolder}",
                    onClick = {}
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    icon = Icons.Rounded.Sync,
                    title = "Sincronizar almacenamiento ahora",
                    subtitle = if (state.isSyncing) "Sincronizando..." else "Escanea archivos físicos y elimina entradas fantasma",
                    enabled = transferEnabled,
                    onClick = { viewModel.syncStorage() }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    icon = Icons.Rounded.CleaningServices,
                    title = "Limpiar archivos temporales de descarga",
                    subtitle = "Libera espacio residual en caché",
                    onClick = { viewModel.clearCache() }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ClashVault Section
            item {
                SettingsSectionTitle("Copia de Seguridad ClashVault (Anti-Pérdida)")

                SettingsItem(
                    icon = Icons.Rounded.Backup,
                    title = "Exportar respaldo ClashVault",
                    subtitle = if (state.isTransferring) "Procesando respaldo..." else "Elige dónde guardar el catálogo JSON con metadatos y colecciones. No incluye audio ni imágenes.",
                    enabled = transferEnabled,
                    onClick = { viewModel.prepareExport { exportLauncher.launch("clashvault_backup.json") } }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    icon = Icons.Rounded.Restore,
                    title = "Importar respaldo ClashVault",
                    subtitle = "Elige un archivo JSON. Añade datos que falten y conserva la biblioteca actual.",
                    enabled = transferEnabled,
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }

            // App Identity & Info
            item {
                SettingsSectionTitle("Acerca de Clash of Music")

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CardShape16,
                    color = DarkSurface,
                    tonalElevation = 0.dp,
                    border = SubtleCardBorder
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(DarkSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = R.drawable.app_logo,
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Clash of Music",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Versión ${BuildConfig.VERSION_NAME}  /  100% Cliente (Backend-Free)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Desarrollada para Android 14 y 15 (Target SDK 35) con Jetpack Compose Material 3, arquitectura limpia, Media3 ExoPlayer y soporte total 100% offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryDark,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Licencias de código abierto: ExoPlayer (Media3), NewPipeExtractor, yt-dlp, Room, Coil.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimaryDark,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(90.dp))
            }
        }
    }

    // Format Dialog
    if (showFormatDialog) {
        AlertDialog(
            onDismissRequest = { showFormatDialog = false },
            containerColor = DarkSurface,
            title = { Text("Seleccionar Formato", color = TextPrimaryDark, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    AudioFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onDefaultFormatChanged(format)
                                    showFormatDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.settings.defaultFormat == format,
                                onClick = {
                                    viewModel.onDefaultFormatChanged(format)
                                    showFormatDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentBerry)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = format.displayName, color = TextPrimaryDark, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFormatDialog = false }) {
                    Text("Cerrar", color = TextPrimaryDark)
                }
            }
        )
    }


}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = AccentBerry,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = CardShape12,
        color = DarkBackground,
        tonalElevation = 0.dp,
        border = null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TextPrimaryDark,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimaryDark
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = TextPrimaryDark
            )
        }
    }
}

@Composable
private fun ThemeDropdown(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded },
            color = DarkSurfaceVariant, shape = RoundedCornerShape(16.dp), border = SubtleCardBorder
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = TextPrimaryDark)
                Column(Modifier.weight(1f)) {
                    Text("Tema de la aplicación", color = TextPrimaryDark, fontWeight = FontWeight.SemiBold)
                    Text("${selected.displayName} · ${selected.subtitle}", color = TextPrimaryDark, style = MaterialTheme.typography.bodySmall)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Contraer temas" else "Elegir tema", tint = TextPrimaryDark)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurfaceVariant)) {
            ThemeMode.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Column { Text(theme.displayName, color = TextPrimaryDark)
                        Text(theme.subtitle, color = TextPrimaryDark, style = MaterialTheme.typography.bodySmall) } },
                    leadingIcon = { Box(Modifier.size(14.dp).background(
                        if (theme == ThemeMode.CHERRY) CherryClashColors.primary else ElixirClashColors.primary, CircleShape)) },
                    trailingIcon = { if (selected == theme) Icon(Icons.Rounded.Check, "Tema seleccionado", tint = AccentBerry) },
                    onClick = { expanded = false; if (theme != selected) onSelect(theme) }
                )
            }
        }
    }
}
