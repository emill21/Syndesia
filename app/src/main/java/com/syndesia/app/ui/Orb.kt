package com.syndesia.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2 * PI).toFloat()

/**
 * A glowing glass orb filled with sloshing liquid. [level] is how full it is (0..1), [progress]
 * draws a ring around it, [spinning] adds a radar sweep, and bumping [burst] fires a particle
 * explosion. Everything animates in the draw phase, so it never recomposes per frame.
 */
@Composable
fun LinkOrb(
    level: Float,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    spinning: Boolean = false,
    burst: Int = 0,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val tertiary = colors.tertiary
    val secondary = colors.secondary
    val time = rememberAnimationTime()

    val fill by animateFloatAsState(
        level.coerceIn(0f, 1f),
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessVeryLow),
        label = "fill",
    )
    val ring by animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), tween(600), label = "ring")
    val ringAlpha by animateFloatAsState(if (progress != null) 1f else 0f, tween(400), label = "ringAlpha")
    val spin by animateFloatAsState(if (spinning) 1f else 0f, tween(500), label = "spin")

    val boom = remember { Animatable(1f) }
    LaunchedEffect(burst) {
        if (burst > 0) {
            boom.snapTo(0f)
            boom.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
        }
    }
    val sparks = remember {
        val r = Random(42)
        List(48) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.floatValue
            val c = center
            val outer = min(size.width, size.height) / 2f
            val r = outer * 0.74f

            // Breathing glow.
            val glowR = outer * (1.0f + 0.04f * sin(t * 1.6f))
            drawCircle(
                Brush.radialGradient(listOf(primary.copy(alpha = 0.32f), primary.copy(alpha = 0f)), c, glowR),
                glowR, c,
            )

            // Glass.
            drawCircle(primary.copy(alpha = 0.08f), r, c)

            // Liquid, two layered waves, clipped to the orb.
            val orb = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(orb) {
                val surface = c.y + r - 2 * r * fill
                val amp = r * (0.05f + 0.04f * spin)
                wave(surface, amp, t * 1.4f, r, c, tertiary.copy(alpha = 0.45f), 1.3f)
                wave(surface + amp * 0.4f, amp, -t * 1.9f + 1.7f, r, c, primary.copy(alpha = 0.7f), 1.0f)
                // Bubbles rising through the liquid.
                if (fill > 0.02f) for (i in 0 until 10) {
                    val s = sparks[i]
                    val p = (t * (0.08f + s[3] * 0.12f) + s[1]) % 1f
                    val y = c.y + r - p * (c.y + r - surface)
                    val x = c.x + (s[0] - 0.5f) * r * 1.4f + sin(t * 2 + i) * 4.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.22f * (1 - p)), (1.5f + s[2] * 3f).dp.toPx(), Offset(x, y))
                }
            }

            // Rim and glass highlight.
            drawCircle(primary.copy(alpha = 0.35f), r, c, style = Stroke(1.5.dp.toPx()))
            drawArc(
                Color.White.copy(alpha = 0.28f), 200f, 50f, false,
                topLeft = Offset(c.x - r * 0.82f, c.y - r * 0.82f), size = Size(r * 1.64f, r * 1.64f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )

            // Progress ring.
            val ringR = outer * 0.9f
            val ringTl = Offset(c.x - ringR, c.y - ringR)
            val ringSz = Size(ringR * 2, ringR * 2)
            val stroke = 8.dp.toPx()
            if (ringAlpha > 0f) {
                drawCircle(primary.copy(alpha = 0.12f * ringAlpha), ringR, c, style = Stroke(stroke))
                drawArc(
                    primary.copy(alpha = ringAlpha), -90f, 360f * ring, false, ringTl, ringSz,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            // Radar sweep.
            if (spin > 0f) rotate(t * 140f % 360f, c) {
                drawArc(
                    Brush.sweepGradient(
                        0f to Color.Transparent, 0.75f to Color.Transparent,
                        1f to tertiary.copy(alpha = 0.9f * spin), center = c,
                    ),
                    0f, 359f, false, ringTl, ringSz, style = Stroke(stroke * 0.6f, cap = StrokeCap.Round),
                )
                drawCircle(tertiary.copy(alpha = spin), stroke * 0.55f, Offset(c.x + ringR, c.y))
            }

            // Celebration burst.
            val b = boom.value
            if (b < 1f) {
                val fade = 1f - b
                drawCircle(primary.copy(alpha = 0.5f * fade), r + (outer * 1.1f - r) * b, c, style = Stroke((6 * fade + 1).dp.toPx()))
                sparks.forEachIndexed { i, s ->
                    val ang = (i / sparks.size.toFloat() + s[0] * 0.03f) * TAU
                    val dist = r * (0.9f + (0.5f + s[1]) * 1.1f * b)
                    val col = when (i % 3) { 0 -> primary; 1 -> tertiary; else -> secondary }
                    val p = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist + 30.dp.toPx() * b * b * s[2])
                    drawCircle(col.copy(alpha = fade), (2f + s[3] * 4f).dp.toPx() * (1f - 0.5f * b), p)
                }
            }
        }
        content()
    }
}

private fun DrawScope.wave(surface: Float, amp: Float, phase: Float, r: Float, c: Offset, color: Color, freq: Float) {
    val left = c.x - r
    val right = c.x + r
    val path = Path().apply {
        moveTo(left, size.height)
        var x = left
        val step = 6f
        while (x <= right + step) {
            val k = (x - left) / (2 * r)
            lineTo(x, surface + sin(k * TAU * freq + phase) * amp)
            x += step
        }
        lineTo(right, size.height)
        close()
    }
    drawPath(path, color)
}
