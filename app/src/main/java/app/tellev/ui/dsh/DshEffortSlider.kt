package app.tellev.ui.dsh

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Preview stays local while dragging; only the final snapped level is persisted. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DshEffortSlider(
    index: Int,
    levelCount: Int,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueLabel: String? = null,
) {
    if (levelCount <= 0) return
    val last = (levelCount - 1).coerceAtLeast(0)
    val committed = index.coerceIn(0, last)
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(committed.toFloat()) }
    LaunchedEffect(committed, levelCount, enabled) {
        dragging = false
        value = committed.toFloat()
    }
    val fraction = if (last == 0) 0f else value / last
    val animatedFraction = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(if (dragging) 0 else 140),
        label = "effort-position",
    )
    val clock = if (enabled && fraction > 0f) produceAnimationMillis() else null
    val radius = with(LocalDensity.current) { 14.dp.toPx() }
    val cell = with(LocalDensity.current) { 4.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier = modifier.fillMaxWidth().height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(30.dp).clip(CircleShape),
        ) {
            val progress = animatedFraction.value
            val center = effortSliderCenter(size.width, radius, progress)
            withTransform({
                if (rtl) scale(-1f, 1f, Offset(size.width / 2f, size.height / 2f))
            }) {
            drawRect(
                brush = Brush.linearGradient(
                    0.00f to Color(0xFF03040A),
                    0.22f to Color(0xFF071126),
                    0.45f to Color(0xFF101D4C),
                    0.70f to Color(0xFF302262),
                    1.00f to Color(0xFF5D35A0),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFFBDC7FF).copy(alpha = 0.15f),
                    0.06f to Color.Transparent,
                    0.94f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                ),
                style = Stroke(width = 2f),
            )
            val time = clock?.value ?: 0f
            if (progress > 0f) {
                drawRadiation(
                    time = time,
                    progress = center / size.width.coerceAtLeast(1f),
                    dragging = dragging,
                    cellPx = cell,
                )
                val pulse = if (value >= last && last > 0) {
                    0.5f + 0.5f * sin(time / 1900f * 2f * PI.toFloat())
                } else 0f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.90f),
                        0.30f to Color(0xFFBCBDFF).copy(alpha = 0.70f),
                        0.65f to Color(0xFF7952FF).copy(alpha = 0.35f + 0.15f * pulse),
                        1f to Color.Transparent,
                        center = Offset(center, size.height / 2f),
                        radius = radius * (2f + pulse * 0.2f),
                    ),
                    radius = radius * (2f + pulse * 0.2f),
                    center = Offset(center, size.height / 2f),
                )
            }
            val knobRadius = min(radius, size.height / 2f) * if (dragging) 1.04f else 1f
            drawCircle(
                color = if (enabled) Color.White else Color(0xFFB8B9C4),
                radius = knobRadius,
                center = Offset(center, size.height / 2f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.94f),
                radius = (knobRadius - 0.5f).coerceAtLeast(0f),
                center = Offset(center, size.height / 2f),
                style = Stroke(width = 1f),
            )
            }
        }
        // Native Slider owns touch slop, cancellation, accessibility and RTL geometry.
        Slider(
            value = value,
            onValueChange = { next ->
                dragging = true
                value = next
                onPreview(effortSliderIndex(next, levelCount))
            },
            onValueChangeFinished = {
                val next = effortSliderIndex(value, levelCount)
                dragging = false
                value = next.toFloat()
                onCommit(next)
            },
            enabled = enabled && last > 0,
            valueRange = 0f..last.coerceAtLeast(1).toFloat(),
            steps = (last - 1).coerceAtLeast(0),
            thumb = { Spacer(Modifier.size(28.dp)) },
            track = { Spacer(Modifier.fillMaxWidth().height(30.dp)) },
            modifier = Modifier.fillMaxSize().testTag("dsh-effort-slider").semantics {
                if (valueLabel != null) stateDescription = valueLabel
            },
        )
    }
}

