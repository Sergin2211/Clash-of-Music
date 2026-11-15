package com.clashofmusic.app.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibraryAlbumTest {
    @Test fun collectionsUseTheirOwnArtworkEvenWhenTheyShareTheFirstSong() {
        val shared = song("shared", cover = "single.jpg")
        val collections = listOf(
            AlbumCollection(Playlist("a", "First", "Ado • Pop", "album-a.jpg", true), listOf("shared")),
            AlbumCollection(Playlist("b", "Second", "Ado • Pop", "album-b.jpg", true), listOf("shared")))
        val albums = groupLibraryAlbums(listOf(shared), collections)
        assertEquals(listOf("album-a.jpg", "album-b.jpg"), albums.map { it.thumbnailUrl })
        assertTrue(albums.all { it.songs.single() == shared })
    }

    @Test fun missingCollectionArtworkFallsBackButDoesNotReplaceAnExplicitCoverDuringMerge() {
        val tracks = listOf(song("a", album = "Album", cover = "track.jpg"), song("b", album = "Album", cover = "other.jpg"))
        fun collection(id: String, cover: String?) = AlbumCollection(Playlist(id, "Album", "Ado • Pop", cover, true), listOf("a"))
        val explicit = groupLibraryAlbums(tracks, listOf(collection("empty", " "), collection("cover", "album.jpg"))).single()
        assertEquals("album.jpg", explicit.thumbnailUrl)
        assertEquals(listOf("a", "b"), explicit.songs.map { it.id })
        assertEquals("track.jpg", groupLibraryAlbums(tracks, listOf(collection("empty", null))).single().thumbnailUrl)
    }

    private fun song(id: String, artist: String = "Ado", album: String? = "Homónimo",
                     cover: String? = null, genre: String? = null) = DownloadedSong(
        id, id, "Pista $id", artist, album, 200, cover, "/$id.mp3", "file:///$id.mp3",
        "MP3", fileSizeBytes = 100, downloadedAt = 1, genre = genre
    )

    @Test fun separatesNamesakesAndTheirArtworkCountsAndGenres() {
        val ado = song("a", cover = "ado.jpg", genre = "Pop")
        val other = song("b", "Other", cover = "other.jpg", genre = "Rock")
        val albums = groupLibraryAlbums(listOf(ado, other, song("c")))
        assertEquals(2, albums.size)
        assertEquals(listOf("a", "c"), albums[0].songs.map { it.id })
        assertEquals("ado.jpg", albums[0].thumbnailUrl)
        assertEquals("Pop", albums[0].genre)
        assertEquals(listOf(other), albums[1].songs)
        assertEquals("other.jpg", albums[1].thumbnailUrl)
        assertEquals("Rock", albums[1].genre)
    }

    @Test fun selectionAndPlaybackMembershipExcludeAnotherArtist() {
        val ado = song("a")
        val other = song("b", "Other")
        val key = groupLibraryAlbums(listOf(ado, other)).first().key
        assertTrue(key.contains(ado))
        assertFalse(key.contains(other))
        assertFalse(key.contains(null))
        assertFalse(key.contains(ado.copy(album = "Otro disco")))
    }

    @Test fun artistSearchDoesNotReturnOtherArtistsNamesake() {
        val albums = groupLibraryAlbums(listOf(song("a"), song("b", "Other")))
        assertEquals(listOf("Ado"), albums.filter { it.matchesQuery("ado") }.map { it.artist })
        assertEquals(2, albums.count { it.matchesQuery("HOMÓNIMO") })
        assertEquals(2, albums.count { it.matchesQuery(" ") })
        assertEquals(0, albums.count { it.matchesQuery("no existe") })
    }

    @Test fun ignoresCaseWhitespaceAndUnicodeCompositionOnly() {
        val original = song("a", "  ADO ", "  Homónimo  ")
        val same = song("b", "ado", "HOMO\u0301NIMO")
        assertEquals(1, groupLibraryAlbums(listOf(original, same)).size)
        assertEquals(1, groupLibraryAlbums(listOf(song("a", "A  B"), song("b", " a b "))).size)
        assertEquals(2, groupLibraryAlbums(listOf(original, song("c", album = "Homonimo"))).size)
        assertEquals(2, groupLibraryAlbums(listOf(original, song("c", album = "Homónimo (Deluxe)"))).size)
    }

    @Test fun normalizationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1, groupLibraryAlbums(listOf(song("a", "INDIGO"), song("b", "indigo"))).size)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun missingAlbumStaysInSongsWithoutCreatingEmptyAlbum() {
        val tracks = listOf(song("a", album = null), song("b", album = "  "), song("c"))
        assertEquals(listOf("c"), groupLibraryAlbums(tracks).single().songs.map { it.id })
        assertEquals(3, tracks.size)
        assertEquals(2, groupLibraryAlbums(listOf(song("a", ""), song("b", "Ado"))).size)
    }

    @Test fun tagEditMovesTrackWithoutKeepingItInOldGroup() {
        val before = listOf(song("a"), song("b", "Other"))
        val oldKey = groupLibraryAlbums(before).first().key
        val after = before.map { if (it.id == "a") it.copy(artist = "Other") else it }
        assertFalse(groupLibraryAlbums(after).any { it.key == oldKey })
        assertEquals(listOf("a", "b"), groupLibraryAlbums(after).single().songs.map { it.id })
    }

    @Test fun stableIdsCannotCollideOnSeparatorsAndGroupsKeepTrackOrder() {
        val albums = groupLibraryAlbums(listOf(song("a", "c", "a:b"), song("b", "b:c", "a")))
        assertEquals(2, albums.map { it.key.stableId }.distinct().size)
        val input = listOf(song("z"), song("a"), song("m"))
        assertEquals(input, groupLibraryAlbums(input).single().songs)
        assertEquals(listOf("a", "a:b"), albums.map { it.title })
    }

    @Test fun blankArtworkAndGenreFallBackWithinSameArtistOnly() {
        val albums = groupLibraryAlbums(listOf(song("a", cover = " ", genre = ""),
            song("b", "Other", cover = "other.jpg", genre = "Rock"),
            song("c", cover = "ado.jpg", genre = "Pop")))
        assertEquals("ado.jpg", albums.first().thumbnailUrl)
        assertEquals("Pop", albums.first().genre)
    }

    @Test fun sharedSongRemainsInBothAlbumsAfterItsFileTagChanges() {
        val shared = song("shared", album = "Second album")
        val collections = listOf(
            AlbumCollection(Playlist("a", "First album", "Ado • Pop • 2022", isAlbum = true), listOf("shared")),
            AlbumCollection(Playlist("b", "Second album", "Ado • Pop • 2023", isAlbum = true), listOf("shared"))
        )
        val albums = groupLibraryAlbums(listOf(shared), collections)
        assertEquals(listOf("First album", "Second album"), albums.map { it.title })
        assertTrue(albums.all { it.contains(shared) && it.songs == listOf(shared) })
        assertEquals(2, groupLibraryAlbums(listOf(shared.copy(album = "First album")), collections).size)
    }

    @Test fun emptyDeletedCollectionsDoNotBecomeGhostAlbums() {
        val tracks = listOf(song("a"))
        val collections = listOf(AlbumCollection(Playlist("empty", "Deleted", isAlbum = true), listOf("missing")))
        assertEquals(listOf("Homónimo"), groupLibraryAlbums(tracks, collections).map { it.title })
    }

    @Test fun sharedCurrentSongDoesNotMarkAnotherAlbumsDifferentQueueAsPlaying() {
        val shared = song("shared", album = "Second")
        val other = song("other", album = "First")
        val first = LibraryAlbum(LibraryAlbumKey.from("First", "Ado"), "First", "Ado", listOf(shared, other))
        val second = LibraryAlbum(LibraryAlbumKey.from("Second", "Ado"), "Second", "Ado", listOf(shared))
        assertTrue(first.matchesPlayingQueue(shared, listOf(shared, other)))
        assertFalse(second.matchesPlayingQueue(shared, listOf(shared, other)))
        assertTrue(second.matchesPlayingQueue(shared, listOf(shared)))
        assertFalse(first.matchesPlayingQueue(shared, listOf(shared)))
    }

    @Test fun repeatedImportsMergeOnlySameTitleAndArtistAndRetainRelationOrder() {
        val tracks = listOf(song("a"), song("b"))
        val collections = listOf(
            AlbumCollection(Playlist("1", "Album", "Ado • Pop • 2022", isAlbum = true), listOf("b", "a")),
            AlbumCollection(Playlist("2", "Album", "Ado • Pop • 2022", isAlbum = true), listOf("a")),
            AlbumCollection(Playlist("3", "Album", "Other • Pop • 2022", isAlbum = true), listOf("a"))
        )
        val albums = groupLibraryAlbums(tracks, collections)
        assertEquals(2, albums.size)
        assertEquals(listOf("b", "a"), albums.first().songs.map { it.id })
        assertEquals("Other", albums.last().artist)
    }
}
