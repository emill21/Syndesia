package com.syndesia.app.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.coroutines.coroutineContext

/**
 * Finds open TCP ports on the watch. Wireless debugging listens on a random high port and normally
 * announces it over mDNS, but those announcements often don't cross the phone's own hotspot, so we
 * look for it directly. Non-blocking connects, a batch at a time: the whole range takes seconds.
 */
object PortScan {
    /** Where Android picks Wireless debugging's port (the kernel's ephemeral range), plus classic 5555. */
    val ADB_RANGES = listOf(5555..5555, 30000..60999)

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
