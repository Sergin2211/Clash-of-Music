package com.clashofmusic.app.data.downloader

import com.clashofmusic.app.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadTaskStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun task(id: String) = DownloadTask(id, id, "Song", "Artist", album = "Album", url = "https://example.org/$id",
        format = AudioFormat.M4A, quality = AudioQuality.HIGH_256K, status = DownloadStatus.PAUSED,
        progress = 42f, statusText = "Paused", createdAt = 123)
    @Test fun taskChoicesAndStateSurviveANewStoreInstance() {
        val file = File(temp.root, "queue.json")
        FileDownloadTaskStore(file).save(listOf(task("a"), task("b").copy(status = DownloadStatus.COMPLETED, destinationPath = "/Music/b.m4a")))
        assertEquals(listOf(task("a"), task("b").copy(status = DownloadStatus.COMPLETED, destinationPath = "/Music/b.m4a")), FileDownloadTaskStore(file).load())
        assertTrue(temp.root.listFiles()!!.none { it.name.endsWith("pending") })
    }
    @Test fun corruptCurrentSnapshotFallsBackToPreviousValidGeneration() {
        val file = File(temp.root, "queue.json")
        val store = FileDownloadTaskStore(file)
        store.save(listOf(task("a"))); store.save(listOf(task("b")))
        file.writeText("{broken")
        assertEquals(listOf(task("a")), FileDownloadTaskStore(file).load())
    }
    @Test fun whollyCorruptQueueIsNotSilentlyRestoredAsEmpty() {
        val file = File(temp.root, "queue.json").apply { writeText("{broken") }
        assertThrows(IOException::class.java) { FileDownloadTaskStore(file).load() }
        assertEquals("{broken", file.readText())
    }
    @Test fun missingQueueIsAnEmptyFirstInstall() { assertTrue(FileDownloadTaskStore(File(temp.root, "queue.json")).load().isEmpty()) }
    @Test fun artworkFallbackSurvivesPauseAndProcessRestart() {
        val file = File(temp.root, "queue.json")
        val pending = task("album").copy(artworkFallbackUrl = "https://example.org/album.jpg")
        FileDownloadTaskStore(file).save(listOf(pending))
        assertEquals(pending, FileDownloadTaskStore(file).load().single())
    }
}
