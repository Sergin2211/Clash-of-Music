package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One owned attempt at a time. Stopping never releases the slot before the worker exits. */
internal class DownloadQueue(
    private val scope: CoroutineScope,
    private val store: DownloadTaskStore,
    initial: List<DownloadTask>,
    private val worker: suspend (Attempt) -> Unit,
    private val stopNative: (String) -> Unit = {},
    private val discardPartial: (DownloadTask) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime
) {
    private val lock = Any()
    private val mutableTasks = MutableStateFlow(initial.map { task ->
        if (task.isRunning || task.status == DownloadStatus.QUEUED) task.copy(status = DownloadStatus.PAUSED,
            statusText = "Interrumpida al cerrar la app. Reanuda para recuperar el avance compatible.", downloadSpeed = null)
        else task
    }.sortedByDescending { it.createdAt })
    val tasks = mutableTasks.asStateFlow()
    private var active: Attempt? = null
    private var lastProgressSave = 0L

    inner class Attempt internal constructor(val task: DownloadTask) {
        val id = UUID.randomUUID().toString()
        val signal = DownloadSignal()
        internal lateinit var job: Job
        internal var committing = false
        internal var discardOnExit = false
        private val progressGate = ProgressUpdateGate(250_000_000L, nowNanos)
        fun check() { signal.check() }
        fun update(block: (DownloadTask) -> DownloadTask) = synchronized(lock) {
            if (active !== this || signal.cancelled.get()) return@synchronized
            val current = mutableTasks.value.firstOrNull { it.id == task.id } ?: return@synchronized
            if (!current.isRunning) return@synchronized
            val changed = block(current)
            if (changed == current) return@synchronized
            if (!progressGate.allow(force = changed.status != current.status || changed.statusText != current.statusText ||
                    changed.progress >= 100f && current.progress < 100f)) return@synchronized
            val now = nowNanos()
            val save = changed.status != current.status || now - lastProgressSave > 1_000_000_000L
            if (save) lastProgressSave = now
            publish(mutableTasks.value.map { if (it.id == task.id) changed else it }, save)
        }
        suspend fun <T> commit(block: suspend () -> T): T {
            synchronized(lock) {
                check()
                check(active === this && mutableTasks.value.any { it.id == task.id && it.isRunning })
                // Publication and Room commit cannot race a pause/cancel promise.
                update { it.copy(status = DownloadStatus.SAVING, statusText = "Guardando archivo verificado...", downloadSpeed = null) }
                committing = true
            }
            return withContext(NonCancellable) { block() }
        }
    }

    private fun publish(tasks: List<DownloadTask>, persist: Boolean = true) {
        if (persist) store.save(tasks)
        mutableTasks.value = tasks
    }

    fun enqueue(task: DownloadTask): String = synchronized(lock) {
        mutableTasks.value.firstOrNull { it.songId == task.songId && it.isActive }?.let { return it.id }
        // Caller-provided IDs are not execution identities, and cannot replace a live task.
        val id = if (mutableTasks.value.any { it.id == task.id } || active?.task?.id == task.id) UUID.randomUUID().toString() else task.id
        val queued = task.copy(id = id, status = DownloadStatus.QUEUED, progress = 0f, statusText = "En cola", errorMessage = null)
        publish((mutableTasks.value + queued).sortedByDescending { it.createdAt })
        pump()
        id
    }

    fun pause(id: String) = synchronized(lock) {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized
        if (!task.isRunning && task.status != DownloadStatus.QUEUED || task.status == DownloadStatus.SAVING) return@synchronized
        publish(mutableTasks.value.map { if (it.id == id) it.copy(status = DownloadStatus.PAUSED,
            statusText = "Pausada. Se conservará el avance compatible para continuar.", downloadSpeed = null) else it })
        stopAttempt(id)
        pump()
    }

    fun cancel(id: String) = synchronized(lock) {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized
        if (!task.isActive || task.status == DownloadStatus.SAVING) return@synchronized
        publish(mutableTasks.value.map { if (it.id == id) it.copy(status = DownloadStatus.CANCELLED,
            statusText = "Cancelada", errorMessage = null, downloadSpeed = null) else it })
        stopAttempt(id)
        discardWhenIdle(task)
        pump()
    }

    fun cancelAll() = synchronized(lock) {
        val cancelled = mutableTasks.value.filter { it.isActive && it.status != DownloadStatus.SAVING }
        if (cancelled.isEmpty()) return@synchronized
        val ids = cancelled.mapTo(hashSetOf()) { it.id }
        // Persist the entire batch before stopping the worker: release() must never start another old task.
        publish(mutableTasks.value.map { if (it.id in ids) it.copy(status = DownloadStatus.CANCELLED,
            statusText = "Cancelada", errorMessage = null, downloadSpeed = null, etaSeconds = 0) else it })
        // Stop transfer immediately; cleaning many old partial directories may take longer.
        cancelled.forEach { stopAttempt(it.id) }
        cancelled.forEach(::discardWhenIdle)
    }

    fun resume(id: String, retry: Boolean = false) = synchronized(lock) {
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized
        if (task.url.isBlank() || task.status !in if (retry) setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)
            else setOf(DownloadStatus.PAUSED)) return@synchronized
        publish(mutableTasks.value.map { if (it.id == id) it.copy(status = DownloadStatus.QUEUED,
            progress = if (task.status == DownloadStatus.CANCELLED) 0f else it.progress.coerceAtMost(79f),
            statusText = "En cola para continuar...", errorMessage = null, downloadSpeed = null, etaSeconds = 0) else it })
        pump()
    }

    fun delete(id: String) = synchronized(lock) {
        if (mutableTasks.value.any { it.id == id && it.status == DownloadStatus.SAVING }) return@synchronized
        val task = mutableTasks.value.firstOrNull { it.id == id }
        publish(mutableTasks.value.filterNot { it.id == id })
        stopAttempt(id)
        task?.let(::discardWhenIdle)
        pump()
    }
    fun clearFinished() = synchronized(lock) {
        val removed = mutableTasks.value.filterNot { it.isActive }
        publish(mutableTasks.value.filter { it.isActive })
        removed.forEach(::discardWhenIdle)
    }

    private fun discardWhenIdle(task: DownloadTask) {
        val attempt = active?.takeIf { it.task.id == task.id }
        if (attempt != null) attempt.discardOnExit = true
        else runCatching { discardPartial(task) }.onFailure(onError)
    }

    private fun release(attempt: Attempt) = synchronized(lock) {
        if (active !== attempt) return@synchronized
        if (attempt.discardOnExit || mutableTasks.value.none { it.id == attempt.task.id } ||
            mutableTasks.value.any { it.id == attempt.task.id && it.status == DownloadStatus.COMPLETED }) {
            runCatching { discardPartial(attempt.task) }.onFailure(onError)
        }
        active = null
        pump()
    }

    fun reportFailure(task: DownloadTask, error: Exception) = synchronized(lock) {
        stopAttempt(task.id)
        mutableTasks.value = mutableTasks.value.filterNot { it.id == task.id } + task.copy(status = DownloadStatus.FAILED,
            statusText = "No se pudo guardar el estado de la descarga", errorMessage = error.message)
    }

    private fun stopAttempt(taskId: String) {
        active?.takeIf { it.task.id == taskId && !it.committing }?.let {
            it.signal.cancel(); it.job.cancel()
            scope.launch { runCatching { stopNative(it.id) } }
        }
    }

    private fun pump() {
        if (active != null) return
        val next = mutableTasks.value.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.createdAt } ?: return
        val attempt = Attempt(next)
        publish(mutableTasks.value.map { if (it.id == next.id) it.copy(status = DownloadStatus.PREPARING, statusText = "Preparando...") else it })
        active = attempt
        attempt.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                attempt.check()
                worker(attempt)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                onError(e)
                synchronized(lock) {
                    if (active === attempt && !attempt.signal.cancelled.get()) {
                        val updated = mutableTasks.value.map { if (it.id == next.id) it.copy(status = DownloadStatus.FAILED,
                            statusText = "Error en la descarga", errorMessage = e.message, downloadSpeed = null) else it }
                        try { publish(updated) } catch (saveError: Exception) { onError(saveError); mutableTasks.value = updated }
                    }
                }
            } finally {
                release(attempt)
            }
        }
        // Even a lazy job cancelled before dispatch must release its reserved slot.
        attempt.job.invokeOnCompletion {
            release(attempt)
        }
        attempt.job.start()
    }
}
