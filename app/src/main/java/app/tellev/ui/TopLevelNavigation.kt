package app.tellev.ui

/** Leaf routes, rather than their containing graph routes, own the bottom bar. */
internal fun isTopLevelScreen(route: String?): Boolean = route in setOf(
    "chat", "characters/list", "world/list", "extensions", "settings",
)

/**
 * Only the five root tabs can lose nothing by being exited, so only they
 * confirm app exit. Sub-pages (character detail, world book entry editor,
 * settings sub-screens, creation, ...) fall back to normal NavHost pop so
 * system back keeps its navigate-up meaning there.
 */
internal fun shouldConfirmAppExit(route: String?): Boolean = isTopLevelScreen(route)
