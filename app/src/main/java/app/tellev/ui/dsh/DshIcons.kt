package app.tellev.ui.dsh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * dsh 图标（dshmob MobileNavToggle 用的 IconPanelLeft 语义：圆角外框 + 左栏分隔线）。
 * lucide panel-left 的几何，2px 圆头描边，跟随 Icon tint。
 */
object DshIcons {

    val PanelLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "DshPanelLeft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 圆角矩形外框（lucide rect x=3 y=3 w=18 h=18 rx=2）。
                moveTo(5f, 3f)
                horizontalLineToRelative(14f)
                arcTo(2f, 2f, 0f, false, true, 21f, 5f)
                verticalLineToRelative(14f)
                arcTo(2f, 2f, 0f, false, true, 19f, 21f)
                horizontalLineToRelative(-14f)
                arcTo(2f, 2f, 0f, false, true, 3f, 19f)
                verticalLineToRelative(-14f)
                arcTo(2f, 2f, 0f, false, true, 5f, 3f)
                close()
                // 左栏分隔线 M9 3v18。
                moveTo(9f, 3f)
                verticalLineToRelative(18f)
            }
        }.build()
    }

    /** 数据库圆柱（图一 DeepSeek 应用模型键的图标语义），2px 圆头描边。 */
    val Database: ImageVector by lazy {
        ImageVector.Builder(
            name = "DshDatabase",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // 圆柱轮廓：顶椭圆 + 两侧竖线 + 底弧。
                moveTo(12f, 3f)
                curveTo(7.6f, 3f, 4f, 4.1f, 4f, 5.5f)
                verticalLineToRelative(13f)
                curveTo(4f, 19.9f, 7.6f, 21f, 12f, 21f)
                curveToRelative(4.4f, 0f, 8f, -1.1f, 8f, -2.5f)
                verticalLineToRelative(-13f)
                curveTo(20f, 4.1f, 16.4f, 3f, 12f, 3f)
                close()
                // 中间分隔弧（顶层数据带）。
                moveTo(4f, 9f)
                curveTo(4f, 10.4f, 7.6f, 11.5f, 12f, 11.5f)
                curveToRelative(4.4f, 0f, 8f, -1.1f, 8f, -2.5f)
            }
        }.build()
    }

    /** 分支（lucide git-branch 语义，消息行直接分支键），圆头描边。 */
    val Branch: ImageVector by lazy {
        ImageVector.Builder(
            name = "DshBranch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // M6 3v12
                moveTo(6f, 3f)
                verticalLineToRelative(12f)
                // 下端节点圆 (6,18) r3。
                moveTo(9f, 18f)
                arcTo(3f, 3f, 0f, false, true, 3f, 18f)
                arcTo(3f, 3f, 0f, false, true, 9f, 18f)
                // 上端节点圆 (18,6) r3。
                moveTo(21f, 6f)
                arcTo(3f, 3f, 0f, false, true, 15f, 6f)
                arcTo(3f, 3f, 0f, false, true, 21f, 6f)
                // M18 9a9 9 0 0 1-9 9
                moveTo(18f, 9f)
                arcToRelative(9f, 9f, 0f, false, true, -9f, 9f)
            }
        }.build()
    }

    /** 删除（tabler trash 语义，会话行），圆头描边，替代 Material 填充图标。 */
    val Trash: ImageVector by lazy {
        ImageVector.Builder(
            name = "DshTrash",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // M4 7h16
                moveTo(4f, 7f)
                horizontalLineToRelative(16f)
                // M10 11v6 / M14 11v6
                moveTo(10f, 11f)
                verticalLineToRelative(6f)
                moveTo(14f, 11f)
                verticalLineToRelative(6f)
                // M6 7v12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7
                moveTo(6f, 7f)
                verticalLineToRelative(12f)
                arcTo(2f, 2f, 0f, false, false, 8f, 21f)
                horizontalLineToRelative(8f)
                arcTo(2f, 2f, 0f, false, false, 18f, 19f)
                verticalLineTo(7f)
                // M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2
                moveTo(9f, 7f)
                verticalLineTo(5f)
                arcTo(1f, 1f, 0f, false, true, 10f, 4f)
                horizontalLineToRelative(4f)
                arcTo(1f, 1f, 0f, false, true, 15f, 5f)
                verticalLineTo(7f)
            }
        }.build()
    }

    /** 图钉（tabler pin 语义，会话行置顶），圆头描边。 */
    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "DshPin",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // M9 4h6l-1 7 3 3v2H7v-2l3-3-1-7z
                moveTo(9f, 4f)
                horizontalLineToRelative(6f)
                lineToRelative(-1f, 7f)
                lineToRelative(3f, 3f)
                verticalLineToRelative(2f)
                horizontalLineTo(7f)
                verticalLineToRelative(-2f)
                lineToRelative(3f, -3f)
                lineToRelative(-1f, -7f)
                close()
                // M12 16v5
                moveTo(12f, 16f)
                verticalLineToRelative(5f)
            }
        }.build()
    }

    /** Lucide 描边图标：SVG 路径串直接解析，2px 圆头描边，跟随 Icon tint。 */
    private fun lucide(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** 回形针（Lucide paperclip）：输入区文件入口，点按直接唤起系统文件选择器。 */
    val Paperclip: ImageVector by lazy {
        lucide(
            "DshPaperclip",
            "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48",
        )
    }

    /** 魔杖+星光（Lucide wand-sparkles）：优化提示词。 */
    val WandSparkles: ImageVector by lazy {
        lucide(
            "DshWandSparkles",
            "m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72",
            "m14 7 3 3", "M5 6v4", "M19 14v4", "M10 2v2", "M7 8H3", "M21 16h-4", "M11 3H9",
        )
    }

    /** 关闭（Lucide x）：附件条移除键。 */
    val Close: ImageVector by lazy { lucide("DshClose", "M18 6 6 18", "m6 6 12 12") }

    /** 垃圾桶图标已有 [Trash]；这里补「清空」用的扫帚/橡皮语义：Lucide eraser。 */
    val Eraser: ImageVector by lazy {
        lucide(
            "DshEraser",
            "m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21",
            "M22 21H7", "m5 11 9 9",
        )
    }

    /** 滑杆（Lucide sliders-horizontal）：模型配置键。 */
    val SlidersHorizontal: ImageVector by lazy {
        lucide(
            "DshSlidersHorizontal",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
        )
    }
}
