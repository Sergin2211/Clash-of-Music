package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Environment
import com.clashofmusic.app.data.local.*
import com.clashofmusic.app.di.ServiceLocator
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Personal comparisons stay in memory on the phone; only counts and pass/fail leave it. */
class VaultOrganizationCheck(private val instrumentation: Instrumentation) {
    fun run() = runBlocking {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "organization_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        var success = false
        try {
            check(root.mkdirs())
            val old = File(root, "Download/ClashOfMusic").apply { mkdirs() }
            val primary = File(root, "Documents/ClashOfMusic/Respaldos/clash_catalog.json")
            val mirror = File(root, "private/catalog.json")
            (1..31).forEach { number -> File(old, "clash_catalog ($number).json").writeText(
                VaultCodec.encode(VaultData(updatedAt = number.toLong(), playlists = listOf(
                    VaultPlaylist("list", "Private $number", createdAt = 1)
                )))
            ) }
            val manual = File(old, "clashvault_backup.json").apply { writeText("manual fixture") }
            val storage = OrganizedVaultStore({ listOf(primary, mirror) },
                { OrganizedVaultStore.legacyFiles(listOf(old)) }, { true })
            check(storage.load()!!.updatedAt == 31L)
            check(old.listFiles()!!.toList() == listOf(manual))
            val archive = File(primary.parentFile, "Historial").listFiles()!!.single()
            ZipFile(archive).use { zip ->
                check(zip.size() == 31)
                zip.entries().asSequence().forEach { entry ->
                    zip.getInputStream(entry).use { VaultCodec.decode(it.readBytes().toString(Charsets.UTF_8)) }
                }
            }
            checks.put("android-filesystem-31-copies-archived-and-manual-export-retained")
            repeat(30) { check(storage.save(VaultData(updatedAt = 100L + it))) }
            check(storage.load()!!.updatedAt == 129L)
            primary.writeText("corrupt")
            mirror.writeText("corrupt")
            check(storage.load()!!.updatedAt == 128L)
            checks.put("android-atomic-rotation-corruption-recovery-no-new-download-files")

            check(Environment.isExternalStorageManager()) { "Broad storage access needed for real migration" }
            val db = ServiceLocator.database
            val songs = db.downloadedSongDao().getAllSongsList()
            val playlists = db.playlistDao().getAllPlaylistsList()
            val links = db.playlistDao().getAllPlaylistSongsList()
            fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").run {
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) }
                }
                digest()
            }
            val audio = songs.map { File(it.filePath) }.filter { it.isFile }.distinct()
            val hashes = audio.associateWith(::digest)
            val genreFile = File(context.filesDir, "genre_profiles.json")
            val genreBytes = if (genreFile.isFile) genreFile.readBytes() else null
            val directories = listOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_DOCUMENTS).map { File(Environment.getExternalStoragePublicDirectory(it), "ClashOfMusic") }
            val before = OrganizedVaultStore.legacyFiles(directories)
            val validBefore = before.count { runCatching { VaultCodec.decode(it.readText()) }.isSuccess }
            val automatic = ServiceLocator.clashVaultHelper.loadVault()
            check(automatic != null || songs.isEmpty())
            val realPrimary = File(directories[2], "Respaldos/clash_catalog.json")
            if (automatic != null) check(VaultCodec.decode(realPrimary.readText()) == automatic)
            check(OrganizedVaultStore.legacyFiles(directories).none {
                runCatching { VaultCodec.decode(it.readText()) }.isSuccess
            })
            checks.put("real-valid-legacy-catalogs-migrated-to-documents")
            check(songs == db.downloadedSongDao().getAllSongsList())
            check(playlists == db.playlistDao().getAllPlaylistsList())
            check(links == db.playlistDao().getAllPlaylistSongsList())
            check(hashes.all { (file, hash) -> file.isFile && hash.contentEquals(digest(file)) })
            check(if (genreBytes == null) !genreFile.exists() else genreBytes.contentEquals(genreFile.readBytes()))
            checks.put("real-audio-hashes-metadata-playlists-relations-and-genres-unchanged")
            check(ServiceLocator.clashVaultHelper.loadVault() == automatic)
            checks.put("real-repeat-load-idempotent")
            report.put("songsChecked", songs.size).put("audioFilesChecked", audio.size)
                .put("legacyCatalogsMigrated", validBefore)
                .put("unrecognizedOrInvalidCatalogsKept", before.size - validBefore)
            success = true
        } catch (error: Throwable) {
            // No catalog contents, names of songs, paths or hashes in reports.
            report.put("errorType", error.javaClass.simpleName)
            report.put("failureAt", error.stackTrace.firstOrNull { it.className.contains("VaultOrganizationCheck") }?.lineNumber)
        } finally {
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
