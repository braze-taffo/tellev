package app.tellev.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.tellev.core.model.Persona
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import app.tellev.feature.update.UpdateUiState
import app.tellev.feature.update.UpdateViewModel
import app.tellev.ui.theme.ThemeMode
import app.tellev.util.UriUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    onOpenProviderSettings: () -> Unit,
    providerDetailsOnly: Boolean = false,
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
    val uriHandler = LocalUriHandler.current
    val bilibiliProfileUrl = "https://space.bilibili.com/499259948"

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
                title = { Text(if (providerDetailsOnly) "模型服务配置" else "设置") },
                navigationIcon = {
                    if (providerDetailsOnly) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                if (!providerDetailsOnly) {
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
                }

                if (providerDetailsOnly) {
                item(key = "provider_selector") {
                    val isCustom = ProviderConfigPersistence.isCustomConfigId(state.selectedProviderId)
                    val selectedLabel = if (isCustom) {
                        state.customConfigs.firstOrNull {
                            it.id == ProviderConfigPersistence.customIdFrom(state.selectedProviderId)
                        }?.name ?: "自定义配置"
                    } else {
                        state.providers.find { it.id == state.selectedProviderId }?.displayName
                            ?: state.selectedProviderId
                    }
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("服务商") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            // Built-in providers; the openai-compatible slot is
                            // superseded by the named custom configs below.
                            state.providers
                                .filter { it.id != ProviderCatalog.OPENAI_COMPATIBLE }
                                .forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            viewModel.selectProvider(provider.id)
                                            expanded = false
                                        },
                                    )
                                }
                            if (state.customConfigs.isNotEmpty()) {
                                HorizontalDivider()
                                state.customConfigs.forEach { config ->
                                    DropdownMenuItem(
                                        text = { Text(config.name) },
                                        onClick = {
                                            viewModel.selectProvider(
                                                ProviderConfigPersistence.selectedIdFor(config.id)
                                            )
                                            expanded = false
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("新建自定义配置") },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                },
                                onClick = {
                                    viewModel.createCustomConfig()
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                item(key = "provider_custom_name") {
                    if (ProviderConfigPersistence.isCustomConfigId(state.selectedProviderId)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = state.customConfigName,
                                onValueChange = { viewModel.updateCustomConfigName(it) },
                                label = { Text("配置名称") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            IconButton(
                                onClick = {
                                    pendingDeleteConfigId =
                                        ProviderConfigPersistence.customIdFrom(state.selectedProviderId)
                                },
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除该配置",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                if (state.isManagedProvider) {
                    item(key = "provider_managed_notice") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("免费测试通道", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "接口与认证已由此测试版内置，无需填写配置。测试结束后该通道可能随时关闭。",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                item(key = "provider_base_url") {
                    if (!state.isManagedProvider) {
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = { viewModel.updateBaseUrl(it) },
                            label = { Text("接口地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://api.example.com") },
                        )
                    }
                }

                item(key = "provider_api_key") {
                    if (!state.isManagedProvider) {
                        OutlinedTextField(
                            value = state.apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            label = { Text("API 密钥") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (apiKeyVisible) "隐藏 API 密钥" else "显示 API 密钥",
                                    )
                                }
                            },
                        )
                    }
                }

                item(key = "provider_model") {
                    if (!state.isManagedProvider) {
                    var modelMenuExpanded by remember { mutableStateOf(false) }
                    val availableModels = remember(state.availableModels) {
                        state.availableModels.distinct()
                    }
                    val modelFilter = state.model.takeUnless { current ->
                        availableModels.any { it == current }
                    }.orEmpty()
                    val matchingModels = remember(availableModels, modelFilter) {
                        if (modelFilter.isBlank()) {
                            availableModels
                        } else {
                            availableModels.filter { it.contains(modelFilter, ignoreCase = true) }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = modelMenuExpanded && matchingModels.isNotEmpty(),
                        onExpandedChange = {
                            modelMenuExpanded = it && availableModels.isNotEmpty()
                        },
                    ) {
                        OutlinedTextField(
                            value = state.model,
                            onValueChange = {
                                viewModel.updateModel(it)
                                modelMenuExpanded = availableModels.isNotEmpty()
                            },
                            label = { Text("模型") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryEditable),
                            singleLine = true,
                            placeholder = { Text("可选择或手动填写模型 ID") },
                            trailingIcon = {
                                if (availableModels.isNotEmpty()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                                }
                            },
                            supportingText = {
                                Text(
                                    if (availableModels.isEmpty()) {
                                        "当前服务未返回模型列表，可手动填写"
                                    } else {
                                        "可输入筛选，已获取 ${availableModels.size} 个模型"
                                    },
                                )
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = modelMenuExpanded && matchingModels.isNotEmpty(),
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            matchingModels.take(100).forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        viewModel.updateModel(model)
                                        modelMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    }
                }

                if (ProviderConfigPersistence.hasAdvancedSettings(state.selectedProviderId)) {
                    item(key = "provider_advanced") {
                        OutlinedButton(
                            onClick = { showAdvancedDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("高级设置")
                        }
                    }
                }

                item(key = "provider_actions") {
                    if (state.isManagedProvider) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.testConnection() },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isTesting,
                            ) {
                                if (state.isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (state.isTesting) "测试中..." else "测试连接")
                            }
                            FilledTonalButton(
                                onClick = { viewModel.saveProviderConfig() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("设为当前通道")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.testConnection() },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isTesting,
                            ) {
                                if (state.isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (state.isTesting) "测试中..." else "测试连接")
                            }
                            FilledTonalButton(
                                onClick = { viewModel.saveProviderConfig() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("保存")
                            }
                        }
                    }
                }

                if (state.providerStatus != null) {
                    item(key = "provider_status") {
                        val status = state.providerStatus!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (status.available) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (status.available) "已连接" else "连接失败",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (status.available) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status.available) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                }

                if (!providerDetailsOnly) {
                item(key = "divider_1") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Generation Presets Section
                item(key = "preset_header") {
                    SectionHeader(
                        icon = Icons.Default.Settings,
                        title = "生成预设",
                        secondaryAction = {
                            // Use */* so .json files stay selectable: many file
                            // managers don't tag them as application/json and SAF
                            // would grey them out.
                            presetImportLauncher.launch("*/*")
                        },
                        action = {
                            showPresetDialog = true
                        },
                    )
                }
                item(key = "preset_categories") {
                    val categories = listOf(
                        PresetCategory.OpenAi to "OpenAI",
                        PresetCategory.TextGen to "TextGen",
                        PresetCategory.Kobold to "Kobold",
                        PresetCategory.NovelAi to "NovelAI",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.chunked(2).forEach { rowCategories ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowCategories.forEach { (category, label) ->
                                    if (category == selectedPresetCategory) {
                                        FilledTonalButton(
                                            onClick = { selectedPresetCategory = category },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(label) }
                                    } else {
                                        OutlinedButton(
                                            onClick = { selectedPresetCategory = category },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(label) }
                                    }
                                }
                            }
                        }
                    }
                }

                val visiblePresets = state.presets.filter { it.category == selectedPresetCategory }



                if (visiblePresets.isEmpty()) {
                    item(key = "preset_empty") {
                        Text(
                            text = "暂无预设。新建一个预设来配置生成参数。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(visiblePresets, key = { "preset_${it.category.name}_${it.id}" }) { preset ->
                        PresetCard(
                            preset = preset,
                            selected = state.selectedPresetNames[preset.category] == preset.id,
                            onSelect = { viewModel.selectPreset(preset) },
                            onExport = {
                                pendingPresetExport = preset
                                presetExportLauncher.launch("${preset.id}.json")
                            },
                            onDelete = { viewModel.deletePreset(preset.id, preset.providerType) },
                            onEdit = { editingPreset = preset },
                        )
                    }
                }

                item(key = "divider_2") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Persona Management Section
                item(key = "persona_header") {
                    SectionHeader(
                        icon = Icons.Default.Person,
                        title = "人设",
                        action = {
                            editingPersona = null
                            showPersonaDialog = true
                        },
                    )
                }

                if (state.personas.isEmpty()) {
                    item(key = "persona_empty") {
                        Text(
                            text = "暂无人设。新建一个来设定你的身份（用于 {{user}} 名字与描述）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.personas, key = { "persona_${it.id}" }) { persona ->
                        PersonaCard(
                            persona = persona,
                            onEdit = {
                                editingPersona = persona
                                showPersonaDialog = true
                            },
                            onDelete = { viewModel.deletePersona(persona.id) },
                        )
                    }
                }

                // Secrets Management Section
                item(key = "secrets_header") {
                    SectionHeader(
                        icon = Icons.Default.Key,
                        title = "密钥",
                        action = {
                            showAddSecretDialog = true
                        },
                    )
                }

                if (state.secretIds.isEmpty()) {
                    item(key = "secrets_empty") {
                        Text(
                            text = "暂无保存的密钥。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.secretIds, key = { "secret_$it" }) { secretId ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = secretId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { viewModel.deleteSecret(secretId) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "divider_3") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Theme Section
                item(key = "theme_header") {
                    SectionHeader(
                        icon = Icons.Default.DarkMode,
                        title = "主题",
                    )
                }

                item(key = "theme_options") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeOption(
                            icon = Icons.Default.LightMode,
                            label = "浅色",
                            selected = state.themeMode == ThemeMode.Light,
                            onClick = { viewModel.setThemeMode(ThemeMode.Light) },
                        )
                        ThemeOption(
                            icon = Icons.Default.DarkMode,
                            label = "深色",
                            selected = state.themeMode == ThemeMode.Dark,
                            onClick = { viewModel.setThemeMode(ThemeMode.Dark) },
                        )
                        ThemeOption(
                            icon = Icons.Default.PhoneAndroid,
                            label = "跟随系统",
                            selected = state.themeMode == ThemeMode.System,
                            onClick = { viewModel.setThemeMode(ThemeMode.System) },
                        )
                    }
                }

                item(key = "divider_4") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // Backup Section
                item(key = "backup_header") {
                    SectionHeader(
                        icon = Icons.Default.Download,
                        title = "备份",
                    )
                }

                item(key = "backup_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { showExportDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导出备份")
                        }
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入备份")
                        }
                    }
                }

                item(key = "divider_5") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                // About Section
                item(key = "about_section") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "关于",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                text = "tellev v$versionName",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            UpdateStatus(
                                state = updateState,
                                onCheck = updateViewModel::checkNow,
                                onUpdate = updateViewModel::downloadAndInstall,
                            )
                            Text(
                                text = "基于 SillyTavern 的原生 Android 客户端。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "发布许可",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "tellev 继续以 GNU Affero General Public License v3.0（AGPL-3.0）发布。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "你可以自由使用、复制、修改和分发本程序；分发修改版或提供网络服务时，应按 AGPL-3.0 提供相应源代码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "tellev 名称、图标和作者信息仅用于官方版本展示，未经授权不得用于冒充官方版本或误导性商业分发。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "作者",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "B站：迷迭香のねこ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { uriHandler.openUri(bilibiliProfileUrl) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("打开作者 B 站主页")
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
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

    // Preset import: after a JSON file is picked, ask the user which ST preset
    // category it belongs to before writing it to the matching directory.
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

    // Add secret dialog
    if (!providerDetailsOnly && showAddSecretDialog) {
        AddSecretDialog(
            onDismiss = { showAddSecretDialog = false },
            onSave = { key, value ->
                viewModel.addSecret(key, value)
                showAddSecretDialog = false
            },
        )
    }

    // Persona edit dialog
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

    // Export confirmation dialog
    if (!providerDetailsOnly && showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出备份") },
            text = { Text("将创建一个 ZIP 备份。除非后续明确支持，已保存的密钥不会写入备份。请选择保存位置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        exportLauncher.launch("tellev-backup-${System.currentTimeMillis()}.zip")
                        showExportDialog = false
                    },
                ) {
                    Text("导出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Import confirmation dialog
    if (!providerDetailsOnly && showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入备份") },
            text = { Text("警告：导入备份会覆盖当前所有数据，且无法撤销。是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        importLauncher.launch("application/zip")
                        showImportDialog = false
                    },
                ) {
                    Text("导入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Advanced OpenAI-compatible protocol settings (2nd-level).
    if (showAdvancedDialog) {
        CompatibilityAdvancedDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showAdvancedDialog = false },
        )
    }

    // Confirm before deleting a custom config (holds its own key/url).
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

internal data class ProviderSwitchOption(
    val id: String,
    val label: String,
)

internal fun providerSwitchOptions(state: SettingsUiState): List<ProviderSwitchOption> {
    val builtIns = state.providers
        .filter { provider ->
            provider.id != ProviderCatalog.OPENAI_COMPATIBLE || state.customConfigs.isEmpty()
        }
        .map { ProviderSwitchOption(it.id, it.displayName) }
    val custom = state.customConfigs.map {
        ProviderSwitchOption(ProviderConfigPersistence.selectedIdFor(it.id), it.name)
    }
    return builtIns + custom
}

internal fun selectedProviderLabel(state: SettingsUiState): String {
    val id = state.selectedProviderId
    return if (ProviderConfigPersistence.isCustomConfigId(id)) {
        state.customConfigs.firstOrNull {
            it.id == ProviderConfigPersistence.customIdFrom(id)
        }?.name ?: "自定义配置"
    } else {
        state.providers.firstOrNull { it.id == id }?.displayName ?: id
    }
}

internal fun effectiveUnsupportedPresetFields(preset: GenerationPreset): Set<String> {
    val applied = setOf(
        "name", "temperature", "temp", "temp_openai", "top_p", "topP", "top_k", "topK",
        "top_a", "topA", "min_p", "minP", "repetition_penalty", "rep_pen",
        "repetition_penalty_range", "rep_pen_range", "presence_penalty", "frequency_penalty",
        "seed", "stop", "openai_max_context", "max_context", "context_length",
        "openai_max_tokens", "max_tokens", "maxTokens", "max_new_tokens", "prompts",
        "prompts_unused", "promptsUnused", "prompt_order", "extensions", "names_behavior",
        "new_chat_prompt", "new_example_chat_prompt", "squash_system_messages", "assistant_prefill",
    )
    val routing = setOf(
        "chat_completion_source", "openai_model", "claude_model", "openrouter_model",
        "custom_model", "custom_url", "reverse_proxy", "proxy_password", "api_key", "model",
    )
    return preset.raw.keys - applied - routing
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderQuickSwitchCard(
    state: SettingsUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    val options = remember(state.providers, state.customConfigs) { providerSwitchOptions(state) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(icon = Icons.Default.Settings, title = "模型服务")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "当前使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedProviderLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.model.ifBlank { "尚未指定模型" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProviderLabel(state),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("快速切换配置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        supportingText = { Text("选择后立即用于下一次生成") },
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                trailingIcon = {
                                    if (option.id == state.selectedProviderId) {
                                        Text("当前", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    if (option.id != state.selectedProviderId) onSelect(option.id)
                                },
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("管理模型服务配置")
                }
            }
        }
    }
}

@Composable
private fun CompatibilitySwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CompatibilityAdvancedDialog(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Base URL 与路径分开配置；默认只发送兼容性较高的字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (
                    state.selectedProviderId == "deepseek" &&
                    state.model in setOf("deepseek-chat", "deepseek-reasoner")
                ) {
                    Text(
                        "该旧模型将于 2026-07-24 停用，请迁移到 deepseek-v4-flash 或 deepseek-v4-pro。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.modelsPath,
                    onValueChange = viewModel::updateModelsPath,
                    label = { Text("模型列表路径") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.compatibility.chatCompletionsPath,
                    onValueChange = viewModel::updateChatCompletionsPath,
                    label = { Text("Chat Completions 路径") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.compatibility.authHeader,
                        onValueChange = viewModel::updateAuthHeader,
                        label = { Text("鉴权 Header") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.compatibility.authScheme,
                        onValueChange = viewModel::updateAuthScheme,
                        label = { Text("鉴权前缀") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Bearer；可留空") },
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.maxTokensField,
                    onValueChange = viewModel::updateMaxTokensField,
                    label = { Text("输出长度字段") },
                    supportingText = { Text("max_tokens 或 max_completion_tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CompatibilitySwitch(
                    label = "发送 stream_options.include_usage",
                    checked = state.compatibility.includeUsage,
                    onCheckedChange = viewModel::updateIncludeUsage,
                )
                CompatibilitySwitch(
                    label = "启用模型列表接口",
                    checked = state.compatibility.supportsModelListing,
                    onCheckedChange = viewModel::updateSupportsModelListing,
                )
                CompatibilitySwitch(
                    label = "发送 top_k",
                    checked = state.compatibility.supportsTopK,
                    onCheckedChange = viewModel::updateSupportsTopK,
                )
                CompatibilitySwitch(
                    label = "启用 tools 字段",
                    checked = state.compatibility.supportsTools,
                    onCheckedChange = viewModel::updateSupportsTools,
                )
                CompatibilitySwitch(
                    label = "启用 reasoning 字段",
                    checked = state.compatibility.supportsReasoning,
                    onCheckedChange = viewModel::updateSupportsReasoning,
                )
                CompatibilitySwitch(
                    label = "启用图片消息",
                    checked = state.compatibility.supportsVision,
                    onCheckedChange = viewModel::updateSupportsVision,
                )
                OutlinedTextField(
                    value = state.extraHeadersJson,
                    onValueChange = viewModel::updateExtraHeadersJson,
                    label = { Text("附加 Headers（JSON）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = state.extraBodyJson,
                    onValueChange = viewModel::updateExtraBodyJson,
                    label = { Text("附加请求体（JSON）") },
                    supportingText = { Text("messages/model/stream 等核心字段不会被覆盖") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}


@Composable
private fun UpdateStatus(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val info = state.pendingUpdate
        when {
            info != null -> {
                Text(
                    text = "发现新版本 v${info.version}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "正在下载… ${(state.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.installStarted) "重新下载更新" else "下载更新")
                    }
                }
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在检查更新…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.error != null -> {
                Text(
                    text = "更新检查失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onCheck) { Text("重试") }
            }
            state.upToDate -> {
                Text(
                    text = "已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                TextButton(onClick = onCheck) { Text("检查更新") }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    action: (() -> Unit)? = null,
    secondaryAction: (() -> Unit)? = null,
    secondaryActionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.FileUpload,
    secondaryActionDescription: String = "导入",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (secondaryAction != null) {
            IconButton(
                onClick = secondaryAction,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    secondaryActionIcon,
                    contentDescription = secondaryActionDescription,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (action != null) {
            IconButton(
                onClick = action,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "添加",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun PresetCard(
    preset: GenerationPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = preset.providerType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "查看或编辑",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val regexCount = (preset.extensions["regex_scripts"] as? JsonArray)?.size ?: 0
            val unsupportedCount = effectiveUnsupportedPresetFields(preset).size
            Text(
                text = "有效摘要：${preset.category.name.lowercase()} · 提示词 ${preset.prompts.count { it.enabled }} · 正则 $regexCount · 未支持 $unsupportedCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(onClick = onExport, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("导出")
            }

            if (selected) {
                FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("当前使用") }
            } else {
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("设为当前") }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (preset.temperature != null) {
                    Text(
                        text = "温度：${"%.2f".format(preset.temperature)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preset.topP != null) {
                    Text(
                        text = "Top-P: ${"%.2f".format(preset.topP)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preset.maxTokens != null) {
                    Text(
                        text = "最大：${preset.maxTokens}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditDialog(
    preset: GenerationPreset,
    onDismiss: () -> Unit,
    onSave: (GenerationPreset) -> Unit,
    onSaveAs: (GenerationPreset, String) -> Unit,
    onRename: (GenerationPreset, String) -> Unit,
) {
    var temperature by remember(preset) { mutableStateOf(preset.temperature?.toString().orEmpty()) }
    var topP by remember(preset) { mutableStateOf(preset.topP?.toString().orEmpty()) }
    var topK by remember(preset) { mutableStateOf(preset.topK?.toString().orEmpty()) }
    var maxContext by remember(preset) { mutableStateOf(preset.maxContextTokens?.toString().orEmpty()) }
    var maxCompletion by remember(preset) {
        mutableStateOf((preset.maxCompletionTokens ?: preset.maxTokens)?.toString().orEmpty())
    }
    var rawText by remember(preset) { mutableStateOf(preset.raw.toString()) }
    var validationError by remember(preset) { mutableStateOf<String?>(null) }
    var targetName by remember(preset) { mutableStateOf("") }
    var prompts by remember(preset) { mutableStateOf(preset.prompts.sortedBy { it.order }) }

    fun editedPreset(): GenerationPreset? {
        val raw = runCatching { Json.parseToJsonElement(rawText) as? JsonObject }.getOrNull()
        if (raw == null) {
            validationError = "原始字段必须是有效的 JSON 对象"
            return null
        }
        return preset.copy(
            temperature = temperature.toDoubleOrNull(),
            topP = topP.toDoubleOrNull(),
            topK = topK.toIntOrNull(),
            maxContextTokens = maxContext.toIntOrNull(),
            maxCompletionTokens = maxCompletion.toIntOrNull(),
            maxTokens = maxCompletion.toIntOrNull(),
            prompts = prompts.mapIndexed { index, prompt -> prompt.copy(order = index) },
            raw = raw,
        )
    }

    fun updatePrompt(index: Int, transform: (app.tellev.core.model.PresetPrompt) -> app.tellev.core.model.PresetPrompt) {
        prompts = prompts.toMutableList().also { it[index] = transform(it[index]) }
    }

    fun movePrompt(from: Int, to: Int) {
        if (to !in prompts.indices) return
        prompts = prompts.toMutableList().also { list ->
            val item = list.removeAt(from)
            list.add(to, item)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查看与编辑预设：${preset.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "分类：${preset.category.name.lowercase()} · 文件：${preset.id}.json",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val regexCount = (preset.extensions["regex_scripts"] as? JsonArray)?.size ?: 0
                val preservedRoutingFields = preset.raw.keys.intersect(setOf(
                    "chat_completion_source", "openai_model", "claude_model", "openrouter_model",
                    "custom_model", "custom_url", "reverse_proxy", "proxy_password", "api_key", "model",
                ))
                val unsupportedFields = effectiveUnsupportedPresetFields(preset)
                Text(
                    text = "当前实际使用：${preset.category.name.lowercase()}；采样参数 ${listOfNotNull(preset.temperature, preset.topP, preset.topK).size} 项；启用提示词 ${preset.prompts.count { it.enabled }} 条；正则 $regexCount 条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preservedRoutingFields.isNotEmpty()) {
                    Text(
                        text = "已保留、未应用：${preservedRoutingFields.sorted().joinToString()}（预设不会切换服务商、接口、密钥或模型）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (unsupportedFields.isNotEmpty()) {
                    Text(
                        text = "尚未进入运行链路：${unsupportedFields.sorted().joinToString(limit = 16, truncated = "…")}。字段仍会原样保留和导出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = targetName,
                    onValueChange = { targetName = it },
                    label = { Text("另存为 / 重命名后的名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Temperature") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = topP,
                    onValueChange = { topP = it },
                    label = { Text("Top-P") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = topK,
                    onValueChange = { topK = it },
                    label = { Text("Top-K") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxContext,
                    onValueChange = { maxContext = it.filter(Char::isDigit) },
                    label = { Text("最大上下文 Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxCompletion,
                    onValueChange = { maxCompletion = it.filter(Char::isDigit) },
                    label = { Text("最大回复 Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        validationError = null
                    },
                    label = { Text("高级原始 JSON（未知字段会保留）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    supportingText = {
                        Text(validationError ?: "可直接查看或编辑供应商专有字段；保存前会校验 JSON 对象。")
                    },
                    isError = validationError != null,
                )
                if (prompts.isNotEmpty()) {
                    Text("提示词顺序", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "长按提示词卡片并上下拖动，也可使用上移/下移按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    prompts.forEachIndexed { index, prompt ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(prompt.identifier, index, prompts.size) {
                                    var accumulatedY = 0f
                                    val threshold = 72.dp.toPx()
                                    detectDragGesturesAfterLongPress(
                                        onDragEnd = { accumulatedY = 0f },
                                        onDragCancel = { accumulatedY = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            accumulatedY += amount.y
                                            when {
                                                accumulatedY <= -threshold && index > 0 -> {
                                                    movePrompt(index, index - 1)
                                                    accumulatedY = 0f
                                                }
                                                accumulatedY >= threshold && index < prompts.lastIndex -> {
                                                    movePrompt(index, index + 1)
                                                    accumulatedY = 0f
                                                }
                                            }
                                        },
                                    )
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(prompt.name.ifBlank { prompt.identifier }, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = prompt.enabled,
                                        onCheckedChange = { enabled ->
                                            updatePrompt(index) { it.copy(enabled = enabled) }
                                        },
                                    )
                                }
                                OutlinedTextField(
                                    value = prompt.role,
                                    onValueChange = { role -> updatePrompt(index) { it.copy(role = role) } },
                                    label = { Text("Role") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                if (prompt.content.isNotEmpty() || prompt.identifier !in setOf(
                                        "main", "worldInfoBefore", "worldInfoAfter", "charDescription",
                                        "charPersonality", "scenario", "personaDescription", "dialogueExamples", "chatHistory",
                                    )
                                ) {
                                    OutlinedTextField(
                                        value = prompt.content,
                                        onValueChange = { content -> updatePrompt(index) { it.copy(content = content) } },
                                        label = { Text("Content") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("In-chat", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = prompt.relative,
                                        onCheckedChange = { relative ->
                                            updatePrompt(index) { it.copy(relative = relative) }
                                        },
                                    )
                                    OutlinedTextField(
                                        value = prompt.depth.toString(),
                                        onValueChange = { value ->
                                            value.filter(Char::isDigit).toIntOrNull()?.let { depth ->
                                                updatePrompt(index) { it.copy(depth = depth) }
                                            }
                                        },
                                        label = { Text("Depth") },
                                        modifier = Modifier.width(96.dp),
                                        singleLine = true,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { movePrompt(index, index - 1) }, enabled = index > 0) {
                                        Text("上移")
                                    }
                                    TextButton(
                                        onClick = { movePrompt(index, index + 1) },
                                        enabled = index < prompts.lastIndex,
                                    ) { Text("下移") }
                                }
                            }
                        }
                    }
                    if (preset.promptsUnused.isNotEmpty()) {
                        Text(
                            "未使用提示词：${preset.promptsUnused.joinToString { it.name.ifBlank { it.identifier } }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editedPreset()?.let(onSave)
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(
                    onClick = { editedPreset()?.let { onSaveAs(it, targetName) } },
                    enabled = targetName.isNotBlank(),
                ) { Text("另存为") }
                TextButton(
                    onClick = { editedPreset()?.let { onRename(it, targetName) } },
                    enabled = targetName.isNotBlank(),
                ) { Text("重命名") }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PresetCreationDialog(
    providers: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (GenerationPreset) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(providers.firstOrNull()?.first ?: "") }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.9f) }
    var maxTokensText by remember { mutableStateOf("") }
    var providerExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建预设") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                ) {
                    OutlinedTextField(
                        value = providers.find { it.first == selectedProvider }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        providers.forEach { (id, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    selectedProvider = id
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = "温度：${"%.2f".format(temperature)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                }

                Column {
                    Text(
                        text = "Top-P: ${"%.2f".format(topP)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = topP,
                        onValueChange = { topP = it },
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                }

                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { value -> maxTokensText = value.filter(Char::isDigit) },
                    label = { Text("最大 Token 数") },
                    supportingText = { Text("留空则不由 tellev 限制输出长度") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            GenerationPreset(
                                id = name.trim()
                                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                                    .ifBlank { "preset" },
                                name = name.trim(),
                                providerType = selectedProvider,
                                category = when (selectedProvider) {
                                    "textgen-webui", "ollama", "llama-cpp" -> PresetCategory.TextGen
                                    "kobold", "koboldcpp", "horde" -> PresetCategory.Kobold
                                    "novelai" -> PresetCategory.NovelAi
                                    else -> PresetCategory.OpenAi
                                },
                                temperature = temperature.toDouble(),
                                topP = topP.toDouble(),
                                maxTokens = maxTokensText.toIntOrNull()?.takeIf { it > 0 },
                                maxCompletionTokens = maxTokensText.toIntOrNull()?.takeIf { it > 0 },
                            ),
                        )
                    }
                },
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetImportCategoryDialog(
    fileName: String,
    initialCategory: String,
    onDismiss: () -> Unit,
    onConfirm: (providerCategory: String) -> Unit,
) {
    // The four SillyTavern preset directories. Keys match FileStDataStore's
    // resolvePresetDirectory() mapping; values are user-facing labels.
    val categories = remember {
        listOf(
            "openai" to "OpenAI 兼容",
            "textgen" to "TextGen WebUI",
            "kobold" to "KoboldAI",
            "novelai" to "NovelAI",
        )
    }
    var selectedCategory by remember(initialCategory) {
        mutableStateOf(initialCategory.takeIf { candidate -> categories.any { it.first == candidate } } ?: "openai")
    }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入预设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "文件：$fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "选择该预设归属的服务商分类，决定它写入的目录与在聊天中可被选用的范围。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = categories.first { it.first == selectedCategory }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedCategory = id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedCategory) },
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AddSecretDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加密钥") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("键") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("值") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (key.isNotBlank() && value.isNotBlank()) {
                        onSave(key.trim(), value)
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun PersonaCard(
    persona: Persona,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = persona.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (persona.description.isNotBlank()) {
                    Text(
                        text = persona.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PersonaEditDialog(
    existing: Persona?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建人设" else "编辑人设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) onSave(name.trim(), description)
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
