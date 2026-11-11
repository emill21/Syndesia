package com.syndesia.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.syndesia.app.adb.RemoteFile
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
private val textExt = setOf(
    "txt", "log", "md", "json", "xml", "csv", "tsv", "ini", "conf", "cfg", "prop", "properties", "yaml", "yml",
    "sh", "rc", "html", "htm", "css", "js", "kt", "java", "py", "toml", "gradle", "srt", "lrc", "m3u", "m3u8",
)

/** Biggest file we'll pull just to look at. */
private const val PREVIEW_LIMIT = 50L * 1024 * 1024
private const val TEXT_LIMIT = 256 * 1024

private fun ext(f: RemoteFile) = f.name.substringAfterLast('.', "").lowercase()

fun previewHint(f: RemoteFile): String = when (ext(f)) {
    in imageExt -> "See the picture here"
    in textExt -> "Read it here"
    else -> "Open it with an app on this phone"
}

private sealed interface Loaded {
    data class Picture(val bmp: Bitmap) : Loaded
    data class Words(val text: String, val truncated: Boolean) : Loaded
    data class Other(val file: File, val mime: String) : Loaded
}

/**
 * Quick look at a watch file: pulls it into the cache, then shows pictures and text right here,
 * and hands anything else (videos, music, PDFs…) to an app on the phone.
 */
@Composable
fun PreviewDialog(f: RemoteFile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var got by remember { mutableLongStateOf(0L) }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openExternally(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        ctx.openSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    LaunchedEffect(f.path) {
        if (f.size > PREVIEW_LIMIT) {
            error = "That's ${formatBytes(f.size)}, too big to preview. Save it to the phone instead."
            return@LaunchedEffect
        }
        val result = runCatching {
            val file = Watch.pullToCache(ctx, f) { got = it }
            withContext(Dispatchers.IO) {
                val e = ext(f)
                when {
                    e in imageExt -> {
                        // Downsample big photos so they don't eat memory.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        if (bmp != null) Loaded.Picture(bmp) else Loaded.Other(file, "image/*")
                    }
                    e in textExt || f.size < 4096 && looksLikeText(file) -> {
                        val bytes = file.inputStream().use { it.readNBytesCompat(TEXT_LIMIT) }
                        Loaded.Words(String(bytes, Charsets.UTF_8), file.length() > TEXT_LIMIT)
                    }
                    else -> Loaded.Other(file, MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "*/*")
                }
            }
        }
        result.onSuccess { l ->
            loaded = l
            if (l is Loaded.Other) openExternally(l.file, l.mime)
        }.onFailure { error = it.message ?: "Couldn't fetch the file" }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(formatBytes(f.size), formatDate(f.mtime)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                val l = loaded
                when {
                    error != null -> Text(error.orEmpty(), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                    l == null -> {
                        val frac = if (f.size > 0) (got.toFloat() / f.size).coerceIn(0f, 1f) else 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Fetching from the watch… ${(frac * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    }
                    l is Loaded.Picture -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(
                            l.bmp.asImageBitmap(), f.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(16.dp)),
                        )
                    }
                    l is Loaded.Words -> Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHighest) {
                        Box(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                            Text(
                                l.text.ifEmpty { "(empty file)" } + if (l.truncated) "\n\n… (first 256 KB shown)" else "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    l is Loaded.Other -> {
                        Text("Opened it with an app on your phone.", style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { openExternally(l.file, l.mime) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open again")
                        }
                    }
                }
                if (error == null) Row {
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { Transfers.download(ctx, f); onDismiss() }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save to phone")
                    }
                }
            }
        }
    }
}

private fun looksLikeText(f: File): Boolean = runCatching {
    val b = f.inputStream().use { it.readNBytesCompat(1024) }
    b.isNotEmpty() && b.count { it == 0.toByte() } == 0
}.getOrDefault(false)

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
