package com.flowstate.focus.ui.dock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowstate.focus.domain.WaterPhysics
import com.flowstate.focus.ui.components.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

/**
 * A capsule that fills with water as today's focus adds up toward the daily goal. While a focus block
 * runs, the level rises in real time, ripples move and bubbles drift up. With Liquid UI on, the water
 * listens to the gravity sensor (fused from the gyroscope and accelerometer where the phone has both):
 * tilt the phone and the surface leans to stay level, overshoots and sloshes back.
 */
@Composable
fun WaterGoalBar(
    liquid: Boolean,
    modifier: Modifier = Modifier,
    vm: DailyGoalViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timer = state.timer
    val running = timer.isFocusRunning

    // Tick once a second while focus runs, so the level climbs with the timer.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        now = System.currentTimeMillis()
        while (running) { delay(1_000); now = System.currentTimeMillis() }
    }
    val liveMs = if (timer.isFocusInProgress) timer.elapsedAt(now) else 0L
    val fraction = WaterPhysics.fillFraction(state.recordedTodayMs, liveMs, state.goalMin)
    val level by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "waterLevel",
    )

    val tilt = rememberTilt(enabled = liquid)
    val doneMs = state.recordedTodayMs + liveMs
    val percent = (fraction * 100).toInt()

    val cs = MaterialTheme.colorScheme
    val glass = cs.surfaceContainerHigh
    val water = cs.primary
    val waterBack = lerp(cs.primary, cs.background, 0.45f)
    val shape = RoundedCornerShape(percent = 50)

    Column(
        modifier = modifier.semantics {
            contentDescription = "Daily goal: ${formatDuration(doneMs)} of ${formatDuration(state.goalMin * 60_000L)}, $percent percent"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(shape)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRoundRect(glass, cornerRadius = CornerRadius(w / 2, w / 2))
                if (level <= 0.001f) return@Canvas
                val baseY = h * (1f - level)
                val slope = tan(tilt.angle)
                val amp = w * if (liquid) WaterPhysics.waveAmplitude(running, tilt.velocity) else 0f
                fun surface(phaseShift: Float, ampScale: Float): Path = Path().apply {
                    val steps = 24
                    moveTo(0f, h)
                    for (i in 0..steps) {
                        val x = w * i / steps
                        val y = baseY - slope * (x - w / 2) +
                            amp * ampScale * sin((x / w) * 2f * PI.toFloat() + tilt.phase + phaseShift)
                        lineTo(x, y)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(surface(PI.toFloat() * 0.8f, 0.8f), waterBack)
                drawPath(surface(0f, 1f), water)
                // Bubbles drift up only while a focus block is running.
                if (running && liquid) {
                    val bubble = lerp(water, Color.White, 0.55f).copy(alpha = 0.55f)
                    repeat(4) { i ->
                        val t = ((tilt.phase / (2f * PI.toFloat())) * 0.35f + i * 0.27f) % 1f
                        val y = h - t * (h - baseY)
                        val x = w * (0.28f + 0.44f * ((i * 37) % 10) / 10f) + sin(t * 9f + i) * w * 0.05f
                        if (y > baseY + w * 0.12f) drawCircle(bubble, radius = w * (0.045f + 0.02f * (i % 2)), center = Offset(x, y))
                    }
                }
            }
            val onWater = if (water.luminance() < 0.18f) Color.White else Color(0xFF101418)
            val labelColor = if (level > 0.12f) onWater else cs.onSurfaceVariant
            Text(
                "$percent%",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                color = labelColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            formatDuration(doneMs),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private class TiltState {
    var angle by mutableFloatStateOf(0f)
    var velocity by mutableFloatStateOf(0f)
    /** Ripple phase, advanced every frame. */
    var phase by mutableFloatStateOf(0f)
}

/**
 * Reads the gravity sensor (accelerometer as a fallback) and runs the slosh spring every frame.
 * The listener is registered only while the bar is on screen and Liquid UI is on.
 */
@Composable
private fun rememberTilt(enabled: Boolean): TiltState {
    val context = LocalContext.current
    val tilt = remember { TiltState() }
    var target by remember { mutableStateOf(0f) }

    DisposableEffect(enabled) {
        if (!enabled) { target = 0f; return@DisposableEffect onDispose { } }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { target = WaterPhysics.targetAngle(e.values[0], e.values[1]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    LaunchedEffect(enabled) {
        if (!enabled) { tilt.angle = 0f; tilt.velocity = 0f; return@LaunchedEffect }
        var last = 0L
        var slosh = WaterPhysics.Slosh()
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else (t - last) / 1_000_000_000f
                last = t
                slosh = WaterPhysics.step(slosh, target, dt)
                tilt.angle = slosh.angle
                tilt.velocity = slosh.velocity
                tilt.phase = (tilt.phase + dt * 2.2f) % (2f * PI.toFloat() * 100f)
            }
        }
    }
    return tilt
}
