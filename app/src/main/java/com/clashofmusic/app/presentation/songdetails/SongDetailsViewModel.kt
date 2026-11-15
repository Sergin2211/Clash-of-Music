package com.clashofmusic.app.presentation.songdetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.clashofmusic.app.data.player.toRadioSong

data class SongDetailsUiState(
    val songDetails: SongDetails? = null,
    val isLoading: Boolean = true,
    val isPreviewLoading: Boolean = false,
    val isPreviewPlaying: Boolean = false,
    val errorMessage: String? = null,
    val selectedFormat: AudioFormat = AudioFormat.MP3,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadEligibility: DownloadEligibility = DownloadEligibility.CAN_DOWNLOAD,
    val isFavorite: Boolean = false,
    val snackbarMessage: String? = null
)

class SongDetailsViewModel : ViewModel() {

    private val musicRepository = ServiceLocator.musicRepository
    private val downloadRepository = ServiceLocator.downloadRepository
    private val libraryRepository = ServiceLocator.libraryRepository
    private val favoritesRepository = ServiceLocator.favoritesRepository
    private val preferencesRepository = ServiceLocator.preferencesRepository
    val playerManager = ServiceLocator.playerManager

    private val _uiState = MutableStateFlow(SongDetailsUiState())
    val uiState: StateFlow<SongDetailsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(playerManager.isPlaying, playerManager.currentSong) { isPlaying, currentSong ->
                val details = _uiState.value.songDetails
                val isOurPreview = details != null && (currentSong?.id == "preview_${details.id}" || currentSong?.songId == details.id)
                isOurPreview && isPlaying
            }.collect { isPlaying ->
                _uiState.update { it.copy(isPreviewPlaying = isPlaying) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val settings = preferencesRepository.observeSettings().first()
            _uiState.update {
                it.copy(
                    selectedFormat = settings.defaultFormat,
                )
            }
        }
    }

    fun loadDetails(songId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = musicRepository.getSongDetails(songId)
            result.fold(
                onSuccess = { details ->
                    _uiState.update { it.copy(songDetails = details, isLoading = false) }
                    checkEligibility(details.id)
                    observeFavorite(details.id)
                    observeDownloading(details.id)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun onFormatSelected(format: AudioFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
        _uiState.value.songDetails?.let { checkEligibility(it.id) }
    }

    private fun checkEligibility(songId: String) {
        viewModelScope.launch {
            val isDown = libraryRepository.isSongDownloaded(songId)
            val eligibility = libraryRepository.checkDownloadEligibility(
                songId = songId,
                format = _uiState.value.selectedFormat,
                quality = AudioQuality.BEST_320K
            )
            _uiState.update {
                it.copy(
                    isDownloaded = isDown,
                    downloadEligibility = eligibility
                )
            }
        }
    }

    private fun observeFavorite(songId: String) {
        viewModelScope.launch {
            favoritesRepository.isFavorite(songId).collect { isFav ->
                _uiState.update { it.copy(isFavorite = isFav) }
            }
        }
    }

    private fun observeDownloading(songId: String) {
        viewModelScope.launch {
            downloadRepository.observeTasks().map { tasks -> tasks.any { it.songId == songId && it.isActive } }
                .distinctUntilChanged().flowOn(Dispatchers.Default).collect { downloading ->
                _uiState.update { it.copy(isDownloading = downloading) }
            }
        }
    }

    fun toggleFavorite() {
        val details = _uiState.value.songDetails ?: return
        viewModelScope.launch {
            val item = SongItem(
                id = details.id,
                title = details.title,
                artist = details.artist,
                thumbnailUrl = details.thumbnailUrl,
                durationSeconds = details.durationSeconds,
                url = details.url
            )
            favoritesRepository.toggleFavorite(item)
        }
    }


    fun startDownload(context: android.content.Context? = null) {
        val details = _uiState.value.songDetails ?: return
        val currentEligibility = _uiState.value.downloadEligibility

        if (currentEligibility == DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY) {
            _uiState.update { it.copy(snackbarMessage = "Esta canción ya está descargada en este formato.") }
            return
        }

        _uiState.update { it.copy(isDownloading = true) }
        viewModelScope.launch {
            val task = DownloadTask(
                id = details.id,
                songId = details.id,
                title = details.title,
                artist = details.artist,
                album = details.album,
                thumbnailUrl = details.thumbnailUrl,
                url = details.url,
                format = _uiState.value.selectedFormat,
                quality = AudioQuality.BEST_320K
            )
            downloadRepository.enqueueDownload(task)

            val msg = if (currentEligibility == DownloadEligibility.CAN_OVERWRITE_QUALITY) {
                "Cambiando de formato: descarga iniciada..."
            } else {
                "Descarga iniciada con sus datos y carátula..."
            }
            _uiState.update { it.copy(snackbarMessage = msg) }
        }
    }

    fun playRadio() {
        val details = _uiState.value.songDetails ?: return
        if (playerManager.radioState.value.active && playerManager.currentSong.value?.songId == details.id) {
            playerManager.togglePlayPause()
            return
        }
        playerManager.startRadio(run {
            // Metadata only: this never enters the download queue or the permanent library.
            SongItem(details.id, details.title, details.artist, details.thumbnailUrl, details.durationSeconds, details.url).toRadioSong()
        })
        _uiState.update { it.copy(snackbarMessage = "Radio iniciada. El audio es temporal y usa Internet.") }
    }

    fun dismissSnackbar(expected: String? = _uiState.value.snackbarMessage) {
        _uiState.update { if (it.snackbarMessage == expected) it.copy(snackbarMessage = null) else it }
    }
}
