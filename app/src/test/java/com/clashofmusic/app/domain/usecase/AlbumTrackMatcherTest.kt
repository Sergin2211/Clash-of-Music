package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AlbumTrackMatcherTest {
    private val request = TrackMatchRequest("Tot Musica", "Ado", 201)
    private fun song(id: String = "correct", title: String = "Tot Musica", artist: String = "Ado", duration: Long = 201) =
        SongItem(id, title, artist, null, duration, "https://www.youtube.com/watch?v=$id")

    @Test fun doesNotTakeFirstResultWhenItIsACover() {
        assertEquals("correct", AlbumTrackMatcher.match(request, listOf(song("cover", "Tot Musica (Cover)"), song())).song?.id)
    }
    @Test fun ignoresCaseUnicodeWidthPunctuationAndAccents() {
        assertNotNull(AlbumTrackMatcher.match(TrackMatchRequest("ＣＡＮＣＩÓＮ", "Björk", 200),
            listOf(song(title = "Canción (Official Audio)", artist = "BJORK - Topic", duration = 201))).song)
    }
    @Test fun recognizesArtistBracketAndFilmAssociationWithoutConfusingTheVersion() {
        assertNotNull(AlbumTrackMatcher.match(request,
            listOf(song(title = "【Ado】Tot Musica（ウタ from ONE PIECE FILM RED）"))).song)
    }
    @Test fun japaneseTitlesAreComparedWithoutLosingTheirLetters() {
        assertNotNull(AlbumTrackMatcher.match(TrackMatchRequest("うっせぇわ", "Ado", 202),
            listOf(song(title = "【Ado】うっせぇわ", duration = 202))).song)
    }
    @Test fun exactArtistIsPreferredOverCoverSinger() {
        assertEquals("correct", AlbumTrackMatcher.match(request, listOf(song("cover", artist = "Cover singer"), song())).song?.id)
    }
    @Test fun unexpectedCollaborationLosesToOriginalButRemainsAFallback() {
        val target = TrackMatchRequest("New Genesis", "Ado", 228)
        val collaboration = song("collab", "New Genesis ft. Hoshi & AmaLee", duration = 224)
        val original = song("original", "新時代", duration = 228)
        assertEquals("original", AlbumTrackMatcher.match(target, listOf(collaboration, original)).song?.id)
        val otherResults = (1..25).map { song("other-$it", "Other song", "Other channel", 0) }
        assertEquals("original", AlbumTrackMatcher.match(target, listOf(collaboration) + otherResults + original).song?.id)
        assertEquals("collab", AlbumTrackMatcher.match(target, listOf(collaboration)).song?.id)
    }
    @Test fun explicitFeaturedPerformerIsPreferred() {
        val target = TrackMatchRequest("Song (feat. Hoshi)", "Ado", 200)
        assertEquals("right", AlbumTrackMatcher.match(target, listOf(song("wrong", "Song ft. Other", duration = 200),
            song("right", "Song ft. Hoshi", duration = 200))).song?.id)
    }
    @Test fun prefersOriginalToUnrequestedVersions() {
        listOf("live", "Remix", "Karaoke", "instrumental", "Sped Up", "Acoustic", "TV Size", "Remastered").forEach {
            assertEquals(it, "correct", AlbumTrackMatcher.match(request, listOf(song("variant", "Tot Musica ($it)"), song())).song?.id)
        }
    }
    @Test fun explicitLiveVersionIsPreferredToStudioVersion() {
        assertEquals("live", AlbumTrackMatcher.match(request.copy(title = "Tot Musica (Live)"),
            listOf(song(), song("live", "Tot Musica (Live)"))).song?.id)
    }
    @Test fun durationRanksInsteadOfRejectingAllResults() {
        assertEquals("correct", AlbumTrackMatcher.match(request, listOf(song("preview", duration = 30), song())).song?.id)
        assertNotNull(AlbumTrackMatcher.match(request, listOf(song(duration = 215))).song)
    }
    @Test fun unknownDurationDoesNotBlockAutomaticDownload() {
        assertNotNull(AlbumTrackMatcher.match(request, listOf(song(duration = 0))).song)
    }
    @Test fun equalCandidatesUseSearchRelevanceWithoutManualSelection() {
        assertEquals("first", AlbumTrackMatcher.match(request, listOf(song("first"), song("second"))).song?.id)
        assertEquals("second", AlbumTrackMatcher.match(request, listOf(song("second"), song("first"))).song?.id)
    }
    @Test fun translatedTitleAndArtistAliasStillProduceAnAutomaticChoice() {
        assertNotNull(AlbumTrackMatcher.match(TrackMatchRequest("New Genesis", "Ado", 228),
            listOf(song(title = "新時代", duration = 228))).song)
        assertNotNull(AlbumTrackMatcher.match(request, listOf(song(artist = "Artist alias"))).song)
    }
    @Test fun unavailableMetadataAndVersionDifferencesStillChooseBestAvailable() {
        assertNotNull(AlbumTrackMatcher.match(request.copy(artist = ""), listOf(song(title = "Different title", duration = 0))).song)
        assertNotNull(AlbumTrackMatcher.match(request, listOf(song(title = "Tot Musica (English Version)"))).song)
    }
    @Test fun emptyResultsAndInvalidDownloadIdentityAreTheOnlyMetadataRejections() {
        assertEquals(TrackMatchIssue.NO_RESULTS, AlbumTrackMatcher.match(request, emptyList()).issue)
        assertNull(AlbumTrackMatcher.match(request, listOf(song().copy(url = ""))).song)
        assertNull(AlbumTrackMatcher.match(request, listOf(song().copy(id = ""))).song)
    }
    @Test fun exactTitleWinsOverDifferentSongBySameArtist() {
        assertEquals("correct", AlbumTrackMatcher.match(request, listOf(song("other", "Odo"), song())).song?.id)
    }
    @Test fun fullAlbumsAreLowerPriorityThanAnIndividualSong() {
        assertEquals("correct", AlbumTrackMatcher.match(request, listOf(song("album", "Tot Musica full album", duration = 2000), song())).song?.id)
    }
    @Test fun strongMatchRequiresAllSignalsButWeakMatchIsStillDownloadable() {
        assertTrue(AlbumTrackMatcher.isStrongMatch(request, song()))
        assertFalse(AlbumTrackMatcher.isStrongMatch(request, song(title = "Translated title")))
        assertNotNull(AlbumTrackMatcher.match(request, listOf(song(title = "Translated title"))).song)
    }

    @Test fun hippocampalPainOfficialBilingualChannelBeatsConcertWithExactRomanizedArtist() {
        val request = TrackMatchRequest("Hippocampal Pain", "ZUTOMAYO", 189)
        val official = song("PLG2Uexyi9s", "ZUTOMAYO – Hippocampal Pain", "ずっと真夜中でいいのに。 ZUTOMAYO", 195)
        val concert = song("kmwb4BgavWY", "Hippocampal Pain [Live @FujiRockFestival 2024]", "ZUTOMAYO", 196)
        assertEquals(official.id, AlbumTrackMatcher.match(request, listOf(concert, official)).song?.id)
        assertTrue(AlbumTrackMatcher.isStrongMatch(request, official))
        assertFalse(AlbumTrackMatcher.isStrongMatch(request, concert))
    }

    @Test fun concertCannotWinJustBecauseItsDurationIsCloser() {
        val studio = song("studio", artist = "Unrecognized channel", duration = 209)
        val concert = song("concert", "Tot Musica (Live)", duration = 201)
        assertEquals("studio", AlbumTrackMatcher.match(request, listOf(concert, studio)).song?.id)
    }

    @Test fun conciertoAndJapanesePerformanceLabelsDoNotBeatStudio() {
        for (label in listOf("Concierto 2025", "En directo", "ライブ映像", "LIVE AT TOUR 2025", "Festival 2024")) {
            val concert = song("concert", "Tot Musica ($label)")
            val studio = song("studio", artist = "Other uploader")
            assertEquals(label, "studio", AlbumTrackMatcher.match(request, listOf(concert, studio)).song?.id)
        }
    }

    @Test fun originalVersionSignalsSurviveDisplayTitleCleaning() {
        val hiddenConcert = song("live").copy(sourceTitle = "Ado - Tot Musica (Official Live Video)")
        val studio = song("studio", artist = "Other uploader")
        assertEquals("studio", AlbumTrackMatcher.match(request, listOf(hiddenConcert, studio)).song?.id)
        assertFalse(AlbumTrackMatcher.isStrongMatch(request, hiddenConcert))
    }

    @Test fun titleArtistClaimDoesNotReplaceTheActualUploader() {
        val spoofed = song("cover").copy(channelName = "Cover singer", sourceTitle = "Ado - Tot Musica Cover")
        val studio = song("studio").copy(channelName = "Ado - Topic", sourceTitle = "Tot Musica")
        assertEquals("studio", AlbumTrackMatcher.match(request, listOf(spoofed, studio)).song?.id)
        assertFalse(AlbumTrackMatcher.isStrongMatch(request, spoofed))
    }

    @Test fun fansAndCoverChannelsAreNotBilingualArtistAliases() {
        for (channel in listOf("Ado fans", "Ado covers", "Ado piano", "Other Ado", "NotAdo")) {
            assertFalse(channel, AlbumTrackMatcher.isStrongMatch(request, song(artist = channel)))
        }
        assertTrue(AlbumTrackMatcher.isStrongMatch(request, song(artist = "アド Ado")))
    }

    @Test fun unrelatedStudioSongDoesNotPushOutTheMatchingLiveFallback() {
        val concert = song("live", "Tot Musica (Live)")
        val unrelated = song("other", "Odo")
        assertEquals("live", AlbumTrackMatcher.match(request, listOf(unrelated, concert)).song?.id)
        assertEquals("live", AlbumTrackMatcher.match(request, listOf(concert)).song?.id)
    }

    @Test fun explicitlyRequestedConcertStillWinsAgainstStudio() {
        val target = request.copy(title = "Tot Musica (Live)")
        val studio = song("studio")
        val live = song("live", "Tot Musica (Live)", artist = "Unknown uploader")
        assertEquals("live", AlbumTrackMatcher.match(target, listOf(studio, live)).song?.id)
    }

    @Test fun reactionAndMashupLoseToTheOriginalButRemainAutomaticFallbacks() {
        for (label in listOf("Reaction", "Mashup")) {
            val alternate = song("alternate", "Tot Musica ($label)")
            val studio = song("studio", artist = "Unknown uploader")
            assertEquals("studio", AlbumTrackMatcher.match(request, listOf(alternate, studio)).song?.id)
            assertEquals("alternate", AlbumTrackMatcher.match(request, listOf(alternate)).song?.id)
        }
    }

    @Test fun channelReactionSignalIsKeptWhenTitleLooksExact() {
        val reaction = song("reaction").copy(channelName = "Music Producers Reacts", sourceTitle = "Ado – Tot Musica")
        val studio = song("studio", artist = "Unknown uploader")
        assertEquals("studio", AlbumTrackMatcher.match(request, listOf(reaction, studio)).song?.id)
    }

    private fun repository(call: (String) -> Result<List<SongItem>>) = object : MusicRepository {
        override suspend fun search(query: String) = call(query)
        override suspend fun searchAlbums(query: String): Result<List<AlbumItem>> = error("unused")
        override suspend fun getAlbumTracks(albumId: String): Result<List<AlbumTrackItem>> = error("unused")
        override suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails> = error("unused")
        override suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo> = error("unused")
        override fun isSupportedPlaylistUrl(url: String) = false
    }

    @Test fun resolverUsesAnotherArtistAndTitleQueryWhenFirstResultsAreWrong() = runTest {
        val queries = mutableListOf<String>()
        val resolver = ResolveAlbumTrack(repository { query ->
            queries.add(query)
            Result.success(if (queries.size == 1) listOf(song("wrong", artist = "Wrong artist")) else listOf(song()))
        })
        assertEquals("correct", resolver.resolve(request).song?.id)
        assertEquals(listOf("Ado - Tot Musica", "Ado Tot Musica official audio"), queries)
    }
    @Test fun weakResultsAreAutomaticallySelectedAfterTryingBothQueries() = runTest {
        var searches = 0
        val resolver = ResolveAlbumTrack(repository { searches++; Result.success(listOf(song(title = "Translated title"))) })
        assertNotNull(resolver.resolve(request).song)
        assertEquals(2, searches)
    }
    @Test fun officialBilingualMatchStopsBeforeAnotherQueryCanPreferAConcert() = runTest {
        var searches = 0
        val target = TrackMatchRequest("Hippocampal Pain", "ZUTOMAYO", 189)
        val resolver = ResolveAlbumTrack(repository {
            searches++
            Result.success(listOf(song("studio", "ZUTOMAYO – Hippocampal Pain", "ずっと真夜中でいいのに。 ZUTOMAYO", 195),
                song("live", "Hippocampal Pain (Live)", "ZUTOMAYO", 189)))
        })
        assertEquals("studio", resolver.resolve(target).song?.id)
        assertEquals(1, searches)
    }
    @Test fun unrelatedArtistTitleAndDurationCannotBeRelabeledAsTheCatalogTrack() {
        val unrelated = song("wrong", "Jailhouse Rock", "Elvis Presley", 95)
        assertNull(AlbumTrackMatcher.match(request, listOf(unrelated)).song)
    }

    @Test fun bilingualOfficialTitleBeatsLyricsUploaderForAlmostHuman() {
        val target = TrackMatchRequest("Almost Human", "ZUTOMAYO", 244)
        val official = song("official", "ずっと真夜中でいいのに。『間人間』 (ZUTOMAYO - Almost Human)（Audio Track）",
            "ずっと真夜中でいいのに。 ZUTOMAYO", 246)
        val lyrics = song("lyrics", "Almost Human - ZUTOMAYO (romaji) Lyrics", "Blu Blu", 245)
        assertTrue(AlbumTrackMatcher.isStrongMatch(target, official))
        assertEquals("official", AlbumTrackMatcher.match(target, listOf(lyrics, official)).song?.id)
    }

    @Test fun emptySearchesReturnNoResults() = runTest {
        assertEquals(TrackMatchIssue.NO_RESULTS, ResolveAlbumTrack(repository { Result.success(emptyList()) }).resolve(request).issue)
    }
    @Test fun cancellationIsNeverConvertedIntoANoMatch() = runTest {
        val resolver = ResolveAlbumTrack(repository { throw CancellationException("Stopped") })
        try { resolver.resolve(request); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
    @Test fun networkFailuresAreReportedSeparately() = runTest {
        val resolver = ResolveAlbumTrack(repository { throw java.io.IOException("Offline") })
        assertEquals(TrackMatchIssue.SEARCH_FAILED, resolver.resolve(request).issue)
    }
}
