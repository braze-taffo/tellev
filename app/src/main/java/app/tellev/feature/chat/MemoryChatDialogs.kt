package app.tellev.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.memory.MemoryMode
import app.tellev.core.memory.MemoryRecord
import app.tellev.core.model.MessageRole

@Composable
internal fun MemoryChatDialogs(state: ChatUiState, viewModel: ChatViewModel, showManager: Boolean, onClose: () -> Unit) {
    val session = state.currentSession ?: return
    val mode = MemoryMode.of(session)
    if (mode == null) {
        if (!state.memoryPluginEnabled) return
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.chat_select_memory_mode_title)) },
            text = { Text(stringResource(R.string.chat_select_memory_mode_message)) },
            confirmButton = {
                Column {
                    MemoryMode.entries.forEach { choice ->
                        TextButton(onClick = { viewModel.selectMemoryMode(choice) }) { Text(choice.label) }
                    }
                }
            },
        )
        return
    }
    if (!showManager) return
    var editing by remember(session.id) { mutableStateOf<MemoryRecord?>(null) }
    var editText by remember(session.id) { mutableStateOf("") }
    var confirmRebuild by remember(session.id) { mutableStateOf(false) }
    var confirmVectors by remember(session.id) { mutableStateOf(false) }
    var showInactive by remember(session.id) { mutableStateOf(false) }
    val replyCount = session.messages.count { it.role == MessageRole.Character || it.role == MessageRole.Assistant }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.chat_memory_manager_title, mode.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.memoryStatus ?: stringResource(R.string.chat_memory_ready))
                if (!state.memoryPluginEnabled && mode != MemoryMode.NONE) Text(stringResource(R.string.chat_memory_global_disabled))
                if (mode == MemoryMode.NONE) Text(stringResource(R.string.chat_memory_locked_none))
                if (state.memoryNeedsRebuild) Text(stringResource(R.string.chat_memory_needs_rebuild))
                if (state.memoryVectorEnabled && mode != MemoryMode.NONE) {
                    TextButton(onClick = { confirmVectors = true }) { Text(stringResource(R.string.chat_rebuild_vectors)) }
                }
                TextButton(onClick = { showInactive = !showInactive }) {
                    Text(if (showInactive) stringResource(R.string.chat_show_active_only) else stringResource(R.string.chat_show_inactive))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.memoryRecords.filter { showInactive || it.active }.asReversed(), key = { it.id }) { record ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text((if (record.active) "" else stringResource(R.string.chat_memory_inactive_prefix) + " ") + "${record.kind} · ${record.text}")
                            val source = record.sourceIds.mapNotNull { id ->
                                state.messages.firstOrNull { it.id == id }?.let { "${it.name}: ${it.content.take(80)}" }
                            }
                            Text(stringResource(R.string.chat_memory_source_label, source.joinToString(" / ").ifBlank { stringResource(R.string.chat_memory_source_manual) }))
                            Row {
                                TextButton(onClick = { editing = record; editText = record.text }) { Text(stringResource(R.string.chat_correct)) }
                                TextButton(onClick = { viewModel.correctMemory(record.id, null) }) { Text(stringResource(R.string.chat_memory_delete)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.chat_memory_close)) } },
        dismissButton = {
            if (mode != MemoryMode.NONE) Row {
                TextButton(onClick = viewModel::retryMemory) { Text(stringResource(R.string.chat_retry_pending)) }
                TextButton(onClick = { confirmRebuild = true }) { Text(stringResource(R.string.chat_backfill_history)) }
            }
        },
    )
    if (confirmRebuild) {
        val estimated = if (mode == MemoryMode.ARCHIVE) replyCount + replyCount / 12
        else (replyCount + 9) / 10
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text(stringResource(R.string.chat_backfill_title)) },
            text = { Text(stringResource(R.string.chat_backfill_message, replyCount, estimated)) },
            confirmButton = {
                TextButton(onClick = { confirmRebuild = false; viewModel.rebuildMemory() }) { Text(stringResource(R.string.chat_start_backfill)) }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text(stringResource(R.string.chat_memory_cancel)) } },
        )
    }
    if (confirmVectors) {
        val count = state.memoryRecords.count { it.active }
        AlertDialog(
            onDismissRequest = { confirmVectors = false },
            title = { Text(stringResource(R.string.chat_rebuild_vectors)) },
            text = { Text(stringResource(R.string.chat_rebuild_vectors_message, count)) },
            confirmButton = { TextButton(onClick = { confirmVectors = false; viewModel.rebuildMemoryVectors() }) { Text(stringResource(R.string.chat_start)) } },
            dismissButton = { TextButton(onClick = { confirmVectors = false }) { Text(stringResource(R.string.chat_memory_cancel)) } },
        )
    }
    editing?.let { record ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_correct_memory_title)) },
            text = { OutlinedTextField(editText, { editText = it }, label = { Text(stringResource(R.string.chat_content_label)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { viewModel.correctMemory(record.id, editText); editing = null }, enabled = editText.isNotBlank()) { Text(stringResource(R.string.chat_memory_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.chat_memory_cancel)) } },
        )
    }
}
