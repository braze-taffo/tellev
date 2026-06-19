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

    // Matches {{#if field}}...content...{{/if}} blocks
    private val ifBlockPattern = Regex(
        """\{\{#if\s+(\w+)\}\}([\s\S]*?)\{\{/if\}\}"""
    )

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

    fun buildStoryString(template: String, context: MacroContext): String {
        // First, resolve {{#if field}}...{{/if}} conditional blocks
        val withConditionals = resolveConditionals(template, context)
        // Then expand macros in the result
        val macroEngine = DefaultMacroEngine()
        return macroEngine.expand(withConditionals, context).trim()
    }

    /**
     * Resolves Handlebars-like conditional blocks: {{#if field}}content{{/if}}
     * If the field resolves to a non-empty string, the content is kept; otherwise removed.
     * Fields that can be checked: system, wiBefore, wiAfter, description, personality,
     * scenario, persona, mes_example, charPrompt, chatStart, firstMessage, lastMessage,
     * dialogueExamples, charDescription.
     */
    private fun resolveConditionals(template: String, context: MacroContext): String {
        // We iterate to handle nested or sequential conditionals
        var result = template
        var iterations = 0
        while (ifBlockPattern.containsMatchIn(result) && iterations < 20) {
            result = ifBlockPattern.replace(result) { matchResult ->
                val fieldName = matchResult.groupValues[1]
                val blockContent = matchResult.groupValues[2]
                val fieldValue = resolveFieldValue(fieldName, context)
                if (fieldValue.isNotEmpty()) {
                    blockContent
                } else {
                    ""
                }
            }
            iterations++
        }
        return result
    }

    /**
     * Resolves a field name used in {{#if field}} to its value from the context.
     * Also supports custom variables.
     */
    private fun resolveFieldValue(fieldName: String, context: MacroContext): String {
        return when (fieldName) {
            "system" -> "system" // Placeholder: system prompt exists by convention
            "wiBefore", "wiAfter" -> "" // Handled externally; no data in context
            "description", "charDescription" -> context.characterDescription
            "personality" -> context.characterPersonality
            "scenario" -> context.characterScenario
            "persona" -> context.userName // persona represented by user name
            "mes_example", "dialogueExamples" -> context.exampleMessages
            "charPrompt" -> "" // System prompt content, handled externally
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
    ): String {
        // Replace wiBefore/wiAfter in the story string manually since they are not in MacroContext
        var storyTemplate = preset.storyString

        // Handle wiBefore and wiAfter conditionals by pre-processing
        // We inject them as custom variables and re-resolve
        val enrichedContext = context.copy(
            customVariables = context.customVariables + mapOf(
                "wiBefore" to worldInfoBefore,
                "wiAfter" to worldInfoAfter,
                "system" to if (preset.systemPromptPrefix.isNotEmpty() || preset.systemPromptSuffix.isNotEmpty()) "present" else "",
            )
        )

        val story = buildStoryString(storyTemplate, enrichedContext)

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
