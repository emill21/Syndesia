package com.syndesia.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Makes this device a Bluetooth mouse + keyboard ("HID device" profile, Android 9+).
 * The computer pairs with it like any wireless mouse; nothing to install there.
 *
 * Mouse report (id 1): buttons, dx, dy, wheel, horizontal pan.
 * Keyboard report (id 2): modifiers, reserved, six key codes.
 */
@SuppressLint("MissingPermission")
object HidMouse {
    enum class Status { Off, Starting, Unsupported, Ready, Connecting, Connected }

    data class Host(val address: String, val name: String, val isComputer: Boolean)

    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 4

    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _host = MutableStateFlow<Host?>(null)
    /** The computer we're connected to (or connecting to). */
    val host: StateFlow<Host?> = _host.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var hid: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private var appContext: Context? = null
    private var buttons = 0
    private var fracX = 0f
    private var fracY = 0f
    private var wantConnect: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val descriptor = byteArrayOf(
        // Mouse, report id 1
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x01, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte(),
        // Keyboard, report id 2
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
        0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
        0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
        0xC0.toByte(),
    )

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("hid", Context.MODE_PRIVATE)

    fun supportedOs(): Boolean = Build.VERSION.SDK_INT >= 28

    private fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Register as a mouse/keyboard. Reconnects to the last computer if there was one. */
    fun start(context: Context, name: String) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!supportedOs()) { _status.value = Status.Unsupported; return }
        if (hid != null) {
            if (_status.value == Status.Ready) prefs(ctx).getString("last", null)?.let { connect(ctx, it) }
            return
        }
        val a = adapter(ctx) ?: run { _status.value = Status.Unsupported; _error.value = "No Bluetooth"; return }
        _status.value = Status.Starting
        _error.value = null
        wantConnect = prefs(ctx).getString("last", null)
        val ok = runCatching {
            a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    register(proxy as BluetoothHidDevice, name)
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    device = null
                    _status.value = Status.Off
                }
            }, BluetoothProfile.HID_DEVICE)
        }.getOrElse { e ->
            _error.value = if (e is SecurityException) "Allow the Nearby devices permission" else e.message
            false
        }
        if (!ok) {
            if (_error.value == null) _error.value = "This device can't act as a Bluetooth mouse"
            _status.value = Status.Unsupported
        } else {
            // Some devices never answer if the HID device profile is missing.
            executor.execute {
                Thread.sleep(4000)
                if (_status.value == Status.Starting) {
                    _status.value = Status.Unsupported
                    _error.value = "This device can't act as a Bluetooth mouse"
                }
            }
        }
    }

    @RequiresApi(28)
    private fun register(h: BluetoothHidDevice, name: String) {
        hid = h
        val sdp = BluetoothHidDeviceAppSdpSettings(
            name, "Mouse and keyboard", "Syndesia",
            BluetoothHidDevice.SUBCLASS1_COMBO, descriptor,
        )
        val cb = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered) {
                    _status.value = Status.Ready
                    val target = pluggedDevice?.address ?: wantConnect
                    if (target != null) appContext?.let { connect(it, target) }
                } else {
                    _status.value = if (hid == null) Status.Off else Status.Ready
                }
            }

            override fun onConnectionStateChanged(d: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        device = d
                        _host.value = hostOf(d)
                        _status.value = Status.Connected
                        appContext?.let { prefs(it).edit().putString("last", d.address).apply() }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _host.value = hostOf(d)
                        _status.value = Status.Connecting
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (device?.address == d.address || device == null) {
                            device = null
                            if (_status.value != Status.Off) _status.value = Status.Ready
                        }
                    }
                }
            }
        }
        val ok = runCatching { h.registerApp(sdp, null, null, executor, cb) }.getOrDefault(false)
        if (!ok) {
            _status.value = Status.Unsupported
            _error.value = "Bluetooth didn't accept the mouse. Turn Bluetooth off and on, then try again."
        }
    }

    /** Stop being a mouse (the computer sees it disconnect). */
    fun stop() {
        val h = hid ?: return
        runCatching { device?.let { h.disconnect(it) } }
        runCatching { if (Build.VERSION.SDK_INT >= 28) h.unregisterApp() }
        val ctx = appContext
        if (ctx != null) runCatching { adapter(ctx)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null
        device = null
        _host.value = null
        _status.value = Status.Off
    }

    private fun hostOf(d: BluetoothDevice): Host {
        val name = runCatching { d.name }.getOrNull() ?: d.address
        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        return Host(d.address, name, major == BluetoothClass.Device.Major.COMPUTER)
    }

    /** Paired devices, computers first. */
    fun paired(context: Context): List<Host> = runCatching {
        adapter(context)?.bondedDevices.orEmpty().map { hostOf(it) }
            .sortedWith(compareBy<Host> { !it.isComputer }.thenBy { it.name.lowercase() })
    }.getOrDefault(emptyList())

    fun lastHost(context: Context): String? = prefs(context).getString("last", null)

    fun connect(context: Context, address: String) {
        val h = hid ?: run { wantConnect = address; return }
        val d = runCatching { adapter(context)?.getRemoteDevice(address) }.getOrNull() ?: return
        wantConnect = address
        if (device?.address == address && _status.value == Status.Connected) return
        _host.value = hostOf(d)
        _status.value = Status.Connecting
        val ok = runCatching { if (Build.VERSION.SDK_INT >= 28) h.connect(d) else false }.getOrDefault(false)
        if (!ok) {
            _status.value = Status.Ready
            _error.value = "Couldn't reach ${hostOf(d).name}. Make sure its Bluetooth is on."
        }
    }

    fun disconnect() {
        val h = hid ?: return
        device?.let { runCatching { if (Build.VERSION.SDK_INT >= 28) h.disconnect(it) } }
        wantConnect = null
        appContext?.let { prefs(it).edit().remove("last").apply() }
    }

    // --------------------------------------------------------------- reports

    private fun send(id: Int, data: ByteArray): Boolean {
        val h = hid ?: return false
        val d = device ?: return false
        return runCatching { if (Build.VERSION.SDK_INT >= 28) h.sendReport(d, id, data) else false }.getOrDefault(false)
    }

    private fun clamp(v: Int) = v.coerceIn(-127, 127).toByte()

    /** Move by (dx, dy) pixels on the computer; fractions carry over so slow moves aren't lost. */
    fun move(dx: Float, dy: Float) {
        fracX += dx
        fracY += dy
        var ix = fracX.roundToInt()
        var iy = fracY.roundToInt()
        fracX -= ix
        fracY -= iy
        // Big jumps go out as several reports (each is limited to ±127).
        while (ix != 0 || iy != 0) {
            val sx = ix.coerceIn(-127, 127)
            val sy = iy.coerceIn(-127, 127)
            send(1, byteArrayOf(buttons.toByte(), clamp(sx), clamp(sy), 0, 0))
            ix -= sx
            iy -= sy
        }
    }

    /** Scroll wheel: positive = up (like rolling the wheel away from you). */
    fun wheel(ticks: Int, horizontal: Int = 0) {
        if (ticks == 0 && horizontal == 0) return
        send(1, byteArrayOf(buttons.toByte(), 0, 0, clamp(ticks), clamp(horizontal)))
    }

    fun press(button: Int) {
        buttons = buttons or button
        send(1, byteArrayOf(buttons.toByte(), 0, 0, 0, 0))
    }

    fun release(button: Int) {
        buttons = buttons and button.inv()
        send(1, byteArrayOf(buttons.toByte(), 0, 0, 0, 0))
    }

    fun click(button: Int = LEFT) {
        press(button)
        release(button)
    }

    /** Type text (US keyboard layout). */
    fun type(text: String) {
        for (c in text) {
            val (code, shift) = Keymap.of(c) ?: continue
            key(code, if (shift) Keymap.SHIFT else 0)
        }
    }

    /** Press and release one key with optional modifiers. */
    fun key(code: Int, modifiers: Int = 0) {
        send(2, byteArrayOf(modifiers.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
        send(2, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
    }
}

/** Characters to HID usage codes, US layout. */
object Keymap {
    const val SHIFT = 0x02
    const val CTRL = 0x01
    const val ALT = 0x04
    const val GUI = 0x08

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"
    private const val PLAIN = "`1234567890-=[]\\;',./"
    private val codes = intArrayOf(0x35, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x36, 0x37, 0x38)

    fun of(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        ' ' -> 0x2C to false
        '\n' -> ENTER to false
        '\t' -> TAB to false
        else -> {
            val p = PLAIN.indexOf(c)
            val s = SHIFTED.indexOf(c)
            when {
                p >= 0 -> codes[p] to false
                s >= 0 -> codes[s] to true
                else -> null
            }
        }
    }
}
