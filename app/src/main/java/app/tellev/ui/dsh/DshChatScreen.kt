package app.tellev.ui.dsh

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.tellev.core.provider.ReasoningFamily
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.tts.TtsPlaybackState
import app.tellev.core.tts.TtsRuntime
import app.tellev.core.tts.ttsUserMessage
import app.tellev.feature.chat.ChatUiState
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.CharacterCardInterfaceDialog
import app.tellev.feature.chat.ContextViewerDialog
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
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/**
 * dsh 聊天界面总装（官方 harness 结构复刻）：
 * 抽屉 + 消息列（左右 20dp/上下 16dp，项距 12px）+ composer（28 圆角白卡）+
 * dock 统计条 + 三弹层 + 集合键 sheet。数据层走既有 ChatViewModel。
 */
@Composable
fun DshChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenWorldBooks: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenImageGenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val graph = LocalTellevGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val dataRoot = graph.dataStore.layout.root.toFile()
    // 外观设置（设置 → 外观）里的气泡不透明度与字号。官方默认正文 14px
    // （--dsh-content-font-size），应用设置的轴是 16 基准，这里 16→14 换算对齐。
    val bubbleAlpha by graph.chatBubbleAlphaFlow.collectAsState()
    val chatFontSizeSp by graph.chatFontSizeSpFlow.collectAsState()
    val contentSp = (chatFontSizeSp * 14f / 16f).roundToInt()
    val userBubbleMaxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.82f

    var inputText by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<DshPendingAttachment>()) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showUsagePanel by remember { mutableStateOf(false) }
    // 轮级用量详情（dsh TurnUsagePanel 弹层）。
    var turnUsageFor by remember { mutableStateOf<Pair<app.tellev.feature.chat.ChatTokenUsageLedger.Buckets, Long>?>(null) }
    // 真实附件（含落盘路径/元数据）与展示条 DshPendingAttachment 一一对应。
    var pendingRealAttachments by remember { mutableStateOf(listOf<app.tellev.core.model.Attachment>()) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    // 顶栏合并键（预设/用户设定/世界书）：集合 sheet + 各自二级弹层。
    var showCollectionsSheet by remember { mutableStateOf(false) }
    var showPersonaEditor by remember { mutableStateOf(false) }
    var showPresetEditor by remember { mutableStateOf(false) }
    var showWorldSheet by remember { mutableStateOf(false) }
    // 消息操作行「更多」菜单的锚点消息。
    var messageMenuIndex by remember { mutableStateOf<Int?>(null) }
    var showMemoryDialog by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showContextViewer by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showScriptManager by remember(state.currentSession?.id) { mutableStateOf(false) }
    var showMetrics by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    var showOptimize by remember { mutableStateOf(false) }
    // 优化版本链：上次「应用」的结果作为下一轮迭代的 basePrompt。
    var lastOptimizedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var showCardInterface by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<app.tellev.core.model.ChatSessionSummary?>(null) }
    // 会话重命名（抽屉长按 / ⋮ 菜单）。
    var sessionPendingRename by remember { mutableStateOf<app.tellev.core.model.ChatSessionSummary?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }
    var followLatest by remember(state.currentSession?.id) { mutableStateOf(true) }

    // 语音输入（长按输入框）。
    val voice = remember { VoiceInputController(onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } }) }
    DisposableEffect(Unit) { onDispose { voice.release() } }
    // 语音输入（长按输入框）。基线=开始识别时的草稿：partial 整体替换基线后的
    // 临时段，final 只提交一次——旧版把 final 追加在 partial 后会重复整句。
    var voiceBaseText by remember { mutableStateOf<String?>(null) }
    fun startVoiceListening() {
        voiceBaseText = inputText
        voice.start(
            context,
            onPartial = { partial -> voiceBaseText?.let { inputText = it + partial } },
            onFinal = { final ->
                voiceBaseText?.let { inputText = it + final }
                voiceBaseText = null
            },
        )
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startVoiceListening()
        } else {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.chat_voice_permission)) }
        }
    }
    fun beginVoice() {
        if (voice.listening.value) {
            voice.stop()
            return
        }
        if (!voice.ensure(context)) {
            voice.unsupported(context)
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceListening()
        } else {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
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
            // Strip 只需展示名/类型/缩略图；附件本体已在 dataRoot 下（builder 落盘）。
            pendingAttachments = pendingAttachments + DshPendingAttachment(
                id = attachment.id,
                name = attachment.name,
                isImage = attachment.mimeType.startsWith("image/"),
                file = java.io.File(dataRoot, attachment.relativePath).takeIf { it.exists() },
            )
            pendingRealAttachments = pendingRealAttachments + attachment
        } else {
            snackbar.showSnackbar(context.getString(R.string.chat_attach_failed, ""))
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { addFromUri(it) } }
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportChatLog(context.contentResolver, uri)
    }
    // 独立会话存档：完整 ST JSONL（头行+消息行），可直接重新导入。
    val exportArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportSessionArchive(context.contentResolver, uri)
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
            if (viewModel.sendMessage(trimmed, pendingRealAttachments)) {
                // Detach any in-flight recognition first: its late partial/final
                // callbacks rebuild the field from voiceBaseText (the draft that
                // was just sent), so the sent text would reappear in the box.
                voiceBaseText = null
                if (voice.listening.value) voice.stop()
                inputText = ""
                pendingAttachments = emptyList()
                pendingRealAttachments = emptyList()
            }
        }
    }
    // 编辑用户消息后的「重新发送」：裁掉该消息及其后所有消息，把正文放回
    // 输入框（不自动发出，用户可改可发），旧附件一并不再重发。
    fun resendUserMessage(message: app.tellev.core.model.ChatMessage) {
        viewModel.trimMessagesAfter(message.id)
        inputText = message.reasoningParts().body
        pendingAttachments = emptyList()
        pendingRealAttachments = emptyList()
    }
    fun startVoice() {
        beginVoice()
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }

    val listState = rememberLazyListState()
    // 用户手动拖动即停止跟随新消息；拖回底部（距底 2 项内）恢复跟随。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLatest = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 2
        }.collect { atBottom -> if (atBottom) followLatest = true }
    }
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
    // 抽屉打开时刷新按角色分组的会话树（跨角色卡全部会话）。
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == androidx.compose.material3.DrawerValue.Open) viewModel.loadSessionGroups()
    }
    DshDrawer(
        groups = state.sessionGroups,
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
        onRenameSession = { sessionPendingRename = it },
        onTogglePinned = { viewModel.togglePinnedSession(it) },
        onOpenPlugins = onOpenPlugins,
        onExportLog = {
            exportLog.launch("chat-log-" + System.currentTimeMillis() + ".json")
            scope.launch { drawerState.close() }
        },
        onOpenSettings = {
            scope.launch { drawerState.close() }
            onOpenSettings()
        },
        drawerState = drawerState,
    ) {
        androidx.compose.material3.Scaffold(
            containerColor = Dsh.bgBase,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // 官方顶栏语义：透明底 + 0.5px border-l3 发丝线；左=返回+抽屉，
                // 中=当前 crumb（14sp w500 primary），右=世界书+更多。
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 0.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 返回键第一位（用户原意：抽屉图标保持原位只整体左移，不调换）。
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_back),
                                tint = Dsh.textSecondary, modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                DshIcons.PanelLeft, stringResource(R.string.dsh_drawer_open_cd),
                                tint = Dsh.textSecondary, modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            state.selectedCharacter?.name.orEmpty(),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = Dsh.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showCollectionsSheet = true }) {
                            Icon(
                                Icons.Default.Tune, stringResource(R.string.chat_collections_title),
                                tint = Dsh.textSecondary, modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert, stringResource(R.string.chat_more_options),
                                tint = Dsh.textSecondary, modifier = Modifier.size(18.dp),
                            )
                        }
                        DshMoreMenu(
                            expanded = showMoreMenu,
                            onDismiss = { showMoreMenu = false },
                            state = state,
                            onCardInterface = { showMoreMenu = false; showCardInterface = true },
                            onMemory = { showMoreMenu = false; viewModel.refreshMemory(); showMemoryDialog = true },
                            onContextViewer = { showMoreMenu = false; showContextViewer = true },
                            onScriptManager = { showMoreMenu = false; showScriptManager = true },
                            onMetrics = { showMoreMenu = false; showMetrics = true },
                            onRenameSession = {
                                showMoreMenu = false
                                state.currentSession?.let { s -> sessionPendingRename = s.toSummary() }
                            },
                            onSaveArchive = {
                                showMoreMenu = false
                                exportArchive.launch("chat-archive-" + System.currentTimeMillis() + ".jsonl")
                            },
                            onGenerateImage = if (state.imageGenAvailable) {
                                { showMoreMenu = false; showImageDialog = true }
                            } else null,
                            onUsageStats = { showMoreMenu = false; showUsagePanel = true },
                            onBackground = { showMoreMenu = false; backgroundPicker.launch("image/*") },
                            onClearBackground = { showMoreMenu = false; viewModel.clearChatBackground() },
                            onDeleteSession = { showMoreMenu = false; state.currentSession?.let { s -> sessionPendingDelete = s.toSummary() } },
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Dsh.borderL3),
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.selectedCharacter == null) {
                    // Character selection loads on the IO dispatcher; the card tap
                    // navigates here first, so show progress instead of the
                    // dead-end prompt while it resolves.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (state.isLoading) {
                            androidx.compose.material3.CircularProgressIndicator()
                        } else {
                            TextButton(onClick = onBack) { Text(stringResource(R.string.chat_select_character)) }
                        }
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
                            Box(Modifier.fillMaxSize().background(Dsh.bgBase.copy(alpha = 0.65f)))
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 20.dp, vertical = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.characterScriptsDisabled != null) {
                                item(key = "character_scripts_disabled") {
                                    Surface(
                                        color = Dsh.blue.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                stringResource(R.string.chat_script_disabled_banner),
                                                fontSize = 13.sp,
                                                color = Dsh.textSecondary,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = viewModel::requestScriptConsentPrompt) {
                                                Text(stringResource(R.string.chat_script_consent_enable), fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
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
                                        bubbleAlpha = bubbleAlpha,
                                        contentSp = contentSp,
                                        userBubbleMaxWidth = userBubbleMaxWidth,
                                        onBoundaryDrag = { },
                                        onEdit = { editingIndex = index; editText = message.content },
                                        onResend = {
                                            editingIndex = null
                                            resendUserMessage(message)
                                        },
                                        onShowTurnUsage = { buckets ->
                                            turnUsageFor = buckets to app.tellev.feature.chat.ChatTokenUsageLedger.messageReasoning(message.metadata)
                                        },
                                        onOpenContext = { showContext = true },
                                        onSwipe = { dir -> viewModel.swipeMessage(index, dir) },
                                        menuExpanded = messageMenuIndex == index,
                                        onMenuChange = { messageMenuIndex = if (it) index else null },
                                        onFork = {
                                            messageMenuIndex = null
                                            state.currentSession?.id?.let { id ->
                                                viewModel.forkSession(id, index + 1)
                                            }
                                        },
                                        onDelete = {
                                            messageMenuIndex = null
                                            viewModel.deleteMessage(index)
                                        },
                                    )
                                }
                            }
                            if (state.isGenerating && (state.streamingText.isNotEmpty() || state.streamingReasoning.isNotEmpty())) {
                                item(key = "streaming") {
                                    // 回复框布局：头像一行置左，流式气泡在头像正下方
                                    // 居中（不再右侧跟随），与用户要求一致。
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        CharacterAvatar(
                                            file = state.characterAvatarFiles[state.selectedCharacter?.id],
                                            fallbackText = state.selectedCharacter?.name.orEmpty(),
                                            modifier = Modifier.size(34.dp),
                                            shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                                            fallbackTextStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Dsh.textPrimary,
                                            ),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        androidx.compose.foundation.layout.BoxWithConstraints(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            StreamingBubble(
                                        macroContext = renderMacroContext,
                                        text = state.streamingText,
                                        reasoning = state.streamingReasoning,
                                        characterName = state.selectedCharacter?.name ?: "",
                                        character = state.selectedCharacter,
                                        preset = state.selectedPreset,
                                        bubbleAlpha = bubbleAlpha,
                                        chatFontSizeSp = contentSp,
                                        userName = state.selectedPersona?.name ?: "User",
                                        availableMaxHeight = 480.dp,
                                        centerText = true,
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
                        attachments = pendingAttachments,
                        onRemoveAttachment = { id ->
                            pendingAttachments = pendingAttachments.filterNot { it.id == id }
                            pendingRealAttachments = pendingRealAttachments.filterNot { it.id == id }
                        },
                        onPickFile = { pickFile.launch(arrayOf("*/*")) },
                        onOptimize = { showOptimize = true },
                        onOpenModelMenu = { showModelMenu = true },
                        onOpenContext = { showContext = true },
                        onLongPressVoice = ::startVoice,
                        listening = voice.listening.value,
                        onCancelVoice = { voiceBaseText = null; voice.stop() },
                        statsLeft = statsLeft(state),
                        statsRight = statsRight(state),
                        onStatsLeft = { showMetrics = true },
                        onStatsRight = { showUsagePanel = true },
                    )
                }
            }

        }
    }

    if (showModelMenu) {
        // 合并弹层（图一+图二）：root=思考滑杆+模型行，钻入=分组模型列表；
        // 长按模型行指派自定义供应商分组。打开时拉取完整模型目录。
        LaunchedEffect(state.currentSession?.id) { viewModel.refreshModelCatalog() }
        val family = ReasoningSupport.familyFor(
            state.providerConfig?.providerType ?: state.selectedProvider,
            state.providerConfig?.options ?: JsonObject(emptyMap()),
        )
        val effortLevels = remember(family) { ReasoningSupport.uiLevels(family) }
        val effortOnLabel = stringResource(R.string.chat_reasoning_effort_on)
        val currentEffort = sessionReasoningEffortOf(state)
        val currentEffortLabel = if (family == ReasoningFamily.DeepSeek &&
            currentEffort != ReasoningEffort.Auto && currentEffort != ReasoningEffort.Off
        ) {
            // DeepSeek 中继只有开/关；Low..Max 在线上同义，标签统一为「开」。
            effortOnLabel
        } else {
            dshEffortLabel(currentEffort)
        }
        DshModelEffortMenu(
            options = modelOptions(
                state,
                groupCurrent = stringResource(R.string.model_group_current),
                groupRecent = stringResource(R.string.model_group_recent),
            ),
            groups = state.modelGroups,
            catalog = state.modelCatalog,
            currentModel = state.providerConfig?.model,
            currentEffort = currentEffort,
            effortLevels = effortLevels,
            currentEffortLabel = currentEffortLabel,
            modelLabel = state.providerConfig?.model?.takeIf(String::isNotBlank)
                ?: state.selectedProvider.takeIf(String::isNotBlank),
            onSelectModel = { viewModel.selectModel(it); showModelMenu = false },
            // 滑杆提交后弹层保持打开（连续拖动调档），与上游 ComposerSlider 一致。
            onSelectEffort = { viewModel.setSessionReasoningEffort(it) },
            onRefreshCatalog = { viewModel.refreshModelCatalog() },
            onAssignGroup = { modelId, groupId, member ->
                viewModel.assignModelGroup(modelId, groupId, member)
            },
            onCreateGroup = { label -> viewModel.createModelGroup(label) },
            onDismiss = { showModelMenu = false },
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
    // 卡内脚本首次装载/变更后的确认弹窗：approve/deny ViewModel 方法一直存在，
    // dsh 重写时只接了横幅「启用」的 requestScriptConsentPrompt,弹窗本身漏接,
    // 授权流程死锁。这里恢复旧 ChatScreen 的三键语义(批准/拒绝并记住/暂不)。
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
    if (showMetrics) {
        GenerationMetricsDialog(
            latest = sessionLatestMetrics(state) ?: state.latestGenerationMetrics,
            history = state.generationMetrics,
            aggregate = state.generationMetricsAggregate,
            onDismiss = { showMetrics = false },
        )
    }
    turnUsageFor?.let { (buckets, reasoning) ->
        DshTurnUsageDialog(
            buckets = buckets,
            reasoningTokens = reasoning,
            onDismiss = { turnUsageFor = null },
        )
    }
    if (showUsagePanel) {
        // 本会话口径：只算 sessionId 匹配的记录；今日=自然日内全部会话；累计=明细全量。
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val todayMetrics = state.generationMetrics.filter {
            java.time.Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() == today
        }
        val sessionId = state.currentSession?.id
        val ledgerBuckets = app.tellev.feature.chat.ChatTokenUsageLedger.sessionBuckets(state.currentSession?.metadata)
        val sessionReasoning = state.generationMetrics
            .filter { it.sessionId == sessionId }
            .sumOf { it.reasoningTokens?.toLong() ?: 0L }
        val sessionAllEstimated = state.generationMetrics
            .filter { it.sessionId == sessionId }
            .let { it.isNotEmpty() && it.all { m -> m.isEstimate } }
        DshUsagePanel(
            sessionLabel = stringResource(R.string.dsh_usage_session),
            sessionBuckets = ledgerBuckets,
            sessionReasoningTokens = sessionReasoning,
            sessionEstimated = sessionAllEstimated,
            todayMetrics = todayMetrics,
            allMetrics = state.generationMetrics,
            detailBufferNote = stringResource(R.string.dsh_usage_buffer_note, state.generationMetrics.size),
            onClear = {
                viewModel.clearAllGenerationMetrics {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.dsh_usage_cleared)) }
                }
            },
            onDismiss = { showUsagePanel = false },
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
            initialBasePrompt = lastOptimizedPrompt,
            onRun = { options, onPreview, onDone -> viewModel.optimizeDraft(inputText, options, onPreview, onDone) },
            onCancelRun = { viewModel.cancelPromptOptimization() },
            onApply = {
                inputText = it
                lastOptimizedPrompt = it
                showOptimize = false
            },
            onDismiss = { showOptimize = false },
        )
    }
    if (showCardInterface) {
        val view = state.characterUiExtensionId?.let { id ->
            (graph.extensionHost as? app.tellev.core.extension.WebViewJsExtensionHost)?.webViewForUi(id)
        }
        if (view != null) CharacterCardInterfaceDialog(view) { showCardInterface = false }
    }

    // ── 顶栏合并键的弹层：集合 sheet（预设点选）→ 用户设定编辑 / ST 世界书 ──
    if (showCollectionsSheet) {
        DshCollectionsSheet(
            title = stringResource(R.string.chat_collections_title),
            presetsLabel = stringResource(R.string.chat_panel_preset_section),
            personaLabel = stringResource(R.string.chat_panel_persona_entry),
            worldLabel = stringResource(R.string.chat_panel_world_entry),
            presetItems = state.presets.map { preset ->
                DshCollectionItem(
                    id = preset.id,
                    label = preset.name,
                    selected = preset.id == state.selectedPreset?.id,
                    onClick = { viewModel.selectPreset(preset.id) },
                )
            },
            personaName = state.selectedPersona?.name.orEmpty(),
            worldName = currentBoundWorldName(state),
            onOpenPersona = {
                showCollectionsSheet = false
                showPersonaEditor = true
            },
            onOpenWorld = {
                showCollectionsSheet = false
                showWorldSheet = true
            },
            onOpenPresetEditor = {
                showCollectionsSheet = false
                showPresetEditor = true
            },
            onDismiss = { showCollectionsSheet = false },
        )
    }
    if (showPresetEditor) {
        state.selectedPreset?.let { preset ->
            DshPresetEditorSheet(
                preset = preset,
                onSave = { temperature, topP, topK, topA, minP, repPen, repPenRange, maxTokens, maxContext, presencePen, freqPen, seed, reasoningEffort ->
                    viewModel.savePresetAdjustment(
                        presetId = preset.id,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        topA = topA,
                        minP = minP,
                        repetitionPenalty = repPen,
                        repetitionPenaltyRange = repPenRange,
                        maxTokens = maxTokens,
                        maxContextTokens = maxContext,
                        presencePenalty = presencePen,
                        frequencyPenalty = freqPen,
                        seed = seed,
                        reasoningEffort = reasoningEffort,
                    )
                },
                onSaveRaw = { raw, onError ->
                    viewModel.savePresetRaw(preset.id, raw, onError) { showPresetEditor = false }
                },
                onSavePrompts = { active, unused ->
                    viewModel.savePresetPrompts(preset.id, active, unused)
                },
                onSaveKey = { path, value ->
                    viewModel.savePresetRawKey(preset.id, path, value)
                },
                onDismiss = { showPresetEditor = false },
            )
        }
    }
    if (showPersonaEditor) {
        // 用户设定（dsh 弹层风格）：名称 + 设定内容可直接填写保存。
        val editing = state.selectedPersona
        var personaName by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
        var personaDesc by remember(editing?.id) { mutableStateOf(editing?.description.orEmpty()) }
        DshCollectionSheet(
            title = stringResource(R.string.persona_editor_title),
            items = emptyList(),
            onDismiss = { showPersonaEditor = false },
            footer = {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    OutlinedTextField(
                        value = personaName,
                        onValueChange = { personaName = it },
                        label = { Text(stringResource(R.string.persona_editor_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = personaDesc,
                        onValueChange = { personaDesc = it },
                        label = { Text(stringResource(R.string.persona_editor_desc)) },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(10.dp))
                    Surface(
                        onClick = {
                            viewModel.upsertPersona(editing?.id, personaName, personaDesc)
                            showPersonaEditor = false
                        },
                        enabled = personaName.isNotBlank() || personaDesc.isNotBlank(),
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.persona_editor_save),
                            fontSize = 14.sp,
                            color = Dsh.blue,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                }
            },
        )
    }
    if (showWorldSheet) {
        // 世界书（SillyTavern 条目布局）：选书即绑定，圆点切换条目启用。
        DshWorldBookSheet(
            books = state.worldBooks,
            boundName = currentBoundWorldName(state),
            onSelectBook = { viewModel.setChatWorldBook(it) },
            onToggleEntry = { bookId, entryId -> viewModel.toggleWorldBookEntry(bookId, entryId) },
            onSaveEntry = { bookId, entry -> viewModel.saveWorldBookEntry(bookId, entry) },
            onManage = onOpenWorldBooks,
            onDismiss = { showWorldSheet = false },
        )
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
    sessionPendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { sessionPendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.renameSession(session.id, renameText)
                        sessionPendingRename = null
                    },
                ) { Text(stringResource(R.string.chat_save)) }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingRename = null }) { Text(stringResource(R.string.chat_cancel)) }
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
    bubbleAlpha: Float,
    contentSp: Int,
    userBubbleMaxWidth: androidx.compose.ui.unit.Dp,
    onBoundaryDrag: (Float) -> Unit,
    onEdit: () -> Unit,
    onResend: () -> Unit,
    onShowTurnUsage: (app.tellev.feature.chat.ChatTokenUsageLedger.Buckets) -> Unit,
    onOpenContext: () -> Unit,
    onSwipe: (Int) -> Unit,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
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
    // QQ 式对话：左侧角色头像 / 右侧用户（persona）头像。
    val characterAvatarFile = state.characterAvatarFiles[state.selectedCharacter?.id]
    val characterName = state.selectedCharacter?.name.orEmpty()
    val personaName = state.selectedPersona?.name ?: "User"

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isUser) {
            // 官方 userRow：右对齐；气泡 deepseek-50、radius-xl=20、padding 10/16、
            // 14sp/22 行高；气泡右侧 persona 头像，下方 6px 时间+复制。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(Dsh.RADIUS_XL.dp),
                        color = Dsh.userBubble.copy(alpha = Dsh.userBubble.alpha * bubbleAlpha),
                        modifier = Modifier.widthIn(max = userBubbleMaxWidth),
                    ) {
                        Text(
                            text = message.reasoningParts().body,
                            fontSize = contentSp.sp,
                            lineHeight = (contentSp + 8).sp,
                            color = Dsh.textPrimary.copy(alpha = Dsh.textPrimary.alpha * bubbleAlpha),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MessageTimeText(message)
                        ActionKey(Icons.Default.ContentCopy, R.string.chat_copy_message) {
                            clipboard.setText(AnnotatedString(message.content))
                        }
                        // 编辑/重发/删除常驻：发送后立即生成期间也可见（重发与编辑
                        // 的落盘通道自带 isLoading 竞态门禁，无并发风险）。
                        ActionKey(Icons.Default.Edit, R.string.chat_edit_message, onClick = onEdit)
                        ActionKey(
                            Icons.Default.Refresh,
                            R.string.dsh_user_resend,
                            onClick = onResend,
                        )
                        ActionKey(
                            Icons.Default.Delete,
                            R.string.chat_delete_message,
                            onClick = onDelete,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                CharacterAvatar(
                    file = null,
                    fallbackText = personaName,
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                    fallbackTextStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Dsh.textPrimary),
                )
            }
        } else {
            // 助手消息：无气泡、无头像，正文满宽；轮尾动作行。
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

            // QQ 式：左侧角色头像+名标，右侧内容列（正文/滑动/操作行都随列缩进）。
            Row(verticalAlignment = Alignment.Top) {
                CharacterAvatar(
                    file = characterAvatarFile,
                    fallbackText = characterName,
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                    fallbackTextStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Dsh.textPrimary,
                    ),
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        characterName,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Dsh.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(2.dp))
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
                    bubbleAlpha = bubbleAlpha,
                    chatFontSizeSp = contentSp,
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
                    centerText = true,
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
            // 轮尾动作行（官方 .actions：高 28、gap 8、图标 15、margin-top 4、
            // -6px 光学对齐）：时间 + 音频/重新生成/继续/复制/编辑/更多。
            // 反馈、分享、上下文、分支、删除收进「更多」（extraActions 语义）。
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .offset(x = (-6).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 轮级用量 pill（dsh TurnUsagePanel）：读这条消息自己的
                // tellev_turn_usage；无样本（估算/未上报）不渲染。
                val turnBuckets = remember(message.id, message.swipeIndex) {
                    app.tellev.feature.chat.ChatTokenUsageLedger.messageBuckets(message.metadata)
                }
                DshTurnUsagePill(
                    buckets = turnBuckets,
                    reasoningTokens = app.tellev.feature.chat.ChatTokenUsageLedger.messageReasoning(message.metadata),
                    onClick = { onShowTurnUsage(turnBuckets) },
                )
                MessageTimeText(message, endStyle = true)
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
                ActionKey(Icons.Default.ContentCopy, R.string.chat_copy_message) { clipboard.setText(AnnotatedString(message.content)) }
                // 分支键直接放在动作行（原版 turn tail 的 branch 图标语义），
                // 不再只能从「更多」菜单进入。
                ActionKey(DshIcons.Branch, R.string.chat_fork_from_here) { onFork() }
                ActionKey(Icons.Default.Edit, R.string.chat_edit_message, onClick = onEdit)
                Box {
                    ActionKey(Icons.Default.MoreVert, R.string.chat_more_options) { onMenuChange(true) }
                    DshMessageMoreMenu(
                        expanded = menuExpanded,
                        onDismiss = { onMenuChange(false) },
                        feedback = feedback,
                        onFeedback = { value ->
                            viewModel.setMessageFeedback(index, if (feedback == value) null else value)
                        },
                        onShare = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, message.content)
                            }
                            runCatching { context.startActivity(android.content.Intent.createChooser(intent, null)) }
                        },
                        onOpenContext = onOpenContext,
                        onFork = onFork,
                        onDelete = onDelete,
                    )
                }
            }
                }
            }
        }
    }
}

