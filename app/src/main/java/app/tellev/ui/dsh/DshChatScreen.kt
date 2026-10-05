package app.tellev.ui.dsh

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.MessageRole
import app.tellev.core.model.reasoningParts
import app.tellev.core.model.toSummary
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.tts.TtsPlaybackState
import app.tellev.core.tts.TtsRuntime
import app.tellev.core.tts.ttsUserMessage
import app.tellev.feature.chat.CharacterCardInterfaceDialog
import app.tellev.feature.chat.ChatUiState
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.EditMessageCard
import app.tellev.feature.chat.GenerationMetricsDialog
import app.tellev.feature.chat.ImageGenerationDialog
import app.tellev.feature.chat.MemoryChatDialogs
import app.tellev.feature.chat.RenderInputs
import app.tellev.feature.chat.StreamingBubble
import app.tellev.feature.chat.TavernMessageContent
import app.tellev.feature.chat.TavernMessageRuntime
import app.tellev.feature.chat.buildAttachmentFromUri
import app.tellev.feature.chat.chatSendEnabled
import app.tellev.feature.chat.buildFileAttachmentFromUri
import app.tellev.feature.chat.canRegenerateResponse
import app.tellev.core.tts.TtsSpeechService
import app.tellev.feature.chat.rememberRenderedSegments
import app.tellev.feature.chat.renderMessageParts
import app.tellev.feature.chat.visibleRegexDepths
import app.tellev.ui.CharacterAvatar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * dsh 聊天界面（图一~五）总装：抽屉 + 消息流 + composer + 统计条 + 三弹层。
 * 数据层走既有 ChatViewModel；渲染走保留的消息渲染管线。
 */
