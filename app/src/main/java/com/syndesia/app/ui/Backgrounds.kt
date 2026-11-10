package com.syndesia.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class BackgroundStyle(val key: String, val label: String) {
    NONE("none", "None"),
    AURORA("aurora", "Aurora"),
    BUBBLES("bubbles", "Bubbles"),
    CONSTELLATION("particles", "Constellation"),
    WAVES("waves", "Waves"),
    PULSE("rings", "Pulse"),
    GRID("grid", "Grid"),
    SPACE("space", "Space");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

private const val TAU = (2 * PI).toFloat()

/** Seconds since first frame, updated every frame. Read it inside draw lambdas only. */
@Composable
fun rememberAnimationTime(): MutableFloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withFrameNanos { n ->
                if (start < 0) start = n
                time.floatValue = (n - start) / 1_000_000_000f
            }
        }
    }
    return time
}

/** Per-style random seeds: x, y, size, speed/phase in 0..1. */
private class Seeds(count: Int, seed: Int) {
    val v: Array<FloatArray> = Random(seed).let { r ->
        Array(count) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
}

/**
 * Full-screen ambient animation drawn in the theme's accent colors. Everything is drawn in
 * the draw phase from a frame clock, so it never triggers recomposition.
 */
@Composable
fun AnimatedBackground(style: String, modifier: Modifier = Modifier, preview: Boolean = false) {
    val s = BackgroundStyle.of(style)
    if (s == BackgroundStyle.NONE) return
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.4f
    val primary = colors.primary
    val tertiary = colors.tertiary
    val secondary = colors.secondary
    val ink = colors.onSurface
    val pastel = listOf(colors.inversePrimary, colors.tertiaryContainer, colors.primaryContainer, colors.tertiary)
    val time = rememberAnimationTime()
    val seeds = remember(s) { Seeds(140, s.ordinal * 31 + 7) }
    val k = if (dark) 1f else 1.15f

    Canvas(modifier) {
        val t = time.floatValue
        when (s) {
            BackgroundStyle.AURORA -> if (dark) aurora(t, listOf(primary, tertiary, secondary, primary), 0.36f)
            else aurora(t, pastel, 0.7f)
            BackgroundStyle.BUBBLES -> bubbles(t, seeds, primary, tertiary, k, preview)
            BackgroundStyle.CONSTELLATION -> constellation(t, seeds, primary, tertiary, k, preview)
            BackgroundStyle.WAVES -> waves(t, listOf(primary, tertiary, secondary, primary), k)
            BackgroundStyle.PULSE -> pulse(t, primary, tertiary, k)
            BackgroundStyle.GRID -> grid(t, primary, tertiary, if (dark) 1f else 0.6f, preview)
            BackgroundStyle.SPACE -> space(t, seeds, if (dark) ink else primary, primary, tertiary, k, preview)
            BackgroundStyle.NONE -> Unit
        }
    }
}

// ---------------------------------------------------------------- styles

private fun DrawScope.aurora(t: Float, cols: List<Color>, strength: Float) {
    val w = size.width
    val h = size.height
    val m = max(w, h)
    cols.forEachIndexed { i, c ->
        val sp = 0.035f + i * 0.012f
        val cx = w * (0.5f + 0.42f * sin(t * sp * TAU + i * 1.7f))
        val cy = h * (0.38f + 0.34f * cos(t * sp * 0.8f * TAU + i * 2.3f))
        val r = m * (0.42f + 0.07f * sin(t * 0.25f + i * 1.3f))
        drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = strength), c.copy(alpha = 0f)), Offset(cx, cy), r),
            radius = r,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.bubbles(t: Float, seeds: Seeds, a: Color, b: Color, k: Float, preview: Boolean) {
    val w = size.width
    val h = size.height
    val n = if (preview) 10 else 26
    val scale = if (preview) 0.45f else 1f
    for (i in 0 until n) {
        val (sx, _, ss, sp) = seeds.v[i]
        val r = (5f + ss * 24f).dp.toPx() * scale
        val speed = 0.025f + sp * 0.05f
        val prog = (t * speed + sp * 7.3f) % 1f
        val y = h + r - prog * (h + 2 * r)
        val x = sx * w + sin(t * (0.6f + ss) + i) * 14.dp.toPx() * scale
        val c = if (i % 3 == 0) b else a
        val fade = min(1f, prog * 4f) * min(1f, (1f - prog) * 3f)
        drawCircle(c.copy(alpha = 0.10f * k * fade), r, Offset(x, y))
        drawCircle(c.copy(alpha = 0.32f * k * fade), r, Offset(x, y), style = Stroke(1.3.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.35f * k * fade), r * 0.18f, Offset(x - r * 0.35f, y - r * 0.35f))
    }
}

private fun DrawScope.constellation(t: Float, seeds: Seeds, a: Color, b: Color, k: Float, preview: Boolean) {
    val w = size.width
    val h = size.height
    val n = if (preview) 14 else 44
    val pts = Array(n) { i ->
        val (sx, sy, vx, vy) = seeds.v[i]
        val x = ((sx + t * (vx - 0.5f) * 0.018f) % 1f + 1f) % 1f
        val y = ((sy + t * (vy - 0.5f) * 0.012f) % 1f + 1f) % 1f
        Offset(x * w, y * h)
    }
    val maxD = min(w, h) * if (preview) 0.45f else 0.27f
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val dx = pts[i].x - pts[j].x
            val dy = pts[i].y - pts[j].y
            val d = sqrt(dx * dx + dy * dy)
            if (d < maxD) {
                val al = (1f - d / maxD) * 0.32f * k
                drawLine(a.copy(alpha = al), pts[i], pts[j], strokeWidth = 1.dp.toPx())
            }
        }
    }
    pts.forEachIndexed { i, p ->
        val pulse = 0.6f + 0.4f * sin(t * 2f + i)
        val c = if (i % 4 == 0) b else a
        drawCircle(c.copy(alpha = 0.18f * k * pulse), 7.dp.toPx(), p)
        drawCircle(c.copy(alpha = 0.75f * k), 2.4.dp.toPx(), p)
    }
}

