package app.tellev.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.MutatePriority
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import app.tellev.core.model.ChatSessionSummary
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
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.core.extension.WebViewJsExtensionHost
import app.tellev.core.model.Attachment
import app.tellev.core.memory.MemoryMode
import app.tellev.core.storage.GeneratedImage
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
    chatFontSizeSp: Int = 16,
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
                chatFontSizeSp = chatFontSizeSp,
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
    chatFontSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val runtimeToken = viewModel.currentRuntimeToken(state.currentSession?.id)
    LaunchedEffect(state.currentSession?.id) { viewModel.refreshMemory() }
    val listState = key(state.currentSession?.id) {
        // Start at the current end; do not compose old HTML cards at the top only
        // to destroy them immediately when the initial follow effect runs.
        rememberLazyListState(initialFirstVisibleItemIndex = state.messages.lastIndex.coerceAtLeast(0))
    }
    val flingBehavior = ScrollableDefaults.flingBehavior()
    var followLatest by remember(state.currentSession?.id) { mutableStateOf(true) }
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputText by remember { mutableStateOf("") }
    var showSessionMenu by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showMemoryDialog by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showCharacterInterface by remember(state.currentSession?.id) { mutableStateOf(false) }
    LaunchedEffect(state.characterUiExtensionId) {
        if (state.characterUiExtensionId == null) showCharacterInterface = false
    }
    var editingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editTextField by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<Attachment>()) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showImageGallery by remember(state.currentSession?.id) { mutableStateOf(false) }
    // 画廊删空后必须复位标志：否则下次生成新图时对话框会凭空自动弹出。
    LaunchedEffect(state.generatedImages.isEmpty()) {
        if (state.generatedImages.isEmpty()) showImageGallery = false
    }
    var showImageDiagnostic by remember { mutableStateOf(false) }
    LaunchedEffect(state.imageGenDiagnostic) {
        if (state.imageGenDiagnostic != null) showImageDiagnostic = true
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onHtmlScrollStart: () -> Unit = {
        followLatest = false
        scope.launch { listState.stopScroll(MutatePriority.UserInput) }
    }
    val onHtmlBoundaryFling: (Float) -> Unit = { velocity ->
        scope.launch {
            listState.scroll(MutatePriority.UserInput) {
                with(flingBehavior) { performFling(velocity) }
            }
        }
    }
    val graph = LocalTellevGraph.current
    // st-data 根：用于把消息里的图片附件相对路径解析成本地文件。
    val dataRoot = graph.dataStore.layout.root.toFile()

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val attachment = withContext(Dispatchers.IO) {
                        buildAttachmentFromUri(context, uri, dataRoot)
                    }
                    if (attachment != null) {
                        pendingAttachments = pendingAttachments + attachment
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 磁盘满/写入失败不再让异常逃逸到协程作用域导致崩溃。
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.chat_error_save_image, e.message),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
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
                        context.getString(R.string.chat_error_read_image, e.message),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { !listState.isScrollInProgress && !listState.canScrollForward }
            .collect { atEnd -> if (atEnd) followLatest = true }
    }
    // A visible last item can still be many screens tall. Scroll to its bottom,
    // not its top, and never restart a token-level animation over a user's drag.
    LaunchedEffect(state.currentSession?.id, state.messages.size, state.streamingText, state.streamingReasoning) {
        if (followLatest && !listState.isScrollInProgress) {
            val streaming = state.isGenerating && (state.streamingText.isNotEmpty() || state.streamingReasoning.isNotEmpty())
            val target = if (streaming) state.messages.size else state.messages.lastIndex
            if (target >= 0) listState.scrollToItem(target, Int.MAX_VALUE)
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
                        MemoryMode.of(state.currentSession)?.let { mode ->
                            val status = if (state.memoryPluginEnabled) state.memoryStatus else stringResource(R.string.chat_memory_paused)
                            Text(stringResource(R.string.chat_memory_label, mode.label) + (status?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.deselectCharacter() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.chat_back))
                }
            },
            actions = {
                if (state.characterUiExtensionId != null) {
                    TextButton(onClick = { showCharacterInterface = true }) {
                        Text(stringResource(R.string.chat_card_interface))
                    }
                }
                if (state.sessions.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { showSessionMenu = true }) {
                            Text(stringResource(R.string.chat_sessions))
                        }
                        DropdownMenu(
                            expanded = showSessionMenu,
                            onDismissRequest = { showSessionMenu = false },
                        ) {
                            state.sessions.forEach { session ->
                                DropdownMenuItem(
                                    text = { Text(session.title) },
                                    onClick = {
                                        // 点击当前会话不触发切换：切换会停掉进行中的生成。
                                        if (session.id != state.currentSession?.id) {
                                            viewModel.switchSession(session.id)
                                        }
                                        showSessionMenu = false
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                sessionPendingDelete = session
                                                showSessionMenu = false
                                            },
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.chat_delete_session),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = { viewModel.createNewSession() }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_new_session))
                }

                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_long_term_memory)) },
                            onClick = { viewModel.refreshMemory(); showMemoryDialog = true; showMoreMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_chat_background)) },
                            onClick = {
                                backgroundPickerLauncher.launch("image/*")
                                showMoreMenu = false
                            },
                        )
                        if (state.chatBackgroundFile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_clear_background)) },
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
                                        stringResource(R.string.chat_switch_persona),
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
                    .pointerInput(state.currentSession?.id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            followLatest = false
                        }
                    }
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
                            chatFontSizeSp = chatFontSizeSp,
                            tavernRuntime = TavernMessageRuntime(
                                onScrollStart = onHtmlScrollStart,
                                onBoundaryFling = onHtmlBoundaryFling,
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
                            characterName = state.selectedCharacter?.name ?: stringResource(R.string.chat_default_character_name),
                            character = state.selectedCharacter,
                            preset = state.selectedPreset,
                            bubbleAlpha = bubbleAlpha,
                            chatFontSizeSp = chatFontSizeSp,
                            userName = state.selectedPersona?.name ?: "User",
                            availableMaxHeight = htmlPanelMaxHeight,
                            tavernRuntime = TavernMessageRuntime(
                                allowContentUpdates = followLatest,
                                onScrollStart = onHtmlScrollStart,
                                onBoundaryFling = onHtmlBoundaryFling,
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
                Text(stringResource(R.string.chat_view_generated_images, state.generatedImages.size))
            }
        }
        if (showImageGallery && state.generatedImages.isNotEmpty()) {
            var imagePendingDelete by remember { mutableStateOf<GeneratedImage?>(null) }
            AlertDialog(
                onDismissRequest = { showImageGallery = false },
                title = { Text(stringResource(R.string.chat_generated_images_title)) },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                        items(state.generatedImages.asReversed(), key = { it.id }) { image ->
                            Column {
                                image.attachments.forEach { attachment ->
                                    val file = dataRoot.resolve(attachment.relativePath)
                                    if (file.isFile) ChatBubbleImage(file)
                                }
                                var showPrompt by remember(image.id) { mutableStateOf(false) }
                                Row {
                                    TextButton(onClick = { showPrompt = !showPrompt }) {
                                        Text(if (showPrompt) stringResource(R.string.chat_hide_prompt) else stringResource(R.string.chat_view_image_prompt))
                                    }
                                    TextButton(onClick = { imagePendingDelete = image }) {
                                        Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                if (showPrompt) SelectionContainer {
                                    Text(image.prompt, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showImageGallery = false }) { Text(stringResource(R.string.chat_close)) } },
            )
            imagePendingDelete?.let { image ->
                AlertDialog(
                    onDismissRequest = { imagePendingDelete = null },
                    title = { Text(stringResource(R.string.chat_delete_image_title)) },
                    text = { Text(stringResource(R.string.chat_delete_image_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteGeneratedImage(image.id)
                            imagePendingDelete = null
                        }) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { imagePendingDelete = null }) { Text(stringResource(R.string.chat_cancel)) }
                    },
                )
            }
        }
        sessionPendingDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { sessionPendingDelete = null },
                title = { Text(stringResource(R.string.chat_delete_session)) },
                text = { Text(stringResource(R.string.chat_delete_session_message, session.title)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteSession(session.id)
                        sessionPendingDelete = null
                    }) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { sessionPendingDelete = null }) { Text(stringResource(R.string.chat_cancel)) }
                },
            )
        }
        if (state.imageGenError != null && state.imageGenDiagnostic == null) {
            AlertDialog(
                onDismissRequest = viewModel::clearImageError,
                title = { Text(stringResource(R.string.chat_image_gen_failed)) },
                text = { Text(state.imageGenError) },
                confirmButton = { TextButton(onClick = viewModel::clearImageError) { Text(stringResource(R.string.chat_close)) } },
            )
        }

        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            isGenerating = state.isGenerating,
            attachments = pendingAttachments,
            bubbleAlpha = bubbleAlpha,
            dataRoot = dataRoot,
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
                val removed = pendingAttachments.firstOrNull { it.id == id }
                pendingAttachments = pendingAttachments.filterNot { it.id == id }
                // 未发送的附件文件此刻已无任何引用，随移除一起清理，避免孤儿文件累积。
                if (removed != null && removed.relativePath.startsWith("user/images/")) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val file = java.io.File(dataRoot, removed.relativePath)
                            val imagesRoot = java.io.File(dataRoot, "user/images")
                            if (file.canonicalFile.startsWith(imagesRoot.canonicalFile)) {
                                file.delete()
                            }
                        }
                    }
                }
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
            val sceneDiagnosticTitle = stringResource(R.string.chat_scene_diagnostic_title)
            AlertDialog(
                onDismissRequest = { showImageDiagnostic = false },
                title = { Text(sceneDiagnosticTitle) },
                text = {
                    SelectionContainer {
                        Text(state.imageGenDiagnostic,
                            modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(sceneDiagnosticTitle, state.imageGenDiagnostic))
                    }) { Text(stringResource(R.string.chat_copy_diagnostic)) }
                },
                dismissButton = { TextButton(onClick = { showImageDiagnostic = false }) { Text(stringResource(R.string.chat_close)) } },
            )
        }
        if (showCharacterInterface) {
            val runtimeView = state.characterUiExtensionId?.let { id ->
                (graph.extensionHost as? WebViewJsExtensionHost)?.webViewForUi(id)
            }
            if (runtimeView != null) {
                CharacterCardInterfaceDialog(runtimeView) { showCharacterInterface = false }
            }
        }
    }
    MemoryChatDialogs(state, viewModel, showMemoryDialog) { showMemoryDialog = false }
}
