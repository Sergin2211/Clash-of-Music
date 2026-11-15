package com.clashofmusic.app

import com.clashofmusic.app.data.extractor.YouTubeTrackIdentity
import com.clashofmusic.app.data.player.*
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class RadioRefinementTest {
    private fun parsed(raw: String, channel: String, title: String, artist: String) {
        assertEquals(YouTubeTrackIdentity(title, artist), YouTubeTrackIdentity.parse(raw, channel))
    }
    @Test fun animeCreditsAreNotInterpretedAsArtists() {
        parsed("BLEACH: Thousand-Year Blood War Part 4 - Ending FULL \"RASEN\" by 9Lana", "AniMelody", "RASEN", "9Lana")
        parsed("Final Part ENDING | RASEN by 9Lana | BLEACH: The Calamity - VIZ", "VIZ", "RASEN", "9Lana")
    }
    @Test fun japaneseSlashAndBracketCreditsHaveStableIdentity() {
        parsed("【MV】「BALALAIKA」 / 9Lana", "9Lana", "BALALAIKA", "9Lana")
        parsed("【MV】 Never Give Up / 9Lana", "9Lana", "Never Give Up", "9Lana")
        parsed("螺旋 -RASEN- / 9Lana | TVアニメ 最終クール『BLEACH 千年血戦篇-禍進譚-』EDテーマ", "9Lana", "螺旋 -RASEN-", "9Lana")
        parsed("【Ado】Value", "Ado", "Value", "Ado")
        parsed("【MV】 Ashiato - Brave the unknown - / 9Lana", "9Lana", "Ashiato - Brave the unknown", "9Lana")
    }
    @Test fun channelDisambiguatesBothHyphenOrders() {
        parsed("Goo Goo Dolls – Iris (Official Video)", "Goo Goo Dolls", "Iris", "Goo Goo Dolls")
        parsed("Venom - Eminem (Lyrics)", "Eminem - Topic", "Venom", "Eminem")
        parsed("AC/DC - Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC")
        parsed("Billie Eilish - lovely (Lyrics)", "7clouds", "lovely", "Billie Eilish")
    }
    @Test fun ambiguousTitlesArePreservedAndByIsNotAlwaysACredit() {
        parsed("Stand by Me", "Ben E. King - Topic", "Stand by Me", "Ben E. King")
        parsed("BLEACH - Ending FULL", "VIZ", "BLEACH - Ending FULL", "VIZ")
    }
    private fun song(id: Int, artist: String = "9Lana") = SongItem(id.toString().padStart(11, '0'), "Track $id", artist, null, 190, "https://youtube.com/watch?v=${id.toString().padStart(11, '0')}")
    @Test fun rockLabelCannotInjectUnrelatedSongsOrChangeTheSeed() = runTest {
        val queriedSeeds = mutableListOf<String>()
        val mix = (1..10).map { song(it) }
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> { queriedSeeds.add(seed.songId); return mix }
        })
        val seed = song(0).toRadioSong().copy(genre = "Rock")
        val first = recommender.next(seed, listOf(seed), 5)
        val unrelatedCurrent = song(20, "Elvis Presley").toRadioSong()
        val second = recommender.next(seed, listOf(seed, unrelatedCurrent) + first, 5)
        assertEquals(listOf(seed.songId), queriedSeeds)
        assertEquals(mix.map { it.id }, (first + second).map { it.songId })
        assertFalse((first + second).any { it.artist == "Elvis Presley" })
    }
    @Test fun sourceWithOnlyOneArtistStillReturnsBestAvailableInsteadOfBlocking() = runTest {
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong) = (1..7).map { song(it) }
        })
        assertEquals(5, recommender.next(song(0).toRadioSong(), emptyList(), 5).size)
    }
    @Test fun exhaustionRequeriesOnlyTheOriginalMix() = runTest {
        var calls = 0
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> { calls++; return listOf(song(1)) }
        })
        val seed = song(0).toRadioSong()
        val first = recommender.next(seed, listOf(seed), 5)
        assertTrue(recommender.next(seed, listOf(seed) + first, 5).isEmpty())
        assertEquals(2, calls)
        assertNotNull(recommender.lastTrace!!.message)
    }
    @Test fun clearingASessionDiscardsItsLateMixAndTrace() = runTest {
        val gate = CompletableDeferred<Unit>()
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> { gate.await(); return listOf(song(1)) }
        })
        val job = async { recommender.next(song(0).toRadioSong(), emptyList(), 5) }
        runCurrent(); recommender.clear(); gate.complete(Unit)
        assertTrue(job.await().isEmpty()); assertNull(recommender.lastTrace)
    }
    @Test fun invalidLocalIdentifierDoesNotQueryAnUnrelatedMix() = runTest {
        val recommender = RadioRecommender(object : RadioRecommendationSource {
            override suspend fun related(seed: DownloadedSong): List<SongItem> = error("Must not query with a local identifier")
        })
        assertTrue(recommender.next(song(0).toRadioSong().copy(songId = "local-file-uuid"), emptyList(), 5).isEmpty())
        assertNull(recommender.lastTrace!!.mixUrl)
    }
    private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>?) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handler(method.name, args) } as T
    @Test fun explicitSaveUsesDefaultFormatStableUrlAndCapturedSongWithoutPlayerCalls() = runTest {
        val emitted = mutableListOf<DownloadTask>(); var active = false
        val library = proxy<LibraryRepository> { name, _ -> when(name) { "isSongDownloaded" -> false; else -> error(name) } }
        val downloads = proxy<DownloadRepository> { name, args -> when(name) {
            "isSongDownloading" -> active
            "enqueueDownload" -> { val task = args!![0] as DownloadTask; emitted.add(task); active = true; task.id }
            else -> error(name)
        } }
        val prefs = proxy<PreferencesRepository> { name, _ -> when(name) { "observeSettings" -> flowOf(AppSettings(defaultFormat = AudioFormat.OPUS)); else -> error(name) } }
        val saver = RadioLibrarySaver(library, downloads, prefs, backgroundScope)
        val current = song(0).toRadioSong()
        val job = saver.save(current)!!
        assertNull(saver.save(current))
        job.join()
        saver.save(current)!!.join()
        assertEquals(1, emitted.size)
        assertEquals(AudioFormat.OPUS, emitted.single().format)
        assertEquals("https://www.youtube.com/watch?v=${current.songId}", emitted.single().url)
        assertEquals(current.title, emitted.single().title)
        assertEquals("", current.filePath)
        assertEquals("RADIO", current.format)
    }
    @Test fun saveDoesNotDuplicateASongAlreadyInLibrary() = runTest {
        val library = proxy<LibraryRepository> { _, _ -> true }
        val downloads = proxy<DownloadRepository> { name, _ -> error("Unexpected $name") }
        val prefs = proxy<PreferencesRepository> { name, _ -> error("Unexpected $name") }
        RadioLibrarySaver(library, downloads, prefs, backgroundScope).save(song(0).toRadioSong())!!.join()
    }
}
