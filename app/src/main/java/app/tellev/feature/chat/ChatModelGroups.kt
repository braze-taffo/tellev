package app.tellev.feature.chat

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

/** 模型菜单的供应商分组：id 可自定义（用户命名），成员是模型 id 集合。 */
@Immutable
@Serializable
data class DshModelGroup(
    val id: String,
    val label: String,
    val models: List<String> = emptyList(),
)

/** model-groups.json 持久化（ChatPinnedSessions 同款 DurableFileOps 模式）。 */
internal object ChatModelGroups {
    private const val FILE_NAME = "model-groups.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: Path): List<DshModelGroup> {
        val file = root.resolve(FILE_NAME).toFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject["groups"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                DshModelGroup(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    models = obj["models"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }.orEmpty().filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun write(root: Path, groups: List<DshModelGroup>): Boolean = runCatching {
        val payload = buildJsonObject {
            put("groups", JsonArray(groups.map { group ->
                buildJsonObject {
                    put("id", JsonPrimitive(group.id))
                    put("label", JsonPrimitive(group.label))
                    put("models", JsonArray(group.models.map { JsonPrimitive(it) }))
                }
            }))
        }.toString()
        app.tellev.core.storage.DurableFileOps.write(
            root.resolve(FILE_NAME),
            payload.toByteArray(Charsets.UTF_8),
        )
    }.isSuccess
}
