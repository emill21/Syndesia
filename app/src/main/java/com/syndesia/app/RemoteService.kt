package com.syndesia.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Lets the watch drive this phone: taps and swipes where a mouse cursor points, Back / Home /
 * Recents, notifications, volume and media, the torch, launching apps and typing. Commands arrive
 * from Syndesia on the watch over Bluetooth on /remote/… paths while this service is switched on in
 * Settings › Accessibility.
 */
class RemoteService : AccessibilityService(), MessageClient.OnMessageReceivedListener {
    private val main = Handler(Looper.getMainLooper())
    private var cursor: CursorView? = null
    private var cx = 0f
    private var cy = 0f
    private var screenW = 1080
    private var screenH = 2400
    private val hideCursor = Runnable { showCursor(false) }
    private var torchOn = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        readScreen()
        cx = screenW / 2f
        cy = screenH / 2f
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onDestroy() {
        runCatching { Wearable.getMessageClient(this).removeListener(this) }
        showCursor(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun readScreen() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }

    // --------------------------------------------------------------- messages from the watch

    override fun onMessageReceived(e: MessageEvent) {
        if (!e.path.startsWith("/remote/")) return
        val o = runCatching { JSONObject(String(e.data, Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val node = e.sourceNodeId
        main.post { handle(e.path.removePrefix("/remote/"), o, node) }
    }

    private fun reply(node: String, body: JSONObject) {
        runCatching { Wearable.getMessageClient(this).sendMessage(node, "/remote/reply", body.toString().toByteArray()) }
    }

    private fun handle(cmd: String, o: JSONObject, node: String) {
        when (cmd) {
            "ping" -> reply(node, JSONObject().put("type", "pong").put("w", screenW).put("h", screenH).put("torch", torchOn))
            "move" -> move(o.optDouble("dx").toFloat(), o.optDouble("dy").toFloat())
            "cursor" -> showCursor(o.optBoolean("on", true))
            "click" -> tap(cx, cy, 40)
            "double" -> { tap(cx, cy, 40); main.postDelayed({ tap(cx, cy, 40) }, 120) }
            "long" -> tap(cx, cy, 650)
            "press" -> press()
            "release" -> release()
            "wheel" -> wheel(o.optDouble("dy").toFloat(), o.optBoolean("h", false))
            "scroll" -> scroll(o.optString("dir"), o.optDouble("amount", 1.0).toFloat(), o.optBoolean("atCursor", false))
            "global" -> global(o.optString("action"))
            "volume" -> volume(o.optString("dir"))
            "media" -> media(o.optString("key"))
            "torch" -> { torch(o.optBoolean("on", !torchOn)); reply(node, JSONObject().put("type", "torch").put("on", torchOn)) }
            "apps" -> reply(node, JSONObject().put("type", "apps").put("apps", apps()))
            "launch" -> launch(o.optString("pkg"))
            "type" -> reply(node, JSONObject().put("type", "typed").put("ok", type(o.optString("text"), o.optBoolean("enter"))))
            "backspace" -> backspace()
            "unlock" -> unlock(node)
            "private" -> openPrivateSpace { why -> reply(node, JSONObject().put("type", "private").put("why", why)) }
            "private_apps" -> openPrivateSpace { why ->
                if (why != "opened") reply(node, JSONObject().put("type", "private_apps").put("why", why))
                else main.postDelayed({
                    collectPrivateApps { names ->
                        reply(
                            node,
                            JSONObject().put("type", "private_apps").put("why", if (names.isEmpty()) "empty" else "ok")
                                .put("apps", org.json.JSONArray(names)),
                        )
                    }
                }, 900)
            }
            "private_open" -> {
                val name = o.optString("name")
                openPrivateSpace { why ->
                    if (why != "opened") reply(node, JSONObject().put("type", "private_open").put("why", why))
                    else main.postDelayed({
                        openPrivateApp(name) { ok -> reply(node, JSONObject().put("type", "private_open").put("why", if (ok) "ok" else "notfound").put("name", name)) }
                    }, 700)
                }
            }
        }
    }

    // --------------------------------------------------------------- unlock with PIN

    private var unlocking = false
    private var seen = 0

    /**
     * Wakes the phone, brings up the PIN pad and types the saved PIN, once. Never retries, so a
     * wrong saved PIN can't burn through attempts and lock the phone out.
     */
    private fun unlock(node: String) {
        fun done(ok: Boolean, why: String) {
            unlocking = false
            reply(node, JSONObject().put("type", "unlocked").put("ok", ok).put("why", why).put("seen", seen))
        }
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!km.isKeyguardLocked) {
            if (!pm.isInteractive) wake(false)
            return done(true, "already")
        }
        if (unlocking) return
        if (!km.isDeviceSecure) { wake(true); return done(true, "nopin") }
        val pin = PinVault.load(this)
        if (pin.isNullOrEmpty()) return done(false, "nopin_saved")
        unlocking = true

        fun type(keys: Map<String, AccessibilityNodeInfo>) = typePin(pin, keys) { entered ->
            if (!entered) return@typePin done(false, "nopad")
            main.postDelayed({ done(!km.isKeyguardLocked, if (km.isKeyguardLocked) "wrong" else "unlocked") }, 900)
        }

        // PIN pad already open? Type straight away (opening anything on top would close it).
        pinKeys()?.let { return type(it) }

        val screenWasOff = !pm.isInteractive
        if (screenWasOff) wake(false)
        var started = SystemClock.uptimeMillis()
        var awakeAt = if (screenWasOff) 0L else started
        var swipes = 0
        var lastSwipe = 0L
        var asked = false
        val find = object : Runnable {
            override fun run() {
                if (!km.isKeyguardLocked) return done(true, "unlocked") // Watch Unlock / face got there first
                pinKeys()?.let { return type(it) }
                val now = SystemClock.uptimeMillis()
                if (awakeAt == 0L) {
                    // Screen still coming on: wait for it, then give the lock screen a moment to take touches.
                    if (pm.isInteractive) awakeAt = now
                    else if (now - started > 3000) { wake(false); started = now }
                    main.postDelayed(this, 100); return
                }
                val sinceAwake = now - awakeAt
                if (sinceAwake > (if (screenWasOff) 650 else 150) && swipes < 3 && now - lastSwipe > 900) {
                    // Swipe up from the very bottom edge, like you would, to bring up the PIN pad.
                    // (Starting higher lands on the under-screen fingerprint sensor, which eats the swipe.)
                    swipes++; lastSwipe = now
                    swipe(screenW / 2f, screenH - 6f, screenW / 2f, screenH * 0.35f, 260)
                } else if (swipes >= 2 && !asked && now - lastSwipe > 700) {
                    // Swiping didn't do it: ask Android to show the PIN pad.
                    asked = true
                    wake(true)
                } else if (sinceAwake > 7000) return done(false, "nopad")
                main.postDelayed(this, 120)
            }
        }
        main.postDelayed(find, 50)
    }

    // --------------------------------------------------------------- private space

    /**
     * Opens Private space the way you would: the app drawer, scroll to the bottom, tap Private.
     * If it asks for the PIN (not just a fingerprint), types the saved one.
     */
    private fun openPrivateSpace(done: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 35) return done("unsupported")
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km.isKeyguardLocked) return done("locked")
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            if (!performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)) {
                swipe(screenW / 2f, screenH * 0.7f, screenW / 2f, screenH * 0.2f, 250)
            }
            var tries = 0
            val look = object : Runnable {
                override fun run() {
                    val root = rootInActiveWindow
                    val tile = root?.let { findPrivateTile(it) }
                    if (tile != null) {
                        // Already open (it shows a Lock button)? Don't tap the header, that could lock it.
                        if (privateUnlocked(root)) return done("opened")
                        clickNode(tile)
                        main.postDelayed({ privateAuth(0, done) }, 700)
                        return
                    }
                    if (++tries > 4) return done("notfound")
                    // Fling straight to the bottom of the app list, where Private space sits.
                    zipToBottom { main.post(this) }
                }
            }
            main.postDelayed(look, 400)
        }, 350)
    }

    private fun findPrivateTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val label = listOfNotNull(n.text, n.contentDescription).joinToString(" ").lowercase()
            if (n.isVisibleToUser && (label == "private" || label.startsWith("private space") || label.startsWith("private,") || label == "private, locked" || label.contains("unlock private"))) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = android.graphics.Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val a = r.width() * r.height()
                if (a > bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return best
    }

    /** After tapping Private: if a PIN pad (or a "Use PIN" button) shows up, type the saved PIN once. */
    private var askedForPin = false

    /**
     * After tapping Private: switch the fingerprint prompt to the PIN, then type the saved PIN —
     * on a number pad, or into the PIN box with Enter.
     */
    private fun privateAuth(step: Int, done: (String) -> Unit) {
        if (step == 0) askedForPin = false
        val pin = PinVault.load(this)
        val keys = pinKeys(anyPackage = true)
        if (keys != null) {
            if (pin.isNullOrEmpty()) return done("needpin")
            return typePin(pin, keys, lockScreen = false) { done(if (it) "opened" else "auth") }
        }
        if (!pin.isNullOrEmpty()) {
            pinField()?.let { field ->
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                main.postDelayed({
                    val entered = Build.VERSION.SDK_INT >= 30 &&
                        field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    if (!entered) findButton(listOf("enter", "ok", "confirm", "done", "next", "continue"))?.let { clickNode(it) }
                    main.postDelayed({ done("opened") }, 500)
                }, 150)
                return
            }
            if (!askedForPin) findButton(listOf("use pin", "use password", "use pattern", "use screen lock"))?.let {
                askedForPin = true
                clickNode(it)
            }
        }
        if (step > 24) return done("opened") // no PIN needed, or you used your fingerprint
        main.postDelayed({ privateAuth(step + 1, done) }, 150)
    }

    /** A visible PIN/password box in a system prompt. */
    private fun pinField(): AccessibilityNodeInfo? {
        for (r in rootsNow()) {
            val pkg = r.packageName?.toString()
            if (pkg != "com.android.systemui" && pkg != "com.android.settings") continue
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n.isVisibleToUser && n.isEditable && (n.isPassword || n.inputType != 0)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun findButton(labels: List<String>): AccessibilityNodeInfo? {
        for (r in rootsNow()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                val l = label(n).lowercase()
                if (n.isVisibleToUser && l in labels) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    /**
     * Gets to the bottom of the app drawer fast: a few hard flicks in a row (the drawer's own
     * scroll action glides a page at a time, which is slow), then lets it settle.
     */
    private fun zipToBottom(then: () -> Unit) {
        var n = 0
        val flick = object : Runnable {
            override fun run() {
                if (n++ >= 4) { main.postDelayed(then, 450); return }
                swipe(screenW / 2f, screenH * 0.86f, screenW / 2f, screenH * 0.14f, 28)
                main.postDelayed(this, 95)
            }
        }
        main.post(flick)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun isPrivateHeader(n: AccessibilityNodeInfo): Boolean {
        val l = listOfNotNull(n.text, n.contentDescription).joinToString(" ").lowercase()
        return l == "private" || l.startsWith("private space") || l.startsWith("private,") || l.contains("unlock private")
    }

    /** Private space shows a Lock button while it's open. */
    private fun privateUnlocked(root: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val l = label(n).lowercase()
            if (n.isVisibleToUser && (l == "lock" || l == "lock private space" || l.startsWith("lock private"))) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return false
    }

    private val notApps = setOf(
        "lock", "settings", "install", "install apps", "add", "add apps", "add app", "private", "private space",
        "private space settings", "unlock", "search", "search apps",
    )

    /**
     * The Private space app tiles on screen right now: tiles in the app list below the Private
     * header, or every tile when the header has scrolled off above (we're at the bottom, so the
     * whole screen is Private space). Null when the header is above but we can't tell, i.e. never.
     */
    private fun privateTiles(root: AccessibilityNodeInfo): Pair<Boolean, List<Pair<String, AccessibilityNodeInfo>>> {
        val list = findScrollable(root) ?: root
        var header: android.graphics.Rect? = null
        val tiles = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        val r = android.graphics.Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(list) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isVisibleToUser) {
                if (isPrivateHeader(n)) {
                    n.getBoundsInScreen(r)
                    if (header == null || r.bottom > header!!.bottom) header = android.graphics.Rect(r)
                } else if (n.isClickable && !n.text.isNullOrBlank()) {
                    val l = label(n)
                    val low = l.lowercase()
                    if (low !in notApps && !low.startsWith("lock") && !low.startsWith("add ") && !low.startsWith("install ")) tiles.add(l to n)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        val h = header ?: return false to tiles
        return true to tiles.filter { (_, n) -> n.getBoundsInScreen(r); r.top >= h.bottom - 4 }
    }

    /** The names of the apps in Private space (it must already be open in the app drawer). */
    private fun collectPrivateApps(then: (List<String>) -> Unit) {
        val names = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        var rounds = 0
        val step = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow ?: return then(names.toList())
                val (sawHeader, tiles) = privateTiles(root)
                tiles.forEach { names.add(it.first) }
                // Header visible: we've seen the whole section. Otherwise page back up toward it.
                val list = findScrollable(root)
                if (sawHeader || ++rounds > 10 || list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) {
                    return then(names.toList())
                }
                main.postDelayed(this, 200)
            }
        }
        zipToBottom { main.post(step) }
    }

    /** Taps a Private space app by name. */
    private fun openPrivateApp(name: String, then: (Boolean) -> Unit) {
        var rounds = 0
        val step = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow ?: return then(false)
                val (sawHeader, tiles) = privateTiles(root)
                tiles.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { clickNode(it.second); return then(true) }
                val list = findScrollable(root)
                if (sawHeader || ++rounds > 10 || list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return then(false)
                main.postDelayed(this, 200)
            }
        }
        zipToBottom { main.post(step) }
    }

    private fun rootsNow(): List<AccessibilityNodeInfo> = buildList {
        runCatching { windows.forEach { w -> w.root?.let { add(it) } } }
        rootInActiveWindow?.let { add(it) }
    }

    /** Turns the screen on; with [dismiss], also asks Android to bring up the PIN pad. */
    private fun wake(dismiss: Boolean) {
        runCatching {
            startActivity(
                Intent(this, WakeActivity::class.java)
                    .putExtra("dismiss", dismiss)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    /** A digit label: "1", or "2 ABC" / "2, A B C" style. */
    private fun digitOf(n: AccessibilityNodeInfo): String? {
        for (cs in listOf(n.text, n.contentDescription)) {
            val t = cs?.toString()?.trim() ?: continue
            if (t.isEmpty() || !t[0].isDigit()) continue
            if (t.length == 1 || !t[1].isDigit()) return t[0].toString()
        }
        return null
    }

    /** The lock screen's digit keys 0–9 (and Enter, if it has one), or null if the PIN pad isn't showing. */
    private fun pinKeys(anyPackage: Boolean = false): Map<String, AccessibilityNodeInfo>? {
        val roots = buildList {
            runCatching { windows.forEach { w -> w.root?.let { add(it) } } }
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val all = windowsOnAllDisplays
                for (i in 0 until all.size()) all.valueAt(i).forEach { w -> w.root?.let { add(it) } }
            }
            rootInActiveWindow?.let { add(it) }
        }
        val out = HashMap<String, AccessibilityNodeInfo>()
        var count = 0
        for (r in roots) {
            val pkg = r.packageName?.toString()
            if (pkg != "com.android.systemui" && !(anyPackage && pkg == "com.android.settings")) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(r) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                count++
                val id = n.viewIdResourceName.orEmpty()
                val d = Regex("""key(\d)$""").find(id)?.groupValues?.get(1) ?: digitOf(n)
                if (d != null && !out.containsKey(d) && n.isVisibleToUser) out[d] = n
                if (!out.containsKey("enter") && n.isVisibleToUser) {
                    val label = (n.contentDescription ?: n.text)?.toString()?.lowercase().orEmpty()
                    if (id.endsWith("key_enter") || label == "enter" || label == "ok" || label == "done") out["enter"] = n
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        seen = count
        return if ((0..9).all { out.containsKey(it.toString()) }) out else null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        if (b.isEmpty) return false
        tap(b.exactCenterX(), b.exactCenterY(), 40)
        return true
    }

    private fun typePin(pin: String, keys: Map<String, AccessibilityNodeInfo>, lockScreen: Boolean = true, then: (Boolean) -> Unit) {
        val seq = pin.map { it.toString() } + (if (keys.containsKey("enter")) listOf("enter") else emptyList())
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i >= seq.size) return then(true)
                // Auto-confirm PINs unlock on the last digit; don't tap anything after that.
                if (lockScreen && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return then(true)
                val n = keys[seq[i]] ?: return then(false)
                if (!clickNode(n)) return then(false)
                i++
                main.postDelayed(this, 110)
            }
        }
        main.post(step)
    }

    // --------------------------------------------------------------- mouse

    /** Smoothed pointer speed, so acceleration ramps instead of jumping. */
    private var gain = 1.6f

    /** dx/dy are fractions of the watch touchpad width; scale to the phone, faster for quick flicks. */
    private fun move(dx: Float, dy: Float) {
        val speed = hypot(dx, dy)
        val target = 1.5f + min(speed * 14f, 3.0f)
        gain += (target - gain) * 0.35f
        cx = (cx + dx * screenW * gain).coerceIn(0f, screenW - 1f)
        cy = (cy + dy * screenW * gain).coerceIn(0f, screenH - 1f)
        showCursor(true)
        cursor?.target(cx, cy)
        // Holding = dragging: the finger follows the cursor.
        if (finger.active && finger.holding) finger.moveTo(cx, cy)
    }

    private fun showCursor(on: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        if (on) {
            if (cursor == null) {
                val v = CursorView(this).also { it.jump(cx, cy) }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                runCatching { wm.addView(v, lp); cursor = v }
            }
            main.removeCallbacks(hideCursor)
            main.postDelayed(hideCursor, 30_000)
        } else {
            if (finger.active) finger.release()
            cursor?.let { runCatching { wm.removeView(it) } }
            cursor = null
        }
    }

    private fun tap(x: Float, y: Float, ms: Long) {
        if (finger.active) return
        cursor?.pulse()
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    /** Press and keep holding at the cursor (long-press to select; then moves drag). */
    private fun press() {
        if (finger.active) finger.release()
        cursor?.holding = true
        finger.start(cx, cy, holding = true)
    }

    private fun release() {
        cursor?.holding = false
        finger.release()
    }

    /**
     * Crown scrolling: one finger that stays down and glides while the crown turns, then lifts
     * without a fling. [dy] is a fraction of the watch touchpad; positive = scroll down.
     */
    private fun wheel(dy: Float, horizontal: Boolean) {
        if (finger.active && finger.holding) return
        val x0 = if (cursor != null) cx else screenW / 2f
        val y0 = if (cursor != null) cy.coerceIn(screenH * 0.2f, screenH * 0.8f) else screenH / 2f
        if (!finger.active) finger.start(x0, y0, holding = false)
        val step = -dy * screenH * 1.1f
        if (horizontal) finger.moveBy(step, 0f) else finger.moveBy(0f, step)
    }

    /** Old-style one-shot scroll (still used by the panel's scroll buttons). */
    private fun scroll(dir: String, amount: Float, atCursor: Boolean) {
        val x = if (atCursor && cursor != null) cx else screenW / 2f
        val y = if (atCursor && cursor != null) cy else screenH / 2f
        val dist = (screenH * 0.28f * amount.coerceIn(0.2f, 2.5f))
        val distX = (screenW * 0.4f * amount.coerceIn(0.2f, 2.5f))
        fun clampY(v: Float) = v.coerceIn(screenH * 0.08f, screenH * 0.92f)
        fun clampX(v: Float) = v.coerceIn(screenW * 0.05f, screenW * 0.95f)
        when (dir) {
            "down" -> swipe(x, clampY(y + dist / 2), x, clampY(y - dist / 2), 300)
            "up" -> swipe(x, clampY(y - dist / 2), x, clampY(y + dist / 2), 300)
            "left" -> swipe(clampX(x - distX / 2), y, clampX(x + distX / 2), y, 300)
            "right" -> swipe(clampX(x + distX / 2), y, clampX(x - distX / 2), y, 300)
        }
    }

    /**
     * One virtual finger built from continued strokes (API 26+): it can stay down, move a bit
     * every frame, and lift when told, which is what drag-select and smooth scrolling need.
     */
    private val finger = Finger()

    private inner class Finger {
        var active = false; private set
        var holding = false; private set
        private var fx = 0f
        private var fy = 0f
        private var tx = 0f
        private var ty = 0f
        private var last: GestureDescription.StrokeDescription? = null
        private var ending = false
        private var lastInput = 0L
        private var inFlight = false

        private val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                next()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                inFlight = false
                reset()
            }
        }

        private fun reset() {
            active = false; holding = false; ending = false; last = null
            cursor?.holding = false
        }

        fun start(x: Float, y: Float, holding: Boolean) {
            reset()
            active = true
            this.holding = holding
            fx = x; fy = y; tx = x; ty = y
            lastInput = SystemClock.uptimeMillis()
            dispatch(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 30, true))
        }

        fun moveTo(x: Float, y: Float) {
            tx = x.coerceIn(1f, screenW - 2f); ty = y.coerceIn(1f, screenH - 2f)
            lastInput = SystemClock.uptimeMillis()
        }

        fun moveBy(dx: Float, dy: Float) {
            val nx = tx + dx
            val ny = ty + dy
            // Near the screen edge: lift (no fling) and let the next turn start a fresh stroke.
            if (!holding && (ny < screenH * 0.06f || ny > screenH * 0.94f || nx < screenW * 0.04f || nx > screenW * 0.96f)) {
                tx = nx.coerceIn(screenW * 0.04f, screenW * 0.96f); ty = ny.coerceIn(screenH * 0.06f, screenH * 0.94f)
                release()
                return
            }
            moveTo(nx, ny)
        }

        fun release() {
            if (!active) return
            ending = true
            if (!inFlight) next()
        }

        private fun dispatch(s: GestureDescription.StrokeDescription) {
            last = s
            inFlight = true
            val ok = runCatching {
                dispatchGesture(GestureDescription.Builder().addStroke(s).build(), callback, main)
            }.getOrDefault(false)
            if (!ok) { inFlight = false; reset() }
        }

        private fun next() {
            if (!active) return
            val prev = last ?: run { reset(); return }
            val idle = SystemClock.uptimeMillis() - lastInput
            // Scrolling lifts by itself shortly after the crown stops; holding waits for release.
            if (ending || (!holding && idle > 180)) {
                // Finish where we are, standing still for a moment, so it doesn't fling.
                val end = Path().apply { moveTo(fx, fy) }
                runCatching { dispatchGesture(GestureDescription.Builder().addStroke(prev.continueStroke(end, 0, 60, false)).build(), null, null) }
                reset()
                return
            }
            // Ease the finger toward the target: smooth, but never more than a frame or two behind.
            val dx = (tx - fx) * 0.6f
            val dy = (ty - fy) * 0.6f
            val p = Path().apply {
                moveTo(fx, fy)
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) lineTo(fx + dx, fy + dy)
            }
            if (abs(dx) > 0.5f || abs(dy) > 0.5f) { fx += dx; fy += dy }
            runCatching { dispatch(prev.continueStroke(p, 0, 16, true)) }.onFailure { reset() }
        }
    }

    // --------------------------------------------------------------- buttons

    private fun global(action: String) {
        val a = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return
            "split" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> return
        }
        performGlobalAction(a)
    }

    private fun volume(dir: String) {
        val am = getSystemService(AudioManager::class.java)
        val d = when (dir) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return
        }
        am.adjustSuggestedStreamVolume(d, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
    }

    private fun media(key: String) {
        val code = when (key) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val am = getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun torch(on: Boolean) {
        runCatching {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        }
    }

    // --------------------------------------------------------------- apps and typing

    private fun apps(): JSONArray {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentActivities(launcher, 0)
        val arr = JSONArray()
        list.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .forEach { (label, pkg) -> arr.put(JSONObject().put("l", label).put("p", pkg)) }
        return arr
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /** Type into whatever text box has focus on the phone. */
    private fun type(text: String, enter: Boolean): Boolean {
        val node = focusedInput() ?: return false
        val current = node.text?.toString().orEmpty().let { if (node.isShowingHintText) "" else it }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (enter && Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    private fun backspace() {
        val node = focusedInput() ?: return
        val current = node.text?.toString().orEmpty()
        if (current.isEmpty() || node.isShowingHintText) return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.dropLast(1))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile
        var instance: RemoteService? = null
            private set

        fun isOn(): Boolean = instance != null
    }
}

/**
 * Full-screen, untouchable layer that draws the pointer. Moves glide toward the latest position
 * every frame (Choreographer), so the cursor looks smooth even though updates arrive in bursts
 * over Bluetooth.
 */
private class CursorView(context: Context) : View(context), android.view.Choreographer.FrameCallback {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 120, 120, 255); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 120, 120, 255); style = Paint.Style.FILL }
    private var pulseUntil = 0L
    private var x = 0f
    private var y = 0f
    private var tx = 0f
    private var ty = 0f
    private var ticking = false

    var holding = false
        set(v) { field = v; invalidate() }

    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 56f); lineTo(14f, 43f); lineTo(24f, 66f); lineTo(34f, 62f); lineTo(24f, 39f); lineTo(42f, 39f); close()
    }

    fun jump(nx: Float, ny: Float) { x = nx; y = ny; tx = nx; ty = ny; invalidate() }

    fun target(nx: Float, ny: Float) {
        tx = nx; ty = ny
        if (!ticking) { ticking = true; android.view.Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        // Exponential ease: covers ~55% of the gap each frame (60–120 Hz), settles in a few frames.
        x += (tx - x) * 0.55f
        y += (ty - y) * 0.55f
        if (abs(tx - x) < 0.3f && abs(ty - y) < 0.3f) { x = tx; y = ty; ticking = false } else {
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(this)
        ticking = false
        super.onDetachedFromWindow()
    }

    fun pulse() {
        pulseUntil = SystemClock.uptimeMillis() + 250
        invalidate()
        postDelayed({ invalidate() }, 260)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(x, y)
        if (holding) canvas.drawCircle(0f, 0f, 30f, dot)
        if (holding || SystemClock.uptimeMillis() < pulseUntil) canvas.drawCircle(0f, 0f, 26f, ring)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }
}
