package com.syndesia.app.adb

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.wearable.MessageClient
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Syndesia's watch helper: a small Wear OS app (bundled in this APK) that the phone can reach over
 * Bluetooth to bring the watch's Wi-Fi and Wireless debugging up when ADB can't reach it.
 */
object Helper {
    const val PACKAGE = "com.syndesia.app"
    private const val ASSET = "syndesia-wear.apk"
    private const val CAPABILITY = "syndesia_helper"
    private const val PATH_WAKE = "/syndesia/wake"
    private const val PATH_RELEASE = "/syndesia/release"
    private const val PATH_HOTSPOT = "/syndesia/hotspot"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastWake = 0L

    /** Watches with the helper installed that are reachable over Bluetooth right now. */
    suspend fun nodes(context: Context): List<String> = runCatching {
        withTimeoutOrNull(5000) {
            val withApp = Wearable.getCapabilityClient(context.applicationContext)
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
                .nodes.map { it.id }
            // Older helper builds didn't advertise themselves; fall back to any connected watch.
            withApp.ifEmpty {
                Wearable.getNodeClient(context.applicationContext).connectedNodes.await().map { it.id }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun send(context: Context, path: String): Int {
        val ids = nodes(context)
        val client = Wearable.getMessageClient(context.applicationContext)
        var sent = 0
        for (id in ids) {
            if (runCatching { withTimeoutOrNull(5000) { client.sendMessage(id, path, ByteArray(0)).await() } != null }.getOrDefault(false)) sent++
        }
        return sent
    }

    /** Ask the watch to bring up Wi-Fi (and Wireless debugging). Returns how many watches got the message. */
    suspend fun wake(context: Context): Int {
        lastWake = System.currentTimeMillis()
        return send(context, PATH_WAKE)
    }

    /** Fire-and-forget wake, at most every 20 s unless [force]. */
    fun wakeAsync(context: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastWake < 20_000) return
        lastWake = System.currentTimeMillis()
        val app = context.applicationContext
        scope.launch { runCatching { send(app, PATH_WAKE) } }
    }

    /** Let the watch drop Wi-Fi again (after the user disconnects). */
    fun releaseAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { send(app, PATH_RELEASE) } }
    }

    // --------------------------------------------------------------- Bluetooth transfers

    private const val PATH_CHANNEL = "/syndesia/channel"
    private const val PATH_RESULT = "/syndesia/result"

    /** A reply from the watch helper: stage is received | saved | confirm | installed | failed. */
    data class Reply(val id: String, val stage: String, val message: String?)

    private val waiting = ConcurrentHashMap<String, MutableStateFlow<Reply?>>()
    @Volatile
    private var listening = false

    private val listener = MessageClient.OnMessageReceivedListener { e ->
        if (e.path != PATH_RESULT) return@OnMessageReceivedListener
        runCatching {
            val o = JSONObject(String(e.data, Charsets.UTF_8))
            val r = Reply(o.getString("id"), o.getString("stage"), o.optString("message").ifBlank { null })
            waiting[r.id]?.value = r
        }
    }

    /** Start listening for replies from the watch (call once at app start). */
    fun listen(context: Context) {
        if (listening) return
        listening = true
        runCatching { Wearable.getMessageClient(context.applicationContext).addListener(listener) }
    }

    /** The best watch to send to: one that's nearby (Bluetooth) if there is one. */
    suspend fun bestNode(context: Context): String? = runCatching {
        withTimeoutOrNull(5000) {
            val nodes = Wearable.getCapabilityClient(context.applicationContext)
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                .ifEmpty { Wearable.getNodeClient(context.applicationContext).connectedNodes.await().toSet() }
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        }
    }.getOrNull()

    /** Register interest in replies for [id] before sending, so none are missed. */
    fun expect(id: String): StateFlow<Reply?> = waiting.getOrPut(id) { MutableStateFlow(null) }
    fun forget(id: String) { waiting.remove(id) }

