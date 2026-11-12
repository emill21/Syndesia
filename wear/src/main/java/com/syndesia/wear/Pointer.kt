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
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.syndesia.hid.HidMouse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Where computer-mouse input goes: straight out of the watch as a Bluetooth mouse, or (if this
 * watch can't be one) to the phone, which acts as the Bluetooth mouse instead.
 */
object Pointer {
    private val _viaPhone = MutableStateFlow(false)
    val viaPhone: StateFlow<Boolean> = _viaPhone.asStateFlow()

    /** Last word from the phone relay: ready / not_ready / connected / disconnected (+ host name). */
    data class PhoneState(val state: String, val host: String?, val message: String?)

    private val _phone = MutableStateFlow<PhoneState?>(null)
    val phone: StateFlow<PhoneState?> = _phone.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var node: String? = null

    fun usePhone(ctx: Context, on: Boolean) {
        _viaPhone.value = on
        if (on) {
            _phone.value = null
            relay(ctx, "start")
        }
    }

    /** Called by [WakeListener] for /syndesia/hidrelay replies. */
    fun onPhoneReply(data: ByteArray) {
        val o = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        _phone.value = PhoneState(o.optString("state"), o.optString("host").ifBlank { null }, o.optString("message").ifBlank { null })
    }

    private suspend fun phoneNode(ctx: Context): String? {
        node?.let { return it }
        return runCatching {
            withTimeoutOrNull(5000) {
                val nodes = Wearable.getCapabilityClient(ctx).getCapability("syndesia_phone", CapabilityClient.FILTER_REACHABLE).await().nodes
                    .ifEmpty { Wearable.getNodeClient(ctx).connectedNodes.await().toSet() }
                (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
            }
        }.getOrNull()?.also { node = it }
    }

    /** Commands to the phone relay. "start" goes through the always-on listener; the rest straight to the relay. */
    fun relay(ctx: Context, cmd: String, body: JSONObject = JSONObject()) {
        val app = ctx.applicationContext
        scope.launch {
            val n = phoneNode(app) ?: run { _phone.value = PhoneState("unreachable", null, "Can't reach Syndesia on your phone"); return@launch }
            val path = if (cmd == "start" || cmd == "connect" || cmd == "stop") "/syndesia/hidrelay/$cmd" else "/hidrelay/$cmd"
            runCatching { Wearable.getMessageClient(app).sendMessage(n, path, body.toString().toByteArray()).await() }
                .onFailure { node = null }
        }
    }

    // --------------------------------------------------------------- input

    fun move(ctx: Context, dx: Float, dy: Float) {
        if (_viaPhone.value) relay(ctx, "move", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        else HidMouse.move(dx, dy)
    }

    fun wheel(ctx: Context, ticks: Int) {
        if (ticks == 0) return
        if (_viaPhone.value) relay(ctx, "wheel", JSONObject().put("t", ticks)) else HidMouse.wheel(ticks)
    }

    fun press(ctx: Context, button: Int) {
        if (_viaPhone.value) relay(ctx, "press", JSONObject().put("b", button)) else HidMouse.press(button)
    }

    fun release(ctx: Context, button: Int) {
        if (_viaPhone.value) relay(ctx, "release", JSONObject().put("b", button)) else HidMouse.release(button)
    }

    fun click(ctx: Context, button: Int) {
        if (_viaPhone.value) relay(ctx, "click", JSONObject().put("b", button)) else HidMouse.click(button)
    }

    fun type(ctx: Context, text: String) {
        if (_viaPhone.value) relay(ctx, "type", JSONObject().put("text", text)) else HidMouse.type(text)
    }

    fun key(ctx: Context, code: Int, mods: Int = 0) {
        if (_viaPhone.value) relay(ctx, "key", JSONObject().put("code", code).put("mods", mods)) else HidMouse.key(code, mods)
    }
}

/** Keeps the watch's Bluetooth mouse alive (process in the foreground) while a computer is connected. */
class MouseKeeper : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HidMouse.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Computer mouse", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, ComputerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MouseKeeper::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Mouse for ${HidMouse.host.value?.name ?: "your computer"}")
            .setContentText("Tap to open the touchpad")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stop)
            .build()
        runCatching { ServiceCompat.startForeground(this, 13, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "mouse"
        private const val ACTION_STOP = "com.syndesia.wear.MOUSE_STOP"
        fun start(ctx: Context) { runCatching { ctx.startForegroundService(Intent(ctx, MouseKeeper::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, MouseKeeper::class.java)) } }
    }
}
