package app.tellev.feature.settings.controller

import android.content.Context
import android.net.Uri
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.storage.CharacterBatchImporter
import app.tellev.core.storage.StDataStore
import app.tellev.feature.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BackupSettingsController(
    private val dataStore: StDataStore,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun exportBackup(context: Context, targetUri: Uri) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            var tempFile: java.io.File? = null
            try {
                tempFile = java.io.File.createTempFile("tellev-backup-", ".zip", context.cacheDir)
                dataStore.exportBackup(tempFile.toPath())

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error(UiStrings.get(S.bkpctl_open_target_failed))
                }

                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        info = UiStrings.get(S.bkpctl_exported),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = UiStrings.get(S.bkpctl_export_failed, e.message),
                    )
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            var tempFile: java.io.File? = null
            try {
                tempFile = java.io.File.createTempFile("tellev-import-", ".zip", context.cacheDir)
                val sourcePath = tempFile.toPath()
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(UiStrings.get(S.main_error_read_file))
                    input.use { BackupImportStaging.stage(it, tempFile) }
                }

                dataStore.importBackup(sourcePath)

                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        info = UiStrings.get(S.bkpctl_imported),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = UiStrings.get(S.bkpctl_import_failed, e.message),
                    )
                }
            } finally {
                tempFile?.delete()
            }
        }
    }
}

/**
 * Streams a picked backup into the cache holding one buffer at a time, under the
 * same byte cap the character-card import entry already applies. A document
 * larger than that is refused before it can fill cacheDir, and the archive
 * signature is checked ahead of the body so a non-ZIP pick fails immediately
 * rather than after a full copy.
 */
internal object BackupImportStaging {
    /** PK\x03\x04 (any archive with entries) plus PK\x05\x06 (an empty archive). */
    private val ZIP_SIGNATURES = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
    )
    private const val COPY_BUFFER = 64 * 1024

    /** Returns the number of bytes staged. */
    fun stage(
        source: java.io.InputStream,
        target: java.io.File,
        maxBytes: Long = CharacterBatchImporter.IMPORT_MAX_BYTES,
    ): Long {
        val signature = ByteArray(4)
        var filled = 0
        while (filled < signature.size) {
            val read = source.read(signature, filled, signature.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled < signature.size || ZIP_SIGNATURES.none { it.contentEquals(signature) }) {
            throw java.io.IOException(UiStrings.get(S.main_error_read_file))
        }

        return target.outputStream().use { output ->
            output.write(signature)
            val buffer = ByteArray(COPY_BUFFER)
            var staged = signature.size.toLong()
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                staged += read
                if (staged > maxBytes) throw java.io.IOException(UiStrings.get(S.main_error_too_large))
                output.write(buffer, 0, read)
            }
            staged
        }
    }
}
