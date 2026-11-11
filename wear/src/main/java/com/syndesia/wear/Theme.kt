package com.syndesia.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Syndesia's indigo ("Iris") on black, Material 3 Expressive for Wear OS. */
private val SyndesiaWearColors = ColorScheme(
    primary = Color(0xFFC3C1FF),
    primaryDim = Color(0xFFA9A6F2),
    primaryContainer = Color(0xFF3F3F9E),
    onPrimary = Color(0xFF1E1D6B),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFFC6C4DD),
    secondaryContainer = Color(0xFF2F2F45),
    onSecondaryContainer = Color(0xFFE3E0F9),
    tertiary = Color(0xFFE9B9D4),
    tertiaryContainer = Color(0xFF5B3A51),
    onTertiaryContainer = Color(0xFFFFD8EC),
    surfaceContainerLow = Color(0xFF16161F),
    surfaceContainer = Color(0xFF1D1D29),
    surfaceContainerHigh = Color(0xFF282836),
    onSurface = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFFC8C5D3),
    outline = Color(0xFF928F9E),
    outlineVariant = Color(0xFF474553),
    background = Color.Black,
    onBackground = Color(0xFFE5E1EC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun SyndesiaWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SyndesiaWearColors, content = content)
}
