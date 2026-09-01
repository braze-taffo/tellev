package app.tellev.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClassicLightColors = lightColorScheme(
    primary = Color(0xFF2364AA),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6C5D),
    tertiary = Color(0xFF9A5C1F),
    background = Color(0xFFFBFCFE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E9F0),
)

private val ClassicDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1D33),
    secondary = Color(0xFFB8C8BA),
    tertiary = Color(0xFFE2B37D),
    background = Color(0xFF101214),
    surface = Color(0xFF171A1D),
    surfaceVariant = Color(0xFF2A3036),
)

// Warm terracotta palette anchored on #D97757. Unlike Classic, every
// container/surface role the app reads is overridden so no Material3
// baseline (purple) defaults can leak through. This includes the tonal
// surface set (surfaceContainer and friends) which NavigationBar, menus
// and dialogs resolve through in material3 1.3.x.
private val WarmLightColors = lightColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DDD2),
    onPrimaryContainer = Color(0xFF5B2313),
    secondary = Color(0xFF75564B),
    onSecondary = Color.White,
    // One step deeper than the nav-bar surfaceContainer so the selected-tab
    // indicator pill stays distinguishable on the warm bar.
    secondaryContainer = Color(0xFFF1D9CC),
    onSecondaryContainer = Color(0xFF2A1710),
    tertiary = Color(0xFF9A5C1F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8DEC2),
    onTertiaryContainer = Color(0xFF31220F),
    background = Color(0xFFFBF7F3),
    onBackground = Color(0xFF1D1B19),
    surface = Color(0xFFFFFDFB),
    onSurface = Color(0xFF1D1B19),
    surfaceVariant = Color(0xFFEAE1D9),
    onSurfaceVariant = Color(0xFF544D46),
    surfaceDim = Color(0xFFE9DAD0),
    surfaceBright = Color(0xFFFEFAF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF5EF),
    surfaceContainer = Color(0xFFF7EDE5),
    surfaceContainerHigh = Color(0xFFF1E5DB),
    surfaceContainerHighest = Color(0xFFEBDCD1),
)

private val WarmDarkColors = darkColorScheme(
    primary = Color(0xFFEFAE92),
    onPrimary = Color(0xFF491A0B),
    primaryContainer = Color(0xFF6E3A26),
    onPrimaryContainer = Color(0xFFFADBCB),
    secondary = Color(0xFFD8BCB2),
    onSecondary = Color(0xFF3A2922),
    secondaryContainer = Color(0xFF54433D),
    onSecondaryContainer = Color(0xFFF0E0DA),
    tertiary = Color(0xFFE2B37D),
    onTertiary = Color(0xFF3E2D17),
    tertiaryContainer = Color(0xFF5C412C),
    onTertiaryContainer = Color(0xFFF6DBBE),
    background = Color(0xFF141111),
    onBackground = Color(0xFFE9E1DB),
    surface = Color(0xFF1C1715),
    onSurface = Color(0xFFE9E1DB),
    surfaceVariant = Color(0xFF322A26),
    onSurfaceVariant = Color(0xFFCDC2BA),
    surfaceDim = Color(0xFF141111),
    surfaceBright = Color(0xFF3C3430),
    surfaceContainerLowest = Color(0xFF0F0C0B),
    surfaceContainerLow = Color(0xFF1D1816),
    surfaceContainer = Color(0xFF221C19),
    surfaceContainerHigh = Color(0xFF2C2521),
    surfaceContainerHighest = Color(0xFF362E2A),
)

@Composable
fun TellevTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: ThemeAccent = ThemeAccent.Warm,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) accent.darkColors() else accent.lightColors(),
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** App-wide theme preference; persisted as its name in AppPreferences. */
enum class ThemeMode {
    Light, Dark, System,
}

fun ThemeMode.isDarkTheme(systemInDark: Boolean): Boolean = when (this) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemInDark
}

/** Tolerant parse for values read from storage; unknown names fall back to System. */
fun parseThemeMode(name: String?): ThemeMode =
    if (name == null) ThemeMode.System
    else runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.System)

/** App-wide accent palette preference; persisted as its name in AppPreferences. */
enum class ThemeAccent {
    /** Terracotta (#D97757) — the default palette. */
    Warm,

    /** The original blue primary with the Material3 baseline (purple) containers. */
    Classic,
}

fun ThemeAccent.lightColors(): ColorScheme = when (this) {
    ThemeAccent.Warm -> WarmLightColors
    ThemeAccent.Classic -> ClassicLightColors
}

fun ThemeAccent.darkColors(): ColorScheme = when (this) {
    ThemeAccent.Warm -> WarmDarkColors
    ThemeAccent.Classic -> ClassicDarkColors
}

/** Tolerant parse for values read from storage; unknown names fall back to Warm. */
fun parseThemeAccent(name: String?): ThemeAccent =
    if (name == null) ThemeAccent.Warm
    else runCatching { ThemeAccent.valueOf(name) }.getOrDefault(ThemeAccent.Warm)
