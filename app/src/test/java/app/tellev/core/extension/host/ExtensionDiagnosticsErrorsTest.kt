package app.tellev.core.extension.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionDiagnosticsErrorsTest {

    @Test
    fun `errors dedupe by extension message and first stack frame`() {
        val diagnostics = ExtensionDiagnostics()
        val stack = "Error: boom\n    at foo (script.js:1)\n    at bar (script.js:2)"
        diagnostics.rememberExtensionError("ext", "Error: boom", stack)
        diagnostics.rememberExtensionError("ext", "Error: boom", stack)
        diagnostics.rememberExtensionError("ext", "Error: boom", stack)

        val errors = diagnostics.snapshotExtensionErrors()
        assertEquals(1, errors.size)
        assertEquals(3, errors.single().occurrences)
    }

    @Test
    fun `same message with a different stack stays distinct`() {
        val diagnostics = ExtensionDiagnostics()
        diagnostics.rememberExtensionError("ext", "Error: boom", "at a (1.js:1)")
        diagnostics.rememberExtensionError("ext", "Error: boom", "at b (2.js:1)")
        assertEquals(2, diagnostics.snapshotExtensionErrors().size)
    }

    @Test
    fun `ring is capped and clear removes one extension only`() {
        val diagnostics = ExtensionDiagnostics()
        repeat(40) { index -> diagnostics.rememberExtensionError("ext-$index", "e$index", null) }
        assertEquals(40, diagnostics.snapshotExtensionErrors().size)
        // Beyond 50 distinct records the oldest are evicted.
        repeat(20) { index -> diagnostics.rememberExtensionError("more-$index", "m$index", null) }
        assertEquals(50, diagnostics.snapshotExtensionErrors().size)
        assertTrue(diagnostics.snapshotExtensionErrors().none { it.extensionId == "ext-0" })

        diagnostics.clearExtensionErrors("ext-15")
        assertTrue(diagnostics.snapshotExtensionErrors().none { it.extensionId == "ext-15" })
        assertEquals(49, diagnostics.snapshotExtensionErrors().size)

        diagnostics.clearExtensionErrors(null)
        assertTrue(diagnostics.snapshotExtensionErrors().isEmpty())
    }
}
