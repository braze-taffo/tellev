package app.tellev

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.tellev.core.i18n.AppLocale
import app.tellev.core.i18n.UiStrings
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.security.AndroidKeystoreSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real MainActivity, real SAF picker and real HTTP adapters; never calls a ViewModel. */
class CreationComposerAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var server: LoopbackFixture
    private var prefsSnapshot: Map<String, Any?>? = null
    private var secretsSnapshot: Map<String, Any?>? = null
    private var automationFlags: Int? = null
    private var baselineDrafts = emptySet<String>()
    private var baselineAttachments = emptyMap<File, Set<String>>()
    private val documents = mutableListOf<FixtureDocument>()
    private val prefix = "tellev-creation-fixture-${UUID.randomUUID().toString().take(12)}"

    @Before
    fun launchIsolatedMainActivity() {
        // The production package is never a permissible instrumentation target.
        require(context.packageName != "app.tellev" &&
            context.packageName in setOf("app.tellev.debug", "app.tellev.mvuvalidation")) {
            "Run creation-composer tests only against the isolated debug/validation package"
        }
        finishTargetActivities()
        baselineDrafts = childNames(creationRoot())
        baselineAttachments = listOf("user/images", "user/files").associate { relative ->
            File(context.filesDir, "st-data/$relative").let { it to childNames(it) }
        }
        prefsSnapshot = snapshot(context.getSharedPreferences("tellev_prefs", Context.MODE_PRIVATE))
        // Preserve ciphertext verbatim, including absent/corrupt entries and the exact custom list.
        // These values stay in memory and are never included in an assertion or diagnostic.
        secretsSnapshot = snapshot(context.getSharedPreferences("tellev_secrets", Context.MODE_PRIVATE))
        val info = instrumentation.uiAutomation.serviceInfo
        automationFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = info
        server = LoopbackFixture()
        try {
            assertTrue("Could not isolate provider preferences", context
                .getSharedPreferences("tellev_secrets", Context.MODE_PRIVATE).edit().clear().commit())
            runBlocking(Dispatchers.IO) {
                val secrets = AndroidKeystoreSecretStore(context)
                val advanced = OpenAiCompatibilitySettings(supportsTools = true, supportsVision = true)
                ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(
                    CustomProviderConfig(CONFIG_ID, PROVIDER_LABEL, server.baseUrl,
                        apiKey = "", model = MODEL_A, advanced = advanced),
                    CustomProviderConfig("creation-fixture-inactive", "Inactive fixture provider",
                        "${server.baseUrl}/inactive", apiKey = "", model = INACTIVE_MODEL,
                        advanced = advanced),
                ))
                secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID,
                    ProviderConfigPersistence.selectedIdFor(CONFIG_ID))
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            assertTrue("Could not configure deterministic startup", context
                .getSharedPreferences("tellev_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean("auto_update_check", false)
                .putBoolean("onboarding_guide_shown", true)
                .putBoolean("qq_group_notice_handled", true)
                .putBoolean("preset_limits_1_5_1_notice_handled", true)
                .putString("update_guide_shown_version", version)
                .putString("language_tag", "en")
                .commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            instrumentation.waitForIdleSync()
            tap(R.string.nav_tab_workshop)
            // Workshop merge: the "new character" card now opens this screen directly.
            tap(R.string.crs_new_character)
            waitUntil("creation composer") { composerNode() != null && matches(R.string.dsh_attach_file).isNotEmpty() }
            waitUntil("new fixture draft") { fixtureDraftFiles().isNotEmpty() }
            waitUntil("initial configured model") { modelLabelVisible(MODEL_A) }
        } catch (error: Throwable) {
            restoreState()
            throw error
        }
    }

    @After
    fun restoreIsolatedState() {
        restoreState()
    }

    @Test
    fun paperclipTextFileIsSentToSelectedModelAndCleared() {
        selectModelB()
        setComposerText(USER_DRAFT)
        val document = textDocument()
        pickDocument(document)
        waitUntil("pending text attachment") { matches(document.name).isNotEmpty() && removeCount() == 1 }
        tap(R.string.chat_send_message)
        val request = awaitInitialCreation()
        assertTrue("Creation did not use the selected model", request.model == MODEL_B)
        assertTrue("Picked text was missing from provider messages", request.messages.toString().contains(TEXT_MARKER))
        assertTrue("Creation did not advertise its native tool", request.hasNativeCreationTool)
        awaitAcceptedTurn(USER_DRAFT)
        assertTrue("Accepted send did not clear the composer", composerText().isNullOrEmpty() && removeCount() == 0)
        assertTrue("Sending a creation turn invoked optimization", server.optimizations().isEmpty())
        assertTrue("Creation queried an inactive provider", server.inactiveCatalogRequests.get() == 0)
    }

    @Test
    fun paperclipImageIsSentAsVisionPayloadToSelectedModel() {
        selectModelB()
        val draft = "Create a lighthouse keeper from this image. FIXTURE_IMAGE_TURN"
        setComposerText(draft)
        val document = imageDocument()
        pickDocument(document)
        waitUntil("pending image thumbnail") { matches(document.name).isNotEmpty() && removeCount() == 1 }
        tap(R.string.chat_send_message)
        val request = awaitInitialCreation()
        assertTrue("Image creation did not use model B", request.model == MODEL_B)
        assertTrue("Image was not encoded in the actual provider request", request.hasImage)
        assertTrue("Image turn lost the typed draft", request.messages.toString().contains("FIXTURE_IMAGE_TURN"))
        awaitAcceptedTurn(draft)
        assertTrue("Accepted image send left a pending attachment", removeCount() == 0)
    }

    @Test
    fun removalAndUnsupportedFilePreserveDraftWithoutSending() {
        selectModelB()
        setComposerText(USER_DRAFT)
        for (document in listOf(textDocument(), imageDocument())) {
            pickDocument(document)
            waitUntil("removable attachment") { removeCount() == 1 }
            tap(R.string.dsh_attach_remove)
            waitUntil("removed attachment") { removeCount() == 0 && matches(document.name).isEmpty() }
        }
        val unsupported = createDocument("bin", "application/octet-stream", byteArrayOf(0, 1, 2, 3))
        pickDocument(unsupported)
        waitUntil("unsupported-file error") { containsText(activity.getString(R.string.crs_attachment_unsupported)) }
        assertTrue("Unsupported file changed the typed draft", composerText() == USER_DRAFT)
        assertTrue("Unsupported file became sendable", removeCount() == 0)
        assertTrue("Picking/removing files sent a request", server.posts.isEmpty())
        tap(R.string.chat_send_message)
        val request = awaitInitialCreation()
        assertTrue("Removed text leaked into generation", !request.messages.toString().contains(TEXT_MARKER))
        assertTrue("Removed image leaked into generation", !request.hasImage)
        assertTrue("Unsupported file leaked into generation", !request.messages.toString().contains(unsupported.name))
        awaitAcceptedTurn(USER_DRAFT)
    }

    @Test
    fun selectedModelDraftAndPendingFileSurviveActivityRecreation() {
        selectModelB()
        setComposerText(USER_DRAFT)
        val document = textDocument()
        pickDocument(document)
        waitUntil("pending file before recreation") { removeCount() == 1 }
        val previous = activity
        instrumentation.runOnMainSync { previous.recreate() }
        waitUntil("recreated MainActivity") {
            resumedMainActivity()?.takeIf { it !== previous }?.let { activity = it; true } == true
        }
        waitUntil("recreated composer state") {
            modelLabelVisible(MODEL_B) && composerText() == USER_DRAFT &&
                matches(document.name).isNotEmpty() && removeCount() == 1
        }
        assertPersistedModelB()
        tap(R.string.chat_send_message)
        val request = awaitInitialCreation()
        assertTrue("Recreation lost the model selection", request.model == MODEL_B)
        assertTrue("Recreation lost the actual attachment content", request.messages.toString().contains(TEXT_MARKER))
        awaitAcceptedTurn(USER_DRAFT)
    }

    @Test
    fun optimizerStopAndCancelPreserveOriginalDraftAndCancelHttp() {
        selectModelB()
        setComposerText(OPTIMIZER_DRAFT)
        val draftBefore = fixtureDraftBytes()
        server.holdNextOptimization.set(true)
        tap(R.string.dsh_optimize_prompt)
        tap(R.string.chat_optimize_start)
        waitUntil("optimizer request") { server.optimizations().size == 1 }
        val request = server.optimizations().single()
        assertTrue("Optimizer did not use model B", request.model == MODEL_B)
        assertTrue("Optimizer did not receive the input draft", request.messages.toString().contains(OPTIMIZER_MARKER))
        tap(R.string.chat_optimize_stop)
        waitUntil("cancelled loopback HTTP request") { server.cancelledOptimizations.get() == 1 }
        tap(R.string.chat_optimize_cancel)
        waitUntil("original composer after cancel") { composerText() == OPTIMIZER_DRAFT && matches(R.string.chat_optimize_title).isEmpty() }
        assertTrue("Cancellation changed the creation session", fixtureDraftBytes().contentEquals(draftBefore))
        assertTrue("Optimization cancellation generated a creation turn", server.creations().isEmpty())
        // A stale optimizing flag would leave this new real UI run unavailable.
        tap(R.string.dsh_optimize_prompt)
        tap(R.string.chat_optimize_start)
        waitUntil("optimizer available after cancellation") { matches(R.string.chat_optimize_apply).isNotEmpty() }
        tap(R.string.chat_optimize_cancel)
        waitUntil("discarded optimizer result") { composerText() == OPTIMIZER_DRAFT && matches(R.string.chat_optimize_title).isEmpty() }
        assertTrue("Discarding the result changed the session", fixtureDraftBytes().contentEquals(draftBefore))
        assertTrue("Discarding the result generated a creation turn", server.creations().isEmpty())
    }

    @Test
    fun optimizerApplyOnlyReplacesInputUntilUserExplicitlySends() {
        selectModelB()
        setComposerText(OPTIMIZER_DRAFT)
        val draftBefore = fixtureDraftBytes()
        tap(R.string.dsh_optimize_prompt)
        tap(R.string.chat_optimize_start)
        waitUntil("optimizer preview/apply") { matches(R.string.chat_optimize_apply).isNotEmpty() }
        assertTrue("Optimization used the wrong model", server.optimizations().single().model == MODEL_B)
        assertTrue("Optimization itself generated a creation turn", server.creations().isEmpty())
        tap(R.string.chat_optimize_apply)
        waitUntil("optimized input draft") { composerText() == OPTIMIZED_DRAFT && matches(R.string.chat_optimize_title).isEmpty() }
        assertTrue("Apply wrote a creation turn", fixtureDraftBytes().contentEquals(draftBefore))
        assertTrue("Apply removed the empty-conversation state", matches(R.string.dsh_creation_empty_title).isNotEmpty())
        val deadline = SystemClock.uptimeMillis() + 1_000
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue("Apply automatically sent the draft", server.creations().isEmpty())
            SystemClock.sleep(100)
        }
        tap(R.string.chat_send_message)
        val request = awaitInitialCreation()
        assertTrue("Explicit send after optimization lost model B", request.model == MODEL_B)
        assertTrue("Explicit send did not use the applied draft", request.messages.toString().contains(OPTIMIZED_DRAFT))
        awaitAcceptedTurn(OPTIMIZED_DRAFT)
    }

    private fun selectModelB() {
        hideKeyboard()
        tap(R.string.model_picker_title)
        waitUntil("model list or root menu") {
            matches(R.string.model_picker_search).isNotEmpty() || matches(R.string.model_picker_title).isNotEmpty()
        }
        if (matches(R.string.model_picker_search).isEmpty()) tap(R.string.model_picker_title)
        waitUntil("active endpoint model catalog") { matches(MODEL_B).isNotEmpty() && server.activeCatalogRequests.get() > 0 }
        assertTrue("Creation exposed an invalid effort pane", matches(R.string.dsh_model_levels).isNotEmpty())
        assertTrue("Creation exposed an inactive model", matches(INACTIVE_MODEL).isEmpty())
        assertTrue("Creation requested an inactive catalog", server.inactiveCatalogRequests.get() == 0)
        tap(MODEL_B)
        waitUntil("saved model selection and closed menu") {
            modelLabelVisible(MODEL_B) && matches(R.string.model_picker_search).isEmpty()
        }
        assertPersistedModelB()
    }

    private fun assertPersistedModelB() {
        val saved = runBlocking(Dispatchers.IO) {
            ProviderConfigPersistence.listCustomConfigs(AndroidKeystoreSecretStore(context))
                .firstOrNull { it.id == CONFIG_ID }?.model
        }
        assertTrue("Model callback closed before persisting model B", saved == MODEL_B)
    }

    private fun awaitInitialCreation(): CapturedPost {
        waitUntil("creation provider request") { server.creations().any { !it.isToolContinuation } }
        return server.creations().first { !it.isToolContinuation }
    }

    private fun awaitAcceptedTurn(draft: String) {
        waitUntil("accepted user turn and final native-tool reply") {
            matches(draft).isNotEmpty() && matches(CREATION_REPLY).isNotEmpty() &&
                matches(R.string.chat_stop_generation).isEmpty()
        }
        assertTrue("Native tool loop did not complete exactly once", server.creations().size == 2)
        assertTrue("A tool-loop round used an old model", server.creations().all { it.model == MODEL_B })
        assertTrue("Native tool result was not replayed", server.creations().last().isToolContinuation)
        assertTrue("Native draft write was not visible", matches(FIXTURE_CHARACTER).isNotEmpty())
    }

    private fun textDocument() = createDocument("txt", "text/plain",
        "Reference notes for the lighthouse keeper. $TEXT_MARKER\n".toByteArray(Charsets.UTF_8))

    private fun imageDocument(): FixtureDocument {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(40, 170, 110))
            val bytes = ByteArrayOutputStream().use { out ->
                assertTrue("Could not encode fixture image", bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
            createDocument("png", "image/png", bytes)
        } finally {
            bitmap.recycle()
        }
    }

    private data class FixtureDocument(val name: String, val staging: File, val downloadPath: String)

    private fun createDocument(extension: String, mime: String, bytes: ByteArray): FixtureDocument {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), prefix)
        assertTrue("Could not create fixture staging directory", directory.isDirectory || directory.mkdirs())
        val name = "$prefix-${documents.size}.$extension"
        val staging = File(directory, name)
        val destination = "/sdcard/Download/$name"
        require(Regex("[A-Za-z0-9/._-]+").matches(staging.absolutePath))
        require(shell("ls $destination").trim() != destination) { "Fixture filename already exists" }
        staging.writeBytes(bytes)
        val document = FixtureDocument(name, staging, destination)
        documents += document
        shell("mkdir -p /sdcard/Download")
        shell("cp -n ${staging.absolutePath} $destination")
        assertTrue("Could not stage the unique Download fixture", shell("ls $destination").trim() == destination)
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(destination), arrayOf(mime)) { _, _ -> scanned.countDown() }
        assertTrue("Fixture media scan did not finish", scanned.await(20, TimeUnit.SECONDS))
        return document
    }

    private fun pickDocument(document: FixtureDocument) {
        hideKeyboard()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT), null, false)
        try {
            tap(R.string.dsh_attach_file)
            waitUntil("real ACTION_OPEN_DOCUMENT picker") {
                monitor.hits > 0 && activePackage()?.contains("documentsui", ignoreCase = true) == true
            }
            if (matches(document.name).isEmpty()) {
                val drawer = nodes().firstOrNull {
                    it.viewIdResourceName?.substringAfterLast('/') in setOf("drawer_anchor", "home") ||
                        it.contentDescription?.toString() in setOf("Show roots", "Show navigation drawer", "Open navigation drawer", "显示根目录", "显示位置", "显示导航栏")
                }
                requireNotNull(drawer) { "DocumentsUI root drawer was not accessible" }
                tapNode(drawer)
                waitUntil("Downloads picker root") { nodes().any { node ->
                    labels(node).any { it.equals("Downloads", true) || it == "下载" }
                } }
                tapNode(nodes().first { node -> labels(node).any { it.equals("Downloads", true) || it == "下载" } })
            }
            waitUntil("unique fixture document in picker") { matches(document.name).isNotEmpty() }
            tap(document.name)
            waitUntil("picker selection or open confirmation") {
                activePackage() == context.packageName || nodes().any { node ->
                    node.isEnabled && labels(node).any { it in setOf("Open", "OPEN", "Select", "SELECT", "打开", "选择") }
                }
            }
            if (activePackage() != context.packageName) {
                tapNode(nodes().first { node -> node.isEnabled && labels(node).any {
                    it in setOf("Open", "OPEN", "Select", "SELECT", "打开", "选择")
                } })
            }
            waitUntil("returned creation composer") { activePackage() == context.packageName && composerNode() != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        // Compose virtual descendants often cannot be found by provider text lookup.
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result
    }

    private fun labels(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).flatMap { it.lines() }

    private fun matches(id: Int) = matches(activity.getString(id))
    private fun matches(expected: String) = nodes().filter { expected in labels(it) }
    private fun containsText(expected: String) = nodes().any { labels(it).any { label -> expected in label } }
    private fun activePackage() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun removeCount() = matches(R.string.dsh_attach_remove).size
    private fun modelLabelVisible(model: String) = nodes().any { node ->
        labels(node).any { label -> model in label && (PROVIDER_LABEL in label || "custom:$CONFIG_ID" in label) }
    }

    private fun composerNode(): AccessibilityNodeInfo? {
        if (!::activity.isInitialized) return null
        val editable = nodes().filter { node -> node.isEditable && node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        } }
        return editable.firstOrNull { activity.getString(R.string.crs_input_label) in labels(it) }
            ?: editable.maxByOrNull { bounds(it).bottom }
    }

    private fun composerText() = composerNode()?.text?.toString().orEmpty()

    private fun setComposerText(text: String) {
        waitUntil("editable creation draft") { composerNode() != null }
        tapNode(requireNotNull(composerNode()))
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        assertTrue("Creation input rejected accessibility text entry",
            requireNotNull(composerNode()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        waitUntil("typed creation draft") { composerText() == text }
        hideKeyboard()
    }

    private fun tap(id: Int) = tap(activity.getString(id))
    private fun tap(expected: String) {
        waitUntil("fixture UI control") { matches(expected).isNotEmpty() }
        tapNode(matches(expected).maxBy { bounds(it).bottom })
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun tapNode(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        assertTrue("Fixture UI control has no tappable bounds", !rect.isEmpty)
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        instrumentation.waitForIdleSync()
    }

    private fun hideKeyboard() {
        if (instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            shell("input keyevent 4")
            instrumentation.waitForIdleSync()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (::server.isInitialized) server.assertHealthy()
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $label", predicate())
    }

    private fun resumedMainActivity(): MainActivity? {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull { it.packageName == context.packageName }
        }
        return result
    }

    private fun finishTargetActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }.flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<MainActivity>().distinct().filter { it.packageName == context.packageName }
                .forEach(Activity::finish)
        }
        instrumentation.waitForIdleSync()
    }

    private fun creationRoot() = File(context.filesDir, "ai-creation")
    private fun childNames(directory: File) = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
    private fun fixtureDraftFiles() = creationRoot().listFiles()?.filter {
        it.isFile && it.name !in baselineDrafts && it.name.startsWith("creation_") && it.extension == "json"
    }.orEmpty()
    private fun fixtureDraftBytes(): ByteArray {
        waitUntil("persisted fixture draft") { fixtureDraftFiles().size == 1 }
        return fixtureDraftFiles().single().readBytes()
    }

    private fun snapshot(prefs: SharedPreferences): Map<String, Any?> = prefs.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }

    private fun restorePreferences(name: String, previous: Map<String, Any?>) {
        val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        previous.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> edit.remove(key)
                else -> error("Unsupported preference value type")
            }
        }
        assertTrue("Could not restore isolated app preferences", edit.commit())
    }

    private fun restoreState() {
        if (prefsSnapshot == null && secretsSnapshot == null) return
        try {
            finishTargetActivities()
        } finally {
            try {
                if (::server.isInitialized) server.close()
                for (document in documents) {
                    shell("rm -f ${document.downloadPath}")
                    MediaScannerConnection.scanFile(context, arrayOf(document.downloadPath), null, null)
                    document.staging.delete()
                }
                documents.firstOrNull()?.staging?.parentFile?.delete()
                creationRoot().listFiles()?.filter {
                    it.isFile && it.name !in baselineDrafts && it.name.startsWith("creation_")
                }?.forEach(File::delete)
                baselineAttachments.forEach { (directory, before) ->
                    directory.listFiles()?.filter { it.isFile && it.name !in before && it.name.startsWith("att-") }
                        ?.forEach(File::delete)
                }
            } finally {
                try {
                    secretsSnapshot?.let { restorePreferences("tellev_secrets", it) }
                } finally {
                    try {
                        prefsSnapshot?.let { restorePreferences("tellev_prefs", it) }
                        UiStrings.init(AppLocale.wrap(context).resources)
                    } finally {
                        automationFlags?.let { flags ->
                            val info = instrumentation.uiAutomation.serviceInfo
                            info.flags = flags
                            instrumentation.uiAutomation.serviceInfo = info
                        }
                        prefsSnapshot = null
                        secretsSnapshot = null
                        automationFlags = null
                    }
                }
            }
        }
    }

    private data class CapturedPost(
        val model: String,
        val messages: JsonArray,
        val isCreation: Boolean,
        val hasNativeCreationTool: Boolean,
    ) {
        val isToolContinuation get() = messages.any { message ->
            val objectValue = message as? JsonObject
            (objectValue?.get("role") as? JsonPrimitive)?.contentOrNull == "user" &&
                (objectValue["content"] as? JsonPrimitive)?.contentOrNull?.contains("<tool_result") == true
        }
        val hasImage get() = messages.any { message ->
            ((message as? JsonObject)?.get("content") as? JsonArray)?.any { part ->
                val image = (part as? JsonObject)?.get("image_url") as? JsonObject
                (image?.get("url") as? JsonPrimitive)?.contentOrNull?.startsWith("data:image/") == true
            } == true
        }
    }

    /** No headers are retained, and only test-generated model/messages live in memory. */
    private class LoopbackFixture : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        val posts = CopyOnWriteArrayList<CapturedPost>()
        val activeCatalogRequests = AtomicInteger()
        val inactiveCatalogRequests = AtomicInteger()
        val cancelledOptimizations = AtomicInteger()
        val holdNextOptimization = AtomicBoolean()
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<String?>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = Executors.newCachedThreadPool { task ->
            Thread(task, "creation-composer-fixture").apply { isDaemon = true }
        }
        private val acceptor = Thread({
            while (!closed.get()) {
                try {
                    val socket = listener.accept()
                    sockets += socket
                    workers.execute {
                        try {
                            socket.use(::serve)
                        } catch (_: Exception) {
                            if (!closed.get()) failure.compareAndSet(null, "Loopback fixture request failed")
                        } finally {
                            sockets -= socket
                        }
                    }
                } catch (_: Exception) {
                    if (!closed.get()) failure.compareAndSet(null, "Loopback fixture accept failed")
                }
            }
        }, "creation-composer-fixture-accept").apply { isDaemon = true; start() }

        fun creations() = posts.filter { it.isCreation }
        fun optimizations() = posts.filterNot { it.isCreation }
        fun assertHealthy() { assertTrue(failure.get() ?: "Loopback fixture failed", failure.get() == null) }

        private fun serve(socket: Socket) {
            socket.soTimeout = 25_000
            val input = BufferedInputStream(socket.getInputStream())
            val line = readLine(input).split(' ')
            require(line.size >= 2)
            var length = 0
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toInt()
                }
            }
            require(length in 0..2_000_000)
            val output = socket.getOutputStream()
            if (line[0] == "GET" && line[1].endsWith("/v1/models")) {
                val inactive = line[1].startsWith("/inactive/")
                if (inactive) inactiveCatalogRequests.incrementAndGet() else activeCatalogRequests.incrementAndGet()
                val models = if (inactive) listOf(INACTIVE_MODEL) else listOf(MODEL_A, MODEL_B, LONG_MODEL)
                val body = buildJsonObject {
                    put("object", "list")
                    put("data", buildJsonArray { models.forEach { model ->
                        add(buildJsonObject { put("id", model); put("object", "model") })
                    } })
                }.toString()
                respond(output, "application/json", body)
                return
            }
            require(line[0] == "POST" && line[1] == "/v1/chat/completions")
            val bytes = ByteArray(length)
            var cursor = 0
            while (cursor < length) {
                val count = input.read(bytes, cursor, length - cursor)
                require(count > 0)
                cursor += count
            }
            val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require((body["stream"] as? JsonPrimitive)?.contentOrNull == "true")
            val tools = body["tools"] as? JsonArray
            val native = tools?.any { tool ->
                val function = (tool as? JsonObject)?.get("function") as? JsonObject
                (function?.get("name") as? JsonPrimitive)?.contentOrNull == "creation_tool"
            } == true
            val captured = CapturedPost(
                (body["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                body["messages"] as? JsonArray ?: error("Missing fixture messages"),
                isCreation = native, hasNativeCreationTool = native,
            )
            require(captured.model in setOf(MODEL_A, MODEL_B))
            posts += captured
            if (!captured.isCreation && holdNextOptimization.getAndSet(false)) {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(event(buildJsonObject { put("content", "{\"optimized\":\"Fixture pending") }).toByteArray())
                output.flush()
                if (input.read() == -1) cancelledOptimizations.incrementAndGet()
                else failure.compareAndSet(null, "Optimizer did not cancel its connection")
                return
            }
            val events = when {
                !captured.isCreation -> {
                    val optimized = buildJsonObject { put("optimized", OPTIMIZED_DRAFT); put("notes", "Fixture rewrite.") }.toString()
                    listOf(event(buildJsonObject { put("content", optimized) }))
                }
                captured.isToolContinuation -> listOf(event(buildJsonObject { put("content", CREATION_REPLY) }))
                else -> {
                    val arguments = buildJsonObject {
                        put("name", "set_card_fields")
                        put("arguments", buildJsonObject {
                            put("name", FIXTURE_CHARACTER)
                            put("description", "A quiet lighthouse keeper.")
                            put("personality", "Patient and observant.")
                            put("firstMessage", "Welcome to the lighthouse.")
                        })
                    }.toString()
                    val midpoint = arguments.length / 2
                    listOf(event(buildJsonObject {
                        put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "fixture-call"); put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "creation_tool"); put("arguments", arguments.take(midpoint))
                            })
                        }) })
                    }), event(buildJsonObject {
                        put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", 0)
                            put("function", buildJsonObject { put("arguments", arguments.drop(midpoint)) })
                        }) })
                    }))
                }
            }
            val finish = if (captured.isCreation && !captured.isToolContinuation) "tool_calls" else "stop"
            respond(output, "text/event-stream", events.joinToString("") +
                event(buildJsonObject { }, finish) + "data: [DONE]\n\n")
        }

        private fun event(delta: JsonObject, finish: String? = null): String {
            val chunk = buildJsonObject {
                put("id", "fixture-stream"); put("object", "chat.completion.chunk")
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0); put("delta", delta)
                    put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
                }) })
            }
            return "data: $chunk\n\n"
        }

        private fun respond(output: OutputStream, type: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }

        private fun readLine(input: BufferedInputStream): String {
            val line = ByteArrayOutputStream()
            while (line.size() < 8_192) {
                val byte = input.read()
                require(byte >= 0)
                if (byte == 10) return line.toString("US-ASCII").removeSuffix("\r")
                line.write(byte)
            }
            error("Fixture HTTP line exceeded limit")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptor.join(1_000)
            workers.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val CONFIG_ID = "creation-composer-fixture"
        const val PROVIDER_LABEL = "Creation fixture provider"
        const val MODEL_A = "fixture-model-a"
        const val MODEL_B = "fixture-model-b"
        const val LONG_MODEL = "fixture-model-with-a-long-name-for-compact-model-menu-layout"
        const val INACTIVE_MODEL = "fixture-inactive-model-must-not-be-listed"
        const val TEXT_MARKER = "CREATION_COMPOSER_TEXT_FIXTURE_2026"
        const val USER_DRAFT = "Create a quiet lighthouse keeper. FIXTURE_USER_TURN"
        const val OPTIMIZER_MARKER = "CREATION_OPTIMIZER_SOURCE_2026"
        const val OPTIMIZER_DRAFT = "$OPTIMIZER_MARKER Write a clear draft for {{char}} and {{user}}."
        const val OPTIMIZED_DRAFT = "Optimized fixture draft for {{char}} and {{user}}."
        const val FIXTURE_CHARACTER = "Fixture Lighthouse Keeper"
        const val CREATION_REPLY = "Creation fixture accepted."
    }
}
