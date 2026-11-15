package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.domain.model.Playlist
import com.clashofmusic.app.domain.repository.PlaylistRepository
import com.clashofmusic.app.presentation.components.MiniPlayerBottomBar
import com.clashofmusic.app.presentation.navigation.*
import com.clashofmusic.app.presentation.playlist.PlaylistDetailsScreen
import com.clashofmusic.app.presentation.playlist.PlaylistDetailsViewModel
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Native navigation and Compose UI checks without adding test libraries or touching the catalog. */
class NavigationPlayerCheck(private val instrumentation: Instrumentation) {
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }
            Thread.sleep(60)
        }
        checks.put(label)
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { result -> return result } }
        return null
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(12) {
            if (current?.isClickable == true && current!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current?.parent
        }
        return false
    }
    private fun visibleTrack(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString()?.matches(Regex("Prueba de cola [0-9]+")) == true && node.isVisibleToUser &&
            node.text?.toString() != ServiceLocator.playerManager.currentSong.value?.title) return node
        repeat(node.childCount) { visibleTrack(node.getChild(it))?.let { found -> return found } }
        return null
    }
    private fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        repeat(node.childCount) { scrollable(node.getChild(it))?.let { result -> return result } }
        return null
    }

    fun run() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "ui_check_${UUID.randomUUID()}")
        val report = JSONObject()
        var activity: MainActivity? = null
        var oldLoader: ImageLoader? = null
        var testLoader: ImageLoader? = null
        var originalVolume = 1f
        var success = false
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as MainActivity
            val host = activity
            main { host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            main {
                fun controller(saved: Bundle? = null): NavHostController {
                    val nav = NavHostController(context)
                    nav.navigatorProvider.addNavigator(ComposeNavigator())
                    nav.navigatorProvider.addNavigator(DialogNavigator())
                    nav.setLifecycleOwner(host)
                    nav.setViewModelStore(ViewModelStore())
                    saved?.let { nav.restoreState(it) }
                    nav.graph = nav.createGraph(startDestination = "home") {
                        composable("home") { }
                        composable("downloads") { }
                        composable("library") { }
                        composable("settings") { }
                        composable("search") { }
                        composable(Screen.Search.pattern, arguments = listOf(navArgument("query") {
                            type = NavType.StringType; nullable = true; defaultValue = null
                        })) { }
                    }
                    return nav
                }
                val nav = controller()
                nav.navigate("downloads")
                nav.navigate("search") // Legacy Downloads -> Ir al Buscador behavior.
                nav.navigate("library") {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    restoreState = true; launchSingleTop = true
                }
                nav.navigate("downloads") {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    restoreState = true; launchSingleTop = true
                }
                check(nav.currentDestination?.route == "search") { "Legacy regression was not reproduced" }
                checks.put("legacy-downloads-restores-search-reproduced")
                nav.navigateToTopLevel(Screen.Downloads)
                check(nav.currentDestination?.route == "downloads")
                checks.put("legacy-saved-stack-recovered-to-downloads")
                repeat(30) {
                    nav.navigateToTopLevel(Screen.Search)
                    nav.navigateToTopLevel(Screen.Library)
                    nav.navigateToTopLevel(Screen.Downloads)
                    check(nav.currentDestination?.route == "downloads")
                }
                checks.put("thirty-search-library-downloads-cycles-stay-on-correct-tab")
                val restored = controller(nav.saveState())
                restored.navigateToTopLevel(Screen.Downloads)
                check(restored.currentDestination?.route == "downloads")
                checks.put("saved-navigation-state-restores-correct-tab")
                val query = "Rock & Pop / 日本語 #1?"
                restored.navigateToTopLevel(Screen.Search, query)
                check(restored.currentBackStackEntry?.arguments?.getString("query") == query)
                check(restored.currentDestination?.route.topLevelRoute() == "search")
                restored.navigateToTopLevel(Screen.Downloads)
                check(restored.currentDestination?.route == "downloads")
                checks.put("encoded-query-and-tab-selection-remain-correct")
            }

            QueueTouchCheck(instrumentation).run(host, checks, report)
            check(directory.mkdirs())
            val audio = File(directory, "fixture.wav")
            val size = 60 * 8000 * 2
            val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray())
            bytes.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(size)
            audio.writeBytes(bytes.array())
            val cover = File(directory, "queue-cover.png")
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(40, 100, 190))
            cover.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val requests = AtomicInteger()
            oldLoader = Coil.imageLoader(context)
            testLoader = ImageLoader.Builder(context).eventListener(object : EventListener {
                override fun onStart(request: ImageRequest) {
                    if (request.data.toString().contains("queue-cover.png")) requests.incrementAndGet()
                }
            }).build()
            Coil.setImageLoader(testLoader)
            val songs = (0 until 500).map { index -> DownloadedSong("ui-perf-$index", "ui-perf-$index",
                "Prueba de cola $index", "Clash test", null, 60,
                android.net.Uri.fromFile(cover).toString() + "?entry=$index", audio.absolutePath,
                android.net.Uri.fromFile(audio).toString(), "WAV", "128k", audio.length(), 1) }
            main {
                val player = ServiceLocator.playerManager
                originalVolume = player.player!!.volume
                player.player!!.volume = 0f
                player.playSong(songs.first(), songs)
                player.pause()
                host.setContent { ClashOfMusicTheme { MiniPlayerBottomBar() } }
            }
            await("mini-player-visible-with-500-entry-queue") { find("Prueba de cola 0") != null }
            requests.set(0)
            val start = SystemClock.elapsedRealtime()
            check(click(find("Prueba de cola 0")!!))
            await("full-player-opens-with-long-queue") { find("REPRODUCIENDO AHORA") != null }
            report.put("openObservedMs", SystemClock.elapsedRealtime() - start)
            Thread.sleep(350)
            report.put("initialArtworkRequests", requests.get())
            await("artwork-gradient-fades-to-dark-without-extra-fetch") {
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                try {
                    val x = (screenshot.width * .025f).toInt()
                    val top = screenshot.getPixel(x, (screenshot.height * .12f).toInt())
                    val bottom = screenshot.getPixel(x, (screenshot.height * .85f).toInt())
                    android.graphics.Color.blue(top) > android.graphics.Color.red(top) + 12 &&
                        kotlin.math.abs(android.graphics.Color.blue(bottom) - android.graphics.Color.red(bottom)) < 8
                } finally { screenshot.recycle() }
            }
            await("system-navigation-area-remains-dark-below-artwork-gradient") {
                val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
                try {
                    val pixel = screenshot.getPixel(5, screenshot.height - 5)
                    kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel)) < 10 &&
                        android.graphics.Color.red(pixel) < 40
                } finally { screenshot.recycle() }
            }
            check(requests.get() < 25) { "Opening the player eagerly requested ${requests.get()} covers" }
            checks.put("opening-does-not-load-all-499-upcoming-artworks")
            check(find("Cola (499)") == null) { "The redundant queue button must stay removed" }
            checks.put("redundant-queue-button-is-absent")
            check(scrollable(instrumentation.uiAutomation.rootInActiveWindow)!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            await("integrated-queue-scrolls-to-visible-upcoming-tracks") {
                (1..30).any { find("Prueba de cola $it")?.isVisibleToUser == true }
            }
            report.put("afterScrollArtworkRequests", requests.get())
            check(requests.get() < 50) { "Queue composed too many offscreen rows" }
            checks.put("scroll-loads-a-small-visible-window-of-the-queue")
            val chosenNode = visibleTrack()!!
            val chosenIndex = chosenNode.text.toString().substringAfterLast(' ').toInt()
            val accessibleClick = click(chosenNode)
            report.put("accessibleQueueClickAccepted", accessibleClick)
            if (accessibleClick) await("queue-row-action-actually-switches-the-playing-track") {
                main { ServiceLocator.playerManager.currentSong.value?.id == songs[chosenIndex].id }
            }
            FullQueueTouchCheck(instrumentation).run(checks, report)
            check(accessibleClick) { "The accessible queue action must remain available" }
            main {
                ServiceLocator.playerManager.playSong(songs.first(), songs)
                ServiceLocator.playerManager.pause()
            }
            var appNav: NavHostController? = null
            main {
                host.setContent { ClashOfMusicTheme {
                    val nav = androidx.navigation.compose.rememberNavController()
                    androidx.compose.runtime.SideEffect { appNav = nav }
                    AppNavigation(nav)
                } }
            }
            await("app-navigation-with-mini-player-ready") { appNav != null && find("Prueba de cola 0") != null }
            main { appNav!!.navigate(Screen.PlaylistDetails.createRoute("private-insets-check")) }
            await("detail-screen-keeps-mini-player-without-bottom-tabs") { find("Volver") != null && find("Biblioteca") == null }
            val navBottom = main { host.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom }
            val systemTop = main { host.window.decorView.height } - navBottom
            var mini: AccessibilityNodeInfo? = find("Prueba de cola 0")
            repeat(8) { if (mini?.isClickable != true) mini = mini?.parent }
            val bounds = android.graphics.Rect().also { mini!!.getBoundsInScreen(it) }
            report.put("systemNavigationBottomPx", navBottom).put("miniPlayerBottomPx", bounds.bottom).put("systemNavigationTopPx", systemTop)
            check(bounds.bottom <= systemTop) { "Mini player overlaps system navigation: $bounds > $systemTop" }
            checks.put("mini-player-does-not-overlap-system-buttons-on-detail-screen")
            check(click(mini!!))
            await("mini-player-expands-from-detail-screen") { find("REPRODUCIENDO AHORA") != null }

            val playlist = Playlist("private-shuffle", "Private shuffle playlist", songCount = songs.size)
            val repository = object : PlaylistRepository by ServiceLocator.playlistRepository {
                override suspend fun getPlaylistById(id: String) = playlist
                override fun getSongsForPlaylist(playlistId: String) = flowOf(songs)
            }
            val model = main { PlaylistDetailsViewModel(repository) }
            try {
                main {
                    host.setContent { ClashOfMusicTheme { PlaylistDetailsScreen(playlist.id, {}, model) } }
                }
                await("playlist-shuffle-button-visible") { find("Aleatorio")?.isVisibleToUser == true }
                val player = ServiceLocator.playerManager
                val expectedIds = songs.map { it.id }.toSet()
                repeat(3) {
                    val revision = player.queueState.value.revision
                    check(click(find("Aleatorio")!!))
                    await("shuffle-start-${it + 1}-keeps-all-upcoming-tracks") {
                        val queue = player.queueState.value
                        queue.revision > revision && queue.currentIndex == 0 &&
                            queue.upcoming.size == songs.size - 1 &&
                            queue.entries.map { entry -> entry.song.id }.toSet() == expectedIds
                    }
                }

                main { player.setShuffleEnabled(false) }
                check(click(find(songs[3].title)!!))
                await("playlist-starts-at-fourth-track") { player.queueState.value.currentIndex == 3 &&
                    player.queueState.value.current?.song?.id == songs[3].id }
                main {
                    player.pause()
                    player.seekTo(5000L)
                    host.setContent { ClashOfMusicTheme { MiniPlayerBottomBar() } }
                }
                await("middle-track-visible-in-mini-player") { find(songs[3].title) != null }
                check(click(find(songs[3].title)!!))
                await("middle-track-shuffle-control-visible") { find("Aleatorio")?.isVisibleToUser == true }
                instrumentation.uiAutomation.waitForIdle(250, 3000)
                val currentEntry = player.queueState.value.currentEntryId
                fun tapShuffle() {
                    val bounds = android.graphics.Rect().also { find("Aleatorio")!!.getBoundsInScreen(it) }
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                        "input tap ${bounds.centerX()} ${bounds.centerY()}"
                    )).use { it.readBytes() }
                }
                tapShuffle()
                await("player-shuffle-recovers-all-tracks-before-the-start") {
                    val queue = player.queueState.value
                    queue.shuffleEnabled && queue.currentEntryId == currentEntry &&
                        queue.upcoming.size == songs.size - 1 &&
                        queue.upcoming.map { it.song.id }.toSet() == expectedIds - songs[3].id
                }
                check(main { player.player!!.currentPosition } >= 5000L)
                checks.put("player-shuffle-keeps-the-current-track-position")

                val jumpedTo = player.queueState.value.upcoming[3]
                main { player.playQueueEntry(jumpedTo.id) }
                await("player-selects-an-intermediate-queue-entry") {
                    player.queueState.value.currentEntryId == jumpedTo.id && find(jumpedTo.song.title)?.isVisibleToUser == true
                }
                tapShuffle()
                val remainingInOriginalOrder = songs.filter { it.id != songs[3].id && it.id != jumpedTo.song.id }
                await("disabling-shuffle-after-a-queue-jump-restores-all-skipped-tracks") {
                    val queue = player.queueState.value
                    !queue.shuffleEnabled && queue.currentEntryId == jumpedTo.id &&
                        queue.upcoming.map { it.song.id } == remainingInOriginalOrder.map { it.id }
                }
            } finally {
                main { model.viewModelScope.cancel() }
            }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            main {
                activity?.setContent { }
                if (oldLoader != null) Coil.setImageLoader(oldLoader!!)
                ServiceLocator.playerManager.stop()
                ServiceLocator.playerManager.player?.volume = originalVolume
                activity?.finish()
            }
            testLoader?.shutdown()
            directory.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "navigation_player_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
