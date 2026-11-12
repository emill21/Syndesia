package com.syndesia.app.ui

import android.content.Intent
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.HidRelayService
import com.syndesia.hid.HidMouse
import android.provider.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.syndesia.app.RemoteService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syndesia.app.Appearance
import com.syndesia.app.BuildConfig
import com.syndesia.app.Look
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Keys

@Composable
fun SettingsScreen(
    st: SyndesiaState,
    look: Appearance,
    padding: PaddingValues,
    onReplayTour: () -> Unit,
    onChangelog: () -> Unit,
) {
    val ctx = LocalContext.current
    fun update(a: Appearance) = Look.update(ctx, a)
    var confirmReset by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = padding.withPage(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        animatedItem(0) { SectionHeader("Appearance") }
        animatedItem(0) {
            SectionCard {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FieldLabel("Theme")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                        modes.forEachIndexed { i, (k, label) ->
                            SegmentedButton(
                                selected = look.themeMode == k,
                                onClick = { update(look.copy(themeMode = k)) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                            ) { Text(label) }
                        }
                    }
                }
                PureBlackRow(look.pureBlack) { update(look.copy(pureBlack = it)) }
                Column(Modifier.padding(horizontal = 16.dp)) { FieldLabel("Accent color") }
                AccentPicker(selected = look.accent) { update(look.copy(accent = it)) }
                Column(Modifier.padding(horizontal = 16.dp)) { FieldLabel("Animated background") }
                BackgroundPicker(selected = look.background) { update(look.copy(background = it)) }
            }
        }

        animatedItem(1) { SectionHeader("Connection") }
        animatedItem(1) {
            SectionCard {
                SwitchRow(
                    Icons.Rounded.Link, "Reconnect automatically",
                    "Connect to your last watch when Syndesia opens, and find it again when its port changes.",
                    st.autoConnect,
                ) { st.setAuto(it) }
                LinkRow(Icons.Rounded.Password, "Pair a new watch", "Use the six-digit code from Wireless debugging") {
                    st.pairRequest = AddressRequest()
                }
                LinkRow(Icons.Rounded.Watch, "Forget saved watches", "${st.saved.size} saved") { confirmForget = true }
                LinkRow(Icons.Rounded.Key, "Reset this phone's ADB key", "Every watch will need pairing again", MaterialTheme.colorScheme.error) {
                    confirmReset = true
                }
            }
        }

        animatedItem(2) { SectionHeader("Watch remote") }
        animatedItem(2) {
            var on by remember { mutableStateOf(RemoteService.isOn()) }
            LifecycleResumeEffect(Unit) {
                on = RemoteService.isOn()
                onPauseOrDispose { }
            }
            SectionCard {
                LinkRow(
                    Icons.Rounded.SettingsRemote,
                    if (on) "Watch remote is on" else "Turn on the watch remote",
                    if (on) "Open Remote in Syndesia on your watch to tap, scroll and press buttons on this phone."
                    else "Settings › Accessibility › Syndesia watch remote. If Android says it's a restricted setting: App info › ⋮ › Allow restricted settings, then try again.",
                ) {
                    ctx.openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        animatedItem(2) { SectionHeader("Computer mouse") }
        animatedItem(2) { ComputerMouseCard() }

        animatedItem(2) { SectionHeader("Installing") }
        animatedItem(2) {
            SectionCard {
                SwitchRow(
                    Icons.Rounded.History, "Allow downgrades",
                    "Install an older version over a newer one (keeps the app's data).",
                    st.allowDowngrade,
                ) { st.setDowngrade(it) }
                SwitchRow(
                    Icons.Rounded.Security, "Grant permissions",
                    "Give the app every permission it asks for at install, so it doesn't prompt on the tiny screen.",
                    st.grantPermissions,
                ) { st.setGrant(it) }
            }
        }

        animatedItem(3) { SectionHeader("About") }
        animatedItem(3) {
            SectionCard {
                InfoRow("Version", "Syndesia ${BuildConfig.VERSION_NAME}", "σύνδεση · Greek for “connection”", icon = Icons.Rounded.Info)
                LinkRow(Icons.AutoMirrored.Rounded.ListAlt, "Changelog", "Everything that's new, version by version", onClick = onChangelog)
                LinkRow(Icons.Rounded.AutoAwesome, "Replay welcome tour", "Walk through watch setup again", onClick = onReplayTour)
                LinkRow(Icons.AutoMirrored.Rounded.OpenInNew, "Source on GitHub", "emill21/Syndesia") {
                    ctx.openSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/emill21/Syndesia")))
                }
            }
        }
    }

    if (confirmForget) ConfirmDialog(
        "Forget all saved watches?", "They'll disappear from the list. Pairing stays valid on the watches themselves.", "Forget",
        onConfirm = { st.forgetAll() }, onDismiss = { confirmForget = false },
    )
    if (confirmReset) ConfirmDialog(
        "Reset the ADB key?",
        "Syndesia makes a new identity. Watches won't recognise this phone any more, so you'll pair each one again.",
        "Reset",
        onConfirm = { Keys.reset(ctx); Adb.resetIdentity() },
        onDismiss = { confirmReset = false },
    )
}

/**
 * Backup for watches that can't be a Bluetooth mouse: this phone becomes the mouse, and the
 * watch's touchpad drives it.
 */
@Composable
private fun ComputerMouseCard() {
    val ctx = LocalContext.current
    val status by HidMouse.status.collectAsStateWithLifecycle()
    val host by HidMouse.host.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(HidRelayService.hasPermission(ctx)) }
    var running by remember { mutableStateOf(HidRelayService.running) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        granted = HidRelayService.hasPermission(ctx)
        running = HidRelayService.running
        refresh++
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val paired = remember(refresh, status, granted) { if (granted) HidMouse.paired(ctx).filter { it.isComputer } else emptyList() }

    SectionCard {
        Text(
            "Your watch can be a Bluetooth mouse for your computer on its own (Computer mouse on the watch). " +
                "If your watch can't, this phone can be the mouse instead and the watch's touchpad drives it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (!granted && Build.VERSION.SDK_INT >= 31) {
            LinkRow(Icons.Rounded.Bluetooth, "Allow Nearby devices", "Needed to act as a Bluetooth mouse") {
                ask.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            }
            return@SectionCard
        }
        val label = when {
            !running -> "Start phone mouse"
            status == HidMouse.Status.Connected -> "Mouse for ${host?.name}"
            status == HidMouse.Status.Unsupported -> "This phone can't be a Bluetooth mouse"
            else -> "Phone mouse is on"
        }
        LinkRow(
            Icons.Rounded.Mouse, label,
            if (running) "Tap to stop" else "Then use Computer mouse › Use your phone on the watch",
        ) {
            if (running) { HidRelayService.stop(ctx); running = false } else running = HidRelayService.start(ctx)
        }
        if (running && status != HidMouse.Status.Connected) {
            paired.forEach { h ->
                LinkRow(Icons.Rounded.Computer, "Connect to ${h.name}", "Paired computer") { HidMouse.connect(ctx, h.address) }
            }
            LinkRow(Icons.Rounded.BluetoothSearching, "Pair a computer", "Makes this phone visible; then add it in the computer's Bluetooth settings") {
                discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
            }
        }
    }
}
