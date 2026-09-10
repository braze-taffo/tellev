package app.tellev.feature.settings.controller

import android.content.Context
import android.net.Uri
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
                    } ?: error("Unable to open backup target")
                }

                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        info = "备份已导出。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "导出备份失败：${e.message}",
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
            try {
                val tempFile = java.io.File.createTempFile("tellev-import-", ".zip", context.cacheDir)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val sourcePath = tempFile.toPath()
                dataStore.importBackup(sourcePath)
                tempFile.delete()

                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        info = "备份已导入。请重启应用以查看变化。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "导入备份失败：${e.message}",
                    )
                }
            }
        }
    }
}
