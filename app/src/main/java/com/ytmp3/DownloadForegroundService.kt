package com.ytmp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("Starting downloads...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        scope.launch {
            DownloadManager.downloads.collect { items ->
                if (items.isEmpty()) return@collect
                val downloading = items.filter { it.status == DownloadStatus.DOWNLOADING }
                val done = items.count { it.status == DownloadStatus.DONE || it.status == DownloadStatus.ERROR }
                val total = items.size
                val text = when {
                    downloading.isNotEmpty() -> {
                        val name = downloading.first().let { if (it.title != it.url) it.title else null }
                        if (name != null) "Downloading: $name" else "${downloading.size} active • $done/$total done"
                    }
                    else -> "All done ($total tracks)"
                }
                nm().notify(NOTIF_ID, buildNotification(text))
                if (!DownloadManager.isActive()) stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            chan.setShowBadge(false)
            nm().createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YT MP3")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "ytmp3_dl"
        const val NOTIF_ID = 1
    }
}
