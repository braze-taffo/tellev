package app.tellev.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelNavigationTest {
    @Test
    fun `dsh tabs retain the bottom navigation`() {
        listOf("characters/list", "creation/home", "community", "settings")
            .forEach { assertTrue(isDshTopLevel(it)) }
    }

    @Test
    fun `chat and pushed pages have no bottom bar`() {
        listOf(
            "chat",
            "world/list", "world/book/lore", "world/book/lore/entry/1", "extensions",
            "characters/create", "characters/detail/alice",
            "settings/usage",
            "creation/chat?kind={kind}", "creation/editor",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "future/page", null,
        ).forEach { assertFalse(isDshTopLevel(it)) }
    }

    @Test
    fun `chat back closes the conversation, an empty chat confirms exit`() {
        assertEquals(AppBackAction.CloseConversation, appBackAction("chat", true))
        assertEquals(AppBackAction.ConfirmExit, appBackAction("chat", false))
    }

    @Test
    fun `dsh home tabs return to the chat home even with a retained conversation`() {
        listOf("characters/list", "creation/home", "community", "settings").forEach { route ->
            for (open in listOf(false, true)) {
                assertEquals(route, AppBackAction.ChatHome, appBackAction(route, open))
            }
        }
    }

    @Test
    fun `secondary pages return to their parent`() {
        listOf(
            "world/list", "world/book/lore", "world/book/lore/entry/1", "extensions",
            "characters/create", "characters/detail/alice",
            "settings/usage",
            "creation/chat?kind={kind}", "creation/editor",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "future/page",
        ).forEach { assertEquals(it, AppBackAction.Parent, appBackAction(it, true)) }
        listOf(null, "", " ").forEach { assertEquals(AppBackAction.None, appBackAction(it, false)) }
    }
}
