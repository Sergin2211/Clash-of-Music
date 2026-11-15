package com.clashofmusic.app.presentation.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PlaylistDetailsUiState(
    val playlist: Playlist? = null,
    val songs: List<DownloadedSong> = emptyList(),
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val isSelectionMode: Boolean = false,
    val isReordering: Boolean = false,
    val isSavingOrder: Boolean = false,
    val errorMessage: String? = null,
    val selectedSongIds: Set<String> = emptySet()
)

class PlaylistDetailsViewModel(
    private val playlistRepository: com.clashofmusic.app.domain.repository.PlaylistRepository = ServiceLocator.playlistRepository
) : ViewModel() {
    private val libraryRepository = ServiceLocator.libraryRepository
    val playerManager = ServiceLocator.playerManager

    private val _uiState = MutableStateFlow(PlaylistDetailsUiState())
    val uiState: StateFlow<PlaylistDetailsUiState> = _uiState.asStateFlow()

    private var currentPlaylistId: String? = null
    private var repositorySongs: List<DownloadedSong> = emptyList()
    private var orderBeforeDrag: List<String> = emptyList()

    fun loadPlaylist(playlistId: String) {
        currentPlaylistId = playlistId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val playlist = playlistRepository.getPlaylistById(playlistId)
            _uiState.update { it.copy(playlist = playlist) }

            playlistRepository.getSongsForPlaylist(playlistId).collect { songs ->
                repositorySongs = songs
                _uiState.update { if (it.isReordering || it.isSavingOrder) it else it.copy(songs = songs, isLoading = false) }
            }
        }
    }

    fun setSelectionMode(enabled: Boolean) {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isSelectionMode = enabled, selectedSongIds = if (enabled) it.selectedSongIds else emptySet()) }
    }

    fun beginReorder() {
        val state = _uiState.value
        if (state.isSelectionMode || state.isSavingOrder || state.isDeleting) return
        orderBeforeDrag = state.songs.map { it.id }
        _uiState.update { it.copy(isReordering = true) }
    }

    fun moveSong(fromId: String, toId: String) {
        _uiState.update { state ->
            if (!state.isReordering) state else {
                val list = state.songs.toMutableList()
                val from = list.indexOfFirst { it.id == fromId }
                val to = list.indexOfFirst { it.id == toId }
                if (from < 0 || to < 0 || from == to) state
                else { list.add(to, list.removeAt(from)); state.copy(songs = list) }
            }
        }
    }

    fun finishReorder(cancelled: Boolean) {
        if (!_uiState.value.isReordering) return
        val pId = currentPlaylistId ?: return
        val ordered = _uiState.value.songs.map { it.id }
        if (cancelled || ordered == orderBeforeDrag) {
            _uiState.update { it.copy(isReordering = false, songs = repositorySongs) }
            return
        }
        _uiState.update { it.copy(isReordering = false, isSavingOrder = true) }
        viewModelScope.launch {
            try {
                // Only one transaction/backup per drop, never per crossed row.
                val latest = playlistRepository.getSongsForPlaylist(pId).first()
                val byId = latest.associateBy { it.id }
                val reconciled = ordered.filter { it in byId } + latest.map { it.id }.filter { it !in ordered }
                playlistRepository.reorderPlaylist(pId, reconciled)
                repositorySongs = playlistRepository.getSongsForPlaylist(pId).first()
                _uiState.update { it.copy(songs = repositorySongs, isSavingOrder = false) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                _uiState.update { it.copy(songs = repositorySongs, isSavingOrder = false,
                    errorMessage = "No se pudo guardar el orden. Inténtalo de nuevo.") }
            }
        }
    }

    fun toggleSongSelection(songId: String) {
        if (_uiState.value.isDeleting) return
        _uiState.update { current ->
            val newSelected = if (current.selectedSongIds.contains(songId)) {
                current.selectedSongIds - songId
            } else {
                current.selectedSongIds + songId
            }
            current.copy(selectedSongIds = newSelected)
        }
    }

    fun selectAll() {
        if (_uiState.value.isDeleting) return
        _uiState.update { current ->
            val allIds = current.songs.map { it.songId }.toSet()
            current.copy(selectedSongIds = allIds)
        }
    }

    fun clearSelection() {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(selectedSongIds = emptySet()) }
    }

    fun playAll() {
        val list = _uiState.value.songs
        if (list.isNotEmpty()) {
            playerManager.playSong(list.first(), list)
        }
    }

    fun playShuffle() {
        val list = _uiState.value.songs.shuffled()
        val first = list.firstOrNull() ?: return
        playerManager.playSong(first, list)
    }

    fun playSong(song: DownloadedSong) {
        val list = _uiState.value.songs
        playerManager.playSong(song, list)
    }

    fun removeSong(songId: String) {
        val pId = currentPlaylistId ?: return
        viewModelScope.launch {
            playlistRepository.removeSongFromPlaylist(pId, songId)
        }
    }

    fun deleteSongPermanently(song: DownloadedSong) {
        viewModelScope.launch {
            if (!libraryRepository.deleteSong(song)) showDeleteError()
        }
    }

    fun deleteSongPermanently(songId: String) {
        val song = _uiState.value.songs.firstOrNull { it.songId == songId || it.id == songId }
        viewModelScope.launch {
            val deleted = if (song != null) {
                libraryRepository.deleteSong(song)
            } else {
                libraryRepository.deleteSong(songId)
            }
            if (!deleted) showDeleteError()
        }
    }

    private fun showDeleteError() {
        _uiState.update { it.copy(errorMessage = "No se pudo eliminar la canción. Revisa el acceso al almacenamiento.") }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun removeSelectedSongsFromPlaylist() {
        val pId = currentPlaylistId ?: return
        val toRemove = _uiState.value.selectedSongIds
        if (toRemove.isEmpty()) return
        viewModelScope.launch {
            for (songId in toRemove) {
                playlistRepository.removeSongFromPlaylist(pId, songId)
            }
            _uiState.update { it.copy(selectedSongIds = emptySet()) }
        }
    }

    fun deleteSelectedSongsPermanently() {
        val toDelete = _uiState.value.selectedSongIds
        if (toDelete.isEmpty()) return
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                val songsToDelete = _uiState.value.songs.filter { it.songId in toDelete || it.id in toDelete }
                val failedIds = libraryRepository.deleteSongs(songsToDelete)
                _uiState.update {
                    it.copy(isDeleting = false, isSelectionMode = failedIds.isNotEmpty(), selectedSongIds = failedIds,
                        errorMessage = if (failedIds.isEmpty()) null else
                            "No se pudieron eliminar ${failedIds.size} canciones. Revisa el acceso al almacenamiento.")
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { _uiState.update { it.copy(isDeleting = false, errorMessage = "No se pudo completar la eliminación") } }
        }
    }

    fun addSelectedSongsToPlaylist(targetPlaylistId: String) {
        val toAdd = _uiState.value.selectedSongIds
        if (toAdd.isEmpty()) return
        viewModelScope.launch {
            for (songId in toAdd) {
                playlistRepository.addSongToPlaylist(targetPlaylistId, songId)
            }
            _uiState.update { it.copy(isSelectionMode = false, selectedSongIds = emptySet()) }
        }
    }

    fun deletePlaylist(onComplete: () -> Unit) {
        val pId = currentPlaylistId ?: return
        viewModelScope.launch {
            playlistRepository.deletePlaylist(pId)
            onComplete()
        }
    }
}
