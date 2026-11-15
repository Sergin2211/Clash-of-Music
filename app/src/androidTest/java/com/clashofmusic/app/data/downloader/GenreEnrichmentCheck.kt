package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Explicit library genre enrichment: only supplemental metadata, with audio hashes checked before/after. */
class GenreEnrichmentCheck(private val instrumentation: Instrumentation, private val refresh: Boolean = false) {
    fun run() {
        val report = JSONObject(); var success = false
        fun hashes(songs: List<DownloadedSong>) = songs.associate { song ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(song.filePath).inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            song.songId to digest.digest().joinToString("") { "%02x".format(it) }
        }
        try {
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val library = ServiceLocator.libraryRepository
            if (refresh) runBlocking {
                library.observeDownloadedSongs().first().filter { it.genreProfile != null && !it.genreProfile!!.manual }
                    .forEach { library.updateGenreProfile(it.songId, null) }
            }
            val before = runBlocking { library.observeDownloadedSongs().first() }
            val originalHashes = hashes(before)
            report.put("beforeCount", before.size).put("beforeWithGenre", before.count { it.genreNames.isNotEmpty() })
            val manager = ServiceLocator.genreManager
            manager.start()
            val deadline = SystemClock.elapsedRealtime() + 20 * 60000
            var seenRunning = false; var lastDone = -1
            while (SystemClock.elapsedRealtime() < deadline) {
                val state = manager.state.value
                if (state.running) seenRunning = true
                if (seenRunning && !state.running) break
                if (state.done != lastDone) {
                    lastDone = state.done
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Géneros: ${state.done}/${state.total}; con datos nuevos: ${state.found}; errores: ${state.errors}\n") })
                }
                Thread.sleep(1000)
            }
            check(!manager.state.value.running) { "Se agotó el tiempo de análisis" }
            val after = runBlocking { library.observeDownloadedSongs().first() }
            check(before.associate { it.songId to it.copy(genreProfile = null) } == after.associate { it.songId to it.copy(genreProfile = null) })
            check(originalHashes == hashes(after)) { "Audio hashes changed" }
            check(ServiceLocator.database.openHelper.readableDatabase.version == 3)
            report.put("audioHashesUnchanged", true).put("legacySongMetadataUnchanged", true).put("roomSchemaUnchanged", true)
                .put("afterCount", after.size).put("afterWithGenre", after.count { it.genreNames.isNotEmpty() })
                .put("recordingEvidence", after.count { it.genreAssignments.any { e -> e.origin == GenreOrigin.RECORDING } })
                .put("artistReferences", after.count { it.genreAssignments.any { e -> e.origin == GenreOrigin.ARTIST } })
                .put("genres", JSONArray(after.flatMap { it.genreNames }.distinct().sorted()))
                .put("message", manager.state.value.message).put("processed", manager.state.value.done).put("errors", manager.state.value.errors)
            success = true
        } catch (e: Throwable) { runCatching { ServiceLocator.genreManager.cancel() }; report.put("error", android.util.Log.getStackTraceString(e)) }
        report.put("success", success)
        File(instrumentation.targetContext.filesDir, "genre_enrichment_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
