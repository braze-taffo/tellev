package app.tellev.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Image-generation dialog: manual prompt or AI scene summary of the chat. */
@Composable
internal fun ImageGenerationDialog(
    initialPrompt: String,
    initialEngine: String?,
    configuredEngines: Set<String>,
    onShowDiagnostic: (() -> Unit)? = null,
    onGenerate: (prompt: String, negativePrompt: String, summarizeScene: Boolean, engine: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var prompt by remember { mutableStateOf(initialPrompt) }
    var negative by remember { mutableStateOf("") }
    var summarizeScene by remember { mutableStateOf(false) }
    var selectedEngineId by remember(initialEngine) { mutableStateOf(initialEngine) }
    val selectedEngine = selectedEngineId?.let(ChatImageEngine::fromProviderId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成图片") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("生图引擎", style = MaterialTheme.typography.titleSmall)
                if (onShowDiagnostic != null) {
                    TextButton(onClick = onShowDiagnostic) { Text("查看上次总结诊断") }
                }
                ChatImageEngine.entries.forEach { engine ->
                    val configured = engine.providerId in configuredEngines
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = selectedEngineId == engine.providerId,
                            onClick = { selectedEngineId = engine.providerId },
                            enabled = configured,
                        )
                        Text(
                            text = engine.label + if (configured) "" else "（未配置）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (configured) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("由 AI 总结当前场景", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = summarizeScene,
                        onCheckedChange = { summarizeScene = it },
                    )
                }

                if (!summarizeScene) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("正面提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        placeholder = { Text("1girl, solo, masterpiece, best quality...") },
                    )
                } else {
                    Text(
                        text = "将使用当前对话最近的上下文自动生成生图提示词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("负面提示词（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = { Text("lowres, bad anatomy, bad hands...") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedEngine?.let { onGenerate(prompt.trim(), negative.trim(), summarizeScene, it.providerId) } },
                enabled = selectedEngineId in configuredEngines && (summarizeScene || prompt.isNotBlank()),
            ) {
                Text("生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
