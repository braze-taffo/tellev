package app.tellev.feature.characters

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

/** 角色卡分组：id 稳定（自建后不改），成员是角色 id 集合。 */
@Immutable
@Serializable
data class CharacterGroup(
    val id: String,
    val label: String,
    val members: List<String> = emptyList(),
)

/**
 * character-groups.json 持久化（ChatModelGroups 同款 DurableFileOps 模式）：
 * 分组定义与成员关系存一份，删除分组只删定义不动角色卡本身。
 */
internal object CharacterGroups {
    private const val FILE_NAME = "character-groups.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: Path): List<CharacterGroup> {
        val file = root.resolve(FILE_NAME).toFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            json.parseToJsonElement(file.readText()).jsonObject["groups"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                CharacterGroup(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    members = obj["members"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }.orEmpty().filter { it.id.isNotBlank() && it.label.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun write(root: Path, groups: List<CharacterGroup>): Boolean = runCatching {
        val payload = buildJsonObject {
            put("groups", JsonArray(groups.map { group ->
                buildJsonObject {
                    put("id", JsonPrimitive(group.id))
                    put("label", JsonPrimitive(group.label))
                    put("members", JsonArray(group.members.map { JsonPrimitive(it) }))
                }
            }))
        }.toString()
        app.tellev.core.storage.DurableFileOps.write(
            root.resolve(FILE_NAME),
            payload.toByteArray(Charsets.UTF_8),
        )
    }.isSuccess

    fun newGroupId(): String = "cg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
}