/** 轮尾时间戳：start 位 13sp tertiary；end 位 12sp（官方 .timeEnd -1px）。 */
@Composable
private fun MessageTimeText(message: app.tellev.core.model.ChatMessage, endStyle: Boolean = false) {
    if (message.createdAtMillis <= 0L) return
    Text(
        text = java.time.Instant.ofEpochMilli(message.createdAtMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
        fontSize = (if (endStyle) 12 else 13).sp,
        lineHeight = 24.sp,
        color = Dsh.textTertiary,
        maxLines = 1,
        modifier = if (endStyle) Modifier else Modifier.padding(end = 4.dp),
    )
}

/**
 * 官方 .action：28×28 命中、radius-sm=8、图标 15、tertiary；
 * hover 面 interactive-bg-hover；激活态主色。
 */
@Composable
private fun ActionKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    active: Boolean = false,
    activeTint: Color = Dsh.blue,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (active) activeTint else Dsh.textTertiary,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 消息操作行「更多」菜单：反馈/分享/上下文/分支（forkAt 语义）/删除。 */
@Composable
private fun DshMessageMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    feedback: String?,
    onFeedback: (String) -> Unit,
    onShare: () -> Unit,
    onOpenContext: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_feedback_up), fontSize = 14.sp) },
            trailingIcon = {
                if (feedback == "up") Icon(Icons.Default.ThumbUp, null, tint = Dsh.blue, modifier = Modifier.size(15.dp))
            },
            onClick = { onFeedback("up"); onDismiss() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_feedback_down), fontSize = 14.sp) },
            trailingIcon = {
                if (feedback == "down") Icon(Icons.Default.ThumbDown, null, tint = Dsh.errorPrimary, modifier = Modifier.size(15.dp))
            },
            onClick = { onFeedback("down"); onDismiss() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_share), fontSize = 14.sp) },
            onClick = { onDismiss(); onShare() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.ctxview_title), fontSize = 14.sp) },
            onClick = { onDismiss(); onOpenContext() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_fork_from_here), fontSize = 14.sp) },
            onClick = { onDismiss(); onFork() },
        )
        HorizontalMenuDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_delete_message), fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun HorizontalMenuDivider() {
    androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
}

