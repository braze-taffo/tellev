package app.tellev.core.model

import kotlinx.serialization.json.*

/** Boundary detection happens on raw content, never on regex-generated HTML. */
object MessageReasoning {
    data class Parts(val body: String, val reasoning: String = "", val status: String = "none")

    private val prefix = Regex("""^\s*<(think|reasoning)>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("""</?(?:think|reasoning)>""", RegexOption.IGNORE_CASE)

    fun split(text: String): Parts {
        val start = prefix.find(text) ?: return Parts(text)
        // Nested/mismatched tags are ambiguous. Preserve the original instead of guessing.
        val end = tag.find(text, start.range.last + 1)
        if (end == null || !end.value.equals("</${start.groupValues[1]}>", ignoreCase = true)) {
            return Parts(text, status = "ambiguous")
        }
        return Parts(
            text.substring(end.range.last + 1).trimStart(),
            text.substring(start.range.last + 1, end.range.first).trim(),
            "parsed",
        )
    }

    fun fromResponse(text: String, apiReasoning: String): Parts {
        val parsed = split(text)
        return parsed.copy(reasoning = listOf(apiReasoning, parsed.reasoning)
            .filter { it.isNotBlank() }.joinToString("\n\n"))
    }
}

private val reasoningKeys = setOf("reasoning", "reasoning_type", "tellev_reasoning_separated", "tellev_generation")

private fun ChatMessage.selectedReasoningExtra(): JsonObject {
    val extra = (swipeInfo.getOrNull(swipeIndex) as? JsonObject)?.get("extra") as? JsonObject
    if (extra != null && reasoningKeys.any { it in extra }) return extra
    // Older Tavern files can have swipe_info timestamps but reasoning only in the active extra.
    val importedActiveIndex = (raw["swipe_id"] as? JsonPrimitive)?.intOrNull ?: 0
    if (swipeInfo.isEmpty() || (raw.isNotEmpty() && importedActiveIndex == swipeIndex)) return metadata
    return extra ?: buildJsonObject { }
}

fun ChatMessage.generationDiagnostics(includeResponse: Boolean = false): JsonObject? {
    val extra = selectedReasoningExtra()
    val diagnostics = extra["tellev_generation"] as? JsonObject ?: return null
    return if (includeResponse) diagnostics else JsonObject(diagnostics - "response")
}

/** An explicit per-swipe record (even an empty one) must never borrow another swipe's reasoning. */
fun ChatMessage.reasoningParts(): MessageReasoning.Parts {
    val text = swipes.getOrNull(swipeIndex) ?: content
    if (role != MessageRole.Character && role != MessageRole.Assistant) return MessageReasoning.Parts(text)
    val extra = selectedReasoningExtra()
    val reasoning = (extra["reasoning"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return if ((extra["tellev_reasoning_separated"] as? JsonPrimitive)?.booleanOrNull == true || reasoning.isNotEmpty()) {
        MessageReasoning.Parts(text, reasoning, "stored")
    } else MessageReasoning.split(text)
}

/** Store provider text for explicit chat export, and only structural diagnostics for inspection. */
fun ChatMessage.withGenerationReasoning(
    parts: MessageReasoning.Parts,
    rawText: String,
    apiReasoning: String,
    finishReason: String?,
    retry: Boolean,
): ChatMessage {
    val extra = JsonObject((metadata - reasoningKeys) + buildJsonObject {
        put("reasoning", parts.reasoning)
        put("reasoning_type", if (apiReasoning.isNotEmpty()) "model" else "parsed")
        put("tellev_reasoning_separated", true)
        put("tellev_generation", buildJsonObject {
            put("bodyLength", parts.body.length)
            put("apiBodyLength", rawText.length)
            put("apiReasoningLength", apiReasoning.length)
            put("reasoningLength", parts.reasoning.length)
            put("tagParsing", parts.status)
            put("retry", retry)
            put("finishReason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
            // No headers/credentials or network logging. Available in user-requested JSONL exports.
            put("response", buildJsonObject { put("content", rawText); put("reasoning", apiReasoning) })
        })
    })
    val slots = swipeInfo.toMutableList()
    while (slots.size <= swipeIndex) slots.add(JsonNull)
    val slot = slots[swipeIndex] as? JsonObject ?: buildJsonObject { }
    slots[swipeIndex] = JsonObject(slot + ("extra" to extra))
    return copy(metadata = extra, swipeInfo = slots)
}

/** Snapshot the active legacy extra before a new version is appended. */
fun ChatMessage.preserveReasoningSwipe(): ChatMessage {
    val slot = swipeInfo.getOrNull(swipeIndex) as? JsonObject ?: buildJsonObject { }
    val extra = slot["extra"] as? JsonObject
    if (extra != null && reasoningKeys.any { it in extra }) return this
    val slots = swipeInfo.toMutableList()
    while (slots.size <= swipeIndex) slots.add(JsonNull)
    slots[swipeIndex] = JsonObject(slot + ("extra" to selectedReasoningExtra()))
    return copy(swipeInfo = slots)
}

fun ChatMessage.selectReasoningSwipe(index: Int): ChatMessage {
    val saved = preserveReasoningSwipe()
    val extra = (saved.swipeInfo.getOrNull(index) as? JsonObject)?.get("extra") as? JsonObject
    return saved.copy(
        swipeIndex = index,
        content = swipes.getOrNull(index) ?: content,
        metadata = JsonObject((metadata - reasoningKeys) + (extra?.filterKeys { it in reasoningKeys } ?: emptyMap())),
    )
}
