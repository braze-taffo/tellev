package app.tellev.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellev.R
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class EditablePresetPrompt(
    val prompt: PresetPrompt,
    val isUnused: Boolean,
)

internal fun editablePresetPrompts(preset: GenerationPreset): List<EditablePresetPrompt> {
    val active = preset.prompts.sortedBy { it.order }
    val activeIds = active.mapTo(mutableSetOf()) { it.identifier }
    return active.map { EditablePresetPrompt(it, isUnused = false) } +
        preset.promptsUnused
            .filterNot { it.identifier in activeIds }
            .map { EditablePresetPrompt(it, isUnused = true) }
}

internal fun presetWithEditablePrompts(
    preset: GenerationPreset,
    entries: List<EditablePresetPrompt>,
): GenerationPreset {
    val active = entries.filterNot { it.isUnused }.mapIndexed { index, entry ->
        entry.prompt.copy(order = index)
    }
    val unused = entries.filter { it.isUnused }.map { it.prompt }
    return preset.copy(prompts = active, promptsUnused = unused)
}

internal fun effectiveUnsupportedPresetFields(preset: GenerationPreset): Set<String> {
    val applied = setOf(
        "name", "temperature", "temp", "temp_openai", "top_p", "topP", "top_k", "topK",
        "top_a", "topA", "min_p", "minP", "repetition_penalty", "rep_pen",
        "repetition_penalty_range", "rep_pen_range", "presence_penalty", "frequency_penalty",
        "seed", "stop", "openai_max_context", "max_context", "context_length",
        "openai_max_tokens", "max_tokens", "maxTokens", "max_new_tokens", "prompts",
        "prompts_unused", "promptsUnused", "prompt_order", "extensions", "names_behavior",
        "new_chat_prompt", "new_example_chat_prompt", "squash_system_messages", "assistant_prefill",
    )
    val routing = setOf(
        "chat_completion_source", "openai_model", "claude_model", "openrouter_model",
        "custom_model", "custom_url", "reverse_proxy", "proxy_password", "api_key", "model",
    )
    return preset.raw.keys - applied - routing
}

private val BUILT_IN_PROMPT_SLOTS = setOf(
    "main",
    "worldInfoBefore",
    "worldInfoAfter",
    "charDescription",
    "charPersonality",
    "scenario",
    "personaDescription",
    "dialogueExamples",
    "chatHistory",
)

