package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun task(id: String) = DownloadTask(id, id, "Title", "Artist", url = "https://example.org/song")

    @Test fun retriesReuseTheirOwnFilesButChangedSourceDoesNot() {
        val workspace = DownloadWorkspace(temp.root)
        val task = task("a")
        val directory = workspace.acquire(task)
        File(directory, "source.part").writeText("kept")
        assertEquals("kept", File(workspace.acquire(task), "source.part").readText())
        assertFalse(File(workspace.acquire(task.copy(url = "https://example.org/other")), "source.part").exists())
    }

    @Test fun taskIdsCannotEscapeTheirRootAndDiscardCannotDeleteAnotherTask() {
        val workspace = DownloadWorkspace(temp.root)
        val first = task("../../outside")
        val second = task("b")
        val firstDirectory = workspace.acquire(first)
        val otherDirectory = workspace.acquire(second)
        assertEquals(temp.root.canonicalFile, firstDirectory.canonicalFile.parentFile)
        File(otherDirectory, "source.part").writeText("other")
        workspace.discard(first)
        assertFalse(firstDirectory.exists())
        assertEquals("other", File(otherDirectory, "source.part").readText())
    }
}
