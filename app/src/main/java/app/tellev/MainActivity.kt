package app.tellev

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import app.tellev.core.model.CharacterSummary
import app.tellev.core.storage.CharacterImporter
import app.tellev.util.UriUtils
import app.tellev.ui.TellevRoot
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val graph: TellevGraph by lazy { TellevGraph.create(this) }
    private val characterImporter = CharacterImporter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalTellevGraph provides graph) {
                TellevTheme {
                    TellevRoot()
                }
            }
        }
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        val uri = intent?.importUri() ?: return
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    importCharacterFromUri(uri)
                }
            }
            result
                .onSuccess { name -> Toast.makeText(this@MainActivity, "角色“$name”已导入", Toast.LENGTH_SHORT).show() }
                .onFailure { error -> Toast.makeText(this@MainActivity, "导入失败：${error.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun importCharacterFromUri(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取文件")
        val fileName = UriUtils.resolveDisplayName(this, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported_character"
        val parsed = characterImporter.importFromBytes(bytes, fileName)
        val existingIds = graph.dataStore.listCharacters().map(CharacterSummary::id).toSet()
        val imported = when {
            parsed.id.isBlank() || parsed.id == "imported_character" -> parsed.copy(id = "char_${UUID.randomUUID()}")
            parsed.id in existingIds -> parsed.copy(id = "${parsed.id}_${UUID.randomUUID().toString().take(8)}")
            else -> parsed
        }
        graph.dataStore.importCharacter(imported, bytes, fileName)
        graph.importedCardSignal.value = graph.importedCardSignal.value + 1L
        return imported.name
    }

    private fun Intent.importUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        @Suppress("DEPRECATION")
        Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}
