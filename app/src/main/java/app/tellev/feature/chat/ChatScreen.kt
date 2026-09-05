package app.tellev.feature.chat

import app.tellev.core.model.generationDiagnostics
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.reasoningParts
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.core.model.Attachment
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.ui.CharacterAvatar
import app.tellev.util.UriUtils
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import app.tellev.core.model.AttachmentSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    bottomBarReserve: Dp = 0.dp,
    bubbleAlpha: Float = 0.6f,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val error = state.error
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        if (state.selectedCharacter == null) {
            CharacterPickerScreen(
                characters = state.characters,
                avatarFiles = state.characterAvatarFiles,
                isLoading = state.isLoading,
                onCharacterSelected = { viewModel.selectCharacter(it) },
                modifier = Modifier.padding(padding),
            )
        } else {
            ChatContentScreen(
                state = state,
                viewModel = viewModel,
                bottomBarReserve = bottomBarReserve,
                bubbleAlpha = bubbleAlpha,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun CharacterPickerScreen(
    characters: List<app.tellev.core.model.CharacterSummary>,
    avatarFiles: Map<String, java.io.File?>,
    isLoading: Boolean,
    onCharacterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "选择角色",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (characters.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无角色",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请先在“角色”页导入角色卡。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(characters, key = { it.id }) { character ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCharacterSelected(character.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CharacterAvatar(
                                file = avatarFiles[character.id],
                                fallbackText = character.name,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = character.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (character.tags.isNotEmpty()) {
                                    Text(
                                        text = character.tags.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContentScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    bottomBarReserve: Dp,
    bubbleAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val runtimeToken = viewModel.currentRuntimeToken(state.currentSession?.id)
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputText by remember { mutableStateOf("") }
    var showSessionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var editingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editTextField by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<Attachment>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = withContext(Dispatchers.IO) {
                    buildAttachmentFromUri(context, uri)
                }
                if (attachment != null) {
                    pendingAttachments = pendingAttachments + attachment
                }
            }
        }
    }

    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.readBytes()
                    }
                    if (bytes != null) {
                        viewModel.setChatBackground(bytes)
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        "读取图片失败：${e.message}",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // 用户是否正停在列表底部（或非常接近底部）。流式输出时只在"停在底部"
    // 的情况下才自动下拉，避免用户上滑阅读历史消息时被每个 token 拽回底部。
    // 容差为 2：刚追加流式气泡时新 item 还没进入视口，此时不应被判定为"已上滑"。
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex == null || lastVisibleIndex >= totalItems - 2
        }
    }

    // 消息条数变化（发送、生成完成、滑动/编辑/删除、切换会话）：总是滚到底。
    // 这些是离散事件，不是 token 级别的频繁刷新，不会和用户抢手势。
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // 流式输出：仅在用户停在底部时跟随，上滑阅读历史时停止强制下拉。
    LaunchedEffect(state.streamingText) {
        if (state.streamingText.isNotEmpty() && state.messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(state.messages.size)
        }
    }

    // ime inset 是从窗口底部算起的，而根部 Scaffold 已经为底部导航栏预留了
    // bottomBarReserve；直接 imePadding 会把导航栏高度再垫一遍，输入栏与键盘
    // 之间出现一条导航栏高度的空白。这里只补超出导航栏的那部分。
    val density = LocalDensity.current
    val bottomBarReservePx = with(density) { bottomBarReserve.toPx() }
    val imeExtraPx = maxOf(WindowInsets.ime.getBottom(density) - bottomBarReservePx, 0f)
    val imeExtraPadding = with(density) { imeExtraPx.toDp() }

    Column(modifier = modifier.fillMaxSize().padding(bottom = imeExtraPadding)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = state.selectedCharacter?.name ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.currentSession != null) {
                        Text(
                            text = state.currentSession?.title ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.deselectCharacter() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (state.sessions.size > 1) {
                    Box {
                        TextButton(onClick = { showSessionMenu = true }) {
                            Text("会话")
                        }
                        DropdownMenu(
                            expanded = showSessionMenu,
                            onDismissRequest = { showSessionMenu = false },
                        ) {
                            state.sessions.forEach { session ->
                                DropdownMenuItem(
                                    text = { Text(session.title) },
                                    onClick = {
                                        viewModel.switchSession(session.id)
                                        showSessionMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = { viewModel.createNewSession() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }

                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多选项")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("聊天背景…") },
                            onClick = {
                                backgroundPickerLauncher.launch("image/*")
                                showMoreMenu = false
                            },
                        )
                        if (state.chatBackgroundFile != null) {
                            DropdownMenuItem(
                                text = { Text("清除背景") },
                                onClick = {
                                    viewModel.clearChatBackground()
                                    showMoreMenu = false
                                },
                            )
                        }
                        if (state.presets.isNotEmpty()) {
                            state.presets.forEach { preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = preset.name,
                                            fontWeight = if (preset.id == state.selectedPreset?.id) Bold else null,
                                        )
                                    },
                                    onClick = {
                                        viewModel.selectPreset(preset.id)
                                        showMoreMenu = false
                                    },
                                )
                            }
                        }
                        if (state.personas.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "切换人设",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                onClick = {},
                                enabled = false,
                            )
                            state.personas.forEach { persona ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = persona.name,
                                            fontWeight = if (persona.id == state.selectedPersona?.id) Bold else null,
                                        )
                                    },
                                    onClick = {
                                        viewModel.selectPersona(persona.id)
                                        showMoreMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 键盘弹出会压小列表可视高度；加回被压掉的部分得到与键盘收起时
            // 一致的上限（用 px 相加，键盘动画期间逐帧严格相等），打字时
            // WebView 面板（含卡片插图）才不会等比跳缩重排。
            val panelViewportHeight = with(density) {
                (constraints.maxHeight + imeExtraPx).toDp()
            }
            val htmlPanelMaxHeight =
                if (panelViewportHeight > 112.dp) panelViewportHeight - 112.dp else panelViewportHeight

            // Per-session background: full-bleed image with a surface-tinted
            // scrim so bubbles of both roles keep readable contrast.
            state.chatBackgroundFile?.let { file ->
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            itemsIndexed(state.messages, key = { _, msg -> msg.id }) { index, message ->
                if (editingMessageIndex == index) {
                    EditMessageCard(
                        initialText = editTextField,
                        onConfirm = { newText ->
                            viewModel.editMessage(index, newText)
                            editingMessageIndex = null
                        },
                        onCancel = { editingMessageIndex = null },
                    )
                } else {
                    ChatBubble(
                        message = message,
                        character = state.selectedCharacter,
                        characterAvatar = state.characterAvatarFile,
                        preset = state.selectedPreset,
                        userName = state.selectedPersona?.name ?: "User",
                        depth = visibleRegexDepth(state.messages, index),
                        htmlPanelMaxHeight = htmlPanelMaxHeight,
                        bubbleAlpha = bubbleAlpha,
                        tavernRuntime = TavernMessageRuntime(
                            token = runtimeToken,
                            messageIndex = index,
                            variablesJson = { viewModel.tavernMessageVariablesJson(runtimeToken) },
                            contextJson = { viewModel.tavernMessageContextJson(runtimeToken) },
                            request = { operation, payload, callback ->
                                viewModel.handleTavernMessageRequest(
                                    operation = operation,
                                    payloadJson = payload,
                                    onSetInput = { inputText = it },
                                    callback = callback,
                                    token = runtimeToken,
                                )
                            },
                        ),
                        onHtmlBoundaryDrag = { chatScrollDelta ->
                            scope.launch { listState.scrollBy(chatScrollDelta) }
                        },
                        onSwipeLeft = { viewModel.swipeMessage(index, 1) },
                        onSwipeRight = { viewModel.swipeMessage(index, -1) },
                        canRegenerate = !state.isGenerating && canRegenerateResponse(state.messages, index),
                        onRegenerate = { viewModel.regenerateResponse(message.id) },
                        onEdit = {
                            editingMessageIndex = index
                            editTextField = message.content
                        },
                        onDelete = { viewModel.deleteMessage(index) },
                    )
                }
            }

            if (state.isGenerating && (state.streamingText.isNotEmpty() || state.streamingReasoning.isNotEmpty())) {
                item(key = "streaming") {
                    StreamingBubble(
                        text = state.streamingText,
                        reasoning = state.streamingReasoning,
                        characterName = state.selectedCharacter?.name ?: "助手",
                        character = state.selectedCharacter,
                        preset = state.selectedPreset,
                        bubbleAlpha = bubbleAlpha,
                        userName = state.selectedPersona?.name ?: "User",
                        availableMaxHeight = htmlPanelMaxHeight,
                        tavernRuntime = TavernMessageRuntime(
                            token = runtimeToken,
                            messageIndex = state.messages.size,
                            variablesJson = { viewModel.tavernMessageVariablesJson(runtimeToken) },
                            contextJson = { viewModel.tavernMessageContextJson(runtimeToken) },
                            request = { operation, payload, callback ->
                                viewModel.handleTavernMessageRequest(operation, payload, { inputText = it }, callback, runtimeToken)
                            },
                        ),
                        onHtmlBoundaryDrag = { delta -> scope.launch { listState.scrollBy(delta) } },
                    )
                }
            }

            if (state.isGenerating && state.streamingText.isEmpty() && state.streamingReasoning.isEmpty()) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            }
        }

        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            isGenerating = state.isGenerating,
            attachments = pendingAttachments,
            bubbleAlpha = bubbleAlpha,
            onPickImage = {
                pickImageLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onRemoveAttachment = { id ->
                pendingAttachments = pendingAttachments.filterNot { it.id == id }
            },
            onSend = {
                val text = inputText.trim()
                if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                    if (viewModel.sendMessage(text, pendingAttachments)) {
                        inputText = ""
                        pendingAttachments = emptyList()
                        keyboardController?.hide()
                    }
                }
            },
            onStop = { viewModel.stopGeneration() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(
    message: ChatMessage,
    character: CharacterCard?,
    characterAvatar: java.io.File?,
    preset: GenerationPreset?,
    userName: String,
    depth: Int,
    htmlPanelMaxHeight: Dp,
    bubbleAlpha: Float,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var pendingDiagnosticExport by remember { mutableStateOf("") }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingDiagnosticExport
        if (uri != null) exportScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(payload) }
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (success) "已导出" else "导出失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val isUser = message.role == MessageRole.User
    var dragAmount by remember { mutableFloatStateOf(0f) }
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isUser) {
                CharacterAvatar(
                    file = characterAvatar,
                    fallbackText = message.name,
                    modifier = Modifier.size(24.dp),
                    fallbackTextStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = message.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { showActions = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "操作",
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false },
                ) {
                    if (canRegenerate) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                onRegenerate()
                                showActions = false
                            },
                        )
                    }
                    if (message.generationDiagnostics() != null) {
                        DropdownMenuItem(
                            text = { Text("导出生成诊断") },
                            onClick = {
                                pendingDiagnosticExport = message.generationDiagnostics().toString()
                                showActions = false
                                diagnosticExport.launch("generation-diagnostics.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出原始回复") },
                            onClick = {
                                pendingDiagnosticExport = message.generationDiagnostics(includeResponse = true).toString()
                                showActions = false
                                diagnosticExport.launch("generation-response.json")
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onEdit()
                            showActions = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            onDelete()
                            showActions = false
                        },
                    )
                }
            }
        }

        val parts = message.reasoningParts()
        val renderSegments = renderMessageParts(
            parts, message.role, character, preset, userName, depth,
            includeNormal = !CharacterRegexApplier.isNormalProcessed(message),
        )
        if (!isUser && parts.body.isBlank() && parts.reasoning.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未收到正文", modifier = Modifier.padding(8.dp))
                if (canRegenerate) TextButton(onClick = onRegenerate) { Text("重试") }
            }
        }
        val hasFrontend = renderSegments.any { it is TavernRenderSegment.Frontend }
        val dragModifier = Modifier.pointerInput(message.id) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        dragAmount > 80f -> onSwipeRight()
                        dragAmount < -80f -> onSwipeLeft()
                    }
                    dragAmount = 0f
                },
                onDragCancel = { dragAmount = 0f },
                onHorizontalDrag = { _, amount ->
                    dragAmount += amount
                },
            )
        }

        if (hasFrontend && !isUser) {
            if (message.swipes.size > 1) {
                HtmlSwipeControls(
                    currentIndex = message.swipeIndex,
                    totalSwipes = message.swipes.size,
                    onPrevious = onSwipeRight,
                    onNext = onSwipeLeft,
                )
            }
            TavernMessageContent(
                segments = renderSegments,
                availableMaxHeight = htmlPanelMaxHeight,
                isUser = isUser,
                highlightDialogue = message.role != MessageRole.System,
                bubbleAlpha = bubbleAlpha,
                modifier = Modifier.fillMaxWidth(),
                tavernRuntime = tavernRuntime,
                onHtmlBoundaryDrag = onHtmlBoundaryDrag,
            )
        } else {
            TavernMessageContent(
                segments = renderSegments,
                availableMaxHeight = htmlPanelMaxHeight,
                isUser = isUser,
                highlightDialogue = message.role != MessageRole.System,
                bubbleAlpha = bubbleAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    // 半透明气泡：背景图透出 40%，前端卡片分支保持无底板。
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha))
                    .then(dragModifier),
                tavernRuntime = tavernRuntime,
                onHtmlBoundaryDrag = onHtmlBoundaryDrag,
            )
        }

        if (message.swipes.size > 1 && (!hasFrontend || isUser)) {
            SwipeIndicator(
                currentIndex = message.swipeIndex,
                totalSwipes = message.swipes.size,
            )
        }
    }
}

