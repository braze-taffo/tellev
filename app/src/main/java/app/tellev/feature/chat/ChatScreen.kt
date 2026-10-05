package app.tellev.feature.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import app.tellev.core.model.ChatSessionSummary
import kotlinx.serialization.json.contentOrNull
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight.Companion.Bold
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellev.LocalTellevGraph
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.R
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.model.toSummary
import app.tellev.ui.CharacterAvatar
import app.tellev.core.extension.WebViewJsExtensionHost
import app.tellev.core.model.Attachment
import app.tellev.core.memory.MemoryMode
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.storage.GeneratedImage
import app.tellev.util.UriUtils
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import androidx.compose.runtime.withFrameNanos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    bottomBarReserve: Dp = 0.dp,
    bubbleAlpha: Float = 0.6f,
    chatFontSizeSp: Int = 16,
    onOpenWorldBooks: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    onOpenImageGenSettings: () -> Unit = {},
    onOpenUsageStats: () -> Unit = {},
    onBack: (() -> Unit)? = null,
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
                snackbarHostState = snackbarHostState,
                bottomBarReserve = bottomBarReserve,
                bubbleAlpha = bubbleAlpha,
                chatFontSizeSp = chatFontSizeSp,
                onOpenWorldBooks = onOpenWorldBooks,
                onOpenPlugins = onOpenPlugins,
                onOpenImageGenSettings = onOpenImageGenSettings,
                onOpenUsageStats = onOpenUsageStats,
                onBack = onBack,
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
    snackbarHostState: SnackbarHostState,
    bottomBarReserve: Dp,
    bubbleAlpha: Float,
    chatFontSizeSp: Int,
    onOpenWorldBooks: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenImageGenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Q10: this builds a full macro context (variables snapshot, budgets).
    // Recomputing it on every keystroke recomposition stalled the main thread
    // while typing; the inputs it reads change per message/session, not per key.
    val renderMacroContext = remember(
        state.selectedCharacter,
        state.currentSession,
        state.selectedPersona,
        state.selectedPreset,
        state.providerConfig?.model,
    ) { viewModel.messageMacroContext(state) }
    // Q12: per-item visibleRegexDepth is O(n) per bubble (O(n²) per screen);
    // one reverse pass per message-list change replaces that.
    val visibleDepths = remember(state.messages) { visibleRegexDepths(state.messages) }
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
    var inputText by rememberSaveable { mutableStateOf("") }
    var sessionPendingDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showMemoryDialog by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showContextViewer by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showContextSheet by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showModelPicker by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showGenerationMetrics by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showScriptManager by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showCharacterInterface by remember(state.currentSession?.id) { mutableStateOf(false) }
    LaunchedEffect(state.characterUiExtensionId) {
        if (state.characterUiExtensionId == null) showCharacterInterface = false
    }
    var editingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editTextField by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<Attachment>()) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showOptimizeDialog by remember { mutableStateOf(false) }
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showQuickSettings by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var showReasoning by remember { mutableStateOf(false) }
    var showModelPopup by remember { mutableStateOf(false) }
    var showContextPopover by remember { mutableStateOf(false) }
    // 抽屉每次打开都重拉分组会话：切换/删除/新建后列表要保持最新。
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen }.collect { open ->
            if (open) viewModel.loadSessionGroups()
        }
    }
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

    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportChatLog(context.contentResolver, uri)
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { selected ->
            scope.launch {
                val attachment = buildFileAttachmentFromUri(context, selected, dataRoot)
                    ?: buildAttachmentFromUri(context, selected, dataRoot)
                if (attachment != null) {
                    pendingAttachments = pendingAttachments + attachment
                } else {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_attach_failed, ""))
                }
            }
        }
    }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { selected ->
            scope.launch {
                val attachment = buildAttachmentFromUri(context, selected, dataRoot)
                    ?: buildFileAttachmentFromUri(context, selected, dataRoot)
                if (attachment != null) {
                    pendingAttachments = pendingAttachments + attachment
                } else {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_attach_failed, ""))
                }
            }
        }
    }

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
                        UriUtils.readBounded(context, it, maxBytes = 12L * 1024L * 1024L)
                    }
                    viewModel.setChatBackground(bytes)
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
    // The IME flag is a trigger too: the keyboard changes no message state, so
    // without it the newest message stayed hidden behind the keyboard.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(state.currentSession?.id, state.messages.size, state.streamingText, state.streamingReasoning, imeVisible) {
        if (followLatest && !listState.isScrollInProgress) {
            // The IME padding lands on the next layout pass; wait one frame or
            // the scroll measures against the pre-keyboard viewport.
            if (imeVisible) withFrameNanos { }
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

    ChatSessionDrawer(
        sessionGroups = state.sessionGroups,
        pinnedSessionIds = state.pinnedSessionIds,
        currentSessionId = state.currentSession?.id,
        onTogglePinned = { viewModel.togglePinnedSession(it) },
        onNewSession = {
            viewModel.createNewSession()
            scope.launch { drawerState.close() }
        },
        onOpenSession = {
            viewModel.switchSession(it)
            scope.launch { drawerState.close() }
        },
        onDeleteSession = { viewModel.deleteSession(it) },
        onOpenPlugins = {
            onOpenPlugins()
            scope.launch { drawerState.close() }
        },
        onExportLog = {
            exportLogLauncher.launch("chat-log-" + System.currentTimeMillis() + ".json")
            scope.launch { drawerState.close() }
        },
        onOpenSettings = {
            showChatSettings = true
            scope.launch { drawerState.close() }
        },
        drawerState = drawerState,
    ) {
        Column(modifier = modifier.fillMaxSize().padding(bottom = imeExtraPadding)) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CharacterAvatar(file = state.characterAvatarFile,
                        fallbackText = state.selectedCharacter?.name.orEmpty(),
                        modifier = Modifier.size(38.dp))
                    Column {
                        Text(
                            text = state.selectedCharacter?.name ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.currentSession != null) {
                            Text(
                                text = state.currentSession?.title ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    }
                },
                // 从角色卡进入聊天时返回角色列表；留在聊天里的（无 onBack）才取消选角。
                navigationIcon = {
                    IconButton(onClick = onBack ?: { viewModel.deselectCharacter() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                        )
                    }
                },
                actions = {
                    // 抽屉键：会话列表、新会话、插件、导出与抽屉设置（参考图三）。
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.chat_drawer_open),
                        )
                    }
                    // 设定集合键：世界书 / 生成预设 / 用户设定（参考图一右上）。
                    IconButton(onClick = { showQuickSettings = true }) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.chat_quick_settings),
                        )
                    }
                    // 世界书键：直接进世界书列表（世界书已并入聊天界面）。
                    IconButton(onClick = onOpenWorldBooks) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = stringResource(R.string.chat_world_books),
                        )
                    }
                    // 溢出菜单：记忆/上下文/指标/脚本/背景等次级功能。
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more_options))
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            if (state.characterUiExtensionId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_card_interface)) },
                                    onClick = { showCharacterInterface = true; showMoreMenu = false },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_long_term_memory)) },
                                onClick = { viewModel.refreshMemory(); showMemoryDialog = true; showMoreMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ctxview_title)) },
                                onClick = { showContextViewer = true; showMoreMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.metrics_title)) },
                                onClick = { showGenerationMetrics = true; showMoreMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_script_manager_title)) },
                                onClick = { showScriptManager = true; showMoreMenu = false },
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
                            if (state.currentSession != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_delete_session)) },
                                    onClick = {
                                        sessionPendingDelete = state.currentSession.toSummary()
                                        showMoreMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )

            // A failure can be much taller than the fixed toolbar title area.
            // Keep its summary in a separate row; the manager displays the full diagnostic.
            MemoryMode.of(state.currentSession)?.let { mode ->
                val status = if (state.memoryPluginEnabled) state.memoryStatus else stringResource(R.string.chat_memory_paused)
                Text(
                    text = stringResource(R.string.chat_memory_label, memoryModeLabel(mode)) +
                        (status?.lineSequence()?.firstOrNull()?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.refreshMemory()
                        showMemoryDialog = true
                    }.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

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
                    if (state.characterScriptsDisabled != null) {
                        item(key = "character_scripts_disabled") {
                            CharacterScriptsDisabledBanner(onEnable = viewModel::requestScriptConsentPrompt)
                        }
                    }
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
                                macroContext = renderMacroContext,
                                message = message,
                                character = state.selectedCharacter,
                                characterAvatar = state.characterAvatarFile,
                                dataRoot = dataRoot,
                                preset = state.selectedPreset,
                                userName = state.selectedPersona?.name ?: "User",
                                depth = visibleDepths.getOrElse(index) { 0 },
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
                                    currentInput = { inputText },
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
                                canContinue = canContinueResponse(state.messages, index) && !state.isGenerating,
                                messageIndex = index,
                                onFeedback = { feedbackIndex, value ->
                                    viewModel.setMessageFeedback(feedbackIndex, value)
                                },
                                onOpenContext = { showContextPopover = true },
                                onRegenerate = { viewModel.regenerateResponse(message.id) },
                                onContinue = { viewModel.continueGeneration() },
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
                                macroContext = renderMacroContext,
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
                                    currentInput = { inputText },
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
            state.pendingScriptConsent?.let { consent ->
                AlertDialog(
                    onDismissRequest = { viewModel.denyCharacterScripts(persist = false) },
                    title = { Text(stringResource(R.string.chat_script_consent_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.chat_script_consent_body,
                                consent.scriptNames.size,
                                consent.scriptNames.take(5).joinToString(", ") +
                                    if (consent.scriptNames.size > 5) "…" else "",
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.approveCharacterScripts() }) {
                            Text(stringResource(R.string.chat_script_consent_approve))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.denyCharacterScripts(persist = true) }) {
                            Text(stringResource(R.string.chat_script_consent_deny))
                        }
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
                // 装载/切换会话期间把发送控件交给同一道闸门置灰：草稿与附件原样保留，
                // 不会出现「按钮可点、业务层却静默拒绝」的空操作。
                isLoading = state.isLoading,
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
                onPickVideo = {
                    videoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onPickAudio = { filePickerLauncher.launch(arrayOf("audio/*")) },
                onPickDocument = { filePickerLauncher.launch(arrayOf("*/*")) },
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
                onOptimizeDraft = { showOptimizeDialog = true },
                onSend = {
                    val text = inputText.trim()
                    if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
                        // 被拒绝（装载/切换进行中、没有会话等）时草稿与附件必须保留：
                        // 只在发送真正被受理时才清空输入。
                        if (viewModel.sendMessage(text, pendingAttachments)) {
                            inputText = ""
                            pendingAttachments = emptyList()
                            keyboardController?.hide()
                        }
                    }
                },
                onStop = { viewModel.stopGeneration() },
                onStopImage = { viewModel.stopImageGeneration() },
                // dsh 复刻（参考图一）：模型库⌄=模型菜单，盾⌄=思考滑杆，圆环=上下文。
                onOpenModelPicker = { showModelPopup = true },
                onOpenReasoning = { showReasoning = true },
                onOpenContext = { showContextPopover = true },
                contextRatio = contextUsageRatio(state),
                onVoiceError = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )

            // 统计条（参考图一输入栏下方）：⏱ 轮/步/速度 · 🗄 token/缓存命中。
            // 左组点开生成指标，右组点开上下文分段浮层（参考图三）。
            ChatStatsBar(
                state = state,
                onOpenMetrics = { showGenerationMetrics = true },
                onOpenContext = { showContextPopover = true },
            )

            if (showOptimizeDialog) {
                // 优化只替换草稿；发送永远由用户按下发送键。
                val providerLabel = state.providerConfig?.let { config ->
                    listOfNotNull(config.model?.takeIf(String::isNotBlank), config.providerType)
                        .joinToString(" · ")
                }
                app.tellev.ui.PromptOptimizationDialog(
                    providerLabel = providerLabel,
                    onRun = { options, onPreview, onDone ->
                        viewModel.optimizeDraft(inputText, options, onPreview, onDone)
                    },
                    onCancelRun = { viewModel.cancelPromptOptimization() },
                    onApply = { optimized ->
                        inputText = optimized
                        showOptimizeDialog = false
                    },
                    onDismiss = { showOptimizeDialog = false },
                )
            }
            if (showImageDialog) {
                ImageGenerationDialog(
                    initialPrompt = inputText,
                    initialEngine = state.imageEngine,
                    configuredEngines = state.configuredImageEngines,
                    profiles = state.imageProfiles,
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
    }
    MemoryChatDialogs(state, viewModel, showMemoryDialog) { showMemoryDialog = false }
    if (showScriptManager) {
        val scriptEntries = remember(state.selectedCharacter?.raw, state.selectedCharacter?.id) {
            viewModel.characterScriptEntries()
        }
        AlertDialog(
            onDismissRequest = { showScriptManager = false },
            title = { Text(stringResource(R.string.chat_script_manager_title)) },
            text = {
                if (scriptEntries.isEmpty()) {
                    Text(stringResource(R.string.chat_script_manager_empty))
                } else {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        scriptEntries.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "≈${entry.contentLength} · ${entry.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = entry.enabled,
                                    onCheckedChange = { viewModel.toggleCharacterScript(entry.path) },
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.chat_script_manager_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScriptManager = false }) { Text(stringResource(R.string.nav_got_it)) }
            },
        )
    }
    if (showQuickSettings) {
        ChatQuickSettingsPanel(
            worldBooks = state.worldBooks,
            boundWorldBookName = (state.currentSession?.metadata?.get("world_info")
                as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
            presets = state.presets,
            selectedPresetId = state.selectedPreset?.id,
            personas = state.personas,
            selectedPersonaId = state.selectedPersona?.id,
            onBindWorldBook = { name ->
                viewModel.setChatWorldBook(name)
                showQuickSettings = false
            },
            onSelectPreset = { presetId ->
                viewModel.selectPreset(presetId)
                showQuickSettings = false
            },
            onSelectPersona = { personaId ->
                viewModel.selectPersona(personaId)
                showQuickSettings = false
            },
            onOpenReasoning = {
                showQuickSettings = false
                showReasoning = true
            },
            reasoningLabel = reasoningEffortLabel(sessionReasoningEffort(state)),
            onDismiss = { showQuickSettings = false },
        )
    }
    if (showChatSettings) {
        ChatSettingsSheet(
            currentModel = state.providerConfig?.model,
            currentReasoningLabel = reasoningEffortLabel(sessionReasoningEffort(state)),
            ttsProviderConfigured = (state.selectedProvider == app.tellev.core.provider.ProviderCatalog.OPENAI_COMPATIBLE ||
                state.selectedProvider.startsWith("custom:")) && state.providerConfig?.apiKey?.isNotBlank() == true,
            onOpenModel = {
                showChatSettings = false
                showModelPicker = true
            },
            onOpenImageGen = {
                showChatSettings = false
                onOpenImageGenSettings()
            },
            onOpenUsage = {
                showChatSettings = false
                onOpenUsageStats()
            },
            onDismiss = { showChatSettings = false },
        )
    }
    if (showReasoning) {
        ReasoningEffortSliderPopup(
            current = sessionReasoningEffort(state),
            modelLabel = state.providerConfig?.model?.takeIf(String::isNotBlank)
                ?: state.selectedProvider,
            onSelect = { effort ->
                viewModel.setSessionReasoningEffort(effort)
                showReasoning = false
            },
            onOpenModelPicker = {
                showReasoning = false
                showModelPopup = true
            },
            onDismiss = { showReasoning = false },
        )
    }
    if (showContextPopover) {
        ContextPopover(
            segments = contextSegments(
                state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) },
                state.worldBooks,
            ),
            onDismiss = { showContextPopover = false },
        )
    }
    if (showModelPopup) {
        ModelPickerPopup(
            providerLabel = state.providerConfig?.providerType ?: state.selectedProvider,
            currentModel = state.providerConfig?.model,
            recentModels = recentModelIds(state.generationMetrics),
            customEndpoint = state.selectedProvider.startsWith("custom:"),
            onSelect = { model ->
                viewModel.selectModel(model)
                showModelPopup = false
            },
            onDismiss = { showModelPopup = false },
        )
    }
    if (showGenerationMetrics) {
        GenerationMetricsDialog(
            latest = state.latestGenerationMetrics,
            history = state.generationMetrics,
            aggregate = state.generationMetricsAggregate,
            onDismiss = { showGenerationMetrics = false },
        )
    }
    if (showContextSheet) {
        ContextBudgetSheet(
            snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) },
            onDismiss = { showContextSheet = false },
            onOpenFullViewer = {
                showContextSheet = false
                showContextViewer = true
            },
        )
    }
    if (showContextViewer) {
        ContextViewerDialog(
            snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) },
            messages = state.messages,
            onDismiss = { showContextViewer = false },
            onJumpToMessage = { index ->
                showContextViewer = false
                scope.launch { listState.animateScrollToItem(index) }
            },
        )
    }
}

