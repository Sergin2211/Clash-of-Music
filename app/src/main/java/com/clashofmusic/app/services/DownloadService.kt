package com.clashofmusic.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.R
import com.clashofmusic.app.di.ServiceLocator
import com.clashofmusic.app.domain.model.DownloadStatus
import com.clashofmusic.app.domain.model.DownloadTask
import kotlinx.coroutines.*

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "clash_music_downloads"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START_DOWNLOAD = "com.clashofmusic.app.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.clashofmusic.app.action.CANCEL_DOWNLOAD"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun startDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)

        if (action == ACTION_CANCEL_DOWNLOAD && !taskId.isNullOrEmpty()) {
            serviceScope.launch {
                try {
                    ServiceLocator.downloadRepository.cancelDownload(taskId)
                } catch (_: Exception) {}
            }
            return START_NOT_STICKY
        }

        // Start foreground immediately with placeholder to satisfy Android 8+ foreground requirements
        val initialNotification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startObservingTasks()
        return START_NOT_STICKY
    }

    private fun startObservingTasks() {
        if (observeJob != null) return

        observeJob = serviceScope.launch(Dispatchers.Default) {
            val updates = DownloadNotificationUpdates()
            try {
                ServiceLocator.downloadRepository.observeTasks().collect { tasks ->
                    val activeTask = tasks.firstOrNull { it.isRunning } ?: tasks.firstOrNull { it.status == DownloadStatus.QUEUED }
                    if (activeTask != null) {
                        if (updates.shouldNotify(activeTask)) {
                            val notification = buildNotification(activeTask)
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        }
                    } else {
                        // No active tasks remaining
                        withContext(Dispatchers.Main.immediate) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(task: DownloadTask?): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (task != null) {
            val progressInt = task.progress.toInt().coerceIn(0, 100)
            val isIndeterminate = task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.PREPARING || (progressInt == 0 && task.downloadSpeed == null)

            val speedPart = if (!task.downloadSpeed.isNullOrEmpty()) " • ${task.downloadSpeed}" else ""
            val etaPart = if (task.etaSeconds > 0) " • ${formatEta(task.etaSeconds)}" else ""
            val contentText = "${task.statusText}$speedPart$etaPart"

            builder.setContentTitle(task.title.ifEmpty { "Descargando audio..." })
                .setContentText(contentText)
                .setProgress(100, progressInt, isIndeterminate)

            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_TASK_ID, task.id)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                task.id.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
        } else {
            builder.setContentTitle("Clash of Music")
                .setContentText("Preparando descargas...")
                .setProgress(100, 0, true)
        }

        return builder.build()
    }

    private fun formatEta(seconds: Long): String {
        return if (seconds >= 60) {
            "${seconds / 60}m ${seconds % 60}s"
        } else {
            "${seconds}s"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
