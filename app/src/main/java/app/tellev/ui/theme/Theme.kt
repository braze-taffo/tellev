package app.tellev.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2364AA),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6C5D),
    tertiary = Color(0xFF9A5C1F),
    background = Color(0xFFFBFCFE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E9F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1D33),
    secondary = Color(0xFFB8C8BA),
    tertiary = Color(0xFFE2B37D),
    background = Color(0xFF101214),
    surface = Color(0xFF171A1D),
    surfaceVariant = Color(0xFF2A3036),
)

@Composable
fun TellevTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

