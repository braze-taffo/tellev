package app.tellev.feature.chat

import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.prompt.PromptTemplateVariableUpdates
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Handles preparation and building of prompt requests, template variable isolation,
 * metadata synthesis, and diagnostics reporting.
 */
internal object ChatPromptBuilder {

    suspend fun buildPromptMetadata(
        state: ChatUiState,
        config: ProviderConfig,
        preset: GenerationPreset,
        session: ChatSession?,
        extensionHost: ExtensionHost,
        dataStore: StDataStore,
        promptEngine: PromptEngine,
    ): JsonObject {
        val variableSnapshot = promptEngine.snapshotPromptTemplateVariables()
        val localVariables = (session?.metadata?.get("variables") as? JsonObject)
            ?: variableSnapshot.local
        val globalVariables = variableSnapshot.global
        val mergedVariables = mergePromptTemplateVariables(globalVariables, localVariables)
        val worldInfoSettings = dataStore.readWorldInfoSettings()
        val promptSettings = dataStore.readPromptSettings()

        return buildJsonObject {
            put("providerType", config.providerType)
            put("worldInfoRecursive", JsonPrimitive(worldInfoSettings.recursive))
            put("worldInfoMaxRecursionSteps", JsonPrimitive(worldInfoSettings.maxRecursionSteps))
            put("worldInfoScanDepth", JsonPrimitive(worldInfoSettings.scanDepth))
            put("preferCharacterPrompt", JsonPrimitive(promptSettings.preferCharacterPrompt))
            put("preferCharacterJailbreak", JsonPrimitive(promptSettings.preferCharacterJailbreak))
            if (promptSettings.instructEnabled) {
                val instructPreset = if (promptSettings.instructPresetName.isNotBlank()) {
                    dataStore.readInstructPreset(promptSettings.instructPresetName)
                } else {
                    DEFAULT_CHATML_INSTRUCT
                }
                instructPreset?.let { put("instructPreset", it) }
            }
            put(
                "maxContextTokens",
                JsonPrimitive(
                    preset.maxContextTokens ?: defaultContextTokens(),
                ),
            )
            config.model?.takeIf { it.isNotBlank() }?.let { put("modelName", JsonPrimitive(it)) }
            put(
                "maxResponseTokens",
                JsonPrimitive(
                    preset.maxCompletionTokens
                        ?: preset.maxTokens
                        ?: defaultResponseTokens(),
                ),
            )
            val groupId = session?.groupId
            if (!groupId.isNullOrBlank()) {
                val group = dataStore.listGroups().firstOrNull { it.id == groupId }
                if (group != null && group.memberCharacterIds.isNotEmpty()) {
                    val byId = state.characters.associateBy { it.id }
                    val names = group.memberCharacterIds.mapNotNull { id -> byId[id]?.name }
                    if (names.isNotEmpty()) {
                        putJsonArray("groupMembers") { names.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
            put("injectedPrompts", extensionHost.collectInjectedPrompts())
            put("promptTemplateLocalVariables", localVariables)
            put("promptTemplateGlobalVariables", globalVariables)
            put("promptTemplateVariables", mergedVariables)
        }
    }

    suspend fun buildPromptWithSessionScope(
        request: PromptBuildRequest,
        session: ChatSession?,
        promptEngine: PromptEngine,
    ): PromptBuildResult {
        val initialLocal = (session?.metadata?.get("variables") as? JsonObject)
            ?: JsonObject(emptyMap())
        val backend = TrackingPromptLocalBackend(LinkedHashMap(initialLocal))
        val result = promptEngine.buildWithLocalVariableBackendAsync(request, backend)
        val templateLocal = result.promptTemplateVariableUpdates.local
        val combinedLocal = when {
            templateLocal != null -> applyTopLevelVariableDiff(
                base = backend.applyChanges(initialLocal),
                before = initialLocal,
                after = templateLocal,
            )
            backend.hasChanges() -> backend.applyChanges(initialLocal)
            else -> null
        }
        return result.copy(
            promptTemplateVariableUpdates = result.promptTemplateVariableUpdates.copy(
                local = combinedLocal,
            ),
        )
    }

    suspend fun persistPromptTemplateVariableUpdates(
        updates: PromptTemplateVariableUpdates,
        targetSessionId: String?,
        getCurrentSession: () -> ChatSession?,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
        promptEngine: PromptEngine,
    ) {
        updates.local?.let { localVariables ->
            if (targetSessionId != null) {
                val source = requireNotNull(getCurrentSession()?.takeIf { it.id == targetSessionId }) {
                    "Template belongs to an expired chat: $targetSessionId"
                }
                sessionRuntime.persistSessionMutation(
                    source,
                    source.withPromptTemplateVariables(localVariables),
                    onSessionUpdated,
                )
            }
        }
        updates.global?.let(promptEngine::persistGlobalPromptTemplateVariables)
    }

    suspend fun emitPromptDiagnostics(result: PromptBuildResult, extensionHost: ExtensionHost) {
        extensionHost.reportHostEvent(
            ExtensionEvent(
                name = "prompt_diagnostics",
                payload = buildJsonObject {
                    val estimated = result.diagnostics.estimatedTokenCount
                    if (estimated == null) {
                        put("estimatedTokenCount", JsonNull)
                    } else {
                        put("estimatedTokenCount", estimated)
                    }
                    putJsonArray("activatedWorldEntryIds") {
                        result.diagnostics.activatedWorldEntryIds.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("warnings") {
                        result.diagnostics.warnings.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("messages") {
                        result.messages.forEach { message ->
                            add(
                                buildJsonObject {
                                    put("role", message.role.name.lowercase())
                                    if (message.name == null) {
                                        put("name", JsonNull)
                                    } else {
                                        put("name", message.name)
                                    }
                                    put("content", message.content)
                                },
                            )
                        }
                    }
                },
            ),
        )
    }

    fun mergePromptTemplateVariables(global: JsonObject, local: JsonObject): JsonObject =
        buildJsonObject {
            global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
        }

    fun defaultContextTokens(): Int = 1_000_000

    fun defaultResponseTokens(): Int = 128 * 1_024

    private fun applyTopLevelVariableDiff(
        base: JsonObject,
        before: JsonObject,
        after: JsonObject,
    ): JsonObject = JsonObject(base.toMutableMap().apply {
        (before.keys + after.keys).forEach { key ->
            if (before[key] != after[key]) {
                if (key in after) put(key, after.getValue(key)) else remove(key)
            }
        }
    })

    private class TrackingPromptLocalBackend(initial: Map<String, JsonElement>) : LocalVariableBackend {
        private val initialValues = LinkedHashMap(initial)
        private val values = LinkedHashMap(initial)

        override fun snapshot(): Map<String, JsonElement> = values.toMap()

        override fun update(
            transform: (MutableMap<String, JsonElement>) -> Unit,
        ): Map<String, JsonElement> {
            transform(values)
            return snapshot()
        }

        fun hasChanges(): Boolean = values != initialValues

        fun applyChanges(base: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
            val currentValues = this@TrackingPromptLocalBackend.values
            (initialValues.keys + currentValues.keys).forEach { key ->
                if (initialValues[key] != currentValues[key]) {
                    currentValues[key]?.let { put(key, it) } ?: remove(key)
                }
            }
        })
    }

    val DEFAULT_CHATML_INSTRUCT: JsonObject = buildJsonObject {
        put("name", "ChatML")
        put("input_sequence", "<|im_start|>user\n")
        put("output_sequence", "<|im_start|>assistant\n")
        put("last_output_sequence", "")
        put("first_output_sequence", "")
        put("first_input_sequence", "")
        put("last_input_sequence", "")
        put("system_sequence", "<|im_start|>system\n")
        put("system_suffix", "<|im_end|>\n")
        put("input_suffix", "<|im_end|>\n")
        put("output_suffix", "<|im_end|>\n")
        put("stop_sequence", "<|im_end|>")
        put("wrap", true)
        put("macro", true)
        put("names", false)
        put("names_force_groups", false)
        put("activation_regex", "")
        put("system_same_as_user", false)
        put("skip_examples", false)
    }
}

internal fun promptHistoryBeforeCurrentMessage(
    messages: List<ChatMessage>,
    currentMessageId: String,
): List<ChatMessage> =
    if (messages.lastOrNull()?.id == currentMessageId) messages.dropLast(1) else messages

internal fun ChatSession.withPromptTemplateVariables(variables: JsonObject): ChatSession =
    copy(
        metadata = JsonObject(metadata.toMutableMap().apply {
            put("variables", variables)
        }),
    )
