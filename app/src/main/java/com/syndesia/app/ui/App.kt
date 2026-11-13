package com.syndesia.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.syndesia.app.LinkService
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syndesia.app.BuildConfig
import com.syndesia.app.Changelog
import com.syndesia.app.Incoming
import com.syndesia.app.Look
import com.syndesia.app.Prefs
import com.syndesia.app.SavedWatch
import com.syndesia.app.adb.Adb
import com.syndesia.app.adb.Apk
import com.syndesia.app.adb.ConnectSuperseded
import com.syndesia.app.adb.Discovery
import com.syndesia.app.adb.Found
import com.syndesia.app.adb.Helper
import com.syndesia.app.adb.Hotspot
import com.syndesia.app.adb.PortScan
import com.syndesia.app.adb.PortState
import com.syndesia.app.adb.InstallOptions
import com.syndesia.app.adb.InstallStatus
import com.syndesia.app.adb.Installer
import com.syndesia.app.adb.Link
import com.syndesia.app.adb.Transfers
import com.syndesia.app.adb.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class STab(val label: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Watch("Watch", "Syndesia", Icons.Outlined.Watch, Icons.Rounded.Watch),
    Install("Install", "Install", Icons.Outlined.InstallMobile, Icons.Rounded.InstallMobile),
    Files("Files", "Files", Icons.Outlined.Folder, Icons.Rounded.Folder),
    Apps("Apps", "Watch apps", Icons.Outlined.Apps, Icons.Rounded.Apps),
    Settings("Settings", "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

const val STORAGE_ROOT = "/storage/emulated/0"

data class AddressRequest(val host: String = "", val port: String = "")

/** Is this advert the saved watch? Same serial, or (for adverts that don't carry one) same address. */
private fun SavedWatch.isAt(f: Found) = (serial.isNotBlank() && serial == f.serial) || host == f.host

/** Shared app state: saved watches, dialogs, options, and the auto-connect logic. */
@Stable
class SyndesiaState(private val ctx: Context) {
    val prefs = Prefs(ctx)
    private val scope = MainScope()

    var saved by mutableStateOf(prefs.watches); private set
    var autoConnect by mutableStateOf(prefs.autoConnect); private set
    var allowDowngrade by mutableStateOf(prefs.allowDowngrade); private set
    var grantPermissions by mutableStateOf(prefs.grantPermissions); private set
    var showHidden by mutableStateOf(prefs.showHidden); private set
    var showSystemApps by mutableStateOf(prefs.showSystemApps); private set

    var pairRequest by mutableStateOf<AddressRequest?>(null)
    var connectRequest by mutableStateOf<AddressRequest?>(null)
    var pairing by mutableStateOf(false)
    var pairError by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** Set when the watch app is too old for the hotspot: offer to update it over Bluetooth. */
    var offerHelperUpdate by mutableStateOf(false)

    /** Send the bundled watch app to the watch and install it (over Wi-Fi if connected, else Bluetooth). */
    fun updateHelper(onTab: ((STab) -> Unit)? = null) {
        scope.launch {
            val pkg = withContext(Dispatchers.IO) { Helper.bundledApk(ctx)?.let { Apk.fromFile(ctx, it) } }
            if (pkg == null) { notice = "The watch app isn't included in this build"; return@launch }
            Installer.add(listOf(pkg.copy(label = "Syndesia for Wear OS")))
            Installer.start()
            onTab?.invoke(STab.Install)
        }
    }

    /** Progress text while connecting through the phone's hotspot (null when not doing that). */
    var hotspotStage by mutableStateOf<String?>(null)

    /** Syndesia's watch helper is reachable over Bluetooth. */
    var helperNearby by mutableStateOf(false)

    var filesPath by mutableStateOf(STORAGE_ROOT)
    var preparing by mutableStateOf(false)
    var problems by mutableStateOf<List<String>>(emptyList())

    /** What auto-connect is doing right now, for the Watch tab to show (null while it isn't looking). */
    var seeking by mutableStateOf<String?>(null); private set

    /** Watch to look for first, after its link dropped or a connect to it failed. Otherwise: the last one used. */
    private var hunting: SavedWatch? = null

    /** An address where connecting failed lately: how many times running, and when last. */
    private class Miss(val count: Int, val at: Long)

    private val misses = mutableMapOf<String, Miss>()
    private var seeker: Job? = null

    init {
        Installer.options = InstallOptions(allowDowngrade, grantPermissions)
        Installer.restore(ctx)
        Helper.listen(ctx)
        Adb.onReconnecting = { Helper.wakeAsync(ctx, force = true) }
        Adb.onLost = { host, _ ->
            scope.launch {
                hunting = saved.firstOrNull { it.host == host }
                misses.clear()
            }
        }
    }

    fun setAuto(on: Boolean) { autoConnect = on; prefs.autoConnect = on }
    fun setDowngrade(on: Boolean) { allowDowngrade = on; prefs.allowDowngrade = on; Installer.options = InstallOptions(allowDowngrade, grantPermissions) }
    fun setGrant(on: Boolean) { grantPermissions = on; prefs.grantPermissions = on; Installer.options = InstallOptions(allowDowngrade, grantPermissions) }
    fun setHidden(on: Boolean) { showHidden = on; prefs.showHidden = on }
    fun setSystemApps(on: Boolean) { showSystemApps = on; prefs.showSystemApps = on }
    fun forget(w: SavedWatch) {
        prefs.forget(w)
        saved = prefs.watches
        if (hunting?.host == w.host) hunting = null
    }

    fun forgetAll() { prefs.watches = emptyList(); saved = emptyList(); hunting = null }

    /** User-initiated connect. */
    fun connect(host: String, port: Int) {
        misses.clear()
        hunting = null
        scope.launch {
            if (!settle(host, port, Adb.connect(ctx, host, port))) {
                // Maybe the watch's Wi-Fi is asleep: ask the helper (over Bluetooth) to wake it.
                if (!Adb.userDisconnected) Helper.wakeAsync(ctx)
                // Port probably changed: auto-connect goes looking for this watch's new one.
                saved.firstOrNull { it.host == host }?.let { hunting = it }
            }
        }
    }

    /** Bookkeeping after a connect attempt, by hand or automatic. True if it connected. */
    private fun settle(host: String, port: Int, r: Result<WatchInfo>): Boolean {
        val info = r.getOrNull()
        if (info != null) {
            prefs.remember(SavedWatch(host, port, info.model, info.serial, System.currentTimeMillis()))
            saved = prefs.watches
            hunting = null
            misses.clear()
            if (Installer.items.value.any { it.status == InstallStatus.Queued }) Installer.start()
            return true
        }
        // Remember the miss, so auto-connect gives this address a rest instead of hammering it.
        if (r.exceptionOrNull() !is ConnectSuperseded) {
            val k = "$host:$port"
            misses[k] = Miss((misses[k]?.count ?: 0) + 1, SystemClock.elapsedRealtime())
        }
        return false
    }

    /** The link is up, perhaps after it came back by itself on a new port: keep the saved address current. */
    fun noteConnected(info: WatchInfo) {
        val w = saved.firstOrNull()
        if (w != null && w.host == info.host && w.port == info.port && w.serial == info.serial) return
        prefs.remember(SavedWatch(info.host, info.port, info.model, info.serial, System.currentTimeMillis()))
        saved = prefs.watches
    }

    fun disconnect() {
        hunting = null
        Adb.disconnect()
        Hotspot.stop()
    }

    /**
     * Switch the watch's ADB to the classic port 5555 (like `adb tcpip 5555`). That mode doesn't ask
     * "Allow wireless debugging on this network?", so hotspot connections go straight through.
     * Lasts until the watch restarts.
     */
    fun enableClassicPort() {
        val info = Adb.connected ?: return
        scope.launch {
            hotspotStage = "Switching the watch to port 5555…"
            runCatching { Adb.service("tcpip:5555") }
            // adbd restarts on the watch; give it a moment, then reconnect on the new port.
            delay(3000)
            hotspotStage = null
            misses.clear()
            hunting = null
            val r = Adb.connect(ctx, info.host, 5555)
            val now = r.getOrNull()
            if (now != null) {
                prefs.remember(SavedWatch(info.host, 5555, now.model, now.serial, System.currentTimeMillis()))
                saved = prefs.watches
                notice = "Done. Until ${now.model} restarts, Syndesia connects on port 5555 and the watch won't ask about each new network, including your phone's hotspot."
            } else {
                notice = "The watch switched modes but Syndesia couldn't reconnect on port 5555. If the watch asks \"Allow debugging?\", tap Always allow, then connect again."
            }
        }
    }

    /**
     * No Wi-Fi in common? Start the phone's own hotspot, have the watch join it over Bluetooth,
     * then connect ADB over it.
     */
    fun connectViaHotspot() {
        if (hotspotStage != null) return
        scope.launch {
            var ok = false
            Hotspot.inUse = true
            Hotspot.cancelStopLater()
            try {
                hotspotStage = "Starting your phone's hotspot…"
                val creds = Hotspot.start(ctx)
                hotspotStage = "Asking your watch to join it…"
                val addr = Helper.joinHotspot(ctx, creds) { stage ->
                    when (stage) {
                        "joining" -> hotspotStage = "Your watch is joining ${creds.ssid}…"
                        "approve" -> hotspotStage = "On your watch, tap Connect to join ${creds.ssid}"
                    }
                }
                // The watch answers "ip[:port][;dbg=1|0]".
                val parts = addr.split(';')
                val ip = parts[0].substringBefore(':')
                val reported = parts[0].substringAfter(':', "").toIntOrNull()
                val debugging = parts.firstOrNull { it.startsWith("dbg=") }?.removePrefix("dbg=")
                hotspotStage = "Looking for your watch on the hotspot…"
                Discovery.restart(ctx)
                // mDNS often doesn't cross the phone's own hotspot, so also look for open ports directly.
                val scanned = PortScan.open(ip)
                val viaMdns = Discovery.found.value.firstOrNull { it.host == ip }?.connectPort
                val ports = (listOfNotNull(reported, viaMdns) + scanned.filter { it != 5555 } + listOf(5555))
                    .distinct().take(8)
                hotspotStage = "Connecting over the hotspot…"
                for (p in ports) {
                    if (p != 5555 && p !in scanned && p != reported && p != viaMdns) continue
                    if (p == 5555 && 5555 !in scanned) continue
                    val r = Adb.connect(ctx, ip, p)
                    val info = r.getOrNull()
                    if (info != null) {
                        prefs.remember(SavedWatch(ip, p, info.model, info.serial, System.currentTimeMillis()))
                        saved = prefs.watches
                        ok = true
                        break
                    }
                    if ((Adb.link.value as? Link.Failed)?.needsPairing == true) break
                }
                if (!ok) notice = when {
                    debugging == "0" ->
                        "Your watch joined the hotspot, but Wireless debugging switched itself off there: Android only allows it on networks you've approved. " +
                            "Connect once over regular Wi-Fi and tap Skip the network prompt (About this watch); after that the hotspot works until the watch restarts."
                    scanned.isEmpty() ->
                        "Your watch joined the hotspot at $ip, but nothing on it is accepting connections. If the watch asks \"Allow wireless debugging on this network?\", tap Always allow and try again. " +
                            "Otherwise connect once over regular Wi-Fi and tap Skip the network prompt (About this watch)."
                    else ->
                        "Your watch joined the hotspot at $ip but Syndesia couldn't connect (tried ${ports.joinToString()}). Check the watch for an \"Allow debugging?\" prompt and try again."
                }
            } catch (e: Helper.Outdated) {
                offerHelperUpdate = true
            } catch (e: Throwable) {
                notice = e.message ?: "Couldn't connect through the hotspot"
            } finally {
                hotspotStage = null
                Hotspot.inUse = false
                // Leave it on for a couple of minutes so trying again reuses the same network.
                if (!ok) Hotspot.stopLater()
            }
        }
    }

    fun refreshHelper() {
        scope.launch { helperNearby = Helper.nodes(ctx).isNotEmpty() }
    }

    /** Manual "wake the watch's Wi-Fi" from the Watch tab. */
    fun wakeWatch() {
        scope.launch {
            notice = "Asking your watch to turn on Wi-Fi…"
            val r = Helper.wakeAndWait(ctx)
            notice = when {
                r == null -> "Couldn't reach Syndesia on your watch over Bluetooth. Make sure the watch is connected to this phone and the helper is installed (Install tab)."
                r.stage == "noreply" -> "Your watch got the message but didn't answer. Update Syndesia on the watch (Install tab, over Bluetooth) and try again."
                r.stage == "on" && r.message == "debugging" -> "Your watch's Wi-Fi is on. It should show up under On this Wi-Fi in a few seconds."
                r.stage == "on" -> "Your watch's Wi-Fi is on, but Wireless debugging is off. On the watch: Settings › Developer options › Wireless debugging."
                r.message == "wifi_off" -> "Your watch's Wi-Fi is switched off, and apps aren't allowed to switch it on. Tap the notification on your watch, or try Connect with phone hotspot."
                r.message == "nofgs" -> "Android on your watch stopped Syndesia from starting in the background. Open Syndesia on the watch and tap Connect to phone, or use Connect with phone hotspot."
                else -> "Your watch's Wi-Fi turned on but found no saved network nearby. Try Connect with phone hotspot, or tap the notification on your watch to pick a network."
            }
        }
    }

    // ------------------------------------------------------------------ auto-connect

    /**
     * Syndesia is on screen: look for the saved watch until it's connected, and again whenever the
     * link is lost for good. One loop does all of it, so nothing depends on catching a single mDNS
     * event at the right moment.
     */
    fun autoStart() {
        if (seeker?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            try {
                seek()
            } finally {
                if (seeker === me) seeking = null
            }
        }
        seeker = job
        job.start()
    }

    /** Syndesia left the screen: stop looking. (A link that's already up is kept by LinkService.) */
    fun autoStop() {
        seeker?.cancel()
        seeker = null
        seeking = null
    }

    /** Auto-connect only acts while nothing is connected and the user isn't in the middle of connecting some other way. */
    private fun autoAllowed(l: Link): Boolean =
        autoConnect && !Adb.userDisconnected && saved.isNotEmpty() &&
            hotspotStage == null && !pairing && pairRequest == null && connectRequest == null &&
            (l is Link.Idle || (l is Link.Failed && !l.needsPairing))

    /** Still inside the rest an address gets after connecting to it failed? The rest grows with each miss. */
    private fun resting(host: String, port: Int): Boolean {
        val m = misses["$host:$port"] ?: return false
        val rest = when (m.count) {
            1 -> 8_000L
            2 -> 30_000L
            3 -> 120_000L
            else -> 600_000L
        }
        return SystemClock.elapsedRealtime() - m.at < rest
    }

    /**
     * One automatic connect, to a port that's known to be open. It runs to the end even if the app
     * leaves the screen meanwhile, so a connect that succeeds is always recorded.
     */
    private suspend fun auto(host: String, port: Int): Boolean {
        if (!autoAllowed(Adb.link.value)) return false
        return scope.async { settle(host, port, Adb.connect(ctx, host, port, byUser = false)) }.await()
    }

    private suspend fun seek() {
        var began = 0L // when this hunt started; 0 between hunts
        var round = 0
        var wakes = 0
        var lastWake = 0L
        var scans = 0
        var lastScan = 0L
        while (true) {
            try {
                val l = Adb.link.value
                if (l is Link.Connected || l is Link.Connecting) {
                    // Nothing to do while a link is up or coming up. (Adb itself retries a dropped one for a while.)
                    seeking = null
                    began = 0L
                    Adb.link.first { it is Link.Idle || it is Link.Failed }
                    continue
                }
                if (!autoAllowed(l)) {
                    seeking = null
                    began = 0L
                    delay(1000)
                    continue
                }
                if (began == 0L) {
                    began = SystemClock.elapsedRealtime()
                    round = 0
                    wakes = 0
                    scans = 0
                }
                val target = hunting?.let { h -> saved.firstOrNull { it.host == h.host && it.serial == h.serial } } ?: saved.first()
                if (seeking == null) seeking = "Looking for ${target.model}…"

                // The watch's Wi-Fi is usually asleep while Bluetooth is connected: nudge it awake, a few times at most.
                val t = SystemClock.elapsedRealtime()
                if (wakes == 0 || (wakes < 4 && t - lastWake > 25_000)) {
                    wakes++
                    lastWake = t
                    Helper.wakeAsync(ctx)
                }

                Discovery.refresh()
                val adverts = Discovery.found.value
                val spots = buildList {
                    // Where mDNS says the watch is now: its port changes whenever Wireless debugging restarts…
                    adverts.firstOrNull { target.isAt(it) }?.let { f -> f.connectPort?.let { add(f.host to it) } }
                    // …then where it was last time…
                    add(target.host to target.port)
                    // …then any other saved watch that's advertising, unless we're after one in particular.
                    if (hunting == null) for (f in adverts) {
                        val p = f.connectPort ?: continue
                        if (saved.any { it.isAt(f) }) add(f.host to p)
                    }
                }.distinct()

                var connected = false
                var shut: String? = null // an address where the watch answers, but not on any port we know
                for ((host, port) in spots) {
                    if (resting(host, port)) continue
                    // A quick knock first: only a port that's open gets a real connect, so looking
                    // for a watch that isn't there never flashes "Connecting…" and an error.
                    when (withContext(Dispatchers.IO) { PortScan.knock(host, port) }) {
                        PortState.Open -> connected = auto(host, port)
                        PortState.Closed -> if (shut == null && (host == target.host || adverts.any { it.host == host && target.isAt(it) })) shut = host
                        PortState.Unreachable -> {}
                    }
                    if (connected || !autoAllowed(Adb.link.value)) break
                }
                if (connected || !autoAllowed(Adb.link.value)) continue

                // The watch is on Wi-Fi but not listening where mDNS or memory says. If mDNS has had
                // its chance, find the port directly (a new one is picked whenever debugging restarts).
                val there = shut
                val sinceScan = SystemClock.elapsedRealtime() - lastScan
                if (there != null && round >= 1 && scans < 4 && (scans == 0 || sinceScan > (if (scans < 2) 20_000 else 60_000))) {
                    scans++
                    seeking = "Finding ${target.model}'s new port…"
                    val ports = PortScan.findAdb(there)
                    lastScan = SystemClock.elapsedRealtime()
                    for (p in ports) {
                        if (resting(there, p)) continue
                        connected = auto(there, p)
                        if (connected || !autoAllowed(Adb.link.value)) break
                    }
                    if (connected || !autoAllowed(Adb.link.value)) continue
                }

                seeking = if (SystemClock.elapsedRealtime() - began < 60_000) "Looking for ${target.model}…"
                else "Still looking for ${target.model}. Check it's on this Wi-Fi with Wireless debugging on."
                // Go again as soon as mDNS has news, or after a pause that grows while nothing turns up.
                val pause = when {
                    round < 10 -> 2000L
                    round < 30 -> 4000L
                    else -> 10_000L
                }
                if (withTimeoutOrNull(pause) { Discovery.found.first { it != adverts } } != null) delay(300)
                round++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(3000) // never let a hiccup here take the app down; just try again
            }
        }
    }

    fun pair(host: String, port: Int, code: String, connectPort: Int?) {
        pairing = true
        pairError = null
        scope.launch {
            val r = Adb.pair(ctx, host, port, code)
            r.onFailure { pairing = false; pairError = it.message }
            r.onSuccess {
                pairRequest = null
                // The connect port is a different one; mDNS usually has it.
                val cp = connectPort ?: withTimeoutOrNull(8000) {
                    var p: Int? = null
                    while (p == null) {
                        p = Discovery.found.value.firstOrNull { it.host == host }?.connectPort
                        if (p == null) delay(300)
                    }
                    p
                }
                pairing = false
                if (cp != null) connect(host, cp)
                else {
                    notice = "Paired! Now enter the port shown under \"IP address & port\" on the watch's Wireless debugging screen."
                    connectRequest = AddressRequest(host, "")
                }
            }
        }
    }

    /** Files from Open with / Share: APKs go to the install queue, anything else gets sent to the watch's Download folder. */
    fun accept(uris: List<Uri>, onTab: (STab) -> Unit) {
        if (uris.isEmpty()) return
        val apkExt = setOf("apk", "apks", "xapk", "apkm")
        val (apks, others) = uris.partition { Apk.displayName(ctx, it).substringAfterLast('.', "").lowercase() in apkExt }
        if (apks.isNotEmpty()) {
            addApks(apks)
            onTab(STab.Install)
        }
        if (others.isNotEmpty()) {
            if (Adb.connected == null && helperNearby) {
                // No Wi-Fi link: send them over Bluetooth to Syndesia on the watch.
                Transfers.uploadBluetooth(ctx, others)
                if (apks.isEmpty()) onTab(STab.Files)
            } else if (Adb.connected == null) {
                notice = "Connect a watch first, then share the files again to send them."
            } else {
                val dir = "$STORAGE_ROOT/Download"
                Transfers.upload(ctx, others, dir)
                filesPath = dir
                if (apks.isEmpty()) onTab(STab.Files)
            }
        }
    }

    fun addApks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        preparing = true
        scope.launch {
            val (pkgs, issues) = withContext(Dispatchers.IO) { Apk.prepare(ctx, uris) }
            preparing = false
            problems = issues
            Installer.add(pkgs)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyndesiaApp(onReplayTour: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val st = remember { SyndesiaState(ctx.applicationContext) }
    var tab by rememberSaveable { mutableStateOf(STab.Watch) }
    val look by Look.state.collectAsStateWithLifecycle()
    val link by Adb.link.collectAsStateWithLifecycle()
    val found by Discovery.found.collectAsStateWithLifecycle()
    val incoming by Incoming.uris.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        Discovery.hold(ctx, "app")
        st.refreshHelper()
        st.autoStart()
        onPauseOrDispose {
            st.autoStop()
            Discovery.release("app")
        }
    }
    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) st.accept(Incoming.take()) { tab = it }
    }
    // Ask once for notifications so the "Connected" notice (with its Disconnect button) shows.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val isConnected = link is Link.Connected
    LaunchedEffect(isConnected) {
        if (!isConnected) { st.refreshHelper(); return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        Adb.connected?.let { st.noteConnected(it) }
        // Pick up installs a dropped connection interrupted.
        Installer.resumeInterrupted()
        // Keep the link alive in the background until the user disconnects or closes the app.
        LinkService.start(ctx)
        if (Build.VERSION.SDK_INT >= 33 && !st.prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            st.prefs.askedNotifications = true
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // "What's new" once after each update (not on a fresh install).
    val releases = remember { Changelog.load(ctx) }
    var showWhatsNew by remember {
        mutableStateOf(st.prefs.lastSeenVersion in 1 until BuildConfig.VERSION_CODE)
    }
    var showChangelog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!showWhatsNew) st.prefs.lastSeenVersion = BuildConfig.VERSION_CODE }

    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        AnimatedBackground(look.background, Modifier.fillMaxSize())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            topBar = {
                LargeTopAppBar(
                    title = { Text(tab.title, style = MaterialTheme.typography.headlineLarge) },
                    actions = { ConnectionChip(link) { tab = STab.Watch } },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = colors.surfaceContainer,
                    ),
                )
            },
            bottomBar = {
                FloatingNavBar(tab) { t ->
                    if (t != tab) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    tab = t
                }
            },
        ) { padding ->
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(260, delayMillis = 60), initialScale = 0.97f))
                        .togetherWith(fadeOut(tween(90)))
                },
                label = "tabs",
            ) { t ->
                when (t) {
                    STab.Watch -> WatchScreen(st, link, found, padding, onTab = { tab = it })
                    STab.Install -> InstallScreen(st, link, padding, onTab = { tab = it })
                    STab.Files -> FilesScreen(st, link, padding, onTab = { tab = it })
                    STab.Apps -> AppsScreen(st, link, padding, onTab = { tab = it })
                    STab.Settings -> SettingsScreen(
                        st, look, padding,
                        onReplayTour = onReplayTour,
                        onChangelog = { showChangelog = true },
                    )
                }
            }
        }
    }

    st.pairRequest?.let { req -> PairDialog(st, req, found) }
    st.connectRequest?.let { req -> ConnectDialog(st, req) }
    st.notice?.let { msg -> NoticeDialog(msg) { st.notice = null } }
    if (st.offerHelperUpdate) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { st.offerHelperUpdate = false },
            icon = { Icon(Icons.Rounded.Watch, null) },
            title = { Text("Update the watch app") },
            text = {
                Text(
                    "Syndesia on your watch is too old to join your phone's hotspot. Syndesia can send the new version over Bluetooth " +
                        "(a couple of minutes), then the watch asks you to tap Install. After that, tap Connect via phone hotspot again.",
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = { st.offerHelperUpdate = false; st.updateHelper { tab = it } }) { Text("Update over Bluetooth") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { st.offerHelperUpdate = false }) { Text("Not now") } },
        )
    }
    if (showWhatsNew && releases.isNotEmpty()) {
        ChangelogSheet(releases, latestOnly = true) {
            st.prefs.lastSeenVersion = BuildConfig.VERSION_CODE
            showWhatsNew = false
        }
    }
    if (showChangelog) ChangelogSheet(releases, latestOnly = false) { showChangelog = false }
}

