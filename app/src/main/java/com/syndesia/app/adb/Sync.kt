package com.syndesia.app.adb

import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** One entry in a watch folder. */
data class RemoteFile(
    val name: String,
    val path: String,
    val mode: Int,
    val size: Long,
    val mtime: Long,
) {
    val isDir: Boolean get() = (mode and 0xF000) == 0x4000
    val isLink: Boolean get() = (mode and 0xF000) == 0xA000
    val isFile: Boolean get() = (mode and 0xF000) == 0x8000
}

/**
 * ADB's file-sync protocol (the one `adb push`, `adb pull` and `adb ls` use), over a `sync:` stream.
 * Little-endian 4-byte ids and lengths; data goes in DATA chunks of at most 64 KB.
 */
class Sync private constructor(private val stream: AdbStream) : AutoCloseable {
    private val input = BufferedInputStream(stream.asInput(), 128 * 1024)
    // Batch small sync frames into big ADB packets; each packet costs a round trip.
    private val out = BufferedOutputStream(stream.asOutput(), writeChunk)

    companion object {
        private const val MAX_DATA = 64 * 1024

        suspend fun <T> use(block: suspend (Sync) -> T): T = withContext(Dispatchers.IO) {
            val s = Sync(Adb.open("sync:"))
            try {
                block(s)
            } finally {
                s.close()
            }
        }
    }

    // --------------------------------------------------------------- wire helpers

    private fun le(v: Int, b: ByteArray, at: Int) {
        b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte(); b[at + 2] = (v shr 16).toByte(); b[at + 3] = (v shr 24).toByte()
    }

    private fun request(id: String, payload: ByteArray) {
        val head = ByteArray(8)
        for (i in 0 until 4) head[i] = id[i].code.toByte()
        le(payload.size, head, 4)
        out.write(head + payload)
        out.flush()
    }

    private fun readFully(b: ByteArray, len: Int = b.size) {
        var got = 0
        while (got < len) {
            val n = input.read(b, got, len - got)
            if (n < 0) throw EOFException("The watch closed the transfer")
            got += n
        }
    }

    private fun readInt(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readId(): String {
        val b = ByteArray(4)
        readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun readString(len: Int): String {
        val b = ByteArray(len)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun fail(): Nothing {
        val len = readInt()
        throw IOException(readString(len).ifBlank { "The watch refused the request" })
    }

    // --------------------------------------------------------------- operations

    /** Folder listing, without "." and "..". */
    fun list(dir: String): List<RemoteFile> {
        request("LIST", dir.toByteArray())
        val res = mutableListOf<RemoteFile>()
        val base = if (dir.endsWith("/")) dir else "$dir/"
        while (true) {
            when (val id = readId()) {
                "DENT" -> {
                    val mode = readInt()
                    val size = readInt().toLong() and 0xFFFFFFFFL
                    val mtime = readInt().toLong() and 0xFFFFFFFFL
                    val name = readString(readInt())
                    if (name != "." && name != "..") res += RemoteFile(name, base + name, mode, size, mtime)
                }
                "DONE" -> { repeat(4) { readInt() }; return res }
                "FAIL" -> fail()
                else -> throw IOException("Unexpected reply $id")
            }
        }
    }

    /** mode, size, mtime; mode is 0 if the path doesn't exist. */
    fun stat(path: String): RemoteFile {
        request("STAT", path.toByteArray())
        val id = readId()
        if (id != "STAT") throw IOException("Unexpected reply $id")
        val mode = readInt()
        val size = readInt().toLong() and 0xFFFFFFFFL
        val mtime = readInt().toLong() and 0xFFFFFFFFL
        return RemoteFile(path.substringAfterLast('/'), path, mode, size, mtime)
    }

    /** Upload [source] to [path] on the watch. */
    suspend fun push(source: InputStream, path: String, mode: Int = 420 /* 0644 */, onProgress: (Long) -> Unit = {}) {
        request("SEND", "$path,$mode".toByteArray())
        val buf = ByteArray(MAX_DATA)
        var sent = 0L
        while (true) {
            coroutineContext.ensureActive()
            var n = 0
            // Fill whole chunks so packets stay big.
            while (n < buf.size) {
                val r = source.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            val head = ByteArray(8)
            "DATA".forEachIndexed { i, c -> head[i] = c.code.toByte() }
            le(n, head, 4)
            out.write(head)
            out.write(buf, 0, n)
            sent += n
            onProgress(sent)
            if (n < buf.size) break
        }
        val done = ByteArray(8)
        "DONE".forEachIndexed { i, c -> done[i] = c.code.toByte() }
        le((System.currentTimeMillis() / 1000).toInt(), done, 4)
        out.write(done)
        out.flush()
        when (val id = readId()) {
            "OKAY" -> readInt()
            "FAIL" -> fail()
            else -> throw IOException("Unexpected reply $id")
        }
    }

    /** Download [path] from the watch into [dest]. */
    suspend fun pull(path: String, dest: OutputStream, onProgress: (Long) -> Unit = {}) {
        request("RECV", path.toByteArray())
        val buf = ByteArray(MAX_DATA)
        var got = 0L
        while (true) {
            coroutineContext.ensureActive()
            when (val id = readId()) {
                "DATA" -> {
                    val len = readInt()
                    if (len > buf.size) throw IOException("Chunk too large")
                    readFully(buf, len)
                    dest.write(buf, 0, len)
                    got += len
                    onProgress(got)
                }
                "DONE" -> { readInt(); dest.flush(); return }
                "FAIL" -> fail()
                else -> throw IOException("Unexpected reply $id")
            }
        }
    }

    override fun close() {
        runCatching {
            request("QUIT", ByteArray(0))
        }
        runCatching { stream.close() }
    }
}
