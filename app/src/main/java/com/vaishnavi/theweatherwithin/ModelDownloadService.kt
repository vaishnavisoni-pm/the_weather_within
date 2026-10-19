package com.vaishnavi.theweatherwithin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the model download as a genuine Foreground Service — the fix for
 * the ANR found in Session 10. The download no longer lives on a
 * coroutine tied to a Composable screen; it now survives backgrounding,
 * app-switching, and the screen closing entirely, which is what a
 * multi-minute, multi-gigabyte download actually needs.
 */
class ModelDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting download…", 0))

        serviceScope.launch {
            var lastNotifiedPercent = -1
            val progressJob = launch {
                ModelManager.downloadState.collect { state ->
                    if (state is ModelDownloadState.Downloading) {
                        val percent = (state.progress * 100).toInt()
                        if (percent != lastNotifiedPercent) {
                            updateNotification("Downloading on-device AI model…", percent)
                            lastNotifiedPercent = percent
                        }
                    }
                }
            }

            val result = ModelManager.downloadModel(applicationContext)
            progressJob.cancel()

            result.onSuccess {
                updateNotification("Download complete — ready to use.", 100, ongoing = false)
            }.onFailure { e ->
                updateNotification("Download failed: ${e.message ?: "unknown error"}", 0, ongoing = false)
            }

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while the on-device AI model downloads"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean = true): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Weather Within")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, ongoing: Boolean = true) {
        val notification = buildNotification(text, progress, ongoing)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
}