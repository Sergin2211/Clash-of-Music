package com.clashofmusic.app.data.downloader

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.presentation.components.queueTrackTaps
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import org.json.JSONArray
import org.json.JSONObject

/** Physical touches on a private queue, including the first tap while inertia is still active. */
internal class QueueTouchCheck(private val instrumentation: Instrumentation) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun event(action: Int, start: Long, x: Float, y: Float) {
        val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
        motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(motion)
        motion.recycle()
    }
    fun run(host: MainActivity, checks: JSONArray, report: JSONObject) {
        var state: LazyListState? = null
        var bounds = androidx.compose.ui.geometry.Rect.Zero
        var clicks = 0
        var selected = -1
        fun show(enhanced: Boolean, trailingControl: Boolean = false) {
            main { host.setContent { ClashOfMusicTheme {
                val list = rememberLazyListState()
                state = list
                val keys = (0 until 500).map { it.toString() }.toSet()
                val taps = if (enhanced) Modifier.queueTrackTaps(list, keys, if (trailingControl) keys else emptySet(), endContentPadding = if (trailingControl) 24.dp else 0.dp) {
                    clicks++; selected = it.toInt()
                } else Modifier
                LazyColumn(Modifier.fillMaxSize().padding(top = 90.dp, bottom = 70.dp)
                    .onGloballyPositioned { bounds = it.boundsInWindow() }.then(taps), state = list,
                    contentPadding = PaddingValues(horizontal = if (trailingControl) 24.dp else 0.dp)) {
                    items(500, key = { it.toString() }) { index ->
                        val action = { clicks++; selected = index; Unit }
                        val touch = Modifier.clickable(onClick = action)
                        Text("Pista privada $index", touch.fillMaxWidth().height(72.dp).padding(12.dp))
                    }
                }
            } } }
            Thread.sleep(600)
            clicks = 0; selected = -1
        }
        fun fling() {
            val area = main { bounds }
            val start = SystemClock.uptimeMillis()
            val x = area.center.x
            val y = area.top + area.height * .8f
            event(MotionEvent.ACTION_DOWN, start, x, y)
            repeat(8) { n ->
                SystemClock.sleep(16)
                event(MotionEvent.ACTION_MOVE, start, x, y - area.height * .48f * (n + 1) / 8)
            }
            event(MotionEvent.ACTION_UP, start, x, y - area.height * .48f)
            SystemClock.sleep(30)
            check(main { state!!.isScrollInProgress }) { "The touch must start during actual inertia" }
        }
        fun touch(drag: Boolean, trailingControl: Boolean = false) {
            val area = main { bounds }
            val x = if (trailingControl) area.right - 70 * instrumentation.targetContext.resources.displayMetrics.density else area.center.x
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, start, x, area.center.y)
            SystemClock.sleep(40)
            val beforeDrag = main { state!!.firstVisibleItemIndex * 100000 + state!!.firstVisibleItemScrollOffset }
            if (drag) {
                event(MotionEvent.ACTION_MOVE, start, x, area.center.y - 160)
                SystemClock.sleep(40)
            }
            event(MotionEvent.ACTION_UP, start, x, area.center.y - if (drag) 160 else 0)
            Thread.sleep(180)
            if (drag) {
                check(main { state!!.firstVisibleItemIndex * 100000 + state!!.firstVisibleItemScrollOffset > beforeDrag }) {
                    "Dragging after stopping inertia must continue scrolling"
                }
            }
        }
        show(false); fling(); touch(false)
        report.put("baselineFirstTapDuringFlingActivations", main { clicks })
        show(true); fling(); touch(false)
        check(main { clicks == 1 && selected >= 0 }) { "First tap must select exactly once: $clicks/$selected" }
        checks.put("first-physical-tap-during-queue-inertia-selects-once")
        Thread.sleep(200)
        touch(false)
        check(main { clicks == 2 }) { "Resting tap must select once" }
        checks.put("resting-queue-tap-still-selects-once")
        show(true); fling(); touch(true)
        check(main { clicks == 0 }) { "Dragging a moving queue must not select a track" }
        checks.put("dragging-moving-queue-does-not-select-a-track")
        show(true, trailingControl = true); fling(); touch(false, trailingControl = true)
        check(main { clicks == 0 }) { "A touch in the remove-control area must not play the track" }
        checks.put("trailing-remove-control-is-not-interpreted-as-playback-during-inertia")
    }
}
