package app.tellev.ui

/** Leaf routes, rather than their containing graph routes, own the bottom bar. */
internal fun isTopLevelScreen(route: String?): Boolean = route in setOf(
    "chat", "characters/list", "world/list", "extensions", "settings",
)

/** Returning within an editor or a conversation must keep its existing behavior. */
internal fun shouldConfirmAppExit(route: String?): Boolean = route == "characters/list"
