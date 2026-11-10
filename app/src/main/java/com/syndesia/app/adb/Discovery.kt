package com.syndesia.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

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
 */
@Suppress("DEPRECATION")
object Discovery {
    private const val CONNECT = "_adb-tls-connect._tcp"
    private const val PAIRING = "_adb-tls-pairing._tcp"
    private const val LEGACY = "_adb._tcp"

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var nsd: NsdManager? = null
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val main = Handler(Looper.getMainLooper())

    // Before Android 14 only one resolve can run at a time, so queue them.
    private val queue = ArrayDeque<Pair<NsdServiceInfo, String>>()
    private var resolving = false

    // "type|serviceName" -> (host, serviceName, port), so lost services can be dropped.
    private val entries = mutableMapOf<String, Triple<String, String, Int>>()

    @Synchronized
    fun start(context: Context) {
        if (listeners.isNotEmpty()) return
        val m = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        _searching.value = true
        for (type in listOf(CONNECT, PAIRING, LEGACY)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) { main.post { enqueue(info, type) } }
                override fun onServiceLost(info: NsdServiceInfo) { main.post { lost(info, type) } }
            }
            runCatching {
                m.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners[type] = l
            }
        }
    }

    @Synchronized
    fun stop() {
        val m = nsd ?: return
        listeners.values.forEach { runCatching { m.stopServiceDiscovery(it) } }
        listeners.clear()
        queue.clear()
        resolving = false
        _searching.value = false
    }

    /** Forget everything seen so far and look again. */
    fun restart(context: Context) {
        stop()
        entries.clear()
        _found.value = emptyList()
        start(context)
    }

    private fun key(name: String, type: String) = "$type|$name"

    private fun enqueue(info: NsdServiceInfo, type: String) {
        queue.addLast(info to type)
        pump()
    }

    private fun pump() {
        if (resolving) return
        val (info, type) = queue.removeFirstOrNull() ?: return
        val m = nsd ?: return
        resolving = true
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; pump() }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val host = si.host?.let { if (it is Inet4Address) it.hostAddress else it.hostAddress?.substringBefore('%') }
                        if (host != null && si.port > 0 && !isMe(host)) {
                            entries[key(si.serviceName, type)] = Triple(host, si.serviceName, si.port)
                            publish()
                        }
                        pump()
                    }
                }
            })
        } catch (e: Throwable) {
            resolving = false
            main.post { pump() }
        }
    }

    private fun lost(info: NsdServiceInfo, type: String) {
        if (entries.remove(key(info.serviceName, type)) != null) publish()
    }

    private fun publish() {
        val byHost = linkedMapOf<String, Found>()
        for ((k, v) in entries) {
            val type = k.substringBefore('|')
            val (host, name, port) = v
            val cur = byHost[host] ?: Found(host, name)
            byHost[host] = when (type) {
                CONNECT -> cur.copy(connectPort = port, name = name)
                PAIRING -> cur.copy(pairPort = port, name = if (cur.connectPort == null) name else cur.name)
                else -> if (cur.connectPort == null) cur.copy(connectPort = port, legacy = true) else cur
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
