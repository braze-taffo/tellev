package app.tellev.feature.extensions

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.ExtensionHandle
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionManifest
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.ExtensionSettingsStore
import app.tellev.core.extension.SlashCommand
import app.tellev.core.extension.SlashCommandAutocomplete
import app.tellev.core.extension.SlashCommandInput
import app.tellev.core.extension.SlashCommandResult
import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.extension.VirtualApiRoute
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsGlobalRegexTest {
    @Test
    fun `import and toggle update the global list and notify chat`() = runBlocking {
        val root = Files.createTempDirectory("ext-global-regex-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val layout = StDirectoryLayout.fromRoot(root)
        val disk = FileStDataStore(layout)
        var notifications = 0
        try {
            disk.bootstrap()
            withContext(main) {
                val vm = ExtensionsViewModel(
                    dataStore = disk,
                    extensionHost = QuietExtensionHost(),
                    permissionManager = ExtensionPermissionManager(layout.extensions),
                    settingsStore = ExtensionSettingsStore(layout.extensions),
                    onGlobalRegexChanged = { notifications += 1 },
                ).also { models.put("extensions", it) }
                waitUntil { !vm.uiState.value.isLoading }
                assertTrue(vm.uiState.value.globalRegexScripts.isEmpty())

                vm.importGlobalRegex(
                    """{"scriptName":"Render","findRegex":"/a/g","replaceString":"b","placement":[2]}""",
                )
                waitUntil { vm.uiState.value.globalRegexScripts.size == 1 }
                val imported = vm.uiState.value.globalRegexScripts.single()
                assertEquals("Render", imported.name)
                assertTrue(imported.enabled)
                assertEquals(1, notifications)

                vm.toggleGlobalRegexScript(imported.id)
                waitUntil { vm.uiState.value.globalRegexScripts.single().enabled == false }
                assertEquals(2, notifications)
                val stored = disk.readGlobalRegexScripts().single().jsonObject
                assertEquals(true, stored["disabled"]!!.jsonPrimitive.booleanOrNull)

                vm.importGlobalRegex("""{"spec":"chara_card_v3","data":{"extensions":{"regex_scripts":[]}}}""")
                waitUntil { vm.uiState.value.error?.contains("角色卡") == true }
                assertEquals(1, vm.uiState.value.globalRegexScripts.size)
                assertEquals(2, notifications)
            }
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }
}

private class QuietExtensionHost : ExtensionHost {
    override val events = MutableSharedFlow<ExtensionEvent>()
    override suspend fun load(manifest: ExtensionManifest, scriptSource: String): ExtensionHandle = error("unused")
    override suspend fun unload(extensionId: String) = error("unused")
    override suspend fun emit(event: ExtensionEvent) = Unit
    override fun registerSlashCommand(extensionId: String, command: SlashCommand) = error("unused")
    override suspend fun executeSlashCommand(input: SlashCommandInput): SlashCommandResult = error("unused")
    override fun registerVirtualRoute(extensionId: String, route: VirtualApiRoute) = error("unused")
    override suspend fun handleVirtualApi(request: VirtualApiRequest): VirtualApiResponse = error("unused")
    override fun listSlashCommandAutocompletions(): List<SlashCommandAutocomplete> = emptyList()
    override fun capabilityToken(extensionId: String): String? = null
    override fun deliverPermissionResult(requestId: String, granted: Boolean) = Unit
}
