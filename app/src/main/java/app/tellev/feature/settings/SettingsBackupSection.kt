package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun LazyListScope.backupSectionItems(
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    item(key = "backup_header") {
        SectionHeader(
            icon = Icons.Default.Download,
            title = "备份",
        )
    }

    item(key = "backup_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onExportClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("导出备份")
            }
            OutlinedButton(
                onClick = onImportClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("导入备份")
            }
        }
    }

    item(key = "divider_5") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
internal fun BackupExportDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出备份") },
        text = { Text("将创建一个 ZIP 备份。除非后续明确支持，已保存的密钥不会写入备份。请选择保存位置。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
internal fun BackupImportDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入备份") },
        text = { Text("警告：导入备份会覆盖当前所有数据，且无法撤销。是否继续？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("导入", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
