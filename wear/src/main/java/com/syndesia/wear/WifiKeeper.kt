package com.syndesia.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the helper's process (and so its Wi-Fi request) alive while the phone is connected.
 * The phone puts this app on the battery-optimisation allowlist at setup, which is what lets it
 * start from a Bluetooth message while in the background.
 */
class WifiKeeper : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WifiHold.release(this)
            stopSelfNow()
            return START_NOT_STICKY
        }
        runCatching {
            ServiceCompat.startForeground(this, 11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        WifiHold.acquire(this)
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wi-Fi for Syndesia", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WearActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WifiKeeper::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Wi-Fi on for Syndesia")
            .setContentText("Keeping Wi-Fi up for your phone")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "wifi"
        private const val ACTION_STOP = "com.syndesia.wear.STOP"

        /** False if Android wouldn't let the keeper start from the background. */
        fun start(context: Context): Boolean {
            val i = Intent(context, WifiKeeper::class.java)
            return runCatching { context.startForegroundService(i) }
                // Not allowed to start from the background (no allowlist yet): hold Wi-Fi anyway,
                // for as long as the process lives.
                .onFailure { WifiHold.acquire(context) }
                .isSuccess
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WifiKeeper::class.java)) }
        }
    }
}
