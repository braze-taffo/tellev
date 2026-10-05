package app.tellev.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {
    @Test
    fun `character library leaf retains the bottom navigation`() {
        assertTrue(isTopLevelScreen("characters/list"))
    }

    @Test
    fun `workshop, community and settings tabs keep their navigation`() {
        listOf("creation/home", "community", "settings").forEach {
            assertTrue(isTopLevelScreen(it))
        }
    }

    @Test
    fun `chat, world books and detail editors do not expose the bottom navigation`() {
        listOf("chat", "world/list", "characters/detail/alice", "characters/create",
            "world/book/lore", "world/book/lore/entry/1", "extensions",
            "settings/providers", "settings/imagegen", "creation/editor", null)
            .forEach { assertFalse(isTopLevelScreen(it)) }
    }

    @Test
    fun `only the four root tabs confirm system back`() {
        listOf("characters/list", "creation/home", "community", "settings")
            .forEach { assertTrue(it, shouldConfirmAppExit(it)) }
        // Nested pages keep normal navigate-up semantics: a "quit app?" dialog
        // three levels deep both trapped navigation and could discard unsaved
        // editor state, so they must NOT confirm.
        listOf("chat", "world/list", "world/book/lore", "world/book/lore/entry/1",
            "characters/detail/alice", "characters/create",
            "settings/providers", "settings/imagegen", "extensions",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "creation/editor", "future/page")
            .forEach { assertFalse(it, shouldConfirmAppExit(it)) }
        assertFalse(shouldConfirmAppExit(null))
        assertFalse(shouldConfirmAppExit(""))
        assertFalse(shouldConfirmAppExit(" "))
    }
}
