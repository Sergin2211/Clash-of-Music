package com.clashofmusic.app.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.R

class DownloadCompletionNotifier(private val context: Context, private val notificationId: Int = 2003,
    private val channelId: String = "clash_music_downloads_finished") {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    internal fun show(result: DownloadBatchResult) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(channelId, "Descargas finalizadas", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { setShowBadge(false) })
        val allCompleted = result.failed == 0 && result.cancelled == 0
        val text = if (allCompleted) "Se completaron todas las descargas pendientes (${result.completed})."
            else "${result.completed} completadas · ${result.failed} con error · ${result.cancelled} canceladas. Revisa Descargas."
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        val open = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(if (allCompleted) "Descargas completadas" else "Descargas finalizadas")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(true).build()
        try { manager.notify(notificationId, notification) } catch (_: SecurityException) { /* Permission may change between checking and posting. */ }
    }
    fun clear() { manager.cancel(notificationId) }
}
