package com.clashofmusic.app.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clashofmusic.app.presentation.theme.*

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Nueva Playlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la playlist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim(), description.trim().ifBlank { null })
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
            ) {
                Text("Crear", color = TextPrimaryDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondaryDark)
            }
        }
    )
}

@Composable
fun EditMetadataDialog(
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String?,
    initialYear: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (title: String, artist: String, album: String?) -> Unit = { _, _, _ -> },
    onConfirmWithYear: ((title: String, artist: String, album: String?, year: String?) -> Unit)? = null
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var album by remember { mutableStateOf(initialAlbum.orEmpty()) }
    var year by remember { mutableStateOf(initialYear.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Editar Etiquetas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artista") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Álbum") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("Año (Opcional)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBerry,
                        unfocusedBorderColor = DarkCardBorder,
                        focusedLabelColor = AccentBerry,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val trimmedTitle = title.trim()
                        val trimmedArtist = artist.trim()
                        val trimmedAlbum = album.trim().ifBlank { null }
                        val trimmedYear = year.trim().ifBlank { null }
                        if (onConfirmWithYear != null) {
                            onConfirmWithYear(trimmedTitle, trimmedArtist, trimmedAlbum, trimmedYear)
                        } else {
                            onConfirm(trimmedTitle, trimmedArtist, trimmedAlbum)
                        }
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank() && artist.isNotBlank() && (year.isBlank() || year.trim().matches(Regex("[0-9]{4}"))),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBerry, contentColor = TextPrimaryDark)
            ) {
                Text("Guardar", color = TextPrimaryDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondaryDark)
            }
        }
    )
}
