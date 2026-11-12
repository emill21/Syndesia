package com.syndesia.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Air mouse: wrist motion moves the cursor (gyroscope), a pinch clicks (a short, sharp jolt the
 * accelerometer feels when finger and thumb tap together).
 *
 * Gyro axes on the wrist, screen up: z points out of the screen, so turning the hand left/right
 * spins around z; tilting it up/down spins around y. Users can flip either if it feels backwards.
 */
object AirMouse {
    enum class Mode { Touch, Always }

    data class Settings(
        val mode: Mode = Mode.Touch,
        /** Cursor pixels per radian of wrist turn. */
        val sensitivity: Float = 900f,
        val invertX: Boolean = false,
        val invertY: Boolean = false,
        val pinch: Boolean = true,
        /** Jolt (m/s², gravity removed) a pinch must reach. Calibration sets it. */
        val pinchThreshold: Float = 2.6f,
    )

    interface Sink {
        fun move(dx: Float, dy: Float)
        fun click()
    }

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Bumped on each detected pinch, for a visual blip. */
    private val _pinches = MutableStateFlow(0)
    val pinches: StateFlow<Int> = _pinches.asStateFlow()

    /** In Touch mode the cursor only moves while this is true (finger on the screen). */
    @Volatile var clutch = false

    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null
    private var sink: Sink? = null

    // gyro state
    private var lastGyroNs = 0L
    private var wx = 0f
    private var wy = 0f
    @Volatile private var gyroMag = 0f
    private var accX = 0f
    private var accY = 0f

    // pinch state
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var primed = 0
    private var spikeStart = 0L
    private var spikePeak = 0f
    private var lastPinch = 0L
    /** While calibrating, peaks go here instead of clicking. */
    @Volatile var calibrating: ((Float) -> Unit)? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("air", Context.MODE_PRIVATE)
        _settings.value = Settings(
            mode = runCatching { Mode.valueOf(p.getString("mode", Mode.Touch.name)!!) }.getOrDefault(Mode.Touch),
            sensitivity = p.getFloat("sens", 900f),
            invertX = p.getBoolean("ix", false),
            invertY = p.getBoolean("iy", false),
            pinch = p.getBoolean("pinch", true),
            pinchThreshold = p.getFloat("thr", 2.6f),
        )
    }

    fun update(ctx: Context, s: Settings) {
        _settings.value = s
        ctx.getSharedPreferences("air", Context.MODE_PRIVATE).edit()
            .putString("mode", s.mode.name).putFloat("sens", s.sensitivity)
            .putBoolean("ix", s.invertX).putBoolean("iy", s.invertY)
            .putBoolean("pinch", s.pinch).putFloat("thr", s.pinchThreshold).apply()
    }

    fun hasSensors(ctx: Context): Boolean {
        val m = ctx.getSystemService(SensorManager::class.java) ?: return false
        return m.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null && m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    fun start(ctx: Context, target: Sink) {
        stop()
        load(ctx)
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val gyro = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val t = HandlerThread("air-mouse").also { it.start() }
        val h = Handler(t.looper)
        sm = m; thread = t; sink = target
        lastGyroNs = 0L; wx = 0f; wy = 0f; primed = 0
        m.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_FASTEST, h)
        if (acc != null) m.registerListener(listener, acc, SensorManager.SENSOR_DELAY_FASTEST, h)
        // Send moves in small batches (~80 a second) so Bluetooth isn't flooded.
        h.post(object : Runnable {
            override fun run() {
                if (sink == null) return
                if (accX != 0f || accY != 0f) {
                    val dx = accX; val dy = accY
                    accX = 0f; accY = 0f
                    sink?.move(dx, dy)
                }
                h.postDelayed(this, 12)
            }
        })
        _running.value = true
    }

    fun stop() {
        sm?.unregisterListener(listener)
        thread?.quitSafely()
        sm = null; thread = null; sink = null
        clutch = false
        _running.value = false
    }

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> onGyro(e)
                Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            }
        }
    }

    private fun onGyro(e: SensorEvent) {
        val s = _settings.value
        val rawY = e.values[1]
        val rawZ = e.values[2]
        gyroMag = sqrt(e.values[0] * e.values[0] + rawY * rawY + rawZ * rawZ)
        if (lastGyroNs == 0L) { lastGyroNs = e.timestamp; return }
        val dt = ((e.timestamp - lastGyroNs) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroNs = e.timestamp
        // Smooth out hand tremor, then ignore tiny drift.
        wx += (-rawZ - wx) * 0.4f
        wy += (-rawY - wy) * 0.4f
        val active = s.mode == Mode.Always || clutch
        if (!active) return
        fun dead(v: Float) = if (abs(v) < 0.035f) 0f else v - 0.035f * Math.signum(v)
        val vx = dead(wx)
        val vy = dead(wy)
        if (vx == 0f && vy == 0f) return
        // Gentle acceleration: slow turns stay precise, fast flicks cross the screen.
        val speed = sqrt(vx * vx + vy * vy)
        val accel = 1f + (speed * 0.9f).coerceAtMost(2.2f)
        val k = s.sensitivity * dt * accel
        accX += vx * k * (if (s.invertX) -1 else 1)
        accY += vy * k * (if (s.invertY) -1 else 1)
    }

    private fun onAccel(e: SensorEvent) {
        val s = _settings.value
        val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
        // Slowly-moving average = gravity + arm motion; what's left is the sharp part of a pinch.
        if (primed < 20) { gx = ax; gy = ay; gz = az; primed++; return }
        gx += (ax - gx) * 0.08f; gy += (ay - gy) * 0.08f; gz += (az - gz) * 0.08f
        val hx = ax - gx; val hy = ay - gy; val hz = az - gz
        val mag = sqrt(hx * hx + hy * hy + hz * hz)
        val now = SystemClock.uptimeMillis()
        val cal = calibrating
        val thr = if (cal != null) 1.2f else s.pinchThreshold
        if (spikeStart == 0L) {
            if (mag > thr) { spikeStart = now; spikePeak = mag }
            return
        }
        if (mag > spikePeak) spikePeak = mag
        if (mag < thr * 0.5f) {
            val dur = now - spikeStart
            val peak = spikePeak
            spikeStart = 0L
            // A pinch is a short jolt while the arm is fairly still; swings and bumps last longer.
            val brief = dur in 0..70
            val still = gyroMag < 2.2f
            if (brief && still && now - lastPinch > 180) {
                lastPinch = now
                if (cal != null) cal(peak)
                else if (s.pinch) { sink?.click(); _pinches.value++ }
            }
        } else if (now - spikeStart > 150) {
            spikeStart = 0L
        }
    }
}
