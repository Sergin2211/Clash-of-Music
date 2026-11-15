package com.clashofmusic.app.data.extractor

import com.clashofmusic.app.domain.model.AudioFormat
import com.clashofmusic.app.domain.model.AudioStreamInfo
import com.clashofmusic.app.domain.model.PlaylistSource
import com.clashofmusic.app.domain.model.RemotePlaylistInfo
import com.clashofmusic.app.domain.model.RemoteTrackItem
import com.clashofmusic.app.domain.model.SongDetails
import com.clashofmusic.app.domain.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.regex.Pattern

data class ResolvedAudioStream(
    val url: String,
    val extension: String,
    val bitrate: Int
)

class YouTubeExtractorDataSource(
    private val playlistRecovery: (suspend (String) -> RemotePlaylistInfo)? = null,
    private val playlistExtractorFactory: (String) -> PlaylistExtractor = {
        ServiceList.YouTube.getPlaylistExtractor(it)
    }
) {

    init {
        try {
            NewPipe.init(NewPipeDownloaderImpl.getInstance())
        } catch (_: Exception) {}
    }

    fun isYouTubeUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.contains("youtube.com") ||
                trimmed.contains("youtu.be") ||
                trimmed.contains("music.youtube.com")
    }

    suspend fun search(query: String): Result<List<SongItem>> = withContext(Dispatchers.IO) {
        try {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val searchExtractor: SearchExtractor = ServiceList.YouTube.getSearchExtractor(trimmedQuery)
            searchExtractor.fetchPage()

            val page = searchExtractor.initialPage
            val rawItems = page.items ?: emptyList()

            val songItems = rawItems.filterIsInstance<StreamInfoItem>().map { item ->
                mapStreamInfoItemToSongItem(item)
            }

            Result.success(songItems)
        } catch (e: ContentNotAvailableException) {
            Result.failure(Exception("Contenido no disponible en YouTube: ${e.message}", e))
        } catch (e: ExtractionException) {
            Result.failure(Exception("Error al extraer información de YouTube: ${e.message}", e))
        } catch (e: IOException) {
            Result.failure(Exception("Error de red al conectar con YouTube. Comprueba tu conexión.", e))
        } catch (e: Exception) {
            Result.failure(Exception("Error inesperado en búsqueda: ${e.message}", e))
        }
    }

    suspend fun getStreamDetails(songIdOrUrl: String): Result<SongDetails> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeYouTubeUrl(songIdOrUrl)
            val streamExtractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(targetUrl)
            streamExtractor.fetchPage()

            val rawTitle = streamExtractor.name.orEmpty()
            val channelName = streamExtractor.uploaderName.orEmpty()
            val identity = YouTubeTrackIdentity.parse(rawTitle, channelName)
            val durationSeconds = streamExtractor.length
            val bestThumbnail = streamExtractor.thumbnails
                ?.maxByOrNull { it.width * it.height }
                ?.url

            val audioStreams = streamExtractor.audioStreams?.map { stream ->
                AudioStreamInfo(
                    format = stream.format?.name ?: "AUDIO",
                    bitrate = stream.averageBitrate,
                    codec = stream.codec.orEmpty(),
                    contentSize = null,
                    url = stream.content
                )
            } ?: emptyList()

            val videoId = extractVideoId(targetUrl) ?: songIdOrUrl

            val details = SongDetails(
                id = videoId,
                title = identity.title,
                artist = identity.artist,
                channelName = channelName,
                album = YouTubeAlbumMetadata.parse(streamExtractor.description?.content),
                thumbnailUrl = bestThumbnail,
                durationSeconds = durationSeconds,
                url = targetUrl,
                audioStreams = audioStreams,
                description = streamExtractor.description?.content
            )

            Result.success(details)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBestAudioStream(
        songIdOrUrl: String,
        preferredFormat: AudioFormat = AudioFormat.MP3
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeYouTubeUrl(songIdOrUrl)
            val streamExtractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(targetUrl)
            streamExtractor.fetchPage()

            val streams = streamExtractor.audioStreams ?: return@withContext null
            if (streams.isEmpty()) return@withContext null

            val selectedStream = when (preferredFormat) {
                AudioFormat.M4A -> {
                    streams.filter { it.format?.name?.contains("M4A", ignoreCase = true) == true }
                        .maxByOrNull { it.averageBitrate } ?: streams.maxByOrNull { it.averageBitrate }
                }
                else -> streams.maxByOrNull { it.averageBitrate }
            } ?: streams.first()

            val extension = selectedStream.format?.suffix?.lowercase()
                ?.takeIf { it in setOf("m4a", "opus", "webm", "aac", "mp3", "ogg") }
                ?: return@withContext null

            ResolvedAudioStream(
                url = selectedStream.content,
                extension = extension,
                bitrate = selectedStream.averageBitrate
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getBestAudioStreamUrl(songIdOrUrl: String): String? {
        return getBestAudioStream(songIdOrUrl)?.url
    }

    suspend fun relatedSongs(songIdOrUrl: String): List<SongItem> = withContext(Dispatchers.IO) {
        val id = extractVideoId(normalizeYouTubeUrl(songIdOrUrl)) ?: return@withContext emptyList()
        // The general video sidebar can contain unrelated trending videos and commentary.
        // Request the music-only mix anchored to this recording instead.
        val extractor = playlistExtractorFactory(YouTubeMusicMix.url(id))
        extractor.fetchPage()
        val info = PlaylistInfo.getInfo(extractor)
        ensureActive()
        check(info.playlistType == PlaylistInfo.PlaylistType.MIX_MUSIC) { "No se recibió una mezcla musical" }
        val raw = info.relatedItems.orEmpty().toMutableList()
        var next = info.nextPage
        // Continue the same mix, with a fixed metadata/page budget. Never follow another seed.
        repeat(2) {
            ensureActive()
            if (!org.schabi.newpipe.extractor.Page.isValid(next) || raw.size >= 180) return@repeat
            val page = try { extractor.getPage(next) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { next = null; return@repeat }
            raw.addAll(page.items); next = page.nextPage
        }
        raw.filterIsInstance<StreamInfoItem>().map(::mapStreamInfoItemToSongItem).distinctBy { it.id }.take(180)
    }

    fun normalizePlaylistUrl(url: String): String {
        val trimmed = url.trim()

        // 1. Check for list query param: ?list=... or &list=...
        val listPattern = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)")
        val listMatcher = listPattern.matcher(trimmed)
        if (listMatcher.find()) {
            val listId = listMatcher.group(1)
            return "https://www.youtube.com/playlist?list=$listId"
        }

        // 2. Check for /browse/VL: e.g. https://music.youtube.com/browse/VLOLAK5uy...
        val vlPattern = Pattern.compile("/browse/(?:VL)?([a-zA-Z0-9_-]+)")
        val vlMatcher = vlPattern.matcher(trimmed)
        if (vlMatcher.find()) {
            val listId = vlMatcher.group(1)
            return "https://www.youtube.com/playlist?list=$listId"
        }

        // 3. Fallback for generic music.youtube.com playlists
        if (trimmed.contains("music.youtube.com")) {
            return trimmed.replace("music.youtube.com", "www.youtube.com")
        }

        return trimmed
    }

    suspend fun extractPlaylist(url: String): Result<RemotePlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizePlaylistUrl(url)
            val playlistExtractor = playlistExtractorFactory(targetUrl)
            playlistExtractor.fetchPage()

            // PlaylistInfo isolates optional metadata failures (notably uploader on music albums).
            val info = PlaylistInfo.getInfo(playlistExtractor)
            val title = info.name.orEmpty()
            val isAlbum = targetUrl.contains("list=OLAK5uy")

            val rawItems = (info.relatedItems ?: emptyList()).toMutableList()
            var itemErrors = info.errors.isNotEmpty()
            var next = info.nextPage
            val visited = hashSetOf<String>()
            while (org.schabi.newpipe.extractor.Page.isValid(next)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val bytes = java.io.ByteArrayOutputStream().also { out -> java.io.ObjectOutputStream(out).use { it.writeObject(next) } }.toByteArray()
                val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).contentToString()
                check(visited.add(fingerprint) && visited.size <= 10000) { "La playlist repite una página; no se importó una lista incompleta" }
                val page = playlistExtractor.getPage(next)
                itemErrors = itemErrors || page.errors.isNotEmpty()
                rawItems.addAll(page.items)
                next = page.nextPage
            }
            if (playlistRecovery != null && (itemErrors || info.streamCount > rawItems.size)) {
                try {
                    val recovered = playlistRecovery.invoke(targetUrl)
                    if (recovered.trackCount >= rawItems.size) return@withContext Result.success(recovered)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep all readable tracks and report the partial extraction below. */ }
            }
            if (rawItems.isEmpty()) throw ExtractionException("No se encontraron canciones en este álbum o playlist")

            val tracks = rawItems.filterIsInstance<StreamInfoItem>().map { item ->
                val identity = YouTubeTrackIdentity.parse(item.name.orEmpty(), item.uploaderName.orEmpty())
                val thumb = item.thumbnails?.maxByOrNull { it.width * it.height }?.url

                RemoteTrackItem(
                    title = identity.title,
                    artist = identity.artist,
                    album = if (isAlbum) title else null,
                    durationSeconds = item.duration,
                    directUrl = item.url,
                    thumbnailUrl = thumb
                )
            }

            val author = info.uploaderName?.takeIf { it.isNotBlank() }
                ?: tracks.map { it.artist }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                    .ifBlank { "YouTube" }
            val bestThumb = info.thumbnails?.maxByOrNull { it.width * it.height }?.url
                ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl }

            Result.success(
                RemotePlaylistInfo(
                    id = info.id.orEmpty(),
                    title = title,
                    author = author,
                    description = info.description?.content,
                    thumbnailUrl = bestThumb,
                    trackCount = tracks.size,
                    source = PlaylistSource.YOUTUBE,
                    isAlbum = isAlbum,
                    tracks = tracks,
                    readWarning = if (info.streamCount > tracks.size)
                        unavailablePlaylistWarning(info.streamCount - tracks.size, tracks.size)
                        else if (itemErrors) "YouTube devolvió avisos al leer la lista. Se muestran las ${tracks.size} canciones disponibles." else null
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed album/playlist must not be silently turned into a single video.
            if (normalizePlaylistUrl(url).contains("list=")) return@withContext Result.failure(e)
            val singleDetails = try { getStreamDetails(url).getOrNull() } catch (_: Exception) { null }
            if (singleDetails != null) {
                return@withContext Result.success(
                    RemotePlaylistInfo(
                        id = singleDetails.id,
                        title = singleDetails.title,
                        author = singleDetails.artist,
                        description = singleDetails.description,
                        thumbnailUrl = singleDetails.thumbnailUrl,
                        trackCount = 1,
                        source = PlaylistSource.YOUTUBE,
                        isAlbum = false,
                        tracks = listOf(
                            RemoteTrackItem(
                                title = singleDetails.title,
                                artist = singleDetails.artist,
                                album = singleDetails.album,
                                durationSeconds = singleDetails.durationSeconds,
                                directUrl = singleDetails.url,
                                thumbnailUrl = singleDetails.thumbnailUrl
                            )
                        )
                    )
                )
            }
            Result.failure(e)
        }
    }

    private fun mapStreamInfoItemToSongItem(item: StreamInfoItem): SongItem {
        val rawTitle = item.name.orEmpty()
        val channelName = item.uploaderName.orEmpty()
        val identity = YouTubeTrackIdentity.parse(rawTitle, channelName)

        val bestThumbnail = item.thumbnails
            ?.maxByOrNull { it.width * it.height }
            ?.url

        val videoId = extractVideoId(item.url) ?: item.url.hashCode().toString()

        return SongItem(
            id = videoId,
            title = identity.title,
            artist = identity.artist,
            thumbnailUrl = bestThumbnail,
            durationSeconds = item.duration,
            url = item.url,
            sourceTitle = rawTitle,
            channelName = channelName
        )
    }

    fun normalizeYouTubeUrl(songIdOrUrl: String): String {
        return if (songIdOrUrl.startsWith("http://") || songIdOrUrl.startsWith("https://")) {
            if (songIdOrUrl.contains("music.youtube.com")) {
                val videoId = extractVideoId(songIdOrUrl)
                if (videoId != null) {
                    "https://www.youtube.com/watch?v=$videoId"
                } else {
                    songIdOrUrl.replace("music.youtube.com", "www.youtube.com")
                }
            } else {
                songIdOrUrl
            }
        } else {
            "https://www.youtube.com/watch?v=$songIdOrUrl"
        }
    }

    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:v=|/v/|youtu\\.be/|/embed/|/watch\\?v=)([^#&?]{11})"),
            Pattern.compile("([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                val candidate = matcher.group(1)
                if (candidate != null && candidate.length == 11) {
                    return candidate
                }
            }
        }
        return null
    }

    fun cleanTitle(rawTitle: String): String {
        var clean = rawTitle
        val bracketsPattern = Pattern.compile("(?i)\\s*[\\[\\(][^\\]\\)]*(?:official|video|audio|visualizer|lyric|letra|videoclip|4k|hd|remastered|explicit)[^\\]\\)]*[\\]\\)]")
        clean = bracketsPattern.matcher(clean).replaceAll("")

        val standalonePattern = Pattern.compile("(?i)\\s*\\b(?:4k|hd|remastered)\\b\\s*")
        clean = standalonePattern.matcher(clean).replaceAll("")

        if (clean.contains(" - ")) {
            clean = clean.substringAfter(" - ").trim()
        }

        return clean.trim()
    }

    fun parseArtistFromTitle(rawTitle: String): String? {
        if (rawTitle.contains(" - ")) {
            val artistPart = rawTitle.substringBefore(" - ").trim()
            if (artistPart.isNotEmpty()) {
                return artistPart
            }
        }
        return null
    }

}
