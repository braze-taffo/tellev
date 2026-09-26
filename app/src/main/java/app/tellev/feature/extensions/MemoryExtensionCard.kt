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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tellev.core.memory.MemorySettings
import app.tellev.core.memory.MemorySettingsStore
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.LocalTellevGraph
import app.tellev.R
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
                    Text(stringResource(R.string.extmem_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.extmem_subtitle), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = saved.enabled, onCheckedChange = { enabled ->
                    val next = saved.copy(enabled = enabled)
                    saved = next
                    scope.launch { runCatching { settingsStore.write(next) }.onFailure { error = it.message } }
                })
            }
            OutlinedButton(onClick = { draft = saved; showSettings = true }) { Text(stringResource(R.string.extmem_open_settings)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.extmem_model_section_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.extmem_model_section_subtitle), style = MaterialTheme.typography.bodySmall)
                MemoryProviderSelector(stringResource(R.string.extmem_existing_connection), draft.providerId, providerChoices) { draft = draft.copy(providerId = it) }
                if (draft.providerId.isNotBlank()) {
                    OutlinedTextField(draft.providerModel, { draft = draft.copy(providerModel = it) }, label = { Text(stringResource(R.string.extmem_model_name_label)) }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(draft.customBaseUrl, { draft = draft.copy(customBaseUrl = it) }, label = { Text(stringResource(R.string.extmem_custom_base_url_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.customApiKey, { draft = draft.copy(customApiKey = it) }, label = { Text(stringResource(R.string.extmem_custom_api_key_label)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.customModel, { draft = draft.copy(customModel = it) }, label = { Text(stringResource(R.string.extmem_custom_model_label)) }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.extmem_vector_toggle_title), modifier = Modifier.weight(1f))
                    Switch(draft.vectorEnabled, onCheckedChange = { draft = draft.copy(vectorEnabled = it) })
                }
                if (draft.vectorEnabled) {
                    Text(stringResource(R.string.extmem_vector_hint), style = MaterialTheme.typography.bodySmall)
                    MemoryProviderSelector(stringResource(R.string.extmem_existing_vector_connection), draft.vectorProviderId, vectorChoices) { draft = draft.copy(vectorProviderId = it) }
                    if (draft.vectorProviderId.isBlank()) {
                        OutlinedTextField(draft.vectorBaseUrl, { draft = draft.copy(vectorBaseUrl = it) }, label = { Text(stringResource(R.string.extmem_vector_base_url_label)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.vectorApiKey, { draft = draft.copy(vectorApiKey = it) }, label = { Text(stringResource(R.string.extmem_vector_api_key_label)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(draft.vectorModel, { draft = draft.copy(vectorModel = it) }, label = { Text(stringResource(R.string.extmem_vector_model_label)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.vectorPath, { draft = draft.copy(vectorPath = it) }, label = { Text(stringResource(R.string.extmem_vector_path_label)) }, modifier = Modifier.fillMaxWidth())
                }
                Button(onClick = {
                    val next = draft
                    scope.launch {
                        runCatching { settingsStore.write(next) }
                            .onSuccess { saved = next; showSettings = false; error = null }
                            .onFailure { error = it.message }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.extmem_save_button)) }
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
    val selectedLabel = choices.firstOrNull { it.first == selected }?.second
        ?: stringResource(R.string.extmem_custom_entry)
    OutlinedButton(onClick = { expanded = true }) {
        Text(stringResource(R.string.extmem_selector_label, title, selectedLabel))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.extmem_custom_entry)) }, onClick = { onSelect(""); expanded = false })
        choices.forEach { (id, label) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(id); expanded = false })
        }
    }
}
