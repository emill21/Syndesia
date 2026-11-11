package com.syndesia.app.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The phone's "local-only hotspot": a Wi-Fi network straight from the phone, with no internet
 * sharing. Regular apps may start this one (unlike the normal tethering hotspot), and it's all
 * ADB needs to reach the watch anywhere. Name and password are picked by Android each time.
 */
object Hotspot {
    data class Credentials(val ssid: String, val passphrase: String, val wpa3: Boolean)

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val _creds = MutableStateFlow<Credentials?>(null)
    /** Non-null while the hotspot is on. */
    val creds: StateFlow<Credentials?> = _creds.asStateFlow()

    /** Start it (or return the running one). Throws with a readable message if Android says no. */
    @SuppressLint("MissingPermission")
    suspend fun start(context: Context): Credentials {
        _creds.value?.let { return it }
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw IllegalStateException("This phone has no Wi-Fi")
        return suspendCancellableCoroutine { cont ->
            val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val c = read(res)
                    if (c == null) {
                        runCatching { res.close() }
                        reservation = null
                        if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("The hotspot started without a password Syndesia could read")))
                        return
                    }
                    _creds.value = c
                    if (cont.isActive) cont.resume(c)
                }

                override fun onStopped() {
                    reservation = null
                    _creds.value = null
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    _creds.value = null
                    val why = when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "This phone doesn't allow hotspots (blocked by your carrier or work profile)."
                        ERROR_INCOMPATIBLE_MODE -> "Turn off the phone's regular hotspot first, then try again."
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel for the hotspot right now. Try again in a moment."
                        else -> "Android couldn't start the hotspot."
                    }
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(why)))
                }
            }
            try {
                wm.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("Syndesia needs the Nearby devices (or Location) permission to start a hotspot.")))
            } catch (e: IllegalStateException) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("A hotspot is already running for another app. Try again in a moment.")))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun read(res: WifiManager.LocalOnlyHotspotReservation): Credentials? {
        if (Build.VERSION.SDK_INT >= 30) {
            val cfg = res.softApConfiguration
            val ssid = (if (Build.VERSION.SDK_INT >= 33) cfg.wifiSsid?.toString() else cfg.ssid)?.removeSurrounding("\"")
            val pass = cfg.passphrase
            if (ssid.isNullOrBlank() || pass.isNullOrBlank()) return null
            val wpa3 = cfg.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
            return Credentials(ssid, pass, wpa3)
        }
        val wc = res.wifiConfiguration ?: return null
        val ssid = wc.SSID?.removeSurrounding("\"") ?: return null
        val pass = wc.preSharedKey?.removeSurrounding("\"") ?: return null
        return Credentials(ssid, pass, false)
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        _creds.value = null
    }
}
