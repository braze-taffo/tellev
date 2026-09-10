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
)

internal data class TemplateState(
    val context: MacroContext,
    val localVariables: MutableMap<String, Any?>,
    val globalVariables: MutableMap<String, Any?>,
    val initialLocalVariables: Map<String, Any?>,
    val initialGlobalVariables: Map<String, Any?>,
    val worldCatalog: List<PromptTemplateWorldEntry>,
    val currentWorldBookId: String?,
    val worldInfoStack: MutableList<String> = mutableListOf(),
    val variables: MutableMap<String, Any?> = linkedMapOf(),
    val locals: MutableMap<String, Any?> = mutableMapOf(),
    val warnings: LinkedHashSet<String> = linkedSetOf(),
) {
    fun warn(message: String) {
        warnings += message
    }
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
