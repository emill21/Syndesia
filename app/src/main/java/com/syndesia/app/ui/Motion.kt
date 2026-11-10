package com.syndesia.app.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/** Fades, lifts and scales content in, staggered by [index]. Plays once per screen visit. */
@Composable
fun Entrance(index: Int, content: @Composable () -> Unit) {
    val shown = rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!shown.value) {
            delay(index.coerceAtMost(8) * 55L)
            progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow))
            shown.value = true
        }
    }
    Box(
        Modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 48.dp.toPx()
            val sc = 0.94f + 0.06f * p
            scaleX = sc
            scaleY = sc
        }
    ) { content() }
}

/** `item {}` with a staggered entrance. */
fun LazyListScope.animatedItem(index: Int, content: @Composable () -> Unit) {
    item { Entrance(index) { content() } }
}

/** Springy shrink while pressed. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Text whose characters roll up or down like an odometer when they change. */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val up = targetState.isDigit() && initialState.isDigit() && targetState > initialState
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(280)) { h -> h * dir } + fadeIn(tween(280)))
                            .togetherWith(slideOutVertically(tween(280)) { h -> -h * dir } + fadeOut(tween(200)))
                            .using(SizeTransform(clip = true))
                    },
                    label = "roll",
                ) { c ->
                    Text(c.toString(), style = style, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Slope of a level water surface in screen coordinates, from the gravity sensor.
 * 0 when the phone is upright; smoothed and clamped.
 */
@Composable
fun rememberLiquidTilt(): MutableFloatState {
    val context = LocalContext.current
    val tilt = remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]
                val ay = e.values[1]
                val target = (ax / max(abs(ay), 2.5f)).coerceIn(-0.55f, 0.55f)
                tilt.floatValue += (target - tilt.floatValue) * 0.12f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}
