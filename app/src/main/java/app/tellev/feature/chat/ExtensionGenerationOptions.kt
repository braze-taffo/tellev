package app.tellev.feature.chat

import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.*

/** Request-only helper options. No selection, history or preset is saved here. */
internal object ExtensionGenerationOptions {
    val rawOrder = listOf("world_info_before", "persona_description", "char_description", "char_personality",
        "scenario", "world_info_after", "dialogue_examples", "chat_history", "user_input")

    suspend fun preset(options: JsonObject, current: GenerationPreset, store: StDataStore): GenerationPreset {
        val name = options["preset_name"]?.jsonPrimitive?.content ?: "in_use"
        val selected = if (name == "in_use") current else requireNotNull(store.readPreset(current.category, name)) {
            "Preset not found: $name"
        }
        val custom = options["custom_api"] as? JsonObject ?: JsonObject(emptyMap())
        fun number(key: String, previous: Double?): Double? = if (custom[key] == null) previous else custom[key]!!.let {
            when ((it as? JsonPrimitive)?.content) {
                "same_as_preset" -> previous; "unset" -> null
                else -> it.jsonPrimitive.doubleOrNull?.takeIf(Double::isFinite) ?: error("Invalid custom API $key")
            }
        }
        fun int(key: String, previous: Int?): Int? = if (custom[key] == null) previous else custom[key]!!.let {
            when ((it as? JsonPrimitive)?.content) {
                "same_as_preset" -> previous; "unset" -> null
                else -> it.jsonPrimitive.intOrNull ?: error("Invalid custom API $key")
            }
        }
        val schema = options["json_schema"] as? JsonObject
        var raw = selected.raw
        if (schema != null) {
            val value = schema["value"] as? JsonObject ?: error("JSON schema must contain value")
            val definition = JsonObject(schema.filterKeys { it != "value" } + ("schema" to value))
            raw = JsonObject(raw + ("response_format" to buildJsonObject {
                put("type", "json_schema"); put("json_schema", definition)
            }))
        }
        val changed = selected.copy(temperature = number("temperature", selected.temperature),
            topP = number("top_p", selected.topP), topK = int("top_k", selected.topK),
            frequencyPenalty = number("frequency_penalty", selected.frequencyPenalty),
            presencePenalty = number("presence_penalty", selected.presencePenalty),
            maxTokens = int("max_tokens", selected.maxTokens), maxCompletionTokens = int("max_tokens", selected.maxCompletionTokens), raw = raw)
        if (options["__tellev_use_preset"]?.jsonPrimitive?.booleanOrNull != false) return changed
        val order = options["ordered_prompts"] as? JsonArray ?: JsonArray(rawOrder.map(::JsonPrimitive))
        val prompts = order.mapIndexed { index, element ->
            when (element) {
                is JsonPrimitive -> {
                    require(element.isString && element.content in rawOrder) { "Invalid ordered prompt placeholder" }
                    PresetPrompt(element.content, order = index)
                }
                is JsonObject -> {
                    val role = element["role"]?.jsonPrimitive?.content
                    require(role in setOf("system", "user", "assistant")) { "Invalid ordered prompt role" }
                    PresetPrompt("tellev_raw_$index", role = role!!, content = element["content"]?.jsonPrimitive?.content ?: "", order = index)
                }
                else -> error("Invalid ordered prompt")
            }
        }
        return changed.copy(prompts = prompts, promptsUnused = emptyList(), raw = JsonObject(raw +
            mapOf("new_chat_prompt" to JsonPrimitive(""), "assistant_prefill" to JsonPrimitive(""))))
    }

    fun config(options: JsonObject, current: ProviderConfig): ProviderConfig {
        val custom = options["custom_api"] as? JsonObject ?: return current
        require(custom["proxy_preset"] == null) { "Proxy presets are not supported by this host; use apiurl/key" }
        val source = custom["source"]?.jsonPrimitive?.content
        val provider = when (source) {
            null -> current.providerType
            "openai", "custom", "openai-compatible" -> ProviderCatalog.OPENAI_COMPATIBLE
            "openrouter" -> ProviderCatalog.OPENROUTER
            "deepseek" -> ProviderCatalog.DEEPSEEK
            "claude", "anthropic" -> ProviderCatalog.ANTHROPIC
            "makersuite", "gemini" -> ProviderCatalog.GEMINI
            else -> error("Unsupported custom API source: $source")
        }
        val url = custom["apiurl"]?.jsonPrimitive?.content
        require(provider == current.providerType || !url.isNullOrBlank()) { "Custom API source requires apiurl" }
        return current.copy(providerType = provider, baseUrl = url ?: current.baseUrl,
            apiKey = custom["key"]?.jsonPrimitive?.content ?: if (provider == current.providerType) current.apiKey else null,
            model = custom["model"]?.jsonPrimitive?.content ?: current.model,
            headers = if (url != null && url != current.baseUrl) emptyMap() else current.headers,
            options = if (provider == current.providerType) current.options else JsonObject(emptyMap()))
    }