@Composable
private fun CharacterScriptsDisabledBanner(onEnable: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_script_disabled_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnable) {
                Text(stringResource(R.string.chat_script_consent_enable))
            }
        }
    }
}


/**
 * dsh 复刻（参考图一输入栏下方的统计行）：左组「⏱ N 轮 N 步 · tok/s」，
 * 右组「🗄 N tok · 缓存命中 N%」。左组点开生成指标弹窗，右组点开上下文分段浮层。
 */
@Composable
private fun ChatStatsBar(
    state: ChatUiState,
    onOpenMetrics: () -> Unit,
    onOpenContext: () -> Unit,
) {
    val rounds = state.messages.count { it.role == app.tellev.core.model.MessageRole.User }
    val steps = state.messages.count {
        it.role == app.tellev.core.model.MessageRole.Character ||
            it.role == app.tellev.core.model.MessageRole.Assistant
    }
    if (rounds == 0 && steps == 0) return
    val latest = state.latestGenerationMetrics
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val speed = latest?.tokensPerSecond?.let { "%.0f tok/s".format(it) }
    val tokens = snapshot?.estimatedTokenCount
    val cache = latest?.cacheHitRate?.let { "%.0f%%".format(it * 100) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onOpenMetrics),
        ) {
            Icon(
                Icons.Outlined.Timer,
                contentDescription = stringResource(R.string.metrics_title),
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = buildString {
                    append(UiStrings.get(S.chat_stats_round, rounds))
                    append(' ')
                    append(UiStrings.get(S.chat_stats_step, steps))
                    speed?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onOpenContext),
        ) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = stringResource(R.string.ctx_used_pct, ""),
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = buildString {
                    if (tokens != null) {
                        val text = if (tokens >= 1000) "%.1fK".format(tokens / 1000.0) else tokens.toString()
                        append(UiStrings.get(S.chat_stats_tokens, text))
                        cache?.let { append(" · " + UiStrings.get(S.chat_stats_cache_hit, it)) }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

/** 最后一条角色/助手回复才给「继续生成」。 */
internal fun canContinueResponse(messages: List<app.tellev.core.model.ChatMessage>, index: Int): Boolean {
    if (index !in messages.indices || index != messages.lastIndex) return false
    val role = messages[index].role
    return role == app.tellev.core.model.MessageRole.Character ||
        role == app.tellev.core.model.MessageRole.Assistant
}

/** 当前会话的推理档位 override（无 override 时跟随预设 = Auto）。 */
internal fun sessionReasoningEffort(state: ChatUiState): ReasoningEffort =
    ReasoningSupport.sessionOverrideFrom(state.currentSession?.metadata) ?: ReasoningEffort.Auto


/** 输入栏右簇的上下文圆环占用（无快照时 null，不渲染圆环）。 */
internal fun contextUsageRatio(state: ChatUiState): Float? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val limit = snapshot?.contextTokenLimit?.takeIf { it > 0 } ?: return null
    val used = snapshot.estimatedTokenCount?.toFloat() ?: return null
    return (used / limit).coerceIn(0f, 1f)
}
