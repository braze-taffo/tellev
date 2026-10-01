package app.tellev.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.ContextWrapper

private val ClassicLightColors = lightColorScheme(
    primary = Color(0xFF2364AA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9F6),
    onPrimaryContainer = Color(0xFF183957),
    secondary = Color(0xFF5B6C5D),
    secondaryContainer = Color(0xFFDFEADF),
    onSecondaryContainer = Color(0xFF293B2C),
    tertiary = Color(0xFF9A5C1F),
    tertiaryContainer = Color(0xFFF2E4D4),
    onTertiaryContainer = Color(0xFF533415),
    background = Color(0xFFFBFCFE),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E9F0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7FB),
    surfaceContainer = Color(0xFFEEF2F7),
    surfaceContainerHigh = Color(0xFFE7ECF3),
    surfaceContainerHighest = Color(0xFFE1E7EF),
)

private val ClassicDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1D33),
    primaryContainer = Color(0xFF243C59),
    onPrimaryContainer = Color(0xFFD9E9FE),
    secondary = Color(0xFFB8C8BA),
    secondaryContainer = Color(0xFF344637),
    onSecondaryContainer = Color(0xFFDCEBDD),
    tertiary = Color(0xFFE2B37D),
    tertiaryContainer = Color(0xFF53412D),
    onTertiaryContainer = Color(0xFFF6DDC0),
    background = Color(0xFF101214),
    surface = Color(0xFF171A1D),
    surfaceVariant = Color(0xFF2A3036),
    surfaceContainerLowest = Color(0xFF0D1014),
    surfaceContainerLow = Color(0xFF191D23),
    surfaceContainer = Color(0xFF20252B),
    surfaceContainerHigh = Color(0xFF282D35),
    surfaceContainerHighest = Color(0xFF30363F),
)

// Muted copper over paper/ink surfaces. Both accents define their own tonal
// containers, keeping the native navigation, cards and dialogs consistent.
private val WarmLightColors = lightColorScheme(
    primary = Color(0xFF9E5843),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E0D6),
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
    background = Color(0xFFF8F4EF),
    onBackground = Color(0xFF1D1B19),
    surface = Color(0xFFFFFDFB),
    onSurface = Color(0xFF1D1B19),
    surfaceVariant = Color(0xFFECE3DA),
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
    background = Color(0xFF141619),
    onBackground = Color(0xFFE9E1DB),
    surface = Color(0xFF1D1F23),
    onSurface = Color(0xFFE9E1DB),
    surfaceVariant = Color(0xFF303035),
    onSurfaceVariant = Color(0xFFCDC2BA),
    surfaceDim = Color(0xFF141619),
    surfaceBright = Color(0xFF393A3F),
    surfaceContainerLowest = Color(0xFF101114),
    surfaceContainerLow = Color(0xFF1C1E22),
    surfaceContainer = Color(0xFF222429),
    surfaceContainerHigh = Color(0xFF2B2D32),
    surfaceContainerHighest = Color(0xFF35363B),
)

@Composable
fun TellevTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: ThemeAccent = ThemeAccent.Warm,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) accent.darkColors() else accent.lightColors()
    SystemBarAppearance(colors, darkTheme)
    MaterialTheme(
        colorScheme = colors,
        typography = AtmosphereTypography,
        shapes = AtmosphereShapes,
        content = content,
    )
}

@Suppress("DEPRECATION")
@Composable
private fun SystemBarAppearance(colors: ColorScheme, darkTheme: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
            if (activity != null) {
                // Pre-Android 15 windows still draw opaque system bars. Match
                // them to the page; on newer edge-to-edge windows only icon
                // appearance needs updating when the in-app theme changes.
                activity.window.statusBarColor = colors.background.toArgb()
                activity.window.navigationBarColor = colors.surfaceContainerLow.toArgb()
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
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
    /** Muted copper — the default palette. */
    Warm,

    /** Blue accent with matching cool surfaces. */
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
