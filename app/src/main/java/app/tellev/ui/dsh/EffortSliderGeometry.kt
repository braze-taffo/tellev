package app.tellev.ui.dsh

import kotlin.math.roundToInt

internal fun effortSliderCenter(width: Float, radius: Float, fraction: Float): Float {
    val inset = radius.coerceIn(0f, width.coerceAtLeast(0f) / 2f)
    return inset + fraction.coerceIn(0f, 1f) * (width - 2f * inset).coerceAtLeast(0f)
}

internal fun effortSliderIndex(value: Float, levelCount: Int): Int =
    if (!value.isFinite() || levelCount <= 1) 0
    else value.roundToInt().coerceIn(0, levelCount - 1)
