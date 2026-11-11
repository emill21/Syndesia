package com.syndesia.wear

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import java.io.File
import java.util.Locale

/** Pick files on the watch to send to the phone over Bluetooth. */
class FilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyndesiaWearTheme {
                AppScaffold { Browser(onDone = { finish() }) }
            }
        }
    }
}

private val ROOT: String = Environment.getExternalStorageDirectory().absolutePath

private fun size(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
}

private fun iconFor(f: File) = when {
    f.isDirectory -> Icons.Rounded.Folder
    else -> when (f.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "heic" -> Icons.Rounded.Image
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav" -> Icons.Rounded.Audiotrack
        "mp4", "mkv", "webm", "mov", "3gp" -> Icons.Rounded.Movie
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
private fun Browser(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var path by rememberSaveable { mutableStateOf(ROOT) }
    val allowed = remember { Environment.isExternalStorageManager() }
    val entries = remember(path, allowed) {
        if (!allowed) emptyList()
        else File(path).listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
    }
    val atRoot = path == ROOT
    BackHandler(enabled = !atRoot) { path = File(path).parent ?: ROOT }
    val listState = rememberScalingLazyListState()
    val colors = MaterialTheme.colorScheme

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader {
                    Text(
                        if (atRoot) "Send to phone" else File(path).name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.primary,
                    )
                }
            }
            if (!allowed) {
                item {
                    Text(
                        "Syndesia needs file access. On your phone, open Syndesia › Install and tap Update on \"Syndesia for your watch\".",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                return@ScalingLazyColumn
            }
            if (!atRoot) item {
                OutlinedButton(
                    onClick = { path = File(path).parent ?: ROOT },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) },
                    label = { Text("Up") },
                )
            }
            if (entries.isEmpty()) item {
                Text("Nothing here", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            items(entries, key = { it.absolutePath }) { f ->
                if (f.isDirectory) {
                    FilledTonalButton(
                        onClick = { path = f.absolutePath },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(iconFor(f), contentDescription = null) },
                        label = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                } else {
                    Button(
                        onClick = {
                            Phone.send(ctx, f)
                            Toast.makeText(ctx, "Sending ${f.name}", Toast.LENGTH_SHORT).show()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(iconFor(f), contentDescription = null) },
                        secondaryLabel = { Text(size(f.length())) },
                        label = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}
