package app.tellev.feature.chat

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * One provider section of the chat model menu: the models its adapter actually
 * returned, or the failure when listing did not succeed. [id] is the selected
 * provider id (including `custom:<id>` forms); [label] is what the section
 * header shows.
 */
@Immutable
data class ModelCatalogGroup(
    val id: String,
    val label: String,
    val models: List<String>,
    val error: String? = null,
    /** 端点披露的能力信号：modelId → (reasoning tri-state, context length)。 */
    val signals: Map<String, Pair<Boolean?, Long?>> = emptyMap(),
) {
    val isFailed: Boolean get() = error != null
}

/** Load state of the whole catalog fetch behind the chat model menu. */
@Immutable
data class ModelCatalogState(
    val isLoading: Boolean = false,
    val groups: List<ModelCatalogGroup> = emptyList(),
    /** Set after a fetch attempt with zero usable groups and at least one failure. */
    val allFailed: Boolean = false,
)

/** Per-model remembered reasoning effort (dsh-better-reasoning-effort slider memory). */
@Immutable
@Serializable
data class ChatModelEffortMemoryEntry(
    val model: String,
    val effort: String,
)

/**
 * model-efforts.json persistence: modelId → effort name. The slider commit
 * writes here and selecting a model re-applies the remembered effort when the
 * session carries no explicit override — same precedence as upstream
 * (session record > model memory), one file, durable write.
 */
object ChatModelEffortMemory {
    private const val FILE_NAME = "model-efforts.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: Path): Map<String, ReasoningEffortByName> {
        val file = root.resolve(FILE_NAME).toFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()).jsonObject
            obj.mapNotNull { (model, element) ->
                val effort = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (model.isBlank() || effort.isBlank()) null else model to ReasoningEffortByName(effort)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun write(root: Path, efforts: Map<String, String>): Boolean = runCatching {
        val payload = buildJsonObject {
            efforts.keys.sorted().forEach { model -> put(model, JsonPrimitive(efforts.getValue(model))) }
        }.toString()
        app.tellev.core.storage.DurableFileOps.write(
            root.resolve(FILE_NAME),
            payload.toByteArray(Charsets.UTF_8),
        )
    }.isSuccess
}

/** Raw stored effort name; resolved lazily so unknown names never crash reads. */
@JvmInline
value class ReasoningEffortByName(val name: String)
