package app.tellev.core.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ContextPreset(
    val name: String,
    val storyString: String,
    val exampleSeparator: String,
    val chatStart: String,
    val stopSequence: String,
    val systemPromptPrefix: String,
    val systemPromptSuffix: String,
    val alwaysSystemPromptInHistory: Boolean,
    val tokenBudget: Int,
    val reservedPromptTokens: Int,
    val reservedExamplesTokens: Int,
)

object ContextTemplate {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun loadPreset(jsonString: String): ContextPreset {
        val obj = json.parseToJsonElement(jsonString) as JsonObject
        return presetFromJson(obj)
    }

    fun loadPreset(obj: JsonObject): ContextPreset {
        return presetFromJson(obj)
    }

    private fun presetFromJson(obj: JsonObject): ContextPreset {
        return ContextPreset(
            name = obj.stringField("name", "Default"),
            storyString = obj.stringField("story_string", defaultStoryString()),
            exampleSeparator = obj.stringField("example_separator", ""),
            chatStart = obj.stringField("chat_start", ""),
            stopSequence = obj.stringField("stop_sequence", ""),
            systemPromptPrefix = obj.stringField("system_prompt_prefix", ""),
            systemPromptSuffix = obj.stringField("system_prompt_suffix", ""),
            alwaysSystemPromptInHistory = obj.boolField("always_system_prompt_in_history", false),
            tokenBudget = obj.intField("token_budget", 2048),
            reservedPromptTokens = obj.intField("reserved_prompt_tokens", 50),
            reservedExamplesTokens = obj.intField("reserved_examples_tokens", 300),
        )
    }

    fun buildStoryString(
        template: String,
        context: MacroContext,
        macroEngine: MacroEngine? = null,
    ): String {
        // First, resolve {{#if field}}...{{/if}} conditional blocks
        val withConditionals = resolveConditionals(template, context)
        // Reuse the caller's engine so {{getvar}} and custom-registered macros
        // resolve; a fresh engine silently degraded every custom story_string.
        val engine = macroEngine ?: DefaultMacroEngine()
        return engine.expand(withConditionals, context).trim()
    }

    /**
     * Resolves Handlebars-like conditional blocks: {{#if field}}content{{/if}}
     * If the field resolves to a non-empty string, the content is kept; otherwise removed.
     * Fields that can be checked: system, wiBefore, wiAfter, description, personality,
     * scenario, persona, mes_example, charPrompt, chatStart, firstMessage, lastMessage,
     * dialogueExamples, charDescription.
     */
    private fun resolveConditionals(template: String, context: MacroContext): String {
        // Depth-counting walk (non-greedy regex matching stopped at the FIRST
        // {{/if}}, so a nested {{#if}} truncated its parent's block and leaked
        // stray {{/if}} markers into the prompt).
        val openTag = Regex("""\{\{#if\s+(\w+)\}\}""")
        var result = template
        var guard = 0
        while (guard++ < 50) {
            val open = openTag.find(result) ?: break
            var depth = 1
            var cursor = open.range.last + 1
            var closeStart = -1
            var closeEnd = -1
            while (cursor < result.length && depth > 0) {
                // {{#if must be followed by whitespace (matches the openTag
                // grammar) so `{{#ifx` lookalikes don't skew the depth count.
                val nextOpen = result.indexOf("{{#if", cursor)
                    .takeIf { it + 5 < result.length && result[it + 5].isWhitespace() }
                val nextClose = result.indexOf("{{/if}}", cursor)
                when {
                    nextOpen == null && nextClose == -1 -> break
                    nextOpen != null && (nextClose == -1 || nextOpen < nextClose) -> {
                        depth++
                        cursor = nextOpen + 5
                    }
                    else -> {
                        depth--
                        if (depth == 0) {
                            closeStart = nextClose
                            closeEnd = nextClose + "{{/if}}".length
                        }
                        cursor = nextClose + "{{/if}}".length
                    }
                }
            }
            if (closeStart < 0) break
            val fieldName = open.groupValues[1]
            val blockContent = result.substring(open.range.last + 1, closeStart)
            val fieldValue = resolveFieldValue(fieldName, context)
            result = result.substring(0, open.range.first) +
                blockContent.takeIf { fieldValue.isNotEmpty() }.orEmpty() +
                result.substring(closeEnd)
        }
        return result
    }

