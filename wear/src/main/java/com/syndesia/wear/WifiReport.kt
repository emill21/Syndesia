package com.syndesia.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the phone what actually happened after it asked for Wi-Fi, instead of it guessing:
 * on, or off and why (Wi-Fi switched off, no saved network in range, background start blocked).
 */
object WifiReport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun watch(context: Context, node: String, id: String, keeperStarted: Boolean) {
        val app = context.applicationContext
        Receiver.result(app, node, id, "asking")
        scope.launch {
            val on = withTimeoutOrNull(25_000) { WifiHold.state.first { it == WifiHold.State.On } } != null
            if (on) {
                val dbg = WifiHold.wirelessDebuggingOn(app)
                Receiver.result(app, node, id, "on", if (dbg) "debugging" else if (WifiHold.canSetDebugging(app)) "nodebug" else "noperm")
                return@launch
            }
            @Suppress("DEPRECATION")
            val radioOff = runCatching { app.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == false }.getOrDefault(false)
            val why = when {
                !keeperStarted -> "nofgs"
                radioOff -> "wifi_off"
                else -> "no_network"
            }
            if (why == "wifi_off" || why == "no_network") notifyTurnOn(app, why == "wifi_off")
            Receiver.result(app, node, id, "off", why)
        }
    }

    /** A tap on the watch opens Wi-Fi settings, since apps can't switch Wi-Fi on themselves. */
    private fun notifyTurnOn(ctx: Context, switchedOff: Boolean) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("wifi_help") == null) {
            nm.createNotificationChannel(NotificationChannel("wifi_help", "Wi-Fi help", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 7,
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            12,
            NotificationCompat.Builder(ctx, "wifi_help")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (switchedOff) "Turn on Wi-Fi for Syndesia" else "No Wi-Fi for Syndesia")
                .setContentText(if (switchedOff) "Your phone wants to connect. Tap to open Wi-Fi settings." else "No saved Wi-Fi nearby. Tap to pick a network.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }
}
