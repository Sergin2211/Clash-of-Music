package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.database.entity.*
import com.clashofmusic.app.data.local.*
import com.clashofmusic.app.data.media.*
import com.clashofmusic.app.data.repository.*
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.components.AddToPlaylistDialog
import com.clashofmusic.app.presentation.library.*
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** All deletion targets and preferences are private fixtures; Room is isolated in memory. */
class LibraryBatchCheck(private val instrumentation: Instrumentation) {
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }; Thread.sleep(60) }
        checks.put(label)
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { found -> return found } }
        return null
    }
    private fun click(text: String) {
        var node = find(text)
        repeat(7) {
            if (node?.isClickable == true) { check(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            node = node?.parent
        }
        error("Not clickable: $text")
    }
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "library_batch_${UUID.randomUUID()}")
        val preferencesName = "library_theme_check_${UUID.randomUUID()}"
        val report = JSONObject()
        var activity: MainActivity? = null
        var vm: LibraryViewModel? = null
        var database: ClashDatabase? = null
        var success = false
        try {
            check(directory.mkdirs())
            runBlocking {
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().putString("key_theme_mode", "CHERRY").commit()
                val preferences = PreferencesRepositoryImpl(context, preferencesName)
                check(preferences.currentSettings().themeMode == ThemeMode.CHERRY)
                check(preferences.observeSettings().first().themeMode == ThemeMode.CHERRY)
                check(PreferencesRepositoryImpl(context, preferencesName).currentSettings().themeMode == ThemeMode.CHERRY)
                checks.put("saved-theme-is-available-before-first-composition")

                database = Room.inMemoryDatabaseBuilder(context, ClashDatabase::class.java).build()
                val db = database!!
                val fixtures = (0 until 25).map { index ->
                    val file = File(directory, "bulk-$index.mp3").apply { writeBytes(ByteArray(4096) { index.toByte() }) }
                    DownloadedSong("bulk-$index", "bulk-$index", "Fixture $index", "Test artist", "Test album", 30,
                        null, file.absolutePath, android.net.Uri.fromFile(file).toString(), "MP3", fileSizeBytes = file.length(), downloadedAt = 1)
                }
                db.downloadedSongDao().insertAll(fixtures.map { DownloadedSongEntity.fromDomain(it) })
                db.playlistDao().insertOrUpdatePlaylist(PlaylistEntity("test-album", "Test album", null, null, true, 1))
                db.playlistDao().insertAllPlaylistSongs(fixtures.map { PlaylistSongEntity("test-album", it.id) })
                db.playlistDao().insertOrUpdatePlaylist(PlaylistEntity("second-album", "Second stored album", "Test artist • Pop • 2023", null, true, 2))
                db.playlistDao().addSongToPlaylist(PlaylistSongEntity("second-album", "bulk-7"))
                val storedCollections = withTimeout(10000) {
                    PlaylistRepositoryImpl(db.playlistDao(), db.downloadedSongDao()).observeAlbumCollections()
                        .first { items -> items.size == 2 && items.all { "bulk-7" in it.songIds } }
                }
                val storedAlbums = groupLibraryAlbums(fixtures, storedCollections)
                check(storedAlbums.size == 2 && storedAlbums.all { it.contains(fixtures[7]) })
                checks.put("room-memberships-keep-one-file-in-two-albums")
                val backups = AtomicInteger()
                val active = AtomicInteger()
                val peak = AtomicInteger()
                val vault = object : LibraryVault {
                    override suspend fun backupCurrentDatabase(): Boolean { backups.incrementAndGet(); return true }
                    override suspend fun <T> reconcile(block: suspend (VaultData?) -> T): T = block(null)
                }
                val storage = object : LibraryStorage {
                    override fun canReadLibrary() = true
                    override fun inspectAudioFile(filePath: String, fileUri: String?) = AudioFileInspection(AudioFileAvailability.AVAILABLE)
                    override suspend fun scanAndImportAudioFiles() = emptyList<DownloadedSong>()
                    override fun findLocalArtwork(cleanFileName: String, songId: String?) = null
                    override fun scanFile(path: String) = Unit
                    override fun deleteAudioFile(filePath: String, fileUri: String?): Boolean {
                        check(File(filePath).canonicalFile.parentFile == directory.canonicalFile)
                        val running = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, running) }
                        try {
                            Thread.sleep(20)
                            return if (File(filePath).name == "bulk-7.mp3") false else File(filePath).delete()
                        } finally { active.decrementAndGet() }
                    }
                }
                val counts = CopyOnWriteArrayList<Int>()
                val observer = launch(Dispatchers.IO) { db.downloadedSongDao().getAllSongs().collect { counts.add(it.size) } }
                withTimeout(10000) { while (counts.isEmpty()) delay(10) }
                val start = SystemClock.elapsedRealtime()
                val failed = LibraryRepositoryImpl(db.downloadedSongDao(), db.playlistDao(), storage, vault, db)
                    .deleteSongs(fixtures.take(24))
                report.put("batchDeleteMs", SystemClock.elapsedRealtime() - start)
                withTimeout(10000) { while (counts.last() != 2) delay(10) }
                observer.cancelAndJoin()
                check(failed == setOf("bulk-7"))
                check(backups.get() == 1)
                check(peak.get() in 2..4)
                check(counts.all { it == 25 || it == 2 })
                check(db.downloadedSongDao().getAllSongsList().map { it.id }.toSet() == setOf("bulk-7", "bulk-24"))
                check(File(fixtures[7].filePath).exists() && File(fixtures[24].filePath).exists())
                check(db.playlistDao().getAllPlaylistSongsList().map { it.songId }.toSet() == setOf("bulk-7", "bulk-24"))
                report.put("backupWrites", backups.get()).put("maxConcurrentDeletes", peak.get()).put("observedRowCounts", JSONArray(counts))
                checks.put("batch-delete-publishes-one-change-and-one-backup")
                checks.put("failed-and-unselected-files-and-relations-survive")
                checks.put("physical-deletes-run-with-bounded-concurrency")
            }

            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
            val host = activity!!
            val shared = DownloadedSong("shared", "shared", "Shared fixture", "Artist", "Second test album", 30,
                null, "/fixture.mp3", "file:///fixture.mp3", "MP3", fileSizeBytes = 4096, downloadedAt = 1)
            val other = shared.copy(id = "other", songId = "other", title = "First album fixture", album = "First test album")
            val collections = listOf(
                AlbumCollection(Playlist("first", "First test album", "Artist • Pop • 2022", isAlbum = true), listOf("shared", "other")),
                AlbumCollection(Playlist("second", "Second test album", "Artist • Pop • 2023", isAlbum = true), listOf("shared")))
            val destinations = MutableStateFlow(collections.map { it.album } + Playlist("deleted", "Deleted album", isAlbum = true) + Playlist("real-playlist", "My actual playlist"))
            val playlists: PlaylistRepository = fake { name -> when (name) {
                "observePlaylists" -> destinations
                "observeAlbumCollections" -> flowOf(collections)
                else -> error(name)
            } }
            val theme = ServiceLocator.preferencesRepository.currentSettings().themeMode
            main {
                vm = LibraryViewModel(fake { flowOf(listOf(shared, other)) }, playlists,
                    fake { flowOf(emptyList<FavoriteSong>()) }, Dispatchers.Main.immediate, false)
                host.setContent { ClashOfMusicTheme(theme) { LibraryScreen({}, {}, vm!!) } }
            }
            await("shared-song-appears-in-both-album-memberships") { main { vm!!.uiState.value.albums.size == 2 } }
            main {
                val first = vm!!.uiState.value.albums.first { it.title == "First test album" }
                check(first.contains(shared) && first.songs.size == 2)
                vm!!.onTabSelected(4); vm!!.selectAlbum(first); vm!!.setSelectionMode(true)
            }
            await("selection-screen-opens-in-original-album") { find("0 seleccionadas") != null }
            click("Todas")
            await("select-all-includes-shared-song") { find("2 seleccionadas") != null }
            click("Deseleccionar")
            await("deselect-keeps-mode-and-album-open") {
                find("0 seleccionadas") != null && find("Cancelar selección") != null && main { vm!!.uiState.value.selectedAlbum?.title == "First test album" }
            }
            click("Todas")
            await("select-again-works-without-reentering-selection") { find("2 seleccionadas") != null }
            main { host.setContent { ClashOfMusicTheme(theme) { AddToPlaylistDialog(onDismiss = {}, onPlaylistSelected = {}, onCreateNewPlaylist = {}, playlistRepository = playlists) } } }
            await("playlist-picker-shows-only-real-playlists") { find("My actual playlist") != null }
            check(find("First test album") == null && find("Second test album") == null && find("Deleted album") == null)
            destinations.value = destinations.value.filter { it.isAlbum }
            await("deleted-playlist-disappears-without-showing-albums") { find("No tienes playlists creadas aún.") != null }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            main { vm?.viewModelScope?.cancel(); activity?.setContent { }; activity?.finish() }
            database?.close()
            context.deleteSharedPreferences(preferencesName)
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            report.put("fixturesRemoved", directory.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "library_batch_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
