package app.tellev.feature.creation

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    var pendingDelete by remember { mutableStateOf<CreationSessionSummary?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    pendingDelete?.let { draft ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.crs_delete_draft_title)) },
            text = {
                val draftName = draft.cardName.ifBlank { draft.worldName.ifBlank { stringResource(R.string.crs_unnamed_draft) } }
                Text(stringResource(R.string.crs_delete_draft_body, draftName))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDraft(draft.id); pendingDelete = null }, enabled = !state.busy) {
                    Text(stringResource(R.string.crs_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.crs_cancel)) } },
        )
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.crs_home_title)) }, navigationIcon = {
        TextButton(onClick = onBack) { Text(stringResource(R.string.crs_back)) }
    }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.crs_home_intro), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.start(CreationKind.Character); onOpenEditor() }, enabled = !state.busy) { Text(stringResource(R.string.crs_new_character)) }
                OutlinedButton(onClick = { viewModel.start(CreationKind.WorldBook); onOpenEditor() }, enabled = !state.busy) { Text(stringResource(R.string.crs_new_worldbook)) }
            }
            Text(stringResource(R.string.crs_drafts_title), style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessions, key = CreationSessionSummary::id) { session ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = !state.busy) {
                                viewModel.open(session.id)
                                onOpenEditor()
                            }.padding(14.dp)) {
                                val sessionName = session.cardName.ifBlank { session.worldName.ifBlank {
                                    stringResource(if (session.kind == CreationKind.Character) R.string.crs_unnamed_character else R.string.crs_unnamed_worldbook)
                                } }
                                Text(sessionName)
                                val kindLabel = stringResource(if (session.kind == CreationKind.Character) R.string.crs_kind_character else R.string.crs_kind_worldbook)
                                Text(
                                    stringResource(R.string.crs_session_meta, kindLabel, session.turnsCount, session.loreCount),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (session.sourceLength > 0) {
                                    Text(stringResource(R.string.crs_source_extracted_short, session.sourceCursor, session.sourceLength), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { pendingDelete = session }, enabled = !state.busy) { Text(stringResource(R.string.crs_delete)) }
                        }
                    }
                }
            }
        }
    }
}

