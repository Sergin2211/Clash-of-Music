package com.clashofmusic.app.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.model.LibraryAlbum
import com.clashofmusic.app.domain.model.groupLibraryAlbums
import com.clashofmusic.app.domain.model.genreNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import com.clashofmusic.app.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortOption(val displayName: String) {
    DATE_DESC("Más recientes"),
    TITLE_ASC("Nombre (A-Z)"),
    DURATION_DESC("Duración (Mayor a menor)")
}

data class LibraryUiState(
    val songs: List<DownloadedSong> = emptyList(),
    val filteredSongs: List<DownloadedSong> = emptyList(),
    val favoriteSongs: List<DownloadedSong> = emptyList(),
    val filteredFavoriteSongs: List<DownloadedSong> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val filteredPlaylists: List<Playlist> = emptyList(),
    val artists: List<String> = emptyList(),
    val filteredArtists: List<String> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val filteredAlbums: List<LibraryAlbum> = emptyList(),
    val selectedTab: Int = 0, // 0 = Canciones, 1 = Favoritos, 2 = Playlists, 3 = Artistas, 4 = Álbumes
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.DATE_DESC,
    val isSyncing: Boolean = false,
    val isDeleting: Boolean = false,
    val syncMessage: String? = null,
    val selectedArtist: String? = null,
    val selectedAlbum: LibraryAlbum? = null,
    val isSelectionMode: Boolean = false,
    val selectedSongIds: Set<String> = emptySet()
)

