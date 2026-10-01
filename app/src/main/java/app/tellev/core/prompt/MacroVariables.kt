package app.tellev.core.prompt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The variable scopes used by a single macro evaluation. */
interface MacroVariableAccess {
    fun getLocal(name: String): String?
    fun setLocal(name: String, value: String)
    fun addLocal(name: String, increment: String): String
    fun incLocal(name: String): String = addLocal(name, "1")
    fun decLocal(name: String): String = addLocal(name, "-1")
    fun deleteLocal(name: String)
    fun hasLocal(name: String): Boolean
    fun getGlobal(name: String): String?
    fun setGlobal(name: String, value: String)
    fun addGlobal(name: String, increment: String): String
    fun incGlobal(name: String): String = addGlobal(name, "1")
    fun decGlobal(name: String): String = addGlobal(name, "-1")
    fun deleteGlobal(name: String)
    fun hasGlobal(name: String): Boolean
}

/** Isolated LOCAL state; GLOBAL can be either a snapshot or the durable store. */
class SnapshotMacroVariables(
    local: JsonObject = JsonObject(emptyMap()),
    global: JsonObject = JsonObject(emptyMap()),
    private val globalAccess: MacroVariableAccess? = null,
    private val preserveExistingLocal: Boolean = false,
) : MacroVariableAccess {
    private val localValues = local.toMutableMap()
    private val globalValues = global.toMutableMap()
    fun localObject(): JsonObject = JsonObject(localValues.toMap())

    override fun getLocal(name: String): String? = stringValue(localValues[name])
    override fun setLocal(name: String, value: String) {
        if (name.isNotBlank() && !(preserveExistingLocal && name in localValues)) localValues[name] = JsonPrimitive(value)
    }
    override fun addLocal(name: String, increment: String): String = add(localValues, name, increment)
    override fun deleteLocal(name: String) { localValues.remove(name) }
    override fun hasLocal(name: String): Boolean = name in localValues
    override fun getGlobal(name: String): String? = if (globalAccess != null) globalAccess.getGlobal(name) else stringValue(globalValues[name])
    override fun setGlobal(name: String, value: String) {
        if (globalAccess != null) globalAccess.setGlobal(name, value)
        else if (name.isNotBlank()) globalValues[name] = JsonPrimitive(value)
    }
    override fun addGlobal(name: String, increment: String): String =
        globalAccess?.addGlobal(name, increment) ?: add(globalValues, name, increment)
    override fun deleteGlobal(name: String) { if (globalAccess != null) globalAccess.deleteGlobal(name) else globalValues.remove(name) }
    override fun hasGlobal(name: String): Boolean = globalAccess?.hasGlobal(name) ?: (name in globalValues)

    private fun add(values: MutableMap<String, JsonElement>, name: String, increment: String): String {
        val current = stringValue(values[name]) ?: "0"
        val a = current.toDoubleOrNull()
        val b = increment.toDoubleOrNull()
        val longA = current.toLongOrNull()
        val longB = increment.toLongOrNull()
        val result = if (longA != null && longB != null) (longA + longB).toString()
        else if (a != null && b != null) {
            val sum = a + b
            if (sum.isFinite() && sum == kotlin.math.floor(sum)) sum.toLong().toString() else sum.toString()
        } else current + increment
        values[name] = JsonPrimitive(result)
        return result
    }

    private fun stringValue(value: JsonElement?): String? = when (value) {
        null -> null
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
