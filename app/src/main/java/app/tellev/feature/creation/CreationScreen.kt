package app.tellev.feature.creation

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationHomeScreen(
    viewModel: CreationViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("AI 协作创作") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("与独立创作 agent 对话，制作角色卡、前端或世界书。", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.start(CreationKind.Character); onOpenEditor() }) { Text("新建角色卡") }
                OutlinedButton(onClick = { viewModel.start(CreationKind.WorldBook); onOpenEditor() }) { Text("新建世界书") }
            }
            Text("创作草稿", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = CreationSession::id) { session ->
                    Card(onClick = { viewModel.open(session.id); onOpenEditor() }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(session.card.name.ifBlank { session.worldName.ifBlank {
                                if (session.kind == CreationKind.Character) "未命名角色" else "未命名世界书"
                            } })
                            Text(
                                "${if (session.kind == CreationKind.Character) "角色卡" else "世界书"} · ${session.turns.size} 轮 · ${session.lore.size} 条世界书内容",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (session.sourceLength > 0) {
                                Text("原文已提炼 ${session.sourceCursor}/${session.sourceLength} 字符", style = MaterialTheme.typography.bodySmall)
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
fun CreationEditorScreen(
    viewModel: CreationViewModel,
    onBack: () -> Unit,
    onSaved: (CreationKind, String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val session = state.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember(session?.id) { mutableStateOf<Pair<CharacterExportFormat, ByteArray>?>(null) }
    fun writeExport(uri: Uri?, format: CharacterExportFormat) {
        val bytes = pendingExport?.takeIf { it.first == format }?.second
        pendingExport = null
        if (uri == null || bytes == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("无法写入所选文件")
                }
                viewModel.showInfo(if (format == CharacterExportFormat.Png) "PNG 角色卡已导出。" else "JSON 角色卡已导出。")
            } catch (e: Exception) {
                viewModel.showError("导出失败：${e.message}")
            }
        }
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeExport(it, CharacterExportFormat.Json)
    }
    val pngExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        writeExport(it, CharacterExportFormat.Png)
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readNBytes(12_000_001) }
                } ?: error("无法读取所选图片")
                require(source.size <= 12_000_000) { "图片超过 12 MB，请选择较小的文件。" }
                val png = withContext(Dispatchers.IO) {
                    app.tellev.util.decodeImageAsPng(source, maxEdge = 1024)
                } ?: error("无法解析所选图片")
                viewModel.setCoverPng(png)
            } catch (e: Exception) {
                viewModel.showError("设置封面失败：${e.message}")
            }
        }
    }
    fun safeExportName(): String = session?.card?.name.orEmpty()
        .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifBlank { "character" }
    fun prepareExport(format: CharacterExportFormat) {
        scope.launch {
            try {
                val bytes = viewModel.exportCharacter(format)
                pendingExport = format to bytes
                val name = safeExportName()
                if (format == CharacterExportFormat.Png) pngExport.launch("$name.png")
                else jsonExport.launch("$name.json")
            } catch (e: Exception) {
                viewModel.showError("导出失败：${e.message}")
            }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(session?.id) { tab = 0 }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (session?.kind == CreationKind.WorldBook) "创作世界书" else "创作角色卡") },
            navigationIcon = { TextButton(onClick = { viewModel.close(); onBack() }) { Text("返回") } },
            actions = {
                TextButton(onClick = { viewModel.saveArtifact(onSaved) }, enabled = session != null && !state.busy) {
                    Text(if (session?.kind == CreationKind.WorldBook) "保存到世界书" else "保存到角色列表")
                }
            })
    }) { padding ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { Text("正在读取草稿…") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (session.turns.lastOrNull()?.role == "user" && !state.busy) {
                TextButton(onClick = viewModel::retry) { Text("重试上一轮") }
            }
            state.info?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp)) }
            if (tab != 0 && (state.busy || state.modelPhase.isNotBlank())) {
                CreationCompactStatus(state, viewModel)
            } else if (tab != 0 && session.sourceLength > 0) {
                val savedFraction = session.sourceCursor.toFloat() / session.sourceLength
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(progress = { savedFraction }, modifier = Modifier.fillMaxWidth())
                    Text("原文已提炼 ${session.sourceCursor}/${session.sourceLength} 字符；可从已保存位置继续。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("对话") })
                if (session.kind == CreationKind.Character) {
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("角色卡") })
                }
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("世界书") })
                if (session.kind == CreationKind.Character) {
                    FilterChip(selected = tab == 3, onClick = { tab = 3 }, label = { Text("前端预览") })
                }
            }
            when (tab) {
                0 -> CreationConversation(session, state, viewModel)
                1 -> CharacterDraftEditor(
                    session.card, state.coverPreviewPng, viewModel, state.busy,
                    onPickCover = { coverPicker.launch("image/*") },
                    onExportJson = { prepareExport(CharacterExportFormat.Json) },
                    onExportPng = { prepareExport(CharacterExportFormat.Png) },
                )
                2 -> WorldDraftEditor(session, viewModel, state.busy)
                3 -> FrontendPreview(session.card, viewModel, state.busy)
            }
        }
    }
}

