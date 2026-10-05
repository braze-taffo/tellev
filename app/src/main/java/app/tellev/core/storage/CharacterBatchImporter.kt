package app.tellev.core.storage

import java.util.UUID

/**
 * One payload handed to [CharacterBatchImporter]. [bytes] is null when the
 * source document could not be read at all (provider error, size cap);
 * [readError] then carries the failure for the report.
 */
data class BatchImportSource(
    val fileName: String,
    val bytes: ByteArray?,
    val readError: String? = null,
) {
    val isSuccess: Boolean
        get() = bytes != null && readError == null
}

/** Per-file outcome of a batch import. */
data class BatchImportResult(
    val fileName: String,
    val characterName: String? = null,
    val cardId: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null
}

/** Aggregated outcome of a batch import, shown to the user as a summary dialog. */
data class BatchImportReport(val results: List<BatchImportResult>) {
    val successCount: Int
        get() = results.count { it.isSuccess }
    val failedCount: Int
        get() = results.size - successCount
}

/**
 * Batch character-card import. Parses each payload with [CharacterImporter],
 * fixes up blank/placeholder ids, guarantees uniqueness against the live id
 * set (including ids minted earlier in the same batch), and persists through
 * [StDataStore.importCharacter]. One malformed file never aborts the rest —
 * failures are isolated per item and surfaced in the [BatchImportReport].
 */
class CharacterBatchImporter(private val importer: CharacterImporter = CharacterImporter()) {

    /**
     * Imports [sources] sequentially. [existingIds] must be the caller's live
     * character id set; ids minted during this batch are added to it so two
     * same-named cards in one drop cannot collide with each other.
     */
    suspend fun importAll(
        sources: List<BatchImportSource>,
        existingIds: Set<String>,
        store: StDataStore,
    ): BatchImportReport {
        val takenIds = existingIds.toMutableSet()
        val results = mutableListOf<BatchImportResult>()
        for (source in sources) {
            val result = if (!source.isSuccess) {
                BatchImportResult(
                    fileName = source.fileName,
                    error = source.readError ?: READ_FAILED,
                )
            } else {
                importOne(source, takenIds, store)
            }
            if (result.isSuccess) takenIds.add(requireNotNull(result.cardId))
            results.add(result)
        }
        return BatchImportReport(results)
    }

    private suspend fun importOne(
        source: BatchImportSource,
        takenIds: MutableSet<String>,
        store: StDataStore,
    ): BatchImportResult {
        val bytes = requireNotNull(source.bytes)
        return try {
            val card = importer.importFromBytes(bytes, source.fileName)
            val named = if (card.id.isBlank() || card.id == PLACEHOLDER_ID) {
                card.copy(id = "char_${UUID.randomUUID()}")
            } else {
                card
            }
            val unique = if (named.id in takenIds) {
                named.copy(id = "${named.id}_${UUID.randomUUID().toString().take(8)}")
            } else {
                named
            }
            store.importCharacter(unique, bytes, source.fileName)
            BatchImportResult(fileName = source.fileName, characterName = unique.name, cardId = unique.id)
        } catch (error: Exception) {
            BatchImportResult(fileName = source.fileName, error = error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        /** Same headroom as the external VIEW/SEND import path in [app.tellev.MainActivity]. */
        const val IMPORT_MAX_BYTES: Long = 100L * 1024 * 1024

        const val PLACEHOLDER_ID = "imported_character"

        const val READ_FAILED = "read failed"
    }
}
