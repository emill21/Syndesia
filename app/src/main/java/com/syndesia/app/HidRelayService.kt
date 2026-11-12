package com.syndesia.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.syndesia.hid.HidMouse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Backup computer mouse: when the watch can't be a Bluetooth mouse itself, this phone is, and the
 * watch's touchpad drives it. Watch input arrives on /hidrelay/… while this service runs.
 */
class HidRelayService : Service(), MessageClient.OnMessageReceivedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Wearable.getMessageClient(this).addListener(this)
        // Tell the watch whenever the mouse's state changes.
        scope.launch {
            HidMouse.status.combine(HidMouse.host) { s, h -> s to h }.collect { (s, h) ->
                val state = when (s) {
                    HidMouse.Status.Connected -> "connected"
                    HidMouse.Status.Ready, HidMouse.Status.Connecting -> "ready"
                    HidMouse.Status.Unsupported -> "not_ready"
                    else -> "starting"
                }
                val msg = when {
                    s == HidMouse.Status.Unsupported -> HidMouse.error.value ?: "This phone can't be a Bluetooth mouse"
                    s == HidMouse.Status.Ready && HidMouse.lastHost(this@HidRelayService) == null ->
                        "Pair this phone with your computer first: Syndesia on the phone › Settings › Computer mouse"
                    else -> null
                }
                reply(this@HidRelayService, state, h?.name, msg)
                notifyState(s, h?.name)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HidMouse.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_CONNECT)?.let { HidMouse.connect(this, it) }
        notifyState(HidMouse.status.value, HidMouse.host.value?.name)
        HidMouse.start(this, "Syndesia mouse")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { Wearable.getMessageClient(this).removeListener(this) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(e: com.google.android.gms.wearable.MessageEvent) {
        if (!e.path.startsWith("/hidrelay/")) return
        node = e.sourceNodeId
        val o = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        when (e.path.removePrefix("/hidrelay/")) {
            "move" -> HidMouse.move(o.optDouble("dx").toFloat(), o.optDouble("dy").toFloat())
            "wheel" -> HidMouse.wheel(o.optInt("t"))
            "press" -> HidMouse.press(o.optInt("b", HidMouse.LEFT))
            "release" -> HidMouse.release(o.optInt("b", HidMouse.LEFT))
            "click" -> HidMouse.click(o.optInt("b", HidMouse.LEFT))
            "type" -> HidMouse.type(o.optString("text"))
            "key" -> HidMouse.key(o.optInt("code"), o.optInt("mods"))
        }
    }

    private fun notifyState(s: HidMouse.Status, host: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Computer mouse", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HidRelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (s == HidMouse.Status.Connected) "Mouse for ${host ?: "your computer"}" else "Phone mouse ready")
            .setContentText("Your watch's touchpad controls the computer")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, 21, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }
    }

    companion object {
        private const val CHANNEL = "hidrelay"
        private const val ACTION_STOP = "com.syndesia.app.HID_STOP"
        private const val EXTRA_CONNECT = "connect"

        @Volatile var running = false
            private set
        @Volatile private var node: String? = null

        fun hasPermission(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** False if Android wouldn't start it (from the background) or permission is missing. */
        fun start(ctx: Context, connect: String? = null): Boolean {
            if (!hasPermission(ctx)) return false
            val i = Intent(ctx, HidRelayService::class.java).apply { if (connect != null) putExtra(EXTRA_CONNECT, connect) }
            return runCatching { ContextCompat.startForegroundService(ctx, i) }.isSuccess
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, HidRelayService::class.java).setAction(ACTION_STOP)) }
        }

        fun setNode(n: String) { node = n }

        /** Tell the watch how the phone mouse is doing. */
        fun reply(ctx: Context, state: String, host: String?, message: String?) {
            val n = node ?: return
            val body = JSONObject().put("state", state).put("host", host ?: "").put("message", message ?: "")
            runCatching { Wearable.getMessageClient(ctx.applicationContext).sendMessage(n, "/syndesia/hidrelay", body.toString().toByteArray()) }
        }
    }
}
