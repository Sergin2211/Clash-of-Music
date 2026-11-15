package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.room.Room
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.database.entity.*
import com.clashofmusic.app.data.local.*
import com.clashofmusic.app.data.media.MediaStoreHelper
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.GenreProfile
import com.clashofmusic.app.domain.model.GenreProfileCodec
import com.clashofmusic.app.domain.model.GenreNames
import com.clashofmusic.app.domain.model.GenreAssignment
import com.clashofmusic.app.domain.model.GenreOrigin
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Real ContentResolver streams and Room transactions; the personal database/catalog are never used. */
class VaultDocumentCheck(private val instrumentation: Instrumentation) {
    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "vault_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        val database = Room.inMemoryDatabaseBuilder(context, ClashDatabase::class.java).build()
        var success = false
        try {
            check(directory.mkdirs())
            fun song(id: String, name: String): DownloadedSong {
                val file = File(directory, name).apply { writeBytes(ByteArray(100)) }
                return DownloadedSong(id, id, "Local edit", "Artist", null, 10, null,
                    file.absolutePath, android.net.Uri.fromFile(file).toString(), "MP3", "128k", file.length(), 1)
            }
            val local = song("local", "existing.mp3")
            val added = song("scanned", "restored.mp3")
            val genreStore = GenreProfileStore(File(directory, "genres.json"))
            val manualGenre = GenreProfile(GenreNames.identity(local), 100, true, listOf(GenreAssignment("Indie Rock", GenreOrigin.MANUAL)))
            val helper = ClashVaultHelper(context, MediaStoreHelper(context), database, genreStore) { listOf(local, added) }
            val data = VaultData(updatedAt = 100, songs = listOf(
                VaultSong("old-alias", "old-alias", "existing.mp3", "Old metadata", "Artist", null, null, 10, null,
                    genreProfileJson = GenreProfileCodec.encode(manualGenre)),
                VaultSong("restored", "restored", "restored.mp3", "Restaurada 日本語", "Artist", "Album", null, 10, null)
            ), playlists = listOf(VaultPlaylist("list", "Mi lista", songIds = listOf("restored", "old-alias"))))
            val document = File(directory, "document.json").apply { writeText("") }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", document)
            runBlocking {
                database.downloadedSongDao().insertAll(listOf(DownloadedSongEntity.fromDomain(local)))
                helper.writeExport(uri, VaultCodec.encode(data))
                check(helper.readImport(uri) == data)
                checks.put("content-resolver-write-close-read-verified-roundtrip")
                document.writeText("{invalid")
                check(runCatching { helper.readImport(uri) }.isFailure)
                check(database.downloadedSongDao().getAllSongsList().size == 1)
                checks.put("invalid-document-does-not-touch-room")
                helper.writeExport(uri, VaultCodec.encode(data))
                database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_vault BEFORE INSERT ON playlist_songs BEGIN SELECT RAISE(ABORT, 'Rollback test'); END")
                check(runCatching { helper.applyImport(helper.readImport(uri)) }.isFailure)
                check(database.downloadedSongDao().getAllSongsList().size == 1)
                check(database.playlistDao().getAllPlaylistsList().isEmpty())
                checks.put("room-failure-rolls-back-songs-collections-and-links")
                check(genreStore.get("local") == null)
                checks.put("failed-import-does-not-apply-genre-profiles")
                database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_vault")
                check(helper.applyImport(data) == VaultImportResult(1, 1, 2, 0))
                val restored = database.downloadedSongDao().getAllSongsList()
                check(restored.single { it.id == "local" }.title == "Local edit")
                check(restored.single { it.id == "restored" }.title == "Restaurada 日本語")
                val links = database.playlistDao().getAllPlaylistSongsList().sortedBy { it.sortOrder }
                check(links.map { it.songId } == listOf("restored", "local"))
                checks.put("additive-import-preserves-current-metadata-and-restores-order")
                check(genreStore.get("local") == manualGenre && genreStore.get("old-alias") == null)
                checks.put("genre-profile-follows-verified-local-id-instead-of-obsolete-backup-alias")
                check(helper.applyImport(data) == VaultImportResult(0, 0, 0, 0))
                checks.put("repeat-import-is-idempotent")
            }
            success = true
        } catch (error: Throwable) {
            report.put("error", android.util.Log.getStackTraceString(error))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "vault_document_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
