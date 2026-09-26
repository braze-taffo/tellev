package app.tellev.core.guide

import app.tellev.core.storage.StDirectoryLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GuideFormatTest {

    @Test
    fun `splits pages on headings and reads the three sections`() {
        val document = GuideFormat.parse(
            """
            <!-- header comment that must be ignored -->
            ## First page
            One sentence about the first thing.

            <!-- where -->
            - Settings → Something

            <!-- steps -->
            1. Do the first thing.
            2. Do the second thing.

            ## Second page
            Another sentence.

            <!-- where -->
            - Chat → more options

            <!-- steps -->
            1. Tap it.
            """.trimIndent(),
        )

        assertEquals(2, document.pages.size)
        val first = document.pages[0]
        assertEquals("First page", first.title)
        assertEquals("One sentence about the first thing.", first.summary)
        assertEquals(listOf("Settings → Something"), first.where)
        assertEquals(listOf("Do the first thing.", "Do the second thing."), first.steps)
        assertEquals("Second page", document.pages[1].title)
    }

    @Test
    fun `summary needs no marker and wrapped lines are joined`() {
        val document = GuideFormat.parse(
            """
            ## Paged
            A summary that the
            translator wrapped.
            <!-- where -->
            - Somewhere
            <!-- steps -->
            1. Something
            """.trimIndent(),
        )

        assertEquals("A summary that the translator wrapped.", document.pages.single().summary)
    }

    @Test
    fun `accepts star bullets and numbered steps with a closing paren`() {
        val document = GuideFormat.parse(
            """
            ## Page
            Summary.
            <!-- where -->
            * Tab → entry
            <!-- steps -->
            1) First
            2) Second
            """.trimIndent(),
        )

        assertEquals(listOf("Tab → entry"), document.pages.single().where)
        assertEquals(listOf("First", "Second"), document.pages.single().steps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a page without steps is rejected instead of silently shipping`() {
        GuideFormat.parse(
            """
            ## Page
            Summary.
            <!-- where -->
            - Somewhere
            """.trimIndent(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a document without any page is rejected`() {
        GuideFormat.parse("<!-- only a comment -->")
    }

    @Test
    fun `language fallback prefers the exact tag then the primary language`() {
        assertEquals(listOf("zh-Hans-CN", "zh-CN", "en"), languageFallbackCandidates("zh-Hans-CN"))
        assertEquals(listOf("ja-JP", "ja", "en", "zh-CN"), languageFallbackCandidates("ja-JP"))
        assertEquals(listOf("en-US", "en", "zh-CN"), languageFallbackCandidates("en-US"))
        assertEquals(listOf("ko", "en", "zh-CN"), languageFallbackCandidates("ko"))
        // An empty tag still has to resolve to something.
        assertEquals(listOf("en", "zh-CN"), languageFallbackCandidates(""))
    }

    @Test
    fun `user data check ignores seeded presets and personas`() {
        val root = Files.createTempDirectory("tellev-guide-data")
        val layout = StDirectoryLayout.fromRoot(root)
        try {
            // A bootstrapped-but-untouched install: the four user dirs exist but are empty.
            layout.characters.createDirectories()
            layout.chats.createDirectories()
            layout.groupChats.createDirectories()
            layout.worlds.createDirectories()
            assertFalse(hasAnyUserData(layout))

            layout.characters.resolve("card.png").writeText("x")
            assertTrue(hasAnyUserData(layout))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `user data check treats a missing directory as empty`() {
        val root = Files.createTempDirectory("tellev-guide-missing")
        try {
            assertFalse(hasAnyUserData(StDirectoryLayout.fromRoot(root)))

            StDirectoryLayout.fromRoot(root).worlds.createDirectories()
            StDirectoryLayout.fromRoot(root).worlds.resolve("book.json").writeText("{}")
            assertTrue(hasAnyUserData(StDirectoryLayout.fromRoot(root)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Asset guard: every shipped guide must parse and every language must carry the
     * same number of pages as the Chinese source. A translator who drops, merges or
     * leaves a section empty turns this red instead of shipping a half-empty page.
     */
    @Test
    fun `every shipped guide asset parses with the same page count in all languages`() {
        val assetsDir = assetsRootDir()
        val languages = listOf("zh-CN", "en", "ja", "ko")

        GuideKind.entries.forEach { kind ->
            val counts = languages.associateWith { language ->
                val file = assetsDir.resolve(kind.assetDir).resolve("$language.md")
                assertTrue("missing guide asset: $file", Files.isRegularFile(file))
                val document = GuideFormat.parse(file.readText())
                document.pages.forEach { page ->
                    assertTrue("${file.fileName}: '${page.title}' has a blank summary", page.summary.isNotBlank())
                    assertTrue("${file.fileName}: '${page.title}' has no entry point", page.where.all { it.isNotBlank() })
                    assertTrue("${file.fileName}: '${page.title}' has no steps", page.steps.all { it.isNotBlank() })
                }
                document.pages.size
            }

            val reference = counts.getValue("zh-CN")
            languages.forEach { language ->
                assertEquals(
                    "${kind.assetDir}/$language.md has a different page count than zh-CN",
                    reference,
                    counts.getValue(language),
                )
            }
            val minimum = if (kind == GuideKind.Update) 6 else 4
            assertTrue("${kind.assetDir} shrank to $reference pages", reference >= minimum)
        }
    }

    /** Walks up from the test working directory so the guard works from app/ or the repo root. */
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
