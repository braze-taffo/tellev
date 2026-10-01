package app.tellev.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.MainActivity
import app.tellev.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the real NavHost/BackHandler priority, not just the route policy. */
class TopLevelNavigationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity

    @Before
    fun launch() {
        val context = instrumentation.targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        context.getSharedPreferences("tellev_prefs", 0).edit()
            .putBoolean("auto_update_check", false)
            .putBoolean("onboarding_guide_shown", true)
            .putBoolean("qq_group_notice_handled", true)
            .putBoolean("preset_limits_1_5_1_notice_handled", true)
            .putString("update_guide_shown_version", version)
            .putString("language_tag", "en")
            .commit()
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        waitUntil { tabsVisible() }
    }

    @After
    fun close() {
        if (::activity.isInitialized && !activity.isFinishing) {
            instrumentation.runOnMainSync { activity.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun characterAndWorldLibrariesKeepAllTabs() {
        tap(R.string.nav_tab_characters)
        waitUntil { tabsVisible() && characterHeaderVisible() }
        tap(R.string.nav_tab_world)
        waitUntil { tabsVisible() }
        assertTrue(tabsVisible())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun libraryBackConfirmsBeforeNavigationAndRespectsNoAndYes() {
        tap(R.string.nav_tab_characters)
        waitUntil { characterHeaderVisible() }
        shell("input keyevent 4")
        waitUntil { matches(R.string.ui_exit_title).isNotEmpty() }
        tap(R.string.ui_exit_no)
        waitUntil { matches(R.string.ui_exit_title).isEmpty() && characterHeaderVisible() }
        assertTrue(tabsVisible())
        assertFalse(activity.isFinishing)

        // No leaves the library in place, so a second back still asks to exit.
        shell("input keyevent 4")
        waitUntil { matches(R.string.ui_exit_title).isNotEmpty() }
        tap(R.string.ui_exit_yes)
        waitUntil { activity.isFinishing || activity.isDestroyed }
    }

    private fun tabsVisible(): Boolean = listOf(R.string.nav_tab_chat, R.string.nav_tab_characters,
        R.string.nav_tab_world, R.string.nav_tab_extensions, R.string.nav_tab_settings)
        .all { matches(it).isNotEmpty() }

    private fun characterHeaderVisible(): Boolean = matches(R.string.chars_title).any { node ->
        val rect = Rect()
        node.getBoundsInScreen(rect)
        rect.centerY() < activity.resources.displayMetrics.heightPixels / 3
    }

    private fun matches(id: Int): List<AccessibilityNodeInfo> {
        val expected = activity.getString(id)
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<AccessibilityNodeInfo>()
        // Compose exposes virtual descendants. Traverse them directly because
        // provider-level findAccessibilityNodeInfosByText may return no matches.
        fun visit(node: AccessibilityNodeInfo) {
            if (node.text?.toString() == expected || node.contentDescription?.toString() == expected) {
                found += node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return found
    }

    private fun tap(id: Int) {
        waitUntil { matches(id).isNotEmpty() }
        // A page title may share its label with a bottom tab. Choose the bottom one.
        val node = matches(id).maxBy { val rect = Rect(); it.getBoundsInScreen(rect); rect.bottom }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        shell("input tap ${rect.centerX()} ${rect.centerY()}")
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Expected UI state did not appear", predicate())
    }
}
