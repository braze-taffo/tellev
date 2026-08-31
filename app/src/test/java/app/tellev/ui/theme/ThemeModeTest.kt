package app.tellev.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `parses persisted enum names`() {
        assertEquals(ThemeMode.Light, parseThemeMode("Light"))
        assertEquals(ThemeMode.Dark, parseThemeMode("Dark"))
        assertEquals(ThemeMode.System, parseThemeMode("System"))
    }

    @Test
    fun `unknown or missing values fall back to System`() {
        assertEquals(ThemeMode.System, parseThemeMode("Midnight"))
        assertEquals(ThemeMode.System, parseThemeMode(""))
        assertEquals(ThemeMode.System, parseThemeMode(null))
    }

    @Test
    fun `isDarkTheme resolves each mode`() {
        assertFalse(ThemeMode.Light.isDarkTheme(systemInDark = true))
        assertTrue(ThemeMode.Dark.isDarkTheme(systemInDark = false))
        assertTrue(ThemeMode.System.isDarkTheme(systemInDark = true))
        assertFalse(ThemeMode.System.isDarkTheme(systemInDark = false))
    }
}
