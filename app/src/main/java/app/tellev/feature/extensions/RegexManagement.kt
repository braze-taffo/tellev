package app.tellev.feature.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.regex.CharacterRegexApplier

internal data class RegexDeleteTarget(val characterId: String?, val id: String, val name: String)

@Composable
internal fun RegexRuleRow(
    script: CharacterRegexApplier.RegexScriptSummary,
    busy: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(script.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = script.enabled, onCheckedChange = { onToggle() }, enabled = !busy)
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ri_delete))
        }
    }
}

@Composable
internal fun GlobalRegexCard(state: ExtensionsUiState, viewModel: ExtensionsViewModel, onDelete: (RegexDeleteTarget) -> Unit) {
    val summaries = remember(state.globalRegexScripts) { CharacterRegexApplier.summarizeScripts(state.globalRegexScripts) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ri_global_title, summaries.size), style = MaterialTheme.typography.titleSmall)
            if (summaries.isEmpty()) Text(stringResource(R.string.ri_global_empty), style = MaterialTheme.typography.bodySmall)
            summaries.forEach { script ->
                key(script.id) {
                    RegexRuleRow(script, state.regexBusy,
                        onToggle = { viewModel.toggleRegexScript(null, script.id) },
                        onDelete = { onDelete(RegexDeleteTarget(null, script.id, script.name)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegexImportSheet(state: ExtensionsUiState, viewModel: ExtensionsViewModel) {
    val preview = state.regexImportPreview ?: return
    var characterScope by remember(preview) { mutableStateOf(false) }
    var selectedId by remember(preview) { mutableStateOf<String?>(null) }
    var search by remember(preview) { mutableStateOf("") }
    val summaries = remember(preview) { CharacterRegexApplier.summarizeScripts(preview) }
    ModalBottomSheet(
        onDismissRequest = viewModel::dismissRegexImport,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ri_preview, summaries.size), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.ri_overwrite_hint), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                items(summaries) { script ->
                    Text(stringResource(if (script.enabled) R.string.ri_rule_enabled else R.string.ri_rule_disabled, script.name),
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !characterScope, onClick = { characterScope = false }, enabled = !state.regexBusy)
                Text(stringResource(R.string.ri_global), Modifier.clickable(enabled = !state.regexBusy) { characterScope = false })
                RadioButton(selected = characterScope, onClick = { characterScope = true },
                    enabled = !state.regexBusy && state.regexImportCharacters.isNotEmpty())
                Text(stringResource(R.string.ri_character), Modifier.clickable(
                    enabled = !state.regexBusy && state.regexImportCharacters.isNotEmpty()) { characterScope = true })
            }
            if (characterScope) {
                OutlinedTextField(value = search, onValueChange = { search = it },
                    label = { Text(stringResource(R.string.ri_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.regexBusy)
                val filtered = state.regexImportCharacters.filter { it.name.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true) }
                if (filtered.isEmpty()) Text(stringResource(R.string.ri_no_matches))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(filtered, key = { it.id }) { character ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.regexBusy) { selectedId = character.id },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == character.id, onClick = { selectedId = character.id }, enabled = !state.regexBusy)
                            Column {
                                Text(character.name)
                                Text(character.id, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else if (state.regexImportCharacters.isEmpty()) {
                Text(stringResource(R.string.ri_no_characters), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::dismissRegexImport, enabled = !state.regexBusy) { Text(stringResource(R.string.ri_cancel)) }
                Button(onClick = { viewModel.confirmRegexImport(if (characterScope) selectedId else null) },
                    enabled = !state.regexBusy && (!characterScope || selectedId != null)) {
                    Text(stringResource(R.string.ri_confirm))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
