package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import com.clashofmusic.app.domain.model.*
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Dependency-free device checks. All audio is generated in private cache, never in the library. */
class DownloadSmokeInstrumentation : Instrumentation() {
    private var sourceUrl: String? = null
    private var notificationOnly = false
    private var downloadAlertsOnly = false
    private var vaultOnly = false
    private var organizationOnly = false
    private var matchingOnly = false
    private var uiOnly = false
    private var resumeOnly = false
    private var libraryOnly = false
    private var progressOnly = false
    private var preparationOnly = false
    private var visualOnly = false
    private var toneOnly = false
    private var playlistOnly = false
    private var reorderOnly = false
    private var cancelAllOnly = false
    private var genresOnly = false
    private var enrichGenresOnly = false
    private var refreshGenres = false
    private var radioOnly = false
    private var gesturesOnly = false
    private var albumControlsOnly = false
    private var artworkOnly = false
    private var radioOnline = false
    private var radio9Lana = false
    private var radioZutomayo = false
    private var albumQuery = "Ado Uta"
    private var trackTitle: String? = null
    override fun onCreate(arguments: Bundle?) {
        sourceUrl = arguments?.getString("sourceUrl")
        notificationOnly = arguments?.getString("notificationOnly") == "true"
        downloadAlertsOnly = arguments?.getString("downloadAlertsOnly") == "true"
        vaultOnly = arguments?.getString("vaultOnly") == "true"
        organizationOnly = arguments?.getString("organizationOnly") == "true"
        matchingOnly = arguments?.getString("matchingOnly") == "true"
        uiOnly = arguments?.getString("uiOnly") == "true"
        resumeOnly = arguments?.getString("resumeOnly") == "true"
        libraryOnly = arguments?.getString("libraryOnly") == "true"
        progressOnly = arguments?.getString("progressOnly") == "true"
        preparationOnly = arguments?.getString("preparationOnly") == "true"
        visualOnly = arguments?.getString("visualOnly") == "true"
        toneOnly = arguments?.getString("toneOnly") == "true"
        playlistOnly = arguments?.getString("playlistOnly") == "true"
        reorderOnly = arguments?.getString("reorderOnly") == "true"
        cancelAllOnly = arguments?.getString("cancelAllOnly") == "true"
        genresOnly = arguments?.getString("genresOnly") == "true"
        enrichGenresOnly = arguments?.getString("enrichGenresOnly") == "true"
        refreshGenres = arguments?.getString("refreshGenres") == "true"
        radioOnly = arguments?.getString("radioOnly") == "true"
        gesturesOnly = arguments?.getString("gesturesOnly") == "true"
        albumControlsOnly = arguments?.getString("albumControlsOnly") == "true"
        artworkOnly = arguments?.getString("artworkOnly") == "true"
        radioOnline = arguments?.getString("radioOnline") == "true"
        radio9Lana = arguments?.getString("radio9Lana") == "true"
        radioZutomayo = arguments?.getString("radioZutomayo") == "true"
        albumQuery = arguments?.getString("albumQuery") ?: "Ado Uta"
        trackTitle = arguments?.getString("trackTitle")
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        if (organizationOnly) { VaultOrganizationCheck(this).run(); return }
        if (downloadAlertsOnly) { DownloadAlertsCheck(this).run(); return }
        if (gesturesOnly) { SwipeMotionCheck(this).run(); return }
        if (albumControlsOnly) { AlbumControlsCheck(this).run(); return }
        if (artworkOnly) { ArtworkFallbackCheck(this).run(); return }
        if (radioOnly) { RadioPlaybackCheck(this, radioOnline, radio9Lana, radioZutomayo).run(); return }
        if (enrichGenresOnly) { GenreEnrichmentCheck(this, refreshGenres).run(); return }
        if (genresOnly) { GenreManagementCheck(this).run(); return }
        if (cancelAllOnly) { CancelAllDownloadsCheck(this).run(); return }
        if (reorderOnly) { PlaylistReorderCheck(this).run(); return }
        if (playlistOnly) {
            PlaylistReadCheck(this, sourceUrl ?: "https://www.youtube.com/playlist?list=PLT6XS3iF17IQTmie-UvRdWzC0VS41nQmQ").run()
            return
        }
        if (toneOnly) {
            ToneEditorCheck(this).run()
            return
        }
        if (visualOnly) {
            LibraryVisualCheck(this).run()
            return
        }
        if (preparationOnly) {
            AlbumPreparationCheck(this).run()
            return
        }
        if (progressOnly) {
            DownloadProgressLoadCheck(this).run()
            return
        }
        if (libraryOnly) {
            LibraryBatchCheck(this).run()
            return
        }
        if (resumeOnly) {
            DownloadResumeCheck(this, sourceUrl).run()
            return
        }
        if (uiOnly) {
            NavigationPlayerCheck(this).run()
            return
        }
        if (matchingOnly) {
            AlbumMatchingCheck(this, albumQuery, trackTitle).run()
            return
        }
        if (vaultOnly) {
            VaultDocumentCheck(this).run()
            return
        }
        if (notificationOnly) {
            NotificationPlaybackCheck(this).run()
            return
        }
        val report = JSONObject().put("checks", JSONArray())
        val results = Bundle()
        val directory = File(targetContext.cacheDir, "download_smoke_${UUID.randomUUID()}")
        try {
            check(directory.mkdirs())
            runBlocking {
                val context = targetContext
                // Build a deterministic PCM WAV without initializing FFmpeg first: this also tests cold startup.
                val fixture = File(directory, "fixture.wav")
                val sampleBytes = 4 * 48000 * 2 * 2
                val wav = java.nio.ByteBuffer.allocate(44 + sampleBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                wav.put("RIFF".toByteArray()); wav.putInt(36 + sampleBytes); wav.put("WAVEfmt ".toByteArray())
                wav.putInt(16); wav.putShort(1); wav.putShort(2); wav.putInt(48000); wav.putInt(192000)
                wav.putShort(4); wav.putShort(16); wav.put("data".toByteArray()); wav.putInt(sampleBytes)
                val random = kotlin.random.Random(42)
                repeat(sampleBytes / 2) { wav.putShort(random.nextInt(-8000, 8000).toShort()) }
                fixture.writeBytes(wav.array())
                val processor = NativeAudioProcessor(context)
                for (format in listOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OPUS)) {
                    for (quality in AudioQuality.entries) {
                        val audio = processor.process(fixture, format, quality, mapOf("title" to "Clash test"), DownloadSignal())
                        check(audio.file.extension == format.extension)
                        check(audio.probe.durationMs in 3900L..4300L)
                        check(audio.quality == audio.probe.quality)
                        report.getJSONArray("checks").put(JSONObject().put("format", format.name).put("target", quality.bitrate)
                            .put("codec", audio.probe.codec).put("measuredBitrate", audio.probe.bitrate)
                            .put("bytes", audio.file.length()))
                    }
                    val sameFormatSource = File(directory, "already.${format.extension}")
                    File(directory, "encoded.${format.extension}").copyTo(sameFormatSource)
                    val again = processor.process(sameFormatSource, format, AudioQuality.BEST_320K, emptyMap(), DownloadSignal())
                    check(again.quality == again.probe.quality)
                    fun packetHash(file: File) = runBlocking { processor.run("libffmpeg.so", listOf("-v", "error", "-i", file.absolutePath,
                        "-map", "0:a:0", "-c:a", "copy", "-f", "hash", "-hash", "sha256", "-"), DownloadSignal()).trim() }
                    check(packetHash(sameFormatSource) == packetHash(again.file)) { "Compatible audio was re-encoded" }
                    report.getJSONArray("checks").put("copy-compatible-${format.name}")
                }
                val webmSource = File(directory, "source-opus.webm")
                processor.run("libffmpeg.so", listOf("-y", "-v", "error", "-i", fixture.absolutePath,
                    "-c:a", "libopus", "-b:a", "128k", webmSource.absolutePath), DownloadSignal())
                val remuxed = processor.process(webmSource, AudioFormat.OPUS, AudioQuality.BEST_320K, emptyMap(), DownloadSignal())
                fun packets(file: File) = runBlocking { processor.run("libffmpeg.so", listOf("-v", "error", "-i", file.absolutePath,
                    "-map", "0:a:0", "-c:a", "copy", "-f", "hash", "-hash", "sha256", "-"), DownloadSignal()).trim() }
                check(packets(webmSource) == packets(remuxed.file))
                check(remuxed.quality == remuxed.probe.quality)
                report.getJSONArray("checks").put(JSONObject().put("check", "webm-to-opus-keeps-source-packets")
                    .put("measuredBitrate", remuxed.probe.bitrate).put("reportedBitrate", remuxed.quality))
                val editor = com.clashofmusic.app.data.media.NativeMetadataEditor(context)
                val bitmap = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
                val pixels = IntArray(320 * 320) { 0xff000000.toInt() or kotlin.random.Random(it).nextInt(0x1000000) }
                bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 320)
                val image = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                check(image.size > 100000)
                for (format in listOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OPUS)) {
                    val source = File(directory, "metadata.${format.extension}")
                    File(directory, "encoded.${format.extension}").copyTo(source)
                    suspend fun audioHash() = processor.run("libffmpeg.so", listOf("-v", "error", "-i", source.absolutePath,
                        "-map", "0:a:0", "-c:a", "copy", "-f", "hash", "-hash", "sha256", "-"), DownloadSignal()).trim()
                    val beforeAudio = audioHash()
                    val fields = com.clashofmusic.app.data.media.AudioTags("Canción 日本語", "Björk 宇多田", "Álbum; #= test", "2024", "Rock", "metadata-test")
                    editor.rewrite(source, fields, image, true, DownloadSignal())
                    check(audioHash() == beforeAudio) { "Audio payload changed during tagging" }
                    check(editor.readYear(source) == "2024")
                    fun hasCover(): Boolean {
                        val retriever = android.media.MediaMetadataRetriever()
                        return try { retriever.setDataSource(source.absolutePath); retriever.embeddedPicture?.isNotEmpty() == true }
                        finally { retriever.release() }
                    }
                    check(hasCover()) { "Missing embedded artwork: $format" }
                    if (format == AudioFormat.MP3) {
                        val header = source.inputStream().use { it.readNBytes(4) }
                        check(header[3].toInt() == 3) { "ID3 version was not 2.3" }
                    }
                    editor.edit(source, fields.copy(title = "Editada", album = null, year = null)) { }
                    check(editor.readYear(source) == null && editor.readTags(source, DownloadSignal())["album"].isNullOrEmpty())
                    check(hasCover() && audioHash() == beforeAudio) { "Editing lost artwork/audio: $format" }
                    val beforeFailure = source.readBytes()
                    try { editor.edit(source, fields) { error("Simulated database failure") }; error("Commit should fail") }
                    catch (error: IllegalStateException) { check(error.message == "Simulated database failure") }
                    check(source.readBytes().contentEquals(beforeFailure)) { "Rollback changed the file" }
                    report.getJSONArray("checks").put("metadata-${format.name}-unicode-year-cover-audio-hash-and-rollback")
                }
                var playerFailure: Throwable? = null
                var originalVolume = 1f
                runOnMainSync {
                    val manager = com.clashofmusic.app.di.ServiceLocator.playerManager
                    val file = File(directory, "metadata.mp3")
                    val sample = com.clashofmusic.app.domain.model.DownloadedSong("metadata-device-test", "metadata-device-test", "Original", "Test", null,
                        4, null, file.absolutePath, android.net.Uri.fromFile(file).toString(), "MP3", "320k", file.length(), 1)
                    originalVolume = manager.player!!.volume
                    try {
                        manager.player!!.volume = 0f
                        manager.playSong(sample); manager.pause(); manager.addToQueue(sample); manager.seekTo(1234)
                        val before = manager.queueState.value.entries.map { it.id }
                        manager.refreshSongMetadata(sample.copy(title = "Refrescada", artist = "日本語"))
                        check(manager.currentSong.value!!.title == "Refrescada")
                        check(manager.player!!.currentMediaItem!!.mediaMetadata.title.toString() == "Refrescada")
                        check(manager.player!!.currentPosition == 1234L && !manager.player!!.playWhenReady)
                        check(before == manager.queueState.value.entries.map { it.id })
                        check(manager.queueState.value.entries.all { it.song.title == "Refrescada" })
                        manager.stop()
                        manager.playSong(sample.copy(title = "Solicitud reciente")); manager.pause()
                    } catch (error: Throwable) { playerFailure = error }
                }
                delay(1500)
                runOnMainSync {
                    val manager = com.clashofmusic.app.di.ServiceLocator.playerManager
                    try {
                        if (playerFailure == null) check(manager.currentSong.value?.title == "Solicitud reciente" && !manager.player!!.playWhenReady) {
                            "An old stop command cleared the newer playback request"
                        }
                    } catch (error: Throwable) { playerFailure = error }
                    finally { manager.stop(); manager.player!!.volume = originalVolume }
                }
                playerFailure?.let { throw it }
                report.getJSONArray("checks").put("metadata-player-refresh-preserves-position-occurrences-and-pause")
                report.getJSONArray("checks").put("rapid-start-stop-start-preserves-newer-request")
                fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
                val before = digest(fixture)
                val original = processor.process(fixture, AudioFormat.ORIGINAL, AudioQuality.BEST_320K, emptyMap(), DownloadSignal())
                check(original.file == fixture && digest(original.file) == before)
                report.getJSONArray("checks").put("original-bytes-unchanged")
                val invalid = File(directory, "broken.webm").apply { writeText("not audio") }
                check(runCatching { processor.process(invalid, AudioFormat.MP3, AudioQuality.STANDARD_128K, emptyMap(), DownloadSignal()) }.isFailure)
                report.getJSONArray("checks").put("invalid-audio-rejected")
                val cancelled = DownloadSignal().apply { cancel() }
                try {
                    processor.process(fixture, AudioFormat.OPUS, AudioQuality.STANDARD_128K, emptyMap(), cancelled)
                    error("A cancelled conversion ran")
                } catch (_: CancellationException) { report.getJSONArray("checks").put("cancel-before-process") }

                // Exercise the real native fallback against a local fixture server, without internet.
                val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                val serverThread = thread(isDaemon = true) {
                    while (!server.isClosed) {
                        try {
                            val socket = server.accept()
                            thread(isDaemon = true) {
                                runCatching {
                                    socket.use {
                                        val reader = it.getInputStream().bufferedReader()
                                        val first = reader.readLine().orEmpty()
                                        while (!reader.readLine().isNullOrEmpty()) { }
                                        val output = it.getOutputStream()
                                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${fixture.length()}\r\nConnection: close\r\n\r\n").toByteArray())
                                        if (!first.startsWith("HEAD")) fixture.inputStream().use { input ->
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                val read = input.read(buffer); if (read < 0) break
                                                output.write(buffer, 0, read); output.flush(); Thread.sleep(8)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                try {
                    val manager = YoutubeDLManager(context)
                    val url = "http://127.0.0.1:${server.localPort}/fixture.wav"
                    val audio = manager.downloadAudio(url, "Fixture", "Clash test", null, directory,
                        UUID.randomUUID().toString(), DownloadSignal(), AudioFormat.MP3, AudioQuality.STANDARD_128K, true) { _, _, _, _ -> }.getOrThrow()
                    check(audio.probe.codec == "mp3" && audio.quality == audio.probe.quality)
                    report.getJSONArray("checks").put("native-fallback-download-and-conversion")
                    val signal = DownloadSignal()
                    val id = UUID.randomUUID().toString()
                    val started = CompletableDeferred<Unit>()
                    val job = async(Dispatchers.IO) {
                        manager.downloadAudio(url, "Cancel", "Clash test", null, directory,
                            id, signal, AudioFormat.OPUS, AudioQuality.BEST_320K, false) { _, _, _, _ -> started.complete(Unit) }
                    }
                    withTimeout(30000) { started.await() }
                    signal.cancel(); manager.cancel(id); job.cancelAndJoin()
                    check(job.isCancelled)
                    report.getJSONArray("checks").put("cancel-native-download")

                    val queueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    try {
                        val startedQueue = CompletableDeferred<Unit>()
                        val active = java.util.concurrent.atomic.AtomicInteger()
                        val peak = java.util.concurrent.atomic.AtomicInteger()
                        val attempts = java.util.concurrent.atomic.AtomicInteger()
                        val store = FileDownloadTaskStore(File(directory, "queue.json"))
                        val queue = DownloadQueue(queueScope, store, emptyList(), worker = { attempt ->
                            attempts.incrementAndGet()
                            val count = active.incrementAndGet()
                            peak.updateAndGet { maxOf(it, count) }
                            try {
                                val result = manager.downloadAudio(url, "Queue fixture", "Clash test", null, directory,
                                    attempt.id, attempt.signal, AudioFormat.M4A, AudioQuality.MEDIUM_192K, false) { progress, _, _, text ->
                                    attempt.update { it.copy(progress = progress, statusText = text) }
                                    startedQueue.complete(Unit)
                                }.getOrThrow()
                                check(result.probe.codec == "aac")
                                attempt.commit { attempt.update { it.copy(status = DownloadStatus.COMPLETED, progress = 100f) } }
                            } finally { active.decrementAndGet() }
                        }, stopNative = manager::cancel)
                        queue.enqueue(DownloadTask("queue-smoke", "queue-smoke", "Private fixture", "Test", url = url,
                            format = AudioFormat.M4A, quality = AudioQuality.MEDIUM_192K))
                        withTimeout(30000) { startedQueue.await() }
                        queue.pause("queue-smoke"); queue.resume("queue-smoke")
                        withTimeout(60000) {
                            while (queue.tasks.value.single().status != DownloadStatus.COMPLETED) {
                                check(queue.tasks.value.single().status != DownloadStatus.FAILED) { queue.tasks.value.single().errorMessage.orEmpty() }
                                delay(50)
                            }
                        }
                        check(peak.get() == 1 && attempts.get() == 2)
                        report.getJSONArray("checks").put("native-queue-pause-immediate-resume-without-overlap")
                        val saved = store.load().single().copy(status = DownloadStatus.DOWNLOADING)
                        store.save(listOf(saved))
                        var recoveredExecuted = false
                        val recovered = DownloadQueue(queueScope, store, store.load(), worker = { recoveredExecuted = true })
                        check(recovered.tasks.value.single().status == DownloadStatus.PAUSED && !recoveredExecuted)
                        check(recovered.tasks.value.single().quality == AudioQuality.MEDIUM_192K)
                        report.getJSONArray("checks").put("native-queue-persistence-and-safe-recovery")
                    } finally { queueScope.cancel() }
                } finally { server.close(); serverThread.join(2000) }
            }
                sourceUrl?.let { online ->
                    runBlocking {
                        val extractor = com.clashofmusic.app.data.extractor.YouTubeExtractorDataSource()
                        val album = extractor.extractPlaylist("https://music.youtube.com/playlist?list=OLAK5uy_l8fQyUWZfOO25E9HeDeeJCIDm0tsxwguc").getOrThrow()
                        check(album.isAlbum && album.tracks.size == 14)
                        report.getJSONArray("checks").put("youtube-music-album-still-has-14-tracks")
                        val details = extractor.getStreamDetails(online).getOrThrow()
                        check(details.album == null || details.album!!.length <= 180 && !details.album!!.contains("<br>"))
                        report.getJSONArray("checks").put("youtube-details-album-is-not-html-description")
                        val manager = YoutubeDLManager(targetContext, extractor)
                        val result = withTimeout(120000) {
                            manager.downloadAudio(online, "Source test", "Clash test", null, directory,
                                UUID.randomUUID().toString(), DownloadSignal(), AudioFormat.MP3, AudioQuality.STANDARD_128K, false) { _, _, _, _ -> }
                                .getOrThrow()
                        }
                        check(result.probe.codec == "mp3" && result.quality == result.probe.quality)
                        report.getJSONArray("checks").put(JSONObject().put("check", "online-source-to-mp3")
                            .put("codec", result.probe.codec).put("measuredBitrate", result.probe.bitrate)
                            .put("durationMs", result.probe.durationMs).put("bytes", result.file.length()))
                    }
                }
            report.put("success", true)
            results.putString("stream", report.toString(2))
            File(targetContext.filesDir, "download_smoke_report.json").writeText(report.toString(2))
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            Log.e("DownloadSmoke", "Device checks failed", error)
            report.put("success", false).put("error", error.toString())
            File(targetContext.filesDir, "download_smoke_report.json").writeText(report.toString(2))
            results.putString("stream", report.toString(2))
            finish(Activity.RESULT_CANCELED, results)
        } finally { directory.deleteRecursively() }
    }
}
