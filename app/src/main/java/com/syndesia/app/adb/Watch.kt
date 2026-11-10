package com.syndesia.app.adb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class WatchApp(
    val packageName: String,
    val apkPath: String,
    val versionCode: Long?,
    val system: Boolean,
    val enabled: Boolean,
)

data class AppDetails(
    val versionName: String?,
    val targetSdk: Int?,
    val firstInstall: String?,
    val lastUpdate: String?,
    val installer: String?,
)

data class WatchStats(
    val battery: Int?,
    val charging: Boolean,
    val batteryTempC: Float?,
    val storageTotalKb: Long?,
    val storageFreeKb: Long?,
    val ramTotalKb: Long?,
    val ramFreeKb: Long?,
    val uptimeSec: Long?,
    val screen: String?,
    val wifi: String?,
)

/** Shell-command helpers for the connected watch. */
object Watch {
    suspend fun stats(): WatchStats = withContext(Dispatchers.IO) {
        val out = Adb.sh(
            "echo @bat; dumpsys battery; echo @df; df -k /data; echo @mem; cat /proc/meminfo; " +
                "echo @up; cat /proc/uptime; echo @wm; wm size; echo @wifi; dumpsys wifi | grep -m1 'mWifiInfo'"
        )
        val sections = mutableMapOf<String, MutableList<String>>()
        var cur = ""
        out.lines().forEach { line ->
            if (line.startsWith("@")) cur = line.trim() else sections.getOrPut(cur) { mutableListOf() } += line
        }
        fun sec(k: String) = sections["@$k"].orEmpty()
        fun field(lines: List<String>, key: String) =
            lines.firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(':')?.trim()

        val bat = sec("bat")
        val level = field(bat, "level")?.toIntOrNull()
        val scale = field(bat, "scale")?.toIntOrNull() ?: 100
        val status = field(bat, "status")?.toIntOrNull()
        val temp = field(bat, "temperature")?.toIntOrNull()?.let { it / 10f }

        val df = sec("df").firstOrNull { it.trim().endsWith("/data") || (it.contains("%") && !it.startsWith("Filesystem")) }
            ?.trim()?.split(Regex("\\s+"))
        val total = df?.getOrNull(1)?.toLongOrNull()
        val free = df?.getOrNull(3)?.toLongOrNull()

        val mem = sec("mem")
        fun kb(k: String) = mem.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()

        val up = sec("up").firstOrNull()?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.toLong()
        val screen = sec("wm").firstOrNull { it.contains("size") }?.substringAfter(':')?.trim()
        val wifiLine = sec("wifi").firstOrNull().orEmpty()
        val ssid = Regex("SSID: \"?([^\",]*)\"?").find(wifiLine)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

        WatchStats(
            battery = level?.let { (it * 100) / scale.coerceAtLeast(1) },
            charging = status == 2 || status == 5,
            batteryTempC = temp,
            storageTotalKb = total,
            storageFreeKb = free,
            ramTotalKb = kb("MemTotal"),
            ramFreeKb = kb("MemAvailable"),
            uptimeSec = up,
            screen = screen,
            wifi = ssid,
        )
    }

    /** PNG bytes and the decoded picture of the watch screen. */
    suspend fun screenshot(): Pair<ByteArray, Bitmap> = withContext(Dispatchers.IO) {
        val png = Adb.execBytes("screencap -p")
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw IOException("The watch didn't send a picture. Wake its screen and try again.")
        png to bmp
    }

    // --------------------------------------------------------------- apps

    suspend fun apps(includeSystem: Boolean): List<WatchApp> = withContext(Dispatchers.IO) {
        val list = Adb.sh("pm list packages -f --show-versioncode${if (includeSystem) "" else " -3"}")
        val disabled = Adb.sh("pm list packages -d").lines().mapNotNull { it.trim().removePrefix("package:").ifBlank { null } }.toSet()
        val system = if (includeSystem) Adb.sh("pm list packages -s").lines().mapNotNull { it.trim().removePrefix("package:").ifBlank { null } }.toSet() else emptySet()
        list.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@mapNotNull null
            val body = line.removePrefix("package:")
            val left = body.substringBefore(" versionCode:")
            val code = body.substringAfter(" versionCode:", "").trim().toLongOrNull()
            val eq = left.lastIndexOf('=')
            if (eq <= 0) return@mapNotNull null
            val pkg = left.substring(eq + 1)
            WatchApp(pkg, left.substring(0, eq), code, pkg in system, pkg !in disabled)
        }.sortedBy { it.packageName }
    }

    suspend fun details(pkg: String): AppDetails = withContext(Dispatchers.IO) {
        val out = Adb.sh("dumpsys package ${Adb.q(pkg)} | grep -E 'versionName=|targetSdk=|firstInstallTime=|lastUpdateTime=|installerPackageName='")
        fun v(k: String) = Regex("$k=([^\\s]+(?: [0-9:]+)?)").find(out)?.groupValues?.get(1)
        AppDetails(
            versionName = v("versionName"),
            targetSdk = Regex("targetSdk=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull(),
            firstInstall = v("firstInstallTime"),
            lastUpdate = v("lastUpdateTime"),
            installer = v("installerPackageName")?.takeIf { it != "null" },
        )
    }

    /** Open the app's launcher activity on the watch. */
    suspend fun launch(pkg: String): Boolean = withContext(Dispatchers.IO) {
        val comp = Adb.sh("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${Adb.q(pkg)}")
            .lines().map { it.trim() }.lastOrNull { it.contains('/') }
        val out = if (comp != null) Adb.sh("am start -n ${Adb.q(comp)}")
        else Adb.sh("monkey -p ${Adb.q(pkg)} -c android.intent.category.LAUNCHER 1")
        !out.contains("Error", true) && !out.contains("No activities", true)
    }

    suspend fun forceStop(pkg: String) { Adb.sh("am force-stop ${Adb.q(pkg)}") }

    suspend fun uninstall(pkg: String): String? {
        val out = Adb.sh("pm uninstall ${Adb.q(pkg)}")
        return if (out.contains("Success")) null else out.trim().ifBlank { "Uninstall failed" }
    }

    suspend fun clearData(pkg: String): Boolean = Adb.sh("pm clear ${Adb.q(pkg)}").contains("Success")

    suspend fun setEnabled(pkg: String, enabled: Boolean): Boolean {
        val out = if (enabled) Adb.sh("pm enable ${Adb.q(pkg)}") else Adb.sh("pm disable-user --user 0 ${Adb.q(pkg)}")
        return out.contains("new state", true)
    }

    /** Paths of the app's APKs on the watch (base first, then splits). */
    suspend fun apkPaths(pkg: String): List<String> =
        Adb.sh("pm path ${Adb.q(pkg)}").lines().mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.endsWith(".apk") } }

    // --------------------------------------------------------------- files

    suspend fun list(dir: String): List<RemoteFile> = Sync.use { it.list(dir) }
        .sortedWith(compareBy<RemoteFile> { !(it.isDir || it.isLink) }.thenBy { it.name.lowercase() })

    suspend fun mkdir(path: String): String? = Adb.sh("mkdir -p ${Adb.q(path)}").trim().ifBlank { null }
    suspend fun delete(path: String): String? = Adb.sh("rm -rf ${Adb.q(path)}").trim().ifBlank { null }
    suspend fun rename(from: String, to: String): String? = Adb.sh("mv ${Adb.q(from)} ${Adb.q(to)}").trim().ifBlank { null }

    suspend fun reboot() { runCatching { Adb.sh("reboot") } }
}