/** Which export flow a pending file write belongs to. */
private enum class ExportKind { CharacterJson, CharacterPng, WorldBookJson }

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
    var pendingExport by remember(session?.id) { mutableStateOf<Pair<ExportKind, ByteArray>?>(null) }
    fun writeExport(uri: Uri?, kind: ExportKind) {
        val bytes = pendingExport?.takeIf { it.first == kind }?.second
        pendingExport = null
        if (uri == null || bytes == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error(context.getString(R.string.crs_error_write_file))
                }
                viewModel.showInfo(
                    when (kind) {
                        ExportKind.CharacterPng -> context.getString(R.string.crs_export_png_done)
                        ExportKind.WorldBookJson -> context.getString(R.string.crs_export_worldbook_done)
                        else -> context.getString(R.string.crs_export_json_done)
                    },
                )
            } catch (e: Exception) {
                viewModel.showError(context.getString(R.string.crs_export_failed, e.message))
            }
        }
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeExport(it, ExportKind.CharacterJson)
    }
    val pngExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        writeExport(it, ExportKind.CharacterPng)
    }
    val worldBookJsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        writeExport(it, ExportKind.WorldBookJson)
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readNBytes(12_000_001) }
                } ?: error(context.getString(R.string.crs_error_read_image))
                require(source.size <= 12_000_000) { context.getString(R.string.crs_error_image_too_large) }
                val png = withContext(Dispatchers.IO) {
                    app.tellev.util.decodeImageAsPng(source, maxEdge = 1024)
                } ?: error(context.getString(R.string.crs_error_parse_image))
                viewModel.setCoverPng(png)
            } catch (e: Exception) {
                viewModel.showError(context.getString(R.string.crs_cover_failed, e.message))
            }
        }
    }
    fun safeExportName(): String = session?.card?.name.orEmpty()
        .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifBlank { "character" }
    fun safeWorldBookName(): String = session?.worldName.orEmpty()
        .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(80).ifBlank { "worldbook" }
    fun prepareExport(format: CharacterExportFormat) {
        scope.launch {
            try {
                val bytes = viewModel.exportCharacter(format)
                pendingExport = (if (format == CharacterExportFormat.Png) ExportKind.CharacterPng else ExportKind.CharacterJson) to bytes
                val name = safeExportName()
                if (format == CharacterExportFormat.Png) pngExport.launch("$name.png")
                else jsonExport.launch("$name.json")
            } catch (e: Exception) {
                viewModel.showError(context.getString(R.string.crs_export_failed, e.message))
            }
        }
    }
    fun prepareWorldBookExport() {
        scope.launch {
            try {
                pendingExport = ExportKind.WorldBookJson to viewModel.exportWorldBook()
                worldBookJsonExport.launch("${safeWorldBookName()}.json")
            } catch (e: Exception) {
                viewModel.showError(context.getString(R.string.crs_export_failed, e.message))
            }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(session?.id) { tab = 0 }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (session?.kind == CreationKind.WorldBook) R.string.crs_editor_title_worldbook else R.string.crs_editor_title_character)) },
            navigationIcon = { TextButton(onClick = { viewModel.close(); onBack() }) { Text(stringResource(R.string.crs_back)) } },
            actions = {
                TextButton(onClick = { viewModel.saveArtifact(onSaved) }, enabled = session != null && !state.busy) {
                    Text(stringResource(if (session?.kind == CreationKind.WorldBook) R.string.crs_save_to_worldbook else R.string.crs_save_to_character_list))
                }
            })
    }) { padding ->
        if (session == null) {
            // Loading failures (missing/corrupt card) must surface here, or the
            // editor would sit on "正在读取草稿…" forever.
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    state.error ?: stringResource(R.string.crs_loading_draft),
                    color = if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (session.turns.lastOrNull()?.role == "user" && !state.busy) {
                if (session.partialTurnSaved) {
                    TextButton(onClick = { viewModel.send("请从已保存的草稿继续完成上一轮未完成的工作；不要重复创建已经写入的条目。") }) {
                        Text(stringResource(R.string.crs_continue_incomplete))
                    }
                } else {
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.crs_retry_last)) }
                }
            }
            state.info?.let { Text(it, modifier = Modifier.padding(horizontal = 12.dp)) }
            if (tab != 0 && (state.busy || state.modelPhase.isNotBlank())) {
                CreationCompactStatus(state, viewModel)
            } else if (tab != 0 && session.sourceLength > 0) {
                val savedFraction = session.sourceCursor.toFloat() / session.sourceLength
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(progress = { savedFraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.crs_source_extracted_continue, session.sourceCursor, session.sourceLength),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.crs_tab_chat)) })
                if (session.kind == CreationKind.Character) {
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.crs_kind_character)) })
                }
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text(stringResource(R.string.crs_kind_worldbook)) })
                if (session.kind == CreationKind.Character) {
                    FilterChip(selected = tab == 3, onClick = { tab = 3 }, label = { Text(stringResource(R.string.crs_tab_frontend)) })
                    FilterChip(selected = tab == 4, onClick = { tab = 4 }, label = { Text(stringResource(R.string.crs_tab_advanced)) })
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
                2 -> WorldDraftEditor(session, viewModel, state.busy, onExportJson = { prepareWorldBookExport() })
                3 -> FrontendPreview(session.card, viewModel, state.busy)
                4 -> AdvancedAssetsPanel(session, viewModel, state.busy)
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
        if (state.busy) TextButton(onClick = viewModel::cancelGeneration) { Text(stringResource(R.string.crs_stop)) }
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
                    val progressTitle = stringResource(R.string.crs_progress_title)
                    Text(state.operationLabel.ifBlank { progressTitle }, style = MaterialTheme.typography.titleSmall)
                }
                if (state.busy) TextButton(onClick = viewModel::cancelGeneration) { Text(stringResource(R.string.crs_stop)) }
            }
            Text(state.modelPhase, style = MaterialTheme.typography.bodySmall)
            if (state.providerLabel.isNotBlank()) {
                Text(stringResource(R.string.crs_provider_connected, state.providerLabel), style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && state.operationStartedAtMillis > 0) {
                val elapsedSeconds = ((clockMillis - state.operationStartedAtMillis).coerceAtLeast(0) / 1_000)
                Text(stringResource(R.string.crs_task_elapsed, elapsedSeconds), style = MaterialTheme.typography.bodySmall)
            }
            if (state.deltaCount > 0) {
                val spanSeconds = ((state.lastDeltaMillis ?: 0L) - (state.firstDeltaMillis ?: 0L))
                    .coerceAtLeast(0) / 1_000
                Text(stringResource(R.string.crs_delta_stats,
                    state.firstDeltaMillis.orZeroSeconds(), state.lastDeltaMillis.orZeroSeconds(), spanSeconds, state.deltaCount),
                    style = MaterialTheme.typography.bodySmall)
            } else if (state.busy && state.modelPhase.contains(UiStrings.get(S.creng_phase_waiting_model))) {
                // modelPhase carries the engine's UiStrings-resolved text; compare against the same
                // creng_phase_waiting_model resource instead of a hardcoded display string.
                Text(stringResource(R.string.crs_waiting_first_chunk), style = MaterialTheme.typography.bodySmall)
            } else if (!state.busy && state.modelElapsedMillis > 0 && state.liveOutput.isNotBlank()) {
                Text(stringResource(R.string.crs_no_stream_fallback, state.modelElapsedMillis / 1_000),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (session.sourceLength > 0) {
                val fraction = session.sourceCursor.toFloat() / session.sourceLength
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.crs_source_saved_pct, session.sourceCursor, session.sourceLength, (fraction * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.extractionProgress.isNotBlank()) {
                Text(state.extractionProgress, style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy && state.liveAssistantMessage.isNotBlank()) {
                Text(stringResource(R.string.crs_agent_replying), style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(state.liveAssistantMessage.takeLast(1_000))
                }
            }
            if (state.liveReasoning.isNotBlank()) {
                Text(stringResource(R.string.crs_reasoning_title), style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(state.liveReasoning) }
                }
            } else if (!state.busy && state.liveOutput.isNotBlank()) {
                Text(stringResource(R.string.crs_no_reasoning),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.liveOutput.isNotBlank()) {
                TextButton(onClick = { showRawStream = !showRawStream }) {
                    Text(if (showRawStream) stringResource(R.string.crs_collapse_draft_stream)
                        else stringResource(R.string.crs_view_draft_stream, state.liveOutput.length))
                }
            }
            if (showRawStream && state.liveOutput.isNotBlank()) {
                val limit = 6_000
                val shortened = !showFullStream && state.liveOutput.length > limit
                if (shortened || showFullStream) {
                    TextButton(onClick = { showFullStream = !showFullStream }) {
                        Text(if (showFullStream) stringResource(R.string.crs_show_latest_only) else stringResource(R.string.crs_view_full_stream))
                    }
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.crs_draft_raw_title), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(if (showFullStream) state.liveOutput else state.liveOutput.takeLast(limit))
                    }
                    if (shortened) Text(stringResource(R.string.crs_stream_truncated, limit), style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.busy && state.liveReasoning.isBlank() && state.liveAssistantMessage.isBlank()) {
                Text(stringResource(R.string.crs_waiting_provider_text), style = MaterialTheme.typography.bodySmall)
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
                    stringResource(R.string.crs_conversation_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (session.turns.isEmpty()) {
                item(key = "creation-brief") {
                    CreationBriefForm(session.kind, busy, viewModel::send)
                }
            }
            items(session.turns) { turn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (turn.role == "user") stringResource(R.string.crs_role_you) else stringResource(R.string.crs_role_agent), style = MaterialTheme.typography.labelMedium)
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
                            Text(stringResource(R.string.crs_view_progress, state.modelPhase))
                        }
                    }
                }
            } else if (session.sourceLength > 0) {
                item(key = "source-progress") {
                    Text(stringResource(R.string.crs_source_extracted_continue, session.sourceCursor, session.sourceLength),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.crs_input_label)) }, minLines = 2, maxLines = 6,
        )
        if (session.turns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.send("请继续引导，只问我当前最关键的 1 至 2 个问题，并先整理已有设定。") }, enabled = !busy) {
                    Text(stringResource(R.string.crs_btn_continue_guide))
                }
                TextButton(onClick = { viewModel.send("这一部分交给你决定。请结合已确定设定写入草稿，并说明你的选择。") }, enabled = !busy) {
                    Text(stringResource(R.string.crs_btn_ai_decide))
                }
                TextButton(onClick = { viewModel.send(if (session.kind == CreationKind.Character)
                    "请根据目前已有信息立即完成可编辑的角色卡初稿和适用的世界书条目，缺口用合理设定补齐并标明待核对处。"
                    else "请根据目前已有信息立即完成可编辑的世界书条目初稿，区分已确定事实与待核对设定。") }, enabled = !busy) {
                    Text(stringResource(R.string.crs_btn_generate_draft))
                }
                if (session.kind == CreationKind.WorldBook) {
                    TextButton(onClick = { viewModel.send("请从现在起逐条与我讨论世界书条目。先提议一条，等我确认或修改后再写入草稿，然后讨论下一条。") }, enabled = !busy) {
                        Text(stringResource(R.string.crs_btn_discuss_one_by_one))
                    }
                }
                if (session.kind == CreationKind.Character) {
                    TextButton(onClick = { viewModel.send("请根据这张卡的设定，实际创建可运行的变量结构和动态状态栏。先检查已有脚本、变量与正则，再分模块写入草稿；不要只输出让玩家复制的提示词。每个模块写完说明作用和待验证点。") }, enabled = !busy) {
                        Text(stringResource(R.string.crs_btn_status_bar))
                    }
                }
            }
        }
        Button(onClick = { viewModel.send(input); input = "" }, enabled = !busy && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.crs_send)) }
    }
}