// ── ⋮ 菜单（集合键弹层入口 + 卡片/记忆/指标/背景/删除会话） ──

@Composable
private fun DshMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: ChatUiState,
    onCardInterface: () -> Unit,
    onGenerateImage: (() -> Unit)?,
    onUsageStats: () -> Unit,
    onMemory: () -> Unit,
    onContextViewer: () -> Unit,
    onScriptManager: () -> Unit,
    onMetrics: () -> Unit,
    onBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onRenameSession: () -> Unit,
    onSaveArchive: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // 集合键三入口：点击弹出对应底部 sheet（当前值随行展示）。
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.dsh_menu_generate_image), fontSize = 14.sp) },
            enabled = onGenerateImage != null,
            onClick = { onGenerateImage?.invoke() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.dsh_menu_usage_stats), fontSize = 14.sp) },
            onClick = onUsageStats,
        )
        HorizontalMenuDivider()
        if (state.characterUiExtensionId != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_card_interface), fontSize = 14.sp) },
                onClick = onCardInterface,
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_long_term_memory), fontSize = 14.sp) },
            onClick = onMemory,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.ctxview_title), fontSize = 14.sp) },
            onClick = onContextViewer,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.metrics_title), fontSize = 14.sp) },
            onClick = onMetrics,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_script_manager_title), fontSize = 14.sp) },
            onClick = onScriptManager,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_chat_background), fontSize = 14.sp) },
            onClick = onBackground,
        )
        if (state.chatBackgroundFile != null) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_clear_background), fontSize = 14.sp) },
                onClick = onClearBackground,
            )
        }
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_rename_session), fontSize = 14.sp) },
            onClick = onRenameSession,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_save_archive), fontSize = 14.sp) },
            onClick = onSaveArchive,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_delete_session), fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
            onClick = onDeleteSession,
        )
    }
}

