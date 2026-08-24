package app.tellev.feature.world

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBooksListScreen(
    viewModel: WorldViewModel,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { it.readBytes() }
                    } ?: error("无法读取所选文件")
                    val fileName = UriUtils.resolveDisplayName(context, selectedUri)
                        ?: selectedUri.lastPathSegment
                        ?: "imported_world_book.json"
                    viewModel.importBook(bytes, fileName)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入世界书失败：${e.message}")
                }
            }
        }
    }


    // Re-read the activation file every time this screen enters composition.
    // ChatViewModel.selectCharacter can write a new exclusive world-book
    // activation set while this (app-scoped) ViewModel retains its earlier
    // snapshot, so without this refresh the switches shown here would lag the
    // file by one selection.
    LaunchedEffect(Unit) {
        viewModel.loadBooks()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("世界书") },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "世界书设置")
                    }
                    // Use */* so .json files stay selectable (SAF MIME filtering
                    // greys them out in many file managers).
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导入世界书")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newBookName = ""
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "新建") },
                text = { Text("新建") },
            )
        },
        modifier = modifier,
    ) { padding ->
        Crossfade(
            targetState = state.isLoading && state.worldBooks.isEmpty(),
            label = "world_loading",
        ) { isLoading ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.worldBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无世界书",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "新建世界书来管理背景设定条目",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(state.worldBooks, key = { it.id }) { book ->
                        WorldBookListItem(
                            book = book,
                            activated = book.id !in state.disabledWorldIds,
                            onClick = { onBookClick(book.id) },
                            onToggleActivation = { viewModel.toggleWorldActivation(book.id) },
                            onDelete = { viewModel.deleteBook(book.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建世界书") },
            text = {
                OutlinedTextField(
                    value = newBookName,
                    onValueChange = { newBookName = it },
                    label = { Text("世界书名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newBookName.isNotBlank()) {
                            viewModel.createBook(newBookName.trim())
                            showCreateDialog = false
                        }
                    },
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showSettingsDialog) {
        val current = state.worldInfoSettings
        val currentPrompt = state.promptSettings
        var recursive by remember(current) { mutableStateOf(current.recursive) }
        var maxRecursionSteps by remember(current) { mutableStateOf(current.maxRecursionSteps.toString()) }
        var scanDepth by remember(current) { mutableStateOf(current.scanDepth.toString()) }
        var preferCharPrompt by remember(currentPrompt) { mutableStateOf(currentPrompt.preferCharacterPrompt) }
        var preferCharJailbreak by remember(currentPrompt) { mutableStateOf(currentPrompt.preferCharacterJailbreak) }
        var instructEnabled by remember(currentPrompt) { mutableStateOf(currentPrompt.instructEnabled) }
        var instructPresetName by remember(currentPrompt) { mutableStateOf(currentPrompt.instructPresetName) }
        val instructPresetOptions = remember(state.instructPresets) { state.instructPresets }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("世界书与提示词设置") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("递归扫描", modifier = Modifier.weight(1f))
                        Switch(checked = recursive, onCheckedChange = { recursive = it })
                    }
                    Text(
                        "激活的条目内容会再次参与关键词匹配（ST 的 world_info_recursive），" +
                            "延迟递归（delayUntilRecursion）的条目需要开启此项才会触发。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxRecursionSteps,
                        onValueChange = { maxRecursionSteps = it.filter(Char::isDigit) },
                        label = { Text("最大递归步数（0 = 不限）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scanDepth,
                        onValueChange = { scanDepth = it.filter(Char::isDigit) },
                        label = { Text("扫描深度（最近 N 条消息参与匹配）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("角色卡提示词", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("使用角色卡系统提示词", modifier = Modifier.weight(1f))
                        Switch(checked = preferCharPrompt, onCheckedChange = { preferCharPrompt = it })
                    }
                    Text(
                        "开启后使用角色卡的 data.system_prompt 替代默认系统提示词（ST 的 prefer_character_prompt）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("使用角色卡越狱提示", modifier = Modifier.weight(1f))
                        Switch(checked = preferCharJailbreak, onCheckedChange = { preferCharJailbreak = it })
                    }
                    Text(
                        "开启后注入角色卡的 data.post_history_instructions 到聊天末尾（ST 的 prefer_character_jailbreak）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Instruct 模式", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("启用 Instruct 格式化", modifier = Modifier.weight(1f))
                        Switch(checked = instructEnabled, onCheckedChange = { instructEnabled = it })
                    }
                    Text(
                        "为补全式 API（textgen/kobold 等）格式化消息序列。聊天补全 API 无需开启。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Instruct 预设", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (instructPresetName.isBlank()) "当前：内置 ChatML" else "当前：$instructPresetName",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (instructPresetOptions.isNotEmpty()) {
                        instructPresetOptions.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = instructPresetName == name,
                                    onClick = { instructPresetName = name },
                                )
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = instructPresetName.isBlank(),
                            onClick = { instructPresetName = "" },
                        )
                        Text("内置 ChatML", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveWorldInfoSettings(
                            current.copy(
                                recursive = recursive,
                                maxRecursionSteps = maxRecursionSteps.toIntOrNull() ?: 0,
                                scanDepth = (scanDepth.toIntOrNull() ?: current.scanDepth).coerceAtLeast(1),
                            ),
                        )
                        viewModel.savePromptSettings(
                            currentPrompt.copy(
                                preferCharacterPrompt = preferCharPrompt,
                                preferCharacterJailbreak = preferCharJailbreak,
                                instructEnabled = instructEnabled,
                                instructPresetName = instructPresetName,
                            ),
                        )
                        showSettingsDialog = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun WorldBookListItem(
    book: WorldBook,
    activated: Boolean,
    onClick: () -> Unit,
    onToggleActivation: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (activated) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${book.entries.size} 条条目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = activated,
                onCheckedChange = { onToggleActivation() },
                modifier = Modifier.height(24.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除世界书") },
            text = { Text("确定要删除“${book.name}”吗？其中的所有条目都会被移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorldBookDetailScreen(
    viewModel: WorldViewModel,
    onBack: () -> Unit,
    onEditEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val book = state.selectedBook
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(book?.name ?: "世界书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchActive) "关闭搜索" else "搜索条目",
                        )
                    }
                    if (book != null) {
                        IconButton(onClick = { viewModel.saveBook(book) }) {
                            Icon(Icons.Default.Save, contentDescription = "保存世界书")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (book != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onEditEntry("new")
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = "新建条目") },
                    text = { Text("新建条目") },
                )
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .animateContentSize(),
        ) {
            Crossfade(targetState = searchActive, label = "search_bar") { active ->
                if (active) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.searchEntries(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("按关键词或内容搜索条目...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.searchEntries("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "清除")
                                }
                            }
                        },
                    )
                }
            }

            if (book == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.selectionError == null) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.selectionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onBack) { Text("返回世界书列表") }
                        }
                    }
                }
            } else if (state.filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "没有匹配的条目" else "此世界书暂无条目",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (searchQuery.isEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击“新建条目”添加第一条设定",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(state.filteredEntries, key = { it.id }) { entry ->
                        WorldBookEntryItem(
                            entry = entry,
                            onClick = {
                                viewModel.selectEntry(entry.id)
                                onEditEntry(entry.id)
                            },
                            onToggleEnabled = {
                                val updatedEntry = entry.copy(enabled = !entry.enabled)
                                viewModel.saveEntry(book.id, updatedEntry)
                            },
                            onDelete = { viewModel.deleteEntry(book.id, entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldBookEntryItem(
    entry: WorldBookEntry,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val displayTitle = entry.keys.firstOrNull() ?: "未命名条目"

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (entry.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )

                Switch(
                    checked = entry.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    modifier = Modifier.height(24.dp),
                )
            }

            if (entry.keys.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    entry.keys.take(4).forEach { key ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.height(24.dp),
                        )
                    }
                    if (entry.keys.size > 4) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "+${entry.keys.size - 4}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.height(24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "P:${entry.priority}",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        "D:${entry.depth}",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (entry.constant) {
                    Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            "常驻",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (entry.selective) {
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "选择",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除条目",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除条目") },
            text = { Text("确定要删除这个条目吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookEntryEditScreen(
    viewModel: WorldViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val entry = state.selectedEntry
    val bookId = state.selectedBook?.id

    var keys by remember(entry?.id) { mutableStateOf(entry?.keys?.joinToString(", ") ?: "") }
    var secondaryKeys by remember(entry?.id) { mutableStateOf(entry?.secondaryKeys?.joinToString(", ") ?: "") }
    var content by remember(entry?.id) { mutableStateOf(entry?.content ?: "") }
    var enabled by remember(entry?.id) { mutableStateOf(entry?.enabled ?: true) }
    var selective by remember(entry?.id) { mutableStateOf(entry?.selective ?: false) }
    var constant by remember(entry?.id) { mutableStateOf(entry?.constant ?: false) }
    var priority by remember(entry?.id) { mutableStateOf(entry?.priority?.toString() ?: "0") }
    var insertionOrder by remember(entry?.id) { mutableStateOf(entry?.insertionOrder?.toString() ?: "100") }
    var depth by remember(entry?.id) { mutableStateOf(entry?.depth?.toString() ?: "4") }
    var position by remember(entry?.id) { mutableStateOf(entry?.position ?: 0) }
    var selectiveLogic by remember(entry?.id) { mutableStateOf(entry?.selectiveLogic ?: 0) }
    var useProbability by remember(entry?.id) { mutableStateOf(entry?.useProbability ?: false) }
    var probability by remember(entry?.id) { mutableStateOf(entry?.probability?.toString() ?: "100") }
    var role by remember(entry?.id) { mutableStateOf(entry?.role ?: 0) }
    var matchWholeWords by remember(entry?.id) { mutableStateOf(entry?.matchWholeWords ?: false) }
    var useRegex by remember(entry?.id) { mutableStateOf(entry?.useRegex ?: false) }
    var caseSensitive by remember(entry?.id) { mutableStateOf(entry?.caseSensitive ?: false) }
    var excludeRecursion by remember(entry?.id) { mutableStateOf(entry?.excludeRecursion ?: false) }
    var preventRecursion by remember(entry?.id) { mutableStateOf(entry?.preventRecursion ?: false) }
    var delayUntilRecursion by remember(entry?.id) { mutableStateOf((entry?.delayUntilRecursion ?: 0) > 0) }
    var delayUntilRecursionLevel by remember(entry?.id) { mutableStateOf((entry?.delayUntilRecursion ?: 1).coerceAtLeast(1).toString()) }
    var comment by remember(entry?.id) { mutableStateOf(entry?.comment ?: "") }

    fun buildUpdatedEntry(base: app.tellev.core.model.WorldBookEntry): app.tellev.core.model.WorldBookEntry {
        val parsedKeys = keys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val parsedSecondaryKeys = secondaryKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return base.copy(
            keys = parsedKeys,
            secondaryKeys = parsedSecondaryKeys,
            content = content,
            enabled = enabled,
            selective = selective,
            constant = constant,
            priority = priority.toIntOrNull() ?: 0,
            insertionOrder = insertionOrder.toIntOrNull() ?: 100,
            depth = depth.toIntOrNull() ?: 4,
            position = position,
            selectiveLogic = selectiveLogic,
            useProbability = useProbability,
            probability = probability.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            role = role,
            matchWholeWords = matchWholeWords,
            useRegex = useRegex,
            caseSensitive = caseSensitive,
            excludeRecursion = excludeRecursion,
            preventRecursion = preventRecursion,
            delayUntilRecursion = if (delayUntilRecursion) {
                (delayUntilRecursionLevel.toIntOrNull() ?: 1).coerceAtLeast(1)
            } else {
                0
            },
            comment = comment,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry?.content?.isBlank() == true || entry?.keys?.isEmpty() == true) "新建条目" else "编辑条目") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (entry != null && bookId != null) {
                                if (viewModel.saveEntry(bookId, buildUpdatedEntry(entry))) onBack()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "保存条目")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (entry == null || bookId == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (state.selectionError == null) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.selectionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onBack) { Text("返回世界书") }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Keys
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text("关键词（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Secondary Keys
                OutlinedTextField(
                    value = secondaryKeys,
                    onValueChange = { secondaryKeys = it },
                    label = { Text("次级关键词（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Content
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 20,
                )

                // Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择性触发", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = selective, onCheckedChange = { selective = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("常驻注入", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = constant, onCheckedChange = { constant = it })
                }

                // Number fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = priority,
                        onValueChange = { priority = it },
                        label = { Text("优先级") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = insertionOrder,
                        onValueChange = { insertionOrder = it },
                        label = { Text("插入顺序") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = depth,
                        onValueChange = { depth = it },
                        label = { Text("深度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                // 注入位置（ST 8 种 position）
                DropdownSelector(
                    label = "注入位置",
                    selected = position,
                    options = listOf(
                        0 to "角色前",
                        1 to "角色后",
                        2 to "作者注前",
                        3 to "作者注后",
                        4 to "@深度",
                        5 to "示例对话前",
                        6 to "示例对话后",
                        7 to "插槽",
                    ),
                    onSelected = { position = it },
                )

                // 选择性逻辑（仅 selective 时有意义）
                DropdownSelector(
                    label = "选择性逻辑",
                    selected = selectiveLogic,
                    enabled = selective,
                    options = listOf(
                        0 to "AND 任一匹配",
                        1 to "NOT 任一不匹配",
                        2 to "NOT 全不匹配",
                        3 to "AND 全部匹配",
                    ),
                    onSelected = { selectiveLogic = it },
                )

                // 角色（仅 @深度 时有意义）
                DropdownSelector(
                    label = "角色",
                    selected = role,
                    enabled = position == 4,
                    options = listOf(
                        0 to "System",
                        1 to "User",
                        2 to "Assistant",
                    ),
                    onSelected = { role = it },
                )

                // 概率触发
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("概率触发", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = useProbability, onCheckedChange = { useProbability = it })
                }
                if (useProbability) {
                    OutlinedTextField(
                        value = probability,
                        onValueChange = { probability = it.filter { c -> c.isDigit() } },
                        label = { Text("概率 (0-100)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // 匹配选项
                ToggleRow("全词匹配", matchWholeWords) { matchWholeWords = it }
                ToggleRow("正则匹配", useRegex) { useRegex = it }
                ToggleRow("区分大小写", caseSensitive) { caseSensitive = it }

                // 递归选项
                ToggleRow("排除递归（不喂入递归文本）", excludeRecursion) { excludeRecursion = it }
                ToggleRow("阻止递归（不触发后续激活）", preventRecursion) { preventRecursion = it }
                ToggleRow("延迟到递归轮", delayUntilRecursion) { delayUntilRecursion = it }
                if (delayUntilRecursion) {
                    OutlinedTextField(
                        value = delayUntilRecursionLevel,
                        onValueChange = { delayUntilRecursionLevel = it.filter(Char::isDigit) },
                        label = { Text("递归层级（第几轮递归开始可激活，需开启递归扫描）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 备注
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Save and Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    FilledTonalButton(
                        onClick = {
                            if (entry != null && bookId != null) {
                                viewModel.saveEntry(bookId, buildUpdatedEntry(entry))
                                onBack()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存条目")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelector(
    label: String,
    selected: Int,
    options: List<Pair<Int, String>>,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
