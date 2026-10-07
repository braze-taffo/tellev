package app.tellev.ui.dsh

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * DeepSeek Harness 官方设计 token 逐值复刻。
 * 来源：harness packages/client/ui-theme/src/styles/design-platform.css 与 base.css
 * 的字面值（圆角体系 4/8/12/16/20/28；正文 14px/24px；secondary 13px）。
 *
 * 亮色是官方 design-platform.css:166-282 的 alias 字面值；暗色是 :285-407 同名
 * token 的暗色字面值（不是凭感觉反相）。聊天界面跟随应用主题模式切换。
 */
@Immutable
data class DshPalette(
    val isDark: Boolean,
    // bg-base：亮 rgb(255,255,255) / 暗 neutral-bluish-950 rgb(21,21,23)
    val bgBase: Color,
    // bg-layer-1/2/3：亮全白；暗 875/850/800 = rgb(35,35,36)/rgb(44,44,46)/rgb(53,54,56)
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    // specific-sidebar-fill：亮 bluish-50 rgb(249,250,251) / 暗 bluish-900 rgb(27,27,28)
    val sidebarFill: Color,
    // specific-bubble（用户气泡）：亮 deepseek-50 rgb(237,243,254) / 暗 bluish-850 rgb(44,44,46)
    val userBubble: Color,
    // specific-input-major（composer 卡）：亮白 / 暗 bluish-850 rgb(44,44,46)
    val inputMajor: Color,
    // specific-selector（+ 按钮底）：亮 bluish-60 rgb(245,246,247) / 暗 bluish-800 rgb(53,54,56)
    val selector: Color,
    // interactive-bg-hover：亮 rgba(38,49,72,.06) / 暗 rgba(255,255,255,.08)
    val hover: Color,
    // label-primary/secondary/tertiary/caption
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textCaption: Color,
    // border l1..l4：亮黑 4%/10%/12%/16%，暗白 6%/12%/16%/20%
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    // state-business-primary：亮 deepseek-500 rgb(65,118,230) / 暗 deepseek-400 rgb(122,170,255)
    val blue: Color,
    // button-info-fill（发送键）：亮 #3964FE / 暗 #679EFE（InputBar.module.css 注释字面值）
    val sendBlue: Color,
    val blue400: Color,
    // state-error-primary：亮 red-600 rgb(236,19,19) / 暗 red-400 rgb(242,90,90)
    val errorPrimary: Color,
    // 状态点：green-500 / amber-500 / idle neutral-300（暗 rgb(84,85,87)）
    val statusGreen: Color,
    val statusAmber: Color,
    val statusIdle: Color,
    // menu-surface-fill：亮 rgba(248,249,250,.58)+blur / 暗 rgba(67,69,74,.45)+blur。
    // Compose 无廉价 backdrop-blur，取等效高不透明近似（亮 F8F9FA / 暗 43454A）。
    val menuSurface: Color,
    // 上下文分段条（ContextMeter.module.css:121-132 字面值）：
    // System neutral-bluish-400 / Tools 紫 rgb(167,139,250) / Messages blue-450 rgb(77,147,248)
    val segSystem: Color,
    val segTools: Color,
    val segMessages: Color,
    /** bre 思考滑杆渐变（dsh-work styles.ts 100deg 五段，非官方宿主 token）。 */
    val trackDark: List<Color>,
)