@Composable
private fun TavernMessageContent(
    segments: List<TavernRenderSegment>,
    availableMaxHeight: Dp,
    isUser: Boolean,
    highlightDialogue: Boolean,
    bubbleAlpha: Float,
    modifier: Modifier = Modifier,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
) {
    val dialogueColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is TavernRenderSegment.Text -> {
                    val text = segment.text
                    // AI messages with Markdown syntax render via the WebView (commonmark -> HTML),
                    // reusing TavernHtmlPanel. Plain text (user messages, short replies) stays on
                    // the cheap native Text() to avoid spinning up a WebView per bubble.
                    if (!isUser && MarkdownRenderer.looksLikeMarkdown(text)) {
                        TavernHtmlPanel(
                            html = MarkdownRenderer.render(text, highlightDialogue = highlightDialogue),
                            availableMaxHeight = availableMaxHeight,
                            dialogueQuoteColor = if (highlightDialogue) dialogueColor.toCssHex() else null,
                            tavernRuntime = tavernRuntime,
                            onBoundaryDrag = onHtmlBoundaryDrag,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = dialogueAnnotatedString(text, dialogueColor, highlightDialogue),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    top = if (index == 0) 12.dp else 8.dp,
                                    end = 12.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                    }
                }
                is TavernRenderSegment.Reasoning -> {
                    ReasoningBlock(
                        content = segment.content,
                        highlightDialogue = highlightDialogue,
                        bubbleAlpha = bubbleAlpha,
                    )
                }
                is TavernRenderSegment.Frontend -> {
                    TavernHtmlPanel(
                        html = segment.html,
                        availableMaxHeight = availableMaxHeight,
                        tavernRuntime = tavernRuntime,
                        onBoundaryDrag = onHtmlBoundaryDrag,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(content: String, highlightDialogue: Boolean, bubbleAlpha: Float) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (expanded) "思考过程" else "思考过程 · 点击展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = dialogueAnnotatedString(
                        content,
                        MaterialTheme.colorScheme.primary,
                        highlightDialogue,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = 10.dp,
                    ),
                )
            }
        }
    }
}