@Composable
fun DshChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenWorldBooks: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenImageGenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val graph = LocalTellevGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val dataRoot = graph.dataStore.layout.root.toFile()

    var inputText by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<app.tellev.core.model.Attachment>()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showEffort by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showMemoryDialog by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showMetrics by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showOptimize by remember { mutableStateOf(false) }
    var showCardInterface by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<app.tellev.core.model.ChatSessionSummary?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    var followLatest by remember(state.currentSession?.id) { mutableStateOf(true) }

    // 语音输入（长按输入框）。
    val voice = remember { VoiceInputController(onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } }) }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && voice.ensure(context)) {
            voice.start(
                context,
                onPartial = { if (inputText.isBlank()) inputText = it },
                onFinal = { inputText = if (inputText.isBlank()) it else inputText + it },
            )
        } else {
            voice.unsupported(context)
        }
    }

    suspend fun addFromUri(uri: android.net.Uri) {
        val attachment = runCatching {
            withContext(Dispatchers.IO) {
                buildAttachmentFromUri(context, uri, dataRoot)
                    ?: buildFileAttachmentFromUri(context, uri, dataRoot)
            }
        }.getOrNull()
        if (attachment != null) {
            pendingAttachments = pendingAttachments + attachment
        } else {
            snackbar.showSnackbar(context.getString(R.string.chat_attach_failed, ""))
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { addFromUri(it) } }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { addFromUri(it) } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { addFromUri(it) } }
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportChatLog(context.contentResolver, uri)
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected ->
            scope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(selected)).use { it.readBytes() }
                    }
                    viewModel.setChatBackground(bytes)
                }.onFailure { snackbar.showSnackbar(context.getString(R.string.chat_attach_failed, it.message)) }
            }
        }
    }

    fun send() {
        val trimmed = inputText.trim()
        if (trimmed.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            if (viewModel.sendMessage(trimmed, pendingAttachments)) {
                inputText = ""
                pendingAttachments = emptyList()
            }
        }
    }
    fun startVoice() {
        if (voice.listening.value) {
            voice.stop()
        } else if (voice.ensure(context)) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                voice.start(
                    context,
                    onPartial = { if (inputText.isBlank()) inputText = it },
                    onFinal = { inputText = if (inputText.isBlank()) it else inputText + it },
                )
            } else {
                voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            voice.unsupported(context)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.isGenerating) {
        if (followLatest) listState.animateScrollToItem((state.messages.size).coerceAtLeast(0))
    }

    val renderMacroContext = remember(
        state.selectedCharacter, state.currentSession, state.selectedPersona,
        state.selectedPreset, state.providerConfig?.model,
    ) { viewModel.messageMacroContext(state) }
    val visibleDepths = remember(state.messages) { visibleRegexDepths(state.messages) }
    val runtimeToken = viewModel.currentRuntimeToken(state.currentSession?.id)

    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    DshDrawer(
        sessions = state.sessions,
        pinnedIds = state.pinnedSessionIds,
        currentSessionId = state.currentSession?.id,
        onNewSession = {
            viewModel.createNewSession()
            scope.launch { drawerState.close() }
        },
        onOpenSession = {
            viewModel.switchSession(it)
            scope.launch { drawerState.close() }
        },
        onDeleteSession = { sessionPendingDelete = it },
        onTogglePinned = { viewModel.togglePinnedSession(it) },
        onOpenPlugins = onOpenPlugins,
        onExportLog = {
            exportLog.launch("chat-log-" + System.currentTimeMillis() + ".json")
            scope.launch { drawerState.close() }
        },
        onOpenSettings = { },
        drawerState = drawerState,
    ) {
        androidx.compose.material3.Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_back), tint = Dsh.textPrimary)
                    }
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, stringResource(R.string.chat_drawer_open), tint = Dsh.textPrimary)
                    }
                    Text(
                        state.selectedCharacter?.name.orEmpty(),
                        fontSize = 16.sp,
                        color = Dsh.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenWorldBooks) {
                        Icon(Icons.Default.Public, stringResource(R.string.chat_world_books), tint = Dsh.textSecondary)
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.chat_more_options), tint = Dsh.textSecondary)
                        }
                        DshMoreMenu(
                            expanded = showMoreMenu,
                            onDismiss = { showMoreMenu = false },
                            state = state,
                            onCardInterface = { showCardInterface = true },
                            onMemory = { viewModel.refreshMemory(); showMemoryDialog = true },
                            onMetrics = { showMetrics = true },
                            onBackground = { backgroundPicker.launch("image/*") },
                            onClearBackground = { viewModel.clearChatBackground() },
                            onBindWorld = { viewModel.setChatWorldBook(it); showMoreMenu = false },
                            onSelectPreset = { viewModel.selectPreset(it); showMoreMenu = false },
                            onSelectPersona = { viewModel.selectPersona(it); showMoreMenu = false },
                            onDeleteSession = { state.currentSession?.let { s -> sessionPendingDelete = s.toSummary() } },
                        )
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.selectedCharacter == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.chat_select_character)) }
                    }
                } else {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    ) {
                        state.chatBackgroundFile?.let { file ->
                                coil.compose.AsyncImage(
                                    model = file,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f)))
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(state.messages, key = { _, m -> m.id }) { index, message ->
                                    if (editingIndex == index) {
                                        EditMessageCard(
                                            initialText = editText,
                                            onConfirm = { viewModel.editMessage(index, it); editingIndex = null },
                                            onCancel = { editingIndex = null },
                                        )
                                    } else {
                                        DshMessageRow(
                                            state = state,
                                            index = index,
                                            message = message,
                                            depth = visibleDepths.getOrElse(index) { 0 },
                                            macroContext = renderMacroContext,
                                            dataRoot = dataRoot,
                                            runtimeToken = runtimeToken,
                                            inputText = { inputText },
                                            onSetInput = { inputText = it },
                                            viewModel = viewModel,
                                            clipboard = clipboard,
                                            onBoundaryDrag = { },
                                            onEdit = { editingIndex = index; editText = message.content },
                                            onOpenContext = { showContext = true },
                                            onSwipe = { dir -> viewModel.swipeMessage(index, dir) },
                                        )
                                    }
                                }
                                if (state.isGenerating && (state.streamingText.isNotEmpty() || state.streamingReasoning.isNotEmpty())) {
                                    item(key = "streaming") {
                                        StreamingBubble(
                                            macroContext = renderMacroContext,
                                            text = state.streamingText,
                                            reasoning = state.streamingReasoning,
                                            characterName = state.selectedCharacter?.name ?: "",
                                            character = state.selectedCharacter,
                                            preset = state.selectedPreset,
                                            bubbleAlpha = 1f,
                                            chatFontSizeSp = 16,
                                            userName = state.selectedPersona?.name ?: "User",
                                            availableMaxHeight = 480.dp,
                                            tavernRuntime = TavernMessageRuntime(
                                                allowContentUpdates = followLatest,
                                                token = runtimeToken,
                                                messageIndex = state.messages.size,
                                                variablesJson = { viewModel.tavernMessageVariablesJson(runtimeToken) },
                                                contextJson = { viewModel.tavernMessageContextJson(runtimeToken) },
                                                currentInput = { inputText },
                                                request = { op, payload, cb ->
                                                    viewModel.handleTavernMessageRequest(op, payload, { inputText = it }, cb, runtimeToken)
                                                },
                                            ),
                                            onHtmlBoundaryDrag = { },
                                        )
                                    }
                                }
                            }
                    }
                    DshComposer(
                        text = inputText,
                        onTextChange = { inputText = it },
                        isGenerating = state.isGenerating,
                        canSend = chatSendEnabled(
                            hasDraft = inputText.isNotBlank() || pendingAttachments.isNotEmpty(),
                            isGenerating = state.isGenerating,
                            isLoading = state.isLoading,
                        ),
                        contextRatio = contextRatioOf(state),
                        onSend = ::send,
                        onStop = { viewModel.stopGeneration() },
                        onOpenAttachMenu = { showAttachMenu = true },
                        onOpenReasoning = { showEffort = true },
                        onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onOpenModelMenu = { showModelMenu = true },
                        onOpenContext = { showContext = true },
                        onLongPressVoice = ::startVoice,
                        listening = voice.listening.value,
                        onCancelVoice = { voice.stop() },
                        statsLeft = statsLeft(state),
                        statsRight = statsRight(state),
                        onStatsLeft = { showMetrics = true },
                        onStatsRight = { showContext = true },
                    )
                }
            }

            // ── 弹层与对话框 ──
            Box {
                androidx.compose.material3.DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false },
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    AttachItem(Icons.Default.AddPhotoAlternate, R.string.chat_attach_image) { showAttachMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    AttachItem(androidx.compose.material.icons.Icons.Default.Videocam, R.string.chat_attach_video) { showAttachMenu = false; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    AttachItem(Icons.Default.Audiotrack, R.string.chat_attach_audio) { showAttachMenu = false; pickFile.launch(arrayOf("audio/*")) }
                    AttachItem(Icons.Default.Article, R.string.chat_attach_document) { showAttachMenu = false; pickFile.launch(arrayOf("*/*")) }
                    if (state.imageGenAvailable) {
                        AttachItem(Icons.Default.Palette, R.string.chat_generate_image) { showAttachMenu = false; showImageDialog = true }
                    }
                    if (inputText.isNotBlank()) {
                        AttachItem(Icons.Default.AutoFixHigh, R.string.chat_optimize_title) { showAttachMenu = false; showOptimize = true }
                    }
                }
            }
        }
    }

    if (showModelMenu) {
        DshModelMenu(
            options = modelOptions(state),
            onSelect = { viewModel.selectModel(it); showModelMenu = false },
            onDismiss = { showModelMenu = false },
        )
    }
    if (showEffort) {
        DshEffortSlider(
            current = sessionReasoningEffortOf(state),
            modelLabel = state.providerConfig?.model?.takeIf(String::isNotBlank) ?: state.selectedProvider,
            onSelect = { viewModel.setSessionReasoningEffort(it); showEffort = false },
            onOpenModelMenu = { showEffort = false; showModelMenu = true },
            onDismiss = { showEffort = false },
        )
    }
    if (showContext) {
        val seg = contextSegmentsOf(state)
        if (seg != null) {
            DshContextPopover(
                usedPercent = seg.first,
                usedLabel = seg.second,
                limitLabel = seg.third,
                systemTokens = seg.fourth.first,
                worldTokens = seg.fourth.second,
                messageTokens = seg.fourth.third,
                systemLabel = stringResource(R.string.ctx_label_system),
                worldLabel = stringResource(R.string.ctx_label_world),
                messagesLabel = stringResource(R.string.ctx_label_messages),
                onDismiss = { showContext = false },
            )
        }
    }
    MemoryChatDialogs(state, viewModel, showMemoryDialog) { showMemoryDialog = false }
    if (showMetrics) {
        GenerationMetricsDialog(
            latest = state.latestGenerationMetrics,
            history = state.generationMetrics,
            aggregate = state.generationMetricsAggregate,
            onDismiss = { showMetrics = false },
        )
    }
    if (showImageDialog) {
        ImageGenerationDialog(
            initialPrompt = inputText,
            initialEngine = state.imageEngine,
            configuredEngines = state.configuredImageEngines,
            profiles = state.imageProfiles,
            onShowDiagnostic = null,
            onGenerate = { prompt, negative, summarize, engine ->
                showImageDialog = false
                viewModel.generateImage(prompt, negative, summarize, engine)
            },
            onDismiss = { showImageDialog = false },
        )
    }
    if (showOptimize) {
        app.tellev.ui.PromptOptimizationDialog(
            providerLabel = state.providerConfig?.providerType,
            onRun = { options, onPreview, onDone -> viewModel.optimizeDraft(inputText, options, onPreview, onDone) },
            onCancelRun = { viewModel.cancelPromptOptimization() },
            onApply = { inputText = it; showOptimize = false },
            onDismiss = { showOptimize = false },
        )
    }
    if (showCardInterface) {
        val view = state.characterUiExtensionId?.let { id ->
            (graph.extensionHost as? app.tellev.core.extension.WebViewJsExtensionHost)?.webViewForUi(id)
        }
        if (view != null) CharacterCardInterfaceDialog(view) { showCardInterface = false }
    }
    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_message, session.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSession(session.id); sessionPendingDelete = null }) {
                    Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

// ── 消息行 ──────────────────────────────────────────────────────

@Composable
private fun DshMessageRow(
    state: ChatUiState,
    index: Int,
    message: app.tellev.core.model.ChatMessage,
    depth: Int,
    macroContext: app.tellev.core.prompt.MacroContext?,
    dataRoot: java.io.File,
    runtimeToken: app.tellev.core.extension.RuntimeToken?,
    inputText: () -> String,
    onSetInput: (String) -> Unit,
    viewModel: ChatViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onBoundaryDrag: (Float) -> Unit,
    onEdit: () -> Unit,
    onOpenContext: () -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val isUser = message.role == MessageRole.User
    val context = LocalContext.current
    val graph = LocalTellevGraph.current
    val scope = rememberCoroutineScope()
    val tts = remember(dataRoot, graph.providerRegistry, graph.secretStore) {
        TtsRuntime.get(context, dataRoot, graph.providerRegistry, graph.secretStore, graph.extensionHost.events)
    }
    val sessionId = state.currentSession?.id
    val playbackId = remember(sessionId, message.id, message.swipeIndex, message.content) {
        TtsSpeechService.messagePlaybackId(sessionId, message.id, message.swipeIndex, message.content)
    }
    val ttsState by tts.state.collectAsState()
    val requestingId by tts.requestingId.collectAsState()
    val isReading = requestingId == playbackId ||
        (ttsState as? TtsPlaybackState.Playing)?.item?.id == playbackId ||
        (ttsState as? TtsPlaybackState.Paused)?.item?.id == playbackId
    val feedback = (message.metadata["feedback"] as? JsonPrimitive)?.contentOrNull

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isUser) {
            // 用户消息：右对齐灰气泡。
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Dsh.userBubble,
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    UserMessageText(message)
                }
            }
        } else {
            // 助手消息：全宽正文 + 头部时间/复制 + 底部操作行。
            val parts = remember(message) { message.reasoningParts() }
            val renderInputs = remember(parts, message.role, state.selectedCharacter, state.selectedPreset, state.selectedPersona?.name, depth, message.metadata, macroContext) {
                RenderInputs(
                    parts, message.role, state.selectedCharacter, state.selectedPreset,
                    state.selectedPersona?.name ?: "User", depth,
                    includeNormal = !app.tellev.core.regex.CharacterRegexApplier.isNormalProcessed(message),
                    macroContext = macroContext,
                )
            }
            val segments = rememberRenderedSegments(renderInputs, message.id) {
                renderMessageParts(
                    parts, message.role, state.selectedCharacter, state.selectedPreset,
                    state.selectedPersona?.name ?: "User", depth,
                    includeNormal = !app.tellev.core.regex.CharacterRegexApplier.isNormalProcessed(message),
                    macroContext = macroContext,
                )
            }.value
            val hasFrontend = segments.any { it is app.tellev.feature.chat.TavernRenderSegment.Frontend }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (message.createdAtMillis > 0) {
                        java.time.Instant.ofEpochMilli(message.createdAtMillis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    } else "",
                    fontSize = 12.sp,
                    color = Dsh.textTertiary,
                )
                Spacer(Modifier.size(4.dp))
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.chat_copy_message), modifier = Modifier.size(14.dp), tint = Dsh.textTertiary)
                }
                Spacer(Modifier.weight(1f))
            }

            val rowDensity = LocalDensity.current
            val drag = androidx.compose.ui.Modifier.pointerInput(message.id) {
                var acc = 0f
                val threshold = with(rowDensity) { 80.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = { if (acc > threshold) onSwipe(1) else if (acc < -threshold) onSwipe(-1); acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { change, amount -> change.consume(); acc += amount }
            }
            Box(modifier = drag) {
                TavernMessageContent(
                    segments = segments,
                    availableMaxHeight = 480.dp,
                    isUser = false,
                    highlightDialogue = true,
                    bubbleAlpha = 1f,
                    chatFontSizeSp = 16,
                    modifier = Modifier.fillMaxWidth(),
                    tavernRuntime = TavernMessageRuntime(
                        token = runtimeToken,
                        messageIndex = index,
                        variablesJson = { viewModel.tavernMessageVariablesJson(runtimeToken) },
                        contextJson = { viewModel.tavernMessageContextJson(runtimeToken) },
                        currentInput = inputText,
                        request = { op, payload, cb ->
                            viewModel.handleTavernMessageRequest(op, payload, onSetInput, cb, runtimeToken)
                        },
                        onScrollStart = { },
                        onBoundaryFling = onBoundaryDrag,
                    ),
                    onHtmlBoundaryDrag = onBoundaryDrag,
                )
            }
            if (message.swipes.size > 1 && !hasFrontend) {
                app.tellev.feature.chat.HtmlSwipeControls(
                    currentIndex = message.swipeIndex,
                    totalSwipes = message.swipes.size,
                    onPrevious = { onSwipe(-1) },
                    onNext = { onSwipe(1) },
                )
            }
            // 操作行（图一）：复制/👍/👎/分享/播放/重新生成/继续/数据库。
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionKey(Icons.Default.ContentCopy, R.string.chat_copy_message) { clipboard.setText(AnnotatedString(message.content)) }
                ActionKey(Icons.Default.ThumbUp, R.string.chat_feedback_up, active = feedback == "up") {
                    viewModel.setMessageFeedback(index, if (feedback == "up") null else "up")
                }
                ActionKey(Icons.Default.ThumbDown, R.string.chat_feedback_down, active = feedback == "down", activeTint = MaterialTheme.colorScheme.error) {
                    viewModel.setMessageFeedback(index, if (feedback == "down") null else "down")
                }
                ActionKey(Icons.Default.Share, R.string.chat_share) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, message.content)
                    }
                    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null)) }
                }
                ActionKey(
                    if (isReading) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    if (isReading) R.string.tts_stop else R.string.chat_speak_current,
                    active = isReading,
                ) {
                    if (isReading) {
                        tts.stopIfOwner(playbackId)
                    } else {
                        scope.launch {
                            tts.speak(message.reasoningParts().body, playbackId, sessionId).onFailure {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.tts_error, it.ttsUserMessage()),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                if (!state.isGenerating && canRegenerateResponse(state.messages, index)) {
                    ActionKey(Icons.Default.Refresh, R.string.chat_regenerate) { viewModel.regenerateResponse(message.id) }
                }
                if (!state.isGenerating && index == state.messages.lastIndex &&
                    (message.role == MessageRole.Character || message.role == MessageRole.Assistant)
                ) {
                    ActionKey(Icons.Default.FastForward, R.string.chat_continue) { viewModel.continueGeneration() }
                }
                ActionKey(Icons.Default.Storage, R.string.ctx_label_system) { onOpenContext() }
            }
        }
    }
}

