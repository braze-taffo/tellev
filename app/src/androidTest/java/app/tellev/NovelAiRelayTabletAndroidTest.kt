package app.tellev

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.storage.PngCardParser
import app.tellev.feature.settings.SettingsViewModel
import app.tellev.feature.settings.imageGenDetailsItems
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** Real tablet UI + loopback HTTP fixture; never contacts a paid image provider. */
class NovelAiRelayTabletAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun configureRelayThroughUiAndGenerateOfficialFormat(): Unit = runBlocking {
        check(context.packageName.endsWith(".mvuvalidation"))
        val graph = TellevGraph.create(context)
        graph.dataStore.bootstrap()
        val ids = listOf("provider-novelai-image-apikey", NovelAiImageSettings.RELAY_TOKEN_SECRET_ID, "provider-novelai-image-settings")
        val saved = ids.associateWith { graph.secretStore.readSecret(it) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val models = ViewModelStore()
        val list = LazyListState(10)
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val png = PngCardParser.createMinimalPng()
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { stream -> stream.putNextEntry(ZipEntry("image.png")); stream.write(png); stream.closeEntry() }
        }.toByteArray()
        val server = ServerSocket(0)
        val serverFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = thread(name = "novelai-relay-fixture", isDaemon = true) {
            try { repeat(3) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val buffer = ByteArrayOutputStream()
                        while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; if (next != 13) buffer.write(next) }
                        return buffer.toString("UTF-8")
                    }
                    val first = line()
                    val headers = mutableMapOf<String, String>()
                    while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
                    val size = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(size)
                    var read = 0
                    while (read < size) { val n = input.read(body, read, size - read); check(n > 0); read += n }
                    val payload = if (size > 0) Json.parseToJsonElement(body.decodeToString()).jsonObject else JsonObject(emptyMap())
                    requests += buildJsonObject {
                        put("request", first); put("relay_key_only", headers["authorization"] == "Bearer tablet-relay-fixture")
                        put("body", payload)
                    }
                    val status = first.startsWith("GET ")
                    val bytes = if (status) "{\"active\":true,\"tier\":3}".toByteArray() else zip
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: ${if (status) "application/json" else "application/zip"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                }
            } } catch (error: Throwable) { if (!server.isClosed) serverFailure.set(error) }
        }
        try {
            graph.secretStore.putSecret("provider-novelai-image-apikey", "tablet-official-fixture")
            ProviderConfigPersistence.saveNovelAiImageSettings(graph.secretStore, NovelAiImageSettings(seed = 4321, upscaleRatio = 2.0))
            val vm = withContext(Dispatchers.Main) {
                SettingsViewModel(graph.dataStore, graph.providerRegistry, graph.secretStore, graph.appPreferences,
                    graph.themeModeFlow, graph.themeAccentFlow, graph.chatBubbleAlphaFlow, graph.chatFontSizeSpFlow).also { vm ->
                    models.put("relay-settings", vm)
                    activity.setContent {
                        val state = vm.uiState.collectAsState().value
                        TellevTheme {
                            LazyColumn(state = list, contentPadding = PaddingValues(16.dp)) {
                                imageGenDetailsItems(state, vm, {}, {}, {}, false, {})
                            }
                        }
                    }
                }
            }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.novelAiToken == "tablet-official-fixture" }
            click(context.getString(R.string.setimg_novelai_relay))
            waitUntil { vm.uiState.value.novelAiSettings.useRelay }
            enter(context.getString(R.string.setimg_novelai_relay_url), "http://127.0.0.1:${server.localPort}/proxy")
            enter(context.getString(R.string.setimg_novelai_relay_key), "tablet-relay-fixture")
            assertEquals("tablet-relay-fixture", vm.uiState.value.novelAiRelayToken)
            screenshot("relay-settings")
            click(context.getString(R.string.setimg_save))
            waitUntil { !vm.uiState.value.isLoading && ProviderConfigPersistence.loadNovelAiImageSettings(graph.secretStore).useRelay }
            assertNull(vm.uiState.value.error)
            assertEquals("tablet-official-fixture", graph.secretStore.readSecret("provider-novelai-image-apikey"))
            click(context.getString(R.string.setimg_test_connection))
            waitUntil { !vm.uiState.value.isTestingNovelAi && vm.uiState.value.novelAiStatus != null }
            assertTrue(vm.uiState.value.novelAiStatus!!.message, vm.uiState.value.novelAiStatus!!.available)
            screenshot("relay-check")
            val config = ProviderConfigPersistence.loadProviderConfig(graph.secretStore, ProviderCatalog.NOVELAI_IMAGE)
            val request = GenerateRequest(
                prompt = PromptBuildResult(listOf(PromptMessage(role = MessageRole.User, content = "snowy courtyard")), emptyList(), null,
                    ProviderCatalog.NOVELAI_IMAGE, PromptDiagnostics(emptyList())),
                preset = GenerationPreset(id = "fixture", name = "fixture", providerType = ProviderCatalog.NOVELAI_IMAGE), stream = false)
            val result = withTimeout(15_000) { NovelAiImageAdapter().streamGenerate(config, request).toList().single() }
            assertTrue(result.toString(), result is GenerateChunk.Completed)
            val image = Base64.getDecoder().decode((result as GenerateChunk.Completed).text)
            assertArrayEquals(png, image)
            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
            assertNotNull(bitmap); bitmap.recycle()
            worker.join(2_000)
            assertNull(serverFailure.get())
            assertEquals(3, requests.size)
            assertTrue(requests.all { it["relay_key_only"]!!.jsonPrimitive.boolean })
            assertTrue(requests[0]["request"]!!.jsonPrimitive.content.startsWith("GET /proxy/user/subscription"))
            assertTrue(requests[1]["request"]!!.jsonPrimitive.content.startsWith("POST /proxy/ai/generate-image"))
            assertEquals("generate", requests[1]["body"]!!.jsonObject["action"]!!.jsonPrimitive.content)
            assertNotNull(requests[1]["body"]!!.jsonObject["parameters"]!!.jsonObject["v4_prompt"])
            assertTrue(requests[2]["request"]!!.jsonPrimitive.content.startsWith("POST /proxy/ai/upscale"))
            withContext(Dispatchers.Main) { list.scrollToItem(10) }
            click(context.getString(R.string.setimg_novelai_official))
            waitUntil { !vm.uiState.value.novelAiSettings.useRelay }
            assertEquals("tablet-official-fixture", vm.uiState.value.novelAiToken)
            screenshot("official-restored")
            context.cacheDir.resolve("novelai-relay-evidence").also { it.mkdirs() }.resolve("report.json").writeText(buildJsonObject {
                put("ui_source_switch", true); put("ui_address_key_save", true); put("official_key_preserved", true)
                put("http_requests", JsonArray(requests.toList())); put("png_decoded_on_android", true)
                put("actual_third_party_provider_tested", false)
            }.toString())
        } finally {
            server.close()
            worker.join(1_000)
            for ((id, value) in saved) { if (value == null) graph.secretStore.deleteSecret(id) else graph.secretStore.putSecret(id, value) }
            withContext(Dispatchers.Main) { models.clear(); activity.finish() }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; result += node; for (i in 0 until node.childCount) visit(node.getChild(i)) }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private suspend fun waitUntil(block: suspend () -> Boolean) = withTimeout(20_000) { while (!block()) delay(100); delay(200) }
    private suspend fun find(label: String): AccessibilityNodeInfo {
        repeat(15) {
            nodes().firstOrNull { it.text?.toString() == label || it.text?.toString()?.contains(label) == true }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(300)
        }
        error("UI label missing: $label; visible=${nodes().mapNotNull { it.text }}")
    }
    private suspend fun click(label: String) {
        var node = find(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(400)
    }
    private suspend fun enter(label: String, value: String) {
        var node = find(label)
        while (node.className?.toString() != "android.widget.EditText" && node.parent != null) node = node.parent
        assertEquals("android.widget.EditText", node.className.toString())
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        delay(400)
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            context.cacheDir.resolve("novelai-relay-evidence").also { it.mkdirs() }.resolve("$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
