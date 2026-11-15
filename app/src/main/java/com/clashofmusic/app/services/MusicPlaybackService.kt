package com.clashofmusic.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.R
import com.clashofmusic.app.di.ServiceLocator
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

class MusicPlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "MusicPlaybackService"
        @OptIn(UnstableApi::class)
        const val NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        const val CHANNEL_ID = "clash_music_playback"

        const val ACTION_PREV = "com.clashofmusic.app.action.PREV"
        const val ACTION_NEXT = "com.clashofmusic.app.action.NEXT"
        const val ACTION_TOGGLE = "com.clashofmusic.app.action.TOGGLE"
        const val ACTION_FAVORITE = "com.clashofmusic.app.action.FAVORITE"
        const val ACTION_STOP = "com.clashofmusic.app.action.STOP"
        private const val EXTRA_START_GENERATION = "startGeneration"
        private const val EXTRA_STOP_GENERATION = "stopGeneration"
        private val generation = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var foregroundStartPending = false
        const val CUSTOM_ACTION_FAVORITE = "com.clashofmusic.app.action.SESSION_FAVORITE"

        fun start(context: Context) {
            val current = generation.incrementAndGet()
            foregroundStartPending = true
            val intent = Intent(context, MusicPlaybackService::class.java).putExtra(EXTRA_START_GENERATION, current)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                if (generation.get() == current) foregroundStartPending = false
                Log.e(TAG, "Error iniciando MusicPlaybackService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java)
            try {
                if (foregroundStartPending) {
                    // Let Android deliver the pending foreground start before handling its stop.
                    context.startService(intent.setAction(ACTION_STOP).putExtra(EXTRA_STOP_GENERATION, generation.get()))
                } else context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error deteniendo MusicPlaybackService: ${e.message}", e)
            }
        }
    }

    private var stopping = false
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationUpdateJob: Job? = null
    private var lastNotificationUpdateMs = 0L

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .setNotificationId(NOTIFICATION_ID)
            .build().apply { setSmallIcon(R.drawable.ic_music_note) }
        setMediaNotificationProvider(provider)
        if (foregroundStartPending) promotePendingStart()

        // Clean obsolete notifications from legacy versions
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(2001)

        try {
            val playerManager = ServiceLocator.playerManager
            playerManager.initPlayer()
            val player = playerManager.player ?: return

            val favoriteCommand = SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle.EMPTY)
            val isFav = playerManager.isFavorite.value
            val favoriteButton = CommandButton.Builder()
                .setDisplayName(if (isFav) "Quitar de favoritos" else "Añadir a favoritos")
                .setIconResId(if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
                .setSessionCommand(favoriteCommand)
                .build()

            val callback = object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val availableCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(favoriteCommand)
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(availableCommands)
                        // This is the controller's permission ceiling, not a snapshot of a
                        // buffering player's capabilities. Media3 intersects it dynamically.
                        .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                            .remove(Player.COMMAND_SET_MEDIA_ITEM).build())
                        .setCustomLayout(listOf(favoriteButton))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == CUSTOM_ACTION_FAVORITE) {
                        playerManager.toggleFavoriteCurrent()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            }

            val sessionActivity = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // System controls must go through the same queue and sleep policy as the app.
            val sessionPlayer = object : ForwardingPlayer(player) {
                override fun play() = playerManager.resume(startService = false)
                override fun pause() = playerManager.pause()
                override fun stop() = playerManager.stop()
                override fun setPlayWhenReady(playWhenReady: Boolean) {
                    if (playWhenReady) playerManager.resume(startService = false) else playerManager.pause()
                }
                override fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)
                override fun seekToNext() = playerManager.playNext()
                override fun seekToNextMediaItem() = playerManager.playNext()
                override fun seekToPrevious() = playerManager.playPrevious()
                override fun seekToPreviousMediaItem() = playerManager.playPrevious()
                override fun setRepeatMode(repeatMode: Int) = playerManager.setRepeatMode(repeatMode)
                override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = playerManager.setShuffleEnabled(shuffleModeEnabled)
                override fun getShuffleModeEnabled(): Boolean = playerManager.queueState.value.shuffleEnabled
            }
            mediaSession = MediaSession.Builder(this, sessionPlayer)
                .setId("ClashOfMusicMediaSession")
                .setCallback(callback)
                .setSessionActivity(sessionActivity)
                .setCustomLayout(listOf(favoriteButton))
                .build()

            // Register immediately, including app-initiated playback with no external controller yet.
            addSession(mediaSession!!)
            serviceScope.launch {
                playerManager.isFavorite.collect { updateSessionFavoriteButton(it) }
            }
            Log.d(TAG, "MediaSession inicializado exitosamente.")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando MediaSession: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproductor de Música",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reproducción multimedia en barra de estado y pantalla de bloqueo"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @OptIn(UnstableApi::class)
    private fun promotePendingStart() {
        if (isPlaybackOngoing) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Preserve the media token when resuming an existing, paused notification.
        val notification = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }?.notification
            ?: NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note).setContentTitle("Clash of Music")
            .setOnlyAlertOnce(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val playerManager = ServiceLocator.playerManager
        if (foregroundStartPending) promotePendingStart()
        val requestedStart = intent?.getLongExtra(EXTRA_START_GENERATION, -1L) ?: -1L
        if (requestedStart == generation.get()) {
            foregroundStartPending = false
            stopping = false
        }

        if (action == ACTION_STOP) {
            val requestedStop = intent?.getLongExtra(EXTRA_STOP_GENERATION, -1L) ?: -1L
            if (requestedStop >= 0 && requestedStop != generation.get()) return START_NOT_STICKY
            foregroundStartPending = false
            stopping = true
            notificationUpdateJob?.cancel()
            playerManager.stopFromService()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(2001)
            stopSelf()
            return START_NOT_STICKY
        }

        if (playerManager.currentSong.value == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_PREV -> playerManager.playPrevious()
            ACTION_NEXT -> playerManager.playNext()
            ACTION_TOGGLE -> playerManager.togglePlayPause()
            ACTION_FAVORITE -> playerManager.toggleFavoriteCurrent()
        }

        // Media3 owns the notification and foreground state together. Do not publish a
        // separate notification from a lagging StateFlow during a media-button command.
        return super.onStartCommand(intent, flags, startId)
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (stopping) return
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            // Android drops notification updates above its per-package rate limit. Always
            // deliver the latest state after a burst, rather than leaving an obsolete button.
            val waitMs = (lastNotificationUpdateMs + 400 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (waitMs > 0) delay(waitMs)
            // Media3 demotes the service on ENDED. Keep it active during a bounded library handoff.
            if (!startInForegroundRequired && isPlaybackOngoing) {
                ServiceLocator.playerManager.pendingAutoplay?.join()
            }
            if (!stopping && mediaSession === session) {
                lastNotificationUpdateMs = android.os.SystemClock.elapsedRealtime()
                val player = session.player
                val continuing = player.playWhenReady &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
                try {
                    super@MusicPlaybackService.onUpdateNotification(session, startInForegroundRequired || continuing)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is android.app.ForegroundServiceStartNotAllowedException) throw e
                    Log.w(TAG, "Android rechazó iniciar el servicio de reproducción", e)
                }
            }
        }
    }

    private fun updateSessionFavoriteButton(isFav: Boolean) {
        try {
            val favoriteCommand = SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle.EMPTY)
            val favoriteButton = CommandButton.Builder()
                .setDisplayName(if (isFav) "Quitar de favoritos" else "Añadir a favoritos")
                .setIconResId(if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
                .setSessionCommand(favoriteCommand)
                .build()
            mediaSession?.setCustomLayout(listOf(favoriteButton))
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ServiceLocator.playerManager.radioState.value.active) {
            ServiceLocator.playerManager.stopFromService()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        if (!foregroundStartPending) {
            if (ServiceLocator.playerManager.radioState.value.active) ServiceLocator.playerManager.stopFromService()
            else ServiceLocator.playerManager.pause()
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(2001)
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