@Composable
private fun UserMessageText(message: app.tellev.core.model.ChatMessage) {
    val parts = remember(message) { message.reasoningParts() }
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (parts.reasoning.isNotBlank()) {
            Text(
                parts.reasoning,
                fontSize = 13.sp,
                color = Dsh.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(parts.body, fontSize = 16.sp, color = Dsh.textPrimary)
    }
}

@Composable
private fun ActionKey(
    icon: ImageVector,
    label: Int,
    active: Boolean = false,
    activeTint: Color = Dsh.blue,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (active) activeTint else Dsh.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AttachItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(stringResource(label), fontSize = 14.sp, color = Dsh.textPrimary) },
        leadingIcon = { Icon(icon, null, tint = Dsh.textSecondary, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

// ── ⋮ 菜单（世界书/设定集合/卡片界面/记忆/指标/背景/删除会话） ──

@Composable
private fun DshMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: ChatUiState,
    onCardInterface: () -> Unit,
    onMemory: () -> Unit,
    onMetrics: () -> Unit,
    onBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onBindWorld: (String?) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectPersona: (String) -> Unit,
    onDeleteSession: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuHead(stringResource(R.string.chat_panel_world_session))
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_panel_world_unbound), fontSize = 14.sp) },
            onClick = { onBindWorld(null) },
        )
        state.worldBooks.forEach { book ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(book.name, fontSize = 14.sp) },
                onClick = { onBindWorld(book.name) },
            )
        }
        MenuHead(stringResource(R.string.chat_panel_preset_section))
        state.presets.forEach { preset ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(preset.name, fontSize = 14.sp) },
                onClick = { onSelectPreset(preset.id) },
            )
        }
        MenuHead(stringResource(R.string.chat_panel_persona_section))
        state.personas.forEach { persona ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(persona.name, fontSize = 14.sp) },
                onClick = { onSelectPersona(persona.id) },
            )
        }
        HorizontalMenuDivider()
        if (state.characterUiExtensionId != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_card_interface), fontSize = 14.sp) },
                onClick = { onDismiss(); onCardInterface() },
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_long_term_memory), fontSize = 14.sp) },
            onClick = { onDismiss(); onMemory() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.metrics_title), fontSize = 14.sp) },
            onClick = { onDismiss(); onMetrics() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_chat_background), fontSize = 14.sp) },
            onClick = { onDismiss(); onBackground() },
        )
        if (state.chatBackgroundFile != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_clear_background), fontSize = 14.sp) },
                onClick = { onDismiss(); onClearBackground() },
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_delete_session), fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDeleteSession() },
        )
    }
}

