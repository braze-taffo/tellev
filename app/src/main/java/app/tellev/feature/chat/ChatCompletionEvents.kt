package app.tellev.feature.chat

import app.tellev.core.extension.*
import app.tellev.core.model.MessageRole
import app.tellev.core.model.GenerationPreset
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import kotlinx.serialization.json.*

/** Pinned ST: prompt ready -> generate after data -> completion settings -> HTTP. */
internal object ChatCompletionEvents {
    data class Prepared(val config: ProviderConfig, val request: GenerateRequest)

    suspend fun prepare(host: ExtensionHost, config: ProviderConfig, request: GenerateRequest,
                        adapter: ProviderAdapter? = null): Prepared {
        val original = (adapter as? CompletionSettingsAdapter)?.completionPayload(config, request)?.get("messages") as? JsonArray
            ?: wireMessages(request.prompt)
        val ready = transform(host, StEventCatalog.CHAT_COMPLETION_PROMPT_READY,
            buildJsonObject { put("chat", original); put("dryRun", false) })
        val chat = messages(ready["chat"])
        val readyRequest = request.copy(prompt = withMessages(request.prompt, chat))
        val before = (adapter as? CompletionSettingsAdapter)?.completionPayload(config, readyRequest)
            ?: buildJsonObject {
                put("messages", wireMessages(readyRequest.prompt))
                config.model?.let { put("model", it) }
                put("stream", request.stream)
                request.preset.temperature?.let { put("temperature", it) }
                request.preset.topP?.let { put("top_p", it) }
                request.preset.topK?.let { put("top_k", it) }
                request.preset.presencePenalty?.let { put("presence_penalty", it) }
                request.preset.frequencyPenalty?.let { put("frequency_penalty", it) }
                request.preset.seed?.let { put("seed", it) }
                (request.prompt.maxTokens ?: request.preset.maxCompletionTokens ?: request.preset.maxTokens)?.let { put("max_tokens", it) }
                put("stop", JsonArray((request.preset.stop + request.prompt.stop).distinct().map(::JsonPrimitive)))
            }
        val after = transform(host, StEventCatalog.GENERATE_AFTER_DATA,
            JsonObject((before - "messages") + ("prompt" to chat)), dryRun = false)
        val outgoing = request.copy(prompt = withMessages(request.prompt, messages(after["prompt"])))
        val initial = JsonObject((after - "prompt") + ("messages" to wireMessages(outgoing.prompt)))
        val baseline = if (adapter?.id == ProviderCatalog.AZURE_OPENAI) JsonObject(before +
            ("model" to (config.options["deployment"] ?: JsonPrimitive(config.model ?: "gpt-4")))) else before
        val eventSettings = if (adapter?.id == ProviderCatalog.AZURE_OPENAI && initial["model"] == null)
            JsonObject(initial + ("model" to baseline.getValue("model"))) else initial
        val settings = transform(host, StEventCatalog.CHAT_COMPLETION_SETTINGS_READY, eventSettings)
        val finalMessages = messages(settings["messages"])
        if (adapter != null && adapter !is CompletionSettingsAdapter) {
            require(finalMessages.all { message ->
                val value = message.jsonObject
                (value["content"] as? JsonPrimitive)?.isString == true &&
                    value["tool_calls"] == null && value["tool_call_id"] == null
            }) { "Provider ${adapter.id} cannot apply structured completion messages" }
            val fields = setOf("messages", "model", "stream", "temperature", "top_p", "top_k", "presence_penalty", "frequency_penalty", "seed", "max_tokens", "max_completion_tokens", "stop")
            require(settings.keys.all { it in fields }) { "Provider ${adapter.id} cannot apply these completion settings" }
        }
        if (settings.filterKeys { it != "messages" } == baseline.filterKeys { it != "messages" }) {
            return Prepared(config, request.copy(prompt = withMessages(request.prompt, finalMessages),
                completionSettings = if (adapter is CompletionSettingsAdapter) settings else null))
        }
        fun number(key: String): Double? = settings[key]?.let {
            require((it as? JsonPrimitive)?.doubleOrNull?.isFinite() == true) { "Invalid completion setting: $key" }
            it.jsonPrimitive.double
        }
        fun integer(key: String): Int? = settings[key]?.let {
            require((it as? JsonPrimitive)?.intOrNull != null) { "Invalid completion setting: $key" }
            it.jsonPrimitive.int
        }
        val stream = settings["stream"]?.let {
            require((it as? JsonPrimitive)?.booleanOrNull != null) { "Invalid completion stream" }
            it.jsonPrimitive.boolean
        } ?: request.stream
        val model = settings["model"]?.let {
            require((it as? JsonPrimitive)?.isString == true && it.jsonPrimitive.content.isNotBlank()) { "Invalid completion model" }
            it.jsonPrimitive.content
        } ?: config.model
        val stop = settings["stop"]?.let { value ->
            when (value) {
                JsonNull -> emptyList()
                is JsonPrimitive -> { require(value.isString); listOf(value.content) }
                is JsonArray -> value.map { require((it as? JsonPrimitive)?.isString == true); it.jsonPrimitive.content }
                else -> error("Invalid completion stop")
            }
        } ?: emptyList()
        val maxTokens = integer("max_completion_tokens") ?: integer("max_tokens")
        require(maxTokens == null || maxTokens > 0) { "Completion token limit must be positive" }
        val preset = request.preset.copy(temperature = number("temperature"), topP = number("top_p"),
            topK = integer("top_k"), presencePenalty = number("presence_penalty"),
            frequencyPenalty = number("frequency_penalty"), seed = settings["seed"]?.jsonPrimitive?.longOrNull,
            maxTokens = maxTokens, maxCompletionTokens = maxTokens, stop = emptyList())
        val finalConfig = if (adapter?.id == ProviderCatalog.AZURE_OPENAI && model != config.model)
            config.copy(model = model, options = JsonObject(config.options + ("deployment" to JsonPrimitive(model!!)))) else config.copy(model = model)
        return Prepared(finalConfig, outgoing.copy(
            prompt = withMessages(request.prompt, finalMessages).copy(stop = stop, maxTokens = maxTokens),
            preset = preset, stream = stream,
            completionSettings = if (adapter is CompletionSettingsAdapter) settings else null))
    }

