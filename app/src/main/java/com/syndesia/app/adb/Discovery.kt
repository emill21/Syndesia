package com.syndesia.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executor

/** A device on this Wi-Fi that's advertising Wireless debugging. */
data class Found(
    val host: String,
    /** e.g. "adb-1A2B3C4D-xYz12" — the serial is in the middle. */
    val name: String,
    val connectPort: Int? = null,
    val pairPort: Int? = null,
    val legacy: Boolean = false,
) {
    val serial: String get() = name.removePrefix("adb-").substringBeforeLast('-')
}

/**
 * Finds watches with Wireless debugging on via mDNS (the same `_adb-tls-connect` and
 * `_adb-tls-pairing` adverts the desktop `adb` uses). Only works while the watch is on Wi-Fi.
 *
 * Several parts of the app want it at different times (the screen while it's open, a reconnect in
 * the background, a request from the watch), so each one [hold]s it and [release]s it when done;
 * the search runs while anyone is holding.
 */
@Suppress("DEPRECATION")
object Discovery {
    private const val CONNECT = "_adb-tls-connect._tcp"
    private const val PAIRING = "_adb-tls-pairing._tcp"
    private const val LEGACY = "_adb._tcp"

    /** How long a watch seen in an earlier search may stay listed before the new search has to confirm it. */
    private const val STALE_AFTER_MS = 8000L

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var nsd: NsdManager? = null
    private val holders = mutableSetOf<String>()
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val main = Handler(Looper.getMainLooper())
    private val onMain = Executor { main.post(it) }

    // Before Android 14 only one resolve can run at a time, so queue them.
    private val queue = ArrayDeque<Pair<NsdServiceInfo, String>>()
    private var resolving = false

    /** One advert: where it points, and which search last confirmed it. */
    private data class Entry(val host: String, val name: String, val port: Int, val search: Int)

    // "type|serviceName" -> advert, so lost services can be dropped.
    private val entries = mutableMapOf<String, Entry>()

    // Every advert the current search has seen, so it can be asked again for its port.
    private val known = mutableMapOf<String, NsdServiceInfo>()

    // Android 14+: one live subscription per advert, which reports a changed port by itself.
    private val watchers = mutableMapOf<String, Any>()

    /** Counts searches, so adverts left over from an earlier one can be told apart. */
    private var search = 0
    private var sweep: Runnable? = null

    /** Search for as long as [who] needs it. Calling it again for the same [who] changes nothing. */
    @Synchronized
    fun hold(context: Context, who: String) {
        holders += who
        start(context)
    }

    /** [who] is done. The search stops once nobody is holding it. */
    @Synchronized
    fun release(who: String) {
        if (holders.remove(who) && holders.isEmpty()) stop()
    }