@Composable
private fun CreationBriefForm(kind: CreationKind, busy: Boolean, onStart: (String) -> Unit) {
    var guided by remember(kind) { mutableStateOf(true) }
    var title by remember(kind) { mutableStateOf("") }
    var premise by remember(kind) { mutableStateOf("") }
    var relationship by remember(kind) { mutableStateOf("") }
    var userPersona by remember(kind) { mutableStateOf("") }
    var characters by remember(kind) { mutableStateOf("") }
    var multipleCharacters by remember(kind) { mutableStateOf(false) }
    var detail by remember(kind) { mutableStateOf(CreationDetail.Normal) }
    var loreOneByOne by remember(kind) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.crs_brief_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.crs_brief_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = guided, onClick = { guided = true }, label = { Text(stringResource(R.string.crs_mode_chat)) })
                FilterChip(selected = !guided, onClick = { guided = false }, label = { Text(stringResource(R.string.crs_mode_direct_draft)) })
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (kind == CreationKind.Character) R.string.crs_field_title_character else R.string.crs_field_title_worldbook)) })
            OutlinedTextField(value = premise, onValueChange = { premise = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (kind == CreationKind.Character) R.string.crs_field_premise_character else R.string.crs_field_premise_worldbook)) },
                minLines = 2, maxLines = 5)
            if (kind == CreationKind.Character) {
                OutlinedTextField(value = relationship, onValueChange = { relationship = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.crs_field_relationship)) })
                OutlinedTextField(value = userPersona, onValueChange = { userPersona = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.crs_field_persona)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = multipleCharacters, onCheckedChange = { multipleCharacters = it })
                    Text(stringResource(R.string.crs_multi_characters), modifier = Modifier.padding(start = 8.dp))
                }
                if (multipleCharacters) {
                    OutlinedTextField(value = characters, onValueChange = { characters = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.crs_field_characters)) }, minLines = 3, maxLines = 6)
                    if (characters.lineSequence().count { it.isNotBlank() } < 2) {
                        Text(stringResource(R.string.crs_min_characters_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.crs_length_title), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CreationDetail.entries.forEach { option ->
                        // CreationDetail.label is also part of the LLM prompt; localize display here only.
                        val optionLabel = when (option) {
                            CreationDetail.Concise -> stringResource(R.string.crs_length_concise)
                            CreationDetail.Normal -> stringResource(R.string.crs_length_normal)
                            CreationDetail.Rich -> stringResource(R.string.crs_length_rich)
                        }
                        FilterChip(selected = detail == option, onClick = { detail = option }, label = { Text(optionLabel) })
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = loreOneByOne, onCheckedChange = { loreOneByOne = it })
                    Text(stringResource(R.string.crs_lore_one_by_one), modifier = Modifier.padding(start = 8.dp))
                }
            }
            Button(onClick = { onStart(CreationBrief(kind, guided, title, premise, relationship,
                userPersona, if (multipleCharacters) characters else "", detail, loreOneByOne).toPrompt()) },
                enabled = !busy && (!multipleCharacters || characters.lineSequence().count { it.isNotBlank() } >= 2),
                modifier = Modifier.fillMaxWidth()) {
                Text(if (guided) stringResource(R.string.crs_start_guided)
                    else if (kind == CreationKind.WorldBook && loreOneByOne) stringResource(R.string.crs_start_one_by_one)
                    else stringResource(R.string.crs_start_first_draft))
            }
        }
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
        Text(stringResource(R.string.crs_cover_title), style = MaterialTheme.typography.titleMedium)
        if (coverBitmap != null) {
            Image(coverBitmap, contentDescription = stringResource(R.string.crs_cover_cd),
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
        } else Text(stringResource(R.string.crs_cover_missing_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPickCover, enabled = !busy) {
            Text(if (coverPng == null) stringResource(R.string.crs_cover_pick) else stringResource(R.string.crs_cover_change))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportJson, enabled = !busy) { Text(stringResource(R.string.crs_export_json)) }
            Button(onClick = onExportPng, enabled = !busy && coverPng != null) { Text(stringResource(R.string.crs_export_png)) }
        }
        DraftField(stringResource(R.string.crs_field_name), card.name, busy) { viewModel.editCard { c -> c.copy(name = it) } }
        DraftField(stringResource(R.string.crs_field_description), card.description, busy, 5) { viewModel.editCard { c -> c.copy(description = it) } }
        DraftField(stringResource(R.string.crs_field_personality), card.personality, busy, 4) { viewModel.editCard { c -> c.copy(personality = it) } }
        DraftField(stringResource(R.string.crs_field_scenario), card.scenario, busy, 4) { viewModel.editCard { c -> c.copy(scenario = it) } }
        DraftField(stringResource(R.string.crs_field_first_message), card.firstMessage, busy, 6) { viewModel.editCard { c -> c.copy(firstMessage = it) } }
        DraftField(stringResource(R.string.crs_field_example_messages), card.exampleMessages, busy, 5) { viewModel.editCard { c -> c.copy(exampleMessages = it) } }
        DraftField(stringResource(R.string.crs_field_system_prompt), card.systemPrompt, busy, 4) { viewModel.editCard { c -> c.copy(systemPrompt = it) } }
        DraftField(stringResource(R.string.crs_field_post_history), card.postHistoryInstructions, busy, 3) { viewModel.editCard { c -> c.copy(postHistoryInstructions = it) } }
        DraftField(stringResource(R.string.crs_field_creator_notes), card.creatorNotes, busy, 3) { viewModel.editCard { c -> c.copy(creatorNotes = it) } }
        DraftField(stringResource(R.string.crs_field_tags), card.tags.joinToString(", "), busy) { value ->
            viewModel.editCard { c -> c.copy(tags = value.split(',', '，').map(String::trim).filter(String::isNotBlank)) }
        }
        DraftField(stringResource(R.string.crs_field_alternate_greetings), card.alternateGreetings.joinToString("\n\n"), busy, 5) { value ->
            viewModel.editCard { c -> c.copy(alternateGreetings = value.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotBlank)) }
        }
    }
}