    // Kept for callers interested only in the outgoing prompt projection.
    suspend fun prepare(host: ExtensionHost, prompt: PromptBuildResult): PromptBuildResult =
        prepare(host, ProviderConfig(prompt.providerType, ""), GenerateRequest(prompt,
            GenerationPreset("event", "event", prompt.providerType))).request.prompt

    private fun wireMessages(prompt: PromptBuildResult) = JsonArray(prompt.messages.map { message -> buildJsonObject {
        put("role", when (message.role) {
            MessageRole.User -> "user"; MessageRole.System -> "system"
            MessageRole.Character, MessageRole.Assistant -> "assistant"; MessageRole.Tool -> "tool"
        })
        put("content", message.content)
        message.name?.let { put("name", it) }
        message.wireFields?.forEach { (key, value) -> put(key, value) }
    } })

    private fun withMessages(prompt: PromptBuildResult, wire: JsonArray): PromptBuildResult {
        if (wire == wireMessages(prompt)) return prompt
        return prompt.copy(messages = wire.mapIndexed { index, element ->
            val message = element.jsonObject
            val role = when (message.getValue("role").jsonPrimitive.content) {
                "user" -> MessageRole.User; "system" -> MessageRole.System; "tool" -> MessageRole.Tool
                else -> MessageRole.Assistant
            }
            val content = message["content"]
            val text = if (content is JsonPrimitive) content.contentOrNull.orEmpty()
                else (content as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }?.joinToString("\n").orEmpty()
            val previous = prompt.messages.getOrNull(index)
            val name = message["name"]?.jsonPrimitive?.contentOrNull
            val fields = JsonObject(message.filterKeys { it !in setOf("role", "name") })
            val previousRole = if (previous?.role == MessageRole.Character) MessageRole.Assistant else previous?.role
            if (previous != null && previousRole == role && previous.name == name)
                previous.copy(content = text, wireFields = fields)
            else PromptMessage(role, name, text, wireFields = fields)
        })
    }

    private suspend fun transform(host: ExtensionHost, name: String, data: JsonObject, dryRun: Boolean? = null): JsonObject {
        val args = listOf(data) + listOfNotNull(dryRun?.let(::JsonPrimitive))
        val payload = host.emitMutable(ExtensionEvent(name = name,
            payload = buildJsonObject { put("args", JsonArray(args)) }))
        return (payload["args"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IllegalArgumentException("Invalid completion event payload: $name")
    }

    private fun messages(value: JsonElement?): JsonArray {
        require(value is JsonArray) { "Completion messages must be an array" }
        value.forEach { element ->
            require(element is JsonObject) { "Completion message must be an object" }
            require((element["role"] as? JsonPrimitive)?.contentOrNull in setOf("user", "system", "assistant", "tool")) { "Invalid completion role" }
            val content = element["content"]
            require((content is JsonPrimitive && content.isString) || content is JsonArray ||
                ((content == null || content == JsonNull) && element["tool_calls"] is JsonArray)) { "Invalid completion content" }
            if (content is JsonArray) content.forEach { part ->
                require(part is JsonObject && (part["type"] as? JsonPrimitive)?.isString == true) { "Invalid completion content part" }
            }
            require(element["name"] == null || (element["name"] as? JsonPrimitive)?.isString == true) { "Invalid completion name" }
        }
        return value
    }
}
