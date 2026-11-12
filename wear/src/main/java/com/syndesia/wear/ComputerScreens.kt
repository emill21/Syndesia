package com.syndesia.wear

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.syndesia.hid.HidMouse
import com.syndesia.hid.Keymap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

private val btPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()

// =================================================================== hub

/** Pick a computer, pair a new one, then open the touchpad or keyboard. */
class ComputerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { AppScaffold { Hub() } } }
    }
}

@Composable
private fun Hub() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status by HidMouse.status.collectAsStateWithLifecycle()
    val host by HidMouse.host.collectAsStateWithLifecycle()
    val error by HidMouse.error.collectAsStateWithLifecycle()
    val viaPhone by Pointer.viaPhone.collectAsStateWithLifecycle()
    val phone by Pointer.phone.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(btPermissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    LaunchedEffect(granted, viaPhone) {
        if (!granted && btPermissions.isNotEmpty()) ask.launch(btPermissions)
        else if (!viaPhone) HidMouse.start(ctx, "Syndesia mouse")
    }
    LaunchedEffect(status) {
        if (status == HidMouse.Status.Connected) MouseKeeper.start(ctx)
    }
    val paired = remember(status, refresh, granted) { if (granted) HidMouse.paired(ctx) else emptyList() }
    val listState = rememberScalingLazyListState()
    val connected = if (viaPhone) phone?.state == "connected" else status == HidMouse.Status.Connected
    val hostName = if (viaPhone) phone?.host else host?.name

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Computer mouse", color = colors.primary) } }
            item {
                Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            viaPhone -> when (phone?.state) {
                                "connected" -> "Phone is the mouse for ${phone?.host ?: "your computer"}"
                                "ready" -> "Phone is ready: pick a computer"
                                "not_ready", "unreachable" -> "Phone isn't ready"
                                else -> "Asking your phone…"
                            }
                            status == HidMouse.Status.Connected -> "Connected to ${host?.name}"
                            status == HidMouse.Status.Connecting -> "Connecting to ${host?.name ?: "computer"}…"
                            status == HidMouse.Status.Ready -> "Ready: pick a computer"
                            status == HidMouse.Status.Unsupported -> "This watch can't be a Bluetooth mouse"
                            !granted -> "Allow Nearby devices to continue"
                            else -> "Starting…"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val msg = if (viaPhone) phone?.message else error
                    if (msg != null) Text(msg, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (connected) {
                item {
                    Button(
                        onClick = { ctx.startActivity(Intent(ctx, ComputerPadActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.TouchApp, contentDescription = null) },
                        secondaryLabel = { Text("Drag, tap, hold, crown") },
                        label = { Text("Touchpad") },
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = { ctx.startActivity(Intent(ctx, ComputerKeysActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                        label = { Text("Keyboard") },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            if (viaPhone) Pointer.relay(ctx, "stop") else { HidMouse.disconnect(); MouseKeeper.stop(ctx) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
                        label = { Text("Disconnect ${hostName ?: ""}".trim()) },
                    )
                }
            } else if (!viaPhone && status == HidMouse.Status.Unsupported) {
                item {
                    Button(
                        onClick = { Pointer.usePhone(ctx, true) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                        secondaryLabel = { Text("Your phone acts as the mouse") },
                        label = { Text("Use your phone") },
                    )
                }
            } else if (!viaPhone && (status == HidMouse.Status.Ready || status == HidMouse.Status.Connecting)) {
                if (paired.isNotEmpty()) item { ListHeader { Text("Paired") } }
                items(paired, key = { it.address }) { h ->
                    FilledTonalButton(
                        onClick = { HidMouse.connect(ctx, h.address) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(if (h.isComputer) Icons.Rounded.Computer else Icons.Rounded.Mouse, contentDescription = null) },
                        label = { Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                item {
                    Button(
                        onClick = {
                            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.BluetoothSearching, contentDescription = null) },
                        secondaryLabel = { Text("Then pick this watch on the computer") },
                        label = { Text("Pair a computer") },
                    )
                }
                item {
                    Text(
                        "On the computer, open Bluetooth settings, add a device, and choose this watch. It shows up as a mouse and keyboard.",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            } else if (viaPhone && phone?.state == "ready") {
                item {
                    Text(
                        "On your phone, pick the computer in the Syndesia notice, or pair the phone with the computer in its Bluetooth settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (viaPhone) item {
                OutlinedButton(
                    onClick = { Pointer.usePhone(ctx, false) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Try the watch again") },
                )
            } else if (status != HidMouse.Status.Unsupported) item {
                OutlinedButton(
                    onClick = { HidMouse.stop(); Pointer.usePhone(ctx, true) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                    label = { Text("Use phone instead") },
                )
            }
        }
    }
}

// =================================================================== touchpad

/** Full-screen touchpad. No swipe-to-dismiss here (it would eat drags); use the X. */
class ComputerPadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { ComputerPad(onClose = { finish() }) } }
    }
}

@Composable
private fun ComputerPad(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    var wheelAcc by remember { mutableFloatStateOf(0f) }
    var gain by remember { mutableFloatStateOf(2.2f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    /** Watch pixels to computer pixels, faster for quick strokes (smoothed so it doesn't jump). */
    fun send(dx: Float, dy: Float) {
        val speed = hypot(dx, dy)
        val target = 2.0f + min(speed * 0.12f, 3.5f)
        gain += (target - gain) * 0.3f
        Pointer.move(ctx, dx * gain, dy * gain)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onRotaryScrollEvent { e ->
                // Crown = scroll wheel. Clockwise scrolls down.
                wheelAcc += e.verticalScrollPixels
                val ticks = (wheelAcc / 22f).toInt()
                if (ticks != 0) {
                    Pointer.wheel(ctx, -ticks)
                    wheelAcc -= ticks * 22f
                }
                hint = false
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    hint = false
                    var last = down.position
                    var moved = false
                    var held = false
                    val holdAt = down.uptimeMillis + 380
                    while (true) {
                        val event = if (!moved && !held) {
                            withTimeoutOrNull((holdAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (event == null) {
                            if (!moved && !held) {
                                held = true; holding = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                Pointer.press(ctx, HidMouse.LEFT)
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (held) Pointer.release(ctx, HidMouse.LEFT) else if (!moved) Pointer.click(ctx, HidMouse.LEFT)
                            holding = false
                            break
                        }
                        if (!moved && !held && (change.position - down.position).getDistance() > slop) moved = true
                        if (moved || held) {
                            val d = change.position - last
                            last = change.position
                            if (abs(d.x) + abs(d.y) > 0f) send(d.x, d.y)
                            change.consume()
                        }
                        if (!moved && !held && change.uptimeMillis >= holdAt) {
                            held = true; holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Pointer.press(ctx, HidMouse.LEFT)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp).background(if (holding) colors.primaryContainer else colors.surfaceContainer, CircleShape))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Computer, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            if (holding) Text("Holding · drag to select or move", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
            else if (hint) Text(
                "Drag to move · tap to click\nHold, then drag to select\nCrown scrolls",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
        }
        Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { Pointer.click(ctx, HidMouse.RIGHT) }, modifier = Modifier.padding(0.dp), label = { Text("Right") })
                FilledTonalIconButton(onClick = { ctx.startActivity(Intent(ctx, ComputerKeysActivity::class.java)) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                }
            }
        }
    }
}

// =================================================================== keyboard

class ComputerKeysActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { AppScaffold { Keys() } } }
    }
}

@Composable
private fun Keys() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val listState = rememberScalingLazyListState()
    fun send(enter: Boolean) {
        if (text.isNotEmpty()) Pointer.type(ctx, text)
        if (enter) Pointer.key(ctx, Keymap.ENTER)
        text = ""
    }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Type on computer", color = colors.primary) } }
            item {
                Box(Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(20.dp)).padding(12.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(false) }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text("Tap to type…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            inner()
                        },
                    )
                }
            }
            item {
                Button(onClick = { send(false) }, enabled = text.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) }, label = { Text("Send") })
            }
            item {
                FilledTonalButton(onClick = { send(true) }, modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = null) }, label = { Text(if (text.isEmpty()) "Enter" else "Send + Enter") })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(onClick = { Pointer.key(ctx, Keymap.BACKSPACE) }) { Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace") }
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.TAB) }, label = { Text("Tab") })
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.ESCAPE) }, label = { Text("Esc") })
                }
            }
            item { ListHeader { Text("Arrows") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.LEFT) }, label = { Text("←") })
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.UP) }, label = { Text("↑") })
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.DOWN) }, label = { Text("↓") })
                    FilledTonalButton(onClick = { Pointer.key(ctx, Keymap.RIGHT) }, label = { Text("→") })
                }
            }
            item { ListHeader { Text("Shortcuts") } }
            item { Shortcut("Copy", Keymap.CTRL, 0x06) }
            item { Shortcut("Paste", Keymap.CTRL, 0x19) }
            item { Shortcut("Undo", Keymap.CTRL, 0x1D) }
            item { Shortcut("Select all", Keymap.CTRL, 0x04) }
            item { Shortcut("Switch window (Alt+Tab)", Keymap.ALT, Keymap.TAB) }
        }
    }
}

@Composable
private fun Shortcut(label: String, mods: Int, code: Int) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { Pointer.key(ctx, code, mods) }, modifier = Modifier.fillMaxWidth(), label = { Text(label) })
}
