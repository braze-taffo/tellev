package app.tellev.feature.chat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.core.model.Attachment
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier
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
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
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
                isLoading = state.isLoading,
                onCharacterSelected = { viewModel.selectCharacter(it) },
                modifier = Modifier.padding(padding),
            )
        } else {
            ChatContentScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun CharacterPickerScreen(
    characters: List<app.tellev.core.model.CharacterSummary>,
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
                                )
                            }
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
    modifier: Modifier = Modifier,
) {
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

    Column(modifier = modifier.fillMaxSize().imePadding()) {
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
            val htmlPanelMaxHeight = if (maxHeight > 112.dp) maxHeight - 112.dp else maxHeight

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
                        disabledRegexScriptIds = state.disabledRegexScriptIds,
                        htmlPanelMaxHeight = htmlPanelMaxHeight,
                        onSwipeLeft = { viewModel.swipeMessage(index, 1) },
                        onSwipeRight = { viewModel.swipeMessage(index, -1) },
                        onEdit = {
                            editingMessageIndex = index
                            editTextField = message.content
                        },
                        onDelete = { viewModel.deleteMessage(index) },
                    )
                }
            }

            if (state.isGenerating && state.streamingText.isNotEmpty()) {
                item(key = "streaming") {
                    StreamingBubble(
                        text = state.streamingText,
                        characterName = state.selectedCharacter?.name ?: "助手",
                    )
                }
            }

            if (state.isGenerating && state.streamingText.isEmpty()) {
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
    disabledRegexScriptIds: Set<String>,
    htmlPanelMaxHeight: Dp,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
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
                Text(
                    text = message.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
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

        val rawText = message.swipes.getOrNull(message.swipeIndex) ?: message.content
        val displayText = CharacterRegexApplier.applyForDisplay(
            rawText,
            message.role,
            character,
            disabledRegexScriptIds,
        )
        val renderSegments = TavernRenderParser.parse(displayText)
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 12.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                TavernMessageContent(
                    segments = renderSegments,
                    availableMaxHeight = htmlPanelMaxHeight,
                    isUser = isUser,
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(dragModifier),
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isUser) 12.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 12.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                TavernMessageContent(
                    segments = renderSegments,
                    availableMaxHeight = htmlPanelMaxHeight,
                    isUser = isUser,
                )
            }
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
) {
    Column {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is TavernRenderSegment.Text -> {
                    val text = segment.text
                    // AI messages with Markdown syntax render via the WebView (commonmark -> HTML),
                    // reusing TavernHtmlPanel. Plain text (user messages, short replies) stays on
                    // the cheap native Text() to avoid spinning up a WebView per bubble.
                    if (!isUser && MarkdownRenderer.looksLikeMarkdown(text)) {
                        TavernHtmlPanel(
                            html = MarkdownRenderer.render(text),
                            availableMaxHeight = availableMaxHeight,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = text,
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
                    ReasoningBlock(content = segment.content)
                }
                is TavernRenderSegment.Frontend -> {
                    TavernHtmlPanel(
                        html = segment.html,
                        availableMaxHeight = availableMaxHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                    text = content,
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

private class TavernHeightBridge(
    private val onHeightChanged: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun resize(height: Int) {
        if (height <= 0) return
        mainHandler.post { onHeightChanged(height) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TavernHtmlPanel(
    html: String,
    availableMaxHeight: Dp,
) {
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
                            // 先假定由 WebView 接管手势；MOVE 时若 WebView 在该方向上
                            // 没有可滚动内容，再把拦截权交还给外层聊天列表。
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = event.y - lastTouchY
                            lastTouchY = event.y
                            val canScrollInDirection = when {
                                dy > 0 -> view.canScrollVertically(-1)
                                dy < 0 -> view.canScrollVertically(1)
                                else -> true
                            }
                            if (!canScrollInDirection) {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
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
                addJavascriptInterface(TavernHeightBridge { height -> contentHeightPx = height }, "TellevBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(tavernResizeScript(), null)
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://message.tellev.local/",
                wrapTavernHtml(html),
                "text/html",
                "UTF-8",
                null,
            )
        },
    )
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

private fun wrapTavernHtml(html: String): String {
    val hostHead = """
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <style id="tellev-host-style">
            html, body {
                width: 100%;
                min-width: 0;
                margin: 0;
                padding: 0;
                background: transparent;
                color: #f7f7f7;
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
        </style>
    """.trimIndent()

    if (html.contains("<html", ignoreCase = true)) {
        return if (html.contains("</head>", ignoreCase = true)) {
            html.replace(Regex("""</head>""", RegexOption.IGNORE_CASE), "$hostHead\n</head>")
        } else {
            val match = Regex("""<html([^>]*)>""", RegexOption.IGNORE_CASE).find(html)
            if (match == null) {
                "<html><head>$hostHead</head>$html</html>"
            } else {
                val replacement = "<html${match.groupValues[1]}>\n<head>\n$hostHead\n</head>"
                html.replaceRange(match.range, replacement)
            }
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

private fun tavernResizeScript(): String = """
    (function() {
        function pageHeight() {
            var body = document.body || {};
            var doc = document.documentElement || {};
            return Math.ceil(Math.max(
                body.scrollHeight || 0,
                body.offsetHeight || 0,
                doc.clientHeight || 0,
                doc.scrollHeight || 0,
                doc.offsetHeight || 0
            ));
        }
        function postHeight() {
            if (window.TellevBridge && window.TellevBridge.resize) {
                window.TellevBridge.resize(pageHeight());
            }
        }
        if (!window.__tellevResizeInstalled) {
            window.__tellevResizeInstalled = true;
            window.addEventListener('load', postHeight);
            window.addEventListener('resize', postHeight);
            if (window.ResizeObserver) {
                var observer = new ResizeObserver(postHeight);
                observer.observe(document.documentElement);
                if (document.body) observer.observe(document.body);
            }
            setTimeout(postHeight, 50);
            setTimeout(postHeight, 250);
            setTimeout(postHeight, 1000);
        }
        postHeight();
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
    characterName: String,
) {
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
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = 4.dp,
                bottomEnd = 12.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
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
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
            )

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
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
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
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
