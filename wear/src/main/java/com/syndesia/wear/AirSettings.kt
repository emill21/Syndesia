package com.syndesia.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

/** Air mouse settings: how it turns on, speed, direction, and pinch calibration. */
class AirSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirMouse.load(this)
        setContent { SyndesiaWearTheme { AppScaffold { AirSettingsScreen() } } }
    }
}

@Composable
private fun Toggle(on: Boolean, label: String, secondary: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val sec: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = secondary?.let { t -> { Text(t) } }
    if (on) Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(icon, contentDescription = null) },
        secondaryLabel = sec,
        label = { Text(label) },
    ) else OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(icon, contentDescription = null) },
        secondaryLabel = sec,
        label = { Text(label) },
    )
}

@Composable
private fun AirSettingsScreen() {
    val ctx = LocalContext.current
    val s by AirMouse.settings.collectAsStateWithLifecycle()
    val hasSensors = remember { AirMouse.hasSensors(ctx) }
    // Calibration: 0 = idle, 1 = waiting for pinches, 2 = done
    var calStep by remember { mutableIntStateOf(0) }
    val peaks = remember { mutableStateListOf<Float>() }
    var calMsg by remember { mutableStateOf<String?>(null) }

    fun set(n: AirMouse.Settings) = AirMouse.update(ctx, n)

    fun finishCal() {
        AirMouse.calibrating = null
        AirMouse.stop()
        val sorted = peaks.sorted()
        if (sorted.size >= 3) {
            val median = sorted[sorted.size / 2]
            val thr = (median * 0.6f).coerceIn(1.6f, 12f)
            set(AirMouse.settings.value.copy(pinchThreshold = thr, pinch = true))
            calMsg = "Done. Pinch strength set to %.1f.".format(thr)
        } else {
            calMsg = "Couldn't feel enough pinches. Try tapping finger and thumb together a little firmer."
        }
        calStep = 0
    }

    fun startCal() {
        peaks.clear()
        calMsg = null
        AirMouse.start(ctx, object : AirMouse.Sink {
            override fun move(dx: Float, dy: Float) {}
            override fun click() {}
        })
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        AirMouse.calibrating = { p -> main.post { peaks.add(p) } }
        calStep = 1
    }

    LaunchedEffect(calStep, peaks.size) {
        if (calStep == 1 && peaks.size >= 5) finishCal()
    }
    LaunchedEffect(calStep) {
        if (calStep == 1) { delay(20_000); if (calStep == 1) finishCal() }
    }
    DisposableEffect(Unit) {
        onDispose { if (AirMouse.calibrating != null) { AirMouse.calibrating = null; AirMouse.stop() } }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Air mouse", color = MaterialTheme.colorScheme.primary) } }
            if (!hasSensors) {
                item { Text("This watch doesn't have the motion sensors the air mouse needs.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                return@ScalingLazyColumn
            }
            item {
                Text(
                    "Turn it on with the sensor button on either touchpad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            item { ListHeader { Text("Move the cursor") } }
            item {
                Toggle(s.mode == AirMouse.Mode.Touch, "While touching", "Rest a finger on the screen", Icons.Rounded.TouchApp) {
                    set(s.copy(mode = AirMouse.Mode.Touch))
                }
            }
            item {
                Toggle(s.mode == AirMouse.Mode.Always, "Always", "Hold the screen to drag", Icons.Rounded.Sensors) {
                    set(s.copy(mode = AirMouse.Mode.Always))
                }
            }
            item { ListHeader { Text("Speed") } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { set(s.copy(sensitivity = (s.sensitivity - 150f).coerceAtLeast(300f))) }) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Slower")
                    }
                    Text("${(s.sensitivity / 150f).toInt() - 1}", style = MaterialTheme.typography.titleMedium)
                    FilledTonalIconButton(onClick = { set(s.copy(sensitivity = (s.sensitivity + 150f).coerceAtMost(2400f))) }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Faster")
                    }
                }
            }
            item { Toggle(s.invertX, "Flip left/right", null, Icons.Rounded.SwapHoriz) { set(s.copy(invertX = !s.invertX)) } }
            item { Toggle(s.invertY, "Flip up/down", null, Icons.Rounded.SwapVert) { set(s.copy(invertY = !s.invertY)) } }
            item { ListHeader { Text("Pinch to click") } }
            item { Toggle(s.pinch, if (s.pinch) "Pinch on" else "Pinch off", "Tap finger and thumb together", Icons.Rounded.TouchApp) { set(s.copy(pinch = !s.pinch)) } }
            item {
                if (calStep == 1) Button(
                    onClick = { finishCal() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    secondaryLabel = { Text("Keep your arm still · ${peaks.size} of 5") },
                    label = { Text("Pinch now") },
                ) else FilledTonalButton(
                    onClick = { startCal() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    secondaryLabel = { Text("Pinch 5 times to teach it") },
                    label = { Text("Calibrate pinch") },
                )
            }
            calMsg?.let { m -> item { Text(m, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) } }
            item {
                Text(
                    "Too many clicks by accident? Calibrate with firmer pinches. Missed pinches? Calibrate with lighter ones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
