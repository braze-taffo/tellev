package app.tellev.feature.creation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal const val TOOL_CALL_OPEN = "<tool_call>"
internal const val TOOL_CALL_CLOSE = "</tool_call>"

internal data class ToolCallRequest(val name: String, val arguments: JsonObject)

internal sealed interface ToolCallBlock {
    data class Valid(val call: ToolCallRequest) : ToolCallBlock
    data class Invalid(val reason: String, val raw: String) : ToolCallBlock
}

internal data class ToolBlocksParseResult(
    val blocks: List<ToolCallBlock>,
    /** Text outside complete blocks; an unclosed trailing block is cut away too. */
    val prose: String,
    val hasUnclosedBlock: Boolean,
)

private val completeBlockRegex = Regex("""$TOOL_CALL_OPEN([\s\S]*?)$TOOL_CALL_CLOSE""")

/** Text the user-facing stream should show: everything except tool-call blocks. */
internal fun proseWithoutToolBlocks(raw: String): String {
    val remainder = StringBuilder()
    var cursor = 0
    for (match in completeBlockRegex.findAll(raw)) {
        remainder.append(raw, cursor, match.range.first)
        cursor = match.range.last + 1
    }
    remainder.append(raw, cursor, raw.length)
    return remainder.toString().substringBefore(TOOL_CALL_OPEN).trim()
}

internal fun parseToolCallBlocks(body: String): ToolBlocksParseResult {
    val blocks = mutableListOf<ToolCallBlock>()
    val json = Json { ignoreUnknownKeys = true }
    for (match in completeBlockRegex.findAll(body)) {
        val inner = match.groupValues[1].trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (inner.isEmpty()) {
            blocks += ToolCallBlock.Invalid("工具块内容为空", match.value)
            continue
        }
        val parsed = runCatching { json.parseToJsonElement(inner).jsonObject }
        val root = parsed.getOrNull()
        if (root == null) {
            blocks += ToolCallBlock.Invalid("工具块内不是合法 JSON 对象", match.value)
            continue
        }
        // jsonPrimitive throws on non-primitive values; a bad block must stay a
        // recoverable Invalid instead of killing the round.
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull
        if (name.isNullOrBlank()) {
            blocks += ToolCallBlock.Invalid("缺少 name 字段", match.value)
            continue
        }
        val arguments = root["arguments"] as? JsonObject
        if (root.containsKey("arguments") && arguments == null) {
            blocks += ToolCallBlock.Invalid("arguments 必须是 JSON 对象", match.value)
            continue
        }
        blocks += ToolCallBlock.Valid(ToolCallRequest(name, arguments ?: JsonObject(emptyMap())))
    }
    val opens = Regex.fromLiteral(TOOL_CALL_OPEN).findAll(body).count()
    return ToolBlocksParseResult(
        blocks = blocks,
        prose = proseWithoutToolBlocks(body),
        hasUnclosedBlock = opens > blocks.size,
    )
}

internal data class ToolResult(val ok: Boolean, val name: String, val payload: JsonObject) {
    fun render(): String = "<tool_result name=\"$name\" ok=\"${if (ok) "true" else "false"}\">" +
        payload.toString() + "</tool_result>"
}

/** Provenance and merge-base fields are system-managed; writes to them are ignored. */
private val SYSTEM_MANAGED_LORE_FIELDS = setOf(
    "sourceQuote", "sourceOffset", "sourceName", "sourceSha256", "originalEntry",
)

/** Lore entry fields a tool call may set; anything else is echoed back as a warning. */
private val LORE_WRITABLE_FIELDS = setOf(
    "id", "title", "keys", "content", "secondaryKeys", "selective", "constant",
    "insertionOrder", "depth", "position", "probability", "matchWholeWords", "note",
)

/**
 * ST-native and snake_case aliases accepted on write. Without this, a model
 * echoing the field names it saw in an imported card would be silently ignored
 * by the LoreDraft decoder (ignoreUnknownKeys) while being told "updated".
 */
