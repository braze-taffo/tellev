package app.tellev.core.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ExtensionEventDispatchTest {
    @Test
    fun `ordinary runtime failure leaves later extensions reachable`() = runBlocking {
        val visited = mutableListOf<String>()
        val failures = mutableListOf<String>()
        dispatchExtensionEventToRuntimes(
            extensionIds = listOf("a", "b", "c"),
            excludeExtensionId = "b",
            dispatch = { id ->
                visited += id
                if (id == "a") throw IllegalStateException("broken extension")
            },
            onFailure = { id, _ -> failures += id },
        )
        assertEquals(listOf("a", "c"), visited)
        assertEquals(listOf("a"), failures)
    }

    @Test
    fun `cancelled dispatch stops before the next extension`() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            dispatchExtensionEventToRuntimes(
                extensionIds = listOf("a", "b"),
                excludeExtensionId = null,
                dispatch = { id ->
                    visited += id
                    throw CancellationException("generation stopped")
                },
                onFailure = { _, _ -> fail("cancellation must not be logged as an extension failure") },
            )
            fail("cancellation must reach the caller")
        } catch (_: CancellationException) {
            assertEquals(listOf("a"), visited)
        }
    }
}
