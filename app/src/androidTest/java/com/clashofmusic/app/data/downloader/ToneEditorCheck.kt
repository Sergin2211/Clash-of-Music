package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.data.media.*
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.presentation.components.SongCard
import com.clashofmusic.app.presentation.components.ToneEditorDialog
import com.clashofmusic.app.presentation.theme.ClashOfMusicTheme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** Real clipping, preview and MediaStore. Default ringtone/alarm writes are simulated. */
class ToneEditorCheck(private val instrumentation: Instrumentation) {
    private fun find(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        repeat(node.childCount) { find(text, node.getChild(it))?.let { found -> return found } }
        return null
    }
    private fun allNodes(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else buildList { add(node); repeat(node.childCount) { addAll(allNodes(node.getChild(it))) } }
    private fun click(text: String, long: Boolean = false) {
        var node = find(text) ?: error("Missing: $text")
        repeat(8) {
            if (if (long) node.isLongClickable else node.isClickable) {
                check(node.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)); return
            }
            node = node.parent ?: error("No action: $text")
        }
        error("No action: $text")
    }
    fun run() {
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "tone_check_${UUID.randomUUID()}")
        val report = JSONObject()
        val checks = JSONArray()
        val actual = AndroidToneSettings(context)
        val originals = ToneTarget.entries.associateWith { actual.current(it) }
        val permissionBefore = actual.canWrite()
        val owned = mutableSetOf<Uri>()
        var host: MainActivity? = null
        var success = false
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { label }; Thread.sleep(25) }
            checks.put(label)
        }
        class TestSettings(var allowed: Boolean = true, val fail: Boolean = false, val partial: Boolean = false) : ToneSettings {
            val values = mutableMapOf<ToneTarget, Uri?>()
            val written = mutableListOf<Pair<ToneTarget, Uri>>()
            override fun canWrite() = allowed
            override fun current(target: ToneTarget) = values[target]
            override fun set(target: ToneTarget, uri: Uri?) {
                uri?.let { owned.add(it); written.add(target to it) }
                if (fail && uri != null) throw java.io.IOException("Deliberate test failure")
                values[target] = uri
                if (partial && uri != null) throw java.io.IOException("Deliberate failure after settings change")
            }
        }
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        fun checkTone(uri: Uri, target: ToneTarget) {
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_MUSIC, MediaStore.Audio.Media.IS_RINGTONE,
                MediaStore.Audio.Media.IS_ALARM, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.MIME_TYPE), null, null, null)!!.use {
                check(it.moveToFirst())
                check(it.getInt(0) == 0)
                check(it.getInt(1) == if (target == ToneTarget.RINGTONE) 1 else 0)
                check(it.getInt(2) == if (target == ToneTarget.ALARM) 1 else 0)
                check(it.getString(3).startsWith(if (target == ToneTarget.RINGTONE) "Ringtones/" else "Alarms/"))
                check(it.getString(4) == "audio/mpeg")
            }
        }
        try {
            check(dir.mkdirs())
            val samples = 48000 * 6
            val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            bytes.putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { i ->
                val frequency = when { i < 96000 -> 440; i < 192000 -> 880; else -> 1760 }
                bytes.putShort((sin(2 * PI * frequency * i / 48000.0) * 10000).toInt().toShort())
            }
            val source = File(dir, "source.wav").apply { writeBytes(bytes.array()) }
            val originalHash = hash(source)
            val song = DownloadedSong("tone-fixture", "tone-fixture", "Private tone test", "Private artist", null, 6,
                null, source.absolutePath, Uri.fromFile(source).toString(), "WAV", fileSizeBytes = source.length(), downloadedAt = 1)
            val range = ToneSelection(2250, 3750)
            val service = ToneClipService(context)
            val fake = TestSettings()
            runBlocking {
                val processor = NativeAudioProcessor(context)
                check(service.duration(song, DownloadSignal()) == 6000L)
                service.withClip(song, range, DownloadSignal()) { clip ->
                    val probe = processor.probe(clip, DownloadSignal())
                    check(probe.codec == "mp3" && abs(probe.durationMs - 1500) < 200)
                    val raw = File(dir, "selected.pcm")
                    processor.run("libffmpeg.so", listOf("-y", "-v", "error", "-i", clip.absolutePath,
                        "-f", "s16le", "-ac", "1", "-ar", "16000", raw.absolutePath), DownloadSignal())
                    val pcm = ByteBuffer.wrap(raw.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val signal = DoubleArray(pcm.remaining() / 2) { pcm.short.toDouble() }
                    fun energy(frequency: Int): Double {
                        var real = 0.0; var imaginary = 0.0
                        signal.forEachIndexed { index, value ->
                            val angle = 2 * PI * frequency * index / 16000.0
                            real += value * cos(angle); imaginary += value * sin(angle)
                        }
                        return real * real + imaginary * imaginary
                    }
                    check(energy(880) > maxOf(energy(440), energy(1760)) * 50)
                    checks.put("trim-duration-and-audio-content-match-selected-middle-fragment")
                    for (target in ToneTarget.entries) {
                        val uri = ToneInstaller(context, fake).install(clip, "Private clip check", "Test", target)
                        owned.add(uri); checkTone(uri, target)
                    }
                    checks.put("ringtone-and-alarm-files-have-correct-mediastore-paths-and-flags")
                    val denied = TestSettings(allowed = false)
                    try { ToneInstaller(context, denied).install(clip, "Denied", "Test", ToneTarget.RINGTONE); error("Permission bypass") }
                    catch (_: IllegalStateException) { check(denied.written.isEmpty()) }
                    checks.put("permission-denied-does-not-apply-a-tone")
                    val failure = TestSettings(fail = true)
                    try { ToneInstaller(context, failure).install(clip, "Failure", "Test", ToneTarget.RINGTONE); error("Expected failure") }
                    catch (_: java.io.IOException) { }
                    val failedUri = failure.written.single().second
                    context.contentResolver.query(failedUri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)!!.use { check(!it.moveToFirst()) }
                    checks.put("failed-settings-write-removes-new-file-without-touching-previous-tone")
                    val partial = TestSettings(partial = true)
                    try { ToneInstaller(context, partial).install(clip, "Partial failure", "Test", ToneTarget.RINGTONE); error("Expected partial failure") }
                    catch (_: java.io.IOException) { }
                    check(partial.current(ToneTarget.RINGTONE) == null)
                    context.contentResolver.query(partial.written.single().second, arrayOf(MediaStore.Audio.Media._ID), null, null, null)!!.use { check(!it.moveToFirst()) }
                    checks.put("partial-settings-failure-restores-previous-value-and-removes-copy")
                }
                for (format in listOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OPUS)) {
                    val encoded = processor.process(source, format, AudioQuality.BEST_320K, emptyMap(), DownloadSignal()).file
                    service.withClip(song.copy(filePath = encoded.absolutePath), range, DownloadSignal()) { clip ->
                        check(abs(processor.probe(clip, DownloadSignal()).durationMs - 1500) < 200)
                    }
                    checks.put("clip-input-${format.name}")
                }
                val webm = File(dir, "original.webm")
                processor.run("libffmpeg.so", listOf("-y", "-v", "error", "-i", source.absolutePath, "-c:a", "libopus", webm.absolutePath), DownloadSignal())
                service.withClip(song.copy(filePath = webm.absolutePath), range, DownloadSignal()) { check(it.length() > 0) }
                checks.put("clip-original-webm-opus")
                val content = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                service.withClip(song.copy(filePath = "", fileUri = content.toString()), range, DownloadSignal()) { check(it.length() > 0) }
                checks.put("content-uri-source-is-copied-privately")
                val cancelled = DownloadSignal().apply { cancel() }
                try { service.withClip(song, range, cancelled) { error("Cancelled clip ran") }; error("Expected cancellation") }
                catch (_: CancellationException) { }
                checks.put("cancelled-clip-does-not-publish")
            }
            host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = host
            var previewStarts = 0
            instrumentation.runOnMainSync {
                activity.setContent { ClashOfMusicTheme(ServiceLocator.preferencesRepository.currentSettings().themeMode) {
                    var editor by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().padding(24.dp, 60.dp)) {
                        SongCard(song.title, song.artist, null, 6, onClick = {}, isDownloaded = true, onSetAsClick = { editor = true })
                    }
                    if (editor) ToneEditorDialog(song, onDismiss = { editor = false }, installer = ToneInstaller(context, fake),
                        previewVolume = 0f, onBeforePreview = { previewStarts++ })
                } }
            }
            await("downloaded-song-row-is-visible") { find(song.title) != null }
            click(song.title, long = true)
            await("long-press-offers-set-as") { find("Establecer como…") != null }
            click("Establecer como…")
            await("tone-editor-reads-source-duration") { find("Duración: 0:06") != null }
            val fields = allNodes().filter { it.isEditable }.sortedBy { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.centerX() }
            check(fields.size == 2)
            fun edit(node: AccessibilityNodeInfo, value: String) = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
            check(edit(fields[0], "0:02.25")); check(edit(fields[1], "0:03.75"))
            await("editor-accepts-exact-fragment-times") { find("Fragmento: 0:01.5") != null }
            click("Escuchar fragmento")
            await("preview-plays-selected-fragment") { find("Detener preescucha") != null }
            await("preview-stops-at-fragment-end") { find("Escuchar fragmento") != null && previewStarts == 1 }
            click("Alarma")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "tone_editor_check.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            click("Establecer")
            await("editor-applies-only-selected-alarm-target") { find("Alarma predeterminada establecida.") != null }
            check(fake.written.last().first == ToneTarget.ALARM)
            checkTone(fake.written.last().second, ToneTarget.ALARM)
            val deniedUi = TestSettings(allowed = false)
            instrumentation.runOnMainSync {
                activity.setContent { ClashOfMusicTheme(ServiceLocator.preferencesRepository.currentSettings().themeMode) {
                    ToneEditorDialog(song, onDismiss = {}, installer = ToneInstaller(context, deniedUi), previewVolume = 0f, onBeforePreview = {})
                } }
            }
            await("permission-test-editor-ready") { find("Duración: 0:06") != null &&
                find("Android pedirá permiso para modificar el tono al establecerlo.") != null &&
                allNodes().filter { it.isEditable }.let { it.size == 2 && it.all { field -> field.isEnabled } } }
            val deniedFields = allNodes().filter { it.isEditable }.sortedBy { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.centerX() }
            check(edit(deniedFields[0], "0:02.25")); check(edit(deniedFields[1], "0:03.75"))
            await("permission-test-fragment-ready") { find("Fragmento: 0:01.5") != null }
            click("Establecer")
            await("system-write-settings-page-opens") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            await("denied-permission-returns-without-changing-tone") { find("No se concedió el permiso. Tu tono no cambió.") != null }
            check(deniedUi.written.isEmpty())
            check(allNodes().filter { it.isEditable }.any { it.text?.contains("0:02.25") == true })
            checks.put("permission-return-preserves-fragment-selection")
            check(hash(source) == originalHash)
            checks.put("original-audio-bytes-unchanged")
            check(ToneTarget.entries.all { actual.current(it) == originals[it] } && actual.canWrite() == permissionBefore)
            checks.put("real-default-tones-and-permission-unchanged")
            success = true
        } catch (failure: Throwable) { report.put("error", android.util.Log.getStackTraceString(failure)) }
        finally {
            instrumentation.runOnMainSync { host?.setContent { }; host?.finish() }
            for (uri in owned) {
                if (ToneTarget.entries.none { actual.matches(it, uri) }) runCatching { context.contentResolver.delete(uri, null, null) }
            }
            check(dir.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            report.put("fixturesRemoved", dir.deleteRecursively())
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "tone_editor_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
