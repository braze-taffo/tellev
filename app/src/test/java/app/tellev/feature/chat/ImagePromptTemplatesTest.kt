package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ImagePromptTemplatesTest {

    @Test
    fun `English tag parser accepts short tags and normalizes wrappers`() {
        assertEquals("1girl, solo, blue eyes, long_hair, garden",
            ImagePromptTemplates.processEnglishTags("```text\n1girl， solo, blue eyes, long_hair, garden\n```"))
    }

    @Test
    fun `English tag parser rejects Chinese mixed text headings and prose`() {
        listOf(
            "教室，女孩微笑",
            "1girl, 蓝色眼睛, garden",
            "Here are the tags: 1girl, solo",
            "A girl with beautiful blue eyes sits quietly in the sunny garden, flowers",
            "she is sitting, blue eyes",
            "1girl, solo. She sits in the garden.",
            "1girl\nsolo\ngarden",
            "",
        ).forEach { assertNull(it, ImagePromptTemplates.processEnglishTags(it)) }
    }

    @Test
    fun `tag engines retry invalid scene output once with format instructions`() = runBlocking {
        for (engine in listOf(ChatImageEngine.NovelAi)) {
            val instructions = mutableListOf<String>()
            val result = ImagePromptTemplates.summarize(engine) { instruction ->
                instructions += instruction
                if (instructions.size == 1) "女孩坐在花园里" else "1girl, solo, sitting, garden"
            }
            assertEquals("1girl, solo, sitting, garden", result)
            assertEquals(2, instructions.size)
            assertTrue(instructions.first().contains("English visual tags"))
            assertTrue(instructions.last().contains("Try again"))
        }
    }

    @Test
    fun `tag engines never pass invalid fallback text to image generation`() = runBlocking {
        var attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) {
            attempts++
            "1girl, 女孩微笑"
        })
        assertEquals(2, attempts)
    }

    @Test
    fun `valid tags and provider failures do not trigger extra requests`() = runBlocking {
        var attempts = 0
        assertEquals("1girl, solo", ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) {
            attempts++
            "1girl, solo"
        })
        assertEquals(1, attempts)
        attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) { attempts++; null })
        assertEquals(1, attempts)
    }

    @Test
    fun `ComfyUI retains existing summary format`() = runBlocking {
        assertEquals("教室, 女孩微笑", ImagePromptTemplates.summarize(ChatImageEngine.ComfyUi) {
            assertEquals(ImagePromptTemplates.NOW, it)
            "教室\n女孩微笑"
        })
    }

    @Test
    fun `NOW template keeps user and char macros and POV ordering`() {
        assertTrue(ImagePromptTemplates.NOW.contains("{{user}}"))
        assertTrue(ImagePromptTemplates.NOW.contains("{{char}}"))
        assertTrue(ImagePromptTemplates.NOW.contains("'POV'"))
        assertTrue(ImagePromptTemplates.NOW.contains("comma-delimited"))
    }

    @Test
    fun `processReply strips quotes newlines and non-ascii noise`() {
        val input = "He said \"hello\"“\nblue eyes, long hair\n\nsmile"
        assertEquals("He said hello, blue eyes, long hair, smile", ImagePromptTemplates.processReply(input))
    }

    @Test
    fun `processReply normalizes comma spacing`() {
        assertEquals("a, b, c", ImagePromptTemplates.processReply(" a ,,\nb ,  c "))
    }

    @Test
    fun `processReply blanks out non-ascii replies so callers fall back`() {
        assertEquals("", ImagePromptTemplates.processReply("教室，女孩微笑"))
    }

    @Test
    fun `processReplyLoose keeps non-ascii scripts`() {
        assertEquals("教室, 女孩微笑", ImagePromptTemplates.processReplyLoose("教室\n女孩微笑"))
        assertEquals("a, b", ImagePromptTemplates.processReplyLoose("a\nb"))
    }
}
