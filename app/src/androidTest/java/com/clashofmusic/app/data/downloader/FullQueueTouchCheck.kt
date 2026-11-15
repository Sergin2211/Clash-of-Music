package com.clashofmusic.app.data.downloader

import android.app.Instrumentation
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clashofmusic.app.di.ServiceLocator
import org.json.JSONArray
import org.json.JSONObject

/** Real pointer sequences through the complete player sheet, not accessibility ACTION_CLICK. */
internal class FullQueueTouchCheck(private val instrumentation: Instrumentation) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun event(action: Int, start: Long, x: Float, y: Float) {
        val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
        motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(motion); motion.recycle()
    }
    private fun headerAtTop(height: Float, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.equals("Reproduciendo ahora", ignoreCase = true) == true && node.isVisibleToUser) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (bounds.top >= 0 && bounds.bottom < height * .24f) return true
        }
        repeat(node.childCount) { if (headerAtTop(height, node.getChild(it))) return true }
        return false
    }
    fun run(checks: JSONArray, report: JSONObject) {
        val shot = instrumentation.uiAutomation.takeScreenshot()!!
        val width = shot.width.toFloat(); val height = shot.height.toFloat(); shot.recycle()
        val x = width * .45f
        fun swipe() {
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, start, x, height * .76f)
            repeat(9) { n ->
                SystemClock.sleep(16)
                event(MotionEvent.ACTION_MOVE, start, x, height * (.76f - .36f * (n + 1) / 9))
            }
            event(MotionEvent.ACTION_UP, start, x, height * .40f)
        }
        val attempts = JSONArray()
        report.put("completeSheetPhysicalTaps", attempts)
        for (delay in listOf(0L, 30L, 90L, 160L)) {
            // Each successful selection now returns to the artwork; expose the queue again.
            Thread.sleep(600)
            repeat(2) { swipe(); Thread.sleep(700) }
            main { ServiceLocator.playerManager.resume(startService = false) }
            val before = main { ServiceLocator.playerManager.currentSong.value?.id }
            swipe(); SystemClock.sleep(delay)
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, start, x, height * .55f)
            SystemClock.sleep(55)
            event(MotionEvent.ACTION_UP, start, x, height * .55f)
            Thread.sleep(350)
            val after = main { ServiceLocator.playerManager.currentSong.value?.id }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!headerAtTop(height) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(80)
            attempts.put(JSONObject().put("delayAfterFlingMs", delay).put("changedOnFirstTap", before != after)
                .put("returnedToPlayerTop", headerAtTop(height)).put("beforeFixture", before).put("afterFixture", after))
        }
        check((0 until attempts.length()).all { attempts.getJSONObject(it).getBoolean("changedOnFirstTap") }) {
            "First physical tap failed in full player: $attempts"
        }
        checks.put("complete-player-first-physical-tap-works-at-four-fling-delays-while-playing")
        check((0 until attempts.length()).all { attempts.getJSONObject(it).getBoolean("returnedToPlayerTop") }) {
            "Selecting a queue track must return to the artwork and controls: $attempts"
        }
        checks.put("queue-selections-return-to-player-artwork-and-controls-at-all-four-fling-delays")
    }
}
