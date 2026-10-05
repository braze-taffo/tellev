package app.tellev.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun `only the chat home confirms exit`() {
        assertEquals(AppBackAction.ConfirmExit, appBackAction("chat", false))
        assertEquals(AppBackAction.CloseConversation, appBackAction("chat", true))
    }

    @Test
    fun `other main pages return to chat home even with a retained conversation`() {
        listOf("characters/list", "world/list", "extensions", "settings").forEach { route ->
            for (open in listOf(false, true)) assertEquals(route, AppBackAction.ChatHome, appBackAction(route, open))
        }
    }

    @Test
    fun `nested pages return to their parent`() {
        listOf("characters/detail/alice", "characters/create",
            "world/book/lore", "world/book/lore/entry/1",
            "settings/providers", "settings/imagegen", "creation/home",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "creation/editor", "future/page")
            .forEach { assertEquals(it, AppBackAction.Parent, appBackAction(it, true)) }
        listOf(null, "", " ").forEach { assertEquals(AppBackAction.None, appBackAction(it, false)) }
    }
}
