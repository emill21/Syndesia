package com.syndesia.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks Wear OS for Wi-Fi and keeps it up while Syndesia on the phone wants it.
 *
 * Wear OS normally keeps Wi-Fi off while Bluetooth to the phone is connected. An app that requests a
 * Wi-Fi network makes the system bring Wi-Fi up (joining a saved network) until the request is
 * released. Once Wi-Fi is up we also switch Wireless debugging back on, if the phone granted us
 * WRITE_SECURE_SETTINGS during setup.
 */
object WifiHold {
    enum class State { Off, Asking, On }

    private val _state = MutableStateFlow(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    /** How long Wi-Fi stays held after the phone's last wake. The phone repeats wakes while connected. */
    private const val HOLD_MS = 20 * 60 * 1000L

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val timeout = Runnable { appContext?.let { release(it) } }

    @Synchronized
    fun acquire(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, HOLD_MS)
        if (callback != null) {
            // Already holding; make sure debugging is on in case it was switched off meanwhile.
            if (_state.value == State.On) enableWirelessDebugging(ctx)
            return
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.value = State.On
                enableWirelessDebugging(ctx)
            }

            override fun onLost(network: Network) {
                if (callback != null) _state.value = State.Asking
            }

            override fun onUnavailable() {
                _state.value = State.Off
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.requestNetwork(request, cb)
            callback = cb
            _state.value = State.Asking
        }.onFailure { _state.value = State.Off }
    }

    @Synchronized
    fun release(context: Context) {
        main.removeCallbacks(timeout)
        HotspotJoin.release(context.applicationContext)
        val cb = callback ?: return
        callback = null
        runCatching { context.applicationContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        _state.value = State.Off
        WifiKeeper.stop(context)
    }

    val holding: Boolean get() = callback != null

    fun canSetDebugging(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun wirelessDebuggingOn(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun wifiSwitchedOff(context: Context): Boolean = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == false &&
            Settings.Global.getInt(context.contentResolver, "wifi_on", 1) == 0
    }.getOrDefault(false)

    /** Turn Wireless debugging on (needs the permission the phone grants at install). */
    fun enableWirelessDebugging(context: Context): Boolean {
        if (!canSetDebugging(context)) return false
        return runCatching {
            val cr = context.contentResolver
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            true
        }.getOrDefault(false)
    }
}
