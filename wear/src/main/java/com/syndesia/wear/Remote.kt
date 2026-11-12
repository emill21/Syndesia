package com.syndesia.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
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
 * The watch side of the phone remote: sends /remote/… commands to Syndesia's accessibility service
 * on the phone, and listens for its replies while a remote screen is open.
 */
object Remote {
    data class App(val label: String, val pkg: String)

    enum class Link { Unknown, Ready, Off }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var node: String? = null

    private val _link = MutableStateFlow(Link.Unknown)
    /** Ready = the phone answered; Off = no answer (remote not switched on, or phone out of reach). */
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _apps = MutableStateFlow<List<App>?>(null)
    val apps: StateFlow<List<App>?> = _apps.asStateFlow()

    private val _torch = MutableStateFlow(false)
    val torch: StateFlow<Boolean> = _torch.asStateFlow()

    /** Result of the last Unlock: null while waiting, else the phone's reason ("unlocked", "wrong", …). */
    private val _unlock = MutableStateFlow<String?>(null)
    val unlock: StateFlow<String?> = _unlock.asStateFlow()

    fun requestUnlock(ctx: Context) {
        _unlock.value = null
        send(ctx, "unlock")
    }

    private val _typed = MutableStateFlow<Boolean?>(null)
    val typed: StateFlow<Boolean?> = _typed.asStateFlow()

    private var listening = 0
    private var listenerCtx: Context? = null
    private val listener = MessageClient.OnMessageReceivedListener { e ->
        if (e.path != "/remote/reply") return@OnMessageReceivedListener
        val o = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrNull() ?: return@OnMessageReceivedListener
        when (o.optString("type")) {
            "pong" -> { _link.value = Link.Ready; _torch.value = o.optBoolean("torch") }
            "apps" -> {
                val arr = o.optJSONArray("apps")
                _apps.value = if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    App(a.optString("l"), a.optString("p"))
                }
            }
            "torch" -> _torch.value = o.optBoolean("on")
            "typed" -> _typed.value = o.optBoolean("ok")
            "unlocked" -> _unlock.value = o.optString("why", if (o.optBoolean("ok")) "unlocked" else "failed").let { w ->
                if (w == "nopad") "nopad:" + o.optInt("seen") else w
            }
        }
    }

    /** Call from each remote screen's onStart / onStop. */
    fun open(ctx: Context) {
        val app = ctx.applicationContext
        if (listening++ == 0) {
            listenerCtx = app
            runCatching { Wearable.getMessageClient(app).addListener(listener) }
        }
        ping(app)
    }

    fun close() {
        if (--listening <= 0) {
            listening = 0
            listenerCtx?.let { c -> runCatching { Wearable.getMessageClient(c).removeListener(listener) } }
            listenerCtx = null
        }
    }

    private suspend fun phone(ctx: Context): String? {
        node?.let { return it }
        return runCatching {
            withTimeoutOrNull(5000) {
                val nodes = Wearable.getCapabilityClient(ctx).getCapability("syndesia_phone", CapabilityClient.FILTER_REACHABLE).await().nodes
                    .ifEmpty { Wearable.getNodeClient(ctx).connectedNodes.await().toSet() }
                (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
            }
        }.getOrNull()?.also { node = it }
    }

    /** Fire a command at the phone. */
    fun send(ctx: Context, cmd: String, body: JSONObject = JSONObject()) {
        val app = ctx.applicationContext
        scope.launch {
            val n = phone(app) ?: run { _link.value = Link.Off; return@launch }
            runCatching { Wearable.getMessageClient(app).sendMessage(n, "/remote/$cmd", body.toString().toByteArray()).await() }
                .onFailure { node = null }
        }
    }

    /** Check the phone's remote is on. Marks [link] Off if it doesn't answer within a few seconds. */
    fun ping(ctx: Context) {
        val app = ctx.applicationContext
        _link.value = if (_link.value == Link.Ready) Link.Ready else Link.Unknown
        send(app, "ping")
        scope.launch {
            kotlinx.coroutines.delay(4000)
            if (_link.value != Link.Ready) _link.value = Link.Off
        }
    }

    fun loadApps(ctx: Context) {
        _apps.value = null
        send(ctx, "apps")
    }

    fun type(ctx: Context, text: String, enter: Boolean) {
        _typed.value = null
        send(ctx, "type", JSONObject().put("text", text).put("enter", enter))
    }
}
