package app.tellev.core.storage.repository

import app.tellev.core.model.Persona
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

internal class PersonaRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val personaChanges: MutableSharedFlow<String>,
) {
    suspend fun listPersonas(): List<Persona> = withContext(Dispatchers.IO) {
        StorageFileOps.readJsonFiles(layout.user, json).map { (path, raw) ->
            Persona(
                id = path.nameWithoutExtension,
                name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                description = raw["description"]?.jsonPrimitive?.content ?: "",
                avatarRelativePath = raw["avatar"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.content,
                metadata = raw,
            )
        }
    }

    suspend fun savePersona(persona: Persona): Unit = withContext(Dispatchers.IO) {
        layout.user.createDirectories()
        val path = layout.user.resolve("${persona.id}.json")
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(persona))
        personaChanges.tryEmit(persona.id)
    }

    suspend fun deletePersona(id: String): Unit = withContext(Dispatchers.IO) {
        layout.user.resolve("$id.json").deleteIfExists()
        personaChanges.tryEmit(id)
    }

    fun ensureDefaultPersona() {
        if (layout.user.listDirectoryEntries("*.json").isNotEmpty()) return
        layout.user.createDirectories()
        val default = Persona(
            id = "default-user",
            name = "用户",
            description = "默认用户人设。",
        )
        val path = layout.user.resolve("${default.id}.json")
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(default))
    }
}
