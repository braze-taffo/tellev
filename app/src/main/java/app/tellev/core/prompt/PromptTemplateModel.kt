package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class PromptTemplateRequest(
    val messages: List<PromptMessage>,
    val context: MacroContext,
    val metadata: JsonObject,
    val worldEntries: List<PromptTemplateWorldEntry> = emptyList(),
    val worldCatalog: List<PromptTemplateWorldEntry> = emptyList(),
    val currentWorldBookId: String? = null,
    /**
     * Initial message-scope variables for the current generation (ST clones the
     * previous floor's variables into the floor being generated). Tellev seeds
     * this from the last message carrying variables / the card's init vars.
     */
    val messageVariables: JsonObject? = null,
    /**
     * Visible chat floors in ST's message shape for the getChatMessage(s) /
     * matchChatMessages template family (ST-Prompt-Template chat.ts).
     */
    val chat: List<PromptTemplateChatMessage> = emptyList(),
)

/** ST `chat` entry subset used by the chat-reading template functions. */
data class PromptTemplateChatMessage(
    val id: Int,
    val isUser: Boolean,
    val isSystem: Boolean,
    val name: String? = null,
    val content: String,
)

/**
 * Per-floor render fields (ST-Prompt-Template handler.ts:43): ST exposes
 * `message_id`/`is_user`/`is_system`/`name`/`is_last` to the environment of
 * each chat floor it renders, and leaves them unset in the generate-before
 * environment that renders the system prompt. `swipe_id` stays unset —
 * Tellev's render pipeline carries no per-floor swipe index.
 */
data class PromptTemplateMessageContext(
    val messageId: Int? = null,
    val swipeId: Int? = null,
    val isLast: Boolean? = null,
    val isUser: Boolean? = null,
    val isSystem: Boolean? = null,
    val name: String? = null,
)

data class PromptTemplateWorldEntry(
    val id: String,
    val content: String,
    val raw: JsonObject = JsonObject(emptyMap()),
    val bookId: String? = null,
    val bookName: String? = null,
    val comment: String = "",
    val title: String = "",
)

data class PromptTemplateResult(
    val messages: List<PromptMessage>,
    val warnings: List<String> = emptyList(),
    val variableUpdates: PromptTemplateVariableUpdates = PromptTemplateVariableUpdates(),
)

/**
 * Scope-aware variable snapshots produced by a template evaluation.
 *
 * A null scope means that evaluation did not mutate it. An empty object is
 * deliberately distinct: it means the template cleared that scope and the
 * caller must persist the empty object.
 */
@Serializable
data class PromptTemplateVariableUpdates(
    val local: JsonObject? = null,
    val global: JsonObject? = null,
    /**
     * Message-scope (per-floor) variables written during the generate phase
     * (ST default scope for setvar). The generation coordinator attaches this
     * to the newly created assistant message — ChatMessage.variables[swipe].
     */
    val message: JsonObject? = null,
)

internal data class TemplateState(
    val context: MacroContext,
    val localVariables: MutableMap<String, Any?>,
    val globalVariables: MutableMap<String, Any?>,
    val messageVariables: MutableMap<String, Any?> = linkedMapOf(),
    val initialLocalVariables: Map<String, Any?>,
    val initialGlobalVariables: Map<String, Any?>,
    val initialMessageVariables: Map<String, Any?> = emptyMap(),
    val worldCatalog: List<PromptTemplateWorldEntry>,
    val currentWorldBookId: String?,
    val chatMessages: List<PromptTemplateChatMessage> = emptyList(),
    val worldInfoStack: MutableList<String> = mutableListOf(),
    val variables: MutableMap<String, Any?> = linkedMapOf(),
    val locals: MutableMap<String, Any?> = mutableMapOf(),
    val warnings: LinkedHashSet<String> = linkedSetOf(),
) {
    fun warn(message: String) {
        warnings += message
    }

    /**
     * A throwaway copy for rendering historical floors: ST renders each floor
     * once at creation (is_ejs_processed guard), so re-running a floor's
     * template on every build must not re-apply its writes. Reads see the
     * current values; writes land in these copies and are dropped.
     */
    fun isolatedSnapshot(): TemplateState = TemplateState(
        context = context,
        localVariables = PromptTemplateExpressionEvaluator.deepCopyMap(localVariables),
        globalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(globalVariables),
        messageVariables = PromptTemplateExpressionEvaluator.deepCopyMap(messageVariables),
        initialLocalVariables = initialLocalVariables,
        initialGlobalVariables = initialGlobalVariables,
        initialMessageVariables = initialMessageVariables,
        worldCatalog = worldCatalog,
        currentWorldBookId = currentWorldBookId,
        variables = PromptTemplateExpressionEvaluator.deepCopyMap(variables),
    )
}

internal sealed interface TemplateToken {
    data class Text(val value: String) : TemplateToken
    data class Output(val expression: String) : TemplateToken
    data class Code(val code: String) : TemplateToken
}

internal data class TemplateBlock(val elseIndex: Int?, val endIndex: Int)

internal enum class InstructionKind { Generate, Inject }
internal enum class Placement { Before, After }

internal data class InstructionBlock(
    val kind: InstructionKind,
    val body: String,
    val placement: Placement? = null,
    val index: Int? = null,
    val target: String? = null,
    val regex: String? = null,
    val role: MessageRole? = null,
)

internal data class InstructionParseResult(
    val normalText: String,
    val blocks: List<InstructionBlock>,
)

internal object UnsupportedExpression

internal data class VariableScopes(
    val local: Map<String, Any?>,
    val global: Map<String, Any?>,
)
