package app.tellev.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.MainActivity
import app.tellev.R
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.storage.AppPreferences
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual settings, NavHost, text editor and session drawer. */
class InterfaceStyleAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: Activity
    private val cardId = "interface-style-fixture"
    private val cardName = "Interface Style Fixture"
    private val greeting = "Interface style greeting."
    private val firstId = "interface-style-first"
    private val secondId = "interface-style-second"
    private lateinit var store: FileStDataStore

    private fun launch(style: String = "Classic", reset: Boolean = true) {
        check(context.packageName.endsWith(".mvuvalidation"))
        if (reset) {
            store = FileStDataStore(StDirectoryLayout.fromRoot(context.filesDir.toPath().resolve("st-data")))
            runBlocking(Dispatchers.IO) {
                store.bootstrap()
                store.listChatSessionSummaries(characterId = cardId).forEach { store.deleteChatSession(it.id) }
                store.saveCharacter(CharacterCard(cardId, cardName, firstMessage = greeting))
                for ((id, title, text, time) in listOf(
                    listOf(firstId, "First style session", greeting, "1000"),
                    listOf(secondId, "Second style session", "Second session greeting.", "2000"),
                )) {
                    store.saveChatSession(ChatSession(id, title, cardId, null,
                        listOf(ChatMessage("$id-greeting", MessageRole.Character, cardName, text, time.toLong()))))
                }
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            context.getSharedPreferences("tellev_prefs", 0).edit()
                .putBoolean("auto_update_check", false).putBoolean("onboarding_guide_shown", true)
                .putBoolean("qq_group_notice_handled", true).putBoolean("preset_limits_1_5_1_notice_handled", true)
                .putString("update_guide_shown_version", version).putString("language_tag", "en")
                .putString("interface_style", style).putString("theme_mode", "Light").commit()
        }
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitUntil { matches(cardName).isNotEmpty() }
    }

    @After
    fun close() {
        if (::activity.isInitialized && !activity.isFinishing) {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
        context.getSharedPreferences("tellev_prefs", 0).edit()
            .putString("interface_style", "Classic").putString("theme_mode", "System").commit()
    }

    @Test
    fun settingsSwitchRetainsDraftAndConversationAndPersistsAfterRestart() {
        launch()
        tap(cardName)
        waitUntil { nodes().any { it.isEditable } }
        val draft = "An unsent draft survives interface changes."
        assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft) }))
        waitUntil { matches(draft).isNotEmpty() }
        for (style in listOf("Dsh", "Classic", "Dsh")) {
            tap(R.string.nav_tab_settings)
            tap(R.string.ui_settings_appearance)
            tap(if (style == "Dsh") R.string.interface_style_dsh else R.string.interface_style_classic)
            waitUntil { AppPreferences(context).interfaceStyleName == style }
            tap(R.string.nav_tab_chat)
            waitUntil { matches(draft).isNotEmpty() && matches(R.string.chat_back).isNotEmpty() }
            assertTrue(runBlocking(Dispatchers.IO) { store.listChatSessionSummaries(characterId = cardId).size == 2 })
        }
        screenshot("interface-dsh-chat.png")
        tap(R.string.nav_tab_settings)
        tap(R.string.ui_settings_appearance)
        tap(R.string.setapp_theme_dark)
        waitUntil { AppPreferences(context).themeModeName == "Dark" }
        screenshot("interface-dsh-settings-dark.png")
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        launch(reset = false)
        assertEquals("Dsh", AppPreferences(context).interfaceStyleName)
        assertEquals("Dark", AppPreferences(context).themeModeName)
        tap(cardName)
        waitUntil { matches(R.string.chat_back).isNotEmpty() }
        tap(R.string.chat_sessions)
        waitUntil { matches(R.string.chat_drawer_new_session).isNotEmpty() }
        screenshot("interface-dsh-drawer-dark.png")
    }

    @Test
    fun dshDrawerBackSwitchAndDeleteUseExistingSessionLifecycle() {
        launch("Dsh")
        tap(cardName)
        waitUntil { matches(R.string.chat_back).isNotEmpty() }
        tap(R.string.chat_sessions)
        waitUntil { matches(R.string.chat_drawer_new_session).isNotEmpty() }
        shell("input keyevent 4")
        waitUntil { matches(R.string.chat_drawer_new_session).isEmpty() && matches(R.string.chat_back).isNotEmpty() }
        assertFalse(activity.isFinishing)
        tap(R.string.chat_sessions)
        tap("First style session")
        waitUntil { matches(greeting).isNotEmpty() }
        tap(R.string.chat_sessions)
        tap("Second style session")
        waitUntil { matches("Second session greeting.").isNotEmpty() }
        tap(R.string.chat_sessions)
        waitUntil { matches("First style session").isNotEmpty() }
        val firstRow = matches("First style session").first()
        val rowRect = Rect().also(firstRow::getBoundsInScreen)
        val deletes = matches(R.string.chat_delete_session)
        val delete = deletes.minBy { val r = Rect(); it.getBoundsInScreen(r); kotlin.math.abs(r.centerY() - rowRect.centerY()) }
        tapNode(delete)
        waitUntil { matches(R.string.chat_delete).isNotEmpty() }
        tap(R.string.chat_cancel)
        assertTrue(runBlocking(Dispatchers.IO) { store.chatSessionExists(firstId) })
        tap(R.string.chat_sessions)
        waitUntil { matches("First style session").isNotEmpty() }
        val row = matches("First style session").first()
        row.getBoundsInScreen(rowRect)
        tapNode(matches(R.string.chat_delete_session).minBy {
            val r = Rect(); it.getBoundsInScreen(r); kotlin.math.abs(r.centerY() - rowRect.centerY())
        })
        tap(R.string.chat_delete)
        waitUntil { runBlocking(Dispatchers.IO) { !store.chatSessionExists(firstId) } }
        assertTrue(runBlocking(Dispatchers.IO) { store.chatSessionExists(secondId) })
        assertTrue(matches("Second session greeting.").isNotEmpty())
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(context.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun matches(id: Int) = matches(activity.getString(id))
    private fun matches(label: String) = nodes().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun tap(id: Int) = tap(activity.getString(id))
    private fun tap(label: String) {
        waitUntil { matches(label).isNotEmpty() }
        tapNode(matches(label).maxBy { val r = Rect(); it.getBoundsInScreen(r); r.bottom })
    }
    private fun tapNode(node: AccessibilityNodeInfo) {
        // Activate the observed accessibility target so drawer animation does
        // not move a coordinate between inspection and the physical tap.
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val r = Rect().also(node::getBoundsInScreen)
            shell("input tap ${r.centerX()} ${r.centerY()}")
        }
        instrumentation.waitForIdleSync()
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        screenshot("interface-failure.png")
        assertTrue("Expected UI state did not appear: " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "), predicate())
    }
}
