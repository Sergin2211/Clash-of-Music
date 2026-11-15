package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.components.SongCard
import com.clashofmusic.app.presentation.library.LibraryScreen
import com.clashofmusic.app.presentation.library.LibraryViewModel
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real components; private covers and repositories, no changes to the user's library. */
class LibraryVisualCheck(private val instrumentation: Instrumentation) {
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name.substringBefore('-')) } as T

    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { found -> return found } }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var parent: AccessibilityNodeInfo? = node
        repeat(8) { if (parent?.isClickable == true) return parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); parent = parent?.parent }
        return false
    }

    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "visual_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        var activity: MainActivity? = null
        var vm: LibraryViewModel? = null
        var originalLoader: ImageLoader? = null
        var loader: ImageLoader? = null
        var success = false
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { label }; Thread.sleep(30) }
            checks.put(label)
        }
        try {
            check(directory.mkdirs())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = activity
            instrumentation.runOnMainSync { host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val theme = ServiceLocator.preferencesRepository.currentSettings().themeMode
            var downloads = 0
            instrumentation.runOnMainSync {
                host.setContent { ClashOfMusicTheme(theme) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 60.dp)) {
                        SongCard("Downloading fixture", "Artist", null, 200, onClick = {}, isDownloading = true, onDownloadClick = { downloads++ })
                        Spacer(Modifier.height(8.dp))
                        SongCard("Downloaded fixture", "Artist", null, 200, onClick = {}, isDownloaded = true, onDownloadClick = { downloads++ }, onSetAsClick = {})
                        Spacer(Modifier.height(8.dp))
                        SongCard("Available fixture with a long title that should truncate", "Artist", null, 200, onClick = {}, onDownloadClick = { downloads++ })
                    }
                } }
            }
            await("all-three-download-states-are-visible") { listOf("Descargando", "Descargada", "Descargar").all { find(it) != null } }
            val centers = listOf("Descargando", "Descargada", "Descargar").map {
                Rect().apply { find(it)!!.getBoundsInScreen(this) }.centerX()
            }
            check(centers.max() - centers.min() <= 1) { "Unaligned centers: $centers" }
            checks.put("three-download-icons-share-one-horizontal-center")
            report.put("statusIconCentersX", JSONArray(centers))
            var target = find("Descargar")
            var clicked = false
            repeat(6) {
                if (!clicked && target?.isClickable == true) clicked = target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                else if (!clicked) target = target?.parent
            }
            check(clicked)
            await("available-download-button-still-works") { downloads == 1 }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "visual_status_check.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            fun cover(name: String, color: Int) = File(directory, name).apply {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            val albumCover = cover("album-cover.png", Color.BLUE)
            val songCover = cover("song-cover.png", Color.RED)
            val requested = ConcurrentLinkedQueue<String>()
            originalLoader = Coil.imageLoader(context)
            loader = ImageLoader.Builder(context).eventListener(object : EventListener {
                override fun onStart(request: ImageRequest) {
                    if (request.data.toString().startsWith(directory.absolutePath)) requested.add(request.data.toString())
                }
            }).build()
            Coil.setImageLoader(loader!!)
            val song = DownloadedSong("visual", "visual", "Shared single", "Artist", "A different single", 200,
                songCover.absolutePath, "/private.mp3", "file:///private.mp3", "MP3", fileSizeBytes = 100, downloadedAt = 1)
            val album = Playlist("visual-album", "Private album cover test", "Artist • Pop", albumCover.absolutePath, true)
            instrumentation.runOnMainSync {
                vm = LibraryViewModel(
                    libraryRepository = fake { if (it == "observeDownloadedSongs") flowOf(listOf(song)) else error(it) },
                    playlistRepository = fake { when (it) {
                        "observePlaylists" -> flowOf(listOf(album))
                        "observeAlbumCollections" -> flowOf(listOf(AlbumCollection(album, listOf(song.songId))))
                        else -> error(it)
                    } },
                    favoritesRepository = fake { if (it == "observeFavorites") flowOf(emptyList<FavoriteSong>()) else error(it) },
                    synchronizeOnStart = false)
                vm!!.onTabSelected(4)
                host.setContent { ClashOfMusicTheme(theme) { LibraryScreen({}, {}, vm!!) } }
            }
            await("library-search-has-short-placeholder") { find("Buscar en biblioteca") != null }
            check(find("Buscar en biblioteca (canciones, artistas, álbumes...)") == null)
            await("album-list-requests-its-own-artwork") { find(album.name) != null && albumCover.absolutePath in requested }
            check(songCover.absolutePath !in requested)
            check(vm!!.uiState.value.albums.single().thumbnailUrl == albumCover.absolutePath)
            checks.put("first-songs-different-cover-is-not-used-for-album")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "visual_album_check.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun verifySelectionInHeader(label: String) {
                await("$label-selection-in-header") { find("Selección múltiple") != null && find("Sincronizar almacenamiento") != null }
                val select = Rect().also { find("Selección múltiple")!!.getBoundsInScreen(it) }
                val sync = Rect().also { find("Sincronizar almacenamiento")!!.getBoundsInScreen(it) }
                check(kotlin.math.abs(select.centerY() - sync.centerY()) < 2)
                check(click(find("Selección múltiple")!!))
                await("$label-selection-mode-opens") { vm!!.uiState.value.isSelectionMode && find("Todas") != null }
                check(click(find("Todas")!!))
                await("$label-select-all-keeps-correct-song") { vm!!.uiState.value.selectedSongIds == setOf(song.songId) && find("Deseleccionar") != null }
                check(click(find("Deseleccionar")!!))
                await("$label-clear-does-not-leave-selection-mode") { vm!!.uiState.value.isSelectionMode && vm!!.uiState.value.selectedSongIds.isEmpty() }
                check(click(find("Cancelar selección")!!))
                await("$label-selection-mode-closes") { !vm!!.uiState.value.isSelectionMode }
            }
            instrumentation.runOnMainSync { vm!!.selectAlbum(vm!!.uiState.value.albums.single()) }
            await("album-detail-ready") { find("Volver a Álbumes") != null }
            verifySelectionInHeader("album")
            instrumentation.runOnMainSync { vm!!.onTabSelected(3); vm!!.selectArtist(song.artist) }
            await("artist-detail-ready") { find("Volver a Artistas") != null }
            verifySelectionInHeader("artist")
            instrumentation.runOnMainSync { vm!!.onTabSelected(0) }
            await("song-sort-button-is-available") { find("Ordenar") != null }
            check(click(find("Ordenar")!!))
            await("sort-dropdown-opens") { find("Nombre (A-Z)") != null }
            check(click(find("Nombre (A-Z)")!!))
            await("sort-option-still-updates-view-model") { vm!!.uiState.value.sortOption == com.clashofmusic.app.presentation.library.SortOption.TITLE_ASC }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel(); activity?.setContent { }; activity?.finish() }
            if (originalLoader != null) Coil.setImageLoader(originalLoader!!)
            loader?.shutdown()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            report.put("fixturesRemoved", directory.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "library_visual_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