private fun DrawScope.drawRadiation(
    time: Float,
    progress: Float,
    dragging: Boolean,
    cellPx: Float,
) {
    val widthPx = size.width
    val heightPx = size.height
    val origin = progress * widthPx
    if (origin <= 0f) return
    val cell = cellPx
    val speed = if (dragging) 2.8f else 1f

    clipRect(left = 0f, top = 0f, right = origin, bottom = heightPx) {
        var x = 0f
        while (x < origin) {
            val delta = x + cell * 0.5f - origin
            val distance = abs(delta)
            val phaseA = distance / 10f - time * 0.0074f * speed
            val phaseB = distance / 23f - time * 0.0041f * speed + 1.7f
            val phaseC = distance / 40f - time * 0.0022f * speed + 3.4f
            val sinA = max(0f, sin(phaseA))
            val sinB = max(0f, sin(phaseB))
            val sinC = max(0f, sin(phaseC))
            val waveA = sinA.pow(2.6f)
            val waveB = sinB.pow(3.2f)
            val waveC = sinC.pow(4f)
            val crest = sinA.pow(15f) + sinB.pow(18f) * 0.78f
            val wave = min(1f, waveA * 0.76f + waveB * 0.58f + waveC * 0.32f)
            val trail = 0.38f + 0.62f * exp(-distance / max(55f, widthPx * 0.72f))
            val pillar = max(0f, sin(x / 20f + time * 0.0016f)).pow(3f) * 0.27f
            val columnEnergy = trail * (wave * 1.04f + pillar + crest * 0.32f)
            val nearness = max(0f, 1f - distance / max(1f, widthPx * 0.78f))

            if (columnEnergy > 0.012f) {
                val red = (42 + 124 * nearness + 75 * wave).roundToInt().coerceIn(0, 255)
                val green = (56 + 58 * nearness + 44 * crest).roundToInt().coerceIn(0, 255)
                val blue = (175 + 72 * nearness + 8 * wave).roundToInt().coerceIn(0, 255)
                val alpha = min(0.88f, columnEnergy * 0.72f)
                drawRect(
                    color = Color(red / 255f, green / 255f, blue / 255f, alpha),
                    topLeft = Offset(x, 0f),
                    size = Size(max(1f, cell - 1f), heightPx),
                )
            }

            var y = 0f
            while (y < heightPx) {
                val deltaY = y + cell * 0.5f - heightPx * 0.5f
                val radial = hypot((delta / 38f).toDouble(), (deltaY / 11f).toDouble()).toFloat()
                val halo = exp(-radial * 0.96f) * 1.08f
                val verticalShape = 0.58f + 0.42f * cos((deltaY / heightPx) * PI.toFloat())
                val grain = 0.72f + 0.28f * sin(x * 0.73f + y * 1.31f + time * 0.006f)
                val alpha = min(0.96f, (columnEnergy * 0.88f + halo + crest * 0.19f) * verticalShape * grain)
                if (alpha >= 0.035f) {
                    val hot = max(0f, 1f - radial / 2.4f)
                    val red = (54 + 148 * hot + 42 * wave + 35 * crest).roundToInt().coerceIn(0, 255)
                    val green = (68 + 78 * hot + 46 * crest).roundToInt().coerceIn(0, 255)
                    val blue = (186 + 64 * hot).roundToInt().coerceIn(0, 255)
                    drawRect(
                        color = Color(red / 255f, green / 255f, blue / 255f, alpha),
                        topLeft = Offset(x, y),
                        size = Size(max(1f, cell - 1f), max(1f, cell - 1f)),
                    )
                }
                y += cell
            }
            x += cell
        }

        for (i in 0 until 14) {
            val travel = (time * (if (dragging) 0.16f else 0.065f) * (0.78f + (i % 5) * 0.09f) + i * 23f) %
                max(30f, origin + 64f)
            val particleX = origin - travel
            if (particleX < -24f || particleX > widthPx + 16f) continue
            val particleY = 3f + ((i * 13 + sin(time * 0.003f + i) * 5f) %
                max(7f, heightPx - 6f))
            val length = 4f + (i % 4) * 4f + (if (dragging) 6f else 0f)
            val alpha = 0.28f + (i % 5) * 0.1f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color(0xFF4876FF).copy(alpha = 0f),
                    0.68f to Color(0xFF7087FF).copy(alpha = alpha),
                    1f to Color(0xFFECDEFF).copy(alpha = min(1f, alpha + 0.26f)),
                    startX = particleX,
                    endX = particleX + length,
                ),
                topLeft = Offset(particleX, particleY),
                size = Size(length, if (i % 3 == 0) 2f else 1f),
            )
        }
    }
}

@Composable
private fun produceAnimationMillis(): State<Float> {
    val transition = rememberInfiniteTransition(label = "effort-clock")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 600_000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600_000, easing = LinearEasing),
        ),
        label = "effort-clock",
    )
}
