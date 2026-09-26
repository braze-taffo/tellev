package app.tellev.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * Covers the asset lookup itself, not just the parser.
 *
 * 1.7.0.1 装机验收时指引在真机上显示「指引内容加载失败」，而单元测试全绿——
 * 因为当时的实现依赖 `AssetManager.list()`，那一段在 JVM 单测里根本没有被走到。
 * 现在读取逻辑经 [loadGuideFrom] 的参数化 reader 暴露出来，这些用例就用真实资产
 * 文件（不是字符串夹具）覆盖回退链、缺语言与坏资产三条路径。
 */
class GuideContentTest {

    @Test
    fun `every guide loads in all four languages through the real asset files`() {
        val assets = assetsRootDir()

        GuideKind.entries.forEach { kind ->
            listOf("zh-Hans-CN", "en-US", "ja-JP", "ko-KR").forEach { tag ->
                val document = loadGuideFrom(kind, tag, diskReader(assets))
                assertNotNull("${kind.assetDir} did not load for '$tag'", document)
                assertEquals(
                    "unexpected page count for ${kind.assetDir} / $tag",
                    expectedPageCount(kind),
                    document!!.pages.size,
                )
            }
        }
    }

    @Test
    fun `a missing language falls back to english before chinese`() {
        val assets = assetsRootDir()
        val requested = mutableListOf<String>()
        val reader: (String) -> String? = { path ->
            requested += path
            // 只提供英文资产，模拟某个语言没翻、只有回退可用的情况。
            if (path.endsWith("en.md")) assets.resolve(path).readText() else null
        }

        val document = loadGuideFrom(GuideKind.Update, "ja-JP", reader)

        assertNotNull(document)
        assertEquals("ja-JP.md", requested.first().substringAfterLast('/'))
        assertEquals("en.md", requested.last().substringAfterLast('/'))
    }

    @Test
    fun `an unreadable guide returns null and reports every candidate it tried`() {
        val diagnostics = mutableListOf<String>()

        val document = loadGuideFrom(
            kind = GuideKind.Onboarding,
            languageTag = "ja-JP",
            reader = { null },
            onFailure = { message, _ -> diagnostics += message },
        )

        assertNull(document)
        assertEquals(1, diagnostics.size)
        assertTrue(
            "diagnostic should name the directory and language: ${diagnostics.single()}",
            diagnostics.single().contains("guide/onboarding") && diagnostics.single().contains("ja-JP"),
        )
    }

    @Test
    fun `a malformed guide returns null and reports the offending path`() {
        val diagnostics = mutableListOf<String>()
        // 有标题但没有摘要/入口/步骤：解析器会抛，加载层必须接着。
        val document = loadGuideFrom(
            kind = GuideKind.Onboarding,
            languageTag = "zh-CN",
            reader = { "## Only a title\n" },
            onFailure = { message, error -> diagnostics += "$message|${error != null}" },
        )

        assertNull(document)
        assertTrue(
            "diagnostic should name the offending asset and carry the error: ${diagnostics.single()}",
            diagnostics.single().contains("guide/onboarding/zh-CN.md") && diagnostics.single().endsWith("|true"),
        )
    }

    private fun diskReader(assets: Path): (String) -> String? = { path ->
        val file = assets.resolve(path)
        if (Files.isRegularFile(file)) file.readText() else null
    }

    private fun expectedPageCount(kind: GuideKind): Int =
        GuideFormat.parse(assetsRootDir().resolve(kind.assetDir).resolve("zh-CN.md").readText()).pages.size

    /** Walks up from the test working directory so the test works from app/ or the repo root. */
    private fun assetsRootDir(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("app/src/main/assets")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        error("assets directory not found, starting from ${Paths.get("").toAbsolutePath()}")
    }
}
