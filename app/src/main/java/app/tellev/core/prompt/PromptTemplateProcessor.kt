package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
import kotlinx.serialization.json.JsonObject

interface PromptTemplateProcessor {
    fun process(request: PromptTemplateRequest): PromptTemplateResult
    fun systemPromptContentFor(entry: PromptTemplateWorldEntry): String = entry.content
}

/**
 * Bridge to the production EJS renderer running on the template WebView.
 *
 * [evaluate] mirrors ST-Prompt-Template's `evalTemplate`; the two lifecycle
 * hooks give [DefaultPromptTemplateProcessor] the per-generation controls ST
 * performs in handler.ts — sticky-injection decay (handler.ts:403) and outlet
 * placeholder resolution (handler.ts:382).
 */
interface PromptTemplateJsBridge {
    fun evaluate(request: JsonObject): JsonObject

    /** Decrement sticky counters on injected prompts; called once per prompt build. */
    fun deactivateInjectedPrompts() {}

    /**
     * Resolve `{{outletPromptsInjected:key}}` placeholders in content that did
     * not go through EJS rendering; rendered content is already resolved.
     */
    fun replaceOutletPlaceholders(content: String): String = content
}

class DefaultPromptTemplateProcessor(
    @Volatile var ejsSettings: EjsTemplateSettings = EjsTemplateSettings.DEFAULT,
    private val javascriptEvaluator: PromptTemplateJsBridge? = null,
) : PromptTemplateProcessor {

    override fun process(request: PromptTemplateRequest): PromptTemplateResult {
        // Master switch: when the EJS template module is disabled,
        // skip all template processing and return messages unchanged.
        if (!ejsSettings.enabled) {
            return PromptTemplateResult(messages = request.messages)
        }

        // ST decays sticky injections once per generation (handler.ts:403).
        // Decaying at the start of each build keeps the same observable
        // lifetime: entries registered during this build stay available for
        // this build's outlet collection, then expire per their sticky count.
        if (javascriptEvaluator != null) {
            javascriptEvaluator.deactivateInjectedPrompts()
        } else {
            PromptInjectedRegistry.deactivate()
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

        // Fast path for content without EJS, outlet placeholders, or
        // instruction markers. Marker-bearing requests route to the full path
        // so the end-of-build scan below resolves them (ST handler.ts:380).
        if (!request.messages.any { it.content.contains("<%") || it.content.contains(OUTLET_MARKER) || PromptTemplateParser.hasInstructionMarker(it.content) } &&
            activeBlocks.isEmpty()
        ) {
            return PromptTemplateResult(messages = request.messages)
        }

        val scopes = PromptTemplateExpressionEvaluator.extractVariableScopes(request.metadata)
        val state = TemplateState(
            context = request.context,
            localVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.local),
            globalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.global),
            messageVariables = PromptTemplateExpressionEvaluator.deepCopyMap(
                PromptTemplateExpressionEvaluator.messageVariableMap(request.messageVariables),
            ),
            initialLocalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.local),
            initialGlobalVariables = PromptTemplateExpressionEvaluator.deepCopyMap(scopes.global),
            initialMessageVariables = PromptTemplateExpressionEvaluator.messageVariableMap(request.messageVariables),
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
            // Side-effect isolation: ST renders each historical floor once at
            // creation (is_ejs_processed guard, handler.ts:443-446), so its
            // setvar/incvar apply exactly once. Tellev must re-render floors
            // every build for fresh output — without isolation a `<% incvar %>`
            // in any history message would accumulate on every generation.
            // Only the system prompt (the worldbook generate phase, where the
            // current turn's message has not been rendered yet) and the last
            // chat message (the current turn) persist writes.
            val lastChatIndex = injectedMessages.indexOfLast { it.channel != CHANNEL_MARKER }
            injectedMessages.mapIndexed { index, message ->
                val persistent = index == 0 || index == lastChatIndex
                val content = if (persistent) {
                    PromptTemplateExpressionEvaluator.renderTemplate(message.content, state, javascriptEvaluator)
                } else {
                    val isolatedState = state.isolatedSnapshot()
                    val isolatedContent = PromptInjectedRegistry.withIsolatedSnapshot {
                        PromptTemplateExpressionEvaluator.renderTemplate(
                            message.content, isolatedState, javascriptEvaluator, isolated = true,
                        )
                    }
                    state.warnings.addAll(isolatedState.warnings)
                    isolatedContent
                }
                message.copy(content = content)
            }
        } else {
            injectedMessages
        }

        return PromptTemplateResult(
            messages = resolveOutletPlaceholders(rendered),
            warnings = state.warnings.toList(),
            variableUpdates = PromptTemplateVariableUpdates(
                local = state.localVariables
                    .takeIf { it != state.initialLocalVariables }
                    ?.let(PromptTemplateExpressionEvaluator::toJsonObject),
                global = state.globalVariables
                    .takeIf { it != state.initialGlobalVariables }
                    ?.let(PromptTemplateExpressionEvaluator::toJsonObject),
                message = state.messageVariables
                    .takeIf { it != state.initialMessageVariables }
                    ?.let(PromptTemplateExpressionEvaluator::toJsonObject),
            ),
        )
    }

    /**
     * ST resolves {{outletPromptsInjected:key}} across message content after
     * the whole generate pass (handler.ts:382) — entries injected by the
     * worldbook entries rendered inside the system prompt must be visible to
     * placeholders sitting in plain messages that never went through EJS.
     */
    private fun resolveOutletPlaceholders(messages: List<PromptMessage>): List<PromptMessage> {
        if (messages.none { it.content.contains(OUTLET_MARKER) }) return messages
        return messages.map { message ->
            if (message.content.contains(OUTLET_MARKER)) {
                message.copy(
                    content = if (javascriptEvaluator != null) {
                        javascriptEvaluator.replaceOutletPlaceholders(message.content)
                    } else {
                        PromptTemplateOutlet.apply(message.content)
                    },
                )
            } else {
                message
            }
        }
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

private const val OUTLET_MARKER = "{{outletPromptsInjected:"
