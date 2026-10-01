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
    fun `every main page and nested page confirms system back`() {
        listOf("chat", "characters/list", "characters/detail/alice", "characters/create",
            "world/list", "world/book/lore", "world/book/lore/entry/1", "extensions",
            "settings", "settings/providers", "settings/imagegen", "creation/home",
            "creation/edit/character/alice", "creation/edit/world/lore",
            "creation/from-character/world/alice", "creation/editor", "future/page")
            .forEach { assertTrue(it, shouldConfirmAppExit(it)) }
        listOf(null, "", " ").forEach { assertFalse(shouldConfirmAppExit(it)) }
    }
}
