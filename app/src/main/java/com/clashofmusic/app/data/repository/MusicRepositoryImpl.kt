package com.clashofmusic.app.data.repository

import com.clashofmusic.app.data.extractor.AppleMusicDataSource
import com.clashofmusic.app.data.extractor.SpotifyExtractorDataSource
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.domain.model.AlbumItem
import com.clashofmusic.app.domain.model.AlbumTrackItem
import com.clashofmusic.app.domain.model.RemotePlaylistInfo
import com.clashofmusic.app.domain.model.SongDetails
import com.clashofmusic.app.domain.model.SongItem
import com.clashofmusic.app.domain.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepositoryImpl(
    private val youtubeDataSource: YouTubeExtractorDataSource,
    private val spotifyDataSource: SpotifyExtractorDataSource = SpotifyExtractorDataSource(),
    private val appleMusicDataSource: AppleMusicDataSource = AppleMusicDataSource()
) : MusicRepository {

    override suspend fun search(query: String): Result<List<SongItem>> = withContext(Dispatchers.IO) {
        youtubeDataSource.search(query)
    }

    override suspend fun searchAlbums(query: String): Result<List<AlbumItem>> = withContext(Dispatchers.IO) {
        try {
            val albums = appleMusicDataSource.searchAlbums(query)
            Result.success(albums)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAlbumTracks(albumId: String): Result<List<AlbumTrackItem>> = withContext(Dispatchers.IO) {
        try {
            val tracks = appleMusicDataSource.getAlbumTracks(albumId)
            Result.success(tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSongDetails(songIdOrUrl: String): Result<SongDetails> = withContext(Dispatchers.IO) {
        youtubeDataSource.getStreamDetails(songIdOrUrl)
    }

    override suspend fun extractRemotePlaylist(url: String): Result<RemotePlaylistInfo> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        when {
            spotifyDataSource.isSpotifyUrl(trimmed) -> {
                spotifyDataSource.extractPlaylist(trimmed)
            }
            youtubeDataSource.isYouTubeUrl(trimmed) -> {
                youtubeDataSource.extractPlaylist(trimmed)
            }
            trimmed.contains("soundcloud.com") -> {
                try {
                    val cleanUrl = trimmed.split("?")[0].trimEnd('/')
                    val parts = cleanUrl.split("/")
                    val trackTitle = parts.lastOrNull()?.replace("-", " ")?.replaceFirstChar { it.uppercase() } ?: "SoundCloud Track"
                    val trackArtist = parts.getOrNull(parts.size - 2)?.replace("-", " ")?.replaceFirstChar { it.uppercase() } ?: "SoundCloud Artist"
                    Result.success(
                        RemotePlaylistInfo(
                            id = cleanUrl.hashCode().toString(),
                            title = trackTitle,
                            author = trackArtist,
                            description = "Pista importada de SoundCloud",
                            thumbnailUrl = null,
                            trackCount = 1,
                            source = com.clashofmusic.app.domain.model.PlaylistSource.LOCAL,
                            isAlbum = false,
                            tracks = listOf(
                                com.clashofmusic.app.domain.model.RemoteTrackItem(
                                    title = trackTitle,
                                    artist = trackArtist,
                                    album = "SoundCloud",
                                    durationSeconds = 0L,
                                    directUrl = cleanUrl,
                                    thumbnailUrl = null
                                )
                            )
                        )
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            else -> {
                Result.failure(IllegalArgumentException("Enlace no reconocido. Debe ser un link de YouTube o Spotify."))
            }
        }
    }

    override fun isSupportedPlaylistUrl(url: String): Boolean {
        val trimmed = url.trim()
        return spotifyDataSource.isSpotifyUrl(trimmed) || youtubeDataSource.isYouTubeUrl(trimmed) || trimmed.contains("soundcloud.com")
    }
}
