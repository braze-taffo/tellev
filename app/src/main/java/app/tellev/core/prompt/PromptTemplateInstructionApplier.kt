package app.tellev.core.prompt

import app.tellev.core.model.MessageRole

internal object PromptTemplateInstructionApplier {

    fun applyInstructionBlocks(
        messages: List<PromptMessage>,
        blocks: List<InstructionBlock>,
        state: TemplateState,
    ): List<PromptMessage> {
        if (blocks.isEmpty()) return messages
        var current = messages
        for (block in blocks) {
            if (block.body.isBlank()) continue
            current = when (block.kind) {
                InstructionKind.Generate -> applyGenerateInstruction(current, block, state)
                InstructionKind.Inject -> applyInjectInstruction(current, block, state)
            }
        }
        return current
    }

    private fun applyGenerateInstruction(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): List<PromptMessage> {
        if (messages.isEmpty()) return messages
        val target = resolveGenerateTarget(messages, block, state) ?: return messages
        val placement = block.placement ?: Placement.Before
        val body = block.body.trim()
        return messages.mapIndexed { index, message ->
            if (index != target) return@mapIndexed message
            val joined = when (placement) {
                Placement.Before -> joinPromptParts(body, message.content)
                Placement.After -> joinPromptParts(message.content, body)
            }
            message.copy(content = joined)
        }
    }

    /**
     * Numeric [GENERATE:N]/@INJECT indices address the logical layout
     * "system prompt, then chat messages". Structural marker messages
     * ('[Start a new Chat]', '[Example Chat]') are invisible to that index
     * space, so cards written against SillyTavern (whose template runs before
     * markers exist) keep working.
     */
    fun logicalIndexToArrayIndex(messages: List<PromptMessage>, logical: Int): Int {
        if (logical < 0) return 0
        var counted = -1
        messages.forEachIndexed { arrayIndex, message ->
            if (message.channel != CHANNEL_MARKER) {
                counted++
                if (counted == logical) return arrayIndex
            }
        }
        return messages.size
    }

    private fun resolveGenerateTarget(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): Int? {
        block.index?.let { return logicalIndexToArrayIndex(messages, it).coerceIn(0, messages.lastIndex) }
        block.regex?.let { pattern ->
            val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrElse {
                state.warn("Invalid [GENERATE:REGEX] pattern: $pattern")
                return null
            }
            return messages.indexOfFirst { regex.containsMatchIn(it.content) }.takeIf { it >= 0 }
        }
        return when (block.placement ?: Placement.Before) {
            Placement.Before -> 0
            Placement.After -> messages.lastIndex
        }
    }

    private fun applyInjectInstruction(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): List<PromptMessage> {
        val role = block.role ?: MessageRole.System
        val injected = PromptMessage(role = role, content = block.body.trim())
        val index = resolveInjectIndex(messages, block, state).coerceIn(0, messages.size)
        return messages.toMutableList().apply { add(index, injected) }
    }

    private fun resolveInjectIndex(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): Int {
        block.index?.let {
            val base = logicalIndexToArrayIndex(messages, it).coerceIn(0, messages.size)
            return when (block.placement ?: Placement.Before) {
                Placement.Before -> base
                Placement.After -> (base + 1).coerceAtMost(messages.size)
            }
        }

        block.target?.let { target ->
            val messageIndex = target.toIntOrNull()
                ?: messages.indexOfFirst { it.content.contains(target, ignoreCase = true) }.takeIf { it >= 0 }
            if (messageIndex != null) {
                return when (block.placement ?: Placement.After) {
                    Placement.Before -> messageIndex
                    Placement.After -> messageIndex + 1
                }
            }
        }

        block.regex?.let { pattern ->
            val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrElse {
                state.warn("Invalid @INJECT regex pattern: $pattern")
                return messages.size
            }
            val messageIndex = messages.indexOfFirst { regex.containsMatchIn(it.content) }
            if (messageIndex >= 0) {
                return when (block.placement ?: Placement.Before) {
                    Placement.Before -> messageIndex
                    Placement.After -> messageIndex + 1
                }
            }
        }

        return messages.size
    }

    fun joinPromptParts(first: String, second: String): String {
        return listOf(first.trim(), second.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
