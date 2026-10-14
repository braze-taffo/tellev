package app.tellev.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.MainActivity
import app.tellev.R
import app.tellev.core.guide.GuideFormat
import org.junit.Assert.*
import org.junit.Test

/** Real bundled assets and upgrade startup queue, in both interface styles. */
class UpdateGuideAndroidTest {
    @Test
    fun upgradeGuidePagesLoadInFourLanguagesAndOnlyAppearOnce() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        check(context.packageName.endsWith(".mvuvalidation"))
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        assertTrue(version.isNotBlank())
        val prefs = context.getSharedPreferences("tellev_prefs", 0)
        var activity: Activity? = null
        fun launch(): Activity = i.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            for ((language, style) in listOf("zh-CN" to "Classic", "en" to "Dsh", "ja" to "Classic", "ko" to "Dsh")) {
                prefs.edit().putBoolean("auto_update_check", false)
                    .putBoolean("onboarding_guide_shown", true).putBoolean("qq_group_notice_handled", true)
                    .putBoolean("preset_limits_1_5_1_notice_handled", true)
                    .putString("update_guide_shown_version", "0.0.0")
                    .putString("language_tag", language).putString("interface_style", style)
                    .putString("theme_mode", "Light").commit()
                activity = launch()
                val host = requireNotNull(activity)
                val document = context.assets.open("guide/update/$language.md").bufferedReader().use {
                    GuideFormat.parse(it.readText())
                }
                assertTrue(document.pages.isNotEmpty())
                for ((index, page) in document.pages.withIndex()) {
                    waitUntil { matches(page.title).isNotEmpty() }
                    val indicator = host.getString(R.string.guide_page_indicator, index + 1, document.pages.size)
                    waitUntil { matches(indicator).isNotEmpty() }
                    assertTrue(matches(host.getString(R.string.guide_load_failed)).isEmpty())
                    i.uiAutomation.takeScreenshot()?.let { image ->
                        java.io.File(context.filesDir, "guide-$language-${index + 1}.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                    }
                    val button = host.getString(if (index == document.pages.lastIndex) R.string.nav_got_it else R.string.guide_action_next)
                    tap(button)
                }
                waitUntil { prefs.getString("update_guide_shown_version", "") == version }
                i.runOnMainSync { host.finish() }
                i.waitForIdleSync()
                activity = launch()
                val restarted = requireNotNull(activity)
                waitUntil { matches(restarted.getString(R.string.nav_tab_settings)).isNotEmpty() }
                assertTrue(matches(document.pages.first().title).isEmpty())
                assertTrue(matches(restarted.getString(R.string.guide_title_update)).isEmpty())
                i.runOnMainSync { restarted.finish() }
                i.waitForIdleSync()
                activity = null
            }
        } finally {
            activity?.let { host -> i.runOnMainSync { host.finish() } }
            prefs.edit().putString("language_tag", "en").putString("interface_style", "Classic")
                .putString("theme_mode", "System").putString("update_guide_shown_version", version).commit()
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun matches(label: String) = nodes().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun tap(label: String) {
        waitUntil { matches(label).isNotEmpty() }
        val node = matches(label).last()
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val rect = Rect().also(node::getBoundsInScreen)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}"),
            ).use { it.readBytes() }
        }
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("Expected update guide state did not appear", predicate())
    }
}
