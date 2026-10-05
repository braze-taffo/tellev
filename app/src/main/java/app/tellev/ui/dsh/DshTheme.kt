package app.tellev.ui.dsh

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * DeepSeek Harness（dsh-web-mobile 官方 token 回退值 + 截图取色）的像素规格。
 * 来源：tmp_search/dshmob 源码里的 --dsw-alias-* 回退字面量与 tgz 的 bre- CSS。
 */
object Dsh {
    val bgBase = Color(0xFFFFFFFF)
    val bgSurface = Color(0xFFF9FAFB)          // --dsw-alias-bg-surface
    val composerBg = Color(0xFFF2F3F5)          // 输入卡底色（截图取色）
    val userBubble = Color(0xFFEFF1F5)          // 用户气泡
    val hover = Color(0x0F787D8C)               // fill-tertiary rgba(120,125,140,.09)

    val textPrimary = Color(0xFF15171B)         // --dsw-alias-label-primary
    val textSecondary = Color(0xFF61666B)       // --dsw-alias-label-secondary
    val textTertiary = Color(0xFF81858C)        // --dsw-alias-label-tertiary
    val textDimmed = Color(0x59000000)          // rgba(0,0,0,.35)

    val borderL1 = Color(0x1F000000)            // rgba(0,0,0,.12)
    val borderL2 = Color(0x1A000000)            // rgba(0,0,0,.10)

    val blue = Color(0xFF4D6BFE)                // 发送键/圆环/链接（DeepSeek 蓝）
    val effortBlue = Color(0xFF4D70FF)          // 档位蓝 dsw-static-deepseek-500
    val blue400 = Color(0xFF5D83FF)

    val segSystem = Color(0xFF8E9196)           // 上下文分段：系统
    val segWorld = Color(0xFFA06AF9)            // 上下文分段：世界书
    val segMessages = Color(0xFF4D6BFE)         // 上下文分段：对话消息

    // 滑轨渐变（上游 .bre-effort-track 100deg 五段）
    val trackDark = listOf(
        Color(0xFF03040A), Color(0xFF071126), Color(0xFF101D4C),
        Color(0xFF302262), Color(0xFF5D35A0),
    )
}

/** dsh 浅色主题映射进 Material3，让旧界面也吃到同一套底色。 */
@Composable
fun DshTheme(content: @Composable () -> Unit) {
    val scheme: ColorScheme = lightColorScheme(
        background = Dsh.bgBase,
        onBackground = Dsh.textPrimary,
        surface = Dsh.bgBase,
        onSurface = Dsh.textPrimary,
        surfaceContainerLowest = Dsh.composerBg,
        surfaceContainerLow = Dsh.bgSurface,
        surfaceContainer = Dsh.bgSurface,
        surfaceContainerHigh = Dsh.hover,
        surfaceVariant = Dsh.userBubble,
        onSurfaceVariant = Dsh.textSecondary,
        primary = Dsh.blue,
        onPrimary = Color.White,
        primaryContainer = Dsh.blue.copy(alpha = 0.12f),
        onPrimaryContainer = Dsh.textPrimary,
        secondary = Dsh.textSecondary,
        outline = Dsh.borderL1,
        outlineVariant = Dsh.borderL2,
        error = Color(0xFFC83E4D),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
