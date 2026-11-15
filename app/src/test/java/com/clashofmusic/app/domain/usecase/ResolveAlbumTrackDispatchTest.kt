package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.MusicRepository
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResolveAlbumTrackDispatchTest {
    @Test fun albumPreparationLeavesUiDispatcherAndReturnsToItsCaller() {
        Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher().use { ui ->
            Executors.newSingleThreadExecutor { Thread(it, "test-album-worker") }.asCoroutineDispatcher().use { worker ->
                var searches = 0
                val song = SongItem("studio", "Canción (Official Audio)", "Björk - Topic", null, 200, "https://example.org/studio")
                val music = object : MusicRepository {
                    override suspend fun search(query: String): Result<List<SongItem>> {
                        assertEquals("test-album-worker", Thread.currentThread().name.substringBefore(" @"))
                        searches++
                        return Result.success(listOf(song.copy(id = "live", title = "Canción (Live)")) +
                            (1..28).map { song.copy(id = "cover-$it", title = "Canción (Cover)", artist = "Other singer") } + song)
                    }
                    override suspend fun searchAlbums(query: String): Result<List<AlbumItem>> = error("unused")
                    override suspend fun getAlbumTracks(albumId: String): Result<List<AlbumTrackItem>> = error("unused")
                    override suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails> = error("unused")
                    override suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo> = error("unused")
                    override fun isSupportedPlaylistUrl(url: String) = false
                }
                runBlocking(ui) {
                    val result = ResolveAlbumTrack(music, worker).resolve(TrackMatchRequest("ＣＡＮＣＩÓＮ", "BJÖRK", 200))
                    assertEquals("studio", result.song?.id)
                    assertEquals("test-ui", Thread.currentThread().name.substringBefore(" @"))
                }
                assertEquals(1, searches)
            }
        }
    }
}
