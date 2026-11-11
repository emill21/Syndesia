package com.syndesia.app.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbConnection
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
    data class Connecting(val host: String, val port: Int, val waitingForApproval: Boolean = false, val reconnecting: Boolean = false) : Link
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

    /** Called when the link drops for good (reconnecting gave up), so the app can look for the watch again. */
    var onLost: ((host: String, port: Int) -> Unit)? = null

    val connected: WatchInfo? get() = (_link.value as? Link.Connected)?.info

    private var appContext: Context? = null

    /** True from a successful connect until the user disconnects: while set, drops are reconnected automatically. */
    @Volatile
    private var wanted = false
    private var reconnectJob: Job? = null

    @Volatile
    private var lastPing = 0L

    /**
     * The live connection, kept outside the manager. The manager guards everything with one lock, and a
     * stream open waiting on a silent watch holds that lock forever, so closing through the manager can
     * hang. Closing the connection's socket directly always works and wakes every waiting stream.
     */
    @Volatile
    private var conn: AdbConnection? = null

    /** Set when the user taps Disconnect: no automatic connecting until they connect again by hand. */
    @Volatile
    var userDisconnected = false
        private set

    private fun manager(context: Context): Manager {
        manager?.let { return it }
        val (key, cert) = Keys.load(context)
        return Manager(key, cert, "Syndesia (${Build.MODEL})").also { manager = it }
    }

    /** Throw away the cached identity (after [Keys.reset]). */
    fun resetIdentity() {
        wanted = false
        reconnectJob?.cancel()
        watchdog?.cancel()
        teardown()
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

    /** User-initiated connect. Cancels any automatic reconnect in progress. */
    suspend fun connect(context: Context, host: String, port: Int): Result<WatchInfo> {
        reconnectJob?.cancel()
        userDisconnected = false
        return connectOnce(context.applicationContext, host, port, reconnecting = false)
    }

    private suspend fun connectOnce(context: Context, host: String, port: Int, reconnecting: Boolean): Result<WatchInfo> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            val m = manager(context)
            watchdog?.cancel()
            closeConn()
            runCatching { m.disconnect() }
            _link.value = Link.Connecting(host, port, reconnecting = reconnecting)
            // A slow "Allow debugging?" prompt on older watches: flag it so the UI can say so.
            val hint = if (reconnecting) null else scope.launch {
                delay(3500)
                val l = _link.value
                if (l is Link.Connecting && l.host == host && l.port == port) _link.value = l.copy(waitingForApproval = true)
            }
            val result = runCatching {
                reachable(host, port)
                val ok = m.connect(host, port)
                if (!ok && !m.isConnected) throw IOException("The watch didn't answer in time. If it asked \"Allow debugging?\", tap Allow and try again.")
                conn = m.adbConnection
                writeChunk = runCatching { m.adbConnection?.maxData ?: 4096 }.getOrDefault(4096).coerceIn(4096, 256 * 1024)
                readInfo(host, port)
            }
            hint?.cancel()
            result.onSuccess {
                if (reconnecting && !wanted) {
                    // The user disconnected while we were reconnecting.
                    closeConn()
                    runCatching { m.disconnect() }
                    _link.value = Link.Idle
                } else {
                    appContext = context
                    wanted = true
                    _link.value = Link.Connected(it)
                    startWatchdog()
                }
            }.onFailure { e ->
                closeConn()
                runCatching { m.disconnect() }
                if (!reconnecting) {
                    val pairing = e is AdbPairingRequiredException || e.cause is AdbPairingRequiredException
                    _link.value = Link.Failed(host, port, friendly(e, pairing = false), needsPairing = pairing)
                }
            }
            result
        }
    }

    /** Only the user (or closing the app) ends the link. Returns at once; the socket closes in the background. */
    fun disconnect() {
        wanted = false
        userDisconnected = true
        reconnectJob?.cancel()
        watchdog?.cancel()
        _link.value = Link.Idle
        teardown()
    }

    private fun closeConn() {
        val c = conn
        conn = null
        if (c != null) runCatching { c.close() }
    }

    /**
     * Close the current connection's socket directly, off the calling thread. It never waits on the
     * manager's lock, and only ever touches the connection that was live when it was called.
     */
    private fun teardown() {
        val c = conn
        conn = null
        if (c != null) scope.launch { runCatching { c.close() } }
    }

    /** Clear a failure message (e.g. after the user dismisses it). */
    fun clearFailure() {
        if (_link.value is Link.Failed) _link.value = Link.Idle
    }

    /**
     * Keep-alive: a tiny command every few seconds. It keeps the watch's Wi-Fi from dozing off and
     * notices a dead link (watch left Wi-Fi, debugging switched off) within ~30 s.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        lastPing = System.currentTimeMillis()
        watchdog = scope.launch {
            while (isActive) {
                delay(6000)
                val m = manager ?: break
                val l = _link.value as? Link.Connected ?: break
                if (!m.isConnected || System.currentTimeMillis() - lastPing > 30_000) {
                    lost(l.info)
                    break
                }
                scope.launch {
                    runCatching {
                        val st = m.openStream("exec:echo")
                        try { st.readAll() } finally { runCatching { st.close() } }
                    }.onSuccess { lastPing = System.currentTimeMillis() }
                }
            }
        }
    }

    private fun lost(info: WatchInfo) {
        watchdog?.cancel()
        teardown()
        val ctx = appContext
        if (wanted && ctx != null) reconnect(ctx, info)
        else {
            _link.value = Link.Failed(info.host, info.port, "Lost the connection to the watch.")
            onLost?.invoke(info.host, info.port)
        }
    }

    /** Try to get the link back for two minutes, using a new port from mDNS if the watch advertises one. */
    private fun reconnect(ctx: Context, info: WatchInfo) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val deadline = System.currentTimeMillis() + 120_000
            var attempt = 0
            _link.value = Link.Connecting(info.host, info.port, reconnecting = true)
            while (isActive && wanted && !userDisconnected && System.currentTimeMillis() < deadline) {
                val f = Discovery.found.value.firstOrNull {
                    it.host == info.host || (info.serial.isNotBlank() && it.serial == info.serial)
                }
                val host = f?.host ?: info.host
                val port = f?.connectPort ?: info.port
                val r = connectOnce(ctx, host, port, reconnecting = true)
                if (r.isSuccess) return@launch
                val e = r.exceptionOrNull()
                if (e is AdbPairingRequiredException || e?.cause is AdbPairingRequiredException) break
                delay(if (attempt++ < 5) 2000 else 5000)
            }
            if (wanted && isActive) {
                wanted = false
                _link.value = Link.Failed(info.host, info.port, "Lost the connection to the watch. Check it's still on Wi-Fi with Wireless debugging on.")
                onLost?.invoke(info.host, info.port)
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
