package app.tellev.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellev.LocalTellevGraph
import app.tellev.core.model.Attachment
import coil.compose.AsyncImage
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
    var showImageDialog by remember { mutableStateOf(false) }
    var showImageGallery by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showImageDiagnostic by remember { mutableStateOf(false) }
    LaunchedEffect(state.imageGenDiagnostic) {
        if (state.imageGenDiagnostic != null) showImageDiagnostic = true
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = LocalTellevGraph.current
    // st-data 根：用于把消息里的图片附件相对路径解析成本地文件。
    val dataRoot = graph.dataStore.layout.root.toFile()

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
                            dataRoot = dataRoot,
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

        if (state.generatedImages.isNotEmpty()) {
            TextButton(onClick = { showImageGallery = true }) {
                Text("查看生成图片（${state.generatedImages.size}）")
            }
        }
        if (showImageGallery) {
            AlertDialog(
                onDismissRequest = { showImageGallery = false },
                title = { Text("生成图片") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                        items(state.generatedImages.asReversed(), key = { it.id }) { image ->
                            Column {
                                image.attachments.forEach { attachment ->
                                    val file = dataRoot.resolve(attachment.relativePath)
                                    if (file.isFile) ChatBubbleImage(file)
                                }
                                var showPrompt by remember(image.id) { mutableStateOf(false) }
                                TextButton(onClick = { showPrompt = !showPrompt }) {
                                    Text(if (showPrompt) "收起提示词" else "查看图片提示词")
                                }
                                if (showPrompt) SelectionContainer {
                                    Text(image.prompt, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showImageGallery = false }) { Text("关闭") } },
            )
        }
        if (state.imageGenError != null && state.imageGenDiagnostic == null) {
            AlertDialog(
                onDismissRequest = viewModel::clearImageError,
                title = { Text("生图失败") },
                text = { Text(state.imageGenError) },
                confirmButton = { TextButton(onClick = viewModel::clearImageError) { Text("关闭") } },
            )
        }

        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            isGenerating = state.isGenerating,
            attachments = pendingAttachments,
            bubbleAlpha = bubbleAlpha,
            imageGenAvailable = state.imageGenAvailable,
            isGeneratingImage = state.isGeneratingImage,
            imageGenStatus = state.imageGenStatus,
            onGenerateImage = { showImageDialog = true },
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
            onStopImage = { viewModel.stopImageGeneration() },
        )

        if (showImageDialog) {
            ImageGenerationDialog(
                initialPrompt = inputText,
                initialEngine = state.imageEngine,
                configuredEngines = state.configuredImageEngines,
                onShowDiagnostic = state.imageGenDiagnostic?.let { { showImageDiagnostic = true } },
                onGenerate = { prompt, negative, summarizeScene, engine ->
                    showImageDialog = false
                    keyboardController?.hide()
                    viewModel.generateImage(prompt, negative, summarizeScene, engine)
                },
                onDismiss = { showImageDialog = false },
            )
        }
        if (showImageDiagnostic && state.imageGenDiagnostic != null) {
            AlertDialog(
                onDismissRequest = { showImageDiagnostic = false },
                title = { Text("场景总结诊断") },
                text = {
                    SelectionContainer {
                        Text(state.imageGenDiagnostic,
                            modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("场景总结诊断", state.imageGenDiagnostic))
                    }) { Text("复制诊断") }
                },
                dismissButton = { TextButton(onClick = { showImageDiagnostic = false }) { Text("关闭") } },
            )
        }
    }
}