@Composable
private fun AdvancedAssetsPanel(session: CreationSession, viewModel: CreationViewModel, busy: Boolean) {
    val extensions = session.advancedExtensions.takeIf { it.isNotEmpty() }
        ?: ((session.originalCard?.raw?.get("data") as? JsonObject)?.get("extensions") as? JsonObject)
        ?: JsonObject(emptyMap())
    val helper = extensions["tavern_helper"] as? JsonObject
    val scripts = helper?.get("scripts") as? JsonArray ?: JsonArray(emptyList())
    val regexes = extensions["regex_scripts"] as? JsonArray ?: JsonArray(emptyList())
    val variables = helper?.get("variables") as? JsonObject ?: JsonObject(emptyMap())
    fun JsonObject.label(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.crs_assets_summary, scripts.size, regexes.size, variables.size),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.crs_assets_hint),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { viewModel.send("请检查并概述当前脚本、正则和变量，列出缺少的运行模块及下一步。") },
            enabled = !busy) { Text(stringResource(R.string.crs_btn_check_assets)) }
        scripts.forEach { item ->
            val script = item as? JsonObject ?: return@forEach
            Text(stringResource(R.string.crs_script_line, script.label("name"),
                if (script.label("enabled") == "true") stringResource(R.string.crs_enabled) else stringResource(R.string.crs_disabled),
                script.label("content").length))
        }
        regexes.forEach { item ->
            val regex = item as? JsonObject ?: return@forEach
            Text(stringResource(R.string.crs_regex_line, regex.label("scriptName")))
        }
        if (variables.isNotEmpty()) Text(stringResource(R.string.crs_variables_line, variables.keys.joinToString("、")))
    }
}

