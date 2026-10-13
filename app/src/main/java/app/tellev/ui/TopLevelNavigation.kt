package app.tellev.ui

/**
 * dsh 外壳的顶层路由：底栏四 tab（工坊/类脑/角色卡/设置）。
 * world/list、extensions 等由 tab 推入的二级页没有底栏，也不算顶层；
 * 聊天页同样不在内——它由角色卡点入、自带抽屉与返回语义，露出底栏会让
 * 「进入聊天后底部导航仍然存在」，tab 还能把会话页盖掉。
 * upstream TellevRoot 的五 tab 契约已随 DshRoot 重写失效，两套谓词分叉是
 * 「返回语义读错集合」的根因，故这里只保留一份。
 */
internal fun isDshTopLevel(route: String?): Boolean = route in setOf(
    "characters/list", "creation/home", "community", "settings",
)

internal enum class AppBackAction { None, Parent, ChatHome, CloseConversation, ConfirmExit }

/**
 * 返回语义：二级页返回上一级；首页 tab 返回聊天首页；聊天页有会话先关会话，
 * 空会话才确认退出。
 */
internal fun appBackAction(route: String?, conversationOpen: Boolean): AppBackAction = when {
    route.isNullOrBlank() -> AppBackAction.None
    route == "chat" && conversationOpen -> AppBackAction.CloseConversation
    route == "chat" -> AppBackAction.ConfirmExit
    isDshTopLevel(route) -> AppBackAction.ChatHome
    else -> AppBackAction.Parent
}
