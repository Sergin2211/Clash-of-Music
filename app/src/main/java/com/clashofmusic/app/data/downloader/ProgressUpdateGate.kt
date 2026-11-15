package com.clashofmusic.app.data.downloader

/** Coalesces telemetry, never transfers, checkpoints or control/state transitions. */
internal class ProgressUpdateGate(
    private val intervalNanos: Long,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private var lastEmission: Long? = null

    @Synchronized
    fun allow(force: Boolean = false): Boolean {
        val now = nowNanos()
        val last = lastEmission
        if (!force && last != null && now - last < intervalNanos) return false
        lastEmission = now
        return true
    }
}