    /**
     * Send one file to the helper over a Data Layer channel: 4-byte header length, JSON header, bytes.
     * Waits until the watch confirms it has everything ("received" or "saved"), and returns that reply.
     */
    suspend fun sendOverBluetooth(
        context: Context,
        node: String,
        header: JSONObject,
        input: InputStream,
        size: Long,
        onProgress: (Long) -> Unit,
    ): Reply {
        val id = header.getString("id")
        val replies = expect(id)
        val cc = Wearable.getChannelClient(context.applicationContext)
        val ch = cc.openChannel(node, PATH_CHANNEL).await()
        try {
            val out = DataOutputStream(BufferedOutputStream(cc.getOutputStream(ch).await(), 64 * 1024))
            out.use { o ->
                val hb = header.toString().toByteArray()
                o.writeInt(hb.size)
                o.write(hb)
                val buf = ByteArray(32 * 1024)
                var sent = 0L
                var last = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; onProgress(sent) }
                }
                o.flush()
                onProgress(sent)
            }
            // Bluetooth is slow and the watch may still be writing: allow plenty of time.
            val timeout = 60_000L + (if (size > 0) size / 20 else 0L)
            return withTimeoutOrNull(timeout) { replies.first { it != null && it.stage != "confirm" } }
                ?: throw IOException("The watch didn't confirm it got the file. Is Syndesia on the watch up to date?")
        } finally {
            forget(id)
            runCatching { cc.close(ch).await() }
        }
    }

    /**
     * Ask the watch to join the phone's hotspot. Returns the watch's "ip" or "ip:port" on success.
     * Stages from the watch: joining → joined (message = ip[:port]) or failed.
     */
    suspend fun joinHotspot(context: Context, creds: Hotspot.Credentials, onStage: (String) -> Unit): String {
        val node = bestNode(context) ?: throw IOException("Can't reach Syndesia on your watch over Bluetooth. Is the watch connected to this phone?")
        val id = "h${System.currentTimeMillis()}"
        val replies = expect(id)
        try {
            val body = JSONObject().put("id", id).put("ssid", creds.ssid).put("pass", creds.passphrase).put("wpa3", creds.wpa3)
            Wearable.getMessageClient(context.applicationContext).sendMessage(node, PATH_HOTSPOT, body.toString().toByteArray()).await()
            val r = withTimeoutOrNull(60_000) {
                var last: String? = null
                replies.first { rep ->
                    if (rep != null && rep.stage != last) { last = rep.stage; onStage(rep.stage) }
                    rep?.stage == "joined" || rep?.stage == "failed"
                }
            } ?: throw IOException("The watch didn't join the hotspot in time. Update Syndesia on the watch (Install tab) and try again.")
            if (r.stage == "failed") throw IOException(r.message ?: "The watch couldn't join the hotspot")
            return r.message ?: throw IOException("The watch joined but didn't say its address")
        } finally {
            forget(id)
        }
    }

    /** The helper APK bundled with this build, copied out so it can be installed. */
    fun bundledApk(context: Context): File? = runCatching {
        val out = File(context.cacheDir, "helper").apply { mkdirs() }.let { File(it, ASSET) }
        context.assets.open(ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    /** Version code of the helper on the connected watch, or null if it isn't installed. */
    suspend fun installedVersion(): Long? {
        val out = Adb.sh("dumpsys package $PACKAGE | grep -m1 versionCode=")
        return Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * One-time setup after installing on the watch, over ADB:
     *  - let it switch Wireless debugging on (WRITE_SECURE_SETTINGS),
     *  - exempt it from battery optimisation so a Bluetooth message can start its Wi-Fi service,
     *  - open it once (freshly installed apps can't receive messages until they've been started).
     */
    suspend fun setUp(): String? {
        val grant = Adb.sh("pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS")
        Adb.sh("dumpsys deviceidle whitelist +$PACKAGE")
        Adb.sh("cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow")
        // Allow installs sent over Bluetooth (the watch still asks to confirm each one).
        Adb.sh("cmd appops set $PACKAGE REQUEST_INSTALL_PACKAGES allow")
        // Let it browse the watch's storage to send files to the phone.
        Adb.sh("cmd appops set --uid $PACKAGE MANAGE_EXTERNAL_STORAGE allow")
        // Let it join the phone's hotspot without the "Allow suggested networks?" prompt.
        Adb.sh("cmd wifi network-suggestions-set-user-approved $PACKAGE yes")
        runCatching { Watch.launch(PACKAGE) }
        return grant.trim().ifBlank { null }?.takeIf { it.contains("Exception", true) || it.contains("Error", true) }
    }
}
