package app.tellev.feature.extensions

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.TavernHelperSettings
import app.tellev.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── EJS Template Settings Panel ───────────────────────────────────────

@Composable
fun EjsTemplateSettingsPanel(
    settings: EjsTemplateSettings,
    onUpdate: (EjsTemplateSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.extcompat_ejs_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.extcompat_ejs_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionHeader(stringResource(R.string.extcompat_section_basic))
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_enable_title), stringResource(R.string.extcompat_ejs_enable_subtitle), settings.enabled) {
            onUpdate(settings.copy(enabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_debug_title), stringResource(R.string.extcompat_ejs_debug_subtitle), settings.debugEnabled) {
            onUpdate(settings.copy(debugEnabled = it))
        }

        SettingsSectionHeader(stringResource(R.string.extcompat_section_generate_stage))
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_generate_title), stringResource(R.string.extcompat_ejs_generate_subtitle), settings.generateEnabled) {
            onUpdate(settings.copy(generateEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_generate_loader_title), stringResource(R.string.extcompat_ejs_generate_loader_subtitle), settings.generateLoaderEnabled) {
            onUpdate(settings.copy(generateLoaderEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_inject_loader_title), stringResource(R.string.extcompat_ejs_inject_loader_subtitle), settings.injectLoaderEnabled) {
            onUpdate(settings.copy(injectLoaderEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_preload_title), stringResource(R.string.extcompat_ejs_preload_subtitle), settings.preloadWorldinfoEnabled) {
            onUpdate(settings.copy(preloadWorldinfoEnabled = it))
        }

        SettingsSectionHeader(stringResource(R.string.extcompat_section_render_stage))
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_render_title), stringResource(R.string.extcompat_ejs_render_subtitle), settings.renderEnabled) {
            onUpdate(settings.copy(renderEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_render_loader_title), stringResource(R.string.extcompat_ejs_render_loader_subtitle), settings.renderLoaderEnabled) {
            onUpdate(settings.copy(renderLoaderEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_code_blocks_title), stringResource(R.string.extcompat_ejs_code_blocks_subtitle), settings.codeBlocksEnabled) {
            onUpdate(settings.copy(codeBlocksEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_raw_eval_title), stringResource(R.string.extcompat_ejs_raw_eval_subtitle), settings.rawMessageEvaluationEnabled) {
            onUpdate(settings.copy(rawMessageEvaluationEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_filter_title), stringResource(R.string.extcompat_ejs_filter_subtitle), settings.filterMessageEnabled) {
            onUpdate(settings.copy(filterMessageEnabled = it))
        }

        SettingsSectionHeader(stringResource(R.string.extcompat_section_advanced))
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_sandbox_title), stringResource(R.string.extcompat_ejs_sandbox_subtitle), settings.sandbox) {
            onUpdate(settings.copy(sandbox = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_autosave_title), stringResource(R.string.extcompat_ejs_autosave_subtitle), settings.autosaveEnabled) {
            onUpdate(settings.copy(autosaveEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_no_context_title), stringResource(R.string.extcompat_ejs_no_context_subtitle), settings.withContextDisabled) {
            onUpdate(settings.copy(withContextDisabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_invert_title), stringResource(R.string.extcompat_ejs_invert_subtitle), settings.invertEnabled) {
            onUpdate(settings.copy(invertEnabled = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_compile_workers_title), stringResource(R.string.extcompat_ejs_compile_workers_subtitle), settings.compileWorkers) {
            onUpdate(settings.copy(compileWorkers = it))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_ejs_code_editor_title), stringResource(R.string.extcompat_ejs_code_editor_subtitle), settings.codeEditor) {
            onUpdate(settings.copy(codeEditor = it))
        }
        Text(
            stringResource(
                R.string.extcompat_depth_limit,
                if (settings.depthLimit < 0) stringResource(R.string.extcompat_depth_unlimited) else settings.depthLimit,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.extcompat_reset_default))
            }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.extcompat_done))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── TavernHelper Settings Panel ───────────────────────────────────────

@Composable
fun TavernHelperSettingsPanel(
    settings: TavernHelperSettings,
    onUpdate: (TavernHelperSettings) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.extcompat_th_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.extcompat_th_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        SettingsSectionHeader(stringResource(R.string.extcompat_section_audio))
        SettingsToggleRow(stringResource(R.string.extcompat_th_audio_enable_title), stringResource(R.string.extcompat_th_audio_enable_subtitle), settings.audio.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(enabled = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_bgm_title), stringResource(R.string.extcompat_th_bgm_subtitle), settings.audio.bgm.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(bgm = settings.audio.bgm.copy(enabled = it))))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_bgm_muted_title), stringResource(R.string.extcompat_th_bgm_muted_subtitle), settings.audio.bgm.muted) {
            onUpdate(settings.copy(audio = settings.audio.copy(bgm = settings.audio.bgm.copy(muted = it))))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_ambient_title), stringResource(R.string.extcompat_th_ambient_subtitle), settings.audio.ambient.enabled) {
            onUpdate(settings.copy(audio = settings.audio.copy(ambient = settings.audio.ambient.copy(enabled = it))))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_ambient_muted_title), stringResource(R.string.extcompat_th_ambient_muted_subtitle), settings.audio.ambient.muted) {
            onUpdate(settings.copy(audio = settings.audio.copy(ambient = settings.audio.ambient.copy(muted = it))))
        }
        Text(stringResource(R.string.extcompat_th_volumes, settings.audio.bgm.volume, settings.audio.ambient.volume),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SettingsSectionHeader(stringResource(R.string.extcompat_section_optimize))
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_incompatible_title), stringResource(R.string.extcompat_th_opt_incompatible_subtitle), settings.optimize.disableIncompatibleOption) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(disableIncompatibleOption = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_better_load_title), stringResource(R.string.extcompat_th_opt_better_load_subtitle), settings.optimize.betterMessageToLoad) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterMessageToLoad = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_better_update_title), stringResource(R.string.extcompat_th_opt_better_update_subtitle), settings.optimize.betterCharacterUpdate) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterUpdate = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_better_export_title), stringResource(R.string.extcompat_th_opt_better_export_subtitle), settings.optimize.betterCharacterExport) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterExport = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_better_delete_title), stringResource(R.string.extcompat_th_opt_better_delete_subtitle), settings.optimize.betterCharacterDeletion) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(betterCharacterDeletion = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_worldbook_title), stringResource(R.string.extcompat_th_opt_worldbook_subtitle), settings.optimize.forceRecommendedWorldbookGlobalSettings) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(forceRecommendedWorldbookGlobalSettings = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_opt_max_context_title), stringResource(R.string.extcompat_th_opt_max_context_subtitle), settings.optimize.maximizePresetContextLength) {
            onUpdate(settings.copy(optimize = settings.optimize.copy(maximizePresetContextLength = it)))
        }

        HorizontalDivider()
        SettingsSectionHeader(stringResource(R.string.extcompat_section_render))
        SettingsToggleRow(stringResource(R.string.extcompat_th_render_enable_title), stringResource(R.string.extcompat_th_render_enable_subtitle), settings.render.enabled) {
            onUpdate(settings.copy(render = settings.render.copy(enabled = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_render_streaming_title), stringResource(R.string.extcompat_th_render_streaming_subtitle), settings.render.allowStreaming) {
            onUpdate(settings.copy(render = settings.render.copy(allowStreaming = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_render_blob_title), stringResource(R.string.extcompat_th_render_blob_subtitle), settings.render.useBlobUrl) {
            onUpdate(settings.copy(render = settings.render.copy(useBlobUrl = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_render_hljs_title), stringResource(R.string.extcompat_th_render_hljs_subtitle), settings.render.optimizeHljs) {
            onUpdate(settings.copy(render = settings.render.copy(optimizeHljs = it)))
        }
        SettingsToggleRow(stringResource(R.string.extcompat_th_render_depth_hidden_title), stringResource(R.string.extcompat_th_render_depth_hidden_subtitle), settings.render.depthIgnoreHidden) {
            onUpdate(settings.copy(render = settings.render.copy(depthIgnoreHidden = it)))
        }
        Text(stringResource(R.string.extcompat_th_render_stats, settings.render.collapseCodeBlock, settings.render.depth),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        SettingsSectionHeader(stringResource(R.string.extcompat_section_macro))
        SettingsToggleRow(stringResource(R.string.extcompat_th_macro_enable_title), stringResource(R.string.extcompat_th_macro_enable_subtitle), settings.macro.enabled) {
            onUpdate(settings.copy(macro = settings.macro.copy(enabled = it)))
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.extcompat_reset_default))
            }
            Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.extcompat_done))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Runtime and final-prompt diagnostics ─────────────────────────────

@Composable
fun ExtensionDebugPanel(
    title: String,
    loaded: Boolean,
    status: ExtensionRuntimeStatus?,
    logs: List<ExtensionRuntimeLog>,
    promptSnapshot: PromptDebugSnapshot?,
    showPromptDiagnostics: Boolean,
    onClearLogs: () -> Unit,
    onClearPromptSnapshot: () -> Unit,
    onClose: () -> Unit,
) {
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.extcompat_debug_title, title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (loaded) stringResource(R.string.extcompat_status_loaded) else stringResource(R.string.extcompat_status_not_loaded),
            style = MaterialTheme.typography.bodyMedium,
            color = if (loaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.lastEvent?.let {
            Text(
                stringResource(R.string.extcompat_last_event, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.lastError?.let {
            Text(stringResource(R.string.extcompat_last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (showPromptDiagnostics) {
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.extcompat_final_prompt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (promptSnapshot != null) {
                    OutlinedButton(onClick = onClearPromptSnapshot) { Text(stringResource(R.string.extcompat_clear)) }
                }
            }
            if (promptSnapshot == null) {
                Text(
                    stringResource(R.string.extcompat_prompt_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(
                        R.string.extcompat_prompt_stats,
                        promptSnapshot.messages.size,
                        promptSnapshot.estimatedTokenCount ?: stringResource(R.string.extcompat_unknown),
                        promptSnapshot.activatedWorldEntryIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (promptSnapshot.warnings.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.extcompat_diag_warnings), style = MaterialTheme.typography.labelLarge)
                            promptSnapshot.warnings.forEach { warning ->
                                Text("• $warning", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (promptSnapshot.activatedWorldEntryIds.isNotEmpty()) {
                    Text(
                        stringResource(R.string.extcompat_activated_entries, promptSnapshot.activatedWorldEntryIds.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                promptSnapshot.messages.forEachIndexed { index, message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${index + 1} ${message.role}${message.name?.let { " · $it" }.orEmpty()}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            SelectionContainer {
                                Text(message.content, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.extcompat_runtime_logs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClearLogs, enabled = logs.isNotEmpty()) { Text(stringResource(R.string.extcompat_clear)) }
        }
        if (logs.isEmpty()) {
            Text(
                stringResource(R.string.extcompat_logs_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            logs.takeLast(100).asReversed().forEach { log ->
                val color = when (log.level) {
                    ExtensionRuntimeLogLevel.Error -> MaterialTheme.colorScheme.error
                    ExtensionRuntimeLogLevel.Warning -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                SelectionContainer {
                    Text(
                        "${timeFormatter.format(Date(log.timestampMillis))}  ${log.level.name.uppercase()}  ${log.extensionId ?: "host"}\n${log.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                    )
                }
                HorizontalDivider()
            }
        }

        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.extcompat_done)) }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Shared helpers ────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
