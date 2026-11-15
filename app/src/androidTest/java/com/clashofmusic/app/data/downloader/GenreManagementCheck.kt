package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.room.Room
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.data.database.ClashDatabase
import com.clashofmusic.app.data.database.entity.DownloadedSongEntity
import com.clashofmusic.app.data.extractor.*
import com.clashofmusic.app.data.local.GenreProfileStore
import com.clashofmusic.app.data.media.*
import com.clashofmusic.app.data.repository.LibraryRepositoryImpl
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.presentation.components.GenreManagerDialog
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

/** Private Room/JSON/UI checks, plus read-only online genre queries. Never retags the user's audio. */
class GenreManagementCheck(private val instrumentation: Instrumentation) {
    private fun <T> main(block: () -> T): T { var r: Result<T>? = null; instrumentation.runOnMainSync { r = runCatching(block) }; return r!!.getOrThrow() }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { return it } }; return null
    }
    private fun click(text: String) {
        var node = find(text) ?: error("Missing $text")
        repeat(8) { if (!node.isClickable && node.parent != null) node = node.parent }
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    fun run() {
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "genre_check_${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var db: ClashDatabase? = null; var activity: MainActivity? = null
        val report = JSONObject(); val checks = JSONArray(); var success = false
        fun await(label: String, block: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (!block()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }; Thread.sleep(60) }; checks.put(label)
        }
        try {
            check(dir.mkdirs())
            val file = File(dir, "original.mp3").apply { writeText("Private unchanged original audio") }
            val original = file.readBytes()
            val database = Room.inMemoryDatabaseBuilder(context, ClashDatabase::class.java).build(); db = database
            val songs = List(3) { i -> DownloadedSong("genre-$i", "genre-$i", "Private genre song $i", "Test artist", null, 180, null,
                file.absolutePath, "", "MP3", "", file.length(), 1) }
            runBlocking { database.downloadedSongDao().insertAll(songs.map(DownloadedSongEntity::fromDomain)) }
            val store = GenreProfileStore(File(dir, "profiles.json")); store.load()
            store.put(songs[2].songId, GenreProfile(GenreNames.identity(songs[2]), 1, true, listOf(GenreAssignment("J-Pop", GenreOrigin.MANUAL))))
            val storage = Proxy.newProxyInstance(LibraryStorage::class.java.classLoader, arrayOf(LibraryStorage::class.java)) { _, method, _ -> error("Unexpected storage write/read: ${method.name}") } as LibraryStorage
            val library = LibraryRepositoryImpl(database.downloadedSongDao(), database.playlistDao(), storage, database = database, genreStore = store)
            val manager = GenreLibraryManager(library, object : GenreLookup {
                override suspend fun lookup(song: DownloadedSong) = if (song.songId == "genre-0")
                    listOf(GenreAssignment("indie rock", GenreOrigin.RECORDING, "private-id"), GenreAssignment("alternative rock", GenreOrigin.RECORDING, "private-id")) else emptyList()
            }, scope)
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity; activity = host
            main { host.setContent { ClashOfMusicTheme { GenreManagerDialog({}, library, manager) } } }
            await("genre-manager-shows-current-library-and-source") { find("1 de 3 canciones con género") != null }
            click("Buscar y completar géneros")
            await("analysis-fills-specific-genres-and-preserves-manual-tags") { !manager.state.value.running && manager.state.value.done == 2 }
            check(store.get("genre-2")!!.manual && store.get("genre-2")!!.entries.single().name == "J-Pop")
            await("multiple-genre-results-and-provenance-visible") { find("Indie Rock · MusicBrainz · canción") != null && find("2 de 3 canciones con género") != null }
            click("Private genre song 0")
            await("manual-editor-opens") { find("Géneros separados por comas") != null }
            fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null || node.isEditable) return node
                repeat(node.childCount) { editable(node.getChild(it))?.let { return it } }; return null
            }
            val field = editable(instrumentation.uiAutomation.rootInActiveWindow)!!
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "jpop, hip hop") }))
            click("Guardar")
            await("manual-multiple-genres-save-and-refresh") { store.get("genre-0")?.manual == true }
            check(runBlocking { library.getDownloadedSong("genre-0")!!.genreNames } == listOf("J-Pop", "Hip-Hop"))
            runBlocking { library.updateGenreProfile("genre-0", GenreProfile(GenreNames.identity(songs[0]), 2,
                entries = listOf(GenreAssignment("Rock", GenreOrigin.ARTIST)))) }
            check(store.get("genre-0")!!.manual); checks.put("late-automatic-result-cannot-overwrite-manual-edit")
            val reopened = GenreProfileStore(File(dir, "profiles.json")); reopened.load()
            check(reopened.get("genre-0") == store.get("genre-0")); checks.put("genre-data-survives-reopening-store")
            check(file.readBytes().contentEquals(original)); checks.put("audio-file-and-room-song-metadata-unchanged")
            check(runBlocking { database.downloadedSongDao().getAllSongsList().map { it.toDomain() } } == songs)
            await("editor-closes-after-saving-without-losing-genre-list") { find("Editar géneros") == null }
            Thread.sleep(250)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(context.filesDir, "genres_manager_check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            val source = MusicBrainzGenres(File(dir, "online-cache"))
            val sample = songs[0].copy(title = "【Ado】Tot Musica（ウタ from ONE PIECE FILM RED）", artist = "Ado", durationSeconds = 202)
            val online = runBlocking { source.lookup(sample) }
            check(online.isNotEmpty()) { "No online genre evidence returned for the sample" }
            report.put("onlineAdo", JSONArray().apply { online.forEach { put(JSONObject().put("genre", it.name).put("source", it.origin.name).put("entityId", it.entityId)) } })
            checks.put("online-musicbrainz-genres-have-explicit-source-and-entity")
            success = true
        } catch (e: Throwable) { report.put("error", android.util.Log.getStackTraceString(e)) }
        finally {
            main { activity?.setContent { }; activity?.finish() }
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }; db?.close()
            check(dir.canonicalFile.parentFile == context.cacheDir.canonicalFile); dir.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "genre_management_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
