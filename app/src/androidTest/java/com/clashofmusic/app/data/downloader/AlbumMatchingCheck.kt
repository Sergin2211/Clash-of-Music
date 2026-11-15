package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.clashofmusic.app.data.repository.MusicRepositoryImpl
import com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource
import com.clashofmusic.app.domain.usecase.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Reads online catalog/search metadata only. No queue, library, audio or playlist mutations. */
class AlbumMatchingCheck(private val instrumentation: Instrumentation,
    private val albumQuery: String = "Ado Uta", private val trackTitle: String? = null) {
    fun run() {
        val report = JSONObject()
        val checks = JSONArray()
        var success = false
        try {
            runBlocking {
                val music = MusicRepositoryImpl(YouTubeExtractorDataSource())
                val resolver = ResolveAlbumTrack(music)
                val albums = music.searchAlbums(albumQuery).getOrThrow()
                val album = albums.firstOrNull {
                    if (albumQuery == "Ado Uta") it.artist.equals("Ado", true) && it.title.contains("Uta", true)
                    else it.title.contains(albumQuery, true)
                }
                    ?: error("No se pudo localizar el álbum de prueba")
                val tracks = music.getAlbumTracks(album.id).getOrThrow()
                check(tracks.isNotEmpty())
                checks.put(JSONObject().put("check", "online-catalog-album").put("title", album.title).put("tracks", tracks.size))
                val selected = if (trackTitle != null) tracks.filter { it.title.contains(trackTitle, true) }
                    else if (albumQuery != "Ado Uta") tracks
                    else (tracks.filter { it.title.contains("Tot Musica", true) } + tracks.take(2)).distinctBy { it.title }
                check(selected.isNotEmpty()) { "No se localizó la pista de prueba: $trackTitle; ${tracks.map { it.title }}" }
                var accepted = 0
                for (track in selected) {
                    val request = TrackMatchRequest(track.title, track.artist, track.durationSeconds)
                    if (trackTitle != null) {
                        val searches = JSONArray()
                        for (query in listOf("${track.artist} - ${track.title}", "${track.artist} ${track.title} official audio")) {
                            val candidates = music.search(query).getOrThrow()
                            searches.put(JSONObject().put("query", query).put("candidates", JSONArray().apply {
                                candidates.take(15).forEach { song -> put(JSONObject().put("id", song.id).put("title", song.title)
                                    .put("artist", song.artist).put("duration", song.durationSeconds)
                                    .put("sourceTitle", song.sourceTitle).put("channelName", song.channelName)) }
                            }))
                        }
                        report.put("searches", searches)
                    }
                    val result = resolver.resolve(request)
                    val row = JSONObject().put("requestedTitle", track.title).put("requestedArtist", track.artist)
                        .put("requestedDuration", track.durationSeconds)
                    result.song?.let {
                        accepted++
                        row.put("chosenTitle", it.title).put("chosenArtist", it.artist).put("chosenDuration", it.durationSeconds).put("id", it.id)
                        check(AlbumTrackMatcher.match(request, listOf(it)).song != null)
                        if (album.title.equals("KEISOUDO", true) && track.title.equals("Hippocampal Pain", true)) {
                            check(it.id == "PLG2Uexyi9s") { "No se eligió el resultado de estudio oficial: ${it.id}" }
                        }
                    } ?: row.put("omitted", result.issue?.name)
                    checks.put(row)
                }
                check(accepted == selected.size) { "La prueba dejó pistas fuera pese a disponer de resultados" }
                report.put("accepted", accepted)
            }
            success = true
        } catch (error: Throwable) { report.put("error", android.util.Log.getStackTraceString(error)) }
        report.put("success", success).put("checks", checks)
        File(instrumentation.targetContext.filesDir, "album_matching_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }
}