@Composable
private fun MenuHead(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        color = Dsh.textTertiary,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun HorizontalMenuDivider() {
    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

// ── 纯计算（原 ChatScreen 内函数的重制版） ────────────────────────

internal fun contextRatioOf(state: ChatUiState): Float? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val limit = snapshot?.contextTokenLimit?.takeIf { it > 0 } ?: return null
    val used = snapshot.estimatedTokenCount?.toFloat() ?: return null
    return (used / limit).coerceIn(0f, 1f)
}

internal fun sessionReasoningEffortOf(state: ChatUiState): ReasoningEffort =
    ReasoningSupport.sessionOverrideFrom(state.currentSession?.metadata) ?: ReasoningEffort.Auto

internal fun modelOptions(state: ChatUiState): List<app.tellev.feature.chat.ModelOption> {
    val current = state.providerConfig?.model?.takeIf(String::isNotBlank)
    return buildList {
        current?.let { add(app.tellev.feature.chat.ModelOption(it, "当前模型", isCurrent = true)) }
        state.generationMetrics.asReversed().mapNotNull { it.model?.takeIf(String::isNotBlank) }
            .distinct().filter { it != current }.take(6)
            .forEach { add(app.tellev.feature.chat.ModelOption(it, "最近使用")) }
        listOf("gpt-4o-mini", "gpt-4o", "deepseek-chat").filter { it != current }
            .forEach { add(app.tellev.feature.chat.ModelOption(it, "常用模型")) }
    }
}

/** 图三四元组：百分比 / 已用量 / 上限量 / (系统, 世界书, 消息)。 */
internal fun contextSegmentsOf(
    state: ChatUiState,
): Quadruple<Int, String, String, Triple<Long, Long, Long>>? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val total = snapshot?.estimatedTokenCount?.takeIf { it > 0 } ?: return null
    val limit = snapshot.contextTokenLimit?.takeIf { it > 0 } ?: return null
    val messageTokens = snapshot.messages
        .filter { it.role != "system" }
        .sumOf { (app.tellev.core.prompt.TokenBudget.estimateTokens(it.content) + 4).toLong() }
        .coerceAtMost(total.toLong())
    val worldTokens = (snapshot.memoryInjections.sumOf { (app.tellev.core.prompt.TokenBudget.estimateTokens(it.text) + 4).toLong() } + snapshot.worldBookHits.size * 64L)
        .coerceAtMost(total.toLong() - messageTokens)
    val systemTokens = (total - messageTokens - worldTokens).coerceAtLeast(0)
    val pct = ((total * 100) / limit).coerceIn(0, 100)
    return Quadruple(pct, "~" + dshCompactTokens(total.toLong()), dshCompactTokens(limit.toLong()),
        Triple(systemTokens, worldTokens, messageTokens))
}

