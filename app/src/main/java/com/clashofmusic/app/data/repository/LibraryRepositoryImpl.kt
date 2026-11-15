package com.clashofmusic.app.data.repository

import androidx.room.withTransaction
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.database.dao.DownloadedSongDao
import com.clashofmusic.app.data.database.dao.PlaylistDao
import com.clashofmusic.app.data.database.entity.DownloadedSongEntity
import com.clashofmusic.app.data.database.entity.PlaylistEntity
import com.clashofmusic.app.data.database.entity.PlaylistSongEntity
import com.clashofmusic.app.data.local.LibraryVault
import com.clashofmusic.app.data.local.VaultData
import com.clashofmusic.app.data.media.AudioFileAvailability
import com.clashofmusic.app.data.media.LibraryStorage
import com.clashofmusic.app.data.media.LibraryStorageUnavailableException
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LibraryRepositoryImpl(
    private val downloadedSongDao: DownloadedSongDao,
    private val playlistDao: PlaylistDao,
    private val mediaStoreHelper: LibraryStorage,
    private val clashVaultHelper: LibraryVault? = null,
    private val database: ClashDatabase? = null,
    private val metadataEditor: com.clashofmusic.app.data.media.AudioMetadataEditor? = null,
    private val genreStore: com.clashofmusic.app.data.local.GenreProfileStore? = null,
    private val onSongSaved: (DownloadedSong) -> Unit = {},
    private val requestedFormats: com.clashofmusic.app.data.local.RequestedAudioFormatStore? = null
) : LibraryRepository {
    private val isSyncRunning = AtomicBoolean(false)

    override fun observeDownloadedSongs(): Flow<List<DownloadedSong>> = kotlinx.coroutines.flow.flow {
        withContext(Dispatchers.IO) { runCatching { genreStore?.load() } }
        val profiles = genreStore?.profiles ?: kotlinx.coroutines.flow.flowOf(emptyMap<String, GenreProfile>())
        emitAll(kotlinx.coroutines.flow.combine(downloadedSongDao.getAllSongs(), profiles) { rows, values ->
            rows.map { row -> row.toDomain().copy(genreProfile = values[row.songId]) }
        }.flowOn(Dispatchers.Default))
    }

    override suspend fun getDownloadedSong(songId: String): DownloadedSong? = withContext(Dispatchers.IO) {
        runCatching { genreStore?.load() }
        downloadedSongDao.getSongById(songId)?.toDomain()?.let { it.copy(genreProfile = genreStore?.get(it.songId)) }
    }

    override suspend fun updateGenreProfile(songId: String, profile: GenreProfile?) = withContext(Dispatchers.IO) {
        val store = genreStore ?: error("Gestión de géneros no disponible")
        store.load()
        mutate {
            val current = downloadedSongDao.getSongById(songId)?.toDomain()
            // Never overwrite a manual edit with an automatic lookup that started earlier.
            if (current != null && (profile == null || profile.manual ||
                    profile.appliesTo(current) && store.get(songId)?.manual != true)) store.put(songId, profile)
        }
    }

    override suspend fun isSongDownloaded(songId: String): Boolean = withContext(Dispatchers.IO) {
        val song = downloadedSongDao.getSongById(songId) ?: return@withContext false
        // Availability checks never mutate the catalog. Only a complete, authorized sync may purge it.
        mediaStoreHelper.inspectAudioFile(song.filePath, song.fileUri).availability == AudioFileAvailability.AVAILABLE
    }

    override suspend fun checkDownloadEligibility(songId: String, format: AudioFormat, quality: AudioQuality): DownloadEligibility = withContext(Dispatchers.IO) {
        val song = downloadedSongDao.getSongById(songId) ?: return@withContext DownloadEligibility.CAN_DOWNLOAD
        if (mediaStoreHelper.inspectAudioFile(song.filePath, song.fileUri).availability == AudioFileAvailability.MISSING) {
            return@withContext DownloadEligibility.CAN_DOWNLOAD
        }
        val requested = requestedFormats?.get(song.toDomain())
        val sameFormat = if (format == AudioFormat.ORIGINAL) requested == AudioFormat.ORIGINAL ||
            song.format.equals("webm", true) else song.format.equals(format.extension, true)
        if (sameFormat)
            DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY else DownloadEligibility.CAN_OVERWRITE_QUALITY
    }

    override suspend fun saveDownloadedSong(song: DownloadedSong) = withContext(Dispatchers.IO) {
        val entity = DownloadedSongEntity.fromDomain(song.copy(id = song.songId))
        mutate {
            databaseTransaction {
                // A scan can discover the published file just before its download commits its canonical ID.
                val aliases = downloadedSongDao.getAllSongsList().filter { it.filePath == entity.filePath && it.id != entity.id }
                val relations = playlistDao.getAllPlaylistSongsList()
                downloadedSongDao.insertOrUpdate(entity)
                for (alias in aliases) {
                    relations.filter { it.songId == alias.id }.forEach { relation ->
                        if (relations.none { it.playlistId == relation.playlistId && it.songId == entity.id }) {
                            playlistDao.addSongToPlaylist(relation.copy(songId = entity.id))
                        }
                    }
                    playlistDao.removeSongFromAllPlaylists(alias.id)
                    downloadedSongDao.deleteById(alias.id)
                }
            }
        }
        onSongSaved(song)
    }

    override suspend fun saveDownloadedSong(song: DownloadedSong, requestedFormat: AudioFormat) = withContext(Dispatchers.IO) {
        // Record before Room emits the new row so Original eligibility is immediately consistent.
        // This auxiliary file never blocks saving audio; stale rows are rejected by their fingerprint.
        runCatching { requestedFormats?.record(song, requestedFormat) }
            .onFailure { android.util.Log.w("LibraryRepository", "Could not retain requested format", it) }
        saveDownloadedSong(song)
        Unit
    }

    override suspend fun deleteSong(song: DownloadedSong): Boolean = deleteSongs(listOf(song)).isEmpty()

    override suspend fun deleteSongs(songs: List<DownloadedSong>): Set<String> = withContext(Dispatchers.IO) {
        val selected = songs.distinctBy { it.id }
        if (selected.isEmpty()) return@withContext emptySet()
        mutate {
            withContext(NonCancellable) {
                val permits = Semaphore(4)
                val results = coroutineScope {
                    selected.map { song -> async {
                        permits.withPermit {
                            song to runCatching { mediaStoreHelper.deleteAudioFile(song.filePath, song.fileUri) }.getOrDefault(false)
                        }
                    } }.awaitAll()
                }
                val removed = results.filter { it.second }.flatMap { listOf(it.first.id, it.first.songId) }.distinct()
                databaseTransaction {
                    for (ids in removed.chunked(400)) {
                        playlistDao.removeSongsFromAllPlaylists(ids)
                        downloadedSongDao.deleteAllByIds(ids)
                    }
                }
                results.filterNot { it.second }.map { it.first.songId }.toSet()
            }
        }
    }

    override suspend fun deleteSong(songId: String): Boolean = withContext(Dispatchers.IO) {
        val song = downloadedSongDao.getSongById(songId) ?: return@withContext false
        deleteSong(song.toDomain())
    }

    override suspend fun getEditableSong(songId: String): DownloadedSong? = withContext(Dispatchers.IO) {
        val song = downloadedSongDao.getSongById(songId)?.toDomain() ?: return@withContext null
        song.copy(year = if (metadataEditor != null) metadataEditor.readYear(File(song.filePath)) else mediaStoreHelper.readAudioYear(song.filePath))
    }

    override suspend fun updateSongMetadata(
        songId: String, newTitle: String, newArtist: String, newAlbum: String?, newYear: String?
    ): Boolean = withContext(Dispatchers.IO) {
        mutate {
            val song = downloadedSongDao.getSongById(songId) ?: return@mutate false
            val editor = metadataEditor ?: return@mutate false
            val tags = com.clashofmusic.app.data.media.AudioTags(newTitle, newArtist, newAlbum, newYear, song.genre, song.songId).validated()
            check(mediaStoreHelper.inspectAudioFile(song.filePath, song.fileUri).availability == AudioFileAvailability.AVAILABLE) {
                "No se puede editar el archivo; revisa el acceso al almacenamiento"
            }
            editor.edit(File(song.filePath), tags) { size ->
                databaseTransaction {
                    downloadedSongDao.update(song.copy(title = tags.title, artist = tags.artist, album = tags.album, fileSizeBytes = size))
                }
            }
            mediaStoreHelper.scanFile(song.filePath)
            true
        }
    }

    override suspend fun syncStorage(): Int = withContext(Dispatchers.IO) {
        if (!isSyncRunning.compareAndSet(false, true)) return@withContext 0
        try {
            mediaStoreHelper.requireReadableLibrary()
            if (clashVaultHelper != null) clashVaultHelper.reconcile { vault -> syncWithVault(vault) }
            else syncWithVault(null)
        } finally {
            isSyncRunning.set(false)
        }
    }

    private suspend fun syncWithVault(vault: VaultData?): Int {
        // Validate the complete listing before deleting anything or replacing a recoverable backup.
        val scanned = mediaStoreHelper.scanAndImportAudioFiles()
        mediaStoreHelper.requireReadableLibrary()
        return if (database != null) database.withTransaction { applySync(scanned, vault) }
            else applySync(scanned, vault)
    }

    private suspend fun applySync(scanned: List<DownloadedSong>, vault: VaultData?): Int {
        if (vault != null && vault.songs.any { it.genreProfileJson != null }) genreStore?.restoreMissing(vault)
        mediaStoreHelper.requireReadableLibrary()
        val dbSongs = downloadedSongDao.getAllSongsList()
        val inspected = dbSongs.map { it to mediaStoreHelper.inspectAudioFile(it.filePath, it.fileUri) }
        if (inspected.any { it.second.availability == AudioFileAvailability.INACCESSIBLE }) {
            throw LibraryStorageUnavailableException()
        }
        val missing = inspected.filter { it.second.availability == AudioFileAvailability.MISSING }.map { it.first }
        if (missing.isNotEmpty()) {
            val ids = missing.flatMap { listOf(it.id, it.songId) }.distinct()
            downloadedSongDao.deleteAllByIds(ids)
            playlistDao.removeSongsFromAllPlaylists(ids)
        }

        val current = downloadedSongDao.getAllSongsList()
        val paths = current.map { it.filePath }.toMutableSet()
        val ids = current.map { it.id }.toMutableSet()
        val songIds = current.map { it.songId }.toMutableSet()
        val imports = mutableListOf<DownloadedSongEntity>()
        for (song in scanned) {
            if (song.filePath in paths) continue
            val inspection = mediaStoreHelper.inspectAudioFile(song.filePath, song.fileUri)
            if (inspection.availability == AudioFileAvailability.INACCESSIBLE) throw LibraryStorageUnavailableException()
            if (inspection.availability == AudioFileAvailability.MISSING) continue
            // Exact, unambiguous identity only. Never match by title, artist or filename prefix.
            val match = vault?.songs?.singleOrNull { it.fileName == File(song.filePath).name }
                ?.takeIf { it.id !in ids && it.songId !in songIds }
            val id = match?.id ?: song.id
            val songId = match?.songId ?: song.songId
            if (id in ids || songId in songIds) continue
            imports.add(DownloadedSongEntity(
                id = id, songId = songId, title = match?.title ?: song.title, artist = match?.artist ?: song.artist,
                // A null saved album/genre is intentional and must not be replaced by an inferred value.
                album = if (match != null) match.album else song.album,
                genre = if (match != null) match.genre else song.genre,
                durationSeconds = song.durationSeconds.takeIf { it > 0 } ?: match?.durationSeconds ?: 0L,
                thumbnailUrl = mediaStoreHelper.findLocalArtwork(File(song.filePath).nameWithoutExtension, match?.songId)
                    ?: match?.thumbnailUrl ?: song.thumbnailUrl,
                filePath = song.filePath, fileUri = song.fileUri, format = match?.format ?: song.format,
                quality = match?.quality ?: song.quality, fileSizeBytes = song.fileSizeBytes,
                downloadedAt = match?.downloadedAt ?: song.downloadedAt
            ))
            paths.add(song.filePath); ids.add(id); songIds.add(songId)
        }
        if (imports.isNotEmpty()) downloadedSongDao.insertAll(imports)

        // Existing rows remain authoritative: no hardcoded seeds, Single/EP stripping or online enrichment.
        val availableSongs = downloadedSongDao.getAllSongsList()
        val identityMap = availableSongs.flatMap { listOf(it.id to it.id, it.songId to it.id) }.toMap()
        var restoredPlaylists = 0
        val existingPlaylistIds = playlistDao.getAllPlaylistsList().map { it.id }.toSet()
        for (playlist in vault?.playlists.orEmpty()) {
            if (playlist.id in existingPlaylistIds) continue
            val inserted = playlistDao.insertMissingPlaylist(PlaylistEntity(
                id = playlist.id, name = playlist.name, description = playlist.description,
                thumbnailUrl = playlist.thumbnailUrl, isAlbum = playlist.isAlbum, createdAt = playlist.createdAt
            ))
            if (inserted == -1L) continue
            restoredPlaylists++
            playlist.songIds.mapNotNull { identityMap[it] }.distinct().forEachIndexed { order, songId ->
                playlistDao.addSongToPlaylist(PlaylistSongEntity(playlistId = playlist.id, songId = songId, sortOrder = order))
            }
        }
        mediaStoreHelper.requireReadableLibrary()
        return missing.size + imports.size + restoredPlaylists
    }

    private suspend fun <T> mutate(block: suspend () -> T): T =
        if (clashVaultHelper != null) clashVaultHelper.mutate(block) else block()

    private suspend fun <T> databaseTransaction(block: suspend () -> T): T =
        if (database != null) database.withTransaction { block() } else block()
}
