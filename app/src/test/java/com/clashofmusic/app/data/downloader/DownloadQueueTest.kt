package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    @Test fun cancelling400TasksIsAtomicAndDoesNotStartAnotherOldWorker() = runTest {
        val completed = task("completed", DownloadStatus.COMPLETED).copy(destinationPath = "/music/kept.mp3", progress = 100f)
        val failed = task("failed", DownloadStatus.FAILED)
        val store = MemoryStore()
        var saves = 0
        val countingStore = object : DownloadTaskStore {
            override fun load() = store.load()
            override fun save(tasks: List<DownloadTask>) { saves++; store.save(tasks) }
        }
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        lateinit var attempt: DownloadQueue.Attempt
        val queue = DownloadQueue(backgroundScope, countingStore, listOf(completed, failed, task("paused", DownloadStatus.PAUSED)),
            worker = { current ->
                started += current.task.id; attempt = current
                try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
            }, discardPartial = { discarded += it.id })
        queue.enqueue(task("active")); runCurrent()
        repeat(399) { queue.enqueue(task("queued-$it")) }
        val before = saves
        queue.cancelAll(); runCurrent()
        assertEquals(before + 1, saves)
        assertEquals(401, queue.tasks.value.count { it.status == DownloadStatus.CANCELLED })
        assertEquals(completed, queue.tasks.value.first { it.id == completed.id })
        assertEquals(failed, queue.tasks.value.first { it.id == failed.id })
        assertFalse("Do not remove a live partial before its worker exits", "active" in discarded)
        attempt.update { it.copy(status = DownloadStatus.COMPLETED) }
        assertEquals(DownloadStatus.CANCELLED, queue.tasks.value.first { it.id == "active" }.status)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("active"), started)
        assertEquals(401, discarded.size)
        assertFalse("completed" in discarded)
        val recovered = DownloadQueue(backgroundScope, MemoryStore(), store.saved, worker = { error("Must not restart") })
        runCurrent(); assertFalse(recovered.tasks.value.any { it.isActive })
    }

    @Test fun cancelAllLetsFinalPublicationFinishAndPreservesCompletedFiles() = runTest {
        val finished = task("finished", DownloadStatus.COMPLETED)
        val release = CompletableDeferred<Unit>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), listOf(finished), worker = { attempt ->
            attempt.commit { release.await(); attempt.update { it.copy(status = DownloadStatus.COMPLETED) } }
        })
        queue.enqueue(task("saving")); runCurrent(); queue.enqueue(task("pending"))
        queue.cancelAll()
        assertEquals(DownloadStatus.SAVING, queue.tasks.value.first { it.id == "saving" }.status)
        assertEquals(DownloadStatus.CANCELLED, queue.tasks.value.first { it.id == "pending" }.status)
        release.complete(Unit); runCurrent()
        assertEquals(2, queue.tasks.value.count { it.status == DownloadStatus.COMPLETED })
        assertEquals(finished, queue.tasks.value.first { it.id == "finished" })
    }

    @Test fun failedBulkPersistenceDoesNotPretendCancellationOrDeletePartialData() = runTest {
        val store = MemoryStore()
        val discarded = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, store, emptyList(), worker = { awaitCancellation() }, discardPartial = { discarded += it.id })
        queue.enqueue(task("active")); queue.enqueue(task("queued")); runCurrent()
        val before = queue.tasks.value
        store.fail = true
        assertThrows(IllegalStateException::class.java) { queue.cancelAll() }
        assertEquals(before, queue.tasks.value)
        assertTrue(discarded.isEmpty())
        store.fail = false; queue.cancelAll(); runCurrent()
        assertTrue(queue.tasks.value.all { it.status == DownloadStatus.CANCELLED })
    }

    @Test fun cancelAllBeforeWorkerDispatchStillAllowsNewExplicitDownloads() = runTest {
        val started = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = { started += it.task.id; awaitCancellation() })
        queue.enqueue(task("old")); queue.cancelAll(); runCurrent()
        queue.enqueue(task("new")); runCurrent()
        assertEquals(listOf("new"), started)
    }

    @Test fun progressBurstIsBoundedButPhaseFailureAndPauseStayImmediate() = runTest {
        var now = 0L
        lateinit var attempt: DownloadQueue.Attempt
        val store = MemoryStore()
        val queue = DownloadQueue(backgroundScope, store, emptyList(), worker = {
            attempt = it
            it.update { task -> task.copy(status = DownloadStatus.DOWNLOADING, statusText = "Downloading") }
            awaitCancellation()
        }, nowNanos = { now })
        val seen = mutableListOf<DownloadTask>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            queue.tasks.collect { tasks -> tasks.singleOrNull()?.let(seen::add) }
        }
        queue.enqueue(task("a")); runCurrent()
        val initialCount = seen.size
        repeat(1000) { index ->
            now = (index + 1L) * 1_000_000L
            attempt.update { it.copy(progress = (index + 1) / 20f, downloadSpeed = "$index MB/s") }
        }
        assertEquals(4, seen.size - initialCount)
        assertEquals(50f, queue.tasks.value.single().progress)
        attempt.update { it.copy(status = DownloadStatus.PROCESSING, statusText = "Converting") }
        assertEquals(DownloadStatus.PROCESSING, queue.tasks.value.single().status)
        queue.pause("a")
        assertEquals(DownloadStatus.PAUSED, store.saved.single().status)
        attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f) }
        assertEquals(DownloadStatus.PAUSED, queue.tasks.value.single().status)
        queue.resume("a"); runCurrent()
        attempt.update { it.copy(status = DownloadStatus.FAILED, errorMessage = "test") }
        assertEquals(DownloadStatus.FAILED, store.saved.single().status)
    }

    @Test fun completionWithinProgressWindowIsImmediatelyPersisted() = runTest {
        var now = 0L
        lateinit var attempt: DownloadQueue.Attempt
        val store = MemoryStore()
        val queue = DownloadQueue(backgroundScope, store, emptyList(), worker = {
            attempt = it
            it.update { task -> task.copy(status = DownloadStatus.DOWNLOADING) }
            awaitCancellation()
        }, nowNanos = { now })
        queue.enqueue(task("a")); runCurrent()
        now = 1L
        attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f) }
        assertEquals(100f, queue.tasks.value.single().progress)
        assertEquals(DownloadStatus.COMPLETED, store.saved.single().status)
    }

    private fun task(id: String, status: DownloadStatus = DownloadStatus.QUEUED) =
        DownloadTask(id, id, id, "Artist", url = "https://example.org/$id", status = status, createdAt = id.hashCode().toLong())
    private class MemoryStore : DownloadTaskStore {
        var saved = emptyList<DownloadTask>()
        var fail = false
        override fun load() = saved
        override fun save(tasks: List<DownloadTask>) { check(!fail); saved = tasks.toList() }
    }

    @Test fun pauseAndImmediateResumeWaitForOldWorkerToFinishAndRejectLateProgress() = runTest {
        val release = CompletableDeferred<Unit>()
        val attempts = mutableListOf<DownloadQueue.Attempt>()
        var concurrent = 0
        var peak = 0
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = { attempt ->
            attempts.add(attempt); concurrent++; peak = maxOf(peak, concurrent)
            try { withContext(NonCancellable) { release.await() } } finally { concurrent-- }
            attempt.check()
            attempt.update { it.copy(status = DownloadStatus.COMPLETED) }
        })
        queue.enqueue(task("a")); runCurrent()
        val old = attempts.single()
        queue.pause("a"); queue.resume("a"); runCurrent()
        assertEquals(DownloadStatus.QUEUED, queue.tasks.value.single().status)
        assertEquals(1, attempts.size)
        old.update { it.copy(status = DownloadStatus.FAILED, progress = 100f) }
        assertEquals(DownloadStatus.QUEUED, queue.tasks.value.single().status)
        release.complete(Unit); runCurrent()
        assertEquals(2, attempts.size)
        assertNotEquals(old.id, attempts.last().id)
        assertEquals(1, peak)
        assertEquals(DownloadStatus.COMPLETED, queue.tasks.value.single().status)
    }

    @Test fun cancellingRunningTaskKeepsItsSlotUntilCleanupAndThenStartsNext() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        lateinit var first: DownloadQueue.Attempt
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = {
            started.add(it.task.id)
            if (it.task.id == "a") { first = it; withContext(NonCancellable) { cleanup.await() }; it.check() }
            it.update { current -> current.copy(status = DownloadStatus.COMPLETED) }
        })
        queue.enqueue(task("a")); queue.enqueue(task("b")); runCurrent()
        queue.cancel("a"); runCurrent()
        assertEquals(listOf("a"), started)
        first.update { it.copy(status = DownloadStatus.FAILED) }
        assertEquals(DownloadStatus.CANCELLED, queue.tasks.value.first { it.id == "a" }.status)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun cancellingBeforeWorkerDispatchDoesNotLeaveAReservedSlot() = runTest {
        val started = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = {
            started.add(it.task.id); it.update { task -> task.copy(status = DownloadStatus.COMPLETED) }
        })
        queue.enqueue(task("a")); queue.cancel("a"); queue.enqueue(task("b")); runCurrent()
        assertEquals(listOf("b"), started)
    }

    @Test fun deletingRunningTaskCannotResurrectItFromCallbacks() = runTest {
        val release = CompletableDeferred<Unit>()
        lateinit var attempt: DownloadQueue.Attempt
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = {
            attempt = it; withContext(NonCancellable) { release.await() }
        })
        queue.enqueue(task("a")); runCurrent(); queue.delete("a")
        attempt.update { it.copy(status = DownloadStatus.COMPLETED) }
        release.complete(Unit); runCurrent()
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun duplicateEnqueueAndRepeatedResumeNeverStartConcurrentAttempts() = runTest {
        val started = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = {
            started.add(it.id); awaitCancellation()
        })
        val id = queue.enqueue(task("a"))
        assertEquals(id, queue.enqueue(task("another").copy(songId = "a")))
        queue.resume(id); queue.resume(id, true); runCurrent()
        assertEquals(1, started.size)
        assertEquals(1, queue.tasks.value.size)
    }

    @Test fun queuedPauseDoesNotCancelTheOtherActiveTask() = runTest {
        val stopped = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = { awaitCancellation() }, stopNative = { stopped.add(it) })
        queue.enqueue(task("a")); queue.enqueue(task("b")); runCurrent(); queue.pause("b"); runCurrent()
        assertTrue(stopped.isEmpty())
        assertEquals(DownloadStatus.PREPARING, queue.tasks.value.first { it.id == "a" }.status)
        assertEquals(DownloadStatus.PAUSED, queue.tasks.value.first { it.id == "b" }.status)
    }

    @Test fun publicationCannotBeCancelledOrDeletedHalfwayThrough() = runTest {
        val release = CompletableDeferred<Unit>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = { attempt ->
            attempt.commit { release.await(); attempt.update { it.copy(status = DownloadStatus.COMPLETED) } }
        })
        queue.enqueue(task("a")); runCurrent()
        assertEquals(DownloadStatus.SAVING, queue.tasks.value.single().status)
        queue.pause("a"); queue.cancel("a"); queue.delete("a"); queue.resume("a")
        assertEquals(DownloadStatus.SAVING, queue.tasks.value.single().status)
        release.complete(Unit); runCurrent()
        assertEquals(DownloadStatus.COMPLETED, queue.tasks.value.single().status)
    }

    @Test fun restoredRunningAndQueuedTasksArePausedWithoutAutomaticNetworkWork() = runTest {
        var executions = 0
        val tasks = DownloadStatus.entries.map { task(it.name, it) }
        val queue = DownloadQueue(backgroundScope, MemoryStore(), tasks, worker = { executions++ })
        runCurrent()
        tasks.forEach { old ->
            val restored = queue.tasks.value.first { it.id == old.id }
            assertEquals(if (old.isRunning || old.status == DownloadStatus.QUEUED) DownloadStatus.PAUSED else old.status, restored.status)
        }
        assertEquals(0, executions)
    }

    @Test fun failedAttemptDoesNotBlockNextAndRetryIsExplicit() = runTest {
        var count = 0
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = {
            count++
            if (it.task.id == "a") error("network")
            it.update { current -> current.copy(status = DownloadStatus.COMPLETED) }
        })
        queue.enqueue(task("a")); queue.enqueue(task("b")); runCurrent()
        assertEquals(2, count)
        assertEquals(DownloadStatus.FAILED, queue.tasks.value.first { it.id == "a" }.status)
        queue.resume("a"); runCurrent(); assertEquals(2, count)
        queue.resume("a", true); runCurrent(); assertEquals(3, count)
    }

    @Test fun persistenceFailureDoesNotStartUnrecordedWork() = runTest {
        val store = MemoryStore().apply { fail = true }
        var executions = 0
        val queue = DownloadQueue(backgroundScope, store, emptyList(), worker = { executions++ })
        assertThrows(IllegalStateException::class.java) { queue.enqueue(task("a")) }
        runCurrent()
        assertEquals(0, executions)
        assertTrue(queue.tasks.value.isEmpty())
    }

    @Test fun idReuseDoesNotOverwriteCompletedHistoryOrOldExecutionIdentity() = runTest {
        val queue = DownloadQueue(backgroundScope, MemoryStore(), listOf(task("a", DownloadStatus.COMPLETED)), worker = { awaitCancellation() })
        val newId = queue.enqueue(task("a"))
        assertNotEquals("a", newId)
        assertEquals(2, queue.tasks.value.size)
    }

    @Test fun pauseKeepsProgressAndPartialsButCancelDiscardsBeforeImmediateRetry() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var count = 0
        val queue = DownloadQueue(backgroundScope, MemoryStore(), emptyList(), worker = { attempt ->
            events.add("start-${++count}")
            attempt.update { it.copy(progress = 37f) }
            if (count == 1) withContext(NonCancellable) { cleanup.await() }
            attempt.check()
            awaitCancellation()
        }, discardPartial = { events.add("discard") })
        queue.enqueue(task("a")); runCurrent()
        queue.pause("a")
        assertEquals(37f, queue.tasks.value.single().progress)
        assertEquals(listOf("start-1"), events)
        queue.cancel("a"); queue.resume("a", retry = true); runCurrent()
        assertEquals(listOf("start-1"), events)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf("start-1", "discard", "start-2"), events)
    }

    @Test fun restoredProgressIsRetainedUntilWorkerValidatesCheckpoint() = runTest {
        val queue = DownloadQueue(backgroundScope, MemoryStore(), listOf(task("a", DownloadStatus.DOWNLOADING).copy(progress = 42f)),
            worker = { awaitCancellation() })
        assertEquals(DownloadStatus.PAUSED, queue.tasks.value.single().status)
        queue.resume("a"); runCurrent()
        assertEquals(42f, queue.tasks.value.single().progress)
    }

    @Test fun successAndRemovingFailedHistoryCleanTheirOwnedPartials() = runTest {
        val removed = mutableListOf<String>()
        val queue = DownloadQueue(backgroundScope, MemoryStore(), listOf(task("failed", DownloadStatus.FAILED)),
            worker = { it.update { row -> row.copy(status = DownloadStatus.COMPLETED) } },
            discardPartial = { removed.add(it.id) })
        queue.enqueue(task("done")); runCurrent()
        assertEquals(listOf("done"), removed)
        queue.clearFinished()
        assertTrue("failed" in removed)
        assertTrue(queue.tasks.value.isEmpty())
    }
}
