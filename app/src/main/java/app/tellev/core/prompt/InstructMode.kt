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
    val firstOutputSequence: String = "",
    val firstInputSequence: String,
    val lastInputSequence: String,
    val systemSequence: String,
    val stopSequence: String,
    val activationRegex: String,
    val wrap: Boolean,
    val macro: Boolean,
    val names: Boolean,
    val namesForceGroups: Boolean,
    val inputSuffix: String = "",
    val outputSuffix: String = "",
    val systemSuffix: String = "",
    val skipExamples: Boolean = false,
    val systemSameAsUser: Boolean = false,
)

object InstructMode {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun loadPreset(jsonString: String): InstructPreset {
        val obj = json.parseToJsonElement(jsonString) as JsonObject
        return loadPreset(obj)
    }

    fun loadPreset(obj: JsonObject): InstructPreset {
        val systemSameAsUser = obj.boolField("system_same_as_user", false)
        return InstructPreset(
            name = obj.stringField("name", ""),
            inputSequence = obj.stringField("input_sequence", ""),
            outputSequence = obj.stringField("output_sequence", ""),
            lastOutputSequence = obj.stringField("last_output_sequence", ""),
            firstOutputSequence = obj.stringField("first_output_sequence", ""),
            firstInputSequence = obj.stringField("first_input_sequence", ""),
            lastInputSequence = obj.stringField("last_input_sequence", ""),
            systemSequence = if (systemSameAsUser) obj.stringField("input_sequence", "") else obj.stringField("system_sequence", ""),
            stopSequence = obj.stringField("stop_sequence", ""),
            activationRegex = obj.stringField("activation_regex", ""),
            wrap = obj.boolField("wrap", false),
            macro = obj.boolField("macro", true),
            names = obj.boolField("names", false),
            namesForceGroups = obj.boolField("names_force_groups", false),
            inputSuffix = obj.stringField("input_suffix", ""),
            outputSuffix = obj.stringField("output_suffix", ""),
            systemSuffix = obj.stringField("system_suffix", ""),
            skipExamples = obj.boolField("skip_examples", false),
            systemSameAsUser = systemSameAsUser,
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

        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val lastAssistantIndex = messages.indexOfLast {
            it.role == MessageRole.Assistant || it.role == MessageRole.Character
        }
        val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.User }
        val firstAssistantIndex = messages.indexOfFirst {
            it.role == MessageRole.Assistant || it.role == MessageRole.Character
        }

        for ((index, message) in messages.withIndex()) {
            val isSystem = message.role == MessageRole.System
            val isUser = message.role == MessageRole.User
            val isAssistant = message.role == MessageRole.Assistant || message.role == MessageRole.Character
            val isFirstUser = index == firstUserIndex
            val isLastUser = index == lastUserIndex
            val isFirstAssistant = index == firstAssistantIndex
            val isLastAssistant = index == lastAssistantIndex

            when {
                isSystem -> {
                    val sysSeq = expand(preset.systemSequence)
                    if (sysSeq.isNotEmpty()) result.append(sysSeq)
                    if (preset.names && message.name != null) result.append(message.name).append(": ")
                    result.append(message.content)
                    val sysSuf = expand(preset.systemSuffix)
                    if (sysSuf.isNotEmpty()) result.append(sysSuf)
                }

                isUser -> {
                    val inputSeq = when {
                        isFirstUser && preset.firstInputSequence.isNotEmpty() -> expand(preset.firstInputSequence)
                        isLastUser && preset.lastInputSequence.isNotEmpty() -> expand(preset.lastInputSequence)
                        else -> expand(preset.inputSequence)
                    }
                    if (inputSeq.isNotEmpty()) result.append(inputSeq)
                    if (preset.names && message.name != null) result.append(message.name).append(": ")
                    result.append(message.content)
                    val inputSuf = expand(preset.inputSuffix)
                    if (inputSuf.isNotEmpty()) result.append(inputSuf)
                }

                isAssistant -> {
                    val outputSeq = when {
                        isLastAssistant && preset.lastOutputSequence.isNotEmpty() -> expand(preset.lastOutputSequence)
                        isFirstAssistant && preset.firstOutputSequence.isNotEmpty() -> expand(preset.firstOutputSequence)
                        else -> expand(preset.outputSequence)
                    }
                    if (outputSeq.isNotEmpty()) result.append(outputSeq)
                    if (preset.names && message.name != null) result.append(message.name).append(": ")
                    result.append(message.content)
                    val outputSuf = expand(preset.outputSuffix)
                    if (outputSuf.isNotEmpty()) result.append(outputSuf)
                }

                else -> {
                    result.append(expand(preset.inputSequence))
                    result.append(message.content)
                    result.append(expand(preset.inputSuffix))
                }
            }
        }

        // wrap: append the output sequence so the AI knows it's its turn (ST
        // appends output_sequence, not stop_sequence, to prompt generation).
        if (preset.wrap) {
            val wrapSeq = expand(preset.outputSequence)
            if (wrapSeq.isNotEmpty()) result.append(wrapSeq)
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
