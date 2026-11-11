package com.syndesia.app.adb

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Syndesia's watch helper: a small Wear OS app (bundled in this APK) that the phone can reach over
 * Bluetooth to bring the watch's Wi-Fi and Wireless debugging up when ADB can't reach it.
 */
object Helper {
    const val PACKAGE = "com.syndesia.app"
    private const val ASSET = "syndesia-wear.apk"
    private const val CAPABILITY = "syndesia_helper"
    private const val PATH_WAKE = "/syndesia/wake"
    private const val PATH_RELEASE = "/syndesia/release"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastWake = 0L

    /** Watches with the helper installed that are reachable over Bluetooth right now. */
    suspend fun nodes(context: Context): List<String> = runCatching {
        withTimeoutOrNull(5000) {
            Wearable.getCapabilityClient(context.applicationContext)
                .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
                .nodes.map { it.id }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun send(context: Context, path: String): Int {
        val ids = nodes(context)
        val client = Wearable.getMessageClient(context.applicationContext)
        var sent = 0
        for (id in ids) {
            if (runCatching { withTimeoutOrNull(5000) { client.sendMessage(id, path, ByteArray(0)).await() } != null }.getOrDefault(false)) sent++
        }
        return sent
    }

    /** Ask the watch to bring up Wi-Fi (and Wireless debugging). Returns how many watches got the message. */
    suspend fun wake(context: Context): Int {
        lastWake = System.currentTimeMillis()
        return send(context, PATH_WAKE)
    }

    /** Fire-and-forget wake, at most every 20 s unless [force]. */
    fun wakeAsync(context: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastWake < 20_000) return
        lastWake = System.currentTimeMillis()
        val app = context.applicationContext
        scope.launch { runCatching { send(app, PATH_WAKE) } }
    }

    /** Let the watch drop Wi-Fi again (after the user disconnects). */
    fun releaseAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { send(app, PATH_RELEASE) } }
    }

    /** The helper APK bundled with this build, copied out so it can be installed. */
    fun bundledApk(context: Context): File? = runCatching {
        val out = File(context.cacheDir, "helper").apply { mkdirs() }.let { File(it, ASSET) }
        context.assets.open(ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    /** Version code of the helper on the connected watch, or null if it isn't installed. */
    suspend fun installedVersion(): Long? {
        val out = Adb.sh("dumpsys package $PACKAGE | grep -m1 versionCode=")
        return Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * One-time setup after installing on the watch, over ADB:
     *  - let it switch Wireless debugging on (WRITE_SECURE_SETTINGS),
     *  - exempt it from battery optimisation so a Bluetooth message can start its Wi-Fi service,
     *  - open it once (freshly installed apps can't receive messages until they've been started).
     */
    suspend fun setUp(): String? {
        val grant = Adb.sh("pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS")
        Adb.sh("dumpsys deviceidle whitelist +$PACKAGE")
        Adb.sh("cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow")
        runCatching { Watch.launch(PACKAGE) }
        return grant.trim().ifBlank { null }?.takeIf { it.contains("Exception", true) || it.contains("Error", true) }
    }
}
