package app.tellev.core.memory

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.ChatSession
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.security.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A chat's choice is immutable. The global switch only pauses work. */
enum class MemoryMode(val label: String) {
    NONE("无记忆"), ARCHIVE("柏宝书式"), EPISODIC("STARmem 式");

    companion object {
        const val METADATA_KEY = "tellev_memory_mode"
        fun of(session: ChatSession?): MemoryMode? =
            session?.metadata?.get(METADATA_KEY)?.jsonPrimitive?.contentOrNull
                ?.let { value -> entries.firstOrNull { it.name == value } }
    }
}

fun ChatSession.withMemoryMode(mode: MemoryMode): ChatSession {
    require(MemoryMode.of(this) == null) { UiStrings.get(S.memmod_error_mode_locked) }
    return copy(metadata = JsonObject(metadata + (MemoryMode.METADATA_KEY to JsonPrimitive(mode.name))))
}

@Serializable
data class MemorySettings(
    val enabled: Boolean = false,
    val providerId: String = "",
    val providerModel: String = "",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",
    val vectorEnabled: Boolean = false,
    val vectorProviderId: String = "",
    val vectorBaseUrl: String = "",
    val vectorApiKey: String = "",
    val vectorModel: String = "",
    val vectorPath: String = "/v1/embeddings",
) {
    suspend fun textConfig(secrets: SecretStore): ProviderConfig? =
        if (providerId.isNotBlank()) {
            ProviderConfigPersistence.loadProviderConfig(secrets, providerId)
                .let { config -> config.copy(model = providerModel.ifBlank { config.model.orEmpty() }) }
                .takeIf { !it.model.isNullOrBlank() }
        } else if (customBaseUrl.isNotBlank() && customModel.isNotBlank()) {
            ProviderConfig(ProviderCatalog.OPENAI_COMPATIBLE, customBaseUrl.trim(), customApiKey.ifBlank { null }, customModel.trim())
        } else null

    suspend fun vectorConfig(secrets: SecretStore): ProviderConfig? =
        if (!vectorEnabled) null
        else if (vectorProviderId.isNotBlank()) {
            ProviderConfigPersistence.loadProviderConfig(secrets, vectorProviderId)
                .let { config -> config.copy(model = vectorModel.ifBlank { config.model.orEmpty() }) }
                .takeIf { !it.model.isNullOrBlank() }
        } else if (vectorBaseUrl.isNotBlank() && vectorModel.isNotBlank()) {
            ProviderConfig(ProviderCatalog.OPENAI_COMPATIBLE, vectorBaseUrl.trim(), vectorApiKey.ifBlank { null }, vectorModel.trim())
        } else null
}

class MemorySettingsStore(private val secrets: SecretStore) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): MemorySettings = secrets.readSecret(SECRET_ID)
        ?.let { runCatching { json.decodeFromString<MemorySettings>(it) }.getOrNull() }
        ?: MemorySettings()

    suspend fun write(settings: MemorySettings) {
        secrets.putSecret(SECRET_ID, json.encodeToString(MemorySettings.serializer(), settings))
    }

    companion object { private const val SECRET_ID = "tellev-memory-settings-v1" }
}

@Serializable
data class MemoryLink(val targetId: String, val type: String)

@Serializable
data class MemoryRecord(
    val id: String,
    val kind: String,
    val text: String,
    val subject: String = "",
    val tags: List<String> = emptyList(),
    val links: List<MemoryLink> = emptyList(),
    val supersedes: List<String> = emptyList(),
    val sourceIds: List<String> = emptyList(),
    val importance: Int = 3,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val active: Boolean = true,
    val manual: Boolean = false,
    val vector: List<Float> = emptyList(),
)

@Serializable
data class MemoryDocument(
    val version: Int = 1,
    val mode: String,
    val records: List<MemoryRecord> = emptyList(),
    /** Hash of each processed message's currently selected text. */
    val processed: Map<String, String> = emptyMap(),
    val baselineIds: Set<String> = emptySet(),
    val needsRebuild: Boolean = false,
    val error: String? = null,
    val stage: String = "就绪",
) {
    companion object { fun empty(mode: MemoryMode) = MemoryDocument(mode = mode.name) }
}
