package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.search.*
import com.clashofmusic.app.presentation.settings.*
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Real Compose controls backed exclusively by private in-memory catalog/download repositories. */
class AlbumControlsCheck(private val instrumentation: Instrumentation) {
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args.orEmpty())
        } as T
    private fun node(text: String, root: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString() == text) return root
        repeat(root.childCount) { node(text, root.getChild(it))?.let { found -> return found } }
        return null
    }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var parent: AccessibilityNodeInfo? = node
        repeat(8) { if (parent?.isClickable == true) return parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); parent = parent?.parent }
        return false
    }
    fun run() {
        val report = JSONObject()
        val checks = JSONArray()
        var host: MainActivity? = null
        var search: SearchViewModel? = null
        var settingsVm: SettingsViewModel? = null
        var success = false
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(label: String, test: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (!test()) { check(SystemClock.elapsedRealtime() < deadline) { label }; Thread.sleep(20) }
            checks.put(label)
        }
        try {
            host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            main { host!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            // Never export catalog or audio; compare metadata and hashes inside the phone.
            val before = runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() }
            val hashes = before.map { File(it.filePath).takeIf(File::isFile)?.let(::sha) }
            val album = AlbumItem("private", "Private album controls", "Private artist", null, null, 18, null)
            val tracks = (1..18).map { AlbumTrackItem(it, "Private track $it", album.artist, album.title, 201, null) }
            val songs = MutableStateFlow(tracks.map { DownloadedSong("video%06d".format(it.trackNumber), "video%06d".format(it.trackNumber),
                it.title, it.artist, it.album, 201, null, "private", "private", "MP3", "123k", 100, 1) })
            val tasks = MutableStateFlow(emptyList<DownloadTask>())
            val queued = mutableListOf<DownloadTask>()
            val missing = mutableSetOf<String>()
            var remote = RemotePlaylistInfo("private", album.title, album.artist, trackCount = 18, source = PlaylistSource.SPOTIFY,
                isAlbum = true, tracks = tracks.map { RemoteTrackItem(it.title, it.artist, it.album, 201) })
            val prefs = object : PreferencesRepository {
                val state = MutableStateFlow(AppSettings(defaultFormat = AudioFormat.MP3))
                override fun observeSettings() = state
                override suspend fun updateDefaultFormat(format: AudioFormat) { state.value = state.value.copy(defaultFormat = format) }
                override suspend fun updateDefaultQuality(quality: AudioQuality) { }
                override suspend fun updateThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
                override suspend fun updateAutoStorageSync(enabled: Boolean) { }
            }
            main {
                search = SearchViewModel(
                    musicRepository = fake { name, _ -> when (name) { "getAlbumTracks" -> tracks; "extractRemotePlaylist" -> remote; "search" -> error("Existing sources must not be searched again"); else -> error(name) } },
                    historyRepository = fake { name, _ -> if (name == "observeRecentHistory") flowOf(emptyList<SearchHistoryItem>()) else error(name) },
                    preferencesRepository = prefs,
                    libraryRepository = fake { name, args -> when (name) {
                        "observeDownloadedSongs" -> songs
                        "checkDownloadEligibility" -> { val song = songs.value.firstOrNull { it.songId == args[0] }
                            when { song == null || song.songId in missing -> DownloadEligibility.CAN_DOWNLOAD
                                song.format.equals((args[1] as AudioFormat).extension, true) -> DownloadEligibility.ALREADY_DOWNLOADED_SAME_QUALITY
                                else -> DownloadEligibility.CAN_OVERWRITE_QUALITY } }
                        else -> error(name)
                    } },
                    downloadRepository = fake { name, args -> when (name) {
                        "observeTasks" -> tasks; "getCancellationGeneration" -> 0L; "checkDownloadBatch" -> Unit
                        "beginBatchPreparation", "endBatchPreparation" -> Unit
                        "enqueueDownload" -> { val task = args[0] as DownloadTask; queued.add(task); tasks.value += task; task.id }
                        else -> error(name)
                    } },
                    playlistRepository = fake { name, _ -> when (name) {
                        "observePlaylists" -> flowOf(emptyList<Playlist>()); "createPlaylist" -> "private"; "addSongToPlaylist" -> Unit; else -> error(name)
                    } })
                host!!.setContent { ClashOfMusicTheme { SearchScreen(onNavigateToSongDetails = {}, viewModel = search!!) } }
                search!!.selectAlbum(album)
            }
            await("search-album-complete-label") { node("Álbum descargado") != null }
            main { search!!.onAlbumFormatSelected(AudioFormat.OPUS) }
            await("different-format-enables-18-tracks") { node("Descargar Álbum (18 canciones)") != null }
            main { search!!.downloadAlbum(album, tracks) }
            await("spinner-label-survives-preparation") { !search!!.uiState.value.isQueueingAlbum && search!!.uiState.value.albumDownloadState.active && node("Descargando álbum") != null }
            check(queued.size == 18 && queued.map { it.songId }.toSet().size == 18)
            main { songs.value = songs.value.map { it.copy(format = "OPUS") }; tasks.value = tasks.value.map { it.copy(status = DownloadStatus.COMPLETED) } }
            await("completion-restores-complete-label") { node("Álbum descargado") != null }
            main { missing.add(songs.value.first().songId); search!!.refreshDownloadAvailability() }
            await("missing-file-enables-one-track") { node("Descargar Álbum (1 canción)") != null }
            main { search!!.selectAlbum(null); search!!.onTabSelected(1); search!!.onLinkFormatSelected(AudioFormat.OPUS); search!!.extractLink("private") }
            await("link-album-enables-only-missing-track") { search!!.uiState.value.extractedPlaylist != null && node("Descargar Álbum (1 canción)") != null }
            main { search!!.downloadExtractedPlaylist(remote) }
            await("linked-album-shows-downloading-after-enqueue") { !search!!.uiState.value.isQueueingLink && node("Descargando álbum") != null }
            check(queued.size == 19 && queued.last().songId == songs.value.first().songId)
            checks.put("format-switch-retains18-identities-and-missing-only-adds-one")
            var visible by mutableStateOf(true)
            main {
                settingsVm = SettingsViewModel(prefs)
                host!!.setContent { val privateSettings by prefs.state.collectAsState(); ClashOfMusicTheme(privateSettings.themeMode) { if (visible) SettingsScreen(viewModel = settingsVm!!) } }
                settingsVm!!.onThemeModeChanged(ThemeMode.CHERRY)
            }
            await("theme-dropdown-trigger-is-visible") { node("Elegir tema") != null }
            check(click(node("Elegir tema")!!))
            await("theme-dropdown-offers-elixir") { node("Elixir") != null }
            check(click(node("Elixir")!!))
            await("theme-dropdown-change-is-saved") { prefs.state.value.themeMode == ThemeMode.ELIXIR && node("Elixir · Carbón y amatista") != null }
            Thread.sleep(300)
            fun hasColor(label: String, expected: Int): Boolean {
                val rect = Rect().also { node(label)!!.getBoundsInScreen(it) }
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return false
                try {
                    var matches = 0
                    for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(screenshot.height)) {
                        for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(screenshot.width)) {
                            if (screenshot.getPixel(x, y) == expected) matches++
                        }
                    }
                    return matches > 5
                } finally { screenshot.recycle() }
            }
            check(hasColor("Tema y Apariencia", 0xff914bb5.toInt()))
            check(hasColor("Ajustes", 0xfff7f7f8.toInt()))
            checks.put("theme-colors-section-heading-while-main-heading-stays-white")
            main { settingsVm!!.onThemeModeChanged(ThemeMode.CHERRY) }
            await("notice-visible") { node("Tema cambiado a") != null }
            main { visible = false }
            await("leaving-section-consumes-notice") { settingsVm!!.uiState.value.feedbackMessage == null }
            main { visible = true }
            instrumentation.uiAutomation.waitForIdle(100, 3000)
            check(node("Tema cambiado a") == null)
            checks.put("notice-does-not-reappear-on-return")
            fun showNotice() {
                main { settingsVm!!.onThemeModeChanged(if (settingsVm!!.uiState.value.settings.themeMode == ThemeMode.CHERRY) ThemeMode.ELIXIR else ThemeMode.CHERRY) }
                await("new-notice-visible") { settingsVm!!.uiState.value.feedbackMessage?.let { node(it) != null } == true }
                Thread.sleep(250) // Let the previous host transition leave before touching the new notice.
            }
            fun swipe(down: Boolean) {
                val rect = Rect().also { node("Tema cambiado a")!!.getBoundsInScreen(it) }
                val metrics = instrumentation.targetContext.resources.displayMetrics
                val x = rect.centerX(); val y = rect.centerY()
                val endX = if (down) x else (x + metrics.widthPixels * 2 / 3).coerceAtMost(metrics.widthPixels - 5)
                val endY = if (down) (y + 180 * metrics.density).toInt().coerceAtMost(metrics.heightPixels - 5) else y
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input swipe $x $y $endX $endY 220")).use { it.readBytes() }
            }
            showNotice(); swipe(false)
            await("horizontal-swipe-dismisses-notice") { settingsVm!!.uiState.value.feedbackMessage == null }
            showNotice(); val downStarted = SystemClock.elapsedRealtime(); swipe(true)
            await("downward-swipe-dismisses-notice") { settingsVm!!.uiState.value.feedbackMessage == null }
            check(SystemClock.elapsedRealtime() - downStarted < 1800) { "Notice expired instead of being swiped" }
            checks.put("downward-swipe-finishes-before-auto-timeout")
            showNotice()
            node("Cerrar aviso")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            await("accessible-close-button-dismisses-notice") { settingsVm!!.uiState.value.feedbackMessage == null }
            main {
                remote = remote.copy(id = "private-large", title = "Private large playlist", trackCount = 401, isAlbum = false,
                    tracks = (0..400).map { RemoteTrackItem("Private list track $it", "Private artist") })
                host!!.setContent { ClashOfMusicTheme { SearchScreen(onNavigateToSongDetails = {}, viewModel = search!!) } }
                search!!.onTabSelected(1)
                search!!.extractLink("private-large")
            }
            await("large-linked-preview-ready") { search!!.uiState.value.extractedPlaylist?.tracks?.size == 401 && node("Private large playlist") != null }
            fun scrollNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
                if (root == null) return emptyList()
                return (if (root.isScrollable) listOf(root) else emptyList()) + (0 until root.childCount).flatMap { scrollNodes(root.getChild(it)) }
            }
            repeat(3) {
                val list = scrollNodes(instrumentation.uiAutomation.rootInActiveWindow).maxBy { item -> Rect().also { item.getBoundsInScreen(it) }.height() }
                val bounds = Rect().also { list.getBoundsInScreen(it) }
                val x = bounds.centerX()
                val startY = bounds.top + (bounds.height() * .85f).toInt()
                val endY = bounds.top + (bounds.height() * .25f).toInt()
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "input swipe $x $startY $x $endY 350"
                )).use { it.readBytes() }
                Thread.sleep(250)
                instrumentation.uiAutomation.waitForIdle(100, 3000)
            }
            fun visibleTracks(root: AccessibilityNodeInfo?): List<Int> {
                if (root == null) return emptyList()
                val index = root.text?.toString()?.takeIf { it.startsWith("Private list track ") }?.removePrefix("Private list track ")?.toIntOrNull()
                return listOfNotNull(index) + (0 until root.childCount).flatMap { visibleTracks(root.getChild(it)) }
            }
            val visibleTracks = visibleTracks(instrumentation.uiAutomation.rootInActiveWindow)
            report.put("largePlaylistVisibleTrackCount", visibleTracks.size)
            report.put("largePlaylistVisibleIndexes", JSONArray(visibleTracks))
            check(visibleTracks.size in 1..35 && visibleTracks.max() > 5) { "Visible private track indexes: $visibleTracks" }
            checks.put("401-tracks-remain-lazy-and-scrollable-inside-one-visual-container")
            report.put("largePlaylistVisibleTrackCount", visibleTracks.size)
            val after = runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() }
            check(before == after)
            check(hashes == after.map { File(it.filePath).takeIf(File::isFile)?.let(::sha) })
            checks.put("personal-library-metadata-and-audio-hashes-unchanged")
            report.put("preservedSongs", after.size)
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally { main { search?.viewModelScope?.cancel(); settingsVm?.viewModelScope?.cancel(); host?.setContent { }; host?.finish() } }
        report.put("success", success).put("checks", checks)
        File(instrumentation.targetContext.filesDir, "album_controls_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
