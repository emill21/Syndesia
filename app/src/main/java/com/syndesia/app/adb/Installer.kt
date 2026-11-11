package com.syndesia.app.adb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

sealed interface InstallStatus {
    data object Queued : InstallStatus
    data class Sending(val sent: Long, val total: Long) : InstallStatus
    data object Installing : InstallStatus
    /** Sent over Bluetooth; the watch is showing its "Install?" screen. */
    data object ConfirmOnWatch : InstallStatus
    data object Done : InstallStatus
    data class Failed(val message: String, val code: String? = null) : InstallStatus
}

/** Sending, installing or waiting on the watch. */
val InstallStatus.active: Boolean
    get() = this is InstallStatus.Sending || this == InstallStatus.Installing || this == InstallStatus.ConfirmOnWatch

data class InstallItem(
    val pkg: ApkPackage,
    val status: InstallStatus = InstallStatus.Queued,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** Was mid-install when the connection dropped; resumes on its own when the watch is back. */
    val interrupted: Boolean = false,
)

data class InstallOptions(
    val downgrade: Boolean = false,
    val grantPermissions: Boolean = false,
)

/** Install queue. Runs one app at a time; survives tab switches. */
object Installer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<InstallItem>>(emptyList())
    val items: StateFlow<List<InstallItem>> = _items.asStateFlow()
    private var worker: Job? = null
    var options = InstallOptions()

    /** Needed for Bluetooth installs (Data Layer). Set once at app start. */
    var appContext: android.content.Context? = null

    /** True while the current install goes over Bluetooth rather than ADB. */
    private val _viaBluetooth = MutableStateFlow(false)
    val viaBluetooth: StateFlow<Boolean> = _viaBluetooth.asStateFlow()

    /** Bumped after each successful install, so the Apps tab knows to refresh. */
    private val _installed = MutableStateFlow(0)
    val installed: StateFlow<Int> = _installed.asStateFlow()

    fun add(pkgs: List<ApkPackage>) {
        _items.update { it + pkgs.map { p -> InstallItem(p) } }
        save()
    }

    fun remove(id: Long) {
        val gone = _items.value.filter { it.pkg.id == id && !it.status.active }
        _items.update { list -> list.filterNot { it.pkg.id == id && !it.status.active } }
        gone.forEach { deleteFiles(it.pkg) }
        save()
    }

    fun clearFinished() {
        val gone = _items.value.filter { it.status == InstallStatus.Done || it.status is InstallStatus.Failed }
        _items.update { list -> list.filter { it.status == InstallStatus.Queued || it.status.active } }
        gone.forEach { deleteFiles(it.pkg) }
        save()
    }

    fun retry(id: Long) {
        set(id) { it.copy(status = InstallStatus.Queued, interrupted = false) }
        save()
        start()
    }

    /** After the watch reconnects: carry on with anything a dropped connection interrupted. */
    fun resumeInterrupted() {
        if (_items.value.any { it.interrupted && it.status == InstallStatus.Queued }) start()
    }

    // --------------------------------------------------------------- queue memory

    private fun queueFile(): File? = appContext?.let { File(it.filesDir, "install_queue.json") }

    /** Bundled files (the watch helper) live in cache; never delete those, and never delete outside our folders. */
    private fun deleteFiles(p: ApkPackage) {
        val ctx = appContext ?: return
        val root = File(ctx.filesDir, "incoming").absolutePath
        p.files.mapNotNull { it.parentFile }.distinct()
            .filter { it.absolutePath.startsWith(root) && it.absolutePath != root }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    /** Write the queue to disk (not progress, just what's in it and how each one ended). */
    private fun save() {
        val f = queueFile() ?: return
        val snapshot = _items.value
        scope.launch {
            runCatching {
                val arr = org.json.JSONArray()
                for (item in snapshot) {
                    val p = item.pkg
                    // Keep the icon next to the APK so it survives a restart.
                    val dir = p.files.firstOrNull()?.parentFile
                    val iconFile = dir?.let { File(it, "icon.png") }
                    if (p.icon != null && iconFile != null && !iconFile.exists()) {
                        runCatching { iconFile.outputStream().use { p.icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    }
                    val o = org.json.JSONObject()
                        .put("id", p.id).put("label", p.label).put("source", p.sourceName)
                        .put("pkg", p.packageName ?: "").put("vName", p.versionName ?: "")
                        .put("vCode", p.versionCode ?: -1).put("minSdk", p.minSdk ?: -1)
                        .put("wear", when (p.wearApp) { true -> 1; false -> 0; null -> -1 })
                        .put("files", org.json.JSONArray(p.files.map { it.absolutePath }))
                        .put("icon", iconFile?.takeIf { it.exists() }?.absolutePath ?: "")
                        .put("interrupted", item.interrupted || item.status.active)
                    when (val st = item.status) {
                        InstallStatus.Done -> o.put("status", "done")
                        is InstallStatus.Failed -> o.put("status", "failed").put("msg", st.message).put("code", st.code ?: "")
                        else -> o.put("status", "queued")
                    }
                    arr.put(o)
                }
                synchronized(this@Installer) { f.writeText(arr.toString()) }
            }
        }
    }

    /** Bring back the queue from the last run (call once at app start). Items whose files are gone are dropped. */
    fun restore(context: android.content.Context) {
        appContext = context.applicationContext
        if (_items.value.isNotEmpty()) return
        val f = queueFile() ?: return
        val restored = runCatching {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val files = o.getJSONArray("files").let { a -> (0 until a.length()).map { File(a.getString(it)) } }
                if (files.isEmpty() || files.any { !it.exists() }) return@mapNotNull null
                val icon = o.optString("icon").takeIf { it.isNotBlank() }?.let { android.graphics.BitmapFactory.decodeFile(it) }
                val pkg = ApkPackage(
                    id = o.getLong("id"),
                    label = o.optString("label", files.first().name),
                    packageName = o.optString("pkg").ifBlank { null },
                    versionName = o.optString("vName").ifBlank { null },
                    versionCode = o.optLong("vCode", -1).takeIf { it >= 0 },
                    minSdk = o.optInt("minSdk", -1).takeIf { it >= 0 },
                    icon = icon,
                    files = files,
                    wearApp = when (o.optInt("wear", -1)) { 1 -> true; 0 -> false; else -> null },
                    sourceName = o.optString("source"),
                )
                val status: InstallStatus = when (o.optString("status")) {
                    "done" -> InstallStatus.Done
                    "failed" -> InstallStatus.Failed(o.optString("msg", "Install failed"), o.optString("code").ifBlank { null })
                    else -> InstallStatus.Queued
                }
                InstallItem(pkg, status, interrupted = status == InstallStatus.Queued && o.optBoolean("interrupted"))
            }
        }.getOrDefault(emptyList())
        if (restored.isNotEmpty()) _items.value = restored
        // Resume interrupted installs as soon as the link is back, even with the app in the background.
        if (!watching) {
            watching = true
            scope.launch { Adb.link.collect { if (it is Link.Connected) resumeInterrupted() } }
        }
    }

    private var watching = false

    val busy: Boolean get() = worker?.isActive == true

    /** Install everything queued, in order. */
    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _items.value.firstOrNull { it.status == InstallStatus.Queued } ?: break
                // No ADB link: fall back to the watch helper over Bluetooth, if it's reachable.
                val ctx = appContext
                val node = if (Adb.connected == null && ctx != null) Helper.bestNode(ctx) else null
                if (Adb.connected == null && node == null) break
                _viaBluetooth.value = node != null
                set(next.pkg.id) { it.copy(status = InstallStatus.Sending(0, next.pkg.totalBytes), startedAt = System.currentTimeMillis()) }
                save()
                val result = runCatching {
                    if (node != null && ctx != null) installOverBluetooth(ctx, node, next.pkg) else install(next.pkg)
                }
                val err = result.exceptionOrNull()
                // Connection dropped mid-install (not a real install error): put it back and wait for the watch.
                if (err != null && err !is InstallFailure && node == null && Adb.connected == null) {
                    set(next.pkg.id) { it.copy(status = InstallStatus.Queued, interrupted = true) }
                    save()
                    break
                }
                set(next.pkg.id) {
                    it.copy(
                        interrupted = false,
                        status = result.fold(
                            onSuccess = { InstallStatus.Done },
                            onFailure = { e -> (e as? InstallFailure)?.let { f -> InstallStatus.Failed(f.message ?: "Install failed", f.code) } ?: InstallStatus.Failed(e.message ?: e.javaClass.simpleName) },
                        ),
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                if (result.isSuccess && node == null && next.pkg.packageName == Helper.PACKAGE) {
                    // The watch helper: grant its permissions and start it once.
                    runCatching { Helper.setUp() }
                }
                if (result.isSuccess) _installed.value++
                save()
            }
        }
    }

    fun cancel() {
        worker?.cancel()
        _items.update { list ->
            list.map {
                if (it.status.active) it.copy(status = InstallStatus.Failed("Cancelled"))
                else it
            }
        }
    }

    private fun set(id: Long, f: (InstallItem) -> InstallItem) {
        _items.update { list -> list.map { if (it.pkg.id == id) f(it) else it } }
    }

    class InstallFailure(message: String, val code: String?) : IOException(message)

    private fun flags(): String {
        val sdk = Adb.connected?.sdk ?: 0
        return buildString {
            append("-r -t")
            if (options.downgrade) append(" -d")
            if (options.grantPermissions && sdk >= 23) append(" -g")
            // Android 14+ blocks very old targetSdk apps unless asked not to; plenty of watch APKs are old.
            if (sdk >= 34) append(" --bypass-low-target-sdk-block")
        }
    }

    /** Send the APK(s) to the watch helper, which hands them to the system installer (the watch asks to confirm). */
    private suspend fun installOverBluetooth(ctx: android.content.Context, node: String, pkg: ApkPackage) {
        val installId = "i${pkg.id}-${System.currentTimeMillis()}"
        val outcome = Helper.expect(installId)
        try {
            val total = pkg.totalBytes
            var base = 0L
            pkg.files.forEachIndexed { i, f ->
                val header = org.json.JSONObject()
                    .put("kind", "apk").put("id", "$installId-$i").put("install", installId)
                    .put("index", i).put("count", pkg.files.size)
                    .put("name", f.name).put("size", f.length())
                val reply = f.inputStream().use { input ->
                    Helper.sendOverBluetooth(ctx, node, header, input, f.length()) { sent ->
                        set(pkg.id) { it.copy(status = InstallStatus.Sending(base + sent, total)) }
                    }
                }
                if (reply.stage == "failed") throw InstallFailure(reply.message ?: "The watch couldn't receive the APK", null)
                base += f.length()
            }
            set(pkg.id) { it.copy(status = InstallStatus.Installing) }
            val final = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) {
                outcome
                    .onEach { rep -> if (rep?.stage == "confirm") set(pkg.id) { it.copy(status = InstallStatus.ConfirmOnWatch) } }
                    .first { it?.stage == "installed" || it?.stage == "failed" }
            }
            if (final == null) throw InstallFailure("The watch didn't say whether it installed. Check the watch for an \"Install\" prompt.", null)
            if (final.stage == "failed") throw InstallFailure(final.message ?: "Install failed on the watch", null)
        } finally {
            Helper.forget(installId)
        }
    }

    private suspend fun install(pkg: ApkPackage) {
        val total = pkg.totalBytes
        var base = 0L
        val progress: (Long) -> Unit = { sent -> set(pkg.id) { it.copy(status = InstallStatus.Sending(base + sent, total)) } }
        if (!pkg.isSplit) {
            val f = pkg.files.first()
            // Once every byte is on the watch, package manager verifies and installs it.
            val out = streamInto("cmd package install ${flags()} -S ${f.length()}", f, progress) {
                set(pkg.id) { it.copy(status = InstallStatus.Installing) }
            }
            check(out)
            return
        }
        val created = Adb.sh("cmd package install-create ${flags()} -S $total")
        val session = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
            ?: throw failure(created)
        try {
            pkg.files.forEachIndexed { i, f ->
                val name = "${i}_" + f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = streamInto("cmd package install-write -S ${f.length()} $session $name -", f, progress)
                if (!out.contains("Success")) throw failure(out)
                base += f.length()
            }
            set(pkg.id) { it.copy(status = InstallStatus.Installing) }
            check(Adb.sh("cmd package install-commit $session"))
        } catch (e: Throwable) {
            runCatching { Adb.sh("cmd package install-abandon $session") }
            throw e
        }
    }

    /** Opens `exec:<command>`, writes the file into its stdin, then returns everything it printed. */
    private suspend fun streamInto(command: String, f: File, progress: (Long) -> Unit, afterSend: () -> Unit = {}): String {
        val s = Adb.open("exec:$command")
        try {
            val out = BufferedOutputStream(s.asOutput(), writeChunk)
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            var lastTick = 0L
            f.inputStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 80) { progress(sent); lastTick = now }
                }
            }
            out.flush()
            progress(sent)
            afterSend()
            return String(s.readAll(), Charsets.UTF_8)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun check(output: String) {
        if (output.contains("Success")) return
        throw failure(output)
    }

    private fun failure(output: String): InstallFailure {
        val code = Regex("(INSTALL_[A-Z_]+|DELETE_[A-Z_]+)").find(output)?.value
        val text = when (code) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> "A version signed with a different key is already on the watch. Uninstall it first (Apps tab), then install again."
            "INSTALL_FAILED_VERSION_DOWNGRADE" -> "The watch already has a newer version. Turn on \"Allow downgrades\" in Settings, or uninstall it first."
            "INSTALL_FAILED_OLDER_SDK" -> "This app needs a newer version of Wear OS than the watch has."
            "INSTALL_FAILED_NO_MATCHING_ABIS" -> "This APK is built for a different processor than the watch's."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "The watch is out of storage."
            "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> "This app targets an Android version too old for this watch."
            "INSTALL_FAILED_MISSING_SPLIT" -> "This app needs split APKs that weren't included. Pick all its APKs together, or use the .apks / .xapk bundle."
            "INSTALL_FAILED_INVALID_APK", "INSTALL_PARSE_FAILED_NOT_APK" -> "The watch couldn't read this APK. It may be damaged or incomplete."
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> "This APK isn't signed, so Android won't install it."
            "INSTALL_FAILED_TEST_ONLY" -> "This is a test-only build the watch refused."
            "INSTALL_FAILED_ALREADY_EXISTS" -> "The app is already installed."
            "INSTALL_FAILED_USER_RESTRICTED" -> "The watch blocked installs over ADB."
            "INSTALL_FAILED_VERIFICATION_FAILURE" -> "Google Play Protect on the watch rejected this app."
            "INSTALL_FAILED_DUPLICATE_PERMISSION" -> "Another app on the watch already defines a permission this app uses."
            else -> null
        }
        val raw = output.lines().firstOrNull { it.contains("Failure") || it.contains("Error") }?.trim()
            ?: output.trim().lines().lastOrNull()?.trim().orEmpty()
        return InstallFailure(text ?: raw.ifBlank { "The watch didn't confirm the install." }, code)
    }
}
