package com.clashofmusic.app.data.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SafeAudioFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun successfulExportKeepsSourceUntilCallerCommits() {
        val source = temp.newFile("source.mp3").apply { writeBytes(ByteArray(8192) { it.toByte() }) }
        val destination = File(temp.newFolder("music"), "song.mp3")
        SafeAudioFiles.publishCopy(source, destination)
        assertArrayEquals(source.readBytes(), destination.readBytes())
        assertTrue(source.exists())
        assertEquals(listOf("song.mp3"), destination.parentFile!!.list()!!.toList())
    }

    @Test fun interruptedCopyPreservesSourceAndPreviousVersion() {
        val source = temp.newFile("source.mp3").apply { writeText("new audio content") }
        val previous = temp.newFile("song.mp3").apply { writeText("previous audio") }
        val destination = File(temp.root, "song (1).mp3")
        assertThrows(IOException::class.java) {
            SafeAudioFiles.publishCopy(source, destination) { _, staged ->
                staged.writeText("partial")
                throw IOException("Disk full")
            }
        }
        assertEquals("new audio content", source.readText())
        assertEquals("previous audio", previous.readText())
        assertFalse(destination.exists())
        assertFalse(temp.root.list()!!.any { it.endsWith(".pending") })
    }

    @Test fun silentShortCopyIsRejected() {
        val source = temp.newFile("source.mp3").apply { writeText("complete audio") }
        val destination = File(temp.root, "song.mp3")
        assertThrows(IOException::class.java) {
            SafeAudioFiles.publishCopy(source, destination) { _, staged -> staged.writeText("short") }
        }
        assertTrue(source.exists())
        assertFalse(destination.exists())
    }

    @Test fun existingDestinationIsNeverOverwritten() {
        val source = temp.newFile("source.mp3").apply { writeText("new audio") }
        val destination = temp.newFile("song.mp3").apply { writeText("previous audio") }
        assertThrows(IOException::class.java) { SafeAudioFiles.publishCopy(source, destination) }
        assertEquals("previous audio", destination.readText())
        assertEquals("new audio", source.readText())
    }

    @Test fun emptyAudioIsNotPublished() {
        val source = temp.newFile("empty.mp3")
        val destination = File(temp.root, "song.mp3")
        assertThrows(IOException::class.java) { SafeAudioFiles.publishCopy(source, destination) }
        assertFalse(destination.exists())
    }

    @Test fun deletingOneFileLeavesSameNamesAndOtherFormatsUntouched() {
        val selected = File(temp.newFolder("music"), "song.mp3").apply { writeText("selected") }
        val otherFolder = File(temp.newFolder("downloads"), "song.mp3").apply { writeText("other song") }
        val otherFormat = File(selected.parentFile, "song.m4a").apply { writeText("other version") }
        assertTrue(SafeAudioFiles.deleteExact(selected))
        assertFalse(selected.exists())
        assertEquals("other song", otherFolder.readText())
        assertEquals("other version", otherFormat.readText())
    }

    @Test fun directoriesAndRelativePathsAreNotDeleted() {
        val folder = temp.newFolder("keep")
        val child = File(folder, "song.mp3").apply { writeText("keep audio") }
        assertFalse(SafeAudioFiles.deleteExact(folder))
        assertEquals("keep audio", child.readText())
        assertThrows(IllegalArgumentException::class.java) { SafeAudioFiles.deleteExact(File("song.mp3")) }
    }
}