    /**
     * Resolves a field name used in {{#if field}} to its value from the context.
     * Also supports custom variables.
     */
    private fun resolveFieldValue(fieldName: String, context: MacroContext): String {
        return when (fieldName) {
            "system" -> context.customVariables["system"] ?: ""
            "wiBefore", "wiAfter" -> "" // Handled externally; no data in context
            "description", "charDescription" -> context.characterDescription
            "personality" -> context.characterPersonality
            "scenario" -> context.characterScenario
            "persona" -> context.personaDescription
            "mes_example", "dialogueExamples" -> context.exampleMessages
            "charPrompt" -> context.customVariables["system"] ?: ""
            "chatStart" -> "" // Chat start marker, handled externally
            "firstMessage" -> context.firstMessage
            "lastMessage" -> context.lastMessage
            "group" -> context.groupMemberNames
            else -> {
                // Check custom variables
                context.customVariables[fieldName] ?: ""
            }
        }
    }

    /**
     * Builds a full system prompt using the context template preset, combining
     * the story string with prefix/suffix and world info.
     */
    fun buildSystemPrompt(
        preset: ContextPreset,
        context: MacroContext,
        worldInfoBefore: String = "",
        worldInfoAfter: String = "",
        macroEngine: MacroEngine? = null,
    ): String {
        // Replace wiBefore/wiAfter in the story string manually since they are not in MacroContext
        var storyTemplate = preset.storyString

        // Handle wiBefore and wiAfter conditionals by pre-processing
        // We inject them as custom variables and re-resolve.
        // `system` is only set here if the caller hasn't already provided it
        // (PromptEngine passes the actual system prompt content; the fallback
        // "present" marker is used when called standalone).
        val systemValue = context.customVariables["system"]
            ?: if (preset.systemPromptPrefix.isNotEmpty() || preset.systemPromptSuffix.isNotEmpty()) "present" else ""
        val enrichedContext = context.copy(
            customVariables = context.customVariables + mapOf(
                "wiBefore" to worldInfoBefore,
                "wiAfter" to worldInfoAfter,
                "system" to systemValue,
            )
        )

        val story = buildStoryString(storyTemplate, enrichedContext, macroEngine)

        return buildString {
            if (preset.systemPromptPrefix.isNotEmpty()) {
                append(preset.systemPromptPrefix)
                append("\n")
            }
            if (story.isNotEmpty()) {
                append(story)
            }
            if (preset.systemPromptSuffix.isNotEmpty()) {
                append("\n")
                append(preset.systemPromptSuffix)
            }
        }.trim()
    }

    private fun defaultStoryString(): String {
        return "{{#if system}}{{system}}\n{{/if}}" +
            "{{#if wiBefore}}{{wiBefore}}\n{{/if}}" +
            "{{#if description}}{{description}}\n{{/if}}" +
            "{{#if personality}}{{personality}}\n{{/if}}" +
            "{{#if scenario}}{{scenario}}\n{{/if}}" +
            "{{#if wiAfter}}{{wiAfter}}\n{{/if}}" +
            "{{#if persona}}{{persona}}\n{{/if}}"
    }

    private fun JsonObject.stringField(key: String, default: String): String {
        val element = this[key] ?: return default
        return try {
            element.jsonPrimitive.content
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.boolField(key: String, default: Boolean): Boolean {
        val element = this[key] ?: return default
        return element.jsonPrimitive.booleanOrNull ?: default
    }

    private fun JsonObject.intField(key: String, default: Int): Int {
        val element = this[key] ?: return default
        return element.jsonPrimitive.intOrNull ?: default
    }
}
