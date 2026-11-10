package com.syndesia.app.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syndesia.app.ui.theme.AccentPresets
import com.syndesia.app.ui.theme.DEFAULT_SEED
import com.syndesia.app.ui.theme.baseScheme

// Accent and background pickers, shared with Bataria.

@Composable
fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    val presets = AccentPresets.filter { it.second != 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val selectedKey = if (selected == 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) DEFAULT_SEED else selected
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(presets.size) { i ->
            val (name, seed) = presets[i]
            AccentSwatch(name, seed, seed == selectedKey) { onSelect(seed) }
        }
    }
}

@Composable
fun AccentSwatch(name: String, seed: Int, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val scheme = remember(seed, dark) { baseScheme(context, seed, dark) }
    val ring by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, label = "ring",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(68.dp)
            .pressScale(interaction, 0.9f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(60.dp)
                .border(2.5.dp, ring, CircleShape)
                .padding(5.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(scheme.primary, 180f, 180f, useCenter = true)
                drawArc(scheme.primaryContainer, 90f, 90f, useCenter = true)
                drawArc(scheme.tertiaryContainer, 0f, 90f, useCenter = true)
            }
            if (seed == 0) {
                Icon(
                    Icons.Rounded.Wallpaper, contentDescription = null, tint = scheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            } else if (selected) {
                Icon(
                    Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun BackgroundPicker(selected: String, onSelect: (String) -> Unit) {
    val styles = BackgroundStyle.entries
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(styles.size) { i ->
            val st = styles[i]
            BackgroundTile(st, st.key == selected) { onSelect(st.key) }
        }
    }
}

@Composable
fun BackgroundTile(style: BackgroundStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val borderColor by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "tileBorder")
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(96.dp)
            .pressScale(interaction, 0.92f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(96.dp)
                .height(132.dp)
                .clip(shape)
                .background(colors.surface)
                .border(if (selected) 3.dp else 1.dp, borderColor, shape),
        ) {
            AnimatedBackground(style.key, Modifier.fillMaxSize(), preview = true)
            if (style == BackgroundStyle.NONE) {
                Icon(
                    Icons.Rounded.Block, contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle, contentDescription = "Selected",
                    tint = colors.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
                )
            }
        }
        Text(
            style.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
