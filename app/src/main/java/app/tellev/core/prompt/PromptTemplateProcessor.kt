package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
import kotlinx.serialization.json.JsonObject

interface PromptTemplateProcessor {
    fun process(request: PromptTemplateRequest): PromptTemplateResult
    fun systemPromptContentFor(entry: PromptTemplateWorldEntry): String = entry.content
}

class DefaultPromptTemplateProcessor(
    @Volatile var ejsSettings: EjsTemplateSettings = EjsTemplateSettings.DEFAULT,
    private val javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
) : PromptTemplateProcessor {

    override fun process(request: PromptTemplateRequest): PromptTemplateResult {
        // Master switch: when the EJS template module is disabled,
        // skip all template processing and return messages unchanged.
        if (!ejsSettings.enabled) {
            return PromptTemplateResult(messages = request.messages)
        }

        val entryParses = request.worldEntries.map { PromptTemplateParser.parseWorldEntry(it) }

        // When generate_loader_enabled is off, skip [GENERATE:BEFORE] /
        // @INJECT instruction blocks from world entries.  The entry text
        // is still included as plain content (normalText) but no
        // instruction-driven message injection happens.
        val activeBlocks = if (ejsSettings.generateLoaderEnabled) {
            entryParses.flatMap { it.blocks }
        } else {
            emptyList()
        }

        if (!request.messages.any { it.content.contains("<%") || PromptTemplateParser.hasInstructionMarker(it.content) } &&
            activeBlocks.isEmpty()
        ) {
            return PromptTemplateResult(messages = request.messages)
        }

        val scopes = PromptTemplateExpressionEvaluator.extractVariableScopes(request.metadata)
        val state = TemplateState(
            context = request.context,
            localVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.local),
            globalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.global),
            initialLocalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.local),
            initialGlobalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.global),
            worldCatalog = request.worldCatalog.ifEmpty { request.worldEntries },
            currentWorldBookId = request.currentWorldBookId,
        ).also(PromptTemplateExpressionEvaluator::refreshMergedVariables)

        val injectedMessages = PromptTemplateInstructionApplier.applyInstructionBlocks(
            request.messages.map { message -> message.copy(content = PromptTemplateParser.stripInstructionBlocks(message.content)) },
            activeBlocks,
            state,
        )

        // When render_enabled is off, skip EJS rendering (<%= ... %>) on
        // message content.  Instruction blocks are still applied above
        // because they are part of the generate phase, not the render phase.
        val rendered = if (ejsSettings.renderEnabled) {
            injectedMessages.map { message ->
                message.copy(content = PromptTemplateExpressionEvaluator.renderTemplate(message.content, state, javascriptEvaluator))
            }
        } else {
            injectedMessages
        }

        return PromptTemplateResult(
            messages = rendered,
            warnings = state.warnings.toList(),
            variableUpdates = PromptTemplateVariableUpdates(
                local = state.localVariables
                    .takeIf { it != state.initialLocalVariables }
                    ?.let(PromptTemplateExpressionEvaluator::toJsonObject),
                global = state.globalVariables
                    .takeIf { it != state.initialGlobalVariables }
                    ?.let(PromptTemplateExpressionEvaluator::toJsonObject),
            ),
        )
    }

    override fun systemPromptContentFor(entry: PromptTemplateWorldEntry): String =
        if (!ejsSettings.enabled || !ejsSettings.generateLoaderEnabled) {
            // When disabled, return the raw content unchanged so world
            // book entries are still included as plain text.
            entry.content
        } else {
            PromptTemplateParser.parseWorldEntry(entry).normalText.trim()
        }
}
