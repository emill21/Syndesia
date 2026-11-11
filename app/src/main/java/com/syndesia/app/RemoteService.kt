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
            "scroll" -> scroll(o.optString("dir"), o.optDouble("amount", 1.0).toFloat(), o.optBoolean("atCursor", false))
            "global" -> global(o.optString("action"))
            "volume" -> volume(o.optString("dir"))
            "media" -> media(o.optString("key"))
            "torch" -> { torch(o.optBoolean("on", !torchOn)); reply(node, JSONObject().put("type", "torch").put("on", torchOn)) }
            "apps" -> reply(node, JSONObject().put("type", "apps").put("apps", apps()))
            "launch" -> launch(o.optString("pkg"))
            "type" -> reply(node, JSONObject().put("type", "typed").put("ok", type(o.optString("text"), o.optBoolean("enter"))))
            "backspace" -> backspace()
        }
    }

    // --------------------------------------------------------------- mouse

    /** dx/dy are fractions of the watch touchpad; scale to the phone screen, faster for quick flicks. */
    private fun move(dx: Float, dy: Float) {
        val speed = hypot(dx, dy)
        val accel = 1.6f + min(speed * 8f, 3.2f)
        cx = (cx + dx * screenW * accel).coerceIn(0f, screenW - 1f)
        cy = (cy + dy * screenW * accel).coerceIn(0f, screenH - 1f)
        showCursor(true)
        cursor?.let { placeCursor(it) }
    }

    private fun showCursor(on: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        if (on) {
            if (cursor == null) {
                val v = CursorView(this)
                val lp = WindowManager.LayoutParams(
                    CursorView.SIZE, CursorView.SIZE,
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
            main.postDelayed(hideCursor, 20_000)
        } else {
            cursor?.let { runCatching { wm.removeView(it) } }
            cursor = null
        }
    }

    private fun placeCursor(v: CursorView) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = cx.toInt()
        lp.y = cy.toInt()
        runCatching { getSystemService(WindowManager::class.java).updateViewLayout(v, lp) }
    }

    private fun tap(x: Float, y: Float, ms: Long) {
        cursor?.pulse()
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    /** Scroll by swiping: "down" moves content up, like rolling a mouse wheel. */
    private fun scroll(dir: String, amount: Float, atCursor: Boolean) {
        val x = if (atCursor && cursor != null) cx else screenW / 2f
        val y = if (atCursor && cursor != null) cy else screenH / 2f
        val dist = (screenH * 0.28f * amount.coerceIn(0.2f, 2.5f))
        val distX = (screenW * 0.4f * amount.coerceIn(0.2f, 2.5f))
        fun clampY(v: Float) = v.coerceIn(screenH * 0.08f, screenH * 0.92f)
        fun clampX(v: Float) = v.coerceIn(screenW * 0.05f, screenW * 0.95f)
        when (dir) {
            "down" -> swipe(x, clampY(y + dist / 2), x, clampY(y - dist / 2), 220)
            "up" -> swipe(x, clampY(y - dist / 2), x, clampY(y + dist / 2), 220)
            "left" -> swipe(clampX(x - distX / 2), y, clampX(x + distX / 2), y, 220)
            "right" -> swipe(clampX(x + distX / 2), y, clampX(x - distX / 2), y, 220)
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

/** The on-screen pointer: a classic arrow with an outline, and a ring that pulses on click. */
private class CursorView(context: Context) : View(context) {
    companion object { const val SIZE = 96 }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 120, 120, 255); style = Paint.Style.STROKE; strokeWidth = 6f }
    private var pulseUntil = 0L

    private val arrow = Path().apply {
        moveTo(4f, 4f); lineTo(4f, 60f); lineTo(18f, 47f); lineTo(28f, 70f); lineTo(38f, 66f); lineTo(28f, 43f); lineTo(46f, 43f); close()
    }

    fun pulse() {
        pulseUntil = SystemClock.uptimeMillis() + 250
        invalidate()
        postDelayed({ invalidate() }, 260)
    }

    override fun onDraw(canvas: Canvas) {
        if (SystemClock.uptimeMillis() < pulseUntil) canvas.drawCircle(4f, 4f, 26f, ring)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, edge)
    }
}

