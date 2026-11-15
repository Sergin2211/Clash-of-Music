package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import androidx.media3.common.Player
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadedSong
import com.clashofmusic.app.services.MusicPlaybackService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import com.clashofmusic.app.data.player.MusicPlayerManager
import com.clashofmusic.app.domain.repository.LibraryRepository

/** Exercises the real PendingIntents and platform controller, using only private synthetic audio. */
class NotificationPlaybackCheck(private val instrumentation: Instrumentation) {
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "Timeout: $label; actions=${notification()?.actions?.map { it.title }}; " +
                    main { "playWhenReady=${ServiceLocator.playerManager.player!!.playWhenReady}, state=${ServiceLocator.playerManager.player!!.playbackState}" }
            }
            Thread.sleep(100)
        }
        checks.put(label)
    }
    private fun notification() = notifications.activeNotifications.firstOrNull {
        it.id == MusicPlaybackService.NOTIFICATION_ID
    }?.notification
    private fun action(playing: Boolean): Notification.Action? {
        val title = context.getString(if (playing) androidx.media3.session.R.string.media3_controls_pause_description
            else androidx.media3.session.R.string.media3_controls_play_description)
        return notification()?.actions?.firstOrNull { it.title.toString() == title }
    }

    fun run() {
        val directory = File(context.cacheDir, "notification_check_${UUID.randomUUID()}")
        val report = JSONObject()
        var volume = 1f
        var success = false
        try {
            check(directory.mkdirs())
            val audio = File(directory, "notification.wav")
            val size = 60 * 8000 * 2
            val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            wav.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            wav.put("data".toByteArray()).putInt(size)
            audio.writeBytes(wav.array())
            val song = DownloadedSong("notification-check", "notification-check", "Prueba de notificación", "Clash test", null,
                60, null, audio.absolutePath, android.net.Uri.fromFile(audio).toString(), "WAV", "128k", audio.length(), 1)
            main {
                val manager = ServiceLocator.playerManager
                volume = manager.player!!.volume
                manager.player!!.volume = 0f
                manager.playSong(song)
            }
            await("playing-notification-with-pause-action") { main { ServiceLocator.playerManager.player!!.isPlaying } && action(true) != null }
            val pauseIntent = action(true)!!.actionIntent
            pauseIntent.send()
            await("notification-pause-stops-audio-and-keeps-play-control") {
                main { !ServiceLocator.playerManager.player!!.playWhenReady } && action(false) != null
            }
            val position = main { ServiceLocator.playerManager.player!!.currentPosition }
            Thread.sleep(400)
            check(main { ServiceLocator.playerManager.player!!.currentPosition } == position)
            checks.put("paused-position-does-not-advance")
            val token = notification()!!.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)!!
            val controller = MediaController(context, token)
            await("system-seek-action-enabled-after-loading") {
                ((controller.playbackState?.actions ?: 0L) and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
            }
            controller.transportControls.seekTo(25000)
            await("notification-seek-forward-while-paused") {
                main { ServiceLocator.playerManager.player!!.currentPosition in 24900L..25100L &&
                    !ServiceLocator.playerManager.player!!.playWhenReady }
            }
            controller.transportControls.seekTo(5000)
            await("notification-seek-backward-while-paused") {
                main { ServiceLocator.playerManager.player!!.currentPosition in 4900L..5100L &&
                    !ServiceLocator.playerManager.player!!.playWhenReady }
            }
            repeat(3) {
                action(false)!!.actionIntent.send()
                await("notification-play-keeps-notification-$it") { main { ServiceLocator.playerManager.player!!.isPlaying } && action(true) != null }
                if (it == 0) {
                    controller.transportControls.seekTo(30000)
                    await("notification-seek-while-playing-keeps-playing") {
                        main { ServiceLocator.playerManager.player!!.currentPosition in 29900L..33000L &&
                            ServiceLocator.playerManager.player!!.isPlaying } && action(true) != null
                    }
                }
                controller.transportControls.pause()
                await("system-pause-keeps-notification-$it") { main { !ServiceLocator.playerManager.player!!.playWhenReady } && action(false) != null }
                controller.transportControls.play()
                await("system-play-keeps-notification-$it") { main { ServiceLocator.playerManager.player!!.isPlaying } && action(true) != null }
                action(true)!!.actionIntent.send()
                await("notification-pause-keeps-notification-$it") { main { !ServiceLocator.playerManager.player!!.playWhenReady } && action(false) != null }
            }
            // Match the regression around service starts queued immediately before Stop/new Play.
            main {
                val manager = ServiceLocator.playerManager
                manager.resume(); manager.stop(); manager.playSong(song)
            }
            await("rapid-stop-new-play-does-not-remove-new-notification") { main { ServiceLocator.playerManager.player!!.isPlaying } && action(true) != null }
            main { ServiceLocator.playerManager.stop() }
            await("explicit-stop-removes-notification") { notification() == null }
            Thread.sleep(600)
            check(notification() == null) { "Late notification after Stop" }
            checks.put("notification-does-not-return-after-stop")
            checkBackgroundAutoplay(song)
            success = true
        } catch (error: Throwable) {
            report.put("error", android.util.Log.getStackTraceString(error))
        } finally {
            main { ServiceLocator.playerManager.stop(); ServiceLocator.playerManager.player?.volume = volume }
            directory.deleteRecursively()
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "notification_check_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString(2)) })
    }

    private fun checkBackgroundAutoplay(song: DownloadedSong) {
        val next = song.copy(id = "notification-next", songId = "notification-next", title = "Continuación privada")
        val windows = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val field = ServiceLocator::class.java.getDeclaredField("playerManager").apply { isAccessible = true }
        val original = ServiceLocator.playerManager
        val activity = instrumentation.startActivitySync(android.content.Intent(context, com.clashofmusic.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val allowLibrary = CompletableDeferred<Unit>()
        val manager = main {
            val library = object : LibraryRepository by ServiceLocator.libraryRepository {
                override fun observeDownloadedSongs() = flow {
                    allowLibrary.await()
                    emit(listOf(next))
                }
            }
            MusicPlayerManager(context, ServiceLocator.favoritesRepository, library).also {
                field.set(ServiceLocator, it)
            }
        }
        fun key(code: Int) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent $code"))
                .use { it.readBytes() }
        }
        try {
            main {
                manager.player!!.volume = 0f
                manager.setRepeatMode(Player.REPEAT_MODE_OFF)
                manager.playSong(song)
            }
            await("last-private-track-playing-with-service") { main { manager.player!!.isPlaying } && action(true) != null }
            // Seek near the end in foreground, then let it end naturally with the display off.
            main { manager.seekTo(55_000L) }
            key(android.view.KeyEvent.KEYCODE_HOME)
            key(android.view.KeyEvent.KEYCODE_SLEEP)
            await("playlist-ended-with-display-off-while-library-loads") {
                !windows.isInteractive && main { manager.player!!.playbackState == Player.STATE_ENDED }
            }
            Thread.sleep(900)
            check((notification()?.flags ?: 0) and Notification.FLAG_FOREGROUND_SERVICE != 0) {
                "Playback service lost foreground during autoplay handoff"
            }
            checks.put("service-stays-foreground-during-autoplay-handoff")
            allowLibrary.complete(Unit)
            await("next-private-track-plays-with-display-still-off") {
                !windows.isInteractive && main { manager.currentSong.value?.id == next.id && manager.player!!.isPlaying } &&
                    action(true) != null
            }
        } finally {
            main { manager.release() }
            try { await("background-playback-test-service-stopped") { notification() == null } }
            finally {
                main { field.set(ServiceLocator, original); activity.finish() }
                key(android.view.KeyEvent.KEYCODE_WAKEUP)
            }
        }
    }
}
