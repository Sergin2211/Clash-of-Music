package com.clashofmusic.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.AppSettings
import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isSyncing: Boolean = false,
    val isTransferring: Boolean = false,
    val importPreview: com.clashofmusic.app.data.local.VaultData? = null,
    val feedbackMessage: String? = null
)

class SettingsViewModel(
    private val preferencesRepository: com.clashofmusic.app.domain.repository.PreferencesRepository = ServiceLocator.preferencesRepository
) : ViewModel() {
    private val libraryRepository = ServiceLocator.libraryRepository
    private val mediaStoreHelper = ServiceLocator.mediaStoreHelper
    private val clashVaultHelper = ServiceLocator.clashVaultHelper

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.observeSettings().collect { s ->
                _uiState.update { it.copy(settings = s) }
            }
        }
    }

    fun onDefaultFormatChanged(format: AudioFormat) {
        viewModelScope.launch {
            preferencesRepository.updateDefaultFormat(format)
            _uiState.update { it.copy(feedbackMessage = "Formato por defecto: ${format.displayName}") }
        }
    }



    fun onThemeModeChanged(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.updateThemeMode(mode)
            _uiState.update { it.copy(feedbackMessage = "Tema cambiado a ${mode.displayName}") }
        }
    }

    fun syncStorage() = runStorageSync()

    private fun runStorageSync() {
        if (_uiState.value.isSyncing || _uiState.value.isTransferring) return
        _uiState.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            try {
                val count = libraryRepository.syncStorage()
                _uiState.update {
                    it.copy(feedbackMessage = "Sincronización completada: $count cambios aplicados")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(feedbackMessage = e.message ?: "No se pudo sincronizar. Se conservaron tus datos.") }
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val cleaned = mediaStoreHelper.cleanTempDir()
            _uiState.update {
                it.copy(feedbackMessage = if (cleaned) "Caché de descargas temporales limpiada." else
                    "No se pudo limpiar la caché. Espera a que terminen las descargas y vuelve a intentar.")
            }
        }
    }

    private var preparedExport: String? = null

    fun prepareExport(onReady: () -> Unit) {
        if (_uiState.value.isTransferring || _uiState.value.isSyncing) return
        _uiState.update { it.copy(isTransferring = true) }
        viewModelScope.launch {
            try {
                preparedExport = clashVaultHelper.prepareExport()
                onReady()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                preparedExport = null
                _uiState.update { it.copy(isTransferring = false, feedbackMessage = e.message ?: "No se pudo preparar el respaldo") }
            }
        }
    }

    fun saveExport(uri: android.net.Uri?) {
        val json = preparedExport
        preparedExport = null
        if (uri == null || json == null) {
            _uiState.update { it.copy(isTransferring = false,
                feedbackMessage = if (uri != null) "La exportación se interrumpió. Vuelve a exportar el respaldo." else it.feedbackMessage) }
            return
        }
        viewModelScope.launch {
            try {
                clashVaultHelper.writeExport(uri, json)
                _uiState.update { it.copy(feedbackMessage = "Respaldo guardado y verificado en la ubicación elegida") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(feedbackMessage = "No se pudo completar o verificar el archivo: ${e.message}") }
            } finally { _uiState.update { it.copy(isTransferring = false) } }
        }
    }

    fun readImport(uri: android.net.Uri?) {
        if (uri == null || _uiState.value.isTransferring || _uiState.value.isSyncing) return
        _uiState.update { it.copy(isTransferring = true) }
        viewModelScope.launch {
            try {
                val data = clashVaultHelper.readImport(uri)
                _uiState.update { it.copy(importPreview = data) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(feedbackMessage = "No se pudo leer un respaldo válido: ${e.message}") }
            } finally { _uiState.update { it.copy(isTransferring = false) } }
        }
    }

    fun cancelImport() { _uiState.update { it.copy(importPreview = null) } }

    fun confirmImport() {
        val data = _uiState.value.importPreview ?: return
        if (_uiState.value.isTransferring || _uiState.value.isSyncing) return
        _uiState.update { it.copy(isTransferring = true, importPreview = null) }
        viewModelScope.launch {
            try {
                val result = clashVaultHelper.applyImport(data)
                _uiState.update { it.copy(feedbackMessage =
                    "Importación completada: ${result.songsAdded} canciones, ${result.playlistsAdded} colecciones y ${result.linksAdded} vínculos añadidos. ${result.unavailableSongs} canciones sin archivo local identificable.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(feedbackMessage = "No se pudo importar: ${e.message}") }
            } finally { _uiState.update { it.copy(isTransferring = false) } }
        }
    }

    fun dismissFeedback(expected: String? = _uiState.value.feedbackMessage) {
        _uiState.update { if (it.feedbackMessage == expected) it.copy(feedbackMessage = null) else it }
    }
}
