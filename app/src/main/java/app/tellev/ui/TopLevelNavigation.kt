package app.tellev.ui

/** Leaf routes, rather than their containing graph routes, own the bottom bar. */
internal fun isTopLevelScreen(route: String?): Boolean = route in setOf(
    "chat", "characters/list", "world/list", "extensions", "settings",
)

internal enum class AppBackAction { None, Parent, ChatHome, CloseConversation, ConfirmExit }

/** The conversation and character picker share the chat route. */
internal fun appBackAction(route: String?, conversationOpen: Boolean): AppBackAction = when {
    route.isNullOrBlank() -> AppBackAction.None
    route == "chat" && conversationOpen -> AppBackAction.CloseConversation
    route == "chat" -> AppBackAction.ConfirmExit
    isTopLevelScreen(route) -> AppBackAction.ChatHome
    else -> AppBackAction.Parent
}
