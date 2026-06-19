package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

data class InstructPreset(
    val name: String,
    val inputSequence: String,
    val outputSequence: String,
    val lastOutputSequence: String,
    val firstInputSequence: String,
    val lastInputSequence: String,
    val systemSequence: String,
    val stopSequence: String,
    val activationRegex: String,
    val wrap: Boolean,
    val macro: Boolean,
    val names: Boolean,
    val namesForceGroups: Boolean,
)

object InstructMode {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun loadPreset(jsonString: String): InstructPreset {
        val obj = json.parseToJsonElement(jsonString) as JsonObject
        return InstructPreset(
            name = obj.stringField("name", ""),
            inputSequence = obj.stringField("input_sequence", ""),
            outputSequence = obj.stringField("output_sequence", ""),
            lastOutputSequence = obj.stringField("last_output_sequence", ""),
            firstInputSequence = obj.stringField("first_input_sequence", ""),
            lastInputSequence = obj.stringField("last_input_sequence", ""),
            systemSequence = obj.stringField("system_sequence", ""),
            stopSequence = obj.stringField("stop_sequence", ""),
            activationRegex = obj.stringField("activation_regex", ""),
            wrap = obj.boolField("wrap", false),
            macro = obj.boolField("macro", true),
            names = obj.boolField("names", false),
            namesForceGroups = obj.boolField("names_force_groups", false),
        )
    }

    fun loadPreset(obj: JsonObject): InstructPreset {
        return InstructPreset(
            name = obj.stringField("name", ""),
            inputSequence = obj.stringField("input_sequence", ""),
            outputSequence = obj.stringField("output_sequence", ""),
            lastOutputSequence = obj.stringField("last_output_sequence", ""),
            firstInputSequence = obj.stringField("first_input_sequence", ""),
            lastInputSequence = obj.stringField("last_input_sequence", ""),
            systemSequence = obj.stringField("system_sequence", ""),
            stopSequence = obj.stringField("stop_sequence", ""),
            activationRegex = obj.stringField("activation_regex", ""),
            wrap = obj.boolField("wrap", false),
            macro = obj.boolField("macro", true),
            names = obj.boolField("names", false),
            namesForceGroups = obj.boolField("names_force_groups", false),
        )
    }

    fun applyInstruct(
        messages: List<PromptMessage>,
        preset: InstructPreset,
        macroEngine: MacroEngine,
        macroContext: MacroContext,
    ): String {
        if (messages.isEmpty()) return ""

        val expand: (String) -> String = { text ->
            if (preset.macro) macroEngine.expand(text, macroContext) else text
        }

        val result = StringBuilder()

        // Find indices of last user and last assistant messages
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val lastAssistantIndex = messages.indexOfLast {
            it.role == MessageRole.Assistant || it.role == MessageRole.Character
        }
        val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.User }

        for ((index, message) in messages.withIndex()) {
            val isSystem = message.role == MessageRole.System
            val isUser = message.role == MessageRole.User
            val isAssistant = message.role == MessageRole.Assistant || message.role == MessageRole.Character
            val isFirstUser = index == firstUserIndex
            val isLastUser = index == lastUserIndex
            val isLastAssistant = index == lastAssistantIndex

            when {
                isSystem -> {
                    val sysSeq = expand(preset.systemSequence)
                    if (sysSeq.isNotEmpty()) {
                        result.append(sysSeq)
                    }
                    if (preset.names && message.name != null) {
                        result.append(message.name).append(": ")
                    }
                    result.append(message.content)
                }

                isUser -> {
                    val inputSeq = when {
                        isFirstUser && preset.firstInputSequence.isNotEmpty() ->
                            expand(preset.firstInputSequence)
                        isLastUser && preset.lastInputSequence.isNotEmpty() ->
                            expand(preset.lastInputSequence)
                        else -> expand(preset.inputSequence)
                    }
                    result.append(inputSeq)
                    if (preset.names && message.name != null) {
                        result.append(message.name).append(": ")
                    }
                    result.append(message.content)
                }

                isAssistant -> {
                    val outputSeq = if (isLastAssistant && preset.lastOutputSequence.isNotEmpty()) {
                        expand(preset.lastOutputSequence)
                    } else {
                        expand(preset.outputSequence)
                    }
                    result.append(outputSeq)
                    if (preset.names && message.name != null) {
                        result.append(message.name).append(": ")
                    }
                    result.append(message.content)
                }

                else -> {
                    // Tool or other roles: use input sequence
                    result.append(expand(preset.inputSequence))
                    result.append(message.content)
                }
            }
        }

        // If wrap is true, append stop sequence at the end
        if (preset.wrap && preset.stopSequence.isNotEmpty()) {
            result.append(expand(preset.stopSequence))
        }

        return result.toString()
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
}
