package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.data.extractor.*
import com.clashofmusic.app.data.player.*
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import com.clashofmusic.app.presentation.components.RadioDownloadButton
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Real Media3/cache checks with private audio, plus an optional real YouTube radio. Never saves songs. */
@androidx.annotation.OptIn(UnstableApi::class)
class RadioPlaybackCheck(private val instrumentation: Instrumentation, private val online: Boolean, private val nineLana: Boolean = false, private val zutomayo: Boolean = false) {
    private fun <T> main(block: () -> T): T { var value: Result<T>? = null; instrumentation.runOnMainSync { value = runCatching(block) }; return value!!.getOrThrow() }
    private fun await(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
        error("Timeout: $label")
    }
    private inline fun <reified T> repo(crossinline handler: (String) -> Any?): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> handler(method.name) } as T
    private fun song(n: Int) = SongItem(n.toString().padStart(11, '0'), "Private radio $n", "Artist ${n % 8}", null, 180, "https://youtube.com/watch?v=${n.toString().padStart(11, '0')}").toRadioSong()
    private fun wav(): ByteArray {
        val size = 44100 * 2 * 20
        return ByteBuffer.allocate(size + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(0) }
        }.array()
    }
    fun run() {
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "radio_check_${UUID.randomUUID()}").apply { mkdirs() }
        val report = JSONObject(); val checks = JSONArray(); var success = false
        var cache: RadioAudioCache? = null; var manager: MusicPlayerManager? = null
        try {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val before = runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() }
            fun hashes() = before.associate { it.filePath to MessageDigest.getInstance("SHA-256").digest(File(it.filePath).readBytes()).joinToString("") { b -> "%02x".format(b) } }
            report.put("songsChecked", before.size)
            val original = hashes()
            val extractor = YouTubeExtractorDataSource()
            val payload = wav()
            var reads = 0
            val resolvedIds = java.util.Collections.synchronizedList(mutableListOf<String>())
            cache = RadioAudioCache(context, extractor, File(dir, "audio"), 4L * 1024 * 1024,
                resolve = { id -> resolvedIds.add(id); ResolvedAudioStream("https://fixture.invalid/audio.wav?itag=1", "wav", 705) },
                upstream = DataSource.Factory {
                    val data = ByteArrayDataSource(payload)
                    object : DataSource by data { override fun open(spec: DataSpec): Long { reads++; return data.open(spec) } }
                })
            val audio = cache
            audio.updateWindow((0..15).map { song(it).songId }, emptyList())
            runBlocking { audio.awaitIdle() }
            fun read(id: String): Long {
                val source = audio.dataSourceFactory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri("clashradio://track/$id").setKey(id).build())
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) return total; total += n }
                } finally { source.close() }
            }
            check(read(song(0).songId) == payload.size.toLong())
            val requests = reads
            check(read(song(0).songId) == payload.size.toLong() && requests == reads)
            checks.put("cached-blocks-replay-without-network")
            repeat(16) { check(read(song(it).songId) == payload.size.toLong()); check(audio.bytes.value <= 4L * 1024 * 1024) }
            check(audio.directory.walkTopDown().filter { it.isFile && it.extension == "exo" }.sumOf { it.length() } <= 4L * 1024 * 1024)
            checks.put("lru-replacement-and-strict-audio-byte-budget")
            audio.updateWindow((5..20).map { song(it).songId }, emptyList())
            runBlocking { audio.awaitIdle() }
            check(audio.cachedKeys().all { it in (5..20).map { n -> song(n).songId } })
            checks.put("leaving-sixteen-track-window-deletes-old-files")
            audio.clear(); runBlocking { audio.awaitIdle() }; resolvedIds.clear()
            audio.updateWindow((0..15).map { song(it).songId }, (1..10).map { song(it).songId })
            runBlocking { audio.awaitIdle() }
            check(resolvedIds.toList() == (1..10).map { song(it).songId })
            check(audio.bytes.value <= 4L * 1024 * 1024)
            checks.put("all-ten-ahead-tracks-prefetch-serially-within-byte-limit")
            val local = File(dir, "local.wav").apply { writeBytes(payload) }
            val localSource = audio.dataSourceFactory.createDataSource()
            val keys = audio.cachedKeys()
            try { localSource.open(DataSpec.Builder().setUri(android.net.Uri.fromFile(local)).build()); localSource.read(ByteArray(16), 0, 16) } finally { localSource.close() }
            check(audio.cachedKeys() == keys && local.readBytes().contentEquals(payload))
            checks.put("local-files-bypass-temporary-cache")
            val library = repo<LibraryRepository> { name -> when(name) { "observeDownloadedSongs" -> flowOf(emptyList<DownloadedSong>()); else -> error("Unexpected library access: $name") } }
            val favorites = repo<FavoritesRepository> { name -> when(name) { "isFavorite" -> flowOf(false); else -> error("Unexpected favorite access: $name") } }
            val recommendations = RadioRecommender(object : RadioRecommendationSource {
                override suspend fun related(song: DownloadedSong) = (0..59).map { n -> SongItem(n.toString().padStart(11, '0'), "Private radio $n", "Artist ${n % 8}", null, 180, "https://youtube.com/watch?v=${n.toString().padStart(11, '0')}") }
            })
            manager = main { MusicPlayerManager(context, favorites, library, audio, recommendations, false) }
            val player = manager
            main { player.startRadio(song(0)) }
            await("audio-playing-and-ten-recommendations") { main { player.isPlaying.value && player.queueState.value.upcoming.size == 10 } }
            checks.put("actual-media3-radio-plays-private-audio-and-fills-ahead")
            checkSavingUi(activity, player, checks)
            val visited = mutableSetOf(song(0).songId)
            repeat(14) {
                main { player.playNext() }
                await("refill-$it") { main { !player.radioState.value.loading && player.queueState.value.upcoming.size == 10 } }
                main {
                    check(visited.add(player.currentSong.value!!.songId))
                    check(player.queueState.value.entries.size <= 16)
                    check(player.queueState.value.currentIndex <= 5)
                }
            }
            checks.put("fourteen-next-transitions-no-repeats-bounded-player-timeline")
            val automaticFrom = main { player.currentSong.value!!.songId }
            main { player.seekTo(19700) }
            await("automatic-track-boundary") { main { player.currentSong.value?.songId != automaticFrom && player.isPlaying.value } }
            checks.put("radio-automatically-continues-at-real-audio-boundary")
            val sleepingOn = main { player.currentSong.value!!.songId }
            main { player.setSleepTimer(SleepTimerMode.END_OF_TRACK); player.seekTo(19700) }
            await("sleep-end-of-track") { main { !player.player!!.playWhenReady } }
            check(main { player.currentSong.value!!.songId == sleepingOn })
            checks.put("end-of-track-sleep-stops-without-starting-recommendation")
            main { player.pause() }; Thread.sleep(600)
            check(main { !player.isPlaying.value && !player.player!!.playWhenReady })
            checks.put("pause-does-not-resume-after-recommendation-refill")
            main { player.stop() }; runBlocking { audio.awaitIdle() }
            check(audio.cachedKeys().isEmpty() && audio.bytes.value == 0L)
            checks.put("ending-session-purges-audio-and-queue")
            main { player.startRadio(song(0).copy(filePath = local.absolutePath, fileUri = android.net.Uri.fromFile(local).toString(), format = "WAV")) }
            await("existing-local-seed-radio") { main { player.isPlaying.value && player.queueState.value.upcoming.size == 10 } }
            check(main { player.currentSong.value!!.filePath == local.absolutePath })
            check(song(0).songId !in audio.cachedKeys() && local.readBytes().contentEquals(payload))
            checks.put("radio-reuses-local-seed-without-copying-or-altering-it")
            main { player.stop() }; runBlocking { audio.awaitIdle() }
            main { player.release() }; manager = null
            runBlocking { audio.release() }; cache = null
            // A killed process can leave files. A new instance must erase that prior generation.
            File(dir, "audio/stale-session.exo").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
            val reopened = RadioAudioCache(context, extractor, File(dir, "audio"), resolve = { null })
            cache = reopened
            reopened.clear(); runBlocking { reopened.awaitIdle() }
            await("stale-audio-purged-on-startup") { !File(dir, "audio/stale-session.exo").exists() }
            checks.put("next-start-purges-files-left-by-process-death")
            runBlocking { reopened.release() }; cache = null
            if (online) {
                val query = when { zutomayo -> "ZUTOMAYO Hippocampal Pain official"; nineLana -> "9Lana RASEN official"; else -> "Ado Value official" }
                val result = runBlocking { extractor.search(query).getOrThrow() }
                val seed = result.first { it.durationSeconds in 120..400 &&
                    (when {
                        zutomayo -> it.artist.contains("ZUTOMAYO", true) && (it.title.contains("Hippocampal", true) || it.title.contains("海馬成長痛"))
                        nineLana -> RadioRecommender.artistKey(it.artist) == "9lana"
                        else -> it.title.contains("Value", true)
                    }) }.toRadioSong()
                val realPlayer = ServiceLocator.playerManager
                main { realPlayer.startRadio(seed) }
                await("online-radio-playing", 120000) { main { realPlayer.isPlaying.value } }
                await("online-related-tracks", 120000) { main { realPlayer.queueState.value.upcoming.isNotEmpty() && !realPlayer.radioState.value.loading } }
                val first = main { realPlayer.queueState.value }
                check(first.upcoming.none { Regex("\\b(reacts?|reaction|mashup)\\b", RegexOption.IGNORE_CASE).containsMatchIn("${it.song.title} ${it.song.artist}") })
                report.put("onlineQueue", JSONArray().apply { first.entries.forEach { put(JSONObject().put("title", it.song.title).put("artist", it.song.artist).put("id", it.song.songId)) } })
                val trace = realPlayer.radioMixTrace ?: error("Missing mix provenance")
                check(trace.seedId == seed.songId)
                check(trace.selected.map { it.songId } == first.upcoming.map { it.song.songId })
                check(trace.selected.all { trace.candidateIds.getOrNull(it.sourceRank - 1) == it.songId })
                check(trace.selected.map { it.sourceRank } == trace.selected.map { it.sourceRank }.sorted())
                report.put("mixProvenance", JSONObject(trace.json()))
                checks.put("every-recommendation-belongs-to-the-original-seed-mix-in-provider-order")
                checks.put(when { zutomayo -> "real-zutomayo-hippocampal-pain-stream"; nineLana -> "real-9lana-stream-and-correct-artist-credits"; else -> "real-ado-value-stream-and-musical-recommendations" })
                val target = minOf(150000L, main { realPlayer.durationMs.value } - 10000).coerceAtLeast(0)
                main { realPlayer.seekTo(target) }
                await("online-seek-beyond-prefix", 60000) { main { realPlayer.isPlaying.value && realPlayer.positionMs.value >= target } }
                checks.put("real-radio-seeks-beyond-initial-audio-blocks")
                main { realPlayer.playNext() }
                await("online-next-playing", 90000) { main { realPlayer.isPlaying.value && realPlayer.currentSong.value?.songId != seed.songId } }
                checks.put("real-radio-next-track-plays")
                main { realPlayer.stop() }; runBlocking { realPlayer.radioCache!!.awaitIdle() }
                check(realPlayer.radioCache!!.cachedKeys().isEmpty())
                checks.put("real-radio-session-purges-its-audio")
            }
            check(runBlocking { ServiceLocator.libraryRepository.observeDownloadedSongs().first() } == before)
            check(original == hashes())
            checks.put("personal-library-genres-and-all-audio-hashes-unchanged")
            success = true
        } catch (e: Throwable) { report.put("error", android.util.Log.getStackTraceString(e)) }
        finally {
            main { manager?.release(); if (ServiceLocator.playerManager.radioState.value.active) ServiceLocator.playerManager.stop() }
            runBlocking { cache?.release() }
            check(dir.canonicalFile.parentFile == context.cacheDir.canonicalFile); dir.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "radio_check.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }

    private fun checkSavingUi(activity: MainActivity, player: MusicPlayerManager, checks: JSONArray) {
        val songs = MutableStateFlow(emptyList<DownloadedSong>())
        val tasks = MutableStateFlow(emptyList<DownloadTask>())
        val enqueued = java.util.concurrent.CopyOnWriteArrayList<DownloadTask>()
        val library = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, m, _ ->
            when(m.name) { "observeDownloadedSongs" -> songs; "isSongDownloaded" -> false; else -> error(m.name) }
        } as LibraryRepository
        val downloads = Proxy.newProxyInstance(DownloadRepository::class.java.classLoader, arrayOf(DownloadRepository::class.java)) { _, m, args -> when(m.name) {
            "observeTasks" -> tasks
            "isSongDownloading" -> tasks.value.any { it.songId == args!![0] && it.isActive }
            "enqueueDownload" -> { val t = (args!![0] as DownloadTask).copy(status = DownloadStatus.DOWNLOADING); enqueued.add(t); tasks.value = tasks.value + t; t.id }
            else -> error(m.name)
        } } as DownloadRepository
        val preferences = repo<PreferencesRepository> { name -> if (name == "observeSettings") flowOf(AppSettings(defaultFormat = AudioFormat.OPUS)) else error(name) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val saver = RadioLibrarySaver(library, downloads, preferences, scope)
        fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            repeat(node.childCount) { find(text, node.getChild(it))?.let { return it } }
            return null
        }
        fun clickDownload() {
            var node = find("Descargar a biblioteca") ?: error("Missing download button")
            repeat(8) { if (!node.isClickable && node.parent != null) node = node.parent }
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            // MainActivity can request optional existing permissions even though the private fixtures
            // need none. Cancel our own prompt; do not grant or revoke permissions for the test.
            repeat(2) {
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.permissioncontroller") {
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(300)
                }
            }
            main { activity.setContent { ClashOfMusicTheme { val song by player.currentSong.collectAsState(); song?.let { androidx.compose.runtime.key(it.songId) { RadioDownloadButton(it, saver) } } } } }
            await("private-download-button") { find("Descargar a biblioteca") != null }
            val first = main { player.currentSong.value!! }
            clickDownload()
            await("download-queued-without-pausing") { enqueued.size == 1 && main { player.isPlaying.value } && find("Descargando…") != null }
            check(enqueued[0].format == AudioFormat.OPUS && enqueued[0].songId == first.songId && enqueued[0].url == "https://www.youtube.com/watch?v=${first.songId}")
            checks.put("radio-download-button-uses-default-format-without-pausing")
            main { player.playNext() }
            await("new-track-has-independent-save-button") { find("Descargar a biblioteca") != null }
            val second = main { player.currentSong.value!! }
            clickDownload()
            await("second-song-captured-correctly") { enqueued.size == 2 }
            check(enqueued[1].songId == second.songId && second.songId != first.songId)
            checks.put("switching-tracks-saves-the-visible-song")
            songs.value = listOf(second)
            tasks.value = tasks.value.map { it.copy(status = DownloadStatus.COMPLETED) }
            await("saved-label-updates") { find("En biblioteca") != null }
            check(main { player.isPlaying.value })
            checks.put("saved-status-refreshes-without-touching-personal-library")
        } finally { scope.cancel(); main { activity.setContent { } } }
    }
}
