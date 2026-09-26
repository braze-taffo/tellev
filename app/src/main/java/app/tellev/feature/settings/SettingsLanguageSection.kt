package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.i18n.AppLocale

internal fun LazyListScope.languageSectionItems(
    state: SettingsUiState,
    onSetLanguage: (String) -> Unit,
) {
    item(key = "language_header") {
        SectionHeader(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_language),
        )
    }

    item(key = "language_options") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LanguageOption(
                label = stringResource(R.string.settings_language_system),
                selected = state.languageTag == AppLocale.SYSTEM,
                onClick = { onSetLanguage(AppLocale.SYSTEM) },
            )
            AppLocale.SUPPORTED.forEach { (tag, nativeName) ->
                LanguageOption(
                    label = nativeName,
                    selected = state.languageTag == tag,
                    onClick = { onSetLanguage(tag) },
                )
            }
        }
    }

    item(key = "language_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