/** 亮色：harness design-platform.css 亮色块字面值。 */
val DshLightPalette = DshPalette(
    isDark = false,
    bgBase = Color(0xFFFFFFFF),
    bgLayer1 = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    sidebarFill = Color(0xFFF9FAFB),        // rgb(249,250,251)
    userBubble = Color(0xFFEDF3FE),         // deepseek-50 rgb(237,243,254)
    inputMajor = Color(0xFFFFFFFF),
    selector = Color(0xFFF5F6F7),           // rgb(245,246,247)
    hover = Color(0x0F263148),              // rgba(38,49,72,.06)
    textPrimary = Color(0xFF0F1115),        // rgb(15,17,21)
    textSecondary = Color(0xFF61666B),      // rgb(97,102,107)
    textTertiary = Color(0xFF81858C),       // rgb(129,133,140)
    textCaption = Color(0xFFADB2B8),        // rgb(173,178,184)
    borderL1 = Color(0x0A000000),           // rgba(0,0,0,.04)
    borderL2 = Color(0x1A000000),           // rgba(0,0,0,.10)
    borderL3 = Color(0x1F000000),           // rgba(0,0,0,.12)
    borderL4 = Color(0x29000000),           // rgba(0,0,0,.16)
    blue = Color(0xFF4176E6),               // deepseek-500 rgb(65,118,230)
    sendBlue = Color(0xFF3964FE),           // button-info-fill 亮
    blue400 = Color(0xFF7AAAFF),            // deepseek-400 rgb(122,170,255)
    errorPrimary = Color(0xFFEC1313),       // red-600 rgb(236,19,19)
    statusGreen = Color(0xFF22C55E),        // green-500
    statusAmber = Color(0xFFF59E0B),        // amber-500
    statusIdle = Color(0xFFD4D4D4),         // neutral-300
    menuSurface = Color(0xFFF8F9FA),
    segSystem = Color(0xFFADB2B8),          // neutral-bluish-400
    segTools = Color(0xFFA78BFA),           // rgb(167,139,250)
    segMessages = Color(0xFF4D93F8),        // blue-450 rgb(77,147,248)
    trackDark = listOf(
        Color(0xFF03040A), Color(0xFF071126), Color(0xFF101D4C),
        Color(0xFF302262), Color(0xFF5D35A0),
    ),
)

/** 暗色：harness design-platform.css 暗色块（:285-407）同名字面值。 */
val DshDarkPalette = DshPalette(
    isDark = true,
    bgBase = Color(0xFF151517),             // neutral-bluish-950 rgb(21,21,23)
    bgLayer1 = Color(0xFF232324),           // 875
    bgLayer2 = Color(0xFF2C2C2E),           // 850
    bgLayer3 = Color(0xFF353638),           // 800
    sidebarFill = Color(0xFF1B1B1C),        // bluish-900 rgb(27,27,28)
    userBubble = Color(0xFF2C2C2E),         // bluish-850
    inputMajor = Color(0xFF2C2C2E),         // bluish-850
    selector = Color(0xFF353638),           // bluish-800
    hover = Color(0x14FFFFFF),              // rgba(255,255,255,.08)
    textPrimary = Color(0xFFF9FAFB),        // bluish-50
    textSecondary = Color(0xFFCFD3D6),      // bluish-300 rgb(207,211,214)
    textTertiary = Color(0xFFADB2B8),       // bluish-400
    textCaption = Color(0xFF81858C),        // bluish-600
    borderL1 = Color(0x0FFFFFFF),           // rgba(255,255,255,.06)
    borderL2 = Color(0x1FFFFFFF),           // rgba(255,255,255,.12)
    borderL3 = Color(0x29FFFFFF),           // rgba(255,255,255,.16)
    borderL4 = Color(0x33FFFFFF),           // rgba(255,255,255,.20)
    blue = Color(0xFF7AAAFF),               // deepseek-400
    sendBlue = Color(0xFF679EFE),           // button-info-fill 暗
    blue400 = Color(0xFF7AAAFF),
    errorPrimary = Color(0xFFF25A5A),       // red-400
    statusGreen = Color(0xFF22C55E),
    statusAmber = Color(0xFFF59E0B),
    statusIdle = Color(0xFF545557),
    menuSurface = Color(0xFF43454A),
    segSystem = Color(0xFFADB2B8),
    segTools = Color(0xFFA78BFA),
    segMessages = Color(0xFF4D93F8),
    trackDark = DshLightPalette.trackDark,
)

/** 当前生效的 dsh 调色板；默认浅色，由 [ProvideDshPalette] / [DshTheme] 提供。 */
val LocalDshPalette = staticCompositionLocalOf { DshLightPalette }

/**
 * dsh token 门面（全部 @Composable 读取）。绘制层（Canvas/DrawScope）不是
 * composable 作用域，取值要先在 composable 里提出来再传进 draw lambda。
 * 圆角体系：xs4 / sm8 / md12 / lg16 / xl20 / panel28（base.css:16-21）。
 */
