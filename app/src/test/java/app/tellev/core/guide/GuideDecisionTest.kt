package app.tellev.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDecisionTest {

    private val installed = 1_700_000_000_000L
    private val updated = installed + 60_000L
    private val version = "1.7.0.1"

    @Test
    fun `a brand new install with no data gets the onboarding guide`() {
        assertEquals(
            StartupGuide.Onboarding,
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = "",
                onboardingShown = false,
                firstInstallTime = installed,
                lastUpdateTime = installed,
                hasUserData = { false },
            ),
        )
    }

    @Test
    fun `a reinstall that restored data gets the update guide, not the onboarding`() {
        assertEquals(
            StartupGuide.UpdateGuide,
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = "",
                onboardingShown = false,
                firstInstallTime = installed,
                lastUpdateTime = installed,
                hasUserData = { true },
            ),
        )
    }

    @Test
    fun `an upgrade over an older version gets the update guide`() {
        assertEquals(
            StartupGuide.UpdateGuide,
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = "",
                onboardingShown = false,
                firstInstallTime = installed,
                lastUpdateTime = updated,
                hasUserData = { true },
            ),
        )
    }

    @Test
    fun `the update guide is not repeated for a version already shown`() {
        assertNull(
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = version,
                onboardingShown = false,
                firstInstallTime = installed,
                lastUpdateTime = updated,
                hasUserData = { true },
            ),
        )
        // A guide shown for 1.7.0 does not cover 1.7.0.1 — the patch release is
        // strictly newer, so the guide opens once more.
        assertEquals(
            StartupGuide.UpdateGuide,
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = "1.7.0",
                onboardingShown = false,
                firstInstallTime = installed,
                lastUpdateTime = updated,
                hasUserData = { true },
            ),
        )
    }

    @Test
    fun `a stored version newer than the installed one does not reopen the guide`() {
        assertNull(
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = "1.8.0",
                onboardingShown = true,
                firstInstallTime = installed,
                lastUpdateTime = updated,
                hasUserData = { true },
            ),
        )
    }

    @Test
    fun `a brand new user who already saw the onboarding is not shown it twice`() {
        assertNull(
            decideStartupGuide(
                currentVersion = version,
                lastGuideVersion = version,
                onboardingShown = true,
                firstInstallTime = installed,
                lastUpdateTime = installed,
                hasUserData = { false },
            ),
        )
    }

    @Test
    fun `the data check is skipped unless a fresh install still needs onboarding`() {
        var probed = false
        decideStartupGuide(
            currentVersion = version,
            lastGuideVersion = "",
            onboardingShown = false,
            firstInstallTime = installed,
            lastUpdateTime = updated,
            hasUserData = { probed = true; false },
        )
        assertEquals(false, probed)
    }

    @Test
    fun `version comparison matches the update checker semantics`() {
        assertTrue(isGuideOlderThan("", version))
        assertTrue(isGuideOlderThan("1.6.6.1", version))
        assertTrue(isGuideOlderThan("1.7.0", "1.7.0.1"))
        assertFalse(isGuideOlderThan("1.7.0.1", "1.7.0.1"))
        assertFalse(isGuideOlderThan("1.7.0.1", "1.7.0.0"))
        assertFalse(isGuideOlderThan("v1.7.1", "1.7.0.1"))
    }
}
