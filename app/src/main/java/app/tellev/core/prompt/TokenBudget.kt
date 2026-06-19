package app.tellev.core.prompt

import app.tellev.core.model.MessageRole

object TokenBudget {

    /**
     * Estimates the token count for a given text.
     * Uses a heuristic: ~4 characters per token for Latin scripts,
     * ~2 characters per token for CJK (Chinese, Japanese, Korean) scripts.
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0

        var latinChars = 0
        var cjkChars = 0
        var otherChars = 0

        for (char in text) {
            when {
                isCjkCharacter(char) -> cjkChars++
                char.code < 128 -> latinChars++ // ASCII range
                else -> otherChars++
            }
        }

        // CJK: approximately 1.5 tokens per character (conservative estimate)
        // Latin/ASCII: approximately 1 token per 4 characters
        // Other Unicode: approximately 1 token per 3 characters
        val cjkTokens = (cjkChars * 1.5).toInt()
        val latinTokens = (latinChars + 3) / 4 // ceiling division
        val otherTokens = (otherChars + 2) / 3

        return maxOf(1, cjkTokens + latinTokens + otherTokens)
    }

    private fun isCjkCharacter(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||      // CJK Unified Ideographs
            code in 0x3400..0x4DBF ||          // CJK Unified Ideographs Extension A
            code in 0x20000..0x2A6DF ||        // Extension B
            code in 0x3040..0x309F ||          // Hiragana
            code in 0x30A0..0x30FF ||          // Katakana
            code in 0xAC00..0xD7AF ||          // Hangul Syllables
            code in 0x1100..0x11FF ||          // Hangul Jamo
            code in 0x3130..0x318F ||          // Hangul Compatibility Jamo
            code in 0xF900..0xFAFF ||          // CJK Compatibility Ideographs
            code in 0x2F00..0x2FDF ||          // Kangxi Radicals
            code in 0x2E80..0x2EFF ||          // CJK Radicals Supplement
            code in 0x3190..0x319F ||          // Kanbun
            code in 0x31A0..0x31BF ||          // Bopomofo Extended
            code in 0x3100..0x312F ||          // Bopomofo
            code in 0xFF00..0xFFEF             // Halfwidth and Fullwidth Forms
    }

    /**
     * Fits messages to a token budget with the following priority:
     * 1. System prompt is always included
     * 2. World info entries (included in order)
     * 3. Character description
     * 4. Messages (most recent first; oldest are trimmed)
     *
     * Returns the list of PromptMessages that fit within the budget.
     * The system prompt message is always the first message if present.
     */
    fun fitToBudget(
        systemPrompt: String,
        worldInfo: List<String>,
        characterDescription: String,
        messages: List<PromptMessage>,
        budget: Int,
    ): List<PromptMessage> {
        var usedTokens = 0
        val result = mutableListOf<PromptMessage>()

        // 1. System prompt always included
        val systemTokens = estimateTokens(systemPrompt)
        if (systemTokens <= budget) {
            result.add(PromptMessage(role = MessageRole.System, content = systemPrompt))
            usedTokens += systemTokens
        } else {
            // Even system prompt alone exceeds budget; include it anyway (it's mandatory)
            result.add(PromptMessage(role = MessageRole.System, content = systemPrompt))
            usedTokens += systemTokens
            return result
        }

        // 2. World info entries - include as many as fit
        for (entry in worldInfo) {
            val entryTokens = estimateTokens(entry)
            if (usedTokens + entryTokens <= budget) {
                // World info is folded into the system prompt conceptually,
                // but we track tokens. We don't add separate messages for world info.
                usedTokens += entryTokens
            }
        }

        // 3. Character description
        if (characterDescription.isNotEmpty()) {
            val descTokens = estimateTokens(characterDescription)
            if (usedTokens + descTokens <= budget) {
                usedTokens += descTokens
            }
        }

        // 4. Messages - include most recent first, then reverse to chronological order
        val remainingBudget = budget - usedTokens
        if (remainingBudget > 0 && messages.isNotEmpty()) {
            val includedMessages = mutableListOf<PromptMessage>()
            var messageTokensUsed = 0

            // Iterate from most recent to oldest
            for (message in messages.asReversed()) {
                val msgTokens = estimateTokens(message.content) +
                    (message.name?.let { estimateTokens(it) } ?: 0) +
                    4 // overhead for role/formatting tokens
                if (messageTokensUsed + msgTokens <= remainingBudget) {
                    includedMessages.add(message)
                    messageTokensUsed += msgTokens
                } else {
                    // Can't fit this message; stop including older messages
                    break
                }
            }

            // Reverse to get chronological order and add to result
            result.addAll(includedMessages.asReversed())
        }

        return result
    }

    /**
     * Estimates the total token count for a list of prompt messages.
     */
    fun estimateTotalTokens(messages: List<PromptMessage>): Int {
        return messages.sumOf { message ->
            estimateTokens(message.content) +
                (message.name?.let { estimateTokens(it) } ?: 0) +
                4 // role overhead
        }
    }
}
