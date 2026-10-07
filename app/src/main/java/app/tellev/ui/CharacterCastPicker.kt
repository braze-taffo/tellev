package app.tellev.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary

@Composable
fun CharacterCastPicker(candidates: List<CharacterSummary>, current: List<CharacterCard>, mainId: String,
    onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current.map { it.id }.filterNot { it == mainId }) }
    var query by remember { mutableStateOf("") }
    val rows = (candidates + current.map { CharacterSummary(it.id, it.name, null) }).distinctBy { it.id }
        .filter { it.id != mainId && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cast_picker_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cast_picker_hint))
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.cast_search)) }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(rows, key = { it.id }) { card ->
                    fun toggle() { selected = if (card.id in selected) selected - card.id else selected + card.id }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(card.id in selected, { toggle() })
                        Text(card.name, Modifier.weight(1f))
                    }
                }
            }
            Text(stringResource(R.string.cast_selected_count, selected.size))
        } },
        confirmButton = { TextButton(onClick = { onApply(selected) }) { Text(stringResource(R.string.cast_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cast_cancel)) } })
}
