package app.tellev.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun `preset limit notice appears once after an app update`() {
        assertTrue(
            shouldShowPresetLimitUpgradeNotice(
                alreadyHandled = false,
                firstInstallTime = 1_000L,
                lastUpdateTime = 2_000L,
            ),
        )
        assertFalse(
            shouldShowPresetLimitUpgradeNotice(
                alreadyHandled = true,
                firstInstallTime = 1_000L,
                lastUpdateTime = 2_000L,
            ),
        )
    }

    @Test
    fun `preset limit notice stays hidden on a clean install`() {
        assertFalse(
            shouldShowPresetLimitUpgradeNotice(
                alreadyHandled = false,
                firstInstallTime = 1_000L,
                lastUpdateTime = 1_000L,
            ),
        )
    }
}
