package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.data.player.MusicPlayerManager
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.components.*
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Injects real pointer sequences. All playback audio and repositories are private fixtures. */
class SwipeMotionCheck(private val instrumentation: Instrumentation) {
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10000
        while (!test()) { check(SystemClock.elapsedRealtime() < end) { label }; Thread.sleep(15) }
        checks.put(label)
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        repeat(node.childCount) { find(label, node.getChild(it))?.let { result -> return result } }
        return null
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(8) {
            if (candidate?.isClickable == true) return candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            candidate = candidate?.parent
        }
        return false
    }
    private fun slider(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.rangeInfo != null && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) return node
        repeat(node.childCount) { slider(node.getChild(it))?.let { result -> return result } }
        return null
    }
    private fun bounds(label: String) = Rect().also { find(label)!!.getBoundsInScreen(it) }
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name.substringBefore('-')) } as T
    private fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private inner class Finger(private val x: Float, private val y: Float) {
        val start = SystemClock.uptimeMillis()
        var lastX = x; var lastY = y
        init { event(MotionEvent.ACTION_DOWN, 0, x, y) }
        private fun event(action: Int, time: Int, x: Float, y: Float) {
            val target = start + time
            if (target > SystemClock.uptimeMillis()) SystemClock.sleep(target - SystemClock.uptimeMillis())
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(start, target, action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
        fun move(dx: Float, dy: Float, time: Int) { lastX = x + dx; lastY = y + dy; event(MotionEvent.ACTION_MOVE, time, lastX, lastY) }
        fun up(time: Int): Long { event(MotionEvent.ACTION_UP, time, lastX, lastY); return start + time }
        fun cancel(time: Int) { event(MotionEvent.ACTION_CANCEL, time, lastX, lastY) }
    }
    fun run() {
        val report = JSONObject()
        var host: MainActivity? = null
        var player: MusicPlayerManager? = null
        val folder = File(instrumentation.targetContext.cacheDir, "swipe_${UUID.randomUUID()}")
        var success = false
        try {
            host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val before = runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() }
            val hashes = before.map { File(it.filePath).takeIf(File::isFile)?.let(::sha) }
            var cardCoordinates: LayoutCoordinates? = null
            fun cardBounds(): Rect = main {
                val coordinates = cardCoordinates!!
                val location = coordinates.localToWindow(Offset.Zero)
                Rect(location.x.toInt(), location.y.toInt(), (location.x + coordinates.size.width).toInt(),
                    (location.y + coordinates.size.height).toInt())
            }
            var generation by mutableIntStateOf(0)
            var visible by mutableStateOf(true)
            var dismissCount = 0
            var dismissTime = 0L
            val density = instrumentation.targetContext.resources.displayMetrics.density
            fun px(value: Int) = value * density
            main { host!!.setContent { ClashOfMusicTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    key(generation) { if (visible) SwipeAwayBox(allowDown = true, onDismiss = {
                        dismissCount++; dismissTime = SystemClock.uptimeMillis(); visible = false
                    }) {
                        Box(Modifier.size(320.dp, 90.dp).onGloballyPositioned { cardCoordinates = it }.background(Color.White).semantics { contentDescription = "Aviso privado" }, contentAlignment = Alignment.Center) { Text("Deslizar $generation") }
                    } }
                }
            } } }
            fun reset(): Rect {
                main { if (!visible) { generation++; visible = true } }
                await("private-card-visible") { find("Deslizar $generation") != null }
                repeat(2) {
                    val frame = java.util.concurrent.CountDownLatch(1)
                    main { android.view.Choreographer.getInstance().postFrameCallback { frame.countDown() } }
                    check(frame.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }
                return cardBounds()
            }
            var origin = reset()
            var finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(30), 0f, 80); finger.move(px(55), px(90), 180); Thread.sleep(70)
            var moved = cardBounds()
            check(kotlin.math.abs(moved.centerY() - origin.centerY()) < px(2) && moved.centerX() > origin.centerX() + px(25))
            checks.put("horizontal-drag-cannot-turn-down")
            finger.move(px(-30), 0f, 300); moved = cardBounds()
            check(moved.centerX() >= origin.centerX() - px(2))
            finger.up(460); Thread.sleep(500); check(dismissCount == 0)
            checks.put("horizontal-reversal-stops-at-origin")
            origin = reset(); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(0f, px(30), 80); finger.move(px(100), px(55), 180); Thread.sleep(70)
            moved = cardBounds()
            check(kotlin.math.abs(moved.centerX() - origin.centerX()) < px(2) && moved.centerY() > origin.centerY() + px(25)) { "Down lock: origin=$origin moved=$moved density=$density" }
            checks.put("down-drag-cannot-turn-sideways")
            finger.move(0f, px(-30), 300); finger.up(450); Thread.sleep(500)
            check(dismissCount == 0 && cardBounds().centerY() >= origin.centerY() - px(2))
            checks.put("down-reversal-returns-to-origin")
            origin = reset(); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(60), 0f, 300); val beforeUp = cardBounds().centerX()
            finger.up(500); Thread.sleep(55)
            val returning = cardBounds().centerX()
            check(dismissCount == 0 && returning > origin.centerX() && returning < beforeUp)
            await("short-slow-drag-animates-back") { kotlin.math.abs(cardBounds().centerX() - origin.centerX()) < 2 }
            origin = reset(); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(170), 0f, 250); val release = finger.up(420)
            await("long-drag-completes-exit") { dismissCount == 1 }
            check(dismissTime - release >= 80) { "Dismiss happened before exit animation" }
            checks.put("dismiss-callback-waits-for-animation")
            report.put("longSwipeExitMs", dismissTime - release)
            origin = reset(); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(-25), 0f, 12); finger.move(px(-50), 0f, 24); finger.move(px(-85), 0f, 36)
            val flingRelease = finger.up(42)
            await("fast-short-flick-completes-exit") { dismissCount == 2 }
            check(dismissTime - flingRelease >= 80)
            origin = reset(); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(180), 0f, 250); finger.cancel(400)
            await("cancelled-drag-restores-origin") { kotlin.math.abs(cardBounds().centerX() - origin.centerX()) < 2 }
            check(dismissCount == 2)

            check(folder.mkdirs())
            val audio = File(folder, "private.wav")
            val size = 60 * 8000 * 2
            val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
            audio.writeBytes(wave.array())
            val song = DownloadedSong("swipe-private", "swipe-private", "Canción privada de gestos", "Prueba", null, 60, null,
                audio.absolutePath, android.net.Uri.fromFile(audio).toString(), "WAV", "128k", audio.length(), 1)
            main {
                player = MusicPlayerManager(instrumentation.targetContext,
                    fake<FavoritesRepository> { if (it == "isFavorite") flowOf(false) else error(it) },
                    fake<LibraryRepository> { if (it == "observeDownloadedSongs") flowOf(emptyList<DownloadedSong>()) else error(it) }, managePlaybackService = false)
                player!!.playSong(song, listOf(song)); player!!.pause()
                host!!.setContent { ClashOfMusicTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { MiniPlayerBottomBar(playerManager = player!!) } } }
            }
            await("private-mini-player-visible") { find(song.title) != null }
            origin = bounds(song.title); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(px(55), 0f, 300); finger.up(500); Thread.sleep(550)
            check(player!!.currentSong.value?.id == song.id)
            checks.put("mini-short-slow-swipe-does-not-stop-music")
            check(click(find(song.title)!!))
            await("private-full-player-visible") { slider() != null }
            check(slider()!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .5f) }))
            await("round-thumb-preserves-accessible-seeking") { main { player!!.player!!.currentPosition } in 29000..31000 }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await("mini-restored-after-player") { slider() == null && find(song.title) != null }
            Thread.sleep(500) // Let the modal window finish its exit before injecting into the mini player.
            origin = bounds(song.title); finger = Finger(origin.centerX().toFloat(), origin.centerY().toFloat())
            finger.move(instrumentation.targetContext.resources.displayMetrics.widthPixels * .36f, 0f, 250); val miniRelease = finger.up(420)
            check(player!!.currentSong.value != null)
            await("mini-reduced-distance-stops-only-after-exit") { player!!.currentSong.value == null }
            check(SystemClock.uptimeMillis() - miniRelease >= 80)
            val after = runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() }
            check(before == after && hashes == after.map { File(it.filePath).takeIf(File::isFile)?.let(::sha) })
            checks.put("personal-library-and-audio-unchanged")
            report.put("preservedSongs", after.size)
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        finally {
            main { player?.release(); host?.setContent { }; host?.finish() }
            if (folder.exists()) folder.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(instrumentation.targetContext.filesDir, "swipe_motion_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
