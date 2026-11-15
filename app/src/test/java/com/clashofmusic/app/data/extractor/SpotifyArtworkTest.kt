package com.clashofmusic.app.data.extractor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyArtworkTest {
    @Test fun currentVisualIdentitySchemaKeepsTheLargestAlbumCover() {
        val entity = JSONObject("""{"visualIdentity":{"image":[
            {"url":"https://images/300","maxWidth":300,"maxHeight":300},
            {"url":"https://images/64","maxWidth":64,"maxHeight":64},
            {"url":"https://images/640","maxWidth":640,"maxHeight":640}]}}""")
        assertEquals("https://images/640", SpotifyArtwork.bestUrl(entity))
    }
    @Test fun legacyCoverArtSchemaRemainsSupported() {
        assertEquals("https://images/old", SpotifyArtwork.bestUrl(JSONObject(
            """{"coverArt":{"sources":[{"url":"https://images/old","width":600,"height":600}]}}""")))
    }
    @Test fun missingLegacyCoverAndInvalidImageCannotHideUsableVisualIdentity() {
        assertEquals("https://images/valid", SpotifyArtwork.bestUrl(JSONObject("""{
          "coverArt":{"sources":[{"url":"","width":9999,"height":9999}]},
          "visualIdentity":{"image":[{"url":"https://images/valid","maxWidth":640,"maxHeight":640}]}}""")))
    }
    @Test fun genuinelyMissingCoverReturnsNullRatherThanAnEmptyLocation() {
        assertNull(SpotifyArtwork.bestUrl(JSONObject("""{"visualIdentity":{"image":[]}}""")))
    }
}
