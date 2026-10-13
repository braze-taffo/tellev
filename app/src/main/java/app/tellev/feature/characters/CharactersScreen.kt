package app.tellev.feature.characters

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Save
import app.tellev.ui.CharacterCover
import app.tellev.ui.CharacterPortraitCard
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.CharacterCastBinding
import app.tellev.ui.CharacterCastPicker
import app.tellev.core.model.CharacterWorldBinding
import app.tellev.core.model.WorldBook
import app.tellev.core.storage.BatchImportSource
import app.tellev.core.storage.CharacterBatchImporter
import app.tellev.ui.CharacterAvatar
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersListScreen(
    viewModel: CharactersViewModel,
    onCreateClick: () -> Unit,
    onCharacterClick: (String) -> Unit,
    onEditClick: (String) -> Unit = {},
    onEditWithAi: (String) -> Unit = {},
    onCreateWorldBookWithAi: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    val searchQuery = state.searchQuery
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 导入菜单（顶栏 ↓ 键）：扫描角色卡 / 添加角色卡 / 搜索。
    var showImportMenu by remember { mutableStateOf(false) }
    // 分组键（顶栏 ⇅ 键）弹层：筛选 + 管理（新建/重命名/删除）。
    var showGroupSheet by remember { mutableStateOf(false) }
    // 卡片长按菜单里的「分组」指派对话框。
    var assignGroupFor by remember { mutableStateOf<CharacterSummary?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val requested = pendingExport
        pendingExport = null
        if (uri != null && requested != null) {
            scope.launch {
                try {
                    val json = viewModel.exportCharacterToJson(requested.first) ?: return@launch
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error(context.getString(R.string.chars_export_write_failed))
                    }
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_export_saved, requested.second))
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_export_failed, e.message))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val sources = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val fileName = UriUtils.resolveDisplayName(context, uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "imported_character"
                    try {
                        BatchImportSource(
                            fileName,
                            UriUtils.readBounded(context, uri, CharacterBatchImporter.IMPORT_MAX_BYTES),
                        )
                    } catch (e: Exception) {
                        // Unreadable document: keep it in the batch so the
                        // report shows the failure instead of silently dropping it.
                        BatchImportSource(fileName, null, readError = e.message ?: e.javaClass.simpleName)
                    }
                }
            }
            viewModel.importCharacters(sources)
        }
    }

    // 「扫描角色卡」：选一个目录（SAF 树），递归找 png/webp/json 卡并按批量导入
    // 管线处理；非卡片文件由导入器逐项报失败，不会中断整批。
    val scanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val sources = withContext(Dispatchers.IO) {
                scanCharacterTree(context, treeUri)
            }
            if (sources.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(R.string.chars_scan_empty))
            } else {
                viewModel.importScannedCharacters(sources)
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

    state.batchImportReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::clearBatchImportReport,
            title = { Text(stringResource(R.string.chars_import_batch_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.chars_import_batch_summary,
                            report.successCount,
                            report.failedCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        report.results.forEach { result ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (result.isSuccess) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (result.isSuccess) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                                Column {
                                    Text(result.fileName, style = MaterialTheme.typography.bodyMedium)
                                    if (result.isSuccess) {
                                        Text(
                                            result.characterName ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        Text(
                                            result.error ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearBatchImportReport) {
                    Text(stringResource(R.string.nav_got_it))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // 「角色卡」标题位改为分组键：显示当前分组名，点开书架分类弹层。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showGroupSheet = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = currentGroupLabel(state),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.chars_group_cd),
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // 搜索键（与导入键调换后在前）。
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchActive) {
                                stringResource(R.string.chars_search_close_cd)
                            } else {
                                stringResource(R.string.chars_search_cd)
                            },
                        )
                    }
                    // 导入键（调换后在后）：扫描角色卡 / 添加角色卡 / 搜索。
                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = stringResource(R.string.chars_import_cd),
                            )
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chars_scan_cards)) },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                onClick = {
                                    showImportMenu = false
                                    scanLauncher.launch(null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chars_add_cards)) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    showImportMenu = false
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chars_search_cd)) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                onClick = {
                                    showImportMenu = false
                                    searchActive = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.chars_new_character)) },
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
                            viewModel.search(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.chars_search_placeholder)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.search("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chars_clear_cd))
                                }
                            }
                        },
                    )
                }
            }

            Crossfade(
                // 只在「加载态 ↔ 网格/空态」真正换面时播放：仅 isLoading 翻转
                // （有缓存数据的静默刷新，如保存/导入后的重载）不该让整页列表
                // 闪一次交叉溶解。
                targetState = state.isLoading && state.filteredCharacters.isEmpty(),
                label = "loading_state",
            ) { showSpinner ->
                if (showSpinner) {
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
                                text = if (searchQuery.isNotEmpty()) {
                                    stringResource(R.string.chars_no_match)
                                } else {
                                    stringResource(R.string.chars_empty_list)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchQuery.isEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.chars_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.87f),
                                )
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        // 书架式：固定 3 列，每格更紧凑（封面 3:4），一行 3 本「书」。
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 104.dp),
                    ) {
                        items(
                            state.filteredCharacters,
                            key = { it.id },
                        ) { character ->
                            CharacterListItem(
                                character = character,
                                avatarFile = state.avatarFiles[character.id],
                                onClick = { onCharacterClick(character.id) },
                                onEdit = { onEditClick(character.id) },
                                onEditWithAi = { onEditWithAi(character.id) },
                                onCreateWorldBookWithAi = { onCreateWorldBookWithAi(character.id) },
                                onDuplicate = { viewModel.duplicateCharacter(character.id) },
                                onDelete = { viewModel.deleteCharacter(character.id) },
                                onAssignGroup = { assignGroupFor = character },
                                onExport = {
                                    pendingExport = character.id to character.name
                                    val safeName = character.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                                        .ifBlank { "character" }
                                    exportLauncher.launch("$safeName.json")
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGroupSheet) {
        DshCharacterGroupSheet(
            groups = state.groups,
            activeGroupId = state.activeGroupId,
            allCount = state.characters.size,
            ungroupedCount = state.characters.count { summary ->
                state.groups.none { group -> summary.id in group.members }
            },
            onSelect = { groupId ->
                viewModel.selectGroup(groupId)
                showGroupSheet = false
            },
            onCreate = { label -> viewModel.createGroup(label) },
            onRename = { id, label -> viewModel.renameGroup(id, label) },
            onDelete = { viewModel.deleteGroup(it) },
            onDismiss = { showGroupSheet = false },
        )
    }

    assignGroupFor?.let { character ->
        CharacterGroupAssignDialog(
            characterName = character.name,
            groups = state.groups,
            isMember = { group -> character.id in group.members },
            onToggle = { groupId, member -> viewModel.setCharacterGroup(character.id, groupId, member) },
            onCreate = { label -> viewModel.createGroup(label) },
            onDismiss = { assignGroupFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterListItem(
    character: app.tellev.core.model.CharacterSummary,
    avatarFile: java.io.File?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onEditWithAi: () -> Unit,
    onCreateWorldBookWithAi: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onAssignGroup: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    CharacterPortraitCard(
        name = character.name,
        tags = character.tags,
        file = avatarFile,
        onClick = onClick,
        onLongClick = { showContextMenu = true },
        compact = true,
        trailing = {
            Box {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.4f)) {
                    IconButton(onClick = { showContextMenu = true }) {
                        Icon(Icons.Default.MoreVert, tint = Color.White,
                            contentDescription = stringResource(R.string.chars_more_options_cd))
                    }
                }
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                ) {
                    // 点卡片进聊天后，编辑卡片改从长按菜单进。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_edit_card)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onEdit()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_ai_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onEditWithAi()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_write_worldbook)) },
                        onClick = {
                            onCreateWorldBookWithAi()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_duplicate)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            onDuplicate()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_assign_group)) },
                        leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
                        onClick = {
                            onAssignGroup()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_export_json)) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            onExport()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_delete)) },
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
        },
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.chars_delete_title)) },
            text = { Text(stringResource(R.string.chars_delete_confirm, character.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.chars_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }
}

/**
 * 角色卡编辑器「进入时」的可编辑面。只含 [CharacterDetailScreen] 保存时会写入的
 * 字段：卡片字段 + 世界书绑定 + 附属角色 + 新建态待保存头像。
 *
 * 不比较整张卡：updatedCard 会把 system_prompt / creator 等键补进 raw，而磁盘上的
 * 卡片可能本来就没有这些键，比较 raw 会把「什么都没改」误判成脏；比较 CharacterWorldBinding /
 * CharacterCastBinding 归一化后的值则不会。
 */
internal data class CharacterEditorSnapshot(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleMessages: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creator: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    val castMemberIds: List<String> = emptyList(),
    /** 绑定的世界书列表（首个为主绑定，其余为追加绑定）。 */
    val linkedWorldNames: List<String> = emptyList(),
    val hasPendingAvatar: Boolean = false,
)

/**
 * 界面当前值 → 快照。归一化必须与 saveCard 一致：name 与世界书绑定名 trim
 * （saveCard 走 updatedCard 的 name.trim() 与 withWorldBookList 的裁剪去重）、
 * 空白绑定视为未绑定、附属角色按 id 去重（withMembers 的过滤）。否则保存后与进入时
 * 相同的内容会被误报成脏。
 */
internal fun characterEditorSnapshot(
    name: String,
    description: String = "",
    personality: String = "",
    scenario: String = "",
    firstMessage: String = "",
    alternateGreetings: List<String> = emptyList(),
    exampleMessages: String = "",
    creatorNotes: String = "",
    systemPrompt: String = "",
    postHistoryInstructions: String = "",
    creator: String = "",
    characterVersion: String = "",
    tags: List<String> = emptyList(),
    castMemberIds: List<String> = emptyList(),
    linkedWorldNames: List<String> = emptyList(),
    hasPendingAvatar: Boolean = false,
): CharacterEditorSnapshot = CharacterEditorSnapshot(
    name = name.trim(),
    description = description,
    personality = personality,
    scenario = scenario,
    firstMessage = firstMessage,
    alternateGreetings = alternateGreetings,
    exampleMessages = exampleMessages,
    creatorNotes = creatorNotes,
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    creator = creator,
    characterVersion = characterVersion,
    tags = tags,
    castMemberIds = castMemberIds.distinct(),
    // 与 withWorldBookList 同款归一化：裁剪、去空、去重、保序。
    linkedWorldNames = linkedWorldNames.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct(),
    hasPendingAvatar = hasPendingAvatar,
)

/** 载入的卡片 → 基线快照。世界书绑定与附属角色用与界面初始化、保存时相同的读法。 */
internal fun CharacterCard.toEditorSnapshot(): CharacterEditorSnapshot = characterEditorSnapshot(
    name = name,
    description = description,
    personality = personality,
    scenario = scenario,
    firstMessage = firstMessage,
    alternateGreetings = alternateGreetings,
    exampleMessages = exampleMessages,
    creatorNotes = creatorNotes,
    systemPrompt = systemPrompt,
    postHistoryInstructions = postHistoryInstructions,
    creator = creator,
    characterVersion = characterVersion,
    tags = tags,
    castMemberIds = CharacterCastBinding.members(this).map { it.id },
    linkedWorldNames = CharacterWorldBinding.linkedWorldBookNames(this),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterDetailScreen(
    viewModel: CharactersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isCreating: Boolean = false,
    onCreated: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val draft = remember { CharacterCard(id = "char_${UUID.randomUUID()}", name = "") }
    val character = if (isCreating) draft else state.selectedCharacter

    LaunchedEffect(isCreating) {
        if (isCreating) viewModel.loadWorldBooks()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAvatarPng by remember { mutableStateOf<ByteArray?>(null) }
    val pendingAvatar = remember(pendingAvatarPng) {
        pendingAvatarPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        UriUtils.readBounded(context, it, maxBytes = 16L * 1024L * 1024L)
                    }
                    if (isCreating) {
                        pendingAvatarPng = withContext(Dispatchers.IO) {
                            app.tellev.util.decodeImageAsPng(bytes, maxEdge = 1024)
                        } ?: error(context.getString(R.string.chars_image_parse_failed))
                    } else {
                        viewModel.setCharacterAvatar(bytes)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_read_image_failed, e.message))
                }
            }
        }
    }

    var name by rememberSaveable(character?.id) { mutableStateOf(character?.name ?: "") }
    var description by rememberSaveable(character?.id) { mutableStateOf(character?.description ?: "") }
    var personality by rememberSaveable(character?.id) { mutableStateOf(character?.personality ?: "") }
    var scenario by rememberSaveable(character?.id) { mutableStateOf(character?.scenario ?: "") }
    var firstMessage by rememberSaveable(character?.id) { mutableStateOf(character?.firstMessage ?: "") }
    var alternateGreetings by remember(character?.id) { mutableStateOf(character?.alternateGreetings ?: emptyList()) }
    var exampleMessages by rememberSaveable(character?.id) { mutableStateOf(character?.exampleMessages ?: "") }
    var creatorNotes by rememberSaveable(character?.id) { mutableStateOf(character?.creatorNotes ?: "") }
    var systemPrompt by rememberSaveable(character?.id) { mutableStateOf(character?.systemPrompt ?: "") }
    var postHistoryInstructions by rememberSaveable(character?.id) { mutableStateOf(character?.postHistoryInstructions ?: "") }
    var creator by rememberSaveable(character?.id) { mutableStateOf(character?.creator ?: "") }
    // 附属角色/群像（1.7.1.4 角色卡演员绑定）。
    var castMembers by remember(character?.id) { mutableStateOf(character?.let(CharacterCastBinding::members).orEmpty()) }
    var showCastPicker by remember { mutableStateOf(false) }
    var characterVersion by rememberSaveable(character?.id) { mutableStateOf(character?.characterVersion ?: "") }
    var tags by remember(character?.id) { mutableStateOf(character?.tags ?: emptyList()) }
    var newTag by remember { mutableStateOf("") }
    var saveAttempted by remember { mutableStateOf(false) }
    // 世界书绑定（首个为主绑定 data.extensions.world，其余落 tellev_additional_worldbooks）。
    // 空列表 = 未绑定。
    var linkedWorldNames by remember(character?.id) {
        mutableStateOf(character?.let { CharacterWorldBinding.linkedWorldBookNames(it) } ?: emptyList())
    }
    var showWorldPicker by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    // 进入时的快照：编辑态在 selectedCharacter 载入后（id 从 null 变成实际 id）重建，
    // 新建态是空草稿。
    var entrySnapshot by remember(character?.id) {
        mutableStateOf(character?.toEditorSnapshot() ?: CharacterEditorSnapshot())
    }
    // 保存成功后把基线推进到已保存内容，见下方 LaunchedEffect。
    var pendingBaseline by remember(character?.id) { mutableStateOf<CharacterCard?>(null) }
    val currentSnapshot = characterEditorSnapshot(
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        firstMessage = firstMessage,
        alternateGreetings = alternateGreetings,
        exampleMessages = exampleMessages,
        creatorNotes = creatorNotes,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        creator = creator,
        characterVersion = characterVersion,
        tags = tags,
        castMemberIds = castMembers.map { it.id },
        linkedWorldNames = linkedWorldNames,
        // 新建态的头像只在点创建时才随卡片落盘，因此单独计入脏。
        hasPendingAvatar = pendingAvatarPng != null,
    )
    val dirty = currentSnapshot != entrySnapshot

    fun confirmLeave() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    BackHandler(enabled = dirty) { showDiscardDialog = true }

    // CharactersViewModel.saveCharacter 成功后会把 selectedCharacter 换成刚写入的卡片；
    // 用它把基线推进到已保存内容，否则保存后再返回仍会被当成有未保存更改。
    // 按 id + 内容比对而不是实例身份（===）：VM 的成功分支换上的不一定是saveCard()
    // 交出去的那个实例（重新读盘、换头像都会重建），身份比对一旦失效基线就永远推进不了，
    // 保存成功也照样弹「放弃未保存的更改？」。保存失败时 selectedCharacter 内容不变，
    // 与 pendingBaseline 对不上，基线保持进入时的值。
    LaunchedEffect(state.selectedCharacter) {
        val expected = pendingBaseline
        val saved = state.selectedCharacter
        if (expected != null && saved != null &&
            saved.id == expected.id && saved.toEditorSnapshot() == expected.toEditorSnapshot()
        ) {
            entrySnapshot = expected.toEditorSnapshot()
            pendingBaseline = null
        }
    }

    fun updatedCard(c: CharacterCard): CharacterCard {
        val rawData = c.raw["data"] as? JsonObject ?: JsonObject(emptyMap())
        val updatedData = JsonObject(rawData + mapOf(
            "system_prompt" to JsonPrimitive(systemPrompt),
            "post_history_instructions" to JsonPrimitive(postHistoryInstructions),
            "creator" to JsonPrimitive(creator),
            "character_version" to JsonPrimitive(characterVersion),
        ))
        return c.copy(
            name = name.trim(),
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            alternateGreetings = alternateGreetings,
            exampleMessages = exampleMessages,
            creatorNotes = creatorNotes,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            creator = creator,
            characterVersion = characterVersion,
            tags = tags,
            raw = JsonObject(c.raw + ("data" to updatedData)),
        )
    }

    fun saveCard() {
        saveAttempted = true
        if (name.isBlank() || state.isLoading) return
        // 一次写完整列表：首个为主绑定、其余为追加绑定。此前只写主绑定，
        // 追加列表会被 withLinkedWorldBookName 静默清掉（绑定丢失根因）。
        val card = CharacterCastBinding.withMembers(
            CharacterWorldBinding.withWorldBookList(
                updatedCard(character ?: return),
                linkedWorldNames,
            ),
            castMembers,
        )
        if (isCreating) {
            scope.launch {
                if (viewModel.createCharacter(card, pendingAvatarPng)) onCreated(card.id)
            }
        } else {
            viewModel.saveCharacter(card)
            pendingBaseline = card
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
                title = {
                    Text(
                        if (isCreating) {
                            stringResource(R.string.chars_new_character)
                        } else {
                            character?.name ?: stringResource(R.string.chars_detail_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::confirmLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chars_back_cd))
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::saveCard,
                        enabled = !state.isLoading,
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = if (isCreating) {
                                stringResource(R.string.chars_create_character)
                            } else {
                                stringResource(R.string.chars_save_cd)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
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
                if (state.selectionError == null) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.selectionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = ::confirmLeave) { Text(stringResource(R.string.chars_back_to_list)) }
                    }
                }
            }
        } else {
            val worldBooks = state.worldBooks
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Cover and avatar are presentation only; the picked image stays
                // in the existing draft until Create is pressed.
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))) {
                    CharacterCover(
                        file = if (isCreating) null else state.avatarFiles[character.id],
                        name = name,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (pendingAvatar != null && isCreating) {
                        Image(bitmap = pendingAvatar, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.8f)),
                    )))
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    if (pendingAvatar != null && isCreating) {
                        Image(
                            bitmap = pendingAvatar,
                            contentDescription = stringResource(R.string.chars_avatar_preview_cd),
                            modifier = Modifier.size(72.dp).clip(CircleShape).clickable { avatarPicker.launch("image/*") },
                        )
                    } else {
                        CharacterAvatar(
                            file = if (isCreating) null else state.avatarFiles[character.id],
                            fallbackText = name,
                            modifier = Modifier
                                .size(72.dp)
                                .clickable { avatarPicker.launch("image/*") },
                            fallbackTextStyle = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name.ifBlank { stringResource(R.string.chars_card_section) },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isCreating) {
                                stringResource(R.string.chars_name_only_hint)
                            } else {
                                stringResource(R.string.chars_card_section)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        TextButton(onClick = { avatarPicker.launch("image/*") }) {
                            Text(
                                if (isCreating) {
                                    stringResource(R.string.chars_pick_avatar_optional)
                                } else {
                                    stringResource(R.string.chars_change_avatar)
                                },
                                color = Color.White,
                            )
                        }
                    }
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.chars_field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = saveAttempted && name.isBlank(),
                    supportingText = if (saveAttempted && name.isBlank()) {{ Text(stringResource(R.string.chars_name_required)) }} else null,
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.chars_field_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Personality
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text(stringResource(R.string.chars_field_personality)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Scenario
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text(stringResource(R.string.chars_field_scenario)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // First Message
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    label = { Text(stringResource(R.string.chars_field_first_message)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chars_alternate_greetings), style = MaterialTheme.typography.titleSmall)
                    alternateGreetings.forEachIndexed { index, greeting ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = greeting,
                                onValueChange = { value ->
                                    alternateGreetings = alternateGreetings.toMutableList().also { it[index] = value }
                                },
                                label = { Text(stringResource(R.string.chars_greeting_n, index + 2)) },
                                modifier = Modifier.weight(1f),
                                minLines = 2,
                                maxLines = 8,
                            )
                            IconButton(onClick = {
                                alternateGreetings = alternateGreetings.toMutableList().also { it.removeAt(index) }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chars_remove_greeting_cd))
                            }
                        }
                    }
                    OutlinedButton(onClick = { alternateGreetings = alternateGreetings + "" }) {
                        Text(stringResource(R.string.chars_add_greeting))
                    }
                }

                // Example Messages
                OutlinedTextField(
                    value = exampleMessages,
                    onValueChange = { exampleMessages = it },
                    label = { Text(stringResource(R.string.chars_field_example_messages)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                // Creator Notes
                OutlinedTextField(
                    value = creatorNotes,
                    onValueChange = { creatorNotes = it },
                    label = { Text(stringResource(R.string.chars_field_creator_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.chars_field_system_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                )

                OutlinedTextField(
                    value = postHistoryInstructions,
                    onValueChange = { postHistoryInstructions = it },
                    label = { Text(stringResource(R.string.chars_field_post_history)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                )

                OutlinedTextField(
                    value = creator,
                    onValueChange = { creator = it },
                    label = { Text(stringResource(R.string.chars_field_creator)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = characterVersion,
                    onValueChange = { characterVersion = it },
                    label = { Text(stringResource(R.string.chars_field_version)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Tags
                Column {
                    Text(
                        text = stringResource(R.string.chars_field_tags),
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
                                        contentDescription = stringResource(R.string.chars_remove_tag_cd),
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
                            label = { Text(stringResource(R.string.chars_add_tag)) },
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
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chars_add_cd))
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
                                text = stringResource(R.string.chars_character_book),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.chars_book_entries,
                                    character.characterBook.name,
                                    character.characterBook.entries.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // World book binding (data.extensions.world): the external
                // lorebook activated when chatting with this character, on top
                // of the embedded character_book above.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.chars_world_binding),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (linkedWorldNames.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chars_world_unbound),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            // 绑定列表：每行显示条目数（读到书时）或缺失告警，尾部 × 解绑。
                            linkedWorldNames.forEachIndexed { index, name ->
                                val book = worldBooks.firstOrNull {
                                    it.name.equals(name, ignoreCase = true) || it.id == name
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (index == 0) {
                                                stringResource(R.string.chars_world_primary, name)
                                            } else {
                                                name
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (book != null) {
                                                stringResource(R.string.chars_book_entries, book.name, book.entryCount)
                                            } else {
                                                stringResource(R.string.chars_world_missing, name)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = { linkedWorldNames = linkedWorldNames - name },
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.chars_world_unbind_cd, name),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = { showWorldPicker = true }) {
                            Text(
                                stringResource(
                                    if (linkedWorldNames.isEmpty()) {
                                        R.string.chars_pick_worldbook
                                    } else {
                                        R.string.chars_world_add
                                    },
                                ),
                            )
                        }
                    }
                }

                // 附属角色 / 群像（1.7.1.4）：角色卡可绑定多个角色，聊天由主卡
                // 统一叙事，附属卡提供人物设定。
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.cast_binding_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cast_binding_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (castMembers.isNotEmpty()) Text(castMembers.joinToString("、") { it.name })
                    FilledTonalButton(onClick = { showCastPicker = true }, enabled = !state.isLoading) {
                        Text(stringResource(R.string.cast_picker_title))
                    }
                }

                // Save button
                FilledTonalButton(
                    onClick = ::saveCard,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text(
                        if (isCreating) {
                            stringResource(R.string.chars_create_character)
                        } else {
                            stringResource(R.string.chars_save_changes)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showCastPicker) {
                CharacterCastPicker(state.characters, castMembers, character?.id.orEmpty(), onApply = { ids ->
                    scope.launch {
                        try {
                            castMembers = viewModel.readCastCharacters(ids, castMembers)
                            showCastPicker = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            snackbarHostState.showSnackbar(error.message.orEmpty())
                        }
                    }
                }, onDismiss = { showCastPicker = false })
            }
            if (showWorldPicker) {
                AlertDialog(
                    onDismissRequest = { showWorldPicker = false },
                    title = { Text(stringResource(R.string.chars_pick_worldbook)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = stringResource(R.string.chars_world_multi_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (worldBooks.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chars_world_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                )
                            } else {
                                worldBooks.forEach { book ->
                                    val selected = linkedWorldNames.any {
                                        it.equals(book.name, ignoreCase = true) || it == book.id
                                    }
                                    // 勾选即加入/移出列表；顺序即绑定顺序（首个为主绑定）。
                                    WorldPickerOption(
                                        label = book.name.ifBlank { book.id },
                                        sublabel = stringResource(R.string.chars_entries_count, book.entryCount),
                                        selected = selected,
                                        onClick = {
                                            linkedWorldNames = if (selected) {
                                                linkedWorldNames.filterNot {
                                                    it.equals(book.name, ignoreCase = true) || it == book.id
                                                }
                                            } else {
                                                linkedWorldNames + book.name
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showWorldPicker = false }) {
                            Text(stringResource(R.string.nav_got_it))
                        }
                    },
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.nav_discard_unsaved_title)) },
            text = { Text(stringResource(R.string.nav_discard_unsaved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.nav_discard_unsaved_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorldPickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    sublabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

