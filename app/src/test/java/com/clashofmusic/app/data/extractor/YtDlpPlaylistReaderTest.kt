package com.clashofmusic.app.data.extractor

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class YtDlpPlaylistReaderTest {
    @Test fun preserves400PositionsIncludingRepeatedVideos() {
        val data = JSONObject().put("id", "playlist").put("title", "Large playlist").put("playlist_count", 400)
            .put("entries", JSONArray().apply { repeat(400) { put(JSONObject().put("id", "abcdefghijk").put("title", "Song $it")
                .put("channel", "Artist").put("duration", 180.5)) } })
        val list = YtDlpPlaylistReader.decode(data.toString(), "https://youtube.com/playlist?list=PLtest")
        assertEquals(400, list.tracks.size)
        assertEquals("Song 399", list.tracks.last().title)
        assertTrue(list.tracks.all { it.album == null && it.directUrl == "https://www.youtube.com/watch?v=abcdefghijk" })
        assertNull(list.readWarning)
    }
    @Test fun unavailableEntriesDoNotBlockReadableMusicAndAreReported() {
        val data = """{"entries":[{"id":"abcdefghijk","title":"Song"},null,{"id":"abcdefghijl","title":"[Private video]"},{"id":"bad","title":"Invalid"}]}"""
        val list = YtDlpPlaylistReader.decode(data, "https://youtube.com/playlist?list=PLtest")
        assertEquals(1, list.trackCount)
        assertTrue(list.readWarning!!.startsWith("3 "))
    }
    @Test fun albumMetadataAndBestThumbnailAreKept() {
        val data = """{"id":"album","title":"Album","uploader":"Artist","entries":[{"id":"abcdefghijk","title":"Song","artist":"Singer","thumbnails":[{"url":"small","width":10,"height":10},{"url":"large","width":500,"height":500}]}]}"""
        val list = YtDlpPlaylistReader.decode(data, "https://youtube.com/playlist?list=OLAK5uytest")
        assertTrue(list.isAlbum)
        assertEquals("Album", list.tracks.single().album)
        assertEquals("Singer", list.tracks.single().artist)
        assertEquals("large", list.thumbnailUrl)
    }
}
