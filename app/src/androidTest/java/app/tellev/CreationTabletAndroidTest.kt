package app.tellev

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.*
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.StDataStore
import app.tellev.feature.creation.*
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Device interaction and optional live-provider evidence in the isolated validation app. */
class CreationTabletAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = context.cacheDir.resolve("tablet-creation-evidence").also { it.mkdirs() }

    @Test fun configureSuppliedFixtures(): Unit = runBlocking {
        check(context.packageName.endsWith(".mvuvalidation"))
        val graph = TellevGraph.create(context)
        graph.dataStore.bootstrap()
        val root = context.cacheDir.resolve("tablet-fixtures")
        val configFile = root.resolve("provider.json")
        val config = Json.parseToJsonElement(configFile.readText()).jsonObject
        val custom = CustomProviderConfig(
            id = "tablet-test-20261007", name = "平板测试 · step-5-preview",
            baseUrl = config["baseUrl"]!!.jsonPrimitive.content,
            apiKey = config["apiKey"]!!.jsonPrimitive.content,
            model = config["model"]!!.jsonPrimitive.content,
            advanced = OpenAiCompatibilitySettings(modelsPath = "/models", chatCompletionsPath = "/chat/completions", supportsTools = true),
        )
        val existing = ProviderConfigPersistence.listCustomConfigs(graph.secretStore)
        ProviderConfigPersistence.saveCustomConfigs(graph.secretStore, existing.filterNot { it.id == custom.id } + custom)
        graph.secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderConfigPersistence.selectedIdFor(custom.id))
        check(configFile.delete()) { "Remove plaintext credential fixture after encrypted import" }
        val preset = graph.dataStore.importPreset(root.resolve("preset.json").readBytes(), "openai", "梦鲸思客V4-0915.json")
        graph.dataStore.selectPreset(PresetCategory.OpenAi, preset.preset.id)
        val bytes = root.resolve("card.png").readBytes()
        val card = CharacterImporter().importFromBytes(bytes, "card.png").copy(id = "tablet-xuanhun-20261007")
        graph.dataStore.importCharacter(card, bytes, "card.png")
        val saved = graph.dataStore.readCharacter(card.id)
        assertEquals("玄浑纪", saved.name)
        assertEquals(109, saved.characterBook!!.entries.size)
        assertEquals("step-5-preview", ProviderConfigPersistence.loadProviderConfig(graph.secretStore,
            ProviderConfigPersistence.selectedIdFor(custom.id)).model)
        report("fixture-import", buildJsonObject {
            put("card", saved.name); put("lore_count", saved.characterBook!!.entries.size)
            put("preset", preset.preset.name); put("preset_warnings", JsonArray(preset.warnings.map(::JsonPrimitive)))
            put("model", custom.model); put("credentials_encrypted", true)
        })
    }

    @Test fun freeInputAndExistingOptionBothResumeVisibleQuestions(): Unit = withEditor(fakeProvider()) { vm, _, activity ->
        withContext(Dispatchers.Main) { vm.start(CreationKind.Character); vm.send("提问交互验证") }
        waitUntil { vm.state.value.pendingQuestion != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        screenshot("01-question")
        tap(activity.getString(R.string.crs_question_custom_answer))
        typeAnswer("轻松和悬疑都要，再加入冒险")
        screenshot("02-typed-combination")
        tapDescription(activity.getString(R.string.crs_send))
        waitUntil { vm.state.value.pendingQuestion?.question == "再选一个方向" || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.toolEvents.first { it.name == "ask_user" }.detail.contains("轻松和悬疑都要"))
        tap("保持原设定")
        waitUntil { !vm.state.value.busy }
        assertNull(vm.state.value.error)
        assertEquals(2, vm.state.value.toolEvents.count { it.name == "ask_user" && it.ok })
        screenshot("03-question-completed")
        report("question-ui", buildJsonObject { put("custom_combination", true); put("existing_option", true) })
    }

    @Test fun worldCardWorkflowUsesUiAndExportsEmbeddedBook(): Unit = withEditor(null) { vm, _, activity ->
        report("workflow-step", buildJsonObject { put("stage", "prepare-world") })
        withContext(Dispatchers.Main) {
            vm.start(CreationKind.WorldBook)
            vm.editWorldName("平板衔接验证世界")
            vm.editLore(0, LoreDraft("潮汐规则", listOf("旧港"), "旧港只在退潮开放，登记口令是青檐。", id = "L1"))
        }
        val sourceId = vm.state.value.current!!.id
        report("workflow-step", buildJsonObject { put("stage", "click-world-to-card") })
        tap(activity.getString(R.string.crs_world_to_card))
        waitUntil { vm.state.value.current?.kind == CreationKind.Character && !vm.state.value.busy }
        assertTrue(vm.state.value.current!!.lore.isEmpty())
        assertNotNull(vm.state.value.current!!.referenceBook)
        report("workflow-step", buildJsonObject { put("stage", "prepare-card-cover") })
        withContext(Dispatchers.Main) {
            vm.editCard { it.copy(name = "平板验证导演", description = "海港故事的导演。", firstMessage = "欢迎来到旧港。") }
            vm.setCoverPng(PngCardParser.createMinimalPng())
        }
        waitUntil { vm.state.value.current!!.coverSha256.isNotBlank() && !vm.state.value.busy }
        val before = CharacterImporter().importFromBytes(vm.exportCharacter(CharacterExportFormat.Json), "card.json")
        assertNull(before.characterBook)
        screenshot("04-linked-reference")
        report("workflow-step", buildJsonObject { put("stage", "click-merge") })
        tap(activity.getString(R.string.crs_merge_worldbook))
        waitUntil { nodes().any { it.text?.contains("PNG/JSON") == true } }
        screenshot("05-merge-confirmation")
        report("workflow-step", buildJsonObject { put("stage", "confirm-merge") })
        tap(activity.getString(R.string.crs_merge_worldbook))
        waitUntil { vm.state.value.current!!.lore.size == 1 }
        for (format in CharacterExportFormat.entries) {
            val bytes = vm.exportCharacter(format)
            val name = if (format == CharacterExportFormat.Png) "merged-card.png" else "merged-card.json"
            evidence.resolve(name).writeBytes(bytes)
            val exported = CharacterImporter().importFromBytes(bytes, name)
            assertEquals("旧港只在退潮开放，登记口令是青檐。", exported.characterBook!!.entries.single().content)
        }
        tap(activity.getString(R.string.crs_card_to_world))
        waitUntil { vm.state.value.current?.kind == CreationKind.WorldBook && !vm.state.value.busy }
        assertEquals("平板验证导演", vm.state.value.current!!.originalCard!!.name)
        assertEquals(1, vm.state.value.current!!.lore.size)
        val repository = CreationRepository(context.filesDir.resolve("ai-creation"))
        assertEquals(1, repository.load(sourceId).lore.size)
        screenshot("06-reverse-workflow")
        report("workflow-ui", buildJsonObject {
            put("world_to_card", true); put("reference_only_before_merge", true)
            put("explicit_merge", true); put("json_png_roundtrip", true); put("card_to_world", true)
        })
    }

    @Test fun liveProviderReadsReferenceAndAcceptsTypedCombination(): Unit = withEditor(null) { vm, graph, activity ->
        val selected = graph.secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
        check(selected == "custom:tablet-test-20261007") { "Configure tablet fixture provider first" }
        withContext(Dispatchers.Main) {
            vm.start(CreationKind.WorldBook)
            vm.editWorldName("联网验证旧港")
            vm.editLore(0, LoreDraft("潮汐规则", listOf("旧港"), "旧港只在退潮开放，登记口令是青檐。", id = "L1"))
            vm.startRelatedDraft(CreationKind.Character).join()
            vm.send("这是角色卡与世界书衔接测试。先调用 read_card，再使用 source=reference 的 list_lore 和 read_lore 读取关联世界书。" +
                "调用 ask_user 问我想要什么基调，给出轻松、悬疑两个选项，等待我回答。收到答案后创建一张名为旧港导演的角色卡：" +
                "把读到的港口开放规则与登记口令写入 scenario，把我选择的基调写入 description，并填写一句 firstMessage。" +
                "只调用 set_card_fields 写角色卡，不新增或修改世界书条目，不调用高级资源工具。最后简短回复完成。")
        }
        try {
            waitUntil(240_000) {
                progress(vm)
                vm.state.value.pendingQuestion != null || !vm.state.value.busy
            }
            assertNull(vm.state.value.error)
            assertNotNull("The live provider never asked the requested question", vm.state.value.pendingQuestion)
            screenshot("07-live-question")
            tap(activity.getString(R.string.crs_question_custom_answer))
            typeAnswer("轻松和悬疑都要，再加入一点冒险")
            tapDescription(activity.getString(R.string.crs_send))
            waitUntil(240_000) { progress(vm); !vm.state.value.busy }
            assertNull(vm.state.value.error)
            val current = vm.state.value.current!!
            assertEquals("旧港导演", current.card.name)
            assertTrue(current.card.scenario.contains("退潮"))
            assertTrue(current.card.scenario.contains("青檐"))
            assertTrue(current.card.description.contains("轻松") && current.card.description.contains("悬疑"))
            assertTrue(current.lore.isEmpty())
            val events = vm.state.value.toolEvents
            assertTrue(events.any { it.name == "list_lore" && it.ok })
            assertTrue(events.any { it.name == "read_lore" && it.ok })
            assertTrue(events.any { it.name == "ask_user" && it.ok && it.detail.contains("轻松和悬疑都要") })
            screenshot("08-live-completed")
            report("live-provider", buildJsonObject {
                put("model", "step-5-preview"); put("phase", vm.state.value.modelPhase)
                put("tool_events", JsonArray(events.map { buildJsonObject { put("name", it.name); put("ok", it.ok); put("detail", it.detail) } }))
                put("scenario", current.card.scenario); put("description", current.card.description)
                put("embedded_entries", current.lore.size); put("delta_count", vm.state.value.deltaCount)
            })
        } finally { withContext(Dispatchers.Main) { vm.cancelGeneration() } }
    }

    @Test fun suppliedBookCanBeLinkedAndMergedWithoutLosingEntries(): Unit = withEditor(null) { vm, graph, activity ->
        val supplied = graph.dataStore.readCharacter("tablet-xuanhun-20261007")
        val source = vm.worldBookSources().first {
            !it.fromDraft && it.id == StDataStore.embeddedCharacterBookId(supplied.id)
        }
        withContext(Dispatchers.Main) {
            vm.start(CreationKind.Character)
            vm.editCard { it.copy(name = "真实世界书合并验证", description = "参考玄浑纪设定的导演。", firstMessage = "开始故事。") }
        }
        tap(activity.getString(R.string.crs_link_worldbook))
        tap(source.name)
        waitUntil { vm.state.value.current!!.referenceBook != null && !vm.state.value.busy }
        assertEquals(109, vm.state.value.current!!.referenceBook!!.entries.size)
        assertTrue(vm.state.value.current!!.lore.isEmpty())
        val expected = supplied.characterBook!!.entries.map { it.content.trim() }
        assertEquals(expected, vm.state.value.current!!.referenceBook!!.entries.map { it.content.trim() })
        tap(activity.getString(R.string.crs_merge_worldbook))
        tap(activity.getString(R.string.crs_merge_worldbook))
        waitUntil { vm.state.value.current!!.lore.size == 109 }
        withContext(Dispatchers.Main) { vm.setCoverPng(PngCardParser.createMinimalPng()) }
        waitUntil { vm.state.value.current!!.coverSha256.isNotBlank() && !vm.state.value.busy }
        for (format in CharacterExportFormat.entries) {
            val bytes = vm.exportCharacter(format)
            val name = if (format == CharacterExportFormat.Png) "supplied-book-merged.png" else "supplied-book-merged.json"
            val exported = CharacterImporter().importFromBytes(bytes, name)
            assertEquals(expected, exported.characterBook!!.entries.map { it.content })
            assertEquals(109, exported.characterBook!!.entries.map { it.id }.distinct().size)
            evidence.resolve(name).writeBytes(bytes)
        }
        screenshot("09-real-book-linked-and-merged")
        report("supplied-book-workflow", buildJsonObject { put("source_card", supplied.name); put("entries", 109); put("contents_preserved", true); put("unique_uids", true); put("json_png_roundtrip", true) })
    }

    @Test fun liveConnectionProbe(): Unit = runBlocking {
        val graph = TellevGraph.create(context)
        val selected = requireNotNull(graph.secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID))
        val config = ProviderConfigPersistence.loadProviderConfig(graph.secretStore, selected)
        val adapter = requireNotNull(graph.providerRegistry.find(ProviderConfigPersistence.adapterIdFor(selected)))
        val status = adapter.checkStatus(config)
        report("connection-status", buildJsonObject { put("available", status.available); put("message", status.message) })
        assertTrue(status.message, status.available)
        val result = buildJsonObject {
            var deltas = 0
            var completed = false
            var error = ""
            withTimeout(150_000) {
                adapter.streamGenerate(config, GenerateRequest(
                    PromptBuildResult(listOf(PromptMessage(MessageRole.User, content = "只回复 OK。")), emptyList(), 128,
                        config.providerType, PromptDiagnostics(emptyList())),
                    GenerationPreset(id = "tablet-connection-probe", name = "Connection probe", providerType = config.providerType,
                        category = PresetCategory.OpenAi, maxCompletionTokens = 128),
                )).collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> deltas++
                        is GenerateChunk.Completed -> { completed = true; put("text", chunk.text); put("finish_reason", chunk.finishReason.orEmpty()) }
                        is GenerateChunk.Failed -> { error = chunk.error.message; put("error_code", chunk.error.code) }
                    }
                }
            }
            put("delta_count", deltas); put("completed", completed); put("error", error)
        }
        report("connection-generation", result)
        assertTrue(result.toString(), result["completed"]!!.jsonPrimitive.boolean)
    }

    private fun fakeProvider(): ProviderRegistry {
        var calls = 0
        val adapter = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "UI fixture"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
                val response = when (++calls) {
                    1 -> """<tool_call>{"name":"ask_user","arguments":{"question":"想要哪种基调？","options":[{"label":"轻松"},{"label":"悬疑"}]}}</tool_call>"""
                    2 -> """<tool_call>{"name":"ask_user","arguments":{"question":"再选一个方向","options":[{"label":"保持原设定"},{"label":"换个方向"}]}}</tool_call>"""
                    else -> "回答已收到，继续制作。"
                }
                return flowOf(GenerateChunk.Completed(response))
            }
        }
        return ProviderRegistry(listOf(adapter))
    }

    private fun withEditor(providers: ProviderRegistry?, block: suspend (CreationViewModel, TellevGraph, MainActivity) -> Unit): Unit = runBlocking {
        check(context.packageName.endsWith(".mvuvalidation"))
        val graph = TellevGraph.create(context)
        graph.dataStore.bootstrap()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val models = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            CreationViewModel(CreationRepository(context.filesDir.resolve("ai-creation")), graph.dataStore, graph.secretStore,
                providers ?: graph.providerRegistry).also {
                models.put("tablet-creation", it)
                activity.setContent {
                    CompositionLocalProvider(LocalTellevGraph provides graph) {
                        TellevTheme { CreationEditorScreen(it, {}, { _, _ -> }) }
                    }
                }
            }
        }
        try { block(vm, graph, activity) }
        catch (error: Throwable) {
            screenshot("last-failure")
            report("last-failure", buildJsonObject {
                put("error_type", error.javaClass.simpleName); put("message", error.message.orEmpty())
                put("busy", vm.state.value.busy); put("kind", vm.state.value.current?.kind?.name.orEmpty())
                put("app_error", vm.state.value.error.orEmpty())
                put("visible_text", JsonArray(nodes().mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.map(::JsonPrimitive)))
            })
            throw error
        }
        finally { withContext(Dispatchers.Main) { models.clear(); activity.finish() } }
    }

    private suspend fun waitUntil(timeout: Long = 30_000, predicate: suspend () -> Boolean) = withTimeout(timeout) {
        while (!predicate()) delay(100)
        delay(300)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }

    private suspend fun tap(label: String) = click { it.text?.toString() == label }
    private suspend fun tapDescription(label: String) = click { it.contentDescription?.toString() == label }
    private suspend fun click(matches: (AccessibilityNodeInfo) -> Boolean) {
        fun clickable(): AccessibilityNodeInfo? = nodes().filter(matches).firstNotNullOfOrNull { candidate ->
            var node = candidate
            while (!node.isClickable && node.parent != null) node = node.parent
            node.takeIf { it.isClickable }
        }
        waitUntil { clickable() != null }
        val node = requireNotNull(clickable())
        assertTrue("Button is disabled: ${node.text}", node.isEnabled)
        assertTrue("Click failed: ${node.text}", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(400)
    }

    private suspend fun typeAnswer(text: String) {
        var field: AccessibilityNodeInfo? = null
        waitUntil { field = nodes().firstOrNull { it.className?.toString() == "android.widget.EditText" && it.isFocused }; field != null }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        delay(400)
    }

    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let {
            evidence.resolve("$name.png").outputStream().use { output -> it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
            it.recycle()
        }
    }

    private fun progress(vm: CreationViewModel) {
        val state = vm.state.value
        report("live-progress", buildJsonObject {
            put("phase", state.modelPhase); put("delta_count", state.deltaCount)
            put("elapsed_ms", state.modelElapsedMillis); put("tool_count", state.toolEvents.size)
            put("question_pending", state.pendingQuestion != null); put("error", state.error.orEmpty())
        })
    }

    private fun report(name: String, value: JsonObject) { evidence.resolve("$name.json").writeText(value.toString()) }
}
