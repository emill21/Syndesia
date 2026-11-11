package com.syndesia.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.syndesia.app.SavedWatch
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Discovery
import com.syndesia.app.adb.Found
import com.syndesia.app.adb.Hotspot
import com.syndesia.app.adb.Link
import com.syndesia.app.adb.Recorder
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.Watch
import com.syndesia.app.adb.WatchInfo
import com.syndesia.app.adb.WatchStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchScreen(st: SyndesiaState, link: Link, found: List<Found>, padding: PaddingValues, onTab: (STab) -> Unit) {
    val info = (link as? Link.Connected)?.info
    var stats by remember { mutableStateOf<WatchStats?>(null) }
    LaunchedEffect(info) {
        stats = null
        if (info != null) while (true) {
            runCatching { Watch.stats() }.onSuccess { stats = it }
            delay(20_000)
        }
    }
    LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        animatedItem(0) { WatchHero(st, link, stats) }
        if (info != null) {
            animatedItem(1) { StatsStrip(stats) }
            animatedItem(2) { QuickActions(st, onTab) }
            animatedItem(3) { ScreenshotCard() }
            animatedItem(3) { RecordCard() }
            animatedItem(4) { CommandCard() }
            animatedItem(5) { DeviceCard(st, info, stats) }
        } else {
            animatedItem(1) { NearbySection(st, found, link) }
            if (st.saved.isNotEmpty()) animatedItem(2) { SavedSection(st, found, link) }
            animatedItem(3) { SetupGuide() }
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun WatchHero(st: SyndesiaState, link: Link, stats: WatchStats?) {
    val colors = MaterialTheme.colorScheme
    val searching by Discovery.searching.collectAsState()
    val level = when (link) {
        is Link.Connected -> (stats?.battery ?: 60) / 100f
        is Link.Connecting -> 0.35f
        else -> 0.12f
    }
    GradientCard(top = colors.primaryContainer.copy(alpha = 0.8f), bottom = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(22.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LinkOrb(
                level = level,
                spinning = link is Link.Connecting || (link !is Link.Connected && searching),
                modifier = Modifier.size(220.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (link) {
                        is Link.Connected -> {
                            val b = stats?.battery
                            if (b != null) {
                                RollingText("$b%", style = MaterialTheme.typography.displaySmall)
                                Text(if (stats?.charging == true) "charging" else "battery", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                            } else {
                                Icon(Icons.Rounded.Watch, null, Modifier.size(56.dp))
                            }
                        }
                        is Link.Connecting -> {
                            Icon(Icons.Rounded.Link, null, Modifier.size(48.dp))
                            Text("connecting", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        }
                        else -> {
                            Icon(Icons.Rounded.Watch, null, Modifier.size(56.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (link) {
                is Link.Connected -> {
                    val i = link.info
                    Text(i.model, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(
                        listOfNotNull(
                            if (i.isWatch) "Wear OS" else "Android device",
                            i.release.takeIf { it.isNotBlank() }?.let { "Android $it" },
                            "${i.host}:${i.port}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Connected", Icons.Rounded.Link, colors.primary, colors.onPrimary)
                        val hs by Hotspot.creds.collectAsState()
                        if (hs != null) Pill("Via phone hotspot", Icons.Rounded.WifiTethering, colors.secondaryContainer, colors.onSecondaryContainer)
                        if (stats?.charging == true) Pill("Charging", Icons.Rounded.Bolt, colors.tertiaryContainer, colors.onTertiaryContainer)
                    }
                }
                is Link.Connecting -> {
                    Text(if (link.reconnecting) "Reconnecting…" else "Connecting…", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (link.reconnecting) "The connection dropped for a moment. Getting it back (${link.host}:${link.port})…"
                        else if (link.waitingForApproval) "If the watch asks \"Allow debugging?\", tap Allow (tick \"Always allow\" to skip this next time)."
                        else "${link.host}:${link.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is Link.Failed -> {
                    Text("Couldn't connect", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(link.message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (link.needsPairing) Button(onClick = { st.pairRequest = AddressRequest(link.host) }) {
                            Icon(Icons.Rounded.Password, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pair")
                        } else Button(onClick = { st.connect(link.host, link.port) }) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
                        }
                        OutlinedButton(onClick = { st.connectRequest = AddressRequest(link.host, "") }) { Text("Other port") }
                    }
                    if (st.helperNearby) { HotspotButton(st); WakeButton(st) }
                }
                Link.Idle -> {
                    Text("No watch connected", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(
                        if (searching) "Looking for watches on this Wi-Fi…" else "Turn on Wireless debugging on your watch.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { st.pairRequest = AddressRequest() }) {
                            Icon(Icons.Rounded.Password, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pair")
                        }
                        OutlinedButton(onClick = { st.connectRequest = AddressRequest() }) { Text("Enter address") }
                    }
                    if (st.helperNearby) HotspotButton(st)
                    if (st.helperNearby && st.saved.isNotEmpty()) WakeButton(st)
                }
            }
        }
    }
}

/** No shared Wi-Fi? Start the phone's hotspot and have the watch join it. */
@Composable
private fun HotspotButton(st: SyndesiaState) {
    val ctx = LocalContext.current
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) st.connectViaHotspot()
        else st.notice = if (Build.VERSION.SDK_INT >= 33) "Syndesia needs the Nearby devices permission to start a hotspot for your watch."
        else "Syndesia needs the Location permission to start a hotspot for your watch (Android asks for it for hotspots)."
    }
    Spacer(Modifier.height(10.dp))
    val stage = st.hotspotStage
    if (stage != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stage, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        FilledTonalButton(onClick = {
            val granted = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
            if (granted) st.connectViaHotspot() else ask.launch(perms)
        }) {
            Icon(Icons.Rounded.WifiTethering, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Connect via phone hotspot")
        }
    }
}

@Composable
private fun WakeButton(st: SyndesiaState) {
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.TextButton(onClick = { st.wakeWatch() }) {
        Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Wake watch Wi-Fi over Bluetooth")
    }
}

@Composable
private fun StatsStrip(stats: WatchStats?) {
    val colors = MaterialTheme.colorScheme
    HeroStatStrip(
        listOf(
            HeroStat(Icons.Rounded.SdStorage, "free storage", stats?.storageFreeKb?.let { formatBytes(it * 1024) } ?: "…"),
            HeroStat(Icons.Rounded.Memory, "free memory", stats?.ramFreeKb?.let { formatBytes(it * 1024) } ?: "…"),
            HeroStat(Icons.Rounded.Schedule, "uptime", stats?.uptimeSec?.let { formatUptime(it) } ?: "…"),
        ),
        container = colors.surfaceContainerLow,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(st: SyndesiaState, onTab: (STab) -> Unit) {
    SectionCard {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { onTab(STab.Install) }) {
                Icon(Icons.Rounded.InstallMobile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Install APK")
            }
            FilledTonalButton(onClick = { onTab(STab.Files) }) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Files")
            }
            OutlinedButton(onClick = { st.disconnect() }) {
                Icon(Icons.Rounded.LinkOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Disconnect")
            }
        }
    }
}

// ---------------------------------------------------------------- screenshot

@Composable
private fun ScreenshotCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<Pair<ByteArray, Bitmap>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun take() {
        busy = true; error = null
        scope.launch {
            runCatching { Watch.screenshot() }.onSuccess { shot = it }.onFailure { error = it.message }
            busy = false
        }
    }
    SectionCard(title = "Screenshot") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            shot?.let { (png, bmp) ->
                val round = bmp.width == bmp.height
                Image(
                    bmp.asImageBitmap(), "Watch screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(220.dp)
                        .clip(if (round) CircleShape else RoundedCornerShape(28.dp))
                        .border(3.dp, MaterialTheme.colorScheme.outlineVariant, if (round) CircleShape else RoundedCornerShape(28.dp)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    FilledTonalButton(onClick = {
                        val name = "watch_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { Transfers.saveBytes(ctx, name, png, "Screenshots") } }
                                .onSuccess { (_, where) -> ctx.toast("Saved to $where") }
                                .onFailure { ctx.toast("Couldn't save: ${it.message}") }
                        }
                    }) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                                val f = File(dir, "watch_screenshot.png").apply { writeBytes(png) }
                                FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            }
                            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.openSafely(Intent.createChooser(send, "Share screenshot"))
                        }
                    }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                    IconButton(onClick = { shot = null }) { Icon(Icons.Rounded.Close, "Close") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
            Button(onClick = { take() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Rounded.Screenshot, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (shot == null) "Take screenshot" else "Take another")
            }
        }
    }
}

// ---------------------------------------------------------------- recording

@Composable
private fun RecordCard() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val state by Recorder.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is Recorder.State.Recording) { now = System.currentTimeMillis(); delay(250) }
    }
    SectionCard(title = "Screen recording") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                Recorder.State.Idle -> {
                    Text(
                        "Records the watch screen (up to ${Recorder.MAX_SECONDS / 60} minutes) and saves the video to Downloads/Syndesia/Recordings.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Button(onClick = { Recorder.start(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Start recording")
                    }
                }
                is Recorder.State.Recording -> {
                    val secs = ((now - s.startedAt) / 1000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FiberManualRecord, null, tint = Color(0xFFE53935), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            String.format(Locale.US, "Recording  %d:%02d", secs / 60, secs % 60),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("max ${Recorder.MAX_SECONDS / 60}:00", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Button(
                        onClick = { Recorder.stop() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    ) {
                        Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop and save")
                    }
                }
                Recorder.State.Stopping, is Recorder.State.Saving -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (s is Recorder.State.Saving) "Saving ${s.name} to your phone…" else "Finishing the video…", style = MaterialTheme.typography.bodyMedium)
                }
                is Recorder.State.Saved -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Saved to Downloads/Syndesia/Recordings", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    Text("Open it from Files › Transfers, or your phone's Files app.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { Recorder.dismiss(); Recorder.start(ctx) }) { Text("Record again") }
                        OutlinedButton(onClick = { Recorder.dismiss() }) { Text("Done") }
                    }
                }
                is Recorder.State.Error -> {
                    Text(s.message, color = colors.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { Recorder.dismiss(); Recorder.start(ctx) }) { Text("Try again") }
                        OutlinedButton(onClick = { Recorder.dismiss() }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shell

@Composable
private fun CommandCard() {
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run() {
        if (cmd.isBlank() || busy) return
        busy = true
        val c = cmd.trim()
        scope.launch {
            output = runCatching { Adb.sh(c) }.getOrElse { "Error: ${it.message}" }.ifBlank { "(no output)" }
            busy = false
        }
    }
    SectionCard(title = "Run a command") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Runs in the watch's ADB shell, like adb shell on a computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("getprop ro.build.display.id", "dumpsys battery", "pm list packages -3", "df -h", "settings list global").forEach { s ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clip(CircleShape).clickable { cmd = s },
                    ) { Text(s, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                }
            }
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                singleLine = true,
                placeholder = { Text("e.g. getprop ro.product.model") },
                leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                trailingIcon = {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { run() }, enabled = cmd.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Run") }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { run() }),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(output != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Text(
                            output.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- details

@Composable
private fun DeviceCard(st: SyndesiaState, info: WatchInfo, stats: WatchStats?) {
    val scope = rememberCoroutineScope()
    var confirmReboot by remember { mutableStateOf(false) }
    SectionCard(title = "About this watch") {
        if (info.manufacturer.isNotBlank()) InfoRow("Maker", info.manufacturer.replaceFirstChar { it.uppercase() }, icon = Icons.Rounded.Watch)
        InfoRow("Android", "${info.release} (API ${info.sdk})", icon = Icons.Rounded.Bolt)
        stats?.screen?.let { InfoRow("Screen", it, icon = Icons.Rounded.Screenshot) }
        stats?.wifi?.let { InfoRow("Wi-Fi", it, icon = Icons.Rounded.Wifi) }
        stats?.batteryTempC?.let { InfoRow("Battery temperature", String.format(Locale.US, "%.1f °C", it), icon = Icons.Rounded.Thermostat) }
        stats?.storageTotalKb?.let { t -> InfoRow("Storage", "${formatBytes((stats?.storageFreeKb ?: 0) * 1024)} free of ${formatBytes(t * 1024)}", icon = Icons.Rounded.SdStorage) }
        if (info.serial.isNotBlank()) InfoRow("Serial", info.serial, icon = Icons.Rounded.Memory)
        if (info.port != 5555) LinkRow(
            Icons.Rounded.WifiTethering, "Skip the network prompt",
            "Switch the watch to port 5555 until it restarts, so connecting through your phone's hotspot (or any new Wi-Fi) doesn't ask each time.",
        ) { st.enableClassicPort() }
        else InfoRow("Port 5555 mode", "On until the watch restarts", icon = Icons.Rounded.WifiTethering)
        LinkRow(Icons.Rounded.RestartAlt, "Restart watch", "Reboots it. You'll need to turn Wireless debugging on again afterwards on most watches.", MaterialTheme.colorScheme.error) {
            confirmReboot = true
        }
    }
    if (confirmReboot) ConfirmDialog(
        "Restart the watch?", "It will disconnect and reboot.", "Restart",
        onConfirm = { scope.launch { Watch.reboot(); st.disconnect() } },
        onDismiss = { confirmReboot = false },
    )
}

// ---------------------------------------------------------------- not connected

@Composable
private fun NearbySection(st: SyndesiaState, found: List<Found>, link: Link) {
    val ctx = LocalContext.current
    val searching by Discovery.searching.collectAsState()
    val colors = MaterialTheme.colorScheme
    SectionCard {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("On this Wi-Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            IconButton(onClick = { Discovery.restart(ctx) }) { Icon(Icons.Rounded.Refresh, "Search again") }
        }
        if (found.isEmpty()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Wifi, colors.primary, 36.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    "Nothing yet. Keep the watch on the same Wi-Fi as this phone, with Wireless debugging on. " +
                        "Some watches drop Wi-Fi while connected over Bluetooth; turning Bluetooth off on the watch for a moment helps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        found.forEach { f ->
            val saved = st.saved.firstOrNull { (it.serial.isNotBlank() && it.serial == f.serial) || it.host == f.host }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { IconBadge(Icons.Rounded.Watch, colors.primary, 40.dp) },
                headlineContent = { Text(saved?.model ?: f.serial.ifBlank { "Wireless debugging device" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        f.host + (f.connectPort?.let { ":$it" } ?: "") + if (f.pairPort != null) " · ready to pair" else "",
                        maxLines = 1,
                    )
                },
                trailingContent = {
                    val busy = link is Link.Connecting && link.host == f.host
                    when {
                        busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        f.pairPort != null && saved == null -> FilledTonalButton(onClick = { st.pairRequest = AddressRequest(f.host, f.pairPort.toString()) }) { Text("Pair") }
                        f.connectPort != null -> FilledTonalButton(onClick = { st.connect(f.host, f.connectPort) }) { Text("Connect") }
                        f.pairPort != null -> FilledTonalButton(onClick = { st.pairRequest = AddressRequest(f.host, f.pairPort.toString()) }) { Text("Pair") }
                    }
                },
            )
        }
    }
}

@Composable
private fun SavedSection(st: SyndesiaState, found: List<Found>, link: Link) {
    val colors = MaterialTheme.colorScheme
    var forget by remember { mutableStateOf<SavedWatch?>(null) }
    SectionCard(title = "Your watches") {
        st.saved.forEach { w ->
            val live = found.firstOrNull { (w.serial.isNotBlank() && it.serial == w.serial) || it.host == w.host }
            val port = live?.connectPort ?: w.port
            val host = live?.host ?: w.host
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { st.connect(host, port) },
                leadingContent = { IconBadge(Icons.Rounded.Watch, if (live != null) colors.primary else colors.outline, 40.dp) },
                headlineContent = { Text(w.model, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text((if (live != null) "Nearby · " else "Last seen ${formatDate(w.lastSeen / 1000)} · ") + "$host:$port", maxLines = 1) },
                trailingContent = {
                    if (link is Link.Connecting && link.host == host) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { forget = w }) { Icon(Icons.Rounded.Delete, "Forget") }
                },
            )
        }
    }
    forget?.let { w ->
        ConfirmDialog(
            "Forget ${w.model}?", "It'll be removed from this list. The watch itself still remembers this phone until you remove it there.", "Forget",
            onConfirm = { st.forget(w) }, onDismiss = { forget = null },
        )
    }
}

@Composable
fun SetupGuide() {
    SectionCard(title = "Set up your watch (once)") {
        GuideStep("1", "Unlock Developer options", "Settings › System › About › Versions (or Software info), then tap Build number 7 times.")
        GuideStep("2", "Join the same Wi-Fi", "Settings › Connectivity › Wi-Fi on the watch. It has to be on the same network as this phone.")
        GuideStep("3", "Turn on debugging", "Settings › Developer options: turn on ADB debugging, then Wireless debugging (older watches: Debug over Wi-Fi).")
        GuideStep("4", "Pair", "In Wireless debugging tap Pair new device. Syndesia usually spots it; tap Pair and type the code.")
    }
}

@Composable
fun GuideStep(n: String, title: String, body: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(n, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
