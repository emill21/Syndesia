package com.syndesia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syndesia.app.adb.Found

private fun validHost(h: String) = h.trim().matches(Regex("[A-Za-z0-9.:%-]+")) && h.trim().isNotEmpty()
private fun validPort(p: String) = p.trim().toIntOrNull()?.let { it in 1..65535 } == true

/** Splits "192.168.1.20:37099" pasted into the address box. */
private fun splitAddress(text: String): Pair<String, String>? {
    val t = text.trim()
    val i = t.lastIndexOf(':')
    if (i <= 0 || t.count { it == ':' } != 1) return null
    return t.substring(0, i) to t.substring(i + 1)
}

/**
 * Pair with the code from the watch's Settings › Developer options › Wireless debugging ›
 * Pair new device. That screen shows its own IP:port just for pairing.
 */
@Composable
fun PairDialog(st: SyndesiaState, req: AddressRequest, found: List<Found>) {
    var host by remember { mutableStateOf(req.host) }
    var port by remember { mutableStateOf(req.port) }
    var code by remember { mutableStateOf("") }
    // Fill the pairing port in as soon as mDNS sees the "Pair new device" screen.
    LaunchedEffect(found, host) {
        val f = found.firstOrNull { it.host == host.trim() && it.pairPort != null }
            ?: found.singleOrNull { it.pairPort != null }.takeIf { host.isBlank() }
        if (f != null) {
            if (host.isBlank()) host = f.host
            if (port.isBlank() || port != f.pairPort.toString()) port = f.pairPort.toString()
        }
    }
    val ok = validHost(host) && validPort(port) && code.trim().length == 6 && code.trim().all { it.isDigit() }
    AlertDialog(
        onDismissRequest = { if (!st.pairing) { st.pairRequest = null; st.pairError = null } },
        icon = { Icon(Icons.Rounded.Password, null) },
        title = { Text("Pair a watch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "On the watch: Settings › Developer options › Wireless debugging › Pair new device. " +
                        "Enter the code and the IP address & port shown there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Six-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { v -> splitAddress(v)?.let { (h, p) -> host = h; port = p } ?: run { host = v } },
                        label = { Text("IP address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1.6f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                st.pairError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (st.pairing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Pairing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = ok && !st.pairing, onClick = {
                val connectPort = found.firstOrNull { it.host == host.trim() }?.connectPort
                st.pair(host.trim(), port.trim().toInt(), code.trim(), connectPort)
            }) { Text("Pair") }
        },
        dismissButton = {
            TextButton(enabled = !st.pairing, onClick = { st.pairRequest = null; st.pairError = null }) { Text("Cancel") }
        },
    )
}

/** Connect to IP:port by hand (the address on the main Wireless debugging screen, or :5555 for older watches). */
@Composable
fun ConnectDialog(st: SyndesiaState, req: AddressRequest) {
    var host by remember { mutableStateOf(req.host) }
    var port by remember { mutableStateOf(req.port) }
    val ok = validHost(host) && validPort(port)
    AlertDialog(
        onDismissRequest = { st.connectRequest = null },
        icon = { Icon(Icons.Rounded.Link, null) },
        title = { Text("Connect by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Use the IP address & port at the top of the watch's Wireless debugging screen. " +
                        "Older Wear OS 2 watches with \"Debug over Wi-Fi\" use port 5555.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { v -> splitAddress(v)?.let { (h, p) -> host = h; port = p } ?: run { host = v } },
                    label = { Text("IP address (or paste IP:port)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            Button(enabled = ok, onClick = {
                st.connectRequest = null
                st.connect(host.trim(), port.trim().toInt())
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = { st.connectRequest = null }) { Text("Cancel") } },
    )
}

@Composable
fun NoticeDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, null) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Yes/no confirmation for destructive actions. */
@Composable
fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Single text field prompt (new folder, rename, shell command). */
@Composable
fun TextPromptDialog(title: String, label: String, initial: String, action: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(enabled = text.isNotBlank() && !text.contains('/'), onClick = { onDismiss(); onDone(text.trim()) }) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
