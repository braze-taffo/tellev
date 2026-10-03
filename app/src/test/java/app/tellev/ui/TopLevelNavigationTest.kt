package app.tellev.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {
    @Test
    fun `character and world library leaves retain the bottom navigation`() {
        assertTrue(isTopLevelScreen("characters/list"))
        assertTrue(isTopLevelScreen("world/list"))
    }

    @Test
    fun `other tabs keep their navigation`() {
        listOf("chat", "extensions", "settings").forEach { assertTrue(isTopLevelScreen(it)) }
    }

    @Test
    fun `editors and creation flows do not expose the bottom navigation`() {
        listOf("characters/detail/alice", "characters/create", "world/book/lore",
            "world/book/lore/entry/1", "settings/providers", "settings/imagegen",
            "creation/home", "creation/editor", null).forEach { assertFalse(isTopLevelScreen(it)) }
    }

    @Test
    fun `only the five root tabs confirm system back`() {
        listOf("chat", "characters/list", "world/list", "extensions", "settings")
            .forEach { assertTrue(it, shouldConfirmAppExit(it)) }
        // Nested pages keep normal navigate-up semantics: a "quit app?" dialog
        // three levels deep both trapped navigation and could discard unsaved
        // editor state, so they must NOT confirm.
        listOf("characters/detail/alice", "characters/create",
            "world/book/lore", "world/book/lore/entry/1",
            "settings/providers", "settings/imagegen", "creation/home",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "creation/editor", "future/page")
            .forEach { assertFalse(it, shouldConfirmAppExit(it)) }
        assertFalse(shouldConfirmAppExit(null))
        assertFalse(shouldConfirmAppExit(""))
        assertFalse(shouldConfirmAppExit(" "))
    }
}
