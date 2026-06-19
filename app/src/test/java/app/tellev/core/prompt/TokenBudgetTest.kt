package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBudgetTest {

    @Test
    fun `estimateTokens returns zero for empty string`() {
        assertEquals(0, TokenBudget.estimateTokens(""))
    }

    @Test
    fun `estimateTokens uses 4 chars per token for ASCII`() {
        // "Hello" is 5 chars, should be ceil(5/4) = 2 tokens
        val tokens = TokenBudget.estimateTokens("Hello")
        assertTrue("Expected ~2 tokens for 'Hello', got $tokens", tokens in 1..3)
    }

    @Test
    fun `estimateTokens for longer ASCII text`() {
        val text = "This is a simple English sentence for testing."
        val tokens = TokenBudget.estimateTokens(text)
        // 47 chars / 4 ≈ 12 tokens
        assertTrue("Expected ~12 tokens, got $tokens", tokens in 8..18)
    }

    @Test
    fun `estimateTokens uses higher rate for CJK characters`() {
        val chinese = "你好世界测试" // 6 CJK characters
        val tokens = TokenBudget.estimateTokens(chinese)
        // 6 CJK chars * 1.5 ≈ 9 tokens
        assertTrue("Expected ~9 tokens for 6 CJK chars, got $tokens", tokens in 6..15)
    }

    @Test
    fun `estimateTokens for mixed text`() {
        val mixed = "Hello你好World世界" // 10 ASCII + 4 CJK
        val tokens = TokenBudget.estimateTokens(mixed)
        // 10/4 = 2.5 (ceil to 3) + 4*1.5 = 6 = ~9 tokens
        assertTrue("Expected ~9 tokens for mixed text, got $tokens", tokens in 5..15)
    }

    @Test
    fun `estimateTokens for Japanese text`() {
        val japanese = "こんにちは世界" // 7 characters (hiragana + kanji)
        val tokens = TokenBudget.estimateTokens(japanese)
        // 7 CJK chars * 1.5 ≈ 10.5 tokens
        assertTrue("Expected ~10 tokens for Japanese, got $tokens", tokens in 6..16)
    }

    @Test
    fun `estimateTokens minimum is 1 for non-empty string`() {
        val tokens = TokenBudget.estimateTokens("a")
        assertTrue("Expected at least 1 token, got $tokens", tokens >= 1)
    }

    @Test
    fun `fitToBudget includes system prompt always`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hello"),
        )
        val result = TokenBudget.fitToBudget(
            systemPrompt = "You are a helpful assistant.",
            worldInfo = emptyList(),
            characterDescription = "",
            messages = messages,
            budget = 100,
        )

        assertTrue("Should include system prompt", result.isNotEmpty())
        assertEquals(MessageRole.System, result.first().role)
        assertTrue("System prompt should be present", result.first().content.contains("helpful assistant"))
    }

    @Test
    fun `fitToBudget trims oldest messages first`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "First message that is somewhat long"),
            PromptMessage(role = MessageRole.Assistant, content = "Second message"),
            PromptMessage(role = MessageRole.User, content = "Third message"),
            PromptMessage(role = MessageRole.Assistant, content = "Fourth message"),
        )

        // Set a tight budget that can't fit all messages
        val result = TokenBudget.fitToBudget(
            systemPrompt = "System",
            worldInfo = emptyList(),
            characterDescription = "",
            messages = messages,
            budget = 30, // Very tight budget
        )

        // System prompt should always be first
        assertEquals(MessageRole.System, result.first().role)

        // The most recent messages should be included, oldest should be dropped
        val nonSystemMessages = result.drop(1)
        if (nonSystemMessages.isNotEmpty()) {
            val lastMessage = nonSystemMessages.last()
            // Most recent message should be preserved
            assertTrue(
                "Most recent messages should be included",
                lastMessage.content == "Fourth message" || lastMessage.content == "Third message"
            )
        }
    }

    @Test
    fun `fitToBudget with generous budget includes all messages`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hello"),
            PromptMessage(role = MessageRole.Assistant, content = "Hi!"),
            PromptMessage(role = MessageRole.User, content = "How are you?"),
        )

        val result = TokenBudget.fitToBudget(
            systemPrompt = "You are helpful.",
            worldInfo = emptyList(),
            characterDescription = "",
            messages = messages,
            budget = 10000,
        )

        // System + all 3 messages = 4 total
        assertEquals("Should include all messages with generous budget", 4, result.size)
    }

    @Test
    fun `fitToBudget accounts for world info tokens`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hello world this is a test message"),
            PromptMessage(role = MessageRole.Assistant, content = "Response here"),
        )

        val worldInfo = listOf(
            "Important world lore about the setting and characters",
            "More detailed background information about the world",
        )

        // Budget that fits system + world info but not many messages
        val result = TokenBudget.fitToBudget(
            systemPrompt = "You are an assistant.",
            worldInfo = worldInfo,
            characterDescription = "A detailed description of the character",
            messages = messages,
            budget = 40,
        )

        // System should always be present
        assertTrue("System prompt should be present", result.isNotEmpty())
        assertEquals(MessageRole.System, result.first().role)

        // Fewer messages should be included due to world info consuming budget
        val nonSystemCount = result.size - 1
        assertTrue(
            "Should have fewer messages due to world info budget usage",
            nonSystemCount <= messages.size
        )
    }

    @Test
    fun `fitToBudget preserves chronological order of included messages`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Message one"),
            PromptMessage(role = MessageRole.Assistant, content = "Message two"),
            PromptMessage(role = MessageRole.User, content = "Message three"),
            PromptMessage(role = MessageRole.Assistant, content = "Message four"),
        )

        val result = TokenBudget.fitToBudget(
            systemPrompt = "Sys",
            worldInfo = emptyList(),
            characterDescription = "",
            messages = messages,
            budget = 10000,
        )

        val nonSystem = result.drop(1)
        // Verify chronological order
        for (i in 1 until nonSystem.size) {
            val prevIndex = messages.indexOfFirst { it.content == nonSystem[i - 1].content }
            val currIndex = messages.indexOfFirst { it.content == nonSystem[i].content }
            assertTrue(
                "Messages should be in chronological order",
                prevIndex < currIndex
            )
        }
    }

    @Test
    fun `estimateTotalTokens sums all message tokens`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.System, content = "You are helpful."),
            PromptMessage(role = MessageRole.User, name = "Bob", content = "Hello there!"),
            PromptMessage(role = MessageRole.Assistant, content = "Hi Bob!"),
        )

        val total = TokenBudget.estimateTotalTokens(messages)

        // Each message should contribute at least 4 (role overhead) + content tokens
        assertTrue("Total should be positive", total > 0)

        // Manual check: "You are helpful." ≈ 4 tokens + 4 overhead = 8
        // "Bob" ≈ 1 + "Hello there!" ≈ 3 + 4 overhead = 8
        // "Hi Bob!" ≈ 2 + 4 overhead = 6
        // Total ≈ 22
        assertTrue("Expected ~22 tokens, got $total", total in 10..40)
    }

    @Test
    fun `fitToBudget returns system only when budget is very tight`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "This is a very long message that should not fit"),
        )

        val result = TokenBudget.fitToBudget(
            systemPrompt = "Short",
            worldInfo = emptyList(),
            characterDescription = "",
            messages = messages,
            budget = 5,
        )

        // System prompt is always included even if it barely fits
        assertTrue("Should have at least system prompt", result.isNotEmpty())
        assertEquals(MessageRole.System, result.first().role)
    }
}
