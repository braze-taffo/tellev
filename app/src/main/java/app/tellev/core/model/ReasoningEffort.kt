package app.tellev.core.model

import kotlinx.serialization.Serializable

/**
 * Unified user-facing reasoning ("thinking") strength.
 *
 * Resolution order for one request:
 * request-level override > session override > preset field > [Auto].
 *
 * [Auto] means "leave the provider's default behavior untouched": adapters
 * keep their existing raw-field passthrough and send nothing new, so a
 * SillyTavern preset that already carries reasoning fields keeps working.
 * Explicit levels (Low..Max) take over the reasoning fields for that request;
 * [Off] suppresses them so a reasoning-capable relay does not silently think.
 *
 * Lives in the model layer: [GenerationPreset] persists it as a first-class
 * field while adapters map it onto their wire protocols.
 */
@Serializable
enum class ReasoningEffort {
    Auto, Off, Minimal, Low, Medium, High, XHigh, Max;

    companion object {
        /** Parses a stored/settings string; unknown or blank values return null. */
        fun fromStored(value: String?): ReasoningEffort? =
            value?.trim()?.takeIf(String::isNotBlank)
                ?.let { v -> entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }
    }
}
