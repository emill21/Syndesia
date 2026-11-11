package com.syndesia.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import kotlinx.coroutines.CompletableDeferred
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.Inet4Address

/**
 * Join the phone's hotspot. The phone sends its name and password over Bluetooth; we add it as a
 * Wi-Fi suggestion (pre-approved by the phone at setup), hold Wi-Fi up so Wear OS actually joins,
 * switch Wireless debugging on, and report our address back.
 */
object HotspotJoin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var current: WifiNetworkSuggestion? = null

    fun join(context: Context, node: String, body: JSONObject) {
        val ctx = context.applicationContext
        val id = body.optString("id")
        val ssid = body.optString("ssid")
        val pass = body.optString("pass")
        val wpa3 = body.optBoolean("wpa3")
        if (id.isBlank() || ssid.isBlank() || pass.isBlank()) return
        scope.launch {
            Receiver.result(ctx, node, id, "joining")
            val wm = ctx.getSystemService(WifiManager::class.java)
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .apply { if (wpa3) setWpa3Passphrase(pass) else setWpa2Passphrase(pass) }
                .setIsAppInteractionRequired(false)
                .build()
            // Drop the last hotspot's suggestion; its name and password are gone now.
            current?.let { old -> runCatching { wm.removeNetworkSuggestions(listOf(old)) } }
            val status = runCatching { wm.addNetworkSuggestions(listOf(suggestion)) }.getOrDefault(-1)
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
                status != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ) {
                Receiver.result(ctx, node, id, "failed", "The watch refused the hotspot network (code $status). Update Syndesia on the watch from the phone's Install tab.")
                return@launch
            }
            current = suggestion
            // Asking for Wi-Fi makes Wear OS bring it up even while Bluetooth is connected.
            WifiKeeper.start(ctx)
            // Both at once, so you're never waiting: the quiet suggestion (Wear OS may just join it),
            // and a direct request for exactly this network ("Connect to <name>?" on the watch).
            Receiver.result(ctx, node, id, "approve")
            val directJob = async { requestDirect(ctx, ssid, pass, wpa3) }
            val ip = withTimeoutOrNull(100_000) {
                var found: String? = null
                while (found == null) {
                    if (directJob.isCompleted) found = directJob.await() ?: hotspotAddress(ctx, ssid)
                    else found = hotspotAddress(ctx, ssid)
                    if (found == null) {
                        if (directJob.isCompleted) {
                            // The direct request ended without a network (declined or failed); keep polling briefly.
                            delay(700)
                        } else delay(500)
                    }
                }
                found
            }
            if (ip == null) {
                Receiver.result(
                    ctx, node, id, "failed",
                    "The watch couldn't join $ssid. Make sure you tapped Connect on the watch. If it never asked, the watch may not see the hotspot: some watches only use 2.4 GHz Wi-Fi.",
                )
                return@launch
            }
            WifiHold.enableWirelessDebugging(ctx)
            // Wireless debugging takes a moment to come up on the new network.
            delay(2500)
            val port = tlsPort()
            Receiver.result(ctx, node, id, "joined", if (port != null) "$ip:$port" else ip)
        }
    }

    private var direct: ConnectivityManager.NetworkCallback? = null

    /** Drop the direct hotspot connection (when the phone says it's done). */
    fun release(ctx: Context) {
        val cb = direct ?: return
        direct = null
        runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    /**
     * Connect to exactly this network with a WifiNetworkSpecifier. Android asks the user to confirm on
     * the watch. The request stays registered (keeping the connection) until [release].
     */
    private suspend fun requestDirect(ctx: Context, ssid: String, pass: String, wpa3: Boolean): String? {
        release(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (wpa3) setWpa3Passphrase(pass) else setWpa2Passphrase(pass) }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val got = CompletableDeferred<String?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
                if (a != null) got.complete(a)
            }

            override fun onUnavailable() {
                got.complete(null)
            }
        }
        return runCatching {
            cm.requestNetwork(request, cb)
            direct = cb
            withTimeoutOrNull(90_000) { got.await() }
        }.getOrNull().also { if (it == null) release(ctx) }
    }

    /** Our IPv4 address on Wi-Fi, once we're on the phone's hotspot. */
    @Suppress("DEPRECATION")
    private fun hotspotAddress(ctx: Context, ssid: String): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wm = ctx.getSystemService(WifiManager::class.java)
        cm.allNetworks.firstNotNullOfOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstNotNullOfOrNull null
            // SSID may be hidden from us (needs location); accept it if we can't tell.
            val name = wm.connectionInfo?.ssid?.removeSurrounding("\"")
            if (name != null && name != "<unknown ssid>" && name != ssid) return@firstNotNullOfOrNull null
            cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        }
    }.getOrNull()

    /** Wireless debugging's current port, if Android lets us read it. */
    private fun tlsPort(): Int? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val v = c.getMethod("get", String::class.java).invoke(null, "service.adb.tls.port") as? String
        v?.toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()
}
