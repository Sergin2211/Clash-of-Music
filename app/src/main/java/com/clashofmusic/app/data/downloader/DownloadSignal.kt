package com.clashofmusic.app.data.downloader

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A cancellation request remains visible even before a native process or HTTP call starts. */
class DownloadSignal {
    val cancelled = AtomicBoolean(false)
    private val lock = Any()
    private val actions = mutableSetOf<() -> Unit>()
    fun check() { if (cancelled.get()) throw CancellationException("Descarga detenida") }
    fun onCancel(action: () -> Unit): () -> Unit {
        synchronized(lock) {
            if (cancelled.get()) action() else actions.add(action)
        }
        return { synchronized(lock) { actions.remove(action) }; Unit }
    }
    fun cancel() {
        val pending = synchronized(lock) {
            if (!cancelled.compareAndSet(false, true)) return
            actions.toList().also { actions.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}

/** Native yt-dlp invokes progress on its own thread: exceptions must never escape it. */
internal class SafeDownloadProgress(private val signal: DownloadSignal,
    private val statusText: String = "Descargando audio...",
    private val callback: (Float, Long, String?, String) -> Unit) {
    private val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
    private val progressGate = ProgressUpdateGate(100_000_000L)
    fun emit(progress: Float, eta: Long) {
        if (signal.cancelled.get() || failure.get() != null) return
        if (!progressGate.allow(force = progress >= 100f)) return
        try { callback(progress.coerceIn(0f, 100f) * 0.8f, eta.coerceAtLeast(0), null, statusText) }
        catch (e: Exception) { failure.compareAndSet(null, e) }
    }
    fun throwIfFailed() { failure.get()?.let { throw it } }
}
