package com.clashofmusic.app.data.downloader

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadSignalTest {
    @Test fun cancellationBeforeRegistrationImmediatelyClosesTheLateResource() {
        val signal = DownloadSignal(); signal.cancel()
        var closed = 0
        signal.onCancel { closed++ }
        assertEquals(1, closed)
        assertThrows(CancellationException::class.java) { signal.check() }
    }
    @Test fun unregisterAndRepeatedCancellationDoNotCloseAResourceTwice() {
        val signal = DownloadSignal()
        var closed = 0
        val unregister = signal.onCancel { closed++ }
        unregister(); signal.cancel(); signal.cancel()
        assertEquals(0, closed)
        val another = DownloadSignal()
        another.onCancel { closed++ }; another.cancel(); another.cancel()
        assertEquals(1, closed)
    }
    @Test fun throwingResourceDoesNotPreventOtherResourcesFromClosing() {
        val signal = DownloadSignal(); var closed = false
        signal.onCancel { error("close") }; signal.onCancel { closed = true }; signal.cancel()
        assertTrue(closed)
    }
    @Test fun cancelledNativeProgressDoesNotThrowOnItsExternalThread() {
        val signal = DownloadSignal(); var emitted = 0
        val callback = SafeDownloadProgress(signal) { _, _, _, _ -> emitted++ }
        signal.cancel()
        var failure: Throwable? = null
        val thread = Thread { try { callback.emit(42f, 10) } catch (e: Throwable) { failure = e } }
        thread.start(); thread.join()
        assertNull(failure); assertEquals(0, emitted)
    }
    @Test fun progressErrorsAreReportedOnWorkerInsteadOfCrashingNativeThread() {
        val callback = SafeDownloadProgress(DownloadSignal()) { _, _, _, _ -> throw IOException("disk full") }
        var failure: Throwable? = null
        val thread = Thread { try { callback.emit(42f, 10) } catch (e: Throwable) { failure = e } }
        thread.start(); thread.join()
        assertNull(failure)
        assertThrows(IOException::class.java) { callback.throwIfFailed() }
    }
}
