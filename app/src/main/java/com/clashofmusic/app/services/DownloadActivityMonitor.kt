package com.clashofmusic.app.services

import android.content.Context
import com.clashofmusic.app.domain.repository.DownloadRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DownloadActivityMonitor(
    context: Context,
    repository: DownloadRepository,
    visibility: StateFlow<Boolean>,
    preferenceName: String = "download_activity",
    private val notifier: DownloadCompletionNotifier = DownloadCompletionNotifier(context)
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val reducer = DownloadActivityState(preferences.getBoolean("unseen_completion", false))
    private val mutableBadge = MutableStateFlow(reducer.badge)
    val badge = mutableBadge.asStateFlow()
    private val viewingDownloads = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            combine(repository.observeTasks().map { tasks -> tasks.map { DownloadPhase(it.id, it.status) } }.distinctUntilChanged(),
                repository.observeBatchPreparation(), visibility, viewingDownloads) { tasks, preparing, visible, viewing ->
                val old = reducer.badge
                val outcome = reducer.update(tasks, preparing, visible && viewing)
                if (old.unseenCompletion != reducer.badge.unseenCompletion) {
                    preferences.edit().putBoolean("unseen_completion", reducer.badge.unseenCompletion).apply()
                }
                mutableBadge.value = reducer.badge
                if (visible && viewing) notifier.clear()
                if (outcome != null && outcome.completed > 0 && !visibility.value) notifier.show(outcome)
            }.collect()
        }
    }

    fun viewingDownloads(viewing: Boolean) { viewingDownloads.value = viewing }
    fun close() { scope.cancel() }
}