private data class TavernMessageRuntime(
    val token: app.tellev.core.extension.RuntimeToken?,
    val messageIndex: Int,
    val variablesJson: () -> String,
    val contextJson: () -> String,
    val request: (String, String, (Boolean, String) -> Unit) -> Unit,
)

private class TavernMessageBridge(
    private val onHeightChanged: (Int) -> Unit,
    private val onBoundaryDrag: (Float) -> Unit,
    runtime: TavernMessageRuntime,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView = java.lang.ref.WeakReference<WebView>(null)

    @Volatile
    private var runtime: TavernMessageRuntime = runtime

    private val loadTracker = TavernMessageLoadTracker()

    @Volatile
    private var nestedScrollGesture: Boolean = false

    /**
     * 已下发过的高度。ResizeObserver 是像素级触发的，卡片内图片渐进加载、
     * 字体就绪都会产生 1px 级抖动；没有这道门限，每次抖动都会经过
     * mainHandler.post → Compose 重组 → WebView 视口变化 → 再次触发
     * ResizeObserver 的完整循环，主线程被排版脉冲淹没（ANR）。
     */
    @Volatile
    private var lastDeliveredHeight: Int = 0

    fun attach(view: WebView) {
        webView = java.lang.ref.WeakReference(view)
    }

    @Volatile private var closed = false
    fun close() { closed = true; mainHandler.removeCallbacksAndMessages(null); webView.clear() }

    private var lastVariablesJson: String? = null
    fun updateRuntime(value: TavernMessageRuntime) {
        check(runtime.token == value.token) { "Frontend runtime ownership changed without replacing WebView" }
        runtime = value
        val variables = runCatching { value.variablesJson() }.getOrNull() ?: return
        if (variables != lastVariablesJson) {
            lastVariablesJson = variables
            webView.get()?.evaluateJavascript("window.__tellevStateChanged?.()", null)
        }
    }

    fun shouldLoad(html: String): Boolean = loadTracker.shouldLoad(html)

    /** 新 HTML 即将加载时调用：旧高度门限不能带到新页面。 */
    fun resetDeliveredHeight() {
        lastDeliveredHeight = 0
    }

    fun beginNativeTouchGesture() {
        nestedScrollGesture = false
    }

    fun hasNestedScrollGesture(): Boolean = nestedScrollGesture

    @JavascriptInterface
    fun setNestedScrollGesture(active: Boolean) {
        nestedScrollGesture = active
    }

    @JavascriptInterface
    fun forwardBoundaryDrag(chatScrollDelta: Double) {
        if (!chatScrollDelta.isFinite() || chatScrollDelta == 0.0) return
        mainHandler.post { onBoundaryDrag(chatScrollDelta.toFloat()) }
    }

    fun dispatchDocumentBoundaryDrag(chatScrollDelta: Float) {
        if (chatScrollDelta != 0f) onBoundaryDrag(chatScrollDelta)
    }

    @JavascriptInterface
    fun resize(height: Int) {
        if (height <= 0) return
        // 差值门限：1px 级抖动直接丢弃，不进主线程消息队列。
        if (kotlin.math.abs(height - lastDeliveredHeight) < 2) return
        lastDeliveredHeight = height
        mainHandler.post { onHeightChanged(height) }
    }

    @JavascriptInterface
    fun getAllVariables(): String =
        runtime.variablesJson()

    @JavascriptInterface
    fun getCurrentMessageId(): Int = runtime.messageIndex

    @JavascriptInterface
    fun getContext(): String = runtime.contextJson()

    @JavascriptInterface
    fun request(requestId: String, operation: String, payloadJson: String) {
        if (closed) return
        val origin = runtime
        mainHandler.post {
            if (closed) return@post
            origin.request(operation, payloadJson) { ok, responseJson ->
                mainHandler.post {
                    val view = webView.get() ?: return@post
                    val idLiteral = org.json.JSONObject.quote(requestId)
                    val payloadLiteral = org.json.JSONObject.quote(responseJson)
                    view.evaluateJavascript(
                        "if(window.__tellevMessageResolve){" +
                            "window.__tellevMessageResolve($idLiteral,${if (ok) "true" else "false"},$payloadLiteral);}",
                        null,
                    )
                }
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TavernHtmlPanel(
    html: String,
    availableMaxHeight: Dp,
    dialogueQuoteColor: String? = null,
    tavernRuntime: TavernMessageRuntime,
    onBoundaryDrag: (Float) -> Unit,
) {
    if (tavernRuntime.token == null) return
    androidx.compose.runtime.key(tavernRuntime.token) {
    val themeOnSurface = MaterialTheme.colorScheme.onSurface.toCssHex()
    val wrappedHtml = remember(html, themeOnSurface, dialogueQuoteColor) {
        wrapTavernHtml(html, themeOnSurface, dialogueQuoteColor)
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxPanelHeight = remember(configuration.screenHeightDp, availableMaxHeight) {
        val screenBound = (configuration.screenHeightDp.dp * 0.92f)
            .coerceAtLeast(420.dp)
            .coerceAtMost(1120.dp)
        val viewportBound = if (availableMaxHeight > 0.dp) availableMaxHeight else screenBound
        minOf(screenBound, viewportBound).coerceAtLeast(180.dp)
    }
    val minPanelHeight = remember(html, maxPanelHeight) {
        if (html.isLargeTavernFrontend()) maxPanelHeight else minOf(240.dp, maxPanelHeight)
    }
    var contentHeightPx by remember(html) { mutableIntStateOf(0) }
    val panelHeight = remember(contentHeightPx, density, minPanelHeight, maxPanelHeight) {
        val measured = with(density) { contentHeightPx.toDp() }
        measured.coercePanelHeight(min = minPanelHeight, max = maxPanelHeight)
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeight),
        factory = { context ->
            val bridge = TavernMessageBridge(
                onHeightChanged = { height ->
                    if (kotlin.math.abs(height - contentHeightPx) >= 2) {
                        contentHeightPx = height
                    }
                },
                onBoundaryDrag = onBoundaryDrag,
                runtime = tavernRuntime,
            )
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true
                overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
                var lastTouchY = 0f
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchY = event.y
                            (view.tag as? TavernMessageBridge)?.beginNativeTouchGesture()
                            // 先假定由 WebView 接管手势；MOVE 时若 WebView 在该方向上
                            // 没有可滚动内容，再把拦截权交还给外层聊天列表。
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = event.y - lastTouchY
                            lastTouchY = event.y
                            val bridge = view.tag as? TavernMessageBridge
                            // Element-level scrollers (for example a card's
                            // `.screen.active`) are invisible to
                            // WebView.canScrollVertically(). JavaScript owns
                            // those gestures and forwards only edge deltas.
                            if (bridge?.hasNestedScrollGesture() == true) {
                                return@setOnTouchListener false
                            }
                            val canScrollInDirection = when {
                                dy > 0 -> view.canScrollVertically(-1)
                                dy < 0 -> view.canScrollVertically(1)
                                else -> true
                            }
                            if (shouldForwardWebViewDragToChat(
                                    canScrollInDirection = canScrollInDirection,
                                )
                            ) {
                                // Compose LazyColumn cannot reliably take over a
                                // gesture after an Android WebView has started it.
                                // Keep receiving MOVE events and forward every
                                // boundary delta explicitly; release on UP/CANCEL.
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                val chatScrollDelta = chatScrollDeltaAtWebViewEdge(
                                    canScrollInDirection = canScrollInDirection,
                                    fingerDeltaY = dy,
                                )
                                bridge?.dispatchDocumentBoundaryDrag(chatScrollDelta)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.textZoom = 100
                tag = bridge
                bridge.attach(this)
                addJavascriptInterface(bridge, "TellevBridge")
                addJavascriptInterface(bridge, "TellevMessage")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                        app.tellev.core.extension.CompatAssets.intercept(context, request.url.toString())
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.post {
                            val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
                            val viewportHeight = (view.height / density).toInt()
                            view.scrollTo(0, 0)
                            view.evaluateJavascript(tavernMessageLayoutScript(viewportHeight), null)
                            view.evaluateJavascript(tavernResizeScript(), null)
                        }
                    }
                }
            }
        },
        onRelease = { webView ->
            (webView.tag as? TavernMessageBridge)?.close()
            webView.stopLoading()
            webView.removeJavascriptInterface("TellevBridge")
            webView.removeJavascriptInterface("TellevMessage")
            webView.destroy()
        },
        update = { webView ->
            val bridge = webView.tag as? TavernMessageBridge
            bridge?.updateRuntime(tavernRuntime)
            if (bridge?.shouldLoad(wrappedHtml) != false) {
                bridge?.resetDeliveredHeight()
                webView.stopLoading()
                webView.scrollTo(0, 0)
                webView.loadDataWithBaseURL(
                    "https://message.tellev.local/",
                    wrappedHtml,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
    )
}
}

private fun Dp.coercePanelHeight(min: Dp, max: Dp): Dp =
    when {
        this < min -> min
        this > max -> max
        else -> this
    }

private fun String.isLargeTavernFrontend(): Boolean =
    length > 1600 ||
        contains("Tavern", ignoreCase = true) ||
        contains("酒馆", ignoreCase = true) ||
        contains("swiper", ignoreCase = true) ||
        contains("carousel", ignoreCase = true)

private fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    "#%06X".format(0xFFFFFF and toArgb())

internal fun wrapTavernHtml(
    html: String,
    themeOnSurface: String,
    dialogueQuoteColor: String? = null,
): String {
    val dialogueQuoteCss = dialogueQuoteColor?.let { color ->
        "q { color: $color; } q::before, q::after { content: none; }"
    }.orEmpty()
    val hostHead = """
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <script src="https://extensions.tellev.local/compat/globals.js"></script>
        ${tavernMessageCompatScript()}
        <script src="https://extensions.tellev.local/compat/chat.js"></script>
        <script src="https://extensions.tellev.local/compat/message.js"></script>
        <style id="tellev-host-style">
            html, body {
                width: 100%;
                min-width: 0;
                margin: 0;
                padding: 0;
                background: transparent;
                color: $themeOnSurface;
                overflow-x: hidden;
            }
            * {
                box-sizing: border-box;
            }
            img, video, canvas, iframe, table { max-width: 100%; }
            body {
                overflow-y: auto !important;
                overflow-x: hidden !important;
            }
            $dialogueQuoteCss
        </style>
    """.trimIndent()

    if (html.contains("<html", ignoreCase = true)) {
        val head = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(html)
        if (head != null) {
            return html.replaceRange(head.range, "${head.value}\n$hostHead")
        }
        val match = Regex("""<html([^>]*)>""", RegexOption.IGNORE_CASE).find(html)
        return if (match == null) {
            "<html><head>$hostHead</head>$html</html>"
        } else {
            val replacement = "<html${match.groupValues[1]}>\n<head>\n$hostHead\n</head>"
            html.replaceRange(match.range, replacement)
        }
    }

    return """
        <!doctype html>
        <html>
        <head>
            $hostHead
        </head>
        $html
        </html>
    """.trimIndent()
}

internal fun tavernResizeScript(): String = """
    (function() {
        var lastPostedHeight = 0;
        var scheduled = false;
        var debounceTimer = 0;
        function pageHeight() {
            var body = document.body || {};
            var doc = document.documentElement || {};
            // NOTE: doc.clientHeight 故意不参与：它是 WebView 视口高度，
            // Compose 侧 panelHeight 变大 → 视口变大 → clientHeight 变大 →
            // 再次 post 更大的高度，正反馈一路顶到 maxPanelHeight。
            return Math.ceil(Math.max(
                body.scrollHeight || 0,
                body.offsetHeight || 0,
                doc.scrollHeight || 0,
                doc.offsetHeight || 0
            ));
        }
        function postHeightNow() {
            scheduled = false;
            var h = pageHeight();
            if (Math.abs(h - lastPostedHeight) < 2) return;
            lastPostedHeight = h;
            if (window.TellevBridge && window.TellevBridge.resize) {
                window.TellevBridge.resize(h);
            }
        }
        function postHeight() {
            if (scheduled) return;
            scheduled = true;
            var flush = function() {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(postHeightNow, 150);
            };
            if (window.requestAnimationFrame) {
                window.requestAnimationFrame(flush);
            } else {
                flush();
            }
        }
        if (!window.__tellevResizeInstalled) {
            window.__tellevResizeInstalled = true;
            window.addEventListener('load', postHeight);
            window.addEventListener('resize', postHeight);
            document.addEventListener('toggle', function() {
                requestAnimationFrame(postHeight);
                setTimeout(postHeight, 80);
            }, true);
            if (window.ResizeObserver) {
                var observer = new ResizeObserver(postHeight);
                observer.observe(document.documentElement);
                if (document.body) observer.observe(document.body);
            }
            setTimeout(postHeight, 50);
            setTimeout(postHeight, 250);
            setTimeout(postHeight, 1000);
        }
        postHeightNow();
    })();
""".trimIndent()

@Composable
private fun HtmlSwipeControls(
    currentIndex: Int,
    totalSwipes: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一页",
            )
        }
        Text(
            text = "${currentIndex + 1}/$totalSwipes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "下一页",
            )
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    reasoning: String,
    characterName: String,
    character: CharacterCard?,
    preset: GenerationPreset?,
    bubbleAlpha: Float,
    userName: String,
    availableMaxHeight: Dp,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
) {
    val segments = renderMessageParts(
        MessageReasoning.fromResponse(text, reasoning), MessageRole.Character,
        character, preset, userName, 0, includeNormal = true,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = characterName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        TavernMessageContent(
            segments = segments,
            availableMaxHeight = availableMaxHeight,
            isUser = false,
            highlightDialogue = true,
            bubbleAlpha = bubbleAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
            tavernRuntime = tavernRuntime,
            onHtmlBoundaryDrag = onHtmlBoundaryDrag,
        )
    }
}

private fun dialogueAnnotatedString(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (!enabled) return@buildAnnotatedString
    DialogueQuoteHighlighter.findRanges(text).forEach { range ->
        addStyle(SpanStyle(color = color), range.first, range.last + 1)
    }
}

@Composable
private fun SwipeIndicator(
    currentIndex: Int,
    totalSwipes: Int,
) {
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${currentIndex + 1}/$totalSwipes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditMessageCard(
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 10,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(text) }) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    attachments: List<Attachment>,
    bubbleAlpha: Float,
    onPickImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val canSend = text.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    val base64 = attachment.metadata["base64"]
                        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        ?.jsonPrimitive?.content
                    Box(modifier = Modifier.size(72.dp)) {
                        if (base64 != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data("data:${attachment.mimeType};base64,$base64")
                                    .build(),
                                contentDescription = attachment.name,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveAttachment(attachment.id) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除附件",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onPickImage,
                enabled = !isGenerating,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "添加图片",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 6,
                placeholder = { Text("输入消息") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    errorContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                ),
            )

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = bubbleAlpha)),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止生成",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary.copy(alpha = bubbleAlpha)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                        ),
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送消息",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Build a vision attachment from a picked image URI: downsample + base64. */
private suspend fun buildAttachmentFromUri(
    context: android.content.Context,
    uri: Uri,
): Attachment? {
    val mimeType = UriUtils.resolveMimeType(context, uri) ?: "image/jpeg"
    if (!mimeType.startsWith("image/")) return null
    val name = UriUtils.resolveDisplayName(context, uri) ?: "image.jpg"
    val bytes = UriUtils.readAndDownsample(context.contentResolver, uri) ?: return null
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return Attachment(
        id = "att-${java.util.UUID.randomUUID()}",
        name = name,
        mimeType = mimeType,
        relativePath = "",
        source = AttachmentSource.Chat,
        metadata = buildJsonObject {
            put("base64", JsonPrimitive(base64))
            put("detail", JsonPrimitive("auto"))
        },
    )
}
