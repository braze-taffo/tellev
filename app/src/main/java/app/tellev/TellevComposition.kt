package app.tellev

import androidx.compose.runtime.staticCompositionLocalOf

val LocalTellevGraph = staticCompositionLocalOf<TellevGraph> {
    error("TellevGraph is not provided")
}

