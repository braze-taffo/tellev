package app.tellev.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.prompt.WorldInfoScanner
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Viewer for the last main-line generation's assembled context: stats,
 * warnings, world-book activation detail, memory provenance and the prompt
 * messages themselves. Read-only; export writes a JSON via SAF.
 */
@Composable
fun ContextViewerDialog(
    snapshot: ContextSnapshot?,
    messages: List<app.tellev.core.model.ChatMessage>,
    onDismiss: () -> Unit,
    onJumpToMessage: (Int) -> Unit,
) {
    if (snapshot == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ctxview_title)) },
            text = { Text(stringResource(R.string.ctxview_no_data)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.nav_got_it)) }
            },
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var expandedMessage by rememberSaveable { mutableStateOf(-1) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val json = exportSnapshotJson(snapshot)
            val appContext = context.applicationContext
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("cannot open output")
                    }
                } catch (e: Exception) {
                    // The dialog may already be gone; a toast survives either way.
                    android.widget.Toast.makeText(
                        appContext,
                        UiStrings.get(S.ctxview_export_failed, e.message),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ctxview_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        R.string.ctxview_stats_tokens,
                        snapshot.estimatedTokenCount?.toString() ?: "—",
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.ctxview_stats_messages, snapshot.messages.size) +
                        " · " + stringResource(R.string.ctxview_stats_hits, snapshot.worldBookHits.size) +
                        " · " + stringResource(R.string.ctxview_stats_warnings, snapshot.warnings.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (snapshot.warnings.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.ctxview_section_warnings))
                    snapshot.warnings.forEach { warning ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                warning,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                SectionLabel(stringResource(R.string.ctxview_section_world_hits, snapshot.worldBookHits.size))
                if (snapshot.worldBookHits.isEmpty()) {
                    MutedText(stringResource(R.string.ctxview_empty_section))
                }
                snapshot.worldBookHits.forEach { hit ->
                    Card {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                hit.title ?: hit.entryId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            hit.bookName?.let { MutedText(stringResource(R.string.ctxview_book_name, it)) }
                            val tags = buildList {
                                if (hit.unconditional) add(stringResource(R.string.ctxview_unconditional))
                                if (hit.recursionLevel > 0) add(stringResource(R.string.ctxview_recursion, hit.recursionLevel))
                                hit.tokens?.let { add("≈$it tok") }
                            }
                            if (tags.isNotEmpty()) MutedText(tags.joinToString(" · "))
                            if (hit.matchedKeys.isNotEmpty()) {
                                MutedText(stringResource(R.string.ctxview_matched_keys, hit.matchedKeys.joinToString("、")))
                            }
                            if (hit.matchedSecondaryKeys.isNotEmpty()) {
                                MutedText(stringResource(R.string.ctxview_matched_secondary, hit.matchedSecondaryKeys.joinToString("、")))
                            }
                        }
                    }
                }

                SectionLabel(stringResource(R.string.ctxview_section_world_rejected, snapshot.rejectedWorldEntries.size))
                if (snapshot.rejectedWorldEntries.isEmpty()) {
                    MutedText(stringResource(R.string.ctxview_empty_section))
                }
                snapshot.rejectedWorldEntries.forEach { rejection ->
                    MutedText(
                        (rejection.title ?: rejection.entryId) + " — " + rejectionLabel(rejection.reason),
                    )
                }

                SectionLabel(stringResource(R.string.ctxview_section_memory, snapshot.memoryInjections.size))
                if (snapshot.memoryInjections.isEmpty()) {
                    MutedText(stringResource(R.string.ctxview_empty_section))
                }
                snapshot.memoryInjections.forEach { injection ->
                    Card {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                injection.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                            )
                            val meta = buildList {
                                injection.score?.let { add(stringResource(R.string.ctxview_score, formatScore(it))) }
                                add(injection.kind)
                            }
                            MutedText(meta.joinToString(" · "))
                            if (injection.sourceMessageIds.isNotEmpty()) {
                                val index = injection.sourceMessageIds.firstNotNullOfOrNull { id ->
                                    messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                                }
                                if (index != null) {
                                    Text(
                                        stringResource(R.string.ctxview_jump_to_source),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .clickable { onJumpToMessage(index) },
                                    )
                                } else {
                                    MutedText(stringResource(R.string.ctxview_no_source))
                                }
                            }
                        }
                    }
                }

                SectionLabel(stringResource(R.string.ctxview_section_messages, snapshot.messages.size))
                snapshot.messages.forEachIndexed { index, message ->
                    val expanded = expandedMessage == index
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedMessage = if (expanded) -1 else index },
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                message.role,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (expanded) message.content else message.content.take(140),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.ctxview_copy_cd),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { exportLauncher.launch("tellev-context-${snapshot.capturedAtMillis}.json") }) {
                Text(stringResource(R.string.ctxview_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.nav_got_it)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun rejectionLabel(reason: String): String = when (reason) {
    WorldInfoScanner.REJECT_DISABLED -> stringResource(R.string.ctxview_reason_disabled)
    WorldInfoScanner.REJECT_KEYWORD_MISS -> stringResource(R.string.ctxview_reason_keyword_miss)
    WorldInfoScanner.REJECT_PROBABILITY -> stringResource(R.string.ctxview_reason_probability)
    WorldInfoScanner.REJECT_INCLUSION_GROUP -> stringResource(R.string.ctxview_reason_inclusion_group)
    WorldInfoScanner.REJECT_BUDGET -> stringResource(R.string.ctxview_reason_budget)
    WorldInfoScanner.REJECT_DELAYED -> stringResource(R.string.ctxview_reason_delayed)
    else -> reason
}

private fun formatScore(score: Double): String = String.format(Locale.ROOT, "%.2f", score)

internal fun exportSnapshotJson(snapshot: ContextSnapshot): String = buildJsonObject {
    put("capturedAtMillis", snapshot.capturedAtMillis)
    put("sessionId", snapshot.sessionId)
    snapshot.estimatedTokenCount?.let { put("estimatedTokenCount", it) }
    putJsonArray("warnings") { snapshot.warnings.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("worldBookHits") {
        snapshot.worldBookHits.forEach { hit ->
            add(
                buildJsonObject {
                    put("entryId", hit.entryId)
                    put("title", hit.title?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bookName", hit.bookName?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonArray("matchedKeys") { hit.matchedKeys.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("matchedSecondaryKeys") { hit.matchedSecondaryKeys.forEach { add(JsonPrimitive(it)) } }
                    put("unconditional", hit.unconditional)
                    put("recursionLevel", hit.recursionLevel)
                    hit.tokens?.let { put("tokens", it) }
                },
            )
        }
    }
    putJsonArray("rejectedWorldEntries") {
        snapshot.rejectedWorldEntries.forEach { rejection ->
            add(
                buildJsonObject {
                    put("entryId", rejection.entryId)
                    put("title", rejection.title?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bookName", rejection.bookName?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("reason", rejection.reason)
                },
            )
        }
    }
    putJsonArray("memoryInjections") {
        snapshot.memoryInjections.forEach { injection ->
            add(
                buildJsonObject {
                    put("recordId", injection.recordId)
                    put("kind", injection.kind)
                    put("text", injection.text)
                    injection.score?.let { put("score", it) }
                    putJsonArray("sourceMessageIds") { injection.sourceMessageIds.forEach { add(JsonPrimitive(it)) } }
                },
            )
        }
    }
    putJsonArray("messages") {
        snapshot.messages.forEach { message ->
            add(
                buildJsonObject {
                    put("role", message.role)
                    message.name?.let { put("name", it) }
                    put("content", message.content)
                },
            )
        }
    }
}.toString()
