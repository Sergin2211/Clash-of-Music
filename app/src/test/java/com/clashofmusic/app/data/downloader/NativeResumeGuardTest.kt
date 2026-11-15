package com.clashofmusic.app.data.downloader

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeResumeGuardTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun verifiedPartialSurvivesNewGuardButChangedSourceStartsFresh() {
        val directory = File(temp.root, "native")
        val guard = NativeResumeGuard(directory, "version-1")
        File(directory, "source.webm.part").writeText("audio bytes")
        guard.save()
        NativeResumeGuard(directory, "version-1")
        assertEquals("audio bytes", File(directory, "source.webm.part").readText())
        NativeResumeGuard(directory, "version-2")
        assertFalse(File(directory, "source.webm.part").exists())
    }

    @Test fun corruptLocalPartialCannotBeResumedEvenIfItsSizeMatches() {
        val directory = File(temp.root, "native")
        val guard = NativeResumeGuard(directory, "same-source")
        val partial = File(directory, "source.webm.part").apply { writeText("abc") }
        guard.save(); partial.writeText("xyz")
        NativeResumeGuard(directory, "same-source")
        assertFalse(partial.exists())
    }

    @Test fun uncheckpointedOrUnverifiableSourceIsRestarted() {
        val directory = File(temp.root, "native")
        NativeResumeGuard(directory, "source")
        val partial = File(directory, "source.webm.part").apply { writeText("crash") }
        NativeResumeGuard(directory, "source")
        assertFalse(partial.exists())
        File(directory, "source.webm.part").writeText("unverified")
        NativeResumeGuard(directory, null)
        assertFalse(partial.exists())
    }
}
