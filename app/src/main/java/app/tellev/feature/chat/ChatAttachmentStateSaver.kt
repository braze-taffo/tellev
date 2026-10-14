package app.tellev.feature.chat

import androidx.compose.runtime.saveable.Saver
import app.tellev.core.model.Attachment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Retain pending attachments when navigation saves the chat page. */
internal val ChatAttachmentStateSaver = Saver<List<Attachment>, String>(
    save = { Json.encodeToString(ListSerializer(Attachment.serializer()), it) },
    restore = { runCatching { Json.decodeFromString(ListSerializer(Attachment.serializer()), it) }.getOrDefault(emptyList()) },
)
