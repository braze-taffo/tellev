package app.tellev.ui

/** Leaf routes, rather than their containing graph routes, own the bottom bar. */
internal fun isTopLevelScreen(route: String?): Boolean = route in setOf(
    "chat", "characters/list", "world/list", "extensions", "settings",
)

/** Every app page confirms system back; toolbar buttons retain page navigation. */
internal fun shouldConfirmAppExit(route: String?): Boolean = !route.isNullOrBlank()
