package com.clashofmusic.app.data.downloader

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clashofmusic.app.ClashOfMusicApp
import com.clashofmusic.app.MainActivity
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.DownloadRepository
import com.clashofmusic.app.presentation.navigation.*
import com.clashofmusic.app.presentation.theme.*
import com.clashofmusic.app.services.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

/** Private queue, preferences and notification ID. No download worker or personal catalog writes. */
internal class DownloadAlertsCheck(private val instrumentation: Instrumentation) {
    private val checks = JSONArray()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timeout: $label" }; Thread.sleep(60) }
        checks.put(label)
    }
    private fun find(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.contains(label) == true ||
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node).stateDescription?.contains(label) == true) return node
        repeat(node.childCount) { find(label, node.getChild(it))?.let { found -> return found } }
        return null
    }
    private fun capture(context: Context, name: String) {
        Thread.sleep(700) // Accessibility state can update before the frame / app-return transition is drawn.
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
        // Only the private fixture's bottom bar; no catalog is rendered by this content.
        val top = (screenshot.height * .82).toInt()
        val crop = android.graphics.Bitmap.createBitmap(screenshot, 0, top, screenshot.width, screenshot.height - top)
        File(context.filesDir, "download_alerts_$name.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle(); screenshot.recycle()
    }
    fun run() {
        val context = instrumentation.targetContext
        val preferences = "alerts_check_${UUID.randomUUID()}"
        val channel = "alerts_check_${UUID.randomUUID()}"
        val notificationId = 29731
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifier = DownloadCompletionNotifier(context, notificationId, channel)
        val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
        val preparing = MutableStateFlow(0)
        val repository = Proxy.newProxyInstance(DownloadRepository::class.java.classLoader, arrayOf(DownloadRepository::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "observeTasks" -> tasks
                "observeBatchPreparation" -> preparing
                else -> Unit
            }
        } as DownloadRepository
        val visibility = (context.applicationContext as ClashOfMusicApp).visibility.visible
        var monitor: DownloadActivityMonitor? = null
        var host: MainActivity? = null
        var success = false
        val report = JSONObject()
        fun snapshot(count: Int, status: DownloadStatus) = List(count) { DownloadTask("fixture-$it", "fixture-$it", "Private fixture $it", "Test", url = "private", status = status) }
        fun notification() = manager.activeNotifications.firstOrNull { it.id == notificationId }
        fun showBadge() {
            val activeMonitor = monitor!!
            main { host!!.setContent { ClashOfMusicTheme {
                Scaffold(containerColor = DarkBackground, bottomBar = {
                    NavigationBar(containerColor = DarkBackground) {
                        DownloadNavigationItem(selected = true, onClick = {}, monitor = activeMonitor)
                    }
                }) { padding -> Text("Verificación privada de descargas", Modifier.padding(padding), color = TextPrimaryDark) }
            } } }
        }
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            check(info.versionName == "2.1.0" && androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info) == 7L); checks.put("installed-version-is-2.1.0-with-upgrade-code")
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val iconBitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 192, 192); icon.draw(android.graphics.Canvas(iconBitmap))
            File(context.filesDir, "download_alerts_launcher.png").outputStream().use { iconBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            iconBitmap.recycle(); checks.put("launcher-icon-renders-from-installed-resources")
            host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            main { host!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            monitor = DownloadActivityMonitor(context, repository, visibility, preferences, notifier)
            await("real-activity-is-visible") { visibility.value }
            showBadge()
            tasks.value = snapshot(1, DownloadStatus.DOWNLOADING)
            await("one-active-download-reaches-monitor-state") { monitor!!.badge.value.activeCount == 1 }
            await("one-active-download-shows-contextual-badge") { find("1 descarga activa") != null }
            capture(context, "one")
            tasks.value = snapshot(12, DownloadStatus.QUEUED)
            await("twelve-active-downloads-show-two-digit-badge") { find("12 descargas activas") != null }
            capture(context, "twelve")
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
            await("real-app-is-outside-foreground") { !visibility.value }
            tasks.value = snapshot(12, DownloadStatus.COMPLETED)
            await("completion-notification-posted-only-after-last-pending-track") { notification() != null }
            val text = notification()!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            check(text.contains("todas") && text.contains("12")); checks.put("notification-describes-all-twelve-completed")
            await("completion-checkmark-is-unseen") { monitor!!.badge.value.unseenCompletion && monitor!!.badge.value.activeCount == 0 }
            monitor!!.close()
            monitor = DownloadActivityMonitor(context, repository, visibility, preferences, notifier)
            await("unseen-checkmark-survives-monitor-restart-without-replaying-history") { monitor!!.badge.value.unseenCompletion }
            notification()!!.notification.contentIntent.send()
            await("completion-intent-brings-app-to-foreground") { visibility.value }
            await("completion-intent-requests-downloads-destination") { main { host!!.intent.getBooleanExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, false) } }
            showBadge()
            await("checkmark-replaces-number-in-badge") { find("Descargas completadas sin revisar") != null }
            capture(context, "completed")
            var nav: androidx.navigation.NavHostController? = null
            var requestConsumed = false
            val requestedDownloads = main { host!!.intent.getBooleanExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, false) }
            val activeMonitor = monitor!!
            main { host!!.setContent { ClashOfMusicTheme {
                val controller = rememberNavController()
                SideEffect { nav = controller }
                AppNavigation(controller, openDownloads = requestedDownloads,
                    onDownloadsOpened = { requestConsumed = true }, downloadActivity = activeMonitor)
            } } }
            await("actual-navigation-is-ready") { nav != null }
            await("completion-request-opens-downloads-and-is-consumed") {
                main { nav!!.currentDestination?.route == Screen.Downloads.route && requestConsumed }
            }
            await("entering-downloads-clears-checkmark-and-completion-notification") { !monitor!!.badge.value.unseenCompletion && notification() == null }
            main { nav!!.navigateToTopLevel(Screen.Home) }
            Thread.sleep(250)
            check(!monitor!!.badge.value.unseenCompletion); checks.put("leaving-downloads-does-not-resurrect-checkmark")
            tasks.value = snapshot(1, DownloadStatus.DOWNLOADING)
            await("new-foreground-batch-active") { monitor!!.badge.value.activeCount == 1 }
            tasks.value = snapshot(1, DownloadStatus.COMPLETED)
            await("foreground-completion-updates-badge") { monitor!!.badge.value.unseenCompletion }
            Thread.sleep(250)
            check(notification() == null); checks.put("foreground-completion-does-not-post-system-notification")
            main { nav!!.navigateToTopLevel(Screen.Downloads) }
            await("viewed-foreground-completion-is-cleared") { !monitor!!.badge.value.unseenCompletion }
            main { nav!!.navigateToTopLevel(Screen.Home) }; Thread.sleep(200)
            preparing.value = 1
            tasks.value = snapshot(1, DownloadStatus.DOWNLOADING)
            await("album-preparation-active") { monitor!!.badge.value.activeCount == 1 }
            tasks.value = snapshot(1, DownloadStatus.COMPLETED); Thread.sleep(250)
            check(!monitor!!.badge.value.unseenCompletion); checks.put("preparation-gap-does-not-claim-album-is-complete")
            preparing.value = 0
            await("completion-after-preparation-finishes") { monitor!!.badge.value.unseenCompletion }
            success = true
        } catch (error: Throwable) {
            report.put("error", android.util.Log.getStackTraceString(error))
                .put("badgeSnapshot", monitor?.badge?.value.toString())
                .put("windowPackage", instrumentation.uiAutomation.rootInActiveWindow?.packageName)
            if (checks.length() <= 5) {
                val descriptions = JSONArray()
                fun collect(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.text != null || node.contentDescription != null) descriptions.put(JSONObject().put("text", node.text?.toString()).put("description", node.contentDescription?.toString()))
                    repeat(node.childCount) { collect(node.getChild(it)) }
                }
                collect(instrumentation.uiAutomation.rootInActiveWindow)
                report.put("privateFixtureDescriptions", descriptions)
            }
            capture(context, "error")
        }
        finally {
            monitor?.close(); notifier.clear(); manager.deleteNotificationChannel(channel); context.deleteSharedPreferences(preferences)
            main { host?.setContent { }; host?.finish() }
        }
        report.put("success", success).put("checks", checks)
        File(context.filesDir, "download_alerts_report.json").writeText(report.toString(2))
        instrumentation.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("stream", report.toString(2)) })
    }
}