/** 当前会话绑定的世界书名（metadata.world_info，ST world_info 语义）。 */
internal fun currentBoundWorldName(state: ChatUiState): String? =
    (state.currentSession?.metadata?.get("world_info") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

// ── 纯计算（原 ChatScreen 内函数的重制版） ────────────────────────

internal fun contextRatioOf(state: ChatUiState): Float? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    // 上限未配置（null）或等于内部兜底值时不画进度——那是占位不是模型上限。
    // 上限来自分层声明（档案/知识库/adapter/预设）；null=未知不画环。
    val limit = snapshot?.contextTokenLimit ?: return null
    if (limit <= 0) return null
    val used = snapshot.estimatedTokenCount?.toFloat() ?: return null
    return (used / limit).coerceIn(0f, 1f)
}

internal fun sessionReasoningEffortOf(state: ChatUiState): ReasoningEffort =
    ReasoningSupport.sessionOverrideFrom(state.currentSession?.metadata) ?: ReasoningEffort.Auto

internal fun modelOptions(
    state: ChatUiState,
    groupCurrent: String,
    groupRecent: String,
): List<app.tellev.feature.chat.ModelOption> {
    val current = state.providerConfig?.model?.takeIf(String::isNotBlank)
    return buildList {
        current?.let { add(app.tellev.feature.chat.ModelOption(it, groupCurrent, isCurrent = true)) }
        state.generationMetrics.asReversed().mapNotNull { it.model?.takeIf(String::isNotBlank) }
            .distinct().filter { it != current }.take(6)
            .forEach { add(app.tellev.feature.chat.ModelOption(it, groupRecent)) }
    }
}

