package com.syndesia.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Helper
import com.syndesia.app.adb.Link
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the watch link alive while Syndesia is in the background (file picker open, another app
 * on screen). Without it, Android cuts a background app's network and the link drops within seconds.
 * Runs from connecting until the user taps Disconnect or swipes Syndesia away from Recents.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Adb.disconnect()
            stop()
            return START_NOT_STICKY
        }
        // Must call startForeground promptly after startForegroundService.
        promote(Adb.link.value)
        if (!started) {
            started = true
            holdWifi()
            // Remind the watch helper every few minutes so it keeps Wi-Fi up while we're connected.
            scope.launch {
                while (true) {
                    if (Adb.link.value is Link.Connected) Helper.wakeAsync(applicationContext, force = true)
                    delay(5 * 60_000L)
                }
            }
            scope.launch {
                Adb.link.collectLatest { l ->
                    when (l) {
                        is Link.Connected, is Link.Connecting -> promote(l)
                        else -> {
                            // User disconnected: let the watch drop Wi-Fi again.
                            if (Adb.userDisconnected) Helper.releaseAsync(applicationContext)
                            stop()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Swiped away from Recents = "closed completely": drop the link and go. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Adb.disconnect()
        stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun holdWifi() {
        // Stops the phone's Wi-Fi from power-saving mid-transfer.
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Syndesia:link").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun promote(l: Link) {
        val n = notification(l)
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }
    }

    private fun notification(l: Link): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Syndesia is connected to your watch"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = when (l) {
            is Link.Connected -> "Connected to ${l.info.model}" to "${l.info.host}:${l.info.port} · stays connected until you disconnect"
            is Link.Connecting -> (if (l.reconnecting) "Reconnecting to your watch…" else "Connecting to your watch…") to "${l.host}:${l.port}"
            else -> "Syndesia" to "Not connected"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_DISCONNECT = "com.syndesia.app.DISCONNECT"

        /** Call when a link comes up (app is in the foreground then, so the service may start). */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
        }
    }
}
