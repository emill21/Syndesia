package com.syndesia.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Files and APKs sent from the phone over Bluetooth (Data Layer channels).
 *
 * Each channel carries one file: a 4-byte header length, a JSON header, then the bytes.
 * Header: {"kind":"file"|"apk", "id", "name", "size", "mime"?, "install"?, "index"?, "count"?}
 * Files go to shared storage (Music, Pictures, Movies or Download, in a Syndesia folder).
 * APKs collect until all parts of an install have arrived, then go to the system installer,
 * which asks for confirmation on the watch.
 */
object Receiver {
    const val PATH_CHANNEL = "/syndesia/channel"
    private const val PATH_RESULT = "/syndesia/result"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)

    /** install id -> received parts */
    private val parts = mutableMapOf<String, MutableMap<Int, File>>()

    fun accept(context: Context, channel: ChannelClient.Channel) {
        val app = context.applicationContext
        active.incrementAndGet()
        TransferService.start(app)
        scope.launch {
            try {
                receive(app, channel)
            } catch (e: Throwable) {
                // Header may not have been read; the phone times out and reports it.
            } finally {
                runCatching { Wearable.getChannelClient(app).close(channel).await() }
                if (active.decrementAndGet() == 0) TransferService.stop(app)
            }
        }
    }

    fun result(context: Context, node: String, id: String, stage: String, message: String? = null) {
        val app = context.applicationContext
        val body = JSONObject().put("id", id).put("stage", stage).apply { if (message != null) put("message", message) }
        scope.launch {
            runCatching { Wearable.getMessageClient(app).sendMessage(node, PATH_RESULT, body.toString().toByteArray()).await() }
        }
    }

    private suspend fun receive(ctx: Context, channel: ChannelClient.Channel) {
        val node = channel.nodeId
        val input = DataInputStream(Wearable.getChannelClient(ctx).getInputStream(channel).await().buffered(128 * 1024))
        input.use { inp ->
            val len = inp.readInt()
            if (len <= 0 || len > 64 * 1024) throw IOException("Bad header")
            val hb = ByteArray(len)
            inp.readFully(hb)
            val h = JSONObject(String(hb, Charsets.UTF_8))
            val id = h.getString("id")
            val name = h.optString("name", "file").replace('/', '_').ifBlank { "file" }
            val size = h.optLong("size", -1)
            WatchTransfers.add(WTransfer(id, name, toPhone = false, total = size))
            var last = 0L
            val progress: (Long) -> Unit = { got ->
                val now = System.currentTimeMillis()
                if (now - last > 200) { last = now; WatchTransfers.set(id) { it.copy(done = got) } }
            }
            when (h.optString("kind")) {
                "file" -> {
                    val r = runCatching { saveShared(ctx, inp, name, h.optString("mime").ifBlank { null }, size, progress) }
                    r.onSuccess { where ->
                        WatchTransfers.set(id) { it.copy(state = WTransfer.State.Done, done = it.total, message = "Saved to $where") }
                        result(ctx, node, id, "saved", where)
                    }.onFailure { e ->
                        WatchTransfers.set(id) { it.copy(state = WTransfer.State.Failed, message = e.message) }
                        result(ctx, node, id, "failed", e.message ?: "Couldn't save $name")
                    }
                }
                "apk" -> {
                    val install = h.getString("install")
                    val index = h.optInt("index", 0)
                    val count = h.optInt("count", 1)
                    val dir = File(ctx.cacheDir, "bt/$install").apply { mkdirs() }
                    val f = File(dir, "${index}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    val got = f.outputStream().use { copy(inp, it, progress) }
                    if (size >= 0 && got != size) {
                        WatchTransfers.set(id) { it.copy(state = WTransfer.State.Failed, message = "Only part of it arrived") }
                        result(ctx, node, id, "failed", "Only part of the file arrived")
                        return
                    }
                    WatchTransfers.set(id) { it.copy(state = WTransfer.State.Done, done = it.total, message = "Received, installing") }
                    result(ctx, node, id, "received")
                    val ready = synchronized(parts) {
                        val m = parts.getOrPut(install) { mutableMapOf() }
                        m[index] = f
                        if (m.size == count) parts.remove(install) else null
                    }
                    if (ready != null) installNow(ctx, node, install, ready.toSortedMap().values.toList())
                }
                else -> result(ctx, node, id, "failed", "Update Syndesia on the watch")
            }
        }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, progress: (Long) -> Unit = {}): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            progress(total)
        }
        return total
    }

    /** Save into shared storage by type, e.g. Music/Syndesia/song.mp3. Returns where it went. */
    private fun saveShared(ctx: Context, input: java.io.InputStream, name: String, mime: String?, size: Long, progress: (Long) -> Unit): String {
        val type = mime ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        val (collection: Uri, dir: String) = when {
            type.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            type.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            type.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Syndesia")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(collection, values) ?: throw IOException("The watch's storage refused $name")
        try {
            val got = (cr.openOutputStream(uri) ?: throw IOException("Couldn't write $name")).use { copy(input, it, progress) }
            if (size >= 0 && got != size) throw IOException("Only part of $name arrived")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return "$dir/Syndesia"
    }

    private fun installNow(ctx: Context, node: String, install: String, files: List<File>) {
        runCatching {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sid = pi.createSession(params)
            pi.openSession(sid).use { s ->
                files.forEach { f ->
                    s.openWrite(f.name, 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out, 64 * 1024) }
                        s.fsync(out)
                    }
                }
                val i = Intent(ctx, InstallReceiver::class.java)
                    .putExtra(InstallReceiver.EXTRA_NODE, node)
                    .putExtra(InstallReceiver.EXTRA_ID, install)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, sid, i, flags).intentSender)
            }
            files.firstOrNull()?.parentFile?.deleteRecursively()
        }.onFailure { e ->
            result(ctx, node, install, "failed", e.message ?: "The watch couldn't start the install")
        }
    }
}

/** System installer callbacks: ask the user on the watch, then report back to the phone. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra(EXTRA_NODE) ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Try to show the "Install?" screen right away; the notification is the fallback.
                runCatching { context.startActivity(confirm) }
                ensureChannel(nm)
                val pending = PendingIntent.getActivity(context, id.hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(
                    id.hashCode(),
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle("Install app from phone?")
                        .setContentText("Tap to review and install")
                        .setContentIntent(pending)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build(),
                )
                Receiver.result(context, node, id, "confirm")
            }
            PackageInstaller.STATUS_SUCCESS -> {
                nm.cancel(id.hashCode())
                Receiver.result(context, node, id, "installed")
            }
            else -> {
                nm.cancel(id.hashCode())
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Receiver.result(
                    context, node, id, "failed",
                    when (status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled on the watch"
                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "A different version signed with another key is already installed. Uninstall it first."
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app isn't compatible with the watch."
                        PackageInstaller.STATUS_FAILURE_STORAGE -> "The watch is out of storage."
                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "The watch blocked the install."
                        else -> msg ?: "Install failed"
                    },
                )
            }
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Installs from phone", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    companion object {
        const val EXTRA_NODE = "node"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "installs"
    }
}

/** Keeps the process alive while files arrive over Bluetooth. */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { ServiceCompat.startForeground(this, 12, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers from phone", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Receiving from phone")
            .setContentText("Over Bluetooth")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TransferService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
