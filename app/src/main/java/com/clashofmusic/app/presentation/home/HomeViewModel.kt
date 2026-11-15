package com.clashofmusic.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.model.genreNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenreItem(
    val name: String,
    val count: Int,
    val sampleArtwork: String? = null
)

data class HomeUiState(
    val downloadedSongs: List<DownloadedSong> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val trendingSongs: List<SongItem> = emptyList(),
    val isLoadingTrending: Boolean = false,
    val activeDownloadsCount: Int = 0,
    val genres: List<GenreItem> = emptyList(),
    val selectedGenre: String? = null
)

class HomeViewModel : ViewModel() {

    private val libraryRepository = ServiceLocator.libraryRepository
    private val playlistRepository = ServiceLocator.playlistRepository
    private val downloadRepository = ServiceLocator.downloadRepository
    private val musicRepository = ServiceLocator.musicRepository
    val playerManager = ServiceLocator.playerManager

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
        syncStorageSilently()
    }

    private fun syncStorageSilently() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                libraryRepository.syncStorage()
            } catch (_: Exception) {}
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.Default) {
            libraryRepository.observeDownloadedSongs().collect { songs ->
                val genreItems = songs.flatMap { song -> song.genreNames.map { name -> name to song } }
                    .groupBy({ it.first }, { it.second })
                    .map { (genreName, occurrences) ->
                        val sampleArtwork = occurrences.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                        GenreItem(name = genreName, count = occurrences.size, sampleArtwork = sampleArtwork)
                    }
                    .sortedByDescending { it.count }

                _uiState.update { it.copy(downloadedSongs = songs, genres = genreItems) }
            }
        }

        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { it.copy(playlists = playlists) }
            }
        }

        viewModelScope.launch {
            downloadRepository.observeTasks().map { tasks -> tasks.count { it.isActive } }
                .distinctUntilChanged().flowOn(Dispatchers.Default).collect { active ->
                _uiState.update { it.copy(activeDownloadsCount = active) }
            }
        }

        loadTrending()
    }

    fun loadTrending() {
        if (_uiState.value.trendingSongs.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoadingTrending = true) }
            val result = musicRepository.search("Top Hits 2026")
            result.fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(trendingSongs = list.take(10), isLoadingTrending = false) }
                },
                onFailure = {
                    _uiState.update { it.copy(isLoadingTrending = false) }
                }
            )
        }
    }

    fun playDownloadedSong(song: DownloadedSong) {
        playerManager.playSong(song, _uiState.value.downloadedSongs)
    }

    fun selectGenre(genre: String?) {
        _uiState.update { it.copy(selectedGenre = genre) }
    }

    fun playGenreSongs(genreName: String) {
        val genreSongs = _uiState.value.downloadedSongs.filter { it.genreNames.any { name -> name.equals(genreName, true) } }
        val first = genreSongs.firstOrNull() ?: return
        playerManager.playSong(first, genreSongs)
    }

    fun playGenreSong(song: DownloadedSong, genreName: String) {
        val genreSongs = _uiState.value.downloadedSongs.filter { it.genreNames.any { name -> name.equals(genreName, true) } }
        playerManager.playSong(song, genreSongs)
    }
}