    fun promptRequest(options: JsonObject, request: PromptBuildRequest): PromptBuildRequest {
        val overrides = options["overrides"] as? JsonObject ?: JsonObject(emptyMap())
        fun text(key: String, old: String) = overrides[key]?.jsonPrimitive?.content ?: old
        val chatOverride = overrides["chat_history"] as? JsonObject
        var history = (chatOverride?.get("prompts") as? JsonArray)?.mapIndexed { index, element ->
            val message = element.jsonObject
            val role = when (message["role"]?.jsonPrimitive?.content) {
                "user" -> MessageRole.User; "assistant" -> MessageRole.Assistant; "system" -> MessageRole.System
                else -> error("Invalid overridden chat role")
            }
            ChatMessage("tellev_override_$index", role, "", message["content"]?.jsonPrimitive?.content.orEmpty(), 0)
        } ?: request.messages
        options["max_chat_history"]?.let {
            if (it.jsonPrimitive.content != "all") {
                val count = it.jsonPrimitive.intOrNull ?: error("Invalid max_chat_history")
                require(count >= 0) { "max_chat_history must be nonnegative" }; history = history.takeLast(count)
            }
        }
        val injections = (request.metadata["injectedPrompts"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        (options["injects"] as? JsonArray)?.forEachIndexed { index, element ->
            val injection = element.jsonObject
            val position = injection["position"]?.jsonPrimitive?.content ?: "in_chat"
            require(position in setOf("none", "in_chat")) { "Invalid helper injection position" }
            injections["tellev_generation_$index"] = buildJsonObject {
                put("value", injection["content"] ?: JsonPrimitive("")); put("position", if (position == "none") -1 else 1)
                put("depth", injection["depth"] ?: JsonPrimitive(0)); put("role", injection["role"] ?: JsonPrimitive("system"))
                put("shouldScan", injection["should_scan"] ?: JsonPrimitive(false))
            }
        }
        chatOverride?.get("author_note")?.let { note -> injections["tellev_author_note"] = buildJsonObject {
            put("value", note); put("position", 1); put("depth", 0); put("role", "system")
        } }
        val raw = options["__tellev_use_preset"]?.jsonPrimitive?.booleanOrNull == false
        return request.copy(character = request.character.copy(description = text("char_description", request.character.description),
            personality = text("char_personality", request.character.personality), scenario = text("scenario", request.character.scenario),
            exampleMessages = text("dialogue_examples", request.character.exampleMessages)),
            persona = request.persona?.copy(description = text("persona_description", request.persona.description))
                ?: overrides["persona_description"]?.let { Persona("generation", "User", it.jsonPrimitive.content) },
            messages = history, metadata = JsonObject(request.metadata + buildJsonObject {
                put("injectedPrompts", JsonObject(injections)); put("tavernRawGeneration", raw)
                put("tavernPromptOverrides", overrides)
                put("tavernWithDepthEntries", chatOverride?.get("with_depth_entries") ?: JsonPrimitive(true))
                if (raw) { put("preferCharacterPrompt", false); put("preferCharacterJailbreak", false) }
            }))
    }

    fun validateCapabilities(options: JsonObject, config: ProviderConfig, request: GenerateRequest, adapter: ProviderAdapter) {
        val tools = options["tools"] as? JsonArray
        val schema = options["json_schema"]
        if (tools?.isNotEmpty() != true && schema == null) return
        val payload = (adapter as? CompletionSettingsAdapter)?.completionPayload(config, request)
        if (tools?.isNotEmpty() == true) require(payload?.get("tools") == tools) {
            "Provider ${adapter.id} cannot apply helper tool requests"
        }
        if (schema != null) require(payload?.get("response_format") == request.preset.raw["response_format"] &&
            payload?.get("response_format") != null) { "Provider ${adapter.id} cannot apply helper JSON schema" }
    }

    fun request(options: JsonObject, prompt: PromptBuildResult, preset: GenerationPreset, stream: Boolean): GenerateRequest {
        val custom = options["custom_api"] as? JsonObject
        fun images(value: JsonElement?): List<String> = when (value) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.flatMap(::images)
            is JsonPrimitive -> {
                require(value.isString && (value.content.startsWith("data:image/") || value.content.startsWith("https://") || value.content.startsWith("http://"))) { "Image must be an image data URL or HTTP URL" }
                listOf(value.content)
            }
            else -> error("Invalid generation image")
        }
        val ordered = options["ordered_prompts"] as? JsonArray
        val messages = prompt.messages.mapIndexed { index, message ->
            val imageSource = when {
                message.channel?.startsWith("tellev_raw_") == true -> {
                    val item = ordered?.getOrNull(message.channel.substringAfterLast('_').toInt()) as? JsonObject
                    item?.get("image")
                }
                message.channel == "user_input" || (index == prompt.messages.indexOfLast { it.role == MessageRole.User }) -> options["image"]
                else -> null
            }
            val urls = images(imageSource)
            if (urls.isEmpty()) message else message.copy(wireFields = buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", message.content) })
                    urls.forEach { url -> add(buildJsonObject {
                        put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) })
                    }) }
                })
            })
        }
        val result = prompt.copy(messages = messages,
            maxTokens = if (custom?.get("max_tokens")?.jsonPrimitive?.content == "unset") null else prompt.maxTokens)
        return GenerateRequest(result, preset, stream = stream, metadata = JsonObject(options.filterKeys { it in setOf("tools", "tool_choice") }))
    }
}
