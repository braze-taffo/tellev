package app.tellev.feature.characters

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.core.model.CharacterCard
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersListScreen(
    viewModel: CharactersViewModel,
    onCharacterClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.readBytes()
                    }
                    val fileName = UriUtils.resolveDisplayName(context, it)
                        ?: it.lastPathSegment
                        ?: "imported_character.json"
                    if (bytes != null) {
                        viewModel.importCharacter(bytes, fileName)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败：${e.message}")
                }
            }
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("角色") },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchActive) "关闭搜索" else "搜索",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch("*/*") },
                icon = { Icon(Icons.Default.FileUpload, contentDescription = "导入") },
                text = { Text("导入") },
            )
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
                            viewModel.search(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("按名称或标签搜索...") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.search("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "清除")
                                }
                            }
                        },
                    )
                }
            }

            Crossfade(
                targetState = state.isLoading,
                label = "loading_state",
            ) { isLoading ->
                if (isLoading && state.filteredCharacters.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.filteredCharacters.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "没有匹配的角色" else "暂无角色",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchQuery.isEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "点击“导入”添加角色卡",
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
                        items(
                            state.filteredCharacters,
                            key = { it.id },
                        ) { character ->
                            CharacterListItem(
                                character = character,
                                onClick = { onCharacterClick(character.id) },
                                onDuplicate = { viewModel.duplicateCharacter(character.id) },
                                onDelete = { viewModel.deleteCharacter(character.id) },
                                onExport = {
                                    scope.launch {
                                        val json = viewModel.exportCharacterToJson(character.id)
                                        if (json != null) {
                                            snackbarHostState.showSnackbar("已将 ${character.name} 导出为 JSON")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterListItem(
    character: app.tellev.core.model.CharacterSummary,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showContextMenu = true },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = character.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (character.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        character.tags.take(5).forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                        if (character.tags.size > 5) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "+${character.tags.size - 5}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                }
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            onDuplicate()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出 JSON") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            onExport()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showDeleteDialog = true
                            showContextMenu = false
                        },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除角色") },
            text = { Text("确定要删除“${character.name}”吗？此操作无法撤销。") },
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
fun CharacterDetailScreen(
    viewModel: CharactersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val character = state.selectedCharacter
    val snackbarHostState = remember { SnackbarHostState() }

    var name by remember(character?.id) { mutableStateOf(character?.name ?: "") }
    var description by remember(character?.id) { mutableStateOf(character?.description ?: "") }
    var personality by remember(character?.id) { mutableStateOf(character?.personality ?: "") }
    var scenario by remember(character?.id) { mutableStateOf(character?.scenario ?: "") }
    var firstMessage by remember(character?.id) { mutableStateOf(character?.firstMessage ?: "") }
    var exampleMessages by remember(character?.id) { mutableStateOf(character?.exampleMessages ?: "") }
    var creatorNotes by remember(character?.id) { mutableStateOf(character?.creatorNotes ?: "") }
    var tags by remember(character?.id) { mutableStateOf(character?.tags ?: emptyList()) }
    var newTag by remember { mutableStateOf("") }

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
                title = { Text(character?.name ?: "角色详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (character != null) {
                                viewModel.saveCharacter(
                                    character.copy(
                                        name = name,
                                        description = description,
                                        personality = personality,
                                        scenario = scenario,
                                        firstMessage = firstMessage,
                                        exampleMessages = exampleMessages,
                                        creatorNotes = creatorNotes,
                                        tags = tags,
                                    ),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (character == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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
                // Avatar placeholder
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "角色卡",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "ID: ${character.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Personality
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text("性格") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Scenario
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text("场景") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // First Message
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    label = { Text("开场白") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                // Example Messages
                OutlinedTextField(
                    value = exampleMessages,
                    onValueChange = { exampleMessages = it },
                    label = { Text("示例对话") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                // Creator Notes
                OutlinedTextField(
                    value = creatorNotes,
                    onValueChange = { creatorNotes = it },
                    label = { Text("创建者备注") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )

                // Tags
                Column {
                    Text(
                        text = "标签",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { tags = tags - tag },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除标签",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text("添加标签") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        FilledTonalButton(
                            onClick = {
                                if (newTag.isNotBlank()) {
                                    tags = tags + newTag.trim()
                                    newTag = ""
                                }
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加")
                        }
                    }
                }

                // Character Book section
                if (character.characterBook != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "角色书",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "“${character.characterBook.name}” · ${character.characterBook.entries.size} 条条目",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // Save button
                FilledTonalButton(
                    onClick = {
                        viewModel.saveCharacter(
                            character.copy(
                                name = name,
                                description = description,
                                personality = personality,
                                scenario = scenario,
                                firstMessage = firstMessage,
                                exampleMessages = exampleMessages,
                                creatorNotes = creatorNotes,
                                tags = tags,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存修改")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
