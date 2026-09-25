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
import androidx.compose.ui.unit.dp
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
            title = { Text("为这条对话选择记忆模式") },
            text = { Text("选择后仅这条对话使用该模式，之后不能转换。打开它时会自动切回相应模式。旧聊天记录不会自动整理；可稍后手动补建。") },
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
        title = { Text("长期记忆 · ${mode.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.memoryStatus ?: "就绪")
                if (!state.memoryPluginEnabled && mode != MemoryMode.NONE) Text("全局记忆开关已关闭，提取和注入均暂停。")
                if (mode == MemoryMode.NONE) Text("此对话已锁定为无记忆。")
                if (state.memoryNeedsRebuild) Text("聊天内容已变更，旧提取结果已停用。请手动重建。")
                if (state.memoryVectorEnabled && mode != MemoryMode.NONE) {
                    TextButton(onClick = { confirmVectors = true }) { Text("重建向量索引") }
                }
                TextButton(onClick = { showInactive = !showInactive }) {
                    Text(if (showInactive) "只看有效记忆" else "显示已失效记忆")
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.memoryRecords.filter { showInactive || it.active }.asReversed(), key = { it.id }) { record ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("${if (record.active) "" else "[已失效] "}${record.kind} · ${record.text}")
                            val source = record.sourceIds.mapNotNull { id ->
                                state.messages.firstOrNull { it.id == id }?.let { "${it.name}: ${it.content.take(80)}" }
                            }
                            Text("来源：${source.joinToString(" / ").ifBlank { "用户手动维护" }}")
                            Row {
                                TextButton(onClick = { editing = record; editText = record.text }) { Text("纠正") }
                                TextButton(onClick = { viewModel.correctMemory(record.id, null) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            if (mode != MemoryMode.NONE) Row {
                TextButton(onClick = viewModel::retryMemory) { Text("重试待处理") }
                TextButton(onClick = { confirmRebuild = true }) { Text("补建历史") }
            }
        },
    )
    if (confirmRebuild) {
        val estimated = if (mode == MemoryMode.ARCHIVE) replyCount + replyCount / 12
        else (replyCount + 9) / 10
        AlertDialog(
            onDismissRequest = { confirmRebuild = false },
            title = { Text("补建历史记忆") },
            text = { Text("将整理本对话约 $replyCount 条角色回复，预计至少 $estimated 次记忆模型请求。重新提取会替换当前记忆并产生模型费用。") },
            confirmButton = {
                TextButton(onClick = { confirmRebuild = false; viewModel.rebuildMemory() }) { Text("开始补建") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("取消") } },
        )
    }
    if (confirmVectors) {
        val count = state.memoryRecords.count { it.active }
        AlertDialog(
            onDismissRequest = { confirmVectors = false },
            title = { Text("重建向量索引") },
            text = { Text("将为约 $count 条记忆调用向量接口；这可能产生费用。") },
            confirmButton = { TextButton(onClick = { confirmVectors = false; viewModel.rebuildMemoryVectors() }) { Text("开始") } },
            dismissButton = { TextButton(onClick = { confirmVectors = false }) { Text("取消") } },
        )
    }
    editing?.let { record ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("纠正记忆") },
            text = { OutlinedTextField(editText, { editText = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { viewModel.correctMemory(record.id, editText); editing = null }, enabled = editText.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}
