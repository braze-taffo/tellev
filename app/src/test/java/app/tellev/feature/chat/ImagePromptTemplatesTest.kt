package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTemplatesTest {

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
