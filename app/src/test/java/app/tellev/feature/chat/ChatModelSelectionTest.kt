package app.tellev.feature.chat

import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelSelectionTest {
    private class MemorySecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        var failWrite = false
        var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun putSecret(id: String, value: String) {
            writeGate?.await()
            check(!failWrite) { "Fixture write failure" }
            values[id] = value
        }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    @Test fun `built in selection is persisted and reread before completion`() = runTest {
        val secrets = MemorySecrets()
        val id = ProviderCatalog.DEEPSEEK
        secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID] = id
        secrets.values["provider-$id-model"] = "fixture-model-a"
        val config = persistSelectedProviderModel(secrets, id, " fixture-model-b ")
        assertEquals("fixture-model-b", config.model)
        assertEquals("fixture-model-b", secrets.values["provider-$id-model"])
        assertEquals(id, secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID])
    }

    @Test fun `custom connection identity is retained for equal model ids`() = runTest {
        val secrets = MemorySecrets()
        val first = CustomProviderConfig("first", "First", "http://127.0.0.1:10001", model = "same-model")
        val second = CustomProviderConfig("second", "Second", "http://127.0.0.1:10002", model = "same-model")
        ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(first, second))
        val selectedId = ProviderConfigPersistence.selectedIdFor(first.id)
        secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID] = selectedId
        val config = persistSelectedProviderModel(secrets, selectedId, "fixture-model-b")
        assertEquals(first.baseUrl, config.baseUrl)
        assertEquals("fixture-model-b", config.model)
        val saved = ProviderConfigPersistence.listCustomConfigs(secrets)
        assertEquals(first.copy(model = "fixture-model-b"), saved.first())
        assertEquals(second, saved.last())
        assertEquals(selectedId, secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID])
    }

    @Test fun `failed write does not return success or change old model`() = runTest {
        val secrets = MemorySecrets()
        val id = ProviderCatalog.DEEPSEEK
        secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID] = id
        secrets.values["provider-$id-model"] = "fixture-model-a"
        secrets.failWrite = true
        val result = runCatching { persistSelectedProviderModel(secrets, id, "fixture-model-b") }
        assertTrue(result.isFailure)
        assertEquals("fixture-model-a", secrets.values["provider-$id-model"])
    }

    @Test fun `stale provider selection cannot overwrite another connection`() = runTest {
        val secrets = MemorySecrets()
        secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID] = ProviderCatalog.DEEPSEEK
        val result = runCatching {
            persistSelectedProviderModel(secrets, ProviderCatalog.ANTHROPIC, "same-model")
        }
        assertTrue(result.isFailure)
        assertFalse(secrets.values.containsKey("provider-${ProviderCatalog.ANTHROPIC}-model"))
    }

    @Test fun `selection completion waits for persistence`() = runTest {
        val secrets = MemorySecrets()
        val id = ProviderCatalog.DEEPSEEK
        secrets.values[ProviderDefaults.SELECTED_PROVIDER_SECRET_ID] = id
        secrets.values["provider-$id-model"] = "fixture-model-a"
        val gate = CompletableDeferred<Unit>()
        secrets.writeGate = gate
        val selection = async { persistSelectedProviderModel(secrets, id, "fixture-model-b") }
        testScheduler.runCurrent()
        assertFalse(selection.isCompleted)
        assertEquals("fixture-model-a", secrets.values["provider-$id-model"])
        gate.complete(Unit)
        assertEquals("fixture-model-b", selection.await().model)
    }

    @Test fun `creation catalog only exposes active connection models and failures`() {
        val catalog = ModelCatalogState(groups = listOf(
            ModelCatalogGroup("custom:first", "First", listOf("same-model", "fixture-model-b")),
            ModelCatalogGroup("custom:second", "Second", listOf("same-model", "unrelated-model")),
        ))
        val selected = catalog.forProvider("custom:first")
        assertEquals(listOf("custom:first"), selected.groups.map { it.id })
        assertFalse(selected.allFailed)
        assertTrue(catalog.forProvider("missing").groups.isEmpty())
        val failed = ModelCatalogState(groups = listOf(
            ModelCatalogGroup("custom:first", "First", emptyList(), error = "Fixture listing failure"),
            ModelCatalogGroup("custom:second", "Second", listOf("same-model")),
        )).forProvider("custom:first")
        assertTrue(failed.allFailed)
    }

    @Test
    fun `catalog filter keeps only the active provider group so other endpoints are not selectable`() {
        val catalog = ModelCatalogState(groups = listOf(
            ModelCatalogGroup("deepseek", "DeepSeek", listOf("deepseek-chat")),
            ModelCatalogGroup("custom:mine", "Mine", listOf("my-model")),
        ))
        val selected = catalog.forProvider("custom:mine")
        assertEquals(listOf("my-model"), selected.groups.single().models)
        // 别的服务商分组被完全剔除：菜单里点不到，就不会写错连接。
        assertTrue(selected.groups.none { it.id == "deepseek" })
    }

    @Test
    fun `model profile narrows slider levels to what the model declares`() {
        val profile = app.tellev.core.provider.ModelReasoningProfile(
            efforts = mapOf("low" to "low", "high" to "high"),
        )
        val levels = app.tellev.core.provider.ReasoningSupport.uiLevelsForModel(
            app.tellev.core.provider.ReasoningFamily.OpenAiCompatible,
            profile,
        )
        // Auto（跟随预设）与 Off（关闭思考）永远保留，其余按档案声明收窄。
        assertEquals(
            listOf(
                app.tellev.core.model.ReasoningEffort.Auto,
                app.tellev.core.model.ReasoningEffort.Off,
                app.tellev.core.model.ReasoningEffort.Low,
                app.tellev.core.model.ReasoningEffort.High,
            ),
            levels,
        )
    }

    @Test
    fun `missing or empty profile falls back to family levels`() {
        val family = app.tellev.core.provider.ReasoningFamily.DeepSeek
        assertEquals(
            app.tellev.core.provider.ReasoningSupport.uiLevels(family),
            app.tellev.core.provider.ReasoningSupport.uiLevelsForModel(family, null),
        )
        assertEquals(
            app.tellev.core.provider.ReasoningSupport.uiLevels(family),
            app.tellev.core.provider.ReasoningSupport.uiLevelsForModel(
                family,
                app.tellev.core.provider.ModelReasoningProfile(),
            ),
        )
    }

    @Test
    fun `a stored level the narrowed list no longer offers snaps to a truthful position`() {
        val levels = listOf(
            app.tellev.core.model.ReasoningEffort.Auto,
            app.tellev.core.model.ReasoningEffort.Off,
            app.tellev.core.model.ReasoningEffort.High,
        )
        // 旧构建存下的 Medium 现在不在列表里：必须贴到高分位而不是 0（Auto）。
        assertEquals(2, app.tellev.core.provider.ReasoningSupport.uiIndexOf(levels, app.tellev.core.model.ReasoningEffort.Medium))
        assertEquals(0, app.tellev.core.provider.ReasoningSupport.uiIndexOf(levels, app.tellev.core.model.ReasoningEffort.Auto))
        assertEquals(1, app.tellev.core.provider.ReasoningSupport.uiIndexOf(levels, app.tellev.core.model.ReasoningEffort.Off))
    }
}
