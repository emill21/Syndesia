package com.syndesia.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File

/**
 * Lets the watch (Arkheia, through Syndesia on the watch) browse this phone's storage and copy
 * files from it, over Bluetooth. Needs "All files access" for Syndesia on the phone.
 */
object PhoneFiles {
    const val PATH_LIST = "/syndesia/pfs/list"
    const val PATH_GET = "/syndesia/pfs/get"
    private const val PATH_REPLY = "/syndesia/pfs/reply"
    private const val PATH_DATA = "/syndesia/pfs/data"
    private const val PAGE = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    fun accessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun reply(ctx: Context, node: String, body: JSONObject) {
        runCatching { com.google.android.gms.tasks.Tasks.await(Wearable.getMessageClient(ctx).sendMessage(node, PATH_REPLY, body.toString().toByteArray())) }
    }

    private fun err(ctx: Context, node: String, id: String, code: String, msg: String) =
        reply(ctx, node, JSONObject().put("id", id).put("error", code).put("message", msg).put("done", true))

    /** Only files inside shared storage, nothing elsewhere on the phone. */
    private fun resolve(path: String): File? {
        val r = root.canonicalFile
        val f = (if (path.isBlank()) r else File(path)).canonicalFile
        return if (f.path == r.path || f.path.startsWith(r.path + "/")) f else null
    }

    private fun needAccess(ctx: Context, node: String, id: String) {
        notifyAccess(ctx)
        err(ctx, node, id, "access", "Allow file access for Syndesia on your phone: tap the notification on your phone.")
    }

    fun list(context: Context, node: String, body: JSONObject) {
        val ctx = context.applicationContext
        val id = body.optString("id")
        scope.launch {
            if (!hasAccess()) return@launch needAccess(ctx, node, id)
            val dir = resolve(body.optString("path")) ?: return@launch err(ctx, node, id, "outside", "That folder isn't in your phone's storage.")
            val kids = dir.listFiles()
            if (kids == null) return@launch err(ctx, node, id, "unreadable", "Can't open that folder on your phone.")
            val hidden = body.optBoolean("hidden", false)
            val items = kids.filter { hidden || !it.name.startsWith(".") }.map { f ->
                val d = f.isDirectory
                JSONArray().put(f.name).put(if (d) 1 else 0).put(if (d) 0L else f.length()).put(f.lastModified())
                    .put(if (d) (f.list()?.size ?: -1) else 0)
            }
            val base = JSONObject().put("id", id).put("path", dir.path).put("root", root.canonicalPath)
            if (items.isEmpty()) return@launch reply(ctx, node, JSONObject(base.toString()).put("items", JSONArray()).put("done", true))
            items.chunked(PAGE).forEachIndexed { i, chunk ->
                val last = (i + 1) * PAGE >= items.size
                reply(ctx, node, JSONObject(base.toString()).put("page", i).put("items", JSONArray(chunk)).put("done", last))
            }
        }
    }

    fun get(context: Context, node: String, body: JSONObject) {
        val ctx = context.applicationContext
        val id = body.optString("id")
        scope.launch {
            if (!hasAccess()) return@launch needAccess(ctx, node, id)
            val f = resolve(body.optString("path"))?.takeIf { it.isFile && it.canRead() }
                ?: return@launch err(ctx, node, id, "missing", "That file isn't there any more.")
            val client = Wearable.getChannelClient(ctx)
            var channel: ChannelClient.Channel? = null
            try {
                channel = client.openChannel(node, PATH_DATA).await()
                DataOutputStream(client.getOutputStream(channel).await().buffered(128 * 1024)).use { out ->
                    val h = JSONObject().put("id", id).put("name", f.name).put("size", f.length()).toString().toByteArray()
                    out.writeInt(h.size)
                    out.write(h)
                    f.inputStream().use { it.copyTo(out, 64 * 1024) }
                    out.flush()
                }
            } catch (e: Exception) {
                err(ctx, node, id, "send", "Couldn't send ${f.name}: ${e.message ?: "Bluetooth dropped"}")
            } finally {
                channel?.let { runCatching { client.close(it).await() } }
            }
        }
    }

    private fun notifyAccess(ctx: Context) = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("phone_files") == null) {
            nm.createNotificationChannel(NotificationChannel("phone_files", "Phone files for your watch", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(ctx, 41, accessIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            41,
            NotificationCompat.Builder(ctx, "phone_files")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Let your watch browse this phone")
                .setContentText("Tap, then turn on All files access for Syndesia.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }
}
