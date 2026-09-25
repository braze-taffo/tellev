package app.tellev.feature.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExtensionOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-read the regex activation file on entry. ChatViewModel.selectCharacter
    // clears the selected character's disabled regex set (exclusive activation)
    // while this app-scoped ViewModel keeps its earlier snapshot, so without a
    // refresh the per-script switches shown here would lag by one selection.
    LaunchedEffect(Unit) {
        viewModel.refreshExtensions()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ext_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refreshExtensions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ext_refresh_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (state.isLoading && state.extensions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item(key = "memory_module") { MemoryExtensionCard() }

                item(key = "built_in_header") {
                    SectionHeader(
                        title = stringResource(R.string.ext_builtin_header_title),
                        subtitle = stringResource(R.string.ext_builtin_header_subtitle),
                    )
                }

                items(state.extensions, key = { it.id }) { extension ->
                    ExtensionCard(
                        extension = extension,
                        runtimeStatus = state.runtime.statusByExtensionId[extension.id],
                        onToggle = { viewModel.toggleExtension(extension.id) },
                        onOpenSettings = { viewModel.openSettings(extension.id) },
                        onOpenDebug = { viewModel.openDebug(extension.id) },
                    )
                }

                item(key = "asset_divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                item(key = "asset_header") {
                    SectionHeader(
                        title = stringResource(R.string.ext_asset_header_title),
                        subtitle = stringResource(R.string.ext_asset_header_subtitle),
                    )
                }

                if (state.characterAssets.isEmpty()) {
                    item(key = "asset_empty") {
                        EmptyAssetState()
                    }
                } else {
                    items(state.characterAssets, key = { it.characterId }) { asset ->
                        CharacterAssetCard(
                            asset = asset,
                            disabledScriptIds = state.disabledRegexScripts[asset.characterId] ?: emptySet(),
                            onToggleScript = { viewModel.toggleRegexScript(asset.characterId, it) },
                        )
                    }
                }
            }
        }
    }

    // Settings sheets for built-in compat modules
    state.settingsSheetTarget?.let { target ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSettings() },
            sheetState = sheetState,
        ) {
            when (target) {
                "tavern-helper-compat" -> TavernHelperSettingsPanel(
                    settings = state.tavernHelperSettings,
                    onUpdate = { viewModel.updateTavernHelperSettings(it) },
                    onReset = { viewModel.resetTavernHelperSettings() },
                    onClose = { viewModel.closeSettings() },
                )
                "ejs-template-compat" -> EjsTemplateSettingsPanel(
                    settings = state.ejsTemplateSettings,
                    onUpdate = { viewModel.updateEjsTemplateSettings(it) },
                    onReset = { viewModel.resetEjsTemplateSettings() },
                    onClose = { viewModel.closeSettings() },
                )
                else -> {}
            }
        }
    }

    state.debugSheetTarget?.let { target ->
        val extension = state.extensions.firstOrNull { it.id == target }
        val showAllRuntimeLogs = target == "tavern-helper-compat"
        val logs = if (showAllRuntimeLogs) {
            state.runtime.logs
        } else {
            state.runtime.logs.filter { it.extensionId == target }
        }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeDebug() },
            sheetState = sheetState,
        ) {
            ExtensionDebugPanel(
                title = extension?.name ?: target,
                loaded = extension?.loaded ?: state.runtime.statusByExtensionId[target]?.loaded == true,
                status = state.runtime.statusByExtensionId[target],
                logs = logs,
                promptSnapshot = state.runtime.promptDebugSnapshot,
                showPromptDiagnostics = target == "ejs-template-compat",
                onClearLogs = {
                    viewModel.clearRuntimeLogs(if (showAllRuntimeLogs) null else target)
                },
                onClearPromptSnapshot = { viewModel.clearPromptDebugSnapshot() },
                onClose = { viewModel.closeDebug() },
            )
        }
    }

    state.pendingPermissionRequests.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = {
                viewModel.respondToPermissionRequest(request.requestId, granted = false)
            },
            title = { Text(stringResource(R.string.ext_permission_dialog_title)) },
            text = {
                Text(stringResource(R.string.ext_permission_dialog_body, request.extensionId, request.permission.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.respondToPermissionRequest(request.requestId, granted = true)
                }) { Text(stringResource(R.string.ext_permission_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.respondToPermissionRequest(request.requestId, granted = false)
                }) { Text(stringResource(R.string.ext_permission_deny)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionCard(
    extension: ExtensionInfo,
    runtimeStatus: ExtensionRuntimeStatus?,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
) {
    val hasSettings = extension.locked && (
        extension.id == "tavern-helper-compat" ||
        extension.id == "ejs-template-compat"
    )
    val hasDebug = extension.installed || hasSettings
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (extension.loaded) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (extension.loaded) Icons.Default.Extension else Icons.Default.ExtensionOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (extension.loaded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = extension.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = extension.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasSettings) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.ext_settings_cd),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (hasDebug) {
                    TextButton(onClick = onOpenDebug) {
                        Text(stringResource(R.string.ext_debug_button))
                    }
                }
                Switch(
                    checked = extension.loaded,
                    enabled = !extension.locked,
                    onCheckedChange = { onToggle() },
                )
            }

            runtimeStatus?.lastError?.let { lastError ->
                Text(
                    text = stringResource(R.string.ext_last_error, lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                extension.permissions.forEach { permission ->
                    AssistChip(
                        onClick = {},
                        label = { Text(permission) },
                    )
                }
                if (extension.locked) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.ext_auto_enable_chip)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterAssetCard(
    asset: CharacterAssetInfo,
    disabledScriptIds: Set<String>,
    onToggleScript: (String) -> Unit,
) {
    var regexExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = asset.characterName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.ext_asset_id, asset.characterId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val worldLabel = if (asset.worldBookId.isNullOrBlank()) {
                    stringResource(R.string.ext_worldbook_none)
                } else {
                    stringResource(R.string.ext_worldbook_imported)
                }
                AssistChip(onClick = {}, label = { Text(worldLabel) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.ext_regex_count, asset.regexScripts)) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.ext_th_scripts_count, asset.tavernHelperScripts)) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.ext_data_count, asset.tavernHelperData)) })
            }
            if (asset.regexScriptSummaries.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { regexExpanded = !regexExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (regexExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ext_regex_scripts_header, asset.regexScriptSummaries.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (regexExpanded) stringResource(R.string.ext_collapse) else stringResource(R.string.ext_expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (regexExpanded) {
                    asset.regexScriptSummaries.forEach { script ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = script.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Switch(
                                checked = script.enabled,
                                onCheckedChange = { onToggleScript(script.id) },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAssetState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ext_asset_empty_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.ext_asset_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