object Dsh {
    const val RADIUS_XS = 4
    const val RADIUS_SM = 8
    const val RADIUS_MD = 12
    const val RADIUS_LG = 16
    const val RADIUS_XL = 20
    const val RADIUS_PANEL = 28

    val palette: DshPalette
        @Composable @ReadOnlyComposable get() = LocalDshPalette.current

    val bgBase: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.bgBase
    val bgLayer1: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.bgLayer1
    val bgLayer2: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.bgLayer2
    val bgLayer3: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.bgLayer3
    val bgSurface: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.sidebarFill
    val sidebarFill: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.sidebarFill
    val userBubble: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.userBubble
    val inputMajor: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.inputMajor
    val selector: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.selector
    val composerBg: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.inputMajor
    val hover: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.hover

    val textPrimary: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.textPrimary
    val textSecondary: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.textSecondary
    val textTertiary: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.textTertiary
    val textCaption: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.textCaption
    val textDimmed: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.textCaption

    val borderL1: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.borderL1
    val borderL2: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.borderL2
    val borderL3: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.borderL3
    val borderL4: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.borderL4

    val blue: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.blue
    val sendBlue: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.sendBlue
    val effortBlue: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.blue
    val blue400: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.blue400
    val errorPrimary: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.errorPrimary

    val statusGreen: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.statusGreen
    val statusAmber: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.statusAmber
    val statusIdle: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.statusIdle

    val menuSurface: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.menuSurface

    val segSystem: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.segSystem
    val segWorld: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.segTools
    val segMessages: Color @Composable @ReadOnlyComposable get() = LocalDshPalette.current.segMessages

    val trackDark: List<Color> @Composable @ReadOnlyComposable get() = LocalDshPalette.current.trackDark
}

/** dsh 调色板映射进 Material3，让对话框/菜单/输入框也吃到同一套 tokens。 */
private fun DshPalette.toMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        background = bgBase,
        onBackground = textPrimary,
        surface = bgBase,
        onSurface = textPrimary,
        surfaceContainerLowest = inputMajor,
        surfaceContainerLow = bgLayer1,
        surfaceContainer = bgLayer2,
        surfaceContainerHigh = bgLayer3,
        surfaceContainerHighest = hover,
        surfaceVariant = userBubble,
        onSurfaceVariant = textSecondary,
        primary = blue,
        onPrimary = Color.White,
        primaryContainer = blue.copy(alpha = 0.12f),
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        outline = borderL3,
        outlineVariant = borderL2,
        error = errorPrimary,
        onError = Color.White,
        errorContainer = errorPrimary.copy(alpha = 0.12f),
        onErrorContainer = errorPrimary,
        scrim = Color.Black,
    )
}

/**
 * 聊天界面的 dsh 主题：提供调色板并把 dsh tokens 铺进 Material3。
 * 只包聊天子树——其他 tab（设置/角色卡/社区）继续用应用的主题与强调色。
 */
@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DshDarkPalette else DshLightPalette
    CompositionLocalProvider(LocalDshPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toMaterialScheme(),
            typography = MaterialTheme.typography,
            // 官方圆角体系 sm8 / md12 / lg16：菜单与对话框共用，AlertDialog(extraLarge)
            // 与 DropdownMenu(extraSmall) 不再各自带 Material 默认的 28dp / 4dp。
            shapes = Shapes(
                extraSmall = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                small = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                medium = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                large = RoundedCornerShape(Dsh.RADIUS_LG.dp),
                extraLarge = RoundedCornerShape(Dsh.RADIUS_LG.dp),
            ),
            content = content,
        )
    }
}

/**
 * 只提供 dsh 调色板、不动 MaterialTheme 的版本：应用外壳（底栏等 dsh 元素）
 * 用它拿到随主题切换的 dsh tokens，同时保留用户的强调色主题。
 */
@Composable
fun ProvideDshPalette(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DshDarkPalette else DshLightPalette
    CompositionLocalProvider(LocalDshPalette provides palette, content = content)
}
