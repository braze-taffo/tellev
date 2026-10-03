package app.tellev.core.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Per-character consent record for scripts embedded in character cards (A1).
 *
 * Card scripts used to auto-execute on chat open; now the first load (and any
 * later change of the script set) requires an explicit user decision which is
 * persisted here, keyed by character id together with the fingerprint of the
 * script source that was approved/denied. A changed fingerprint re-opens the
 * question; an approved one re-loads silently.
 *
 * Written via tmp-file + ATOMIC_MOVE so a crash never leaves a truncated
 * consent file (the file is read with a runCatching fallback upstream anyway).
 */
internal class CharacterScriptConsentStore(private val root: Path) {

    data class Consent(val approved: Boolean, val fingerprint: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun read(characterId: String): Consent? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readAll()[characterId]
        }
    }

    suspend fun write(characterId: String, approved: Boolean, fingerprint: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = readAll().toMutableMap()
                updated[characterId] = Consent(approved, fingerprint)
                writeAll(updated)
            }
        }

    private fun readAll(): Map<String, Consent> {
        val path = consentFile()
        if (!path.exists()) return emptyMap()
        val raw = runCatching { json.parseToJsonElement(path.readText()) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val entries = raw["consents"] as? JsonObject ?: return emptyMap()
        return entries.mapNotNull { (characterId, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val fingerprint = (obj["fingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@mapNotNull null
            val approved = obj["approved"]?.let { element ->
                runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
            } ?: return@mapNotNull null
            characterId to Consent(approved, fingerprint)
        }.toMap()
    }

    private fun writeAll(entries: Map<String, Consent>) {
        val output = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("consents", buildJsonObject {
                    entries.forEach { (characterId, consent) ->
                        put(characterId, buildJsonObject {
                            put("approved", JsonPrimitive(consent.approved))
                            put("fingerprint", JsonPrimitive(consent.fingerprint))
                        })
                    }
                })
            },
        )
        root.toFile().mkdirs()
        val destination = consentFile()
        val temporary = root.resolve("script-consent.json.tmp")
        temporary.toFile().writeText(output)
        try {
            Files.move(
                temporary, destination,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun consentFile(): Path = root.resolve("script-consent.json")

    companion object {
        /** SHA-256 of the isolated script source; changes when the script set changes. */
        fun fingerprint(scriptSource: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(scriptSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
