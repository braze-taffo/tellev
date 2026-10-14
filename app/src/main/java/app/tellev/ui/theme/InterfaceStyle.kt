package app.tellev.ui.theme

enum class InterfaceStyle { Classic, Dsh }

fun parseInterfaceStyle(name: String?): InterfaceStyle =
    InterfaceStyle.entries.firstOrNull { it.name == name } ?: InterfaceStyle.Classic
