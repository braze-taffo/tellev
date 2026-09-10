package app.tellev.core.storage.repository

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetImportResult
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.ST_1_18_OPENAI_DEFAULT_JSON
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.codec.PresetCodec
import app.tellev.core.storage.parsePresetPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.readText

internal class PresetRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val presetChanges: MutableSharedFlow<PresetCategory>,
) {
    suspend fun listPresets(): List<GenerationPreset> = withContext(Dispatchers.IO) {
        presetDirectoriesWithCategories().flatMap { (category, root) ->
            StorageFileOps.readJsonFiles(root, json).filterNot { (path, _) -> path.nameWithoutExtension == "in_use" }
                .map { (path, raw) -> PresetCodec.parsePreset(path, raw, category, resolvePresetDirectory(category).name) }
        }
    }

    suspend fun readPreset(category: PresetCategory, name: String): GenerationPreset? =
        withContext(Dispatchers.IO) {
            val path = resolvePresetDirectory(category).resolve("$name.json")
            if (!path.exists()) return@withContext null
            val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
                ?: return@withContext null
            PresetCodec.parsePreset(path, raw, category, resolvePresetDirectory(category).name)
        }

    suspend fun readSelectedPresetName(category: PresetCategory): String? =
        withContext(Dispatchers.IO) {
            val statePath = layout.root.resolve("preset-selection.json")
            if (!statePath.exists()) return@withContext null
            val raw = runCatching { json.parseToJsonElement(statePath.readText()) as? JsonObject }.getOrNull()
                ?: return@withContext null
            raw[category.name.lowercase()]?.jsonPrimitive?.content
        }

    suspend fun selectPreset(category: PresetCategory, name: String): Unit =
        withContext(Dispatchers.IO) {
            val directory = resolvePresetDirectory(category)
            val source = directory.resolve("$name.json")
            if (!source.exists()) error("Preset not found: ${category.name.lowercase()}/$name")
            source.copyTo(directory.resolve("in_use.json"), overwrite = true)

            val statePath = layout.root.resolve("preset-selection.json")
            val current = if (statePath.exists()) {
                runCatching { json.parseToJsonElement(statePath.readText()) as? JsonObject }.getOrNull()
            } else null
            val merged = current.orEmpty().toMutableMap()
            merged[category.name.lowercase()] = JsonPrimitive(name)
            StorageFileOps.durableWriteText(durableFiles, statePath, json.encodeToString(JsonObject.serializer(), JsonObject(merged)))
            presetChanges.tryEmit(category)
        }

    suspend fun savePreset(preset: GenerationPreset): Unit = withContext(Dispatchers.IO) {
        val parent = resolvePresetDirectory(if (preset.category == PresetCategory.OpenAi) presetCategory(preset.providerType) else preset.category)
        parent.createDirectories()
        val merged = preset.raw.toMutableMap()
        preset.temperature?.let { merged["temperature"] = JsonPrimitive(it) }
        preset.topP?.let { merged["top_p"] = JsonPrimitive(it) }
        preset.topK?.let { merged["top_k"] = JsonPrimitive(it) }
        preset.topA?.let { merged["top_a"] = JsonPrimitive(it) }
        preset.minP?.let { merged["min_p"] = JsonPrimitive(it) }
        preset.repetitionPenalty?.let { merged["repetition_penalty"] = JsonPrimitive(it) }
        preset.repetitionPenaltyRange?.let { merged["repetition_penalty_range"] = JsonPrimitive(it) }
        preset.presencePenalty?.let { merged["presence_penalty"] = JsonPrimitive(it) }
        preset.frequencyPenalty?.let { merged["frequency_penalty"] = JsonPrimitive(it) }
        preset.seed?.let { merged["seed"] = JsonPrimitive(it) }
        preset.maxContextTokens?.let { merged["openai_max_context"] = JsonPrimitive(it) }
        (preset.maxCompletionTokens ?: preset.maxTokens)?.let {
            merged["openai_max_tokens"] = JsonPrimitive(it)
            merged["max_tokens"] = JsonPrimitive(it)
        }
        if (preset.stop.isNotEmpty()) merged["stop"] = stringArray(preset.stop)
        if (preset.prompts.isNotEmpty() || preset.promptsUnused.isNotEmpty() || "prompts" in merged) {
            val definitions = (preset.prompts + preset.promptsUnused).distinctBy { it.identifier }
            merged["prompts"] = JsonArray(definitions.map(PresetCodec::serializePresetPrompt))
            merged["prompts_unused"] = JsonArray(preset.promptsUnused.map(PresetCodec::serializePresetPrompt))
            merged["prompt_order"] = PresetCodec.serializePromptOrder(merged["prompt_order"], preset.prompts)
        }
        if (preset.extensions.isNotEmpty()) merged["extensions"] = preset.extensions
        val path = parent.resolve("${preset.id}.json")
        StorageFileOps.durableWriteText(
            durableFiles,
            path,
            json.encodeToString(JsonObject.serializer(), JsonObject(merged)),
        )
        presetChanges.tryEmit(preset.category)
    }

    suspend fun saveWorkingPreset(
        category: PresetCategory,
        preset: GenerationPreset,
    ) {
        savePreset(
            preset.copy(
                id = "in_use",
                category = category,
                providerType = resolvePresetDirectory(category).name,
            ),
        )
    }

    suspend fun deletePreset(id: String, providerType: String?): Boolean = withContext(Dispatchers.IO) {
        val targets = if (providerType.isNullOrBlank()) {
            presetDirectoriesWithCategories()
        } else {
            val category = presetCategory(providerType)
            listOf(category to resolvePresetDirectory(category))
        }
        var deletedAny = false
        targets.forEach { (category, directory) ->
            if (directory.resolve("$id.json").deleteIfExists()) {
                deletedAny = true
                ensureDefaultPreset(category)
                if (readSelectedPresetName(category) == id) {
                    selectPreset(category, "default")
                } else {
                    presetChanges.tryEmit(category)
                }
            }
        }
        deletedAny
    }

    suspend fun importPreset(
        jsonBytes: ByteArray,
        providerCategory: String,
        sourceFileName: String,
    ): PresetImportResult = withContext(Dispatchers.IO) {
        val rawJsonString = jsonBytes.decodeToString()
        val parsed = runCatching { json.parseToJsonElement(rawJsonString) }.getOrNull()
        val rawObj = parsed as? JsonObject
            ?: error("预设 JSON 格式无效：$sourceFileName 不是有效的 JSON 对象")

        val category = presetCategory(providerCategory)
        val parent = resolvePresetDirectory(category)
        parent.createDirectories()

        val baseStem = sourceFileName.substringBeforeLast('.')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "preset" }
        var id = baseStem
        var suffix = 2
        while (parent.resolve("$id.json").exists()) id = "$baseStem-${suffix++}"
        val destination = parent.resolve("$id.json")
        destination.outputStream().use { it.write(jsonBytes) }
        destination.copyTo(parent.resolve("in_use.json"), overwrite = true)
        val statePath = layout.root.resolve("preset-selection.json")
        val current = if (statePath.exists()) {
            runCatching { json.parseToJsonElement(statePath.readText()) as? JsonObject }.getOrNull()
        } else null
        val selection = current.orEmpty().toMutableMap()
        selection[category.name.lowercase()] = JsonPrimitive(id)
        StorageFileOps.durableWriteText(durableFiles, statePath, json.encodeToString(JsonObject.serializer(), JsonObject(selection)))

        val preset = PresetCodec.parsePreset(destination, rawObj, category, resolvePresetDirectory(category).name)
        val applied = rawObj.keys.intersect(APPLIED_PRESET_FIELDS)
        val routing = rawObj.keys.intersect(ROUTING_PRESET_FIELDS)
        PresetImportResult(
            preset = preset,
            inferredCategory = category,
            appliedFields = applied,
            preservedFields = rawObj.keys - applied,
            warnings = buildList {
                if (routing.isNotEmpty()) add("服务商、接口和模型字段已保留但不会应用：${routing.sorted().joinToString()}")
            },
        ).also { presetChanges.tryEmit(category) }
    }

    fun ensureDefaultPresets() {
        presetDirectoriesWithCategories().forEach { (category, directory) ->
            directory.createDirectories()
            val hasNamedPreset = directory.listDirectoryEntries("*.json")
                .any { it.nameWithoutExtension != "in_use" }
            if (!hasNamedPreset) ensureDefaultPreset(category)
        }
    }

    fun ensureDefaultPreset(category: PresetCategory) {
        val directory = resolvePresetDirectory(category)
        directory.createDirectories()
        val path = directory.resolve("default.json")
        if (!path.exists()) {
            StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(JsonObject.serializer(), defaultPresetRaw(category)))
        }
    }

    suspend fun migrateDefaultPresetLimits() {
        presetDirectoriesWithCategories().forEach { (category, directory) ->
            var changed = upgradeDefaultPresetLimits(directory.resolve("default.json"))
            if (runCatching { readSelectedPresetName(category) }.getOrNull() == "default") {
                changed = upgradeDefaultPresetLimits(directory.resolve("in_use.json")) || changed
            }
            if (changed) presetChanges.tryEmit(category)
        }
    }

    private fun upgradeDefaultPresetLimits(path: Path): Boolean {
        if (!path.exists()) return false
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }
            .getOrNull() ?: return false
        val contextTokens = raw.intValue("openai_max_context")
            ?: raw.intValue("max_context")
            ?: raw.intValue("context_length")
        val completionTokens = raw.intValue("openai_max_tokens")
            ?: raw.intValue("max_tokens")
            ?: raw.intValue("maxTokens")
            ?: raw.intValue("max_new_tokens")
        val upgradeContext = contextTokens == null || contextTokens <= LEGACY_LOW_CONTEXT_TOKENS
        val upgradeCompletion = completionTokens == null || completionTokens <= LEGACY_LOW_COMPLETION_TOKENS
        if (!upgradeContext && !upgradeCompletion) return false

        val migrated = raw.toMutableMap().apply {
            if (upgradeContext) put("openai_max_context", JsonPrimitive(DEFAULT_CONTEXT_TOKENS))
            if (upgradeCompletion) put("openai_max_tokens", JsonPrimitive(DEFAULT_COMPLETION_TOKENS))
        }
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(JsonObject.serializer(), JsonObject(migrated)))
        return true
    }

    suspend fun migrateHandwrittenOpenAiDefaultPreset() {
        val path = layout.openAiSettings.resolve("default.json")
        if (!path.exists()) return
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull() ?: return
        if (raw["name"]?.jsonPrimitive?.content != "默认聊天") return
        if (raw.doubleValue("temperature") != 0.7 || raw.doubleValue("top_p") != 1.0) return
        val identifiers = parsePresetPrompts(raw["prompts"]).map { it.identifier }.toSet()
        val legacyIdentifiers = setOf(
            "main", "worldInfoBefore", "charDescription", "charPersonality", "scenario",
            "personaDescription", "dialogueExamples", "worldInfoAfter", "chatHistory", "jailbreak",
        )
        if (identifiers != legacyIdentifiers) return
        val canonical = defaultPresetRaw(PresetCategory.OpenAi)
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(JsonObject.serializer(), canonical))
        if (runCatching { readSelectedPresetName(PresetCategory.OpenAi) }.getOrNull() == "default") {
            path.copyTo(layout.openAiSettings.resolve("in_use.json"), overwrite = true)
        }
        presetChanges.tryEmit(PresetCategory.OpenAi)
    }

    private fun defaultPresetRaw(category: PresetCategory): JsonObject {
        if (category != PresetCategory.OpenAi) return legacyDefaultPresetRaw()
        return json.parseToJsonElement(ST_1_18_OPENAI_DEFAULT_JSON).jsonObject
    }

    private fun legacyDefaultPresetRaw(): JsonObject = buildJsonObject {
        put("name", "默认聊天")
        put("temperature", 0.7)
        put("top_p", 1.0)
        put("openai_max_context", DEFAULT_CONTEXT_TOKENS)
        put("openai_max_tokens", DEFAULT_COMPLETION_TOKENS)
        val identifiers = listOf("main", "worldInfoBefore", "charDescription", "charPersonality", "scenario", "personaDescription", "dialogueExamples", "worldInfoAfter", "chatHistory", "jailbreak")
        putJsonArray("prompts") {
            identifiers.forEachIndexed { index, identifier -> addJsonObject {
                put("identifier", identifier); put("name", identifier); put("role", "system")
                put("content", ""); put("enabled", true); put("relative", identifier == "chatHistory"); put("order", index)
            } }
        }
        putJsonArray("prompt_order") { addJsonObject {
            put("character_id", 100001)
            putJsonArray("order") { identifiers.forEach { identifier -> addJsonObject { put("identifier", identifier); put("enabled", true) } } }
        } }
    }

    fun presetCategory(value: String): PresetCategory = when (value.lowercase()) {
        "textgen", "textgen-webui", "textgen settings" -> PresetCategory.TextGen
        "kobold", "koboldai", "koboldcpp", "koboldai settings" -> PresetCategory.Kobold
        "novelai", "novelai settings" -> PresetCategory.NovelAi
        else -> PresetCategory.OpenAi
    }

    fun resolvePresetDirectory(category: PresetCategory): Path = when (category) {
        PresetCategory.OpenAi -> layout.openAiSettings
        PresetCategory.TextGen -> layout.textGenSettings
        PresetCategory.Kobold -> layout.koboldAiSettings
        PresetCategory.NovelAi -> layout.novelAiSettings
    }

    fun presetDirectoriesWithCategories(): List<Pair<PresetCategory, Path>> = listOf(
        PresetCategory.OpenAi to layout.openAiSettings,
        PresetCategory.TextGen to layout.textGenSettings,
        PresetCategory.Kobold to layout.koboldAiSettings,
        PresetCategory.NovelAi to layout.novelAiSettings,
    )

    private fun stringArray(values: List<String>): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })

    private fun JsonObject.doubleValue(key: String): Double? =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    companion object {
        val ROUTING_PRESET_FIELDS = setOf(
            "chat_completion_source", "openai_model", "claude_model", "openrouter_model",
            "custom_model", "custom_url", "reverse_proxy", "proxy_password", "api_key", "model",
        )
        val APPLIED_PRESET_FIELDS = setOf(
            "temperature", "temp", "temp_openai", "top_p", "topP", "top_k", "topK", "top_a",
            "topA", "min_p", "minP", "repetition_penalty", "rep_pen", "repetition_penalty_range",
            "rep_pen_range", "presence_penalty", "frequency_penalty", "seed", "stop",
            "openai_max_context", "max_context", "context_length", "openai_max_tokens", "max_tokens",
            "maxTokens", "max_new_tokens", "prompts", "prompts_unused", "promptsUnused", "prompt_order",
            "extensions", "names_behavior", "new_chat_prompt", "new_example_chat_prompt",
            "squash_system_messages", "assistant_prefill",
        )
        const val DEFAULT_CONTEXT_TOKENS = 1_000_000
        const val DEFAULT_COMPLETION_TOKENS = 128 * 1_024
        const val LEGACY_LOW_CONTEXT_TOKENS = 4_096
        const val LEGACY_LOW_COMPLETION_TOKENS = 300
    }
}