@Composable
private fun WorldDraftEditor(
    session: CreationSession,
    viewModel: CreationViewModel,
    busy: Boolean,
    onExportJson: () -> Unit = {},
) {
    var pastedSource by remember(session.id) { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readNBytes(12_000_001) }
                } ?: error(context.getString(R.string.crs_error_read_source))
                require(bytes.size <= 12_000_000) { context.getString(R.string.crs_error_file_too_large) }
                val decoded = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                }.getOrElse { error(context.getString(R.string.crs_error_not_utf8)) }
                viewModel.setSource(
                    UriUtils.resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: "导入原文",
                    decoded,
                )
            } catch (e: Exception) {
                viewModel.showError(e.message ?: context.getString(R.string.crs_import_failed))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DraftField(stringResource(R.string.crs_field_worldbook_name), session.worldName, busy) { viewModel.editWorldName(it) }
        OutlinedButton(onClick = onExportJson, enabled = !busy) {
            Text(stringResource(R.string.crs_export_worldbook_json))
        }
        OutlinedTextField(
            value = pastedSource, onValueChange = { pastedSource = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.crs_field_paste_source)) }, minLines = 3, maxLines = 6, enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setSource("粘贴原文", pastedSource); pastedSource = "" },
                enabled = !busy && pastedSource.isNotBlank()) { Text(stringResource(R.string.crs_save_source)) }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("text/plain", "*/*")) }, enabled = !busy) { Text(stringResource(R.string.crs_import_text_file)) }
        }
        if (session.sourceLength > 0) {
            Text(stringResource(R.string.crs_source_status, session.sourceName, session.sourceCursor, session.sourceLength))
            Button(onClick = viewModel::extractSource,
                enabled = !busy && session.sourceCursor < session.sourceLength) {
                Text(if (session.sourceCursor == 0) stringResource(R.string.crs_start_extract) else stringResource(R.string.crs_continue_extract))
            }
        }
        Text(stringResource(R.string.crs_lore_count, session.lore.size), style = MaterialTheme.typography.titleMedium)
        val repeatedTitles = session.lore.groupBy { it.title.trim().lowercase() }
            .filter { (title, entries) -> title.isNotBlank() && entries.size > 1 }
        if (repeatedTitles.isNotEmpty()) {
            Text(stringResource(R.string.crs_duplicate_titles, repeatedTitles.size), color = MaterialTheme.colorScheme.error)
        }
        session.lore.forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DraftField(stringResource(R.string.crs_field_lore_title), item.title, busy) { viewModel.editLore(index, item.copy(title = it)) }
                    DraftField(stringResource(R.string.crs_field_keys), item.keys.joinToString(", "), busy) { text ->
                        viewModel.editLore(index, item.copy(keys = text.split(',', '，').map(String::trim).filter(String::isNotBlank)))
                    }
                    DraftField(stringResource(R.string.crs_field_lore_content), item.content, busy, 4) { viewModel.editLore(index, item.copy(content = it)) }
                    DraftField(stringResource(R.string.crs_field_secondary_keys), item.secondaryKeys.joinToString(", "), busy) { text ->
                        viewModel.editLore(index, item.copy(
                            secondaryKeys = text.split(',', '，').map(String::trim).filter(String::isNotBlank),
                        ))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.crs_constant_entry))
                        Switch(checked = item.constant, onCheckedChange = { viewModel.editLore(index, item.copy(constant = it)) }, enabled = !busy)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.crs_selective_entry))
                        Switch(checked = item.selective, onCheckedChange = { viewModel.editLore(index, item.copy(selective = it)) },
                            enabled = !busy && item.secondaryKeys.isNotEmpty())
                    }
                    DraftField(stringResource(R.string.crs_field_note), item.note, busy, 2) { viewModel.editLore(index, item.copy(note = it)) }
                    if (item.sourceQuote.isNotBlank()) {
                        Text(stringResource(R.string.crs_source_quote, item.sourceName, item.sourceOffset, item.sourceQuote), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.editLore(index, null) }, enabled = !busy) { Text(stringResource(R.string.crs_delete_entry)) }
                }
            }
        }
        OutlinedButton(onClick = { viewModel.editLore(session.lore.size, LoreDraft("新条目", emptyList(), "")) },
            enabled = !busy) { Text(stringResource(R.string.crs_add_entry)) }
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
        Text(stringResource(R.string.crs_frontend_hint))
        DraftField(stringResource(R.string.crs_field_frontend_html), card.frontendHtml, busy, 8) { value ->
            viewModel.editCard { it.copy(frontendHtml = value) }
        }
        val issues = portableFrontendIssues(card.frontendHtml)
        if (issues.isNotEmpty()) Text(stringResource(R.string.crs_portability_issues, issues.joinToString()), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.crs_first_message_preview, card.firstMessage))
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