    @Synchronized
    private fun start(context: Context) {
        if (listeners.isNotEmpty()) return
        val m = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        _searching.value = true
        val mine = ++search
        for (type in listOf(CONNECT, PAIRING, LEGACY)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { main.post { seen(info, type, mine) } }
                override fun onServiceLost(info: NsdServiceInfo) { main.post { gone(info.serviceName, type, mine) } }
            }
            runCatching {
                m.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners[type] = l
            }
        }
        // What an earlier search saw stays listed for a moment, then goes unless this search saw it too:
        // the watch may have left Wi-Fi or changed port while nobody was listening.
        sweep?.let { main.removeCallbacks(it) }
        val r = Runnable { dropStale(mine) }
        sweep = r
        main.postDelayed(r, STALE_AFTER_MS)
    }

    @Synchronized
    private fun stop() {
        val m = nsd ?: return
        listeners.values.forEach { runCatching { m.stopServiceDiscovery(it) } }
        listeners.clear()
        if (Build.VERSION.SDK_INT >= 34) watchers.values.forEach { unwatch(m, it) }
        watchers.clear()
        known.clear()
        queue.clear()
        resolving = false
        sweep?.let { main.removeCallbacks(it) }
        sweep = null
        _searching.value = false
    }

    /** Forget everything seen so far and look again. */
    @Synchronized
    fun restart(context: Context) {
        stop()
        entries.clear()
        publish()
        if (holders.isNotEmpty()) start(context)
    }

    /**
     * Ask every advert again where it points. Android 14+ hears about a changed port by itself;
     * older versions only learn it by asking.
     */
    @Synchronized
    fun refresh() {
        if (Build.VERSION.SDK_INT >= 34 || listeners.isEmpty()) return
        for ((k, info) in known) {
            val type = k.substringBefore('|')
            if (queue.none { it.first.serviceName == info.serviceName && it.second == type }) queue.addLast(info to type)
        }
        pump()
    }

    private fun key(name: String, type: String) = "$type|$name"

    @Synchronized
    private fun seen(info: NsdServiceInfo, type: String, from: Int) {
        if (from != search || listeners.isEmpty()) return
        val name = info.serviceName ?: return
        known[key(name, type)] = info
        if (Build.VERSION.SDK_INT >= 34 && watch(info, name, type, from)) return
        queue.addLast(info to type)
        pump()
    }

    /** Subscribe to one advert. False if Android wouldn't take the subscription (then it's resolved once instead). */
    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo, name: String, type: String, from: Int): Boolean {
        val m = nsd ?: return false
        val k = key(name, type)
        // Seen again after going quiet: start over with a fresh subscription, which reports where it is now.
        watchers.remove(k)?.let { unwatch(m, it) }
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = unwatched(k, this, info, type, from)
            override fun onServiceUpdated(si: NsdServiceInfo) {
                // IPv4 if it has one. A link-local IPv6 address alone can't be dialled, so wait for a better one.
                val all = si.hostAddresses
                val address = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { !it.isLinkLocalAddress } ?: return
                record(name, type, from, address, si.port, this)
            }
            override fun onServiceLost() = quiet(k, this, from)
            override fun onServiceInfoCallbackUnregistered() {}
        }
        return runCatching {
            m.registerServiceInfoCallback(info, onMain, cb)
            watchers[k] = cb
        }.isSuccess
    }

    /** The subscription didn't take: fall back to asking once. */
    @Synchronized
    private fun unwatched(k: String, cb: Any, info: NsdServiceInfo, type: String, from: Int) {
        if (watchers[k] !== cb) return
        watchers.remove(k)
        if (from != search || listeners.isEmpty()) return
        queue.addLast(info to type)
        pump()
    }

    @RequiresApi(34)
    private fun unwatch(m: NsdManager, cb: Any) {
        runCatching { m.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
    }

    @Synchronized
    private fun pump() {
        if (resolving) return
        val (info, type) = queue.removeFirstOrNull() ?: return
        val m = nsd ?: return
        val from = search
        resolving = true
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post { resolved(from) }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    main.post {
                        // Keyed by the name the search reported, which is what a later "lost" will use.
                        record(info.serviceName ?: si.serviceName, type, from, si.host, si.port, null)
                        resolved(from)
                    }
                }
            })
        } catch (e: Throwable) {
            main.post { resolved(from) }
        }
    }

    @Synchronized
    private fun resolved(from: Int) {
        if (from != search) return // an answer for a search that has since been stopped
        resolving = false
        pump()
    }

    /** An advert told us where it points. [cb] is the subscription it came from, if any. */
    @Synchronized
    private fun record(name: String, type: String, from: Int, address: InetAddress?, port: Int, cb: Any?) {
        if (from != search || listeners.isEmpty()) return
        val k = key(name, type)
        if (cb != null && watchers[k] !== cb) return
        val host = address?.let { if (it is Inet4Address) it.hostAddress else it.hostAddress?.substringBefore('%') }
        if (host == null || port <= 0 || isMe(host)) return
        entries[k] = Entry(host, name, port, from)
        publish()
    }

    /** The search says an advert is gone. It reports it again if it comes back. */
    @Synchronized
    private fun gone(name: String?, type: String, from: Int) {
        if (from != search || name == null) return
        val k = key(name, type)
        known.remove(k)
        val w = watchers.remove(k)
        val m = nsd
        if (w != null && m != null && Build.VERSION.SDK_INT >= 34) unwatch(m, w)
        if (entries.remove(k) != null) publish()
    }

    /** A subscription says its advert stopped answering. Stop listing it, but keep listening for it. */
    @Synchronized
    private fun quiet(k: String, cb: Any, from: Int) {
        if (from != search || watchers[k] !== cb) return
        if (entries.remove(k) != null) publish()
    }

    /** Adverts from before search number [from] that it hasn't confirmed: gone, or pointing somewhere old. */
    @Synchronized
    private fun dropStale(from: Int) {
        if (from != search) return
        if (entries.values.removeAll { it.search != from }) publish()
    }

    @Synchronized
    private fun publish() {
        val byHost = linkedMapOf<String, Found>()
        for ((k, v) in entries) {
            val type = k.substringBefore('|')
            val cur = byHost[v.host] ?: Found(v.host, v.name)
            byHost[v.host] = when (type) {
                CONNECT -> cur.copy(connectPort = v.port, name = v.name)
                PAIRING -> cur.copy(pairPort = v.port, name = if (cur.connectPort == null) v.name else cur.name)
                else -> if (cur.connectPort == null) cur.copy(connectPort = v.port, legacy = true) else cur
            }
        }
        _found.value = byHost.values.toList()
    }

    private fun isMe(host: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            ni.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == host }
        }
    }.getOrDefault(false)
}
