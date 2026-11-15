package com.clashofmusic.app.data.media

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.Base64

class MetadataSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.root, "song.mp3").apply { writeText("ORIGINAL AUDIO") }
    @Test fun preparationFailureLeavesOriginalAndDatabaseUntouched() = runBlocking {
        val source = file(); var committed = false
        assertThrows(IOException::class.java) { runBlocking {
            MetadataFileTransaction().replace(source, { it.writeText("partial"); throw IOException("encoder failed") }, { committed = true })
        } }
        assertEquals("ORIGINAL AUDIO", source.readText()); assertFalse(committed)
        assertEquals(listOf("song.mp3"), temp.root.listFiles()!!.map { it.name })
    }
    @Test fun databaseFailureRestoresTheOriginalBytes() = runBlocking {
        val source = file()
        assertThrows(IOException::class.java) { runBlocking {
            MetadataFileTransaction().replace(source, { it.writeText("NEW TAGS + AUDIO") }, { throw IOException("Room failed") })
        } }
        assertEquals("ORIGINAL AUDIO", source.readText())
        assertEquals(1, temp.root.listFiles()!!.size)
    }
    @Test fun successfulCommitSeesPublishedFileAndUpdatesExactSize() = runBlocking {
        val source = file(); var size = 0L
        MetadataFileTransaction().replace(source, { it.writeText("NEW TAGS + AUDIO") }, { assertEquals("NEW TAGS + AUDIO", source.readText()); size = it })
        assertEquals(source.length(), size); assertEquals(1, temp.root.listFiles()!!.size)
    }
    @Test fun cancellationBeforePublicationDoesNotChangeTheOriginal() {
        val source = file()
        assertThrows(CancellationException::class.java) { runBlocking {
            MetadataFileTransaction().replace(source, { it.writeText("staged"); throw CancellationException() }, { error("commit") })
        } }
        assertEquals("ORIGINAL AUDIO", source.readText()); assertEquals(1, temp.root.listFiles()!!.size)
    }
    @Test fun tagsValidateYearAndRequiredFields() {
        assertEquals(AudioTags("Title", "Artist", null, "2024"), AudioTags(" Title ", " Artist ", "  ", " 2024 ").validated())
        listOf("24", "20245", "abcd").forEach { year -> assertThrows(IllegalArgumentException::class.java) { AudioTags("T", "A", null, year).validated() } }
        assertThrows(IllegalArgumentException::class.java) { AudioTags("", "A", null, null).validated() }
    }
    @Test fun allMetadataPlansCopyTheAudioAndMp3UsesCompatibleId3Version() {
        for (ext in listOf("mp3", "m4a", "opus")) {
            val args = MetadataCommand.arguments(File("song.$ext"), File("out.$ext"), AudioTags("ñ 日本語", "Artist", null, "2024"), null, null, true)
            assertEquals("0:a", args[args.indexOf("-map") + 1])
            assertEquals("copy", args[args.indexOf("-c:a") + 1]); assertFalse(args.contains("-b:a"))
            assertTrue(args.contains("album=")); assertTrue(args.contains("date=2024"))
            if (ext == "mp3") assertEquals("3", args[args.indexOf("-id3v2_version") + 1])
        }
    }
    @Test fun opusPictureUsesTheFlacPictureLayoutAndAFileInsteadOfAHugeArgument() {
        val image = byteArrayOf(1, 2, 3)
        val block = Base64.getDecoder().decode(MetadataCommand.pictureBlock(image, "image/png", 64, 32))
        DataInputStream(ByteArrayInputStream(block)).use {
            assertEquals(3, it.readInt()); val mime = ByteArray(it.readInt()); it.readFully(mime)
            assertEquals("image/png", String(mime)); assertEquals(0, it.readInt())
            assertEquals(64, it.readInt()); assertEquals(32, it.readInt()); assertEquals(24, it.readInt()); assertEquals(0, it.readInt())
            val picture = ByteArray(it.readInt()); it.readFully(picture); assertArrayEquals(image, picture)
        }
        val args = MetadataCommand.arguments(File("in.opus"), File("out.opus"), null, null, File("cover.ffmeta"), true)
        assertTrue(args.contains("ffmetadata")); assertFalse(args.any { it.startsWith("METADATA_BLOCK_PICTURE=") })
    }
    @Test fun metadataFileEscapesSpecialCharacters() {
        assertEquals(";FFMETADATA1\ntitle=A\\=B\\;C\\#D\n", MetadataCommand.metadataFile(mapOf("title" to "A=B;C#D")))
    }
}