internal data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

internal fun statsLeft(state: ChatUiState): String? {
    val rounds = state.messages.count { it.role == MessageRole.User }
    val steps = state.messages.count {
        it.role == MessageRole.Character || it.role == MessageRole.Assistant
    }
    if (rounds == 0 && steps == 0) return null
    val speed = state.latestGenerationMetrics?.tokensPerSecond?.let { "%.0f tok/s".format(it) }
    return "${UiStrings.get(S.chat_stats_round, rounds)} ${UiStrings.get(S.chat_stats_step, steps)}" +
        (speed?.let { " · $it" } ?: "")
}

internal fun statsRight(state: ChatUiState): String? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val tokens = snapshot?.estimatedTokenCount ?: return null
    val cache = state.latestGenerationMetrics?.cacheHitRate
        ?.let { UiStrings.get(S.chat_stats_cache_hit, "%.0f%%".format(it * 100)) }
    return UiStrings.get(S.chat_stats_tokens, dshCompactTokens(tokens.toLong())) +
        (cache?.let { " · $it" } ?: "")
}

// ── 语音控制器与附件工具 ────────────────────────────────────────

internal class VoiceInputController(private val onError: (String) -> Unit) {
    private var recognizer: android.speech.SpeechRecognizer? = null
    val listening = androidx.compose.runtime.mutableStateOf(false)
    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var appContext: android.content.Context? = null

    fun ensure(context: android.content.Context): Boolean {
        appContext = context.applicationContext
        if (recognizer == null) {
            recognizer = runCatching {
                if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
                    android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                } else null
            }.getOrNull()
        }
        return recognizer != null
    }

    fun start(
        context: android.content.Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
    ) {
        val active = recognizer ?: return
        this.onPartial = onPartial
        this.onFinal = onFinal
        active.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening.value = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening.value = false }
            override fun onError(code: Int) { listening.value = false }
            override fun onResults(results: android.os.Bundle?) {
                listening.value = false
                results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal ?: {})
            }
            override fun onPartialResults(partial: android.os.Bundle?) {
                partial?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial ?: {})
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { active.startListening(intent) }
    }

    fun stop() {
        listening.value = false
        runCatching { recognizer?.stopListening() }
    }

    fun unsupported(context: android.content.Context) {
        onError(context.getString(R.string.chat_voice_unsupported))
    }

    fun release() {
        listening.value = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
