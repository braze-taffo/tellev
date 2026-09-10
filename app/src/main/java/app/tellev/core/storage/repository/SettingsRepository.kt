package app.tellev.core.storage.repository

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.PromptSettings
import app.tellev.core.model.WorldInfoSettings
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

internal class SettingsRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
) {
    suspend fun readWorldInfoSettings(): WorldInfoSettings = withContext(Dispatchers.IO) {
        val path = layout.root.resolve("world-info-settings.json")
        if (!path.exists()) return@withContext WorldInfoSettings()
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
            ?: return@withContext WorldInfoSettings()
        WorldInfoSettings(
            recursive = raw["recursive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            maxRecursionSteps = raw["maxRecursionSteps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            scanDepth = raw["scanDepth"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 12,
        )
    }

    suspend fun saveWorldInfoSettings(settings: WorldInfoSettings): Unit = withContext(Dispatchers.IO) {
        val output = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("recursive", JsonPrimitive(settings.recursive))
                put("maxRecursionSteps", JsonPrimitive(settings.maxRecursionSteps))
                put("scanDepth", JsonPrimitive(settings.scanDepth))
            },
        )
        StorageFileOps.durableWriteText(durableFiles, layout.root.resolve("world-info-settings.json"), output)
    }

    suspend fun readPromptSettings(): PromptSettings = withContext(Dispatchers.IO) {
        val path = layout.root.resolve("prompt-settings.json")
        if (!path.exists()) return@withContext PromptSettings()
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
            ?: return@withContext PromptSettings()
        PromptSettings(
            preferCharacterPrompt = raw["preferCharacterPrompt"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            preferCharacterJailbreak = raw["preferCharacterJailbreak"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            instructEnabled = raw["instructEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            instructPresetName = raw["instructPresetName"]?.jsonPrimitive?.content ?: "",
        )
    }

    suspend fun savePromptSettings(settings: PromptSettings): Unit = withContext(Dispatchers.IO) {
        val output = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("preferCharacterPrompt", JsonPrimitive(settings.preferCharacterPrompt))
                put("preferCharacterJailbreak", JsonPrimitive(settings.preferCharacterJailbreak))
                put("instructEnabled", JsonPrimitive(settings.instructEnabled))
                put("instructPresetName", JsonPrimitive(settings.instructPresetName))
            },
        )
        StorageFileOps.durableWriteText(durableFiles, layout.root.resolve("prompt-settings.json"), output)
    }

    suspend fun listInstructPresets(): List<String> = withContext(Dispatchers.IO) {
        layout.instruct.toFile().listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    suspend fun readInstructPreset(name: String): JsonObject? = withContext(Dispatchers.IO) {
        val path = layout.instruct.resolve("$name.json")
        if (!path.exists()) return@withContext null
        runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
    }

    suspend fun readDisabledRegexScriptIds(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val path = layout.regexActivation
        if (!path.exists()) return@withContext emptyMap()
        val raw = runCatching { json.parseToJsonElement(path.readText()) }.getOrNull() as? JsonObject
            ?: return@withContext emptyMap()
        val disabled = raw["disabled"] as? JsonObject ?: return@withContext emptyMap()
        disabled.entries.mapNotNull { (characterId, value) ->
            val ids = (value as? JsonArray)?.mapNotNull { it.stringContentOrNull() }?.toSet()
                ?: return@mapNotNull null
            if (ids.isEmpty()) return@mapNotNull null
            characterId to ids
        }.toMap()
    }

    suspend fun saveDisabledRegexScriptIds(map: Map<String, Set<String>>): Unit = withContext(Dispatchers.IO) {
        val output = buildJsonObject {
            putJsonObject("disabled") {
                map.forEach { (characterId, ids) ->
                    if (ids.isNotEmpty()) {
                        putJsonArray(characterId) {
                            ids.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
        }
        StorageFileOps.durableWriteText(durableFiles, layout.regexActivation, json.encodeToString(JsonObject.serializer(), output))
    }

    suspend fun migrateLegacyRegexActivation(
        readCharacter: suspend (String) -> CharacterCard,
        saveCharacter: suspend (CharacterCard) -> Unit,
    ) {
        if (!layout.regexActivation.exists()) return
        val legacy = readDisabledRegexScriptIds()
        var allWritesSucceeded = true
        for ((characterId, disabledIds) in legacy) {
            val card = runCatching { readCharacter(characterId) }.getOrNull() ?: continue
            var patched = card
            disabledIds.forEach { scriptId ->
                patched = CharacterRegexApplier.withScriptEnabled(patched, scriptId, enabled = false)
            }
            if (patched != card) {
                runCatching { saveCharacter(patched) }
                    .onFailure { allWritesSucceeded = false }
            }
        }
        if (allWritesSucceeded) layout.regexActivation.deleteIfExists()
    }

    private fun JsonElement.stringContentOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