/** 图三四元组：百分比 / 已用量 / 上限量 / (系统, 世界书, 消息)。 */
internal fun contextSegmentsOf(
    state: ChatUiState,
): Quadruple<Int, String, String, Triple<Long, Long, Long>>? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val total = snapshot?.estimatedTokenCount?.takeIf { it > 0 } ?: return null
    val limit = snapshot.contextTokenLimit?.takeIf { it > 0 } ?: return null
    // 与 contextRatioOf 同口径：上限未知（null）不出百分比。
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

/**
 * 底栏统计只反映当前会话：speed/cache 取本会话最近一次生成记录。
 * 旧记录（无 session 归属）与其它会话的记录都不再冒充当前会话的数据。
 */
internal fun sessionLatestMetrics(state: ChatUiState): app.tellev.core.metrics.GenerationMetrics? {
    val sessionId = state.currentSession?.id ?: return null
    return state.generationMetrics.lastOrNull { it.sessionId == sessionId }
}

internal fun statsLeft(state: ChatUiState): String? {
    val rounds = state.messages.count { it.role == MessageRole.User }
    val steps = state.messages.count {
        it.role == MessageRole.Character || it.role == MessageRole.Assistant
    }
    if (rounds == 0 && steps == 0) return null
    val latest = sessionLatestMetrics(state)
    // decode 速度（dsh ActivityPill 口径）：剔除 TTFT 等待，只算出字窗口；
    // 无 decode 计时的旧记录回落总时长口径。
    val speed = latest?.let { m ->
        val tps = m.decodeTokensPerSecond ?: m.tokensPerSecond
        tps?.let { (if (m.isEstimate) "≈" else "") + "%.0f tok/s".format(it) }
    }
    return "${UiStrings.get(S.chat_stats_round, rounds)} ${UiStrings.get(S.chat_stats_step, steps)}" +
        (speed?.let { " · $it" } ?: "")
}

