package com.syndesia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.syndesia.app.Release

/** "What's new" after an update (latest only) or the full changelog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogSheet(releases: List<Release>, latestOnly: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shown = if (latestOnly) releases.take(1) else releases
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow.copy(alpha = 1f),
        // Set explicitly: with Syndesia's see-through card colours, Android can't work out the text colour
        // from the background and falls back to black — unreadable in dark mode.
        contentColor = colors.onSurface,
    ) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 40.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.AutoAwesome, colors.primary, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(if (latestOnly) "What's new" else "Changelog", style = MaterialTheme.typography.headlineMedium)
                        if (latestOnly && shown.isNotEmpty()) {
                            Text(
                                "Syndesia ${shown[0].version}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            items(shown.size) { i ->
                ReleaseBlock(shown[i], highlight = i == 0)
                if (i < shown.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
                }
            }
            if (latestOnly) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Got it") }
                }
            }
        }
    }
}

@Composable
private fun ReleaseBlock(r: Release, highlight: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "v${r.version}",
                style = MaterialTheme.typography.labelLarge,
                color = if (highlight) colors.onPrimary else colors.onSecondaryContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (highlight) colors.primary else colors.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(r.date, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text(r.title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.items.forEach { item ->
                Row {
                    Box(
                        Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape)
                            .background(if (highlight) colors.primary else colors.outline)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
