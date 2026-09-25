package app.tellev.feature.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tellev.core.memory.MemorySettings
import app.tellev.core.memory.MemorySettingsStore
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.LocalTellevGraph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryExtensionCard() {
    val graph = LocalTellevGraph.current
    val settingsStore = remember(graph) { MemorySettingsStore(graph.secretStore) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(MemorySettings()) }
    var draft by remember { mutableStateOf(saved) }
    var showSettings by remember { mutableStateOf(false) }
    var providerChoices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var vectorChoices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(graph) {
        saved = settingsStore.read()
        draft = saved
        val custom = ProviderConfigPersistence.listCustomConfigs(graph.secretStore)
            .map { ProviderConfigPersistence.selectedIdFor(it.id) to it.name }
        providerChoices = graph.providerRegistry.chatAdapters().map { it.id to it.displayName } + custom
        vectorChoices = custom
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("长期记忆", style = MaterialTheme.typography.titleMedium)
                    Text("内置可开关模块；关闭时暂停所有对话的提取与注入，已有记忆保留。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = saved.enabled, onCheckedChange = { enabled ->
                    val next = saved.copy(enabled = enabled)
                    saved = next
                    scope.launch { runCatching { settingsStore.write(next) }.onFailure { error = it.message } }
                })
            }
            OutlinedButton(onClick = { draft = saved; showSettings = true }) { Text("模型与检索设置") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("记忆整理模型", style = MaterialTheme.typography.titleMedium)
                Text("与聊天模型、生成预设分开配置。未配置时不自动使用聊天模型。", style = MaterialTheme.typography.bodySmall)
                MemoryProviderSelector("已有连接", draft.providerId, providerChoices) { draft = draft.copy(providerId = it) }
                if (draft.providerId.isNotBlank()) {
                    OutlinedTextField(draft.providerModel, { draft = draft.copy(providerModel = it) }, label = { Text("记忆模型名（留空沿用连接设置）") }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(draft.customBaseUrl, { draft = draft.copy(customBaseUrl = it) }, label = { Text("独立 API 地址") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.customApiKey, { draft = draft.copy(customApiKey = it) }, label = { Text("独立 API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.customModel, { draft = draft.copy(customModel = it) }, label = { Text("独立模型名") }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("向量检索（可选）", modifier = Modifier.weight(1f))
                    Switch(draft.vectorEnabled, onCheckedChange = { draft = draft.copy(vectorEnabled = it) })
                }
                if (draft.vectorEnabled) {
                    Text("向量接口使用 OpenAI 兼容的 /embeddings。失败时继续用本地检索；启用后查询可能产生额外费用。", style = MaterialTheme.typography.bodySmall)
                    MemoryProviderSelector("已有向量连接", draft.vectorProviderId, vectorChoices) { draft = draft.copy(vectorProviderId = it) }
                    if (draft.vectorProviderId.isBlank()) {
                        OutlinedTextField(draft.vectorBaseUrl, { draft = draft.copy(vectorBaseUrl = it) }, label = { Text("向量 API 地址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.vectorApiKey, { draft = draft.copy(vectorApiKey = it) }, label = { Text("向量 API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(draft.vectorModel, { draft = draft.copy(vectorModel = it) }, label = { Text("向量模型名（已有连接可覆盖）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.vectorPath, { draft = draft.copy(vectorPath = it) }, label = { Text("向量接口路径") }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = {
                    val next = draft
                    scope.launch {
                        runCatching { settingsStore.write(next) }
                            .onSuccess { saved = next; showSettings = false; error = null }
                            .onFailure { error = it.message }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
            }
        }
    }
}

@Composable
private fun MemoryProviderSelector(
    title: String,
    selected: String,
    choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }) {
        Text("$title：${choices.firstOrNull { it.first == selected }?.second ?: "单独填写"}")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("单独填写") }, onClick = { onSelect(""); expanded = false })
        choices.forEach { (id, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false })
        }
    }
}
