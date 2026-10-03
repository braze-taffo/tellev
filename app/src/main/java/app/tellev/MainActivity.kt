package app.tellev

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.tellev.R
import app.tellev.core.i18n.AppLocale
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterSummary
import app.tellev.core.storage.CharacterImporter
import app.tellev.util.UriUtils
import app.tellev.ui.TellevRoot
import app.tellev.ui.theme.TellevTheme
import app.tellev.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val graph: TellevGraph by lazy { TellevGraph.create(this) }
    private val characterImporter = CharacterImporter()

    private companion object {
        /** Import payload cap; see importCharacterFromUri. */
        const val IMPORT_MAX_BYTES = 100L * 1024 * 1024
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
        // 重建后即刻以新语言解析非 Composable 侧的文案（ViewModel 消息等）。
        UiStrings.init(resources)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalTellevGraph provides graph) {
                val themeMode by graph.themeModeFlow.collectAsState()
                val themeAccent by graph.themeAccentFlow.collectAsState()
                TellevTheme(
                    darkTheme = themeMode.isDarkTheme(isSystemInDarkTheme()),
                    accent = themeAccent,
                ) {
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
                .onSuccess { name -> Toast.makeText(this@MainActivity, getString(R.string.main_import_success, name), Toast.LENGTH_SHORT).show() }
                .onFailure { error -> Toast.makeText(this@MainActivity, getString(R.string.main_import_failed, error.message ?: getString(R.string.main_error_read_file)), Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun importCharacterFromUri(uri: Uri): String {
        // An external app can hand us an arbitrarily large VIEW/SEND payload
        // (BROWSABLE intent → a web page can trigger it too). Reading it fully
        // into memory unbounded turned "import" into an OOM kill switch; a card
        // with an embedded 4MB cover is ~6MB, so 100MB is generous headroom.
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                check(total <= IMPORT_MAX_BYTES) { getString(R.string.main_error_too_large) }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: error(getString(R.string.main_error_read_file))
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
        // Type-safe overload arrived in API 33; minSdk 31 keeps the legacy path.
        Intent.ACTION_SEND ->
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(Intent.EXTRA_STREAM)
            }
        else -> null
    }
}
