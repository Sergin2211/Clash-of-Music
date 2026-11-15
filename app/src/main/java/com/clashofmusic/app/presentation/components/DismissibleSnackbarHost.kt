package com.clashofmusic.app.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** The swipe belongs only to the notice, never to the content behind it. */
@Composable
fun DismissibleSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        key(data) {
            SwipeAwayBox(onDismiss = data::dismiss, allowDown = true) {
                Snackbar(
                    dismissAction = {
                        IconButton(onClick = data::dismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cerrar aviso")
                        }
                    },
                    action = data.visuals.actionLabel?.let { label ->
                        { TextButton(onClick = data::performAction) { Text(label) } }
                    }
                ) { Text(data.visuals.message) }
            }
        }
    }
}
