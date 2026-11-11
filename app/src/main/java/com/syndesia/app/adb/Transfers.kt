package com.syndesia.app.adb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream

enum class Direction { ToWatch, ToPhone }

data class Transfer(
    val id: Long,
    val name: String,
    val direction: Direction,
    val total: Long,
    val done: Long = 0,
    val state: State = State.Waiting,
    val error: String? = null,
    /** Where a download ended up, to open or share it. */
    val result: Uri? = null,
    val where: String? = null,
    val viaBluetooth: Boolean = false,
) {
    enum class State { Waiting, Running, Done, Failed }
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Uploads and downloads, one at a time (the watch's Wi-Fi is the bottleneck anyway).
 * Downloads land in Downloads/Syndesia.
 */
object Transfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    val list: StateFlow<List<Transfer>> = _list.asStateFlow()
    private var nextId = 1L

    /** Bumped after each finished upload, so the file browser can refresh. */
    private val _uploaded = MutableStateFlow(0)
    val uploaded: StateFlow<Int> = _uploaded.asStateFlow()

    fun clearFinished() = _list.update { l -> l.filter { it.state == Transfer.State.Waiting || it.state == Transfer.State.Running } }

    private fun add(t: Transfer) = _list.update { it + t }
    private fun set(id: Long, f: (Transfer) -> Transfer) = _list.update { l -> l.map { if (it.id == id) f(it) else it } }

    fun upload(context: Context, uris: List<Uri>, dir: String) {
        val app = context.applicationContext
        for (uri in uris) {
            val name = Apk.displayName(app, uri)
            val size = Apk.size(app, uri) ?: -1L
            val t = Transfer(nextId++, name, Direction.ToWatch, size)
            add(t)
            scope.launch {
                lock.withLock {
                    set(t.id) { it.copy(state = Transfer.State.Running) }
                    val target = (if (dir.endsWith("/")) dir else "$dir/") + name.replace("/", "_")
                    runCatching {
                        val input = app.contentResolver.openInputStream(uri) ?: error("Couldn't open $name")
                        input.use { s ->
                            var last = 0L
                            Sync.use { sync ->
                                sync.push(s.buffered(256 * 1024), target) { sent ->
                                    val now = System.currentTimeMillis()
                                    if (now - last > 80 || sent == size) { last = now; set(t.id) { it.copy(done = sent) } }
                                }
                            }
                        }
                    }.onSuccess {
                        set(t.id) { it.copy(state = Transfer.State.Done, done = if (it.total > 0) it.total else it.done, where = target) }
                        _uploaded.value++
                    }.onFailure { e ->
                        set(t.id) { it.copy(state = Transfer.State.Failed, error = e.message ?: e.javaClass.simpleName) }
                    }
                }
            }
        }
    }

    /**
     * Send files to the watch helper over Bluetooth (no Wi-Fi needed). The watch files them by type:
     * Music/Syndesia, Pictures/Syndesia, Movies/Syndesia, or Download/Syndesia.
     */
    fun uploadBluetooth(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        for (uri in uris) {
            val name = Apk.displayName(app, uri)
            val size = Apk.size(app, uri) ?: -1L
            val t = Transfer(nextId++, name, Direction.ToWatch, size, viaBluetooth = true)
            add(t)
            scope.launch {
                lock.withLock {
                    set(t.id) { it.copy(state = Transfer.State.Running) }
                    runCatching {
                        val node = Helper.bestNode(app) ?: error("Can't reach Syndesia on your watch over Bluetooth")
                        val header = org.json.JSONObject()
                            .put("kind", "file").put("id", "f${t.id}-${System.currentTimeMillis()}")
                            .put("name", name).put("size", size)
                            .put("mime", app.contentResolver.getType(uri) ?: "")
                        val input = app.contentResolver.openInputStream(uri) ?: error("Couldn't open $name")
                        input.use { s ->
                            Helper.sendOverBluetooth(app, node, header, s, size) { sent -> set(t.id) { it.copy(done = sent) } }
                        }
                    }.onSuccess { reply ->
                        if (reply.stage == "failed") set(t.id) { it.copy(state = Transfer.State.Failed, error = reply.message ?: "The watch couldn't save it") }
                        else set(t.id) { it.copy(state = Transfer.State.Done, done = if (it.total > 0) it.total else it.done, where = reply.message) }
                    }.onFailure { e ->
                        set(t.id) { it.copy(state = Transfer.State.Failed, error = e.message ?: e.javaClass.simpleName) }
                    }
                }
            }
        }
    }

    /** Save one watch file into Downloads/Syndesia (or [subfolder] inside it). */
    fun download(
        context: Context,
        file: RemoteFile,
        subfolder: String? = null,
        rename: String? = null,
        onDone: suspend (ok: Boolean) -> Unit = {},
    ) {
        val app = context.applicationContext
        val name = rename ?: file.name
        val t = Transfer(nextId++, name, Direction.ToPhone, file.size)
        add(t)
        scope.launch {
            lock.withLock {
                set(t.id) { it.copy(state = Transfer.State.Running) }
                var created: Uri? = null
                runCatching {
                    val (uri, out, where) = openDownload(app, name, subfolder)
                    created = uri
                    out.use { o ->
                        var last = 0L
                        Sync.use { sync ->
                            sync.pull(file.path, o.buffered(256 * 1024)) { got ->
                                val now = System.currentTimeMillis()
                                if (now - last > 80) { last = now; set(t.id) { it.copy(done = got) } }
                            }
                        }
                    }
                    finishDownload(app, uri)
                    uri to where
                }.onSuccess { (uri, where) ->
                    set(t.id) { it.copy(state = Transfer.State.Done, done = it.total, result = uri, where = where) }
                    runCatching { onDone(true) }
                }.onFailure { e ->
                    created?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
                    set(t.id) { it.copy(state = Transfer.State.Failed, error = e.message ?: e.javaClass.simpleName) }
                    runCatching { onDone(false) }
                }
            }
        }
    }

    private fun openDownload(context: Context, name: String, subfolder: String?): Triple<Uri, OutputStream, String> {
        val rel = "Syndesia" + (subfolder?.let { "/$it" } ?: "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create $name in Downloads")
            val out = context.contentResolver.openOutputStream(uri) ?: error("Couldn't write $name")
            return Triple(uri, out, "Downloads/$rel")
        }
        // Android 8–9: the app's own Downloads folder (no storage permission needed).
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), rel).apply { mkdirs() }
        val f = File(dir, name)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        return Triple(uri, f.outputStream(), dir.path)
    }

    private fun finishDownload(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, v, null, null)
        }
    }

    /** Save a phone-side bitmap/bytes (e.g. a screenshot) into Downloads/Syndesia. */
    fun saveBytes(context: Context, name: String, bytes: ByteArray, subfolder: String? = null): Pair<Uri, String> {
        val (uri, out, where) = openDownload(context.applicationContext, name, subfolder)
        out.use { it.write(bytes) }
        finishDownload(context.applicationContext, uri)
        return uri to where
    }
}
