package app.tellev.ui

/** Leaf routes, rather than their containing graph routes, own the bottom bar. */
internal fun isTopLevelScreen(route: String?): Boolean = route in setOf(
    "characters/list", "creation/home", "community", "settings",
)

/**
 * Only the four root tabs can lose nothing by being exited, so only they
 * confirm app exit. Sub-pages (chat, world book list/editors, character detail,
 * settings sub-screens, creation editor, ...) fall back to normal NavHost pop so
 * system back keeps its navigate-up meaning there.
 */
internal fun shouldConfirmAppExit(route: String?): Boolean = isTopLevelScreen(route)
