package com.syndesia.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.color.utilities.CorePalette
import com.google.android.material.color.utilities.TonalPalette

// Last-resort palette if tonal generation ever fails.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B6A3F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAEF2BA),
    onPrimaryContainer = Color(0xFF00210D),
    inversePrimary = Color(0xFF93D69F),
    secondary = Color(0xFF506352),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D3),
    onSecondaryContainer = Color(0xFF0E1F12),
    tertiary = Color(0xFF3A646F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEE9F6),
    onTertiaryContainer = Color(0xFF001F26),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    surfaceTint = Color(0xFF2B6A3F),
    inverseSurface = Color(0xFF2D322D),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF717970),
    outlineVariant = Color(0xFFC1C9BF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceDim = Color(0xFFD7DBD4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAEFE7),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93D69F),
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF0F5229),
    onPrimaryContainer = Color(0xFFAEF2BA),
    inversePrimary = Color(0xFF2B6A3F),
    secondary = Color(0xFFB7CCB7),
    onSecondary = Color(0xFF233526),
    secondaryContainer = Color(0xFF394B3B),
    onSecondaryContainer = Color(0xFFD3E8D3),
    tertiary = Color(0xFFA2CDDA),
    onTertiary = Color(0xFF03363F),
    tertiaryContainer = Color(0xFF214C56),
    onTertiaryContainer = Color(0xFFBEE9F6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BF),
    surfaceTint = Color(0xFF93D69F),
    inverseSurface = Color(0xFFDFE4DC),
    inverseOnSurface = Color(0xFF2D322D),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A35),
    surfaceDim = Color(0xFF101510),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313631),
)

private val Tabular = TextStyle(fontFeatureSettings = "tnum")
private val base = Typography()

val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-2).sp).merge(Tabular),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp).merge(Tabular),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp).merge(Tabular),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp).merge(Tabular),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold).merge(Tabular),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold).merge(Tabular),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold).merge(Tabular),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold).merge(Tabular),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

const val DEFAULT_SEED = 0xFF5B5BD6.toInt()

/** Accent presets shown in Settings. 0 = wallpaper (Material You). */
val AccentPresets: List<Pair<String, Int>> = listOf(
    "Wallpaper" to 0,
    "Iris" to DEFAULT_SEED,
    "Emerald" to 0xFF2E8B57.toInt(),
    "Teal" to 0xFF00897B.toInt(),
    "Ocean" to 0xFF1E88E5.toInt(),
    "Indigo" to 0xFF3F51B5.toInt(),
    "Violet" to 0xFF7E57C2.toInt(),
    "Rose" to 0xFFD81B60.toInt(),
    "Crimson" to 0xFFE53935.toInt(),
    "Sunset" to 0xFFF4511E.toInt(),
    "Amber" to 0xFFFFB300.toInt(),
    "Slate" to 0xFF607D8B.toInt(),
)

/** Full Material 3 color scheme generated from one seed color (tonal-spot style). */
fun schemeFromSeed(seed: Int, dark: Boolean): ColorScheme {
    val p = CorePalette.of(seed)
    fun c(palette: TonalPalette, tone: Int) = Color(palette.tone(tone))
    return if (!dark) lightColorScheme(
        primary = c(p.a1, 40), onPrimary = c(p.a1, 100),
        primaryContainer = c(p.a1, 90), onPrimaryContainer = c(p.a1, 10),
        inversePrimary = c(p.a1, 80),
        secondary = c(p.a2, 40), onSecondary = c(p.a2, 100),
        secondaryContainer = c(p.a2, 90), onSecondaryContainer = c(p.a2, 10),
        tertiary = c(p.a3, 40), onTertiary = c(p.a3, 100),
        tertiaryContainer = c(p.a3, 90), onTertiaryContainer = c(p.a3, 10),
        error = c(p.error, 40), onError = c(p.error, 100),
        errorContainer = c(p.error, 90), onErrorContainer = c(p.error, 10),
        background = c(p.n1, 98), onBackground = c(p.n1, 10),
        surface = c(p.n1, 98), onSurface = c(p.n1, 10),
        surfaceVariant = c(p.n2, 90), onSurfaceVariant = c(p.n2, 30),
        surfaceTint = c(p.a1, 40),
        inverseSurface = c(p.n1, 20), inverseOnSurface = c(p.n1, 95),
        outline = c(p.n2, 50), outlineVariant = c(p.n2, 80),
        scrim = Color.Black,
        surfaceBright = c(p.n1, 98), surfaceDim = c(p.n1, 87),
        surfaceContainerLowest = c(p.n1, 100), surfaceContainerLow = c(p.n1, 96),
        surfaceContainer = c(p.n1, 94), surfaceContainerHigh = c(p.n1, 92),
        surfaceContainerHighest = c(p.n1, 90),
    ) else darkColorScheme(
        primary = c(p.a1, 80), onPrimary = c(p.a1, 20),
        primaryContainer = c(p.a1, 30), onPrimaryContainer = c(p.a1, 90),
        inversePrimary = c(p.a1, 40),
        secondary = c(p.a2, 80), onSecondary = c(p.a2, 20),
        secondaryContainer = c(p.a2, 30), onSecondaryContainer = c(p.a2, 90),
        tertiary = c(p.a3, 80), onTertiary = c(p.a3, 20),
        tertiaryContainer = c(p.a3, 30), onTertiaryContainer = c(p.a3, 90),
        error = c(p.error, 80), onError = c(p.error, 20),
        errorContainer = c(p.error, 30), onErrorContainer = c(p.error, 90),
        background = c(p.n1, 6), onBackground = c(p.n1, 90),
        surface = c(p.n1, 6), onSurface = c(p.n1, 90),
        surfaceVariant = c(p.n2, 30), onSurfaceVariant = c(p.n2, 80),
        surfaceTint = c(p.a1, 80),
        inverseSurface = c(p.n1, 90), inverseOnSurface = c(p.n1, 20),
        outline = c(p.n2, 60), outlineVariant = c(p.n2, 30),
        scrim = Color.Black,
        surfaceBright = c(p.n1, 24), surfaceDim = c(p.n1, 6),
        surfaceContainerLowest = c(p.n1, 4), surfaceContainerLow = c(p.n1, 10),
        surfaceContainer = c(p.n1, 12), surfaceContainerHigh = c(p.n1, 17),
        surfaceContainerHighest = c(p.n1, 22),
    )
}

/** Base scheme for an accent choice (no pure-black / glass adjustments). */
fun baseScheme(context: Context, accent: Int, dark: Boolean): ColorScheme = when {
    accent == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    else -> try {
        schemeFromSeed(if (accent == 0) DEFAULT_SEED else accent, dark)
    } catch (e: Throwable) {
        if (dark) DarkColors else LightColors
    }
}

/** Frosted surfaces so an animated background glows through cards and bars. */
private fun ColorScheme.frosted(dark: Boolean): ColorScheme {
    val a = if (dark) 0.72f else 0.84f
    return copy(
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = a),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = a),
        surfaceContainer = surfaceContainer.copy(alpha = a + 0.04f),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = a + 0.08f),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = a + 0.12f),
    )
}

/** Same look as Bataria and Katharia: wallpaper colours (or the indigo seed), bold type, round shapes, frosted cards over the aurora. */
@Composable
fun SyndesiaTheme(
    dark: Boolean,
    accent: Int = 0,
    background: String = "aurora",
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var colors = remember(accent, dark, context) { baseScheme(context, accent, dark) }
    if (dark && pureBlack) {
        colors = colors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0B),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF232323),
        )
    }
    if (background != "none") colors = colors.frosted(dark)
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
