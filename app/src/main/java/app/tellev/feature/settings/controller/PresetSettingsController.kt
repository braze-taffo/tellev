package app.tellev.feature.settings.controller

import android.content.Context
import android.net.Uri
import app.tellev.core.model.GenerationPreset
import app.tellev.core.storage.StDataStore
import app.tellev.feature.settings.SettingsUiState
import app.tellev.util.UriUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class PresetSettingsController(
    private val dataStore: StDataStore,
    private val json: Json,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun loadPresets() {
        scope.launch {
            try {
                val presets = dataStore.listPresets()
                stateFlow.update { it.copy(presets = presets) }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(error = "加载预设失败：${e.message}")
                }
            }
        }
    }

    fun savePreset(preset: GenerationPreset) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.savePreset(preset)
                if (dataStore.readSelectedPresetName(preset.category) == preset.id) {
                    dataStore.selectPreset(preset.category, preset.id)
                }
                val presets = dataStore.listPresets()
                stateFlow.update {
                    it.copy(
                        presets = presets,
                        isLoading = false,
                        info = "预设“${preset.name}”已保存。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "保存预设失败：${e.message}",
                    )
                }
            }
        }
    }

    fun selectPreset(preset: GenerationPreset) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.selectPreset(preset.category, preset.id)
                stateFlow.update {
                    it.copy(
                        selectedPresetNames = it.selectedPresetNames + (preset.category to preset.id),
                        isLoading = false,
                        info = "已切换到预设“${preset.name}”。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "切换预设失败：${e.message}")
                }
            }
        }
    }

    fun copyPreset(preset: GenerationPreset, requestedName: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val id = presetId(requestedName)
                require(stateFlow.value.presets.none { it.category == preset.category && it.id == id }) {
                    "同分类已存在预设“$id”"
                }
                dataStore.savePreset(preset.copy(id = id, name = requestedName.trim()))
                val presets = dataStore.listPresets()
                stateFlow.update {
                    it.copy(
                        presets = presets,
                        isLoading = false,
                        info = "预设已另存为“${requestedName.trim()}”。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(isLoading = false, error = "另存为失败：${e.message}") }
            }
        }
    }

    fun renamePreset(preset: GenerationPreset, requestedName: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val id = presetId(requestedName)
                require(id != preset.id) { "新名称与原名称相同" }
                require(stateFlow.value.presets.none { it.category == preset.category && it.id == id }) {
                    "同分类已存在预设“$id”"
                }
                val wasSelected = dataStore.readSelectedPresetName(preset.category) == preset.id
                dataStore.savePreset(preset.copy(id = id, name = requestedName.trim()))
                dataStore.deletePreset(preset.id, preset.category.name)
                if (wasSelected) dataStore.selectPreset(preset.category, id)
                val selected = dataStore.readSelectedPresetName(preset.category)
                val presets = dataStore.listPresets()
                stateFlow.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = if (selected == null) it.selectedPresetNames - preset.category
                            else it.selectedPresetNames + (preset.category to selected),
                        isLoading = false,
                        info = "预设已重命名为“${requestedName.trim()}”。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(isLoading = false, error = "重命名失败：${e.message}") }
            }
        }
    }

    fun exportPreset(context: Context, uri: Uri, preset: GenerationPreset) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val raw = dataStore.readPreset(preset.category, preset.id)?.raw ?: preset.raw
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.encodeToString(JsonObject.serializer(), raw).encodeToByteArray())
                    } ?: error("无法创建导出文件")
                }
                stateFlow.update { it.copy(isLoading = false, info = "预设“${preset.name}”已导出。") }
            } catch (e: Exception) {
                stateFlow.update { it.copy(isLoading = false, error = "导出预设失败：${e.message}") }
            }
        }
    }

    fun deletePreset(id: String, providerType: String? = null) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val deletedPreset = stateFlow.value.presets.firstOrNull {
                    it.id == id && (providerType == null || it.providerType == providerType)
                }
                dataStore.deletePreset(id, providerType)
                val presets = dataStore.listPresets()
                val selectedNames = deletedPreset?.category?.let { category ->
                    val selected = dataStore.readSelectedPresetName(category)
                    if (selected == null) stateFlow.value.selectedPresetNames - category
                    else stateFlow.value.selectedPresetNames + (category to selected)
                } ?: stateFlow.value.selectedPresetNames
                stateFlow.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = selectedNames,
                        isLoading = false,
                        info = "预设已删除。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "删除预设失败：${e.message}",
                    )
                }
            }
        }
    }

    fun importPreset(context: Context, uri: Uri, providerCategory: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }
                val fileName = UriUtils.resolveDisplayName(context, uri)
                    ?: uri.lastPathSegment
                    ?: "preset.json"
                val result = dataStore.importPreset(bytes, providerCategory, fileName)
                val imported = result.preset
                val presets = dataStore.listPresets()
                stateFlow.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = it.selectedPresetNames + (result.inferredCategory to imported.id),
                        isLoading = false,
                        info = buildString {
                            append("预设「${imported.name}」已导入并启用")
                            if (result.warnings.isNotEmpty()) append("；${result.warnings.joinToString("；")}")
                            append('。')
                        },
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "导入预设失败：${e.message}",
                    )
                }
            }
        }
    }

    private fun presetId(name: String): String = name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .ifBlank { "preset" }
}
