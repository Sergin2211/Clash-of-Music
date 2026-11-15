package com.clashofmusic.app.data.media

import org.junit.Assert.*
import org.junit.Test

class ArtworkCandidatesTest {
    @Test fun albumsPreserveTheirOwnArtworkBeforeCatalogOrVideoAlternatives() {
        val choices = ArtworkCandidates.forDownload("album", "track", "catalog", "abcdefghijk", true)
        assertEquals(listOf("album", "track", "catalog"), choices.take(3))
        assertTrue(choices.last().endsWith("/abcdefghijk/mqdefault.jpg"))
    }
    @Test fun emptyOrRepeatedUrlsCannotHideAnIndependentFallback() {
        assertEquals(listOf("album"), ArtworkCandidates.forDownload(" ", "album", "album", "local", true))
    }
    @Test fun individualSongsStillPreferMatchedCatalogArtwork() {
        assertEquals(listOf("catalog", "video"), ArtworkCandidates.forDownload("video", null, "catalog", "local", false))
    }
    @Test fun invalidVideoIdentityDoesNotCreateGuessedArtworkRequests() {
        assertTrue(ArtworkCandidates.forDownload(null, null, null, "local song", false).isEmpty())
    }
}
