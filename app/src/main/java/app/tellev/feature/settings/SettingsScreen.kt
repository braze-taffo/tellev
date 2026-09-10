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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.feature.update.UpdateViewModel
import app.tellev.util.UriUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    onOpenProviderSettings: () -> Unit,
    providerDetailsOnly: Boolean = false,
    onOpenImageGenSettings: () -> Unit = {},
    imageGenDetailsOnly: Boolean = false,
    presetFocusRequest: Int = 0,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val versionName = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    }
    var editingPreset by remember { mutableStateOf<GenerationPreset?>(null) }
    var selectedPresetCategory by remember { mutableStateOf(PresetCategory.OpenAi) }

    var showPresetDialog by remember { mutableStateOf(false) }
    var showAddSecretDialog by remember { mutableStateOf(false) }
    var showPersonaDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var pendingPresetImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPresetExport by remember { mutableStateOf<GenerationPreset?>(null) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var showComfyWorkflowDialog by remember { mutableStateOf(false) }
    var showComfyParamsDialog by remember { mutableStateOf(false) }
    var showLocalParamsDialog by remember { mutableStateOf(false) }
    var showNovelAiParamsDialog by remember { mutableStateOf(false) }
    var novelAiTokenVisible by remember { mutableStateOf(false) }
    var pendingDeleteConfigId by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.info) {
        state.info?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    LaunchedEffect(presetFocusRequest, state.isLoading, providerDetailsOnly) {
        if (presetFocusRequest > 0 && !state.isLoading && !providerDetailsOnly) {
            listState.animateScrollToItem(3)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            providerDetailsOnly -> "模型服务配置"
                            imageGenDetailsOnly -> "生图设置"
                            else -> "设置"
                        },
                    )
                },
                navigationIcon = {
                    if (providerDetailsOnly || imageGenDetailsOnly) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                if (imageGenDetailsOnly) {
                    imageGenDetailsItems(
                        state = state,
                        viewModel = viewModel,
                        onOpenComfyWorkflowDialog = { showComfyWorkflowDialog = true },
                        onOpenComfyParamsDialog = { showComfyParamsDialog = true },
                        onOpenLocalParamsDialog = { showLocalParamsDialog = true },
                        onOpenNovelAiParamsDialog = { showNovelAiParamsDialog = true },
                        novelAiTokenVisible = novelAiTokenVisible,
                        onToggleNovelAiTokenVisible = { novelAiTokenVisible = !novelAiTokenVisible },
                    )
                } else if (providerDetailsOnly) {
                    providerDetailsItems(
                        state = state,
                        viewModel = viewModel,
                        apiKeyVisible = apiKeyVisible,
                        onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                        onOpenAdvancedDialog = { showAdvancedDialog = true },
                        onDeleteCustomConfigClick = { pendingDeleteConfigId = it },
                    )
                } else {
                    item(key = "provider_quick_switch") {
                        ProviderQuickSwitchCard(
                            state = state,
                            onSelect = viewModel::activateProvider,
                            onManage = onOpenProviderSettings,
                        )
                    }

                    item(key = "provider_quick_divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    item(key = "imagegen_entry") {
                        ImageGenEntryCard(
                            state = state,
                            onClick = onOpenImageGenSettings,
                        )
                    }

                    item(key = "imagegen_entry_divider") {
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

                    secretSectionItems(
                        state = state,
                        onAddSecret = { showAddSecretDialog = true },
                        onDeleteSecret = { viewModel.deleteSecret(it) },
                    )

                    appearanceSectionItems(
                        state = state,
                        onSetThemeMode = viewModel::setThemeMode,
                        onSetThemeAccent = viewModel::setThemeAccent,
                        onSetChatBubbleAlpha = viewModel::setChatBubbleAlpha,
                    )

                    backupSectionItems(
                        onExportClick = { showExportDialog = true },
                        onImportClick = { showImportDialog = true },
                    )

                    aboutSectionItems(
                        versionName = versionName,
                        updateState = updateState,
                        updateViewModel = updateViewModel,
                    )
                }
            }
        }
    }

    // Preset creation dialog
    if (!providerDetailsOnly && showPresetDialog) {
        PresetCreationDialog(
            providers = state.providers.map { it.id to it.displayName },
            onDismiss = { showPresetDialog = false },
            onSave = { preset ->
                viewModel.savePreset(preset)
                showPresetDialog = false
            },
        )
    }

    if (!providerDetailsOnly) editingPreset?.let { preset ->
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

    if (!providerDetailsOnly) pendingPresetImportUri?.let { uri ->
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

    if (!providerDetailsOnly && showAddSecretDialog) {
        AddSecretDialog(
            onDismiss = { showAddSecretDialog = false },
            onSave = { key, value ->
                viewModel.addSecret(key, value)
                showAddSecretDialog = false
            },
        )
    }

    if (!providerDetailsOnly && showPersonaDialog) {
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

    if (!providerDetailsOnly && showExportDialog) {
        BackupExportDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = {
                exportLauncher.launch("tellev-backup-${System.currentTimeMillis()}.zip")
                showExportDialog = false
            },
        )
    }

    if (!providerDetailsOnly && showImportDialog) {
        BackupImportDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = {
                importLauncher.launch("application/zip")
                showImportDialog = false
            },
        )
    }

    if (showAdvancedDialog) {
        CompatibilityAdvancedDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showAdvancedDialog = false },
        )
    }

    if (showComfyWorkflowDialog) {
        ComfyWorkflowDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showComfyWorkflowDialog = false },
        )
    }

    if (showComfyParamsDialog) {
        ComfyParamsDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showComfyParamsDialog = false },
        )
    }

    if (showLocalParamsDialog) {
        LocalDreamParamsDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showLocalParamsDialog = false },
        )
    }

    if (showNovelAiParamsDialog) {
        NovelAiImageParamsDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showNovelAiParamsDialog = false },
        )
    }

    pendingDeleteConfigId?.let { configId ->
        val configName = state.customConfigs.firstOrNull { it.id == configId }?.name ?: "该配置"
        AlertDialog(
            onDismissRequest = { pendingDeleteConfigId = null },
            title = { Text("删除自定义配置") },
            text = { Text("确定删除“$configName”吗？该配置的接口地址与密钥将被清除，且无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomConfig(configId)
                        pendingDeleteConfigId = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteConfigId = null }) {
                    Text("取消")
                }
            },
        )
    }
}
