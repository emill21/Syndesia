package com.syndesia.wear

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            val ip = withTimeoutOrNull(45_000) {
                var found: String? = null
                while (found == null) {
                    found = hotspotAddress(ctx, ssid)
                    if (found == null) delay(700)
                }
                found
            }
            if (ip == null) {
                Receiver.result(
                    ctx, node, id, "failed",
                    "The watch didn't join ${ssid}. If it asked to allow Syndesia's suggested networks, tap Allow, then try again.",
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
