package app.tellev.core.prompt

import android.os.Bundle
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicitly invoked UI input helper; skipped during the compatibility test suite. */
class DeviceTextInputTest {
    @Test fun setFocusedText() {
        val encoded = InstrumentationRegistry.getArguments().getString("inputBase64")
        assumeTrue(encoded != null)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        android.os.SystemClock.sleep(1000)
        val root = automation.rootInActiveWindow ?: automation.windows.firstNotNullOf { it.root }
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className == "android.widget.EditText" && node.isFocused) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        val node = find(root)
        require(node != null) { "No focused editable field in ${root.packageName}" }
        val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }
}
