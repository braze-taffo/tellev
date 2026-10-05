package app.tellev.core.tts

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/** One playback/error/session binding for the process; bubbles never own its lifetime. */
object TtsRuntime {
    private var service: TtsSpeechService? = null
    private var binding: TtsLifecycleBinding? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun get(
        context: Context,
        dataRoot: File,
        providers: ProviderRegistry,
        secrets: SecretStore,
        sessionEvents: Flow<ExtensionEvent>,
    ): TtsSpeechService {
        return service ?: run {
            val appContext = context.applicationContext
            val settings = TtsSettings(appContext)
            TtsSpeechService(settings::load, TtsCache(dataRoot), TtsPlayer(appContext), providers, secrets).also { created ->
                service = created
                binding = TtsLifecycleBinding(scope, created, sessionEvents)
                scope.launch {
                    created.errors.collect { error ->
                        android.widget.Toast.makeText(
                            appContext,
                            UiStrings.get(S.tts_error, error.failure.userMessage()),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    fun bindPage(owner: LifecycleOwner, sessionId: String?) {
        binding?.bind(owner, sessionId)
    }
}
