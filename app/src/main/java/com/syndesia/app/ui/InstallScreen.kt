package com.syndesia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.adb.InstallItem
import com.syndesia.app.adb.InstallStatus
import com.syndesia.app.adb.Installer
import com.syndesia.app.adb.active
import com.syndesia.app.adb.Link
import com.syndesia.app.adb.Watch
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.syndesia.app.BuildConfig
import com.syndesia.app.adb.Apk
import com.syndesia.app.adb.Helper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun InstallScreen(st: SyndesiaState, link: Link, padding: PaddingValues, onTab: (STab) -> Unit) {
    val items by Installer.items.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        st.addApks(uris)
    }
    val connected = link is Link.Connected
    // Without Wi-Fi, installs can still go through Syndesia on the watch, over Bluetooth.
    val bluetooth = !connected && st.helperNearby
    val queued = items.count { it.status == InstallStatus.Queued }
    val running = items.any { it.status.active }
    val finished = items.count { it.status == InstallStatus.Done || it.status is InstallStatus.Failed }

    LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        animatedItem(0) {
            PickCard(
                connected = connected,
                bluetooth = bluetooth,
                preparing = st.preparing,
                onPick = { picker.launch(arrayOf("*/*")) },
                onConnect = { onTab(STab.Watch) },
            )
        }
        animatedItem(1) { HelperCard(st, link) }
        if (st.problems.isNotEmpty()) {
            item {
                SectionCard(container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)) {
                    st.problems.forEach { p -> Tip(Icons.Rounded.Warning, p) }
                    TextButton(onClick = { st.problems = emptyList() }, modifier = Modifier.padding(start = 8.dp)) { Text("Dismiss") }
                }
            }
        }
        if (items.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Queue", Modifier.weight(1f))
                    if (finished > 0 && !running) TextButton(onClick = { Installer.clearFinished() }) { Text("Clear finished") }
                    if (running) TextButton(onClick = { Installer.cancel() }) { Text("Stop") }
                }
            }
            items(items, key = { it.pkg.id }) { item -> InstallRow(st, item, connected, bluetooth) }
            if (queued > 0) item {
                Button(
                    onClick = { Installer.start() },
                    enabled = (connected || bluetooth) && !running,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Rounded.Upload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (!connected && !bluetooth) "Connect a watch to install"
                        else if (bluetooth) (if (queued == 1) "Install over Bluetooth" else "Install all $queued over Bluetooth")
                        else if (queued == 1) "Install on watch" else "Install all $queued",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            animatedItem(1) {
                SectionCard(title = "Good to know") {
                    Tip(Icons.Rounded.FolderOpen, "Open or share an APK from any app (Files, Chrome, Telegram…) and pick Syndesia to send it here.")
                    Tip(Icons.Rounded.Layers, "Split apps: pick all their APKs together, or pick the .apks / .xapk bundle.")
                    Tip(Icons.Rounded.Watch, "Look for the Wear OS badge. Phone-only apps usually install but may not open on a watch.")
                }
            }
        }
    }
}

