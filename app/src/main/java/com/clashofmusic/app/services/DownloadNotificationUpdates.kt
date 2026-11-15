package com.clashofmusic.app.services

import com.clashofmusic.app.data.downloader.ProgressUpdateGate
import com.clashofmusic.app.domain.model.DownloadTask

/** Notification IPC needs less frequent progress than the visible download row. */
internal class DownloadNotificationUpdates(nowNanos: () -> Long = System::nanoTime) {
    private val gate = ProgressUpdateGate(1_000_000_000L, nowNanos)
    private var previous: DownloadTask? = null

    fun shouldNotify(task: DownloadTask): Boolean {
        val old = previous
        val transition = old == null || old.id != task.id || old.status != task.status || old.statusText != task.statusText
        if (old == task || !gate.allow(force = transition)) return false
        previous = task
        return true
    }
}
