package app.tellev.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.tellev.feature.update.UpdateUiState
import app.tellev.feature.update.UpdateViewModel

internal fun LazyListScope.aboutSectionItems(
    versionName: String,
    updateState: UpdateUiState,
    updateViewModel: UpdateViewModel,
) {
    item(key = "about_section") {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val clipboardManager = LocalClipboardManager.current
        val bilibiliProfileUrl = "https://space.bilibili.com/499259948"

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = "tellev v$versionName",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UpdateStatus(
                    state = updateState,
                    onCheck = updateViewModel::checkNow,
                    onUpdate = updateViewModel::downloadAndInstall,
                )
                Text(
                    text = "基于 SillyTavern 的原生 Android 客户端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "发布许可",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "tellev 继续以 GNU Affero General Public License v3.0（AGPL-3.0）发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "你可以自由使用、复制、修改和分发本程序；分发修改版或提供网络服务时，应按 AGPL-3.0 提供相应源代码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "tellev 名称、图标和作者信息仅用于官方版本展示，未经授权不得用于冒充官方版本或误导性商业分发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "作者",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "B站：迷迭香のねこ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { uriHandler.openUri(bilibiliProfileUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开作者 B 站主页")
                }
                Text(
                    text = "QQ 交流群：tellev酒馆交流群",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString("754350480"))
                        Toast.makeText(context, "已复制群号 754350480", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("复制 QQ 群号：754350480")
                }
            }
        }
    }

    item(key = "bottom_spacer") {
        Spacer(modifier = Modifier.height(16.dp))
    }
}