@Composable
internal fun PresetListItem(
    preset: GenerationPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.setpre_cd_in_use),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.setpre_cd_more),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (selected) stringResource(R.string.setpre_set_current_active) else stringResource(R.string.setpre_set_current)) },
                        enabled = !selected,
                        onClick = {
                            menuOpen = false
                            onSelect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.setpre_export)) },
                        leadingIcon = {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.setpre_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetEditDialog(
    preset: GenerationPreset,
    onDismiss: () -> Unit,
    onSave: (GenerationPreset) -> Unit,
    onSaveAs: (GenerationPreset, String) -> Unit,
    onRename: (GenerationPreset, String) -> Unit,
) {
    var temperature by remember(preset) { mutableStateOf(preset.temperature?.toString().orEmpty()) }
    var topP by remember(preset) { mutableStateOf(preset.topP?.toString().orEmpty()) }
    var topK by remember(preset) { mutableStateOf(preset.topK?.toString().orEmpty()) }
    var maxContext by remember(preset) { mutableStateOf(preset.maxContextTokens?.toString().orEmpty()) }
    var maxCompletion by remember(preset) {
        mutableStateOf((preset.maxCompletionTokens ?: preset.maxTokens)?.toString().orEmpty())
    }
    var rawText by remember(preset) { mutableStateOf(preset.raw.toString()) }
    var validationError by remember(preset) { mutableStateOf<String?>(null) }
    var targetName by remember(preset) { mutableStateOf("") }
    var promptEntries by remember(preset) { mutableStateOf(editablePresetPrompts(preset)) }
    var expandedPromptIds by remember(preset) { mutableStateOf(emptySet<String>()) }
    var generationExpanded by remember(preset) { mutableStateOf(false) }
    var rawJsonExpanded by remember(preset) { mutableStateOf(false) }
    val rawJsonInvalidText = stringResource(R.string.setpre_raw_json_invalid)

    val regexCount = (preset.extensions["regex_scripts"] as? JsonArray)?.size ?: 0
    val preservedRoutingFields = preset.raw.keys.intersect(setOf(
        "chat_completion_source", "openai_model", "claude_model", "openrouter_model",
        "custom_model", "custom_url", "reverse_proxy", "proxy_password", "api_key", "model",
    ))
    val unsupportedFields = effectiveUnsupportedPresetFields(preset)

    fun editedPreset(): GenerationPreset? {
        val raw = runCatching { Json.parseToJsonElement(rawText) as? JsonObject }.getOrNull()
        if (raw == null) {
            validationError = rawJsonInvalidText
            rawJsonExpanded = true
            return null
        }
        return presetWithEditablePrompts(
            preset = preset.copy(
                temperature = temperature.toDoubleOrNull(),
                topP = topP.toDoubleOrNull(),
                topK = topK.toIntOrNull(),
                maxContextTokens = maxContext.toIntOrNull(),
                maxCompletionTokens = maxCompletion.toIntOrNull(),
                maxTokens = maxCompletion.toIntOrNull(),
                raw = raw,
            ),
            entries = promptEntries,
        )
    }

    fun updatePrompt(index: Int, transform: (EditablePresetPrompt) -> EditablePresetPrompt) {
        promptEntries = promptEntries.toMutableList().also { it[index] = transform(it[index]) }
    }

    fun movePrompt(from: Int, to: Int) {
        if (to !in promptEntries.indices) return
        promptEntries = promptEntries.toMutableList().also { list ->
            val item = list.removeAt(from)
            list.add(to, item)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = preset.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${preset.category.name.lowercase()} · ${preset.id}.json",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.setpre_cd_close_editor))
                        }
                    },
                    actions = {
                        IconButton(onClick = { editedPreset()?.let(onSave) }) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.setpre_cd_save_preset))
                        }
                    },
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "preset_summary") {
                        Text(
                            text = stringResource(
                                R.string.setpre_summary_active,
                                promptEntries.count { !it.isUnused && it.prompt.enabled },
                                regexCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    item(key = "generation_header") {
                        PresetEditorSectionHeader(
                            title = stringResource(R.string.setpre_section_generation),
                            subtitle = stringResource(R.string.setpre_section_generation_sub),
                            expanded = generationExpanded,
                            onClick = { generationExpanded = !generationExpanded },
                        )
                    }
                    if (generationExpanded) {
                        item(key = "generation_fields") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = targetName,
                                    onValueChange = { targetName = it },
                                    label = { Text(stringResource(R.string.setpre_target_name_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = temperature,
                                        onValueChange = { temperature = it },
                                        label = { Text("Temperature") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = topP,
                                        onValueChange = { topP = it },
                                        label = { Text("Top-P") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                }
                                OutlinedTextField(
                                    value = topK,
                                    onValueChange = { topK = it },
                                    label = { Text("Top-K") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = maxContext,
                                    onValueChange = { maxContext = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.setpre_max_context_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = maxCompletion,
                                    onValueChange = { maxCompletion = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.setpre_max_completion_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            }
                        }
                    }

                    item(key = "raw_header") {
                        PresetEditorSectionHeader(
                            title = stringResource(R.string.setpre_section_raw),
                            subtitle = stringResource(R.string.setpre_section_raw_sub),
                            expanded = rawJsonExpanded,
                            onClick = { rawJsonExpanded = !rawJsonExpanded },
                        )
                    }
                    if (rawJsonExpanded) {
                        item(key = "raw_fields") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (preservedRoutingFields.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.setpre_preserved_fields, preservedRoutingFields.sorted().joinToString()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                if (unsupportedFields.isNotEmpty()) {
                                    Text(
                                        text = stringResource(
                                            R.string.setpre_unsupported_fields,
                                            unsupportedFields.sorted().joinToString(limit = 16, truncated = "…"),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedTextField(
                                    value = rawText,
                                    onValueChange = {
                                        rawText = it
                                        validationError = null
                                    },
                                    label = { Text(stringResource(R.string.setpre_raw_json_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 8,
                                    maxLines = 18,
                                    supportingText = {
                                        Text(validationError ?: stringResource(R.string.setpre_raw_json_hint))
                                    },
                                    isError = validationError != null,
                                )
                            }
                        }
                    }

                    item(key = "prompts_title") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.setpre_prompts_order_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.setpre_prompts_order_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    itemsIndexed(
                        items = promptEntries,
                        key = { _, entry -> entry.prompt.identifier },
                    ) { index, entry ->
                        val prompt = entry.prompt
                        val expanded = prompt.identifier in expandedPromptIds
                        val effectivelyEnabled = !entry.isUnused && prompt.enabled
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (effectivelyEnabled) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                            ),
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expandedPromptIds = if (expanded) {
                                                expandedPromptIds - prompt.identifier
                                            } else {
                                                expandedPromptIds + prompt.identifier
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.setpre_cd_drag_sort),
                                        modifier = Modifier
                                            .size(36.dp)
                                            .padding(6.dp)
                                            .pointerInput(prompt.identifier, index, promptEntries.size) {
                                                var accumulatedY = 0f
                                                val threshold = 56.dp.toPx()
                                                detectDragGesturesAfterLongPress(
                                                    onDragEnd = { accumulatedY = 0f },
                                                    onDragCancel = { accumulatedY = 0f },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        accumulatedY += amount.y
                                                        when {
                                                            accumulatedY <= -threshold && index > 0 -> {
                                                                movePrompt(index, index - 1)
                                                                accumulatedY = 0f
                                                            }
                                                            accumulatedY >= threshold && index < promptEntries.lastIndex -> {
                                                                movePrompt(index, index + 1)
                                                                accumulatedY = 0f
                                                            }
                                                        }
                                                    },
                                                )
                                            },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = prompt.name.ifBlank { prompt.identifier },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (effectivelyEnabled) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        Text(
                                            text = buildString {
                                                append(prompt.role.ifBlank { "system" })
                                                if (prompt.relative) append(" · In-chat D:${prompt.depth}")
                                                if (entry.isUnused) append(" · ${stringResource(R.string.setpre_not_in_order)}")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Switch(
                                        checked = effectivelyEnabled,
                                        onCheckedChange = { enabled ->
                                            updatePrompt(index) { current ->
                                                current.copy(
                                                    prompt = current.prompt.copy(enabled = enabled),
                                                    isUnused = if (enabled) false else current.isUnused,
                                                )
                                            }
                                        },
                                    )
                                    Icon(
                                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = if (expanded) stringResource(R.string.setpre_cd_collapse) else stringResource(R.string.setpre_cd_expand),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (expanded) {
                                    HorizontalDivider()
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = prompt.role,
                                            onValueChange = { role ->
                                                updatePrompt(index) { current ->
                                                    current.copy(prompt = current.prompt.copy(role = role))
                                                }
                                            },
                                            label = { Text("Role") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                        if (prompt.content.isNotEmpty() || prompt.identifier !in BUILT_IN_PROMPT_SLOTS) {
                                            OutlinedTextField(
                                                value = prompt.content,
                                                onValueChange = { content ->
                                                    updatePrompt(index) { current ->
                                                        current.copy(prompt = current.prompt.copy(content = content))
                                                    }
                                                },
                                                label = { Text("Content") },
                                                modifier = Modifier.fillMaxWidth(),
                                                minLines = 3,
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text("In-chat", modifier = Modifier.weight(1f))
                                            Switch(
                                                checked = prompt.relative,
                                                onCheckedChange = { relative ->
                                                    updatePrompt(index) { current ->
                                                        current.copy(prompt = current.prompt.copy(relative = relative))
                                                    }
                                                },
                                            )
                                            OutlinedTextField(
                                                value = prompt.depth.toString(),
                                                onValueChange = { value ->
                                                    value.filter(Char::isDigit).toIntOrNull()?.let { depth ->
                                                        updatePrompt(index) { current ->
                                                            current.copy(prompt = current.prompt.copy(depth = depth))
                                                        }
                                                    }
                                                },
                                                label = { Text("Depth") },
                                                modifier = Modifier.width(96.dp),
                                                singleLine = true,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = { movePrompt(index, index - 1) },
                                                enabled = index > 0,
                                            ) { Text(stringResource(R.string.setpre_move_up)) }
                                            TextButton(
                                                onClick = { movePrompt(index, index + 1) },
                                                enabled = index < promptEntries.lastIndex,
                                            ) { Text(stringResource(R.string.setpre_move_down)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(shadowElevation = 6.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.setpre_cancel)) }
                        TextButton(
                            onClick = { editedPreset()?.let { onSaveAs(it, targetName) } },
                            enabled = targetName.isNotBlank(),
                        ) { Text(stringResource(R.string.setpre_save_as)) }
                        TextButton(
                            onClick = { editedPreset()?.let { onRename(it, targetName) } },
                            enabled = targetName.isNotBlank(),
                        ) { Text(stringResource(R.string.setpre_rename)) }
                        TextButton(onClick = { editedPreset()?.let(onSave) }) { Text(stringResource(R.string.setpre_save)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PresetEditorSectionHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) stringResource(R.string.setpre_cd_collapse) else stringResource(R.string.setpre_cd_expand),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetCreationDialog(
    providers: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (GenerationPreset) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf(providers.firstOrNull()?.first ?: "") }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var topP by remember { mutableFloatStateOf(0.9f) }
    var maxTokensText by remember { mutableStateOf("") }
    var providerExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setpre_create_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setpre_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                ) {
                    OutlinedTextField(
                        value = providers.find { it.first == selectedProvider }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.setpre_provider_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        providers.forEach { (id, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = {
                                    selectedProvider = id
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.setpre_temperature_value, "%.2f".format(temperature)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19,
                    )
                }

                Column {
                    Text(
                        text = "Top-P: ${"%.2f".format(topP)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = topP,
                        onValueChange = { topP = it },
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                }

                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { value -> maxTokensText = value.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.setpre_max_tokens_label)) },
                    supportingText = { Text(stringResource(R.string.setpre_max_tokens_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            GenerationPreset(
                                id = name.trim()
                                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                                    .ifBlank { "preset" },
                                name = name.trim(),
                                providerType = selectedProvider,
                                category = when (selectedProvider) {
                                    "textgen-webui", "ollama", "llama-cpp" -> PresetCategory.TextGen
                                    "kobold", "koboldcpp", "horde" -> PresetCategory.Kobold
                                    "novelai" -> PresetCategory.NovelAi
                                    else -> PresetCategory.OpenAi
                                },
                                temperature = temperature.toDouble(),
                                topP = topP.toDouble(),
                                maxTokens = maxTokensText.toIntOrNull()?.takeIf { it > 0 },
                                maxCompletionTokens = maxTokensText.toIntOrNull()?.takeIf { it > 0 },
                            ),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.setpre_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setpre_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetImportCategoryDialog(
    fileName: String,
    initialCategory: String,
    onDismiss: () -> Unit,
    onConfirm: (providerCategory: String) -> Unit,
) {
    // The four SillyTavern preset directories. Keys match FileStDataStore's
    // resolvePresetDirectory() mapping; values are user-facing labels.
    val openAiCompatible = stringResource(R.string.setpre_cat_openai_compatible)
    val categories = remember(openAiCompatible) {
        listOf(
            "openai" to openAiCompatible,
            "textgen" to "TextGen WebUI",
            "kobold" to "KoboldAI",
            "novelai" to "NovelAI",
        )
    }
    var selectedCategory by remember(initialCategory) {
        mutableStateOf(initialCategory.takeIf { candidate -> categories.any { it.first == candidate } } ?: "openai")
    }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setpre_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.setpre_import_file, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.setpre_import_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = categories.first { it.first == selectedCategory }.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.setpre_provider_category_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedCategory = id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedCategory) },
            ) {
                Text(stringResource(R.string.setpre_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setpre_cancel))
            }
        },
    )
}

internal fun LazyListScope.presetSectionItems(
    state: SettingsUiState,
    selectedPresetCategory: PresetCategory,
    onSelectCategory: (PresetCategory) -> Unit,
    onImportClick: () -> Unit,
    onCreateClick: () -> Unit,
    onSelectPreset: (GenerationPreset) -> Unit,
    onExportPreset: (GenerationPreset) -> Unit,
    onDeletePreset: (GenerationPreset) -> Unit,
    onEditPreset: (GenerationPreset) -> Unit,
) {
    item(key = "divider_1") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    item(key = "preset_header") {
        SectionHeader(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.setpre_section_header),
            secondaryAction = onImportClick,
            action = onCreateClick,
        )
    }

    item(key = "preset_categories") {
        val categories = listOf(
            PresetCategory.OpenAi to "OpenAI",
            PresetCategory.TextGen to "TextGen",
            PresetCategory.Kobold to "Kobold",
            PresetCategory.NovelAi to "NovelAI",
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.chunked(2).forEach { rowCategories ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowCategories.forEach { (category, label) ->
                        if (category == selectedPresetCategory) {
                            FilledTonalButton(
                                onClick = { onSelectCategory(category) },
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectCategory(category) },
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }
                }
            }
        }
    }

    val visiblePresets = state.presets.filter { it.category == selectedPresetCategory }

    if (visiblePresets.isEmpty()) {
        item(key = "preset_empty") {
            Text(
                text = stringResource(R.string.setpre_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(visiblePresets, key = { "preset_${it.category.name}_${it.id}" }) { preset ->
            PresetListItem(
                preset = preset,
                selected = state.selectedPresetNames[preset.category] == preset.id,
                onSelect = { onSelectPreset(preset) },
                onExport = { onExportPreset(preset) },
                onDelete = { onDeletePreset(preset) },
                onEdit = { onEditPreset(preset) },
            )
        }
    }

    item(key = "divider_2") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}
