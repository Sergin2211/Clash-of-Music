package com.clashofmusic.app.data.local

import com.clashofmusic.app.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestedAudioFormatStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val song = DownloadedSong("video", "video", "Private song", "Artist", "Album", 200, null,
        "/private/audio.m4a", "private", "M4A", "124k", 1000, 100)
    @Test fun originalM4aIsRecognizedAfterRestartWithoutFakingItsContainer() {
        val file = directory.newFile("formats.json")
        RequestedAudioFormatStore(file).record(song, AudioFormat.ORIGINAL)
        assertEquals(AudioFormat.ORIGINAL, RequestedAudioFormatStore(file).get(song))
        assertEquals("M4A", song.format)
    }
    @Test fun replacementAndMovedFilesCannotReuseStaleOriginalState() {
        val store = RequestedAudioFormatStore(directory.newFile("formats.json"))
        store.record(song, AudioFormat.ORIGINAL)
        assertNull(store.get(song.copy(downloadedAt = 101)))
        assertNull(store.get(song.copy(filePath = "/other/file.m4a")))
        assertNull(store.get(song.copy(fileSizeBytes = 1001)))
        store.record(song.copy(downloadedAt = 102), AudioFormat.M4A)
        assertEquals(AudioFormat.M4A, store.get(song.copy(downloadedAt = 102)))
    }
    @Test fun damagedAuxiliaryFileDoesNotMakeTheLibraryUnreadable() {
        val file = directory.newFile("formats.json").apply { writeText("broken") }
        assertNull(RequestedAudioFormatStore(file).get(song))
        RequestedAudioFormatStore(file).record(song, AudioFormat.OPUS)
        assertEquals(AudioFormat.OPUS, RequestedAudioFormatStore(file).get(song))
    }
}
