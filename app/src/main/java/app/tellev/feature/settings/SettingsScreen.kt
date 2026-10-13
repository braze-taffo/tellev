package app.tellev.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.guide.GuideKind
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.feature.update.UpdateViewModel
import app.tellev.util.UriUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    onOpenModelServices: () -> Unit,
    onOpenUsageStats: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    onOpenPromptOptimizer: () -> Unit = {},
    presetFocusRequest: Int = 0,
    onOpenGuide: (GuideKind) -> Unit = {},
    // 模型服务弹层打开时 settingsState.info/error 由弹层内联反馈消费（弹层没有
    // snackbar），本页跳过消费，避免把弹层要展示的反馈先吞掉。
    suppressFeedbackSnackbar: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val versionUnknown = stringResource(R.string.setscreen_version_unknown)
    val versionName = remember(context, versionUnknown) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: versionUnknown
    }
    var editingPreset by remember { mutableStateOf<GenerationPreset?>(null) }
    var selectedPresetCategory by remember { mutableStateOf(PresetCategory.OpenAi) }

    var showPresetDialog by remember { mutableStateOf(false) }
    var showAddSecretDialog by remember { mutableStateOf(false) }
    var showPersonaDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingPresetImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPresetExport by remember { mutableStateOf<GenerationPreset?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importBackup(context, it)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportBackup(context, it)
        }
    }

    val presetImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { pendingPresetImportUri = it }
    }

    val presetExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val preset = pendingPresetExport
        pendingPresetExport = null
        if (uri != null && preset != null) viewModel.exportPreset(context, uri, preset)
    }

    LaunchedEffect(state.selectedProviderId) {
        selectedPresetCategory = when (state.selectedProviderId) {
            "textgen-webui", "ollama", "llama-cpp" -> PresetCategory.TextGen
            "kobold", "koboldcpp", "horde" -> PresetCategory.Kobold
            "novelai" -> PresetCategory.NovelAi
            else -> PresetCategory.OpenAi
        }
    }

    // 模型服务弹层打开期间反馈归弹层（内联展示），这里跳过消费；弹层关闭后
    // 若仍有残留，key 变化会重启本 effect 再走一遍消费。
    LaunchedEffect(state.error, suppressFeedbackSnackbar) {
        if (!suppressFeedbackSnackbar) {
            state.error?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    LaunchedEffect(state.info, suppressFeedbackSnackbar) {
        if (!suppressFeedbackSnackbar) {
            state.info?.let {
                snackbarHostState.showSnackbar(it)
                viewModel.clearInfo()
            }
        }
    }

    LaunchedEffect(presetFocusRequest, state.isLoading) {
        if (presetFocusRequest > 0 && !state.isLoading) {
            settingsSection = 0
            // 目标 index 5 = 预设分组头；其上依次：使用统计卡、优化提示词卡、
            // 模型服务卡与分隔线、预设分隔线。
            listState.animateScrollToItem(5)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setscreen_title_settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (state.isLoading && state.providers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                val categories = listOf(R.string.ui_settings_models, R.string.ui_settings_identity,
                    R.string.ui_settings_appearance, R.string.ui_settings_data)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEachIndexed { index, label ->
                        FilterChip(
                            selected = settingsSection == index,
                            onClick = { settingsSection = index; scope.launch { listState.scrollToItem(0) } },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                    if (settingsSection == 0) {
                    item(key = "usage_stats_entry") {
                        UsageStatsEntryCard(onClick = onOpenUsageStats)
                    }

                    item(key = "dsh_prompt_optimizer_entry") {
                        // 优化提示词（写作助手）设置入口。
                        PromptOptimizerEntryCard(onClick = onOpenPromptOptimizer)
                    }

                    item(key = "provider_quick_switch") {
                        ProviderQuickSwitchCard(
                            state = state,
                            onSelect = viewModel::activateProvider,
                            onManage = onOpenModelServices,
                        )
                    }

                    item(key = "provider_quick_divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    presetSectionItems(
                        state = state,
                        selectedPresetCategory = selectedPresetCategory,
                        onSelectCategory = { selectedPresetCategory = it },
                        onImportClick = { presetImportLauncher.launch("*/*") },
                        onCreateClick = { showPresetDialog = true },
                        onSelectPreset = { viewModel.selectPreset(it) },
                        onExportPreset = {
                            pendingPresetExport = it
                            presetExportLauncher.launch("${it.id}.json")
                        },
                        onDeletePreset = { viewModel.deletePreset(it.id, it.providerType) },
                        onEditPreset = { editingPreset = it },
                    )
                    secretSectionItems(
                        state = state,
                        onAddSecret = { showAddSecretDialog = true },
                        onDeleteSecret = { viewModel.deleteSecret(it) },
                    )

                    item(key = "cleartext_setting") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.setnet_cleartext),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.setnet_cleartext_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.allowRemoteCleartext,
                                onCheckedChange = viewModel::setAllowRemoteCleartext,
                            )
                        }
                    }
                    }

                    if (settingsSection == 1) {
                    personaSectionItems(
                        state = state,
                        onAddPersona = {
                            editingPersona = null
                            showPersonaDialog = true
                        },
                        onEditPersona = {
                            editingPersona = it
                            showPersonaDialog = true
                        },
                        onDeletePersona = { viewModel.deletePersona(it) },
                    )
                    }

                    if (settingsSection == 2) {
                    appearanceSectionItems(
                        state = state,
                        onSetThemeMode = viewModel::setThemeMode,
                        onPreviewChatBubbleAlpha = viewModel::previewChatBubbleAlpha,
                        onPersistChatBubbleAlpha = { viewModel.setChatBubbleAlpha(state.chatBubbleAlpha) },
                        onPreviewChatFontSizeSp = viewModel::previewChatFontSizeSp,
                        onPersistChatFontSizeSp = { viewModel.setChatFontSizeSp(state.chatFontSizeSp) },
                    )

                    languageSectionItems(
                        state = state,
                        onSetLanguage = { tag ->
                            viewModel.setLanguage(tag)
                            (context as? android.app.Activity)?.recreate()
                        },
                    )
                    }

                    if (settingsSection == 3) {
                    // 拓展并入设置：脚本、正则、记忆等扩展能力统一从这里进。
                    item(key = "extensions_entry") {
                        ExtensionsEntryCard(onClick = onOpenExtensions)
                    }

                    backupSectionItems(
                        onExportClick = { showExportDialog = true },
                        onImportClick = { showImportDialog = true },
                    )

                    aboutSectionItems(
                        versionName = versionName,
                        updateState = updateState,
                        updateViewModel = updateViewModel,
                        onOpenGuide = onOpenGuide,
                    )
                    }
            }
            }
        }
    }

    // Preset creation dialog
    if (showPresetDialog) {
        PresetCreationDialog(
            providers = state.providers.map { it.id to it.displayName },
            onDismiss = { showPresetDialog = false },
            onSave = { preset ->
                viewModel.savePreset(preset)
                showPresetDialog = false
            },
        )
    }

    editingPreset?.let { preset ->
        PresetEditDialog(
            preset = preset,
            onDismiss = { editingPreset = null },
            onSave = {
                viewModel.savePreset(it)
                editingPreset = null
            },
            onSaveAs = { changed, name ->
                viewModel.copyPreset(changed, name)
                editingPreset = null
            },
            onRename = { changed, name ->
                viewModel.renamePreset(changed, name)
                editingPreset = null
            },
        )
    }

    pendingPresetImportUri?.let { uri ->
        val fileName = remember(uri) { UriUtils.resolveDisplayName(context, uri) }
            ?: uri.lastPathSegment
            ?: "preset.json"
        PresetImportCategoryDialog(
            fileName = fileName,
            initialCategory = when (selectedPresetCategory) {
                PresetCategory.OpenAi -> "openai"
                PresetCategory.TextGen -> "textgen"
                PresetCategory.Kobold -> "kobold"
                PresetCategory.NovelAi -> "novelai"
            },
            onDismiss = { pendingPresetImportUri = null },
            onConfirm = { category ->
                viewModel.importPreset(context, uri, category)
                pendingPresetImportUri = null
            },
        )
    }

    if (showAddSecretDialog) {
        AddSecretDialog(
            onDismiss = { showAddSecretDialog = false },
            onSave = { key, value ->
                viewModel.addSecret(key, value)
                showAddSecretDialog = false
            },
        )
    }

    if (showPersonaDialog) {
        PersonaEditDialog(
            existing = editingPersona,
            onDismiss = {
                showPersonaDialog = false
                editingPersona = null
            },
            onSave = { name, description ->
                val editing = editingPersona
                if (editing == null) {
                    viewModel.addPersona(name, description)
                } else {
                    viewModel.updatePersona(editing.id, name, description)
                }
                showPersonaDialog = false
                editingPersona = null
            },
        )
    }

    if (showExportDialog) {
        BackupExportDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = {
                exportLauncher.launch("tellev-backup-${System.currentTimeMillis()}.zip")
                showExportDialog = false
            },
        )
    }

    if (showImportDialog) {
        BackupImportDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = {
                importLauncher.launch("application/zip")
                showImportDialog = false
            },
        )
    }
}


/** 优化提示词设置入口卡。 */
@Composable
private fun PromptOptimizerEntryCard(onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.dsh_optimize_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
