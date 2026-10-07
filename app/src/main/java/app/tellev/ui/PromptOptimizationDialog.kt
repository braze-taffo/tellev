package app.tellev.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.prompt.PromptOptimizationMode
import app.tellev.core.prompt.PromptOptimizationOptions
import app.tellev.core.prompt.PromptOptimizationStrategies
import app.tellev.core.prompt.PromptOptimizationResult

/**
 * Shared draft-optimization dialog for the chat input bar and the creation
 * conversation. Stateless towards its host: [onRun] starts one optimization
 * (the host owns the job), streaming previews arrive through [onRun]'s
 * callback, and "apply" merely replaces the host's draft — it never sends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptOptimizationDialog(
    providerLabel: String?,
    initialBasePrompt: String? = null,
    onRun: (
        options: PromptOptimizationOptions,
        onPreview: (String) -> Unit,
        onDone: (PromptOptimizationResult) -> Unit,
    ) -> Boolean,
    onCancelRun: () -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(PromptOptimizationMode.Polish) }
    // 策略选择（linshenkx/prompt-optimizer 复刻）：默认通用优化。
    var strategyId by remember { mutableStateOf("general") }
    var keepFacts by remember { mutableStateOf(true) }
    var allowDetail by remember { mutableStateOf(false) }
    var keepMacros by remember { mutableStateOf(true) }
    var languageHint by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    // 迭代：迭代需求在这里填；basePrompt（上一版结果）由调用方传入形成版本链。
    var iterateInput by remember { mutableStateOf("") }
    var basePrompt by remember { mutableStateOf(initialBasePrompt) }
    var running by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PromptOptimizationResult?>(null) }
    var startError by remember { mutableStateOf(false) }

    fun options() = PromptOptimizationOptions(
        mode = mode,
        strategyId = strategyId,
        keepFacts = keepFacts,
        allowDetail = allowDetail,
        keepMacros = keepMacros,
        languageHint = languageHint.trim(),
        instruction = instruction,
        iterateInput = iterateInput.trim(),
        basePrompt = basePrompt,
    )

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.chat_optimize_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (providerLabel != null) {
                    Text(
                        providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 策略选择（开源案例复刻）：六条策略，选中项带一句说明。
                Text(
                    stringResource(R.string.chat_optimize_strategy),
                    style = MaterialTheme.typography.labelMedium,
                )
                PromptOptimizationStrategies.ALL.forEach { strategy ->
                    val selected = strategyId == strategy.id
                    Surface(
                        onClick = { if (!running) strategyId = strategy.id },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { if (!running) strategyId = strategy.id },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strategy.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    strategy.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // 迭代优化：填了迭代需求就走 iterate 模板（把需求融进上一版，
                // 不执行它）；带 basePrompt（上一版结果）时是版本链的一环。
                OutlinedTextField(
                    value = iterateInput,
                    onValueChange = { iterateInput = it.take(PromptOptimizationOptions.MAX_ITERATE_CHARS) },
                    label = { Text(stringResource(R.string.chat_optimize_iterate_input)) },
                    enabled = !running,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.chat_optimize_iterate_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (basePrompt != null) {
                    Text(
                        stringResource(R.string.chat_optimize_version_chain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ToggleRow(stringResource(R.string.chat_optimize_keep_facts), keepFacts, enabled = !running) { keepFacts = it }
                ToggleRow(stringResource(R.string.chat_optimize_allow_detail), allowDetail, enabled = !running) { allowDetail = it }
                ToggleRow(stringResource(R.string.chat_optimize_keep_macros), keepMacros, enabled = !running) { keepMacros = it }
                OutlinedTextField(
                    value = languageHint,
                    onValueChange = { languageHint = it },
                    label = { Text(stringResource(R.string.chat_optimize_language)) },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text(stringResource(R.string.chat_optimize_instruction)) },
                    enabled = !running,
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (running) {
                    Text(
                        stringResource(R.string.chat_optimize_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.isNotBlank()) {
                        Text(
                            preview.takeLast(2_000),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 160.dp),
                        )
                    }
                }
                result?.let { finished ->
                    if (finished.ok) {
                        Text(
                            stringResource(R.string.chat_optimize_preview),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            finished.optimized.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(max = 220.dp),
                        )
                        if (finished.notes.isNotBlank()) {
                            Text(
                                stringResource(R.string.chat_optimize_notes, finished.notes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.chat_optimize_failed, finished.warnings.joinToString("；")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (startError) {
                    Text(
                        stringResource(R.string.chat_optimize_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = {
                    onCancelRun()
                    running = false
                }) { Text(stringResource(R.string.chat_optimize_stop)) }
            } else if (result?.ok == true) {
                Row {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_optimize_cancel)) }
                    TextButton(onClick = {
                        result?.optimized?.let(onApply)
                    }) { Text(stringResource(R.string.chat_optimize_apply)) }
                }
            } else {
                TextButton(onClick = {
                    startError = false
                    preview = ""
                    result = null
                    val started = onRun(options(), { preview = it }, { finished ->
                        running = false
                        result = finished
                    })
                    if (started) running = true else startError = true
                }) { Text(stringResource(R.string.chat_optimize_start)) }
            }
        },
        dismissButton = {
            if (!running && result?.ok != true) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_optimize_cancel)) }
            }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun modeLabel(mode: PromptOptimizationMode): String = when (mode) {
    PromptOptimizationMode.Polish -> stringResource(R.string.chat_optimize_mode_polish)
    PromptOptimizationMode.Expand -> stringResource(R.string.chat_optimize_mode_expand)
    PromptOptimizationMode.Condense -> stringResource(R.string.chat_optimize_mode_condense)
    PromptOptimizationMode.Roleplay -> stringResource(R.string.chat_optimize_mode_roleplay)
    PromptOptimizationMode.Structured -> stringResource(R.string.chat_optimize_mode_structured)
}
