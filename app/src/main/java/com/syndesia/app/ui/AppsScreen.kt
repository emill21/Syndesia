package com.syndesia.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.adb.AppDetails
import com.syndesia.app.adb.Installer
import com.syndesia.app.adb.Link
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.Watch
import com.syndesia.app.adb.WatchApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(st: SyndesiaState, link: Link, padding: PaddingValues, onTab: (STab) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = link is Link.Connected
    val installed by Installer.installed.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<WatchApp>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<WatchApp?>(null) }

    LaunchedEffect(connected, st.showSystemApps, reload, installed) {
        if (!connected) { apps = null; return@LaunchedEffect }
        error = null
        runCatching { Watch.apps(st.showSystemApps) }
            .onSuccess { apps = it }
            .onFailure { error = it.message; apps = emptyList() }
    }

    if (!connected) {
        LazyColumn(contentPadding = padding.withPage()) {
            animatedItem(0) {
                EmptyState(
                    Icons.Rounded.Apps, "No watch connected",
                    "Connect your watch to see the apps on it, open them, save their APKs or uninstall them.",
                    "Connect", onAction = { onTab(STab.Watch) },
                )
            }
        }
        return
    }

    val shown = apps?.filter {
        query.isBlank() || it.packageName.contains(query.trim(), true) || prettyName(it.packageName).contains(query.trim(), true)
    }
    LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SearchField(query, "Search apps on the watch") { query = it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !st.showSystemApps, onClick = { st.setSystemApps(false) },
                    label = { Text("Installed by you") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = st.showSystemApps, onClick = { st.setSystemApps(true) },
                    label = { Text("All apps") },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { apps = null; reload++ }) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
        }
        when {
            shown == null -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            error != null -> item { EmptyState(Icons.Rounded.Apps, "Couldn't list apps", error.orEmpty(), "Try again", onAction = { reload++ }) }
            shown.isEmpty() -> item {
                EmptyState(
                    Icons.Rounded.Apps,
                    if (query.isBlank()) "No apps yet" else "No matches",
                    if (query.isBlank()) "Apps you install on the watch show up here." else "Nothing on the watch matches \"$query\".",
                )
            }
            else -> {
                item { SectionHeader("${shown.size} ${if (shown.size == 1) "app" else "apps"}") }
                items(shown, key = { it.packageName }) { a -> AppRow(a) { open = a } }
            }
        }
    }

    open?.let { a ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var details by remember(a.packageName) { mutableStateOf<AppDetails?>(null) }
        var confirm by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(a.packageName) { details = runCatching { Watch.details(a.packageName) }.getOrNull() }
        fun done(msg: String) { ctx.toast(msg) }
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = sheet) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { IconBadge(Icons.Rounded.Watch, MaterialTheme.colorScheme.primary, 48.dp) },
                    headlineContent = { Text(prettyName(a.packageName), style = MaterialTheme.typography.titleLarge) },
                    supportingContent = {
                        Column {
                            Text(a.packageName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            val d = details
                            val line = listOfNotNull(
                                d?.versionName?.let { "v$it" },
                                a.versionCode?.let { "build $it" },
                                d?.targetSdk?.let { "targets API $it" },
                            ).joinToString(" · ")
                            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall)
                            d?.lastUpdate?.let { Text("Updated $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                )
                Action(Icons.AutoMirrored.Rounded.OpenInNew, "Open on watch") {
                    scope.launch { if (!runCatching { Watch.launch(a.packageName) }.getOrDefault(false)) done("This app has no screen to open") }
                }
                Action(Icons.Rounded.StopCircle, "Force stop") {
                    scope.launch { runCatching { Watch.forceStop(a.packageName) }; done("Stopped") }
                }
                Action(Icons.Rounded.Download, "Save APK to phone") {
                    scope.launch {
                        val paths = runCatching { Watch.apkPaths(a.packageName) }.getOrDefault(emptyList())
                        if (paths.isEmpty()) { done("Couldn't find its APK"); return@launch }
                        val ver = details?.versionName?.let { "-$it" }.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_")
                        paths.forEachIndexed { i, p ->
                            val file = com.syndesia.app.adb.RemoteFile(p.substringAfterLast('/'), p, 0x8000, 0, 0)
                            val name = if (paths.size == 1) "${a.packageName}$ver.apk" else if (i == 0) "base.apk" else p.substringAfterLast('/')
                            Transfers.download(ctx, file, subfolder = if (paths.size > 1) "${a.packageName}$ver" else "APKs", rename = name)
                        }
                        done("Saving to Downloads/Syndesia — see Files › Transfers")
                        open = null
                    }
                }
                Action(if (a.enabled) Icons.Rounded.Block else Icons.Rounded.CheckCircle, if (a.enabled) "Disable" else "Enable") {
                    scope.launch {
                        val ok = runCatching { Watch.setEnabled(a.packageName, !a.enabled) }.getOrDefault(false)
                        done(if (ok) (if (a.enabled) "Disabled" else "Enabled") else "The watch didn't allow that")
                        open = null; reload++
                    }
                }
                Action(Icons.Rounded.CleaningServices, "Clear data", MaterialTheme.colorScheme.error) { confirm = "clear" }
                if (!a.system) Action(Icons.Rounded.Delete, "Uninstall", MaterialTheme.colorScheme.error) { confirm = "uninstall" }
            }
        }
        when (confirm) {
            "clear" -> ConfirmDialog(
                "Clear ${prettyName(a.packageName)}'s data?", "Its settings, accounts and files on the watch are erased, as if freshly installed.", "Clear data",
                onConfirm = { scope.launch { done(if (runCatching { Watch.clearData(a.packageName) }.getOrDefault(false)) "Data cleared" else "Couldn't clear data") } },
                onDismiss = { confirm = null },
            )
            "uninstall" -> ConfirmDialog(
                "Uninstall ${prettyName(a.packageName)}?", "${a.packageName} will be removed from the watch.", "Uninstall",
                onConfirm = {
                    scope.launch {
                        val err = runCatching { Watch.uninstall(a.packageName) }.getOrElse { it.message }
                        done(err ?: "Uninstalled")
                        open = null; reload++
                    }
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, accent: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null, tint = accent) },
        headlineContent = { Text(title, color = if (accent == MaterialTheme.colorScheme.error) accent else Color.Unspecified) },
    )
}

@Composable
private fun AppRow(a: WatchApp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Watch, if (a.enabled) colors.primary else colors.outline, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(prettyName(a.packageName), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!a.enabled) Pill("Disabled", null, colors.surfaceContainerHighest, colors.onSurfaceVariant)
            else if (a.system) Pill("System", null, colors.secondaryContainer, colors.onSecondaryContainer)
        }
    }
    Spacer(Modifier.size(0.dp))
}
