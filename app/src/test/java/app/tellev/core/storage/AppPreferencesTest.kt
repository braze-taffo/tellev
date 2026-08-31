package app.tellev.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun `one-shot notices appear once after an app update`() {
        assertTrue(
            shouldShowOnceAfterUpdate(
                alreadyHandled = false,
                firstInstallTime = 1_000L,
                lastUpdateTime = 2_000L,
            ),
        )
        assertFalse(
            shouldShowOnceAfterUpdate(
                alreadyHandled = true,
                firstInstallTime = 1_000L,
                lastUpdateTime = 2_000L,
            ),
        )
    }

    @Test
    fun `one-shot notices stay hidden on a clean install`() {
        assertFalse(
            shouldShowOnceAfterUpdate(
                alreadyHandled = false,
                firstInstallTime = 1_000L,
                lastUpdateTime = 1_000L,
            ),
        )
    }
}
