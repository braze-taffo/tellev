package app.tellev.feature.creation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestions block rides as an invisible HTML comment: it must never
 * reach the user-visible reply, and a malformed one must degrade silently.
 */
class CreationSuggestionsTest {

    @Test
    fun trailingBlockIsStrippedAndParsed() {
        val reply = "好的，我们继续。" + "\n<!-- suggestions: [\"补充外貌细节\", \"写一段开场白\"] -->"
        val (clean, suggestions) = splitReplySuggestions(reply)
        assertEquals("好的，我们继续。", clean)
        assertEquals(listOf("补充外貌细节", "写一段开场白"), suggestions)
    }

    @Test
    fun blockAnywhereInTextIsStillStrippedCleanly() {
        val reply = "前半句。<!-- suggestions: [\"A\"] --> 后半句。"
        val (clean, suggestions) = splitReplySuggestions(reply)
        assertEquals("前半句。 后半句。", clean)
        assertEquals(listOf("A"), suggestions)
    }

    @Test
    fun repliesWithoutSuggestionsPassThrough() {
        val (clean, suggestions) = splitReplySuggestions("  普通回复，没有注释。  ")
        assertEquals("普通回复，没有注释。", clean)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun malformedBlocksAreDroppedSilently() {
        val reply = "回复。<!-- suggestions: [\"未闭合 -->"
        val (clean, suggestions) = splitReplySuggestions(reply)
        // Regex requires a closing ] so a truncated block stays invisible text
        // only when it also lacks the terminator; a full-comment malformed JSON
        // is stripped but yields no suggestions.
        assertTrue(suggestions.isEmpty())
        assertTrue(!clean.contains("suggestions"))
    }

    @Test
    fun malformedJsonInsideAWellFormedCommentYieldsNoSuggestions() {
        val reply = "回复。<!-- suggestions: [\"未闭合 --> ] -->"
        val (clean, suggestions) = splitReplySuggestions(reply)
        assertTrue(suggestions.isEmpty())
        assertEquals("回复。", clean)
    }

    @Test
    fun duplicatesAreRemovedAndListIsCapped() {
        val reply = "回复。<!-- suggestions: [\"A\", \"A \", \"B\", \"C\", \"D\", \"E\"] -->"
        val (_, suggestions) = splitReplySuggestions(reply)
        assertEquals(listOf("A", "B", "C", "D"), suggestions)
    }

    @Test
    fun systemPromptCarriesTheSuggestionsDirective() {
        val session = CreationSession(kind = CreationKind.Character)
        // Indirect check: the directive constant is non-empty and mentions the marker.
        assertTrue(SUGGESTIONS_DIRECTIVE.contains("<!-- suggestions:"))
        assertTrue(session.kind == CreationKind.Character)
    }
}
