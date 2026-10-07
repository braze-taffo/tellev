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
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterCastBinding
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.*
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.feature.creation.*
import app.tellev.feature.characters.CharactersViewModel
import app.tellev.feature.characters.CharacterDetailScreen
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.ChatScreen
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Device interaction and optional live-provider evidence in the isolated validation app. */
class CreationTabletAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = context.cacheDir.resolve("tablet-creation-evidence").also { it.mkdirs() }

    private suspend fun installCastAndBookFixtures(graph: TellevGraph): List<WorldBook> {
        val books = listOf(
            WorldBook("tablet-cast-canon", "00平板主世界·潮港", listOf(WorldBookEntry("0", listOf("潮港"),
                content = "潮港处于无电力的帆船时代，仅退潮开放。青檐是官方登记口令。禁止现代科技与超自然法术。", constant = true))),
            WorldBook("tablet-cast-economy", "00平板辅助·航运", listOf(WorldBookEntry("0", listOf("商会"),
                content = "潮港航运以盐票结算。青帆商会垄断盐票，渡船公会维护小船户权益，两方既合作又争夺码头。", constant = true))),
        )
        books.forEach { graph.dataStore.saveWorldBook(it) }
        graph.dataStore.saveCharacter(CharacterCard("tablet-cast-zhou", "00舟青·平板附属", description = "舟青是潮港渡船人，随身带潮位册。",
            personality = "谨慎寡言，目标是摆脱青帆商会债务。", scenario = "舟青知道青檐口令，但不知道商会金库位置。",
            exampleMessages = "舟青：先看潮位，别急着上船。"))
        graph.dataStore.saveCharacter(CharacterCard("tablet-cast-shen", "00沈砚·平板附属", description = "沈砚是青帆商会账房，用算盘核验盐票。",
            personality = "精明多话，目标是保住商会航运垄断。", scenario = "沈砚知道金库位置，想招募舟青为商会效力。",
            exampleMessages = "沈砚：潮水会退，账可不会自己清。"))
        return books.map { graph.dataStore.readWorldBook(it.id) }
    }

    @Test fun multiWorldBooksAndCastUseVisiblePickerAndSurviveReopen(): Unit = withEditor(null) { vm, graph, activity ->
        val books = installCastAndBookFixtures(graph)
        withContext(Dispatchers.Main) {
            vm.start(CreationKind.Character)
            vm.editCard { it.copy(name = "平板群像导演", description = "由导演统一叙事，按附属人物设定表现潮港群像。", firstMessage = "潮港退潮，渡船靠岸。") }
        }
        val draftId = vm.state.value.current!!.id
        tapScrolled(activity.getString(R.string.crs_multi_worldbooks))
        toggleBook(books[0].name)
        toggleBook(books[1].name)
        tapScrolled(activity.getString(R.string.crs_make_primary_worldbook))
        screenshot("cast-01-two-books-primary-changed")
        tap(activity.getString(R.string.cast_apply))
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.referenceBooks().size == 2 }
        assertEquals(books[1].id, vm.state.value.current!!.referenceBook!!.id)
        tapScrolled(activity.getString(R.string.crs_multi_worldbooks))
        tapScrolled(activity.getString(R.string.crs_make_primary_worldbook))
        tap(activity.getString(R.string.cast_apply))
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.referenceBook!!.id == books[0].id }
        tapScrolled(activity.getString(R.string.cast_picker_title))
        tapScrolled("00舟青·平板附属")
        tapScrolled("00沈砚·平板附属")
        screenshot("cast-02-two-actors-selected")
        tap(activity.getString(R.string.cast_apply))
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.castMembers().size == 2 }
        withContext(Dispatchers.Main) { vm.open(draftId).join() }
        assertEquals(listOf(books[0].id, books[1].id), vm.state.value.current!!.referenceBooks().map { it.id })
        assertEquals(2, vm.state.value.current!!.castMembers().size)
        withContext(Dispatchers.Main) { vm.setCoverPng(PngCardParser.createMinimalPng()) }
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.coverSha256.isNotBlank() }
        for (format in CharacterExportFormat.entries) {
            val bytes = vm.exportCharacter(format)
            val name = if (format == CharacterExportFormat.Png) "cast-card.png" else "cast-card.json"
            val roundtrip = CharacterImporter().importFromBytes(bytes, name)
            assertEquals(2, CharacterCastBinding.members(roundtrip).size)
            assertTrue(CharacterCastBinding.members(roundtrip).first().personality.contains("摆脱"))
            evidence.resolve(name).writeBytes(bytes)
        }
        tapScrolled(activity.getString(R.string.crs_merge_worldbook))
        screenshot("cast-03-multi-book-merge-confirmation")
        tap(activity.getString(R.string.crs_merge_worldbook))
        waitUntil { vm.state.value.current!!.lore.size == 2 }
        assertEquals(2, vm.state.value.current!!.lore.map { it.id }.distinct().size)
        tap(activity.getString(R.string.crs_save_to_character_list))
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.savedArtifactId.isNotBlank() }
        assertNull(vm.state.value.error)
        val stored = graph.dataStore.readCharacter(vm.state.value.current!!.savedArtifactId)
        assertEquals(2, CharacterCastBinding.members(stored).size)
        assertEquals(2, stored.characterBook!!.entries.size)
        for (book in books) assertEquals(book.entries, graph.dataStore.readWorldBook(book.id).entries)
        screenshot("cast-04-restored-and-merged")
        report("cast-multi-ui", buildJsonObject {
            put("two_reference_books", true); put("primary_switched_twice", true); put("two_supporting_cards", true)
            put("reopen_preserves_all", true); put("png_json_cast_roundtrip", true); put("merge_unique_ids", true)
            put("source_books_unchanged", true); put("draft_id", draftId)
            put("saved_card_id", stored.id); put("saved_card_cast_and_lore", true)
        })
    }

    @Test fun liveFactionBookReadsTwoSourcesAndWritesIndependentDraft(): Unit = withEditor(recordingLiveProvider()) { vm, graph, activity ->
        val books = installCastAndBookFixtures(graph)
        withContext(Dispatchers.Main) {
            vm.start(CreationKind.WorldBook)
            vm.editWorldName("平板势力创作源草稿")
            vm.associateWorldBooks(books.map { CreationWorldBookSource(it.id, it.name, it.entries.size, false) }).join()
        }
        val sourceId = vm.state.value.current!!.id
        tapScrolled(activity.getString(R.string.crs_create_faction_book))
        waitUntil { vm.state.value.current!!.id != sourceId }
        val factionId = vm.state.value.current!!.id
        try {
            waitUntil(600_000) {
                progress(vm)
                if (vm.state.value.pendingQuestion != null) {
                    screenshot("faction-live-question")
                    tap(activity.getString(R.string.crs_question_custom_answer))
                    typeAnswer("用于通用群像叙事，请写三到四个相互制衡的势力，每个势力写完整的起源、目标、组织、代表人物、资源、地盘、关系、内部矛盾、加入条件和剧情钩子。坚持主世界无电力无超自然设定，辅助航运设定完全采纳。不依赖玩家，缺失历史人物可合理新增并标明新增设计。现在直接完成并写入世界书条目。")
                    tapDescription(activity.getString(R.string.crs_send))
                }
                !vm.state.value.busy
            }
            assertNull(vm.state.value.error)
            val result = vm.state.value.current!!
            assertTrue("No faction entries created", result.lore.size >= 3)
            val events = vm.state.value.toolEvents
            assertTrue(events.any { it.name == "list_lore" && it.ok })
            assertTrue(events.any { it.name == "read_lore" && it.ok })
            val contents = result.lore.joinToString("\n") { it.content }
            assertTrue(contents.contains("盐票"))
            assertTrue(contents.contains("退潮") || contents.contains("潮位"))
            assertTrue(result.lore.all { it.keys.isNotEmpty() && it.content.length >= 150 })
            val repository = CreationRepository(context.filesDir.resolve("ai-creation"))
            assertTrue(repository.load(sourceId).lore.isEmpty())
            for (book in books) assertEquals(book.entries, graph.dataStore.readWorldBook(book.id).entries)
            evidence.resolve("live-faction-worldbook.json").writeBytes(worldBookExportBytes(result.toWorldBook()))
            evidence.resolve("live-faction-session.json").writeText(FileStDataStore.defaultJson.encodeToString(CreationSession.serializer(), result))
            screenshot("faction-live-completed")
            withContext(Dispatchers.Main) { vm.open(factionId).join() }
            assertEquals(result.lore, vm.state.value.current!!.lore)
            report("live-faction", buildJsonObject {
                put("model", "step-5-preview"); put("entry_count", result.lore.size); put("independent_draft", factionId != sourceId)
                put("sources_unchanged", true); put("reopen_preserves_result", true)
                put("tool_events", JsonArray(events.map { buildJsonObject { put("name", it.name); put("ok", it.ok); put("detail", it.detail) } }))
            })
        } finally { withContext(Dispatchers.Main) { vm.cancelGeneration() } }
    }

    @Test fun liveNarrationUsesFullCastOnTablet(): Unit = withEditor(null) { _, graph, activity ->
        installCastAndBookFixtures(graph)
        val actors = listOf("tablet-cast-zhou", "tablet-cast-shen").map { graph.dataStore.readCharacter(it) }
        val card = CharacterCastBinding.withMembers(CharacterCard("tablet-cast-director-${java.util.UUID.randomUUID()}", "潮港导演",
            description = "由你统一叙事。保持各角色知识边界，不替玩家决定行动。", firstMessage = "潮港退潮，渡船靠岸，商会账房等在码头。"), actors)
        graph.dataStore.saveCharacter(card)
        val selected = requireNotNull(graph.secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID))
        val config = ProviderConfigPersistence.loadProviderConfig(graph.secretStore, selected)
        val previousPreset = graph.dataStore.readSelectedPresetName(PresetCategory.OpenAi)
        val preset = GenerationPreset("tablet-cast-live", "平板群像测试", config.providerType,
            category = PresetCategory.OpenAi, maxContextTokens = 64000, maxCompletionTokens = 16384)
        graph.dataStore.savePreset(preset)
        graph.dataStore.selectPreset(PresetCategory.OpenAi, preset.id)
        val models = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ChatViewModel(graph.dataStore, recordingLiveProvider(http1 = false, label = "cast"), graph.promptEngine, graph.secretStore,
                graph.extensionHost, graph.permissionManager).also {
                models.put("tablet-cast-chat", it)
                activity.setContent { CompositionLocalProvider(LocalTellevGraph provides graph) {
                    TellevTheme { ChatScreen(it) }
                } }
            }
        }
        try {
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.providerConfig != null }
            withContext(Dispatchers.Main) { vm.selectCharacter(card.id) }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.selectedCharacter?.id == card.id && vm.uiState.value.currentSession != null }
            waitUntil { nodes().any { it.className?.toString() == "android.widget.EditText" } }
            assertTrue(nodes().first { it.className?.toString() == "android.widget.EditText" }
                .performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            typeAnswer("请用约500字演出舟青与沈砚在潮港码头谈判的一幕，包含各自台词，体现两人的目标、说话方式和知识边界。不要让舟青突然知道金库位置，不替旅人做选择。")
            tapDescription(activity.getString(R.string.chat_send_message))
            waitUntil(400_000) {
                report("live-cast-progress", buildJsonObject { put("generating", vm.uiState.value.isGenerating); put("stream_chars", vm.uiState.value.streamingText.length); put("reasoning_chars", vm.uiState.value.streamingReasoning.length); put("error", vm.uiState.value.error.orEmpty()) })
                val state = vm.uiState.value
                val userIndex = state.messages.indexOfLast { it.role == MessageRole.User }
                !state.isGenerating && (state.error != null ||
                    (userIndex >= 0 && state.messages.indexOfLast { it.role == MessageRole.Character } > userIndex))
            }
            assertNull(vm.uiState.value.error)
            val output = vm.uiState.value.messages.last { it.role == MessageRole.Character }.content
            evidence.resolve("live-cast-narration.txt").writeText(output)
            assertTrue(output.contains("舟青") && output.contains("沈砚"))
            assertTrue(output.contains("盐票") || output.contains("债"))
            val stored = graph.dataStore.readChatSession(vm.uiState.value.currentSession!!.id)
            assertEquals(output, stored.messages.last { it.role == MessageRole.Character }.content)
            screenshot("cast-chat-live-completed")
            report("live-cast", buildJsonObject { put("completed", true); put("real_chat_screen", true); put("model", config.model); put("characters", 2); put("output_chars", output.length); put("saved", true) })
        } catch (error: Throwable) {
            report("cast-chat-failure", buildJsonObject {
                put("error", error.message.orEmpty()); put("app_error", vm.uiState.value.error.orEmpty())
                put("loading", vm.uiState.value.isLoading); put("provider_configured", vm.uiState.value.providerConfig != null)
                put("character_id", vm.uiState.value.selectedCharacter?.id.orEmpty())
                put("roles", vm.uiState.value.messages.joinToString { it.role.name })
            })
            throw error
        } finally {
            withContext(Dispatchers.Main) { vm.stopGeneration(); models.clear() }
            previousPreset?.let { graph.dataStore.selectPreset(PresetCategory.OpenAi, it) }
        }
    }

    @Test fun savedCardDetailCanChangeAndClearCastThroughUi(): Unit = withEditor(null) { _, graph, activity ->
        installCastAndBookFixtures(graph)
        val actors = listOf("tablet-cast-zhou", "tablet-cast-shen").map { graph.dataStore.readCharacter(it) }
        val main = CharacterCastBinding.withMembers(CharacterCard("tablet-detail-cast-main", "平板详情群像导演",
            description = "主卡统一叙事。", firstMessage = "请进入潮港。"), actors)
        graph.dataStore.saveCharacter(main)
        val models = ViewModelStore()
        var vm = withContext(Dispatchers.Main) {
            CharactersViewModel(graph.dataStore, MutableStateFlow(0L)).also {
                models.put("tablet-details", it)
                it.selectCharacter(main.id)
                activity.setContent { TellevTheme { CharacterDetailScreen(it, {}) } }
            }
        }
        var stage = "initial-load"
        try {
            waitUntil { vm.uiState.value.selectedCharacter?.id == main.id && !vm.uiState.value.isLoading }
            tapScrolled(activity.getString(R.string.cast_picker_title))
            tapScrolled("00沈砚·平板附属")
            tap(activity.getString(R.string.cast_apply))
            tapScrolled(activity.getString(R.string.chars_save_changes))
            waitUntil { CharacterCastBinding.members(graph.dataStore.readCharacter(main.id)).size == 1 }
            assertEquals(listOf("tablet-cast-zhou"), CharacterCastBinding.members(graph.dataStore.readCharacter(main.id)).map { it.id })
            screenshot("cast-detail-01-one-member-saved")
            stage = "reopen-load"
            vm = withContext(Dispatchers.Main) {
                CharactersViewModel(graph.dataStore, MutableStateFlow(0L)).also {
                    models.put("tablet-details-reopened", it)
                    it.selectCharacter(main.id)
                    activity.setContent { androidx.compose.runtime.key("reopened") {
                        TellevTheme { CharacterDetailScreen(it, {}) }
                    } }
                }
            }
            waitUntil { vm.uiState.value.selectedCharacter?.id == main.id && !vm.uiState.value.isLoading }
            stage = "open-picker-after-reopen"
            tapScrolled(activity.getString(R.string.cast_picker_title))
            stage = "clear-last-member"
            tapScrolled("00舟青·平板附属")
            tap(activity.getString(R.string.cast_apply))
            stage = "save-empty-cast"
            tapScrolled(activity.getString(R.string.chars_save_changes))
            stage = "verify-empty-cast"
            waitUntil { CharacterCastBinding.members(graph.dataStore.readCharacter(main.id)).isEmpty() }
            val result = graph.dataStore.readCharacter(main.id)
            assertEquals(main.description, result.description)
            assertEquals(main.firstMessage, result.firstMessage)
            screenshot("cast-detail-02-all-members-cleared")
            report("cast-detail-ui", buildJsonObject { put("remove_one", true); put("reopen", true); put("clear_all", true); put("main_fields_preserved", true) })
        } catch (error: Throwable) {
            report("cast-detail-failure", buildJsonObject {
                put("stage", stage); put("error", error.message.orEmpty()); put("app_error", vm.uiState.value.error.orEmpty())
                put("loading", vm.uiState.value.isLoading); put("selected", vm.uiState.value.selectedCharacter?.id.orEmpty())
                put("stored_cast", CharacterCastBinding.members(graph.dataStore.readCharacter(main.id)).joinToString { it.id })
            })
            throw error
        } finally { withContext(Dispatchers.Main) { models.clear() } }
    }

    @Test fun saveCompletedFactionDraftThroughUi(): Unit = withEditor(null) { vm, graph, activity ->
        val result = FileStDataStore.defaultJson.decodeFromString(CreationSession.serializer(),
            evidence.resolve("live-faction-session.json").readText())
        withContext(Dispatchers.Main) { vm.open(result.id).join() }
        tap(activity.getString(R.string.crs_save_to_worldbook))
        waitUntil { !vm.state.value.busy && vm.state.value.current!!.savedArtifactId.isNotBlank() }
        assertNull(vm.state.value.error)
        val saved = graph.dataStore.readWorldBook(vm.state.value.current!!.savedArtifactId)
        assertEquals(result.lore.map { it.content }, saved.entries.map { it.content })
        assertEquals(result.lore.map { it.keys }, saved.entries.map { it.keys })
        screenshot("faction-library-saved")
        report("faction-library-save", buildJsonObject { put("saved_to_library", true); put("id", saved.id); put("entry_count", saved.entries.size) })
    }

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
        tapScrolled(activity.getString(R.string.crs_multi_worldbooks))
        toggleBook(source.name)
        tap(activity.getString(R.string.cast_apply))
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

    private fun recordingLiveProvider(http1: Boolean = true, label: String = "faction"): ProviderRegistry {
        val delegate = if (!http1) OpenAiCompatibleAdapter() else OpenAiCompatibleAdapter(client = okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build())
        var round = 0
        val adapter = object : ProviderAdapter by delegate {
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = kotlinx.coroutines.flow.flow {
                val currentRound = ++round
                delegate.streamGenerate(config, request.copy(metadata = JsonObject(request.metadata +
                    ("capture_response_diagnostics" to JsonPrimitive(true))))).collect { chunk ->
                    if (chunk is GenerateChunk.Completed) report("$label-native-round-$currentRound", buildJsonObject {
                        put("finish_reason", chunk.finishReason.orEmpty()); put("text", chunk.text)
                        put("reasoning_chars", chunk.reasoning.length)
                        put("tool_calls", chunk.toolCalls ?: JsonArray(emptyList()))
                        put("response_diagnostics", chunk.providerDiagnostics ?: JsonObject(emptyMap()))
                    })
                    emit(chunk)
                }
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
        instrumentation.uiAutomation.waitForIdle(100, 1000)
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        root?.refresh()
        collect(root)
        return result
    }

    private suspend fun tap(label: String) = click { it.text?.toString() == label }
    private suspend fun tapScrolled(label: String) {
        scrollTo(label)
        tap(label)
    }

    private suspend fun scrollTo(label: String) {
        waitUntil { nodes().any { !it.text.isNullOrBlank() } }
        repeat(35) {
            if (nodes().any { it.text?.toString() == label && it.isVisibleToUser }) return
            val scroll = nodes().filter { it.isScrollable }.firstOrNull()
            if (scroll == null) {
                val shot = instrumentation.uiAutomation.takeScreenshot()
                val x = shot.width * 0.65f
                val from = shot.height * 0.78f
                val to = shot.height * 0.25f
                shot.recycle()
                val down = android.os.SystemClock.uptimeMillis()
                for (step in 0..12) {
                    val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 12 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x,
                        from + (to - from) * step / 12, 0).also {
                        it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
                    }
                    delay(25)
                }
            } else if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                delay(500)
                if (nodes().any { it.text?.toString() == label && it.isVisibleToUser }) return
                repeat(20) {
                    val back = nodes().firstOrNull { it.isScrollable }
                    if (back?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return@repeat
                    delay(150)
                }
            }
            delay(350)
        }
        error("Cannot find $label")
    }

    private suspend fun toggleBook(name: String) {
        scrollTo(name)
        val title = nodes().first { it.text?.toString() == name }
        val bounds = android.graphics.Rect().also { title.getBoundsInScreen(it) }
        val x = bounds.left - 24 * context.resources.displayMetrics.density
        val y = bounds.centerY().toFloat()
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
        delay(400)
    }
    private suspend fun tapDescription(label: String) = click { it.contentDescription?.toString() == label }
    private suspend fun click(matches: (AccessibilityNodeInfo) -> Boolean) {
        fun clickable(): AccessibilityNodeInfo? = nodes().filter(matches).firstNotNullOfOrNull { candidate ->
            var node = candidate
            while (!node.isClickable && node.parent != null) node = node.parent
            node.takeIf { it.isClickable && it.isEnabled && it.isVisibleToUser }
        }
        waitUntil { clickable() != null }
        val node = requireNotNull(clickable())
        assertTrue("Button is disabled: ${node.text}", node.isEnabled)
        val target = nodes().first { matches(it) && it.isVisibleToUser }
        val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
        touch(bounds.exactCenterX(), bounds.exactCenterY())
        delay(400)
    }

    private fun touch(x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
            }
        }
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
