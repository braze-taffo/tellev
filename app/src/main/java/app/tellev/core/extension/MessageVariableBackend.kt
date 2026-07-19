package app.tellev.core.extension

import kotlinx.serialization.json.JsonObject

/**
 * Live read/write access to per-message variables (`chat[i].variables[swipe_id]`)
 * for the TavernHelper `message` variable scope (js-slash-runner
 * variables.ts:59-70). Implemented by the UI layer because messages are
 * per-chat while the extension host is a process-wide singleton — same
 * pattern as [LocalVariableBackend].
 */
interface MessageVariableBackend {
    /** Number of messages in the active chat (for negative-index resolution). */
    fun messageCount(): Int

    /** Variables of message [index] at its current swipe, or null when absent. */
    fun messageVariables(index: Int): JsonObject?

    /** Index of the last message carrying a variables object at its current swipe, or -1. */
    fun lastIndexWithVariables(): Int

    /** Replace the variables of message [index] at its current swipe and persist. */
    fun replaceMessageVariables(index: Int, variables: JsonObject)
}
