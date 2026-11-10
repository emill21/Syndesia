package com.syndesia.app.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/** What we know about the connected watch. */
data class WatchInfo(
    val host: String,
    val port: Int,
    val model: String,
    val manufacturer: String,
    val release: String,
    val sdk: Int,
    val serial: String,
    val isWatch: Boolean,
)

sealed interface Link {
    data object Idle : Link
    data class Connecting(val host: String, val port: Int, val waitingForApproval: Boolean = false) : Link
    data class Connected(val info: WatchInfo) : Link
    data class Failed(val host: String, val port: Int, val message: String, val needsPairing: Boolean = false) : Link
}

class WatchNotConnected : IOException("Not connected to a watch")

private class Manager(
    private val key: PrivateKey,
    private val cert: Certificate,
    private val name: String,
) : AbsAdbConnectionManager() {
    init {
        // Wear OS 3+ is Android 11+. Anything P or later gets the 1 MB packets and TLS.
        api = Build.VERSION_CODES.R
        setTimeout(25, TimeUnit.SECONDS)
        isThrowOnUnauthorised = false
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = name
}

/**
 * The one ADB link to a watch. Everything blocks on the network, so every call hops to [Dispatchers.IO].
 */
object Adb {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _link = MutableStateFlow<Link>(Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    private var manager: Manager? = null
    private val connectLock = Mutex()
    private var watchdog: Job? = null

    /** Called when the link drops on its own, so the app can try to find the watch again. */
    var onLost: ((host: String, port: Int) -> Unit)? = null

    val connected: WatchInfo? get() = (_link.value as? Link.Connected)?.info

    private fun manager(context: Context): Manager {
        manager?.let { return it }
        val (key, cert) = Keys.load(context)
        return Manager(key, cert, "Syndesia (${Build.MODEL})").also { manager = it }
    }

    /** Throw away the cached identity (after [Keys.reset]). */
    fun resetIdentity() {
        runCatching { manager?.disconnect() }
        manager = null
        _link.value = Link.Idle
    }

    /** Pair using the six-digit code from Settings › Developer options › Wireless debugging › Pair new device. */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            reachable(host, port)
            manager(context).pair(host, port, code.trim())
            Unit
        }.recoverCatching { e ->
            throw IOException(friendly(e, pairing = true), e)
        }
    }

    suspend fun connect(context: Context, host: String, port: Int): Result<WatchInfo> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            val m = manager(context)
            runCatching { m.disconnect() }
            watchdog?.cancel()
            _link.value = Link.Connecting(host, port)
            // A slow "Allow debugging?" prompt on older watches: flag it so the UI can say so.
            val hint = scope.launch {
                delay(3500)
                val l = _link.value
                if (l is Link.Connecting && l.host == host && l.port == port) _link.value = l.copy(waitingForApproval = true)
            }
            val result = runCatching {
                reachable(host, port)
                val ok = m.connect(host, port)
                if (!ok && !m.isConnected) throw IOException("The watch didn't answer in time. If it asked \"Allow debugging?\", tap Allow and try again.")
                writeChunk = runCatching { m.adbConnection?.maxData ?: 4096 }.getOrDefault(4096).coerceIn(4096, 256 * 1024)
                readInfo(host, port)
            }
            hint.cancel()
            result.onSuccess {
                _link.value = Link.Connected(it)
                startWatchdog(host, port)
            }.onFailure { e ->
                runCatching { m.disconnect() }
                val pairing = e is AdbPairingRequiredException || e.cause is AdbPairingRequiredException
                _link.value = Link.Failed(host, port, friendly(e, pairing = false), needsPairing = pairing)
            }
            result
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        watchdog?.cancel()
        runCatching { manager?.disconnect() }
        _link.value = Link.Idle
    }

    /** Clear a failure message (e.g. after the user dismisses it). */
    fun clearFailure() {
        if (_link.value is Link.Failed) _link.value = Link.Idle
    }

    private fun startWatchdog(host: String, port: Int) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(5000)
                val m = manager ?: break
                if (_link.value !is Link.Connected) break
                if (!m.isConnected) {
                    runCatching { m.disconnect() }
                    _link.value = Link.Failed(host, port, "Lost the connection to the watch.")
                    onLost?.invoke(host, port)
                    break
                }
            }
        }
    }

    private fun reachable(host: String, port: Int) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 5000) }
        } catch (e: IOException) {
            throw IOException("Couldn't reach $host:$port. Check the address, and that the watch is on the same Wi-Fi with Wireless debugging on.", e)
        }
    }

    private fun friendly(e: Throwable, pairing: Boolean): String {
        val msg = e.message.orEmpty()
        return when {
            e is AdbPairingRequiredException || e.cause is AdbPairingRequiredException ->
                "This watch hasn't been paired with Syndesia yet. Pair it with a code first."
            e is AdbAuthenticationFailedException -> "The watch refused this phone. Tap Allow on the watch, or pair again."
            msg.startsWith("Couldn't reach") || msg.startsWith("The watch didn't") -> msg
            pairing && (msg.contains("SSL", true) || msg.contains("handshake", true) || msg.contains("closed", true) || msg.contains("reset", true)) ->
                "Pairing failed. Check the six-digit code (it changes each time) and the pairing port, which is different from the connect port."
            pairing -> "Pairing failed: ${msg.ifBlank { e.javaClass.simpleName }}"
            msg.contains("Connection failed", true) || msg.contains("closed", true) || msg.contains("reset", true) ->
                "The watch closed the connection. Make sure Wireless debugging is still on and the port is right (it changes whenever it's switched off and on)."
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    // ------------------------------------------------------------------ streams

    /** Open an ADB service such as `exec:…`, `shell:…` or `sync:`. Caller closes it. */
    suspend fun open(destination: String): AdbStream = withContext(Dispatchers.IO) {
        val m = manager
        if (m == null || _link.value !is Link.Connected) throw WatchNotConnected()
        try {
            m.openStream(destination)
        } catch (e: IOException) {
            if (!m.isConnected) _link.value.let { l -> if (l is Link.Connected) lost(l.info) }
            throw e
        }
    }

    private fun lost(info: WatchInfo) {
        watchdog?.cancel()
        runCatching { manager?.disconnect() }
        _link.value = Link.Failed(info.host, info.port, "Lost the connection to the watch.")
        onLost?.invoke(info.host, info.port)
    }

    /** Run a shell command on the watch and return everything it printed (stdout and stderr). */
    suspend fun sh(command: String): String = withContext(Dispatchers.IO) {
        val s = open("exec:$command 2>&1")
        try {
            String(s.readAll(), Charsets.UTF_8)
        } finally {
            runCatching { s.close() }
        }
    }

    /** Raw bytes from a command, e.g. `screencap -p`. */
    suspend fun execBytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val s = open("exec:$command")
        try {
            s.readAll()
        } finally {
            runCatching { s.close() }
        }
    }

    private suspend fun readInfo(host: String, port: Int): WatchInfo {
        val m = manager ?: throw WatchNotConnected()
        // The link isn't "Connected" yet, so open streams directly.
        fun run(cmd: String): String {
            val s = m.openStream("exec:$cmd")
            return try { String(s.readAll(), Charsets.UTF_8) } finally { runCatching { s.close() } }
        }
        val props = run("getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.serialno; getprop ro.build.characteristics")
            .lines()
        fun p(i: Int) = props.getOrNull(i)?.trim().orEmpty()
        return WatchInfo(
            host = host,
            port = port,
            model = p(0).ifBlank { "Watch" },
            manufacturer = p(1),
            release = p(2),
            sdk = p(3).toIntOrNull() ?: 0,
            serial = p(4),
            isWatch = p(5).contains("watch"),
        )
    }

    /** Quote a string for the watch's shell. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}

// ------------------------------------------------------------------ stream helpers

/**
 * Max bytes per WRTE packet we send. libadb doesn't split big writes into READY-acknowledged
 * packets itself, so we do, sized to what the watch said it accepts (1 MB on Android 9+), capped.
 */
@Volatile
internal var writeChunk = 64 * 1024

internal fun AdbStream.readAll(): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    val input = asInput()
    while (true) {
        val n = input.read(buf, 0, buf.size)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** InputStream view that reports end-of-stream (instead of throwing) once the watch closes the stream. */
internal fun AdbStream.asInput(): InputStream = object : InputStream() {
    private val one = ByteArray(1)
    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val n = try {
                this@asInput.read(b, off, len)
            } catch (e: IOException) {
                if (this@asInput.isClosed) return -1 else throw e
            }
            if (n != 0) return n
        }
    }
}

/** OutputStream view that sends at most [writeChunk] bytes per packet. */
internal fun AdbStream.asOutput(): OutputStream = object : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, writeChunk)
            this@asOutput.write(b, o, n)
            o += n
            left -= n
        }
    }
    override fun flush() = this@asOutput.flush()
}