class LibraryViewModel(
    private val libraryRepository: LibraryRepository = ServiceLocator.libraryRepository,
    private val playlistRepository: PlaylistRepository = ServiceLocator.playlistRepository,
    private val favoritesRepository: FavoritesRepository = ServiceLocator.favoritesRepository,
    private val dataDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val synchronizeOnStart: Boolean = true
) : ViewModel() {
    val playerManager get() = ServiceLocator.playerManager

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        observeSongs()
        observePlaylists()
        observeFavorites()
        if (synchronizeOnStart) syncStorageSilently()
    }

    private fun observeFavorites() {
        viewModelScope.launch(dataDispatcher) {
            combine(
                favoritesRepository.observeFavorites(),
                libraryRepository.observeDownloadedSongs()
            ) { favList, downloadedList ->
                val downloadedMap = downloadedList.associateBy { it.songId }
                favList.mapNotNull { fav ->
                    downloadedMap[fav.id] ?: downloadedList.firstOrNull { it.id == fav.id }
                }
            }.collect { favSongs ->
                _uiState.update { current ->
                    current.copy(
                        favoriteSongs = favSongs,
                        filteredFavoriteSongs = applyFilterAndSort(favSongs, current.searchQuery, current.sortOption)
                    )
                }
            }
        }
    }

    private fun observeSongs() {
        viewModelScope.launch(dataDispatcher) {
            combine(libraryRepository.observeDownloadedSongs(), playlistRepository.observeAlbumCollections()) { songs, collections ->
                songs to groupLibraryAlbums(songs, collections)
            }.collect { (songs, distinctAlbums) ->
                val distinctArtists = songs.map { it.artist }.distinct().sorted()

                _uiState.update { current ->
                    current.copy(
                        songs = songs,
                        artists = distinctArtists,
                        albums = distinctAlbums,
                        selectedAlbum = distinctAlbums.firstOrNull { it.key == current.selectedAlbum?.key },
                        filteredSongs = applyFilterAndSort(songs, current.searchQuery, current.sortOption),
                        filteredArtists = filterArtists(distinctArtists, current.searchQuery),
                        filteredAlbums = filterAlbums(distinctAlbums, current.searchQuery)
                    )
                }
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { current ->
                    current.copy(
                        playlists = playlists,
                        filteredPlaylists = filterPlaylists(playlists, current.searchQuery)
                    )
                }
            }
        }
    }

    fun onTabSelected(index: Int) {
        _uiState.update { it.copy(selectedTab = index, selectedArtist = null, selectedAlbum = null, isSelectionMode = false, selectedSongIds = emptySet()) }
    }

    fun selectArtist(artist: String?) {
        _uiState.update { it.copy(selectedArtist = artist, isSelectionMode = false, selectedSongIds = emptySet()) }
    }

    fun selectAlbum(album: LibraryAlbum?) {
        _uiState.update { it.copy(selectedAlbum = album, isSelectionMode = false, selectedSongIds = emptySet()) }
    }

    fun setSelectionMode(enabled: Boolean) {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isSelectionMode = enabled, selectedSongIds = if (enabled) it.selectedSongIds else emptySet()) }
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

    fun selectAll(songs: List<DownloadedSong>) {
        if (_uiState.value.isDeleting) return
        _uiState.update { current ->
            val allIds = songs.map { it.songId }.toSet()
            current.copy(selectedSongIds = allIds)
        }
    }

    fun clearSelection() {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(selectedSongIds = emptySet()) }
    }

    fun deleteSelectedSongs() {
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
                        syncMessage = if (failedIds.isEmpty()) null else
                            "No se pudieron eliminar ${failedIds.size} canciones. Revisa el acceso al almacenamiento.")
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _uiState.update { it.copy(isDeleting = false, syncMessage = "No se pudo completar la eliminación. Revisa el almacenamiento.") }
            }
        }
    }

    fun addSelectedSongsToPlaylist(playlistId: String) {
        val toAdd = _uiState.value.selectedSongIds
        if (toAdd.isEmpty()) return
        viewModelScope.launch {
            for (songId in toAdd) {
                playlistRepository.addSongToPlaylist(playlistId, songId)
            }
            _uiState.update { it.copy(isSelectionMode = false, selectedSongIds = emptySet()) }
        }
    }

    fun playFavorites(songs: List<DownloadedSong>? = null) {
        val list = songs ?: _uiState.value.filteredFavoriteSongs
        val first = list.firstOrNull() ?: return
        playerManager.playSong(first, list)
    }

    fun shuffleFavorites(songs: List<DownloadedSong>? = null) {
        val list = (songs ?: _uiState.value.filteredFavoriteSongs).shuffled()
        val first = list.firstOrNull() ?: return
        playerManager.playSong(first, list)
    }

    fun removeFavorite(song: DownloadedSong) {
        viewModelScope.launch {
            favoritesRepository.toggleFavoriteFromDownloaded(song)
        }
    }

    fun playAllForArtist(artist: String, songs: List<DownloadedSong>? = null) {
        val artistSongs = songs ?: _uiState.value.songs.filter { it.artist.equals(artist, ignoreCase = true) }
        val first = artistSongs.firstOrNull() ?: return
        playerManager.playSong(first, artistSongs)
    }

    fun playAllForAlbum(album: LibraryAlbum, songs: List<DownloadedSong>? = null) {
        val current = _uiState.value.albums.firstOrNull { it.key == album.key } ?: return
        val albumSongs = songs?.filter { current.contains(it) } ?: current.songs
        val first = albumSongs.firstOrNull() ?: return
        playerManager.playSong(first, albumSongs)
    }

    fun onSearchQueryChanged(query: String) {
        // Text fields need their value immediately; delayed writes can reorder keystrokes.
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch(dataDispatcher) {
            _uiState.update { current ->
                if (current.searchQuery != query) return@update current
                current.copy(
                    filteredSongs = applyFilterAndSort(current.songs, query, current.sortOption),
                    filteredFavoriteSongs = applyFilterAndSort(current.favoriteSongs, query, current.sortOption),
                    filteredPlaylists = filterPlaylists(current.playlists, query),
                    filteredArtists = filterArtists(current.artists, query),
                    filteredAlbums = filterAlbums(current.albums, query)
                )
            }
        }
    }

    fun onSortOptionChanged(option: SortOption) {
        viewModelScope.launch(dataDispatcher) {
            _uiState.update { current ->
                current.copy(
                    sortOption = option,
                    filteredSongs = applyFilterAndSort(current.songs, current.searchQuery, option),
                    filteredFavoriteSongs = applyFilterAndSort(current.favoriteSongs, current.searchQuery, option)
                )
            }
        }
    }

    private fun filterPlaylists(playlists: List<Playlist>, query: String): List<Playlist> {
        if (query.isBlank()) return playlists
        return playlists.filter {
            it.name.contains(query, ignoreCase = true) ||
                    (it.description?.contains(query, ignoreCase = true) == true)
        }
    }

    private fun filterArtists(artists: List<String>, query: String): List<String> {
        if (query.isBlank()) return artists
        return artists.filter { it.contains(query, ignoreCase = true) }
    }

    private fun filterAlbums(albums: List<LibraryAlbum>, query: String): List<LibraryAlbum> =
        albums.filter { it.matchesQuery(query) }

    private fun applyFilterAndSort(
        songs: List<DownloadedSong>,
        query: String,
        sortOption: SortOption
    ): List<DownloadedSong> {
        val filtered = if (query.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true) ||
                        (it.album?.contains(query, ignoreCase = true) == true) ||
                        it.genreNames.any { genre -> genre.contains(query, ignoreCase = true) }
            }
        }

        return when (sortOption) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.downloadedAt }
            SortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.DURATION_DESC -> filtered.sortedByDescending { it.durationSeconds }
        }
    }

    fun syncStorage() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = null) }
            try {
                val count = libraryRepository.syncStorage()
                _uiState.update { it.copy(syncMessage = "Sincronización completada: $count cambios aplicados") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(syncMessage = e.message ?: "No se pudo sincronizar. Se conservaron tus datos.") }
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    private fun syncStorageSilently() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                libraryRepository.syncStorage()
            } catch (_: Exception) {}
        }
    }

    fun playSong(song: DownloadedSong, playlist: List<DownloadedSong>? = null) {
        val currentList = playlist ?: _uiState.value.filteredSongs
        playerManager.playSong(song, currentList)
    }

    fun deleteSong(song: DownloadedSong) {
        viewModelScope.launch {
            if (!libraryRepository.deleteSong(song)) {
                _uiState.update { it.copy(syncMessage = "No se pudo eliminar la canción. Revisa el acceso al almacenamiento.") }
            }
        }
    }

    fun loadMetadata(song: DownloadedSong, onLoaded: (DownloadedSong) -> Unit) {
        viewModelScope.launch {
            try {
                libraryRepository.getEditableSong(song.songId)?.let(onLoaded)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(syncMessage = "No se pudieron leer las etiquetas del archivo") } }
        }
    }

    fun updateMetadata(songId: String, title: String, artist: String, album: String?, year: String? = null) {
        viewModelScope.launch {
            try {
                val saved = libraryRepository.updateSongMetadata(songId, title, artist, album, year)
                if (saved) libraryRepository.getDownloadedSong(songId)?.let { playerManager.refreshSongMetadata(it) }
                _uiState.update { it.copy(syncMessage = if (saved) "Etiquetas guardadas en el archivo" else "No se pudieron guardar las etiquetas") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(syncMessage = e.message ?: "No se pudieron guardar las etiquetas") } }
        }
    }

    fun createPlaylist(name: String, description: String?) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name, description)
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            playlistRepository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun dismissSyncMessage(expected: String? = _uiState.value.syncMessage) {
        _uiState.update { if (it.syncMessage == expected) it.copy(syncMessage = null) else it }
    }
}
