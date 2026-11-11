package com.syndesia.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Direction
import com.syndesia.app.adb.Link
import com.syndesia.app.adb.RemoteFile
import com.syndesia.app.adb.Transfer
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.Watch
import kotlinx.coroutines.launch

private val shortcuts = listOf(
    "Storage" to STORAGE_ROOT,
    "Download" to "$STORAGE_ROOT/Download",
    "Music" to "$STORAGE_ROOT/Music",
    "Pictures" to "$STORAGE_ROOT/Pictures",
    "DCIM" to "$STORAGE_ROOT/DCIM",
    "Temp" to "/data/local/tmp",
    "Root" to "/",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(st: SyndesiaState, link: Link, padding: PaddingValues, onTab: (STab) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers by Transfers.list.collectAsStateWithLifecycle()
    val uploaded by Transfers.uploaded.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var previewing by remember { mutableStateOf<RemoteFile?>(null) }
    var folderPlan by remember { mutableStateOf<Pair<RemoteFile, List<Pair<RemoteFile, String>>>?>(null) }
    var counting by remember { mutableStateOf(false) }

    /** Walk a folder, then ask before saving everything in it. */
    fun planFolder(dir: RemoteFile) {
        counting = true
        scope.launch {
            val all = runCatching { Watch.walk(dir.path) }.getOrElse { ctx.toast(it.message ?: "Couldn't read that folder"); emptyList() }
            counting = false
            if (all.isEmpty()) ctx.toast("Nothing to save in ${dir.name}") else folderPlan = dir to all
        }
    }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    val connected = link is Link.Connected
    val path = st.filesPath

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Transfers.upload(ctx, uris, path)
    }

    LaunchedEffect(path, reload, uploaded, connected) {
        if (!connected) { entries = null; return@LaunchedEffect }
        error = null
        entries = null
        runCatching { Watch.list(if (path.endsWith("/")) path else "$path/") }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "Couldn't open this folder"; entries = emptyList() }
    }

    fun open(f: RemoteFile) {
        if (f.isDir || f.isLink) st.filesPath = f.path else selected = f
    }

    fun up() {
        if (path == "/") return
        st.filesPath = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
    }

    val btUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Transfers.uploadBluetooth(ctx, uris)
    }

    if (!connected) {
        LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.helperNearby) animatedItem(0) {
                EmptyState(
                    Icons.Rounded.Upload, "Send over Bluetooth",
                    "Not on Wi-Fi? Syndesia on your watch can still receive files over Bluetooth (slower). " +
                        "Music goes to Music, pictures to Pictures, videos to Movies, everything else to Download, each in a Syndesia folder.",
                    "Choose files", onAction = { btUpload.launch(arrayOf("*/*")) },
                )
            }
            animatedItem(1) {
                EmptyState(
                    Icons.Rounded.Folder,
                    if ((link as? Link.Connecting)?.reconnecting == true) "Reconnecting to your watch…" else "No watch connected",
                    "Connect your watch to browse its storage, send files to it and save files from it.",
                    "Connect", onAction = { onTab(STab.Watch) },
                )
            }
            if (transfers.isNotEmpty()) item { TransfersCard(transfers) }
        }
        return
    }

    val shown = entries?.filter { st.showHidden || !it.name.startsWith(".") }
    LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.forEach { (label, p) ->
                    val on = path.trimEnd('/') == p.trimEnd('/').ifBlank { "/" } || (p == "/" && path == "/")
                    Surface(
                        shape = CircleShape,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clip(CircleShape).clickable { st.filesPath = p },
                    ) { Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                }
            }
        }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { up() }, enabled = path != "/") { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up") }
                    Text(
                        path.removePrefix(STORAGE_ROOT).ifBlank { "Internal storage" }.let { if (path.startsWith(STORAGE_ROOT) && it != "Internal storage") "Storage$it" else it },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { st.setHidden(!st.showHidden) }) {
                        Icon(if (st.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Hidden files")
                    }
                    IconButton(onClick = {
                        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { "Watch" }
                        planFolder(RemoteFile(name, path.trimEnd('/').ifBlank { "/" }, 0x4000, 0, 0))
                    }, enabled = !counting) {
                        if (counting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Download, "Save this folder to phone")
                    }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { upload.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send files here")
                    }
                    FilledTonalButton(onClick = { newFolder = true }) {
                        Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Folder")
                    }
                }
            }
        }
        if (transfers.isNotEmpty()) item { TransfersCard(transfers) }
        when {
            shown == null -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            error != null -> item {
                EmptyState(Icons.Rounded.Error, "Can't open this folder", error.orEmpty(), "Go back", onAction = { up() })
            }
            shown.isEmpty() -> item {
                EmptyState(Icons.Rounded.Folder, "Empty folder", "Nothing here yet. Send some files from your phone.")
            }
            else -> {
                item { SectionHeader("${shown.size} ${if (shown.size == 1) "item" else "items"}") }
                item {
                    Text(
                        "Tip: long-press a folder to save all of it.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                items(shown, key = { it.path }) { f -> FileRow(f, onLongClick = { selected = f }) { open(f) } }
            }
        }
    }

    selected?.let { f ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = sheet) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { IconBadge(iconFor(f), MaterialTheme.colorScheme.primary, 44.dp) },
                    headlineContent = { Text(f.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(listOf(formatBytes(f.size), formatDate(f.mtime)).filter { it.isNotBlank() }.joinToString(" · ")) },
                )
                if (f.isDir || f.isLink) {
                    SheetAction(Icons.Rounded.FolderOpen, "Open", null) { st.filesPath = f.path; selected = null }
                    SheetAction(Icons.Rounded.Download, "Save folder to phone", "Everything inside, into Downloads/Syndesia/${f.name}") {
                        selected = null; planFolder(f)
                    }
                } else {
                    SheetAction(Icons.Rounded.Visibility, "Preview", previewHint(f)) { previewing = f; selected = null }
                    SheetAction(Icons.Rounded.Download, "Save to phone", "Into Downloads/Syndesia") {
                        Transfers.download(ctx, f); selected = null
                    }
                }
                if (f.name.endsWith(".apk", true)) {
                    SheetAction(Icons.Rounded.InstallMobile, "Install on watch", "Installs this APK straight from the watch's storage") {
                        selected = null
                        scope.launch {
                            val out = runCatching { Adb.sh("pm install -r -t ${Adb.q(f.path)}") }.getOrElse { it.message.orEmpty() }
                            ctx.toast(if (out.contains("Success")) "Installed" else out.trim().lines().lastOrNull().orEmpty().ifBlank { "Install failed" })
                        }
                    }
                }
                SheetAction(Icons.Rounded.DriveFileRenameOutline, "Rename", null) { renaming = f; selected = null }
                SheetAction(Icons.Rounded.Delete, "Delete", "From the watch", MaterialTheme.colorScheme.error) { deleting = f; selected = null }
            }
        }
    }

    previewing?.let { f -> PreviewDialog(f) { previewing = null } }

    folderPlan?.let { (dir, all) ->
        val total = all.sumOf { it.first.size }
        val capped = all.size >= 5000
        AlertDialog(
            onDismissRequest = { folderPlan = null },
            icon = { Icon(Icons.Rounded.Download, null) },
            title = { Text("Save ${dir.name}?") },
            text = {
                Text(
                    "${all.size}${if (capped) "+" else ""} files, ${formatBytes(total)}, into Downloads/Syndesia/${dir.name} on this phone. " +
                        "Folders inside are kept as they are." + if (capped) " Only the first 5,000 files will be saved." else ""
                )
            },
            confirmButton = {
                Button(onClick = {
                    folderPlan = null
                    val base = dir.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    all.forEach { (file, rel) -> Transfers.download(ctx, file, subfolder = if (rel.isEmpty()) base else "$base/$rel") }
                    ctx.toast("Saving ${all.size} files — progress is under Transfers")
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { folderPlan = null }) { Text("Cancel") } },
        )
    }

    if (newFolder) TextPromptDialog("New folder", "Folder name", "", "Create", onDone = { name ->
        scope.launch {
            val err = runCatching { Watch.mkdir(path.trimEnd('/') + "/" + name) }.getOrElse { it.message }
            if (err != null) ctx.toast(err) else reload++
        }
    }, onDismiss = { newFolder = false })

    renaming?.let { f ->
        TextPromptDialog("Rename", "New name", f.name, "Rename", onDone = { name ->
            scope.launch {
                val err = runCatching { Watch.rename(f.path, f.path.substringBeforeLast('/') + "/" + name) }.getOrElse { it.message }
                if (err != null) ctx.toast(err) else reload++
            }
        }, onDismiss = { renaming = null })
    }

    deleting?.let { f ->
        ConfirmDialog(
            "Delete ${f.name}?", if (f.isDir) "The folder and everything in it will be deleted from the watch." else "It will be deleted from the watch.",
            "Delete",
            onConfirm = {
                scope.launch {
                    val err = runCatching { Watch.delete(f.path) }.getOrElse { it.message }
                    if (err != null) ctx.toast(err) else reload++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, sub: String?, accent: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = accent) },
        headlineContent = { Text(title) },
        supportingContent = if (sub != null) { { Text(sub) } } else null,
    )
}

private fun iconFor(f: RemoteFile): ImageVector {
    if (f.isDir) return Icons.Rounded.Folder
    if (f.isLink) return Icons.Rounded.Link
    return when (f.name.substringAfterLast('.', "").lowercase()) {
        "apk", "apks", "xapk" -> Icons.Rounded.Android
        "png", "jpg", "jpeg", "webp", "gif", "heic", "bmp" -> Icons.Rounded.Image
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav" -> Icons.Rounded.Audiotrack
        "mp4", "mkv", "webm", "mov", "3gp" -> Icons.Rounded.Movie
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: RemoteFile, onLongClick: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(iconFor(f), if (f.isDir || f.isLink) colors.primary else colors.tertiary, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (f.isDir || f.isLink) formatDate(f.mtime) else listOf(formatBytes(f.size), formatDate(f.mtime)).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun TransfersCard(list: List<Transfer>) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    SectionCard {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (list.any { it.state == Transfer.State.Done || it.state == Transfer.State.Failed }) {
                TextButton(onClick = { Transfers.clearFinished() }) { Text("Clear") }
            }
        }
        list.takeLast(8).reversed().forEach { t ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (t.state) {
                            Transfer.State.Done -> Icons.Rounded.CheckCircle
                            Transfer.State.Failed -> Icons.Rounded.Error
                            else -> if (t.direction == Direction.ToWatch) Icons.Rounded.Upload else Icons.Rounded.Download
                        },
                        null,
                        tint = if (t.state == Transfer.State.Failed) colors.error else colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (t.state) {
                                Transfer.State.Waiting -> "Waiting"
                                Transfer.State.Running -> (if (t.viaBluetooth) "Sending over Bluetooth · " else if (t.direction == Direction.ToWatch) "Sending · " else "Saving · ") +
                                    if (t.total > 0) "${(t.fraction * 100).toInt()}% of ${formatBytes(t.total)}" else formatBytes(t.done)
                                Transfer.State.Done -> if (t.direction == Direction.ToWatch) "On the watch" + (if (t.viaBluetooth && t.where != null) " · ${t.where}" else "") else "Saved to ${t.where}"
                                Transfer.State.Failed -> t.error ?: "Failed"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t.state == Transfer.State.Failed) colors.error else colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    val uri = t.result
                    if (t.state == Transfer.State.Done && uri != null) {
                        TextButton(onClick = {
                            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
                            ctx.openSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }) { Text("Open") }
                    }
                }
                if (t.state == Transfer.State.Running) {
                    Spacer(Modifier.height(6.dp))
                    if (t.total > 0) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
