package com.clashofmusic.app.services

import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadTask
import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationUpdatesTest {
    @Test fun speedAndProgressBurstsDoNotRebuildNotificationEveryFrame() {
        var now = 0L
        val updates = DownloadNotificationUpdates { now }
        val task = DownloadTask("a", "a", "Song", "Artist", url = "test", status = DownloadStatus.DOWNLOADING)
        assertTrue(updates.shouldNotify(task))
        var emissions = 0
        repeat(1000) { index ->
            now = (index + 1L) * 1_000_000L
            if (updates.shouldNotify(task.copy(progress = (index + 1) / 20f, downloadSpeed = "$index"))) emissions++
        }
        assertEquals(1, emissions)
        assertTrue(updates.shouldNotify(task.copy(status = DownloadStatus.PROCESSING)))
        assertTrue(updates.shouldNotify(task.copy(id = "b")))
        assertTrue(updates.shouldNotify(task.copy(id = "b", statusText = "Validating")))
    }

    @Test fun identicalSnapshotsDoNotNotifyAgain() {
        var now = 0L
        val updates = DownloadNotificationUpdates { now }
        val task = DownloadTask("a", "a", "Song", "Artist", url = "test")
        assertTrue(updates.shouldNotify(task))
        now = 5_000_000_000L
        assertFalse(updates.shouldNotify(task))
        assertTrue(updates.shouldNotify(task.copy(progress = 10f)))
    }
}
