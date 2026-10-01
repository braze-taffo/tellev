package app.tellev.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
internal fun SectionHeader(
    icon: ImageVector,
    title: String,
    action: (() -> Unit)? = null,
    secondaryAction: (() -> Unit)? = null,
    secondaryActionIcon: ImageVector = Icons.Default.FileUpload,
    secondaryActionDescription: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
            .padding(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (secondaryAction != null) {
            IconButton(
                onClick = secondaryAction,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    secondaryActionIcon,
                    contentDescription = secondaryActionDescription
                        ?: stringResource(R.string.setcomp_import),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (action != null) {
            IconButton(
                onClick = action,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.setcomp_add_cd),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
