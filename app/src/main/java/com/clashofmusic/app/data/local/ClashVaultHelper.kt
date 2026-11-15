package com.clashofmusic.app.data.local

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.media.MediaStoreHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ClashVaultHelper(
    private val context: Context,
    private val mediaStoreHelper: MediaStoreHelper,
    private val database: ClashDatabase,
    private val genreStore: GenreProfileStore? = null,
    private val scanForImport: suspend () -> List<com.clashofmusic.app.domain.model.DownloadedSong> = {
        mediaStoreHelper.scanAndImportAudioFiles()
    }
) : LibraryVault {
    companion object {
        private const val TAG = "ClashVaultHelper"
        private const val CATALOG = "clash_catalog.json"
        private const val SHARED_PATH = "Download/ClashOfMusic/"
    }

    private fun catalogFiles(): List<File> = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ClashOfMusic/Respaldos/$CATALOG"),
        File(context.filesDir, "clash_vault_backup.json")
    )

    private fun legacyDirectories(): List<File> = listOf(
        Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS
    ).map { File(Environment.getExternalStoragePublicDirectory(it), "ClashOfMusic") }

    private val fileStore = OrganizedVaultStore(
        targets = ::catalogFiles,
        legacyFiles = { OrganizedVaultStore.legacyFiles(legacyDirectories()) },
        canMigrate = ::canReconcileCatalog,
        readExtra = ::readFromMediaStore,
        onRetired = { files ->
            if (files.isNotEmpty()) MediaScannerConnection.scanFile(
                context, files.map { it.absolutePath }.toTypedArray(), null, null
            )
        }
    )
    private fun canReconcileCatalog(): Boolean = mediaStoreHelper.canReadLibrary() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())

    private val coordinator = VaultCoordinator(fileStore, ::snapshot, ::canReconcileCatalog)

    private suspend fun snapshot(): VaultData = database.withTransaction {
        runCatching { genreStore?.load() }
        val songs = database.downloadedSongDao().getAllSongsList()
        val playlists = database.playlistDao().getAllPlaylistsList()
        val relations = database.playlistDao().getAllPlaylistSongsList()
        VaultData(
            songs = songs.map { s ->
                VaultSong(s.id, s.songId, File(s.filePath).name, s.title, s.artist, s.album, s.genre,
                    s.durationSeconds, s.thumbnailUrl, s.format, s.quality, s.fileSizeBytes, s.downloadedAt,
                    genreStore?.get(s.songId)?.let(com.clashofmusic.app.domain.model.GenreProfileCodec::encode))
            },
            playlists = playlists.map { p ->
                VaultPlaylist(p.id, p.name, p.description, p.thumbnailUrl, p.isAlbum, p.createdAt,
                    relations.filter { it.playlistId == p.id }.sortedBy { it.sortOrder }.map { it.songId })
            }
        )
    }

    suspend fun loadVault(): VaultData? = withContext(Dispatchers.IO) { coordinator.load() }

    override suspend fun backupCurrentDatabase(): Boolean = withContext(Dispatchers.IO) {
        coordinator.backupCurrentDatabase()
    }

    override suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        coordinator.mutate(block)
    }

    override suspend fun <T> reconcile(block: suspend (VaultData?) -> T): T = withContext(Dispatchers.IO) {
        if (!canReconcileCatalog()) throw java.io.IOException(
            "Concede acceso a todos los archivos para sincronizar y recuperar ClashVault. La biblioteca y los respaldos se conservaron."
        )
        coordinator.reconcile(block)
    }

    private fun readFromMediaStore(): List<VaultData> {
        val resolver = context.contentResolver
        val baseUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))
        } else listOf(MediaStore.Files.getContentUri("external"))
        val results = mutableListOf<VaultData>()
        for (base in baseUris) {
            try {
                val names = "(${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
                val selection: String
                val args: Array<String>
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    selection = "$names AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?, ?)"
                    args = arrayOf(CATALOG, "clash_catalog_%.json", SHARED_PATH, "Music/ClashOfMusic/", "Documents/ClashOfMusic/")
                } else {
                    @Suppress("DEPRECATION")
                    val dataColumn = MediaStore.MediaColumns.DATA
                    selection = "$names AND $dataColumn = ?"
                    args = arrayOf(CATALOG, "clash_catalog_%.json", File(legacyDirectories()[1], CATALOG).absolutePath)
                }
                resolver.query(base, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        try {
                            val uri = android.content.ContentUris.withAppendedId(base, cursor.getLong(idCol))
                            resolver.openInputStream(uri)?.use { input ->
                                results.add(VaultCodec.decode(input.bufferedReader(Charsets.UTF_8).readText()))
                            }
                        } catch (_: Exception) { /* Try the next generation, not just the first row. */ }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read backup collection: ${e.message}")
            }
        }
        return results
    }

    suspend fun prepareExport(): String = withContext(Dispatchers.IO) {
        VaultCodec.encode(coordinator.exportSnapshot()).also {
            require(it.toByteArray(Charsets.UTF_8).size <= VaultTransfer.MAX_BYTES) { "El respaldo supera el límite de 10 MB" }
        }
    }

    suspend fun writeExport(uri: Uri, json: String) = withContext(Dispatchers.IO) {
        val expected = VaultTransfer.read(json.byteInputStream(Charsets.UTF_8))
        val resolver = context.contentResolver
        (resolver.openOutputStream(uri, "wt") ?: error("No se pudo abrir el destino")).use {
            it.write(json.toByteArray(Charsets.UTF_8))
        }
        val actual = (resolver.openInputStream(uri) ?: error("No se pudo verificar el archivo exportado")).use { VaultTransfer.read(it) }
        check(actual == expected) { "El archivo guardado no pudo verificarse; vuelve a exportar el respaldo" }
    }

    suspend fun readImport(uri: Uri): VaultData = withContext(Dispatchers.IO) {
        (context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el respaldo")).use { VaultTransfer.read(it) }
    }

    suspend fun applyImport(data: VaultData): VaultImportResult = withContext(Dispatchers.IO) {
        VaultTransfer.validate(data)
        check(canReconcileCatalog()) { "Concede acceso a todos los archivos antes de importar; tus datos se conservaron" }
        coordinator.mutate {
            // This scan supplies existing audio paths only. Import never deletes an absent file or DB row.
            val scanned = scanForImport()
            database.withTransaction {
                mediaStoreHelper.requireReadableLibrary()
                val current = database.downloadedSongDao().getAllSongsList()
                val candidates = current.map { it.toDomain() } + scanned
                val available = candidates.distinctBy { it.filePath }.filter {
                    val state = mediaStoreHelper.inspectAudioFile(it.filePath, it.fileUri).availability
                    check(state != com.clashofmusic.app.data.media.AudioFileAvailability.INACCESSIBLE) {
                        "No se puede comprobar el acceso a la música; no se aplicó la importación"
                    }
                    state == com.clashofmusic.app.data.media.AudioFileAvailability.AVAILABLE
                }
                val dao = database.playlistDao()
                val plan = VaultImportPlanner.plan(data, current, available, dao.getAllPlaylistsList(), dao.getAllPlaylistSongsList())
                if (plan.songs.isNotEmpty()) database.downloadedSongDao().insertAll(plan.songs)
                plan.playlists.forEach { dao.insertMissingPlaylist(it) }
                plan.links.forEach { dao.addSongToPlaylist(it) }
                mediaStoreHelper.requireReadableLibrary()
                val genreSongs = data.songs.mapNotNull { saved ->
                    val localId = plan.identities[saved.songId] ?: return@mapNotNull null
                    val local = database.downloadedSongDao().getSongById(localId) ?: return@mapNotNull null
                    saved.copy(songId = local.songId)
                }
                genreStore?.restoreMissing(data.copy(songs = genreSongs))
                plan.result
            }
        }
    }
}
