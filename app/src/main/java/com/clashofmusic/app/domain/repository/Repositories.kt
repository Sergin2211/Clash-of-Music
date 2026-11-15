package com.clashofmusic.app.domain.repository

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MusicRepository {
    suspend fun search(query: String): Result<List<SongItem>>
    suspend fun searchAlbums(query: String): Result<List<AlbumItem>>
    suspend fun getAlbumTracks(albumId: String): Result<List<AlbumTrackItem>>
    suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails>
    suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo>
    fun isSupportedPlaylistUrl(url: String): Boolean
}

interface DownloadRepository {
    fun observeBatchPreparation(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)
    fun beginBatchPreparation() {}
    fun endBatchPreparation() {}
    fun observeTasks(): Flow<List<DownloadTask>>
    fun isSongDownloading(songId: String): Boolean
    suspend fun enqueueDownload(task: DownloadTask): String
    suspend fun pauseDownload(taskId: String)
    suspend fun resumeDownload(taskId: String)
    suspend fun cancelDownload(taskId: String)
    suspend fun cancelAllDownloads()
    val cancellationGeneration: Long get() = 0L
    fun checkDownloadBatch(generation: Long) {
        if (generation != cancellationGeneration) throw DownloadBatchCancelled()
    }
    suspend fun enqueueDownload(task: DownloadTask, generation: Long): String {
        checkDownloadBatch(generation)
        return enqueueDownload(task)
    }
    suspend fun retryDownload(taskId: String)
    suspend fun clearFinishedDownloads()
    suspend fun deleteDownloadTask(taskId: String)
}

class DownloadBatchCancelled : kotlinx.coroutines.CancellationException("Se cancelaron todas las descargas")

interface LibraryRepository {
    suspend fun updateGenreProfile(songId: String, profile: GenreProfile?) { error("Gestión de géneros no disponible") }
    fun observeDownloadedSongs(): Flow<List<DownloadedSong>>
    suspend fun getDownloadedSong(songId: String): DownloadedSong?
    suspend fun getEditableSong(songId: String): DownloadedSong? = getDownloadedSong(songId)
    suspend fun isSongDownloaded(songId: String): Boolean
    suspend fun checkDownloadEligibility(
        songId: String,
        format: AudioFormat,
        quality: AudioQuality
    ): DownloadEligibility
    suspend fun saveDownloadedSong(song: DownloadedSong)
    suspend fun saveDownloadedSong(song: DownloadedSong, requestedFormat: AudioFormat) = saveDownloadedSong(song)
    suspend fun deleteSong(songId: String): Boolean
    suspend fun deleteSong(song: DownloadedSong): Boolean = deleteSong(song.songId)
    suspend fun deleteSongs(songs: List<DownloadedSong>): Set<String> = songs.filterNot { deleteSong(it) }.map { it.songId }.toSet()
    suspend fun updateSongMetadata(songId: String, newTitle: String, newArtist: String, newAlbum: String?, newYear: String? = null): Boolean
    suspend fun syncStorage(): Int
}

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeAlbumCollections(): Flow<List<AlbumCollection>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun getPlaylistById(id: String): Playlist?
    suspend fun createPlaylist(name: String, description: String? = null, isAlbum: Boolean = false, thumbnailUrl: String? = null): String
    suspend fun updatePlaylist(playlist: Playlist)
    suspend fun deletePlaylist(id: String)
    suspend fun addSongToPlaylist(playlistId: String, songId: String)
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)
    suspend fun reorderPlaylist(playlistId: String, orderedSongIds: List<String>)
    fun getSongsForPlaylist(playlistId: String): Flow<List<DownloadedSong>>
}

interface FavoritesRepository {
    fun observeFavorites(): Flow<List<FavoriteSong>>
    fun isFavorite(songId: String): Flow<Boolean>
    suspend fun toggleFavorite(song: SongItem)
    suspend fun toggleFavoriteFromDownloaded(song: DownloadedSong)
}

interface HistoryRepository {
    fun observeRecentHistory(): Flow<List<SearchHistoryItem>>
    suspend fun addSearchQuery(query: String)
    suspend fun deleteSearchQuery(query: String)
    suspend fun clearSearchHistory()
}

interface PreferencesRepository {
    fun observeSettings(): Flow<AppSettings>
    fun currentSettings(): AppSettings = AppSettings()
    suspend fun updateDefaultFormat(format: AudioFormat)
    suspend fun updateDefaultQuality(quality: AudioQuality)
    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateAutoStorageSync(enabled: Boolean)
}
