package com.syndesia.wear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** The helper's home screen: talk to the phone, see transfers, and check Wi-Fi / debugging. */
class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyndesiaWearTheme {
                AppScaffold { Home(onScreenshot = { screenshot() }) }
            }
        }
    }

    /** Get out of the way so the screenshot shows what's under the app. */
    private fun screenshot() {
        Phone.screenshot(this, SCREENSHOT_DELAY)
        Toast.makeText(this, "Screenshot in $SCREENSHOT_DELAY s", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    companion object {
        const val SCREENSHOT_DELAY = 5
    }
}

@Composable
private fun Home(onScreenshot: () -> Unit) {
    val ctx = LocalContext.current
    val wifi by WifiHold.state.collectAsStateWithLifecycle()
    val status by Phone.status.collectAsStateWithLifecycle()
    val transfers by WatchTransfers.list.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val canSet = remember(tick) { WifiHold.canSetDebugging(ctx) }
    val debugging = remember(tick) { WifiHold.wirelessDebuggingOn(ctx) }
    val switchedOff = remember(tick) { WifiHold.wifiSwitchedOff(ctx) }
    val listState = rememberScalingLazyListState()
    val colors = MaterialTheme.colorScheme

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ListHeader { Text("Syndesia", color = colors.primary) } }

            // What the phone last said about a connect / screenshot request.
            status?.let { s ->
                item {
                    Card(onClick = { Phone.clearStatus() }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            statusTitle(s),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (s.stage == "failed") colors.error else colors.primary,
                        )
                        s.message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { Phone.connect(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    secondaryLabel = { Text("Wi-Fi on, then link up") },
                    label = { Text("Connect to phone") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { ctx.startActivity(Intent(ctx, FilesActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                    secondaryLabel = { Text("Over Bluetooth") },
                    label = { Text("Send to phone") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = onScreenshot,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Screenshot, contentDescription = null) },
                    secondaryLabel = { Text("Saved on phone in ${WearActivity.SCREENSHOT_DELAY} s") },
                    label = { Text("Screenshot") },
                )
            }

            if (transfers.isNotEmpty()) {
                item { ListHeader { Text("Transfers") } }
                items(transfers.reversed(), key = { it.id }) { t -> TransferCard(t) }
                if (transfers.any { it.state != WTransfer.State.Running }) item {
                    OutlinedButton(
                        onClick = { WatchTransfers.clearFinished() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Clear finished") },
                    )
                }
            }

            item { ListHeader { Text("This watch") } }
            item {
                StatusButton(
                    icon = { Icon(if (wifi == WifiHold.State.On) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, contentDescription = null) },
                    title = "Wi-Fi",
                    value = when {
                        switchedOff -> "Off in Settings"
                        wifi == WifiHold.State.On -> "On for Syndesia"
                        wifi == WifiHold.State.Asking -> "Turning on…"
                        else -> "Not held"
                    },
                ) {
                    if (switchedOff) runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else if (WifiHold.holding) WifiHold.release(ctx) else WifiKeeper.start(ctx)
                    tick++
                }
            }
            item {
                StatusButton(
                    icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                    title = "Wireless debugging",
                    value = if (debugging) "On" else if (canSet) "Off · tap to turn on" else "Off",
                ) {
                    if (canSet) WifiHold.enableWirelessDebugging(ctx)
                    tick++
                }
            }
            item {
                StatusButton(
                    icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                    title = "Set up by phone",
                    value = if (canSet) "Yes" else "Install from Syndesia",
                ) {}
            }
            item {
                Text(
                    "Your phone uses this app to wake Wi-Fi and swap files over Bluetooth.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun statusTitle(s: Phone.Status): String = when (s.kind) {
    "connect" -> when (s.stage) {
        "wifi" -> "Turning on Wi-Fi…"
        "asking" -> "Asking the phone…"
        "connecting" -> "Phone is connecting…"
        "connected" -> "Connected to phone"
        "tap" -> "Tap the notice on your phone"
        else -> "Couldn't connect"
    }
    "screenshot" -> when (s.stage) {
        "asking", "waiting" -> "Screenshot coming…"
        "saved" -> "Screenshot saved on phone"
        else -> "Screenshot failed"
    }
    else -> s.stage
}

@Composable
private fun TransferCard(t: WTransfer) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column {
            androidx.compose.foundation.layout.Row {
                Icon(
                    when (t.state) {
                        WTransfer.State.Done -> Icons.Rounded.CheckCircle
                        WTransfer.State.Failed -> Icons.Rounded.ErrorOutline
                        else -> if (t.toPhone) Icons.Rounded.Upload else Icons.Rounded.Download
                    },
                    contentDescription = null,
                    tint = if (t.state == WTransfer.State.Failed) colors.error else colors.primary,
                )
                Spacer(Modifier.padding(start = 6.dp))
                Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when (t.state) {
                    WTransfer.State.Running -> (if (t.toPhone) "To phone · " else "From phone · ") +
                        if (t.total > 0) "${(t.fraction * 100).toInt()}%" else "…"
                    WTransfer.State.Done -> t.message ?: if (t.toPhone) "On your phone" else "Done"
                    WTransfer.State.Failed -> t.message ?: "Failed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (t.state == WTransfer.State.Failed) colors.error else colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.state == WTransfer.State.Running) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatusButton(icon: @Composable () -> Unit, title: String, value: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { icon() },
        secondaryLabel = { Text(value) },
        label = { Text(title) },
    )
}