internal fun statsRight(state: ChatUiState): String? {
    val snapshot = state.contextSnapshot?.takeIf { it.matches(state.currentSession?.id) }
    val tokens = snapshot?.estimatedTokenCount ?: return null
    // 上限来自分层声明（档案/知识库/adapter/预设），未知时不显示 x/y——
    // 生成链已不再写 1M 内部兜底，快照里的 limit 一律可信。
    val limit = snapshot.contextTokenLimit
    val usedLabel = if (limit != null && limit > 0) {
        UiStrings.get(S.chat_stats_tokens, dshCompactTokens(tokens.toLong()) + "/" + dshCompactTokens(limit.toLong()))
    } else {
        UiStrings.get(S.chat_stats_tokens, dshCompactTokens(tokens.toLong()))
    }
    // 缓存命中率走账本桶（dsh 口径：cacheRead/billedInput，诚实百分比，
    // 部分命中不会显示成 100%）。
    val buckets = app.tellev.feature.chat.ChatTokenUsageLedger.sessionBuckets(state.currentSession?.metadata)
    val cache = if (buckets.billedInput > 0L) {
        UiStrings.get(S.chat_stats_cache_hit, dshCacheHitPercent(buckets.cacheRead, buckets.billedInput) + "%")
    } else null
    return usedLabel + (cache?.let { " · $it" } ?: "")
}

