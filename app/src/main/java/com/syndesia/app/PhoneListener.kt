package com.syndesia.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Discovery
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Requests from Syndesia on the watch, delivered over Bluetooth even when the phone app isn't open. */
class PhoneListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val body = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        when (event.path) {
            "/syndesia/connect" -> FromWatch.connect(this, event.sourceNodeId, body.optString("ip"))
            "/syndesia/screenshot" -> FromWatch.screenshot(this, event.sourceNodeId, body.optInt("delay", 5), body.optString("ip"))
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path == "/syndesia/to_phone") FromWatch.receive(this, channel)
    }
}

/** What the phone does for the watch: connect over Wi-Fi, take screenshots, take files. */
object FromWatch {
    private const val PATH_REPLY = "/syndesia/phone"
    private const val CHANNEL = "watch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connecting = Mutex()

    private fun reply(ctx: Context, node: String, body: JSONObject) {
        val app = ctx.applicationContext
        scope.launch {
            runCatching { Wearable.getMessageClient(app).sendMessage(node, PATH_REPLY, body.toString().toByteArray()).await() }
        }
    }

    private fun status(ctx: Context, node: String, kind: String, stage: String, message: String? = null) =
        reply(ctx, node, JSONObject().put("kind", kind).put("stage", stage).apply { if (message != null) put("message", message) })

    /** Make sure ADB is up to the watch at [ip]. Returns null on success, else why not. */
    private suspend fun ensureConnected(ctx: Context, ip: String): String? = connecting.withLock {
        val now = Adb.connected
        if (now != null && (ip.isBlank() || now.host == ip)) return@withLock null
        val prefs = Prefs(ctx)
        val saved = prefs.watches
        val host = ip.ifBlank { saved.firstOrNull()?.host } ?: return@withLock "Connect this watch in Syndesia on your phone once first."
        // The port changes whenever Wireless debugging restarts: ask mDNS, else use the last one we knew.
        val searching = Discovery.searching.value
        if (!searching) Discovery.start(ctx)
        val port = withTimeoutOrNull(10_000) {
            var p: Int? = null
            while (p == null) {
                p = Discovery.found.value.firstOrNull { it.host == host }?.connectPort
                if (p == null) delay(300)
            }
            p
        } ?: saved.firstOrNull { it.host == host }?.port
        if (!searching) Discovery.stop()
        if (port == null) return@withLock "Couldn't find the watch on Wi-Fi. Is Wireless debugging on?"
        val r = Adb.connect(ctx, host, port)
        r.exceptionOrNull()?.let { return@withLock it.message ?: "Couldn't connect" }
        r.getOrNull()?.let { info -> prefs.remember(SavedWatch(host, port, info.model, info.serial, System.currentTimeMillis())) }
        null
    }

    fun connect(ctx: Context, node: String, ip: String) {
        val app = ctx.applicationContext
        scope.launch {
            status(app, node, "connect", "connecting")
            val err = ensureConnected(app, ip)
            if (err != null) {
                status(app, node, "connect", "failed", err)
                return@launch
            }
            val model = Adb.connected?.model ?: "your watch"
            // Keep the link alive; Android may not let us start that from the background.
            val kept = withContext(Dispatchers.Main) { LinkService.start(app) }
            if (kept) status(app, node, "connect", "connected", "Syndesia on your phone is connected to $model.")
            else {
                notify(app, 41, "Your watch connected", "Tap to keep $model connected", null)
                status(app, node, "connect", "tap", "Connected. Tap the notice on your phone to keep it connected in the background.")
            }
        }
    }

    fun screenshot(ctx: Context, node: String, delaySec: Int, ip: String) {
        val app = ctx.applicationContext
        val started = System.currentTimeMillis()
        scope.launch {
            status(app, node, "screenshot", "waiting")
            val err = ensureConnected(app, ip)
            if (err != null) {
                status(app, node, "screenshot", "failed", "Screenshots need the Wi-Fi link. $err")
                return@launch
            }
            if (Adb.connected != null) withContext(Dispatchers.Main) { LinkService.start(app) }
            val wait = delaySec * 1000L - (System.currentTimeMillis() - started)
            if (wait > 0) delay(wait)
            runCatching {
                val (png, _) = Watch.screenshot()
                val name = "watch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                Transfers.saveBytes(app, name, png, "Screenshots")
            }.onSuccess { (uri, where) ->
                notify(app, 42, "Watch screenshot saved", where, uri to "image/png")
                status(app, node, "screenshot", "saved", "In $where on your phone.")
            }.onFailure { e ->
                status(app, node, "screenshot", "failed", e.message ?: "Couldn't take it")
            }
        }
    }

    fun receive(ctx: Context, channel: ChannelClient.Channel) {
        val app = ctx.applicationContext
        val node = channel.nodeId
        scope.launch {
            val cc = Wearable.getChannelClient(app)
            var id = ""
            try {
                val input = DataInputStream(cc.getInputStream(channel).await().buffered(128 * 1024))
                input.use { inp ->
                    val len = inp.readInt()
                    if (len <= 0 || len > 64 * 1024) error("Bad header")
                    val hb = ByteArray(len).also { inp.readFully(it) }
                    val h = JSONObject(String(hb, Charsets.UTF_8))
                    id = h.getString("id")
                    val name = h.optString("name", "file").replace('/', '_').ifBlank { "file" }
                    val (uri, where) = Transfers.receiveFromWatch(app, name, h.optLong("size", -1), inp)
                    reply(app, node, JSONObject().put("kind", "file").put("id", id).put("stage", "saved").put("message", "Saved to $where"))
                    val mime = app.contentResolver.getType(uri) ?: "*/*"
                    notify(app, 43, "Got $name from your watch", where, uri to mime)
                }
            } catch (e: Throwable) {
                if (id.isNotBlank()) reply(app, node, JSONObject().put("kind", "file").put("id", id).put("stage", "failed").put("message", e.message ?: "Couldn't save it"))
            } finally {
                runCatching { cc.close(channel).await() }
            }
        }
    }

    /** A notice on the phone; [open] is a file to open when tapped (else it opens Syndesia). */
    private fun notify(ctx: Context, id: Int, title: String, text: String, open: Pair<Uri, String>?) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "From your watch", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = if (open != null) Intent(Intent.ACTION_VIEW).setDataAndType(open.first, open.second)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                id,
                NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
