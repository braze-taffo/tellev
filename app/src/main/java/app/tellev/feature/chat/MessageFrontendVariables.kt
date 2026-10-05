package app.tellev.feature.chat

import app.tellev.core.model.ChatMessage
import app.tellev.core.prompt.PromptTemplateVariableSnapshot
import app.tellev.core.extension.CharacterTavernHelperScripts
import kotlinx.serialization.json.*

/** One session's compact variable view. Never copies message bodies or world books. */
internal class MessageFrontendVariables {
    private var messages: List<ChatMessage>? = null
    private var local: JsonObject? = null
    private var global: JsonObject? = null
    private var character: app.tellev.core.model.CharacterCard? = null
    private var preset: app.tellev.core.model.GenerationPreset? = null
    private var characterVariables = JsonObject(emptyMap())
    private var presetVariables = JsonObject(emptyMap())
    private var merged = "{}"
    private var indices = emptyMap<String, Int>()
    private val prefix = LinkedHashMap<Int, JsonObject>(8, .75f, true)
    internal var mergeCount = 0
        private set

    @Synchronized
    fun legacy(state: ChatUiState, snapshot: PromptTemplateVariableSnapshot): String {
        refresh(state, snapshot)
        return merged
    }

    @Synchronized
    fun read(state: ChatUiState, snapshot: PromptTemplateVariableSnapshot, currentId: String, payload: String): String =
        try {
            refresh(state, snapshot)
            val currentIndex = if (currentId == "streaming") state.messages.size
                else requireNotNull(indices[currentId]) { "消息已失效" }
            val request = Json.parseToJsonElement(payload).jsonObject
            val list = state.messages
            val value: JsonElement = when (request["kind"]?.jsonPrimitive?.content) {
                "last" -> JsonPrimitive(list.lastIndex)
                "all" -> prefix.getOrPut(currentIndex) {
                    buildJsonObject {
                        global?.forEach { (k, v) -> put(k, v) }
                        characterVariables.forEach { (k, v) -> put(k, v) }
                        local?.forEach { (k, v) -> put(k, v) }
                        list.take((currentIndex + 1).coerceIn(0, list.size)).forEach { message ->
                            (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                        }
                    }
                }
                else -> {
                    val options = request["options"] as? JsonObject ?: JsonObject(emptyMap())
                    when (options["type"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: "chat") {
                        "global" -> global ?: JsonObject(emptyMap())
                        "chat" -> local ?: JsonObject(emptyMap())
                        "character" -> characterVariables
                        "preset" -> presetVariables
                        "message" -> {
                            val selector = options["message_id"]?.jsonPrimitive
                            val id = if (selector == null || selector.content == "latest") list.indexOfLast { !it.isHidden }
                                else requireNotNull(selector.intOrNull) { "Invalid message_id: ${selector.content}" }
                                    .let { if (it < 0) it + list.size else it }
                            val message = requireNotNull(list.getOrNull(id)) { "Invalid message_id: $id" }
                            message.variables.getOrNull(message.swipeIndex) ?: JsonObject(emptyMap())
                        }
                        else -> JsonObject(emptyMap())
                    }
                }
            }
            while (prefix.size > 8) prefix.remove(prefix.keys.first())
            buildJsonObject { put("ok", true); put("value", value) }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("error", e.message ?: "Variable read failed") }.toString()
        }

    private fun refresh(state: ChatUiState, snapshot: PromptTemplateVariableSnapshot) {
        val nextLocal = state.currentSession?.metadata?.get("variables") as? JsonObject ?: snapshot.local
        if (messages === state.messages && local == nextLocal && global == snapshot.global &&
            character === state.selectedCharacter && preset === state.selectedPreset) return
        messages = state.messages
        indices = state.messages.mapIndexed { index, message -> message.id to index }.toMap()
        local = nextLocal
        global = snapshot.global
        character = state.selectedCharacter
        preset = state.selectedPreset
        characterVariables = character?.let(CharacterTavernHelperScripts::extractCharacterVariables) ?: JsonObject(emptyMap())
        presetVariables = preset?.extensions?.get("tavern_helper")?.let { it as? JsonObject }
            ?.get("variables") as? JsonObject ?: JsonObject(emptyMap())
        prefix.clear()
        merged = decodeEmbeddedJsonValues(buildJsonObject {
            snapshot.global.forEach { (k, v) -> put(k, v) }
            nextLocal.forEach { (k, v) -> put(k, v) }
            state.messages.forEach { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.forEach { (k, v) -> put(k, v) }
            }
        }).toString()
        mergeCount++
    }
}
