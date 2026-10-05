package app.tellev.feature.chat

import app.tellev.core.prompt.WorldEntryHit
import app.tellev.core.prompt.WorldEntryRejection
import kotlinx.serialization.Serializable

/**
 * In-memory snapshot of the last main-line generation's assembled context:
 * the built prompt, world-book activation detail and memory provenance.
 * Kept for the context viewer only — never persisted; the export button
 * writes the user-selected JSON through SAF.
 */
@Serializable
data class ContextSnapshot(
    val sessionId: String,
    val capturedAtMillis: Long,
    /** The built prompt messages in wire order (role/name/content). */
    val messages: List<ContextMessageSnapshot> = emptyList(),
    val estimatedTokenCount: Int? = null,
    /** The preset context-window budget this turn was built against; null when unknown. */
    val contextTokenLimit: Int? = null,
    val warnings: List<String> = emptyList(),
    val worldBookHits: List<WorldEntryHit> = emptyList(),
    val rejectedWorldEntries: List<WorldEntryRejection> = emptyList(),
    val memoryInjections: List<ContextMemoryInjection> = emptyList(),
) {
    /** Meaningful only while [sessionId] is still the open session. */
    fun matches(sessionId: String?): Boolean = this.sessionId == sessionId
}

@Serializable
data class ContextMessageSnapshot(
    val role: String,
    val name: String? = null,
    val content: String,
)

@Serializable
data class ContextMemoryInjection(
    val recordId: String,
    val kind: String,
    val text: String,
    /** Retrieval score; null for pinned lines (state/summary records). */
    val score: Double? = null,
    val sourceMessageIds: List<String> = emptyList(),
)