private val LORE_KEY_ALIASES = mapOf(
    "key" to "keys",
    "keysecondary" to "secondaryKeys",
    "secondary_keys" to "secondaryKeys",
    "order" to "insertionOrder",
    "insertion_order" to "insertionOrder",
    "match_whole_words" to "matchWholeWords",
    "comment" to "title",
)

private val CARD_FIELD_WHITELIST = setOf(
    "name", "description", "personality", "scenario", "firstMessage", "alternateGreetings",
    "exampleMessages", "systemPrompt", "postHistoryInstructions", "creatorNotes", "tags", "frontendHtml",
)

/**
 * Executes creation tools against a working copy of the session. Writes apply
 * immediately inside the turn; the caller decides whether to keep them.
 */
internal class CreationToolBox(initial: CreationSession) {
    var session: CreationSession = initial
        private set

    private val decodeJson = Json { ignoreUnknownKeys = true }
    private val encodeJson = Json { encodeDefaults = true }

    fun execute(call: ToolCallRequest): ToolResult = try {
        when (call.name) {
            "read_card" -> readCard()
            "list_lore" -> listLore(call.arguments)
            "read_lore" -> readLore(call.arguments)
            "set_card_fields" -> setCardFields(call.arguments)
            "upsert_lore" -> upsertLore(call.arguments)
            "remove_lore" -> removeLore(call.arguments)
            else -> ToolResult(
                ok = false, name = call.name,
                payload = errorPayload("未知工具。可用：read_card, list_lore, read_lore, set_card_fields, upsert_lore, remove_lore"),
            )
        }
    } catch (e: IllegalArgumentException) {
        ToolResult(ok = false, name = call.name, payload = errorPayload(e.message ?: "参数无效"))
    } catch (e: Exception) {
        ToolResult(ok = false, name = call.name, payload = errorPayload("执行失败：${e.message ?: e.javaClass.simpleName}"))
    }

    private fun errorPayload(message: String): JsonObject = buildJsonObject { put("error", message) }

    private fun readCard(): ToolResult = ToolResult(
        ok = true, name = "read_card",
        payload = buildJsonObject {
            put("kind", if (session.kind == CreationKind.Character) "角色卡" else "世界书")
            put("card", encodeJson.encodeToJsonElement(session.card))
            put("world_name", session.worldName)
            put("lore_count", session.lore.size)
        },
    )

    private fun JsonObject.argInt(name: String, default: Int): Int {
        val value = this[name] ?: return default
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("$name 必须是整数")
        return primitive.intOrNull ?: throw IllegalArgumentException("$name 必须是整数，收到：${primitive.content}")
    }