@Composable
private fun PickCard(connected: Boolean, bluetooth: Boolean, preparing: Boolean, onPick: () -> Unit, onConnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GradientCard(top = colors.primaryContainer.copy(alpha = 0.8f), bottom = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Rounded.Android, colors.primary, size = 72.dp)
            Spacer(Modifier.height(14.dp))
            Text("Install apps on your watch", style = MaterialTheme.typography.headlineSmall)
            Text(
                "APK, split APKs, .apks, .xapk",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPick, enabled = !preparing, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (preparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading files…")
                } else {
                    Icon(Icons.Rounded.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose APKs", style = MaterialTheme.typography.titleMedium)
                }
            }
            if (bluetooth) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Not on Wi-Fi: apps will go over Bluetooth to Syndesia on your watch, and the watch asks you to confirm each install. Slower, but no Wi-Fi needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else if (!connected) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LinkOff, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("No watch connected.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    TextButton(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun InstallRow(st: SyndesiaState, item: InstallItem, connected: Boolean, bluetooth: Boolean) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = item.pkg
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = p.icon
                if (icon != null) {
                    Image(icon.asImageBitmap(), null, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
                } else IconBadge(Icons.Rounded.Android, colors.primary, 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(p.versionName?.let { "v$it" }, formatBytes(p.totalBytes), p.packageName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val removable = item.status == InstallStatus.Queued || item.status == InstallStatus.Done || item.status is InstallStatus.Failed
                if (removable) IconButton(onClick = { Installer.remove(p.id) }) { Icon(Icons.Rounded.Close, "Remove") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (p.wearApp) {
                    true -> Pill("Wear OS app", Icons.Rounded.Watch, colors.primaryContainer, colors.onPrimaryContainer)
                    false -> Pill("Phone app", Icons.Rounded.PhoneAndroid, colors.tertiaryContainer, colors.onTertiaryContainer)
                    null -> {}
                }
                if (p.isSplit) Pill("${p.files.size} APKs", Icons.Rounded.Layers, colors.secondaryContainer, colors.onSecondaryContainer)
            }
            val watchSdk = com.syndesia.app.adb.Adb.connected?.sdk
            if (p.minSdk != null && watchSdk != null && watchSdk in 1 until p.minSdk && item.status == InstallStatus.Queued) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Needs Android API ${p.minSdk}; this watch is on API $watchSdk, so it will probably be refused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            val viaBt by Installer.viaBluetooth.collectAsStateWithLifecycle()
            when (val s = item.status) {
                InstallStatus.Queued -> Text(
                    if (item.interrupted) (if (connected) "Picking up where it left off…" else "Paused: the connection dropped. Resumes when the watch is back.")
                    else if (connected) "Ready to install" else if (bluetooth) "Ready to install over Bluetooth" else "Waiting for a watch",
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                )
                is InstallStatus.Sending -> {
                    val f = if (s.total > 0) (s.sent.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                    Text((if (viaBt) "Sending over Bluetooth · " else "Sending to watch · ") + "${(f * 100).toInt()}% of ${formatBytes(s.total)}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                }
                InstallStatus.ConfirmOnWatch -> {
                    Text("Tap Install on your watch", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Text(
                        "The watch is asking to confirm. If you don't see it, check its notifications.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                }
                InstallStatus.Installing -> {
                    Text(if (viaBt) "Handing it to the watch…" else "Installing on watch…", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                }
                InstallStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    val secs = ((item.finishedAt - item.startedAt) / 1000.0).coerceAtLeast(0.1)
                    Text("Installed in ${String.format(java.util.Locale.US, "%.1f", secs)} s", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val pkg = p.packageName
                    if (pkg != null && connected) FilledTonalButton(onClick = {
                        scope.launch { if (!runCatching { Watch.launch(pkg) }.getOrDefault(false)) ctx.toast("This app has no screen to open on the watch") }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open on watch")
                    }
                }
                is InstallStatus.Failed -> {
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Error, null, tint = colors.onErrorContainer, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
                                s.code?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onErrorContainer.copy(alpha = 0.7f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { Installer.retry(p.id) }, enabled = connected || bluetooth) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
                        }
                        if (s.code == "INSTALL_FAILED_VERSION_DOWNGRADE" && !st.allowDowngrade) {
                            TextButton(onClick = { st.setDowngrade(true); Installer.retry(p.id) }) { Text("Allow downgrade") }
                        }
                    }
                }
            }
        }
    }
}

/** Install (or update) Syndesia's own watch helper, bundled inside this app. */
@Composable
private fun HelperCard(st: SyndesiaState, link: Link) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Installer.items.collectAsStateWithLifecycle()
    val installedCount by Installer.installed.collectAsStateWithLifecycle()
    val info = (link as? Link.Connected)?.info
    var onWatch by remember { mutableStateOf<Long?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(info, installedCount) {
        checked = false
        onWatch = null
        if (info != null) {
            onWatch = runCatching { Helper.installedVersion() }.getOrNull()
            checked = true
        }
    }
    val bundled = BuildConfig.VERSION_CODE.toLong()
    val busy = items.any { it.pkg.packageName == Helper.PACKAGE && (it.status == InstallStatus.Queued || it.status.active) }
    val upToDate = onWatch != null && onWatch!! >= bundled
    SectionCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Watch, colors.primary, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Syndesia for your watch", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        info == null -> "Wakes the watch's Wi-Fi over Bluetooth when Syndesia can't reach it, and turns Wireless debugging back on."
                        !checked -> "Checking the watch…"
                        onWatch == null -> "Not on this watch yet. Wakes its Wi-Fi over Bluetooth and turns Wireless debugging back on."
                        upToDate -> "Installed and set up. Syndesia wakes your watch's Wi-Fi when it needs to."
                        else -> "An update is ready for the watch helper."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (upToDate) Pill("Installed", Icons.Rounded.CheckCircle, colors.primaryContainer, colors.onPrimaryContainer)
            Spacer(Modifier.weight(1f))
            val label = when {
                onWatch == null -> "Install on watch"
                upToDate -> "Reinstall"
                else -> "Update"
            }
            val action: () -> Unit = {
                scope.launch {
                    val pkg = withContext(Dispatchers.IO) { Helper.bundledApk(ctx)?.let { Apk.fromFile(ctx, it) } }
                    if (pkg == null) ctx.toast("The watch helper isn't included in this build")
                    else { Installer.add(listOf(pkg.copy(label = "Syndesia for Wear OS"))); Installer.start() }
                }
            }
            if (upToDate) OutlinedButton(onClick = action, enabled = info != null && !busy) { Text(label) }
            else Button(onClick = action, enabled = info != null && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                else Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label)
            }
        }
        if (info == null) Text(
            "Connect over Wi-Fi once to install it.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}
