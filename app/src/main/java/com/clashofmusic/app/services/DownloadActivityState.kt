package com.clashofmusic.app.services

import com.clashofmusic.app.domain.model.DownloadStatus

data class DownloadBadgeState(val activeCount: Int = 0, val unseenCompletion: Boolean = false)
internal data class DownloadPhase(val id: String, val status: DownloadStatus)
internal data class DownloadBatchResult(val completed: Int, val failed: Int, val cancelled: Int)

/** Tracks only status changes, never byte/progress updates or historical completed tasks. */
internal class DownloadActivityState(unseen: Boolean = false) {
    var badge = DownloadBadgeState(unseenCompletion = unseen)
        private set
    private val batch = mutableMapOf<String, DownloadStatus>()

    fun update(tasks: List<DownloadPhase>, preparing: Int, viewed: Boolean): DownloadBatchResult? {
        val current = tasks.associate { it.id to it.status }
        tasks.filter { it.status in pending }.forEach { batch[it.id] = it.status }
        batch.keys.toList().forEach { id -> batch[id] = current[id] ?: if (batch[id] in pending) DownloadStatus.CANCELLED else batch.getValue(id) }
        var result: DownloadBatchResult? = null
        if (preparing == 0 && batch.isNotEmpty() && batch.values.none { it in pending }) {
            result = DownloadBatchResult(batch.values.count { it == DownloadStatus.COMPLETED },
                batch.values.count { it == DownloadStatus.FAILED }, batch.values.count { it == DownloadStatus.CANCELLED })
            batch.clear()
        }
        badge = DownloadBadgeState(tasks.count { it.status in running },
            !viewed && (badge.unseenCompletion || (result?.completed ?: 0) > 0))
        return result
    }

    companion object {
        private val running = setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING, DownloadStatus.SAVING)
        private val pending = running + DownloadStatus.PAUSED
    }
}
