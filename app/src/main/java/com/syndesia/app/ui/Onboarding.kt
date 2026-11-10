package com.syndesia.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syndesia.app.Appearance
import com.syndesia.app.Look
import kotlinx.coroutines.launch

private const val PAGES = 6

/** First-launch tour, in the same style as Bataria and Katharia: what it is, watch setup, then looks. */
@Composable
fun OnboardingScreen(appearance: Appearance, onFinish: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        AnimatedBackground(appearance.background, Modifier.fillMaxSize())
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Step ${pager.currentPage + 1} of $PAGES",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                    if (pager.currentPage < PAGES - 1) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage(PAGES - 1) } }) { Text("Skip") }
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        when (page) {
                            0 -> WelcomePage()
                            1 -> FeaturesPage()
                            2 -> WatchSetupPage()
                            3 -> PairPage()
                            4 -> LookPage(appearance)
                            else -> DonePage()
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(PAGES) { i ->
                            val selected = i == pager.currentPage
                            val w by animateDpAsState(if (selected) 26.dp else 8.dp, label = "dot")
                            val c by animateColorAsState(
                                if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f), label = "dotColor",
                            )
                            Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(c))
                        }
                    }
                    if (pager.currentPage > 0 && pager.currentPage < PAGES - 1) {
                        OutlinedButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                    }
                    val last = pager.currentPage == PAGES - 1
                    Button(onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(
                            when {
                                last -> "Start"
                                pager.currentPage == 0 -> "Let's go"
                                else -> "Next"
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pages

@Composable
private fun PageTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(vertical = 8.dp), content = content) }
}

@Composable
private fun WelcomePage() {
    val colors = MaterialTheme.colorScheme
    val demo = rememberInfiniteTransition(label = "demo")
    val level by demo.animateFloat(
        initialValue = 0.1f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "demoLevel",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        LinkOrb(level = level, spinning = true, modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) {
            Icon(Icons.Rounded.Watch, null, tint = colors.onSurface, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Welcome to", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
        Text(
            "Syndesia",
            fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp,
            color = colors.primary,
        )
        Text("σύνδεση · Greek for “connection”", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(
            "Install apps on your Wear OS watch and move files to and from it, straight from your phone. No computer needed.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("Setup takes about two minutes, once.", style = MaterialTheme.typography.labelLarge, color = colors.primary)
    }
}

@Composable
private fun FeaturesPage() {
    PageTitle("What you get", "Everything adb does for a watch, with buttons instead of a terminal.")
    val features = listOf(
        Triple(Icons.Rounded.InstallMobile, "Install any APK", "Single APKs, split APKs and .apks / .xapk bundles, with progress."),
        Triple(Icons.Rounded.Share, "Share to install", "Open or share an APK from any app and pick Syndesia."),
        Triple(Icons.Rounded.Folder, "File browser", "Send music, pictures or anything else to the watch, and save files back."),
        Triple(Icons.Rounded.Apps, "Watch apps", "Open, stop, disable or uninstall apps, and save any app's APK."),
        Triple(Icons.Rounded.Screenshot, "Screenshots", "Grab the watch screen and save or share it."),
        Triple(Icons.Rounded.Wifi, "Finds your watch", "Spots watches on your Wi-Fi and reconnects by itself, even when the port changes."),
    )
    features.forEachIndexed { i, (icon, title, body) ->
        Entrance(i) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, MaterialTheme.colorScheme.primary, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WatchSetupPage() {
    PageTitle(
        "Get the watch ready",
        "Syndesia talks to the watch over Wireless debugging, the same way a computer with adb would. Do this once on the watch:",
    )
    GlassCard {
        GuideStep("1", "Unlock Developer options", "Settings › System › About › Versions (Software info on some watches). Tap Build number 7 times.")
        GuideStep("2", "Join your Wi-Fi", "Settings › Connectivity › Wi-Fi. Same network as this phone.")
        GuideStep("3", "Turn on debugging", "Settings › Developer options › ADB debugging, then Wireless debugging. Older Wear OS 2 watches: Debug over Wi-Fi.")
    }
    Spacer(Modifier.height(16.dp))
    GlassCard {
        Tip(Icons.Rounded.Bluetooth, "Watches often switch Wi-Fi off while they're connected to the phone by Bluetooth. If Syndesia can't find yours, turn Bluetooth off on the watch for a moment.")
    }
}

@Composable
private fun PairPage() {
    PageTitle("Pair once", "Android asks for a one-time code so only your phone can connect.")
    GlassCard {
        GuideStep("1", "Pair new device", "On the watch, in Wireless debugging, tap Pair new device. A six-digit code appears.")
        GuideStep("2", "Tap Pair in Syndesia", "The watch usually shows up under On this Wi-Fi. Tap Pair and type the code.")
        GuideStep("3", "That's it", "Syndesia connects and remembers the watch. Next time it reconnects by itself.")
    }
    Spacer(Modifier.height(16.dp))
    GlassCard {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.VerifiedUser, MaterialTheme.colorScheme.primary, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                "The connection stays on your Wi-Fi between your phone and your watch. Syndesia has no servers and sends nothing anywhere else.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LookPage(appearance: Appearance) {
    val ctx = LocalContext.current
    PageTitle("Make it yours", "Pick a color and a background. Change them any time in Settings.")
    GlassCard {
        Column(Modifier.padding(horizontal = 16.dp)) {
            FieldLabel("Theme", top = 8.dp)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                modes.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = appearance.themeMode == k,
                        onClick = { Look.update(ctx, appearance.copy(themeMode = k)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    ) { Text(label) }
                }
            }
        }
        PureBlackRow(appearance.pureBlack) { Look.update(ctx, appearance.copy(pureBlack = it)) }
        Column(Modifier.padding(horizontal = 16.dp)) { FieldLabel("Accent color", top = 8.dp) }
        AccentPicker(selected = appearance.accent) { Look.update(ctx, appearance.copy(accent = it)) }
        Column(Modifier.padding(horizontal = 16.dp)) { FieldLabel("Animated background", top = 18.dp) }
        BackgroundPicker(selected = appearance.background) { Look.update(ctx, appearance.copy(background = it)) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DonePage() {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "done")
    val scale by pulse.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "doneScale")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.size(150.dp).clip(CircleShape).background(colors.primaryContainer)
                .border(3.dp, colors.primary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = colors.primary, modifier = Modifier.size((84 * scale).dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("You're all set", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn on Wireless debugging on the watch, then pair it from the Watch tab.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        GlassCard {
            Tip(Icons.Rounded.Share, "Share an APK from any app to Syndesia to install it on the watch.")
            Tip(Icons.Rounded.Folder, "Files › Send files here puts music, pictures or anything else on the watch.")
            Tip(Icons.Rounded.Palette, "Replay this tour any time from Settings.")
        }
    }
}