@Composable
private fun CreationCompactStatus(state: CreationUiState, viewModel: CreationViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) CircularProgressIndicator(modifier = Modifier.height(18.dp))
        Text(state.extractionProgress.ifBlank { state.modelPhase },
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (state.busy) TextButton(onClick = viewModel::cancelGeneration) { Text("停止") }
    }
}

@Composable
private fun CreationActivityPanel(state: CreationUiState, session: CreationSession, viewModel: CreationViewModel) {
    var showRawStream by remember(session.id, state.operationStartedAtMillis) { mutableStateOf(false) }
    var showFullStream by remember(session.id, state.operationStartedAtMillis) { mutableStateOf(false) }
    var clockMillis by remember(state.operationStartedAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.busy, state.operationStartedAtMillis) {
        while (state.busy) {
            clockMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.busy) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text(state.operationLabel.ifBlank { "创作进度" }, style = MaterialTheme.typography.titleSmall)
                }
                if (state.busy) TextButton(onClick = viewModel::cancelGeneration) { Text("停止") }
            }
            Text(state.modelPhase, style = MaterialTheme.typography.bodySmall)
            if (state.providerLabel.isNotBlank()) {
                Text("当前连接：${state.providerLabel}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && state.operationStartedAtMillis > 0) {
                val elapsedSeconds = ((clockMillis - state.operationStartedAtMillis).coerceAtLeast(0) / 1_000)
                Text("任务已运行 ${elapsedSeconds} 秒", style = MaterialTheme.typography.bodySmall)
            }
            if (state.deltaCount > 0) {
                val spanSeconds = ((state.lastDeltaMillis ?: 0L) - (state.firstDeltaMillis ?: 0L))
                    .coerceAtLeast(0) / 1_000
                Text("首片在 ${state.firstDeltaMillis.orZeroSeconds()} 秒、末片在 ${state.lastDeltaMillis.orZeroSeconds()} 秒到达；片段持续 $spanSeconds 秒，共 ${state.deltaCount} 个。",
                    style = MaterialTheme.typography.bodySmall)
            } else if (state.busy && state.modelPhase.contains("等待模型响应")) {
                Text("仍在等待首个流式片段。", style = MaterialTheme.typography.bodySmall)
            } else if (!state.busy && state.modelElapsedMillis > 0 && state.liveOutput.isNotBlank()) {
                Text("当前请求没有收到增量片段；结果在 ${state.modelElapsedMillis / 1_000} 秒后整段到达。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (session.sourceLength > 0) {
                val fraction = session.sourceCursor.toFloat() / session.sourceLength
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("已保存 ${session.sourceCursor}/${session.sourceLength} 字符（${(fraction * 100).toInt()}%）",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.extractionProgress.isNotBlank()) {
                Text(state.extractionProgress, style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && state.liveAssistantMessage.isNotBlank()) {
                Text("agent 正在回复", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(state.liveAssistantMessage.takeLast(1_000))
                }
            }
            if (state.liveReasoning.isNotBlank()) {
                Text("模型返回的思考文本", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(state.liveReasoning) }
                }
            } else if (!state.busy && state.liveOutput.isNotBlank()) {
                Text("本轮未收到可显示的思考文本；等待时间不能说明模型内部如何处理。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.liveOutput.isNotBlank()) {
                TextButton(onClick = { showRawStream = !showRawStream }) {
                    Text(if (showRawStream) "收起结构化草稿" else "查看结构化草稿流（${state.liveOutput.length} 字）")
                }
            }
            if (showRawStream && state.liveOutput.isNotBlank()) {
                val limit = 6_000
                val shortened = !showFullStream && state.liveOutput.length > limit
                if (shortened || showFullStream) {
                    TextButton(onClick = { showFullStream = !showFullStream }) {
                        Text(if (showFullStream) "只看最新片段" else "查看完整流式内容")
                    }
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("结构化草稿原文（校验前）", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(if (showFullStream) state.liveOutput else state.liveOutput.takeLast(limit))
                    }
                    if (shortened) Text("当前显示最近 $limit 字；可展开查看全部。", style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.busy && state.liveReasoning.isBlank() && state.liveAssistantMessage.isBlank()) {
                Text("等待供应商返回可显示的文字…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun Long?.orZeroSeconds(): Long = (this ?: 0L) / 1_000

@Composable
private fun CreationConversation(session: CreationSession, state: CreationUiState, viewModel: CreationViewModel) {
    var input by remember(session.id) { mutableStateOf("") }
    val busy = state.busy
    val listState = rememberLazyListState()
    var showCompletedDetails by remember(session.id, state.operationStartedAtMillis) { mutableStateOf(false) }
    LaunchedEffect(session.id, session.turns.size, state.operationStartedAtMillis, busy) {
        // Follow the in-flight message, then return focus to the saved agent reply.
        listState.scrollToItem(session.turns.size + if (busy) 1 else 0)
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "可以从一句设想开始。也可以指定叙事视角、人物关系、风格、开场、世界规则或前端形式；agent 会给出草稿供你修改。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(session.turns) { turn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (turn.role == "user") "你" else "创作 agent", style = MaterialTheme.typography.labelMedium)
                        Text(turn.text)
                    }
                }
            }
            if (busy || state.modelPhase.isNotBlank()) {
                item(key = "creation-progress") {
                    if (busy || showCompletedDetails) {
                        CreationActivityPanel(state, session, viewModel)
                    } else {
                        TextButton(onClick = { showCompletedDetails = true }) {
                            Text("查看本轮进度 · ${state.modelPhase}")
                        }
                    }
                }
            } else if (session.sourceLength > 0) {
                item(key = "source-progress") {
                    Text("原文已提炼 ${session.sourceCursor}/${session.sourceLength} 字符；可从已保存位置继续。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("告诉 agent 想创作什么") }, minLines = 2, maxLines = 6,
        )
        Button(onClick = { viewModel.send(input); input = "" }, enabled = !busy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("发送") }
    }
}

@Composable
private fun CharacterDraftEditor(
    card: CharacterDraft,
    coverPng: ByteArray?,
    viewModel: CreationViewModel,
    busy: Boolean,
    onPickCover: () -> Unit,
    onExportJson: () -> Unit,
    onExportPng: () -> Unit,
) {
    val coverBitmap = remember(coverPng) {
        coverPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("角色封面", style = MaterialTheme.typography.titleMedium)
        if (coverBitmap != null) {
            Image(coverBitmap, contentDescription = "角色封面预览",
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
        } else Text("尚未设置封面；JSON 可直接导出，PNG 需先选择封面。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPickCover, enabled = !busy) { Text(if (coverPng == null) "选择封面图片" else "更换封面图片") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportJson, enabled = !busy) { Text("导出 JSON") }
            Button(onClick = onExportPng, enabled = !busy && coverPng != null) { Text("导出 PNG 角色卡") }
        }
        DraftField("名称", card.name, busy) { viewModel.editCard { c -> c.copy(name = it) } }
        DraftField("描述与背景", card.description, busy, 5) { viewModel.editCard { c -> c.copy(description = it) } }
        DraftField("性格与行为", card.personality, busy, 4) { viewModel.editCard { c -> c.copy(personality = it) } }
        DraftField("场景", card.scenario, busy, 4) { viewModel.editCard { c -> c.copy(scenario = it) } }
        DraftField("开场消息", card.firstMessage, busy, 6) { viewModel.editCard { c -> c.copy(firstMessage = it) } }
        DraftField("示例对话", card.exampleMessages, busy, 5) { viewModel.editCard { c -> c.copy(exampleMessages = it) } }
        DraftField("系统提示", card.systemPrompt, busy, 4) { viewModel.editCard { c -> c.copy(systemPrompt = it) } }
        DraftField("历史后提示", card.postHistoryInstructions, busy, 3) { viewModel.editCard { c -> c.copy(postHistoryInstructions = it) } }
        DraftField("作者说明", card.creatorNotes, busy, 3) { viewModel.editCard { c -> c.copy(creatorNotes = it) } }
        DraftField("标签（逗号分隔）", card.tags.joinToString(", "), busy) { value ->
            viewModel.editCard { c -> c.copy(tags = value.split(',', '，').map(String::trim).filter(String::isNotBlank)) }
        }
        DraftField("备选开场（以空行分隔）", card.alternateGreetings.joinToString("\n\n"), busy, 5) { value ->
            viewModel.editCard { c -> c.copy(alternateGreetings = value.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotBlank)) }
        }
    }
}

@Composable
private fun WorldDraftEditor(session: CreationSession, viewModel: CreationViewModel, busy: Boolean) {
    var pastedSource by remember(session.id) { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readNBytes(12_000_001) }
                } ?: error("无法读取原文")
                require(bytes.size <= 12_000_000) { "文件超过 12 MB，请拆分；没有截断导入。" }
                val decoded = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                }.getOrElse { error("仅支持 UTF-8 文本；文件编码无法解码，原文未处理。") }
                viewModel.setSource(
                    UriUtils.resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: "导入原文",
                    decoded,
                )
            } catch (e: Exception) {
                viewModel.showError(e.message ?: "导入失败")
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DraftField("世界书名称", session.worldName, busy) { viewModel.editWorldName(it) }
        OutlinedTextField(
            value = pastedSource, onValueChange = { pastedSource = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("粘贴原文（可选）") }, minLines = 3, maxLines = 6, enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setSource("粘贴原文", pastedSource); pastedSource = "" },
                enabled = !busy && pastedSource.isNotBlank()) { Text("保存原文") }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("text/plain", "*/*")) }, enabled = !busy) { Text("导入文本文件") }
        }
        if (session.sourceLength > 0) {
            Text("${session.sourceName} · ${session.sourceCursor}/${session.sourceLength} 字符已提炼")
            Button(onClick = viewModel::extractSource,
                enabled = !busy && session.sourceCursor < session.sourceLength) {
                Text(if (session.sourceCursor == 0) "开始逐段提炼" else "继续提炼")
            }
        }
        Text("条目 ${session.lore.size}", style = MaterialTheme.typography.titleMedium)
        val repeatedTitles = session.lore.groupBy { it.title.trim().lowercase() }
            .filter { (title, entries) -> title.isNotBlank() && entries.size > 1 }
        if (repeatedTitles.isNotEmpty()) {
            Text("有 ${repeatedTitles.size} 组同名条目，请核对重复或冲突。", color = MaterialTheme.colorScheme.error)
        }
        session.lore.forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DraftField("标题", item.title, busy) { viewModel.editLore(index, item.copy(title = it)) }
                    DraftField("关键词（逗号分隔）", item.keys.joinToString(", "), busy) { text ->
                        viewModel.editLore(index, item.copy(keys = text.split(',', '，').map(String::trim).filter(String::isNotBlank)))
                    }
                    DraftField("条目内容", item.content, busy, 4) { viewModel.editLore(index, item.copy(content = it)) }
                    DraftField("次级关键词（逗号分隔）", item.secondaryKeys.joinToString(", "), busy) { text ->
                        viewModel.editLore(index, item.copy(
                            secondaryKeys = text.split(',', '，').map(String::trim).filter(String::isNotBlank),
                        ))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("常驻条目")
                        Switch(checked = item.constant, onCheckedChange = { viewModel.editLore(index, item.copy(constant = it)) }, enabled = !busy)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("要求次级关键词")
                        Switch(checked = item.selective, onCheckedChange = { viewModel.editLore(index, item.copy(selective = it)) },
                            enabled = !busy && item.secondaryKeys.isNotEmpty())
                    }
                    DraftField("说明 / 事实或传闻", item.note, busy, 2) { viewModel.editLore(index, item.copy(note = it)) }
                    if (item.sourceQuote.isNotBlank()) {
                        Text("${item.sourceName} [${item.sourceOffset}]：${item.sourceQuote}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.editLore(index, null) }, enabled = !busy) { Text("删除条目") }
                }
            }
        }
        OutlinedButton(onClick = { viewModel.editLore(session.lore.size, LoreDraft("新条目", emptyList(), "")) },
            enabled = !busy) { Text("添加条目") }
    }
}

@Composable
private fun DraftField(label: String, value: String, busy: Boolean, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, minLines = minLines, maxLines = if (minLines == 1) 2 else 12,
        enabled = !busy)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FrontendPreview(card: CharacterDraft, viewModel: CreationViewModel, busy: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("前端源代码会随开场消息保存。此处为隔离预览，最终仍需在 SillyTavern 和 Tellev 的真实聊天中验证。")
        DraftField("HTML/CSS 片段", card.frontendHtml, busy, 8) { value ->
            viewModel.editCard { it.copy(frontendHtml = value) }
        }
        val issues = portableFrontendIssues(card.frontendHtml)
        if (issues.isNotEmpty()) Text("可移植性检查：${issues.joinToString()}", color = MaterialTheme.colorScheme.error)
        Text("开场正文：${card.firstMessage}")
        if (card.frontendHtml.isNotBlank()) {
            val webView = remember { mutableStateOf<WebView?>(null) }
            DisposableEffect(Unit) { onDispose { webView.value?.destroy(); webView.value = null } }
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                        }
                        webView.value = this
                    }
                },
                update = { it.loadDataWithBaseURL(null, card.frontendHtml, "text/html", "UTF-8", null) },
            )
        }
    }
}
