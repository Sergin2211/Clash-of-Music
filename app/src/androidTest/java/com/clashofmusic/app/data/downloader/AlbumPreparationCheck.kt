package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.search.SearchScreen
import com.clashofmusic.app.presentation.search.SearchViewModel
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject

/** Exercises real album UI/resolution while adding tasks. Repositories are private in-memory fakes. */
class AlbumPreparationCheck(private val instrumentation: Instrumentation) {
    private fun hasText(text: String, node: android.view.accessibility.AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        repeat(node.childCount) { if (hasText(text, node.getChild(it))) return true }
        return false
    }
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args.orEmpty())
        } as T

    fun run() {
        val context = instrumentation.targetContext
        val report = JSONObject()
        val checks = JSONArray()
        var host: MainActivity? = null
        var vm: SearchViewModel? = null
        var frames = 0
        var lastFrame = 0L
        var maxGap = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (lastFrame != 0L) maxGap = maxOf(maxGap, time - lastFrame)
                lastFrame = time; frames++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { label }
                Thread.sleep(15)
            }
            checks.put(label)
        }
        var success = false
        try {
            val album = AlbumItem("private", "Private preparation test", "Private artist", null, null, 20, null)
            val tracks = (1..20).map { AlbumTrackItem(it, "Track $it", album.artist, album.title, 200, null) }
            val queued = Collections.synchronizedList(mutableListOf<DownloadTask>())
            val relations = Collections.synchronizedList(mutableListOf<String>())
            val searches = AtomicInteger()
            val music = object : MusicRepository {
                override suspend fun search(query: String): Result<List<SongItem>> {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "Album search/matching is still on UI thread" }
                    searches.incrementAndGet()
                    Thread.sleep(50) // Deterministic simulated network latency, outside UI.
                    val title = query.substringAfter(" - ")
                    val number = title.substringAfterLast(' ').toInt()
                    val studio = SongItem("studio-$number", "【Private artist】$title (Official Audio)", album.artist,
                        null, 200, "https://example.org/$number", channelName = "Private artist - Topic")
                    return Result.success(listOf(studio.copy(id = "live-$number", title = "$title (Live)")) +
                        (1..28).map { studio.copy(id = "cover-$number-$it", title = "$title (Cover)", channelName = "Other singer") } + studio)
                }
                override suspend fun getAlbumTracks(albumId: String) = Result.success(tracks)
                override suspend fun searchAlbums(query: String): Result<List<AlbumItem>> = error("unused")
                override suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails> = error("unused")
                override suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo> = error("unused")
                override fun isSupportedPlaylistUrl(url: String) = false
            }
            host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = host
            instrumentation.runOnMainSync {
                vm = SearchViewModel(musicRepository = music,
                    historyRepository = fake { name, _ -> if (name == "observeRecentHistory") flowOf(emptyList<SearchHistoryItem>()) else error(name) },
                    downloadRepository = fake { name, args -> when (name) {
                        "observeTasks" -> flowOf(emptyList<DownloadTask>())
                        "getCancellationGeneration" -> 0L
                        "checkDownloadBatch" -> Unit
                        "enqueueDownload" -> (args[0] as DownloadTask).also(queued::add).id
                        else -> error(name)
                    } },
                    libraryRepository = fake { name, _ -> when (name) {
                        "observeDownloadedSongs" -> flowOf(emptyList<DownloadedSong>())
                        "checkDownloadEligibility" -> DownloadEligibility.CAN_DOWNLOAD
                        else -> error(name)
                    } },
                    playlistRepository = fake { name, args -> when (name) {
                        "observePlaylists" -> flowOf(emptyList<Playlist>())
                        "createPlaylist" -> "private-album"
                        "addSongToPlaylist" -> { relations.add(args[1] as String); Unit }
                        else -> error(name)
                    } },
                    preferencesRepository = fake { name, _ -> if (name == "observeSettings") flowOf(AppSettings()) else error(name) })
                activity.setContent { ClashOfMusicTheme(ServiceLocator.preferencesRepository.currentSettings().themeMode) {
                    SearchScreen(onNavigateToSongDetails = {}, viewModel = vm!!)
                } }
                vm!!.selectAlbum(album)
            }
            await("album-preview-ready") { vm!!.uiState.value.albumTracks.size == 20 }
            await("album-preview-is-drawn") { hasText(album.title) && hasText("Descargar Álbum") }
            instrumentation.uiAutomation.waitForIdle(100, 5000)
            val start = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                Choreographer.getInstance().postFrameCallback(callback)
                vm!!.downloadAlbum(album, tracks)
            }
            val metrics = context.resources.displayMetrics
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * .75).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * .35).toInt()} 350"
            )).use { it.readBytes() }
            await("all-tracks-prepared-and-enqueued") { !vm!!.uiState.value.isQueueingAlbum }
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
            check(queued.size == 20 && relations.size == 20) { vm!!.uiState.value.albumDownloadMessage.orEmpty() }
            check(queued.map { it.songId } == tracks.map { "studio-${it.trackNumber}" })
            check(searches.get() == 20)
            check(frames > 5)
            checks.put("all-searches-run-off-ui-thread").put("studio-selected-over-first-live-result")
                .put("membership-and-queue-order-preserved").put("ui-keeps-producing-frames-during-preparation")
            report.put("tracks", queued.size).put("searches", searches.get()).put("durationMs", SystemClock.elapsedRealtime() - start)
                .put("framesObserved", frames).put("maxFrameGapMs", maxGap / 1_000_000.0)
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            instrumentation.runOnMainSync {
                Choreographer.getInstance().removeFrameCallback(callback)
                vm?.viewModelScope?.cancel()
                host?.setContent { }
                host?.finish()
            }
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "album_preparation_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
