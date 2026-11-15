package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.PlaylistRepository
import com.clashofmusic.app.presentation.playlist.*
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy

/** Drags real Compose song rows against a private in-memory repository. */
class PlaylistReorderCheck(private val instrumentation: Instrumentation) {
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow()
    }
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { return it } }; return null
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }; Thread.sleep(60) }
        checks.put(label)
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        check(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
    }
    private fun scrollable(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null || node.isScrollable) return node
        repeat(node.childCount) { scrollable(node.getChild(it))?.let { return it } }; return null
    }
    fun run() {
        var activity: MainActivity? = null
        var vm: PlaylistDetailsViewModel? = null
        var success = false
        val report = JSONObject()
        val original = List(45) { i -> DownloadedSong("drag-$i", "drag-$i", "Private drag $i", "Test", null, 180,
            null, "", "", "MP3", "", 0, 1) }
        val songs = MutableStateFlow(original)
        var writes = 0
        var failWrite = false
        val repository = Proxy.newProxyInstance(PlaylistRepository::class.java.classLoader, arrayOf(PlaylistRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaylistById" -> Playlist("drag-list", "Private drag playlist", songCount = songs.value.size)
                "getSongsForPlaylist" -> songs
                "observePlaylists" -> flowOf(emptyList<Playlist>())
                "reorderPlaylist" -> {
                    if (failWrite) error("Simulated write failure")
                    @Suppress("UNCHECKED_CAST") val ids = args!![1] as List<String>
                    val map = songs.value.associateBy { it.id }; songs.value = ids.map { map.getValue(it) }; writes++; Unit
                }
                else -> Unit
            }
        } as PlaylistRepository
        try {
            val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = host
            val model = main { PlaylistDetailsViewModel(repository) }; vm = model
            main { host.setContent { ClashOfMusicTheme { PlaylistDetailsScreen("drag-list", {}, model) } } }
            await("private-playlist-visible") { find("Private drag 0") != null }
            check(find("Reordenar canciones") == null); checks.put("reorder-mode-button-removed")
            val first = Rect().also { find("Private drag 0")!!.getBoundsInScreen(it) }
            val viewport = Rect().also { scrollable()!!.getBoundsInScreen(it) }
            val bottom = viewport.bottom.toFloat() - 8
            val x = first.centerX().toFloat(); val start = first.centerY().toFloat()
            val down = SystemClock.uptimeMillis()
            event(down, MotionEvent.ACTION_DOWN, x, start)
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 180)
            await("long-press-starts-reorder-without-opening-menu") { model.uiState.value.isReordering && find("Reproducir a continuación") == null }
            repeat(16) { i -> event(down, MotionEvent.ACTION_MOVE, x, start + (bottom - start) * (i + 1) / 16); Thread.sleep(24) }
            Thread.sleep(1800)
            event(down, MotionEvent.ACTION_UP, x, bottom)
            await("drag-skips-many-positions-and-auto-scrolls") { !model.uiState.value.isSavingOrder && songs.value.indexOfFirst { it.id == "drag-0" } >= 8 }
            check(writes == 1); checks.put("one-save-per-drop")
            report.put("movedToIndex", songs.value.indexOfFirst { it.id == "drag-0" })
            check(songs.value.map { it.id }.toSet() == original.map { it.id }.toSet()); checks.put("drag-preserves-every-song")
            main { host.setContent { } }; main { model.viewModelScope.cancel() }
            val reopened = main { PlaylistDetailsViewModel(repository) }; vm = reopened
            main { host.setContent { ClashOfMusicTheme { PlaylistDetailsScreen("drag-list", {}, reopened) } } }
            await("saved-order-restored-after-reopening") { !reopened.uiState.value.isLoading && reopened.uiState.value.songs == songs.value }
            val saved = songs.value
            main { reopened.beginReorder(); reopened.moveSong(saved.last().id, saved.first().id); reopened.finishReorder(true) }
            check(reopened.uiState.value.songs == saved && writes == 1); checks.put("cancelled-drag-does-not-save")
            failWrite = true
            main { reopened.beginReorder(); reopened.moveSong(saved.last().id, saved.first().id); reopened.finishReorder(false) }
            await("failed-save-restores-repository-order") { !reopened.uiState.value.isSavingOrder && reopened.uiState.value.errorMessage != null }
            check(reopened.uiState.value.songs == saved && songs.value == saved)
            main { reopened.dismissError() }
            await("song-options-button-remains-available") { find("Opciones") != null }
            var option: AccessibilityNodeInfo? = find("Opciones")
            repeat(5) { if (option?.isClickable != true) option = option?.parent }
            check(option!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("song-options-open-from-three-dots") { find("Reproducir a continuación") != null }
            success = true
        } catch (error: Throwable) {
            report.put("error", android.util.Log.getStackTraceString(error)).put("writes", writes)
                .put("indexAfterFailure", songs.value.indexOfFirst { it.id == "drag-0" })
                .put("viewModelOrder", JSONArray(vm?.uiState?.value?.songs?.map { it.id }))
                .put("isReordering", vm?.uiState?.value?.isReordering).put("isSaving", vm?.uiState?.value?.isSavingOrder)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.filesDir, "reorder_check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        finally { main { activity?.setContent { }; vm?.viewModelScope?.cancel(); activity?.finish() } }
        report.put("success", success).put("checks", checks)
        File(instrumentation.targetContext.filesDir, "playlist_reorder_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
