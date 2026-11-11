package com.syndesia.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong

/** One transfer shown on the watch, in either direction. */
data class WTransfer(
    val id: String,
    val name: String,
    val toPhone: Boolean,
    val total: Long,
    val done: Long = 0,
    val state: State = State.Running,
    val message: String? = null,
) {
    enum class State { Running, Done, Failed }
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Live transfer list for the watch UI (incoming from the phone and outgoing to it). */
object WatchTransfers {
    private val _list = MutableStateFlow<List<WTransfer>>(emptyList())
    val list: StateFlow<List<WTransfer>> = _list.asStateFlow()

    fun add(t: WTransfer) = _list.update { (it + t).takeLast(20) }
    fun set(id: String, f: (WTransfer) -> WTransfer) = _list.update { l -> l.map { if (it.id == id) f(it) else it } }
    fun clearFinished() = _list.update { l -> l.filter { it.state == WTransfer.State.Running } }
}

/**
 * Talking to Syndesia on the phone: send files, ask it to connect over Wi-Fi, ask it for a screenshot.
 * Replies come back on /syndesia/phone and land in [status] (and the transfer list).
 */
object Phone {
    private const val CAPABILITY = "syndesia_phone"
    const val PATH_REPLY = "/syndesia/phone"
    private const val PATH_TO_PHONE = "/syndesia/to_phone"
    private const val PATH_CONNECT = "/syndesia/connect"
    private const val PATH_SCREENSHOT = "/syndesia/screenshot"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong(System.currentTimeMillis())

    /** Latest word from the phone about a connect or screenshot request. */
    data class Status(val kind: String, val stage: String, val message: String?)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val replies = MutableStateFlow<JSONObject?>(null)

    private suspend fun node(ctx: Context): String? = runCatching {
        withTimeoutOrNull(5000) {
            val nodes = Wearable.getCapabilityClient(ctx.applicationContext)
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                .ifEmpty { Wearable.getNodeClient(ctx.applicationContext).connectedNodes.await().toSet() }
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        }
    }.getOrNull()

    /** Called by [WakeListener] for every /syndesia/phone message. */
    fun onReply(data: ByteArray) {
        val o = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        replies.value = o
        val kind = o.optString("kind")
        val stage = o.optString("stage")
        val msg = o.optString("message").ifBlank { null }
        if (kind == "file") {
            val id = o.optString("id")
            WatchTransfers.set(id) {
                it.copy(
                    state = if (stage == "saved") WTransfer.State.Done else WTransfer.State.Failed,
                    message = msg,
                    done = if (stage == "saved") it.total else it.done,
                )
            }
        } else {
            _status.value = Status(kind, stage, msg)
        }
    }

    private suspend fun message(ctx: Context, path: String, body: JSONObject): Boolean {
        val n = node(ctx) ?: return false
        return runCatching {
            Wearable.getMessageClient(ctx.applicationContext).sendMessage(n, path, body.toString().toByteArray()).await()
            true
        }.getOrDefault(false)
    }

    /** This watch's Wi-Fi IPv4 address, if Wi-Fi is up. */
    fun wifiAddress(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        networks.firstNotNullOfOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) null
            else cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        }
    }.getOrNull()

    /** Bring up Wi-Fi + Wireless debugging here, then ask the phone to connect. */
    fun connect(ctx: Context) {
        val app = ctx.applicationContext
        _status.value = Status("connect", "wifi", null)
        scope.launch {
            WifiKeeper.start(app)
            WifiHold.enableWirelessDebugging(app)
            // Give Wi-Fi a moment to come up so the phone can find us.
            withTimeoutOrNull(15_000) { WifiHold.state.first { it == WifiHold.State.On } }
            val ip = wifiAddress(app)
            if (ip == null) {
                _status.value = Status("connect", "failed", "Wi-Fi didn't come on. Check the watch's Wi-Fi is On or Automatic and it knows a network nearby.")
                return@launch
            }
            WifiHold.enableWirelessDebugging(app)
            delay(1500)
            _status.value = Status("connect", "asking", null)
            if (!message(app, PATH_CONNECT, JSONObject().put("ip", ip))) {
                _status.value = Status("connect", "failed", "Can't reach Syndesia on your phone. Is the phone nearby, with Bluetooth on?")
            }
        }
    }

    /** Ask the phone to take a screenshot of this watch in [delaySec] seconds (over ADB). */
    fun screenshot(ctx: Context, delaySec: Int) {
        val app = ctx.applicationContext
        _status.value = Status("screenshot", "asking", null)
        scope.launch {
            // The phone needs a Wi-Fi link for screenshots; make sure we can offer one.
            WifiKeeper.start(app)
            WifiHold.enableWirelessDebugging(app)
            val body = JSONObject().put("delay", delaySec).put("ip", wifiAddress(app) ?: "")
            if (!message(app, PATH_SCREENSHOT, body)) {
                _status.value = Status("screenshot", "failed", "Can't reach Syndesia on your phone.")
            }
        }
    }

    fun clearStatus() { _status.value = null }

    /** Send a file to the phone over a Data Layer channel (it lands in Downloads/Syndesia/From watch). */
    fun send(ctx: Context, file: File) {
        val app = ctx.applicationContext
        val id = "w${ids.incrementAndGet()}"
        WatchTransfers.add(WTransfer(id, file.name, toPhone = true, total = file.length()))
        scope.launch {
            runCatching {
                val n = node(app) ?: error("Can't reach Syndesia on your phone")
                val cc = Wearable.getChannelClient(app)
                val ch = cc.openChannel(n, PATH_TO_PHONE).await()
                try {
                    DataOutputStream(BufferedOutputStream(cc.getOutputStream(ch).await(), 64 * 1024)).use { o ->
                        val hb = JSONObject().put("id", id).put("name", file.name).put("size", file.length())
                            .toString().toByteArray()
                        o.writeInt(hb.size)
                        o.write(hb)
                        val buf = ByteArray(32 * 1024)
                        var sent = 0L
                        var last = 0L
                        file.inputStream().use { input ->
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                o.write(buf, 0, r)
                                sent += r
                                val now = System.currentTimeMillis()
                                if (now - last > 200) { last = now; val s = sent; WatchTransfers.set(id) { it.copy(done = s) } }
                            }
                        }
                        o.flush()
                    }
                    // Wait for the phone to say it saved it before closing the channel.
                    val timeout = 60_000L + file.length() / 20
                    withTimeoutOrNull(timeout) { replies.first { it?.optString("id") == id } }
                        ?: WatchTransfers.set(id) { it.copy(state = WTransfer.State.Failed, message = "The phone didn't confirm it. Update Syndesia on the phone?") }
                } finally {
                    runCatching { cc.close(ch).await() }
                }
            }.onFailure { e ->
                WatchTransfers.set(id) { it.copy(state = WTransfer.State.Failed, message = e.message ?: "Couldn't send") }
            }
        }
    }
}
