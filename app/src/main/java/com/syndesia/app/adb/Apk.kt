package com.syndesia.app.adb

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.zip.ZipFile

/** One app ready to install: a single APK, or a base APK plus its splits. */
data class ApkPackage(
    val id: Long,
    val label: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val icon: Bitmap?,
    val files: List<File>,
    /** true = declares the watch feature, false = phone-only app, null = couldn't tell. */
    val wearApp: Boolean?,
    val sourceName: String,
) {
    val totalBytes: Long get() = files.sumOf { it.length() }
    val isSplit: Boolean get() = files.size > 1
}

/**
 * Copies picked files into the app's cache, unpacks bundles (.apks, .xapk, .apkm, .zip) and reads
 * each APK's name, version and icon on the phone, before anything is sent to the watch.
 */
object Apk {
    private var nextId = System.currentTimeMillis()

    private val bundleExt = setOf("apks", "xapk", "apkm", "zip")

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
    }

    fun size(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }

    /** Blocking: call off the main thread. Returns the apps found, plus a message for anything skipped. */
    fun prepare(context: Context, uris: List<Uri>): Pair<List<ApkPackage>, List<String>> {
        // App storage, not cache: queued installs must survive restarts (see Installer.restore).
        val root = File(context.filesDir, "incoming").apply { mkdirs() }
        val problems = mutableListOf<String>()
        // Loose APKs picked together may be one split app; bundles are always one app.
        val loose = mutableListOf<Pair<File, String>>()
        val result = mutableListOf<ApkPackage>()

        for (uri in uris) {
            val name = displayName(context, uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            val dir = File(root, "${System.nanoTime()}").apply { mkdirs() }
            val copy = File(dir, safe(name))
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    copy.outputStream().use { input.copyTo(it, 256 * 1024) }
                } ?: throw IllegalStateException("Couldn't open $name")
            } catch (e: Exception) {
                problems += "Couldn't read $name"
                continue
            }
            if (ext in bundleExt || (ext != "apk" && isZipOfApks(copy))) {
                val apks = runCatching { unpack(copy, dir) }.getOrElse { emptyList() }
                copy.delete()
                if (apks.isEmpty()) {
                    problems += if (ext == "apkm") "$name looks encrypted (APKMirror's newer .apkm). Use APKMirror Installer to convert it, or grab the .apk."
                    else "$name has no APKs inside"
                    continue
                }
                describe(context, apks, name)?.let { result += it } ?: run { problems += "Couldn't read the app inside $name" }
            } else {
                loose += copy to name
            }
        }

        // Group loose APKs by package; splits that can't be parsed alone join the only base present.
        val parsed = loose.map { (f, n) -> Triple(f, n, info(context, f)) }
        val groups = linkedMapOf<String, MutableList<Pair<File, String>>>()
        val orphans = mutableListOf<Pair<File, String>>()
        for ((f, n, pi) in parsed) {
            val pkg = pi?.packageName
            if (pkg == null) orphans += f to n else groups.getOrPut(pkg) { mutableListOf() } += f to n
        }
        if (orphans.isNotEmpty()) {
            if (groups.size == 1) groups.values.first() += orphans
            else orphans.forEach { (_, n) -> problems += "$n isn't an APK Android can read" }
        }
        for ((_, files) in groups) {
            describe(context, files.map { it.first }, files.first().second)?.let { result += it }
        }
        return result to problems
    }

    private fun isZipOfApks(f: File): Boolean = runCatching {
        ZipFile(f).use { z -> z.entries().asSequence().any { it.name.endsWith(".apk", true) } && z.getEntry("AndroidManifest.xml") == null }
    }.getOrDefault(false)

    private fun unpack(zip: File, dir: File): List<File> {
        val out = mutableListOf<File>()
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.endsWith(".apk", true)) continue
                val f = File(dir, safe(e.name.substringAfterLast('/')))
                z.getInputStream(e).use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                out += f
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun info(context: Context, f: File): PackageInfo? = runCatching {
        context.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA)
    }.getOrNull()

    /** Builds the install entry; the base APK (the one with an application label) goes first. */
    private fun describe(context: Context, files: List<File>, sourceName: String): ApkPackage? {
        val pm = context.packageManager
        val infos = files.map { it to info(context, it) }
        // Base APK: not named split/config, or the first with an icon.
        val base = infos.firstOrNull { (f, pi) -> pi != null && !f.name.contains("config.", true) && !f.name.startsWith("split", true) }
            ?: infos.firstOrNull { it.second != null }
        val ordered = if (base == null) files else listOf(base.first) + files.filter { it != base.first }
        val pi = base?.second
        var label = sourceName.substringBeforeLast('.')
        var icon: Bitmap? = null
        var minSdk: Int? = null
        var wear: Boolean? = null
        if (pi != null && base != null) {
            pi.applicationInfo?.let { ai ->
                ai.sourceDir = base.first.path
                ai.publicSourceDir = base.first.path
                runCatching { label = ai.loadLabel(pm).toString() }
                runCatching { icon = ai.loadIcon(pm).toBitmap(144, 144) }
                minSdk = ai.minSdkVersion
                val standalone = ai.metaData?.containsKey("com.google.android.wearable.standalone") == true
                val watchFeature = pi.reqFeatures?.any { it.name == "android.hardware.type.watch" } == true
                wear = watchFeature || standalone
            }
        }
        if (pi == null && files.isEmpty()) return null
        return ApkPackage(
            id = nextId++,
            label = label,
            packageName = pi?.packageName,
            versionName = pi?.versionName,
            versionCode = pi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() },
            minSdk = minSdk,
            icon = icon,
            files = ordered,
            wearApp = wear,
            sourceName = sourceName,
        )
    }

    /** Describe an APK that's already a local file (e.g. the bundled watch helper). */
    fun fromFile(context: Context, file: File): ApkPackage? = describe(context, listOf(file), file.name)

    /** Clear unpacked files from earlier sessions. */
    fun cleanCache(context: Context) {
        runCatching { File(context.filesDir, "incoming").deleteRecursively() }
    }
}
