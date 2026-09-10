package app.tellev.feature.world

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.core.model.WorldBook
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    fontWeight = FontWeight.SemiBold,
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
            )

            Spacer(modifier = Modifier.padding(horizontal = 4.dp))

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除世界书",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除世界书") },
            text = { Text("确定要删除世界书「${book.name}」吗？其中的所有条目都将被删除。") },
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