private fun DrawScope.waves(t: Float, cols: List<Color>, k: Float) {
    val w = size.width
    val h = size.height
    cols.forEachIndexed { i, c ->
        val base = h * (0.58f + i * 0.1f)
        val amp = (16f + i * 7f).dp.toPx()
        val cycles = 1.1f + i * 0.35f
        val ph = t * (0.35f + i * 0.13f) * (if (i % 2 == 0) 1f else -1f)
        val path = Path()
        path.moveTo(0f, h)
        var x = 0f
        val step = 6.dp.toPx()
        while (x <= w + step) {
            val y = base + sin(x / w * cycles * TAU + ph) * amp + sin(x / w * 2.7f * TAU - ph * 1.4f) * amp * 0.3f
            path.lineTo(x, y)
            x += step
        }
        path.lineTo(w, h)
        path.close()
        drawPath(
            path,
            Brush.verticalGradient(
                listOf(c.copy(alpha = (0.16f + i * 0.03f) * k), c.copy(alpha = 0.04f * k)),
                startY = base - amp, endY = h,
            ),
        )
    }
}

private fun DrawScope.pulse(t: Float, a: Color, b: Color, k: Float) {
    val w = size.width
    val h = size.height
    val maxR = max(w, h) * 0.95f
    val centers = listOf(Offset(w * 0.5f, h * 0.24f) to a, Offset(w * 0.85f, h * 0.88f) to b)
    centers.forEachIndexed { ci, (c, col) ->
        drawCircle(
            Brush.radialGradient(listOf(col.copy(alpha = 0.22f * k), col.copy(alpha = 0f)), c, maxR * 0.35f),
            radius = maxR * 0.35f, center = c,
        )
        for (ring in 0 until 6) {
            val prog = ((t * 0.09f + ci * 0.37f) + ring / 6f) % 1f
            val r = prog * maxR
            val al = (1f - prog) * (1f - prog) * 0.45f * k
            drawCircle(col.copy(alpha = al), r, c, style = Stroke((1.5f + (1f - prog) * 2f).dp.toPx()))
        }
    }
}

private fun DrawScope.grid(t: Float, a: Color, b: Color, k: Float, preview: Boolean) {
    val w = size.width
    val h = size.height
    val gap = (if (preview) 12f else 26f).dp.toPx()
    val ox = w * (0.5f + 0.3f * sin(t * 0.21f))
    val oy = h * (0.35f + 0.25f * cos(t * 0.17f))
    val wl = (if (preview) 40f else 110f).dp.toPx()
    var y = gap / 2
    while (y < h) {
        var x = gap / 2
        while (x < w) {
            val dx = x - ox
            val dy = y - oy
            val d = sqrt(dx * dx + dy * dy)
            val wave = 0.5f + 0.5f * sin(d / wl * TAU - t * 1.6f)
            val e = wave * wave * wave
            val col = if (((x + y) / gap).toInt() % 5 == 0) b else a
            drawCircle(col.copy(alpha = (0.07f + 0.5f * e) * k), (1.1f + 1.6f * e).dp.toPx(), Offset(x, y))
            x += gap
        }
        y += gap
    }
}

private fun DrawScope.space(t: Float, seeds: Seeds, star: Color, a: Color, b: Color, k: Float, preview: Boolean) {
    val w = size.width
    val h = size.height
    // Nebula
    drawCircle(
        Brush.radialGradient(listOf(a.copy(alpha = 0.20f * k), a.copy(alpha = 0f)), Offset(w * 0.2f, h * 0.25f), max(w, h) * 0.5f),
        radius = max(w, h) * 0.5f, center = Offset(w * 0.2f, h * 0.25f),
    )
    drawCircle(
        Brush.radialGradient(listOf(b.copy(alpha = 0.16f * k), b.copy(alpha = 0f)), Offset(w * 0.85f, h * 0.7f), max(w, h) * 0.45f),
        radius = max(w, h) * 0.45f, center = Offset(w * 0.85f, h * 0.7f),
    )
    val n = if (preview) 40 else 130
    for (i in 0 until n) {
        val (sx, sy, ss, sp) = seeds.v[i]
        val y = ((sy + t * (0.002f + ss * 0.006f)) % 1f) * h
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.8f + sp * 3f) + i * 1.3f))
        val r = (0.5f + ss * ss * 1.8f).dp.toPx()
        drawCircle(star.copy(alpha = 0.85f * tw * k), r, Offset(sx * w, y))
    }
    // Shooting star every ~8 s
    val cycle = 8f
    val p = (t % cycle) / 1.1f
    if (p < 1f) {
        val idx = (t / cycle).toInt()
        val r = Random(idx)
        val sx = w * (0.3f + r.nextFloat() * 0.7f)
        val sy = h * (r.nextFloat() * 0.4f)
        val len = min(w, h) * 0.35f
        val head = Offset(sx - p * len * 1.6f, sy + p * len * 0.8f)
        val tail = Offset(head.x + len * 0.5f, head.y - len * 0.25f)
        val fade = sin(p * PI.toFloat())
        drawLine(
            Brush.linearGradient(listOf(star.copy(alpha = 0.9f * fade * k), star.copy(alpha = 0f)), head, tail),
            head, tail, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}