// ── 语音控制器与附件工具 ────────────────────────────────────────

/**
 * 长按语音输入控制器。
 *
 * 文本合并语义（修复旧版重复拼接）：partial 是「本次识别到目前为止的完整
 * 中间结果」，每次应整体替换基线之后的临时段；final 只提交一次。基线
 * （开始识别时的草稿）由调用方持有，控制器只回调增量文本。
 *
 * 错误处理（修复旧版吞错）：识别错误不再只静默停表，映射成可读消息回调；
 * 出错后销毁识别器，下一次 ensure() 重建，避免 ERROR_CLIENT 后一直不可用。
 */
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
        if (!ensure(context)) {
            unsupported(context)
            return
        }
        val active = recognizer ?: return
        this.onPartial = onPartial
        this.onFinal = onFinal
        active.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening.value = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening.value = false }
            override fun onError(code: Int) {
                listening.value = false
                // ERROR_CLIENT 后识别器常处于坏状态：销毁待重建。
                if (code == android.speech.SpeechRecognizer.ERROR_CLIENT) {
                    release()
                }
                onError(messageFor(code, context))
            }
            override fun onResults(results: android.os.Bundle?) {
                listening.value = false
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    onFinal?.invoke(text)
                } else {
                    onError(context.getString(R.string.chat_voice_no_match))
                }
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
            // 跟随系统/应用语言，而不是识别服务默认语言。
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        }
        runCatching { active.startListening(intent) }
            .onFailure { listening.value = false; onError(it.message ?: context.getString(R.string.chat_voice_error_generic, -1)) }
    }

    private fun messageFor(code: Int, context: android.content.Context): String = when (code) {
        android.speech.SpeechRecognizer.ERROR_NO_MATCH,
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> context.getString(R.string.chat_voice_no_match)
        else -> context.getString(R.string.chat_voice_error_generic, code)
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