    private fun JsonObject.argString(name: String, default: String): String {
        val value = this[name] ?: return default
        return (value as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("$name 必须是字符串")
    }

    private fun JsonObject.argStringArray(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("$name 必须是字符串数组")
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun listLore(arguments: JsonObject): ToolResult {
        val offset = arguments.argInt("offset", 0).coerceAtLeast(0)
        val limit = arguments.argInt("limit", 20).coerceIn(1, 50)
        val keyword = arguments.argString("keyword", "").trim()
        val filtered = if (keyword.isEmpty()) session.lore else session.lore.filter { entry ->
            entry.title.contains(keyword, ignoreCase = true) ||
                entry.keys.any { it.contains(keyword, ignoreCase = true) }
        }
        return ToolResult(
            ok = true, name = "list_lore",
            payload = buildJsonObject {
                put("total", filtered.size)
                put("offset", offset)
                put("limit", limit)
                put(
                    "entries",
                    JsonArray(filtered.drop(offset).take(limit).map { entry ->
                        buildJsonObject {
                            put("id", entry.id)
                            put("title", entry.title)
                            put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
                            put("constant", entry.constant)
                            put("insertionOrder", entry.insertionOrder)
                        }
                    }),
                )
            },
        )
    }

    /** Field names mirror LoreDraft exactly so the model can echo what it read. */
    private fun loreModelView(entry: LoreDraft): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("title", entry.title)
        put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
        put("content", entry.content)
        put("secondaryKeys", JsonArray(entry.secondaryKeys.map(::JsonPrimitive)))
        put("selective", entry.selective)
        put("constant", entry.constant)
        put("insertionOrder", entry.insertionOrder)
        put("depth", entry.depth)
        put("position", entry.position)
        put("probability", entry.probability)
        put("matchWholeWords", entry.matchWholeWords)
        put("note", entry.note)
    }

    private fun readLore(arguments: JsonObject): ToolResult {
        val ids = arguments.argStringArray("ids")
        require(ids.isNotEmpty()) { "ids 不能为空" }
        require(ids.size <= 20) { "单次最多读取 20 条（收到 ${ids.size} 条）" }
        val found = ids.mapNotNull { id -> session.lore.firstOrNull { it.id == id } }
        val notFound = ids.filter { id -> session.lore.none { it.id == id } }
        return ToolResult(
            ok = true, name = "read_lore",
            payload = buildJsonObject {
                put("found", JsonArray(found.map(::loreModelView)))
                put("not_found", JsonArray(notFound.map(::JsonPrimitive)))
            },
        )
    }

    private fun setCardFields(patch: JsonObject): ToolResult {
        val unknown = patch.keys - CARD_FIELD_WHITELIST
        require(unknown.isEmpty()) {
            "未知 card 字段：${unknown.sorted().joinToString(", ")}。合法字段：${CARD_FIELD_WHITELIST.sorted().joinToString(", ")}"
        }
        if (session.kind == CreationKind.WorldBook) {
            // 世界书会话没有角色卡：name 重定向到世界书名称，其余 card 字段不可用。
            val unsupported = patch.keys - setOf("name")
            require(unsupported.isEmpty()) {
                "世界书会话只能用 name 修改世界书名称，不支持：${unsupported.sorted().joinToString(", ")}"
            }
            val name = (patch["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            require(name.isNotEmpty()) { "name 不能为空" }
            session = session.copy(
                worldName = name,
                nextLoreNumber = loreIdCursor,
                updatedAt = System.currentTimeMillis(),
            )
            return ToolResult(
                ok = true, name = "set_card_fields",
                payload = buildJsonObject {
                    put("applied", JsonArray(listOf(JsonPrimitive("name"))))
                    put("world_name", name)
                },
            )
        }
        val current = encodeJson.encodeToJsonElement(session.card).jsonObject
        val merged = decodeJson.decodeFromJsonElement<CharacterDraft>(JsonObject(current + patch))
        session = session.copy(card = merged, nextLoreNumber = loreIdCursor, updatedAt = System.currentTimeMillis())
        return ToolResult(
            ok = true, name = "set_card_fields",
            payload = buildJsonObject {
                put("applied", JsonArray(patch.keys.sorted().map(::JsonPrimitive)))
                put("card", encodeJson.encodeToJsonElement(session.card))
            },
        )
    }

    /**
     * Monotonic session-level counter: ids are never reused, even after
     * remove_lore, so an id mentioned in earlier tool results always refers to
     * the same entry within this session.
     */
    private var loreIdCursor: Int = session.nextLoreNumber.takeIf { it > 0 }
        ?: session.lore.mapNotNull { it.id.removePrefix("L").toIntOrNull() }.maxOrNull() ?: 0

    private fun nextLoreId(): String {
        require(loreIdCursor < Int.MAX_VALUE - 1)
        loreIdCursor += 1
        return "L$loreIdCursor"
    }

    private fun upsertLore(arguments: JsonObject): ToolResult {
        val entries = arguments["entries"] as? JsonArray
            ?: throw IllegalArgumentException("缺少 entries 数组")
        require(entries.isNotEmpty()) { "entries 不能为空" }
        require(entries.size <= 20) { "单次最多处理 20 条（收到 ${entries.size} 条），请分多轮打包" }
        val working = session.lore.toMutableList()
        val outcomes = mutableListOf<JsonObject>()
        val notFound = mutableListOf<JsonPrimitive>()
        for (element in entries) {
            val incoming = element as? JsonObject
                ?: throw IllegalArgumentException("entries 中的每一项必须是 JSON 对象")
            val normalized = normalizeLoreKeys(incoming)
            val unknownKeys = normalized.keys - LORE_WRITABLE_FIELDS
            val unknownWarnings = unknownKeys.sorted().map { "未识别字段被忽略：$it（合法字段见工具说明）" }
            val id = normalized["id"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (id.isNullOrBlank()) {
                val created = decodeJson.decodeFromJsonElement<LoreDraft>(normalized).copy(id = nextLoreId())
                working += created
                outcomes += buildJsonObject {
                    put("id", created.id)
                    put("status", "created")
                    put("fields", JsonArray((normalized.keys - "id").sorted().map(::JsonPrimitive)))
                    put("warnings", JsonArray((unknownWarnings + creationWarnings(created)).map(::JsonPrimitive)))
                }
            } else {
                val index = working.indexOfFirst { it.id == id }
                if (index < 0) {
                    notFound += JsonPrimitive(id)
                    continue
                }
                val existing = encodeJson.encodeToJsonElement(working[index]).jsonObject
                val updated = decodeJson.decodeFromJsonElement<LoreDraft>(JsonObject(existing + normalized))
                working[index] = updated
                outcomes += buildJsonObject {
                    put("id", updated.id)
                    put("status", "updated")
                    put("fields", JsonArray((normalized.keys - "id").sorted().map(::JsonPrimitive)))
                    put("warnings", JsonArray((unknownWarnings + titleConflictWarnings(updated, id, working)).map(::JsonPrimitive)))
                }
            }
        }
        session = session.copy(lore = working, nextLoreNumber = loreIdCursor, updatedAt = System.currentTimeMillis())
        return ToolResult(
            ok = true, name = "upsert_lore",
            payload = buildJsonObject {
                put("results", JsonArray(outcomes))
                put("not_found", JsonArray(notFound))
            },
        )
    }

    /** Strip system-managed keys, then map ST-native aliases onto draft field names. */
    private fun normalizeLoreKeys(incoming: JsonObject): JsonObject = JsonObject(buildMap {
        incoming.forEach { (key, value) ->
            if (key !in SYSTEM_MANAGED_LORE_FIELDS) put(LORE_KEY_ALIASES[key] ?: key, value)
        }
    })

    private fun creationWarnings(entry: LoreDraft): List<String> = buildList {
        if (entry.title.isBlank()) add("缺少标题")
        if (entry.content.isBlank()) add("缺少内容")
        if (!entry.constant && entry.keys.none(String::isNotBlank)) {
            add("非常驻条目缺少触发词 keys，保存前需补齐")
        }
    }

    private fun titleConflictWarnings(entry: LoreDraft, id: String, list: List<LoreDraft>): List<String> =
        if (entry.title.isNotBlank() && list.any { it.id != id && it.title.trim().equals(entry.title.trim(), ignoreCase = true) }) {
            listOf("与其他条目同名：${entry.title.trim()}（同名不冲突，但请确认不是笔误）")
        } else emptyList()

    private fun removeLore(arguments: JsonObject): ToolResult {
        val ids = arguments.argStringArray("ids")
        require(ids.isNotEmpty()) { "ids 不能为空" }
        val removed = session.lore.filter { it.id in ids }
        val notFound = ids.filter { id -> session.lore.none { it.id == id } }
        session = session.copy(
            lore = session.lore.filterNot { it.id in ids },
            updatedAt = System.currentTimeMillis(),
        )
        return ToolResult(
            ok = true, name = "remove_lore",
            payload = buildJsonObject {
                put("removed", JsonArray(removed.map { entry ->
                    buildJsonObject {
                        put("id", entry.id)
                        put("title", entry.title)
                    }
                }))
                put("not_found", JsonArray(notFound.map(::JsonPrimitive)))
            },
        )
    }
}
