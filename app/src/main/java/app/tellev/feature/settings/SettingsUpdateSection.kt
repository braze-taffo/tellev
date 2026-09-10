package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellev.feature.update.UpdateUiState
import kotlin.math.roundToInt

@Composable
internal fun UpdateStatus(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val info = state.pendingUpdate
        when {
            info != null -> {
                Text(
                    text = "发现新版本 v${info.version}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (info.releaseNotes.isNotBlank()) {
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "正在下载… ${(state.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.installStarted) "重新下载更新" else "下载更新")
                    }
                }
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在检查更新…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.error != null -> {
                Text(
                    text = "更新检查失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onCheck) { Text("重试") }
            }
            state.upToDate -> {
                Text(
                    text = "已是最新版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                TextButton(onClick = onCheck) { Text("检查更新") }
            }
        }
    }
}
