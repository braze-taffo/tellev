package app.tellev.feature.creation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
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
    /** The trailing block lost its closing tag but its JSON was complete, so it still ran. */
    val recoveredUnclosedBlock: Boolean = false,
)

private val completeBlockRegex = Regex("""$TOOL_CALL_OPEN([\s\S]*?)$TOOL_CALL_CLOSE""")
// Some compatible providers return DeepSeek-style DSML in message content even
// though this agent asks for <tool_call>. Accept both the documented form and
// the doubled-pipe, spaced form observed on device.
private val dsmlTagRegex = Regex("""<(/?)[｜|]{1,2}DSML[｜|]{1,2}\s*(tool_calls|calls|invoke|parameter)([^>]*)>""")
private val dsmlWrapperRegex = Regex("""<DSML:(?:tool_calls|calls)>([\s\S]*?)</DSML:(?:tool_calls|calls)>""")
private val dsmlInvokeRegex = Regex("""<DSML:invoke([^>]*)>([\s\S]*?)</DSML:invoke>""")
private val dsmlParameterRegex = Regex("""<DSML:parameter([^>]*)>([\s\S]*?)</DSML:parameter>""")
private val dsmlAttributeRegex = Regex("""([A-Za-z_]+)\s*=\s*"([^"]*)"""")

private fun normalizeDsmlTags(raw: String): String = dsmlTagRegex.replace(raw) { match ->
    "<${match.groupValues[1]}DSML:${match.groupValues[2]}${match.groupValues[3]}>"
}

private fun completeToolRanges(body: String): List<IntRange> =
    (completeBlockRegex.findAll(body).map { it.range } + dsmlWrapperRegex.findAll(body).map { it.range })
        .sortedBy { it.first }.toList()

private fun removeCompleteToolBlocks(body: String): String {
    val remainder = StringBuilder()
    var cursor = 0
    for (range in completeToolRanges(body)) {
        if (range.first < cursor) continue
        remainder.append(body, cursor, range.first)
        cursor = range.last + 1
    }
    remainder.append(body, cursor, body.length)
    return remainder.toString()
}

private fun firstToolStart(body: String): Int = listOf(
    body.indexOf(TOOL_CALL_OPEN), body.indexOf("<DSML:"), body.indexOf("<｜"), body.indexOf("<|DSML"),
).filter { it >= 0 }.minOrNull() ?: body.length

/** Text the user-facing stream should show: everything except tool-call blocks. */
internal fun proseWithoutToolBlocks(raw: String): String {
    val remainder = removeCompleteToolBlocks(normalizeDsmlTags(raw))
    return remainder.substring(0, firstToolStart(remainder)).trim()
}

private fun dsmlAttributes(raw: String): Map<String, String>? {
    val found = dsmlAttributeRegex.findAll(raw).toList()
    if (dsmlAttributeRegex.replace(raw, "").isNotBlank()) return null
    if (found.map { it.groupValues[1] }.distinct().size != found.size) return null
    return found.associate { it.groupValues[1] to it.groupValues[2] }
}

private fun parseDsmlInvoke(match: MatchResult, json: Json): ToolCallBlock {
    val attrs = dsmlAttributes(match.groupValues[1])
    val name = attrs?.get("name")?.takeIf(String::isNotBlank)
    if (name == null || attrs?.keys != setOf("name")) {
        return ToolCallBlock.Invalid("DSML invoke 缺少合法 name", match.value)
    }
    val inner = match.groupValues[2]
    val arguments = mutableMapOf<String, JsonElement>()
    for (parameter in dsmlParameterRegex.findAll(inner)) {
        val parameterAttrs = dsmlAttributes(parameter.groupValues[1])
        val key = parameterAttrs?.get("name")?.takeIf(String::isNotBlank)
        val stringFlag = parameterAttrs?.get("string")
        if (key == null || (parameterAttrs?.keys.orEmpty() - setOf("name", "string")).isNotEmpty() ||
            stringFlag !in setOf(null, "true", "false") || key in arguments
        ) {
            return ToolCallBlock.Invalid("DSML parameter 属性无效或重复", match.value)
        }
        val value = if (stringFlag == "false") {
            runCatching { json.parseToJsonElement(parameter.groupValues[2].trim()) }.getOrNull()
                ?: return ToolCallBlock.Invalid("DSML parameter 不是合法 JSON", match.value)
        } else JsonPrimitive(parameter.groupValues[2])
        arguments[key] = value
    }
    if (dsmlParameterRegex.replace(inner, "").isNotBlank()) {
        return ToolCallBlock.Invalid("DSML invoke 含无法解析的内容", match.value)
    }
    return ToolCallBlock.Valid(ToolCallRequest(name, JsonObject(arguments)))
}

private fun parseDsmlWrapper(match: MatchResult, json: Json): List<ToolCallBlock> {
    val inner = match.groupValues[1]
    val invokes = dsmlInvokeRegex.findAll(inner).toList()
    if (invokes.isEmpty()) return listOf(ToolCallBlock.Invalid("DSML 工具块没有完整 invoke", match.value))
    val blocks = invokes.map { parseDsmlInvoke(it, json) }.toMutableList()
    if (dsmlInvokeRegex.replace(inner, "").isNotBlank()) {
        blocks += ToolCallBlock.Invalid("DSML 工具块含无法解析的内容", match.value)
    }
    return blocks
}

/** Shared by closed blocks and by a trailing block whose closing tag never arrived. */
private fun parseBlockBody(raw: String, json: Json, reported: String = raw): ToolCallBlock {
    val inner = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    if (inner.isEmpty()) return ToolCallBlock.Invalid("工具块内容为空", reported)
    val root = runCatching { json.parseToJsonElement(inner).jsonObject }.getOrNull()
        ?: return ToolCallBlock.Invalid("工具块内不是合法 JSON 对象", reported)
    // jsonPrimitive throws on non-primitive values; a bad block must stay a
    // recoverable Invalid instead of killing the round.
    val name = (root["name"] as? JsonPrimitive)?.contentOrNull
    if (name.isNullOrBlank()) return ToolCallBlock.Invalid("缺少 name 字段", reported)
    val arguments = root["arguments"] as? JsonObject
    if (root.containsKey("arguments") && arguments == null) {
        return ToolCallBlock.Invalid("arguments 必须是 JSON 对象", reported)
    }
    return ToolCallBlock.Valid(ToolCallRequest(name, arguments ?: JsonObject(emptyMap())))
}

/**
 * Some relays end generation at the closing tag and drop it from the content
 * (the tag doubles as their stop token), so a perfectly usable call can arrive
 * as `<tool_call>{...}` with no close. Recovering it beats burning a bad round.
 */
private fun recoverUnclosedTrailingBlock(tail: String, json: Json): ToolCallBlock.Valid? {
    if (!tail.startsWith(TOOL_CALL_OPEN)) return null
    val body = tail.removePrefix(TOOL_CALL_OPEN).substringBefore("</tool_call")
    return parseBlockBody(body, json, tail) as? ToolCallBlock.Valid
}

internal fun parseToolCallBlocks(body: String): ToolBlocksParseResult {
    val normalized = normalizeDsmlTags(body)
    val indexedBlocks = mutableListOf<Pair<Int, ToolCallBlock>>()
    val json = Json { ignoreUnknownKeys = true }
    for (match in completeBlockRegex.findAll(normalized)) {
        indexedBlocks += match.range.first to parseBlockBody(match.groupValues[1], json, match.value)
    }
    for (match in dsmlWrapperRegex.findAll(normalized)) {
        indexedBlocks += parseDsmlWrapper(match, json).map { match.range.first to it }
    }
    val remainder = removeCompleteToolBlocks(normalized)
    val tailStart = firstToolStart(remainder)
    val unclosedTail = if (tailStart < remainder.length) remainder.substring(tailStart) else null
    val recovered = unclosedTail?.let { recoverUnclosedTrailingBlock(it, json) }
    if (recovered != null) indexedBlocks += remainder.length to recovered
    return ToolBlocksParseResult(
        blocks = indexedBlocks.sortedBy { it.first }.map { it.second },
        prose = proseWithoutToolBlocks(body),
        hasUnclosedBlock = unclosedTail != null && recovered == null,
        recoveredUnclosedBlock = recovered != null,
    )
}

/** OpenAI-compatible providers can return function calls outside message.content. */
internal fun parseNativeCreationCalls(calls: JsonArray?): List<ToolCallBlock> = calls?.map { element ->
    val function = (element as? JsonObject)?.get("function") as? JsonObject
    val nativeName = (function?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
    val rawValue = function?.get("arguments")
    val rawArguments = when (rawValue) {
        is JsonPrimitive -> rawValue.contentOrNull.orEmpty()
        is JsonObject -> rawValue.toString()
        else -> ""
    }
    val arguments = if (rawValue is JsonObject) rawValue
        else runCatching { Json.parseToJsonElement(rawArguments) as? JsonObject }.getOrNull()
    if (arguments == null) {
        ToolCallBlock.Invalid("原生工具 $nativeName 的 arguments 不是完整 JSON 对象", rawArguments.take(200))
    } else if (nativeName == "creation_tool") {
        val name = (arguments["name"] as? JsonPrimitive)?.contentOrNull
        val nested = arguments["arguments"] as? JsonObject
        if (name.isNullOrBlank() || nested == null) {
            ToolCallBlock.Invalid("creation_tool 缺少 name 或 arguments 对象", rawArguments.take(200))
        } else ToolCallBlock.Valid(ToolCallRequest(name, nested))
    } else if (nativeName.isBlank()) {
        ToolCallBlock.Invalid("原生工具缺少名称", rawArguments.take(200))
    } else ToolCallBlock.Valid(ToolCallRequest(nativeName, arguments))
} ?: emptyList()

internal data class ToolResult(val ok: Boolean, val name: String, val payload: JsonObject) {
    fun render(): String {
        val safeName = name.takeIf { Regex("[A-Za-z_][A-Za-z_0-9]{0,63}").matches(it) } ?: "unknown"
        return "<tool_result name=\"$safeName\" ok=\"${if (ok) "true" else "false"}\">" +
            payload.toString() + "</tool_result>"
    }
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
            "list_assets", "read_script", "upsert_script", "upsert_regex", "read_regex",
            "set_variables", "read_variables", "remove_asset" -> {
                val (updated, result) = CreationAdvancedAssets.execute(session, call)
                session = updated
                result
            }
            else -> ToolResult(
                ok = false, name = call.name,
                payload = errorPayload("未知工具。可用：read_card, list_lore, read_lore, set_card_fields, upsert_lore, remove_lore, list_assets, read_script, upsert_script, read_regex, upsert_regex, read_variables, set_variables, remove_asset"),
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
            val sourceCard = session.originalCard
            if (session.kind == CreationKind.WorldBook && sourceCard != null) {
                put("source_card_name", sourceCard.name)
                put("source_card_is_reference_only", true)
            }
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
        return array.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
            require(primitive != null && primitive.isString) { "$name 第 ${index + 1} 项必须是字符串" }
            primitive.content
        }
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
