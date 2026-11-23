package com.arin.bypass.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arin.bypass.R

/** Only exists so long unpack/rebuild work survives the app going to the background. */
class ForegroundSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, build())
        return START_STICKY
    }

    private fun build(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sync_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL = "bypass_work"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ForegroundSyncService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ForegroundSyncService::class.java)) }
        }
    }
}
