package app.tellev.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import app.tellev.R
import app.tellev.core.metrics.GenerationMetrics

/** One row in the picker: a model the user can switch to right now. */
internal data class ModelOption(
    val id: String,
    /** Group header this row belongs to (provider display name or a local list). */
    val group: String,
    val isCurrent: Boolean = false,
)

private const val GROUP_CURRENT = "当前模型"
private const val GROUP_RECENT = "最近使用"
private const val GROUP_SUGGESTED = "常用模型"
private const val GROUP_MANUAL = "手动输入"

/**
 * dsh 复刻（参考图二）：模型菜单是锚定在输入栏上方的小浮层，不是底部弹层——
 * 顶部一个内嵌搜索框，下面按服务商分组的模型列表，当前模型行尾打勾。
 * 浮层从触发按钮上方展开（Popup 对齐 BottomEnd）。
 */
@Composable
internal fun ModelPickerPopup(
    providerLabel: String,
    currentModel: String?,
    recentModels: List<String>,
    customEndpoint: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(currentModel.orEmpty()) }

    val options = remember(currentModel, recentModels) {
        buildList {
            currentModel?.takeIf(String::isNotBlank)?.let {
                add(ModelOption(id = it, group = GROUP_CURRENT, isCurrent = true))
            }
            recentModels.filter { it != currentModel }.forEach {
                add(ModelOption(id = it, group = GROUP_RECENT))
            }
            suggestionsFor(providerLabel).filterNot { it == currentModel }.forEach {
                add(ModelOption(id = it, group = GROUP_SUGGESTED))
            }
        }
    }
    val visible = remember(options, query) {
        if (query.isBlank()) {
            options
        } else {
            options.filter { it.id.contains(query.trim(), ignoreCase = true) }
        }
    }
    val grouped = remember(visible) { visible.groupBy { it.group } }

    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, -8),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .width(320.dp)
                .padding(2.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    placeholder = { Text(stringResource(R.string.model_picker_search)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                ) {
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.model_picker_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    grouped.forEach { (group, rows) ->
                        item(key = "header-$group") {
                            Text(
                                group,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 6.dp),
                            )
                        }
                        items(rows, key = { "${group}/${it.id}" }) { option ->
                            Surface(
                                onClick = { onSelect(option.id) },
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        option.id,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (option.isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (option.isCurrent) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = stringResource(R.string.model_picker_current),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 手动输入：自定义端点/任意模型名的兜底入口，视觉上收窄为菜单底部一行。
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.model_picker_manual), style = MaterialTheme.typography.bodySmall) },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { onSelect(manual) },
                        enabled = manual.isNotBlank() && manual.trim() != currentModel,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.model_picker_apply),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
                if (customEndpoint) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.model_picker_custom_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    providerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 6.dp),
                )
            }
        }
    }
}

private fun suggestionsFor(providerLabel: String): List<String> = when {
    providerLabel.contains("Anthropic", ignoreCase = true) -> listOf(
        "claude-sonnet-4-20250514", "claude-3-5-haiku-20241022",
    )
    providerLabel.contains("Gemini", ignoreCase = true) -> listOf(
        "gemini-2.0-flash", "gemini-1.5-pro",
    )
    providerLabel.contains("OpenRouter", ignoreCase = true) -> listOf(
        "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet",
    )
    providerLabel.contains("DeepSeek", ignoreCase = true) -> listOf("deepseek-v4-flash")
    providerLabel.contains("Ollama", ignoreCase = true) -> listOf("llama3", "qwen2.5")
    providerLabel.contains("Horde", ignoreCase = true) -> emptyList()
    else -> listOf("gpt-4o-mini", "gpt-4o")
}

/** Recent distinct model ids from this device's generation history, newest first. */
internal fun recentModelIds(history: List<GenerationMetrics>, limit: Int = 6): List<String> =
    history.asReversed().mapNotNull { it.model?.takeIf(String::isNotBlank) }.distinct().take(limit)
