package com.syndesia.app.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.coroutines.coroutineContext

/** What one knock on a port found. */
enum class PortState {
    /** Something is listening. */
    Open,

    /** The watch answered, but nothing is listening on that port (it changed, or debugging is off). */
    Closed,

    /** Nobody answered at that address: Wi-Fi is asleep, or the watch has a different address now. */
    Unreachable,
}

/**
 * Finds open TCP ports on the watch. Wireless debugging listens on a random high port and normally
 * announces it over mDNS, but those announcements often don't cross the phone's own hotspot, so we
 * look for it directly. Non-blocking connects, a batch at a time: the whole range takes seconds.
 */
object PortScan {
    /** Where Android picks Wireless debugging's port (the kernel's ephemeral range), plus classic 5555. */
    val ADB_RANGES = listOf(5555..5555, 30000..60999)

    /**
     * One quick knock on a single port, without touching the ADB link. Cheap enough to repeat while
     * waiting for a watch to show up. Blocks for up to [timeoutMs]: call it off the main thread.
     */
    fun knock(host: String, port: Int, timeoutMs: Int = 2500): PortState = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        PortState.Open
    } catch (e: ConnectException) {
        if (e.message.orEmpty().contains("refused", ignoreCase = true)) PortState.Closed else PortState.Unreachable
    } catch (e: IOException) {
        PortState.Unreachable
    } catch (e: RuntimeException) {
        PortState.Unreachable // a malformed address
    }

    // ADB packet commands, as the little-endian words they travel as.
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val STLS = 0x534c5453

    /**
     * Does whatever is listening on this port speak ADB? Sends ADB's opening CONNECT and checks that
     * the reply is one of the three things adbd answers it with: STLS (Wireless debugging), AUTH or
     * CNXN (classic port 5555). Then hangs up, before anything that would make the watch ask the
     * user a question. Blocks for up to [timeoutMs] twice over: call it off the main thread.
     */
    fun speaksAdb(host: String, port: Int, timeoutMs: Int = 1500): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val banner = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CNXN)
                .putInt(0x01000001) // protocol version
                .putInt(1024 * 1024) // largest packet we take
                .putInt(banner.size)
                .putInt(banner.sumOf { it.toInt() and 0xFF }) // checksum; only old adbd still looks at it
                .putInt(CNXN.inv())
            s.getOutputStream().apply {
                write(head.array())
                write(banner)
                flush()
            }
            val reply = ByteArray(24)
            val input = s.getInputStream()
            var got = 0
            while (got < reply.size) {
                val n = input.read(reply, got, reply.size - got)
                if (n < 0) break
                got += n
            }
            if (got < reply.size) return@use false
            val words = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
            val command = words.getInt(0)
            words.getInt(20) == command.inv() && (command == STLS || command == AUTH || command == CNXN)
        }
    }.getOrDefault(false)

    /**
     * Scan the watch for its ADB port, checking that what's found really answers as ADB. Looks through
     * Wireless debugging's range a slice at a time and stops at the first hit, then falls back to
     * classic 5555. Empty if nothing on the watch speaks ADB. Takes up to a quarter of a minute.
     */
    suspend fun findAdb(host: String): List<Int> = withContext(Dispatchers.IO) {
        val wireless = ADB_RANGES.last()
        for (from in wireless.first..wireless.last step 4000) {
            val slice = from..minOf(from + 3999, wireless.last)
            for (p in open(host, listOf(slice))) {
                coroutineContext.ensureActive()
                if (speaksAdb(host, p)) return@withContext listOf(p)
            }
        }
        if (speaksAdb(host, 5555)) listOf(5555) else emptyList()
    }

    suspend fun open(host: String, ranges: List<IntRange> = ADB_RANGES, batch: Int = 800, waitMs: Long = 400): List<Int> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<Int>()
            val ports = ranges.flatMap { it.toList() }
            Selector.open().use { selector ->
                for (chunk in ports.chunked(batch)) {
                    coroutineContext.ensureActive()
                    val channels = ArrayList<SocketChannel>(chunk.size)
                    for (p in chunk) {
                        val ch = runCatching { SocketChannel.open() }.getOrNull() ?: continue
                        runCatching {
                            ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(host, p))) found += p
                            else ch.register(selector, SelectionKey.OP_CONNECT, p)
                        }
                        channels += ch
                    }
                    val deadline = System.currentTimeMillis() + waitMs
                    while (true) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) break
                        if (selector.select(left) == 0) continue
                        val it = selector.selectedKeys().iterator()
                        while (it.hasNext()) {
                            val key = it.next()
                            it.remove()
                            val ch = key.channel() as SocketChannel
                            if (runCatching { ch.finishConnect() }.getOrDefault(false)) found += key.attachment() as Int
                            key.cancel()
                            runCatching { ch.close() }
                        }
                    }
                    channels.forEach { runCatching { it.close() } }
                    runCatching { selector.selectNow() } // flush cancelled keys before the next batch
                }
            }
            found.distinct().sorted()
        }
}
