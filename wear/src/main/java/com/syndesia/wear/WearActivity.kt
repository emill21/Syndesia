package com.syndesia.wear

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay

private val colors = Colors(
    primary = Color(0xFFC9C8FF),
    primaryVariant = Color(0xFF8E8CF5),
    secondary = Color(0xFFC9C8FF),
    onPrimary = Color(0xFF1C1D5C),
    surface = Color(0xFF1F2033),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFC7C6DC),
    background = Color.Black,
)

/** Status screen for the watch helper. Normally the phone drives it; this is for checking and manual control. */
class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colors = colors) { Status() } }
    }
}

@Composable
private fun Status() {
    val ctx = LocalContext.current
    val wifi by WifiHold.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val canSet = remember(tick) { WifiHold.canSetDebugging(ctx) }
    val debugging = remember(tick) { WifiHold.wirelessDebuggingOn(ctx) }
    val switchedOff = remember(tick) { WifiHold.wifiSwitchedOff(ctx) }
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text("Syndesia", style = MaterialTheme.typography.title2, color = MaterialTheme.colors.primary)
            }
            item {
                Text(
                    "Your phone uses this to wake the watch's Wi-Fi.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                StatusChip(
                    "Wi-Fi",
                    when {
                        switchedOff -> "Switched off in Settings"
                        wifi == WifiHold.State.On -> "On for Syndesia"
                        wifi == WifiHold.State.Asking -> "Turning on…"
                        else -> "Not held"
                    },
                )
            }
            item { StatusChip("Wireless debugging", if (debugging) "On" else "Off") }
            item { StatusChip("Set up by phone", if (canSet) "Yes" else "No: install from Syndesia") }
            item {
                if (WifiHold.holding) {
                    Chip(
                        onClick = { WifiHold.release(ctx); tick++ },
                        label = { Text("Release Wi-Fi") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Chip(
                        onClick = { WifiKeeper.start(ctx); tick++ },
                        label = { Text("Turn on Wi-Fi") },
                        secondaryLabel = { Text("and Wireless debugging") },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (switchedOff) item {
                Chip(
                    onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    label = { Text("Open Wi-Fi settings") },
                    secondaryLabel = { Text("Set Wi-Fi to On or Automatic") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(title: String, value: String) {
    Chip(
        onClick = {},
        label = { Text(title) },
        secondaryLabel = { Text(value) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
