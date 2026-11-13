package com.syndesia.wear

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's files, over Bluetooth, for Arkheia (or any app Syndesia trusts on this watch).
 * Folder listings come back as messages; files stream over a channel straight into a pipe.
 */
object PhoneFs {
    private const val CAPABILITY = "syndesia_phone"
    private const val PATH_LIST = "/syndesia/pfs/list"
    private const val PATH_GET = "/syndesia/pfs/get"
    const val PATH_REPLY = "/syndesia/pfs/reply"
    const val PATH_DATA = "/syndesia/pfs/data"

    class Failure(val code: String, message: String) : IOException(message)

    data class Listing(val path: String, val root: String, val items: List<JSONArray>)

    private class Pending {
        val items = mutableListOf<JSONArray>()
        val done = CompletableDeferred<Listing>()
    }

    private val lists = ConcurrentHashMap<String, Pending>()
    private val gets = ConcurrentHashMap<String, CompletableDeferred<Pair<ChannelClient.Channel, Long>>>()
    private val getErrors = ConcurrentHashMap<String, CompletableDeferred<Failure>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun phone(ctx: Context): String? = runCatching {
        withTimeoutOrNull(5000) {
            val nodes = Wearable.getCapabilityClient(ctx).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                .ifEmpty { Wearable.getNodeClient(ctx).connectedNodes.await().toSet() }
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        }
    }.getOrNull()

    suspend fun list(ctx: Context, path: String, hidden: Boolean): Listing {
        val node = phone(ctx) ?: throw Failure("offline", "Your phone isn't connected over Bluetooth.")
        val id = "l${System.nanoTime()}"
        val p = Pending().also { lists[id] = it }
        try {
            val body = JSONObject().put("id", id).put("path", path).put("hidden", hidden).toString().toByteArray()
            withTimeoutOrNull(8000) { Wearable.getMessageClient(ctx).sendMessage(node, PATH_LIST, body).await() }
                ?: throw Failure("offline", "Couldn't reach your phone over Bluetooth.")
            return withTimeoutOrNull(20_000) { p.done.await() }
                ?: throw Failure("noreply", "Your phone didn't answer. Update Syndesia on your phone and open it once.")
        } finally {
            lists.remove(id)
        }
    }

    /** Called by [WakeListener] for /syndesia/pfs/reply. */
    fun onReply(data: ByteArray) {
        val o = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return
        val id = o.optString("id")
        if (o.has("error")) {
            val f = Failure(o.optString("error"), o.optString("message", "Couldn't read your phone's files."))
            lists[id]?.done?.completeExceptionally(f)
            getErrors[id]?.complete(f)
            return
        }
        val p = lists[id] ?: return
        o.optJSONArray("items")?.let { a -> synchronized(p) { for (i in 0 until a.length()) p.items.add(a.getJSONArray(i)) } }
        if (o.optBoolean("done")) p.done.complete(Listing(o.optString("path"), o.optString("root"), synchronized(p) { p.items.toList() }))
    }

    /** Called by [WakeListener] when the phone opens a file channel. */
    fun accept(ctx: Context, channel: ChannelClient.Channel) {
        scope.launch {
            val client = Wearable.getChannelClient(ctx)
            val input = runCatching { DataInputStream(client.getInputStream(channel).await()) }.getOrNull()
            val header = runCatching {
                val n = input!!.readInt()
                if (n <= 0 || n > 64 * 1024) error("bad header")
                JSONObject(String(ByteArray(n).also { input.readFully(it) }, Charsets.UTF_8))
            }.getOrNull()
            val waiter = header?.optString("id")?.let { gets[it] }
            if (waiter == null) { runCatching { client.close(channel).await() }; return@launch }
            // Hand the open stream over; the copier below reads the rest.
            streams[header.optString("id")] = input
            waiter.complete(channel to header.optLong("size", -1))
        }
    }

    private val streams = ConcurrentHashMap<String, DataInputStream>()

    /** Streams a phone file into a pipe; the caller reads the other end as it arrives. */
    fun open(ctx: Context, path: String): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        scope.launch {
            val id = "g${System.nanoTime()}"
            val ready = CompletableDeferred<Pair<ChannelClient.Channel, Long>>().also { gets[id] = it }
            val failed = CompletableDeferred<Failure>().also { getErrors[id] = it }
            var channel: ChannelClient.Channel? = null
            try {
                val node = phone(ctx) ?: throw Failure("offline", "Your phone isn't connected over Bluetooth.")
                val body = JSONObject().put("id", id).put("path", path).toString().toByteArray()
                withTimeoutOrNull(8000) { Wearable.getMessageClient(ctx).sendMessage(node, PATH_GET, body).await() }
                    ?: throw Failure("offline", "Couldn't reach your phone over Bluetooth.")
                val got = withTimeoutOrNull(25_000) {
                    kotlinx.coroutines.selects.select<Any> {
                        ready.onAwait { it }
                        failed.onAwait { it }
                    }
                } ?: throw Failure("noreply", "Your phone didn't send the file. Update Syndesia on your phone.")
                if (got is Failure) throw got
                @Suppress("UNCHECKED_CAST")
                channel = (got as Pair<ChannelClient.Channel, Long>).first
                val size = got.second
                val input = streams.remove(id) ?: throw Failure("send", "The file stream closed.")
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    val copied = input.use { it.copyTo(out, 64 * 1024) }
                    if (size >= 0 && copied != size) throw Failure("send", "Only part of the file arrived. Bluetooth dropped?")
                }
            } catch (e: Exception) {
                runCatching { write.closeWithError(e.message ?: "Couldn't copy from your phone") }
            } finally {
                gets.remove(id); getErrors.remove(id); streams.remove(id)
                channel?.let { c -> runCatching { Wearable.getChannelClient(ctx).close(c).await() } }
            }
        }
        return read
    }
}

/**
 * content://com.syndesia.app.phonefiles/list?path=…&hidden=0 → name, dir, size, modified, children
 * content://com.syndesia.app.phonefiles/file?path=…          → the file's bytes, streamed
 * call("status") → connected (Boolean)
 *
 * Only Arkheia (and Syndesia itself) may use it.
 */
class PhoneFilesProvider : ContentProvider() {
    private val allowed = setOf("com.arkheia.app", "com.syndesia.app")

    private fun check() {
        val ctx = context ?: throw SecurityException("No context")
        val pkgs = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (pkgs.none { it in allowed }) throw SecurityException("Not allowed")
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check()
        val ctx = context!!.applicationContext
        val cols = arrayOf("name", "dir", "size", "modified", "children")
        val c = MatrixCursor(cols)
        val extras = Bundle()
        try {
            val l = runBlocking { PhoneFs.list(ctx, uri.getQueryParameter("path").orEmpty(), uri.getQueryParameter("hidden") == "1") }
            for (a in l.items) c.addRow(arrayOf<Any>(a.optString(0), a.optInt(1), a.optLong(2), a.optLong(3), a.optInt(4)))
            extras.putString("path", l.path)
            extras.putString("root", l.root)
        } catch (e: PhoneFs.Failure) {
            extras.putString("error", e.code); extras.putString("message", e.message)
        } catch (e: Exception) {
            extras.putString("error", "failed"); extras.putString("message", e.message ?: "Couldn't read your phone's files.")
        }
        c.extras = extras
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check()
        if (mode != "r") throw SecurityException("Read only")
        return PhoneFs.open(context!!.applicationContext, uri.getQueryParameter("path").orEmpty())
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check()
        return Bundle().apply {
            if (method == "status") putBoolean("connected", runBlocking { PhoneFs.phone(context!!.applicationContext) } != null)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