/** Small status pill in the top bar: green dot when a watch is connected. */
@Composable
private fun ConnectionChip(link: Link, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (text, on) = when (link) {
        is Link.Connected -> link.info.model to true
        is Link.Connecting -> (if (link.reconnecting) "Reconnecting…" else "Connecting…") to false
        is Link.Failed -> "Not connected" to false
        Link.Idle -> "No watch" to false
    }
    val dot by animateColorAsState(if (on) Color(0xFF34C759) else colors.outline, label = "dot")
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        modifier = Modifier.padding(end = 12.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 8.dp).clip(CircleShape).background(dot).padding(4.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Bataria's floating pill navigation: the selected tab expands to show its label. */
@Composable
fun FloatingNavBar(tab: STab, onTab: (STab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.surface.copy(alpha = 0f), colors.surface.copy(alpha = 0.72f))))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainerHigh.let { if (it.alpha > 0.82f) it.copy(alpha = 0.82f) else it },
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                STab.entries.forEach { t -> NavPill(t, selected = t == tab) { onTab(t) } }
            }
        }
    }
}

@Composable
private fun NavPill(t: STab, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.primary else Color.Transparent, tween(250), label = "navBg")
    val fg by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, tween(250), label = "navFg")
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .animateContentSize(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) t.selectedIcon else t.icon, contentDescription = t.label, tint = fg)
        if (selected) {
            Text(t.label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
