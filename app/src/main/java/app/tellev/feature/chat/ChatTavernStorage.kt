package app.tellev.feature.chat

import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.model.*
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Both background scripts and message frontends use the same persisted data. */
internal object ChatTavernStorage {
    fun activeWorldBooks(active: List<WorldBook>, all: List<WorldBook>, character: CharacterCard, session: ChatSession?): List<WorldBook> {
        val names = CharacterWorldBinding.linkedWorldBookNames(character) + listOfNotNull(
            session?.metadata?.get("world_info")?.jsonPrimitive?.contentOrNull)
        val bound = all.filter { book -> names.any { it == book.name || it == book.id } }
        return (active + bound).distinctBy { it.id }
    }

    private val writes = Mutex()

    suspend fun call(operation: String, payload: JsonObject, store: StDataStore,
                     state: MutableStateFlow<ChatUiState>, sessions: ChatSessionRuntime): JsonObject {
        val accepted = state.value
        val owner = sessions.runtimeToken
        return writes.withLock {
            val source = state.value
            check(sessions.runtimeToken == owner && source.currentSession?.id == accepted.currentSession?.id &&
                source.selectedCharacter?.id == accepted.selectedCharacter?.id) { "Compatibility request belongs to an expired context" }
            val character = source.selectedCharacter
            val category = source.selectedPreset?.category ?: PresetCategory.OpenAi
            fun ok() = buildJsonObject { put("ok", true) }
            fun variableRaw(raw: JsonObject, variables: JsonObject): JsonObject {
                val extensions = raw["extensions"] as? JsonObject ?: JsonObject(emptyMap())
                val helper = when (val existing = extensions["tavern_helper"]) {
                    is JsonObject -> existing
                    is JsonArray -> JsonObject(existing.mapNotNull { item ->
                        val pair = item as? JsonArray
                        if (pair?.size == 2 && pair[0] is JsonPrimitive) pair[0].jsonPrimitive.content to pair[1] else null
                    }.toMap())
                    else -> JsonObject(emptyMap())
                }
                return JsonObject(raw + ("extensions" to JsonObject(extensions +
                    ("tavern_helper" to JsonObject(helper + ("variables" to variables))))))
            }
            suspend fun saveCharacter(card: CharacterCard) {
                store.saveCharacter(card)
                state.update { if (it.selectedCharacter?.id == card.id) it.copy(selectedCharacter = card) else it }
            }
            suspend fun bindChat(name: String) {
                val session = requireNotNull(source.currentSession) { "No current chat" }
                val next = session.copy(metadata = JsonObject(session.metadata + ("world_info" to JsonPrimitive(name))))
                sessions.persistSessionMutation(session, next) { saved ->
                    state.update { if (it.currentSession?.id == saved.id) it.copy(currentSession = saved, messages = saved.messages) else it }
                }
            }
            fun checkBooks(names: List<String>, books: List<WorldBook>) {
                require(names.all { name -> books.any { it.name == name || it.id == name } }) { "Worldbook not found" }
            }
            when (operation) {
                "getVariables" -> {
                    val vars = when (payload["type"]?.jsonPrimitive?.content) {
                        "character" -> requireNotNull(character).let { store.readCharacter(it.id) }
                            .let(CharacterTavernHelperScripts::extractCharacterVariables)
                        "preset" -> store.readPreset(category, "in_use")?.let {
                            CharacterTavernHelperScripts.extractCharacterVariables(CharacterCard(it.id, it.name, raw = it.raw))
                        }
                        else -> error("Unsupported canonical variable scope")
                    } ?: JsonObject(emptyMap())
                    buildJsonObject { put("variables", vars) }
                }
                "replaceVariables" -> {
                    val vars = payload["variables"] as? JsonObject ?: error("Variables must be a JSON object")
                    when (payload["type"]?.jsonPrimitive?.content) {
                        "character" -> {
                            val card = store.readCharacter(requireNotNull(character).id)
                            val wrapped = card.raw["data"] as? JsonObject
                            val data = wrapped ?: card.raw
                            val raw = variableRaw(data, vars)
                            saveCharacter(card.copy(raw = if (wrapped != null) JsonObject(card.raw + ("data" to raw)) else raw))
                        }
                        "preset" -> {
                            val working = requireNotNull(store.readPreset(category, "in_use")) { "No working preset" }
                            val next = working.copy(raw = variableRaw(working.raw, vars),
                                extensions = variableRaw(working.raw, vars)["extensions"]!!.jsonObject)
                            // Keep variables with the selected preset when in_use is replaced on selection.
                            val selected = store.readSelectedPresetName(category)
                            if (selected != null && selected != "in_use") {
                                store.readPreset(category, selected)?.let { named ->
                                    val raw = variableRaw(named.raw, vars)
                                    store.savePreset(named.copy(raw = raw, extensions = raw["extensions"]!!.jsonObject))
                                }
                            }
                            store.saveWorkingPreset(category, next)
                            state.update { if (it.selectedPreset?.category == category) it.copy(selectedPreset = next.copy(id = it.selectedPreset.id, name = it.selectedPreset.name)) else it }
                        }
                        else -> error("Unsupported canonical variable scope")
                    }
                    ok()
                }
                "getCharWorldbookNames" -> {
                    val name = payload["name"]?.jsonPrimitive?.content ?: "current"
                    val card = if (name == "current") requireNotNull(character) else {
                        val id = store.listCharacters().firstOrNull { it.name == name || it.id == name }?.id
                            ?: error("Character not found: $name")
                        store.readCharacter(id)
                    }
                    val names = CharacterWorldBinding.linkedWorldBookNames(card)
                    val primary = CharacterWorldBinding.linkedWorldBookName(card)
                    buildJsonObject {
                        put("primary", primary?.let(::JsonPrimitive) ?: JsonNull)
                        put("additional", JsonArray(names.filter { it != primary }.map(::JsonPrimitive)))
                    }
                }
                "rebindCharWorldbooks" -> {
                    require(payload["name"]?.jsonPrimitive?.content == "current") { "Only current character rebinding is supported upstream" }
                    val binding = payload["binding"] as? JsonObject ?: error("Missing binding")
                    val primary = (binding["primary"] as? JsonPrimitive)?.contentOrNull
                    val additional = (binding["additional"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                    checkBooks(listOfNotNull(primary) + additional, store.listWorldBooks())
                    val card = store.readCharacter(requireNotNull(character).id)
                    saveCharacter(CharacterWorldBinding.withWorldBookNames(card, primary, additional))
                    ok()
                }
                "rebindChatWorldbook", "getOrCreateChatWorldbook" -> {
                    require(payload["chat"]?.jsonPrimitive?.content == "current") { "Only current chat is supported upstream" }
                    val current = source.currentSession?.metadata?.get("world_info")?.jsonPrimitive?.contentOrNull
                    if (operation == "getOrCreateChatWorldbook" && !current.isNullOrBlank())
                        return@withLock buildJsonObject { put("name", current) }
                    val name = payload["name"]?.jsonPrimitive?.contentOrNull
                        ?: "chat_${requireNotNull(source.currentSession).id}"
                    val books = store.listWorldBooks()
                    if (operation == "getOrCreateChatWorldbook" && books.none { it.name == name || it.id == name }) {
                        store.saveWorldBook(WorldBook(name, name, emptyList()))
                        state.update { it.copy(worldBooks = it.worldBooks + WorldBook(name, name, emptyList())) }
                    } else checkBooks(listOf(name), books)
                    bindChat(name)
                    buildJsonObject { put("name", name); put("ok", true) }
                }
                else -> error("Unsupported compatibility storage operation: $operation")
            }
        }
    }
}
