package com.clashofmusic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import com.clashofmusic.app.domain.model.ThemeMode
import com.clashofmusic.app.presentation.theme.CherryClashColors
import com.clashofmusic.app.presentation.theme.ElixirClashColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.AppSettings
import com.clashofmusic.app.presentation.navigation.AppNavigation
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_DOWNLOADS = "open_downloads" }
    private var openDownloads by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDownloads = intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        syncLibraryIfReadable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDownloads = intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)

        val initialSettings = ServiceLocator.preferencesRepository.currentSettings()
        val colors = if (initialSettings.themeMode == ThemeMode.CHERRY) CherryClashColors else ElixirClashColors
        window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))

        checkAndRequestPermissions()

        setContent {
            val settings by ServiceLocator.preferencesRepository.observeSettings()
                .collectAsStateWithLifecycle(initialValue = initialSettings)

            ClashOfMusicTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(openDownloads = openDownloads, onDownloadsOpened = {
                        openDownloads = false
                        intent.removeExtra(EXTRA_OPEN_DOWNLOADS)
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncLibraryIfReadable()
    }

    private fun syncLibraryIfReadable() {
        if (!ServiceLocator.mediaStoreHelper.canReadLibrary()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ServiceLocator.libraryRepository.syncStorage()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* Keep existing data until permissions/storage are available. */ }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Storage permissions request
        val storagePermissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            else -> emptyArray()
        }

        val neededPermissions = storagePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestStoragePermissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }
}
