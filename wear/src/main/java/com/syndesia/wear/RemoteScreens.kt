package com.syndesia.wear

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Base for remote screens: listen for the phone's replies while visible. */
abstract class RemoteScreenActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        Remote.open(this)
    }

    override fun onStop() {
        Remote.close()
        super.onStop()
    }
}

// =================================================================== control panel

class RemoteActivity : RemoteScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { AppScaffold { Panel() } } }
    }
}

@Composable
private fun Panel() {
    val ctx = LocalContext.current
    val link by Remote.link.collectAsStateWithLifecycle()
    val torch by Remote.torch.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val colors = MaterialTheme.colorScheme
    fun g(action: String) = Remote.send(ctx, "global", JSONObject().put("action", action))

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Phone remote", color = colors.primary) } }
            if (link == Remote.Link.Off) item {
                Card(onClick = { Remote.ping(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Phone isn't answering", style = MaterialTheme.typography.titleSmall, color = colors.error)
                    Text(
                        "On your phone: Syndesia › Settings › Turn on the watch remote. Tap to check again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { g("back") }
                    RoundButton(Icons.Rounded.Home, "Home", filled = true) { g("home") }
                    RoundButton(Icons.Rounded.CropSquare, "Recent apps") { g("recents") }
                }
            }
            item {
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, MouseActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Mouse, contentDescription = null) },
                    secondaryLabel = { Text("Touchpad · crown scrolls") },
                    label = { Text("Mouse") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { ctx.startActivity(Intent(ctx, AppsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                    label = { Text("Open an app") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { ctx.startActivity(Intent(ctx, TypeActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                    secondaryLabel = { Text("Into the focused text box") },
                    label = { Text("Type") },
                )
            }
            item { ListHeader { Text("Media") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundButton(Icons.Rounded.SkipPrevious, "Previous") { Remote.send(ctx, "media", JSONObject().put("key", "previous")) }
                    RoundButton(Icons.Rounded.PlayArrow, "Play or pause", filled = true) { Remote.send(ctx, "media", JSONObject().put("key", "play_pause")) }
                    RoundButton(Icons.Rounded.SkipNext, "Next") { Remote.send(ctx, "media", JSONObject().put("key", "next")) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundButton(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down") { Remote.send(ctx, "volume", JSONObject().put("dir", "down")) }
                    RoundButton(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { Remote.send(ctx, "volume", JSONObject().put("dir", "mute")) }
                    RoundButton(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up") { Remote.send(ctx, "volume", JSONObject().put("dir", "up")) }
                }
            }
            item { ListHeader { Text("Phone") } }
            item { Action(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, if (torch) "Torch off" else "Torch on") { Remote.send(ctx, "torch", JSONObject().put("on", !torch)) } }
            item { Action(Icons.Rounded.Notifications, "Notifications") { g("notifications") } }
            item { Action(Icons.Rounded.Tune, "Quick settings", "Wi-Fi, Bluetooth and more") { g("quick_settings") } }
            item { Action(Icons.Rounded.Screenshot, "Screenshot") { g("screenshot") } }
            item { Action(Icons.Rounded.ViewAgenda, "Split screen") { g("split") } }
            item { Action(Icons.Rounded.Lock, "Lock phone") { g("lock") } }
            item { Action(Icons.Rounded.PowerSettingsNew, "Power menu") { g("power") } }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, filled: Boolean = false, onClick: () -> Unit) {
    if (filled) FilledIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, contentDescription = label) }
    else FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = label) }
}

@Composable
private fun Action(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(icon, contentDescription = null) },
        secondaryLabel = if (sub != null) { { Text(sub) } } else null,
        label = { Text(label) },
    )
}

// =================================================================== mouse

/** Touchpad. Swipe-to-dismiss is off for this screen (it would fight the drags); use the X to leave. */
class MouseActivity : RemoteScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { Touchpad(onClose = { finish() }) } }
    }

    override fun onStart() {
        super.onStart()
        Remote.send(this, "cursor", JSONObject().put("on", true))
    }

    override fun onStop() {
        Remote.send(this, "cursor", JSONObject().put("on", false))
        super.onStop()
    }
}

@Composable
private fun Touchpad(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var padW by remember { mutableFloatStateOf(1f) }
    // Moves and crown turns are collected here and sent ~50 times a second.
    val pending = remember { floatArrayOf(0f, 0f, 0f) } // dx, dy, wheel
    var hint by remember { mutableStateOf(true) }
    var holding by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        while (true) {
            delay(20)
            val (move, wheel) = synchronized(pending) {
                val r = Triple(pending[0], pending[1], pending[2])
                pending[0] = 0f; pending[1] = 0f; pending[2] = 0f
                (r.first to r.second) to r.third
            }
            if (move.first != 0f || move.second != 0f) {
                Remote.send(ctx, "move", JSONObject().put("dx", (move.first / padW).toDouble()).put("dy", (move.second / padW).toDouble()))
            }
            if (wheel != 0f) Remote.send(ctx, "wheel", JSONObject().put("dy", (wheel / padW).toDouble()))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged { padW = it.width.toFloat().coerceAtLeast(1f) }
            .onRotaryScrollEvent { e ->
                // Crown = scroll wheel: one smooth, continuous scroll at the cursor.
                synchronized(pending) { pending[2] += e.verticalScrollPixels }
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
                        // Wake up at the hold deadline even if the finger doesn't move.
                        val event = if (!moved && !held) {
                            withTimeoutOrNull((holdAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
                        } else awaitPointerEvent()
                        if (event == null) {
                            // Still down, didn't move: press and hold on the phone (long-press, then drag).
                            if (!moved && !held) {
                                held = true
                                holding = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                Remote.send(ctx, "press")
                            }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (held) Remote.send(ctx, "release")
                            else if (!moved) Remote.send(ctx, "click")
                            holding = false
                            break
                        }
                        val delta = change.position - last
                        if (!moved && !held && (change.position - down.position).getDistance() > slop) moved = true
                        if (moved || held) {
                            synchronized(pending) { pending[0] += delta.x; pending[1] += delta.y }
                            last = change.position
                            change.consume()
                        }
                        if (!moved && !held && change.uptimeMillis >= holdAt) {
                            held = true
                            holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            Remote.send(ctx, "press")
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Touch surface; lights up while holding (dragging / selecting).
        Box(
            Modifier.fillMaxSize().padding(10.dp)
                .background(if (holding) colors.primaryContainer else colors.surfaceContainer, CircleShape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mouse, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            if (holding) Text("Holding · drag to select or move", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
            else if (hint) Text(
                "Drag to move · tap to click\nHold, then drag to select or move\nTurn the crown to scroll",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        // Close (top) and Back / Home (bottom)
        Box(Modifier.fillMaxSize().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
        }
        Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { Remote.send(ctx, "global", JSONObject().put("action", "back")) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                FilledTonalIconButton(onClick = { Remote.send(ctx, "global", JSONObject().put("action", "home")) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Home, contentDescription = "Home")
                }
            }
        }
    }
}

// =================================================================== apps

class AppsActivity : RemoteScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { AppScaffold { AppList() } } }
    }
}

@Composable
private fun AppList() {
    val ctx = LocalContext.current
    val apps by Remote.apps.collectAsStateWithLifecycle()
    val link by Remote.link.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Remote.loadApps(ctx) }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Open on phone", color = MaterialTheme.colorScheme.primary) } }
            val list = apps
            when {
                list == null && link == Remote.Link.Off -> item {
                    Text("Phone isn't answering. Turn on the watch remote in Syndesia on your phone.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                list == null -> item { Text("Loading apps…", style = MaterialTheme.typography.bodySmall) }
                else -> items(list, key = { it.pkg }) { a ->
                    FilledTonalButton(
                        onClick = {
                            Remote.send(ctx, "launch", JSONObject().put("pkg", a.pkg))
                            Toast.makeText(ctx, "Opening ${a.label}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(a.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

// =================================================================== typing

class TypeActivity : RemoteScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyndesiaWearTheme { AppScaffold { Typer() } } }
    }
}

@Composable
private fun Typer() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val typed by Remote.typed.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val listState = rememberScalingLazyListState()
    fun send(enter: Boolean) {
        Remote.type(ctx, text, enter)
        text = ""
    }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Type on phone", color = colors.primary) } }
            item {
                Box(
                    Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(20.dp)).padding(12.dp),
                ) {
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
                Button(
                    onClick = { send(false) },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) },
                    label = { Text("Send") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { send(true) },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = null) },
                    label = { Text("Send + Enter") },
                )
            }
            item {
                OutlinedButton(
                    onClick = { Remote.send(ctx, "backspace") },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null) },
                    label = { Text("Delete a letter") },
                )
            }
            if (typed == false) item {
                Text(
                    "No text box is selected on the phone. Tap one with the mouse first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
